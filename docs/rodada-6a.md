# Rodada 6.a — andamento

Patch 7.3d, barra fixa, regra das botas e Efetividade por cenários de teste. Um commit por parte; cada parte termina
com `./mvnw test` e `npm run build` limpos.

**Branch:** o prompt pede o `main`; o `main` e o `arena-ia` apontavam para o mesmo commit (f9e8f37), então a rodada
segue no `main`.

**Testes antes da rodada:** 196 (8 opcionais pulados). As contagens "213" das Partes 1 a 3, anotadas durante a
rodada, somavam relatórios antigos que sobravam em `target/surefire-reports/`; na Parte 4 a pasta foi limpa e a conta
refeita (ver "Testes" no fechamento).

## Parte 1 — Base no patch 7.3d ✅

Pelo `/atualizar-patch 7.3b 7.3d`. O levantamento completo, linha a linha e com os links, está em
`docs/patches/7.3c-7.3d.md`.

- **7.3c:** tudo é do AAA ARAM. Os "Ajustes de Campeões" (Jarvan IV, Taliyah, Ryze, Kayn, Vladimir: multiplicadores
  de dano causado e sofrido) ficam dentro do bloco "Mudanças nos modos de jogo › AAA ARAM", e a introdução da nota diz
  que os ajustes de balanceamento são do ARAM. Fora do modelo.
- **7.3d:** 40 linhas aplicadas (8 de itens, 32 de 10 campeões), 0 já estavam, 0 divergentes, 19 fora do modelo
  (ARAM, a Ashe e os sistemas). Todos os "antes" bateram com a base.
- **Nomes:** "Barragem de Abertura" (não "Rajada") é o nome da loja e da nota pt-BR; o Stormrazor é a Chuva de
  Canivete (nota pt-BR e a passiva "Raio" com 120 / 45%).
- **Sistemas da 6.b** (Mão do Barão, torres, tropas) ficaram como "fora do modelo", com os números no levantamento.
- **Nenhum dos 6 campeões com ficha na Arena aparece nas notas.** Golden intacto (`ArenaRegressionTest` e
  `ArenaPolicyMigrationTest` passaram sem regravar).
- **Builds salvas:** nenhuma das 30 mudou (nenhuma usa um item ou um campeão das notas). Conferido com o teste novo
  `SavedBuildsReportTest` (opcional), que roda o planejador em todas as builds de uma **cópia** do banco num patch
  escolhido e escreve as compras e os status aos 10/15/20/25 min, para comparar duas rodadas com `diff`:

  ```bash
  cd backend && ./mvnw test -Dtest=SavedBuildsReportTest -Dplanner.builds.copy=data/ornn-planner.db -Dplanner.builds.patch=7_3d -Dplanner.builds.out=target/builds-7_3d.txt
  ```

- **Deduções:** selos `alterado` (os 8 herdados do 7.3b saíram; os 5 itens do 7.3d ganharam), o nv15 dos status por
  nível pela relação que a base já segue (Vida linear, Velocidade de Ataque pela curva do LoL com a Espontaneidade
  descontada) e a unidade da Regeneração de Vida por nível da Shyvana (a nota fala em "por 10 s").

Testes: 196 → 197 (o `SavedBuildsReportTest` é opcional e fica entre os pulados: 9).

## Parte 2 — Barra fixa: os status que saíram do padrão ✅

- `format.js`: `extraStats(point, mostrados)` devolve os status de um ponto da série que saíram do valor do campeão
  naquele nível sem itens nem runas (`point.base`), fora os já mostrados, na ordem de `TABLE_STATS` e depois na da
  ficha (`SHEET_STATS`), e então qualquer outro que o motor devolva. As quatro penetrações (`PENETRATION_STATS`: fixa e %,
  física e mágica; a fixa física é a letalidade do jogo) contam sempre que forem diferentes de 0. Status efetivos
  (escudos, curas) e derivados (reduções de dano) nunca são extras: já têm o lugar deles.
