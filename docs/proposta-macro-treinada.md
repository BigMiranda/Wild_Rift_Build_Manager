# Proposta: macro treinada da partida inteira

Plano, ainda não começado (08/11/2026). Uma parte por commit, no `main`, com a nota de progresso de cada parte anexada
aqui, no estilo das rodadas.

## Onde estamos

- **A macro é só roteiro.** `Match` recebe **uma** `MacroPolicy` para os 10 assentos e pergunta `decide(match, seat)` a
  cada passo grosso (0,25 s), sempre que o campeão não está ocupado, morto ou voltando. A única implementação é a
  `ScriptedMacro`: decide pela função do campeão, com o `TwoTempo` quando está ferido ou com ouro. Os pesos estão em
  `macro_7_3d.yml`, todos EST. O próprio YAML diz que "a aprendida fica para depois".
- **As lutas não são da macro.** Quem decide se uma luta acontece é o `Match.tickFights`: a distância, a linha livre, a
  vantagem de força (`luta_vantagem`) e um sorteio (`luta_chance`). A luta roda na Arena com o cérebro de cada posição
  (`MatchModel.Slot.brain`). A macro só escolhe **onde estar e o que fazer**, e é isso que ela vai aprender.
- **O treinador já existe e serve:** `ai/Trainer` (OpenAI-ES) só pede um `Evaluator` que dê nota a uma `Network` num
  episódio. Ele é determinístico pela semente, com qualquer número de threads, e a máquina tem 20 threads.
- **Custo hoje:** uma partida de 15 min leva de 2,7 a 6,4 s numa thread. As lutas são de 0,3 a 1,3 s disso. O resto é
  montar os lutadores pelo planejador (`MatchService.Live`), que dá para fazer uma vez só por composição.

## Parte 0 — Antes de treinar: a partida tem de estar certa no que já se sabe

Uma macro treinada aprende a tirar proveito dos números da partida. Se eles estão errados, ela aprende o erro. Antes do
treino:

1. **Fechar a Rodada 12:** moitas e paredes pelo "Guia do Rift". Isso já está no `mapa_7_3d.yml`, sem commit.
2. **Aplicar os tempos do guia** que estão anotados e não aplicados: a 1ª onda aos 0:00, os acampamentos e os buffs aos
   0:20 e a velocidade das tropas a partir de 8:00. Depois, refazer a tabela do `MatchReportTest`.
3. **Aceitar a diferença de ouro como está:** a partida rende uns 550 de ouro por minuto, e as builds supõem de 700 a 900.
   Isso não impede o treino, porque os dois lados jogam na mesma economia. Mas fica escrito que a macro treinada vale
   **para esta economia**. Uma calibragem futura pede treino novo, como já acontece com as IAs da Arena (`RULES`).

## Parte 1 — Uma partida treinável: barata, por assento e determinística

- **Macro por assento:** um `SeatMacro implements MacroPolicy` que repassa cada assento à macro do seu lado. Assim, o
  `Match` não muda: o azul pode jogar com a rede e o vermelho com o roteiro.
- **Mundo montado uma vez por composição:** `Live` passa a ter uma versão pronta e imutável, com as linhas do tempo e os
  `Arena.Input` de cada assento, compra e nível. Essa versão é reaproveitada em todos os episódios.
  - ⚠ **Risco de determinismo encontrado:** hoje o cache `inputs` usa a chave `assento|compras|nível`, mas o lutador é
    montado com o **minuto** da primeira vez que a chave aparece. Com o cache dividido entre episódios e threads, o
    resultado dependeria da ordem. A chave tem de levar tudo o que muda o lutador, ou o cache tem de ser preenchido
    antes, numa ordem fixa. Além disso, o `HashMap` atual precisa virar um mapa seguro entre threads, ou ser montado
    antes e só lido.
- **Medida 🎯:** partidas por segundo, numa e em 20 threads, com o mundo pronto. Se uma partida custar 1,5 s ou menos,
  uma geração de 32 candidatos × 8 episódios = 256 partidas leva uns 20 s em 20 threads, e o treino fica com **as lutas
  de verdade**. Só se a medida falhar entra uma "luta estimada" pela força (`Match.power`), calibrada contra as lutas
  finas e usada só no treino, nunca na validação.
