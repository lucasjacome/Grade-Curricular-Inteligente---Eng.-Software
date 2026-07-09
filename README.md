# Grade Curricular Inteligente

Analisa a grade curricular do curso de **Engenharia de Software (PUC Minas, currículo 37203)**,
entende a cadeia de pré-requisitos/co-requisitos e calcula a **rota ótima de matrícula** para
**minimizar o número de períodos até a formatura**, priorizando os gargalos (disciplinas que
destravam o maior número de caminhos na árvore curricular).

Além disso, ele lê a **oferta de turmas do próximo semestre** (exportada do SGA) e, para o
1º período do plano, **escolhe automaticamente turmas sem choque de horário**, exibindo uma
**grade semanal** pronta para a matrícula.

## Como funciona (visão de arquitetura)

O currículo é modelado como um **grafo acíclico dirigido (DAG)**:

- **Nós** = disciplinas.
- **Arestas de pré-requisito** (`A → B`): B só pode ser cursada depois de A.
- **Co-requisitos**: podem ser cursados no mesmo período ou antes.

A partir daí, dois algoritmos trabalham juntos:

1. **Análise de gargalos (JGraphT):** ordenação topológica + cálculo de quantas disciplinas
   cada uma destrava (descendentes) e do tamanho da maior cadeia de dependências (caminho crítico).
2. **Otimização exata (OR-Tools CP-SAT):** resolve um modelo de escalonamento com precedência
   e capacidade (máximo de disciplinas por período), com objetivo lexicográfico:
   1. minimizar o número de períodos;
   2. como desempate, antecipar os gargalos.
3. **Escolha de turma sem conflito (CP-SAT):** para as disciplinas que caem no **1º período**,
   o solver seleciona uma turma da oferta de forma que não haja **choque de horário** entre elas.

### Camadas

```
domain/      → Disciplina, Curriculo, Horario, Turma, Oferta (modelo imutável)
ingestion/   → OfertaParser (jsoup) + HorarioDecoder + NameMatcher (casamento por nome)
repository/  → carrega curriculum.json e oferta.json
service/     → GrafoService (JGraphT) + PlanejadorService (CP-SAT)
controller/  → REST API
resources/static/ → UI web (Cytoscape.js) para o grafo, o plano e a grade semanal
```

## Stack

- Java 21, Maven, Spring Boot 3
- [JGraphT](https://jgrapht.org/) — algoritmos de grafo
- [Google OR-Tools](https://developers.google.com/optimization) (CP-SAT) — otimização exata
- [jsoup](https://jsoup.org/) — parsing do HTML da oferta de turmas do SGA
- Cytoscape.js — visualização do grafo no front-end

## Como executar

Pré-requisitos: **JDK 21** e **Maven** instalados.

```bash
mvn spring-boot:run
```

Depois abra: <http://localhost:8080>

Na interface você pode:

- marcar as disciplinas já concluídas;
- definir o máximo de disciplinas por período;
- clicar em **Calcular rota ótima** e ver o plano período a período, com a explicação
  do porquê de cada escolha;
- ver a **grade semanal** (Seg–Sex) do próximo semestre, com a turma escolhida para cada
  disciplina do 1º período sem choque de horário.

## API

### `GET /api/curriculo`
Retorna o currículo completo.

### `GET /api/grafo`
Retorna nós e arestas (pré/co-requisito) com métricas de gargalo, para visualização.

### `POST /api/plano`
Calcula o plano ótimo.

Requisição:

```json
{
  "concluidas": ["60422", "57384"],
  "maxDisciplinasPorPeriodo": 6,
  "incluirOptativas": true,
  "considerarHorarios": true
}
```

Resposta (resumo):

```json
{
  "status": "OK",
  "totalPeriodos": 8,
  "totalDisciplinasRestantes": 55,
  "cargaHorariaRestante": 3640,
  "otimoComprovado": true,
  "periodos": [
    {
      "numero": 1,
      "quantidade": 6,
      "cargaHorariaTotal": 400,
      "disciplinas": [
        { "codigo": "60427", "nome": "Programação Modular", "prioridade": 12,
          "destrava": 8, "motivo": "Gargalo: destrava 8 disciplina(s)...",
          "turma": "7527.1.00",
          "horarios": [ { "dia": "TER", "inicio": "19:00", "fim": "20:30" } ] }
      ]
    }
  ],
  "avisos": []
}
```

> `turma` e `horarios` só são preenchidos para as disciplinas do **1º período** que casaram
> com a oferta do semestre.

## Dados de entrada

A fonte da verdade é [`src/main/resources/curriculum.json`](src/main/resources/curriculum.json),
extraído do PDF oficial do currículo. Cada disciplina tem:

| Campo | Descrição |
|---|---|
| `codigo` | Código único da disciplina |
| `nome` | Nome |
| `cargaHoraria` | Carga horária (h) |
| `periodoSugerido` | Período sugerido na grade oficial |
| `preRequisitos` | Códigos de pré-requisitos (PRÉ) |
| `coRequisitos` | Códigos de co-requisitos (CO) |
| `cargaHorariaMinima` | CH acumulada mínima exigida (ex.: 1.800h) |
| `optativa` | Se é optativa genérica |
| `semipresencial` | Se é semipresencial |

## Oferta de turmas e horários

A oferta do próximo semestre fica em [`src/main/resources/oferta.json`](src/main/resources/oferta.json)
(disciplina → turmas → horários). Ela é gerada a partir da página **"Solicitação de Plano de
Estudo"** do SGA:

1. No SGA, abra a tela de plano de estudo e salve a página com **Ctrl + S** (isso gera a pasta
   `..._files/` com os frames). Copie o arquivo `pgAln_PmTurmas.html` para `dados-brutos/`.
2. Rode o parser, que decodifica os horários e escreve o `oferta.json`:

   ```bash
   mvn test -Dtest=OfertaParserTest
   ```

### Como os horários são decodificados

Cada turma do SGA carrega uma string de códigos de **4 dígitos** `[tipo][bloco][dia][linha]`:

- **tipo**: `1` = teórica, `2` = prática;
- **bloco**: `1` = manhã, `2` = tarde, `3` = noite;
- **dia**: `2`=Seg, `3`=Ter, `4`=Qua, `5`=Qui, `6`=Sex, `7`=Sáb;
- **linha**: posição do slot dentro do bloco.

O `HorarioDecoder` converte esses códigos em encontros `{dia, início, fim}` (unindo slots
consecutivos), e o `NameMatcher` casa a disciplina do currículo com a oferta:

- **casamento exato** por nome normalizado (sem acento/caixa);
- **casamento aproximado** por similaridade **TF-IDF** (tokens raros pesam mais), com uma
  **trava de numeral** para nunca confundir níveis diferentes (ex.: *Cálculo I* × *Cálculo II*).
  Os casamentos aproximados aparecem nos `avisos` da resposta para conferência.

> Se o `oferta.json` não existir, o planejamento continua funcionando normalmente, apenas
> sem a seleção de turmas/horários.
