# Rodada 9 — Builds: calculadora refeita, descoberta de builds e runas, árvores de decisão e busca em massa

Um commit por parte, sem push. Cada parte termina com `./mvnw test` e `npm run build` limpos.

**Branch:** `rodada-9`, criada a partir do `main` (9cff69c, já igual ao `origin/main`).

**Testes antes da rodada:** 336 (14 opcionais pulados).

## O tamanho do problema

Contado pelas regras da loja e das runas do 7.3d com `tools/espaco_busca.py` (lê os YAML do patch):

| o quê | quantos |
|---|---|
| itens aprimorados sem botas | 102 |
| botas tier 2 / tier 3 | 7 / 7 |
| grupos exclusivos entre os aprimorados | Lâmina Arcana 6, Lágrima da Deusa 4, Salva-Vidas 4, Penetração de Armadura % 4, Penetração Mágica % 3 |
| conjuntos de 5 itens sem regra | 83.291.670 |
| conjuntos de 5 itens com os grupos exclusivos | **77.830.488** |
| builds sem ordem (5 itens + a bota final, tier 2 ou 3) | **1.089.626.832** |
| builds com ordem (6 compras; a tier 3 tem a tier 2 como passo antes dela) | **1.765.195.467.840** (1,8·10¹²) |
| páginas de runas (fundamental + 1 por linha da principal + 1 de outra árvore) | **52.104** (13 × 4.008) |
| builds com ordem × páginas | **9,2·10¹⁶** |

Sem contar componentes como passos, feitiços e o minuto de cada compra. A 1 ms por avaliação, só as builds com ordem
levariam 56 anos; com as páginas, 2,9 milhões de anos. Por isso a busca é em funil (Partes 2 a 4).

## Parte 0 — Calculadora: correções e o que falta ✅

### Causas corrigidas

| causa | correção |
|---|---|
| só `secao: aprimorado` entrava: botas tier 2 nunca | toda bota tier 2 e tier 3 entra como passo, fora dos filtros de aba |
| "só tanque" exigia a aba Defesa e tirava as botas aprimoradas | o filtro vale só para os itens; a bota tem espaço próprio |
| componentes e meios itens nunca entravam | entram como passos, em dois modos |
| `held` contava um componente depois de consumido | o que a ordem tem num minuto é o inventário depois da última compra até ali |
| minutos 8/12/16/20: item pronto antes das 8:00 tinha importância 0 | padrão **1, 2, 3, 4, 8, 12, 16, 20** (o valor salvo antigo é trocado ao abrir a tela) |

### Busca por passos (`BuildSearch.steps`)

`BuildSearch.Space` diz o que cada candidato é (`ITEM`, `BOOTS` tier 2, `BOOTS3`, `COMPONENT`), de quais itens cada
componente faz parte (a árvore inteira da receita) e quantos espaços preencher (`length`, 1 a 5, além da bota).

- **Seis espaços, o 6º é bota:** uma ordem só é completa com bota, salvo "Sem bota". A tier 3 só entra sobre a tier 2
  que está na receita dela, ou direto; a busca escolhe **quando** comprar a bota, pelo feixe sobre a ordem.
- **Moderada (padrão):** no máximo um componente isolado na ordem (precisa ser de um item ainda não comprado). Depois da
  busca, `componentFirst` tenta cada componente de cada item logo antes dele na ordem final e guarda os ganhos: "qual
  componente do próximo item fazer primeiro". A melhor linha já sai com eles.
- **Agressiva:** componentes até ocupar os `length` espaços, fechados depois (o item da receita consome o componente
  pela linha do tempo) ou nunca. A tela avisa que o espaço é muito maior e pede confirmação com a estimativa.
- **Quando fechar:** sai da própria ordem: "Cota de Malha → … → Armadura de Espinhos" é o componente num passo e o item
  num passo depois; a linha do tempo do planejador desconta o componente e aplica a checagem única da loja.
- **Pool de completas:** toda ordem completa vista fica guardada, então uma ordem com menos passos não se perde para as
  mais longas do feixe.

### Tela

- Modo (moderada, agressiva) com `?`, "Sem bota", minutos novos, "Itens" = espaços além da bota.
- Estimativa antes de começar, com a conta do espaço ("30 itens, 14 botas, 25 componentes").
- **A tabela virou colunas lado a lado:** a melhor e as alternativas, com a ordem em ícones (o minuto em que cada compra
  fica pronta embaixo, componente em ícone redondo, "fechado por …" no `title`), a nota e a nota de cada minuto com a
  diferença para a melhor, o Δ da build inteira e as ações. O que difere da melhor fica marcado.
