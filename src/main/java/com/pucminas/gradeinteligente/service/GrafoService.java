package com.pucminas.gradeinteligente.service;

import com.pucminas.gradeinteligente.domain.Curriculo;
import com.pucminas.gradeinteligente.domain.Disciplina;
import com.pucminas.gradeinteligente.dto.GrafoDTO;
import com.pucminas.gradeinteligente.repository.CurriculoRepository;
import jakarta.annotation.PostConstruct;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Modela cada currículo como um DAG de pré-requisitos e expõe métricas de gargalo.
 */
@Service
public class GrafoService {

    private final CurriculoRepository repository;
    private final Map<String, AnaliseGrafo> porCurriculo = new HashMap<>();

    public GrafoService(CurriculoRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void construir() {
        porCurriculo.clear();
        for (Curriculo curriculo : repository.listar()) {
            porCurriculo.put(curriculo.codigoCurriculo(), analisar(curriculo));
        }
    }

    private AnaliseGrafo analisar(Curriculo curriculo) {
        Map<String, Disciplina> porCodigo = curriculo.indexadoPorCodigo();

        DirectedAcyclicGraph<String, DefaultEdge> grafo = new DirectedAcyclicGraph<>(DefaultEdge.class);
        for (String codigo : porCodigo.keySet()) {
            grafo.addVertex(codigo);
        }
        for (Disciplina d : curriculo.disciplinas()) {
            for (String pre : d.preRequisitos()) {
                if (porCodigo.containsKey(pre)) {
                    grafo.addEdge(pre, d.codigo());
                }
            }
        }

        CycleDetector<String, DefaultEdge> detector = new CycleDetector<>(grafo);
        if (detector.detectCycles()) {
            throw new IllegalStateException(
                    "Ciclo de pré-requisitos no currículo " + curriculo.codigoCurriculo()
                            + ": " + detector.findCycles());
        }

        List<String> ordem = calcularOrdemTopologica(grafo);
        return new AnaliseGrafo(
                grafo,
                ordem,
                calcularDescendentes(grafo, ordem),
                calcularProfundidade(grafo, ordem));
    }

    private List<String> calcularOrdemTopologica(Graph<String, DefaultEdge> grafo) {
        List<String> ordem = new ArrayList<>();
        new TopologicalOrderIterator<>(grafo).forEachRemaining(ordem::add);
        return ordem;
    }

    private Map<String, Integer> calcularDescendentes(Graph<String, DefaultEdge> grafo, List<String> ordem) {
        Map<String, Set<String>> alcancaveis = new HashMap<>();
        List<String> reversa = new ArrayList<>(ordem);
        java.util.Collections.reverse(reversa);
        for (String v : reversa) {
            Set<String> acc = new HashSet<>();
            for (String suc : Graphs.successorListOf(grafo, v)) {
                acc.add(suc);
                acc.addAll(alcancaveis.get(suc));
            }
            alcancaveis.put(v, acc);
        }
        Map<String, Integer> resultado = new HashMap<>();
        alcancaveis.forEach((k, v) -> resultado.put(k, v.size()));
        return resultado;
    }

    private Map<String, Integer> calcularProfundidade(Graph<String, DefaultEdge> grafo, List<String> ordem) {
        Map<String, Integer> prof = new HashMap<>();
        List<String> reversa = new ArrayList<>(ordem);
        java.util.Collections.reverse(reversa);
        for (String v : reversa) {
            int max = 0;
            for (String suc : Graphs.successorListOf(grafo, v)) {
                max = Math.max(max, 1 + prof.get(suc));
            }
            prof.put(v, max);
        }
        return prof;
    }

    private AnaliseGrafo analise(String codigoCurriculo) {
        String chave = (codigoCurriculo == null || codigoCurriculo.isBlank())
                ? repository.getCodigoPadrao()
                : codigoCurriculo.trim();
        AnaliseGrafo a = porCurriculo.get(chave);
        if (a == null) {
            throw new IllegalArgumentException("Grafo não disponível para currículo: " + chave);
        }
        return a;
    }

    public int prioridade(String codigo) {
        return prioridade(repository.getCodigoPadrao(), codigo);
    }

    public int prioridade(String codigoCurriculo, String codigo) {
        AnaliseGrafo a = analise(codigoCurriculo);
        return a.descendentes.getOrDefault(codigo, 0) + a.profundidadeCadeia.getOrDefault(codigo, 0);
    }

    public int getDescendentes(String codigo) {
        return getDescendentes(repository.getCodigoPadrao(), codigo);
    }

    public int getDescendentes(String codigoCurriculo, String codigo) {
        return analise(codigoCurriculo).descendentes.getOrDefault(codigo, 0);
    }

    public int getProfundidadeCadeia(String codigo) {
        return getProfundidadeCadeia(repository.getCodigoPadrao(), codigo);
    }

    public int getProfundidadeCadeia(String codigoCurriculo, String codigo) {
        return analise(codigoCurriculo).profundidadeCadeia.getOrDefault(codigo, 0);
    }

    public GrafoDTO montarVisualizacao() {
        return montarVisualizacao(repository.getCodigoPadrao());
    }

    public GrafoDTO montarVisualizacao(String codigoCurriculo) {
        Curriculo curriculo = repository.getCurriculo(codigoCurriculo);
        Map<String, Disciplina> porCodigo = curriculo.indexadoPorCodigo();

        List<GrafoDTO.No> nos = new ArrayList<>();
        List<GrafoDTO.Aresta> arestas = new ArrayList<>();

        for (Disciplina d : curriculo.disciplinas()) {
            nos.add(new GrafoDTO.No(
                    d.codigo(), d.nome(), d.cargaHoraria(), d.periodoSugerido(),
                    d.optativa(), d.semipresencial(),
                    prioridade(codigoCurriculo, d.codigo()),
                    getDescendentes(codigoCurriculo, d.codigo())));

            for (String pre : d.preRequisitos()) {
                if (porCodigo.containsKey(pre)) {
                    arestas.add(new GrafoDTO.Aresta(pre, d.codigo(), "PRE"));
                }
            }
            for (String co : d.coRequisitos()) {
                if (porCodigo.containsKey(co)) {
                    arestas.add(new GrafoDTO.Aresta(co, d.codigo(), "CO"));
                }
            }
        }
        return new GrafoDTO(nos, arestas);
    }

    public Set<String> dependentesDe(String codigo) {
        return dependentesDe(repository.getCodigoPadrao(), codigo);
    }

    public Set<String> dependentesDe(String codigoCurriculo, String codigo) {
        Set<String> acc = new LinkedHashSet<>();
        coletarDependentes(analise(codigoCurriculo).grafo, codigo, acc);
        return acc;
    }

    private void coletarDependentes(Graph<String, DefaultEdge> grafo, String codigo, Set<String> acc) {
        for (String suc : Graphs.successorListOf(grafo, codigo)) {
            if (acc.add(suc)) {
                coletarDependentes(grafo, suc, acc);
            }
        }
    }

    private record AnaliseGrafo(
            Graph<String, DefaultEdge> grafo,
            List<String> ordemTopologica,
            Map<String, Integer> descendentes,
            Map<String, Integer> profundidadeCadeia
    ) {}
}
