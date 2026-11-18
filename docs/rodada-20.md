# Rodada 20: Simulações › Draft e partida

Prompt de 18/11/2026, a partir do `main` em 6c0ff57 (depois da Rodada 19). Lê o `CLAUDE.md` e os docs das rodadas 10,
14, 16 e 19.

**Para quem é:** quem **assiste** a uma partida (replay, transmissão, campeonato, coaching), não quem joga. A ferramenta
só usa o que o usuário informa (toques e voz; os prints ficam para a Rodada 21). Nada lê o jogo nem automatiza o cliente.
Não pede nem mostra recompensas (bounties).

**O que é:** a sub aba nova **Simulações › Draft e partida** (`#/simulacoes/draft`, `pages/LiveGame.jsx`), que acompanha
uma partida (ou uma série, no Fearless) do draft até o fim. Fica fora da versão pública e do site estático (os
endpoints novos, `/api/draft/...`, respondem 404 no perfil `publico`; as tabelas novas saem da exportação).

## Pedido ao usuário: a ordem do draft do Wild Rift

A ordem de banimentos e escolhas em `draft_7_3f.yml` é **EST**: a do LoL de PC (ranqueada: 5 banimentos de cada time ao
mesmo tempo e escolhas em serpente; campeonato: 3 + 2 banimentos alternados em duas fases). A tela mostra "Ordem do draft:
EST..." em cima. **Falta confirmar a do Wild Rift:** quantos banimentos por time na ranqueada e no campeonato, se os
banimentos são simultâneos ou alternados, e a ordem das escolhas. Basta editar `passos` no seed.

## Parte 1: a partida acompanhada (✅)

- **Tabela `live_game`** (schema v32; `schema.sql` cria também `draft_apelido` e `draft_tabelas` das partes 2 e 3). Cada
  linha guarda a partida inteira em JSON (`arena/draft/LiveGameModel.Game`): o modo (ranqueada | campeonato), o Fearless e
  quem perde os campeões usados (`ambos` | `proprio`), a série (os jogos anteriores), o lado aliado, os dois times
  (nome, banimentos, cinco jogadores: campeão, rota, feitiços, runas, nível, itens e componentes em ordem, item inicial,
  ouro, K/D/A; dragões, arautos e barões), as previsões fixadas pelo usuário, o relógio e os eventos (compra,
  componente, morte, abate, dragão com o elemento, arauto, barão). Tudo é opcional.
- **Seed `draft_7_3f.yml`**: os formatos (`passos` em ordem, com `qtd` e `simultaneo`), o Fearless padrão, as rotas, os
  dragões da ação rápida (os 3 elementais do Wild Rift e o Ancião) e as seções das partes 3 e 4 (pesos, tabelas,
  primeiro encontro). `DraftCatalog` lê o seed e diz o próximo passo do draft e o que cada lado não pode escolher
  (banidos, escolhidos e, no Fearless, os da série: dos dois times ou só do próprio).
- **API** (`api/DraftController`, só local): `GET /api/draft/formatos`, `GET|POST /api/draft/jogos`,
  `GET|PUT|DELETE /api/draft/jogos/{id}`, `POST /api/draft/estado` (o próximo passo e os indisponíveis).
- **Tela** (`pages/LiveGame.jsx`, `components/LivePlayerEditor.jsx`, estado puro em `liveGame.js`):
  - lista de partidas e "Nova partida"; nome, modo, Fearless (com quem perde os usados), "Aliado: Azul ⇄" e, no Fearless,
    "Jogo N da série" (guarda as escolhas na série e limpa o tabuleiro);
  - duas colunas (aliado à esquerda), cada uma com os banimentos e cinco cartões (rota, nível, K/D/A, ouro, feitiços,
    runa fundamental, itens e componentes);
  - o cartão abre o seletor no estilo da loja, em abas: Campeão (grade com busca e filtro de rota, os indisponíveis
    apagados, a rota do cartão), Feitiços (os 9 do jogo, toque em dois), Runas (o editor de runas do planejador),
    Itens (a loja com abas e busca: um toque registra a compra no minuto do relógio; componentes à parte, gastos quando
    o item que os usa é comprado; botas trocam botas; "Marcar o item inicial"), Placar (nível, ouro e K/D/A com botões
    de ±);
  - o relógio: tocar e escrever "7:30" (ou "730", "7"), ▶ / ⏸;
  - ações rápidas, com alvos de 48 px: Comprou (abre os itens do selecionado), Morreu, Matou, Dragão (lado e elemento),
    Arauto, Barão (lado);
  - **Desfazer** sempre visível na barra fixa (até 60 passos); a partida se salva sozinha 0,7 s depois de cada mudança;
  - os eventos, os mais novos em cima.