- Cada linha tem um `?` com o que o número é, de onde vem e se maior é melhor (`components/Help.jsx`).
- **Abrir como build:** a build base com a ordem achada, como rascunho não salvo (`openDraft` no `buildContext`), em
  Build › Loja. **Comparar com a minha:** o Δ da build inteira (`/api/quick/delta`, troca de ordem) da build escolhida
  para a ordem achada.
- "Qual componente primeiro" e a importância cedo com ícones.

**A medida por classe e as runas** aparecem na tela desde já, mas entram nas Partes 1 e 4 (até lá a medida é o índice
do relatório, como antes).

### Testes

`BuildSearchTest` (+6): a bota sempre num dos espaços e nenhuma com "Sem bota"; a moderada só com um componente isolado
e `length` itens; um componente forte comprado cedo e fechado depois; a agressiva com componente que nunca fecha;
"qual componente primeiro"; determinismo.

Testes: 336 → 342 (14 opcionais pulados). `npm run build` limpo.

## Parte 1b — Efetividade ofensiva ✅

A build testada **ataca** um boneco-alvo que não ataca e morre de verdade, com o cérebro automático (`auto`), e a medida é
quão bem ela o mata. Fica em Relatórios › Efetividade, na aba **Causar dano** (a defensiva virou a aba **Aguentar**), e
os alvos ficam no Admin › Configuração da Efetividade › **Alvos (causar dano)**, com presets só de leitura,
"Duplicar", alvos do usuário e o relatório (pesos e varreduras).

**Serviço próprio:** `arena/OffenseTestService` (+ `OffenseModel`, `api/OffenseController`, `/api/offense/...`). Os
alvos do usuário e o relatório salvo ficam na tabela `effect_test` com `kind` `alvo` e `relatorio_ofensivo`, **sem
migração**. As variantes por item (`sem`, `so_status`, `ouro`) são as mesmas da defensiva (`EffectTestService.fighter`).

### Os alvos (`ofensivo` em `efetividade_7_3d.yml`)

Os números do prompt, sem mudança. Cura e escudo são pulsos do boneco (o mesmo mecanismo dos bonecos atacantes). Dois
campos novos no `ArenaModel.Dummy`, que só valem para quem os usa (os golden não mudaram):

- `growTo` / `growSeconds`: a Armadura e a RM sobem até o valor ao longo do tempo, contado do primeiro golpe;
- `dashEvery` / `dashDistance` / `seed`: um avanço a 1.200 unidades/s, na direção sorteada pela semente da largada
  (`Arena.unit`, splitmix64: igual em qualquer thread).

O "revide leve" é um pulso de dano físico por segundo, desligado nos presets.

### Medidas

| medida | como |
|---|---|
| tempo para matar | a morte do alvo, ou "não matou em 30 s" com a vida restante |
| DPS efetivo | vida tirada por segundo, depois das resistências, escudos e cura |
| % perdido | (bruto − depois das resistências), escudo absorvido e cura do alvo, sobre o bruto |
| acertos | `mira_lancada` / `mira_acertou` (formas miradas, sem canal nem avanço) e `ataque_iniciado` / `ataque_acertou`: contadores novos em `events`, que não entram na impressão digital dos golden |
| tempo em alcance | `em_alcance`: segundos com o alvo no alcance de ataque, contados só quando há boneco mortal |

**Índice ofensivo** = tempo para matar da referência ÷ o da build × 100. **Decisão:** o tempo comparado é o
**contínuo** (`smoothTime` = vida do alvo × tempo ÷ dano líquido, com o excesso do último golpe): o tempo da morte vem
em degraus de um ataque, e duas builds que matam no 17º ataque davam índice idêntico (achei isso no teste da
penetração). Quando um dos dois não mata, o índice é a razão das taxas de dano efetivas (`byRate`), como a Rodada 7 fez
na defensiva.

**Leituras:** "como joga" (uma largada) ou "média de 6 largadas" (distância, lado e a semente dos avanços variam).
Por item: sem o item, só os status, e o mesmo ouro em status puros, numa das misturas do YAML (DdA, PdH, penetrações,
velocidade de ataque, DdA + VA). Varreduras de 5 estágios: Vida 1.000–10.000, Armadura e RM 0–400, cura 0–5%/s,
escudo 0–400/s, velocidade 300–550 (andando) e 0–2 avanços/s.