- **Barra fixa (`BuildDock.jsx`):** os 10 de `DOCK_STATS` continuam sempre visíveis; os extras vêm depois deles, como
  "+x" (ou "−x") sobre a base, com o total e a base no `title` ("Tenacidade: 60% (campeão sem itens nem runas neste
  nível: 0%)"). Os efetivos e a vida efetiva seguem no fim.
- **Leitura do momento (`StatSheet`):** já mostrava todos os de `SHEET_STATS`, mesmo zerados; agora acrescenta os que
  ficavam de fora quando saem da base (ex.: "% Regeneração de Vida", "% Regeneração de Mana").

Big Carry v1 aos 23:30 (banco de desenvolvimento): a barra ganhou RegV +51 (85; base 34), Ten +60% e RegV% +150%.

Testes: sem mudança (197). `npm run build` limpo.

## Parte 3 — Loja: botas aprimoradas só a partir dos 10:00 ✅

- **Regra:** `regras_loja.yml` ganhou `botas_aprimoradas: {a_partir_do_minuto: 10}`, com o comentário no padrão do
  arquivo ("informado pelo usuário em 28/10/2026").
- **Uma checagem só:** `engine/ShopRuleCheck` (puro, sem Spring) diz o primeiro minuto em que a loja vende um item
  (`earliestMinute`), se vende num minuto (`allowedAt`) e o texto da regra. Nenhum item é citado: "bota aprimorada" é
  bota (aba Botas) da seção `aprimorado`. O `ShopRules` monta a checagem a partir do YAML; o `PlannerService` a põe
  no `EngineInput.shopRules` de toda build calculada, e a Efetividade da Parte 4 usa o mesmo caminho. O `/api/meta`
  manda `shopRules.upgradedBootsMinute` para a tela (`shopModel.earliestMinute`, o espelho no cliente).
- **Planejador (`TimelineEngine`):** uma compra da build que a regra segura sai em
  `max(minuto em que o ouro cobre, compra anterior, momento, 10:00)`. O ouro fica guardado (o `goldLeft` mostra) e o
  passo ganha `waitedUntil` e o aviso "Esperou os 10:00 (regra da loja: botas aprimoradas só a partir dos 10:00).".
  Como as compras seguem a ordem, nada depois dela (nem os meios itens assumidos das próximas) passa na frente; os
  componentes da própria bota (botas de tier 2) saem quando houver ouro, como antes. Uma venda "quando der para comprar
  o próximo inteiro" cujo próximo é uma bota aprimorada também espera os 10:00.
- **Decisão:** o `snapshot` (inventário hipotético num nível e minuto, usado pela Arena e pela Efetividade para montar
  um lutador com itens dados) não aplica a regra: os itens vêm de uma linha do tempo que já a respeita, ou são uma
  hipótese explícita.
- **Loja do planejador:** as botas aprimoradas mostram um selo "10:00" no ícone, "Só a partir dos 10:00 (regra da
  loja)" no detalhe (com a explicação no `title`) e no `title` do ícone.

**Testes novos (`TimelineEngineTest`):** bota aprimorada com ouro aos 5:40 espera os 10:00 (o ouro fica na bolsa; o
item seguinte, com ouro aos 7:20, sai aos 10:00; com meios itens supostos, a bota de tier 2 sai aos 2:20 e nada da
compra seguinte vem antes dos 10:00); depois dos 10:00 a compra é normal; builds sem bota aprimorada antes dos 10:00
dão o mesmo JSON com e sem a regra; a venda cronometrada para uma bota aprimorada espera também.
Testes: 197 → 201 (9 opcionais pulados). Golden sem mudança.

**Builds do usuário que mudaram** (cópia do banco, 7.3d, `SavedBuildsReportTest` antes e depois):

