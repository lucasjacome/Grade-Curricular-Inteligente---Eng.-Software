package com.pucminas.gradeinteligente.service;

import com.pucminas.gradeinteligente.domain.Curriculo;
import com.pucminas.gradeinteligente.domain.Disciplina;
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
 * Modela o currículo como um grafo acíclico dirigido (DAG) de pré-requisitos
 * (aresta pré -> disciplina) e expõe métricas de importância:
 *
 * <ul>
 *   <li><b>descendentes</b>: quantas disciplinas essa destrava (direta e indiretamente);</li>
 *   <li><b>profundidadeCadeia</b>: maior cadeia de dependências que parte dela (caminho crítico).</li>
 * </ul>
 *
 * A soma dessas métricas é usada como prioridade: gargalos primeiro.
 */
@Service
public class GrafoService {

    private final CurriculoRepository repository;

    private Graph<String, DefaultEdge> grafoPreRequisitos;
    private Map<String, Integer> descendentes;
    private Map<String, Integer> profundidadeCadeia;
    private List<String> ordemTopologica;

    public GrafoService(CurriculoRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void construir() {
        Curriculo curriculo = repository.getCurriculo();
        Map<String, Disciplina> porCodigo = curriculo.indexadoPorCodigo();

        DirectedAcyclicGraph<String, DefaultEdge> grafo = new DirectedAcyclicGraph<>(DefaultEdge.class);
        for (String codigo : porCodigo.keySet()) {
            grafo.addVertex(codigo);
        }
        for (Disciplina d : curriculo.disciplinas()) {
            for (String pre : d.preRequisitos()) {
                if (porCodigo.containsKey(pre)) {
                    grafo.addEdge(pre, d.codigo()); // pré destrava a disciplina
                }
            }
        }

        CycleDetector<String, DefaultEdge> detector = new CycleDetector<>(grafo);
        if (detector.detectCycles()) {
            throw new IllegalStateException(
                    "Ciclo de pré-requisitos detectado: " + detector.findCycles());
        }

        this.grafoPreRequisitos = grafo;
        this.ordemTopologica = calcularOrdemTopologica(grafo);
        this.descendentes = calcularDescendentes(grafo);
        this.profundidadeCadeia = calcularProfundidade(grafo);
    }

    private List<String> calcularOrdemTopologica(Graph<String, DefaultEdge> grafo) {
        List<String> ordem = new ArrayList<>();
        new TopologicalOrderIterator<>(grafo).forEachRemaining(ordem::add);
        return ordem;
    }

    private Map<String, Integer> calcularDescendentes(Graph<String, DefaultEdge> grafo) {
        Map<String, Set<String>> alcancaveis = new HashMap<>();
        List<String> reversa = new ArrayList<>(ordemTopologica);
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

    private Map<String, Integer> calcularProfundidade(Graph<String, DefaultEdge> grafo) {
        Map<String, Integer> prof = new HashMap<>();
        List<String> reversa = new ArrayList<>(ordemTopologica);
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

    /** Prioridade do gargalo: quanto maior, mais cedo deve ser cursada. */
    public int prioridade(String codigo) {
        return descendentes.getOrDefault(codigo, 0) + profundidadeCadeia.getOrDefault(codigo, 0);
    }

    public int getDescendentes(String codigo) {
        return descendentes.getOrDefault(codigo, 0);
    }

    public int getProfundidadeCadeia(String codigo) {
        return profundidadeCadeia.getOrDefault(codigo, 0);
    }

    public List<String> getOrdemTopologica() {
        return List.copyOf(ordemTopologica);
    }

    /** Monta a representação do grafo para o front-end (nós + arestas tipadas). */
    public com.pucminas.gradeinteligente.dto.GrafoDTO montarVisualizacao() {
        Curriculo curriculo = repository.getCurriculo();
        Map<String, Disciplina> porCodigo = curriculo.indexadoPorCodigo();

        List<com.pucminas.gradeinteligente.dto.GrafoDTO.No> nos = new ArrayList<>();
        List<com.pucminas.gradeinteligente.dto.GrafoDTO.Aresta> arestas = new ArrayList<>();

        for (Disciplina d : curriculo.disciplinas()) {
            nos.add(new com.pucminas.gradeinteligente.dto.GrafoDTO.No(
                    d.codigo(), d.nome(), d.cargaHoraria(), d.periodoSugerido(),
                    d.optativa(), d.semipresencial(),
                    prioridade(d.codigo()), getDescendentes(d.codigo())));

            for (String pre : d.preRequisitos()) {
                if (porCodigo.containsKey(pre)) {
                    arestas.add(new com.pucminas.gradeinteligente.dto.GrafoDTO.Aresta(pre, d.codigo(), "PRE"));
                }
            }
            for (String co : d.coRequisitos()) {
                if (porCodigo.containsKey(co)) {
                    arestas.add(new com.pucminas.gradeinteligente.dto.GrafoDTO.Aresta(co, d.codigo(), "CO"));
                }
            }
        }
        return new com.pucminas.gradeinteligente.dto.GrafoDTO(nos, arestas);
    }

    /** Códigos alcançáveis (dependentes) a partir de uma disciplina. */
    public Set<String> dependentesDe(String codigo) {
        Set<String> acc = new LinkedHashSet<>();
        coletarDependentes(codigo, acc);
        return acc;
    }

    private void coletarDependentes(String codigo, Set<String> acc) {
        for (String suc : Graphs.successorListOf(grafoPreRequisitos, codigo)) {
            if (acc.add(suc)) {
                coletarDependentes(suc, acc);
            }
        }
    }
}