### Calibragem (aos 15:00, 800 de ouro/min, uma largada)

`-Doffense.report=true` (`OffenseTestsTest#calibrationReport`). Tempo para matar da build (e da referência sem itens), índice:

| alvo | Vayne (Gume + Dominik) | Darius (Serrespada + Serylda) | Ornn (Égide + Espinhos) |
|---|---|---|---|
| Frágil | 4,0 s (10,4) · 238 | 6,0 s (7,7) · 126 | 6,9 s (9,1) · 119 |
| Muita vida | 10,4 s (18,8) · 185 | 13,9 s (21,8) · 163 | 19,9 s (23,2) · 116 |
| Muita vida e cura | 13,4 s (> 30) · 247 taxa | 17,4 s (> 30) · 254 taxa | 29,2 s (> 30) · 179 taxa |
| Pouca vida, muita resist. | 9,4 s (18,8) · 197 | 10,3 s (19,4) · 195 | 29,3 s (> 30) · 125 taxa |
| Muita vida e resist. | 16,4 s (21,2) · 130 | 23,4 s (> 30) · 199 taxa | > 30 s, 34% resta · 130 taxa |
| Escudo contínuo | 16,4 s (> 30) · 278 taxa | 22,1 s (> 30) · 495 taxa | > 30 s, 84% · 120 taxa |
| Resistência crescente | 10,4 s (21,2) · 207 | 15,2 s (> 30) · 201 taxa | > 30 s, 10% · 127 taxa |
| Escudo grande em janela | 16,4 s (> 30) · 188 taxa | 20,8 s (> 30) · 184 taxa | > 30 s, 40% · 145 taxa |
| Esquiva por velocidade | 6,6 s (15,6) · 223 | 9,0 s (14,5) · 162 | 14,8 s (23,5) · 157 |
| Esquiva por avanços | 7,2 s (16,3) · 231 | 8,2 s (13,4) · 176 | 23,4 s (24,9) · 107 |

O que a tabela diz: o tanque com 2 itens de tanque não mata os alvos resistentes em 30 s (é o esperado, e a nota por
classe da Parte 1 não cobra isso dele); o Escudo contínuo de 80/s come 15–23% do dano bruto; contra a cura de 2%/s
nenhuma referência sem itens mata.

**Calibragem a rever:**
- **Os perfis de esquiva quase não esquivam numa largada:** o Ornn acerta 5/5 do Q contra os avanços. Com a média de
  largadas aparecem erros (o teste confere que a mira erra mais que o ataque com alvo). A causa provável é que a IA
  lança perto do alvo, onde o avanço de 300 não tira o corpo da linha a tempo. Não mexi nos números do prompt.
- **Índice pela taxa e pelo tempo misturados** na média ponderada: quando só a referência não mata, o índice vem pela
  taxa e tende a ser maior (Escudo contínuo do Darius: 495). Está marcado "pela taxa" na tela.

### Testes

`OffenseTestsTest` (4 + o relatório opcional): mais penetração % (Rancor de Serylda contra o mesmo ouro em DdA) sobe o
índice contra "muita resistência" mais que contra "frágil"; o corta-cura (Serrespada, inteira contra só os status)
sobe o índice contra "muita cura" e quase nada contra "frágil"; nos perfis de esquiva a mira acerta menos que o ataque
com alvo (média de 4 largadas); determinismo, com os avanços sorteados.

Testes: 342 → 347 (15 opcionais pulados). `npm run build` limpo. Golden sem mudança.

## Parte 1 — O que é uma "boa build" (nota por classe) ✅

`arena/calc/BuildScore` (+ `seed/nota_builds_7_3d.yml`, `api/ScoreController`, `/api/score`). A nota de uma build num
minuto junta, **na mesma escala de pontos** (0 = como o campeão sem itens, 100 = perfeita):

| parte | de onde vem |
|---|---|
| defensiva | o índice da Efetividade defensiva no relatório Básico, com o **comportamento fixo** da Rodada 7 |
| ofensiva | o índice ofensivo da Parte 1b em pontos: 100 × (1 − 100 / índice), na **média de 3 largadas** |
| utilidade | controle aplicado (s por 10 s de luta × 25) e a parte do dano do time absorvida, até 100 |
| duelos | x1 contra os representativos de cada classe (fichas A ou B, primeira build salva de cada classe), com o cérebro automático; **peso 0 por padrão** |

