# Rodada 6.a — andamento

Patch 7.3d, barra fixa, regra das botas e Efetividade por cenários de teste. Um commit por parte; cada parte termina
com `./mvnw test` e `npm run build` limpos.

**Branch:** o prompt pede o `main`; o `main` e o `arena-ia` apontavam para o mesmo commit (f9e8f37), então a rodada
segue no `main`.

**Testes antes da rodada:** 213 (8 opcionais pulados).

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

Testes: 213 → 213 (o `SavedBuildsReportTest` é opcional e fica entre os pulados: 9).

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

Testes: sem mudança (213). `npm run build` limpo.

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
Testes: 213 → 218 (9 opcionais pulados). Golden sem mudança.

**Builds do usuário que mudaram** (cópia do banco, 7.3d, `SavedBuildsReportTest` antes e depois):

| build | campeão | bota | antes | depois | efeito |
|---|---|---|---|---|---|
| Kalista off tank feedada, 2 e 3 | Kalista | Grevas Bélicas | 7:36 | 10:00 | o resto da build já vinha depois dos 10:00: sem mudança |
| Rumble Carry | Rumble | Sapatos Enfeitiçados | 8:34 | 10:00 | Tomo Amplificador 9:07 → 10:00 e Orbe do Oblívio 9:34 → 10:00 (o ouro guardado compra os dois no mesmo minuto); daí em diante igual |
| Rumble Redutor Off Tank | Rumble | Esmagadores Acorrentados | 9:30 | 10:00 | o resto igual |

As outras 25 builds não mudam (nenhuma bota aprimorada antes dos 10:00; as do Ornn compram as botas de tier 3 depois).

## Para o usuário

- **Selos da loja no 7.3d:** um print da loja diz se os selos `alterado` do 7.3b sumiram e se os 5 itens do 7.3d
  mostram o selo.
- **Shyvana, Regeneração de Vida no nível 15:** 25 é uma dedução (a nota usa outra unidade). Um print do Treino no
  nível 15 confirma.
