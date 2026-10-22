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

### Parte E — Laboratório: o boneco de pano na Arena e a busca de combos ✅
- **Boneco** (`ArenaModel.Dummy`; `Slot.dummy` num cenário; `Arena.Input.dummy`):
  - **Status:** vida, armadura, RM, raio e velocidade (padrão 10000 / 100 / 100 / 80 / 350).
  - **Comportamentos** (`Arena.dummyStep`): `parado`; `anda`, de um lado para o outro da rota, virando a cada 1,5 s
    ou na parede; `foge`, recuando do atacante; `reage`, com Flash para fugir e Purificar pelas regras do Tático
    (`ComboBench.ReactPolicy`, com Flash + Purificar por padrão). Nunca ataca nem lança habilidades.
  - **Pode vestir uma build salva** (itens e runas num minuto): a build vira, em memória, uma build da unidade "Boneco
    de pano" com os status do boneco como base (`ArenaService.dummyInput`; `CombatService.Combatant.build` aceita
    uma build não salva).
  - **"Aplica efeitos":** os efeitos de quem acerta dos itens que ele veste (os de `combate_7_3.yml`: Feridas, redução
    de armadura e RM, corte de escudo, marcas) caem no campeão testado a cada X s, pelo mesmo `onHit` / `reaver` das
    lutas, ou "no máximo", com 12 acertos a cada 0,5 s.
  - **Presets:** "Tanque 100/100", "Mago 40/40" e "Atirador 30/30" (2500 de vida). Os bonecos salvos ficam na tabela
    `arena_dummy` (esquema v17).
- **`ComboBench`** (sem Spring, sem frames, determinístico):
  - **Modos:** `uma_vez`, fora de combate e com tudo pronto, da distância inicial, roda o combo como roteiro
    (`Input.script`) até o tempo máximo + 1,5 s para os danos ao longo do tempo; `repetir N s`, em que o combo volta
    sempre que pode começar e, entre um e outro, ataca (corpo a corpo) ou faz kite (à distância).
  - **Métricas:**
    - dano total, por fonte e por tipo (`FighterStats.damageByType`), após resistências, e DPS;
    - controle no boneco (s) e a maior janela sem controle durante o combo (`FighterStats.controlSpans`);
    - marcas consumidas, aplicadas e sobrescritas, encadeado e sobreposto, ativações de runa e item;
    - recargas no fim (`FighterStats.cooldownsLeft`) e "pronto de novo em";
    - os passos com o tempo de cada um.
  - **Nota** = dano + 400 × controle (s) + 150 × marcas consumidas, configurável. Contra os 4 comportamentos (padrão),
    em média ponderada ou no pior caso.
- **Busca de combos** (`ComboSearch`, genérica, por campeão, build e minuto):
  - feixe de K prefixos (64 por padrão), cada prefixo medido do início;
  - o próximo passo sai do retrato do estado em que o prefixo terminou (`ComboReport.snapshot`): habilidades prontas
    ou que ficam prontas em até 0,6 s, reconjurações abertas ou por abrir, o ataque, o Flash;
  - esperas só por eventos com Δ discreto ∈ {0; 0,1; 0,2; 0,35}, e só os que fazem sentido naquele estado: alvo no ar
    → `alvo_pousa` / `alvo_no_chao`; alvo controlado → `controle_restante`; reconjuração → `reconjuracao_acertaria`;
    sem marca → `marca_aplicada`;
  - poda dos prefixos 50% abaixo do melhor a partir do 3º passo (os mais curtos ainda estão montando o combo; com 35%
    desde o 1º, o puxão do Darius era podado logo de saída);
  - calibragem coordenada a coordenada dos Δ dos 8 melhores (grade de 0,05 s, duas passadas);
  - os 8 melhores distintos (pela sequência de ações; as esperas não contam) com nota, métricas por comportamento e a
    diferença para o melhor;
  - orçamento de avaliações; paralela e determinística (a mesma ordem com 1 ou 4 threads, coberto pelo teste).
  Os combos achados vão para a biblioteca com origem `descoberto`, a build e o minuto, as condições de início
  (`pronto:S == 1` das habilidades usadas, distância) e as métricas. Reexecutar para a mesma build e minuto
  substitui os achados anteriores; outra build ou minuto gera outra biblioteca.
- **API:** `POST /api/arena/lab/run`, `/lab/calibrate`, `/lab/search` (em segundo plano, um por vez; `GET` e
  `DELETE /lab/search/{id}`), `GET/POST/DELETE /api/arena/dummies`. `LabService` monta as entradas.
- **Tela:** a aba **Laboratório** na Arena tem:
  - build e minuto; o boneco (preset ou salvo, status, a build que veste, aplica efeitos a cada X s ou no máximo,
    salvar); modo, distância, comportamentos e média / pior caso;
  - o editor do combo (passos com a espera e o Δ, a ação, ↑ ↓ ✕), que pode vir da biblioteca ou da linha do tempo do
    cenário (as ordens do campeão da build viram passos, com esperas por tempo entre elas);
  - os botões Executar, Calibrar, Salvar/Pôr na biblioteca e Buscar combos (feixe, passos, orçamento, com progresso);
  - o resultado: o mapa animado do comportamento escolhido, a linha do tempo do combo (passos, controle e Frágil no
    boneco), o cartão de métricas com dano por fonte e por tipo, a tabela por comportamento e a dos combos achados
    com a diferença;
  - a biblioteca, com origem, nota, build/minuto e as ações ativar, testar, editar e apagar.
  A tela do cenário ainda não tem o boneco no seletor do slot; ele existe no modelo (`Slot.dummy`).
- **Migração** testada numa cópia do banco real (`SchemaMigrationCopyTest`, `-Darena.dbcopy=`): v15 → v17, as 16 builds
  e as 109 IAs intactas, todas lidas e migradas do esquema 2.
- **Testes:**
  - `ComboSearchTest`: o banco mede um combo do Darius (fontes, tipo, controle, janela livre, recargas,
    determinismo, os comportamentos fazem diferença).
  - 🎯 a busca no Darius acha uma sequência que bate o roteiro em dano contra o boneco parado: 1227 contra 1060
    ("1 → ataque → R → ataque → ataque → ataque"), igual com 1 ou 4 threads.
  - `ArenaDummyTest`: parado, anda e foge; reage purifica um atordoamento longo; os efeitos dos itens põem Feridas
    na Vayne.

### Parte F — A prova com o Ornn ✅
- **Combo manual** do usuário, só como referência (`OrnnProofTest.manual()`, origem `manual`; nunca entra na busca):
  `1 → R → (acertaria) 3 → ataque → (marca aplicada) R² → (pousa −0,25) ataque → 2 → (no chão) ataque`.
  - **Decisão nova:** o evento de espera `acertaria`, a habilidade do passo acertaria pela previsão ≥ 0,6, irmão do
    `reconjuracao_acertaria`. O E precisa esperar o pilar da Q existir (ele aparece 1,125 s depois do fim do
    projétil), e nenhum evento da lista cobria isso. A busca também passou a oferecer essa espera antes de uma
    habilidade.
  - Início: `alvo.avanco_pronto == 0` e Q, R, E e W prontas.
