package com.pucminas.gradeinteligente.dto;

/** Resumo de um currículo disponível para seleção na UI. */
public record CurriculoResumoDTO(
        String codigoCurriculo,
        String rotulo,
        String situacao,
        String instituicao,
        int quantidadeDisciplinas,
        int cargaHorariaTotal
) {}
