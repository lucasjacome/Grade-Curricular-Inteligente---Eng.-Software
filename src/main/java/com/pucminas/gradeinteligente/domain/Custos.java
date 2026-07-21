package com.pucminas.gradeinteligente.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/** Parâmetros de cobrança calibrados com o simulador do SGA (2026/2). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Custos(
        String semestre,
        double matricula,
        double valorHoraMensalPadrao,
        int parcelasMensais,
        Map<String, Double> tarifasPorCodigo
) {
    public Custos {
        tarifasPorCodigo = tarifasPorCodigo == null ? Map.of() : Map.copyOf(tarifasPorCodigo);
    }

    public double valorHoraMensal(String codigo) {
        return tarifasPorCodigo.getOrDefault(codigo, valorHoraMensalPadrao);
    }
}
