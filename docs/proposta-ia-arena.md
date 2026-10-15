# Proposta: IA que aprende na Arena (substituir regras fixas por política treinada)

> Documento para o Claude Code. Contexto, decisões e plano em fases para o repositório
> `BigMiranda/Wild_Rift_Build_Manager`. Trabalhe uma fase por vez, com testes, e pare ao fim de cada fase para revisão.

## Andamento

- **Fase 0 — feita.** `ArenaBenchmarkTest` (`./mvnw test -Dtest=ArenaBenchmarkTest -Darena.bench=true`) e
  `ArenaRegressionTest` (mesma luta duas vezes e em paralelo com os mesmos inputs ⇒ resultado idêntico).
  Achado: o id do desenho do elemental do Ornn usava `System.identityHashCode` e mudava a cada execução; agora é
  um contador. Números (10 núcleos / 20 threads, sem frames): ~14 mil s simulados/s em 1 thread
  (~300 lutas de 60 s/s), ~130 mil s simulados/s em 20 threads (~2.900 lutas de 60 s/s, ~9x).
- **Fase 1 — feita.** Pacote `arena/ai`: `Policy`, `ScriptedPolicy`, `Observation` (71 features, preenchida só
  quando lida), `ActionSpace` (16 ações: habilidade s no inimigo k, atacar k, parado), `ActionMask` (o `Turn` do
  `Arena`). `Arena.Input.policy` (null = `ScriptedPolicy(priority)`). Resultados idênticos aos de antes: golden de
  35 cenários em `src/test/resources/arena/golden-fingerprints.txt` (SHA-256 do `Result` inteiro, com frames).
  Diferenças em relação ao texto abaixo: o slot 0 de inimigos é o alvo atual (foco > alvo fixo > mais próximo), os
  demais por distância; a decisão ainda acontece a cada passo (o limite de 0,1 s mudaria os resultados, fica para
  quando houver política treinada); `Slot.brain` fica para a Fase 4/5.
- **Fase 2 — feita.** Ações novas no `ActionSpace` (32 no total): reconjurar s, ativo/feitiço i (no alvo atual),
  Flash para fugir, recuar (para longe do centro dos inimigos; ao longo da parede quando encostado; inválido quando
  encurralado no fim da rota), kite em k (ataca quando o alvo não alcança durante a animação; senão fica no limite
  do alcance; se não for mais rápido que o alvo, ataca sempre que pode) e desviar (passo lateral ao projétil de linha
  ou elemental inimigo que vai acertar). Observação no esquema 2 (85 features: ataque pronto, ativos e seus estados,
  ameaça de projétil). `TacticalPolicy` com as regras (desviar, Barreira/Curar < 35%, recuar < 25% quando perdendo,
  Flash para fugir < 15%, reconjurar, ativos de dano, Incendiar num alvo < 50%, Exaustão quando ferido, Fantasma
  para alcançar um alvo < 50%, depois o roteiro; à distância faz kite). `Slot.brain` (`"script"` padrão,
  `"tactical"`) e seletor "Cérebro" na tela. O roteiro padrão continua idêntico (golden intacto). Em 270 duelos com
  feitiços, tático × roteiro: 144 V / 122 D / 4 E, contra 138 / 132 / 0 do roteiro × roteiro.
  Limitação: a arena não tem torre nem fonte, então recuar só adia a luta (o perseguidor alcança no fim da rota).
- **Fase 3 — feita.** `Network` (MLP), `NeuralPolicy` e `Trainer` (OpenAI-ES: amostragem espelhada, postos
  centrados, Adam, decaimento de pesos; paralelo e determinístico pela semente, com qualquer número de threads).
  Decisões da proposta que mudaram com os experimentos:
  - a rede é **residual sobre o Tático**: a ação que o `TacticalPolicy` escolheria ganha +0,5 no placar; com pesos
    zero ela joga como o Tático, e o treino só aprende quando fazer outra coisa;
  - rede **85 → 16 → 32** (~1,9 mil pesos) com sigma 0,1 e taxa 0,1: a 85 → 32 → 32 → 32 (~4,9 mil) e taxas
    menores não saíam do lugar (ficavam imitando o roteiro, ~54% "melhor" = empate);
  - a IA decide a cada 0,1 s (`Policy.interval()`); roteiro e tático continuam a cada passo.
  Teste offline Darius × Vayne (Darius com DdA ajustado para a luta ser apertada): 30 gerações em ~7 s; nos 24
  cenários de validação nunca vistos, melhor que o roteiro em 67% e vence 50% (roteiro: 0%); melhor que o Tático
  em 79%. `Gym` (arena como ambiente: episódio = luta com um campeão controlado) e `ArenaScore` (objetivos e
  recompensa = objetivo + 500 × vantagem de vida).
- **Fase 4 — feita.** Tabela `arena_policy` (esquema v14, pesos em JSON com `obs_schema` e tamanho das ações para
  barrar pesos incompatíveis), `PolicyRepository`, `ArenaTrainingService` (um treino por vez, em segundo plano) e
  endpoints `POST /api/arena/train`, `GET /api/arena/train[/{job}]`, `DELETE /api/arena/train/{job}` (cancelar),
  `GET /api/arena/policies?champion=`, `DELETE /api/arena/policies/{id}`; `Slot.brain = "ai:<id>"`. Episódios:
  24 de treino com inícios variados, minutos das builds −3/0/+3 e oponentes roteiro, tático, hall da fama (as 2
  últimas versões aceitas dos campeões inimigos) e self-play em espelho (a própria rede de 5 gerações antes); 8 de
  seleção (escolhem a melhor geração) e 16 de validação com outra semente (só roteiro e tático). Aceite: melhor que
  o roteiro e que a versão aceita anterior em ≥ 55% da validação (as não aceitas também ficam salvas, marcadas).
  Ao vivo (Darius de CdC × Rumble Carry): 30 gerações em ~10 s; a IA aprendeu a entrar com Flash e Fantasma e a
  guardar o R (execução) para quando a Hemorragia está cheia, em vez de usá-lo no primeiro contato; vence 100% da
  validação contra 0% do roteiro.
- **Fase 5 — feita.** Seletor de cérebro com as IAs do campeão ("IA v1 · objetivo · melhor que o roteiro N%"),
  painel "Treinar IA" (campeão, objetivo, gerações, população, semente; barra, gráfico de progresso, números da
  validação, usar / apagar versões), "→ linha do tempo" (habilidades, reconjurações, ativos e feitiços que o
  cérebro usou viram ordens, e o cérebro volta ao Roteiro; movimentos não são exportados) e "Decisões da IA" no
  relatório (cada mudança de decisão com os 3 melhores placares).
