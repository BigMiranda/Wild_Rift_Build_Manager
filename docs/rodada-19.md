# Rodada 19: Atlas com progressões naturais do oponente, objetivo por papel e o Ornn de crítico

Prompt de 17/11/2026, a partir do `main` em 5415482. Lê o `docs/rodada-16.md` (o Atlas).

## O que o usuário viu

1. O guia do Atlas é míope: monta o que é bom num momento fixado, contra uma build fixa do outro lado. Deveria
   considerar o que o oponente tende a comprar a seguir, e quando.
2. O Atlas recomendou ao Ornn uma build de crítico e velocidade de ataque. O usuário perguntou se "o boneco" herda ao
   menos os status genéricos da classe.

## O que a revisão achou (conferido)

- **Não é um boneco.** O Atlas luta com o Ornn de verdade: a ficha da Arena (`kits_7_3f.yml`) e os status do jogo
  (`campeoes_7_3f.yml`). A velocidade de ataque base vem de `status.nv1/nv15.VdA` (Ornn: 0,73 → 0,84), interpolada pelo
  nível em `FighterBuilder` (linha ~545: `a1 + (a15 - a1) × (nível - 1) / 14`). A classe não entra nos status.
- **O cérebro era o `tactical_stand`** (`AtlasService.slot`: `s.brain = ArenaService.STAND`), as regras táticas sem
  fugir: os dois ficam parados trocando até a barra acabar. Nesse duelo o dano por segundo bruto domina, e o que faz o
  Ornn ser o Ornn (sair da troca, segurar a luta para o time, controle em grupo) não pesa. A Rodada 16 já mostrava isso:
  "a build AD do guia para o Ornn deu 67 real".
- **O objetivo era só o 1x1 de 60 s no minuto 15**, contra um campeão. O papel do campeão não entrava na nota.

### A fórmula da velocidade de ataque (pendência na revisão; aplicada abaixo)

A Arena faz `VdA = base(nível) × (1 + bônus% / 100)` (`Arena.java:580`, `MatchService.java:405`), com a base já
interpolada pelo nível. No LoL de PC o crescimento por nível é bônus, e o total é
`base + razão × (bônus de nível + bônus de itens)`, com uma razão por campeão (em geral perto da base).

Se o Wild Rift seguir o PC, a Arena superestima um pouco os itens de velocidade de ataque, para todos os campeões.
**Tamanho do efeito**, no exemplo do Ornn no nível 13 com +40% de itens (razão = base de nível 1, 0,73):

| | VdA base no nível | Total | O que os +40% dão |
|---|---|---|---|
| Arena hoje | 0,73 + 0,11 × 12/14 = 0,824 | 0,824 × 1,40 = **1,154** | 0,330 |
| Como no PC | 0,73 (bônus de nível 12,9%) | 0,73 × (1 + 0,129 + 0,40) = **1,116** | 0,292 |

O item vale ~13% a mais na Arena do que valeria (o total, 3,4% a mais). É pouco perto do efeito do cérebro (abaixo),
mas puxa na mesma direção.

**Não há print para conferir.** `capturas/` tem a loja (7.3), o mapa, os dragões e o Guia do Rift, nenhuma tela de
status de campeão com item de velocidade de ataque. **Pedido:** um print da tela de status do Ornn (ou de qualquer
campeão) **com uma Lâmina Ravenosa ou outro item só de velocidade de ataque**, mostrando o nível e a velocidade de ataque
total; de preferência dois níveis diferentes com o mesmo item. Com ele dá para decidir entre as duas fórmulas. A fórmula
não muda sem essa prova.

**Prints recebidos (depois da Parte 5):** Ornn com 5 Arcos Recurvos (+20% cada, **+100%** no total; item confirmado
pelo usuário), modo de treino. Velocidade de ataque: **1,36 no nível 2** e **1,46 no nível 15**.

| Fórmula | Nível 2 | Nível 15 |
|---|---|---|
| Print do jogo | **1,36** | **1,46** |
| Arena hoje: `base(nível) × (1 + 1,00)` | 0,738 × 2 = 1,48 | 0,84 × 2 = 1,68 |
| `base(nível) + razão × 1,00` | 0,738 + 0,62 = 1,36 | 0,84 + 0,62 = 1,46 |

- **A multiplicativa da Arena está errada:** do nível 2 ao 15 a base sobe 13,8%, mas o total só sobe 7,4%. O que os
  itens dão não cresce com o nível.