**Pesos por classe** (YAML): Tanque 70% defensiva, 20% utilidade, 10% ofensiva; Lutador 45/45/10; Atirador, Mago e
Assassino 100% ofensiva, com **piso de sobrevivência** (abaixo de 20 de defensiva, perde a diferença; Assassino 10);
Controlador 40/40/20 de defensiva/utilidade/ofensiva. O **arquétipo** da build ajusta: build de tanque +15 de
defensiva, crítico e letalidade +15 de ofensiva, suporte +20 de utilidade. A classe é a primeira classe principal do
perfil (`ChampionProfiles.classesOf`); nenhuma regra cita campeão.

**Robustez:** a nota de um minuto = 60% da média ponderada + 40% do **percentil 20** dos cenários das partes com peso,
menos o piso. A nota robusta junta 10, 15, 20 min e o fim da build (última compra ou fim da partida), com pesos
decrescentes 1,0 / 0,9 / 0,8 / 0,7. "Funciona contra a maioria" = a % de cenários-minuto em que a build supera a build
de referência (`Robust.beatsReference`).

**Exemplo (aos 20:00, `BuildScoreTest`):** Ornn com 3 itens de tanque 40,7 (defensiva 46,4, P20 34,5) contra 15,4 com
3 itens de dano; Vayne com 3 itens de dano 42,8 (ofensiva 66,9, piso −22,3) contra 6,2 com itens de tanque.

**Na calculadora:** a "Medida" padrão passou a ser a **nota por classe**; "Só o relatório defensivo" é a medida antiga.
Isso resolve "a busca escolhia itens de dano para um tanque".

**Na tela:** Relatórios › Efetividade ganhou a aba **Nota**, com a nota robusta, cada minuto (classe, arquétipo, partes
com os pesos, média, P20, piso) e a build de referência.

### Conferido na tela (servidores `backend-r9` 8092 e `frontend-r9` 5176, banco copiado em `data/dev-r9`)

Calculadora com Big Carry v1, só tanque, 1 item + bota, nota por classe, orçamento 300: 249 medidas em 131 s
(0,53 s por medida). A resposta veio, mas **o orçamento acabou antes da bota**: com 70 candidatos (30 itens, 14 botas,
26 componentes) e 8 minutos, só o primeiro passo custa umas 200 medidas. Achados e correções:

- **A busca devolvia nada** quando o orçamento acabava antes da primeira ordem completa (achei com orçamento 60).
  Agora devolve os melhores prefixos, e a tela já dizia "o orçamento acabou".
- **A estimativa escondia o tamanho real:** ela cortava no orçamento. Agora mostra também o total sem corte e avisa
  quando o orçamento não cobre a busca (8.960 medidas no exemplo).
- **Sinal duplicado** ("−-0,8") na importância cedo; corrigido.
- Melhor achado: Cinto do Gigante (0:43) → Coração de Aço (3:17), "Cinto do Gigante antes de Coração de Aço (+2,2)".

A busca inteira com a nota por classe é cara (0,5 s por medida contra 0,1 s só do relatório). É o motivo do funil da
Parte 2.

Testes: 347 → 351 (`BuildScoreTest`, 4; 15 opcionais pulados). `npm run build` limpo.

## Partes 2 e 3 — Funil de avaliação e busca dos conjuntos ✅

Num commit só: o funil (Parte 2) é o que mede cada passo da busca genética (Parte 3), e os dois só fazem sentido juntos.
Os algoritmos estão separados de como uma build é medida: `arena/discovery/Funnel` e `Ridge` são puros (testados num
mundo sintético), e `DiscoveryService` os mede pela nota por classe da Parte 1 (`/api/discovery/...`).

### O funil

1. **Triagem sem luta:** para cada item aprimorado, o Δ da build inteira de comprá-lo sozinho aos 15:00
   (`BuildDeltaService`, o mesmo dos comparadores): o ouro dos status dele separado nas famílias ofensivas (pesadas pela
   parte física e mágica do dano do campeão, do perfil da Rodada 7) e na defensiva, mais as passivas modeladas, pelos
   pesos da classe, dividido pelo custo. Lista curta de 24 (rápido) ou 30 (completo) itens, **com cota de 2 por grupo**
   (cada aba da loja e "com passiva" / "só status") e **coringas** (4 ou 6) sorteados entre os descartados.
