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

## Parte 3: a recomendação que entende a ordem (✅)

A nota ganha duas partes e a contra-escolha passa a depender da posição; os pesos ficam em `draft_7_3f.yml › nota` e
"Como a nota é feita" explica cada um (com a tabela das mecânicas, abaixo).

| Parte | Peso | O que é |
|---|---|---|
| flex | 0,15 (novo) | o candidato serve a 2+ rotas **abertas** com presença real (≥ `flex.presenca_real`, 15% da presença dele); vale × (picks do próprio time ainda por vir) / 4: cheio no 1º pick, zero no último pick do time |
| contra | 0,30 (era 0,20) | − (em cada rota provável dele, pesada pela chance de a rota do adversário estar aberta) o quanto os disponíveis do outro time o punem × (picks do outro time ainda por vir depois deste) / `contra.cheio_com` (5): o **último pick não tem risco** |

- **Quem pune quem**, sem nome de campeão no código: `DraftModel.punishes(o, c, rota)` = a vantagem medida (as tabelas do
  Admin lutam com a build padrão da campanha ou, sem ela, a mais provável da progressão; um contra que tende a fechar um
  item de lentidão já está na medida) + `contra.mecanicas` (0,3) × a punição por mecânicas, de 0 a 1. Numa rota, o risco
  é 0,6 × o contra mais forte + 0,4 × a média dos 3 mais fortes (`contra.mais_forte`): um contra forte sozinho já é risco
  (antes era só a média dos 3, que diluía).
- **Mecânicas que punem**: seed novo `contra_mecanicas_7_3f.yml` (rascunho **EST** para o usuário conferir;
  `arena/draft/CounterMechanics`), a tabela "vulnerabilidade → o que pune". Cada vulnerabilidade e cada punição é um campo:

| Vulnerabilidade | Campo | Punida por | Peso | Campeões (7.3f) |
|---|---|---|---|---|
| saltos curtos encadeados | ficha: avanço/piscar/salto até 450 de alcance com recarga até 8 s no último rank, reconjuração ou reinício de ataque | lentidão no kit (perfil) ou item de lentidão na build provável (passiva com "N% de Lentidão") | 0,6 | 13 (Vayne, Lucian, Zeri, Riven, Fiora...) |
| cura | perfil: habilidades com cura | Feridas Dolorosas na build provável (`combate: feridas`) | 0,5 | 70 |
| escudos | perfil: habilidades com escudo | anti-escudo na build provável (`combate: corta_escudo`) | 0,4 | 59 |
| linha de frente sem fuga | perfil: Vida e resistências no terço de cima e nenhum avanço | dano verdadeiro (≥ 15% do kit) ou dano por % da Vida (ficha: `vida_max_alvo`, `vida_atual_alvo`, `vida_perdida_alvo`) | 0,5 | 5 |
| depender de projéteis | ficha: ataque com `velocidade_projetil` e alcance ≥ 450 | anula projéteis | 0,6 | 67 |

  Punidores encontrados: lentidão no kit 107, item de lentidão 27, Feridas 8, anti-escudo 0 (nenhuma build provável o tem),
  dano verdadeiro 9, % da Vida 38, anula projéteis 3. **Anula projéteis** não é campo de ficha ainda: fica como lista no
  seed (Yasuo, Braum, Samira; EST) até a ficha ganhar o campo. A punição de B sobre A = min(1, Σ das vulnerabilidades de A
  que alguma punição de B responde × o peso). As builds prováveis de 143 campeões levam ~13 s: a tabela é calculada uma
  vez **em segundo plano** no primeiro pedido do draft; até ficar pronta, o risco usa só a medida e a tela diz
  "mecânicas que punem em cálculo". `GET /api/draft/mecanicas` mostra a tabela e os campeões de cada linha (botão "Ver a
  tabela" em "Como a nota é feita").
- **Os motivos** dizem o porquê: "risco de contra-escolha: Barão6 (4 picks do outro time depois deste)", "... X pune
  saltos curtos encadeados", "flex (Barão / Meio): esconde a rota".
- **Banimentos** ("o que ameaça o seu time"): os melhores do outro time **mais** `contra.banimentos` (0,5) × o quanto cada
  um contra-escolhe um pick já feito do seu time (nas rotas em que os dois são prováveis, a vantagem medida mais as
  mecânicas); o motivo vem primeiro: "contra-escolhe Ornn (pune cura)".

**Testes** (`DraftModelTest`): um candidato com contra forte disponível (só ele tem contra) cai no 1º pick do vermelho
(risco < −0,4, motivo com o contra) e volta a ser o 1º no último pick (risco zero, `after` = 0 depois); banir o contra
devolve o candidato ao topo; um flex vale mais no 1º pick que no 4º e zero no 5º, e um campeão de uma rota nunca é flex;
os banimentos põem em cima quem contra-escolhe o pick feito; a punição por mecânicas (soma, teto 1, o porquê); a leitura
dos saltos curtos e dos termos por % da Vida numa ficha sintética.
