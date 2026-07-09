package com.pucminas.gradeinteligente.ingestion;

import com.pucminas.gradeinteligente.domain.Horario;
import com.pucminas.gradeinteligente.domain.Oferta;
import com.pucminas.gradeinteligente.domain.Turma;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrai a oferta de turmas (disciplina → turmas → horários) da página
 * "Solicitação de Plano de Estudo" salva do SGA (frame pgAln_PmTurmas.html).
 */
public class OfertaParser {

    private static final Pattern MOUSE_OVER =
            Pattern.compile("mouseOver\\('(\\d+)','([^']*)','([SD])'\\)");
    private static final Pattern COD_TURMA = Pattern.compile("Turma\\s+([\\d.]+)");

    public Oferta parse(File arquivo, String semestre) throws IOException {
        Document doc = Jsoup.parse(arquivo, "UTF-8");
        List<Oferta.OfertaDisciplina> disciplinas = new ArrayList<>();

        for (Element span : doc.select("span.smc-sgagrad-disciplina")) {
            String nome = span.text().trim();
            Element td = span.closest("td");
            if (td == null) continue;

            List<Turma> turmas = new ArrayList<>();
            for (Element a : td.select("a[onmouseover]")) {
                Turma turma = extrairTurma(a);
                if (turma != null) turmas.add(turma);
            }
            if (!turmas.isEmpty()) {
                disciplinas.add(new Oferta.OfertaDisciplina(nome, turmas));
            }
        }
        return new Oferta("SGA PUC Minas - pgAln_PmTurmas.html", semestre, disciplinas);
    }

    private Turma extrairTurma(Element a) {
        Matcher m = MOUSE_OVER.matcher(a.attr("onmouseover"));
        if (!m.find()) return null;

        String codigoRaw = m.group(1);
        String codigos = m.group(2);
        String tipoGrade = m.group(3);

        String textoLinha = a.parent() != null ? a.parent().text() : a.text();
        boolean sincrona = textoLinha.contains("Síncrona") || textoLinha.contains("Sincrona");

        String codigoTurma = formatarCodigo(textoLinha, codigoRaw);
        List<Horario> horarios = "S".equals(tipoGrade)
                ? HorarioDecoder.decodificar(codigos)
                : List.of();

        return new Turma(codigoTurma, sincrona, horarios);
    }

    private String formatarCodigo(String textoLinha, String codigoRaw) {
        Matcher m = COD_TURMA.matcher(textoLinha);
        if (m.find()) return m.group(1);
        if (codigoRaw.length() == 7) {
            return codigoRaw.substring(0, 4) + "." + codigoRaw.charAt(4) + "." + codigoRaw.substring(5);
        }
        return codigoRaw;
    }
}
