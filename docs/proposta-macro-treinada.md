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
