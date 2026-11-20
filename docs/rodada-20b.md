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

**A segunda fase do Campeonato começa pelo vermelho** (banimentos e escolhas): usei o vermelho, como no LoL de PC, e o
usuário confirmou em 20/11/2026 (os passos da fase 2 perderam a marca `fonte: EST`).

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
`ABBAABBAAB`; campeonato `ban:ABBAAB|escolha:ABBAAB|ban:BAAB|escolha:BAAB`, a fase 2 começando pelo vermelho); o próximo passo
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

## Parte 4: a tela em etapas, Draft → Carregamento → Partida (✅)

Uma **barra de passos** no topo (fixa ao rolar; no celular deitado ela rola junto) com as três etapas, o Desfazer ao lado
(no celular ele flutua no canto, como na Rodada 20), e **"Próxima etapa"** no fim de cada uma. Voltar é sempre possível e
nada se perde: a etapa é só uma vista (`game.stage`, salva). Partidas antigas abrem pela etapa do que têm (eventos =
Partida; dez picks = Carregamento; senão Draft).

1. **Draft** (`components/DraftSteps.jsx`, os times e `DraftPanel`):
   - a faixa de pré-banidos do Fearless (Parte 1) no topo;
   - os banimentos e as escolhas **na ordem do formato**, agrupados (Banimentos 1, Escolhas 1, Banimentos 2...), com a cor
     do lado, o campeão de cada passo feito e **o passo atual em destaque**; o anúncio "Vermelho: banimento 2 de 5 · fase
     1" com "Banir" / "Escolher" e, no Campeonato, "Sem banimento"; na Ranqueada "banimentos às cegas (em qualquer
     ordem)"; um passo com fonte EST mostra a marca. Tocar em qualquer passo abre o seletor dele (dá para trocar depois);
   - escolher um campeão no draft volta ao draft (feitiços e runas ficam para o Carregamento);
   - os dois times com as vagas na ordem de escolha, a rota provável com a % e as rotas possíveis de cada pick (Parte 2);
   - as listas da vez (previsão, recomendação, banimentos e a árvore, Partes 2 e 3);
   - **"Pular draft"** vai direto ao Carregamento.
2. **Carregamento** (`components/LoadingStage.jsx`):
   - por time, as cinco rotas em linhas; **tocar num campeão e depois na rota** fixa (troca com quem estava lá), e com o
     mouse dá para **arrastar** o campeão até a rota; "Deixar a rota em aberto" e "Usar a atribuição provável"; ao entrar
     pela primeira vez, o time sem nenhuma rota fixada recebe a **atribuição provável pronta** (a mais provável inteira);
   - "Feitiços e runas" em cada campeão (o editor nas abas de feitiços e runas);
   - a **voz** (Rodada 20) no topo da etapa;
   - o que faltar fica em branco e se preenche na Partida;
   - **Recomendação do aliado** ("Sugestão"; `POST /api/draft/carregamento`, `LoadoutService`): feitiços e fundamental
     **mesmo sem a rota definida** — a melhor de cada rota provável (com a chance), a **ponderada** (a de melhor valor
     esperado pelas chances) e a **mais robusta** (a melhor no pior caso entre as rotas com chance ≥ 20%); "Aplicar" põe
     no cartão (a fundamental própria leva a página padrão inteira). **EST**: não há medida de feitiço ou runa por rota;
     as regras estão em `draft_7_3f.yml › carregamento` (os pares de feitiços de cada rota na ordem, Golpear só na Selva,
     e a fundamental por rota e classe quando a da página padrão dele, campanha ou 1ª build salva, não serve à rota).
3. **Partida**: o relógio, as ações rápidas, a voz, os cartões (itens, placar), os painéis Primeiro encontro, Builds
   prováveis e Dicas (o painel do Draft saiu daqui: é a etapa 1) e os eventos, como na Rodada 20; "‹ Carregamento" e a
   barra de passos levam de volta para corrigir runas e rotas.

**Conferido** em 1920 × 1080, 844 × 390 e 390 × 844 (painel do navegador do app; `docs/rodada-20b/`): no retrato o
Carregamento empilha os dois times (em duas colunas os botões não cabiam); as rotas possíveis quebram em duas linhas no
cartão; alvos de toque de 36 a 52 px.

| Tamanho | Capturas |
|---|---|
| 1920 × 1080 | `draft-1920x1080.jpg`, `carregamento-1920x1080.jpg` (a sugestão do Ornn sem rota: ponderada, robusta, Suporte 51%, Barão 49%), `partida-1920x1080.jpg` |
| 844 × 390 | `draft-844x390.jpg`, `carregamento-844x390.jpg`, `partida-844x390.jpg` |
| 390 × 844 | `draft-390x844.jpg`, `draft-390x844-times.jpg`, `carregamento-390x844.jpg` |

