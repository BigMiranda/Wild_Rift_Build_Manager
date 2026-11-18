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
