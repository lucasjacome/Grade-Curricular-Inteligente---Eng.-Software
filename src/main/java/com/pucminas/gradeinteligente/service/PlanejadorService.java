package com.pucminas.gradeinteligente.service;

import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;
import com.pucminas.gradeinteligente.domain.Custos;
import com.pucminas.gradeinteligente.domain.Curriculo;
import com.pucminas.gradeinteligente.domain.Disciplina;
import com.pucminas.gradeinteligente.domain.Turma;
import com.pucminas.gradeinteligente.dto.AlternativaDTO;
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
 *   <li>Co-requisito x de c ⇒ mesmo período (os dois pendentes entram juntos,
 *       como na matrícula real). Se x já foi concluído, a restrição some.</li>
 *   <li>Capacidade: em cada período, no máximo {@code maxDisciplinas} disciplinas.</li>
 *   <li>Orçamento mensal (opcional): em cada período, a soma das mensalidades estimadas
 *       das disciplinas não pode ultrapassar o teto informado.</li>
 *   <li>Carga horária mínima (ex.: 1.800h): só libera após acumular a CH exigida.</li>
 *   <li>Turmas/horários: só o 1º período (próximo semestre) precisa caber na oferta
 *       sem choque — senão a matéria vai para o período seguinte. Os demais períodos
 *       não usam a oferta atual.</li>
 *   <li>Objetivo lexicográfico: 1) minimizar o número de períodos; 2) antecipar
 *       todas as disciplinas (peso extra nos gargalos).</li>
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
        String codigoCurriculo = request.codigoCurriculo();
        Curriculo curriculo = repository.getCurriculo(codigoCurriculo);
        Map<String, Disciplina> porCodigo = curriculo.indexadoPorCodigo();

        Set<String> concluidas = new HashSet<>(request.concluidas());
        Set<String> excluidas = new HashSet<>(request.excluidas());
        List<String> avisos = new ArrayList<>();
        for (String c : concluidas) {
            if (!porCodigo.containsKey(c)) {
                avisos.add("Código concluído não reconhecido e ignorado: " + c);
            }
        }
        registrarExcluidas(excluidas, porCodigo, avisos);
        Set<String> bloqueadas = expandirExcluidas(excluidas, curriculo.disciplinas(), concluidas, porCodigo, avisos);

        int maxDisciplinas = request.maxDisciplinasOrDefault();
        boolean incluirOptativas = request.incluirOptativasOrDefault();

        long chConcluida = porCodigo.values().stream()
                .filter(d -> concluidas.contains(d.codigo()))
                .mapToLong(Disciplina::cargaHoraria)
                .sum();

        List<Disciplina> pendentes = new ArrayList<>();
        int totalRestantes = 0;
        int chRestante = 0;
        for (Disciplina d : curriculo.disciplinas()) {
            if (concluidas.contains(d.codigo())) continue;
            if (d.optativa() && !incluirOptativas) continue;
            totalRestantes++;
            chRestante += d.cargaHoraria();
            if (bloqueadas.contains(d.codigo())) continue;
            pendentes.add(d);
        }

        if (totalRestantes == 0) {
            return new PlanoResponse("FORMADO", 0, 0, 0, true, List.of(),
                    List.of("Todas as disciplinas já foram concluídas. Parabéns!"));
        }
        if (pendentes.isEmpty()) {
            avisos.add("Nada a planejar: o filtro removeu todas as disciplinas restantes.");
            return new PlanoResponse("OK", 0, totalRestantes, chRestante, true, List.of(), avisos);
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
                if (!porCodigo.containsKey(pre)) continue;
                IntVar tp = termo.get(pre);
                if (tp == null) continue; // filtrada na expansão do bloqueio
                model.addLessOrEqual(tp, LinearExpr.affine(tc, 1, -1));
            }

            for (String co : d.coRequisitos()) {
                if (concluidas.contains(co)) continue;
                if (!porCodigo.containsKey(co)) continue;
                IntVar tco = termo.get(co);
                if (tco == null) continue;
                model.addEquality(tco, tc);
            }

            if (d.cargaHorariaMinima() > 0) {
                aplicarCargaHorariaMinima(model, d, tc, termo, porCodigo, chConcluida);
            }
        }

        aplicarCapacidade(model, termo, horizonte, maxDisciplinas);
        aplicarOrcamentoMensal(model, termo, horizonte, pendentes, curriculo.custos(), request, avisos);

        // Horários só no 1º período: o conjunto do próximo semestre tem que caber na oferta.
        Map<String, List<Turma>> turmasDaDisc = new LinkedHashMap<>();
        Map<String, Literal[]> selecaoTurma = new HashMap<>();
        if (request.considerarHorariosOrDefault() && ofertaRepository.disponivel()) {
            List<String> aproximados = new ArrayList<>();
            for (Disciplina d : pendentes) {
                OfertaRepository.Casamento m = ofertaRepository.casar(d.nome());
                if (m == null || m.turmas().isEmpty()) continue;
                List<Turma> comHorario = new ArrayList<>();
                for (Turma t : m.turmas()) {
                    if (t.horarios() != null && !t.horarios().isEmpty()) comHorario.add(t);
                }
                if (comHorario.isEmpty()) continue;
                turmasDaDisc.put(d.codigo(), comHorario);
                if (!m.exato()) {
                    aproximados.add(String.format("%s ≈ \"%s\" (%.0f%%)",
                            d.nome(), m.nomeOferta(), m.score() * 100));
                }
            }
            if (!aproximados.isEmpty()) {
                avisos.add("Casamento aproximado de nome (confira): " + String.join("; ", aproximados));
            }
            if (!turmasDaDisc.isEmpty()) {
                Map<String, Literal> emPrimeiroPeriodo = new HashMap<>();
                selecaoTurma = criarSelecaoDeTurmas(model, termo, turmasDaDisc, emPrimeiroPeriodo);
                int pares = aplicarConflitoPrimeiroPeriodo(model, turmasDaDisc, selecaoTurma, emPrimeiroPeriodo);
                avisos.add(String.format(
                        "Oferta %s: o 1º período respeita choque de horário (%d disciplinas com turma, %d par(es) de choque).",
                        ofertaRepository.semestre(), turmasDaDisc.size(), pares));
            }
        }

        // 1) menos períodos; 2) cada disciplina o mais cedo possível (gargalos pesam mais).
        IntVar makespan = model.newIntVar(1, horizonte, "makespan");
        model.addMaxEquality(makespan, termo.values().toArray(new IntVar[0]));

        // Peso: gargalo manda; empate vai para quem a grade oficial pede mais cedo.
        // Sem isso, TI/lab/humanas (prioridade 0) empatam e o solver joga umas no 9º período.
        int maxSugerido = 1;
        for (Disciplina d : pendentes) {
            maxSugerido = Math.max(maxSugerido, d.periodoSugerido());
        }
        final int faixaSugerida = maxSugerido + 1;
        final int pesoBase = 8;
        long somaPesos = 0;
        Map<String, Integer> pesoCurso = new HashMap<>();
        for (Disciplina d : pendentes) {
            int pri = grafoService.prioridade(codigoCurriculo, d.codigo());
            int cedoNaGrade = faixaSugerida - Math.max(1, d.periodoSugerido());
            int w = (pesoBase + pri) * faixaSugerida + cedoNaGrade;
            pesoCurso.put(d.codigo(), w);
            somaPesos += (long) w * horizonte;
        }
        long pesoMakespan = somaPesos + 1;

        LinearExprBuilder objetivo = LinearExpr.newBuilder();
        objetivo.addTerm(makespan, pesoMakespan);
        for (Disciplina d : pendentes) {
            objetivo.addTerm(termo.get(d.codigo()), pesoCurso.get(d.codigo()));
        }
        model.minimize(objetivo);

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(LIMITE_TEMPO_SEGUNDOS);
        CpSolverStatus status = solver.solve(model);

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            String motivo = request.temOrcamentoMensal()
                    ? "Não foi possível montar um plano viável com o orçamento mensal informado. "
                    + "Aumente o teto ou reduza a carga por período."
                    : "Não foi possível montar um plano viável. "
                    + "Verifique as disciplinas concluídas e a capacidade por período.";
            return new PlanoResponse("INVIAVEL", 0, pendentes.size(), chRestante, false,
                    List.of(), List.of(motivo));
        }

        boolean otimo = status == CpSolverStatus.OPTIMAL;
        if (!otimo) {
            avisos.add("Solução viável encontrada dentro do limite de tempo, "
                    + "mas a otimalidade não foi comprovada.");
        }

        Map<String, Integer> periodoDe = new HashMap<>();
        List<Disciplina> primeiroPeriodo = new ArrayList<>();
        for (Disciplina d : pendentes) {
            int t = (int) solver.value(termo.get(d.codigo()));
            periodoDe.put(d.codigo(), t);
            if (t == 1) primeiroPeriodo.add(d);
        }
        validarPlano(periodoDe, pendentes, porCodigo, concluidas, maxDisciplinas, avisos);

        Map<String, Turma> turmaEscolhida = new LinkedHashMap<>(
                extrairTurmasEscolhidas(solver, termo, turmasDaDisc, selecaoTurma));
        if (request.considerarHorariosOrDefault() && ofertaRepository.disponivel()) {
            // Completa matérias do 1º período sem horário no modelo (ex.: tópicos sem slot no SGA).
            List<Disciplina> semTurmaModelo = new ArrayList<>();
            for (Disciplina d : primeiroPeriodo) {
                if (!turmaEscolhida.containsKey(d.codigo())) semTurmaModelo.add(d);
            }
            Map<String, Turma> extra = atribuirTurmasSemConflito(semTurmaModelo, codigoCurriculo, new ArrayList<>());
            for (Map.Entry<String, Turma> e : extra.entrySet()) {
                boolean conflita = false;
                for (Turma ja : turmaEscolhida.values()) {
                    if (e.getValue().conflitaCom(ja)) {
                        conflita = true;
                        break;
                    }
                }
                if (!conflita) turmaEscolhida.put(e.getKey(), e.getValue());
            }
        }

        return montarResposta(solver, termo, pendentes, totalRestantes, chRestante, otimo, avisos,
                turmaEscolhida, codigoCurriculo);
    }

    /**
     * Planeja <b>apenas o próximo semestre</b>: escolhe o maior conjunto possível de
     * disciplinas que o aluno já pode cursar agora (todos os pré-requisitos concluídos e
     * carga horária mínima cumprida), respeitando a capacidade máxima por período, o
     * orçamento mensal (se informado) e evitando choque de horário entre as turmas da
     * oferta atual. Entre os empates, prioriza os gargalos (disciplinas que destravam
     * mais o currículo).
     *
     * <p>Faz sentido como um cálculo separado da rota completa porque os horários mudam a cada
     * semestre — só temos como garantir a grade do próximo semestre.
     */
    public PlanoResponse planejarProximoSemestre(PlanoRequest request) {
        String codigoCurriculo = request.codigoCurriculo();
        Curriculo curriculo = repository.getCurriculo(codigoCurriculo);
        Map<String, Disciplina> porCodigo = curriculo.indexadoPorCodigo();

        Set<String> concluidas = new HashSet<>(request.concluidas());
        Set<String> excluidas = new HashSet<>(request.excluidas());
        List<String> avisos = new ArrayList<>();
        for (String c : concluidas) {
            if (!porCodigo.containsKey(c)) {
                avisos.add("Código concluído não reconhecido e ignorado: " + c);
            }
        }
        registrarExcluidas(excluidas, porCodigo, avisos);

        int maxDisciplinas = request.maxDisciplinasOrDefault();
        boolean incluirOptativas = request.incluirOptativasOrDefault();

        long chConcluida = porCodigo.values().stream()
                .filter(d -> concluidas.contains(d.codigo()))
                .mapToLong(Disciplina::cargaHoraria)
                .sum();

        int totalPendentes = 0;
        int chRestante = 0;
        for (Disciplina d : curriculo.disciplinas()) {
            if (concluidas.contains(d.codigo())) continue;
            if (d.optativa() && !incluirOptativas) continue;
            totalPendentes++;
            chRestante += d.cargaHoraria();
        }

        // Disciplinas que o aluno já pode cursar no próximo semestre.
        List<Disciplina> elegiveis = new ArrayList<>();
        for (Disciplina d : curriculo.disciplinas()) {
            if (concluidas.contains(d.codigo())) continue;
            if (excluidas.contains(d.codigo())) continue;
            if (d.optativa() && !incluirOptativas) continue;

            boolean preOk = true;
            for (String pre : d.preRequisitos()) {
                if (!porCodigo.containsKey(pre)) continue; // pré externo: ignorado
                if (!concluidas.contains(pre)) { preOk = false; break; }
            }
            if (!preOk) continue;
            if (d.cargaHorariaMinima() > 0 && chConcluida < d.cargaHorariaMinima()) continue;

            elegiveis.add(d);
        }

        if (elegiveis.isEmpty()) {
            avisos.add("Nenhuma disciplina disponível para o próximo semestre com as concluídas informadas.");
            return new PlanoResponse("OK", 0, totalPendentes, chRestante, true, List.of(), avisos);
        }

        Set<String> codigosElegiveis = new HashSet<>();
        for (Disciplina d : elegiveis) codigosElegiveis.add(d.codigo());

        CpModel model = new CpModel();
        Map<String, Literal> cursar = new LinkedHashMap<>();
        for (Disciplina d : elegiveis) {
            cursar.put(d.codigo(), model.newBoolVar("cursar_" + d.codigo()));
        }

        // Co-requisitos: se não concluído, precisa ser cursado junto (ou é impossível agora).
        for (Disciplina d : elegiveis) {
            for (String co : d.coRequisitos()) {
                if (!porCodigo.containsKey(co) || concluidas.contains(co)) continue;
                if (codigosElegiveis.contains(co)) {
                    model.addImplication(cursar.get(d.codigo()), cursar.get(co));
                } else {
                    model.addBoolAnd(new Literal[]{cursar.get(d.codigo()).not()});
                }
            }
        }

        // Turmas/horários da oferta atual: escolha sem conflito.
        Map<String, List<Turma>> turmasDaDisc = new LinkedHashMap<>();
        Map<String, Literal[]> selecaoTurma = new HashMap<>();
        List<String> semTurma = new ArrayList<>();
        boolean usarHorarios = request.considerarHorariosOrDefault() && ofertaRepository.disponivel();
        if (usarHorarios) {
            List<String> aproximados = new ArrayList<>();
            for (Disciplina d : elegiveis) {
                OfertaRepository.Casamento m = ofertaRepository.casar(d.nome());
                if (m == null || m.turmas().isEmpty()) {
                    semTurma.add(d.nome());
                    continue;
                }
                turmasDaDisc.put(d.codigo(), m.turmas());
                if (!m.exato()) {
                    aproximados.add(String.format("%s ≈ \"%s\" (%.0f%%)",
                            d.nome(), m.nomeOferta(), m.score() * 100));
                }
            }
            if (!aproximados.isEmpty()) {
                avisos.add("Casamento aproximado de nome (confira): " + String.join("; ", aproximados));
            }

            for (Map.Entry<String, List<Turma>> e : turmasDaDisc.entrySet()) {
                String cod = e.getKey();
                int n = e.getValue().size();
                Literal[] sel = new Literal[n];
                for (int i = 0; i < n; i++) {
                    sel[i] = model.newBoolVar("turma_" + cod + "_" + i);
                }
                // Se cursar, escolhe exatamente uma turma; se não, nenhuma.
                model.addEquality(LinearExpr.sum(sel), LinearExpr.sum(new Literal[]{cursar.get(cod)}));
                selecaoTurma.put(cod, sel);
            }

            List<String> comTurma = new ArrayList<>(turmasDaDisc.keySet());
            for (int x = 0; x < comTurma.size(); x++) {
                for (int y = x + 1; y < comTurma.size(); y++) {
                    String c1 = comTurma.get(x);
                    String c2 = comTurma.get(y);
                    List<Turma> t1 = turmasDaDisc.get(c1);
                    List<Turma> t2 = turmasDaDisc.get(c2);
                    for (int i = 0; i < t1.size(); i++) {
                        for (int j = 0; j < t2.size(); j++) {
                            if (t1.get(i).conflitaCom(t2.get(j))) {
                                model.addBoolOr(new Literal[]{
                                        selecaoTurma.get(c1)[i].not(),
                                        selecaoTurma.get(c2)[j].not()
                                });
                            }
                        }
                    }
                }
            }

            // Sem turma na oferta atual: não pode ser cursada com checagem de horário.
            for (Disciplina d : elegiveis) {
                if (!turmasDaDisc.containsKey(d.codigo())) {
                    model.addBoolAnd(new Literal[]{cursar.get(d.codigo()).not()});
                }
            }
        } else if (request.considerarHorariosOrDefault()) {
            avisos.add("Oferta de turmas indisponível; o semestre foi montado sem checagem de horário.");
        }

        // Capacidade: no máximo maxDisciplinas no semestre.
        model.addLessOrEqual(LinearExpr.sum(cursar.values().toArray(new Literal[0])), maxDisciplinas);

        // Orçamento mensal: soma das mensalidades estimadas ≤ teto (se informado).
        aplicarOrcamentoMensalProximoSemestre(model, cursar, elegiveis, curriculo.custos(), request, avisos);

        // Objetivo: 1) maximizar quantidade de disciplinas; 2) priorizar gargalos.
        long somaPrioridades = 0;
        for (Disciplina d : elegiveis) somaPrioridades += grafoService.prioridade(codigoCurriculo, d.codigo());
        long pesoPorDisciplina = somaPrioridades + 1;

        LinearExprBuilder objetivo = LinearExpr.newBuilder();
        for (Disciplina d : elegiveis) {
            objetivo.addTerm(cursar.get(d.codigo()), pesoPorDisciplina + grafoService.prioridade(codigoCurriculo, d.codigo()));
            Literal[] sel = selecaoTurma.get(d.codigo());
            if (sel != null) {
                List<Turma> turmas = turmasDaDisc.get(d.codigo());
                for (int i = 0; i < sel.length; i++) {
                    objetivo.addTerm(sel[i], bonusHorarioTurma(turmas.get(i)));
                }
            }
        }
        model.maximize(objetivo);

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(LIMITE_TEMPO_SEGUNDOS);
        CpSolverStatus status = solver.solve(model);

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            String motivo = request.temOrcamentoMensal()
                    ? "Não foi possível montar o próximo semestre com o orçamento mensal informado."
                    : "Não foi possível montar o próximo semestre.";
            return new PlanoResponse("INVIAVEL", 0, totalPendentes, chRestante, false,
                    List.of(), List.of(motivo));
        }
        boolean otimo = status == CpSolverStatus.OPTIMAL;

        List<Disciplina> escolhidas = new ArrayList<>();
        for (Disciplina d : elegiveis) {
            if (solver.booleanValue(cursar.get(d.codigo()))) escolhidas.add(d);
        }
        escolhidas.sort(Comparator.comparingInt((Disciplina d) -> grafoService.prioridade(codigoCurriculo, d.codigo())).reversed());

        Map<String, Turma> turmaEscolhida = new HashMap<>();
        for (Disciplina d : escolhidas) {
            Literal[] sel = selecaoTurma.get(d.codigo());
            if (sel == null) continue;
            for (int i = 0; i < sel.length; i++) {
                if (solver.booleanValue(sel[i])) {
                    turmaEscolhida.put(d.codigo(), turmasDaDisc.get(d.codigo()).get(i));
                    break;
                }
            }
        }

        Set<String> codigosEscolhidos = new HashSet<>();
        for (Disciplina d : escolhidas) codigosEscolhidos.add(d.codigo());

        // Disciplinas que são co-requisito de alguma escolhida ficam "travadas" (não podem ser trocadas,
        // senão quebrariam o co-requisito da outra).
        Set<String> travadasPorCoreq = new HashSet<>();
        for (Disciplina d : escolhidas) {
            for (String co : d.coRequisitos()) {
                if (codigosEscolhidos.contains(co)) travadasPorCoreq.add(co);
            }
        }

        List<DisciplinaPlanejadaDTO> dtos = new ArrayList<>();
        int chTotal = 0;
        for (Disciplina d : escolhidas) {
            chTotal += d.cargaHoraria();
            Turma turma = turmaEscolhida.get(d.codigo());
            List<AlternativaDTO> alternativas = travadasPorCoreq.contains(d.codigo())
                    ? List.of()
                    : alternativasMesmoHorario(d, turma, elegiveis, codigosEscolhidos,
                            turmasDaDisc, porCodigo, concluidas, codigoCurriculo);
            dtos.add(new DisciplinaPlanejadaDTO(
                    d.codigo(), d.nome(), d.cargaHoraria(), d.optativa(), d.semipresencial(),
                    grafoService.prioridade(codigoCurriculo, d.codigo()),
                    grafoService.getDescendentes(codigoCurriculo, d.codigo()),
                    montarMotivo(d, codigoCurriculo),
                    turma != null ? turma.codigo() : null,
                    turma != null ? turma.horarios() : null,
                    alternativas));
        }

        if (ofertaRepository.disponivel()) {
            avisos.add(String.format("Oferta %s: %d de %d disciplina(s) escolhidas têm turma casada.",
                    ofertaRepository.semestre(), turmaEscolhida.size(), escolhidas.size()));
        }
        if (!semTurma.isEmpty()) {
            avisos.add("Disciplinas elegíveis sem turma casada na oferta (não entram na grade de horários): "
                    + String.join("; ", semTurma));
        }

        List<PeriodoDTO> periodos = List.of(new PeriodoDTO(1, escolhidas.size(), chTotal, dtos));
        return new PlanoResponse("OK", 1, totalPendentes, chRestante, otimo, periodos, avisos);
    }

    /**
     * Encontra disciplinas elegíveis (não escolhidas) que possuem uma turma com <b>exatamente o
     * mesmo horário</b> da turma escolhida para {@code alvo} — logo, podem ser trocadas sem mexer
     * na grade nem gerar choque com as demais.
     */
    private List<AlternativaDTO> alternativasMesmoHorario(Disciplina alvo, Turma turmaAlvo,
                                                          List<Disciplina> elegiveis,
                                                          Set<String> escolhidas,
                                                          Map<String, List<Turma>> turmasDaDisc,
                                                          Map<String, Disciplina> porCodigo,
                                                          Set<String> concluidas,
                                                          String codigoCurriculo) {
        if (turmaAlvo == null || turmaAlvo.horarios().isEmpty()) return List.of();
        Set<String> assinaturaAlvo = assinaturaHorario(turmaAlvo);

        List<AlternativaDTO> alternativas = new ArrayList<>();
        for (Disciplina e : elegiveis) {
            if (e.codigo().equals(alvo.codigo()) || escolhidas.contains(e.codigo())) continue;

            boolean coreqOk = true;
            for (String co : e.coRequisitos()) {
                if (porCodigo.containsKey(co) && !concluidas.contains(co)) { coreqOk = false; break; }
            }
            if (!coreqOk) continue;

            List<Turma> turmas = turmasDaDisc.get(e.codigo());
            if (turmas == null) continue;
            for (Turma t : turmas) {
                if (assinaturaHorario(t).equals(assinaturaAlvo)) {
                    alternativas.add(new AlternativaDTO(
                            e.codigo(), e.nome(), e.cargaHoraria(), e.optativa(), e.semipresencial(),
                            grafoService.prioridade(codigoCurriculo, e.codigo()),
                            grafoService.getDescendentes(codigoCurriculo, e.codigo()),
                            t.codigo(), t.horarios()));
                    break;
                }
            }
        }
        alternativas.sort(Comparator.comparingInt(AlternativaDTO::prioridade).reversed());
        return alternativas;
    }

    private Set<String> assinaturaHorario(Turma turma) {
        Set<String> assinatura = new HashSet<>();
        for (com.pucminas.gradeinteligente.domain.Horario h : turma.horarios()) {
            assinatura.add(h.dia() + "|" + h.inicio() + "|" + h.fim());
        }
        return assinatura;
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
            Literal emT1 = model.newBoolVar("emT1_" + cod);
            model.addEquality(termo.get(cod), 1).onlyEnforceIf(emT1);
            model.addDifferent(termo.get(cod), 1).onlyEnforceIf(emT1.not());
            emPrimeiroPeriodo.put(cod, emT1);
            // Só escolhe turma da oferta atual se a disciplina cair no 1º período.
            model.addEquality(LinearExpr.sum(sel), LinearExpr.sum(new Literal[]{emT1}));
            selecao.put(cod, sel);
        }
        return selecao;
    }

    /** Bônus leve para turmas que ocupam o primeiro horário noturno (19:00). */
    private int bonusHorarioTurma(Turma turma) {
        int bonus = 0;
        for (com.pucminas.gradeinteligente.domain.Horario h : turma.horarios()) {
            bonus += 1;
            if ("19:00".equals(h.inicio())) bonus += 5;
        }
        return bonus;
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

    /**
     * Restringe a mensalidade estimada de cada período ao teto informado (rota completa).
     * Usa centavos para manter o CP-SAT em aritmética inteira.
     */
    private void aplicarOrcamentoMensal(CpModel model, Map<String, IntVar> termo, int horizonte,
                                        List<Disciplina> pendentes, Custos custos,
                                        PlanoRequest request, List<String> avisos) {
        Long tetoCentavos = tetoOrcamentoCentavos(custos, request, avisos);
        if (tetoCentavos == null) return;

        Map<String, Disciplina> porCodigo = new HashMap<>();
        for (Disciplina d : pendentes) porCodigo.put(d.codigo(), d);

        for (int t = 1; t <= horizonte; t++) {
            LinearExprBuilder custoPeriodo = LinearExpr.newBuilder();
            for (Map.Entry<String, IntVar> e : termo.entrySet()) {
                Literal b = model.newBoolVar("custoT_" + e.getKey() + "_" + t);
                model.addEquality(e.getValue(), t).onlyEnforceIf(b);
                model.addDifferent(e.getValue(), t).onlyEnforceIf(b.not());
                custoPeriodo.addTerm(b, custos.custoMensalCentavos(porCodigo.get(e.getKey())));
            }
            model.addLessOrEqual(custoPeriodo, tetoCentavos);
        }
    }

    /** Restringe a mensalidade estimada do próximo semestre ao teto informado. */
    private void aplicarOrcamentoMensalProximoSemestre(CpModel model, Map<String, Literal> cursar,
                                                       List<Disciplina> elegiveis, Custos custos,
                                                       PlanoRequest request, List<String> avisos) {
        Long tetoCentavos = tetoOrcamentoCentavos(custos, request, avisos);
        if (tetoCentavos == null) return;

        LinearExprBuilder custoSemestre = LinearExpr.newBuilder();
        for (Disciplina d : elegiveis) {
            custoSemestre.addTerm(cursar.get(d.codigo()), custos.custoMensalCentavos(d));
        }
        model.addLessOrEqual(custoSemestre, tetoCentavos);
    }

    /**
     * Converte o orçamento em centavos, ou {@code null} se não houver teto aplicável.
     * Emite aviso quando o usuário pediu orçamento mas o currículo não tem custos.
     */
    private Long tetoOrcamentoCentavos(Custos custos, PlanoRequest request, List<String> avisos) {
        if (!request.temOrcamentoMensal()) return null;
        if (custos == null) {
            avisos.add("Orçamento mensal ignorado: o currículo selecionado não possui parâmetros de custo.");
            return null;
        }
        long teto = Math.round(request.orcamentoMensalMax() * 100.0);
        avisos.add(String.format(
                "Orçamento mensal limitado a R$ %.2f (estimativa SGA, sem matrícula/bolsas).",
                request.orcamentoMensalMax()));
        return teto;
    }

    /**
     * Se o usuário filtra uma disciplina, ninguém que dependa dela (pré ou co)
     * pode entrar no plano — senão a rota inventa Cálculo II sem Cálculo I.
     */
    private Set<String> expandirExcluidas(Set<String> excluidas, List<Disciplina> todas,
                                          Set<String> concluidas, Map<String, Disciplina> porCodigo,
                                          List<String> avisos) {
        Set<String> bloqueadas = new HashSet<>(excluidas);
        boolean mudou = true;
        while (mudou) {
            mudou = false;
            for (Disciplina d : todas) {
                if (concluidas.contains(d.codigo()) || bloqueadas.contains(d.codigo())) continue;
                boolean depende = false;
                for (String pre : d.preRequisitos()) {
                    if (porCodigo.containsKey(pre) && bloqueadas.contains(pre)) {
                        depende = true;
                        break;
                    }
                }
                if (!depende) {
                    for (String co : d.coRequisitos()) {
                        if (porCodigo.containsKey(co) && bloqueadas.contains(co)) {
                            depende = true;
                            break;
                        }
                    }
                }
                if (depende) {
                    bloqueadas.add(d.codigo());
                    mudou = true;
                }
            }
        }
        List<String> extras = new ArrayList<>();
        for (String c : bloqueadas) {
            if (excluidas.contains(c)) continue;
            Disciplina d = porCodigo.get(c);
            if (d != null) extras.add(d.nome());
        }
        if (!extras.isEmpty()) {
            avisos.add("Também fora do plano (dependem de matéria filtrada): " + String.join("; ", extras));
        }
        return bloqueadas;
    }

    private void validarPlano(Map<String, Integer> periodoDe, List<Disciplina> pendentes,
                              Map<String, Disciplina> porCodigo, Set<String> concluidas,
                              int maxDisciplinas, List<String> avisos) {
        Map<Integer, Integer> qtd = new HashMap<>();
        for (Disciplina d : pendentes) {
            int t = periodoDe.getOrDefault(d.codigo(), -1);
            qtd.merge(t, 1, Integer::sum);
            for (String pre : d.preRequisitos()) {
                if (concluidas.contains(pre) || !porCodigo.containsKey(pre)) continue;
                Integer tp = periodoDe.get(pre);
                if (tp == null) {
                    avisos.add("Inconsistência: " + d.nome() + " ficou no plano sem o pré-requisito "
                            + porCodigo.get(pre).nome() + ".");
                } else if (tp >= t) {
                    avisos.add("Inconsistência: " + d.nome() + " no período " + t
                            + " sem ter concluído " + porCodigo.get(pre).nome() + ".");
                }
            }
            for (String co : d.coRequisitos()) {
                if (concluidas.contains(co) || !porCodigo.containsKey(co)) continue;
                Integer tc = periodoDe.get(co);
                if (tc == null) {
                    avisos.add("Inconsistência: " + d.nome() + " ficou no plano sem o co-requisito "
                            + porCodigo.get(co).nome() + ".");
                } else if (!tc.equals(t)) {
                    avisos.add("Inconsistência: co-requisito " + porCodigo.get(co).nome()
                            + " fora do mesmo período de " + d.nome() + ".");
                }
            }
        }
        for (Map.Entry<Integer, Integer> e : qtd.entrySet()) {
            if (e.getValue() > maxDisciplinas) {
                avisos.add("Inconsistência: período " + e.getKey() + " com " + e.getValue()
                        + " disciplinas (teto " + maxDisciplinas + ").");
            }
        }
    }

    /** Escolhe turmas do 1º período sem mexer na rota acadêmica. */
    private Map<String, Turma> atribuirTurmasSemConflito(List<Disciplina> doPeriodo,
                                                         String codigoCurriculo,
                                                         List<String> avisos) {
        List<Disciplina> ordenadas = new ArrayList<>(doPeriodo);
        ordenadas.sort(Comparator.comparingInt(
                (Disciplina d) -> grafoService.prioridade(codigoCurriculo, d.codigo())).reversed());

        Map<String, Turma> escolhida = new LinkedHashMap<>();
        List<String> semEncaixe = new ArrayList<>();
        for (Disciplina d : ordenadas) {
            OfertaRepository.Casamento m = ofertaRepository.casar(d.nome());
            if (m == null || m.turmas().isEmpty()) continue;
            List<Turma> turmas = new ArrayList<>();
            for (Turma t : m.turmas()) {
                if (t.horarios() != null && !t.horarios().isEmpty()) turmas.add(t);
            }
            if (turmas.isEmpty()) continue;
            turmas.sort(Comparator.comparingInt(this::bonusHorarioTurma).reversed());
            Turma ok = null;
            for (Turma t : turmas) {
                boolean conflita = false;
                for (Turma outra : escolhida.values()) {
                    if (t.conflitaCom(outra)) {
                        conflita = true;
                        break;
                    }
                }
                if (!conflita) {
                    ok = t;
                    break;
                }
            }
            if (ok != null) escolhida.put(d.codigo(), ok);
            else semEncaixe.add(d.nome());
        }
        if (!escolhida.isEmpty()) {
            avisos.add(String.format("Oferta %s: turmas encaixadas em %d de %d disciplina(s) do 1º período.",
                    ofertaRepository.semestre(), escolhida.size(), doPeriodo.size()));
        }
        if (!semEncaixe.isEmpty()) {
            avisos.add("Sem turma sem choque neste semestre (a rota acadêmica foi mantida): "
                    + String.join("; ", semEncaixe));
        }
        return escolhida;
    }

    private void registrarExcluidas(Set<String> excluidas, Map<String, Disciplina> porCodigo,
                                    List<String> avisos) {
        List<String> validas = new ArrayList<>();
        for (String c : excluidas) {
            Disciplina d = porCodigo.get(c);
            if (d == null) {
                avisos.add("Código excluído não reconhecido e ignorado: " + c);
            } else {
                validas.add(d.nome());
            }
        }
        if (!validas.isEmpty()) {
            avisos.add("Filtro do usuário — não incluir: " + String.join("; ", validas));
        }
    }

    private PlanoResponse montarResposta(CpSolver solver, Map<String, IntVar> termo,
                                         List<Disciplina> pendentes, int totalRestantes,
                                         int chRestante, boolean otimo, List<String> avisos,
                                         Map<String, Turma> turmaEscolhida,
                                         String codigoCurriculo) {
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
            lista.sort(Comparator.comparingInt((Disciplina d) -> grafoService.prioridade(codigoCurriculo, d.codigo()))
                    .reversed());

            List<DisciplinaPlanejadaDTO> dtos = new ArrayList<>();
            int chTotal = 0;
            for (Disciplina d : lista) {
                chTotal += d.cargaHoraria();
                Turma turma = turmaEscolhida.get(d.codigo());
                dtos.add(new DisciplinaPlanejadaDTO(
                        d.codigo(), d.nome(), d.cargaHoraria(), d.optativa(), d.semipresencial(),
                        grafoService.prioridade(codigoCurriculo, d.codigo()),
                        grafoService.getDescendentes(codigoCurriculo, d.codigo()),
                        montarMotivo(d, codigoCurriculo),
                        turma != null ? turma.codigo() : null,
                        turma != null ? turma.horarios() : null,
                        List.of()));
            }
            periodos.add(new PeriodoDTO(numeroSequencial, lista.size(), chTotal, dtos));
        }

        return new PlanoResponse("OK", periodos.size(), totalRestantes, chRestante, otimo,
                periodos, avisos);
    }

    private String montarMotivo(Disciplina d, String codigoCurriculo) {
        int destrava = grafoService.getDescendentes(codigoCurriculo, d.codigo());
        int profundidade = grafoService.getProfundidadeCadeia(codigoCurriculo, d.codigo());
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
