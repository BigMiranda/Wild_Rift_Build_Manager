# Rodada 7 — andamento

Ajustes da Efetividade, classificação de campeões e builds, e IA automática. Um commit por parte; cada parte termina com
`./mvnw test` e `npm run build` limpos.

**Testes antes da rodada:** 217 (0 falhas, 10 opcionais pulados), contados na `target/surefire-reports/`.

**Tabelas:** cópia do banco (`-Deffect.report=data/ornn-planner.db`), 7.3d, minuto padrão de cada build. O relatório Básico
salvo no banco tem 10 cenários: os 9 presets mais "Liandry + Maldição Sanguinária + Corta Cura", do usuário.

## Parte 1 — Referência do índice consistente ✅

### Causa

**A hipótese do prompt não se confirmou: o atacante não escala com o minuto.** Medido na cópia do banco, o Atirador alimentado
tem os mesmos status aos 14:00 e aos 23:30 (DdA 410, Armadura 105, 8.000 de Vida, 4 itens, sempre "minuto 30"). Nível,
início e distância também são os mesmos nas duas referências.

**A causa é a fuga.** O Tático recua com menos de 25% de Vida, e a Arena é uma rota reta:

| referência | Vida | t = 2 s | t = 4 s | depois | morre |
|---|---|---|---|---|---|
| Big Carry v1 (23:30) | 2.538 | 757 | morta aos 2,78 s | — | 2,8 s |
| Soldado v8 (21:52) | 2.944 | 1.090 | 144 | anda de x = 4.823 até x = 48, na parede | 20,8 s |

Os dois Ornn sem itens recebem o mesmo dano. A diferença de 400 de Vida (a runa Crescimento Excessivo, que a Big Carry não
tem) faz um passar vivo pelo limiar de recuar, e ele ganha 18 s de caminhada. O atacante é só um pouco mais rápido e o
alcança na parede. O índice 722 da Big Carry vinha daí.

O mesmo limiar explica parte das variações por minuto: Soldado v8 dava 7,1 s aos 14:00, 17,7 s aos 23:30 e 29,2 s aos 21:52.

Conferidos os outros pontos pedidos:
- **Runas:** a referência usa as da build. É assim que deve ser, e por isso duas builds têm referências diferentes.
- **Nível:** igual. Mas o Crescimento Excessivo cresce com o relógio, então dois minutos do mesmo nível davam Vida diferente
  (2.944 aos 21:52, 2.969 aos 23:30, 3.006 aos 26:00).
- **Condicionais:** os das runas seguem a build; os de itens saem com os itens.
- **Início e distância:** os mesmos.

### Correção

- **Tático sem fuga** (`ai/StandPolicy`, cérebro `tactical_stand`): nas lutas da Efetividade, recuar e o Flash de escape nunca
  são válidos. Vale para a build, a referência, os candidatos e o valor medido das passivas. É o Tático com essas duas ações
  mascaradas; o resto é igual, e a Arena comum não muda.
- **A referência depende do nível, não do minuto:** ela roda no primeiro minuto daquele nível
  (`FighterBuilder.levelStart`).
- **O que escala com o minuto:** só a build. O atacante tem os status do preset. O cenário ganhou **"Escala com o minuto"**
  (`scalesWithMinute`, desligado; o YAML traz a regra em `escala_com_minuto`: fator = minuto ÷ 25, entre 0,2 e 1,4, sobre
  Vida, DdA, PdH e base dos pulsos; os itens não mudam).
- **Comparativo:** segue a regra da 6.a, de todas as builds usarem a referência da build aberta, agora explicada na tela e
  na ajuda.
- **"Abrir a luta na Arena"** abre com o mesmo cérebro, que aparece como "Tático sem fuga" no seletor (só nesse caso).

### Antes e depois

Índice, com o tempo até morrer e a referência. O tempo da build quase não muda: ela raramente chega a recuar, porque o
atacante imortal fica com 1 de Vida e o Tático só recua de quem está à frente na luta. Quem muda é a referência.