- **Fase 6 — não feita** (opcional): equipes treinadas juntas, mira com antecipação, política única com one-hot do
  campeão, ponte Python/PPO. O `Gym` roda episódios inteiros; para PPO faltaria expor um passo a passo.

## 1. Como a "IA" funciona hoje

Há duas peças, e nenhuma aprende de verdade:

**a) Piloto automático por regras** — `Arena.step(U u)` (backend/.../arena/Arena.java):
1. Executa a próxima ordem da linha do tempo do cenário, se houver.
2. Se `autoAbilities`, percorre `priority` fixa (`R, 3, 1, 2`) e lança a primeira habilidade que estiver
   pronta (`isReady`) e no alcance (`inUseRange`).
3. Senão, persegue o alvo (`target(u)`: foco > alvo fixo > inimigo mais próximo) e dá ataque básico.

Consequências: nunca recua, não faz kite, não guarda CC para interromper/combo, não reage a vida baixa,
não esquiva projéteis, não usa itens/feitiços sozinho, não escolhe o alvo mais vantajoso.

**b) "Busca automática"** — `ArenaService.search`: hill-climbing (1+1) aleatório sobre a
*linha do tempo* de UM campeão (adiciona/remove/move/troca ordens em segundos fixos), contra oponentes fixos.
É um otimizador de roteiro em malha aberta: o resultado vale só para aquele cenário exato (posições, builds,
minuto). Se o inimigo fizer algo diferente, o roteiro aprendido não reage, e nada é reaproveitado entre cenários.

Pontos a favor do código atual, que tornam o aprendizado viável:
- Simulação **determinística** (sem `Random` no `Arena`), passos de `DT = 0.02s`, e `withoutFrames()` para rodar rápido.
- `Arena.Input` / `Fighter` são praticamente só-leitura durante a luta (o único write é `in.f.index = i`),
  então **várias `Arena` podem rodar em paralelo** com os mesmos inputs (confirmado por teste).
- Já existem funções objetivo (`score`: win_fast, damage, survive, team) para servir de recompensa.
- 6 campeões com ficha completa em `kits_7_3.yml`: Ornn, Darius, Renekton, Rumble, Vayne, K'Sante.

## 2. Ideia central

Trocar "**o que fazer em cada segundo**" (roteiro) por "**o que fazer dado o estado da luta**" (política).
A política é uma pequena rede neural: recebe um vetor de observação e devolve a próxima ação. Ela é treinada
por **neuroevolução com self-play**, em Java puro, sem dependências novas, reaproveitando o simulador como
ambiente de treino. Assim a IA:
- reage ao que acontece (malha fechada),
- generaliza entre posições, minutos e builds (porque vê features, não o relógio),
- melhora contra versões anteriores de si mesma e contra o piloto por regras.

### Por que neuroevolução (e não PPO/DQN ou MCTS) como primeiro passo
| Opção | Prós | Contras |
|---|---|---|
| **Neuroevolução (CMA-ES / ES simples) — recomendado** | Java puro; usa só a pontuação final da luta (recompensa esparsa não atrapalha); paralelizável trivialmente; determinístico por seed; cabe no `search` existente | Menos eficiente em amostras que RL com gradiente; rede precisa ser pequena (≤ ~5k pesos) |
| PPO via Python (Gymnasium + Stable-Baselines3) | Estado da arte, aprende políticas maiores | Precisa ponte Java↔Python (HTTP/py4j), outro runtime, mais infra; mais difícil de depurar |
| MCTS em tempo de execução | Joga muito bem sem treino | Exige clonar o estado inteiro da `Arena` (fila `timed` cheia de lambdas → difícil), lento por decisão |

Deixar a porta aberta para PPO depois: a Fase 1 cria uma interface de ambiente que serve aos dois.

## 3. Arquitetura proposta

```
arena/
  ai/
    Policy.java            interface: Decision decide(Observation obs, ActionMask mask)
    ScriptedPolicy.java    o comportamento atual de step() extraído sem mudanças (baseline)
    NeuralPolicy.java      MLP pequena (ex.: N_obs → 64 → 64 → N_ações), tanh, pesos em double[]
    Observation.java       montagem do vetor de features (normalizadas em [-1, 1])
    ActionSpace.java       lista discreta de ações + máscara de ações válidas
    Trainer.java           CMA-ES (ou OpenAI-ES) + self-play + hall da fama
    PolicyRepository.java  persistência em SQLite (tabela arena_policy)
```

### 3.1 Ponto de decisão
- `Arena.step` passa a chamar `u.policy.decide(...)` quando o campeão está livre (`canAct()` e
  `now >= busyUntil`), **no máximo a cada 0,1 s** (decisões a cada `DT` são caras e desnecessárias).
- Ordens explícitas da linha do tempo continuam tendo prioridade (o usuário ainda pode roteirizar).
- `Arena.Input` ganha `Policy policy` (default `ScriptedPolicy`), e `ArenaModel.Slot` ganha
  `String brain` (`"script"` | `"ai:<id>"`).

### 3.2 Observação (exemplo inicial, ~40–60 floats, normalizados)
Próprio: vida %, escudo/vida máx., recurso %, nível/18, cada cooldown (1,2,3,R) normalizado e flag "pronto",
cargas, alcance de ataque, estados (atordoado, no ar, invisível, empoderado, janela de reconjuração aberta),
itens/feitiços ativos prontos.
Por inimigo (ordenar por distância; fixar K=2 ou 3 slots, preencher com zeros): vida %, escudo, dist/1000,
ângulo relativo (sin, cos), cooldowns conhecidos, se está em CC, se está ao alcance de cada habilidade minha.
Aliados (K slots): vida %, distância.
Ambiente: distância à parede mais próxima (importa para Condenar/Vayne e empurrões), projéteis inimigos
vindo na minha direção (dist. mais próxima ao meu caminho), tempo restante/duração.

### 3.3 Ações (discretas, com máscara)
`atacar alvo k`, `habilidade s no alvo k` (s ∈ 1,2,3,R), `reconjurar s`, `item/feitiço ativo i (no alvo k)`,
`aproximar de k`, `recuar` (afastar do centro de massa inimigo), `kite` (afastar mantendo alcance de ataque),
`desviar` (passo lateral ao projétil mais próximo), `parar`.
Máscara: zera ações impossíveis (não aprendida, em recarga, sem recurso, alvo inválido) usando
`isReady`, `inUseRange`, `rank` etc. A rede só escolhe entre ações válidas (argmax dos logits mascarados
na avaliação; amostragem com temperatura só se usar RL de gradiente).