**Testes:** `LoadoutTest` (um flex Barão/Selva: cada rota com a sua melhor, Golpear só na Selva, a fundamental própria
servindo; um Mago no Suporte troca o Ritmo Fatal por Aery; com a rota fixada, ponderada = robusta = a da rota).

## Parte 5: a meta e as pendências da Rodada 20 (✅)

### A meta importada

`python tools/importar_meta.py` rodado em 20/11/2026 com o `tools/meta_herois.json` novo do usuário (os 143 campeões pelo
nome chinês, com variantes nos incertos): dados de **19/11/2026**, **5 chaves externas**, **687 pares campeão-chave**, **0
heróis sem correspondência** (os 143 da lista de heróis casaram pelo heroId ou pelo nome chinês). Gragas e Nilah não têm
linha no ranking (sem dado na data). `backend/data/meta/meta.json` (fora do git, como o cache).

- **Pinyin (`alias`)**: implementado sem biblioteca nova. O pinyin de cada caractere é **aprendido da própria lista de
  heróis**: o nome chinês de cada herói alinhado com as sílabas do `alias` dele quando a divisão em sílabas é única
  (sílabas = iniciais × finais do pinyin, sem tons); 166 caracteres aprendidos. Um herói que não casa pelo id nem pelo
  nome casa se o `alias` dele for o pinyin de um nome chinês do mapa (um nome com caractere não aprendido não entra). Hoje
  **nenhum herói precisou** dele; os que casarem assim ficam em `pelo_pinyin` no `meta.json` e no relatório.
- **O que restou sem correspondência:** nada (o `tools/meta_herois_faltando.json` ficou vazio).

**Posição → rota** (a maioria das rotas dos heróis listados em cada posição, somadas as 5 chaves; EST):

| Posição | Rota | Contagem (heróis × chaves) |
|---|---|---|
| 1 | Meio | Meio 159, Barão 23, Suporte 16, Dragão 1 |
| 2 | Barão | Barão 138, Meio 30, Selva 20, Dragão 14, Suporte 9 |
| 3 | Dragão | Dragão 110 |
| 4 | Suporte | Suporte 115, Meio 30, Barão 9, Dragão 5, Selva 5 |
| 5 | Selva | Selva 130, Barão 56, Meio 34, Dragão 4 |

A posição 3 é só de atiradores e a 4 tem os suportes (mais magos que também vão de suporte); a 5 tem lutadores que vão à
selva. As cinco rotas saem diferentes, o que reforça a leitura, mas segue EST até alguém conferir no app chinês.

**A chave externa** ("0" a "4"; `fonte_0`..`fonte_4`): a hipótese é que seja a **faixa de elo**, do mais baixo (0) ao mais
alto (4). O que se vê: as chaves 1 e 2 são quase iguais (o mesmo herói no topo da posição 1, com 24% de banimento), as
chaves 3 e 4 têm banimentos bem menores no topo (<1%) e outro herói em primeiro, a 0 é diferente das outras; o número de
heróis com dado cresce de 177 (0) a 186 (4) e a soma das presenças fica em ~8,8 em todas. Nada na resposta diz o nome da
faixa: segue EST. A tela de configuração da partida ganhou o seletor **"Elo da meta"** (as chaves, com esta hipótese no
título); sem escolha, vale a primeira (`fonte_0`).

**A meta alimenta:** a chance de cada rota (Parte 2: a parte da presença; as rotas com 10% ou mais da presença entram nas
possíveis), o flex (Parte 3: rotas com 15% ou mais) e a parte `meta` da nota (presença, banimento e vitória nas rotas
prováveis). Exemplos do `fonte_0`: Yasuo Meio 14,7% / Barão 4,1%; Ornn Barão 1,5% / Suporte 1,6% (a meta chinesa o
mostra de suporte tanto quanto no Barão, então ele vira "?" até ser resolvido).

### A calibração da composição na partida inteira

