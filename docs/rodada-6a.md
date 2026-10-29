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

## Para o usuário

- **Selos da loja no 7.3d:** um print da loja diz se os selos `alterado` do 7.3b sumiram e se os 5 itens do 7.3d
  mostram o selo.
- **Shyvana, Regeneração de Vida no nível 15:** 25 é uma dedução (a nota usa outra unidade). Um print do Treino no
  nível 15 confirma.
