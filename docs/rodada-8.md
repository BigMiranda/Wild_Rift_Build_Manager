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
