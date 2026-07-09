package com.pucminas.gradeinteligente.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Uma turma ofertada de uma disciplina, com seus horários semanais.
 *
 * @param codigo   código da turma (ex.: 7622.1.00)
 * @param sincrona se é aula síncrona/remota
 * @param horarios encontros semanais
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Turma(String codigo, boolean sincrona, List<Horario> horarios) {
    public Turma {
        horarios = horarios == null ? List.of() : List.copyOf(horarios);
    }

    public boolean conflitaCom(Turma outra) {
        for (Horario h1 : horarios) {
            for (Horario h2 : outra.horarios()) {
                if (h1.conflitaCom(h2)) return true;
            }
        }
        return false;
    }
}
