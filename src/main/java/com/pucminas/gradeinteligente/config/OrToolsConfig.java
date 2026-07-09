package com.pucminas.gradeinteligente.config;

import com.google.ortools.Loader;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/** Carrega as bibliotecas nativas do OR-Tools uma única vez na inicialização. */
@Configuration
public class OrToolsConfig {

    @PostConstruct
    public void carregarNativo() {
        Loader.loadNativeLibraries();
    }
}
