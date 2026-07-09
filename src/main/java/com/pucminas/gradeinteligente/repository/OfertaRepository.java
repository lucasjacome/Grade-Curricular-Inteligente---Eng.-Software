package com.pucminas.gradeinteligente.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pucminas.gradeinteligente.domain.Oferta;
import com.pucminas.gradeinteligente.domain.Turma;
import com.pucminas.gradeinteligente.ingestion.NameMatcher;
import com.pucminas.gradeinteligente.ingestion.Normalizador;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carrega a oferta de turmas do próximo semestre (oferta.json), se existir,
 * e casa disciplinas do currículo por nome — exato ou aproximado (TF-IDF).
 */
@Repository
public class OfertaRepository {

    /** Limite mínimo de similaridade para aceitar um casamento aproximado. */
    private static final double LIMITE_FUZZY = 0.70;

    private final ObjectMapper objectMapper;
    private final Resource ofertaResource;

    private Oferta oferta;
    private Map<String, List<Turma>> turmasPorNome = new LinkedHashMap<>();
    private Map<String, String> nomeExibicao = new LinkedHashMap<>();
    private NameMatcher matcher;

    public OfertaRepository(ObjectMapper objectMapper,
                            @Value("${grade.oferta-path:classpath:oferta.json}") Resource ofertaResource) {
        this.objectMapper = objectMapper;
        this.ofertaResource = ofertaResource;
    }

    @PostConstruct
    void carregar() {
        if (!ofertaResource.exists()) {
            return;
        }
        try {
            oferta = objectMapper.readValue(ofertaResource.getInputStream(), Oferta.class);
            Map<String, List<Turma>> mapa = new LinkedHashMap<>();
            for (Oferta.OfertaDisciplina d : oferta.disciplinas()) {
                String chave = Normalizador.normalizar(d.nome());
                mapa.computeIfAbsent(chave, k -> new ArrayList<>()).addAll(d.turmas());
                nomeExibicao.putIfAbsent(chave, d.nome());
            }
            this.turmasPorNome = mapa;
            this.matcher = new NameMatcher(new ArrayList<>(mapa.keySet()));
        } catch (Exception e) {
            this.oferta = null;
        }
    }

    public boolean disponivel() {
        return oferta != null && !turmasPorNome.isEmpty();
    }

    public String semestre() {
        return oferta != null ? oferta.semestre() : null;
    }

    /** Casa a disciplina do currículo com a oferta (exato e depois aproximado). */
    public Casamento casar(String nomeDisciplina) {
        if (!disponivel()) return null;
        String chave = Normalizador.normalizar(nomeDisciplina);

        List<Turma> exato = turmasPorNome.get(chave);
        if (exato != null) {
            return new Casamento(nomeExibicao.get(chave), exato, true, 1.0);
        }

        NameMatcher.Resultado r = matcher.melhor(chave, LIMITE_FUZZY);
        if (r != null) {
            return new Casamento(nomeExibicao.get(r.nomeNormalizado()),
                    turmasPorNome.get(r.nomeNormalizado()), false, r.score());
        }
        return null;
    }

    public record Casamento(String nomeOferta, List<Turma> turmas, boolean exato, double score) {
    }
}
