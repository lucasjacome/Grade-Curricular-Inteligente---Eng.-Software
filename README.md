# Grade Curricular Inteligente

Planejador da grade de **Engenharia de Software (PUC Minas — Campus Lourdes)**.
Monta a **rota ótima até a formatura**, a **grade do próximo semestre** (sem choque
de horário) e estima a **mensalidade** do 1º período.

Há duas grades:

| Código | Situação |
|---|---|
| `37203` | 3ª grade (em extinção) — padrão |
| `372` | 4ª grade (SGA / vigente) |

## O que a interface faz

- Marcar disciplinas já feitas (com bloqueio de pré/co-requisito)
- Filtrar matérias que não devem entrar no plano
- Limitar disciplinas por período e teto de mensalidade
- **Rota ótima:** todos os períodos até formar
- **Próximo semestre:** maior conjunto viável agora, com troca no mesmo horário
- Mapa de dependências por período oficial (arrastar, zoom no scroll, foco ao passar o mouse)
- Disciplinas restantes, com filtro “só com requisitos cumpridos”
- Grade semanal e custo estimado só do 1º semestre

Abra [http://localhost:8080](http://localhost:8080) depois de subir o servidor.

## Como executar

Pré-requisitos: **JDK 21** e **Maven**.

```bash
mvn spring-boot:run
```

Testes:

```bash
mvn test
```

## Arquitetura

O currículo é um **DAG**:

- aresta de **pré-requisito** `A → B`: B só depois de A
- **co-requisito**: no mesmo período (se os dois ainda faltam)

```
src/main/java/...     domínio, ingestão, serviços, API
src/main/resources/   currículos, oferta e UI
src/test/             testes
dados/oficiais/       PDF da grade (referência)
dados/brutos/         HTML exportado do SGA
```

Dois planejadores:

1. **Rota completa** — minimiza períodos e antecipa gargalos. Só o 1º período
   precisa caber na oferta atual (sem choque).
2. **Próximo semestre** — escolhe o maior conjunto que o aluno já pode cursar
   agora, com turmas sem conflito.

A mensalidade estimada é `cargaHorariaCobranca × valorHoraMensal` (parcelas do SGA,
sem matrícula no teto mensal).

## Stack

- Java 21, Maven, Spring Boot 3.3
- [JGraphT](https://jgrapht.org/) — gargalos e cadeias
- [Google OR-Tools](https://developers.google.com/optimization) (CP-SAT)
- [jsoup](https://jsoup.org/) — HTML da oferta do SGA

## API

### `GET /api/curriculos`

Lista as grades disponíveis.

### `GET /api/curriculo?codigo=37203`

Currículo completo (disciplinas, custos). Sem `codigo`, usa a grade padrão.

### `GET /api/grafo?codigo=37203`

Nós e arestas (pré/co) com métricas de gargalo.

### `GET /api/oferta?codigo=37203`

Turmas da oferta atual casadas com as disciplinas do currículo.

### `POST /api/plano`

Rota ótima até formar.

### `POST /api/plano/proximo-semestre`

Só o próximo semestre.

Corpo comum:

```json
{
  "concluidas": ["60422", "57384"],
  "excluidas": [],
  "maxDisciplinasPorPeriodo": 6,
  "incluirOptativas": true,
  "considerarHorarios": true,
  "codigoCurriculo": "37203",
  "orcamentoMensalMax": null
}
```

`turma` e `horarios` vêm só no 1º período, quando há casamento com a oferta.

## Dados

| Arquivo | Função |
|---|---|
| [`curriculum-37203.json`](src/main/resources/curriculum-37203.json) | 3ª grade |
| [`curriculum-372.json`](src/main/resources/curriculum-372.json) | 4ª grade |
| [`oferta.json`](src/main/resources/oferta.json) | Turmas 2026/2 |
| [`dados/oficiais/37203.pdf`](dados/oficiais/37203.pdf) | PDF oficial da grade antiga |
| [`dados/brutos/`](dados/brutos/) | HTML exportado do SGA |

Campos principais de cada disciplina: `codigo`, `nome`, `cargaHoraria`,
`cargaHorariaCobranca` (opcional), `periodoSugerido`, `preRequisitos`,
`coRequisitos`, `cargaHorariaMinima`, `optativa`, `semipresencial`.

### Atualizar a oferta

1. No SGA, abra **Solicitação de Plano de Estudo** e salve a página (Ctrl+S).
2. Copie `pgAln_PmTurmas.html` para `dados/brutos/`.
3. Rode:

   ```bash
   mvn test -Dtest=OfertaParserTest
   ```

Os horários do SGA vêm em códigos de 4 dígitos `[tipo][bloco][dia][linha]`.
O `HorarioDecoder` vira `{dia, início, fim}`. O `NameMatcher` casa por nome
(exato ou TF-IDF) e não mistura *Cálculo I* com *Cálculo II*.

Sem `oferta.json`, o plano acadêmico continua; só não escolhe turma/horário.
