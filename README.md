# Ornn Build Timing Planner

Ferramenta pessoal, 100% local, para planejar e comparar a **ordem de compra de itens do Ornn** (tank/suporte) no
League of Legends: Wild Rift. Ela **não interage com o jogo** (sem ADB, leitura de tela ou automação): é uma
calculadora que mostra **em que minuto e nível** cada compra acontece e quais os **status efetivos** naquele ponto,
calculando passivas percentuais **de forma dinâmica**, no contexto real da build.

## Como rodar

Pré-requisitos: **Java 11+** e **Node 18+** (o frontend usa Vite 5; com nvm: `nvm use 24`). Não precisa de Maven
instalado — o projeto usa o Maven Wrapper.

Backend (API REST em http://localhost:8080; cria `backend/data/ornn-planner.db` e popula o banco na primeira execução):

```bash
cd backend && ./mvnw spring-boot:run
```

Frontend em modo de desenvolvimento (http://localhost:5173, faz proxy de `/api` para o backend):

```bash
cd frontend && npm install && npm run dev
```

Opcional — servir tudo pelo backend: `npm run build` no `frontend/` gera o SPA em
`backend/src/main/resources/static/`; depois disso basta o `./mvnw spring-boot:run` e abrir http://localhost:8080.

Testes do motor de cálculo e da consistência do catálogo:

```bash
cd backend && ./mvnw test
```

## Stack e decisões

| Camada | Escolha | Por quê |
|---|---|---|
| Backend | Java 11 + **Spring Boot 2.7** (web + jdbc) | A máquina tem JDK 11; Spring Boot 3 exige Java 17. Migrar para 3.x é trocar a versão do parent quando houver JDK 17+. |
| Persistência | **SQLite** (`org.xerial:sqlite-jdbc`) + **`JdbcTemplate` puro**, sem JPA/Hibernate | Hibernate não tem dialect oficial de SQLite (só o community dialect). O modelo é pequeno e as consultas são simples, então SQL direto evita toda essa aresta. `SchemaManager` aplica migrações (versão em `PRAGMA user_version`) e depois o `schema.sql` idempotente; pool Hikari com 1 conexão (SQLite tem um único escritor; uso monousuário). |
| Frontend | React 18 + Vite 5 + Recharts | SPA simples, sem roteador. Gráfico com paleta categórica validada para daltonismo, cor fixa por build. |

## Estrutura

```
backend/
  src/main/java/com/ornnplanner/
    engine/      motor de cálculo puro (sem Spring): TimelineEngine, GoldPricing, Model, Stats
    seed/        SchemaManager (migrações), CatalogImporter (YAML -> SQLite), ReferenceSeeder (Ornn, Forja Viva, XP)
    repo/        acesso a dados com JdbcTemplate
    service/     PlannerService (monta dados de referência e roda o cálculo)
    api/         PlannerController (/api/...), AdminController (/api/admin/...), ItemImageController (ícones)
  src/main/resources/
    schema.sql, application.properties
    seed/        loja_7_3.yml (catálogo da loja), precos_status.yml, icones/ (1 PNG por item + manifest.json)
  src/test/...   testes do motor, do catálogo e da réplica da eficiência estática
    resources/referencia/   YAMLs do changchiyou usados só para testar a metodologia + LICENSE
frontend/src/
  pages/         Planner (pastas, editor, loja, linha do tempo, comparação), Admin, About
  components/    Shop (loja), BuildBar, FolderTree, TimelineTable, StatChart, ItemIcon
tools/           capturas_receitas.py, capturas_icones.py (extração a partir dos prints da loja)
capturas/        prints da loja (fora do git, só o LEIA-ME é versionado)
```

## Catálogo de itens (transcrito da loja do jogo)

A fonte dos itens é a própria loja do Wild Rift, em português: [`seed/loja_7_3.yml`](backend/src/main/resources/seed/loja_7_3.yml)
traz os **171 itens** do patch 7.3 (todas as abas), com custo, abas e seção da loja, se é ativável, o selo do patch
(novo / reformulado / alterado), o resumo, os status, o texto das passivas, a receita, e a forma evoluída dos itens que
se transformam sozinhos (Fimbulwinter, Muramana, Abraço de Seraph, Diadema de Canções, Foice da Névoa Negra, Bastião
da Montanha). O cabeçalho do arquivo documenta cada campo.

Como ele foi montado (e como repetir num patch novo):

1. **Prints** da loja em `capturas/<patch>/` (instruções em [`capturas/LEIA-ME.md`](capturas/LEIA-ME.md)): uma lista
   “Resumos” por aba e, por item, a descrição + a árvore de construção.
2. **Transcrição** dos textos pela IA (nome, custo, status, passivas, receita lida da árvore).
3. **Conferência automática das receitas** com `tools/capturas_receitas.py`: ele reconhece os ícones de cada árvore
   e da lista “Fabrica” por comparação de imagem. No 7.3, as receitas transcritas e as listas “Fabrica” de todos os
   itens bateram entre si.
4. **Ícones** recortados dos mesmos prints com `tools/capturas_icones.py` → `seed/icones/`.
5. O teste `LojaCatalogTest` confere que todo componente existe, que os componentes custam menos que o item e que
   todo status tem preço definido.

Os scripts precisam de `opencv-python-headless`, `numpy` e `pyyaml` (de preferência num ambiente virtual):

```bash
python tools/capturas_receitas.py capturas/7.3
```
```bash
python tools/capturas_icones.py loja_7_3.yml capturas/7.3
```

### Passivas modeladas

O texto das passivas é guardado como na loja. Só as que **mudam status** ganham uma modelagem numérica (campo
`efeitos` do YAML), que é o que o cálculo usa — 17 itens no 7.3, por exemplo:

| Item | Efeito modelado |
|---|---|
| Duplaguarda de Amaranto | +30% da Armadura e da RM **adicionais** (condicional: 5 acúmulos em combate) |
| Manto da Aurora | +20% da Armadura e da RM **totais** (condicional) |
| Manto da Meia-noite | +200–300 de Vida conforme o nível (condicional: vida baixa) |
| Armadura Sangrenta do Suserano | Dano de Ataque = 2,5% da Vida **adicional** |
| Sinal de Sterak | +50% do Dano de Ataque **base** |
| Aproximação Invernal / Fimbulwinter | Vida = 15% do Mana máximo |

Escopos: `total` (base + bônus), `bonus` (o que a loja chama de “adicional”) e `base`. Efeitos **condicionais**
(acúmulos, combate, vida baixa, carga de mana) são ligados ou desligados **em cada compra** da build (botão “cond.” na
sequência de compras), e itens que evoluem podem ser contados já evoluídos (botão “evoluído”). Status adaptativos
(Grevas Vorazes, Passos Imortais, Foice Espectral) foram modelados como Dano de Ataque, que é o que o Ornn recebe.

### Importação

`CatalogImporter` lê o catálogo e [`seed/precos_status.yml`](backend/src/main/resources/seed/precos_status.yml)
(itens-base do preço por ponto de cada status, com o nome do status na loja). Roda quando o banco está vazio e pela tela
**Admin → Reimportar catálogo**. A reimportação faz *upsert* pelo nome (ids das builds salvas não mudam) e **preserva
itens corrigidos à mão** no Admin, a menos que se marque “sobrescrever”. Para um patch novo: gere o `loja_<patch>.yml`
e ajuste `planner.seed.catalog` em `application.properties`.

> A migração para este catálogo (schema v2) substituiu os itens da base antiga; sequências de compra de builds salvas
> antes dela são esvaziadas (pastas e builds continuam).

## Regras de cálculo

Implementadas e documentadas em [`TimelineEngine`](backend/src/main/java/com/ornnplanner/engine/TimelineEngine.java).

- **Tempo**: ouro no minuto *t* = 500 + ouro/min × *t*. A compra *i* acontece quando o ouro acumulado cobre o total
  pago até ela. **Nível**: XP = XP/min × *t*, comparado com a tabela de XP acumulado (máximo nível 15).
- **Supor meios itens / itens menores comprados instantaneamente** (opções da build): antes de cada item da
  sequência, o motor compra sozinho os componentes que faltam para ele (meios itens e/ou itens básicos, na ordem da
  receita), cada um assim que o ouro alcança o seu preço. O ouro total não muda; os status dos componentes entram
  antes. Na linha do tempo elas aparecem como "suposto" (↳) logo abaixo do item a que levam, e podem ser recolhidas
  por item (▾) ou todas de uma vez; não entram na sequência. Uma compra suposta que quebraria uma regra da loja
  (ex.: sem espaço no inventário) não é feita.
- **Momento de compra** (marcador fino na sequência, "+ momento de compra"): volta à base num minuto exato ou um tempo
  depois da compra anterior. Os itens depois do marcador só são comprados a partir desse minuto (ou mais tarde, se o
  ouro ainda não der); os componentes supostos continuam instantâneos. Sem marcador, cada item sai assim que o ouro
  dá. A sequência, a linha do tempo (coluna **Mochila**) e o painel da compra mostram o ouro que sobrou na mochila
  depois de cada compra; o marcador mostra o ouro na mochila ao voltar à base.
- **Status do Ornn** no nível L: `base + crescimento × (L − 1)`.
- **Por compra**, sobre o inventário inteiro naquele momento:
  1. soma dos status planos de todos os itens;
  2. status base da unidade no nível do minuto da compra;
  3. **conversões** (um status vira outro: Mana → Vida, Vida adicional → DdA…) leem o valor **final** do status de
     origem; por isso os status são resolvidos em ordem de dependência (Mana antes de Vida, Vida antes de DdA/PdH);
  4. multiplicadores de itens e **Forja Viva**, regra ajustada a 8 dicas de status medidas no jogo (Ornn nível 15
     com 6 itens de tanque, todas batendo dentro de ~1 ponto):
     - a **Forja** multiplica planos + conversões + o ganho dos percentuais **de adicional**;
     - **% do adicional** (ex.: Duplaguarda) é contínuo: lê todo o adicional, inclusive a Forja e os bônus de % do
       total, e a Forja amplifica o ganho;
     - **% do total** (ex.: Manto da Aurora, Rabadon) lê o total já com a Forja e o ganho dos percentuais de
       adicional (sem contar o bônus de % do total que eles recebem depois) e **não** é amplificado pela Forja.
       No jogo o resultado é o mesmo ativando o Manto antes ou depois de a Duplaguarda acumular (testado em
       06/10/2026): o jogo recalcula numa ordem fixa, independente do momento da ativação;
     - **% da base** (ex.: Sterak): proporção × base.
     Faixas por nível (“200–300”) são interpoladas entre o nível 1 e o 15;
  5. o resultado **não depende da ordem de compra**, só do que está no inventário. Referência: wiki do League of
     Legends, notas do Capuz da Morte de Rabadon (o multiplicador “stacks additively” com outros e “stacks recursively
     with other sources of ability power”);
  6. valor em ouro = quantidade × preço por ponto do status.
- **Eficiências** da compra:
  - *estática*: fórmula do site changchiyou (passiva só sobre os status do próprio item) — o teste
    `GoldPricingReferenceTest` garante que a implementação reproduz os valores publicados por eles;
  - *dinâmica*: (valor dos status planos + valor da passiva calculada no passo 3) ÷ custo do item;
  - *marginal* (extra): ganho de valor em ouro de **toda** a build (inclui Forja Viva e passivas de outros itens)
    ÷ ouro efetivamente pago.
- **Componentes**: componentes já possuídos são consumidos (recursivamente) e descontados do preço, como na loja.
- **Regras da loja** (checadas pelo motor sobre o inventário logo após cada compra, já com os componentes consumidos):
  nenhum item finalizado repetido; só um item por grupo exclusivo (penetração de armadura %, penetração mágica %,
  Lâmina Arcana, Lágrima da Deusa, Salva-Vidas, item de suporte — campo `exclusivo` do catálogo; botas ficam fora dos
  grupos de penetração); só um item ativável; 5 itens + 1 bota. A interface não deixa adicionar uma compra que quebre
  uma regra, exceto o limite de itens: uma compra sem espaço no inventário naquele ponto da ordem é mantida na
  sequência, mas **desconsiderada** (sem ouro, tempo nem status) e aparece esmaecida com ⊘. A linha do tempo mostra os
  6 espaços do inventário a cada compra, com os itens finalizados em destaque.
- **Sequência de compras**: arraste os itens para reordenar; clique num item para selecioná-lo, e o painel da loja
  mostra o que aquela compra traz à build (minuto, ouro pago, valor em ouro somado e eficiências).
- Clique numa linha da linha do tempo para ver o **detalhamento**: base/itens/passivas/forja por status, fórmula de
  cada passiva e de cada eficiência.
- No gráfico, a faixa de eventos mostra cada compra (ícone do item) e cada subida de nível. Clicar no gráfico ou num
  evento escolhe o momento exibido no **painel de status do campeão** (total com base + adicional, como na aba de
  status do jogo) e no **relatório de relevância** (valor em ouro de cada item e de cada passiva, mais a Forja Viva).
- Interface em português ou inglês, com siglas de status em qualquer dos dois (DdA/AD, PdH/AP, RM/MR…), escolhidas
  no cabeçalho. Nomes e textos dos itens continuam em português, como na loja.

## Aproximações assumidas

- **Tabela de XP por nível é uma estimativa**: não há fonte pública confiável para o Wild Rift. Os valores iniciais
  são os 15 primeiros níveis da curva do LoL de PC (280 XP para o nível 2, +100 por nível). Editável no Admin.
- O minuto de compra considera só ouro acumulado (não modela a volta à base nem ouro gasto em outras coisas).
- Passivas de dano, cura, escudo e efeitos em aliados ficam só como texto; não entram no valor em ouro.
- O preço do Mana mantém a razão da metodologia original (Lágrima carregada: 400 de ouro por 900 de Mana), porque a
  Lágrima da loja mostra só os 200 de Mana iniciais.

## Créditos

- Itens, textos e ícones: League of Legends: Wild Rift, Riot Games — transcritos de capturas da loja do jogo.
- Metodologia de preço por status e eficiência de ouro estática:
  [changchiyou/wildrift-gold-efficiency](https://github.com/changchiyou/wildrift-gold-efficiency), licença MIT —
  Copyright (c) 2024 changchiyou. Aviso completo em
  [`backend/src/test/resources/referencia/LICENSE-wildrift-gold-efficiency.txt`](backend/src/test/resources/referencia/LICENSE-wildrift-gold-efficiency.txt).
- Status base do Ornn: medidos no jogo (aba de status nos níveis 1 e 15); Forja Viva: wiki.leagueoflegends.com/en-us/WR:Ornn.
