package com.pucminas.gradeinteligente.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Oferta de turmas de um semestre, extraída do SGA. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Oferta(String fonte, String semestre, List<OfertaDisciplina> disciplinas) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OfertaDisciplina(String nome, List<Turma> turmas) {
    }
}
