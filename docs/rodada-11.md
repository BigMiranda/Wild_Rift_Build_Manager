# Rodada 11 — Versão online pública (somente leitura, sem treino e sem admin)

Prompt: `prompt-rodada-11-publica.md`, depois das Rodadas 8 a 10. Uma parte por commit, sem push, no `main`.
**Nada foi publicado:** esta rodada prepara a versão pública e o pacote. A hospedagem é decisão do usuário (Parte 3).

## Parte 1 — Perfil "público" do app ✅

### Como roda
- **Backend:** perfil do Spring `publico` (`--spring.profiles.active=publico`, ou `SPRING_PROFILES_ACTIVE=publico`),
  configurado em `application-publico.properties`. Lê o banco exportado (Parte 2) em `./data/publico/ornn-planner-publico.db`.
- **Frontend:** `npm run build:publico` (= `vite build --mode publico`, que lê `frontend/.env.publico` com `VITE_PUBLIC=1`).
  Em desenvolvimento: `vite --mode publico`.
- `.claude/launch.json` ganhou `backend-publico` (porta 8094, sobre uma **cópia** do banco em `data/publico-teste/`) e
  `frontend-publico` (porta 5178).
- **O app local não muda:** sem o perfil, `planner.public` é falso, o filtro e o CORS do público nem são criados, e os
  cálculos rodam direto, como antes.

### O que não existe no perfil público (de fato, não só escondido)
- **Lista de permitidos** (`publico/PublicApi`): sob `/api`, só os GETs de leitura e os cálculos leves. Qualquer outro
  método ou caminho responde **404** "Não existe na versão pública.", antes de chegar a um controller
  (`PublicApiFilter`).
- **Controllers que nem são criados** no público (`@Profile("!publico")`): `AdminController`, `CalculatorController`,
  `DiscoveryController`, `MatchController` e `OffenseController`. O `ReferenceSeeder` também não.
- **Sem escrita nenhuma:** o banco abre **somente leitura** (`open_mode=1` na URL do SQLite e `hikari.read-only=true`,
  que o sqlite-jdbc exige que concordem). Por isso:
  - o `SchemaManager` não migra: só confere que o banco é do esquema que o app lê (senão, recusa subir e pede uma nova
    exportação);
  - o `CampaignService` não devolve campanhas "rodando" à fila ao subir;
  - o `AutoBrain` não grava o histórico das lutas da tela.
- Nenhum `PUT`, `DELETE` ou `POST` de escrita está na lista: não há como apagar, salvar cenário, editar item, catálogo,
  configuração da Efetividade, guias, combos ou bonecos.

### O que fica, com limites
| O quê | Endpoint | Limite |
|---|---|---|
| Linha do tempo, ficha, relevância | `POST /api/calculate`, `/api/relevance`, `/api/archetypes` | cálculo |
| Ouro efetivo estimado | `POST /api/passives/estimate` (o "medido" sai) | cálculo |
| Comparador rápido | `POST /api/quick/compare`, `fits`, `delta`, `suggest` | cálculo |
| Visão sob efeitos, valores dos efeitos, habilidades | `POST /api/lab/seen-by`, `effect-values`, `abilities` | cálculo |
| Efetividade Básica | `POST /api/effect/evaluate`, `/api/effect/scenario` | só cenários do relatório por chave, até 6 por pedido, referência "própria" ou "build" |
| Luta curta da Arena | `POST /api/arena/run` | só um cenário exportado, **como foi salvo** (o pedido só o nomeia), até 30 s simulados |

- **Por IP e por minuto** (`PublicApiFilter`): 30 cálculos e 600 pedidos no total. Passou: **429** "tente mais tarde".
  O IP só conta em memória, no minuto corrente, e nunca é gravado. Atrás de proxy, vem de `X-Forwarded-For`
  (`server.forward-headers-strategy=native`).