- **A forma do PC fecha os dois níveis com a mesma razão:** `VdA = base(nível) + razão × bônus`, com a razão do Ornn
  entre **0,617 e 0,625** (pelo arredondamento dos dois prints). A razão não é a base de nível 1 (0,73): fica perto da
  do Ornn no PC (0,625).
- **Consequência:** a Arena **superestima** os itens de velocidade de ataque, mais do que a estimativa acima (que usava
  razão = 0,73). No Ornn nível 13 com +40%: a Arena dá +0,33; o jogo, +0,25 (32% a mais na Arena).
- **A fonte oficial:** o apêndice das notas do patch 7.3 ("como a Velocidade de Ataque é calculada",
  wildrift.leagueoflegends.com/pt-br/news/game-updates/wild-rift-patch-notes-7-3/) dá a fórmula e a tabela de 141
  campeões: `VdA total = VdA base + multiplicador × VdA adicional`, com `VdA adicional = adicional_base + o que os
  níveis deram + itens, runas e habilidades`, e cada nível ganho dá `por_nivel × (0,7 + 0,04 × nível atual)`. O Ornn é
  base 0,625, multiplicador 0,625, adicional_base 0,17, por_nivel 0,012: 1,362 no nível 2 e 1,461 no 15 com +100%,
  os dois prints.

### A fórmula da velocidade de ataque (aplicada)

- **Seed:** `velocidade_ataque: {multiplicador, adicional_base, por_nivel}` nos 141 campeões da tabela
  (`campeoes_7_3f.yml`, cabeçalho com a fonte). Hwei e Sylas não estão na tabela: multiplicador 0,625 (o da maioria).
- **Conferência contra o seed:** a tabela reproduz `nv1` e `nv15` (lidos no Treino) a ±0,006 em 131 dos 141. Não batem
  Akali, Caitlyn, Camille, Galio, Gnar, Graves, Jhin, Lee Sin, Senna e Shyvana (passivas, formas, Jhin e Graves com
  ataque próprio, ou a tabela; a da Caitlyn diverge do próprio exemplo da nota, 0,2 contra 0,28). Por isso a base do
  nível continua saindo de `nv1` / `nv15` (agora pela curva dos níveis, não em linha reta) e da tabela só sai o
  multiplicador.
- **Código:** `Arena.Input.attackSpeed(bônus)` = `base + multiplicador × bônus` (`FighterBuilder.attackSpeedRatio`,
  `attackSpeedGrowth`), usado na luta, no painel de status da luta e na partida (`MatchService`). Bonecos, invocações e
  lutadores montados à mão (multiplicador 0) seguem `base × (1 + bônus)`, por isso o golden da Arena não mudou.
- **Efeito:** no Ornn nível 13 com +40%, os itens davam +0,33 ataques/s e agora dão +0,25. A velocidade de ataque
  ficou mais fraca para todo campeão cuja base no nível passa do multiplicador (quase todos depois do nível 1). As
  medidas do Atlas e da efetividade feitas antes desta mudança superestimam os itens de velocidade de ataque.
- **Teste:** `AttackSpeedFormulaTest` (os dois prints do Ornn, a curva dos níveis, os bonecos com a regra antiga e o
  multiplicador de todos os campeões).

## Parte 1: diagnóstico medido (✅)

**O que mudou no código:** o cérebro do Atlas virou configurável (`cerebro` no seed, `brain` no pedido):

- `tactical_stand` (a "troca parada", o de antes; execuções sem o campo continuam com ele);
- `tactical` (o Tático normal, com fuga);
- `auto` (o `AutoBrain`: a IA do campeão ou a da classe da Rodada 18). O Automático é escolhido **uma vez por lado e
  minuto**, nas duas bases frente a frente, e vale para todas as medidas daquele minuto (`MinuteResult.brainA/brainB`,
  com a nota de como foi escolhido). Assim nenhuma condição muda qual IA luta, e a medida continua determinística.

**Medida:** Ornn × Rumble no minuto 15, só as condições sozinhas (`dimensoes: [1]`), 6 largadas, 600 builds inteiras
e 3 rodadas de refino de 120 (o modelo que o guia usa). Três execuções, com o mesmo resto, numa cópia do banco.

A base de hoje não é a da Rodada 16: a build padrão da campanha mudou depois da campanha da Rodada 18, e a corrida da
base, com a troca parada, caiu de 47,3 para 20,7. Por isso a execução da Rodada 16 (a nº 3) aparece à parte.