Habilidades com mira (linha/cone/área) mirar inicialmente na posição atual do alvo, como hoje; numa fase
posterior, adicionar "mirar com antecipação" (posição prevista pela velocidade) como variante da ação.

### 3.4 Treino (Trainer)
- Algoritmo: CMA-ES diagonal ou OpenAI-ES (população 32–64, sigma 0.05–0.1), pesos iniciais pequenos.
- Avaliação de um indivíduo = média em **vários cenários** (≥ 8): posições iniciais variadas, minutos de
  build diferentes, oponente sorteado entre `ScriptedPolicy` e o **hall da fama** (melhores versões
  anteriores). Isso evita decorar um cenário só e evita ciclos de self-play.
- Recompensa: reutilizar `ArenaService.score` (objetivo escolhido) e somar termos pequenos de modelagem
  (ex.: diferença de vida % ao fim) para ter sinal mesmo sem vitória. Cuidado com reward hacking: validar
  vendo as lutas.
- Seeds: um `java.util.Random(seed)` só no Trainer (sorteio de cenários e ruído); a luta segue determinística.
- Paralelismo: `ExecutorService` com `availableProcessors()` threads, uma `Arena` nova por avaliação.
- Critério de aceite de uma nova versão: vencer o campeão atual (e o script) em ≥ 55% de um conjunto de
  cenários de validação **fixo e separado** do treino.

### 3.5 Persistência e API
- Tabela `arena_policy(id, champion, objective, version, fitness, winrate_vs_script, weights_json,
  obs_schema_version, created_at)` via `SchemaManager` (nova migração).
- Endpoints em `ArenaController`:
  - `POST /api/arena/train` → inicia treino em background (campeão, objetivo, gerações, população, seed);
    retorna id do job.
  - `GET /api/arena/train/{job}` → progresso (geração, melhor fitness, winrate vs script).
  - `GET /api/arena/policies?champion=` → versões salvas.
- `obs_schema_version` evita carregar pesos incompatíveis se as features mudarem.

### 3.6 Tela (frontend/src/pages/Arena.jsx)
- Em cada slot: seletor "Cérebro: Roteiro / IA vN (fitness, % vs roteiro)".
- Painel "Treinar IA" ao lado do `SearchPanel` atual: campeão, objetivo, gerações; gráfico de progresso
  (o `progress` do search já tem formato parecido).
- Botão "Converter em linha do tempo": roda a luta com a IA e exporta as decisões tomadas como `Action`s,
  para o usuário ver/editar o que a IA fez (explicabilidade e compatibilidade com o fluxo atual).
- No log de eventos, registrar a ação escolhida pela IA e o motivo resumido (top-3 logits).

## 4. Plano em fases (cada fase entrega algo testável)

**Fase 0 — Medir.** Benchmark: quantas lutas de 60 s por segundo com `withoutFrames()` (1 thread e N threads).
Teste que roda a mesma luta em paralelo e confirma resultados idênticos (garante thread safety).
Isso define o orçamento de treino (pop × cenários × gerações).

**Fase 1 — Extrair a política, sem mudar comportamento.** Criar `Policy`, `ScriptedPolicy`, `Observation`,
`ActionSpace`. Mover a lógica de decisão de `step()` para `ScriptedPolicy`. **Todos os testes de
`ArenaTest` devem continuar passando e os resultados das lutas devem ser idênticos** (adicionar um teste de
regressão: cenário fixo → mesmo `Result` antes/depois).

**Fase 2 — Ações novas no script.** Implementar `recuar`, `kite`, `desviar`, uso automático de itens/feitiços,
e dar ao `ScriptedPolicy` regras simples para elas (ex.: recuar com vida < 25%). Isso já melhora o oponente
de treino e valida que as ações funcionam no simulador.

**Fase 3 — Rede + treino offline.** `NeuralPolicy` + `Trainer` (CMA-ES/ES) rodando por teste/CLI, para um
duelo 1v1 (ex.: Darius × Vayne). Meta: superar `ScriptedPolicy` em ≥ 60% dos cenários de validação.
Salvar pesos em JSON.

**Fase 4 — Self-play, hall da fama, persistência e API.** Tabela, endpoints, job em background, versões.

**Fase 5 — Tela.** Seletor de cérebro, painel de treino, exportar decisões como linha do tempo.

**Fase 6 (opcional) — Avançado.** Equipes (2v2+), mirar com antecipação, política única para todos os
campeões com one-hot do campeão na observação, ou ponte Python/PPO usando a mesma interface de ambiente
(`reset(seed)`, `step(ação) → obs, recompensa, fim`).

## 5. Restrições do projeto (respeitar)
- Java 11 / Spring Boot 2.7, sem novas dependências pesadas (implementar MLP e ES na mão; são ~200 linhas).
- Manter o estilo do código: comentários em inglês nos `.java`, textos de UI e mensagens ao usuário em
  português (via `i18n.js` no frontend).
- Determinismo: mesma seed ⇒ mesmo treino ⇒ mesmos pesos.
- Não quebrar cenários salvos: `Slot.brain` ausente = `"script"`.

## Rodada 2 — remover a busca, prioridades do time e treino em cadeia

Andamento, parte a parte (cada parte é um commit no `arena-ia`).

### Parte 1 — Remover a busca automática ✅
- Backend: saíram `ArenaService.search`, `SearchRequest`, `SearchResult`, `mutate`, `SPELLS` e os auxiliares
  privados que só a busca usava (`simulate`, `score`, `summary`, `copy` de `Action`), além de `POST /api/arena/search`.
  Os comentários que citavam a busca (`withoutFrames`, `prepare`, `Policy`, `ArenaScore`, `ArenaModel`) agora citam o treino.
- Frontend: saiu o `SearchPanel`. As chaves reaproveitadas pelo painel de treino foram renomeadas (pt e en):
  `arena.search.obj.*` → `arena.obj.*`; `arena.search.{fighter,objective,seed,seedTitle}` → `arena.train.*`
  (o texto da dica da semente passou a falar de lutas e variações). O CSS `.arena-search` virou `.arena-train`.
- Cenários salvos: `Scenario` nunca teve campo da busca (a busca recebia o cenário no corpo da requisição), então
  não há nada a migrar.
- `./mvnw test` (94 testes, golden intacto) e `npm run build` limpos.