| build | campeão | bota | antes | depois | efeito |
|---|---|---|---|---|---|
| Kalista off tank feedada, 2 e 3 | Kalista | Grevas Bélicas | 7:36 | 10:00 | o resto da build já vinha depois dos 10:00: sem mudança |
| Rumble Carry | Rumble | Sapatos Enfeitiçados | 8:34 | 10:00 | Tomo Amplificador 9:07 → 10:00 e Orbe do Oblívio 9:34 → 10:00 (o ouro guardado compra os dois no mesmo minuto); daí em diante igual |
| Rumble Redutor Off Tank | Rumble | Esmagadores Acorrentados | 9:30 | 10:00 | o resto igual |

As outras 25 builds não mudam (nenhuma bota aprimorada antes dos 10:00; as do Ornn compram as botas de tier 3 depois).

## Parte 4 — Efetividade por cenários de teste, com bonecos de pano ✅

### 4.1 Cenário de teste (backend)

- **Boneco de teste** = o boneco de pano da Arena (`ArenaModel.Dummy`) com campos novos, todos opcionais (os bonecos
  antigos lutam igual): `role` (função), `stats` (outros status por cima: PdH, crítico, penetrações, letalidade, roubo
  de vida...), `items` (itens da loja), `attackSpeed`, `attackRange`, `pulses` e `immortal`.
  - **Itens reais:** um boneco com itens vira uma build sintética da unidade "Boneco de pano" (nível 15, todos os itens
    comprados aos 30:00, depois da regra das botas) e passa pelo `FighterBuilder`, como um campeão: status, passivas,
    efeitos de `combate_7_3d.yml` e gatilhos. DdA e PdH entram como base (as multiplicações, como a do Capuz, leem
    eles); crítico, penetrações e roubo de vida entram por cima.
  - **Pulsos** (`ArenaModel.Pulse`): a cada N s, com o alvo no alcance, dano `base + dda × DdA + pdh × PdH` do tipo
    escolhido no inimigo (pelo mesmo `deal` das habilidades, com os efeitos de habilidade dos itens: queimadura do
    Liandry, redução de RM da Maldição, Lâmina), ou escudo / cura no aliado com menos Vida proporcional (ele incluído).
  - **Função** (`efetividade_7_3d.yml`, `funcoes`): alcance, posição (frente, meio, trás) e pulsos padrão. Um boneco
    sem pulsos próprios usa os da função.
- **Decisões que mudaram no caminho:**
  - **Atacantes não morrem** (`immortal`: ficam em 1 de Vida). Na primeira rodada a Big Carry matava o boneco de 8.000
    de Vida aos 16 s, com 17 de Vida perdida, e o tempo até morrer ficava sem sentido. A Vida do atacante continua
    valendo para o que lê a Vida do alvo (a varredura "Vida máx. do atacante" mostra isso). Os aliados morrem.
  - **A luta acaba quando a build morre** (`Arena.stopWhenDead(0)`): sem isso, depois da morte da build o atacante
    seguia batendo nos aliados e a "% do dano inimigo que a build absorveu" perdia o sentido.
  - **Lutas longas** (1×1 e varreduras: 180 s, o máximo da Arena; equipe: 120 s) e só depois a estimativa: com 60 s e
    a estimativa pela Vida perdida, quase tudo batia no teto (a cura empatava com o dano e a conta explodia). O teto da
    estimativa ficou em 5× a duração.
  - **Presets mais fortes** que o primeiro rascunho (são testes de estresse): Crítico com 2,5 ataques/s, magos com 600
    de PdH e habilidades a cada 5 s / 2 s; os magos têm DdA 0 (só Dano Mágico), senão a Armadura mexeria no "1000 de
    PdH".
