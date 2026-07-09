package com.pucminas.gradeinteligente.service;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;
import com.pucminas.gradeinteligente.domain.Curriculo;
import com.pucminas.gradeinteligente.domain.Disciplina;
import com.pucminas.gradeinteligente.domain.Turma;
import com.pucminas.gradeinteligente.dto.DisciplinaPlanejadaDTO;
import com.pucminas.gradeinteligente.dto.PeriodoDTO;
import com.pucminas.gradeinteligente.dto.PlanoRequest;
import com.pucminas.gradeinteligente.dto.PlanoResponse;
import com.pucminas.gradeinteligente.repository.CurriculoRepository;
import com.pucminas.gradeinteligente.repository.OfertaRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Calcula a rota ótima de disciplinas (menor número de períodos) usando
 * programação por restrições (CP-SAT do OR-Tools).
 *
 * <p>Modelo:
 * <ul>
 *   <li>Variável inteira {@code termo[c]} ∈ [1, T] = período em que a disciplina c será cursada.</li>
 *   <li>Pré-requisito p de c ⇒ {@code termo[p] < termo[c]} (ou p já concluída = termo 0).</li>
 *   <li>Co-requisito x de c ⇒ {@code termo[x] <= termo[c]} (mesmo período ou antes).</li>
 *   <li>Capacidade: em cada período, no máximo {@code maxDisciplinas} disciplinas.</li>
 *   <li>Carga horária mínima (ex.: 1.800h): só libera após acumular a CH exigida.</li>
 *   <li>Turmas/horários: para o 1º período (próximo semestre), o solver escolhe uma turma
 *       de cada disciplina de forma que não haja choque de horário.</li>
 *   <li>Objetivo lexicográfico: minimizar o número de períodos e, como desempate,
 *       antecipar os gargalos (maior prioridade no grafo).</li>
 * </ul>
 */
@Service
public class PlanejadorService {

    private static final double LIMITE_TEMPO_SEGUNDOS = 15.0;

    private final CurriculoRepository repository;
    private final OfertaRepository ofertaRepository;
    private final GrafoService grafoService;

    public PlanejadorService(CurriculoRepository repository,
                             OfertaRepository ofertaRepository,
                             GrafoService grafoService) {
        this.repository = repository;
        this.ofertaRepository = ofertaRepository;
        this.grafoService = grafoService;
    }

