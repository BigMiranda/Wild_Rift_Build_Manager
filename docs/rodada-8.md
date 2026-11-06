# Rodada 8 — Repaginação: super abas, estado que não se perde, tela grande para mobile em paisagem

Um commit por parte. Cada parte termina com `npm run build` limpo, e com `./mvnw test` limpo quando mexe no backend.

**Testes antes da rodada:** 330 (14 opcionais pulados), contados na `target/surefire-reports/`. Depois da Parte 0: 334.

## Parte 0 — Comparadores consistentes ✅

### Uma função para tudo: `BuildDeltaService`

`backend/.../arena/BuildDeltaService.java` é o "trocar e recalcular a build inteira". Recebe a build, uma **troca**
(`Change`: um item numa compra ou numa compra nova, outra página de runas ou outra ordem de compras) e o minuto, roda as
duas linhas do tempo e devolve o **Δ da build inteira** (`Delta`):

- **ouro efetivo** da build antes e depois (status × preço + passivas modeladas + Forja Viva + runas), separado em
  **do próprio item** (as fontes da troca) e **efeito nos outros itens e runas**, com a lista **por fonte** (cada compra,
  cada runa, o campeão, a Forja Viva);
- os **status** da ficha inteira, antes, depois e Δ;
- a **vida efetiva** física, mágica e mista pela ficha do planejador (sem luta);
- o **valor das passivas** (ouro do que as passivas modeladas dão em status).

As fontes casam entre as duas linhas do tempo por item e cópia (`item:<id>#<n>`), runa e campeão por nome, e a compra
trocada é uma fonte só (`own-item`), seja qual for o item antes e depois. Assim o "efeito nos outros" inclui a Forja
Viva, as passivas com `ref`, o Abençoado do Warmog, as runas que escalam **e** as compras seguintes que a troca atrasa ou
adianta (um item mais caro deixa o próximo incompleto no minuto).

**Quem chama:**

| comparador | onde | como |
|---|---|---|
| Comparador rápido de itens | `QuickCompareService.compare` | `deltas.compare(antes, depois, minuto, Change.item(...))` em cada cartão (`Card.delta`) |
| Comparar runas (Parte 4) | `POST /api/quick/delta` | `Change.runes(página)` |
| Candidatos da Efetividade | `EffectivenessReport` → `POST /api/quick/delta` | `Change.item(compra, candidato)`, uma linha "Build inteira" na tabela |
| Calculadora | `CalculatorService.compute` | cada alternativa contra a melhor ordem no último minuto: `Change.order(...)` (`Line.delta`) |

No frontend, `components/WholeDelta.jsx` mostra um `Delta` (cada tela escolhe as partes com `show`), e `fightDelta`
soma as medidas de luta quando há cenário: com o **Índice Básico** (comparador rápido) ou um cenário de teste
(candidatos da Efetividade), o cartão ganha o **Δ de dano causado e de dano recebido** da build inteira, média das lutas,
contra a build como está (as mesmas lutas da build sem a troca rodam junto).

### Itens que evoluem

O comparador rápido aceita a forma evoluída como candidata (`MAX_CANDIDATES` 5 → 9: a referência e até 4 itens, cada
um nas duas formas). Cada cartão de um item que evolui (os seis `evolucao` da loja) tem o seletor **Forma: base ·
evoluída · as duas**. O motor não estima quando a evolução acontece, então a evoluída diz "evoluído desde a compra
(estimativa)" (`Card.evolvesAt` fica nulo; o campo existe para quando o motor souber).

### Testes (`BuildDeltaTest`)

- trocar um item que alimenta a Forja Viva muda o "efeito nos outros" (a fonte Forja Viva muda);
- o Δ da build inteira bate com a soma das fontes, e com próprio + outros; o Δ de cada status é depois − antes;
- um candidato evoluído soma os status da forma evoluída (Fimbulwinter × Aproximação Invernal), e cada forma aponta a outra;
- os comparadores dão o mesmo número para a mesma troca: o cartão do comparador rápido, o Δ pedido sozinho (o caminho
  dos candidatos da Efetividade e do comparar runas) e a leitura de uma ordem (o caminho da calculadora).

Medido no banco de desenvolvimento (Big Carry v1, compra #2, Passos de Mercúrio → Aproximação Invernal, aos 12:00):
ouro efetivo −1.249 (24.295 → 23.046), sendo +2.028 do próprio item e −3.277 nos outros. A Égide de Fogo Solar fica
incompleta aos 12:00 (−3.066) e o Suserano do Gelo rende menos (−1.426). O comparador antigo só mostrava os +2.028.
