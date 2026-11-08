# Rodada 11.b — Site estático no GitHub Pages (sem servidor, bom no celular)

Prompt: `prompt-rodada-11b-site-estatico.md`, depois da Rodada 12. Uma parte por commit, sem push, no `main`.
**Decisão do usuário:** a versão pública é um **site estático no GitHub Pages, num repositório à parte**, só com o que
foi calculado no PC. O perfil `publico` e o `Dockerfile` da Rodada 11 continuam, mas não são o alvo. **Nada foi
publicado:** tudo fica pronto para o usuário criar o repositório e dar push.

## Parte 1 — Privacidade na exportação ✅

### O vazamento
O resultado da Descoberta guarda as **builds conhecidas** do campeão (`DiscoveryModel.Result.known`: o nome e os itens de
cada build salva, de **qualquer** pasta), e o resultado de uma campanha guarda ainda as builds salvas medidas (`saved`,
com id e nome) e o nome da build de cada rival das matchups. O `PublicExporter` mantinha as linhas `build_discovery`
terminadas sem limpar esse conteúdo: uma build de pasta privada podia sair no site.

### A correção (`publico/PublicScrubber`, chamada pelo `PublicExporter` logo depois dos cortes)
- **Em cada linha `build_discovery` exportada:**
  - `known`: as builds **salvas** que não são públicas saem **por inteiro** (nome, itens, leituras). Uma conhecida só
    fica se for uma build pública do mesmo campeão (mesmo nome e os itens do kit dentro dos da build). As **builds da
    meta** (com fonte) ficam.
  - `saved` (o passo "salvas" da campanha): só as linhas de builds públicas.
  - `matchups`: a rival privada mantém o campeão e a medida; o nome da build vira "build (privada)".
  - O pedido (`config`): a build enviada sai, e o id da build base, se privado, vira `null`.
  - **A build base privada** (a do pedido; numa campanha, a primeira build salva do campeão): o nome dela vira
    "build base (privada)" onde aparecer — "mais próxima", "supera a conhecida" (`against`), o texto da descoberta, as
    notas. Os itens dela saem com as conhecidas.
- **Depois, uma varredura do banco copiado inteiro:** toda coluna de texto de toda tabela, menos as do catálogo (itens,
  campeões, níveis). O nome de uma build ou pasta privada dentro de qualquer texto vira "build (privada)"; o JSON é
  percorrido (continua válido, inclusive nas chaves) e um `buildId` privado vira `null`. Cobre as **guias sugeridas
  pela Descoberta** (`build_guide.tree`), os **combos** (`arena_combo`: nome, dados), o nome da campanha, as notas das
  builds públicas e o que mais aparecer depois.
- **Não troca** o nome privado que também é publicado de qualquer jeito: igual a (ou dentro de) uma build, pasta ou
  guia pública, ou um nome do catálogo (item, campeão). Nomes com menos de 3 letras também não (não identificam nada e
  estragariam qualquer texto). O relatório da exportação conta o que foi trocado, por tabela e coluna.

### Teste (`PublicDiscoveryPrivacyTest`)
Uma pasta pública e uma privada (com nomes que não existem em mais lugar nenhum), uma Descoberta que conhece as duas
(a base é a privada), uma matchup contra a privada, uma guia pública que a cita por nome e por id e um combo achado
nela. Exportado:
- **nenhum nome da build privada nem da pasta privada aparece no arquivo** (busca nos bytes do banco inteiro);
- nenhum `buildId` privado em `build_discovery.config/result`, `build_guide.tree` e `arena_combo.data`, e
  `arena_combo.build_id` vazio;
- ficam a conhecida pública e a da meta; "mais próxima" e "supera" viram "build base (privada)"; a matchup mantém o
  rival (Darius) e perde o nome da build; o id da build pública na guia continua.

## Parte 2 — O que o site pré-calcula ✅

