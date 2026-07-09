package com.pucminas.gradeinteligente.dto;

import com.pucminas.gradeinteligente.domain.Horario;

import java.util.List;

public record DisciplinaPlanejadaDTO(
        String codigo,
        String nome,
        int cargaHoraria,
        boolean optativa,
        boolean semipresencial,
        int prioridade,
        int destrava,
        String motivo,
        String turma,
        List<Horario> horarios
) {
}
