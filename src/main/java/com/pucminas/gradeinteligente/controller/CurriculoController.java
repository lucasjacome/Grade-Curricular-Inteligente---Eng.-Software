package com.pucminas.gradeinteligente.controller;

import com.pucminas.gradeinteligente.domain.Curriculo;
import com.pucminas.gradeinteligente.dto.CurriculoResumoDTO;
import com.pucminas.gradeinteligente.dto.GrafoDTO;
import com.pucminas.gradeinteligente.repository.CurriculoRepository;
import com.pucminas.gradeinteligente.service.GrafoService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class CurriculoController {

    private final CurriculoRepository repository;
    private final GrafoService grafoService;

    public CurriculoController(CurriculoRepository repository, GrafoService grafoService) {
        this.repository = repository;
        this.grafoService = grafoService;
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
}
