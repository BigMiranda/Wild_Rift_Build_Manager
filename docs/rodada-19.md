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

### A fórmula da velocidade de ataque (pendência, sem mudança)

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
