package com.pucminas.gradeinteligente.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Curriculo(
        String curso,
        String codigoCurriculo,
        String rotulo,
        String instituicao,
        int cargaHorariaTotal,
        String situacao,
        String fonte,
        Custos custos,
        List<Disciplina> disciplinas
) {
    /** Índice código -> disciplina, preservando a ordem do JSON. */
    public Map<String, Disciplina> indexadoPorCodigo() {
        Map<String, Disciplina> mapa = new LinkedHashMap<>();
        for (Disciplina d : disciplinas) {
            mapa.put(d.codigo(), d);
        }
        return mapa;
    }
}
