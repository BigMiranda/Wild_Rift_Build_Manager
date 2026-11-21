# Rodada 21: IA que generaliza, treino em massa, IA padrão e objetivos visíveis

Prompt de 20/11/2026, a partir do `main` em ce5dcb6 (depois da Rodada 20.b). Lê o `CLAUDE.md` e as rodadas 1 a 3 (a
rede, o `Trainer`, o `ArenaChain`), 7 (Parte 6, o Automático), 18 (Parte 2b, as IAs de classe) e 19 (a progressão).
Nenhuma regra de luta mudou: os golden (`ArenaRegressionTest`, `ArenaPolicyMigrationTest`) continuam os mesmos.

**A pergunta do usuário:** o treino consegue uma IA que lida com vários tipos de campeão e várias formas de jogar?

## Parte 1: medir a generalização, antes de mudar (✅)

**O banco de validação** (`ia_validacao_7_3f.yml`, `arena/league/LeagueModel.bank`, `LeagueService`), fixo e nunca usado
no treino (sementes próprias; o treino em liga tira estes adversários do seu sorteio):

- **adversários:** 2 campeões de cada uma das 7 classes, pela ordem presença na meta (`data/meta/meta.json`, o maior das
  rotas) → qualidade da ficha → nome; só campeões com ficha. Com a meta importada hoje: Tanque Nautilus e Leona, Lutador
  Darius e Lee Sin, Assassino Master Yi e Yasuo, Mago Seraphine e Sylas, Atirador Caitlyn e Kai'Sa, Controlador Thresh e
  Blitzcrank, Especialista Graves e Cho'Gath. `fixos:` no seed troca a escolha de uma classe;
- **builds:** amostradas pela progressão natural (Rodada 19, `ProgressionService.sample`) aos 6, 12 e 20 minutos, contra
  o adversário da luta; runas, feitiços e ritmo de ouro/XP da primeira build salva do campeão (sem nenhuma: a base
  sintética da campanha, 700/700);
- **4 tipos de luta:** `rota` (1x1 numa rota do mapa inteiro, entre as torres externas, com tropas e torres no minuto da
  luta, sem visão; Barão, meio e Dragão em rodízio — `RiftTemplates.laneDuel`); `aberto` (1x1 na rota única, sem zona
  segura); `dupla` (2x2: o aliado de cada lado vem de outra classe do banco); `fuga` (quem foge começa com 40% de Vida,
  2600 da zona segura do seu lado, o outro 350 atrás com a Vida cheia; semente par o jogador foge, ímpar persegue);
- **3 sementes** por combinação: 7 × 2 × 3 minutos × 4 tipos × 3 = **504 lutas**, 18 por célula (classe adversária ×
  tipo de luta). Uma IA de classe joga, em rodízio, os 2 primeiros campeões da sua classe na mesma ordem.

**Êxito** = vitória limpa (o lado vence, o jogador vivo, todo inimigo morto); na fuga em que o jogador foge, chegar vivo à
zona segura também conta. Cada luta é preparada uma vez e jogada com a IA e com o **Tático** (mesmos lutadores, posições
e builds; todos os outros com o Tático, fixo, para a matriz não mudar com as IAs salvas). Determinístico em qualquer
número de threads. O `Gym` ganhou o mapa inteiro (`Episode.rift`).

**Tabela `ai_validation`** (schema v33): a matriz de cada IA, quem jogou, a impressão do banco (seed + campeões), o hash
da ficha, o patch e o tempo. **Relatório:** `LeagueReportTest` (opt-in `-Dleague.report=data/ornn-planner.db`, numa
cópia; `-Dleague.owners=`, `-Dleague.seeds=`, `-Dleague.threads=`).

**"No cenário em que treinou"**: o cenário salvo com o nome do registro (ou um salvo cujo nome começa o do registro,
como os da cadeia "… · Soldado v8.1"), 18 vezes com as posições deslocadas como o treino deslocava, a IA contra o Tático
no mesmo lugar, medido pelo **objetivo do treino** (o `win_fast` "melhor que o Tático" que o treino otimizou). As IAs de
classe usam os campeões com que a Rodada 18 as treinou (`jogadores_do_treino` no seed), com a build da progressão aos 15
minutos, no lugar 0 de "Darius ganha de Ornn".