| Execução | Cérebros escolhidos | Base | 5 condições sozinhas mais fortes do Ornn | Build do guia, nada fixado | Tempo |
|---|---|---|---|---|---|
| Rodada 16 (nº 3, base antiga, `STAND`) | troca parada | 47,3 | Manto da Meia-noite +15, Liandry +9,6, RM +80 +8,8, % Pen. de Armadura +70 +8,4, Terminus +7,6 | **Lembrete Mortal**, Ruptor Divino, Hemodrenário, Limite da Razão, Força da Natureza, Grevas Bélicas | 31 min |
| (a') hoje, `STAND` | troca parada | 20,7 | Liandry +33,1, % Pen. de Armadura +70 +28,3, Ruptor +26,5, Terminus +26,2, Força da Natureza +25,6 | Ruptor, Desespero Eterno, Duplaguarda de Amaranto, Manto da Aurora, Força da Natureza, Mobilização Blindada | 6,8 min |
| (a) `AutoBrain` | Ornn: IA do Ornn v121; Rumble: IA do Rumble v22 | 14,1 | Manto da Meia-noite +28,3, Força da Natureza +26,6, RM +80 +25,8, Fimbulwinter +22,8, Ruptor +22,3 | Ruptor, Força da Natureza, Desespero Eterno, Placa Gargolítica, Esmagadores Acorrentados, Céu Dividido | 6,3 min |
| (b) Tático com fuga | tático | 0,0 | Ruptor +66,7, Muramana +64,1, Redenção +60,8, Liandry +54,4, RM +80 +54,2 | Ruptor, Desespero Eterno, Força da Natureza, Rookern, Resplendor Vazio, Passos Imortais | 6,5 min |

**O crítico e a velocidade de ataque** (posição entre as 119 condições de status e passivas do Ornn, sozinha; e o
ganho médio dentro das builds medidas, pelo modelo):

| Execução | VdA +70 | Crítico +50 | VdA +70 nas builds | Crítico +50 nas builds |
|---|---|---|---|---|
| Rodada 16 (`STAND`, base antiga) | +3,8 (16º) | +2,7 (24º) | +9,3 | +6,0 |
| `STAND`, base de hoje | +21,9 (14º) | +15,1 (35º) | +4,3 | +3,8 |
| `AutoBrain` | +4,5 (50º) | +2,2 (62º) | +1,9 | +5,2 |
| Tático com fuga | +19,1 (68º) | +27,6 (51º) | +11,7 | +12,9 |

**Leitura:**

- **Quanto do crítico vinha do `STAND`:** com a mesma base, trocar a troca parada pelo Automático tira a velocidade de
  ataque do topo (14º → 50º sozinha) e o crítico (35º → 62º); a build do guia antigo, com Lembrete Mortal, vinha do
  `STAND` sobre a base antiga, em que VdA e crítico somavam ~15 pontos dentro das builds; com o Automático somam ~7.
  Ou seja: **a maior parte do crítico vinha da troca parada** (e da base de então); o resto é o modelo, que ainda lê
  ~2 pontos de crítico nas builds.
- **O Tático com fuga não serve de medida no 1x1:** a base dá 0 (o Ornn recua com a vida baixa e a corrida conta como
  perdida), as notas sozinhas ficam enormes (qualquer condição que evita a fuga vira o resultado) e o erro do modelo
  sobe para 17,3 (10,6 no `STAND`, 9,1 no Automático). Fica como opção, não como padrão.
- **Custo:** o Automático não encarece a medida (6,3 min contra 6,8 do `STAND`; quase tudo é preparar os lutadores).
- Com o Automático, o topo do Ornn é o de um tanque contra um mago (Manto da Meia-noite, Força da Natureza, RM,
  Fimbulwinter). Por isso a Parte 4 o torna o padrão.

## Parte 2: modelo de progressão natural de builds (✅)

**Onde ficou:** `arena/progressao` (não em `engine/`, porque as fontes vêm do banco e da Arena: Descoberta, guias,
perfis de campeão). `ProgressionModel` é a parte pura (sem Spring, determinística); `ProgressionService` lê as fontes e
monta o modelo de novo quando uma tabela muda; seed `progressao_7_3f.yml`. A Rodada 20 usa o mesmo serviço.

**Fontes**, com peso no seed (`fontes`):

| Fonte | O que entra | Peso |
|---|---|---|
| descoberta | de cada campanha guardada: a build padrão (×2), as ranqueadas (×1) e a de cada nicho (×0,6), na ordem de compra que a Descoberta mediu | 1,0 |
| salvas | as builds salvas, na ordem dos passos (sem vendas) | 1,5 |
| meta | as builds meta com `fonte` (sem ordem: a bota entra depois do primeiro item) | 2,0 |
| guias | cada caminho das guias de decisão, com o peso da frequência dele | 1,5 |

Hoje o banco tem 150 linhas de campanha (142 campeões), 49 builds salvas e nenhuma build meta ou guia: o modelo lê
~3.000 builds; o Ornn tem 56 da Descoberta e 40 salvas, o Rumble 28 e 2.

**Forma:** uma cadeia "estado → próximo item", em que o estado é o conjunto de itens finais e a bota já comprados.

- Cada build conhecida vota no primeiro item dela que o estado não tem (e que a loja permite), com o peso da fonte vezes
  a sobreposição com o estado, `(itens em comum / itens do estado) ^ 4`.
- Mistura, cada parte normalizada (seed `mistura`): as builds do campeão pela sobreposição (1,0); a frequência dos itens
  nas builds dele, em qualquer posição (0,8); as builds de campeões da mesma subclasse (0,3); as builds do mesmo
  arquétipo de build da Rodada 9 que o do campeão (0,3; o arquétipo de um campeão sem build é o da subclasse dele); um
  pouco de tudo o que a loja permite (0,02).
- **Regras da loja:** itens únicos e grupos exclusivos, uma bota, 5 itens além dela, item de suporte só em quem joga de
  suporte (o campeão tem build de suporte, ou o pedido diz), bota aprimorada só a partir dos 10:00 no relógio.
- **Componentes comprados** multiplicam a chance dos itens finais que os usam por
  `1 + 1,5 × (parte do custo do item que eles já pagam)`; comprado o item, os componentes saem do estado.
- **Composição inimiga** (seed `composicao`, as leituras das guias: dano mágico ou físico pelo perfil, quem se cura,
  tanques): quando a regra vale, os itens com aqueles status ou efeitos de combate pesam o fator (RM ×1,4 contra 60%+ de
  dano mágico, Armadura ×1,4 contra físico, efeito `feridas` ×1,6 contra quem se cura, penetração % ×1,3 contra
  tanques). Nenhum item ou campeão é citado.
- **Tempo:** o relógio do planejador e do site, `500 + ritmo × minuto` cobrindo o custo acumulado da build na ordem; três
  ritmos (atrás 600, padrão 700, à frente 800). A bota aprimorada espera os 10:00, e o que vem depois espera por ela.
  Um teste confere o relógio contra a linha do tempo do planejador numa build do Ornn (diferença < 0,75 min por item).

**API** (só local; no modo público é 404):

- `GET /api/progressao?campeao=&itens=&componentes=&inimigos=&k=` → o próximo item (distribuição inteira, soma 1) e as k
  continuações mais prováveis até a build completa (busca em feixe: 60 estados, 10 ramos), com a probabilidade de cada
  passo e da continuação, e o minuto de cada item nos três ritmos;
- `GET /api/progressao/amostras?campeao=&minuto=&n=&semente=&itens=&inimigos=` → n builds sorteadas pela cadeia, cada
  uma num ritmo sorteado (25% / 50% / 25%), com o que já terminou no minuto (o que o Atlas usa);
- `GET /api/progressao/validacao?campeoes=` → a validação "deixe uma fora".

**Testes:** `ProgressionModelTest` (loja sintética): a soma dá 1; segue as builds conhecidas; regras da loja; um
componente sobe o item que o usa (e é gasto na compra); a composição inclina a cadeia; a bota aprimorada nunca antes
dos 10 min (no relógio, nas continuações e nas amostras); determinismo; a conta do "deixe uma fora".
`ProgressionReportTest` (opcional, `-Dprogressao.report=data/ornn-planner.db`, sobre uma cópia): as 3 continuações
mais prováveis do Ornn e do Rumble batem com builds da campanha (4 a 6 itens de 6 em comum) e o relógio bate com o
planejador.

**As 3 mais prováveis** (sem nada comprado, contra o outro; minuto no ritmo padrão):

- Ornn (arquétipo tanque): Manto da Meia-noite 2,9', Botas Galvanizadas 4,6', Resplendor Vazio 8,6', Desespero Eterno
  12,9', Manto da Aurora 16,8', Duplaguarda de Amaranto 21,4' (e duas variações da ordem e do último item). 4 de 6 em
  comum com a build da campanha mais próxima: o Ornn tem muitas builds diferentes (96), e a cadeia junta pedaços delas.
- Rumble (mago contínuo): Liandry 3,6', Virtude Radiante 7,4', Cajado do Vazio 11,6', Sapatos Enfeitiçados 14,8', Eco
  de Luden 18,8', e Armadura de Warmog / Fúria de Guinsoo / Bastão das Eras. 6 de 6 em comum com builds da campanha.
- As probabilidades de uma build inteira são pequenas (Ornn 0,008%, Rumble 0,04%): são 6 escolhas seguidas entre ~100
  itens. O que importa é a ordem entre elas e a distribuição do próximo item.

**Validação "deixe uma fora":** para cada build conhecida (as iguais saem juntas), cada prefixo é um estado e o
próximo item real fica escondido. O modelo, sem aquela build, ordena o próximo item.

| | Casos | Top-1 | **Top-3** | Top-3 depois da 2ª compra | Linha de base (frequência dos itens do campeão) |
|---|---|---|---|---|---|
| Todos os campeões | 19.864 | 28,7% | **50,0%** | 42,9% | 44,4% |
| Ornn e Rumble | 735 | 24,1% | **49,7%** | 44,5% | 42,6% |
| Ornn | 553 | | 52,1% | | 42,0% |
| Rumble | 182 | | 42,3% | | 44,5% |

- O expoente da sobreposição e o peso da frequência foram escolhidos nessa mesma validação (expoente 2 e 0,25 davam
  47,1%; 4 e 0,8 dão 50,0%). São dois números num grid pequeno: o ganho sobre a linha de base (+5,6 pontos) é real mas
  modesto.
- No Rumble a cadeia perde para a linha de base (42,3% × 44,5%): as builds dele na campanha são quase todas a mesma
  (Liandry, Virtude, Cajado...) e a frequência pura já acerta; a sobreposição só ajuda onde há variedade (Ornn: +10).

## Parte 3: o Atlas contra a progressão natural (✅)

**Novo modo do guia, "contra a progressão natural"** (`modo_guia: progressao`, o padrão das execuções novas), ao lado
de "contra o fixado" (o de antes, e o de toda execução sem o campo: as antigas abrem como eram). A tela tem os dois e
deixa trocar.

- **O outro lado não tem build fixa:** ele é sorteado pelo modelo da Parte 2 nos minutos `minutos_progressao`
  (padrão 10, 15, 20 e 25), `amostras_progressao` builds por minuto (12), contra o campeão deste lado. Cada minuto é lido
  no minuto medido mais perto da execução (numa execução só de 15 min, todos no 15).
- **O que o outro lado fixar vira o começo da cadeia** (ex.: Rumble com Liandry): o resto é sorteado a partir dali.
- **A nota de uma build** deste lado é a dos itens que ela já terminou em cada minuto (o relógio do planejador, ritmo
  padrão) contra cada build sorteada, combinadas em `peso_media` (0,4) × a média + o resto × a robustez, que é a média do
  pior caso e do percentil baixo (`percentil_baixo`: 20%), como a versatilidade das runas.
- **A montagem** passa a usar essa nota: itens por ganho da nota a cada mil de ouro (até 5 e a bota), depois runa
  fundamental, runas e feitiço, depois trocas enquanto a nota sobe. O outro lado "responde" comprando o mais provável
  condicionado à composição (a cadeia), não o ótimo do modelo.
- **O ótimo continua como opção** ("o outro lado joga perfeito"): a montagem conjunta da Rodada 16 dá a build ótima do
  outro lado, e ela entra no tempo pelo mesmo relógio, uma build por minuto.
- **Na tela**, para cada lado: a build item a item com o minuto de cada um, o ganho médio dele sobre as builds sorteadas e
  em quantas delas ele rende; a nota em cada minuto (itens, média, pior); a linha do tempo prevista do outro lado (as 3
  continuações mais prováveis, com a probabilidade de cada passo e o minuto); e um **aviso** quando um item só rende
  (meio ponto ou mais) contra menos de `aviso_ramo` (30%) das builds sorteadas, ou sai depois do último minuto lido.
- "Lutar de verdade", "Melhorar lutando" e o refino continuam no modo de antes (a montagem conjunta no minuto da
  execução).

**Custo:** um guia contra a progressão natural numa execução de um minuto leva ~7 s (o fixado, ~3 s). O modelo de
progressão passou a conferir o banco no máximo a cada 5 s (antes, a cada pedido: o guia o consulta milhares de vezes).

**Mais minutos:** o seed passa a medir 10, 15 e 20 (`minutos`). O custo de uma execução inteira com eles está na
Parte 5.

**Exemplo** (a execução da Parte 1 com o Automático, só o minuto 15, nada fixado): o Ornn monta Ruptor Divino 4',
Força da Natureza 8', Passos de Mercúrio 9,7', Desespero Eterno 14' contra o Rumble previsto (Liandry 3,6', Virtude
Radiante 7,4', Cajado do Vazio 11,6'...), nota 91,8 (pior caso 91,1). Com Liandry fixado no Rumble e o oponente perfeito,
o Ornn troca a ordem (Desespero antes da bota) e a nota cai para 81,3.

