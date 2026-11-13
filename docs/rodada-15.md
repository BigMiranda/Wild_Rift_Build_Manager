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
   entre a corrida do campeão sem itens e 100 que a build fecha (`racePoints`). As leituras vêm das lutas da defensiva,
   com peso `corrida.peso_projetada` 0,5, e do relatório `corrida`, com peso 1. Pesos por classe em
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

Cada parte foi conferida nos testes (`LifebarRaceTest`). A campanha roda de novo antes de publicar.
