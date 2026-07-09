package com.pucminas.gradeinteligente.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pucminas.gradeinteligente.domain.Curriculo;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;

/**
 * Carrega o currículo do arquivo JSON (fonte da verdade) na inicialização.
 */
@Repository
public class CurriculoRepository {

    private final ObjectMapper objectMapper;
    private final Resource curriculoResource;
    private Curriculo curriculo;

    public CurriculoRepository(ObjectMapper objectMapper,
                               @Value("${grade.curriculo-path:classpath:curriculum.json}") Resource curriculoResource) {
        this.objectMapper = objectMapper;
        this.curriculoResource = curriculoResource;
    }

    @PostConstruct
    void carregar() throws IOException {
        try (InputStream in = curriculoResource.getInputStream()) {
            this.curriculo = objectMapper.readValue(in, Curriculo.class);
        }
    }

    public Curriculo getCurriculo() {
        return curriculo;
    }
}