## Parte 4: objetivo por papel e aproveitamento do kit (✅)

**Cérebro:** o padrão do Atlas passa a ser o Automático (`cerebro: auto`): a IA do campeão ou a da classe, escolhida
uma vez por lado e minuto (Parte 1). A troca parada (`tactical_stand`) e o Tático continuam como opção no seed e na tela
(o seletor "Cérebro" ao rodar uma execução). As execuções antigas seguem com a troca parada.

**Objetivo por classe** (`objetivo: papel` no seed; `duelo` é o de antes e o das execuções antigas). A nota de cada lado
mistura o duelo 1x1 e uma luta pequena de equipe, com os pesos de `objetivo_por_classe` ("Classe/Subclasse" ou a
classe; editáveis):

| Classe | Duelo | Equipe |
|---|---|---|
| Tanque, Controlador | 0,35 | 0,65 |
| Lutador | 0,8 | 0,2 |
| Assassino | 0,9 | 0,1 |
| Mago, Atirador, Especialista | 1 | 0 |

- **A luta de equipe** usa o cenário `suporte` da Rodada 14 (`equipe.cenario: mergulho_no_carry`): o atirador aliado do
  cenário, crescendo com o minuto e mortal, fica atrás do lado que protege, e o campeão do outro lado mergulha nele (o
  alvo dele é o carry). A medida é a corrida das barras do carry e do mergulhador
  (`EffectTestService.race` com o carry como "nós"): o que o lado faz pelo carry (controle no mergulhador, dano
  absorvido, dano inimigo cortado, matar o mergulhador antes) entra nela. É o "carry vivo" das medidas da Rodada 14
  numa escala de 0 a 100, a mesma do duelo.
