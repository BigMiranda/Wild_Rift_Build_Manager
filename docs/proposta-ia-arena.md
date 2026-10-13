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