- **Tempo máximo por cálculo** (`ComputeGuard`): os cálculos rodam em 2 trabalhadores fixos, sem fila. Todos ocupados
  ou passou de 20 s: **503** "tente mais tarde".
  - **Limitação honesta:** o Java não mata um cálculo no meio. O que passa do tempo é interrompido, e o que não para
    na hora segura o trabalhador até terminar. Então o servidor **nunca roda mais de 2 cálculos ao mesmo tempo**, mas um
    cálculo longo ainda gasta a CPU dele. Os limites de entrada (6 cenários, 30 s de luta) é que mantêm cada um curto.
- Tudo em `planner.public.*`: `compute-per-minute`, `requests-per-minute`, `compute-seconds`, `workers`,
  `arena-max-seconds`, `effect-jobs`, `allowed-origins`.

### CORS e origem
- Sem CORS por padrão: a página é servida pelo mesmo servidor. Um pedido com `Origin` de outro site recebe **403**,
  inclusive os "simples" que o navegador não pré-verifica.
- `planner.public.allowed-origins` libera outros sites (só GET, POST e DELETE, sem cookies). O `backend-publico` do
  `launch.json` libera `http://localhost:5178`, porque o proxy do Vite troca o Host.

### Builds dos visitantes: no navegador
- `frontend/src/localStore.js` responde às mesmas chamadas do backend (`/api/folders`, `/api/builds`, `/timeline`,
  mover, renomear, apagar), guardando tudo no `localStorage`. As telas não mudaram por isso.
  - Ids locais são negativos. As pastas e builds públicas do servidor aparecem com 🌐, **somente leitura**: salvar uma
    delas cria uma cópia do visitante.
  - Um pedido de cálculo que cita uma build local por id (`buildId: -3`) leva a build inteira (`build: {...}`). Para
    isso, a Visão sob efeitos (`PlannerLabService.Request.build`) e a referência da Efetividade (`Reference.build`)
    passaram a aceitar a build no corpo, como os outros painéis já aceitavam. O cache da Visão sob efeitos chaveia uma
    build enviada pelo SHA-256 do conteúdo.
- **Exportar e importar por arquivo:** os botões que já existiam na aba Builds, agora sobre o navegador.
- **Por link:** "Copiar link" põe a build inteira no endereço (`#/build/loja?b=<código>`): o JSON comprimido pelo
  `CompressionStream` do próprio navegador, em base64url (`shareLink.js`). Quem abre vê a build como rascunho; nada vai
  ao servidor. Funciona também no app local.

### Telas no modo público
- **Abas:** sem Admin; sem Ranking, Partida e Calculadora; Laboratório só com a Visão sob efeitos; "Sobre e
  configurações" foi para Builds. Uma faixa avisa que é a versão pública.
- **Escondidos:** treino de IA (todas as formas), salvar/apagar cenário, editar a luta, valor dos itens por luta, o
  "Testar na Arena", a nota e a efetividade ofensiva, o valor medido das passivas, as cópias em massa de runas,
  habilidades e efeitos, as leituras por item e os candidatos da Efetividade, o Completo, a referência por pasta, a
  edição de guias e de builds da meta, e o painel de Descoberta (fica só o último resultado do campeão).

### Testes
`PublicProfileTest` (6 testes): prepara um banco com o app local e sobe o perfil `publico` sobre ele.
- **Cada endpoint fora da lista responde 404.** A lista vem de varrer, por reflexão, todos os `@RestController` de
  `api/` e `publico/`, inclusive os desligados no público: um endpoint novo entra no teste sozinho (o teste exige
  mais de 100 verificados).
- Os de `/api/admin` respondem 404 com a mensagem do filtro.
- A leitura e o planejador respondem; outro site recebe 403; o 21º cálculo do minuto de um IP recebe 429 e outro IP
  não é afetado; gravar no banco falha.

### Conferido nas telas (`backend-publico` + `frontend-publico`)
- Duplicar uma build pública criou a cópia em "Minhas builds" (id −2, no `localStorage`).
- A Efetividade Básica dessa build local rodou os 11 cenários; a Visão sob efeitos e o comparador também.
- A Arena abriu "vs Renekton" e rodou a luta salva (vitória do lado vermelho aos 21,5 s, abaixo do teto de 30 s).
- A Descoberta mostra só o seletor de build e o resultado salvo.
