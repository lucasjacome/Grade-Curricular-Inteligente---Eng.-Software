package com.pucminas.gradeinteligente.dto;

import com.pucminas.gradeinteligente.domain.Horario;

import java.util.List;
import java.util.Map;

/** Oferta do semestre atual, indexada pelo código da disciplina do currículo. */
public record OfertaHorariosDTO(
        String semestre,
        Map<String, DisciplinaOfertadaDTO> disciplinas
) {
    public record DisciplinaOfertadaDTO(
            String nomeOferta,
            boolean exato,
            List<TurmaOfertadaDTO> turmas
    ) {
    }

    public record TurmaOfertadaDTO(
            String codigo,
            List<Horario> horarios
    ) {
    }
}
