package com.pucminas.gradeinteligente.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Um encontro semanal da disciplina (ex.: Segunda, 19:00–20:40).
 *
 * @param dia    dia da semana (SEG, TER, QUA, QUI, SEX, SAB)
 * @param inicio horário de início no formato HH:mm
 * @param fim    horário de término no formato HH:mm
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Horario(String dia, String inicio, String fim) {

    public int inicioEmMinutos() {
        return paraMinutos(inicio);
    }

    public int fimEmMinutos() {
        return paraMinutos(fim);
    }

    /** Há sobreposição de horário no mesmo dia? */
    public boolean conflitaCom(Horario outro) {
        if (dia == null || outro.dia == null) return false;
        if (!dia.equalsIgnoreCase(outro.dia)) return false;
        return inicioEmMinutos() < outro.fimEmMinutos()
                && outro.inicioEmMinutos() < fimEmMinutos();
    }

    private static int paraMinutos(String hhmm) {
        if (hhmm == null || !hhmm.contains(":")) return 0;
        String[] partes = hhmm.split(":");
        return Integer.parseInt(partes[0].trim()) * 60 + Integer.parseInt(partes[1].trim());
    }
}