### Linha de base (20/11/2026, banco de hoje, 18 threads; detalhe por célula em `docs/rodada-21/linha-de-base.md`)

| IA | êxito no banco | Tático no banco | diferença | no cenário de treino: vitória IA / Tático; melhor que o Tático | s |
|---|---|---|---|---|---|
| Darius v22 | 79,4 | 82,1 | −2,8 | (cenário "Novo cenário" não está mais salvo) | 39 |
| Mordekaiser v3 | 42,3 | 56,0 | −13,7 | (não salvo) | 20 |
| Ornn v121 | 61,3 | 72,2 | −10,9 | (não salvo: "Luta da partida aos 2:39") | 22 |
| Rumble v49 | 57,5 | 72,8 | −15,3 | 11% / 0%; **61%** | 16 |
| Yasuo v5 | 77,4 | 88,5 | −11,1 | (o cenário salvo não tem o Yasuo) | 12 |
| classe Assassino v1 | 46,4 | 58,5 | −12,1 | 0% / 0%; **61%** | 11 |
| classe Atirador v1 | 25,8 | 36,1 | −10,3 | 28% / 28%; 56% | 12 |
| classe Controlador v1 | 21,8 | 26,8 | −5,0 | 0% / 0%; **83%** | 15 |
| classe Especialista v1 | 33,3 | 36,7 | −3,4 | 0% / 0%; **83%** | 14 |
| classe Lutador v1 | 42,9 | 58,7 | −15,9 | 0% / 0%; **97%** | 19 |
| classe Mago v1 | 21,4 | 25,2 | −3,8 | 0% / 11%; 39% | 11 |
| classe Tanque v1 | 26,8 | 29,0 | −2,2 | 0% / 0%; 50% | 14 |

**Leitura.** Todas as 12 IAs em uso ficam **abaixo do Tático** no banco (−2 a −16 pontos), e a pior coluna é a fuga (a
IA de classe Tanque: −31 pontos na fuga, +5 a +7 na rota e no aberto): elas aprenderam a vencer **aquele** adversário
naquela luta (a Lutador "melhor que o Tático" em 97% do seu cenário). É o sobreajuste que a revisão apontou.

**Um achado que explica parte da diferença:** a rede zerada **não** jogava igual ao Tático, ao contrário do que a
`NeuralPolicy` promete ("zero weights == Tactical"): ela decide a cada 0,1 s e o Tático a cada passo (0,02 s). No banco,
uma rede zerada da classe Lutador fez 41,1% contra 46,4% do Tático: −5 pontos só pela demora em decidir. As IAs salvas
continuam com 0,1 s (o golden das IAs não muda); as da liga decidem a cada passo (`Weights.interval = 0`, Parte 2), e o
teste `zeroLeagueNetworkPlaysExactlyLikeTheTacticalRules` confere a igualdade célula a célula.

## Parte 2: treino em liga, estilos como entrada (✅)

O `Trainer` (estratégia evolutiva) é o mesmo; ele só ganhou `Evaluator.generationStart(g)`, chamado antes das lutas de
cada geração, e passou a perturbar a rede pelo seu próprio formato (`Network.withWeights`). O que muda é **de onde vêm
os episódios** (`arena/league/LeagueTrainer`, seed `treino_liga_7_3f.yml`):

- **pool sorteado a cada geração** (12 lutas por padrão; cada candidato joga todas), da semente da geração: adversários
  de todas as classes (classe sorteada por igual, depois o campeão; ficha A, B ou C; os adversários do banco ficam de
  fora, `exclui_banco`), minutos de 3 a 25, builds pela progressão (20% puxadas para outro arquétipo: a build "errada"
  provável), 30% com outro feitiço e outra runa fundamental, os 4 tipos de luta do banco (as mesmas posições), e na
  fuga a Vida de quem foge sorteada de 30% a 100%;
- **currículo**: os pesos dos tipos de luta vão do começo (`rota` 0,6, `aberto` 0,3, `dupla` e `fuga` 0,05) ao fim
  (0,25 cada), e o teto dos minutos sobe de 10 a 25;
- **adversários em liga**: o Tático (0,4), as IAs aceitas dos adversários (a do campeão, senão a da classe; 0,3) e o
  **salão** (0,3): as 4 últimas versões da própria IA e ela mesma de 5 gerações atrás (self-play);