2. **Lutas rápidas:** a nota aos 15:00 com 3 cenários de cada relatório, uma largada. Corta a metade pior
   (*successive halving*).
3. **Bateria completa:** a nota robusta da Parte 1 (todos os cenários, 10/15/20/fim).
4. **Confirmação:** os finalistas e o melhor de cada nicho com a média de 6 largadas nos dois lados, que a busca nunca
   viu.

O cache por (build, minuto, cenário, leitura) é o dos serviços de efetividade; as lutas rodam em paralelo dentro de cada
leitura. Barra de progresso por etapa e "Parar" no trabalho (`DiscoveryModel.Job`).

### A busca

- **Genética** sobre 5 itens + 1 bota (tier 2 ou 3): torneio, cruzamento (itens sorteados da união dos pais, bota de um
  deles), mutação (troca um item por outro da lista curta, ou a bota), elitismo (a próxima população é o melhor de
  tudo o que foi medido). As regras da loja pela linha do tempo do planejador (a mesma checagem única).
- **População inicial:** as builds salvas do campeão (sementes) e conjuntos sorteados.
- **MAP-Elites:** o melhor de cada nicho. Eixos configuráveis (`axes`): arquétipo (Rodada 7), dano (físico, mágico,
  misto, sem dano, pelo ouro dos status), durabilidade (a parte defensiva da nota: baixa, média, alta) e passivas.
- **Modelo substituto:** ridge sobre "item presente" mais os pares dos itens dos 5 melhores, treinado a cada geração com
  tudo o que já teve bateria completa; ordena os filhos, só a metade melhor (e 2 sorteados) vai para as lutas rápidas.
- **Sinergias:** para os pares de itens dos 2 melhores, v(base + A + B) − v(base + A) − v(base + B) + v(base), com a base
  sendo o finalista sem os dois (inclusão e exclusão).

**Orçamentos:** rápido = população 8, 3 gerações de 16 filhos, 4 baterias por geração, 2 confirmações, 6 pares de
sinergia; completo = 16, 10 × 32, 8, 4 e 20.

### Medido 🎯 (cópia do banco, Big Carry v1, rápido)

`-Ddiscovery.report=data/ornn-planner.db` (`DiscoveryReportTest`): **95 s** (a primeira estimativa dizia 564 s; ela
aprende o tempo por bateria a cada execução), 36 leituras rápidas, 20 baterias (≈ 4 s cada), 3 confirmações.

| # | nota (média, P20, confirmada) | itens + bota | nicho |
|---|---|---|---|
| 1 | 73,9 (75,1 · 72,1 · 73,8) | Coração de Aço, Rookern Lamúrico, Égide de Fogo Solar, Desespero Eterno, Bastão das Eras + Mobilização Blindada | tanque · mágico · durab. alta |
| 2 | 73,5 (74,9 · 71,5 · 73,0) | … Canhão Fumegante no lugar do Bastão | tanque · físico · durab. alta |
| 3 | 72,8 (72,5 · 73,2 · 72,1) | Manto da Meia-noite, Força da Natureza, Coração de Aço, Égide, Duplaguarda + Mobilização | tanque · sem dano · durab. alta |

- **As sinergias medidas são negativas** entre itens de defesa: Rookern + Desespero Eterno −6,4, Coração de Aço +
  Rookern −5,1. É o retorno decrescente da durabilidade (a nota satura), não um erro: a 2ª defesa vale menos que a 1ª.
- **A confirmação segurou:** 73,8 contra 73,9 nas largadas que a busca não viu.
- **Acerto do modelo substituto: r = 0,26** no rápido (só 8 previsões antes da bateria). Pouco dado; o completo é que dá a
  medida (Relatório final).
- **Item de dano no 5º espaço do tanque** (Bastão das Eras, Canhão Fumegante): a ofensiva pesa 10% para o Tanque e o
  arquétipo continua "tanque". É a nota fazendo o que o YAML manda; se não for o desejado, o peso é do usuário.

### Testes

`FunnelTest` (7, mundo sintético: 30 itens, o 7 claramente melhor, sinergia plantada entre 11 e 12, 3 e 4 exclusivos):
a busca acha o item e a sinergia (e a sinergia mede exatamente +45); as regras sempre valem, mesmo com uma semente
proibida; o MAP-Elites guarda o melhor de cada nicho; o modelo substituto não piora a busca no mesmo orçamento (6
sementes) e acerta r > 0,5; a lista curta mantém a cota e os coringas; determinismo; o ridge aprende um mundo aditivo.