- Cada condição, par, trio e build inteira medidos ganham também a corrida de equipe (de cada lado que tem peso de
  equipe), com `largadas_equipe` (3) largadas. As notas, o modelo de builds inteiras (ajustado nas mesmas builds) e a
  previsão de equipe são os do duelo, num segundo mapa (`MinuteResult.teamA/teamB`).
- **A nota de um lado** (guia contra a progressão natural) é `duelo × peso + equipe × peso`, cada uma na visão do lado.
  O modo "contra o fixado", "Lutar de verdade", "Melhorar lutando" e o refino continuam só no duelo.
- No Ornn × Rumble só o Ornn (Tanque) tem a luta de equipe; o Rumble (Mago) é só duelo.

**"Aproveita o kit?"** (`KitUse`, seed `aproveita_kit`): para cada status, quanto o kit do campeão o converte em
efeito, de 0 a 1.

- **Dependência de ataques básicos:** a da classe principal no seed (Atirador 1, Lutador e Assassino 0,6, Especialista
  0,4, Tanque 0,3, Controlador 0,2, Mago 0,15), misturada (60/40) com a parte do dano por segundo dos ataques básicos
  no perfil (`ChampionProfiles`). Só o perfil não separa: nos números de referência, os ataques do Ornn são 72% do dano
  dele (as habilidades de tanque dão pouco dano), mais que os da Caitlyn (41%).