- 🎯 **Manual × boneco parado** (Ornn de teste, nível 13, ranks 4/3/2/2, sem itens): concluído, **3 Frágeis
  consumidos** (a volta da R e dois ataques; o Frágil foi aplicado pela ida, pela volta e pelo W) e **controle
  contínuo** de 1,98 s (o E no pilar) a 4,62 s (o pouso depois da volta, que encadeou antes do pouso, +30%).
  O simulador permite o combo como descrito. Teste `theManualComboConsumesThreeBrittlesWithUnbrokenControl`.
  - Calibrado nos 4 comportamentos, o manual tem nota 4411: parado e reage 4217 de dano, 3 Frágeis; foge igual. Contra
    o boneco que **anda**, o manual não fecha: o boneco sai da linha do elemental, e a reconjuração fica impossível
    (150 de dano). Isso derruba a média.
- 🎯 **Descoberta** (`-Darena.proof=true`; feixe 64, até 9 passos): 3294 combos medidos em 4,9 s (10 threads). O melhor
  tem **nota 5631 = 128% do manual calibrado**:

  | | passos | Frágeis (parado / anda) | controle (parado) | dano (parado / anda / foge) |
  |---|---|---|---|---|
  | manual calibrado | 1 → R → 3 → ataque → R² → ataque → 2 → ataque | 3 / 0 | 2,62 s | 4217 / 150 / 4217 |
  | achado #1 | R → 1 → R² → 3 → 2 → ataque → ataque → 1 → ataque | 3 / 3 | 2,06 s | 4370 / 4217 / 4057 |
  | achado #2 | igual ao #1, com os ataques e a 2ª Q trocados de lugar | 3 / 3 | 2,06 s | 4370 / 4217 / 4057 |
  | achado #3 | o #1 sem o último ataque | 3 / 3 | 2,06 s | 4290 / 4137 / 4057 |

  A busca preferiu lançar a R antes da Q: a Q chega com o elemental já passando e o segura, e a ordem funciona
  também contra o boneco que anda. O E vem depois da volta, no pilar da Q (`acertaria`). O controle é um pouco menor e
  o dano maior, sobretudo no pior caso.
- **Runas no mesmo combo** (`runesOnTheCombo`; busca refeita para cada runa). O Pós-choque não existe no
  `combate_7_3.yml`, então ficou de fora.

  | runa | manual: nota / ativações / dano da runa | melhor achado: nota / ativações |
  |---|---|---|
  | nenhuma | 4134 | 5631 |
  | Aperto dos Mortos-Vivos | 4171 / 1 / 50 | 5685 / **2** |
  | Suserano do Gelo | **4985** / 1 / 44 | 5675 / 1 |
  | Impacto Repentino | 4199 / 1 / 66 | **6031** / 1 |

  - **Aperto:** cabe 1 ativação no manual (a 1ª só 3 s depois de entrar em combate, e o combo dura ~5,3 s) e 2 no
    melhor achado, que estica o combo com mais ataques.
  - **Suserano:** é o que mais rende **no manual** (+851 de nota). A lentidão do campo de gelo depois do E segura o
    boneco que anda na linha do elemental e salva a reconjuração. No achado (que já não dependia disso) rende +44, e
    a ordem não muda.
  - **Impacto Repentino:** 1 por combo; a busca muda a ordem para "R → 1 → R² → ataque → 2 → 3 → ...". No modo
    `repetir` de 30 s, o manual roda uma vez só (a R tem 80 s de recarga), então 1 Impacto. Um combo de "E e
    ataques" repetido dá **2 Impactos em 30 s** (a recarga de 15 s é o limite).
  - **Avanços que armam a runa** (`dashedAt`, no fim de cada avanço): o E, a cabeçada da reconjuração da R e o Flash.
    O avanço do W é andar durante o canal, não um avanço, e não arma.
  - Com a build real "Big Carry v1" no laboratório ("Comparar runas", `POST /api/arena/lab/compare-runes`), o Suserano
    também é o melhor no combo dado (5511 contra 5454 a 5474), e o Impacto, de novo, leva a busca a outra ordem
    (6322).
- **Tela:** o botão "Comparar runas" no laboratório (o combo com cada runa principal e uma busca nova com cada uma).

### Parte G — Fugir: zona segura e o uso defensivo da R do Ornn ✅
- **Zona segura opcional** (`Scenario.safeZone` = `ArenaModel.SafeZone`: o lado, a profundidade a partir da ponta da
  rota desse lado e o dano por segundo nos inimigos dentro; 0 = só "chegou = escapou"):
  - um campeão do lado que chega vivo escapa (`FighterStats.escapedAt`, evento `escapou`) e sai da luta (não é mais
    alvo, não age);
  - a torre causa o dano a cada 0,5 s;
  - a luta acaba quando um lado não tem mais ninguém lutando (sem vencedor se os que restam escaparam);
  - o mapa desenha a zona, e a tela do cenário tem o bloco "Zona segura" (lado, profundidade, dano/s).
  Sem zona, nada muda (golden intacto; `escapedAt` está entre os campos descritivos da impressão digital).
- **Objetivo `escapar`** (`ArenaScore`): escapou → 50000 − 100 × tempo + vida restante; vivo no fim → 20000 + vida;
  morto → 100 × tempo de vida. Está na lista de objetivos do treino e da tela. A zona chega aos episódios de treino
  (`StageSpec.safeZone`, `Gym.Episode.safeZone`, cadeia).
- **Eventos novos para medir a fuga:** `reconjuracao_fuga:<slot>` (a cabeçada levou o Ornn para longe do inimigo
  mais próximo, mais de 50) e o `deslocamento_anulado:puxao` da Parte B.
- 🎯 **Ornn × Darius** (`ArenaEscapeTest.ornnFleesDarius`, `-Darena.report=true`): Ornn com 25% de vida e Flash; Darius
  250 atrás, com Fantasma e o Tático; torre azul 3100 adiante com 300 de dano/s. A IA foi treinada só com previsões e
  eventos ("Sozinha"; 40 gerações, população 24, objetivo `escapar`) e medida contra o Tático do Ornn nas mesmas 32
  largadas:

  | Ornn | fugas | mortes | R usada | cabeçadas para fugir | puxões anulados |
  |---|---|---|---|---|---|
  | Tático | 19/32 | 13 | 32 | 0 | 0 |
  | **IA (sozinha)** | **32/32** | 0 | 32 | 0 | 0 |

  A IA aprendeu a fugir sempre, mas não pelo caminho que o prompt esperava: as duas lançam a R no Darius (a ida
  desacelera o perseguidor) e nenhuma usou a cabeçada para ganhar distância nem anulou um puxão. O ganho veio de
  quando recuar e quando usar o Flash. A cabeçada só se afasta do Darius se o elemental já tiver passado o Ornn na
  direção da fuga, e a ação "reconjurar" não escolhe a direção. Isso fica registrado como limite da formulação das
  ações, não como regra a acrescentar.

### Parte H — Treino mais eficiente e níveis de ajuda ✅
- **Base residual da `NeuralPolicy`:** o Tático com previsões (desde a Parte C, `Weights.base`) e os combos da
  biblioteca como ações (Parte D).
- **Recompensa de eventos só no treino** (`ArenaTraining.REWARDS`, por prefixo da chave): marca consumida +150,
  sobrescrita −100, expirada −50, controle encadeado +80, sobreposto −60, reconjuração que acerta +100, combo
  concluído +50; mais a recompensa opcional de cada diretriz (evento `diretriz:<id>`). Os pesos são configuráveis
  por campeão (`arena_guideline.rewards`; sem eles, os padrões). Só a fitness do pool de treino soma esses pontos;
  seleção, validação, aceite e números salvos usam o objetivo puro.
