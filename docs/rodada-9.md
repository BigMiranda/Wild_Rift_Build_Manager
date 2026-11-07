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