- **Teste:** a mesma partida dá o mesmo resultado com 1 e com 20 threads, e com o mundo pronto ou montado na hora.

## Parte 2 — O que a macro vê e o que ela pode fazer

**Ações** (`MacroActionSpace`, cerca de 13, ligadas às `Goal` que já existem):

| ação | vira |
|---|---|
| ficar na rota da função | `LANE` |
| ir à rota do Barão / do meio / do Dragão | `LANE` em outra rota (ajudar, trocar de rota) |
| próximo acampamento | `JUNGLE` |
| Dragão / Barão | `OBJECTIVE` |
| agrupar e empurrar | `PUSH` |
| recuar para o Frutomel | `RETREAT` |
| voltar à base a pé / de Teleporte | `RECALL` |
| comprar aqui | `SHOP` |
| ir à torre aliada mais próxima e esperar | `LANE` recuado (defender, esperar a onda) |

A **máscara** tira o que não vale agora: Teleporte em recarga, nenhum Frutomel de pé, objetivo que não está de pé,
"comprar aqui" sem o campo de ficha, voltar à base estando na base.

**Observação** (`MacroObservation`, com `macro_obs_schema` versionado, como a da Arena), toda normalizada:
- **o próprio campeão:** função (one-hot), Vida, ouro na mão sobre o preço da próxima compra, nível, compras feitas,
  Teleporte pronto, distância à base e à rota da função;
- **o tempo:** minuto, tempo até a próxima onda e até o Dragão e o Barão;
- **a própria rota:** posição da onda da frente, Vida e placas da torre aliada e da inimiga da frente;
- **inimigos e aliados por perto:** os 2 mais próximos de cada lado, com distância, Vida e força relativa (`power`);
  inimigos sem visão contam como ausentes, quando existir visão;
- **o placar:** diferença de ouro e de abates do time, dragões de cada lado, alma, Mão do Barão ativa.

**Decisão a cada 2 s**, com a última intenção mantida no meio-tempo, enquanto continuar válida. Voltar à base e comer o
Frutomel continuam sendo compromissos, como no `Match`.

**Residual sobre o roteiro**, como a rede da Arena é sobre o Tático: a ação que a `ScriptedMacro` escolheria ganha um bônus.
- 🎯 **Teste-chave:** com os pesos zerados, a partida é **idêntica** à do roteiro, com a mesma impressão do resultado.
  Para isso, cada intenção do roteiro tem de cair numa ação, e a ação tem de voltar à mesma intenção, inclusive o log do
  `TwoTempo` (`m.decided`).
- A rede tem uma camada escondida pequena (obs → 16 → ações), como a da Arena. **Uma rede só para as 5 funções**, com a
  função na entrada: são 5 vezes mais exemplos por partida do que redes separadas.

## Parte 3 — Recompensa e treino

- **Episódio:** uma partida de até 20 min, com uma semente e uma composição sorteadas de um conjunto fixo. As composições
  saem das builds salvas, só de campeões com ficha A ou B. Cada composição é jogada **duas vezes, trocando os lados**:
  o mapa não é simétrico na ordem dos dragões nem no lado das rotas.
- **Lados:** a rede candidata joga contra a `ScriptedMacro` (fase 1). Depois, contra a última versão aceita e o roteiro
  misturados (fase 2), para não aprender só a explorar o roteiro.
- **Nota do episódio** (pesos em `macro_7_3d.yml`, `treino:`):
  - vitória (+1) ou derrota (−1), mais um bônus para quem vence mais cedo;
  - diferença de ouro do time no fim, sobre o ouro total;
  - torres, dragões e Barão de diferença, com peso pequeno, para orientar o começo do treino.

  Como o `Trainer` usa postos (só a ordem conta), a escala dos pesos importa pouco. O que importa é a vitória pesar mais
  que o ouro, para a rede não aprender a farmar em vez de ganhar.