Botão novo em **Admin › Catálogo › Draft e voz**: "Calibrar na partida inteira" (`POST /api/draft/calibracao-partida`,
`DraftTablesService.calibrateOnMatches`, em segundo plano, com o progresso no mesmo quadro das tabelas). Composições
sorteadas (um campeão por rota em cada time, dos 8 mais jogados de cada rota pela meta), builds pela progressão (a
continuação mais provável, com a página e os feitiços padrão), o roteiro de macro nos dois lados, partidas inteiras da
Rodada 10 até 25 minutos; o resultado é 100 / 0 para a vitória do azul / do vermelho (sem vencedor no limite, 50 mais o
ouro à frente). Uma regressão ridge das diferenças de composição contra o resultado, ajustada na metade par e **validada
na metade ímpar**; os pesos só entram se essa correlação passar de `nota.calibracao_minima` (0,3).

- **A partida inteira normal só aceita fichas A e B**: 11 campeões, quase todos do Barão (Ornn, Darius, Renekton, Rumble,
  Vayne, K'Sante, Garen, Irelia, Kayle, Teemo, Shen). Para sortear composições de verdade a calibração aceita fichas até
  **C** (`tabelas.calibracao_partida.qualidade_minima`), sabendo que as lutas dependem delas.
- **Resultado** (banco de teste, meta de 19/11, 20 threads):

| Partidas | Decididas | Vitórias do azul | Correlação (amostra) | Correlação na metade separada | Tempo |
|---|---|---|---|---|---|
| 120 | 116 | 59 | 0,27 | **0,10** | 355 s |

  Abaixo do mínimo: **os pesos do seed ficam** (EST) e a tela diz "calibração em 120 partidas inteiras com correlação na
  metade separada 0,1, abaixo do mínimo". A correlação melhorou em relação à luta 5x5 de rota única (0,005 validada na
  Rodada 20), mas ainda não prediz. Os coeficientes saíram nos extremos (dano físico e mágico 2, controle 0,26, o resto 0),
  sinal de amostra pequena para 7 traços. Próximo passo: mais partidas (o custo é ~3 s por partida por thread) e as fichas
  C revistas.

### O primeiro encontro com tropas e torre: não oferecido

Medido (`EncounterRiftProbeTest`, opcional, `-Ddraft.rift=data/ornn-planner.db`; Ornn × Darius no meio da rota do Barão,
minuto 1,4, 18 lutas de 25 s): **60 ms por luta no mapa com tropas e torre contra 62 ms na rota única**: o custo caberia
(o encontro inteiro, 1.800 lutas, ~107 s; um "refinar" de 18 lutas, ~1 s). Mas o custo igual mostra o problema: na Arena
com mapa **a onda nasce no Nexo** e, nos minutos do primeiro encontro (0:30 a 2:12), não chega ao meio da rota dentro dos
25 s da luta; a luta "com tropas" é a mesma da rota única. Para ser fiel, a Arena precisa pôr uma onda já na rota (uma
mudança no simulador, com as goldens). Por isso **o botão "refinar" não foi oferecido**; fica registrado como pendência.

**Testes:** `tools/test_importar_meta.py` (o cache sintético de antes e o casamento pelo pinyin: a divisão em sílabas, a
tabela aprendida, um herói com nome variante casado pelo `alias`); `EncounterRiftProbeTest` (opcional).

## Relatório final

- **Os formatos** são os do usuário, inclusive a fase 2 do Campeonato começando pelo vermelho (confirmado).
- **Rotas livres**: tudo lê a distribuição das 120 atribuições; um pick inesperado só move a distribuição.
- **A ordem do draft** entra na nota (flex e risco de contra-escolha pela posição) e nos banimentos.
- **Mecânicas que punem**: rascunho EST em `contra_mecanicas_7_3f.yml` para conferir (limiares, pesos, a lista de quem
  anula projéteis).
- **A tela** tem três etapas e cabe em 844 × 390 e 390 × 844.
- **Meta** importada (0 sem correspondência); posição → rota e a chave externa como elo seguem EST.

### Pendências e pedidos ao usuário

1. ~~Quem começa a fase 2 do Campeonato~~: o vermelho, confirmado pelo usuário em 20/11/2026.
2. Conferir o rascunho `contra_mecanicas_7_3f.yml` ("Ver a tabela" em "Como a nota é feita" mostra os campeões de cada
   linha).
3. As regras de feitiços e fundamental por rota do Carregamento (`draft_7_3f.yml › carregamento`) são minhas (EST): não há
   medida de feitiço ou runa por rota.
4. A posição → rota e o significado da chave externa do ranking chinês (EST).
5. A calibração da composição ainda não prediz (0,10 na metade separada); mais partidas e fichas C revistas.
6. O primeiro encontro com tropas e torre precisa de uma onda já na rota na Arena com mapa.
7. As tabelas de matchup do Admin foram medidas antes desta rodada; vale recalcular com a meta importada (os 20 mais
   jogados por rota passam a vir da meta).