### Parte 2 — Prioridades do time ✅
- Modelo: `ArenaModel.TeamPlan` (`focus`, `order`, `shared`, `switchBelow`, `chainCc`), `Scenario.teams` e
  `Slot.followTeam` (padrão `true`). O `TeamPlan` de cada lado vai para o `Arena.Input` (o mesmo objeto para os
  campeões do lado), então as cópias dos episódios de treino já o levam, sem mudar o `Gym`.
- Compatibilidade: **`Scenario.teams` é `null` por padrão** (não `{new TeamPlan(), new TeamPlan()}`). Sem `teams` no
  JSON nada muda: cada campeão escolhe o próprio alvo como antes. Cenários novos criados na tela já vêm com os dois
  planos; um cenário antigo mostra um aviso e o botão "Usar prioridades do time". O golden não mudou.
- `Arena.target(u)`: foco da ordem > (se `followTeam` e há plano) alvo do plano > `Slot.target` > mais próximo.
  - Escolha refeita a cada 0,25 s (`PICK_EVERY`), ou na hora se o alvo morreu ou sumiu. Histerese: o novo
    só substitui o atual se for 10% melhor no critério (`PICK_MARGIN`).
  - `shared=true`: um alvo por lado, medido do centro dos campeões que seguem o plano. `shared=false`: cada um
    aplica o critério a partir de onde está.
  - Desempate: a `order`, depois a distância, depois o índice.
  - `switchBelow`: um inimigo ao alcance de ataque (+150) de quem segue o plano e abaixo da fração vira o alvo. Se o
    atual também está abaixo, fica o atual.
  - `lowest_effective_hp` = (vida + escudo) ÷ fração do dano do lado que passa pelas resistências do inimigo.
    Cada aliado vivo pesa pelo seu DA + PH, dividido em físico e mágico nessa proporção, com a sua penetração.
    É uma estimativa pelos atributos, não pelo dano já causado.
  - `most_damage`: quem mais causou dano até agora.
- `chainCc`: na máscara (`Turn.valid`), vale para Roteiro, Tático e IA. Uma habilidade que imobiliza fica inválida
  enquanto o alvo tiver mais de 0,3 s (`CHAIN_SLACK`) de controle restante.
  - Conta como controle: atordoamento, arremesso, ou puxão/empurrão ainda em curso.
  - Conta como habilidade que imobiliza a que tem, em qualquer nível da ficha, `atordoa`, `arremessa`, `puxa`,
    `empurra` ou `forma: empurrao`.
  - Ordens explícitas da linha do tempo não são bloqueadas.
  - Conta o controle de qualquer um do lado, inclusive o do próprio campeão: sobrepor o próprio controle também
    desperdiça.
- `Observation.SCHEMA_VERSION` não mudou. A IA vê o foco do time porque o slot 0 de inimigos é o alvo atual. As
  features opcionais ("é o foco do time", "aliados mirando nele", "CC de aliado restante") **não** foram feitas
  nesta parte.
- Tela:
  - Em cada lado, um bloco "Prioridades do time": critério, compartilhar o foco, trocar abaixo de X%, encadear
    controle e a ordem dos inimigos, que se arrasta ou se move com ‹ ›.
  - Em cada slot: "seguir o time" (só quando há planos) e o editor da ordem das habilidades (`priority`).
  - Ao remover um campeão, a `order` dos planos é reindexada.
- Testes (`ArenaTeamPlanTest`):
  - 2v2 sem plano (cada um bate no da frente) e com `lowest_hp` compartilhado (só o de menos vida apanha);
  - `order`, `followTeam=false` e `nearest` não compartilhado igual a sem plano;
  - troca abaixo de 25% (o alvo baixo morre antes);
  - `chainCc`: o puxão do Darius só fica liberado nos últimos 0,3 s do atordoamento do Renekton;
  - JSON antigo sem `teams` → `teams == null`.

### Parte 3 — Treino em cadeia com platô ✅
- **Etapa reutilizável:** `ArenaTraining.trainStage(StageSpec, Listener)` é uma classe comum, testável sem Spring. Ela define:
  - campeão, objetivo e sementes;
  - a rede inicial;
  - as versões da cadeia para aliados e inimigos;
  - o platô e o limiar de aceite.
  O job individual e a cadeia usam a mesma etapa. A cadeia é a `ArenaChain` e roda na mesma thread única
  (`arena-training`).
- **Episódios na cadeia:**
  - Aliados usam sempre a versão atual da cadeia, ou o cérebro do slot enquanto não houver.
  - Metade das lutas de treino põe a versão atual da cadeia nos inimigos; a outra metade alterna Roteiro, Tático,
    hall da fama e self-play.
  - Seleção e validação usam só os aliados e inimigos atuais da cadeia.
  - Também se mede a validação contra a versão anterior dos adversários: `Stage.vsOldOpponents`.
- **Partida quente:** a etapa começa da versão aceita mais recente da cadeia. Na 1ª rodada, começa da última versão
  aceita salva do campeão para o objetivo. Uma versão incompatível (`obs_schema`, ações ou camadas) é ignorada:
  começa de zeros e grava um aviso em `Stage.warning`.
  - A versão a superar é a mesma da partida quente. Sem ela, é a melhor compatível salva (`policies.best`).
- **Mesmo campeão em dois slots:** a política é por campeão. Só o primeiro slot treina na rodada; o outro fica
  `skipped` com `sameAs`, e a versão vale para os dois (`Job.latest`).
- **Determinismo:** semente da etapa = `seed·1000003 + rodada·1009 + índice`. O hall da fama vem do banco, então
  "mesma configuração ⇒ mesmos pesos" vale partindo do mesmo banco (o teste usa dois bancos novos).
- **Fim da cadeia:**
  - `done`: rodadas máximas;
  - `converged`: uma rodada inteira sem nenhuma versão aceita;
  - `stopped`: "parar após o atual" (`stopping` enquanto a etapa termina, com validação e salvamento);
  - `cancelled`: cancelar agora, sem salvar a etapa;
  - `failed`: erro.
- **Platô:** `Plateau(pct, window, minGenerations)`, com padrão 2 / 6 / 8 e `pct = 0` desliga.
  - O `Trainer.Listener` ganhou `stop()`, separado de `cancelled()`: `stop()` encerra e segue para a validação,
    `cancelled()` aborta.
  - O treino individual também aceita o platô; `Job.plateau` diz se parou por ele.
