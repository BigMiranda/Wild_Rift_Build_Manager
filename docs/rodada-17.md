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

## Parte 2: redução de dano em %

**O pedido.** Redução de dano em %, como a da bota de Armadura, do Randuin, da 2ª do Garen e da ultimate do Alistar,
como condicionais modeladas, com o impacto visível no gráfico e no resto.

**O modelo.** Status novos, que se multiplicam entre si (`Stats.DR_STATS`): `% Damage Reduction` (todo o dano),
`% Physical / Magic Damage Reduction` (um tipo), `% Attack Damage Reduction` (ataques de Campeões) e `% Critical Damage
Reduction` (acertos críticos). O dano sofrido de um tipo é cortado pela resistência e depois por
`1 − (1 − todo) × (1 − do tipo)` (`Stats.damageCut`, espelhada em `format.js › damageCut`). Preço: 1% de todo o dano a
menos ≈ 2 de Armadura e 2 de RM com 100 de cada, numa luta inteira; as de habilidade duram poucos segundos (≈ 1/4 da
luta), daí todo o dano = o preço da Armadura, um tipo = metade, ataques = a Armadura (vale sempre, mas só contra
ataques) e críticos = 0,4.

| Fonte | Status | Valor | Arena |
|---|---|---|---|
| Botas Galvanizadas de Aço, Mobilização Blindada (Bloqueio) | ataques | 10 | `reduz_dano` do item (já existia) |
| Presságio de Randuin (Resiliência) | críticos | 30 | `reduz_dano` do item (já existia) |
| Alistar: Vontade Indestrutível (R) | todo o dano | 55/65/75 | ficha do lutador, se ligada |
| Ambessa: Execução Pública (redução) (R) | todo o dano | 10/20/30 | ficha |
| Braum: Inquebrável (3) | todo o dano | 35–50 (pela frente) | ficha |
| Galio: Escudo de Durand (Defender) (2) | mágico / físico | 25–40 / 12,5–20 | ficha |
| Garen: Coragem (ativo) (2) | todo o dano | 30 | a ficha do Garen (modelado) |
| Gragas: Fúria da Bebedeira (2) | todo o dano | 11–17 | ficha |
| Irelia: Dança Desafiadora (segurar) (2) | todo o dano | 60 | a ficha da Irelia (modelado) |
| Jarvan IV: Cataclisma (arena) (R) | todo o dano | 12/16/20 | ficha |
| K'Sante: Criador de Caminhos (carga) (2) | todo o dano | 30 | a ficha do K'Sante (modelado) |
| Master Yi: Meditar (2) | todo o dano | 55–70 | ficha |
| Nilah: Véu Jubiloso (2) | mágico | 25 | ficha |
| Warwick: Uivo Primitivo (3) | todo o dano | 35–50 | ficha |

As partes que escalam com PdH, RM ou Vida (Galio, Gragas, Irelia) ficam de fora (na nota de cada efeito).

**Na tela.** Os gráficos de Redução de Dano Físico e Mágico ganham linhas tracejadas: com as reduções em % e, no físico,
contra ataques de Campeões e contra acertos críticos. A ficha do momento mostra os status em % e quanto dano físico,
mágico, de ataques e de críticos a build sofre a menos ao todo (resistência + efeitos).

Migração v31: reimporta o catálogo (preços novos). Na Arena, só mudam lutas com uma dessas condicionais ligada num
campeão sem ficha que a faça.