- **seleção**: a cada 2 gerações a rede joga 24 lutas fixas do mesmo sorteio (outra semente, o fim do currículo, todos
  com o Tático); a melhor geração por recompensa média é a escolhida;
- **aceite pela matriz inteira** (`LeagueModel.accept`, `aceite` no seed): a média acima da IA em uso (`media_acima: 0`)
  e nenhuma célula pior que 15 pontos (`pior_celula`). Sem IA em uso, a referência é o Tático nas mesmas lutas.
  Recusada, a versão fica salva com `accepted = false` e o motivo; a anterior continua valendo.

**Estilos como entrada: Observation schema 4.** O vetor do schema 3 (241 entradas) fica no começo, intacto, e o estilo
pedido vai no fim, um entre seis (`padrao`, `agressivo`, `cauteloso`, `enrolar`, `fugir`, `farmar`; sem pedido =
`padrao`), lido de `Arena.Input.style`. Uma rede joga todos os estilos; a recompensa de cada um é uma soma de termos
medidos na luta (`StyleReward`; termos e pesos no seed: vitória, vitória limpa, rapidez, dano, Vida, viveu, chegou à
zona e quão cedo, inimigos presos × segundos, controle aplicado, ouro, vantagem de Vida). "Enrolar" pontua inimigos
presos × segundos com o jogador vivo e a Vida preservada; "fugir" pontua chegar à torre (e cedo); o "padrão" é o
objetivo das IAs de antes (vencer rápido), e sair da luta para a torre não ajuda (`chegou: −300`).

Três decisões que mudaram no caminho, cada uma medida:

1. **Decidir a cada passo.** As IAs da liga decidem a cada passo livre, como o Tático (`NeuralPolicy.everyStep`,
   `Weights.interval = 0`), e então pesos zero são exatamente o Tático (Parte 1). As salvas continuam a cada 0,1 s.
2. **O "padrão" codificado também.** Com o "padrão" como tudo zero, nenhum peso mexia só nas lutas padrão; a rede
   aprendia "fugir sempre" e o teste "fugir chega mais que padrão" empatava (13 × 13).
3. **O estilo ligado direto às saídas** (`Network.skip`: as 6 entradas de estilo somam um peso por ação, 6 × 38). Pela
   camada oculta (tanh) "fugir só quando pedido" exige mudanças coordenadas que a estratégia evolutiva acha devagar: com
   40 gerações a rede continuava fugindo nos dois estilos. Com a ligação direta, na mesma posição (Vida cheia, o rival
   350 atrás): **fugir chega 15 de 16, padrão 11, o Tático 4** (só recuar: 16). Uma rede salva antes não tem essa
   ligação (`skip` nulo): continua igual. Uma rede do schema 3 de onde um treino de liga parte ganha a ligação zerada.

**IAs salvas no schema 3** continuam abrindo: a migração põe as entradas novas com peso zero, e a rede lê qualquer
estilo como lia a luta (o "padrão"); o banco deu os **mesmos números** com o schema 4 (linha de base refeita). O
`ArenaPredictionTest` confere a versão 4 e o tamanho.

**Testes** (`LeagueTrainingTest`, banco novo com o catálogo do seed): uma IA do schema 3 carrega e lê todo estilo como o
padrão; a rede zerada da liga joga igual ao Tático em todas as células; o treino é igual com 1 e com N threads; uma IA
de liga de classe (orçamento 3 × 8 × 6) contra uma IA em uso: se aceita, a média é maior e nenhuma célula piora mais
que o limite, se recusada fica salva sem aceite (no teste: aceita, 46,4% contra 41,1% da em uso — exatamente o Tático,
o ganho veio de decidir a cada passo); "fugir" chega à torre mais vezes que "padrão" da mesma posição (15 × 11).
`LeagueModelTest` (puro): 504 lutas, 18 por célula, determinismo e a regra de aceite.

## Parte 3: hierarquia classe → campeão (✅)

`arena/league/LeagueHierarchy`:

1. **IA de classe em liga primeiro** (`trainClass`): joga, em rodízio, todos os campeões com ficha da classe; a
   referência do aceite é a IA de classe em uso (a mais nova aceita da liga, senão a da Rodada 18). Começa dela quando
   ela é melhor que o Tático no banco (`inicio_classe: melhor`; `atual` = sempre dela, `zero` = do Tático); a linha de
   base mostrou todas as da Rodada 18 abaixo do Tático, então as primeiras da liga começam do Tático.