- **Medidas** (`EffectTestService.measure`): tempo até morrer (ou estimado: duração × Vida máx. ÷ Vida perdida, no
  máximo 5×); vida efetiva = dano bruto recebido até morrer (`FighterStats.rawTaken`, novo: o dano amplificado antes
  das resistências e reduções; campo descritivo, fora da impressão digital do golden); mitigado % = (bruto − depois das
  resistências) ÷ bruto; escudo absorvido; cura e cura perdida; dano devolvido (o que a build causou aos atacantes);
  controle aplicado (`controle_causado`); com aliados, a % do dano bruto inimigo que foi na build e o tempo vivo médio
  dos aliados.
- **Índice** = tempo até morrer ÷ o da referência × 100. Referência: o mesmo campeão sem os itens que tem no minuto (as
  runas e o nível ficam), uma build no mesmo minuto, ou a média das builds de uma pasta.
- **Onde ficam:** presets em `seed/efetividade_7_3d.yml` (documentado no cabeçalho; itens pelo nome da loja, resolvidos
  no primeiro uso); cenários e configurações dos relatórios do usuário na tabela nova `effect_test` (migração v23 no
  `SchemaManager` e no `schema.sql`). Na cópia do banco: "schema 22 → 23; 32 builds, 178 policies", tudo preservado.
- **Presets** (11): Crítico, Anti-tanque físico (Espada do Rei Destruído + Ruptor Divino + Lembranças do Lorde
  Dominik), Tormento de Liandry, Liandry + Maldição Sanguinária, Cajado do Vazio, 1000 de PdH + Cajado do Vazio, Misto,
  Verdadeiro, Atirador alimentado (Gume do Infinito, Lembranças, Canhão Fumegante, Dança da Morte), 5 contra 1 e Luta
  de equipe genérica (aliados: suporte de proteção, atirador, mago e lutador de flanco). Nomes casados com a loja.
- **Variantes da build** (`EffectModel.Variant`): `sem` (o item fora, `excludeItems`), `so_status` e `ouro` (itens
  sintéticos com id negativo no lugar do item: `Slot.replaceItems` → `FighterBuilder.Combatant.replaceItems` →
  `PlannerService.snapshot(..., extra)`; a Forja e as passivas de % os leem como a um item), `candidato` (a compra
  trocada na build e a linha do tempo recalculada, com a regra das botas).
- **Custo:** as entradas da Arena da build e dos bonecos ficam em cache (reaproveitadas por cópia rasa), as medidas em
  cache LRU por (build, minuto, cenário, estágio, variante), e as lutas de um lote rodam em paralelo
  (`ArenaService.parallel`). A tela manda lotes de 6 lutas para mostrar o progresso e o "Parar".
- **API** (`/api/effect`): `meta`, `tests` (CRUD), `reports/{basico|completo}` (GET, PUT, DELETE = restaurar), `items`,
  `evaluate` (lote) e `scenario` (o cenário da Arena de um teste, para abrir a luta).

### 4.2–4.4 Telas

- **Aba "Efetividade"** (no topo, entre Arena e Ranking): lista de presets e dos cenários do usuário; novo, duplicar,
  editar, salvar e apagar (presets só duplicam); editor do boneco (status, itens em chips com ícone, função, padrão de
  dano da função ou pulsos próprios); "Testar agora" com uma build salva, minuto e referência, e "Abrir a luta na
  Arena" (a mesma luta, com quadros: a Big Carry contra o Atirador alimentado cai aos 19,5 s nos dois). Relatórios:
  Básico e Completo, com pesos, ordem, cenários a mais, os 5 estágios de cada varredura editáveis, "Salvar" e
  "Restaurar o padrão". Ajuda: como funciona, bonecos, medidas, índice, referência, relatórios, por item e custo.
- **Planejador, aba "Efetividade" do momento:** Básico (tabela, barra do índice por cenário, índice ponderado e "Pontos
  fracos: ...", os cenários abaixo de 70% da média) e Completo (mais um gráfico de linha por dimensão, com o ponto de
  quebra marcado: o estágio em que o índice cai mais de 25% em relação ao anterior). "Rodar por item" (tabela item ×
  cenário com as três leituras e as frases) e "Comparar itens para um espaço" (a compra, os candidatos e o cenário ou
  todos; o melhor de cada cenário destacado; ⚠ quando o candidato ainda não foi comprado no minuto).