- **Métrica do platô:** a seleção agora é medida em todas as gerações (antes, a cada 5), com **`SELECTION = 16`**.
  As duas candidatas são calculadas e mostradas (`Point.fitnessPct`, `Point.betterPct`).
  - No teste `plateauMetricsOnARealTraining` (Darius × Vayne, 20 gerações, população 16):
    - fitness normalizada: −144 a +265 pontos. Um único combate de seleção que vira de derrota para vitória
      (`win_fast`) desloca a média em centenas de pontos, então a "evolução mínima de 2%" não tem significado
      estável;
    - % de lutas de seleção melhores que o Roteiro: limitada a 0–100, em degraus de ~3 pontos, e com a mesma escala
      para todos os objetivos.
  - **Escolhida a segunda** (`ArenaTraining.METRIC = "better"`).
  - Ressalva: na mesma curva, a regra 2/6/8 parou na geração 10, e a métrica subiu de novo depois (de 34% para 41%).
    Com 16 lutas a métrica é discreta; a janela de 6 é o mínimo razoável. A tela deixa ajustar janela e mínimo.
- **Melhor geração:** a com mais lutas de seleção melhores que o Roteiro; em empate, a de maior fitness de seleção.
  Antes valia "a mais recente". Num duelo que o campeão sempre vence (100% em todas as gerações), isso deixava
  vitórias mais lentas substituírem a versão de partida, e ela perdia para a anterior em 100% da validação.
- **Banco (v15):** `arena_policy.chain_id`, `chain_round`, `team_plan` (JSON) e `parent_id`. O `SchemaManager` faz
  `ALTER TABLE` em bancos v14, e o `schema.sql` já cria as colunas. A migração foi testada numa cópia do banco real.
- **API:**
  - `POST /api/arena/train/chain` e `GET /api/arena/train/chain/{id}`;
  - `POST /api/arena/train/chain/{id}/stop-after-current`;
  - `DELETE /api/arena/train/chain/{id}`;
  - `GET /api/arena/train` lista jobs e cadeias, cada um com `kind` (`single` | `chain`).
- **Tela:** o painel "Treinar IA" tem os modos Um campeão / Em cadeia.
  - **Configuração da cadeia:** campeões com caixa de seleção e ordem arrastável; objetivo único ou por campeão;
    gerações máximas, população, semente e rodadas máximas; platô (%), janela e mínimo, com a frase explicativa
    (também no modo individual).
  - **Progresso da cadeia:**
    - rodada atual e gráfico da força por rodada (% da validação melhor que o Roteiro, com a linha de 55%);
    - uma linha por IA com status, geração com barra, minigráfico da métrica do platô e a última validação;
    - "Parar após o campeão atual" (vira "Parando após {campeão}…" e fica desabilitado) e "Cancelar agora";
    - ao terminar, o motivo e "Usar estas IAs no cenário".
  - O ícone do campeão é o avatar de iniciais que o projeto já usa; não há retratos.
  - O polling é de 1×/s.
- **Testes (`ArenaChainTest`):**
  - mesma semente ⇒ mesmos pesos, em 2 rodadas;
  - partida quente e `vsOldOpponents`;
  - versão incompatível ⇒ zeros e aviso;
  - "parar após o atual" salva a etapa e não inicia a próxima;
  - "cancelar" não salva nada;
  - convergência;
  - mesmo campeão em dois slots;
  - platô numa curva achatada, sem disparar antes do mínimo nem com ganho real;
  - etapa que para por platô e ainda valida;
  - o relatório das duas métricas.

### Depois da Rodada 2 — parar na vitória total
- **Novo critério de parada (padrão):** `stopWhen = victory`. O platô continua disponível como `stopWhen = plateau`.
- **O que é vitória total:** em cada luta, o lado da IA vence no final (`Result.winner` = o lado dela: todos os
  inimigos caem, mesmo que a própria IA tenha caído; empate por tempo ou com todos caídos conta meio). A etapa exige
  isso em **todas** as lutas de seleção de uma geração.
  Cada `Point` passa a ter `winPct`; nesse modo, ele é também a métrica de parada que aparece no minigráfico.
- **Etapa:**
  - A primeira geração com vitória total encerra a etapa, e **são esses pesos** que vão para a validação e o banco.
    Eles não passam pela escolha da "melhor geração".
  - A versão é aceita pela regra de 55% de sempre, ou se também vencer todas as lutas de validação. Uma vitória
    total confirmada não é descartada só por não ser mais rápida que a anterior.
  - Sem vitória total, a etapa vai até as gerações máximas.
- **Cadeia:**
  - Na vitória total, a etapa salva e a cadeia passa para o próximo campeão.
  - No início de cada etapa, se a versão atual do campeão já vence todas as lutas de seleção contra os adversários
    atuais, ele **não é treinado de novo**. A etapa fica `kept` ("mantida vN (já vence tudo)"), nada é salvo, e a
    versão vitoriosa continua sendo a da cadeia.
  - Se os adversários melhorarem e ele deixar de vencer tudo, volta a treinar.
  - Uma rodada sem versão nova aceita encerra como `converged`. Se todos foram mantidos, a mensagem é "todos já
    vencem todas as lutas".
- **Testes:**
  - a etapa para na primeira geração com 100% de vitórias;
  - a cadeia mantém a versão vitoriosa na rodada seguinte, sem salvar outra versão desse campeão;
  - a rodada em que todos já vencem converge.

## Rodada 3 — IA genérica, combos descobertos por busca e laboratório no planejador

Andamento, parte a parte (cada parte é um commit no `arena-ia`, com `./mvnw test` e `npm run build` limpos ao fim).

### Parte 0 — Correções da revisão da Rodada 2 ✅
- **Hall da fama:** `ArenaTraining.hallOfFame(lista, objetivo)` pega as 2 versões aceitas e compatíveis mais novas do
  objetivo da etapa; quando há menos de 2, completa com as mais novas de qualquer objetivo. Teste
  `hallOfFameTakesTheStageObjectiveFirst`.
- **Vitória total confirmada:** antes de parar, a geração com 100% nas lutas de seleção joga um segundo lote do mesmo
  tamanho, com outra semente (`seed·104729 + 31`). Só para se vencer todas as lutas dele também. Vale para o `kept`
  da cadeia (a versão de partida só é mantida se confirmar). `Point.confirmPct` guarda o resultado, medido só nas
  gerações com 100%. Testes: `stageStopsAtTotalVictoryWithThoseWeights` (a última geração confirmou, as anteriores
  não venceram tudo ou não confirmaram) e `aLuckyTotalVictoryIsNotConfirmedOnAnotherBatch`.
- **Self-play:** o snapshot guarda a rede; o `SelfPlay` monta a `NeuralPolicy` uma vez por snapshot (par rede →
  política numa `AtomicReference`), em vez de criar `NeuralPolicy` e `TacticalPolicy` a cada decisão. As lutas
  continuam iguais (a política é sem estado).
