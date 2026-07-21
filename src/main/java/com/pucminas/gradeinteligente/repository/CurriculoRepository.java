package com.pucminas.gradeinteligente.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pucminas.gradeinteligente.domain.Curriculo;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carrega um ou mais currículos JSON na inicialização e permite selecionar por código.
 */
@Repository
public class CurriculoRepository {

    private static final Logger log = LoggerFactory.getLogger(CurriculoRepository.class);

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final String curriculosCsv;
    private final String codigoPadrao;

    private final Map<String, Curriculo> porCodigo = new LinkedHashMap<>();

    public CurriculoRepository(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${grade.curriculos:classpath:curriculum-37203.json,classpath:curriculum-372.json}")
            String curriculosCsv,
            @Value("${grade.curriculo-padrao:37203}") String codigoPadrao) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.curriculosCsv = curriculosCsv;
        this.codigoPadrao = codigoPadrao;
    }

    @PostConstruct
    void carregar() throws IOException {
        porCodigo.clear();
        for (String caminho : curriculosCsv.split(",")) {
            String loc = caminho.trim();
            if (loc.isEmpty()) continue;
            Resource resource = resourceLoader.getResource(loc);
            if (!resource.exists()) {
                throw new IllegalStateException("Arquivo de currículo não encontrado: " + loc);
            }
            try (InputStream in = resource.getInputStream()) {
                Curriculo c = objectMapper.readValue(in, Curriculo.class);
                if (c.codigoCurriculo() == null || c.codigoCurriculo().isBlank()) {
                    throw new IllegalStateException("Currículo sem codigoCurriculo: " + loc);
                }
                porCodigo.put(c.codigoCurriculo(), c);
                log.info("Currículo carregado: {} ({}) — {} disciplinas",
                        c.codigoCurriculo(),
                        c.rotulo() != null ? c.rotulo() : c.situacao(),
                        c.disciplinas().size());
            }
        }
        if (porCodigo.isEmpty()) {
            throw new IllegalStateException("Nenhum currículo carregado.");
        }
        if (!porCodigo.containsKey(codigoPadrao)) {
            throw new IllegalStateException(
                    "Currículo padrão '" + codigoPadrao + "' não encontrado. Disponíveis: " + porCodigo.keySet());
        }
        log.info("Currículos disponíveis: {}", porCodigo.keySet());
    }

    /** Currículo padrão (compatibilidade com código antigo). */
    public Curriculo getCurriculo() {
        return getCurriculo(codigoPadrao);
    }

    public Curriculo getCurriculo(String codigo) {
        String chave = (codigo == null || codigo.isBlank()) ? codigoPadrao : codigo.trim();
        Curriculo c = porCodigo.get(chave);
        if (c == null) {
            throw new IllegalArgumentException("Currículo não encontrado: " + chave
                    + ". Disponíveis: " + porCodigo.keySet());
        }
        return c;
    }

    public String getCodigoPadrao() {
        return codigoPadrao;
    }

    public List<Curriculo> listar() {
        return new ArrayList<>(porCodigo.values());
    }

    public boolean existe(String codigo) {
        return codigo != null && porCodigo.containsKey(codigo.trim());
    }
}
