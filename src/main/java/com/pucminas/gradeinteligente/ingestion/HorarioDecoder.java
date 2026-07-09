package com.pucminas.gradeinteligente.ingestion;

import com.pucminas.gradeinteligente.domain.Horario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodifica a string de horários do SGA em encontros semanais.
 *
 * <p>Cada horário é um grupo de 4 caracteres <b>[tipo][bloco][dia][linha]</b>:
 * <ul>
 *   <li>tipo: 1 = teórica, 2 = prática;</li>
 *   <li>bloco: 1 = manhã, 2 = tarde, 3 = noite;</li>
 *   <li>dia: 2=Seg, 3=Ter, 4=Qua, 5=Qui, 6=Sex, 7=Sáb;</li>
 *   <li>linha: posição do slot dentro do bloco (1..N).</li>
 * </ul>
 * O nome da célula na grade é {@code img + bloco + dia + linha}.
 */
public final class HorarioDecoder {

    private static final Map<String, String> DIAS = Map.of(
            "2", "SEG", "3", "TER", "4", "QUA", "5", "QUI", "6", "SEX", "7", "SAB");

    // Início e fim (em minutos) de cada slot, por bloco (índice 0 = linha 1).
    private static final int[][] INICIO = {
            {},
            {420, 470, 530, 580, 640, 690, 740},                 // bloco 1 (manhã)
            {810, 860, 920, 970, 1030, 1080},                    // bloco 2 (tarde)
            {1140, 1190, 1250, 1300},                            // bloco 3 (noite)
    };
    private static final int[][] FIM = {
            {},
            {460, 510, 570, 620, 680, 730, 780},
            {850, 900, 960, 1010, 1070, 1120},
            {1180, 1230, 1290, 1340},
    };

    private HorarioDecoder() {
    }

    public static List<Horario> decodificar(String codigos) {
        if (codigos == null || codigos.isBlank() || codigos.length() % 4 != 0) {
            return List.of();
        }

        // dia -> lista de [inicio, fim] em minutos
        Map<String, List<int[]>> porDia = new LinkedHashMap<>();
        for (int i = 0; i + 4 <= codigos.length(); i += 4) {
            int bloco = codigos.charAt(i + 1) - '0';
            String diaCod = String.valueOf(codigos.charAt(i + 2));
            int linha = codigos.charAt(i + 3) - '0';
            String dia = DIAS.get(diaCod);
            if (dia == null || bloco < 1 || bloco > 3 || linha < 1 || linha > INICIO[bloco].length) {
                continue;
            }
            int ini = INICIO[bloco][linha - 1];
            int fim = FIM[bloco][linha - 1];
            porDia.computeIfAbsent(dia, k -> new ArrayList<>()).add(new int[]{ini, fim});
        }

        List<Horario> resultado = new ArrayList<>();
        for (Map.Entry<String, List<int[]>> e : porDia.entrySet()) {
            for (int[] intervalo : mesclar(e.getValue())) {
                resultado.add(new Horario(e.getKey(), paraHHmm(intervalo[0]), paraHHmm(intervalo[1])));
            }
        }
        return resultado;
    }

    /** Une slots consecutivos (intervalo entre eles de até 15 min). */
    private static List<int[]> mesclar(List<int[]> intervalos) {
        intervalos.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] atual : intervalos) {
            if (!merged.isEmpty() && atual[0] <= merged.get(merged.size() - 1)[1] + 15) {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], atual[1]);
            } else {
                merged.add(new int[]{atual[0], atual[1]});
            }
        }
        return merged;
    }

    private static String paraHHmm(int minutos) {
        return String.format("%02d:%02d", minutos / 60, minutos % 60);
    }
}