- **Teste:** `LiveGameTest` (os formatos têm 5 banimentos e 5 escolhas por lado e a fonte EST; o próximo passo segue a
  ordem; o Fearless nos dois modos; a partida vai e volta da tabela).

## Parte 2: entrada por voz (✅)

- **Microfone** (`components/VoiceBar.jsx`): a Web Speech API do navegador (`SpeechRecognition` /
  `webkitSpeechRecognition`), `pt-BR`, contínuo, com resultados parciais (aparecem em itálico). Sem dependência nova.
  Só aparece onde há a API e o contexto é seguro (localhost ou HTTPS); nos outros navegadores fica escondido, com a dica
  "sem microfone" (o título explica). Na primeira vez, um aviso: no Chrome, o áudio vai para o serviço de reconhecimento
  do Google (lembrado no navegador).
- Ao lado, **uma caixa de texto** que lê as mesmas frases (serve no Firefox e para testar).
- **Leitor** (`frontend/src/voiceParser.js`, JS puro): cada trecho de até 5 palavras é comparado com os catálogos
  (campeões, feitiços, runas fundamentais e menores, itens da loja) e com os apelidos, sem acento e sem caixa, pela
  distância de edição e por uma chave fonética simples (dígrafos, c/k/q, s/z/ç, y/i, w/v, h mudo, letras dobradas, a
  vogal final que o reconhecimento acrescenta). O trecho mais longo com boa semelhança vence; nomes curtos (até 3
  letras) precisam ser exatos. Um item dito pela primeira palavra ("o cutelo") vale quando só um nome começa com ela.
