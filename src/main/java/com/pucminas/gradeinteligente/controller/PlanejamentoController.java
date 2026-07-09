package com.pucminas.gradeinteligente.controller;

import com.pucminas.gradeinteligente.dto.PlanoRequest;
import com.pucminas.gradeinteligente.dto.PlanoResponse;
import com.pucminas.gradeinteligente.service.PlanejadorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class PlanejamentoController {

    private final PlanejadorService planejadorService;

    public PlanejamentoController(PlanejadorService planejadorService) {
        this.planejadorService = planejadorService;
    }

    @PostMapping("/plano")
    public PlanoResponse planejar(@Valid @RequestBody PlanoRequest request) {
        return planejadorService.planejar(request);
    }
}
