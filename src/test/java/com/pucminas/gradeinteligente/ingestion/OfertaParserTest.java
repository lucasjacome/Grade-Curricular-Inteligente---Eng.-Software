package com.pucminas.gradeinteligente.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pucminas.gradeinteligente.domain.Curriculo;
import com.pucminas.gradeinteligente.domain.Disciplina;
import com.pucminas.gradeinteligente.domain.Oferta;
import com.pucminas.gradeinteligente.domain.Turma;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Converte o HTML da oferta em oferta.json e reporta o casamento com o currículo.
 * Executar com: mvn test -Dtest=OfertaParserTest
 */
class OfertaParserTest {

    @Test
    void gerarOfertaJson() throws Exception {
        File entrada = new File("dados/brutos/pgAln_PmTurmas.html");
        if (!entrada.exists()) {
            System.out.println("[oferta] arquivo não encontrado: " + entrada.getAbsolutePath());
            return;
        }

        Oferta oferta = new OfertaParser().parse(entrada, "2026/2");

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        File saida = new File("src/main/resources/oferta.json");
        mapper.writeValue(saida, oferta);

        int totalTurmas = oferta.disciplinas().stream().mapToInt(d -> d.turmas().size()).sum();
        System.out.printf("%n[oferta] %d disciplinas, %d turmas -> %s%n",
                oferta.disciplinas().size(), totalTurmas, saida.getAbsolutePath());

        // Casamento com o currículo por nome normalizado
        Curriculo curriculo = mapper.readValue(
                getClass().getResourceAsStream("/curriculum-37203.json"), Curriculo.class);
        Map<String, Disciplina> porNome = new LinkedHashMap<>();
        for (Disciplina d : curriculo.disciplinas()) {
            porNome.put(Normalizador.normalizar(d.nome()), d);
        }

        int casados = 0;
        StringBuilder naoCasados = new StringBuilder();
        for (Oferta.OfertaDisciplina od : oferta.disciplinas()) {
            String chave = Normalizador.normalizar(od.nome());
            if (porNome.containsKey(chave)) {
                casados++;
            } else {
                naoCasados.append("   - ").append(od.nome()).append('\n');
            }
        }
        System.out.printf("[oferta] casados com o currículo: %d/%d%n",
                casados, oferta.disciplinas().size());
        System.out.println("[oferta] NÃO casados (oferta sem correspondência no currículo 37203):");
        System.out.println(naoCasados);

        // Amostra de horários decodificados
        System.out.println("[oferta] amostra:");
        oferta.disciplinas().stream().limit(6).forEach(d -> {
            String turmas = d.turmas().stream()
                    .map(t -> t.codigo() + " " + t.horarios().stream()
                            .map(h -> h.dia() + " " + h.inicio() + "-" + h.fim())
                            .collect(Collectors.joining("; ")))
                    .collect(Collectors.joining(" | "));
            System.out.printf("   %s => %s%n", d.nome(), turmas);
        });

        assertFalse(oferta.disciplinas().isEmpty(), "deveria extrair ao menos uma disciplina");
    }
}