    public PlanoResponse planejar(PlanoRequest request) {
        Curriculo curriculo = repository.getCurriculo();
        Map<String, Disciplina> porCodigo = curriculo.indexadoPorCodigo();

        Set<String> concluidas = new HashSet<>(request.concluidas());
        List<String> avisos = new ArrayList<>();
        for (String c : concluidas) {
            if (!porCodigo.containsKey(c)) {
                avisos.add("Código concluído não reconhecido e ignorado: " + c);
            }
        }

        int maxDisciplinas = request.maxDisciplinasOrDefault();
        boolean incluirOptativas = request.incluirOptativasOrDefault();

        long chConcluida = porCodigo.values().stream()
                .filter(d -> concluidas.contains(d.codigo()))
                .mapToLong(Disciplina::cargaHoraria)
                .sum();

        List<Disciplina> pendentes = new ArrayList<>();
        for (Disciplina d : curriculo.disciplinas()) {
            if (concluidas.contains(d.codigo())) continue;
            if (d.optativa() && !incluirOptativas) continue;
            pendentes.add(d);
        }

        int chRestante = pendentes.stream().mapToInt(Disciplina::cargaHoraria).sum();

        if (pendentes.isEmpty()) {
            return new PlanoResponse("FORMADO", 0, 0, 0, true, List.of(),
                    List.of("Todas as disciplinas já foram concluídas. Parabéns!"));
        }

        int horizonte = pendentes.size();

        CpModel model = new CpModel();
        Map<String, IntVar> termo = new LinkedHashMap<>();
        for (Disciplina d : pendentes) {
            termo.put(d.codigo(), model.newIntVar(1, horizonte, "termo_" + d.codigo()));
        }

        for (Disciplina d : pendentes) {
            IntVar tc = termo.get(d.codigo());

            for (String pre : d.preRequisitos()) {
                if (concluidas.contains(pre)) continue;
                IntVar tp = termo.get(pre);
                if (tp == null) {
                    avisos.add("Pré-requisito " + pre + " de " + d.codigo()
                            + " não está no plano; considerado pendente externo.");
                    continue;
                }
                model.addLessOrEqual(tp, LinearExpr.affine(tc, 1, -1));
            }

            for (String co : d.coRequisitos()) {
                if (concluidas.contains(co)) continue;
                IntVar tco = termo.get(co);
                if (tco == null) continue;
                model.addLessOrEqual(tco, tc);
            }

            if (d.cargaHorariaMinima() > 0) {
                aplicarCargaHorariaMinima(model, d, tc, termo, porCodigo, chConcluida);
            }
        }

        aplicarCapacidade(model, termo, horizonte, maxDisciplinas);

        // Turmas/horários: escolha de turma sem conflito no 1º período (próximo semestre).
        Map<String, List<Turma>> turmasDaDisc = new LinkedHashMap<>();
        Map<String, Literal[]> selecaoTurma = new HashMap<>();
        if (request.considerarHorariosOrDefault() && ofertaRepository.disponivel()) {
            List<String> aproximados = new ArrayList<>();
            for (Disciplina d : pendentes) {
                OfertaRepository.Casamento m = ofertaRepository.casar(d.nome());
                if (m == null || m.turmas().isEmpty()) continue;
                turmasDaDisc.put(d.codigo(), m.turmas());
                if (!m.exato()) {
                    aproximados.add(String.format("%s ≈ \"%s\" (%.0f%%)",
                            d.nome(), m.nomeOferta(), m.score() * 100));
                }
            }
            if (!aproximados.isEmpty()) {
                avisos.add("Casamento aproximado de nome (confira): " + String.join("; ", aproximados));
            }
            if (turmasDaDisc.isEmpty()) {
                avisos.add("Nenhuma disciplina pendente casou com a oferta de turmas do semestre.");
            } else {
                Map<String, Literal> emPrimeiroPeriodo = new HashMap<>();
                selecaoTurma = criarSelecaoDeTurmas(model, termo, turmasDaDisc, emPrimeiroPeriodo);
                int pares = aplicarConflitoPrimeiroPeriodo(model, turmasDaDisc, selecaoTurma, emPrimeiroPeriodo);
                avisos.add(String.format("Oferta %s: %d disciplinas com turmas; %d par(es) de choque tratados no 1º período.",
                        ofertaRepository.semestre(), turmasDaDisc.size(), pares));
            }
        }

        // Objetivo lexicográfico: 1) menos períodos; 2) gargalos mais cedo.
        IntVar makespan = model.newIntVar(1, horizonte, "makespan");
        model.addMaxEquality(makespan, termo.values().toArray(new IntVar[0]));

        long pesoSecundarioMax = 0;
        for (Disciplina d : pendentes) {
            pesoSecundarioMax += (long) grafoService.prioridade(d.codigo()) * horizonte;
        }
        long pesoPrimario = pesoSecundarioMax + 1;

        LinearExprBuilder objetivo = LinearExpr.newBuilder();
        objetivo.addTerm(makespan, pesoPrimario);
        for (Disciplina d : pendentes) {
            objetivo.addTerm(termo.get(d.codigo()), grafoService.prioridade(d.codigo()));
        }
        model.minimize(objetivo);

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(LIMITE_TEMPO_SEGUNDOS);
        CpSolverStatus status = solver.solve(model);

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            return new PlanoResponse("INVIAVEL", 0, pendentes.size(), chRestante, false,
                    List.of(), List.of("Não foi possível montar um plano viável. "
                    + "Verifique as disciplinas concluídas e a capacidade por período."));
        }

