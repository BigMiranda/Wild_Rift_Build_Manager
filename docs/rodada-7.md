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