- **Validação**, como a da Arena: sementes e composições separadas, nunca vistas no treino, sempre com as lutas finas.
  A versão é aceita com taxa de vitória acima de 55% contra o roteiro em ao menos 40 partidas, com os lados trocados.
  Fica no registro: ouro/min, XP/min, mortes, objetivos e o histograma de ações.
- **Custo previsto:** 40 gerações × 256 partidas, com uns 20 s por geração, dá cerca de 15 min por treino. A Parte 1 mede
  o número real.

## Parte 4 — Guardar, escolher e mostrar

- **Tabela nova `macro_policy`** (`SchemaManager.VERSION` 27 → 28), separada de `arena_policy`, para não aparecer nas
  listas de IAs de luta nem no `AutoBrain`. Colunas: versão, pai, nota, vitória contra o roteiro, pesos, `macro_obs_schema`,
  número de ações, `game_patch`, a versão das regras da partida, semente, gerações, população, composições e data.
  Pesos com esquema ou número de ações diferentes são recusados, como na Arena.
- **Aba "Partida":** um seletor "Macro" por lado: Roteiro (o padrão) ou uma IA da macro salva. O log mostra as decisões
  da rede ("decidiu: ajudar a rota do Dragão"), e a tela avisa quando a IA é de outro patch ou de outras regras.
- **Simulações › "Treino da macro":** iniciar, com o sino de tarefas (`tasks.js`), gráfico por geração, "Parar" e o
  resultado da validação.
- **Versão pública:** nada disso entra no `PublicApi`. O treino e a escolha ficam só locais.

## Parte 5 — Relatório

`MacroReportTest` (opcional, `-Dmacro.report=data/ornn-planner.db`), sobre uma cópia do banco: roteiro contra a melhor
IA aceita, nas sementes de validação. Ele mostra taxa de vitória, duração, ouro/min, XP/min, mortes, objetivos, as ações
mais escolhidas por função e minuto, e três decisões comentadas, de onde a rede mais se afastou do roteiro.

## Riscos

- **Explorar a economia estimada:** é a razão da Parte 0. O histograma de ações e a ordem "vitória antes de ouro" ajudam
  a ver isso.
- **A rede só se afastar do roteiro onde o roteiro é fraco:** é o que se quer, mas o ES pode parar perto do roteiro. Se
  parar, dá para reduzir o bônus residual ao longo das gerações.
- **Lutas sorteadas (`luta_chance`):** o sorteio usa a semente da partida. Por isso, os dois candidatos do par espelhado
  podem ter lutas diferentes, e a nota fica mais ruidosa. Isso se resolve com mais episódios por geração, não com menos
  aleatoriedade.
- **Determinismo:** o risco do cache do `Live` está na Parte 1, e o teste com 1 e com 20 threads entra antes de qualquer
  treino.

## Decisões para o usuário

1. **Fazer a Parte 0 antes**, com a Rodada 12 e os tempos do guia? (recomendado)
2. **Uma rede para as 5 funções**, com a função na entrada, ou uma rede por função?
3. **Adversário da fase 1:** só o roteiro, ou já o roteiro mais as versões anteriores?
4. **Limite de aceite:** 55% contra o roteiro, em 40 partidas, está bom?

## Progresso

### Parte 0 ✅
Foi feita como Rodada 12, partes 3 e 4 ([rodada-12.md](rodada-12.md)). O usuário decidiu manter os tempos observados em
partida, em vez dos do guia (08/11/2026). A velocidade das tropas do guia entrou. A tabela de base está na parte 4.

### Parte 1 ✅ — uma partida treinável
- `MatchService.draft(req)` devolve um `Draft`: os assentos, as builds depois das guias, as funções, as linhas do tempo do
  planejador e o cache de lutadores, num `ConcurrentHashMap`. `run(draft, seed, limite, azul, vermelho)` joga uma partida
  dele, e `run(req)` virou `draft` + `run` com o roteiro dos dois lados.