- **Exercícios** (`Arena.Input`: `startHealth`, `startCooldowns`, `startResource`, `startAirborne`, `markedBy`), gerados
  pela ficha de cada campeão, sem lista por nome (`ArenaTraining.drillsOf`):
  - "alvo marcado com o ataque pronto", se a ficha tem marca (Frágil, dardos, marcas, Hemorragia);
  - "alvo no ar com 0,8 s restantes", se alguma habilidade imobiliza;
  - "reconjuração aberta": a 1ª parte é lançada no início, por uma ordem do episódio;
  - "vida baixa perseguido" (30%);
  - "ultimate em recarga" (40 s).
  Os primeiros 30% do pool (configurável, campo "exercícios %") viram exercícios, alternando entre eles. O Rumble,
  sem marca nem reconjuração, fica com os três restantes.
- **Níveis**, guardados na versão (`arena_policy.level` e `guidelines`, esquema v18, testado numa cópia do banco):
  - `sozinha`: previsões, eventos e exercícios, sem combos;
  - `descobertos`: mais os combos de origem `descoberto` da biblioteca;
  - `dicas`: mais as diretrizes e os combos manuais.
  Uma IA salva roda com os combos do seu nível (`ArenaService.levelCombos`) e, no nível `dicas`, com as diretrizes
  do snapshot (`GuidedPolicy`). A lista de versões mostra o nível e avisa "⚠ outras diretrizes" quando o snapshot
  difere das diretrizes atuais do campeão. A cadeia aceita o nível também.
- **"Comparar níveis"** (`POST /api/arena/train/levels`, em segundo plano): o mesmo treino (semente e orçamento) nos três
  níveis, com uma tabela por luta de validação: objetivo, vitórias, marcas consumidas, sobrescritas, controle
  encadeado e combos usados. Ornn × Darius (`ArenaLevelsTest.compareLevels`, `-Darena.report=true`; 30 gerações,
  população 24; os combos descobertos vêm de uma busca no boneco, e o nível dicas tem o combo manual e o preset):

  | nível | objetivo | vitórias | marcas consumidas | sobrescritas | encadeado | combos | melhor que o roteiro |
  |---|---|---|---|---|---|---|---|
  | sozinha | 92555 | 94% | 3,81 | 0 | 1,13 | 0 | 88% |
  | com combos descobertos | 98811 | 100% | 4,00 | 0 | 1,63 | 0 | 100% |
  | com dicas do usuário | 98858 | 100% | 4,00 | 0 | 1,75 | 0 | 100% |

  **Ressalva:** nenhuma versão usou combos nas lutas de validação. As ações de combo quase nunca ficam válidas: o 1º
  passo pede as habilidades prontas e no alcance, e a base tática as gasta assim que o alvo chega perto. A diferença
  entre os níveis veio das diretrizes (`dicas`) e da trajetória do treino, porque nas lutas de treino os combos ora
  estão válidos, ora não. Fazer a IA escolher combos pede ou combos que começam por uma espera (que valem antes de a
  base gastar as habilidades), ou o Tático deixar de lançar as habilidades que um combo ativo usa. Fica como próximo
  passo.
- **Diretrizes (opcional):** `ArenaModel.Guideline` (`quando` [fato op valor]+ → `preferir | evitar | obrigar | proibir`
  ação, força e recompensa opcional), com os fatos da Parte C (`Facts`).
  - Ordem: `proibir` (mascarado) → `obrigar` (escolhido se válido) → placar (± força).
  - Tabela `arena_guideline` por campeão; API `GET/PUT /api/arena/guidelines/{campeão}`.
  - Editor na tela (painel de treino): condições em texto, como "alvo.marca_restante > 0.5 e aplica_marca:2 == 1".
  - Um único preset, genérico (`/api/arena/guidelines-preset`): "não sobrescrever marca", uma diretriz por slot que
    evita a habilidade que aplica marca e não consome, com a marca no alvo há mais de 0,5 s.
- Testes (`ArenaLevelsTest`): pontos dos eventos (e das diretrizes); exercícios pela ficha (Ornn tem os 5, Rumble não
  tem marca nem reconjuração); um exercício começa do estado (Frágil no alvo quebrado pelo 1º ataque, alvo no ar
  desde 0, R em recarga); proibir / obrigar; o preset evita o W com Frágil no alvo; o nível e as diretrizes salvos
  na versão.

### Parte I — Laboratório no planejador de builds ✅
Aba **"Laboratório"** nos detalhes do momento do planejador (`PlannerLab.jsx`), para a build aberta (a versão salva)
no minuto escolhido na linha do tempo. API em `/api/lab` (`PlannerLabService`).
1. **Combo no boneco** (`POST /api/lab/combo`): o melhor combo da biblioteca para essa build e minuto. Se não existe,
   uma busca pequena (feixe 24, 8 passos, 800 combos) gera os combos na hora e os salva na biblioteca, que serve de
   cache. Mostra dano, controle, marcas e duração por comportamento do boneco escolhido, e deixa trocar de combo.
2. **Valor de cada item** (`/api/lab/items`): o mesmo combo sem cada item. O item sai da resolução da build
   (`CombatService.Combatant.excludeItems`), o mesmo contrafactual do combate rápido: status, efeitos e gatilhos
   saem juntos. Mostra Δ dano, Δ controle, Δ duração e Δ nota, com opção de recalibrar o combo sem o item.
   **Tanques:** com "Dano recebido de" outra build, o melhor combo dela contra esta parada, com e sem cada item
   (Δ dano recebido; positivo = o item poupa isso).
3. **Item × boneco** (`/api/lab/scenarios`): Δ dano do combo sem cada item em cada boneco (os presets e os salvos;
   os salvos podem vestir uma build e aplicar efeitos: corte de cura, de escudo, redução de armadura / RM).
4. **"Visto por"** (`/api/lab/seen-by`, `SeenBy`): como outra build, num minuto, enxerga esta, com opção de um boneco
   aliado do atacante vestindo uma build (os efeitos de quem acerta dela no máximo, ex.: Cutelo Negro).
   - Mostra vida, armadura e RM efetivas (redução %, penetração % e fixa, letalidade, a redução no máximo), o que passa
     de físico e de mágico, a redução contra ataques, escudos depois do corte, a cura que chega depois de Feridas e
     a vida efetiva contra físico, só ataques, mágico e misto (na proporção DdA / PdH do atacante, a mesma regra do
     `lowest_effective_hp` dos planos).
   - **Sem fórmula paralela:** os status de início vêm de uma corrida de 0 s da própria Arena
     (`FighterStats.startStats` / `startShields`), e o que passa vem de `Arena.through`, a mesma
     `CombatSimulator.mitigation` de cada golpe.
   - O gráfico mostra a vida efetiva minuto a minuto (as duas builds no mesmo minuto, até o fim da partida), e há a
     opção "ao contrário".
   - 🎯 `SeenByTest`: um atacante com 20% de penetração e 10 de letalidade contra 120 de armadura (efetiva 86) só ataca
     até matar. A vida efetiva física calculada (5580) é igual ao dano bruto dos 31 ataques de 180 que mataram.