        boolean otimo = status == CpSolverStatus.OPTIMAL;
        if (!otimo) {
            avisos.add("Solução viável encontrada dentro do limite de tempo, "
                    + "mas a otimalidade não foi comprovada.");
        }

        Map<String, Turma> turmaEscolhida = extrairTurmasEscolhidas(solver, termo, turmasDaDisc, selecaoTurma);
        return montarResposta(solver, termo, pendentes, chRestante, otimo, avisos, turmaEscolhida);
    }

    private Map<String, Literal[]> criarSelecaoDeTurmas(CpModel model, Map<String, IntVar> termo,
                                                        Map<String, List<Turma>> turmasDaDisc,
                                                        Map<String, Literal> emPrimeiroPeriodo) {
        Map<String, Literal[]> selecao = new HashMap<>();
        for (Map.Entry<String, List<Turma>> e : turmasDaDisc.entrySet()) {
            String cod = e.getKey();
            int n = e.getValue().size();
            Literal[] sel = new Literal[n];
            for (int i = 0; i < n; i++) {
                sel[i] = model.newBoolVar("turma_" + cod + "_" + i);
            }
            model.addExactlyOne(sel);
            selecao.put(cod, sel);

            Literal emT1 = model.newBoolVar("emT1_" + cod);
            model.addEquality(termo.get(cod), 1).onlyEnforceIf(emT1);
            model.addDifferent(termo.get(cod), 1).onlyEnforceIf(emT1.not());
            emPrimeiroPeriodo.put(cod, emT1);
        }
        return selecao;
    }

    private int aplicarConflitoPrimeiroPeriodo(CpModel model, Map<String, List<Turma>> turmasDaDisc,
                                               Map<String, Literal[]> selecao,
                                               Map<String, Literal> emPrimeiroPeriodo) {
        List<String> codigos = new ArrayList<>(turmasDaDisc.keySet());
        int paresComConflito = 0;
        for (int x = 0; x < codigos.size(); x++) {
            for (int y = x + 1; y < codigos.size(); y++) {
                String c1 = codigos.get(x);
                String c2 = codigos.get(y);
                List<Turma> t1 = turmasDaDisc.get(c1);
                List<Turma> t2 = turmasDaDisc.get(c2);
                boolean houveConflito = false;
                for (int i = 0; i < t1.size(); i++) {
                    for (int j = 0; j < t2.size(); j++) {
                        if (t1.get(i).conflitaCom(t2.get(j))) {
                            houveConflito = true;
                            // Se ambas caírem no 1º período, não podem usar essas turmas juntas.
                            model.addBoolOr(new Literal[]{
                                    emPrimeiroPeriodo.get(c1).not(),
                                    emPrimeiroPeriodo.get(c2).not(),
                                    selecao.get(c1)[i].not(),
                                    selecao.get(c2)[j].not()
                            });
                        }
                    }
                }
                if (houveConflito) paresComConflito++;
            }
        }
        return paresComConflito;
    }

    private Map<String, Turma> extrairTurmasEscolhidas(CpSolver solver, Map<String, IntVar> termo,
                                                       Map<String, List<Turma>> turmasDaDisc,
                                                       Map<String, Literal[]> selecao) {
        Map<String, Turma> escolhida = new HashMap<>();
        for (Map.Entry<String, Literal[]> e : selecao.entrySet()) {
            String cod = e.getKey();
            if (solver.value(termo.get(cod)) != 1) continue; // só o próximo semestre
            Literal[] sel = e.getValue();
            for (int i = 0; i < sel.length; i++) {
                if (solver.booleanValue(sel[i])) {
                    escolhida.put(cod, turmasDaDisc.get(cod).get(i));
                    break;
                }
            }
        }
        return escolhida;
    }

    private void aplicarCargaHorariaMinima(CpModel model, Disciplina alvo, IntVar tAlvo,
                                           Map<String, IntVar> termo,
                                           Map<String, Disciplina> porCodigo, long chConcluida) {
        LinearExprBuilder acumulado = LinearExpr.newBuilder();
        acumulado.add(chConcluida);
        for (Map.Entry<String, IntVar> e : termo.entrySet()) {
            if (e.getKey().equals(alvo.codigo())) continue;
            Disciplina outra = porCodigo.get(e.getKey());
            Literal antes = model.newBoolVar("antes_" + e.getKey() + "_" + alvo.codigo());
            model.addLessOrEqual(e.getValue(), LinearExpr.affine(tAlvo, 1, -1)).onlyEnforceIf(antes);
            model.addGreaterOrEqual(e.getValue(), tAlvo).onlyEnforceIf(antes.not());
            acumulado.addTerm(antes, outra.cargaHoraria());
        }
        model.addGreaterOrEqual(acumulado, alvo.cargaHorariaMinima());
    }

    private void aplicarCapacidade(CpModel model, Map<String, IntVar> termo,
                                   int horizonte, int maxDisciplinas) {
        for (int t = 1; t <= horizonte; t++) {
            List<Literal> noTermo = new ArrayList<>();
            for (Map.Entry<String, IntVar> e : termo.entrySet()) {
                Literal b = model.newBoolVar("ehT_" + e.getKey() + "_" + t);
                model.addEquality(e.getValue(), t).onlyEnforceIf(b);
                model.addDifferent(e.getValue(), t).onlyEnforceIf(b.not());
                noTermo.add(b);
            }
            model.addLessOrEqual(LinearExpr.sum(noTermo.toArray(new Literal[0])), maxDisciplinas);
        }
    }

    private PlanoResponse montarResposta(CpSolver solver, Map<String, IntVar> termo,
                                         List<Disciplina> pendentes, int chRestante,
                                         boolean otimo, List<String> avisos,
                                         Map<String, Turma> turmaEscolhida) {
        Map<Integer, List<Disciplina>> porPeriodo = new TreeMap<>();
        for (Disciplina d : pendentes) {
            int t = (int) solver.value(termo.get(d.codigo()));
            porPeriodo.computeIfAbsent(t, k -> new ArrayList<>()).add(d);
        }

        List<PeriodoDTO> periodos = new ArrayList<>();
        int numeroSequencial = 0;
        for (Map.Entry<Integer, List<Disciplina>> entry : porPeriodo.entrySet()) {
            numeroSequencial++;
            List<Disciplina> lista = entry.getValue();
            lista.sort(Comparator.comparingInt((Disciplina d) -> grafoService.prioridade(d.codigo()))
                    .reversed());

            List<DisciplinaPlanejadaDTO> dtos = new ArrayList<>();
            int chTotal = 0;
            for (Disciplina d : lista) {
                chTotal += d.cargaHoraria();
                Turma turma = turmaEscolhida.get(d.codigo());
                dtos.add(new DisciplinaPlanejadaDTO(
                        d.codigo(), d.nome(), d.cargaHoraria(), d.optativa(), d.semipresencial(),
                        grafoService.prioridade(d.codigo()),
                        grafoService.getDescendentes(d.codigo()),
                        montarMotivo(d),
                        turma != null ? turma.codigo() : null,
                        turma != null ? turma.horarios() : null));
            }
            periodos.add(new PeriodoDTO(numeroSequencial, lista.size(), chTotal, dtos));
        }

        return new PlanoResponse("OK", periodos.size(), pendentes.size(), chRestante, otimo,
                periodos, avisos);
    }

    private String montarMotivo(Disciplina d) {
        int destrava = grafoService.getDescendentes(d.codigo());
        int profundidade = grafoService.getProfundidadeCadeia(d.codigo());
        if (d.optativa()) {
            return "Optativa (sem pré-requisito) — encaixada para completar carga horária.";
        }
        if (destrava == 0) {
            return "Não é pré-requisito de outras disciplinas; encaixada quando há espaço.";
        }
        return "Gargalo: destrava %d disciplina(s), com cadeia de profundidade %d."
                .formatted(destrava, profundidade);
    }
}
