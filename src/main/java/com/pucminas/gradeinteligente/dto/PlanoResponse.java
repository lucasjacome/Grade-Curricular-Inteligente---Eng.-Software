package com.pucminas.gradeinteligente.dto;

import java.util.List;

public record PlanoResponse(
        String status,
        int totalPeriodos,
        int totalDisciplinasRestantes,
        int cargaHorariaRestante,
        boolean otimoComprovado,
        List<PeriodoDTO> periodos,
        List<String> avisos
) {
}
