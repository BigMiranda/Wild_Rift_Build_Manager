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

## Parte 3 — Opções de hospedagem (para o usuário decidir) ✅

**Nada foi publicado nem implantado.** Ficaram o `Dockerfile` (opção 1) e o `tools/site_estatico.py` (opção 2). Os
preços são aproximados, de memória, e mudam: **confira na página de cada serviço antes de escolher.**

### O que pesa na decisão
- **Memória:** o servidor é uma JVM com Spring. No perfil público, com o banco de ~1 MB e 2 trabalhadores de cálculo,
  cabe em **512 MB a 1 GB** de RAM. Uma luta curta ou a Efetividade Básica ocupa 1 núcleo por alguns segundos.
- **Disco:** o jar (~40 MB) e o banco exportado (~1 MB hoje).
- **Escrita:** nenhuma no banco público. Só a Parte 4 (builds compartilhadas, opcional) grava, num arquivo à parte.
- **Atualização:** a cada exportação, troca-se o banco (imagem nova, ou o arquivo montado por cima).

### Opção 1 — Servidor pequeno com o perfil público (interativo)
O jar no perfil `publico` com o banco exportado, numa VPS ou num serviço de contêiner.

| | |
|---|---|
| **Prós** | Tudo interativo: planejador, comparador, Visão sob efeitos, Efetividade Básica, Arena curta. Um único artefato (a imagem). Os limites da Parte 1 protegem o servidor. É a única opção em que a Parte 4 funciona. |
| **Contras** | Custo mensal. Alguém cuida do servidor (atualizações do sistema, certificado, reinício). Um pico de visitantes recebe "tente mais tarde". |
| **Custo aproximado** | VPS de 1–2 vCPU e 2–4 GB: **US$ 4–6/mês** (Hetzner, DigitalOcean, Vultr). Contêiner gerenciado com 512 MB–1 GB: **US$ 5–10/mês** (Fly.io, Render, Railway). Há camadas gratuitas (Oracle Cloud Always Free; Render free, que dorme sem uso e demora a acordar a JVM). Domínio: ~US$ 10–15/ano, opcional. |

**Com o `Dockerfile`:** `cd backend && ./mvnw -Pexport`, depois, na raiz, `docker build -t ornn-planner-publico .` e
`docker run -p 8080:8080 ornn-planner-publico`.
- Três estágios: a página no modo público (Node), o jar (JDK, testes pulados: rode `./mvnw test` antes) e a imagem
  final só com o JRE, o jar e o banco (somente leitura, `chmod 0444`, usuário sem root, `HEALTHCHECK` em
  `/api/public/info`).
- `.dockerignore` deixa de fora o resto de `backend/data/` (o banco completo do usuário nunca entra no contexto).
- Atrás de um proxy com HTTPS (Caddy, nginx, o do serviço), o IP do visitante vem do `X-Forwarded-For`.
- **O que deu para conferir aqui:** o Docker está instalado nesta máquina, mas o serviço dele estava parado e não o
  liguei, então **a imagem não foi montada**. Simulei os passos dela: `npm run build:publico`, `./mvnw package
  -DskipTests` (jar de 61 MB) e o jar rodando com as mesmas variáveis (`SPRING_PROFILES_ACTIVE=publico`,
  `PLANNER_DB_PATH`, `JAVA_TOOL_OPTIONS`) sobre o banco exportado marcado como somente leitura. Subiu, serviu a página
  pública, `POST /api/admin/export` deu 404, e a Efetividade Básica rodou na tela pela mesma origem (11 cenários,
  pontuação ponderada 76,6, os mesmos números da Parte 1).

### Opção 2 — Site estático só com o pré-calculado (de graça)
O frontend lê arquivos JSON gerados do banco exportado, em hospedagem estática.

| | |
|---|---|
| **Prós** | **Grátis** (GitHub Pages, Cloudflare Pages, Netlify). Sem servidor para cuidar, sem limite de visitantes, rápido (CDN). |
| **Contras** | **Sem planejador interativo:** só o que foi calculado na exportação (as builds públicas no ritmo salvo, a Efetividade Básica delas, a Descoberta, as guias, as lutas dos cenários públicos). Mudar um item, o minuto ou o ouro/min não recalcula. A Parte 4 não funciona. |
| **Custo** | US$ 0 (domínio opcional). |

