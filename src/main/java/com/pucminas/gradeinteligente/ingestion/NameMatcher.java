package com.pucminas.gradeinteligente.ingestion;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Casa nomes de disciplinas por similaridade usando cosseno ponderado por TF-IDF
 * sobre os tokens. O peso IDF faz com que tokens raros/distintivos (ex.:
 * "COMPUTABILIDADE") pesem mais do que comuns (ex.: "SOFTWARE"), evitando
 * falsos positivos entre disciplinas que só compartilham palavras genéricas.
 */
public class NameMatcher {

    private static final Set<String> STOPWORDS = Set.of(
            "DE", "DA", "DO", "DOS", "DAS", "E", "A", "O", "AS", "OS",
            "EM", "COM", "PARA", "NO", "NA");

    // Numerais de nível (romanos/arábicos) que DIFERENCIAM disciplinas (I x II).
    private static final Map<String, String> NIVEIS = Map.ofEntries(
            Map.entry("I", "1"), Map.entry("II", "2"), Map.entry("III", "3"),
            Map.entry("IV", "4"), Map.entry("V", "5"), Map.entry("VI", "6"),
            Map.entry("VII", "7"), Map.entry("VIII", "8"),
            Map.entry("1", "1"), Map.entry("2", "2"), Map.entry("3", "3"),
            Map.entry("4", "4"), Map.entry("5", "5"), Map.entry("6", "6"));

    private final List<String> candidatos;
    private final Map<String, Double> idf = new HashMap<>();

    public NameMatcher(List<String> nomesNormalizados) {
        this.candidatos = nomesNormalizados;
        int n = Math.max(1, candidatos.size());
        Map<String, Integer> df = new HashMap<>();
        for (String nome : candidatos) {
            for (String token : new HashSet<>(tokenizar(nome))) {
                df.merge(token, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            idf.put(e.getKey(), Math.log((double) (n + 1) / (e.getValue() + 1)) + 1.0);
        }
    }

    /** Melhor candidato com similaridade >= limite, ou null. */
    public Resultado melhor(String consultaNormalizada, double limite) {
        Set<String> q = new HashSet<>(tokenizar(consultaNormalizada));
        if (q.isEmpty()) return null;

        String melhor = null;
        double melhorScore = 0;
        for (String cand : candidatos) {
            double s = similaridade(q, new HashSet<>(tokenizar(cand)));
            if (s > melhorScore) {
                melhorScore = s;
                melhor = cand;
            }
        }
        return (melhor != null && melhorScore >= limite) ? new Resultado(melhor, melhorScore) : null;
    }

    private double similaridade(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;

        // Trava de nível: "Cálculo I" nunca casa com "Cálculo II".
        Set<String> nivelA = niveis(a);
        Set<String> nivelB = niveis(b);
        if (!nivelA.isEmpty() && !nivelB.isEmpty() && java.util.Collections.disjoint(nivelA, nivelB)) {
            return 0;
        }

        double dot = 0, na = 0, nb = 0;
        Set<String> uniao = new HashSet<>(a);
        uniao.addAll(b);
        for (String t : uniao) {
            double w = idf.getOrDefault(t, 1.0);
            double va = a.contains(t) ? w : 0;
            double vb = b.contains(t) ? w : 0;
            dot += va * vb;
            na += va * va;
            nb += vb * vb;
        }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private Set<String> niveis(Set<String> tokens) {
        Set<String> r = new HashSet<>();
        for (String t : tokens) {
            String n = NIVEIS.get(t);
            if (n != null) r.add(n);
        }
        return r;
    }

    private List<String> tokenizar(String nomeNormalizado) {
        return Arrays.stream(nomeNormalizado.split(" "))
                .filter(t -> !t.isBlank() && !STOPWORDS.contains(t))
                .toList();
    }

    public record Resultado(String nomeNormalizado, double score) {
    }
}
