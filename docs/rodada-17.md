# Rodada 17: subtipos de recarga e redução de dano

Pedido de 15/11/2026, em duas partes (um commit cada).

## Parte 1: recarga (Aceleração de Habilidade e seus subtipos)

**O pedido.** A Aceleração da Ultimate e a Aceleração de Habilidade Básica são tipos de redução de recarga e devem
aparecer no gráfico de Aceleração de Habilidade, como o Escudo Mágico e o Físico aparecem no de Escudo. Um terceiro
subtipo, visível no gráfico e nas estatísticas: a redução de recarga em %, como no Ímpeto Gradual, na Transcendência,
no Arcanista do Axioma e na Investida do Ornn que atinge o pilar da Ruptura Vulcânica, modelados e refletidos nos
tempos de recarga.

**O modelo.**

- Status novos (`precos_status.yml`, efetivos como os escudos): `% Basic Cooldown Reduction`, `% Ultimate Cooldown
  Reduction` e `% Ability N Cooldown Reduction` (N = 1, 2, 3), na loja "% Redução de Recarga das Básicas / da Ultimate /
  da Habilidade N". As fontes se multiplicam como a Tenacidade (`Stats.combine`: cada uma corta o que sobrou).
- Recarga de uma habilidade (`Stats.cooldownFactor`, espelhada em `format.js › cooldownCut`):
  `base / (1 + (AH + AH da ultimate ou das básicas) / 100) × (1 − redução / 100)`, com a redução das básicas combinada
  com a da própria habilidade (teto de 90%).
- Preço: 1% de recarga a menos ≈ 1,25 de AH na faixa comum (40 de AH), vezes a parte das habilidades atingidas (3/4
  nas básicas, 1/4 na ultimate ou numa habilidade).
- Fontes modeladas (condicionais, salvo o Navori, que já era uma opção):

| Fonte | Status | Valor |
|---|---|---|
| Ímpeto Gradual | % das básicas | 20 (corta 20% das recargas restantes ao acionar) |
| Transcendência | % das básicas | 8 a partir do nível 9 (a básica que acerta corta 8% da própria recarga) |
| Arcanista do Axioma | % da ultimate | 7 (o abate corta 7% da recarga restante) |
| Adagas Rápidas Navori | % das básicas | 14 por ataque (antes 19 de AH básica por ataque) |
| Ornn: Investida no pilar | % da habilidade 3 | 25 |
| Gnar: Bumerangue apanhado | % da habilidade 1 | 40 |
| Annie: Desintegrar abate | % da habilidade 1 | 50 |

**Na Arena.** A recarga lê as reduções em % só da ficha do lutador (o sheet com os efeitos que a luta decide
desligados). Os cortes que a luta faz de verdade ficam fora da ficha, para não contar duas vezes: Ímpeto Gradual (já
tinha `reduz_recargas`), Transcendência (efeito `transcendencia`), Navori (`modelado`), e os novos `abate_recarga_ult`
do Arcanista (no abate) e `pilar_recarga` da Investida do Ornn (ao destruir um pilar). As escolhas da build que a luta
não tem como decidir (o Gnar apanhar o bumerangue) contam a luta toda.

**Na tela.** O chip "Aceleração da Ultimate" saiu: o gráfico de Aceleração de Habilidade desenha a AH e, tracejadas,
a da ultimate, a das básicas e a de uma habilidade com redução própria, cada uma com as reduções em % convertidas na
AH que cortaria o mesmo; a dica diz quanto de recarga cada linha tem a menos. A ficha do momento mostra os status em %
e "Recarga a menos" da ultimate, das básicas e de cada habilidade com redução própria. As habilidades do campeão, no
modo "momento", mostram a recarga efetiva do rank; o cartão de recarga da Arena diz a redução usada.

Migração v30: reimporta o catálogo (preços novos). Lutas com o Ornn que destrói o pilar e com o Arcanista que abate
mudam de resultado.
