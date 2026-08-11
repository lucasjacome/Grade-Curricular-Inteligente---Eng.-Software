package com.pucminas.gradeinteligente.controller;

import com.pucminas.gradeinteligente.domain.Curriculo;
import com.pucminas.gradeinteligente.domain.Disciplina;
import com.pucminas.gradeinteligente.dto.CurriculoResumoDTO;
import com.pucminas.gradeinteligente.dto.GrafoDTO;
import com.pucminas.gradeinteligente.dto.OfertaHorariosDTO;
import com.pucminas.gradeinteligente.repository.CurriculoRepository;
import com.pucminas.gradeinteligente.repository.OfertaRepository;
import com.pucminas.gradeinteligente.service.GrafoService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class CurriculoController {

    private final CurriculoRepository repository;
    private final GrafoService grafoService;
    private final OfertaRepository ofertaRepository;

    public CurriculoController(CurriculoRepository repository,
                               GrafoService grafoService,
                               OfertaRepository ofertaRepository) {
        this.repository = repository;
        this.grafoService = grafoService;
        this.ofertaRepository = ofertaRepository;
    }

    @GetMapping("/curriculos")
    public List<CurriculoResumoDTO> listar() {
        return repository.listar().stream()
                .map(c -> new CurriculoResumoDTO(
                        c.codigoCurriculo(),
                        c.rotulo() != null ? c.rotulo() : ("Currículo " + c.codigoCurriculo()),
                        c.situacao(),
                        c.instituicao(),
                        c.disciplinas().size(),
                        c.cargaHorariaTotal()))
                .toList();
    }

    @GetMapping("/curriculo")
    public Curriculo curriculo(
            @RequestParam(value = "codigo", required = false) String codigo) {
        return repository.getCurriculo(codigo);
    }

    @GetMapping("/grafo")
    public GrafoDTO grafo(
            @RequestParam(value = "codigo", required = false) String codigo) {
        return grafoService.montarVisualizacao(codigo);
    }

    /** Turmas e horários da oferta atual, casados com as disciplinas do currículo. */
    @GetMapping("/oferta")
    public OfertaHorariosDTO oferta(
            @RequestParam(value = "codigo", required = false) String codigo) {
        if (!ofertaRepository.disponivel()) {
            return new OfertaHorariosDTO(null, Map.of());
        }
        Curriculo curriculo = repository.getCurriculo(codigo);
        Map<String, OfertaHorariosDTO.DisciplinaOfertadaDTO> mapa = new LinkedHashMap<>();
        for (Disciplina d : curriculo.disciplinas()) {
            OfertaRepository.Casamento m = ofertaRepository.casar(d.nome());
            if (m == null || m.turmas().isEmpty()) continue;
            List<OfertaHorariosDTO.TurmaOfertadaDTO> turmas = m.turmas().stream()
                    .map(t -> new OfertaHorariosDTO.TurmaOfertadaDTO(t.codigo(), t.horarios()))
                    .toList();
            mapa.put(d.codigo(), new OfertaHorariosDTO.DisciplinaOfertadaDTO(
                    m.nomeOferta(), m.exato(), turmas));
        }
        return new OfertaHorariosDTO(ofertaRepository.semestre(), mapa);
    }
}