- **Por status:** crítico = a dependência (× 0,6 quando nenhuma habilidade fala de crítico); velocidade de ataque = a
  dependência × 0,8; roubo de vida = a dependência; Dano de Ataque e Poder de Habilidade = 1 se alguma habilidade escala
  com ele (`AbilityParser`), senão (só o DdA) a dependência; penetrações = a parte do dano daquele tipo; mana = 1 só para
  quem usa mana; o resto (vida, resistências, aceleração, velocidade...) = 1.
- **Um item ou passiva** vale a média dos status dele, pelo ouro.
- **Números:** Ornn: crítico 0,28, velocidade de ataque 0,38 (fora); Jinx: 0,49 e 0,66 (dentro); Rumble: mana 0 (fora),
  Poder de Habilidade 1. Itens só de crítico e velocidade de ataque (Dançarina Fantasma, Furacão de Runaan, Adagas
  Rápidas Navori, Canhão Fumegante) ficam em 0,35 para o Ornn e ~0,6 para a Jinx. Itens de Dano de Ataque continuam no
  kit do Ornn (Gume do Infinito 0,79), porque as habilidades dele escalam com DdA.
- **Não proíbe nada:** abaixo do `limiar` (0,4) a condição é "fora do arquétipo". Na montagem contra a progressão
  natural, um item fora do arquétipo só entra se a nota com ele (objetivo do papel e robustez) vencer a do melhor item do
  kit por `margem` (3 pontos); numa troca, se subir a nota em 3 pontos. Ele aparece marcado "fora do arquétipo (o kit
  aproveita X%), vence as do kit por Y pontos: descoberta a conferir". A regra só vale nas execuções com o objetivo do
  papel.