- `rift/SeatMacro`: uma macro por lado, sem mudar o `Match`.
- **O risco de determinismo foi corrigido:** a chave do cache do lutador agora leva o minuto arredondado, o mesmo que
  monta o lutador. Antes, o lutador ficava com o minuto da primeira vez que a chave aparecia. **Isso muda um pouco as
  partidas de antes**, porque as compras e os efeitos que dependem do minuto passam a ser lidos no minuto certo:

| semente | vencedor | duração | ouro/min médio | XP/min médio | mortes |
|---|---|---|---|---|---|
| 1 | azul | 15:08 → **16:22** | 550 → **549** | 382 → **375** | 35 → **42** |
| 2 | vermelho | 14:56 → **14:56** | 555 → **555** | 371 → **371** | 39 → **39** |
| 3 | azul | 15:56 → **14:53** | 552 → **559** | 366 → **376** | 41 → **36** |

- 🎯 `MatchDraftTest`: com a mesma semente, o resultado é o mesmo com um draft novo, com um já usado em outra ordem e em
  20 threads ao mesmo tempo. Uma macro por lado, com o roteiro dos dois lados, dá a mesma partida que a simples.
- **Medida** (`MatchReportTest`, builds reais): **1,63 s por partida** numa thread, com o draft já usado, e **6,6
  partidas por segundo em 20 threads** (40 partidas em 6,1 s). Uma geração de 24 candidatos × 4 episódios × 2 lados
  = 192 partidas leva uns 30 s. **O treino fica com as lutas finas**, e a "luta estimada" não foi necessária.
- Testes: 413 → 415 (16 opcionais pulados), 0 falhas.

### Parte 2 ✅ — o que a macro vê e faz
- **`rift/MacroActions`**: 12 ações, na ordem que as redes salvas vão guardar. São elas: ficar na própria rota, ir à rota
  do Barão / ao meio / à rota do Dragão, fazer a selva, ir ao objetivo, agrupar e empurrar, recuar para o Frutomel,
  voltar à base a pé / de Teleporte, comprar onde está e esperar sob a torre. `of` leva a intenção do roteiro à ação,
  `intent` faz a intenção da ação com as mesmas contas do roteiro, e `valid` é a máscara.
- **`rift/MacroObservation`** (`SCHEMA` 1, 50 números):
  - o campeão: função, Vida, ouro na mão, nível, compras, Teleporte, se está na base;
  - as distâncias à base, à rota e aos dois fossos;
  - o relógio e os objetivos;
  - a própria rota: torres da frente e ondas da frente dos dois lados;
  - quem está por perto: contagens, o inimigo mais próximo e a força relativa;
  - o placar: ouro, abates, dragões, Barão e torres;
  - a escolha do roteiro, em one-hot.
- **`rift/NeuralMacro`**: obs → 16 → 12, residual sobre o roteiro (+0,5 na escolha dele). Não guarda estado: o que
  precisa lembrar fica no `Seat`.
- **`ScriptedMacro.suggest`**: o roteiro sem deixar rastro, separado do `decide`, que registra a decisão dos dois tempos.
  Quando a rede segue o roteiro, o log é o do roteiro. Quando a rede discorda, o log diz, uma vez por mudança,
  "Fulano (macro treinada): voltar à base a pé em vez de ficar na própria rota".
- **Mudou do plano:** a decisão é a cada passo grosso (0,25 s), como no roteiro, e não a cada 2 s. Decidir a cada 2 s
  quebraria a igualdade com o roteiro quando os pesos são zero, e a rede custa pouco: 10 assentos × 4 por segundo.
- 🎯 `MatchDraftTest`:
  - **com os pesos zerados, a partida é idêntica à do roteiro**, nos dois lados ou só no azul, com o log igual linha a
    linha;
  - em uma partida inteira, toda intenção do roteiro é uma ação válida, e essa ação devolve a mesma intenção (meta,
    posição, rota). A observação tem sempre 50 números;
  - nessa partida aparecem a rota, a selva, o objetivo, o agrupar e a volta à base.

