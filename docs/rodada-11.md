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

## Parte 2 — Exportar a base otimizada ✅

### Como exportar
- **Linha de comando:** `cd backend && ./mvnw -Pexport`. O perfil `export` do Maven roda o app local no perfil
  `exportar` do Spring, sem servidor web (`application-exportar.properties`); o `PublicExportRunner` exporta, imprime o
  resumo e sai. Saída padrão: `backend/data/publico/ornn-planner-publico.db` (`planner.export.out` ou
  `PLANNER_EXPORT_OUT`), que é o mesmo caminho que o perfil `publico` lê.
- **Pelo Admin:** Admin › Catálogo › "Exportar a base pública" (`POST /api/admin/export`), com as contagens e o que
  ficou de fora.

### O que sai: só o marcado (esquema v26)
- `folder.publico` e `build_guide.publico` (migração v26). Em Builds, cada pasta tem um botão 🔒/🌐; nas Guias, uma
  caixa "Pública". **Sem nenhuma pasta marcada, a exportação recusa** ("marque ao menos uma").
- **Builds:** só as das pastas marcadas, com as compras, os momentos e os status de boneco delas.
- **Guias:** só as marcadas.
- **Cenários da Arena:** só os feitos inteiramente de builds públicas (e bonecos). Não precisou de outra marcação: um
  cenário que cita uma build privada não sai.
- **Pré-calculado:**
  - Descoberta e campanhas: as linhas de `build_discovery` terminadas (`feito`) do patch atual;
  - Efetividade Básica de cada build pública, na última compra, numa tabela nova `public_effect` (a tela pública
    mostra na prévia da build, em Builds; `GET /api/public/effect`);
  - IAs do automático: as que têm luta no histórico e a melhor salva de cada dono (campeão ou classe), na ordem do
    `AutoBrain`, mais as que um cenário público cita. Só pesos e o que a luta lê: a cadeia, o plano do time, a versão
    de origem e o cenário de treino saem (`NULL`).
- **Catálogo:** itens, status, campeões, runas e feitiços (YAML no jar), Forja, XP. O mapa e os objetivos vêm dos YAML
  do jar.
- `public_export`: data, patch e pastas da exportação (`GET /api/public/info`).

### O que fica de fora
- Builds, pastas e guias não marcadas; cenários com builds privadas; IAs fora da escolha do automático (histórico de
  treino) e as linhas do histórico delas; combos inativos; a configuração da efetividade ofensiva; as builds da meta
  digitadas; a fila de campanhas e descobertas não terminadas ou de outro patch.
- Texto que podia citar builds privadas: de que build cada combo foi achado, a "impressão" das lutas do histórico (a
  escolha do automático não a lê). E de que print cada item foi transcrito.
- **Tabelas que o público não lê:** `arena_dummy`, `arena_guideline` (apagadas). **Coluna:** `build.include_conditional`
  (sem uso desde a v3). As colunas que os repositórios leem ficam, mesmo vazias: apagar quebraria a leitura.
  - Por isso os endpoints de diretrizes de treino saíram da lista pública.

### Otimizado
- Cópia por `VACUUM INTO` (o SQLite copia o banco aberto sem travar o app), cortes, índices para as leituras públicas
  (`build.folder_id`, `build_step.item_id`, `build_discovery (unit_code, patch, status)`, `build_guide.unit_code`,
  `effect_test.kind`), `ANALYZE` e **`VACUUM` de novo**: nenhuma linha apagada sobra em página livre do arquivo.
- O arquivo é escrito ao lado (`.tmp`) e trocado no fim.
- **Relatório:** `<banco>-relatorio.md` e `-relatorio.json` ao lado do banco: pastas, builds, cenários, linhas por
  tabela, tamanho, tempo e a lista do que ficou de fora.

### Rodado numa cópia do banco do usuário
Duas pastas marcadas (Anti Tank AD e Anti Tank AP), `./mvnw -Pexport` numa cópia em `data/export-teste/`:
- **6 builds** de 43, **14 IAs** de 186, **0 cenários** (os 5 citam builds de outras pastas), 6 Efetividades Básicas
  pré-calculadas;
- **1.232 KB** (o banco inteiro tem 9.273 KB), em **4,3 s** de exportação (22 s com a subida do Maven);
- `integrity_check` ok; nenhum nome das 37 builds privadas aparece no arquivo (a única coincidência é um item da loja
  com o mesmo nome de uma build).

### Defeito achado na primeira rodada real
- Com nenhum cenário público, o corte era `id NOT IN (NULL)`, que no SQL **não apaga nada**: os 5 cenários privados
  teriam saído. Agora uma lista vazia é `()`, que o SQLite lê como lista vazia (apaga tudo). Teste próprio.
- A contagem de builds excluídas somava as compras apagadas em cascata (420 em vez de 37): agora conta antes de apagar.

### Testes
`PublicExportTest` (3 testes): um banco com uma pasta marcada e outra não, um cenário de cada lado e uma guia de cada.
- Só o marcado sai (pastas, builds, cenários, guias), a tabela de bonecos some, o relatório existe e bate com o arquivo.
- **O app no perfil público sobe com o banco exportado, abre o planejador (linha do tempo), roda o comparador rápido
  e a Efetividade Básica**, e lê a Efetividade pré-calculada. A build privada responde 404.
- Uma lista vazia de ids apaga tudo.

### Conferido nas telas
- App local (`backend-dev`, banco de desenvolvimento): o 🔒 da pasta "Anti Tank AD" virou 🌐 (`publico: true`); a caixa
  "Pública" aparece no editor de guias; Admin › "Exportar" gerou o banco com as 2 builds da pasta, 748 KB, 2,25 s, com
  a lista do que ficou de fora.
- Versão pública sobre o banco exportado: só as 6 builds das pastas marcadas, a cópia local do visitante em "Minhas
  builds", e na prévia de "Darius de CdC" a Efetividade Básica da exportação (72,5 · Regular, aos 22:32, 11 cenários).
- **No Windows, o arquivo em uso por um servidor público aberto não pode ser trocado:** pare o servidor antes de
  exportar por cima (a exportação avisa que falhou).
- Suíte inteira: 393 testes, 0 falhas.