Testes: 351 → 359 (16 opcionais pulados, com o `DiscoveryReportTest`).

## Partes 4, 4b e 5 — Runas, ordem, o começo, versatilidade e novidade ✅

Num commit: as três mexem no mesmo pós-busca da Descoberta (`DiscoveryService.afterSearch` e `novelty`).

### Parte 4 — Runas e ordem de compra

- **Runas coordenada a coordenada** (`arena/discovery/RuneSearch`): a fundamental (13), a árvore principal (com as linhas
  voltando à primeira de cada), cada linha da principal e a runa secundária (de outra árvore, qualquer linha), cada uma
  com as outras fixas, repetido até nada melhorar (no máximo 2 passadas). A estrutura vem do catálogo de runas; nenhuma
  runa é citada no código. Cada página é medida pela nota por classe aos 15:00 em todos os cenários (uma largada),
  misturada com a versatilidade (Parte 4b).
- **Os feitiços** ficam os da build do usuário (não entram na busca: ficou de fora).
- **Ordem de compra:** a calculadora da Parte 0 sobre os itens do conjunto (obrigatórios) e só a bota dele (e a tier 2
  da receita), com a importância cedo e "qual componente primeiro". A calculadora aceita agora uma build não salva
  (`Request.build`) e uma lista de botas (`Request.boots`).
- **Iteração itens → runas → ordem → itens:** se a página do melhor finalista mudou, a busca de itens roda de novo com a
  página nova (um terço das gerações, sementes = os finalistas). Se o melhor conjunto muda, ele entra no topo com origem
  "com as runas novas".
- **Na calculadora:** "Buscar as runas também" roda a mesma busca sobre a melhor ordem (nota nos dois últimos minutos
  pedidos) e mostra a página e o que mudou.

**Medido (cópia do banco, Big Carry v1, rápido, com runas e ordem): 195 s.** A busca de runas mediu **101 páginas** e
subiu a nota do 1º finalista de **65,7 para 72,5** (aos 15:00): Suserano do Gelo → Ímpeto Gradual na fundamental,
Fonte da Vida/Revitalizar na Determinação, Lenda: Aceleração na secundária, entre outras. **Com as runas novas o melhor
conjunto mudou:** Manto da Meia-noite, Coração de Aço, Rookern, Desespero Eterno, Duplaguarda (76,3, confirmada 75,2).
A ordem gastou 55 medidas.

**Cuidado na leitura:** depois da iteração, o novo 1º foi medido com as runas novas e os outros com as da build. Estão
na mesma lista, mas não na mesma base; a tela marca a origem.

### Parte 4b — O começo vale mais, e versatilidade

- **Pesos por minuto decrescentes** (10, 15, 20, fim: 1,0 / 0,9 / 0,8 / 0,7) já vinham da Parte 1.
- **Desconto por "não chegar lá"** (`chegar` no YAML): cada minuto multiplica a chance de chegar aos seguintes por uma
  logística da nota dele (limiar 10, escala 10, piso 0,3). Quem perde as medidas iniciais vale menos no fim.
- **Poda pelo começo:** antes de buscar, a calculadora mede o 1º e os 2 primeiros passos de **todas as builds salvas**
  do campeão; um prefixo de 1 ou 2 passos mais de 5% abaixo do começo mais fraco delas é cortado antes de medir os
  itens seguintes ("ramos descartados pelo começo", na calculadora e na Descoberta). **Os começos fortes viram
  sementes:** na Descoberta, as builds salvas e meta entram inteiras na população inicial.
- **Versatilidade:** de uma escolha, sobre as variações (os cenários): metade o pior caso + metade o percentil 20.
  **Runas: 40% a média + 60% a versatilidade; itens: 80% + 20%** (`versatilidade` no YAML), porque a loja reage e o
  draft não. A tabela da Descoberta mostra a coluna "Versatilidade".

**Variações do adversário: o que ficou de fora.** O prompt pede variações por builds alternativas do adversário (pelo
arquétipo e pela Descoberta dele) e pelas runas dele. Aqui as variações são os cenários da nota (os testes defensivos,
os alvos e, se ligados, os duelos). Os bonecos não têm runas, então "as fundamentais plausíveis do adversário" não
existem como variação. Fazer isso pede duelos contra builds descobertas do adversário, ou seja, campanhas antes
(Parte 8). Está em "Para o usuário".

### Parte 5 — "Ninguém notou": novidade em relação ao meta