- **Na tela:** uma coluna "Aproveita o kit?" no relatório de condições (com ⚠ fora do arquétipo) e a marca no item da
  build montada; o cabeçalho do guia diz o cérebro e o objetivo de cada lado.

**Testes:** `AtlasKitTest`: com o seed de verdade, o Ornn não converte crítico nem velocidade de ataque e a Jinx sim;
mana do Rumble 0 e Poder de Habilidade 1; os itens só de crítico e velocidade de ataque ficam fora do kit do Ornn e
dentro do da Jinx; a regra da margem (vence por 2: fica a do kit; por 4: a descoberta) e a média pelo ouro. A montagem
do Ornn × Rumble está na Parte 5.

## Parte 5: o piloto de novo (✅)

Ornn × Rumble nos três jeitos, numa cópia do banco. Para caber no tempo, as execuções novas mediram só as condições
sozinhas e as builds inteiras (600 + 3 × 120 de refino), que é o que o guia usa; o custo do seed completo está abaixo.

| Jeito | Execução | Minutos medidos | Tempo |
|---|---|---|---|
| Antigo: contra o fixado + troca parada | nº 3 (Rodada 16, completa: 1, 2 e 3 dimensões) | 15 | 31,5 min |
| Parte 3: contra a progressão natural, troca parada, só duelo | nº 7 | 10, 15, 20 | 19,0 min |
| Parte 3 + 4: progressão natural, Automático, objetivo do papel | nº 8 | 10, 15, 20 | 17,8 min |

**Builds montadas do Ornn** (nada fixado; o minuto é o do relógio no ritmo padrão):

- **Antigo:** Lembrete Mortal, Ruptor Divino, Hemodrenário, Limite da Razão, Força da Natureza, Grevas Bélicas.
- **Parte 3** (troca parada): Lembrete Mortal 3,6', Força da Natureza 7,6', Passos de Mercúrio 9,3', Ruptor Divino 14',
  Resplendor Vazio 18', Eclipse 22,1'. Nota 73,8 (média 82,2, robustez 68,1). Com a troca parada o guia continua
  abrindo com crítico: o problema da Parte 1 aparece de novo, agora contra a progressão natural.
- **Parte 3 + 4** (Automático, Tanque: duelo 0,35 + equipe 0,65): Ruptor Divino 4', Botas Ionianas da Lucidez 5,4',
  Desespero Eterno 9,7', Serrespada Quimiopunk 13,7', Terminus 18', Hemodrenário 22,4'. Nota 71,1 (média 82,6, robustez
  63,4). **Sem item de crítico ou só de velocidade de ataque**, e nenhum fora do arquétipo (o teste da Parte 4 pede isso
  ou a marca com a margem). É uma build de lutador de DdA com Vida, não de tanque puro: as habilidades do Ornn escalam
  com DdA, e o Automático do Ornn troca bem nesse perfil.
- O Rumble previsto (a cadeia, contra o Ornn), nos dois jeitos novos: Liandry 3,6', Virtude Radiante 7,4', Cajado do
  Vazio 11,6', Sapatos Enfeitiçados 14,8', Eco de Luden 18,8', Armadura de Warmog 22,9'.

**A luta de verdade:** cada build do Ornn, com o que o relógio dá a ela no minuto, contra o mesmo Rumble previsto (o
mesmo relógio), com os cérebros Automáticos da execução nº 8, 24 largadas; a luta de equipe (o carry do Ornn) também
com 24.