### Parte 3 ✅ — recompensa, treino e validação
- **`arena/MacroTraining`**: OpenAI-ES (o `Trainer` da Arena) sobre os pesos da `NeuralMacro`. Um episódio é uma semente
  de um draft jogada duas vezes, com a candidata de cada lado. A nota (`treino_macro` em `macro_7_3d.yml`, EST) é:
  vitória ±1, mais até ±0,2 por vencer (ou perder) cedo, mais 0,5 × a diferença de ouro sobre o ouro dos dois.
  A validação usa sementes a partir de 1.000.000, que o treino nunca vê, sempre contra o roteiro e dos dois lados.
  A versão é aceita com 55% (`aceite`).
- 🎯 `MacroTrainingTest`: o roteiro contra ele mesmo dá **exatamente 50%**, com cada semente jogada dos dois lados. A
  nota lê o vencedor e é simétrica. A mesma semente treina **os mesmos pesos em 1 e em 8 threads**.
- **O primeiro treino real achou um furo da partida.** `MacroReportTest` (opcional, `-Dmacro.report=...`) usa cópia do
  banco, 2 composições espelhadas, 24 candidatos, 30 gerações e 4 episódios. Em 8 gerações, a rede aprendeu a
  **agrupar os 5 desde o segundo 0 e empurrar: o Nexo caía em 1:42**, com 100% de vitórias. No passo grosso, as torres
  nunca atiravam em campeões, e os campeões batiam nelas sem armadura e sem anti-backdoor.
- **A partida corrigida** (`Match`), com as regras de torre que a Arena já usa:
  - a torre e o Nexo **atiram no campeão inimigo mais próximo** quando nenhuma tropa inimiga está ao alcance (750),
    com o dano de `ataques_seguidos` (1,0 / 1,5 / 2,1 / 2,7) e o `towerDamage` do minuto. A armadura do campeão não
    entra, porque o passo grosso não a tem (EST). Quem morre para a torre aparece no log;
  - o dano de campeão em estrutura é DdA + 40% do PdH por ataque, passa pela armadura da torre e pela defesa do início
    (meio e Barão até 2:00), e leva o corte de anti-backdoor (75%, ou 80% no Nexo) sem tropas aliadas a 1.200;
  - **"agrupar e empurrar" agora vai com a onda** (`Match.rally`): fica atrás da onda da frente da rota escolhida e só vai
    à estrutura quando as tropas chegam. Vale para o roteiro e para a ação da rede.
  - 🎯 `MatchTest.anEarlyRushWithoutMinionsDiesToTheTower`: 5 campeões no nível 1 que vão direto à torre externa antes
    das tropas morrem para ela, e nenhuma torre cai em 50 s. O teste "o lado com um campeão vence sozinho" ganhou um
    campeão 4× mais forte: o de antes, com 200 de DdA no fim, não derruba a base com as torres revidando.
- **A partida de base depois da correção** (`MatchReportTest`, as mesmas 3 sementes):

| semente | vencedor | duração | ouro/min médio | XP/min médio | mortes | mortes para torres |
|---|---|---|---|---|---|---|
| 1 | azul → **empate** | 16:22 → **20:00 (limite)** | 549 → **519** | 375 → **337** | 42 → **60** | 6 nas 3 sementes |
| 2 | vermelho → **empate** | 14:56 → **20:00** | 555 → **515** | 371 → **337** | 39 → **63** | |
| 3 | azul → **empate** | 14:53 → **20:00** | 559 → **530** | 376 → **344** | 36 → **60** | |

  **Com o roteiro, nenhuma partida termina antes dos 20 min.** O roteiro só agrupa aos 14:00 e passa a esperar a onda.
  No jogo, uma partida termina por volta de 15 a 18 min. É a próxima calibragem da partida, fora desta proposta.
- **O treino depois da correção**, nas mesmas condições: 30 gerações em **500 s**. A partida ficou mais longa, e o custo
  foi de 3,3 s por partida numa thread e 4,7 partidas por segundo em 20 threads.

