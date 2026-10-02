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

Testes do motor de cálculo:

```bash
cd backend && ./mvnw test
```

## Stack e decisões

| Camada | Escolha | Por quê |
|---|---|---|
| Backend | Java 11 + **Spring Boot 2.7** (web + jdbc) | A máquina tem JDK 11; Spring Boot 3 exige Java 17. Migrar para 3.x é trocar a versão do parent quando houver JDK 17+. |
| Persistência | **SQLite** (`org.xerial:sqlite-jdbc`) + **`JdbcTemplate` puro**, sem JPA/Hibernate | Hibernate não tem dialect oficial de SQLite (só o community dialect). O modelo é pequeno e as consultas são simples, então SQL direto evita toda essa aresta. Schema idempotente em `schema.sql`, pool Hikari com 1 conexão (SQLite tem um único escritor; uso monousuário). |
| Frontend | React 18 + Vite 5 + Recharts | SPA simples, sem roteador. Gráfico com paleta categórica validada para daltonismo, cor fixa por build. |

## Estrutura

```
backend/
  src/main/java/com/ornnplanner/
    engine/      motor de cálculo puro (sem Spring): TimelineEngine, GoldPricing, Model, Stats
    seed/        CatalogImporter (YAML -> SQLite) e ReferenceSeeder (Ornn, Forja Viva, XP, pasta inicial)
    repo/        acesso a dados com JdbcTemplate
    service/     PlannerService (monta dados de referência, filtro tank/suporte, cálculo)
    api/         PlannerController (/api/...) e AdminController (/api/admin/...)
  src/main/resources/
    schema.sql, application.properties
    seed/        items_7_3.yml, stats_7_3.yml + LICENSE do projeto de origem
  src/test/...   testes do motor e da réplica da eficiência estática
frontend/src/
  pages/         Planner (pastas, editor, linha do tempo, comparação), Admin, About
  components/    FolderTree, ItemPicker, TimelineTable, StatChart
```

## Importação do catálogo

`CatalogImporter` lê `seed/items_7_3.yml` e `seed/stats_7_3.yml` (patch **7.3**, o mais recente do repositório de
origem em 28/09/2026) e grava itens, linhas de status (incluindo `ratio`, `ref`, `ref_type` das passivas) e as
definições de status usadas no preço por ponto. Roda automaticamente quando o banco está vazio e pode ser disparado de
novo pela tela **Admin → Reimportar catálogo** (`POST /api/admin/catalog/reimport`). A reimportação faz *upsert* pelo
nome (ids das builds salvas não mudam) e **preserva itens corrigidos à mão** no Admin, a menos que se marque
“sobrescrever”. Para um patch novo: copie os YAMLs para `seed/` e ajuste `planner.seed.*` em `application.properties`.

## Ícones dos itens

Os ícones ficam versionados em `backend/src/main/resources/seed/images/` (1 por item-base; variantes como
`X (Passiva)` usam o ícone de `X`) e são servidos por `GET /api/items/{id}/image`, sem nenhuma chamada externa durante
o uso. Eles foram baixados uma única vez com:

```bash
python tools/download_item_images.py
```

O script tenta primeiro o ícone oficial do Wild Rift na wiki (`<Nome>_WR_item.png`) e depois a URL do YAML de
referência (alguns hosts, como a Fandom, recusam download direto). Ele só baixa o que falta, então basta rodá-lo de
novo quando um patch trouxer itens novos. Os ícones são arte da Riot Games, obtidos via wiki.leagueoflegends.com.

## Regras de cálculo

Implementadas e documentadas em [`TimelineEngine`](backend/src/main/java/com/ornnplanner/engine/TimelineEngine.java).

- **Tempo**: ouro no minuto *t* = 500 + ouro/min × *t*. A compra *i* acontece quando o ouro acumulado cobre o total
  pago até ela. **Nível**: XP = XP/min × *t*, comparado com a tabela de XP acumulado (máximo nível 15).
- **Status do Ornn** no nível L: `base + crescimento × (L − 1)`.
- **Por compra**, sobre o inventário inteiro naquele momento:
  1. soma dos status planos de todos os itens;
  2. status base da unidade no nível do minuto da compra;
  3. **passivas percentuais na ordem de compra**: `ratio × (base [se escopo TOTAL] + status planos de todos os itens
     + passivas de itens comprados ANTES)`. Um item nunca compõe sobre a própria passiva nem sobre itens comprados depois;
  4. **Forja Viva** por último: % do nível × (vida/armadura/RM **bônus** = itens + passivas). A base nunca é multiplicada;
  5. valor em ouro = quantidade × preço por ponto do status.
- **Eficiências** da compra:
  - *estática*: fórmula do site de referência (passiva só sobre os status do próprio item) — o teste
    `GoldPricingReferenceTest` garante que ela reproduz o valor publicado para **todos os 243 itens** do patch;
  - *dinâmica*: (valor dos status planos + valor da passiva calculada no passo 3) ÷ custo do item;
  - *marginal* (extra): ganho de valor em ouro de **toda** a build (inclui Forja Viva e passivas de outros itens)
    ÷ ouro efetivamente pago.
- **Componentes**: se um item tiver receita cadastrada, componentes já possuídos são consumidos (recursivamente) e
  descontados do preço, como na loja do jogo.
- Clique numa linha da linha do tempo para ver o **detalhamento**: base/itens/passivas/forja por status, fórmula de
  cada passiva e de cada eficiência.

## Aproximações assumidas

- **Tabela de XP por nível é uma estimativa**: não há fonte pública confiável para o Wild Rift. Os valores iniciais
  são os 15 primeiros níveis da curva do LoL de PC (280 XP para o nível 2, +100 por nível). Editável no Admin.
- **Receitas não existem na fonte de dados** (os YAMLs não trazem componentes). A tabela `item_component` começa
  vazia e é editável no Admin. Enquanto não houver receitas, o filtro padrão mostra os itens básicos/intermediários que
  têm algum status defensivo/suporte (“provável componente”).
- O minuto de compra considera só ouro acumulado (não modela a volta à base nem ouro gasto em outras coisas).
- Linhas de passiva com `ratio` mas **sem `ref_type`** (ex.: Sterak's Gage) não podem ser avaliadas contra a build e
  usam o valor estático da referência (aparece um aviso ⚠ na linha).
- Variantes de item do site de origem (ex.: `Amaranth's Twinguard` vs `Amaranth's Twinguard (Endurance)`, ou
  `(lv1 …)`/`(lv15 …)`) foram importadas como itens separados. No cálculo dinâmico, o `ref` (valor de status de um
  campeão de exemplo) é ignorado, então as variantes lv1/lv15 se comportam igual.

## Créditos

O catálogo de itens e a metodologia de preço por status / eficiência de ouro estática vêm de
[changchiyou/wildrift-gold-efficiency](https://github.com/changchiyou/wildrift-gold-efficiency), licença MIT —
Copyright (c) 2024 changchiyou. O aviso completo está em
[`backend/src/main/resources/seed/LICENSE-wildrift-gold-efficiency.txt`](backend/src/main/resources/seed/LICENSE-wildrift-gold-efficiency.txt).
Status base do Ornn e Forja Viva: wiki.leagueoflegends.com/en-us/WR:Ornn.