**Script:** `tools/site_estatico.py` lê um servidor público **local** (o jar no perfil `publico` sobre o banco
exportado) e grava cada resposta de leitura em `site-estatico/api/...json`, no mesmo formato da API: catálogo e
ícones, builds públicas e a linha do tempo de cada uma, arquétipos, Efetividade pré-calculada, relatórios, guias,
Descoberta por campeão, kits, mapa, cenários públicos e a luta de cada um já rodada. Só a biblioteca padrão do Python;
respeita o limite de cálculos (espera no 429).
- **Rodado** contra o servidor público local (banco exportado com a pasta Anti Tank AD): 480 arquivos, 7,5 MB (a maior
  parte são os ícones; com `--sem-icones` fica bem menor), 1 min 27 s, com uma espera de 60 s quando passou dos 600
  pedidos por minuto.
- Ler do próprio servidor garante que os JSONs têm exatamente o formato que as telas já leem.
- **Falta para o site existir:** um modo `VITE_STATIC=1` do frontend que troque `fetch('/api/x')` por
  `fetch('api/x.json')` e esconda o que recalcula. Pelo desenho do `api.js` (um ponto só de chamadas), é **1–2 dias**.

**E o planejador interativo no navegador?** Teria de portar o motor para JavaScript:

| Parte | Java hoje | Estimativa |
|---|---|---|
| Linha do tempo, ficha, relevância, ouro efetivo (`engine/`, `PlannerService`, leitura do catálogo e dos efeitos) | ~4.000 linhas | **3–5 semanas** de uma pessoa, com testes de paridade contra o Java (o `abilityMath.js`, o `effective.js` e o `shopModel.js` já espelham uma parte) |
| Comparador rápido e Visão sob efeitos (usam a corrida de zero segundo da Arena: `SeenBy`, `QuickCompareService`, `BuildDeltaService`) | ~1.600 linhas, mais o que usam da Arena | +2–3 semanas |
| Efetividade e lutas (a Arena inteira) | ~32.000 linhas em `arena/` | **meses**; e manter duas Arenas iguais quebraria o determinismo garantido pelos testes golden |

- **Alternativa ao porte à mão:** compilar o Java do motor para o navegador (TeaVM ou GraalVM para WebAssembly).
  `engine/` não usa Spring; o difícil é a leitura dos YAML (SnakeYAML), o Jackson e a reflexão. Estimo **1–2 semanas**
  para provar se a linha do tempo compila e bate com o Java, com risco real de não compensar.
- **Recomendação, se for estático:** começar só com o pré-calculado (1–2 dias de frontend) e deixar o porte para
  depois de ver se há visitantes.

### Opção 3 — Misto
Estático para consultar (opção 2, de graça, na CDN) e um servidor pequeno **só** para o planejador e o comparador
(opção 1 com os limites mais baixos).

| | |
|---|---|
| **Prós** | A maioria das visitas (ler builds, guias, Descoberta) não toca o servidor; ele pode ser o menor plano ou uma camada gratuita que dorme. Se o servidor cair, a consulta continua no ar. |
| **Contras** | Dois deploys a manter em sincronia a cada exportação; o servidor precisa liberar a origem do site estático (`planner.public.allowed-origins`, já suportado, só GET/POST/DELETE e sem cookies); o mesmo frontend precisa saber o que é estático e o que vai ao servidor. |
| **Custo aproximado** | US$ 0 do estático + **US$ 0–6/mês** do servidor. |

### Recomendação
Para começar: **opção 1** num plano pequeno (ou numa camada gratuita), porque não exige trabalho novo, já tem os
limites e é a única em que a Parte 4 roda. Se o custo ou a carga incomodarem, a **opção 3** reaproveita tudo isto e o
script da opção 2. **A escolha e qualquer publicação ficam com o usuário.**
