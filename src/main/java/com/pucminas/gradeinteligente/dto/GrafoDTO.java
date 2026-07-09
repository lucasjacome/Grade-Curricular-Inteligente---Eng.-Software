package com.pucminas.gradeinteligente.dto;

import java.util.List;

/** Representação do grafo para visualização no front-end (Cytoscape.js). */
public record GrafoDTO(List<No> nos, List<Aresta> arestas) {

    public record No(
            String codigo,
            String nome,
            int cargaHoraria,
            int periodoSugerido,
            boolean optativa,
            boolean semipresencial,
            int prioridade,
            int destrava
    ) {
    }

    public record Aresta(String origem, String destino, String tipo) {
    }
}