- **Ranking:** modo "Efetividade" (Básico ou Completo sem as varreduras, com a referência): ordena pelo índice
  ponderado, com o mapa de calor cenário × build.

### Testes novos (`EffectTestsTest`)

- 🎯 boneco só com ataques, 0 de penetração: a vida efetiva medida na luta é a do `SeenBy` com folga de um ataque
  (6.660 medida, 6.600 do `SeenBy`, ataques de 180) e o mitigado é 120/220;
- mais Armadura aumenta o índice contra Crítico (2,5 / 4,1 / 6,5 s com 50 / 150 / 300 de Armadura: índice 164 → 260) e
  não muda contra 1000 de PdH + 40% de penetração mágica (10,0 s nos três);
- num item só de status, a variante "só status" é o mesmo item (mesmos status no motor): a passiva vale 0; num item com
  passiva, a linha da passiva sai;
- pulsos: Dano Verdadeiro ignora resistências; o atacante não morre e a luta segue; o escudo do suporte absorve e a
  build vive mais, com 100% do dano inimigo nela;
- determinístico: 12 lutas iguais em sequência e em 6 threads.

Também: `SchemaMigrationCopyTest` (opcional) tinha asserções antigas (0 combos e 0 bonecos no banco real); agora
compara antes e depois e confere a tabela nova. `EffectReportTest` (opcional) roda os relatórios numa cópia do banco.
Golden sem mudança (os campos novos da Arena são opcionais; `rawTaken` é descritivo). Testes: 201 → 207 (10 opcionais
pulados).

### Resultados (cópia do banco, 7.3d, minuto padrão de cada build)

**Big Carry v1 (Ornn, 23:30, nível 15)** — Básico em 5–6 s, índice ponderado 5.950:

| cenário | índice | tempo até morrer | referência | vida efetiva | mitigado | escudo | cura | devolvido | controle |
|---|---|---|---|---|---|---|---|---|---|
| Crítico | 1.989 | 123,7 s | 6,2 s | 150.600 | 82% | 710 | 18.445 | 59.599 | 24,0 s |
| Anti-tanque físico | 360 | 66,9 s | 18,6 s | 78.164 | 75% | 355 | 11.621 | 38.799 | 14,5 s |
| Tormento de Liandry | 9.595 | 900 s* | 9,4 s | 601.962 | 74% | 11.485 | 19.626 | 68.322 | 34,1 s |
| Liandry + Maldição | 8.735 | 557,3 s* | 6,4 s | 386.167 | 67% | 13.122 | 25.891 | 69.076 | 34,1 s |
| Cajado do Vazio | 8.671 | 900 s* | 10,4 s | 327.923 | 64% | 8.860 | 15.069 | 67.675 | 34,1 s |
| 1000 de PdH + Cajado | 16.729 | 900 s* | 5,4 s | 454.123 | 64% | 9.807 | 22.990 | 67.675 | 34,1 s |
| Misto | 5.792 | 900 s* | 15,5 s | 416.000 | 81% | 1.575 | 14.136 | 80.680 | 36,0 s |
| Verdadeiro | 956 | 243,1 s* | 25,4 s | 83.336 | 47% | 1.064 | 26.123 | 78.305 | 36,0 s |
| Atirador alimentado | 722 | 20,1 s | 2,8 s | 40.980 | 75% | 220 | 2.658 | 10.919 | 2,5 s |