### Como o script lê o app
`tools/site_estatico.py` foi reescrito. Ele lê o **app local completo** (perfil normal, sem os limites do público)
rodando sobre **uma cópia do banco exportado**, e só usa endpoints de leitura e de cálculo (`GET`, e os `POST` de
cálculo: `/api/calculate`, `/api/effect/evaluate`, `/api/offense/evaluate`, `/api/lab/abilities`, `/api/archetypes`,
`/api/guides/suggest`, `/api/arena/run`). Nunca grava nada pelo app.
- **Por que uma cópia:** o app normal grava ao subir (migrações idempotentes, sementes de referência, o `AutoBrain`
  guarda o histórico das lutas). O banco exportado nunca muda; a cópia é jogada fora no fim.
- **Por que o completo e não o público:** a efetividade ofensiva só existe no app completo (`OffenseController` é
  desligado no público), e o completo não tem limites por minuto. Pelo perfil público o script também roda, sem a
  ofensiva; os limites sobem por variável de ambiente só para a geração (`PLANNER_PUBLIC_COMPUTE_PER_MINUTE`,
  `..._REQUESTS_PER_MINUTE`, `..._COMPUTE_SECONDS`, `..._EFFECT_JOBS`), e ele espera nos 429/503.
- **Endpoint novo (só no app local):** `GET /api/site/info` (`api/SiteController`, `@Profile("!publico")`): o patch, o
  registro da exportação e a lista de campeões do site — os que têm **Descoberta terminada no patch** (todos, não só
  os com build pública) e os que têm build pública. No perfil público ele responde 404 (o `PublicProfileTest` varre
  e confere).

### O que sai (em `<saida>/api/`, JSON minificado, números com 2 casas)
- **Catálogo** (como as telas já leem): meta, itens, preços, runas e feitiços, campeões e fichas, perfis, a
  configuração e o relatório básico da Efetividade, o relatório ofensivo; os **ícones** (com `--sem-icones`, não).
- **Por campeão** (`site/campeao/<código>.json`): a lista de builds (resumo: tipo, rótulo, arquétipo, itens e nomes,
  fundamental, índice e nota, ofensiva, último item), as **matchups** da campanha, as sinergias, as conhecidas (já
  limpas pela Parte 1), as notas, as **guias públicas** e a **guia sugerida pela Descoberta**.
- **Por build** (`site/build/<chave>.json`), para a **padrão robusta**, a **melhor de cada nicho**, as
  **descobertas** e as **públicas**:
  - a build (campeão, ordem, runas do kit ou da build pública do campeão, feitiços);
  - a **linha do tempo** em 3 ritmos: padrão (700 de ouro e XP por minuto para as descobertas; o ritmo salvo nas
    públicas), **atrás** (−100) e **à frente** (+100). Os extras guardam só as compras; o padrão guarda também a série
    (minuto, nível, ouro na mão, totais);
  - nos **minutos-chave** (10, 15 e o do último item; dois a menos de 1 minuto viram um): o ponto da ficha (com as
    contribuições de cada item), a **Efetividade Básica**, a **ofensiva** e as **habilidades** contra o boneco de pano;
  - o arquétipo, o índice ponderado e a nota no último item, as matchups (na padrão).
  - Kits iguais (a padrão que também é a melhor de um nicho, por exemplo) viram **uma** build com os dois rótulos.
- As **lutas** dos cenários públicos, já rodadas (o replay), e a lista delas.
- **`api/manifest.json`:** formato (1), patch, data, o registro da exportação, os campeões (classe, rotas,
  arquétipos, quantas builds, se tem Descoberta, quantas públicas e guias, segundos), lutas, ritmos, arquivos e bytes.
- `verificar()` confere o manifesto contra os arquivos no fim (e o `publicar_site.py` também).
- **Opções:** `--campeoes`, `--sem-icones`, `--sem-lutas`, `--sem-ritmos`, `--rapido` (só linha do tempo e
  Efetividade Básica), `--limpar`.

### Tamanho
- Por build, ~120 KB (as três linhas do tempo ~45 KB; efetividade, ofensiva, habilidades e ficha nos 3 minutos
  ~85 KB). A primeira versão guardava a série inteira, com as contribuições de cada item, nos 3 ritmos: 820 KB por build.
