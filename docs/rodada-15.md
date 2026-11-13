# Rodada 15: a corrida das barras de vida e uma descrição para cada teste

Levantado depois da Rodada 14 (12/11/2026), a partir de uma pergunta sobre a Armadura de Espinhos e o Colete Espinhoso
no Ornn.

## O que estava errado

| Sintoma | Causa |
|---|---|
| Armadura de Espinhos e Colete Espinhoso nunca entram numa build padrão | A modelagem está certa: Feridas Dolorosas ao causar dano (ataque ou habilidade) e ao ser atacado, e o reflexo a cada ataque recebido. Mas nenhuma nota dava valor a isso. Nos testes de resistência os atacantes não morrem, então cortar a cura deles e devolver dano não encurta a luta. Os duelos, onde pesaria, estão com peso 0. |
| Atiradores medidos sem o risco de morrer | A nota deles era só a ofensiva, contra bonecos que não revidam. Nos testes de resistência eles morrem rápido, mas o dano que causaram até ali não contava. |
| Testes sem explicação | Só alguns cenários tinham `nota`, e o site não mostrava nenhuma. |

O jogo é sobre a barra inimiga zerar antes da sua, também para tanques. Esta rodada mede isso sem descartar o que
existe.

## Partes

1. ✅ **A corrida** (`EffectTestService.race`, `Measures.enemyLost`, `teamLost`, `race`). Em cada luta, compara duas
   coisas:
   - a parte da barra inimiga que o lado da build tirou: dano e dano devolvido, menos a cura e os escudos que eles
     receberam. Assim a cura cortada pelas Feridas conta, e quem morre conta inteiro;
   - a parte da barra do próprio lado que ela perdeu.

   A conta é `corrida = 100 x inimiga / (inimiga + minha)`: 50 é empate e 100 é vencer sem perder Vida. Nos testes de
   resistência, onde os atacantes não morrem, a corrida é uma projeção. A barra inimiga pode passar de 1, e isso
   significa que eles teriam morrido antes.
2. ✅ **Lutas em que os inimigos morrem**: grupo `corrida` em `efetividade_7_3d.yml`, com `mortais: true` (os atacantes
   morrem e a luta acaba quando um lado zera) e `escala_com_minuto`. Os cenários:
   - um atirador sozinho, só batendo;
   - um lutador que se cura;
   - um atirador com um suporte de proteção;
   - a build com um suporte aliado contra um mergulhador e um atirador.

   O relatório é `corrida`.
3. ✅ **A nota**: parte nova `corrida` em `BuildScore`, na mesma escala das outras. Os pontos são a parte da distância
   entre a corrida do campeão sem itens e 100 que a build fecha (`racePoints`). As leituras vêm do relatório
   `corrida`; as leituras projetadas das lutas da defensiva saíram na parte 5. Pesos por classe em
   `nota_builds_7_3d.yml`:

   | Classe | Antes | Agora |
   |---|---|---|
   | Tanque | def 0,70 / util 0,20 / ofen 0,10 | def 0,55 / util 0,15 / ofen 0,10 / corrida 0,20 |
   | Lutador | def 0,45 / ofen 0,45 / util 0,10 | def 0,30 / ofen 0,30 / util 0,10 / corrida 0,30 |
   | Atirador, Mago, Assassino | só ofensiva | ofen 0,70 / corrida 0,30 |
   | Controlador | def 0,30 / util 0,55 / ofen 0,15 | def 0,25 / util 0,50 / ofen 0,10 / corrida 0,15 |
   | Especialista | def 0,50 / ofen 0,50 | def 0,35 / ofen 0,35 / corrida 0,30 |

4. ✅ **Uma descrição para cada teste**: `nota` em todos os cenários de efetividade e nos alvos ofensivos, no
   `Outcome.testNote` e no `OffenseModel.Outcome.targetNote`.
   - A tela de Efetividade mostra a descrição sob o nome e ganhou a coluna Corrida (a da build e a do campeão sem
     itens).
   - O site mostra a descrição de cada teste e a corrida ao lado do índice, mais a seção "Corrida das barras de vida",
     com as lutas em que os inimigos morrem (`tools/site_estatico.py` calcula o relatório `corrida` em cada
     minuto-chave).

## Medidas

**Ornn aos 20 min**, Coração Congelado contra Armadura de Espinhos no mesmo lugar:

| Teste | Coração: índice / corrida | Espinhos: índice / corrida |
|---|---|---|
| Colossos (não morrem) | 114,4 / 99,6 | 110,0 / 97,8 |
| Crítico (não morre) | 95,8 / 86,3 | 90,6 / 75,9 |
| Corrida contra um atirador | 101,3 / 97,8 | 88,6 / 87,1 |
| Corrida contra um lutador que se cura | 104,4 / 98,8 | 99,8 / 96,2 |

- Nos Colossos, a Armadura de Espinhos tira mais barra inimiga: 4,07 contra 3,64, com 2.224 de cura cortada e 7.114 de
  dano devolvido.
- Mas o Ornn perde mais Vida com ela. A lentidão de ataque do Coração Congelado vale mais contra atiradores e
  lutadores de ataque básico.