5. **API para a busca automática de builds** (a busca não foi implementada):
   - `PlannerLabService.score(campeão, boneco, combo, config)`, com cache pela chave itens + runas + nível + passos do
     combo + boneco + modo;
   - `POST /api/lab/compare-builds`: builds lado a lado, cada uma com o seu melhor combo no minuto.

#### Como será a busca automática de builds (desenho e custo)
- **Espaço:** ordens de compra pela linha do tempo de ouro do `TimelineEngine` (o ouro/min e o XP/min da build dão,
  em cada minuto, quais itens cabem). Uma build candidata é uma ordem de até 6 itens dentre os que fazem sentido
  para o campeão (filtro pelo planejador: categoria, grupos exclusivos).
- **Algoritmo:** busca em feixe sobre a ordem, como a dos combos. Cada prefixo é medido nos minutos-chave (ex.: 10,
  14, 18, fim) pela nota do banco com o melhor combo do campeão naquele minuto; a nota da build é a média ponderada
  dos minutos. O combo de cada minuto vem da biblioteca, recalibrado só no fim, para os finalistas.
- **Custo, pelos números do benchmark:** uma avaliação no banco = 4 comportamentos × ~7 s simulados ≈ 28 s
  simulados. A ~14 mil s simulados/s por thread (roteiro com combo), são ~2 ms por avaliação; com 20 threads, ~120 mil
  s/s, ou ~4 mil avaliações/s. Uma build em 4 minutos = 4 avaliações. Com feixe 32 e ~40 itens candidatos por
  passo: 6 passos × 32 × 40 × 4 ≈ 31 mil avaliações ≈ **8 s** com cache, mais o `TimelineEngine` e a montagem de
  cada lutador. A montagem (`arenaBuild`, ~50–100 ms por build e minuto, medida no laboratório) passa a dominar:
  6 × 32 × 40 × 4 × 75 ms ≈ 38 min em 1 thread. Por isso a montagem precisa de cache por (itens, minuto) e de
  paralelismo; com isso, a estimativa fica em alguns minutos por campeão e minuto.

### Relatório final da Rodada 3
**Benchmark** (s simulados por s; 10 núcleos / 20 threads):

| | antes (0ab31d1) | depois (471903d) |
|---|---|---|
| roteiro, 1 thread | 15,0 mil | 13,1 mil |
| roteiro, 20 threads | 137 mil | 114 mil |
| Tático, 1 thread | 4,5 mil | 3,0 mil |
| Tático, 20 threads | 38 mil | 27 mil |

O custo veio dos eventos estruturados (−12% no roteiro) e das previsões (−32% no Tático, que decide a cada passo);
as previsões só são calculadas quando lidas.

**Perguntas abertas para o usuário:**
1. O ataque do Ornn num alvo Frágil: é um empurrão curto (o padrão, `empurra: 150`, ~0,15 s) ou um arremesso que
   "mantém o alvo no ar mais um pouco"? Se for arremesso, quanto tempo? Para testar: `fragil.ataques: {efeito:
   arremessa, duracao: <s>}`.
2. Atordoamentos durante a cabeçada da R: a suposição em uso é que a cabeçada anula puxões, empurrões e arremessos
   enquanto dura e que um atordoamento cai no fim dela com o tempo restante (`continua_depois`). É assim no jogo, ou
   o atordoamento interrompe (`interrompe`)?

## Rodada 4 — Simplificar: sai o Confronto, relatório da Arena com imagens, parada por vitória real, planejador enxuto

Testes no início da rodada: 167 (8 pulados por padrão).

### Parte 1 — Uma recarga por fonte (Suserano do Gelo contado 2 vezes) ✅
- **Causa confirmada:** o Suserano tem duas partes. Uma é o efeito modelado `glacial()` (campo de gelo, explosão e
  lentidão, com recarga em `strikeReady["runa:Suserano do Gelo"]`). A outra é o gatilho `ao_imobilizar` das defesas
  (`combate_7_3.yml`), com recarga própria em `T.readyAt`. As duas disparam na mesma imobilização, e cada uma contava
  `runa_ativou` uma vez: 2 ativações num combo de 8 s. Com o código antigo, o teste novo mede 2.
  As outras hipóteses não se confirmaram:
  - as 4 linhas de status da runa já viravam um único `T`, porque o `CombatService.discover` cria um por runa;
  - o `ComboBench` roda cada comportamento numa `Arena` nova e não soma execuções.
- **Correção** (`Arena.activate` e `Arena.proc`):
  - uma recarga por fonte, guardada em `strikeReady` com a chave da fonte (`runa:<nome>` ou o nome do item). Ela é
    compartilhada pelo efeito modelado (golpes de item, `glacial`), pelos gatilhos e pelas linhas de status;
  - as partes que disparam no mesmo instante são uma ativação, contada uma vez (`runa_ativou` / `item_ativou`);
  - cada ativação guarda o segundo em `FighterStats.procTimes`, por fonte, para o relatório.
  - O campo `T.readyAt` saiu.
- **Decisões:**
  - As partes **sem recarga própria**, como acúmulos por ataque, não são barradas pela recarga da fonte; só a
    contagem por instante vale para elas. Assim, um item com golpe de recarga e um bônus por ataque continua dando
    o bônus a cada ataque.
  - O ativo de um item continua com a recarga do ativo (`u.ready`).
- **Fontes com mais de uma parte (dados de 7.3):**
  - **com recarga, agora compartilhada:**
    - Suserano do Gelo: campo modelado (20 s) + defesas `ao_imobilizar` (20 s);
    - Manto da Aurora: golpe ao acertar habilidade (3 s) + Emissário da Aurora `ao_ser_imobilizado`/imobilizar
      (3 s), o mesmo dano escrito duas vezes;
    - Eclipse: golpe (6 s) + Lua Sempre Crescente (`golpe_do_item`, junto do golpe). Antes contava 2 por golpe.
  - **sem recarga em comum (só a contagem por instante):** Cutelo Negro, Quebrapassos, Mobilização Blindada,
    Presságio de Randuin, Guinsoo, Terminus, Força da Trindade, Tocha de Chamas Negras, Ritmo Fatal.
  - **parte modelada sem gatilho que dispare:** Aurora e Crepúsculo, Desespero Eterno, Céu Dividido, Hemodrenário,
    Redenção, Mandato Imperial, Criafendas, Aperto dos Mortos-Vivos, Aery.
  - **uma parte só:** Coragem do Colosso (gatilho `ao_imobilizar`, 18 s); o teste cobre a regra também nela.
- **Golden:** não mudou. Nenhuma das ~35 lutas tem essas runas ou itens.
- **Testes (`SourceCooldownTest`, 5):**
  - o combo completo do Ornn (8 s, 3+ imobilizações) dá 1 ativação do Suserano e 1 da Coragem do Colosso;
  - `repetir 45 s` com W + ataque (uma imobilização a cada W) dá de 2 a 3 ativações, espaçadas por ≥ 20 s
    (Suserano) e por ≥ 18 s (Colosso), com a mesma contagem no gatilho e no contador;
  - o dano do campo de gelo é o de uma explosão por ativação.