| build | cenário | antes | depois |
|---|---|---|---|
| Big Carry v1 | Atirador alimentado | 722 (20,1 s; ref. 2,8 s) | **536** (20,1 s; ref. 3,7 s) |
| Big Carry v1 | Anti-tanque físico | 360 (66,9 s; ref. 18,6 s) | **783** (66,9 s; ref. 8,5 s) |
| Big Carry v1 | Verdadeiro | 956 (ref. 25,4 s) | **1.967** (ref. 12,4 s) |
| Soldado v8 | Atirador alimentado | 141 (29,2 s; ref. 20,8 s) | **423** (17,9 s; ref. 4,2 s) |
| Soldado v8 | Anti-tanque físico | 238 (44,2 s) | **518** (44,2 s; ref. 8,5 s) |
| Soldado v8 | Liandry + Maldição | 315 | **617** (51,7 s; ref. 8,4 s) |
| Manopla dos Glacinatas | Atirador alimentado | 153 | **755** (31,8 s; ref. 4,2 s) |
| Manopla dos Glacinatas | Anti-tanque físico | 249 | **556** (47,5 s; ref. 8,5 s) |
| Manopla dos Glacinatas | Verdadeiro | 114 | **243** (32,0 s; ref. 13,2 s) |

**Ordem contra o Atirador alimentado:** antes, Big Carry 722 > Manopla 153 > Soldado 141, com as referências de cada uma. Agora
Manopla 755 > Big Carry 536 > Soldado 423, a mesma ordem dos tempos até morrer (31,8 / 20,1 / 17,9 s).

Soldado v8 contra o Atirador caiu de 29,2 s para 17,9 s: os 29,2 s já incluíam a caminhada até a parede.

**Testes novos (`EffectReferenceTest`, catálogo do seed num banco novo):** 🎯 a mesma build aos 19:00 e aos 26:00 (nível 15) tem
a mesma referência, com a mesma Vida e o mesmo dano recebido; o atacante não muda com o minuto, a menos que o cenário escale
(120 de DdA aos 10:00, 300 aos 25:00); o Ornn sem itens morre onde a luta acontece.

Testes: 217 → 220 (10 opcionais pulados). Golden sem mudança. `npm run build` limpo.

## Parte 2 — Índice que não satura ✅

### Taxa líquida

**Taxa líquida** = (dano recebido depois das mitigações − cura − escudo absorvido) ÷ segundos da luta. A cura que encontra a Vida
cheia entra na conta (`FighterStats.healOver`, campo novo e descritivo, fora da impressão digital do golden): é ela que dá
a "sobra por segundo" de quem sustenta.

- **Índice** = taxa da referência ÷ taxa da build × 100, com **teto de 1000**, configurável em `indice.teto`, para todo índice
  (marcado com "+"). Se a taxa for ≤ 0, a tela mostra **"sustenta (+x/s)"** e entra como o teto no ponderado.
- **Tempo até morrer:** o real, com "> 180 s" quando não morre. A estimativa de 900 s saiu.
- **`durability`** (Vida ÷ taxa líquida, no máximo 5× a duração) é o número que as leituras por item e o valor medido das
  passivas comparam, porque elas precisam de um valor também quando ninguém morre.
- **Comparativo:** a tela recalcula pela taxa da referência aberta (`overReference`).

### ⚠ Desvio do prompt: a taxa do índice é em % da Vida

O prompt pede `taxa da referência ÷ taxa da build`. Com a taxa em dano por segundo, **a Vida não conta**: um item só de Vida
não muda a taxa, e o índice dele seria 100. O resultado contradiz o tempo até morrer, contra o Atirador alimentado:

| build | tempo até morrer | Vida | taxa líquida | índice em dano/s | índice em % da Vida/s |
|---|---|---|---|---|---|
| Manopla dos Glacinatas | 31,8 s | 4.640 | 146/s | 522 | 755 |
| Big Carry v1 | 20,1 s | 7.326 | 374/s | 193 | 536 |
| Soldado v8 | 17,9 s | 5.457 | 335/s | 228 | 423 |