- **Builds meta cadastradas** (tabela `meta_build`, `/api/meta-builds`): itens, bota, fundamental e de onde veio, por
  campeão. Na Descoberta, o painel "Builds meta" lista, apaga e **lê um texto colado** ("item, item → bota / runa"),
  pelos nomes da loja sem acento nem caixa, e diz o que não achou.
- **Conhecidas** = as builds salvas do campeão + as meta. Todas passam pela mesma bateria.
- **Novidade** = 1 − Jaccard (itens e bota) até a conhecida mais próxima, + 0,15 quando a fundamental difere.
- **Descobertas:** nota ≥ a melhor conhecida, novidade ≥ 0,4 e **mantida na confirmação** (largadas que a busca não viu,
  com tolerância de 1 ponto contra a conhecida confirmada do mesmo jeito). Cada uma explicada: em que cenários ganha e
  perde (média nos minutos), o que troca, a fundamental, e a sinergia que sustenta (a maior positiva entre os itens
  dela). Campeões de ficha C, D ou sem ficha: as descobertas viram nota, não destaque.

**Na tela (servidores da rodada, banco `data/dev-r9`, Big Carry v1, rápido sem runas nem ordem): 303 s**, com 21
conhecidas medidas (20 salvas + 1 meta colada na tela). Três descobertas, por exemplo:

> troca Armadura de Espinhos + Égide de Fogo Solar por Convergência de Zeke + Bastão das Eras: 72,9 contra 72,6 de
> Soldado v0; ganha em Cajado do Vazio +2,0, Tormento de Liandry +1,8, 1000 de PdH + Cajado do Vazio +1,6; perde em
> Crítico −0,6

**As margens são pequenas** (72,9 contra 72,6): "nota igual ou maior" deixa passar descobertas por 0,3 ponto. Elas se
mantiveram na confirmação, mas um limiar de margem pode ser desejável (está em "Para o usuário"). A estimativa não
contava as conhecidas (21 baterias a mais); agora conta.

### Testes

`RuneSearchTest` (3): a busca coordenada acha o melhor de uma medida separável medindo menos páginas que todas; uma runa
versátil vence uma "tudo ou nada" na matriz das runas e perde na dos itens; um ramo com começo perdedor é podado.
`MetaNoveltyTest` (2): o texto colado vira build meta (itens, bota, fundamental, o que não achou) e só aparece para o
campeão dela; a novidade é o Jaccard com o passo da fundamental. **Achei e corrigi** na busca de runas: as páginas de
uma coordenada eram montadas antes da troca da fundamental, e a árvore nunca mudava.

**Banco:** migração **v25** cria `meta_build`, `build_discovery` e `build_guide` (a última também pelo `SchemaManager`).

Testes: 359 → 364 (16 opcionais pulados). `npm run build` limpo.

## Parte 6 — Campanhas e tela ✅

**Tela da Descoberta** (Simulações › Descoberta; feita junto da Parte 5): build (campeão, runas, feitiços, ouro), orçamento
rápido ou completo, arquétipo alvo ou "deixar livre", runas, ordem, sementes, eixos de nicho, semente; **estimativa antes
de começar** (agora contando as builds conhecidas); etapa, barra e "Parar". Resultado: a **build padrão robusta** (a
melhor média com P20 pelo menos a mediana dos cinco primeiros, e a % de cenários em que supera a melhor conhecida), as
melhores achadas (nota, média, P20, confirmada, versatilidade, novidade, supera a conhecida, nicho, origem), as
**descobertas**, a **curva de efetividade no tempo** (padrão, melhor conhecida e as três primeiras), os **nichos**, as
**sinergias** e as conhecidas. Ações em cada build: **Abrir como build**, **Salvar como build** (`/api/discovery/save`, na
pasta da build base, com a ordem e as runas), **Comparar com a minha** (Δ da build inteira) e **Na Efetividade** (abre o
rascunho em Relatórios › Efetividade).

**Campanhas** (Admin › Campanhas, `arena/discovery/CampaignService`, `/api/campaigns`): a mesma busca para uma lista de
campeões, em segundo plano, um de cada vez, numa fila em `build_discovery` por **patch, campeão e configuração** (hash).
Pausar, retomar (a fila ou uma linha), cancelar, prioridade e apagar. Um campeão já feito para o mesmo patch e
configuração não roda de novo. Um servidor parado no meio volta a fila ao subir (`rodando` → `pendente`, ao ficar pronto),
e o campeão refaz a etapa em que estava. **A Descoberta avulsa também é guardada** (sem campanha). **As análises
simples leem de lá:** a Descoberta mostra "Há um resultado salvo deste campeão neste patch" com "Mostrar", e a guia
sugerida (Parte 7) lê o mais recente.

