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

## Parte 2: picks livres de rota (✅)

**O que o usuário viu:** os cartões presos às rotas; um pick numa posição não prevista quebrava as recomendações.

- **Cada time tem 5 vagas na ordem de escolha** (1º pick, 2º pick...). Cada jogador guarda `lanes` (as rotas possíveis:
  nulo = as do seed e da meta; vazio = indefinido; senão as marcadas) e `lane` (a rota **fixada**, só no Carregamento,
  Parte 4). Ao escolher um campeão, a tela pré-preenche as rotas possíveis (`possibleLanes` de `/api/draft/formatos`:
  as do seed de campeões mais as da meta com presença acima de `nota.rotas.presenca_minima`, 10% da presença dele).
  No cartão: o "Nº pick", a rota provável com a % (📌 quando fixada; "?" com as duas mais prováveis quando nenhuma passa
  de `incerto_abaixo`, 60%) e os botões das cinco rotas (várias ao mesmo tempo) e "?" (indefinido). O editor do cartão
  tem as rotas possíveis e a rota fixada ("Em aberto" por padrão).
- **Atribuição provável** (`arena/draft/LaneAssignment`, puro): as atribuições injetivas dos picks às 5 rotas (5!/(5-k)!,
  no máximo 120), cada uma pesada pelo produto das chances de cada campeão na sua rota. A chance de um campeão numa rota
  (`Champ.lanePrior`) é a parte da presença dele naquela rota na meta (as rotas do seed sem presença ganham
  `sem_meta_na_rota`, 0,05), ou, sem meta, a ordem do seed (`ordem_seed`: 1; 0,4; 0,2; 0,1; 0,05, normalizado). O peso de
  um pick numa rota: só a fixada; senão as marcadas pelo usuário (ao menos `marcada_minimo`, 0,3, da melhor rota dele);
  indefinido = todas (as de fora com `indefinido`, 0,05); não informado = as possíveis. O resultado: a distribuição de
  rotas de cada campeão, a **chance de cada rota ainda estar aberta** e a atribuição inteira mais provável. Quando nada
  cabe (dois picks que só jogam Meio), toda rota ganha um peso pequeno e a conta segue (`relaxed`): nada quebra.
- **Tudo lê a distribuição** (`DraftModel`):
  - a parte **rota** de um candidato é a chance de as rotas dele estarem abertas no time (Σ rota aberta × chance dele na
    rota; 1 = livres); abaixo de `encaixe_minimo` (0,08) ele sai da lista (o "rota cheia" de antes);
  - a rota mostrada de um candidato é a mais provável dele se escolhido agora (rota aberta × chance dele), e a lista mostra
    as duas quando ele divide ("Bar 70% · Mei 30%");
  - **matchup**: a média sobre os adversários prováveis de rota (Σ rota × Σ adversário P(adversário na rota) × vantagem);
  - **meta**: a média sobre as rotas prováveis dele;
  - **contra-escolha**: em cada rota provável, pesada pela chance de a rota do adversário ainda estar aberta;
  - a **previsão do inimigo** é a mesma conta do lado dele, então um flex inimigo deixa as duas rotas "talvez ocupadas";
  - a dupla do Dragão (2x2) e os painéis por jogador (primeiro encontro, builds prováveis, dicas) leem a atribuição
    inteira mais provável (`DraftService.resolved`: uma cópia da partida com cada rota em aberto preenchida), então um pick
    numa rota inesperada só muda a distribuição.
- `POST /api/draft/sugestoes` devolve também `lanes` (por lado: cada pick com a sua distribuição e a rota provável, e a
  chance de cada rota estar aberta) e `unsureBelow`. A árvore de cenários explora picks sem rota (a distribuição decide).

**Testes** (`DraftModelTest`): um campeão de Meio como 1º pick do vermelho com o Meio do azul já escolhido não quebra as
listas, e um time com dois picks só de Meio segue com listas, árvore e distribuição (relaxada); um campeão de duas rotas
deixa as duas "talvez ocupadas" (a principal mais) até ser resolvido, e fixar ou marcar uma rota abre a outra; picks
indefinidos funcionam até o fim do draft (o 5º recomendado vai para a rota que sobra; com 5 picks a lista fica vazia); a
enumeração das 120 permutações.