- Fixo: o catálogo ~0,5 MB e os ícones ~6 MB.
- Primeira rodada (sobre o banco de teste inteiro, não exportado: as 34 builds do Ornn contavam como "públicas"): Ornn
  (38 builds), Rumble e Mordekaiser, 370 arquivos, **11,4 MB em 4 min 48 s** (1.271 pedidos).
- A medida dos 6 campeões de ficha A, sobre uma exportação de verdade, está na Parte 4.

### Testes (`tools/test_site_estatico.py`, `python -m unittest tools/test_site_estatico.py`)
A regra do nome de arquivo com os casos compartilhados com o frontend; os minutos-chave; kits iguais viram uma build
com os dois rótulos e chaves únicas; a verificação do manifesto acha a build que falta e o minuto sem cálculo.

## Parte 3 — Modo estático do frontend ✅

### Como gera
- `npm run build:estatico` (= `vite build --mode estatico`, que lê `frontend/.env.estatico`: `VITE_STATIC=1` e
  `VITE_PUBLIC=1`). Sai em `frontend/dist-estatico/` (o `publicar_site.py` junta com o `api/`).
- `base` do Vite: **relativo** (`./`) por padrão, então o site funciona em qualquer pasta (`/<nome-do-repo>/` no
  GitHub Pages) sem configurar; `VITE_BASE=/meu-repo/` fixa uma pasta se preferir. Rotas por hash: não precisa de
  `404.html`.
- `main.jsx` carrega o app completo **ou** o site (`src/site/`) por import dinâmico: o build de um não leva o outro.

### A regra do nome de arquivo (um lugar de cada lado, com os mesmos casos)
`frontend/src/staticPath.js` (`staticFile`) e `tools/site_estatico.py` (`arquivo_estatico`):
`/api/x?y=1` → `api/x/y=1.json`. O caminho e a query são decodificados antes; cada pedaço mantém `[A-Za-z0-9._=-]` e
troca o resto por `~` + o hex dos bytes UTF-8 (`K'SANTE` → `K~27SANTE`, espaço → `~20`, `ç` → `~C3~A7`): só ASCII,
igual no Windows, no Linux e na URL, e sem `%` que o navegador decodificaria de novo. Os parâmetros, na ordem, unidos
por `_`. Ícones mantêm a extensão (ou ganham `.png`) e perdem a query. Os casos (query, acentos, espaços, `%`, `+`,
emoji) ficam em `tools/caminhos_estaticos.json` e os dois testes leem o mesmo arquivo.
- `api.js` no estático: um `GET` lê o arquivo; qualquer outro método responde "Não existe no site estático: os números
  aparecem quando a build for calculada no app completo." As builds do visitante continuam no `localStore.js` da
  Rodada 11 (mesmas chamadas, no `localStorage`). Os ícones passam por `asset()`.

### O que o visitante faz
- **Campeões** (`#/`): grade com busca e filtros por **classe, rota e arquétipo** (do manifesto).
- **Campeão** (`#/c/ORNN`): as builds em cartões (tipo, rótulo, ícones dos itens na ordem, arquétipo, fundamental,
  último item, Efetividade defensiva com a nota, ofensiva), filtro por tipo e arquétipo, as **matchups** da campanha,
  as **guias** (árvore "se … senão …") e a sugerida pela Descoberta, as notas.
- **Build** (`#/b/<chave>?m=15`): itens na ordem com minuto e nível nos **3 ritmos**, runas e feitiços, e nos
  **minutos-chave** a ficha, a Efetividade defensiva (média e cada cenário), a ofensiva e as habilidades; por que é
  uma descoberta (ganha em, perde em); as matchups.
- **Comparar** (`#/comparar?k=a,b,c`): 2 ou 3 builds lado a lado (efetividade, último item, custo, vida,
  resistências, dano, aceleração), o melhor de cada linha destacado. Nada recalcula. O botão "Comparar" guarda a escolha.
- **Compartilhar:** "Copiar link" (o mesmo `#/build/loja?b=` da Rodada 11, que o site abre no Montar),
  "Exportar arquivo" (o mesmo formato `ornn-planner-build` da aba Builds) e "Guardar no navegador".
