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