2. **IA por campeão = ajuste fino** (`trainChampion`): começa da rede da IA de classe do campeão (com a ligação de estilo
   zerada, quando ela não tem), na mesma liga, só com o próprio kit; orçamento menor (`orcamentos.*.campeao`). O aceite
   compara com a **IA da classe jogando o mesmo campeão**, no banco daquele campeão; as duas matrizes ficam em
   `ai_validation` com `champion` = o campeão.
3. **O Automático** (`AutoBrain.choose`): no "geral" (sem histórico do cenário), a IA do campeão só é usada se
   `championOverClass` — o êxito dela no banco daquele campeão maior que o da IA da classe ali (ou, só ela medida, maior
   que o Tático); medida só a da classe, a da classe. Sem matriz de nenhuma das duas, a ordem antiga (as IAs de antes
   da liga não somem). O "cenário" e o "parecido" (a IA que venceu aquela luta, ou uma parecida) continuam na frente:
   são evidência daquela luta. Entre as IAs salvas de um dono, as da liga (validadas no banco) vêm antes das outras
   aceitas.

**Teste** (`AutoBrainTest.theChampionsAiOnlyWhenItBeatsItsClassOnItsValidation`): sem matrizes, a do campeão; a da
classe medida e a do campeão não, a da classe; a do campeão abaixo da classe, a da classe; uma versão nova acima, a do
campeão.

## Parte 4: a campanha "Treinar IAs de todos os campeões" (✅)

Admin › Campanhas ganhou uma segunda campanha (`components/AiCampaign.jsx`, `arena/league/AiCampaignService`,
`api/AiCampaignController` em `/api/ai-campaign`, só local; tabela `ai_training_queue`, também do schema v33), no mesmo
modelo da de builds: um trabalhador de fundo, uma linha por IA treinada, retomar depois de um servidor parado (a linha
"rodando" volta para a fila), **pausar** (a IA em treino volta como "pausado"), **parar após o atual** (a que está em
treino termina, é validada e salva) e o progresso no sino (`useTask`: quem, a geração, a validação).

- **Ordem** (`AiCampaignService.order`): as 7 de classe; depois os campeões que a campanha de builds vai usar a seguir
  (as linhas dela pendentes / rodando / pausadas, por prioridade); depois a presença na meta; depois fichas A/B antes de
  C/D; o nome desempata. A tela mostra a ordem antes de enfileirar.
- **Por IA**: o orçamento do seed (`orcamentos.rapido` e `.completo`, um para classe e outro para campeão). A estimativa
  mede as primeiras `estimativa.amostra` (3) de cada tipo e projeta a fila e todos os campeões com ficha.
- **Uso imediato**: o Automático lê as IAs do banco a cada escolha; a aceita vale na próxima luta. O relatório da
  campanha de builds guarda, em cada confronto, a IA de cada lado por minuto (`brainChoices`: tipo, dono, versão),
  e a tela mostra ao lado do rival.
- **Treinar de novo** (`needs`): sem IA da liga, a última recusada, o patch mudou ou a ficha mudou (o hash da ficha fica
  na matriz da IA, `ai_validation.sheet_hash`). As atualizadas ficam de fora (a menos de "também as atualizadas"). A
  antiga vale até a nova ser aceita.
