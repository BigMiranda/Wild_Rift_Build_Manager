# Rodada 6.b — andamento

Hitboxes reais, motor para os outros campeões, mapa completo, partida inteira e calculadora de builds. Um commit por
parte; cada parte termina com `./mvnw test` e `npm run build` limpos.

**Branch:** `rodada-6b`, criada a partir do `main` (7a31735). Nada foi enviado ao remoto.

**Testes antes da rodada:** 236 (0 falhas, 11 opcionais pulados), contados com a `target/surefire-reports/` limpa.

## Parte 1 — Hitboxes próximas das reais ✅

### Vocabulário

`arena/fight/HitShape` guarda a área de uma zona no próprio sistema dela: origem em quem lança (ou no ponto mirado), +x
para onde mira. Todas as formas respondem à mesma pergunta, `gap(x, y)`: a distância do ponto até a área. O alvo é acertado
quando o círculo do corpo dele toca a área (`hits`), e o mapa desenha a mesma geometria (`outline`).

| forma | campos |
|---|---|
| `circulo` | `raio` |
| `anel` | `raio_interno`, `raio` |
| `setor` | `angulo`, `raio`, `raio_interno` (o setor anelar) |
| `retangulo` | `comprimento`, `largura`, `centro` |
| `capsula` | `comprimento`, `largura`, `centro` |
| `poligono` | `vertices` |
| `arco` ou `corrente` | `pontos`, `largura` |

**Habilidade com zonas:** `forma: zonas`, `zonas: [{nome, forma, ..., dano, efeitos, acumula}]`. O `dano` pode ser um nome de
`danos`. A primeira zona em que o alvo está vale, salvo `acumula: true`.

**No tempo:** `atraso`, `expande: {de, em}`, `move: {velocidade, velocidade_final, aceleracao, distancia}`, `segue: true` e
`duracao` com `intervalo`. Forma que move ou cresce acerta cada alvo uma vez. `origem: ponto` centra a zona no ponto mirado.

**Canais e campos:** `canal` e `campo` continuam como formas e leem a hitbox da primeira zona, porque têm regras próprias
(tempo parado, dano dividido pelos pulsos, atraso do campo).

**Tela:** forma nova de desenho `zone`, com o contorno em pontos. A primeira zona é cheia; as seguintes, tracejadas e mais
claras. O cartão da Rodada 5 mostra "zonas: lâmina ×1" (`Use.zones`), e os eventos ganharam `zona:1:lâmina`.

**Previsões da IA:** `zonas` entra nas formas miradas; o caminho de fuga é o da zona de que o alvo demora mais a sair.

### Fichas convertidas

Medidas lidas em 31/10/2026 nas páginas `WR:` da wiki; onde a página do Wild Rift não traz, a do LoL de PC (marcada).

| habilidade | antes | agora | fonte |
|---|---|---|---|
| Darius, Dizimar | área de raio 460, com `interno: 0.35` testado pelo centro do alvo | `anel` 240–460 (lâmina: dano cheio, cura e Hemorragia) e `circulo` 240 (cabo: 35%, nada mais) | WR traz "240 / 460" e os 35%; qual raio é qual vem do PC |
| Darius, Apreender | `cone` de 50° e 450, pelo centro do alvo | `setor` de 50° e 450, pelo corpo do alvo | WR |
| Rumble, Cospe-Fogo | canal em cone de 64° e 600, pelo centro do alvo | canal com `setor` de 64° e 600, pelo corpo | PC (o WR não traz) |
| Rumble, O Equalizador | retângulo de 1000 × 390 | `capsula` de 900 com largura 410: seis foguetes de raio 205, a 180 um do outro, 1310 de ponta a ponta | PC (o WR não traz) |
| Vayne, Condenar | sem desenho do empurrão | `capsula` de 475 no mapa: o corredor do empurrão | PC (475); largura EST |

**Condenar não muda a luta:** a parede já era testada pelo corpo do alvo enquanto ele é empurrado. A wiki não traz a largura do
teste, então o corredor é desenho.

**Não convertidas:** os demais são linhas, avanços e círculos simples, sem forma composta. As páginas `WR:` de Renekton e Ornn não
trazem larguras, então nada mudou neles. O elemental do Ornn segue com o retângulo próprio (`elementalSweeps`), porque a reconjuração
depende dele; o `move` das zonas é a versão genérica.

**Número sem explicação:** o PC dá "RANGE: 600 • -50" no Cospe-Fogo. A página não explica o −50 e ele ficou de fora.

### Golden 🎯

Regravados com `-Darena.golden.write=true`, com a mudança intencional das formas. **Mudaram 21 de 35 cenários, todos com Darius ou
Rumble:**

- duelos: Ornn × Darius, Ornn × Rumble, Darius × (Ornn, Renekton, Rumble, Vayne, K'Sante), Renekton × (Darius, Rumble), Rumble × (Ornn, Darius,
  Renekton, Vayne, K'Sante), Vayne × (Darius, Rumble), K'Sante × (Darius, Rumble);
- `3v3`, `fixed target, no abilities` e `long Darius x Vayne`.

Os demais, sem Darius nem Rumble, ficaram iguais. O contorno de uma zona é desenho e ficou fora da impressão digital. `golden-ai-v2.txt`
mudou em 3 linhas pelo mesmo motivo.

**O efeito é grande em algumas lutas.** Antes, um alvo com o corpo tocando o anel e o centro dentro de 240 levava o cabo; agora leva a lâmina,
como a regra do corpo manda. Exemplos: no `3v3`, o vencedor passou do lado vermelho (19,9 s) para o azul (28,2 s), com o Darius causando 7.967
em vez de 5.053; em Rumble × Darius o Rumble vence em 14,9 s em vez de 19,1 s, com o Darius causando 2.404 em vez de 3.127.

### Testes

`HitShapeTest` (17): cada forma acerta e erra nos limites; o teste no mundo gira com a conjuração; os contornos fecham, e o anel tem furo. Na luta: no
anel, Hemorragia e dano cheio; no centro, nada disso, com menos da metade do dano; o alvo só recebe a primeira zona; forma com atraso, que fica,
que viaja e que cresce; determinismo.

**`trainedDariusBeatsTheScriptOnValidation` mudou de orçamento.** Com a lâmina certa, a IA treinada em 30 gerações ganhou do Roteiro em 56% das largadas, abaixo da meta de
60%. Subi para 45 gerações, sem mudar a meta: 85%, 75% e 71% nas três sementes (média 77%).

Testes: 236 → 253 (11 opcionais pulados). `npm run build` limpo.

## Para o usuário

**Vale conferir:**
- **Dizimar:** decidi que o corpo do alvo tocando o anel já recebe a lâmina, pela regra geral do prompt. Se no jogo vale o centro do alvo, o Darius fica forte demais nesta base.