- **Montar** (`#/montar`): campeão, nome, ouro e XP por minuto, a loja inteira (busca, parte, aba), a sequência
  (subir, descer, tirar), a soma do preço; guardar no navegador, exportar, link, importar. Aviso fixo: "Os números
  aparecem quando a build for calculada no app completo."
  - **Os cálculos opcionais em JS ficaram de fora:** o `effective.js`, o `shopModel.js` e o `abilityMath.js` partem
    do ponto da linha do tempo, que só o Java calcula; sem ele não há como fazer o teste de paridade com 3 builds, e
    a regra do prompt é "se não bater, não mostre". Só a soma do preço da loja aparece.
- **Minhas** (`#/minhas`): as builds guardadas neste navegador, com abrir no Montar, link, exportar, apagar, importar.
- **Lutas** (`#/lutas`, `#/luta/<id>`): as lutas dos cenários públicos **assistidas** pelo replay gravado (posições e
  vida quadro a quadro num SVG, com tocar e uma barra de tempo), e o resumo de cada lutador.
- **Sobre:** de onde vêm os números, o que não existe aqui, como enviar uma build e a escala da tela.

### Escondido no estático (não existe, nem como botão)
Arena ao vivo, comparador que recalcula, Efetividade nova, treino, Admin, Descoberta, calculadora, comunidade (a
Parte 4 da Rodada 11 é trocada pelo formulário de issue da Parte 4 abaixo). O site é outro app pequeno: as telas do
app completo nem entram no pacote.

### Celular primeiro
- Grade de campeões em 2 colunas no retrato; uma build por tela; cartões em vez de tabelas (a comparação é uma lista
  de linhas com 2–3 colunas estreitas); **escala Grande por padrão** (`data-scale="grande"`, trocável em Sobre);
  `overflow-x: hidden` e `min-width: 0`/`overflow-wrap` nos textos longos.
- **Conferido no navegador** (site gerado com Ornn — 38 builds —, Rumble e Mordekaiser):
  - **390 × 844 (retrato):** campeões, Ornn, a padrão robusta inteira (itens, runas, ficha, efetividade,
    habilidades, matchups), a comparação de 3 builds, o Montar (escolher campeão, tocar 3 itens, guardar), Minhas e
    uma luta. `scrollWidth` = `clientWidth` (390) em todas: sem rolagem lateral.
  - **844 × 390 (paisagem):** campeão em 2 colunas de cartões, a build; sem rolagem lateral.
  - **1920 × 1080:** coluna central de até 1100 px, cartões em grade; sem rolagem lateral.
  - O link de build no formato do app completo abriu no Montar com o nome, o ritmo e os itens.
  - Nenhum pedido saiu do site: todos a `api/...json` e `api/.../*.png` relativos.
- **Defeitos achados e corrigidos na conferência:** tocar rápido em vários itens da loja perdia itens (estado
  antigo); "Custo total" destacava o menor como "melhor"; "tirar" da comparação não mudava o endereço; a chave da
  build perdia o "ã" ("padro-robusta"); o replay ficava achatado (a pista é longa e fina).

### Carga leve
Cada tela baixa só os JSONs que usa (`src/site/data.js`, cada um uma vez; o navegador guarda o catálogo e os ícones em
cache). Abrir a página de um campeão baixa o manifesto, o arquivo do campeão, o relatório básico (pelas notas) e os
ícones dos itens que aparecem; **não** baixa o catálogo de itens (os nomes vêm no arquivo do campeão). Medida na
Parte 4.

### Testes (`npm test` = `node --test`, sem dependência)
- `test/staticPath.test.mjs`: a regra do nome com os casos de `tools/caminhos_estaticos.json` (os mesmos do Python).
- `test/site.test.mjs` (pula se não houver site gerado; `SITE_DIR` escolhe a pasta): serve `site-publico/` com um
  servidor HTTP simples que registra cada pedido; o `index.html` só usa caminhos relativos; para **cada campeão** do
  manifesto, percorre as leituras da página do campeão, de uma build e da comparação de duas (`walk` de `data.js`) e
  confere que **todo pedido foi a um JSON gerado sob `api/` e respondeu 200**; e o **manifesto bate com os arquivos**
  (cada campeão, cada build da lista, a linha do tempo, os minutos-chave, as lutas).