| macro contra o roteiro (80 partidas de validação) | taxa de vitória | V-D | nota | ouro/min (roteiro) | desvios por partida |
|---|---|---|---|---|---|
| roteiro (pesos zerados) | 50,0% | 0-0 | 0 | | 0 |
| geração 10 | 48,1% | 0-3 | −0,057 | 502 (544) | 125 |
| geração 20 | 50,0% | 0-0 | −0,018 | 498 (536) | 192 |
| **final (30)** | **73,1%** | **37-0** | **+0,521** | **508 (467)** | **35** |

  - **O que ela aprendeu:** agrupar e empurrar com a onda bem antes dos 14 min, no lugar da rota, da selva e do
    objetivo, e voltar à base de Teleporte em vez de a pé quando o Teleporte está pronto. Na validação, ela vence 37
    partidas, empata 43 e não perde nenhuma. Uma das amostras vence aos 9:57.
  - **Os desvios caem** de 125–192 por partida no meio do treino para 35 no fim: a rede passou a mudar menos de ideia.
  - Isso **vale para a economia e as regras atuais da partida** (`MacroTraining.RULES` = 1). Com as partidas do roteiro
    terminando empatadas, vence quem empurra antes. Se a partida for calibrada para terminar no tempo do jogo, a vantagem
    pode mudar, e então a macro pede treino novo.

### Parte 4 ✅ — guardar, escolher e mostrar
- **Tabela `macro_policy`** (`SchemaManager.VERSION` 27 → 28; tabela nova, criada pelo `schema.sql`): versão, de qual
  partiu, se foi aceita, a validação (vitórias, partidas, nota, ouro/min, desvios), como foi treinada, as composições, os
  pesos, `obs_schema`, o número de ações, o patch e `rules`. O export público **apaga a tabela** (`PublicExporter.DROPPED`):
  o público não lê a macro.
- **`MacroPolicyRepository`** e **`MacroTrainingService`**: um treino por vez, no fundo, com o progresso por geração. No
  fim, a validação e a versão salva. "Parar" cancela sem salvar. Começar de uma versão salva recusa as de outro esquema.
  "Também contra as versões aceitas antes" mistura o roteiro com até 3 versões aceitas como adversárias.
- **API** (`MatchController`, só fora da versão pública):
  - `GET /api/match/macros` e `DELETE /api/match/macros/{id}`;
  - `POST /api/match/macros/train`, `GET /api/match/macros/train[/{id}]` e `POST .../{id}/cancel`.

  `POST /api/match/run` aceita `macroBlue` e `macroRed` (`script` ou `macro:<id>`), e o resultado diz a macro de cada lado.
  Uma macro de outro esquema de observação ou de ações é recusada com 400. `/api/match/meta` traz o esquema, as ações, as
  regras e o patch atuais.
- **Aba "Partida"**:
  - um seletor "Macro" por lado: Roteiro ou uma macro treinada atual ("Macro treinada v3 · 73% contra o roteiro");
  - o painel **"Treinar a macro"**: as builds escolhidas de cada lado viram as composições. Tem gerações, candidatos e
    episódios, de qual versão começar, uma estimativa de partidas e minutos, o progresso por geração no sino de tarefas e
    "Parar";
  - a tabela das versões, com aceita ou não, vitórias contra o roteiro, ouro/min, desvios, treino, data e "Apagar". Uma
    versão de outro patch ou de outras regras aparece com aviso e não entra no seletor.
- 🎯 `MacroTrainingTest`:
  - uma macro salva com pesos zerados joga a mesma partida que o roteiro, e o resultado diz "Macro treinada vN" e
    "Roteiro";
  - a mesma macro, com outro `obs_schema`, é recusada;
  - um treino mínimo pelo serviço roda, valida (2 partidas) e salva.

### Parte 5 ✅ — relatório
`MacroReportTest` (opcional) foi feito na Parte 3, porque o treino de verdade guiou a correção da partida:
`./mvnw test -Dtest=MacroReportTest -Dmacro.report=data/ornn-planner.db` (+ `-Dmacro.generations=30
-Dmacro.population=24 -Dmacro.episodes=4 -Dmacro.seed=1`).
- Ele treina sobre uma cópia do banco, com 2 composições espelhadas das builds salvas.
- Depois mede contra o roteiro: as gerações 10 e 20 e a final, nas sementes de validação.
- Lista os desvios mais comuns ("agrupar e empurrar em vez de ficar na própria rota") e alguns do log, com o tempo.
- Escreve `target/macro-report.txt` e os pesos em `target/macro-weights.txt`.

