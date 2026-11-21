# Campanha de treino das IAs (Rodada 21): orçamento rapido, 20 núcleos

Ordem: as 7 classes, depois Ornn, Twisted Fate, Caitlyn.

| IA | status | êxito da nova | IA em uso (referência) | Tático | pior célula | gerações (melhor) | treino s | validação s | total s |
|---|---|---|---|---|---|---|---|---|---|
| class:Tanque v2 | recusada | 29.0 | 26.8 | 29.0 | -27.8 (Controlador · rota) | 20 (0) | 62 | 18 | 80 |
| class:Lutador v2 | recusada | 51.8 | 42.9 | 58.7 | -33.3 (Mago · fuga) | 20 (20) | 49 | 16 | 66 |
| class:Assassino v2 | aceita | 58.5 | 46.4 | 58.5 | -5.6 (Lutador · aberto) | 20 (0) | 43 | 10 | 53 |
| class:Mago v2 | recusada | 25.2 | 21.4 | 25.2 | -22.2 (Lutador · rota) | 20 (0) | 37 | 10 | 48 |
| class:Atirador v2 | recusada | 28.2 | 25.8 | 36.1 | -38.9 (Mago · aberto) | 20 (16) | 40 | 11 | 52 |
| class:Controlador v2 | recusada | 26.4 | 21.8 | 26.8 | -22.2 (Atirador · aberto) | 20 (4) | 50 | 16 | 66 |
| class:Especialista v2 | recusada | 36.7 | 33.3 | 36.7 | -38.9 (Tanque · rota) | 20 (0) | 47 | 14 | 62 |
| Ornn v122 | recusada | 61.5 | 61.3 | 72.2 | -27.8 (Mago · rota) | 8 (2) | 14 | 18 | 32 |
| Twisted Fate v1 | recusada | 30.0 | 29.6 | 46.2 | -16.7 (Especialista · aberto) | 8 (0) | 17 | 13 | 30 |
| Caitlyn v1 | recusada | 23.4 | 23.6 | 35.1 | -22.2 (Controlador · rota) | 8 (4) | 14 | 11 | 25 |

Estimativa medida (rapido): 66 s por IA de classe (3 medidas), 29 s por campeão (3 medidos); os 143 campeões e as 7 classes do zero: 1.3 h. Esta execução: 518 s.

## class:Tanque (recusada): êxito 29.0%, Tático 29.0% (+0.0)

| classe adversária | rota | aberto | dupla | fuga |
|---|---|---|---|---|
| Tanque | 17 (+0) | 0 (+0) | 0 (+0) | 22 (+0) |
| Lutador | 17 (+0) | 11 (+0) | 6 (+0) | 28 (+0) |
| Assassino | 44 (+0) | 39 (+0) | 6 (+0) | 17 (+0) |
| Mago | 50 (+0) | 61 (+0) | 56 (+0) | 33 (+0) |
| Atirador | 17 (+0) | 56 (+0) | 0 (+0) | 61 (+0) |
| Controlador | 17 (+0) | 22 (+0) | 100 (+0) | 22 (+0) |
| Especialista | 56 (+0) | 22 (+0) | 6 (+0) | 28 (+0) |

## class:Lutador (recusada): êxito 51.8%, Tático 58.7% (-6.9)

| classe adversária | rota | aberto | dupla | fuga |
|---|---|---|---|---|
| Tanque | 67 (-6) | 61 (+0) | 28 (-17) | 44 (-17) |
| Lutador | 44 (-28) | 39 (-11) | 17 (-11) | 50 (+6) |
| Assassino | 67 (+6) | 50 (+6) | 39 (+6) | 44 (-17) |
| Mago | 78 (+11) | 100 (+0) | 50 (+0) | 17 (-44) |
| Atirador | 56 (+11) | 89 (+6) | 28 (+11) | 39 (-39) |
| Controlador | 61 (-11) | 56 (+0) | 100 (+0) | 39 (-22) |
| Especialista | 56 (-11) | 56 (-6) | 33 (+0) | 44 (-17) |

## class:Assassino (aceita): êxito 58.5%, Tático 58.5% (+0.0)

| classe adversária | rota | aberto | dupla | fuga |
|---|---|---|---|---|
| Tanque | 67 (+0) | 50 (+0) | 39 (+0) | 72 (+0) |
| Lutador | 44 (+0) | 39 (+0) | 33 (+0) | 39 (+0) |
| Assassino | 56 (+0) | 61 (+0) | 17 (+0) | 56 (+0) |
| Mago | 72 (+0) | 72 (+0) | 56 (+0) | 89 (+0) |
| Atirador | 56 (+0) | 50 (+0) | 50 (+0) | 61 (+0) |
| Controlador | 50 (+0) | 61 (+0) | 94 (+0) | 83 (+0) |
| Especialista | 78 (+0) | 61 (+0) | 83 (+0) | 50 (+0) |

## class:Mago (recusada): êxito 25.2%, Tático 25.2% (+0.0)

| classe adversária | rota | aberto | dupla | fuga |
|---|---|---|---|---|
| Tanque | 44 (+0) | 6 (+0) | 0 (+0) | 22 (+0) |
| Lutador | 11 (+0) | 0 (+0) | 0 (+0) | 28 (+0) |
| Assassino | 28 (+0) | 28 (+0) | 6 (+0) | 22 (+0) |
| Mago | 56 (+0) | 44 (+0) | 6 (+0) | 50 (+0) |
| Atirador | 61 (+0) | 33 (+0) | 0 (+0) | 22 (+0) |
| Controlador | 44 (+0) | 17 (+0) | 94 (+0) | 28 (+0) |
| Especialista | 33 (+0) | 6 (+0) | 0 (+0) | 17 (+0) |