\* sobreviveu aos 180 s: estimado. Contra um mago sozinho a Big Carry não morre (a cura empata com o dano). Pontos
fracos: Atirador alimentado, Anti-tanque físico e Verdadeiro. Completo (+52 lutas, 6,6 s): 5 contra 1 não morre em
120 s; Luta de equipe 10.989, absorveu 86% do dano inimigo; quebras: penetração % física aos 50%, Armadura/RM do
atacante aos 500, DdA aos 750.

**Soldado v8 (Ornn)** — Básico 2,0 s, índice ponderado 1.180: Crítico 877 (58,1 s), Anti-tanque 238 (44,2 s), Liandry
844, Liandry + Maldição 315, Cajado 1.292, 1000 de PdH 883, Misto 5.725 (estimado), Verdadeiro 304, Atirador alimentado
141 (29,2 s contra 20,8 s da referência). Completo +4,1 s; quebras em penetração % física 50%, PdH 1500, inimigos 4.

**Manopla dos Glacinatas (Ornn)** — Básico 2,2 s, índice ponderado 534: Crítico 2.479 (174 s, 91% mitigado, 32 s de
controle), Anti-tanque 249, Liandry 188, Liandry + Maldição 326, Cajado 213, 1000 de PdH 380, Misto 705, Verdadeiro 114,
Atirador alimentado 153. Ponto fraco claro: dano mágico (a build é de Armadura); 5 contra 1 1.061 (32 s); luta de
equipe 88 (morre em 25,7 s, antes dos aliados).

**Por item (Big Carry, Básico, 657 lutas, 56 s)** — trechos (o `EffectReportTest` escreve a tabela inteira):

| item | cenário | com × sem | passiva (com × só status) | mesmo ouro em status |
|---|---|---|---|---|
| Coração de Aço | Crítico | +404% | +165% | +60% (100% Armadura) |
| Desespero Eterno | Liandry + Maldição | +1.097% | +566% | +229% (100% RM) |
| Duplaguarda de Amaranto | Atirador alimentado | +45% | +14% | +41% (100% Armadura) |
| Armadura de Espinhos | Crítico | +156% | +0% | +258% (100% Armadura) |
| Esmagadores Acorrentados | 1000 de PdH + Cajado | +495% | +223% | +431% (100% RM) |

Frases como as da tela: "a passiva de Desespero Eterno vale +566% contra Liandry + Maldição Sanguinária; o mesmo ouro em
RM daria +229%"; "a passiva de Duplaguarda de Amaranto vale +14% contra Atirador alimentado; o mesmo ouro em Armadura
daria +41%". A Armadura de Espinhos tem passiva 0 em todos: o reflexo e as Feridas não seguram o Ornn contra bonecos
que não se curam (o dano devolvido conta como medida, não no tempo até morrer).

**Comparação de candidatos** (no lugar da Armadura de Espinhos, compra #7 da Big Carry, contra Atirador alimentado,
2,3 s): Presságio de Randuin **898** (25,0 s), Armadura de Espinhos 722 (20,1 s), Coração Congelado 680 (18,9 s), Manto
da Meia-noite 606 (16,9 s).

**Tempos:** Básico 2–6 s por build (a primeira inclui montar a build e a referência); Completo +4–7 s (52 lutas
novas); por item ~56 s (72 variantes da build montadas: quase todo o tempo é preparação, que lê o catálogo do banco a
cada consulta ao planejador); comparação de 4 candidatos num cenário, 2,3 s.

## Para o usuário

- **Selos da loja no 7.3d:** um print da loja diz se os selos `alterado` do 7.3b sumiram e se os 5 itens do 7.3d
  mostram o selo.
- **Shyvana, Regeneração de Vida no nível 15:** 25 é uma dedução (a nota usa outra unidade). Um print do Treino no
  nível 15 confirma.
- **Calibragem dos presets:** os números dos bonecos são meus (o prompt fixava os itens e "100% de crítico e 300 de
  DdA"). Contra builds de tanque de fim de jogo, os magos sozinhos não matam (a Big Carry não morre em 180 s contra
  Liandry, Cajado e 1000 de PdH, e o índice fica no teto da estimativa). Para diferenciar tanques nesses cenários, os
  magos precisam de mais dano; os presets se duplicam e editam na aba Efetividade.
