# Dados de origem

Não entram no runtime. O app lê os JSON em `src/main/resources/`.

```
dados/
  oficiais/     PDF da grade (referência)
  brutos/       HTML exportado do SGA
```

## Atualizar a oferta

1. No SGA, abra **Solicitação de Plano de Estudo** e salve a página (Ctrl+S).
2. Copie `pgAln_PmTurmas.html` para `dados/brutos/`.
3. Rode:

```bash
mvn test -Dtest=OfertaParserTest
```

Isso regenera `src/main/resources/oferta.json`.
