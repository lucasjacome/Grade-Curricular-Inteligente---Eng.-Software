package com.pucminas.gradeinteligente.controller;

import com.pucminas.gradeinteligente.domain.Curriculo;
import com.pucminas.gradeinteligente.dto.GrafoDTO;
import com.pucminas.gradeinteligente.repository.CurriculoRepository;
import com.pucminas.gradeinteligente.service.GrafoService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/curriculo")
    public Curriculo curriculo() {
        return repository.getCurriculo();
    }

    @GetMapping("/grafo")
    public GrafoDTO grafo() {
        return grafoService.montarVisualizacao();
    }
}