- **Tabela dos 143**: IA própria (versão, data, êxito no banco dela, o da IA da classe e o do Tático no mesmo campeão; "não
  usada" quando abaixo da classe; "desatualizada" com o motivo), na fila, recusada (o motivo do aceite) ou sem treino.
- **Com a campanha de builds rodando**, as duas dividem os núcleos: o treino fica com `trabalhadores.com_campanha_de_builds`
  (0,5) deles, decidido no começo de cada IA (`CampaignService.running()`); sozinho, com `sozinha` (1,0). A linha guarda
  quantos usou e se dividiu. O impacto medido está no relatório final.

**Testes**: `AiCampaignTest` (a ordem: classes primeiro, depois presença na meta e A/B antes de C/D; sem IA da liga tudo
precisa de treino e a tabela diz "sem treino"; a estimativa conta os campeões com ficha). `AiCampaignReportTest`
(opt-in `-Daicamp.report=data/ornn-planner.db`, numa cópia): a campanha das 7 classes e dos primeiros campeões.

## Parte 5: que IA cada processo usa (✅)

**Ajuste central** `ia_padrao_7_3f.yml` (`arena/BrainDefaults`, `GET /api/arena/brain-defaults`; no frontend
`brainDefaults.js`, lido uma vez na abertura, com `auto` enquanto não chega e na versão pública). Todos os pontos que
escolhem um cérebro, achados com `grep -rn "brain = \|brain: '"` no backend e no frontend:

| processo | onde | cérebro | antes |
|---|---|---|---|
| Arena: lugar novo do cenário | `pages/Arena.jsx › newSlot` | `arena` = auto | auto |
| Planejador: Testar na Arena | `components/TestInArena.jsx › slot` | `testar_na_arena` = auto | **tactical** |
| Partida (Simulações › Partida) | `pages/Match.jsx`, `MatchService.Draft.slot` (sem cérebro no pedido) | `partida` = auto | **tactical** (tela), script (sem cérebro) |
| Campanha de builds: confrontos | `CampaignService.duel` | `campanha` = auto | auto |
| Descoberta e nota robusta | `BuildScore.slot` | `descoberta` = auto | auto |
| Calculadora de builds | `CalculatorService.input` | `calculadora` = auto | **tactical** |
| Atlas | `AtlasService` (quando a execução não diz; `atlas_7_3d.yml › cerebro: auto`) | `atlas` = auto | auto (seed) |
| Draft e partida: tabelas do draft | `DraftTablesService.prepare` | `draft_tabelas` = auto | **tactical_stand** |
| Draft e partida: primeiro encontro | `FirstEncounterService` | `primeiro_encontro` = auto | auto |
| Draft e partida: builds prováveis | `LiveBuildsService` | `builds_provaveis` = auto | auto |
| Modelos do mapa inteiro | `RiftTemplates.slot` | `modelos_do_mapa` = auto | auto |
| Efetividade: a build contra os bonecos | `EffectTestService.slot` | `efetividade` = **tactical_stand** | tactical_stand |
| Efetividade, leitura "comportamento fixo" | `EffectTestService` (replay das decisões gravadas) | script, de propósito | script |
| Efetividade ofensiva, Atlas de runs antigas | recebem o cérebro de quem chama (`fighter(…, brain)`, `brainOf` = tactical_stand para runs de antes da Rodada 19) | — | — |
| Treino (o lugar treinado) | `ArenaTrainingService.remember` | a IA treinada (`ai:<id>`) | — |
| Liga: os outros lutadores do banco | `LeagueService` (`cerebro_outros` do banco) | tactical, de propósito (a matriz não pode mudar com as IAs salvas) | — |
| Admin › Fichas (prévia), Arena "gravar ordens", boneco atacante | `KitsReview.jsx`, `Arena.jsx`, `TestInArena.jsx` | script, de propósito (mostrar a ficha ou repetir ordens) | script |

**Por que a Efetividade fica no `tactical_stand`:** a nota mede o quanto a build aguenta e causa contra bonecos fixos;
com um cérebro que foge, a nota passaria a medir a fuga (e a corrida das barras pararia antes do fim), e mudaria quando
uma IA nova fosse aceita. A troca parada é a mesma para todo campeão e todo dia.

**As tabelas do draft passam a `auto`**: o custo coube (`DraftBrainCostTest`, opt-in, numa cópia: 40 pares de Lutadores
e Tanques, 3 largadas, minuto 10): **17,5 s com o Automático contra 11,5 s com o `tactical_stand` (1,52×)**; a corrida
muda em média 11,8 pontos por par e o favorito troca em 1 dos 40. O Automático escolhe a IA de cada campeão (aqui, as
de classe), e quem corre fica com a Vida que tinha (a corrida já tratava "quem segura nunca zera").

**Testes**: `AiCampaignTest.everyPlayerProcessIsAutomaticAndTheRagdollsStand`; os testes que passam pelos serviços
trocados (`BuildScoreTest`, `EffectReferenceTest`, `EffectTestsTest`, `LifebarRaceTest`, `MatchDraftTest`,
`MacroTrainingTest`, `TwoTempoTest`, `PublicProfileTest` e outros: 77 testes) passaram.

## Parte 6: dragões, almas, Barão e Ancião legíveis e presentes (✅)

**O que a revisão achou, e o que mudou:**

| achado | agora |
|---|---|
| o `ObjectivesPicker` só tinha caixas e um `select`, sem texto de efeito | um cartão por objetivo, com ícone, nome e o efeito numa linha com o valor no minuto |
| o Ancião não entrava em `objectiveParts` | entra: 450 de dano verdadeiro em 3 s, reaplicado, lido em 10 s de troca (1500), e "executa abaixo de 15%" |
| o Oceano não virava número | o dragão Oceano: 2,8% da Vida perdida a cada 5 s, lido com 50% perdida, em 10 s de troca; a alma Oceano: a cura no minuto |
| o planejador não recebia os objetivos (`/api/calculate`, `/api/relevance`, `/api/passives/*`) | o `payload` leva `objectives`; o motor soma a fonte "Objetivos" |

**O seletor** (`components/ObjectivesPicker.jsx`, um componente só, usado no Planejador, na Arena — por lado —, na
Efetividade — "Testar agora" — e no Draft e partida — por time, durante a partida, guardado em
`live_game.objectives`): os 3 dragões lado a lado; a faixa da alma só abre com os 3, e escolhe-se entre eles; Ancião e
Barão na mesma faixa. Ícones: SVG simples por elemento (`ObjectiveIcon`) até os do jogo chegarem. **Segurar o cartão
(ou passar o mouse)** abre a descrição inteira: o efeito do jogo, o valor com a build aberta e a fonte do seed — por
exemplo "Alma Oceano: … Com esta build: cura 518 em 4 s. Fonte: print do usuário (05/11/2026)". Sem build (Arena,
Draft), a linha diz o efeito sem número. Em tela estreita os cartões ficam um por linha (a alma em 3).

**No motor** (`engine/ObjectiveRules`, puro): `parts` dá cada fonte (dragão por elemento, alma, Ancião, Barão) com os
seus status e o seu **efeito de luta** (`Fight`: dano, dano verdadeiro ou cura, o valor em 10 s de troca, cada ativação
e quantas); `apply` soma os status ao ponto do minuto como **uma contribuição "Objetivos"** (`ItemContribution.objective`,
segurada como runa, o ouro pelos preços dos status) e guarda `objectiveDelta` e `objectiveParts`. O `TimelineEngine`
chama `apply` em cada ponto da série quando o `EngineInput` tem objetivos (o `PlannerService` converte os
`objectives` do `Build`). A regra é **a mesma das lutas**: `RiftRules.dragonDelta` agora delega para
`ObjectiveRules.dragonDelta` (os % leem o total depois de todas as outras fontes e do fixo dos dragões). Com isso eles
aparecem em **Relevância** (a contribuição entra no relatório), **Ouro efetivo** (no bloco das runas e da Forja),
**Status** e **passivas** (o `snapshot` passa pelo mesmo motor). No relatório do momento, a linha "Objetivos" lista cada
fonte com o ouro dos status e, para Infernal (alma), Oceano e Ancião, o "efeito de luta" com o valor em 10 s.

**Paridade JS × Java**: o frontend (`src/objectives.js`, puro) espelha `ObjectiveRules.parts`. `ObjectivesParityTest`
calcula 8 casos (cada dragão, dois, três, cada alma, alma sem os três, Ancião, Barão, acúmulos acima de 1) com as
regras do seed e confere a fixture `frontend/test/objetivos-paridade.json` (regras + casos + o esperado;
`-Dparity.write=true` a gera de novo); `frontend/test/objectives.test.mjs` (`npm test`) confere o JS contra a mesma
fixture. `ObjectivesPlannerTest`: com objetivos, o total de cada status é o sem objetivos mais o delta, a contribuição
"Objetivos" tem ouro, o Ancião e o Oceano têm efeito de luta, e a relevância vê a fonte.

**A Mão do Barão continua `EST`** (`objetivos_7_3d.yml › mao_do_barao.dda_pdh_por_min`: 24 + 2/min de DdA, 40 + 3/min de
PdH). **Pedido de print:** o status de um campeão (DdA e PdH na ficha) logo antes de pegar o Barão e de novo 2 minutos
depois, com o minuto da partida visível — dá a base e o crescimento por minuto. O cartão do Barão e a descrição dizem
que é estimado.