- **Leituras por item não monótonas:** algumas trocas pioram o tempo até morrer (Big Carry contra Atirador alimentado:
  sem o Coração de Aço ela dura 15% mais). A luta é determinística, mas o cérebro Tático joga diferente com outro
  inventário (controle e tempo das habilidades); vale abrir a luta na Arena antes de concluir.
- **A leitura por item demora ~1 min.** Dá para cortar com um cache do catálogo no planejador, se incomodar.
- **"Dano devolvido"** é tudo o que a build causou aos atacantes. Se a ideia era só o reflexo (Armadura de Espinhos,
  Colete Espinhoso), dá para separar pela fonte.

## Fechamento

- **Testes:** 196 antes da rodada (8 opcionais pulados) → 207 depois (10 opcionais pulados), contados com a pasta
  `target/surefire-reports/` limpa. `npm run build` limpo em todas as partes.
- **Golden:** sem mudança em toda a rodada (nenhum dos 6 kits nas notas; campos novos opcionais ou descritivos).
- **Migração:** v23 (`effect_test`).

## Ajustes depois da rodada (31/10/2026)

- **Penetração % no máximo 75%:** os estágios padrão das três varreduras de penetração % passam a 0, 10, 30, 50 e 75. O
  0 fica como base da curva e mantém os 5 estágios. Um estágio salvo ou digitado acima de 75 roda como 75, e um relatório
  salvo com os estágios antigos (0, 25, 50, 75, 95) segue o padrão novo. Os resultados da Parte 4 foram medidos com os
  estágios antigos. O limite vale para as varreduras; a penetração na luta (itens, status de boneco) não tem teto.
- **Comparativo no planejador:** com builds em "comparar", "Rodar" da aba Efetividade roda o relatório da build aberta e
  o de cada comparada (como no gráfico: salvas, ou com os condicionais da aberta): tabela lado a lado por cenário, com o
  melhor marcado e o índice ponderado, e, no Completo, uma linha por build em cada varredura, com o ponto de quebra de
  cada uma. A leitura por item e os candidatos seguem só na build aberta.
- **No comparativo, todas as builds usam a referência da build aberta.** Com a referência de cada uma, quem vivia mais
  podia ter índice menor: contra o Atirador alimentado, Big Carry v1 (20,1 s) dava 722 e Manto do Manto do Desespero de
  Amaranto (30,4 s) dava 146, porque as referências eram 2,8 s e 20,8 s.

**Em aberto:** a referência do Ornn sem itens contra o Atirador alimentado é 2,8 s na Big Carry e 20,8 s nas outras
builds conferidas, e isso infla o índice 722 da Parte 4. A causa ainda não foi investigada.

## Complemento: comparador rápido ✅

- **Abrir:** "⇄" em cada compra da sequência e a caixa ⇄ nos itens aprimorados da loja (até 4). Sem espaço escolhido, os
  candidatos entram como próximo item: depois do minuto selecionado no gráfico, ou da última compra. Os candidatos
  ficam em `QuickCompare.jsx`, abaixo da sequência, e a resposta vem sozinha a cada mudança.
- **Função única:** `PlannerService.withItem(build, espaço, item, minuto)` troca o item do espaço (ou insere uma compra).
  A comparação de candidatos da Efetividade passou a usá-la (antes tinha a própria troca); só a medida muda.
- **Backend:** `arena/QuickCompareService` e `api/QuickCompareController` (`/api/quick/compare` e `/suggest`). Sem luta:
  linha do tempo, contribuição do item no ponto (a mesma da relevância), `SeenBy.Base` da corrida de 0 s (cache por build
  e minuto) e valores das passivas (a mesma função de `/api/lab/effect-values`).
