package com.pucminas.gradeinteligente.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * Entrada do planejamento.
 *
 * @param concluidas               códigos das disciplinas já aprovadas
 * @param maxDisciplinasPorPeriodo teto de disciplinas por período (capacidade)
 * @param incluirOptativas         se as optativas genéricas entram no plano
 * @param considerarHorarios       se a oferta/horários entram no modelo
 * @param codigoCurriculo          código da grade (37203 / 372)
 * @param excluidas                códigos a ignorar no próximo semestre (filtro do usuário)
 * @param orcamentoMensalMax       teto de mensalidade estimada (R$); {@code null} = sem limite
 */
public record PlanoRequest(
        List<String> concluidas,
        @Min(1) @Max(15) Integer maxDisciplinasPorPeriodo,
        Boolean incluirOptativas,
        Boolean considerarHorarios,
        String codigoCurriculo,
        List<String> excluidas,
        @DecimalMin("0.0") Double orcamentoMensalMax
) {
    public PlanoRequest {
        concluidas = concluidas == null ? List.of() : concluidas;
        excluidas = excluidas == null ? List.of() : excluidas;
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

    /** {@code true} quando o usuário informou um teto de mensalidade. */
    public boolean temOrcamentoMensal() {
        return orcamentoMensalMax != null && orcamentoMensalMax >= 0;
    }
}