- A corrida passou a enxergar os espinhos, e o Coração continua à frente nesses testes.

**Kalista aos 20 min:**

| Teste | Crítico | Off-tank |
|---|---|---|
| Atirador sozinho | 55,8 | 69,0 |
| Atirador com suporte | 35,2 | 52,9 |
| Com um suporte aliado | 52,5 | 75,4 |

O atirador de crítico é vulnerável sozinho e volta ao empate com o suporte ao lado.

## Parte 5: imortais inalvejáveis, a corrida pelo ritmo do fim e os finalizadores

A primeira campanha com a corrida mostrou o problema da Vida inflada. Os atacantes imortais tinham entre 6.000 e 9.000
de Vida, e por isso o que depende da Vida máxima do alvo valia cerca de 3 vezes mais: o Ruptor Divino cura 6% dela a
cada acerto. As 10 melhores builds do Ornn levavam Ruptor Divino e itens de velocidade de ataque, e o Renekton chegava a
1.380 de índice contra artilharia. Decisões do usuário (13/11/2026):

1. ✅ **Os imortais ficam inalvejáveis** (`Dummy.untargetable`, `untargetableUntil` infinito): ninguém os ataca,
   conjura neles, controla ou dispara efeitos ao acertar. Medem só quanto a build aguenta. Ficaram assim os 1x1 do
   Básico, o "5 contra 1" e os cenários do usuário.
   - O resto passou a ter atacantes que morrem e que crescem com o minuto, aliados também: a luta de equipe, as
     ameaças e os de suporte.
   - A Vida dos atacantes é a de um campeão do minuto 25, 55% da antiga.
   - A utilidade (controle aplicado e dano absorvido) passou a vir das lutas da corrida.
   - O passo `ameacas` da campanha compara pela corrida.
2. ✅ **A corrida pelo ritmo do fim**: não importa quanta Vida sobrou, e sim se o lado iria zerar. O tempo até cada lado
   zerar é a hora da morte ou, para quem está vivo, a Vida no fim sobre o ritmo em que caía na última janela da luta.
   - A janela é um terço da luta, de 4 a 15 s. A Vida é registrada a cada segundo (`Arena.Input.healthTrail`,
     `FighterStats.healthTrail`, desligado fora da efetividade).
   - O ritmo é a diferença entre as médias das duas metades da janela. Quem oscila entre 1 e 400 de Vida até o fim
     (sustain) segura e nunca zera.
   - `corrida = 100 x meu tempo / (meu + o deles)`.
3. ✅ **Finalizadores** (relatório `corrida`):
   - crítico com execução: A Coletora, Lembranças do Lorde Dominik, Gume do Infinito e Colhedor de Essência;
   - mago explosivo com o Orbe do Infinito;
   - queimadura de Dano Verdadeiro que cresce com a Vida que falta e executa abaixo de 12%.

   Dois itens passaram a ser modelados de verdade: A Coletora (`executa`, abaixo de 5% da Vida) e o Orbe do Infinito
   (`amplifica` com `alvo_abaixo: 0.40`). Os pulsos dos bonecos ganharam `vida_perdida_alvo` e `executa_abaixo`.

**Ornn aos 20 min**, depois desta parte:

| Teste | Tanque | Ruptor Divino |
|---|---|---|
| Crítico (resistência) | 89,4 | 71,5 |
| Misto (resistência) | 90,5 | 75,0 |
| Média da corrida | parecida | parecida |

**Kalista de crítico aos 20 min**, corrida:

| Teste | Kalista de crítico | Com Anjo Guardião |
|---|---|---|
| Crítico com execução | 35,7 | 46,0 |
| Atirador com suporte | 36,8 | 48,6 |

Golden inalterado. Cada parte foi conferida nos testes (`LifebarRaceTest`).

## Parte 6: os imortais valem menos e lutam menos

Decisão do usuário (13/11/2026): muitos itens e passivas só se pagam interagindo com outros campeões, o que os testes
contra imortais inalvejáveis não medem.

- ✅ **Pesos** (`nota_builds_7_3d.yml`):

  | Classe | Defensiva | Utilidade | Ofensiva | Corrida |
  |---|---|---|---|---|
  | Tanque | 0,35 | 0,15 | 0,10 | 0,40 |
  | Lutador | 0,20 | 0,10 | 0,30 | 0,40 |
  | Atirador, Mago, Assassino | — | — | 0,55 | 0,45 |
  | Controlador | 0,15 | 0,50 | 0,10 | 0,25 |
  | Especialista | 0,25 | — | 0,30 | 0,45 |

- ✅ **Lutas mais curtas**: as lutas contra imortais duram 60 s (eram 180 nos 1x1 e 120 no "5 contra 1"). O que dura
  mais que isso entra pela projeção da taxa líquida.
- ✅ **Dois cenários de alto DPS pela Vida máxima**, que faltavam no Básico e no Completo:
  - "Verdadeiro pela Vida máxima": um terceiro golpe como as Flechas de Prata;
  - "Misto pela Vida máxima": Espada do Rei Destruído, Tormento de Liandry e golpe mágico pela Vida máxima.

  Os pulsos ganharam `vida_max_alvo`.