Em dano/s, o Soldado passa da Big Carry mesmo morrendo antes. É o problema "quem vive mais tem índice menor" que a 6.a já tinha
corrigido no comparativo. Por isso o padrão é `indice.taxa: por_vida`: a taxa em % da Vida máxima de cada um por segundo. Numa
luta até a morte, o índice fica exatamente tempo ÷ tempo da referência; sem morte, vale a taxa medida. Para seguir o prompt ao pé
da letra, basta `taxa: bruta` no YAML. A coluna "índice bruto" do `EffectReportTest` mostra os dois.

### Calibragem dos magos: nada a mudar

**Conta:** o preset de 600 de PdH faz, por segundo, Combo (350 + 0,9 × 600) ÷ 5 s = 178 mais Toque (120 + 0,4 × 600) ÷ 2 s = 180,
ou seja 358 de dano bruto. Com os 40% de penetração do Cajado contra 68 de RM, o multiplicador é 100 ÷ 141 = 0,71, o que dá 254/s
depois da RM.

Medido, sem itens de RM, em builds de fim de jogo:

| cenário | Manopla dos Glacinatas | tanque de teste* |
|---|---|---|
| Tormento de Liandry | 19,5 s | 11,2 s |
| Cajado do Vazio | 22,1 s | 15,2 s |
| 1000 de PdH + Cajado | 15,5 s | 11,2 s |

\* Botas Galvanizadas de Aço, Coração de Aço, Égide de Fogo Solar, Armadura de Espinhos, Presságio de Randuin e Coração Congelado
(`EffectReferenceTest`).

Todos abaixo dos 60 s pedidos, então os pulsos **não mudaram**. A Big Carry não morre contra eles porque tem RM de sobra
(Desespero Eterno, Esmagadores, Duplaguarda) e cura 128/s. Agora isso aparece como o que é: "sustenta".

### Antes e depois

| cenário | Big Carry v1 | Soldado v8 | Manopla dos Glacinatas |
|---|---|---|---|
| Crítico | 2.163 → **1000+** | 949 | 2.843 → **1000+** |
| Anti-tanque físico | 783 | 518 | 556 |
| Tormento de Liandry | 9.595 → **sustenta (+14/s)** | 844 | 188 |
| Liandry + Maldição | 8.735 → **1000+** (12/s) | 617 | 185 |
| Cajado do Vazio | 8.671 → **sustenta (+35/s)** | 1.292 → **1000+** | 213 |
| 1000 de PdH + Cajado | 16.729 → **sustenta (+2/s)** | 883 | 243 |
| Misto | 5.984 → **sustenta (+41/s)** | 5.792 → **sustenta (+4/s)** | 713 |
| Verdadeiro | 1.967 → **1000+** (30/s) | 626 | 243 |
| Atirador alimentado | 536 | 423 | 755 |
| Corta Cura (do usuário) | 1.311 → **1000+** | 425 | 181 |
| **ponderado** | 5.647 → **932** | 1.237 → **729** | 612 → **428** |

🎯 Nenhum índice do Básico da Big Carry passa do teto (o `EffectReportTest` confere).

**A ordem faz sentido:** Big Carry (932) cobre físico e mágico e só cai contra quem mata de verdade: Atirador 536, Anti-tanque 783.
Soldado (729) é médio em tudo, sem um cenário muito ruim. Manopla (428) é a melhor de longe contra físico puro (Atirador 755, a
mais alta dos três) e a pior contra mágico (185 a 243), porque não tem RM. O ponderado fica assim porque 5 dos 10 cenários são
mágicos.

**Efeito colateral bom:** sem a fuga da Parte 1, a contradição da 6.a sumiu. Sem o Coração de Aço, a Big Carry dura **menos**
contra o Atirador alimentado (+86% com o item, antes −15%).

**Testes novos:** `theNetRateNeedsNoDeath` (`EffectTestsTest`); 🎯 `magesKillATankWithoutMagicResist` e
`theIndexOfAFightToTheDeathIsTheTimeRatio` (`EffectReferenceTest`).

Testes: 220 → 223 (10 opcionais pulados). Golden sem mudança. `npm run build` limpo.