- **Frágil sobrescrito:** entra com a Parte A, que cria os eventos (`marca_sobrescrita`); o comportamento não muda.

### Parte A — Eventos estruturados ✅
- `FighterStats.events` (contadores por chave) e `FighterStats.eventSummary` (o resumo em palavras), documentados em
  `ArenaEvents`. Nenhuma luta muda: só contam.
  - **Marcas:** `marca_aplicada:<marca>`, `marca_consumida:<marca>:<fonte>` (`ataque`, `1`, `2`, `3`, `R`),
    `marca_sobrescrita:<marca>` e `marca_expirou:<marca>`. As marcas são as das fichas: Frágil (Ornn), Hemorragia
    (Darius: consumida quando a R usa os acúmulos), Dardos de Prata (Vayne; perder os acúmulos ao bater em outro alvo
    conta como expirada) e Instinto Destemido (K'Sante; reaplicar sobre uma marca ativa conta como sobrescrita).
    O nome vem da ficha, não do código: a marca da Vayne é o nome da habilidade de `marcas.habilidade` ("Dardos de
    Prata", e não "Setas de Prata" como no prompt).
  - **Frágil sobrescrito (Parte 0):** um `fragil` que não imobiliza, como o último golpe do W ou a ida da R, aplicado
    com um Frágil ainda ativo, gera `marca_sobrescrita:Frágil`. O comportamento é o mesmo de antes.
  - **Controle:** `cc_aplicado:<tipo>` (atordoamento, arremesso, puxao, empurrao, lentidao). Um imobilizar aplicado
    com controle ainda restante no alvo, ou até 0,1 s depois do fim, é `cc_encadeado`. Se desperdiça mais de 0,3 s
    (`CHAIN_SLACK`) do novo controle, é `cc_sobreposto`; os dois são exclusivos. `tempo_controlado` (s) fica em quem
    foi controlado e `controle_causado` (s) em quem aplicou o controle atual.
  - **Habilidades:** cada conjuração abre um registro que fecha na próxima do mesmo slot ou no fim da luta, como
    `habilidade_acertou:<slot>` ou `habilidade_errou:<slot>`. O mesmo vale para a reconjuração
    (`reconjuracao_acertou` / `reconjuracao_errou`). Não contam: as formas `proprio` e os avanços `sem_dano` sem
    `proximo_ataque` (o E do K'Sante), que não têm como acertar alguém.
  - **Reconjuração perdida:** `reconjuracao_perdida:<slot>:<motivo>`, quando a janela fecha (ou some, como no fim do
    elemental) sem uso. Os motivos, nessa ordem: "fora de alcance" (tentou e o elemental não estava ao alcance),
    "em controle", "ocupado com <estado>" e "não tentou". O motivo fica no terceiro campo da chave.
  - **Runas e itens:** `runa_ativou:<nome>` em cada efeito de runa que dispara (Aperto, Eletrocutar, Cometa,
    Suserano...), `item_ativou:<nome>` nos golpes, lâminas e ativos. Os gatilhos do planejador contam pelo tipo
    (runa / item / loja); os ativos contam em `useItem`, uma vez.
- **Golden:** `ArenaScenarios.fingerprint` tira `events` e `eventSummary` dos lutadores antes do hash. O golden não
  mudou.
- **Tela:** "Eventos da luta" no relatório, um resumo por campeão. Por exemplo, Ornn × Rumble: "Frágil: 3 aplicados,
  2 consumidos (ataque 1, 3 1), 1 sobrescrito · controle: 1,8 s · acertos: 3 2/2, 1 3/3, 2 3/3, R 1/1 · reconjuração
  perdida: R (não tentou)". O roteiro nunca reconjura a R, e agora isso aparece.
- Testes: `ArenaEventsTest` (Frágil consumido pelo ataque, tempo controlado = controle causado, reconjuração que
  consome o Frágil, sobrescrita, marcas da Vayne e do K'Sante, encadeado, resumo).

### Parte B — Fichas: mecânicas que o texto do jogo não diz ✅
Campos novos, documentados no cabeçalho do `kits_7_3.yml`:
- **`mobilidade`** por campeão (slot → avanco | salto | corrida | imune): Ornn E, Darius R (salto), Renekton E, Rumble
  W (corrida), Vayne Q e K'Sante W e E. `Arena.dashReady(u)` diz se um avanço ou salto próprio está pronto (o Flash
  fica à parte). Sem o campo, a arena lê das fichas (forma `avanco`, `alvo` com `salta`), de modo que um campeão novo
  já tem uma mobilidade lida da ficha.
- **`imune_deslocamento`** num avanço ou na reconjuração que avança: puxões, empurrões e arremessos no campeão são
  anulados só enquanto o avanço dura (evento `deslocamento_anulado:<tipo>`). `cc_durante_avanco`:
  - `continua_depois` (padrão): o avanço segue e o atordoamento cai no fim dele com o tempo que sobrar; se acabou
    antes, não cai. Evento `atordoamento_adiado`.
  - `interrompe`: como sem imunidade.
  - Aplicado na **cabeçada da R do Ornn** (`reconjurar`) com `continua_depois`. **Suposição a confirmar com o
    usuário** (anotada na ficha).
- **`imparavel`** (W do Ornn) passou a usar a mesma regra: um canal com `imparavel` é `imune_deslocamento` com os
  atordoamentos anulados (`ignora`); um canal pode ter `imune_deslocamento` com `cc_durante_avanco` também. O W
  joga igual (golden intacto). O antigo `unstoppableUntil` virou `immuneUntil` + `immuneCc`.
- **Ataque num alvo Frágil (Ornn):** `fragil.ataques.efeito` = `empurra` (padrão, distância em `empurra`, como antes)
  ou `arremessa` (com `duracao`; encadeia com um arremesso em curso pelo maior fim, a regra do `airborne`). **Pergunta
  aberta** no relatório final.
- **Lentidão e controle visíveis:** `U.slowPct()` (a maior lentidão ativa) e `U.slowUntil()` (quando ela acaba), além
  do `controlLeft(u)` que já existia. São calculados na hora a partir das listas do `U`, em vez de campos guardados,
  para não haver duas fontes da mesma informação.
- Testes (`ArenaSheetsTest`), com fichas sintéticas: atordoamento num avanço imune cai no fim com o resto do tempo;
  `interrompe` igual a sem imunidade; puxão anulado no avanço imune. Com a ficha real, em 86 tempos de um E do Darius
  em volta da cabeçada, alguns puxões caem nela e são anulados e a maioria não. O ataque no Frágil como arremesso
  aplica `cc_aplicado:arremesso` no lugar do empurrão.

### Parte C — Previsões genéricas e visão da IA (esquema 3) ✅
- **Previsões** (calculadas pela ficha, para cada habilidade sobre o alvo atual, o inimigo 0; `Arena.predict`):
  - `acerto_previsto` (`Self.hitChance`): o alvo precisa andar `escape` para sair da área e o efeito chega em `T`.
    É 1 se o controle dele dura até lá; senão anda `velocidade × tempo livre` (a lentidão vale até acabar, depois a
    velocidade cheia). Fica 1 se não alcança sair; senão `escape / distância que anda`, e ×0,8 se ele tem avanço
    próprio pronto. Fora do alcance, 0. Por forma: linha (largura), cone (borda ou alcance), area (raio), canal (o
    último golpe quando há, senão o 1º pulso), avanco (corpo do avanço; o Rolamento conta o ataque fortalecido;
    avanço sem dano = 0), campo (atraso), elemental (aceleração 450 → 1250), ataque, alvo, empurrao e tudo_ou_nada
    (com alvo: 1 no alcance).
  - **Avanços que dependem de terreno** (E do Ornn): procura a parede ou o pilar no caminho, como o próprio avanço
    para; sem terreno, 0. O acerto do corpo do avanço fica à parte em `Self.bodyHit` (fora do vetor).
  - `reconjuracao_acertaria` (`recastHit`): a volta do elemental, da posição dele na direção Ornn → elemental, desde
    que esteja ao alcance da cabeçada (senão 0, porque a reconjuração ainda espera); para o 2º avanço do Renekton, a
    mesma conta de um avanço. Com a janela aberta, `tempo_ate_efeito` e as marcas do slot são as da reconjuração.
  - `tempo_ate_efeito`, `encadeia_controle` (imobiliza e `T ∈ [controle − 0,3; controle + 0,1]`),
    `consome_marca` / `aplica_marca`, e o mesmo para o ataque básico (`attackConsumesMark` / `attackAppliesMark`):
    Frágil por imobilizar (efeitos, último golpe, onda do terreno, volta da R), o 3º dardo da Vayne, a marca do
    K'Sante pelo ataque, a Hemorragia pela R do Darius (`por_acumulo`).
  - **Decisões diferentes do prompt, com o motivo (medido no relatório Tático novo × antigo):**
    - **5 → 6 valores por slot:** acerto, reconjuração, tempo, encadeia, consome, aplica.
    - **Janela de percepção (`PERCEIVE` = 0,7 s):** o alvo só começa a sair nos últimos 0,7 s antes do efeito, a mesma
      janela em que o Tático desvia. Sem isso, a R do Ornn (2,6 s de viagem) dava acerto ~0,3 contra qualquer alvo e
      nunca era lançada, e o Tático novo perdia para o antigo.
    - **Avanço pronto corta para ×0,8, não ×0,5:** quase todo campeão tem um avanço pronto quase sempre, e ×0,5 levava
      o acerto de 1 para 0,5, abaixo do limiar.
- **Fatos novos:**
  - por alvo: `markLeft`, `marksConsumed`, `controlLeft`, `slowLeft`, `slowPct`, `resourcePct`, `overheatLeft`,
    `dashReady`, `flashReady`, `cleanseReady`, `attackRange` e `reachesMe`;
  - próprios: `overheatLeft`, `resourceZone`, `immuneDash`, `recastLeft`, `controlLeft` e `recentLoss` (vida perdida
    nos últimos 2 s, amostrada a cada 0,1 s).
- **Observation esquema 3** (`SCHEMA_VERSION = 3`, 153 entradas):
  - as 85 do esquema 2 nas mesmas posições, depois 24 previsões (4 slots × 6), 2 do ataque, 6 próprias e 12 por
    inimigo × 3;
  - `Observation.migration(2)` é o mapa explícito de índices (0..84 → 0..84);
  - as previsões e os fatos novos dos alvos são preenchidos à parte, na primeira leitura (`Observation.predicted()`;
    `vector()` chama sozinho), porque a maioria das decisões das regras não os lê.
- **Migração:** `Network.migrate(mapaEntradas, n, mapaSaídas, m, viés)`; as entradas novas pesam zero e as saídas
  novas ficam com viés `ActionSpace.NEW_ACTION_BIAS` = −5, para a Parte D. Ela acontece:
  - **ao carregar:** `PolicyRepository.find` migra os pesos uma vez e os guarda em cache; `Record.compatible()` aceita
    esquemas migráveis; `Record.migratedFrom` aparece na tela como "(esquema 2, lida no atual)";
  - **na partida quente:** a cadeia parte da versão migrada, porque `fits` lê as camadas já migradas.

  🎯 `ArenaPolicyMigrationTest`: uma rede v2 aleatória (85 → 16 → 32), com o golden gravado no código v2 (commit
  8a9f24b, `golden-ai-v2.txt`), carregada pelo caminho do repositório, **joga idêntica** em 4 lutas com cérebro `ai:`
  (Ornn × Darius, Vayne × Renekton, K'Sante × Rumble, 3v3).
- **A base residual guarda a versão das regras:** `Weights.base` = "tatico-previsoes" nas versões treinadas a partir
  de agora; ausente nas antigas, que usam `TacticalPolicy.legacy` (as regras de antes), para jogarem igual. Por isso a
  partida quente de uma versão v2 continua sobre as regras novas, que é a base nova do treino (Parte H).
- **Tático com previsões** (`TacticalPolicy`, as regras em bits para medir cada uma):
  - Purificar num atordoamento com mais de 1 s;
  - Flash para fugir ao perder mais de 35% da vida em 2 s estando atrás (vida % menor que a do alvo, a menos de 600);
  - reconjura só com `recastHit` ≥ 0,6;
  - ataca primeiro quando o ataque consome marca;
  - prefere a habilidade que encadeia controle;
  - lança habilidade de mira só com acerto ≥ 0,6 (ou com o corpo do avanço acertando, para o E do Ornn sem parede);
  - segura um imobilizar que o controle do alvo desperdiçaria em mais de 0,3 s.
- **Purificar** passou a existir na arena (`combate_7_3.yml`): remove atordoamentos, lentidões e Exaustão (não
  arremessos nem deslocamentos) e anula atordoamentos por 0,25 s. Pode ser usado atordoado: o `step` pergunta à
  política com só o Purificar válido (`stunnedTurn`), exceto no roteiro.
- **Tático novo × antigo** (`TacticalReportTest`, `-Darena.report=true`): 270 duelos, os 6 campeões nos dois lados, 3
  rotas e Flash + Incendiar / Barreira / Purificar.

  | Lado azul × lado vermelho | V | D | E |
  |---|---|---|---|
  | antigo × antigo (referência) | 123 | 145 | 2 |
  | **novo × antigo** | **139** | **125** | 6 |
  | antigo × roteiro | 144 | 120 | 6 |
  | **novo × roteiro** | **168** | **96** | 6 |
  | roteiro × roteiro | 138 | 132 | 0 |

  Por campeão, novo × antigo contra a referência: Ornn 30/15 (18/26), K'Sante 11/32 (2/43), Vayne 36/9 (36/9),
  Renekton 28/16 (29/15), Rumble 25/20 (27/18), Darius 9/33 (11/34).

  Ablação com as regras de antes: a de mira com o limiar original, sozinha, fazia 99/165 (era a causa da piora) e o
  resto sem ela 145/122. As correções acima resolveram isso.
- **Benchmark** (10 núcleos / 20 threads, s simulados por s):

  | Lutas | Antes da Rodada 3 (0ab31d1) | Depois da Parte C |
  |---|---|---|
  | roteiro, 1 thread | 15,0 mil | 13,9 mil (eventos) |
  | roteiro, 20 threads | 137 mil | 120 mil |
  | Tático, 1 thread | 4,5 mil | 3,2 mil |
  | Tático, 20 threads | 38 mil | 27 mil |

  Sem a leitura preguiçosa, o Tático caía para 2,0 mil.
- Testes: `ArenaPredictionTest` (vetor no tamanho e em [−1, 1]; E sem parede = 0 e com parede ≥ 0,6; avanço pronto
  reduz; marcas consumidas e aplicadas por habilidade; janela de encadeamento; volta do elemental ≥ 0,6),
  `ArenaPolicyMigrationTest` (🎯 e o mapa de entradas e saídas). O `tacticalBeatsTheScriptOverAllDuels` de antes
  passou (168 V / 98 D contra 138 / 132), e o Darius treinado ficou melhor que o roteiro em 77% da validação.

### Parte D — Combos como dados e como ações ✅
- **Modelo** (`ArenaModel.Combo`, `ComboStep`, `Condition`; tabela `arena_combo`, esquema v16, `ComboRepository`):
  - por campeão, com origem `descoberto` | `manual`, ativo, nota, build e minuto em que foi achado, `tempo_max`,
    condições de início (todas) e de aborto (qualquer uma), e métricas do banco de testes;
  - passos `habilidade:S` | `reconjurar:S` | `ativo:<nome>` | `atacar` | `mover:alvo` | `mover:longe` | `esperar`;
  - cada passo pode esperar, antes da ação, um evento: `alvo_pousa` (o arremesso acaba em até Δ), `alvo_no_chao`
    (no chão há Δ), `marca_aplicada`, `reconjuracao_acertaria` (do slot do passo, ≥ 0,6), `controle_restante` (≤ Δ),
    `ataque_pronto` e `tempo` (Δ s). Cada espera tem `max` (padrão 2 s; depois o passo segue);
  - o Δ é calibrável quando há `deltaMin` / `deltaMax`;
  - num `mover`, o evento é o fim do movimento.
- **Condições** como dados: `ai.Facts` dá nome aos fatos da observação (os da Parte C e mais `alvo.no_ar_restante`,
  `alvo.distancia`, `pronto:S`, `acerto:S`, `encadeia:S`, `consome_marca:S`, `proprio.vida_perdida_2s`...). É o
  mesmo vocabulário que as diretrizes da Parte H vão usar. `GET /api/arena/facts` lista os nomes, e a API recusa um
  fato desconhecido.
- **Executor** (`Arena.runCombo`):
  - o estado (`ComboRun`) fica no `U`, nunca na política;
  - aborta quando: o tempo esgota, o alvo morre ou some, o campeão entra em controle (atordoado, no ar,
    deslocado), uma ordem da linha do tempo é cumprida, um passo fica impossível (não aprendida, ou a recarga só
    volta depois do `tempo_max`) ou um `abortar_se` vale;
  - no aborto, a política volta a decidir no passo seguinte;
  - fora do alcance de uma habilidade, anda até ela;
  - vários passos seguidos acontecem no mesmo passo de simulação enquanto o campeão está livre;
  - `Result.combos` registra cada execução com os passos (tempo e texto) e o fim (concluído, ou abortado com o
    motivo). Na tela: "Combos executados";
  - eventos `combo_iniciado`, `combo_concluido` e `combo_abortado:<nome>`;
  - as conjurações do combo entram em `policyOrders`, de modo que "→ linha do tempo" as exporta.
- **Na IA:** `ActionSpace.COMBOS = 4`, as ações 32..35 (`SIZE = 36`), com os combos ativos do campeão em ordem de
  nota (`ComboRepository.forPolicy`; `ArenaService.prepare` os põe no `Input`).
  - Uma ação de combo é válida quando as condições de início valem e o 1º passo é possível agora (habilidade
    pronta e no alcance, janela aberta, ativo pronto, alvo ao alcance de ataque, ou um 1º passo que espera um
    evento).
  - Enquanto um combo roda, só valem parado/o próprio combo (continuar), `desviar` e `Flash para fugir`: a política
    só o interrompe com eles.
  - `Arena.Input.script` roda um combo desde o início, sem política (o boneco da Parte E).
- **Migração:** as saídas novas recebem viés −5 (`ActionSpace.migration(32)` é o mapa: as 32 ações ficam nos mesmos
  índices). 🎯 A IA v2 migrada continua idêntica (golden das 4 lutas com cérebro `ai:`) e o golden de 35 cenários
  também.
- **Testes:**
  - `ArenaComboTest`: passos em ordem com esperas; abortos por tempo, passo impossível, alvo morto e `abortar_se`; a
    política roda o combo como uma ação, sem poder lançar habilidades enquanto ele roda; condições de início; a
    biblioteca (ordem por nota, ativos, origem).
  - `trainedDariusBeatsTheScriptOnValidation`: passou a exigir a média de 3 sementes ≥ 60%. Com 4 saídas a mais, o
    ruído do treino muda de dimensão e uma semente só variou de 54% a 85% (8 sementes, média 62%).
  - O cenário "desequilibrado" do teste da cadeia ficou mais desequilibrado, para continuar sendo uma vitória total
    estável.
