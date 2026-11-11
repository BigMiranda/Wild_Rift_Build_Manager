# Rodada 13: Descoberta mais confiável (acúmulos, ordem, runas, objetivo) e o site com as melhores builds

Levantado olhando o site estático publicado (`site-publico/`, 8 campeões) e o código da Descoberta. Nenhum dos problemas
vinha do treino nem da pool de campeões: a nota da build é medida contra bonecos de teste, não contra campeões. A pool
pequena só pesa na novidade (com 1 build conhecida, quase tudo é "novo"), nos duelos (peso 0) e nas matchups.

## O que estava errado

| Sintoma no site | Causa |
|---|---|
| Bastão das Eras em tanques (Ornn, K'Sante, Darius, Renekton), comprado aos 17-18 min | O Veterano (`quando: estatico`) valia os 10 acúmulos (+150 Vida, +300 Mana, +40 PdH) no minuto da compra: comprar tarde não custava nada e o item rendia 500 de Vida por 2700 desde o primeiro segundo. O Mejai, igual. |
| "Padrão robusta" do Renekton com 2 itens e nota "péssimo"; Descoberta 2 do Rumble com 3 | A ordem dos finalistas vem da calculadora com `orderBudget` 60 (rápido); sem orçamento para uma ordem completa, ela devolve o melhor começo, e a Descoberta gravava esse começo como a ordem. |
| Nichos sem runas (K'Sante, Ornn, Vayne); nos outros, Conquistador em tudo | Só os finalistas (1 no rápido, 3 no completo) tinham runas buscadas; o site caía na página da primeira build pública, e esses campeões não têm build pública. |
| Builds exóticas (Renekton "suporte · mágico" com Redenção e Mandato) | O site publicava o melhor de cada nicho (MAP-Elites), que existe para guardar o melhor de cada estilo, mesmo os ruins; as descobertas também saíam dos nichos. A novidade **não** entra na nota. |
| Notas que não batem com o ranking ("bom 85" num nicho, "péssimo" na padrão) | O site mostrava o índice defensivo Básico no último item, não a nota por classe que ordenou as builds. |
| Texto da descoberta ilegível | "troca 6 itens + bota por 6 itens + bota" quando quase tudo muda. |

## O que mudou

### Acúmulo por tempo de posse (`acumulo_por_posse`)

`efeitos_itens_7_3d.yml` ganha a seção `acumulo_por_posse`, por "item|passiva" das linhas de `efeitos` da loja: a linha
vale o valor cheio x acúmulos / max, com 1 acúmulo a cada `a_cada_s` segundos **desde a compra**; `inteiro_a_partir`
dá um status inteiro a partir de N acúmulos.

- Bastão das Eras|Veterano: 1 a cada 35 s, máximo 10 (o texto da loja).
- Ladrão de Almas de Mejai|Temor: premissa de 1 acúmulo de Glória por minuto (um abate ou assistência a cada ~2 min,
  menos as mortes), máximo 30; os 10% de Velocidade de Movimento só a partir de 10 acúmulos. É uma premissa: ajustar
  no YAML se as partidas disserem outra coisa.

No motor: `StatLine.heldEvery / heldMax / heldFrom` e `heldFraction` (acúmulos inteiros); `TimelineEngine.Owned` guarda
o minuto da compra; `EngineInput.acquiredAt` dá o minuto real a um inventário hipotético (`PlannerService.snapshot`, que
a Arena usa: compra tudo no minuto 0). O `snapshot` procura o minuto na linha do tempo da própria build; um item que não
está nela (posto agora, por exemplo numa troca) começa sem acúmulos. As linhas por razão (ratio) não usam o tempo de
posse (nenhuma delas tem acúmulo assim hoje).

Na calculadora, a medida passou a ser a da **ordem** (não do inventário ordenado por id) e a chave do cache leva o minuto
de compra dos itens com acúmulo: o mesmo conjunto com o Bastão comprado depois vale menos.

### Ordem completa

`DiscoveryService.finish`: a ordem da calculadora só é aceita se compra todos os itens e a bota do kit; senão fica a ordem
da triagem (o item que mais vale primeiro), `orderCut` marca o kit e uma nota explica. O `orderBudget` subiu: rápido
60 → 240, completo 400 → 800.

### Runas e ordem do que é publicado

`DiscoveryService.publish`, depois da novidade: a padrão robusta e cada descoberta recebem runas buscadas e a ordem da
calculadora (um kit igual a outro já pronto herda dele). Os outros kits mostrados (ranking, nichos) herdam a página da
padrão, marcados `runesFrom: padrao`; `busca` = buscadas para o kit. O site mostra a origem ("*" no card, a frase na
página da build) e "Sem runas" quando não há nenhuma.

### Objetivo: melhores builds (padrão) ou novidade

`DiscoveryModel.Request.objective` (`melhores` | `novidade`), nas telas Descoberta e Campanhas.

- **melhores**: descobertas só do ranking, e só quando o campeão tem ao menos `novidade.minimo_conhecidas` (2, em
  `nota_builds_7_3d.yml`) builds conhecidas medidas; senão uma nota diz por quê.
- **novidade**: como antes (ranking e o melhor de cada nicho, sem mínimo).

A novidade continua fora da nota nos dois.

### Texto da descoberta

"Nota 55,1 contra 45,0 de Armor Shredder (a melhor conhecida). Outro conjunto: mantém X; entram A, B, C, D. Ganha em …
Perde em …" — quando mais de 3 itens entram, diz o que fica e o que entra em vez de uma troca gigante.

### Site estático

- `champion_builds`: a padrão, as 3 primeiras do ranking (`melhor`), as públicas na seção **melhores**; nichos e
  descobertas na seção **Novidade**, recolhida (aberta quando o objetivo da campanha foi "novidade"; resultados antigos,
  sem objetivo, contam como "novidade").
- Cada card mostra primeiro a **Nota** (a nota por classe que ordenou, com a escala de notas do relatório) e depois a
  Defensiva e a Ofensiva no último item.

### Itens de suporte e formas evoluídas

A Descoberta e a calculadora só montavam conjuntos com itens "aprimorado": o Bastião da Montanha (seção `evolucao`, a
forma evoluída do Escudo Relicário) e as outras formas evoluídas (Fimbulwinter...) ficavam de fora. Pior: as builds de
suporte salvas (Sup Rush Warmog, Sup Desengaje, Sup Rush Armadilha) entravam como sementes **sem** o item de suporte e eram
medidas assim. Agora `evolucao` conta como item completo na triagem, nas sementes (`kitOf`) e nos candidatos da
calculadora, e a forma evoluída de um item de preparação (o item de suporte) vai primeiro na ordem da triagem.

### Itens que melhoram sozinhos com a partida (parte 2)

| Item | Antes | Agora |
|---|---|---|
| Coração de Aço (Consumo Colossal, taxa da build) | golpes contados desde 0:00 | desde a compra: `StatLine.from(início)` recorta os períodos de qualquer taxa por minuto de item (runas e efeitos do campeão seguem desde 0:00) |
| Manamune, Cajado do Arcanjo, Aproximação Invernal, Tiara Sussurrante (Carga de Mana) | +700 de Mana na compra | 1 carga a cada 7 s (metade do ritmo máximo), 50 cargas, contadas desde a Lágrima |
| Muramana, Abraço de Seraph, Fimbulwinter, Diadema de Canções | comprados já evoluídos | `evolucao_por_tempo`: valem como o item base por 6 min depois da compra (ou da Lágrima) |
| Bastião da Montanha, Foice da Névoa Negra | evoluídos na compra, Conjuração Anímica cheia | base (Escudo Relicário / Foice Espectral) por 7 min (Missão de 750 de ouro); depois +25 Vida / +2 DdA por minuto até 10 |
| Flechatroz de Yun Tal (Praticar e Matar) | 25% de crítico na compra | 1% a cada 12 s desde a compra (25 ataques a distância por minuto); a opção vira o teto |
| Grevas Vorazes, Passos Imortais (Conversão) | abates = 0 (só os 5%) | 5% fixos + 0,5% por abate, 1 a cada 2 min desde as Grevas, até 10; a opção vira o teto (padrão 10) |
| Armadilha de Yordle (Apanhador) | o ouro dos abates com o fortalecimento não entrava | `ouro_por_posse`: 20 de ouro a cada 2 min desde a compra (premissa: 1 participação em abate com o bônus ativo a cada 2 min), no ouro da linha do tempo; o bônus de 8 s segue na Arena |

No motor: `ItemDef.evolveAfter / evolvedFrom` (a contribuição do item mostra o item base enquanto não evolui, e a Arena
lê o item base, com os efeitos de combate dele), `ItemDef.stacksFrom` (`herda_acumulos`: a compra mais antiga entre o
item e os componentes que ele consome), `ItemContribution.acquired` (o minuto que a Arena e a calculadora usam).
`acumulo_por_posse` também vale numa linha de `efeitos_itens` (Yun Tal, Grevas).

As taxas (cargas da Lágrima, ataques do Yun Tal, abates das Grevas, a Missão do suporte) são premissas no YAML, ajustáveis.

### Item de suporte: só em build de suporte, primeiro item, ritmo próprio

- Um item de suporte por build e sempre a primeira compra: a calculadora recusa outra ordem (e, com ele obrigatório,
  qualquer começo sem ele).
- Só para campeões que jogam de suporte: a rota Suporte no perfil, ou uma build salva/meta do campeão com item de
  suporte (o Ornn tem as suas).
- Uma build de suporte ganha menos ouro: o ritmo é a média das builds de suporte salvas do campeão (Ornn: 600-650
  ouro/min), senão o da base x `suporte` de `nota_builds_7_3d.yml` (0,86 do ouro, 0,9 da XP).
- Ela não compete com as outras no ranking (joga com menos ouro): a **Melhor de suporte** (`Result.support`) é a de maior
  nota entre os conjuntos medidos com item de suporte, recebe runas e ordem, e aparece no site na seção principal e na
  tela Descoberta. A busca começa também de versões de suporte das sementes (o item de suporte + os 4 primeiros itens).
- Não feito: a regra "um item de suporte por time" na partida inteira (`Match`): cada build é medida sozinha.

### Guia como fluxograma

`components/GuideFlow.jsx`: o guia desenhado como árvore de decisão (compras uma embaixo da outra, cada condição um
losango com os ramos "sim" e "não" lado a lado), no site (no lugar das listas aninhadas) e na tela Guias (abaixo do
editor, que continua em lista). A guia sugerida pela Descoberta repete a build inteira em cada ramo: é a sugestão, não
o desenho.

### Campanha do site (o que foi rodado em `data/site-teste`)

A campanha "Site 11b" rodou com orçamento **rápido** e só para ORNN, DARIUS, KSANTE, RENEKTON, RUMBLE e VAYNE;
Mordekaiser e Yasuo não estavam na lista (aparecem só com a build pública; a ficha do Mordekaiser é D, que a opção "pular
baixa confiança" também pularia). As 34 builds salvas do Ornn foram sementes e medidas (a melhor, Soldado v0, 72,1), e
filhos delas passaram (74,6), em parte pelo Bastão das Eras inflado. Elas não aparecem no site porque a pasta "Ornn" não
está marcada como pública, e a exportação tira as builds privadas da descoberta. A tela Campanhas passa a sugerir o
orçamento completo.

## Para valer no site

Rodar a campanha de novo (os resultados guardados são de antes) e regerar o site (`python tools/publicar_site.py`).
Os resultados guardados continuam legíveis: sem `section`, o site usa o tipo; sem `runesFrom`, deduz.

## Campanha mais rápida: kits medidos ao mesmo tempo

A campanha usava pouco a CPU: as lutas de um kit já rodavam em todos os núcleos, mas cada lote é pequeno (poucos
cenários, as referências em cache), e os kits eram medidos um de cada vez. Agora `Funnel.parallel` (metade dos núcleos)
kits são medidos ao mesmo tempo nas rápidas, na bateria e na confirmação, e a busca de runas mede de uma vez as páginas
que a próxima coordenada vai testar (`RuneSearch.prefetch`) e depois anda na mesma ordem de antes. O resultado não muda:
cada kit é medido sozinho e de forma determinística, e cada leitura vai para o seu próprio lugar.

`EffectTestService.evaluate` passou a guardar as leituras e as gravações do próprio lote: o cache de lutas é um LRU, e
outro lote rodando ao mesmo tempo podia tirar dele uma leitura entre o plano e a leitura. A ordem pela calculadora
mede de uma vez as ordens de cada camada que o orçamento restante alcança (`BuildSearch.Oracle.prefetch`); cada medida só
conta quando a busca a lê, então o orçamento e a resposta são os de antes.

Medido na Descoberta rápida do Ornn (Big Carry v1): 372 s em sequência, 237 s em paralelo (-36%), com o mesmo resultado
(ranking, descobertas, nichos, padrão, suporte, notas) e as mesmas 544 medidas de ordem.

`DiscoveryReportTest` aceita `-Ddiscovery.parallel=1` (e `-Ddiscovery.suffix`) para comparar com a versão sequencial.
