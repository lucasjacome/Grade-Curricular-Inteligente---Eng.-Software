package com.pucminas.gradeinteligente.dto;

import com.pucminas.gradeinteligente.domain.Horario;

import java.util.List;

/**
 * Disciplina que pode substituir uma escolhida no próximo semestre <b>sem alterar a grade</b>:
 * ocupa exatamente a mesma faixa de horário (mesmos dias e intervalos), então é uma troca livre.
 */
public record AlternativaDTO(
        String codigo,
        String nome,
        int cargaHoraria,
        boolean optativa,
        boolean semipresencial,
        int prioridade,
        int destrava,
        String turma,
        List<Horario> horarios
) {
}