- **Apelidos** no seed `apelidos_7_3f.yml` (campeões, feitiços, runas, itens; e as palavras da gramática: rotas, lados,
  objetivos, dragões), editáveis em **Admin › Catálogo › Draft e voz** (tabela `draft_apelido`, por cima do seed; "Voltar
  ao seed"). Um teste confere que todo apelido aponta para um nome que existe nos catálogos.
- **Confirmação:** cada frase vira intenções, mostradas por campo ("campeão Darius", "feitiço Flash", "item Cutelo
  Negro"), com o que foi ouvido e a semelhança no título; o campo lido com tolerância fica destacado. ✓ aplica, ✗
  descarta, "Confirmar todos", e o Desfazer da barra. Nada é gravado sem aparecer na tela.

### Frases que o leitor entende

| Tipo | Exemplos |
|---|---|
| Tela de carregamento (lado opcional; vale para os campeões seguintes) | "time azul: Ornn barão, flash e teleporte, Aperto dos Mortos-Vivos, Fonte de Vida; Lee Sin selva, flash e golpear" · "inimigo Darius, flash e incendiar, Conquistador" |
| Compra (com ou sem "comprou"; componente também) | "Darius comprou Cutelo Negro" · "Ornn botas de mercúrio" · "Rumble comprou Varinha Explosiva" · "dárius comprou o cutelo" |
| Placar | "Ornn dois, um, cinco" · "Ornn 2 1 5" · "o darius tá com 3 abates 1 morte e 2 assistências" |
| Ouro e nível | "Darius 4500 de ouro" · "Darius 3 mil e 200 de ouro" · "Ornn nível 6" |
| Eventos | "Ornn morreu" · "Darius matou Ornn" · "o ornn morreu pro darius" |
| Objetivos | "dragão infernal azul" · "dragão de fogo pro azul" · "barão vermelho" · "arauto aliado" · "time azul pegou o barão" |

Regras: "barão" e "dragão" são rota logo depois de um campeão e objetivo quando vêm sozinhos ou seguidos de um lado ou
de um elemento; o lado dito antes ("time azul", "inimigo") vale até outro; "aliado" e "inimigo" seguem o lado aliado da
partida. Um jogador vai para o cartão do mesmo campeão, senão para o da rota dita, senão para o primeiro vazio do lado
(sem lado: o aliado). Compra, placar e eventos pedem o campeão já no tabuleiro ("não está na partida").

### Taxa de acerto

`frontend/test/voiceParser.test.mjs` (no `npm test`): 40 frases escritas como o reconhecimento do Chrome em pt-BR
escreve (números em dígitos, acentos, os erros comuns "orne", "dárius", "flex", "flesh", "lisin", "malfite"), contra
os seeds de verdade.

- **38 de 40 = 95%**, ~8 ms por frase.
- As 2 que erram estão lá de propósito: "a ahri comprou o capuz" e "zed comprou a lâmina". Há dois itens que começam com
  "capuz" e vários com "lâmina": o leitor não adivinha (mostra "Não entendi (capuz)").
- Ressalva: as frases foram escritas por mim junto com o leitor, não gravadas de uma transmissão; a taxa real com áudio
  vai ser menor. Quando o usuário usar a voz, vale juntar as frases que erraram e acrescentá-las ao teste e aos apelidos.

## Parte 3: draft com previsões que afunilam (✅)

**O que mostra** (painel "Draft" embaixo dos times, `components/DraftPanel.jsx`; `POST /api/draft/sugestoes`): o próximo
passo do draft, os **5 mais prováveis do inimigo** (previsão) e os **5 melhores para o aliado** (recomendação), cada um com
a nota e o motivo em uma linha ("rota Barão aberta; ganha a rota contra Darius (previsto) (medido); o time está sem dano
mágico"), e os **banimentos** ("os 5 que mais ameaçam o seu time": a lista do inimigo, a mesma conta invertida).

- **📌 Fixar uma previsão:** "acho que vão de Darius" vai para o cartão vazio da rota dele (`predictions` da partida; o
  cartão mostra "Darius?"); as listas se recalculam como se fosse verdade. ✕ tira.
- **Árvore de cenários, feixe 5** (`DraftModel.expand`): cada nó calcula só os 5 primeiros do lado que escolhe a seguir
  (pela ordem do formato); um ramo só abre ao tocar em ▸, e o caminho fica em migalhas ("Agora › Nidalee (Selva) › ...").

**A nota** (`arena/draft/DraftModel`, puro; pesos em `draft_7_3f.yml › nota`, explicados em "Como a nota é feita"), cada
parte de -1 a 1, soma ponderada × 100:

| Parte | Peso | De onde vem |
|---|---|---|
| meta | 0,25 | presença (0,4), banimento (0,2) e vitória centrada em 50% (0,4) na rota, do elo da partida; sem importação, 0 |
| rota | 0,35 | a rota principal livre = 1, a 2ª rota = 0,4; com todas as rotas dele ocupadas, o campeão fica fora da lista |
| matchup | 0,30 | a vantagem 1x1 medida na rota contra o adversário revelado ou previsto (tabelas; Dragão e Suporte pela dupla 2x2 quando os dois parceiros são conhecidos); sem medida, a subclasse contra a subclasse pelo perfil (log do dano por segundo × vida efetiva no nível 15, média da subclasse) |
| composição | 0,30 | o que falta ao time: dano mágico e físico (mínimo 30% cada), linha de frente (1), controle forte (2), iniciação (1), alcance longo (1), cura ou escudo (1) e o equilíbrio forte cedo / tarde, lidos de `ChampionProfiles` |
| contra | 0,20 | risco de contra-escolha: a média das 3 maiores vantagens dos disponíveis do outro time naquela rota (negativa) |

Os indisponíveis (banidos, escolhidos, Fearless) saem antes.

**Meta:** `tools/importar_meta.py` (só a biblioteca padrão; rodado pelo usuário) baixa o ranking público do servidor chinês
(`mlol.qt.qq.com/.../hero_rank_list_v2`: `appear_rate`, `forbid_rate`, `win_rate` por `hero_id`) e a lista de heróis
(`hero_list.js`), guarda em cache com a data (`backend/data/meta/cache/`) e escreve `backend/data/meta/meta.json`
(`MetaStats`, relido quando muda). O formato foi conferido nas respostas em 18/11/2026, com três limitações que o script
trata sem inventar:
- os nomes vêm só em chinês e pinyin: a correspondência heroId → nome do catálogo fica em `tools/meta_herois.json`
  (conferidos: 10001 Garen, 10002 Aatrox); os que faltam vão para `tools/meta_herois_faltando.json`;
- a rota de cada posição "1".."5" não está na resposta: sai da maioria das rotas da lista de heróis de cada posição
  (EST; o relatório mostra a contagem), ou `--posicoes`;
- a chave externa (provavelmente o elo) vira "fonte_<chave>".
Sem importação a parte da meta é neutra. **A meta não foi importada nesta rodada** (pede rede e o mapa de heróis).

**Tabelas pré-calculadas** (`DraftTablesService`, tabela `draft_tabelas`; botão em **Admin › Catálogo › Draft e voz**, em
segundo plano, com progresso e tempo por etapa): o 1x1 de rota de todos os pares de cada rota (os 20 mais jogados com meta;
sem meta, todos com ficha) aos 3, 8 e 15 minutos, com a build padrão da campanha (a mais provável da progressão quando não
há) no relógio do planejador, 3 largadas de 30 s, o cérebro tático sem fuga (o das lutas de referência do Atlas: barato e
simétrico); e a dupla do Dragão 2x2 (os 4 primeiros atiradores × os 4 primeiros suportes, toda dupla contra toda dupla).
Numa cópia do banco (142 campanhas), sem meta:

| Etapa | Pares | Tempo |
|---|---|---|
| Barão (55 campeões), 3 / 8 / 15' | 1.485 cada | 30,5 / 40,5 / 36,4 s |
| Selva (51) | 1.275 cada | 26,9 / 20,4 / 28,2 s |
| Meio (51) | 1.275 cada | 24,3 / 23,7 / 25,9 s |
| Dragão (24) | 276 cada | 5,7 / 8,8 / 9,5 s |
| Suporte (35) | 595 cada | 17,8 / 17,9 / 15,6 s |
| Dragão 2x2 | 54 cada | 2,4 / 2,3 / 2,3 s |
| preparo dos lutadores (848) | | 57 s (dentro das etapas) |
| **Total** | **14.892 linhas** | **5,7 min** |

Um pedido do draft leva ~0,3 s com as tabelas (a tabela fica em memória e é conferida a cada 5 s).

**Calibração dos pesos de composição** (no mesmo botão): times sorteados (um campeão por rota, dos 12 primeiros de cada)
lutam 5x5 no minuto 15, e uma regressão ridge das diferenças de composição contra a corrida dá os multiplicadores, como
no Atlas. **Calibração parcial:** rota única, sem o mapa e sem a partida simulada da Rodada 10 (o custo de uma partida
inteira por amostra não cabia). Resultado:

| Amostra | Correlação (na amostra) | Correlação validada (5 dobras) | Tempo |
|---|---|---|---|
| 60 lutas | 0,33 | 0,08 | 3,4 s |
| 300 lutas | 0,18 | **0,005** | 42 s |

Os traços de composição **não predizem** a luta 5x5 na rota única. Por isso a calibração só entra nos pesos com correlação
validada de 0,3 ou mais (`nota.calibracao_minima`). Abaixo disso a tela diz "calibração parcial... ficam os pesos do seed
(EST)". O próximo passo é medir a composição na partida inteira (Rodada 10), onde iniciação, alcance e linha de frente
pesam.

**Testes:** `DraftModelTest` (o feixe nunca calcula mais que 5 por passo, também ao abrir um ramo; fixar uma previsão
muda as listas e a resposta é quem ganha do previsto; o Fearless tira os usados, no escopo de cada lado; uma rota cheia não
recebe candidatos e com as cinco cheias a lista fica vazia; os banimentos são a lista do outro lado) e
`tools/test_importar_meta.py` (o script sobre um cache sintético, sem rede).

## Parte 4: o primeiro encontro na fase de rotas (✅)

**Painel "Primeiro encontro"** (`components/EncounterPanel.jsx`; `POST /api/draft/encontro`; `arena/draft/FirstEncounterService`),
para o campeão do cartão selecionado:

- **Opções de início:** os itens da loja que cabem no ouro inicial (500, `TimelineEngine.STARTING_GOLD`) e são vendidos
  no começo: os `secao: basico` de até 500 (Adaga, Anel da Revelação, Botas da Velocidade, Couraça de Pano, Cristal de
  Rubi, Espada Longa, Lágrima da Deusa, Luvas da Pancadaria, Manto Anula-Magia, Tomo Amplificador) e, para quem joga de
  suporte, os 2 `secao: preparacao` (Foice Espectral, Escudo Relicário): **10, ou 12 no suporte**. O Wild Rift não tem
  os itens iniciais do PC (Lâmina de Doran etc.): a loja transcrita confirma.
- **O adversário:** o revelado no cartão da mesma rota; senão a previsão fixada; senão os 3 mais prováveis do draft para
  a rota (Parte 3). No Dragão e no Suporte a luta é 2x2: o parceiro de cada lado entra com o início mais provável dele.
- **O início provável do inimigo:** pela progressão (Rodada 19): a chance de cada primeiro item do campeão se espalha
  nos inícios que estão na árvore dele (um pouco em todos). Quando o usuário informa o início real do inimigo ("Marcar o
  item inicial" no cartão dele), só ele entra e o painel recalcula sozinho.
- **As lutas:** na Arena, nos níveis 1, 2 e 3 (aos 0,5, 1,4 e 2,2 minutos: a 1ª onda sai aos 0:25), cada início do aliado
  contra cada início do inimigo, nos três estilos de rota no cérebro do aliado (seed `primeiro_encontro.estilos`:
  agressivo = tático sem fugir, padrão = o Automático, cauteloso = tático com fuga; o inimigo joga o Automático), com as
  runas e os feitiços informados (senão os da build padrão), 2 largadas. A **troca** é a vantagem de vida aos 4 s (−100
  a 100); o **all-in**, a corrida das barras até o fim (25 s).
- **A saída:** os inícios prováveis do inimigo com a chance; o melhor início do aliado com o estilo que mais rende (média
  do all-in nos três níveis, pesada pela chance de cada início inimigo); a tabela de troca e all-in por nível nos três
  estilos; o conselho por modelo de frase ("Pressione antes do nível 2 do X: você ganha o nível 1 (62) e ele ganha o
  nível 2 (44)", "Farme até o nível 3...", "ganha os três", "perde os três", "equilibrada"); e todos os inícios.
- **A selva (invasão no nível 1):** o mesmo painel no cartão da Selva; a coluna do nível 1 é a invasão.
- **Usa a Arena de rota única**, não a Arena com mapa: o 1x1 da fase de rotas é uma luta entre dois (com tropas e torre
  ficaria uma partida inteira por combinação, fora do orçamento de segundos). Fica para quando o mapa for barato.

**Tempo** (Ornn no Barão, 10 inícios de cada lado, 3 níveis × 3 estilos × 2 largadas = 1.800 lutas):

| Caso | Tempo |
|---|---|
| Darius revelado, servidor aquecido | **5,7 s** (1,7 s preparando) |
| Darius revelado, primeiro pedido (`DraftReportTest`, servidor frio) | 12,2 s (2,8 s preparando) |
| Adversário não revelado: os 3 prováveis (Rumble, Mordekaiser, Kennen), 5.400 lutas | 14,5 a 14,9 s |

Exemplo (Ornn × Darius): o Darius ganha os três níveis com qualquer início (melhor do Ornn: Espada Longa, agressivo, 41);
contra os prováveis sem o Darius, o melhor é Cristal de Rubi no estilo cauteloso (55).

**Testes:** `FirstEncounterTest` (a loja dá 10 inícios e 2 de suporte, 12 no total; o conselho segue os níveis medidos) e
`DraftReportTest` (opcional, `-Ddraft.report=data/ornn-planner.db`: o encontro de uma rota em menos de 30 s, com 10 a 12
inícios).

## Parte 5: builds prováveis durante a partida (✅)

**Painel "Builds prováveis"** (`components/LiveBuildsPanel.jsx`; `POST /api/draft/builds?medir=`; `LiveBuildsService`):

- As **10 builds completas mais prováveis** do campeão selecionado, pelo modelo de progressão da Rodada 19 condicionado
  aos itens e componentes comprados, às runas (pela build do campeão) e à composição dos dois times. A cadeia sozinha é
  rápida e acompanha cada compra; as lutas esperam o botão "Medir as lutas".
- **Ordem:** a importância para o contexto próximo = |o quanto o próximo item muda o duelo com o adversário de rota| (a
  corrida agora contra a corrida no minuto em que o item fecha) + 0,5 × |o ganho de Efetividade Básica| (a durabilidade
  média em 4 cenários 1x1 do `efetividade_7_3d.yml`, com e sem o item). A probabilidade fica ao lado; "Ordenar por
  probabilidade" troca a ordem.
- **Por build:** cada item com o minuto estimado (pelo ouro informado no cartão: o ritmo é (ouro − 500) / minuto, e cada
  item espera o que falta pagar descontados os componentes; sem ouro, o ritmo padrão da progressão, 700/min, com a bota
  aprimorada só depois dos 10:00), o duelo depois de cada um dos 3 primeiros itens (o adversário também cresce, pela
  continuação mais provável dele) e o que muda ao fechar: "duelo +45,6 · efetividade +3,3 · Ornn passa a ganhar do Darius
  a partir do 1º item".
- **O lado aliado:** a mesma lista e a resposta recomendada: para cada aliado com adversário de rota, entre os 3 próximos
  itens mais prováveis dele, o que vai melhor no duelo contra o adversário com o próximo item provável dele. O Atlas não
  entra (uma execução do Atlas leva minutos).

**Tempo:** 2,5 a 4,5 s por campeão com as lutas (10 builds; os prefixos iguais lutam uma vez).

## Parte 6: dicas de como jogar contra cada inimigo (✅)

**Painel "Dicas"** (`components/TipsPanel.jsx`; `POST /api/draft/dicas`; `TipsService`): para o aliado selecionado, as dicas
contra cada inimigo, **o adversário de rota primeiro**; o usuário pode escolher contra quem (um ou mais).

- **Sem texto inventado:** cada dica é uma chave de modelo de frase (pt e en no `i18n`) com os números medidos, e diz de
  onde veio: **Arena**, **ficha** (com a qualidade A a D) ou **guia**. ⚠ marca a ficha de qualidade C/D ou com números do
  PC / estimados (`fontes` com PC ou EST, ou uma `lacuna`).
- **Janelas de força (Arena):** quem ganha a troca agora e depois do próximo item de cada um (o mais provável da
  progressão, no minuto em que fecha), e se vira; antes dos 4 minutos e sem itens, os níveis 1 a 3 com os inícios (o
  informado ou o mais provável) e o nível em que a troca vira.
- **Ameaças do kit (ficha):** para cada habilidade aprendida (os ranks e a Aceleração de Habilidade do inimigo no minuto,
  pelo lutador preparado), o controle forte (atordoa, arremessa, enraíza, puxa, silencia, amedronta, suprime, provoca,
  encanta, adormece, polimorfa, empurra; lentidão sozinha não conta) com a recarga no rank atual: "Depois do 3 dele
  (Apreender, puxa; recarga ~13,8 s no rank 1), você tem a janela para trocar"; o alcance: "Fique fora de 450 de alcance
  do 3 dele"; os avanços e saltos (`mobilidade` da ficha); a ultimate com a recarga.
- **Guias da Rodada 14:** o estilo de rota que mais rendeu na campanha mais nova que mediu os estilos: contra o próprio
  campeão, se a campanha o mediu ("trocas curtas, +2.361 de ouro aos 10 min" para o Ornn contra o Darius), senão contra a
  subclasse dele.

**Tempo:** 0,7 a 4,7 s (três inimigos; mais no começo, em que cada nível é uma luta preparada).

**Testes:** `TipsTest` (o controle vem da habilidade e das zonas dela: o E do Darius puxa, o Q não abre janela; a recarga
no rank; as estimativas marcadas; a corrida em palavras) e `DraftReportTest` (as dicas do Ornn têm todas uma fonte).

## Relatório final

### As telas

`docs/rodada-20/`:

| Tamanho | Arquivos |
|---|---|
| 1920 × 1080 (Chrome headless) | `draft-1920x1080.png` (times e draft), `encontro-1920x1080.png` |
| 844 × 390, paisagem | `draft-844x390.png` (topo: a configuração recolhida, o relógio, as ações e a voz), `draft-844x390-times.jpg` (os cartões, com o Desfazer flutuante) |
| 390 × 844, retrato | `draft-390x844-topo.jpg` (ações em grade de 3), `draft-390x844-times.jpg` (as duas colunas e o draft) |

Alvos de toque de 44 a 52 px; a configuração da partida fica recolhida quando já há campeões; no celular (em pé ou
deitado) a barra deixa de ser fixa e o **Desfazer flutua no canto**, sempre visível. Um link abre a partida direto:
`#/simulacoes/draft?jogo=1&sel=azul:0&painel=encontro`. As capturas em 390 de largura saíram do painel do navegador do
app (o Chrome headless não abre janelas tão estreitas).

### A taxa de acerto do leitor de voz

**38 de 40 = 95%** (`frontend/test/voiceParser.test.mjs`); as 2 falhas são nomes ambíguos de propósito ("o capuz", "a
lâmina"). Ressalva: frases escritas, não áudio gravado.

### O tempo das tabelas do draft e do primeiro encontro

- Tabelas do draft, completas: **5,7 min** (14.892 linhas; tabela por etapa na Parte 3). Calibração 5x5 com 300 lutas:
  42 s.
- Pedido do draft (listas e árvore): ~0,3 s.
- Primeiro encontro de uma rota (10 inícios × 10, 3 níveis, 3 estilos): **5,7 s** aquecido, 12,2 s frio; com 3
  adversários prováveis, ~15 s.
- Builds prováveis com as lutas: 2,5 a 4,5 s. Dicas: 0,7 a 4,7 s.

### O que depende de ficha C/D, e por isso pode errar

As fichas de `kits_7_3f.yml`: 6 de qualidade A, 5 B, **114 C e 18 D** (Rodada 18), a geometria (alcances, raios,
larguras) quase toda **EST** (valores do PC, a conferir no Treino). Dependem delas:

- todas as lutas (tabelas de matchup, primeiro encontro, duelos das builds, janelas das dicas): um alcance errado muda
  quem acerta primeiro;
- as dicas do kit: os alcances ("fique fora de 450") e as recargas; elas aparecem com ⚠ quando a ficha é C/D ou o número é
  do PC / estimado;
- o perfil (`ChampionProfiles`: controle, alcance, avanços) que a composição do draft lê.

### Pendências

1. **A ordem do draft do Wild Rift** (no começo deste doc): a de agora é a do LoL de PC (EST).
2. **A meta:** rodar `python tools/importar_meta.py`, preencher `tools/meta_herois.json` (heroId → nome) a partir de
   `tools/meta_herois_faltando.json` e conferir a rota de cada posição no relatório do script.
3. **A composição** não se mede na luta 5x5 de rota única (correlação validada ~0): medir na partida inteira.
4. O primeiro encontro na Arena com mapa (tropas e torre) e a resposta aliada pelo Atlas ficaram de fora pelo custo.
5. A taxa do leitor de voz com áudio de verdade.
