package com.pucminas.gradeinteligente.service;

import com.pucminas.gradeinteligente.domain.Curriculo;
import com.pucminas.gradeinteligente.domain.Disciplina;
import com.pucminas.gradeinteligente.dto.DisciplinaPlanejadaDTO;
import com.pucminas.gradeinteligente.dto.PeriodoDTO;
import com.pucminas.gradeinteligente.dto.PlanoRequest;
import com.pucminas.gradeinteligente.dto.PlanoResponse;
import com.pucminas.gradeinteligente.repository.CurriculoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PlanejadorRotaTest {

    @Autowired
    PlanejadorService planejador;

    @Autowired
    CurriculoRepository repository;

    @Test
    void rotaRespeitaPreCoreqCapacidadeENaoAtrasaInicio() {
        PlanoResponse plano = planejador.planejar(new PlanoRequest(
                List.of(), 6, true, false, "37203", List.of(), null));

        assertEquals("OK", plano.status());
        assertTrue(plano.totalPeriodos() >= 10);
        assertTrue(plano.avisos().stream().noneMatch(a -> a.startsWith("Inconsistência")));

        Curriculo curriculo = repository.getCurriculo("37203");
        Map<String, Disciplina> porCodigo = curriculo.indexadoPorCodigo();
        Map<String, Integer> periodo = periodoPorCodigo(plano);

        for (PeriodoDTO p : plano.periodos()) {
            assertTrue(p.quantidade() <= 6, "período " + p.numero() + " estourou capacidade");
        }

        for (Disciplina d : curriculo.disciplinas()) {
            Integer t = periodo.get(d.codigo());
            if (t == null) continue;
            for (String pre : d.preRequisitos()) {
                if (!porCodigo.containsKey(pre)) continue;
                Integer tp = periodo.get(pre);
                assertTrue(tp != null && tp < t, d.nome() + " sem pré " + pre + " antes");
            }
            for (String co : d.coRequisitos()) {
                if (!porCodigo.containsKey(co)) continue;
                Integer tc = periodo.get(co);
                assertEquals(t, tc, d.nome() + " fora do período do co-req " + co);
            }
        }

        assertEquals(1, periodo.get("60422"), "AED I deveria abrir a rota");
        assertEquals(1, periodo.get("57384"), "Cálculo I deveria abrir a rota");
        assertEquals(periodo.get("60422"), periodo.get("60423"), "TI Web junto de AED I");
        assertEquals(periodo.get("63317"), periodo.get("60423"), "TI Web junto de Interfaces");
        assertTrue(periodo.get("54810") > periodo.get("57384"), "Cálculo II depois de Cálculo I");
        assertEquals(periodo.get("60427"), periodo.get("60428"), "Lab PM no mesmo período de PM");
    }

    @Test
    void primeiroPeriodoComHorarioNaoTemChoque() {
        PlanoResponse plano = planejador.planejar(new PlanoRequest(
                List.of(), 6, true, true, "37203", List.of(), null));

        assertEquals("OK", plano.status());
        assertFalse(plano.periodos().isEmpty());
        List<DisciplinaPlanejadaDTO> p1 = plano.periodos().getFirst().disciplinas();
        assertTrue(p1.stream().anyMatch(d -> "57384".equals(d.codigo())), "Cálculo I deve abrir a rota");

        List<DisciplinaPlanejadaDTO> comGrade = p1.stream()
                .filter(d -> d.turma() != null && d.horarios() != null && !d.horarios().isEmpty())
                .toList();
        assertFalse(comGrade.isEmpty(), "1º período precisa de turmas na grade");
        for (int i = 0; i < comGrade.size(); i++) {
            for (int j = i + 1; j < comGrade.size(); j++) {
                assertFalse(conflita(comGrade.get(i), comGrade.get(j)),
                        comGrade.get(i).nome() + " choca com " + comGrade.get(j).nome());
            }
        }
    }

    @Test
    void filtroRemoveDependentesDoPreRequisito() {
        PlanoResponse plano = planejador.planejar(new PlanoRequest(
                List.of(), 6, true, false, "37203", List.of("57384"), null));

        Map<String, Integer> periodo = periodoPorCodigo(plano);
        assertFalse(periodo.containsKey("57384"), "Cálculo I filtrado");
        assertFalse(periodo.containsKey("54810"), "Cálculo II depende de Cálculo I");
        assertFalse(periodo.containsKey("60441"), "Estatística depende de Cálculo I");
        assertTrue(plano.avisos().stream().anyMatch(a -> a.contains("dependem de matéria filtrada")));
    }

    private static boolean conflita(DisciplinaPlanejadaDTO a, DisciplinaPlanejadaDTO b) {
        for (var h1 : a.horarios()) {
            for (var h2 : b.horarios()) {
                if (h1.conflitaCom(h2)) return true;
            }
        }
        return false;
    }

    private static Map<String, Integer> periodoPorCodigo(PlanoResponse plano) {
        Map<String, Integer> mapa = new HashMap<>();
        for (PeriodoDTO p : plano.periodos()) {
            for (DisciplinaPlanejadaDTO d : p.disciplinas()) {
                mapa.put(d.codigo(), p.numero());
            }
        }
        return mapa;
    }
}
