# Rodada 8 — Repaginação: super abas, estado que não se perde, tela grande para mobile em paisagem

Um commit por parte. Cada parte termina com `npm run build` limpo, e com `./mvnw test` limpo quando mexe no backend.

**Testes antes da rodada:** 330 (14 opcionais pulados), contados na `target/surefire-reports/`. Depois da Parte 0: 334.

## Parte 0 — Comparadores consistentes ✅

### Uma função para tudo: `BuildDeltaService`

`backend/.../arena/BuildDeltaService.java` é o "trocar e recalcular a build inteira". Recebe a build, uma **troca**
(`Change`: um item numa compra ou numa compra nova, outra página de runas ou outra ordem de compras) e o minuto, roda as
duas linhas do tempo e devolve o **Δ da build inteira** (`Delta`):

- **ouro efetivo** da build antes e depois (status × preço + passivas modeladas + Forja Viva + runas), separado em
  **do próprio item** (as fontes da troca) e **efeito nos outros itens e runas**, com a lista **por fonte** (cada compra,
  cada runa, o campeão, a Forja Viva);
- os **status** da ficha inteira, antes, depois e Δ;
- a **vida efetiva** física, mágica e mista pela ficha do planejador (sem luta);
- o **valor das passivas** (ouro do que as passivas modeladas dão em status).

As fontes casam entre as duas linhas do tempo por item e cópia (`item:<id>#<n>`), runa e campeão por nome, e a compra
trocada é uma fonte só (`own-item`), seja qual for o item antes e depois. Assim o "efeito nos outros" inclui a Forja
Viva, as passivas com `ref`, o Abençoado do Warmog, as runas que escalam **e** as compras seguintes que a troca atrasa ou
adianta (um item mais caro deixa o próximo incompleto no minuto).

**Quem chama:**

| comparador | onde | como |
|---|---|---|
| Comparador rápido de itens | `QuickCompareService.compare` | `deltas.compare(antes, depois, minuto, Change.item(...))` em cada cartão (`Card.delta`) |
| Comparar runas (Parte 4) | `POST /api/quick/delta` | `Change.runes(página)` |
| Candidatos da Efetividade | `EffectivenessReport` → `POST /api/quick/delta` | `Change.item(compra, candidato)`, uma linha "Build inteira" na tabela |
| Calculadora | `CalculatorService.compute` | cada alternativa contra a melhor ordem no último minuto: `Change.order(...)` (`Line.delta`) |

No frontend, `components/WholeDelta.jsx` mostra um `Delta` (cada tela escolhe as partes com `show`), e `fightDelta`
soma as medidas de luta quando há cenário: com o **Índice Básico** (comparador rápido) ou um cenário de teste
(candidatos da Efetividade), o cartão ganha o **Δ de dano causado e de dano recebido** da build inteira, média das lutas,
contra a build como está (as mesmas lutas da build sem a troca rodam junto).

### Itens que evoluem

O comparador rápido aceita a forma evoluída como candidata (`MAX_CANDIDATES` 5 → 9: a referência e até 4 itens, cada
um nas duas formas). Cada cartão de um item que evolui (os seis `evolucao` da loja) tem o seletor **Forma: base ·
evoluída · as duas**. O motor não estima quando a evolução acontece, então a evoluída diz "evoluído desde a compra
(estimativa)" (`Card.evolvesAt` fica nulo; o campo existe para quando o motor souber).

### Testes (`BuildDeltaTest`)

- trocar um item que alimenta a Forja Viva muda o "efeito nos outros" (a fonte Forja Viva muda);
- o Δ da build inteira bate com a soma das fontes, e com próprio + outros; o Δ de cada status é depois − antes;
- um candidato evoluído soma os status da forma evoluída (Fimbulwinter × Aproximação Invernal), e cada forma aponta a outra;
- os comparadores dão o mesmo número para a mesma troca: o cartão do comparador rápido, o Δ pedido sozinho (o caminho
  dos candidatos da Efetividade e do comparar runas) e a leitura de uma ordem (o caminho da calculadora).