## class:Atirador (recusada): êxito 28.2%, Tático 36.1% (-7.9)

| classe adversária | rota | aberto | dupla | fuga |
|---|---|---|---|---|
| Tanque | 61 (-17) | 0 (-11) | 0 (+0) | 44 (-11) |
| Lutador | 50 (-6) | 0 (-22) | 0 (-6) | 33 (+11) |
| Assassino | 39 (-6) | 17 (-28) | 17 (+6) | 33 (-6) |
| Mago | 56 (+17) | 33 (-17) | 33 (+0) | 22 (-11) |
| Atirador | 6 (-39) | 33 (-17) | 0 (+0) | 33 (-6) |
| Controlador | 28 (-39) | 6 (+0) | 94 (-6) | 56 (+6) |
| Especialista | 44 (+6) | 22 (-11) | 0 (+0) | 28 (-11) |

## class:Controlador (recusada): êxito 26.4%, Tático 26.8% (-0.4)

| classe adversária | rota | aberto | dupla | fuga |
|---|---|---|---|---|
| Tanque | 39 (-6) | 0 (+0) | 0 (+0) | 67 (+33) |
| Lutador | 22 (-6) | 0 (+0) | 0 (-6) | 39 (+6) |
| Assassino | 28 (-28) | 11 (-17) | 6 (+6) | 44 (+17) |
| Mago | 78 (+39) | 11 (-28) | 39 (-22) | 44 (+17) |
| Atirador | 33 (+6) | 6 (-39) | 0 (+0) | 50 (+28) |
| Controlador | 28 (-11) | 0 (-22) | 89 (-11) | 44 (+28) |
| Especialista | 28 (-11) | 0 (-6) | 0 (+0) | 33 (+22) |

## class:Especialista (recusada): êxito 36.7%, Tático 36.7% (+0.0)

| classe adversária | rota | aberto | dupla | fuga |
|---|---|---|---|---|
| Tanque | 11 (+0) | 11 (+0) | 0 (+0) | 61 (+0) |
| Lutador | 28 (+0) | 0 (+0) | 33 (+0) | 22 (+0) |
| Assassino | 28 (+0) | 50 (+0) | 22 (+0) | 39 (+0) |
| Mago | 61 (+0) | 67 (+0) | 78 (+0) | 44 (+0) |
| Atirador | 50 (+0) | 78 (+0) | 0 (+0) | 44 (+0) |
| Controlador | 28 (+0) | 28 (+0) | 94 (+0) | 28 (+0) |
| Especialista | 28 (+0) | 39 (+0) | 6 (+0) | 50 (+0) |

## Ornn (recusada): êxito 61.5%, Tático 72.2% (-10.7)

| classe adversária | rota | aberto | dupla | fuga |
|---|---|---|---|---|
| Tanque | 50 (-11) | 83 (-6) | 22 (-22) | 44 (+0) |
| Lutador | 33 (-28) | 72 (+17) | 50 (+11) | 28 (-11) |
| Assassino | 39 (-17) | 83 (+11) | 28 (-6) | 50 (-6) |
| Mago | 50 (-39) | 100 (+0) | 100 (+6) | 94 (+0) |
| Atirador | 56 (-28) | 100 (+0) | 33 (+0) | 72 (-17) |
| Controlador | 39 (-39) | 89 (-6) | 100 (+0) | 50 (-6) |
| Especialista | 33 (-56) | 94 (-6) | 67 (-22) | 61 (-22) |

## Twisted Fate (recusada): êxito 30.0%, Tático 46.2% (-16.3)

| classe adversária | rota | aberto | dupla | fuga |
|---|---|---|---|---|
| Tanque | 44 (-39) | 6 (-6) | 0 (+0) | 61 (-22) |
| Lutador | 44 (-33) | 0 (-39) | 0 (-6) | 50 (+6) |
| Assassino | 39 (+0) | 39 (+6) | 0 (-6) | 33 (-6) |
| Mago | 67 (-17) | 17 (-39) | 11 (-44) | 56 (+0) |
| Atirador | 33 (-22) | 22 (-33) | 0 (+0) | 39 (-28) |
| Controlador | 33 (-39) | 0 (-22) | 100 (+0) | 61 (-6) |
| Especialista | 22 (-22) | 6 (-28) | 0 (-6) | 56 (-6) |

## Caitlyn (recusada): êxito 23.4%, Tático 35.1% (-11.7)

| classe adversária | rota | aberto | dupla | fuga |
|---|---|---|---|---|
| Tanque | 28 (-56) | 0 (-6) | 0 (+0) | 6 (-50) |
| Lutador | 39 (-17) | 11 (+6) | 0 (-6) | 6 (-33) |
| Assassino | 39 (-6) | 44 (+0) | 0 (-6) | 33 (-11) |
| Mago | 56 (-6) | 50 (+0) | 17 (+6) | 33 (-6) |
| Atirador | 28 (-17) | 44 (+0) | 0 (+0) | 11 (-22) |
| Controlador | 11 (-50) | 17 (+6) | 100 (+0) | 11 (-22) |
| Especialista | 33 (-17) | 22 (-11) | 0 (+0) | 17 (-6) |