Conferido na tela (servidor de desenvolvimento, porta 8090, banco `data/dev`, que subiu para o esquema 28):
- o painel "Treinar a macro" lista a composição das builds escolhidas e roda um treino de 2 gerações × 4 candidatos,
  com "Geração 1/2 · média −0,047 · melhor −0,012 · 17 s" e o sino de tarefas com 1;
- no fim, a v1 aparece na tabela: 50% em 40 partidas, não aceita;
- com "Macro treinada v1" no azul, a partida mostra "Azul: Macro treinada v1 · Vermelho: Roteiro", e o log traz as linhas
  "(macro treinada): ir à rota do Dragão em vez de ficar na própria rota";
- nenhum erro do React. Os 500 do console são das telas escondidas, de quando o backend estava parado.

## Relatório final

| parte | commit | testes depois |
|---|---|---|
| 0. Rodada 12, partes 3 e 4 (mapa do guia, tropas mais rápidas, partida de base) | 9adbcfd, ef24258 | 413 |
| 1. Partida treinável | 94ca45b | 415 |
| 2. Ações, observação e macro neural | da0baa7 | 417 |
| 3. Treino, validação e as torres que revidam | 8b1d056 | 421 |
| 4. Tabela, API e tela | 06a2b41 | 423 |
| 5. Relatório | este | 423 |

- **Testes:** 413 → **423** (17 opcionais pulados), 0 falhas. Os golden da Arena não mudaram, porque a Rota única não usa
  a partida. `npm run build` limpo e `npm test` com 4 de 4.
- **A macro treinada vence o roteiro:** **73% de vitórias** (37-0, com 43 empates) em 80 partidas de validação que o
  treino não viu, depois de 30 gerações (8 min e 20 s em 20 threads).
- **O que mudou na partida, também com o roteiro:** as torres atiram em campeões, o dano em estrutura passa pela
  armadura e pelo anti-backdoor, e o empurrar vai com a onda. Com isso, **as partidas do roteiro não terminam antes dos
  20 min**: 3 de 3 empatam no limite, com 515 a 530 de ouro/min e cerca de 60 mortes.
- **Determinismo:**
  - a mesma semente dá a mesma partida em 1 e em 20 threads, com o draft novo ou usado;
  - a mesma semente treina os mesmos pesos em 1 e em 8 threads;
  - com os pesos zerados, a partida é idêntica à do roteiro, log incluído.
- **Banco:** esquema 28, com a tabela nova `macro_policy`. Os dados antigos não mudam.

## Para o usuário

**Precisa de ação:**
- **Reinicie o backend da porta 8080.** Ele está rodando desde 11:10 com o código de antes. Ao subir, o seu banco passa
  para o esquema 28.
- **Os 7 commits estão no `main`, sem push.**

**Precisa de decisão:**
- **A duração da partida:** com as torres revidando, o roteiro não fecha a partida em 20 min. No jogo, ela termina por
  volta de 15 a 18 min. Calibrar isso (o roteiro agrupar antes dos 14:00, o dano das torres, as tropas) muda o jogo que a
  macro aprende. Depois da calibragem, `MacroTraining.RULES` sobe para 2, e as macros salvas pedem treino novo (a tela
  avisa).
- **O dano da torre em campeões ignora a armadura do campeão**, porque o passo grosso não a tem. A Arena a aplica. Vale
  levar a armadura ao passo grosso?

**Vale conferir:**
- **A nota do treino** (`treino_macro`: vitória 1, cedo 0,2, ouro 0,5) e o aceite de 55% são estimativas.
- **O treino é sobre composições espelhadas** (as mesmas 5 builds dos dois lados). Composições diferentes de cada lado
  entram quando você escolhe builds diferentes nos dois lados da aba Partida.
