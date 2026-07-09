package com.pucminas.gradeinteligente.ingestion;

import java.text.Normalizer;

/** Normaliza nomes de disciplinas para comparação robusta (sem acento/caixa/pontuação). */
public final class Normalizador {

    private Normalizador() {
    }

    public static String normalizar(String texto) {
        if (texto == null) return "";
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return semAcento.toUpperCase()
                .replaceAll("[^A-Z0-9]+", " ")
                .trim();
    }
}