| Ornn de | Min. 15: duelo | equipe | 0,35 d + 0,65 e | Min. 20: duelo | equipe | 0,35 d + 0,65 e |
|---|---|---|---|---|---|---|
| Antigo | **40,6 ± 6,5** | **30,6 ± 6,2** | **34,1** | 34,8 ± 4,3 | 27,9 ± 0,6 | 30,3 |
| Parte 3 | 30,5 ± 5,7 | 25,2 ± 0,6 | 27,0 | 52,4 ± 5,6 | **33,8 ± 2,2** | 40,3 |
| Parte 3 + 4 | 28,7 ± 2,8 | 23,6 ± 0,9 | 25,4 | **56,0 ± 5,4** | 33,3 ± 1,0 | **41,2** |

- **No minuto 20 as builds novas ganham com folga** (41,2 e 40,3 contra 30,3 no objetivo do Tanque; 56 contra 35 no
  duelo). **No minuto 15 a antiga ganha** (34,1 contra 25,4): os 4 primeiros itens dela (Lembrete Mortal, Ruptor,
  Hemodrenário, Limite da Razão) são mais dano cedo; as novas põem a bota e o Desespero antes.
- A previsão das notas (82–92 nos minutos) fica bem acima das lutas de verdade (25–56). Duas razões: o modelo de cada
  minuto é o das builds inteiras sorteadas, e o guia escolhe o que ele superestima (o otimismo da Rodada 16); e o
  Automático do Ornn não é o mesmo em todos os minutos (a IA v23 nos minutos 10 e 20, "parecida", e a v121 no 15,
  "geral"): a luta muda com a IA escolhida, e a nota por minuto da Parte 3+4 cai justamente no 15 (70,5, pior 51,7).
  O número absoluto continua valendo pela luta de verdade, como na Rodada 16.
- **O atirador continua montando crítico:** Jinx × Caitlyn (execução nº 9, Automático, objetivo do papel, minuto 15):
  Força do Vendaval 3,7', Botas Galvanizadas 5,4', Lembrete Mortal 9,7', Manto da Meia-noite 13,4', Espada do Rei
  Destruído 17,8', Gume do Infinito 22,6', nenhum fora do arquétipo.

**Custo do seed completo** (Automático, objetivo do papel, 1, 2 e 3 dimensões, validação, builds): uma execução só do
minuto 15 (nº 10) mediu 14.317 conjuntos em 15,4 min (13,6 min preparando os lutadores, numa thread; 1,8 min lutando,
com as lutas de equipe) e foi cancelada nos pares. No ritmo medido, os 49 mil pares levariam ~53 min e o minuto inteiro
(pares, trios, validação e builds) passaria de 1 h. Por isso o seed fica em **15 e 20** (a regra pedia isso acima de
1 h), e há o preset **"rápido"** (`ornn_rumble_rapido`: 1 dimensão e builds, nos minutos 10, 15 e 20, ~18 min), que é o
que os pilotos desta rodada usaram. O que custa é preparar os lutadores (88% do tempo), não a luta de equipe.

## O que mudou nas recomendações

1. O crítico do Ornn vinha da **troca parada**: com ela, até contra a progressão natural, o guia abre com Lembrete
   Mortal. Com o Automático e o objetivo do Tanque, ele sai (e, se voltasse, viria marcado fora do arquétipo).
2. O guia agora monta **contra o que o outro lado tende a comprar e quando**, e mostra a linha do tempo prevista e as
   notas em cada minuto. O Rumble previsto contra o Ornn é o mago de Liandry da campanha, não um Rumble tanque fixado.
3. A build nova é melhor a partir do minuto 20 e pior no 15, na luta de verdade: o próximo passo é pesar os minutos
   (hoje iguais) ou medir o 15 com mais builds, e escolher o Automático do Ornn uma vez para todos os minutos.

## Pendências

- ~~A fórmula da velocidade de ataque~~: resolvida pelos prints e pelas notas do patch 7.3 (seção no começo). Falta
  rodar de novo o piloto Ornn × Rumble com ela (os números da Parte 5 são da fórmula antiga) e conferir em print os 10
  campeões cuja tabela não bate com o seed.
- O relatório de condições e a aba Validação continuam lendo só o duelo; a luta de equipe entra no guia contra a
  progressão natural e no "Lutar de verdade".
- O preparo dos lutadores é numa thread e domina o custo; com o Automático, cada preparo lê a IA do banco de novo.