Medido no banco de desenvolvimento (Big Carry v1, compra #2, Passos de Mercúrio → Aproximação Invernal, aos 12:00):
ouro efetivo −1.249 (24.295 → 23.046), sendo +2.028 do próprio item e −3.277 nos outros. A Égide de Fogo Solar fica
incompleta aos 12:00 (−3.066) e o Suserano do Gelo rende menos (−1.426). O comparador antigo só mostrava os +2.028.

## Partes 1 e 2 — Estado que não se perde, super abas e sub abas ✅

As duas partes saíram num commit só: as rotas por hash **são** as super abas e as sub abas, e o estado global só faz
sentido quando a mesma build é lida por várias abas.

### Estado global (`frontend/src/buildContext.jsx`)

`BuildProvider` (React Context, sem biblioteca) guarda tudo o que era estado local do planejador: a build aberta e o
rascunho, o minuto selecionado, as builds em comparação e as "mesmas condicionais", o comparador rápido, os objetivos,
o alinhamento do ouro efetivo, a árvore de pastas, os valores das passivas e as ações (abrir, nova, salvar, duplicar,
apagar, mover compras…). As telas leem `useBuild()`.

**Escolha: as telas ficam montadas e escondidas.** Cada tela visitada fica montada (`<div class="pane" hidden>`), então o
estado local da Arena (cenário, resultado, treino), da Partida, da Calculadora, do Ranking, da Efetividade e do Admin
sobrevive à troca de aba, e os trabalhos longos seguem rodando. O que precisa ser compartilhado (a build aberta e o
minuto) está no contexto. Arena e Laboratório são a **mesma instância** (um cenário só), cada sub aba mostra a sua parte
(`Arena forcedTab`). Como a Arena não remonta mais, "Testar na Arena" e "Abrir a luta na Arena" mandam o cenário por
evento (`ornn-arena-load`) além do `localStorage`.

**Sino de tarefas** (`tasks.js` + `TaskBell`): `useTask(id, ativo, { label, done, total, path })` registra um trabalho;
o sino no cabeçalho mostra quantos estão rodando, com progresso, e leva à tela de cada um. Ligados: relatórios da
Efetividade, Pontuação Básica do comparador rápido, calculadora, treino de IA da Arena, Ranking e Partida.

**Rascunho no navegador:** o rascunho da build aberta, se está sujo, o minuto e os condicionais do cabeçalho ficam em
`ornn-planner-draft` (com `try/catch`). Ao recarregar, a build volta como estava, inclusive sem salvar. O cenário não
salvo da Arena já ficava em `ornn-planner-arena`.

### Roteamento por hash (`frontend/src/router.js`)

`#/<super>/<sub>?build=12&min=15`, escrito à mão. O botão voltar funciona (cada troca de aba é uma entrada do histórico),
e um link abre direto a tela com a build e o minuto: `?build=` abre a build (perguntando se o rascunho tem mudanças não
salvas; se a resposta é não, a URL volta para a build aberta) e `?min=` escolhe o minuto. Abrir uma build ou escolher um
minuto reescreve a URL (substituindo a entrada). Um recarregamento sem hash abre a última rota; cada super aba lembra a
última sub aba.

**Links antigos:** o app antigo não guardava a aba no `localStorage` (começava sempre no Planejador), mas as telas
pediam abas pelo nome antigo (`ornn-open-tab`: `planner`, `arena`…). `LEGACY` leva cada nome antigo à tela equivalente,
tanto pelo evento quanto por hash (`#arena` → `#/simulacoes/arena`).

### Super abas, sub abas e o cabeçalho fino

| super aba | sub abas |
|---|---|
| Builds | Builds e pastas |
| Build | Loja · Sequência · Gráfico · Status do campeão · Comparar itens · Comparar runas · Runas e feitiços · Habilidades · Guias |
| Relatórios | Análise da build · Campeão: passiva e habilidades · Efetividade · Relevância · Ouro efetivo · Ranking · Comparar builds |
| Simulações | Arena · Laboratório · Partida · Calculadora |
| Admin | Catálogo · Configuração da Efetividade · Sobre e configurações |

O `Planner.jsx` virou um conjunto de seções (`BuildFields`, `ShopSection`, `SequenceBar`, `TimelineSection`,
`ChartSection`, `StatusSection`, `CompareItemsSection`, `PrepSection`, `SkillsSection`, `RelevanceSection`,
`EffectiveGoldSection`, `PassivesSection`, `EffectivenessSection`, `BuildLabSection`, `PrepEditors`), cada uma lendo o
contexto; o `App.jsx` monta cada sub aba com elas.

**Cabeçalho fino e fixo** (`BuildHeader`): nome (• quando há mudança não salva), campeão e nível, os 6 espaços do
inventário no minuto (um clique lê o item), o minuto selecionado (campo editável; vazio = a última compra) e os botões
rápidos **Status**, **Relevância**, **Habilidades**, **Condicionais** e **Testar na Arena**. A barra fixa antiga
(`BuildDock`) saiu do topo: os status, as passivas, a venda pendente e os objetivos dela estão em **Build › Status do
campeão** (o `BuildDock` em modo `inline`, ao lado da ficha); o leitor de item abre na gaveta lateral.

**Builds:** a coluna lateral do planejador virou a super aba Builds, com a prévia da build escolhida e **criar,
duplicar, importar e exportar** (novos: exportar baixa um JSON `ornn-planner-build`; importar envia o JSON como uma build
nova, na pasta dela se existir). A caixa "comparar" segue na lista; as comparações abrem em Relatórios › Comparar builds.

### Telas antes e depois

- **Antes:** uma barra com 8 abas (Planejador, Arena, Partida, Efetividade, Calculadora, Ranking, Admin, Sobre). O
  Planejador era uma página só: coluna de pastas à esquerda, barra fixa da build no topo, campos da build, campeão,
  runas, loja com a sequência, o comparador rápido e os efeitos dos itens, a linha do tempo, o gráfico e, embaixo, a
  ficha com 5 abas (Relevância, Passivas, Ouro efetivo, Efetividade, Laboratório). Trocar de aba desmontava a página.
- **Depois:** 5 super abas com sub abas numa segunda linha e o cabeçalho fino da build aberta embaixo delas, as três
  fixas no topo. Cada parte do Planejador tem a sua sub aba, e nenhuma tela perde estado ao trocar de aba.

## Parte 2b — Relatórios › Campeão: passiva e habilidades ✅

**Os números vêm da Arena.** `Arena.withAbilityValues()` escreve, no início de uma luta de zero segundo contra o
boneco-alvo, a leitura de cada habilidade (`FighterStats.abilityValues`) com **as mesmas funções da luta**: a ficha da
habilidade (`ability`), o `formula()` do dano termo a termo, a recarga com a aceleração (`cooldown`), o custo (`custo`
do recurso), o alcance, os `efeitos` (controle e duração) e o que passa da resistência do alvo com a penetração da build
(`DamageRules.mitigation`, como o `deal`). Campeões sem ficha caem no dano lido do texto (`AbilityParser`), também como
a Arena faz. Endpoint: `POST /api/lab/abilities` (`build` ou `buildId`, `minute`, `target`: um boneco).

**A tela** (`pages/ChampionReport.jsx`) reaproveita o `Ability` do `ChampionPanel` (o texto do jogo recalculado por
`abilityMath.js`) e o `skills.js` (o rank pelo plano de habilidades), sem duplicar:

- por habilidade: rank no minuto, dano com tipo, recarga (base → com a AH da build), custo, alcance, controle (tipo e
  duração; com a tenacidade do alvo quando ele tem), escudo / cura quando a ficha tem;
- **de onde vem o valor:** cada termo ("110% × 290 Dano de Ataque = 319"); ao passar o mouse, quais itens e runas dão
  aquele status ("Vem de: Coração de Aço 180, Armadura de Warmog 155…");
- **contra um alvo:** o boneco do laboratório, os bonecos da Arena (padrão e salvos) e os atacantes dos cenários da
  Efetividade; dano depois das resistências e quantos usos matam o alvo;
- **passiva:** o que ela soma em status e, para o Ornn, a tabela de quanto a Forja Viva amplifica cada item; o Frágil
  (W e R) aparece com o dano dele;
- **comparar:** outro minuto ou outra build do mesmo campeão, com a diferença entre parênteses (verde: melhor);
- os condicionais do cabeçalho valem (a leitura usa a build com eles).

O cabeçalho ganhou **Habilidades**, que abre esta aba (compacta) no painel lateral.

**Teste** (`ChampionAbilitiesTest`): Ornn com 3 itens de tanque e sem runas aos 18:00 contra um boneco de 200.000 de
Vida, 80 de Armadura e 60 de RM. A leitura de Q e E é o dano por acerto que a própria Arena registrou (dano por fonte ÷
`habilidade_acertou`), ±1%; os termos somam o dano; a recarga é a da ficha com a AH. Medido: Q 137,0 por acerto (3
acertos), E 137,6 (2 acertos), iguais à leitura.

**Fora:** "Visão sob efeitos" como alvo. Ela descreve a build sob efeitos do inimigo, não um alvo com Vida e resistências.
O alvo escolhido já cobre o dano depois das resistências.

## Parte 3 — Painéis temporários ✅

**Gaveta lateral** (`components/Drawer.jsx`): `useDrawer().open({ title, render, path })` abre o conteúdo de outra sub aba
por cima da tela, sem trocar a tela de baixo nem perder nada. Fecha com **Esc** ou **×**; **abrir como aba** leva à sub aba
inteira (`path`). Ela vive dentro do estado global, então mostra a build aberta no minuto selecionado e acompanha as
mudanças. Usada por:

- os botões rápidos do cabeçalho: **Status** (Status do campeão), **Relevância**, **Habilidades** (Campeão, compacto) e
  **Condicionais** (Parte 5);
- a **leitura de item**: um clique num item do cabeçalho, ou em qualquer nome de item dos relatórios
  (`ItemReaderContext`, que antes abria o leitor da barra fixa);
- a **prévia da build**.

**Prévia da build** (`components/BuildPreview.jsx`): itens com ícones na ordem, runas, feitiços, campeão, minuto e nível,
os status principais e a nota, com **Abrir esta build** (troca a build aberta, perguntando se há rascunho não salvo).
`BuildLink` torna clicável toda referência a uma build salva:

| tela | onde |
|---|---|
| Arena | ao lado do seletor de build de cada campeão ("prévia", no minuto do campeão) |
| Ranking | o nome de cada build nos resultados, nas tabelas e na lista de builds da pasta |
| Efetividade | o cabeçalho de cada build comparada no comparativo |
| Calculadora | ao lado da build e da build adversária |
| Partida | a build de cada jogador na tabela |
| Comparar builds | cada build marcada |
| Builds | a própria prévia, ao lado da lista |
| Campeão | a build escolhida para comparar |

## Parte 4 — Comparar runas ✅

`components/RuneCompare.jsx`, em **Build › Comparar runas**. A página da build é a referência; até 4 páginas candidatas,
cada uma editada com o mesmo editor de runas da build (fundamental e menores no estilo do jogo). Cada página passa pela
**mesma função de troca** da Parte 0: `POST /api/quick/delta` com `change.runePage` (`BuildDeltaService.Change.runes`).
O cartão mostra, no minuto selecionado:

- o que as runas da página **somam em status** (as fontes `rune` do Δ ganharam `statsAfter`);
- o **valor das runas em ouro** (status e passivas modeladas: a estimativa das passivas delas);
- a **vida efetiva** física, mágica e mista da build inteira com a página;
- contra a referência, o Δ da build inteira (ouro "das próprias runas" × efeito nos outros, status, vida efetiva).

**Índice Básico** roda o relatório Básico da Efetividade só para as páginas (a build com cada página, leitura fixa), com
progresso no sino.

Teste novo em `BuildDeltaTest`: a página da própria build não muda nada; outra página (com Suserano do Gelo) dá o mesmo Δ
pelo endpoint e pela função, as runas dela são "próprias" e somam Armadura.

Medido (Soldado v1, 22:39): trocar o Suserano do Gelo pelo Aperto dos Mortos-Vivos dá −27.085 de ouro efetivo na build
inteira, −17.792 das próprias runas e −9.292 nos outros (a Forja Viva lê a Armadura e a RM do Suserano), e −29.809 de vida
efetiva mista.

## Parte 5 — Condicionais ligáveis no cabeçalho ✅

O botão **Condicionais** do cabeçalho abre na gaveta (`components/Conditionals.jsx`) a lista de todos os efeitos
condicionais da build aberta:

- **compras**: as linhas condicionais do item (Duplaguarda de Amaranto após 5 s…), por compra (`includeConditional`);
- **efeitos dos itens**: os efeitos modelados com condicional (`itemSetup.conditional`);
- **runas**: Suserano do Gelo ao imobilizar, Coragem do Colosso… (`runePage.conditional`);
- **campeão**: os efeitos da passiva e das habilidades (Poder de Noxus…), `championSetup.conditional`.

Cada um tem o botão ligado / desligado (com • quando difere da build); **Ligar tudo**, **Desligar tudo** e **Como a build
salvou**. Os botões gravam uma camada por cima do rascunho (`condOverride` no estado global, `applyConditionals`), que
toda leitura da build aberta usa (linha do tempo, ficha, relatórios, Efetividade, comparadores, Campeão). Não mexe no
rascunho; o cabeçalho mostra **condicionais alterados**, a camada fica no navegador com o rascunho, e **Salvar** grava a
build com ela (e a desfaz). Usa o que já existia (`includeConditional` e os mapas `conditional`) e estende o liga/desliga
a runas e habilidades, que antes só se mudavam nos editores de cada uma.

Conferido no navegador (Soldado v1, Status do campeão): "Desligar tudo" leva a Armadura de 1.182 para 378 e a RM de
1.031 para 306, e some o escudo do Suserano.

## Parte 6 — Tamanho e mobile em paisagem ✅

**Escala da interface** nas configurações (cabeçalho e Admin › Sobre e configurações): **Normal / Grande / Live**,
lembrada no navegador (`ornn-planner-settings.scale`, `data-scale` no `<html>`). A folha de estilo tem 160 tamanhos em
px (base 14 px), então a escala é um `zoom` no `body`, que aumenta texto, ícones e alvos de toque juntos:

| escala | zoom | texto base | o que muda |
|---|---|---|---|
| Normal | 1 | 14 px (16 px no celular) | — |
| Grande | 1,15 | 16 px | — |
| Live | 1,25 | 17,5 px | cartões mais largos (menos colunas), grades de 2 colunas viram 1, tabelas mostram as 6 primeiras colunas e as outras ao tocar ou passar o mouse na tabela (com a dica "+ colunas"), botões e campos com pelo menos 36 × 1,25 = 45 px, ícones de item com pelo menos 33 px |

Coordenadas medidas na tela e usadas como estilo de um elemento fixo são divididas pelo zoom (`zoom.js`): o seletor de
itens, o cartão de recarga e a dica do mapa da Arena, e o tempo clicado na linha do tempo da Arena. O gráfico mede a
largura com `clientWidth` (pixels de layout, como o `ResizeObserver`), não com `getBoundingClientRect`, que já vem com o
zoom e deixava as faixas de compras mais largas que o gráfico.

**Layout por largura** (`max-height: 520px` em paisagem, ou `max-width: 760px`):

- texto base de pelo menos 16 px (zoom 1,15 na escala Normal);
- o cabeçalho do app e as sub abas rolam junto com a página; as super abas e as **sub abas viram faixas roláveis**
  (sem barra visível, com encaixe);
- o **cabeçalho da build encolhe para uma linha** fixa no topo (52 px), rolável para os lados: nome, itens, minuto e os
  botões rápidos;
- os **painéis laterais ocupam até 60%** da largura (medido: 505 de 844 px);
- Builds, Status e as grades de 2 colunas viram uma coluna; botões e campos com pelo menos 36 px × 1,15 = 41 px. As
  marcas desenhadas (faixas do gráfico, espaços do inventário, células do plano de habilidades) mantêm o tamanho;
- os gráficos ficam com até 70% da altura útil, e nenhuma tela rola para os lados.

**Contraste** (WCAG, sobre o fundo `#060d17` / painel `#0c1826` / cartão `#13253a`): texto 15,7 / 14,4 / 12,5; texto
secundário 11,0 / 10,1 / 8,8; texto apagado 6,0 / 5,5 / 4,7; dourado 8,8 / 8,0 / 7,0; erro 8,8; verde 10,3. Todos acima de
4,5:1.

### Revisão das telas

Medido no navegador em cada uma das 24 sub abas: largura do documento contra a da janela, tamanho do texto, menor
ícone de item e altura do topo fixo.

**844 × 390 (Normal):** texto de 16,1 px em todas. Nenhuma rola para os lados depois das correções (antes: Gráfico e
Comparar builds com 908 px, por causa das faixas de compras). O topo some ao rolar e fica só a linha da build.

- Loja: a loja e a sequência rolam dentro das próprias caixas; Sequência e Comparar itens também.
- Gráfico: ocupa a largura, e as marcas de nível voltaram ao tamanho normal (a altura mínima de botão as esticava).
- Status, Análise, Relevância, Ouro efetivo, Efetividade e Campeão: uma coluna.
- Arena: a explicação e o mapa numa coluna; a linha do tempo rola dentro da caixa.
- Gaveta: 60% da largura.

**915 × 412 (Normal):** as 24 sub abas sem rolagem lateral, com o cabeçalho da build de 52 px.

**1920 × 1080 (Live):** texto de 17,5 px, menor ícone de item com 33 px (70 px na Loja), topo fixo de 180 px (17% da
altura) e nenhuma tela com rolagem lateral. As tabelas largas (Relevância, Linha do tempo, Ranking) mostram 6 colunas e
a dica.

**Cabeçalho a 375 px** (a Rodada 6.a mediu 1.004 px): com 760 px ou menos, o cabeçalho do app não quebra mais em várias
linhas. As super abas, as sub abas e a linha da build rolam para os lados dentro de si, sem alargar a página.

# Relatório final

## Onde estava → onde ficou

| funcionalidade | onde estava | onde ficou |
|---|---|---|
| Pastas e builds (abrir, criar, mover, renomear, apagar pasta) | Planejador, coluna lateral | **Builds › Builds e pastas** (+ prévia, duplicar, importar, exportar) |
| Marcar builds para comparar | Planejador, coluna lateral (caixa "comparar") | Builds (a mesma caixa) e **Relatórios › Comparar builds** (adicionar e tirar) |
| Nome, pasta, campeão, ouro/min, XP/min, fim da partida, suposições, nota, boneco de pano | Planejador, topo | **Build › Loja** (topo) |
| Salvar, duplicar e apagar a build | Planejador, topo | **Build › Loja** (topo) |
| Barra fixa da build (6 espaços, status, passivas, venda pendente, objetivos) | Planejador, fixa no topo | **Cabeçalho fino** (espaços, minuto, botões) + **Build › Status do campeão** (o resto) |
| Leitor de item | Barra fixa | **Gaveta lateral** (item do cabeçalho ou qualquer nome de item) |
| Objetivos (Infernal, Montanha, Barão…) | Barra fixa | **Build › Status do campeão** |
| Testar na Arena | Barra fixa e aba Laboratório | **Cabeçalho** e **Simulações › Laboratório** |
| Campeão: plano de habilidades, efeitos, escolhas, texto | Planejador | **Build › Habilidades** |
| Runas e feitiços, exportar | Planejador ("Preparações") | **Build › Runas e feitiços** |
| Loja | Planejador | **Build › Loja** |
| Sequência de compras (mover, momentos, vendas, condicionais, evoluir) | Planejador, sob a loja | **Build › Loja**, **Build › Sequência** e **Build › Comparar itens** |
| Comparador rápido de itens | Planejador, sob a sequência | **Build › Comparar itens** (o ⇄ da loja e da sequência leva até lá) |
| Efeitos modelados dos itens | Planejador, sob a sequência | **Build › Loja** (embaixo) |
| Linha do tempo de compras | Planejador | **Build › Sequência** (e Relatórios › Comparar builds) |
| Gráfico (status, ouro efetivo, passivas) | Planejador | **Build › Gráfico** (e Comparar builds) |
| Ficha de status | Planejador, aba do momento | **Build › Status do campeão** (e a gaveta "Status") |
| Relevância | Planejador, aba do momento | **Relatórios › Relevância** (e a gaveta) |
| Passivas | Planejador, aba do momento | **Relatórios › Análise da build** |
| Ouro efetivo | Planejador, aba do momento | **Relatórios › Ouro efetivo** |
| Efetividade da build (relatórios, por item, candidatos) | Planejador, aba do momento | **Relatórios › Efetividade** |
| Laboratório da build (sob efeitos) | Planejador, aba do momento | **Simulações › Laboratório** |
| Arena (cenário, luta, treino) | aba Arena | **Simulações › Arena** |
| Laboratório da Arena (combos, boneco) | aba Arena › Laboratório | **Simulações › Laboratório** |
| Partida | aba Partida | **Simulações › Partida** |
| Calculadora | aba Calculadora | **Simulações › Calculadora** |
| Ranking | aba Ranking | **Relatórios › Ranking** |
| Cenários de teste, bonecos, relatórios Básico e Completo | aba Efetividade | **Admin › Configuração da Efetividade** |
| Admin (catálogo, Ornn, XP, preços, reimportar) | aba Admin | **Admin › Catálogo** |
| Sobre | aba Sobre | **Admin › Sobre e configurações** |
| Idioma e siglas | cabeçalho | cabeçalho e **Admin › Sobre e configurações** (+ Tamanho) |
| — (novo) | — | Relatórios › Campeão; Build › Comparar runas; Build › Guias (lugar da Rodada 9); Condicionais no cabeçalho; sino de tarefas; prévia de build |

Nada sumiu: as 24 sub abas foram abertas com uma build salva e todas renderizam, sem erro no console nem resposta de
erro do backend.

## O que ficou de fora, e por quê

- **"Visão sob efeitos" como alvo do Campeão:** ela descreve a build sob efeitos do inimigo, não um alvo com Vida e
  resistências. Os bonecos da Arena e os atacantes da Efetividade cobrem o dano contra um alvo.
- **Minuto da evolução** dos itens que evoluem: o motor não estima quando a evolução acontece. O cartão diz "evoluído
  desde a compra (estimativa)", e o campo `evolvesAt` fica pronto para quando o motor souber.
- **Tabelas em Live:** as colunas da 7ª em diante ficam escondidas por CSS. O botão "mais" virou a dica "+ colunas", e a
  tabela inteira aparece ao tocar ou passar o mouse nela. Um botão de verdade exigiria mexer em cada tabela.
- **A escala usa `zoom`**, que funciona no Chrome, no Edge e no Firefox recente. Converter os 160 tamanhos em px para
  `rem` seria uma rodada à parte.
- **As Partes 1 e 2 saíram num commit só**, porque as rotas são as próprias super abas (explicado acima).
- **Calculadora:** o Δ da build inteira aparece só nas alternativas, contra a melhor ordem. Na árvore de escolhas, que
  compara ordens dos dois lados, o Δ de uma troca não tem um sentido único.

**Testes:** 330 antes da rodada → 336 (14 opcionais pulados): `BuildDeltaTest` (5) e `ChampionAbilitiesTest` (1). Golden
sem mudança. `npm run build` limpo.