- **Decisões:**
  - Todos os cartões comparam no **mesmo minuto** (o escolhido, ou 5 min depois de o último candidato ficar pronto). Com o
    minuto de cada um, um item caro seria medido mais tarde, com mais nível e ouro, e levaria vantagem. "Soma ao ficar
    pronto" continua no minuto de cada um.
  - O atraso compara com a build como está, casando as compras pela posição, e mostra o item seguinte e a última compra.
  - **Sugerir** usa a ficha do planejador, sem a corrida de 0 s: a ficha conta os condicionais como a build os deixa, e a
    escala sai bem mais alta que a dos cartões (28 mil contra 13 mil). Por isso a sugestão mostra o ganho % sobre a build
    como está, e clicar leva o item aos cartões, que dão o número final.
- **Desempenho:** a primeira medição deu 5,4 s (4 candidatos). A causa eram duas leituras repetidas do banco a cada
  consulta ao planejador: o catálogo inteiro, e todas as ~140 unidades em `findUnit` para pegar uma. Agora o catálogo fica
  em cache (invalidado por qualquer escrita, contador nos repositórios) e `findUnit` lê só a unidade. Isso acelera também
  a Arena e a Efetividade.

| medida (Big Carry v1, 4 candidatos) | antes dos caches | depois |
|---|---|---|
| primeira consulta | 5.388 ms | 789 ms |
| 🎯 a mesma consulta de novo | 367 ms | 152 ms |
| um minuto novo | 4.595 ms | 578 ms |
| Sugerir (aba inteira) | 3.174 ms | 1.340 ms |

**Exemplo:** 3º item da Big Carry v1 (Colete Espinhoso, 1.000, pronto aos 6:26) contra 3 candidatos, aos 14:00, sem
efeitos, 50% físico:

| candidato | custo | pronto | próximo item | última compra | mista | física | mágica | eficiência |
|---|---|---|---|---|---|---|---|---|
| Colete Espinhoso (referência) | 1.000 | 6:26 | = | = | 12.590 | 15.010 | 10.842 | 75% |
| Presságio de Randuin | 2.800 | 9:00 | +2,6 min | +4,0 min | 13.287 | 17.026 | 10.895 | 115% |
| Manto da Meia-noite | 2.550 | 8:39 | +2,2 min | +3,6 min | 12.271 | 13.214 | 11.454 | 399% |
| Coração Congelado | 2.550 | 8:39 | +2,2 min | +3,6 min | 12.002 | 15.543 | 9.775 | 132% |

Veredito na tela: "Presságio de Randuin rende +6% de vida efetiva mista aos 14:00 e fica pronto 2,6 min depois; Manto da
Meia-noite rende −3%; Coração Congelado rende −5%." Os números negativos vêm do atraso: aos 14:00 os itens seguintes
ainda não chegaram. Sugerir traz Placa Gargolítica +64%, Manto da Aurora +41%, Rookern Lamúrico +32%, Duplaguarda de
Amaranto +25% e Força da Natureza +25% (triagem pela ficha, aos 11:26).

**Testes novos (`QuickCompareTest`, com o catálogo do seed num banco novo):** o item atual é a referência, com Δ 0; um
candidato mais caro atrasa os seguintes exatamente pela conta do ouro (Δ custo ÷ ouro/min); a bota aprimorada antes dos
10:00 espera; a sugestão respeita os grupos exclusivos.

**Índice Básico do exemplo:** Colete Espinhoso 347, Manto da Meia-noite 430, Coração Congelado 423, Presságio de Randuin
419: na luta o Manto passa na frente, ao contrário da vida efetiva mista. Numa build cheia, o próximo item aparece como
"Não cabe aqui" (regra dos espaços); escolher um minuto no gráfico o põe no meio da sequência.

Testes: 207 → 211 (10 opcionais pulados). Golden sem mudança. `npm run build` limpo.
