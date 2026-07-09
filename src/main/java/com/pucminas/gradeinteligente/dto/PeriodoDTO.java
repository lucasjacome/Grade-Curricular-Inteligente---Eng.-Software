package com.pucminas.gradeinteligente.dto;

import java.util.List;

public record PeriodoDTO(
        int numero,
        int quantidade,
        int cargaHorariaTotal,
        List<DisciplinaPlanejadaDTO> disciplinas
) {
}