### Parte 2 — Sai o Confronto ✅
- **Removido:**
  - frontend: `pages/Combat.jsx`, a aba `combat` do `App.jsx` e 138 chaves `combat.*` (pt e en) que só ela usava;
  - o CSS exclusivo: `combat-setup`, `-teams`, `-team`, `-slot`, `-order`, `order-chip`, `-fighter(-grid)`,
    `-sources`, `-items` e `delay`;
  - o `localStorage` que guardava a montagem do Confronto (`ornn-planner-combat`), apagado na abertura do app;
  - backend: `CombatController` (`POST /api/combat/simulate`) e `CombatSimulator` inteiro (laço, `State`, `Pending`,
    `Burn`, `Result`, `FighterResult`, 1.340 linhas);
  - do `CombatService`: `simulate`, `Request`, `Response`, `FighterInfo`, `ItemReport`, `without`, `health`, `info`;
  - os campos de `Fighter` só da luta rápida (`order`, `target`, `attackSpeed`, `mechanics`) e os de `Combatant`
    (`order`, `target`);
  - a montagem de `Delay` no construtor (a Arena usa gatilhos), `CombatCatalog.activation` e `champion` e a seção
    `campeoes` do `combate_7_3.yml` (Frágil, Hemorragia e abertura do Ornn da luta rápida; a Arena usa `kits_7_3.yml`).
  - O About não citava o Confronto. O README trocou a seção "Simulador de confronto" por "Regras de dano e efeitos de
    combate", que vale para a Arena.
- **Movido para `com.ornnplanner.arena.fight`:**
  - `Fighter`, `ItemEffect`, `Ledger` e `Delay` viraram arquivos próprios. A Arena ainda respeita `Delay` num lutador
    montado à mão (os testes); o construtor não cria mais.
  - `DamageRules.mitigation` (antes `CombatSimulator.mitigation`);
  - `CombatCatalog` e `AbilityParser`, sem mudanças;
  - `CombatService` → **`FighterBuilder`**: `Combatant`, `Triggered`, `ArenaBuild`, `arenaBuild`, `championName`,
    `formDeltas` e o contrafactual por item (`Combatant.excludeItems`, usado pelo laboratório do planejador).
- **Decisão:** o pacote `combat` deixou de existir. O que restou dele só serve à Arena e aos painéis do planejador que
  rodam lutas na Arena; não há nada "genuinamente comum" fora dela.
- **i18n:** as 26 chaves que a Arena usa viraram `report.*` em pt e en: `fighters`, `col.*`, `team0/1`, `win`,
  `draw`, `dealt`, `taken`, `mitigated`, `healed`, `healLost`, `shieldAbs`, `alive`, `events`, `minute`, `lvMin`…
  As classes `combat-details` / `combat-events` viraram `report-details` / `report-events`.
- **Banco:** nenhuma tabela era do Confronto, então não há migração. Cenários, IAs, combos e builds não mudam.
- **Testes portados** (13 casos antes, 13 depois):
  - `AbilityParserTest`, os 3 de leitura de texto sem mudança: dano do Garen, ≥ 75% dos textos lidos, Fôlego do
    Ornn;
  - `ArenaRulesTest`, 10 casos refeitos na Arena:
    - armadura reduz o ataque à metade;
    - o `Delay` só vale depois do tempo;
    - o escudo mágico não absorve dano físico;
    - o Frágil do W quebrado pela imobilização da reconjuração da R, e não por ataque;
    - a reconjuração da R quebra o Frágil da 1ª passagem;
    - o Poder de Noxus vem no 5º acúmulo (antes dele, 4 e nada);
    - o Coração Congelado reduz os ataques;
    - as Feridas da Armadura de Espinhos cortam o roubo de vida, e o corte não passa da cura;
    - as Feridas só contam a cura que chegaria;
    - a Presa corta escudo e o Cutelo soma dano pela redução de armadura.
  - **Descartado:** só a ideia de "contato" da luta rápida (`ornnOpeningWithTheUltimateDelaysTheContact`: ninguém
    ataca até o elemental chegar). Na Arena há posições, e a distância faz isso sozinha. O caso foi trocado pelo da
    reconjuração que quebra o Frágil da 1ª passagem.
  - `ItemEffectsTest` não usava a luta rápida (testa os efeitos no `TimelineEngine`) e ficou como estava.
- Testes: 172 antes e depois. O golden não mudou.

### Parte 3 — Relatório da Arena com imagens (`FightReport`) ✅
Componente único `components/FightReport.jsx`, usado pela luta do cenário (`Arena.jsx`) e pela luta mostrada no
laboratório (`ArenaLab.jsx`). O antigo `ArenaReport` e o `TriggerTable` saíram. As seções:
1. **Resultado:** vencedor e duração; a vida restante de cada lado; e o gráfico de vida + escudos ao longo da luta,
   montado dos quadros. Tem uma linha por campeão, em tons da cor do lado, e as mortes marcadas com ✝.
2. **Campeões:** a tabela de antes. Em cada linha, duas fileiras de ícones: os itens na ordem dos slots; e a pedra
   fundamental (22 px), as outras runas (16 px) e os feitiços. Mais nível e minuto. O detalhe recolhido traz os status
   do início (`StatIcon`), as habilidades com nível e recarga, e as notas.
3. **Dano por fonte:** barras horizontais por campeão, ordenadas e com o ícone da fonte. A barra divide-se nas cores
   do tipo de dano (físico, mágico, verdadeiro), com legenda.
4. **Itens e efeitos:** o livro-caixa agrupado por campeão, com ícone. Os itens aparecem na ordem dos slots mesmo sem
   lançamento no livro, porque podem valer pela luta sem eles; depois vêm as demais fontes.
   - O botão **"Calcular o valor de cada item"** roda a mesma luta sem cada item, sem quadros e em paralelo. Mostra o
     vencedor sem o item, Δ dano causado, Δ tempo vivo e Δ vida do lado.
   - Na Arena: `POST /api/arena/item-values` (o cenário da luta mostrada).
   - No laboratório: `POST /api/arena/lab/item-values` (o combo contra o boneco no comportamento mostrado).
   - A luta sem o item vem de `Slot.excludeItems` / `labInput(..., without)`: status, efeitos e gatilhos saem juntos.
5. **Gatilhos e runas:** uma linha por fonte (runa ou item), juntando as partes, com ícone e a contagem por fonte da
   Parte 1 (`procTimes` / `runa_ativou`). Também entram as runas e itens que só têm efeito de combate, como Primeiro
   Ataque e o golpe do Coração de Aço.
6. **Decisões da IA, eventos, combos e ordens não cumpridas:** recolhidos.

- **Dados novos em `FighterStats`** (todos fora da impressão digital do golden, por serem descritivos):
  - `items` ({nome, id}, na ordem dos slots), `runes` (pedra fundamental primeiro) e `spells`;
  - `damageBySourceType` (fonte → tipo → dano).
  - `Fighter.runes` vem da página de runas da build.
- **Ícones:** os de itens, runas e feitiços já existiam (`seed/icones`, `seed/runas`, `seed/feiticos`). O catálogo de
  runas é carregado pelo relatório quando a Arena abre sem passar pelo planejador. Não há ícones de habilidades no
  projeto: a habilidade aparece como um selo com a tecla (1, 2, 3, R), o ataque como ⚔ e as outras fontes (Frágil,
  Regeneração) como um selo redondo com a inicial. O nome vai sempre no `title`.
- **Tela estreita:** cada tabela rola dentro de si (`.table-wrap`), as barras passam a uma coluna abaixo de 640 px e
  os ícones têm 16 a 22 px. Testado a 375 px: o relatório cabe. O cabeçalho do app já transbordava antes e não mudou.
