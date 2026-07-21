package com.pucminas.gradeinteligente.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * Entrada do planejamento.
 *
 * @param concluidas               códigos das disciplinas já aprovadas
 * @param maxDisciplinasPorPeriodo teto de disciplinas por período (capacidade)
 * @param incluirOptativas         se as optativas genéricas entram no plano
 */
public record PlanoRequest(
        List<String> concluidas,
        @Min(1) @Max(15) Integer maxDisciplinasPorPeriodo,
        Boolean incluirOptativas,
        Boolean considerarHorarios,
        String codigoCurriculo
) {
    public PlanoRequest {
        concluidas = concluidas == null ? List.of() : concluidas;
    }

    public int maxDisciplinasOrDefault() {
        return maxDisciplinasPorPeriodo == null ? 6 : maxDisciplinasPorPeriodo;
    }

    public boolean incluirOptativasOrDefault() {
        return incluirOptativas == null || incluirOptativas;
    }

    public boolean considerarHorariosOrDefault() {
        return considerarHorarios == null || considerarHorarios;
    }
}