**Confiança:** campeões com ficha C, D ou sem ficha recebem "baixa confiança", e as descobertas deles viram nota, não
destaque. Na campanha, podem ser pulados (padrão).

**Conferido na tela (servidores da rodada):** a campanha "Teste da tela" (Darius, rápido sem runas nem ordem) rodou as
builds salvas, entrou na descoberta, **pausou** pela tela (estado "pausado" na etapa "descoberta", 11 min gastos),
**retomou** e pausou de novo. **Achado: a confirmação dos nichos era cara** (no rápido, 9 confirmações de ~20 s cada para
o Darius, com 6 nichos): agora confirmam-se os finalistas e os melhores nichos, no mesmo número.

**Mais lento que o Ornn:** o Darius levou ~11 min até as sinergias no rápido, contra ~3 min do Ornn. As lutas do Darius
(zonas, sangramento, execução) custam mais por bateria.

## Parte 7 — Guias ✅

`arena/guide/Guide` (o modelo e a leitura, puros) e `GuideService` (`/api/guides`, tabela `build_guide`).

- **Guia por campeão:** nós de **condição** com o galho do sim e o do não, e folhas de **decisão** (item, componente,
  bota ou página de runas). Condições: inimigos com dano mágico, físico ou misto (N ou mais), da classe, com o arquétipo
  de build; o adversário de rota (classe ou campeão); estar à frente ou atrás em ouro; o minuto (a partir de, antes de);
  o item que o adversário fechou. O dano de um inimigo sai do ouro dos status dos itens da build dele.
- **Editor** em Build › Guias: lista recuada clicável, sem biblioteca; cada galho se abre com um clique, e "+ condição" ou
  "+ as runas da build" entram no galho aberto. **Modo Guia da loja:** a loja ao lado põe o item clicado no galho aberto
  (componente e bota reconhecidos pela seção e pela aba).
- **Ler numa partida:** contra builds inimigas escolhidas (e o adversário de rota), a guia vira uma ordem, decisão por
  decisão, cada uma no minuto em que a linha do tempo chega com as anteriores: "a partir dos 10 min" vira quando a build
  chega lá.
- **Avaliar:** cada caminho pelas condições vira uma build e recebe a nota robusta; a nota da guia é a média ponderada
  pela **frequência** de cada condição (campo do nó; vazio = metade). Conferido na tela: uma guia de 1 condição (sim:
  Máscara Abissal, 22,5; não: Armadura de Espinhos, 15,5) deu 19.
- **Sugestão automática:** pela árvore dos dois lados da calculadora ("se o adversário rushar X, a nossa melhor resposta
  a X; senão a linha maximin"), ou pela Descoberta mais recente guardada ("2+ inimigos com dano mágico alto → a build
  achada melhor nos cenários mágicos; 2+ físico → a melhor nos físicos; senão a padrão robusta"). Os cenários são
  mágicos ou físicos pelas funções dos atacantes do YAML, nunca pelo nome.
- **A macro da partida compra pela guia** quando a posição tem uma (`MatchModel.Slot.guideId`): a guia é lida **no draft**
  contra o outro lado (o adversário de rota pela função) e vira a ordem da build daquela posição, inclusive nas lutas.

**Fora:** a releitura **durante** a partida. O ouro e os itens do adversário não são conhecidos no draft, então essas
condições leem "não"; reler a guia a cada compra pede mudar o `World` da partida (a ordem é um índice de passos
calculado antes). A Efetividade e a calculadora avaliam uma guia pelo botão da tela de Guias (a nota robusta é a
mesma medida), não dentro das telas delas.

### Testes

`CampaignTest` (1, orçamento mínimo): a estimativa pula o campeão sem ficha; a fila roda o Ornn até "feito" com base
sintética; o mesmo campeão e configuração de novo não cria linha. `GuideTest` (3): a leitura pelas condições (dano,
minuto); os caminhos pesam pela frequência; uma guia lida numa partida vira ordem (inimigo mágico → Máscara primeiro; o
2º item lido antes dos 10 min) e a avaliação tem 4 caminhos.

Testes: 364 → 368 (16 opcionais pulados). `npm run build` limpo.