- **Bug achado e corrigido:** o dano refletido da Armadura de Espinhos e do Colete Espinhoso saía com o tipo
  `espinhos`. Como esse tipo não é físico nem mágico, nenhuma resistência reduzia o dano, que funcionava como dano
  verdadeiro. Agora é mágico, como manda o `combate_7_3.yml`. Teste: `ArenaRulesTest.thornsAreMagicDamage`. O golden
  não mudou, porque nenhuma luta dele tem esses itens.
- **Verificado na tela** com o cenário salvo "Ornn de Coração de Aço vs Rumble":
  - o gráfico mostra a queda da vida do Rumble até a morte aos 18,9 s;
  - as barras do Ornn têm o Frágil mágico, os ataques físicos e os espinhos agora mágicos;
  - o valor dos itens traz, por exemplo, "sem o Coração de Aço vence o Lado vermelho" e "sem a Duplaguarda vence o
    Lado vermelho";
  - na tabela de gatilhos, o Suserano tem 1 ativação.
- Testes: 173 (mais 1). O golden não mudou.

### Parte 4 — Treino: critério de parada ✅
- **"Parar a etapa"** tem três opções (painel individual e cadeia; `stopWhen` = `generations` | `plateau` |
  `victory`):
  - **Ao fim das gerações** (`generations`, padrão): o platô fica desligado; roda todas e fica com a melhor pela
    seleção;
  - **Platô:** como antes;
  - **Vitória:** o lado do campeão treinado vence a luta do cenário **como a tela mostra**
    (`ArenaTraining.screenEpisode`):
    - as entradas preparadas, sem deslocar os inícios, no minuto da tela, com as ordens do cenário e o plano do time;
    - os cérebros dos outros slots: na cadeia, as versões atuais da cadeia onde houver, e o cérebro do slot nos
      demais;
    - os combos do nível do campeão treinado.
    - É verificada em toda geração, com uma luta determinística sem quadros (`Point.scenarioWin`). A opção
      **"e terminar vivo"** (`aliveToo`, desligada) exige ainda que ele não morra.
  - O texto da tela explica a opção escolhida em uma frase.
- **Ao vencer:** esses pesos são o resultado. São validados e salvos como sempre, e a versão leva
  `arena_policy.wins_scenario` (esquema v19). A tela mostra os dois fatos separados: "🏆 vence o cenário" e "aceita (≥
  55% da validação)" ou "não aceita". Toda versão salva traz `winsScenario` medido, mesmo sem parar por vitória.
  - Lista de versões: 🏆 ao lado do ✓.
  - Individual: ao terminar por vitória, o slot passa a usar a versão (`brain = ai:<id>`) e a tela roda a luta de novo.
  - Cadeia: no fim, o mesmo vale para cada campeão cuja última etapa venceu ou manteve uma versão que vence
    (`Job.latest`, que agora inclui as mantidas).
