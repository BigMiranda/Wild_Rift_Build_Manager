# Rodada 20.b: o draft refeito

Prompt de 20/11/2026, a partir do `main` em d26b742 (depois da Rodada 20). Lê o `CLAUDE.md` e o `docs/rodada-20.md`.
A sub aba continua sendo **Simulações › Draft e partida** (`pages/LiveGame.jsx`), só local.

## Parte 1: os três formatos do usuário (✅)

**Fonte:** o usuário (USU), 20/11/2026. `formatos` de `draft_7_3f.yml` tem agora só três, e os campos novos
`banimentos` (simultaneos | alternados), `pode_pular_ban`, `fearless` e, por passo, `fase` e `fonte`:

| Formato | Banimentos | Escolhas | Passos |
|---|---|---|---|
| Ranqueada | 5 de cada lado, às cegas e ao mesmo tempo; os dois podem banir o mesmo | serpente: azul 1, vermelho 2, azul 2, vermelho 2, azul 2, vermelho 1 | 20 |
| Campeonato | fase 1: 1-2-2-1 (azul, vermelho ×2, azul ×2, vermelho); fase 2: 1-2-1 (vermelho, azul ×2, vermelho); visíveis, sem repetir; "Sem banimento" conta como feito | fase 1: 1-2-2-1; fase 2: 1-2-1 (vermelho, azul ×2, vermelho) | 20 |
| Campeonato Fearless | como o Campeonato | como o Campeonato | 20 |

**Pedido ao usuário:** quem começa a **segunda fase** do Campeonato (banimentos e escolhas). Usei o **vermelho**, como no
LoL de PC; os passos da fase 2 levam `fonte: EST` no seed e a tela mostra a fonte. Basta trocar `time` nesses passos.

- **Banimento pulado:** `LiveGameModel.NO_BAN` ("-") no lugar do campeão; o próximo passo segue (`DraftCatalog.next`) e
  ninguém fica banido. Na tela, o seletor de banimento do Campeonato tem o botão "Sem banimento" e o espaço mostra "sem".
- **Repetição:** `DraftCatalog.cannotBan` (e `liveGame.js › cannotBan`, a mesma regra) diz por que um campeão não pode
  ser banido num espaço: já escolhido, banido pela série ou, nos banimentos visíveis, já banido em outro espaço. Na
  Ranqueada (às cegas) os dois times podem banir o mesmo.
- **Fearless:** sempre os dois times (o seletor `indisponivel: ambos | proprio` saiu). "Próximo jogo da série"
  (`POST /api/draft/jogos/{id}/proximo`, `DraftCatalog.nextInSeries`) cria **uma partida nova** que herda os pré-banidos
  e acrescenta os 10 campeões desta (jogo 3 = 20 pré-banidos); o nome ganha "— jogo N", os nomes dos times e o lado
  aliado vêm junto, e a partida anterior fica salva como estava. A faixa "Banidos pela série" mostra os pré-banidos por
  jogo, e dá para corrigir à mão: tirar um campeão, acrescentar um, acrescentar um jogo anterior (série começada fora do
  app) ou tirar um jogo.
- **Partidas antigas** (`version` nula; agora `LiveGameModel.VERSION` = 2): `DraftCatalog.upgrade` as abre pelo formato
  mais próximo e a tela mostra um aviso (`notices`, uma vez): Ranqueada fica Ranqueada (a mesma ordem de antes);
  Campeonato fica Campeonato (os banimentos feitos ficam nos espaços em que estavam, mas a ordem agora é a do usuário);
  qualquer Fearless vira Campeonato Fearless (a Ranqueada com Fearless não existe mais; o "só para quem usou" vira os
  dois times); um formato desconhecido vira Ranqueada. Os cartões passam a ser as vagas na ordem de escolha (Parte 2):
  os campeões já escolhidos guardam a rota como fixada, os cartões vazios perdem a rota.

**Testes** (`LiveGameTest`): os três formatos, 20 passos cada, 5 + 5 por lado, as sequências exatas (ranqueada
`ABBAABBAAB`; campeonato `ban:ABBAAB|escolha:ABBAAB|ban:BAAB|escolha:BAAB`, a fase 2 com `fonte: EST`); o próximo passo
do campeonato com banimentos pulados até a fase 2; a proibição de repetir no campeonato e a permissão na ranqueada; a
herança de 10 por jogo no Fearless (jogo 3 = 20, os dois times); as partidas antigas abrindo pelo formato mais próximo,
com o aviso; a partida indo e voltando da tabela.
