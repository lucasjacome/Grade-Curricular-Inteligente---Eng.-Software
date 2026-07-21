package com.pucminas.gradeinteligente.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Representa uma disciplina do currículo. É imutável: o progresso do aluno
 * (concluídas) é informado por requisição, não fica acoplado ao catálogo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Disciplina(
        String codigo,
        String nome,
        int cargaHoraria,
        Integer cargaHorariaCobranca,
        int periodoSugerido,
        List<String> preRequisitos,
        List<String> coRequisitos,
        int cargaHorariaMinima,
        boolean optativa,
        boolean semipresencial,
        List<Horario> horarios
) {
    public Disciplina {
        preRequisitos = preRequisitos == null ? List.of() : List.copyOf(preRequisitos);
        coRequisitos = coRequisitos == null ? List.of() : List.copyOf(coRequisitos);
        horarios = horarios == null ? List.of() : List.copyOf(horarios);
    }

    /** Carga horária usada pelo SGA na cobrança (pode diferir do currículo). */
    public int chCobranca() {
        return cargaHorariaCobranca != null ? cargaHorariaCobranca : cargaHoraria;
    }

    public boolean temHorario() {
        return !horarios.isEmpty();
    }

    /** Há choque de horário entre esta disciplina e outra (mesmo dia e sobreposição)? */
    public boolean conflitaCom(Disciplina outra) {
        if (!temHorario() || !outra.temHorario()) return false;
        for (Horario h1 : horarios) {
            for (Horario h2 : outra.horarios()) {
                if (h1.conflitaCom(h2)) return true;
            }
        }
        return false;
    }
}