- **Cadeia:**
  - "manter" (`kept`) passa a significar que a versão atual já vence o cenário da tela, com as versões atuais dos
    outros: "mantida vN (já vence o cenário)".
  - **Decisão:** uma versão que vence o cenário vira a versão da cadeia mesmo sem os 55% (status `won`, "vN vence o
    cenário (não aceita)"), porque é a luta que a cadeia procura; ela conta como progresso da rodada.
  - "convergiu" passa a ser uma rodada sem versão nova aceita ou vencedora.
- **Removido:** a "vitória total" e tudo o que dependia dela:
  - o lote de confirmação (`confirmed`, `Point.confirmPct`);
  - a métrica de parada por lutas vencidas (a do platô agora é sempre "melhor que o Roteiro");
  - o aceite forçado de uma vitória total;
  - os textos da tela.
- **Causa confirmada** de "vitória total" seguida de derrota na tela. As 16 lutas de seleção (e as 16 de
  confirmação) de `ArenaTraining.episodes` diferem da tela:
  - deslocam todos os inícios ao acaso (±1.500 em x, separação −200 a +300 entre os lados, ±300 em y);
  - alternam as variantes de minuto da build (−3 / 0 / +3);
  - trocam os cérebros dos inimigos por Roteiro e Tático (na cadeia, pelas versões da cadeia), em vez dos cérebros
    dos slots da tela.
  - Vencer todas elas não dizia nada sobre a luta da tela.
  - **Verificado também:**
    - a tela **não** trocava o slot ao terminar: só com "Usar como cérebro" ou "Usar estas IAs";
    - as ordens da linha do tempo e o plano do time **eram** os mesmos (`spec.actions` e o `TeamPlan` das entradas
      preparadas).
  - Na tela, um treino do Ornn (6 gerações, população 8, "na vitória") no cenário "Ornn de Coração de Aço vs Rumble"
    mostrou o problema ao vivo: a v75 foi **aceita**, com 100% de vitórias na validação, e **não vence o cenário**.
    O slot continuou com a v40 dele. A versão de teste foi apagada depois.
- **Esquema v19** (`arena_policy.wins_scenario`, anulável). Migrado no banco real (backup antes): 113 IAs e 18
  builds intactas.
- **Testes** (`ArenaChainTest`: 4 testes da vitória total saíram e 7 entraram):
  - a etapa para na primeira geração que vence o cenário, com esses pesos e `winsScenario` salvo;
  - 🎯 depois de uma etapa que parou por "Vitória", o cenário rodado como a tela (entradas novas, o slot com a versão
    lida do banco) dá vitória do lado dele, duas vezes com a mesma impressão digital;
  - a checagem é a luta da tela: `playScreen` tem a mesma impressão digital que a luta direta, sem inícios
    deslocados;
  - "ao fim das gerações" roda todas;
  - a cadeia mantém a versão vencedora e segue;
  - uma vencedora não aceita vira a versão da cadeia;
  - a rodada em que todos já vencem converge.
- Testes: 176. O golden não mudou.

### Parte 5 — Planejador: dois painéis simples no lugar do laboratório ✅
- **Saiu do planejador:**
  - o `PlannerLab.jsx` (combo no boneco, valor dos itens no combo, tabela item × boneco, bonecos múltiplos, recalibrar);
  - os endpoints `/api/lab/combo`, `/items` e `/scenarios`, com `ItemRow` / `ItemsResult`;
  - 72 chaves `plab.*`.
  - A busca e o boneco continuam na aba Laboratório da Arena.
  - Ficaram:
    - `PlannerLabService.combo` (interno), `score` e `/api/lab/compare-builds`, a API da futura busca de builds;
    - as chaves `plab.s.*`, usadas pela visão de outra build.
- **A aba do momento passa a se chamar "Testar"** (`BuildTestPanels.jsx`), com dois painéis. Cada um tem uma frase de
  ajuda e um link "como funciona" com a explicação pedida.
  1. **Testar esta build num cenário ou combo:**
     - **Seletor único** com dois grupos: os cenários salvos que têm o campeão (`GET /api/lab/test-options`; um item por
       slot quando ele aparece em mais de um) e os combos da biblioteca. Ao lado, o minuto (padrão: o da linha do
       tempo) e, para combo, o boneco (presets e salvos).
     - **Execução** (`POST /api/lab/test`): no cenário, a build entra no slot e a luta roda sem quadros; no combo,
       uma vez contra o boneco parado.
     - **Resultado compacto:** vencedor do ponto de vista dele (ou o dano do combo), dano causado e recebido, e os
       3 itens que mais pesaram com ícone.
       - No cenário, a ordem é: muda o vencedor primeiro, depois |Δ vida do lado| + |Δ dano causado|. O valor de
         cada item vem do contrafactual da Parte 3 (`ArenaService.itemValues(sc, slot)`), só nesse slot.
       - No combo, a ordem é |Δ dano| (`LabService.itemValues`).
     - **"Abrir na Arena"** grava o estado que a Arena já lê do navegador e troca de aba (evento `ornn-open-tab` no
       `App`). O cenário abre como cópia ("(teste)", sem id, para não sobrescrever o salvo); o combo abre no
       laboratório, com build, minuto, combo e boneco.
  2. **Visão de outra build** (`/api/lab/seen-by`):
     - qualquer build salva, de qualquer campeão, e o minuto dela;
     - **"Efeitos de um aliado dela"**: uma lista de itens reais com efeitos de quem acerta (`GET /api/lab/ally-items`:
       redução de resistência, corte de escudo, Feridas), no máximo de acúmulos. Antes era um boneco vestindo uma
       build.
     - Cada valor mostra "de → para", com o motivo: `SeenBy.armorReasons` / `magicReasons` (o item da redução e as
       fontes de penetração, de `Fighter.statSources`, preenchido pelo `FighterBuilder`), `shieldCutBy` e `healCutBy`.
       Exemplo, Ornn "Big Carry v1" visto pelo "Darius de CdC" com Cutelo Negro do aliado: "Armadura 366 → 74
       (Cutelo Negro (aliado) −30%, Apreender 36% de penetração, Lembranças do Lorde Dominik 35% de penetração)".
     - O dano que passa por tipo (físico, mágico, verdadeiro 100%) e a redução contra ataques.
     - A vida efetiva física, mágica e mista (na proporção dos status da outra build), com escudos depois do corte e
       cura depois das Feridas, quando houver.
     - **Inverter**, e um minigráfico da vida efetiva física e mágica minuto a minuto, com as duas builds no mesmo
       minuto.
- **Decisão:** os status são os do **início da luta**, como na Rodada 3 (uma corrida de 0 s da Arena). Os efeitos
  condicionais ainda estão desligados. Por isso a armadura da Big Carry aparece como 366, e não os 784 da ficha:
  366 + Duplaguarda (+92) + Suserano (+311). O painel avisa isso numa nota.
- **Verificado na tela** (Ornn "Big Carry v1", 23:17):
  - "Darius ganha de Ornn": o lado dele perde em 40,9 s, causado 3.463, recebido 10.110; os itens que mais pesaram
    foram Espinhos (+1.954 de dano), Duplaguarda (+1.689) e Coração de Aço (+1.679);
  - "Abrir na Arena" abriu "Darius ganha de Ornn (teste)" com a Big Carry no slot, com o mesmo resultado. O rascunho
    da Arena foi restaurado depois;
  - o combo "Full combo Ornn" no boneco deu 5.635 de dano em 3,1 s, com Coração de Aço (+596), Égide (+470) e
    Desespero Eterno (+293).
- **Testes:**
  - 🎯 `SeenByTest.physicalEffectiveHealthMatchesAFightOfAttacks` (Rodada 3) continua passando: a vida efetiva física
    bate com o dano medido na Arena;
  - novo `theReasonsNameTheItems`: Cutelo do aliado, penetração % e fixa por item, Feridas do aliado.
- Testes: 177. O golden não mudou.

### Relatório final da Rodada 4
Commits (sem push): parte 1 `09d198d`, parte 2 `78bcf35`, parte 3 `ca32873`, parte 4 `9a15a34`, parte 5 `4bdddfe`.

**O que mudou na tela:**
- **Arena, relatório da luta** (cenário "Ornn de Coração de Aço vs Rumble"):
  - no topo, "Vitória do Lado azul em 18,9s", a vida restante de cada lado e o gráfico de vida, em que a linha
    vermelha do Rumble cai até o ✝ aos 18,9 s;
  - a tabela dos campeões, com duas fileiras de ícones em cada linha (os 6 itens; a pedra fundamental, as runas
    menores e os feitiços);
  - as barras de dano por fonte em duas colunas, com o Frágil roxo (mágico), o ataque laranja (físico) e a Incendiar
    branca (verdadeiro);
  - o livro-caixa por campeão, com ícones e o botão "Calcular o valor de cada item", que acrescenta "Sem ele / Δ
    causado / Δ vivo / Δ lado";
  - os gatilhos por fonte (o Suserano com 1 ativação);
  - as seções recolhidas no fim.
  - A 375 px, as tabelas rolam dentro do painel e as barras ficam numa coluna.
- **Arena, "Treinar IA":**
  - "parar a etapa: ao fim das gerações / no platô / na vitória", com "e terminar vivo" ao lado da vitória e uma frase
    que explica a opção escolhida;
  - o resultado mostra "🏆 vence o cenário" ou "não vence o cenário", e "aceita" ou "não aceita", separados;
  - a lista de versões marca 🏆.
- **Planejador, aba "Testar":**
  - o painel "Testar esta build num cenário ou combo" (seletor com "Cenários salvos da Arena" e "Combos da
    biblioteca", minuto, Testar). O resultado aparece em uma linha ("O lado dele perde em 40,9s · causado 3.463 ·
    recebido 10.110"), seguido dos 3 itens com ícone e o link "Abrir na Arena";
  - o painel "Visão de outra build" (outra build, minuto, Inverter, efeitos de um aliado com chips de itens), com a
    lista "de → para" e o minigráfico.

**Causas confirmadas:**
- **Suserano do Gelo 2 vezes:** o efeito modelado (`glacial`) e o gatilho das defesas (`ao_imobilizar`) tinham
  recargas separadas e contavam `runa_ativou` cada um, na mesma imobilização. O teste novo mede 2 com o código antigo
  e 1 com o novo. Eclipse e Manto da Aurora tinham o mesmo defeito.
- **"Vitória total" e depois derrota na tela:** a regra exigia vencer as 16 lutas de seleção, que não são a luta da
  tela: inícios deslocados ao acaso, minutos −3/0/+3 e inimigos com Roteiro ou Tático. Ao terminar, a tela também não
  trocava o slot sozinha. As ordens e o plano do time eram os mesmos.

**Bug achado de passagem:** o dano dos espinhos (Armadura de Espinhos, Colete Espinhoso) saía sem tipo e, portanto,
sem redução de resistência. Agora é mágico.

**Código removido, movido e portado:**
- **Removido:**
  - `CombatController`, `CombatSimulator` (1.340 linhas) e `CombatService.simulate` com os seus tipos;
  - `Combat.jsx` (413 linhas), a aba, 138 chaves `combat.*` e o CSS do Confronto;
  - `PlannerLab.jsx`, os endpoints `/api/lab/combo`, `/items` e `/scenarios`, e 72 chaves `plab.*`;
  - a seção `campeoes` do `combate_7_3.yml`;
  - a lógica da vitória total (confirmação, aceite forçado);
  - `T.readyAt`.
- **Movido para `arena.fight`:** `Fighter`, `ItemEffect`, `Ledger`, `Delay`, `CombatCatalog`, `AbilityParser`,
  `DamageRules.mitigation` e `FighterBuilder` (ex-`CombatService`: `arenaBuild`, `formDeltas`, `Combatant` com
  `excludeItems`).
- **Portado:**
  - os 13 casos do `CombatSimulatorTest`: 3 em `AbilityParserTest` e 10 em `ArenaRulesTest`;
  - descartado só o conceito de "contato" da luta rápida, trocado pelo caso da reconjuração da R.

**Testes:** 167 antes (8 opcionais pulados) → 177 depois (os mesmos 8 pulados). O golden não mudou em nenhuma parte.

**Perguntas abertas para o usuário:**
1. Na "Visão de outra build", os status são os do início da luta, com os condicionais desligados: armadura 366 em vez
   dos 784 da ficha. Faz sentido mostrar também uma linha com os condicionais ligados, como a ficha do planejador?
2. Na cadeia, uma versão que vence o cenário mas não passa dos 55% vira a versão da cadeia. Prefere que ela só sirva
   ao slot da tela e a cadeia siga com a última aceita?
3. O "Calcular o valor de cada item" de um cenário leva ~1 s por item: cada variante monta as builds de novo, e a luta
   em si é rápida. Um cache da montagem por (itens, minuto) baixaria isso para frações de segundo. Vale fazer?

### Depois da Rodada 4 — o valor de cada passiva no momento
- A aba **Passivas** do momento mostra, embaixo de cada item, o que cada efeito vale naquele minuto. Exemplos da
  Big Carry do Ornn aos 23:17:
  - "Reflete 89 de dano mágico a cada ataque sofrido" (Armadura de Espinhos), mais as Feridas Dolorosas de −50%;
  - "100 de dano mágico a cada 1,0s" (Égide de Fogo Solar);
  - "237 de dano mágico a cada 4,0s; cura 250% do dano causado (593 por pulso)" (Desespero Eterno);
  - "+535 de escudo mágico" (Esmagadores Acorrentados).
  - Também entram o escudo do Sterak, o corte de escudo, a redução de resistência, as amplificações e os ativos.
  - No fim vêm as runas e os demais efeitos: o dano do Suserano, as defesas que ele dá, o escudo da Coragem do
    Colosso.
- **Sem fórmula paralela:** os textos saem de dentro da `Arena` (`withEffectValues`, `FighterStats.effectValues`),
  numa luta de 0 s contra o boneco. Usam as mesmas fórmulas e os mesmos status que a luta usa: os do início dela, com
  os efeitos condicionais ainda desligados. O que depende do alvo fica como fração da Vida dele, e o dano é antes das
  resistências do alvo. Para os efeitos condicionais do planejador, mostra o que dão quando ligados e quando ligam.
- Os mesmos valores aparecem na **barra fixa da build**:
  - no botão "Passivas ▾", ao lado de "Status", com a lista por item e por runa e os ícones;
  - na leitura do item que abre ao clicar no ícone ("Valor em mm:ss").
  - A barra e a aba usam o mesmo pedido (`effectValues.js`, com cache).
- **Detalhe do cálculo:** cada valor traz a conta termo a termo (`detail`), montada pelos mesmos termos que somam o
  valor (`Arena.Calc`). Por isso o detalhe e o número nunca divergem. Exemplos aos 23:17:
  - Coração de Aço: "140 + 3,5% × 7.903 Vida máxima (277) = 417";
  - Suserano do Gelo: "100 (15–100 pelo nível; 15 agora) + 5% × 5.365 Vida adicional (268) = 368".
  - Os efeitos condicionais dizem que o valor é medido pelo planejador (a build com o efeito ligado menos desligado).
- **Acompanha o momento:** a lista segue o ponto clicado no gráfico. O cabeçalho diz "Passivas em 6:26 (nível 8)", e
  os valores antigos ficam esmaecidos, com "calculando…", até chegarem os novos. A lista da barra fixa ficou mais baixa
  (32% da altura da tela) para não cobrir o gráfico. Antes, aberta, ela tapava o gráfico e o clique caía nela.
- **Status da ficha, não do início da luta:** os valores usam os status que a ficha do planejador mostra no minuto,
  com os efeitos condicionais como a build os liga, igual ao relatório de relevância (`Fighter.sheet` / `sheetBase`,
  vindos de `FighterBuilder.plannerPoint`). Antes usavam os status de uma luta que começa com as condicionais
  desligadas: o Colete Espinhoso contava 177 de Armadura adicional quando a ficha tinha quase 800 de Armadura.
  Conferido na build "Sup sem ser sup" aos 14:26: Armadura 809 − base 106 = 703 adicional, e a conta do Colete é "4 +
  6% × 703 Armadura adicional (42) = 46". Os efeitos condicionais dizem se já estão nos status da ficha (ligados na
  build) ou não.
- `POST /api/lab/effect-values` aceita o rascunho da build, sem precisar salvar (`Slot.build`, que nunca é gravado).
- Também: os gatilhos dizem o tipo de dano em português ("mágico", não "magico").
- Teste: `ArenaRulesTest.effectValuesMatchWhatTheFightDoes`. O valor refletido mostrado (36) é o que a luta mede por
  ataque, e os valores só são calculados quando pedidos. Testes: 178. O golden não mudou.

### Depois da Rodada 4 — visão sob efeitos no lugar da visão de outra build
- O painel **"Visão de outra build"** virou **"Visão sob efeitos"**: em vez de escolher outra build (e inverter, e
  somar um aliado), escolhem-se os efeitos que atingem a build, cada um com o valor editável.
  - **Fontes** (`GET /api/lab/effect-sources`, `EffectSources`): os itens com redução de resistência, Feridas, corte de
    escudo (`combate_7_3b.yml`) e penetração (status da loja e linhas de `efeitos_itens_7_3b.yml`, como o Terminus);
    e as passivas e habilidades de campeões com penetração (`campeoes_7_3b.yml`) ou redução de resistência nas fichas
    da Arena (`kits_7_3b.yml`: Renekton e Rumble). Habilidades entram no rank máximo; o texto mostra os ranks.
  - **Multiplicador por status:** o efeito que escala traz um campo para o status, e o valor segue a fórmula até o
    limite. Tridente da Oceânide: 40% + 0,05% por PdH; Presa da Serpente: 40% + 0,1% por DdA adicional (até 60%);
    Nilah: 28% da Taxa de Crítico.
  - **Regras:** penetrações % se somam (como o status na luta); reduções, corte e Feridas valem pelo mais forte.
  - Saíram a vida efetiva mista (dependia da proporção de DdA/PdH da outra build), `pseen.reverse`/`ally*` e
    `GET /api/lab/ally-items`.
- **Status base em cache:** `SeenBy.base` (a corrida de 0 s da Arena) depende só da build e do minuto; `SeenBy.apply`
  aplica os efeitos com aritmética. O `PlannerLabService` guarda as bases por build, `updatedAt`, minuto e itens
  retirados. Big Carry v1 (20 minutos na série): a primeira consulta leva ~10 s; trocar efeitos leva ~50 ms.
- **Verificado na tela** (Ornn "Big Carry v1", 22:39): Cutelo Negro 30% + Presa da Serpente com 150 de DdA adicional
  dá "Armadura 366 → 241 (Cutelo Negro −30%, Presa da Serpente 15 de penetração)" e corte de escudo de 55%.
- **Testes** (`SeenByTest`): a vida efetiva física sob a penetração de um atacante continua batendo com a luta; o
  corte da Tridente e da Presa escala e para em 60%; os motivos nomeiam as fontes (item e passiva de campeão).
