# Rodada 10 — Simulação mais fiel: mapa pelos prints, IA que joga em dois tempos e Arena mais legível

Prompt: `prompt-rodada-10-simulacao.md`, depois das Rodadas 8 e 9. Uma parte por commit, sem push, no `main`.

Prints usados (fora do git, em `capturas/7.3d/`):
- `mapa/minimapa-base.png`: o minimapa base do usuário ("Mapa wild rift.png", 05/11/2026), sem moitas nem plantas;
- `dragoes/almas.png`: os três painéis "Mata-Dragões" ("Almas dos dragões wild rift.png").

O segundo print do mapa (moitas e plantas) e os status sob a Mão do Barão ainda não chegaram.

## Parte 1 — Mapa retraçado pelos prints ✅

### Escala e calibragem
- O print tem o mapa em cerca de **298 px** de lado (de 20 a 318 px na horizontal, de 8 a 305 px na vertical).
- **Centro:** o ponto médio entre os dois Nexos do print, (48, 274) e (285, 38) px, dá (166,5; 156) px.
- **Escala: 40 unidades da Arena por pixel.** O mapa segue com 12.000 unidades de lado, o tamanho de antes. Esse tamanho
  também é estimativa: nenhum print traz uma distância em unidades do jogo.
- **Conferência com o alcance da torre:** sem print do círculo de alcance, não deu para calibrar por ele. A conferência
  feita foi a de dentro do próprio print: com 40 un/px, os acampamentos e o fosso do Dragão caem a menos de 600 unidades
  das posições do esquema antigo, que vinham do LoL de PC na escala 0,807. As duas fontes concordam. **Quando vier um
  print com o alcance de uma torre desenhado, basta trocar `escala.unidades_por_pixel`** e rodar a ferramenta de novo.
- Conversão: `x = 6000 + (px − 166,5) × 40`, `y = 6000 − (py − 156) × 40` (o print tem y para baixo).

### O que mudou na geometria
- **Paredes:** saíram os 18 blocos do esquema. Entrou uma **grade de 60 × 60 células de 200 unidades** (`paredes_grade`),
  traçada por `tools/mapa_minimapa.py`:
  - uma célula é parede quando 40% ou mais dos pixels dela são escuros (os blocos pretos do minimapa);
  - parede de uma célula só, sem vizinha, sai como ruído (contorno de ícone);
  - **o lado azul vale e o vermelho é o espelho**, como no resto do mapa. Na diagonal, as duas células do par só são
    parede se as duas forem. O espelho bate com o lado vermelho do print, conferido por sobreposição;
  - a base azul (o arco claro), as **rotas** e os acampamentos ficam sempre livres. Os ícones de torre e de tropa por cima
    das rotas viravam parede falsa.
  - O `RiftMap` junta as células de cada linha e as linhas iguais em retângulos (cerca de 200 polígonos), e a tela
    desenha os mesmos polígonos de antes.
- **Rotas** pelo meio das faixas do print: Barão em x = 800, subindo, e no alto em y = 11.200; Dragão em y = 800 e na
  direita em x = 11.200; o meio pelas torres, cruzando o centro.
- **Torres** pelos ícones do print, levadas ao meio da rota. Externa, interna e da base: Barão (800, 8.800), (800, 5.200)
  e (850, 2.800); meio (4.620, 4.750), (3.400, 3.300) e (2.340, 2.200); Dragão (8.800, 800), (5.200, 800) e (2.800, 850).
- **Nexo** em (1.260, 1.280). **Fosso do Dragão** pelo ícone do print, em (8.060, 3.360); o do Barão é o espelho, em (3.940, 8.640).
- **Acampamentos** pelos números e ampulhetas do print: Azul (2.900, 6.160), Gromp (1.620, 6.840), Lobos (2.540, 4.640),
  Aves (5.620, 3.720), Vermelho (6.260, 2.440) e Krugs (7.820, 1.840).
- **Erro de antes, corrigido:** refletido pelo centro, o lado da rota do Barão de um time cai onde fica a rota do Dragão
  do outro. As torres vermelhas ficavam com a rota trocada: a "externa do Barão" vermelha estava na direita do mapa. Agora
  as torres trocam de rota no espelho (`RiftMap.mirrorLane`).
- **Outro erro de antes:** `RiftMap.clear` amostrava a linha a cada 50 unidades e podia pular o canto de uma parede. Com
  as paredes do print, um campeão ficava preso no canto. Agora a linha visita cada célula que cruza.
- **Moitas, plantas e Aronguejos** seguem como esquema, só tirados de dentro das paredes novas, até o segundo print.

### Rotas conferidas
`RiftMapTest.everyLanePitCampPlantAndTowerOfBothSidesIsReachableOnFoot`: a partir da fonte azul, a pé, chega a cada
ponto de cada rota, às 18 torres, aos 12 acampamentos, aos Aronguejos, às plantas, aos dois fossos e ao Nexo vermelho.
`lanesAreFreeOfWalls` confere cada trecho de rota em linha reta, agora com a travessia exata.

### Ondas, selva e Aronguejo (dados do usuário, 05/11/2026)
- **1ª onda aos 0:25 e uma a cada 25 s**, a partida inteira. A janela de 25 s da RPN saiu.
- **Selva aos 0:46:** a 1ª onda do meio anda 6.703 unidades até o centro, a 325/s, ou seja 20,6 s, e chega aos 45,6 s.
  **Vermelho e Azul aos 0:47** (`buffs_depois: 1`). O teste refaz a conta pelo mapa.
- **Aronguejo aos 1:30.**

### Torres
- **Alcance desenhado:** a forma da torre leva `reach`. A tela da Arena desenha o círculo ao passar o mouse ou tocar, e
  sempre quando um campeão está dentro (com um fundo vermelho leve). As placas restantes aparecem sob a barra de vida.
- **Torre destruída:**
  - sai como alvo e o **escombro** fica desenhado, na Arena e na reprodução da partida;
  - **o ouro vai ao time:** o global a todos, o local a quem deu o último golpe. Isso já era assim na Arena e na partida;
  - a próxima torre da rota vira a da frente e as tropas avançam até ela, como a partida já fazia, agora com a destruída
    de fato;
  - **regra das placas:** quando cai a primeira torre da partida, as placas das outras torres caem junto, sem ouro
    (`placas.caem_com_a_primeira_torre`). **Não achei fonte escrita** dessa regra no Wild Rift: está como estimativa, na
    lista "Para o usuário".
- **Dano, vida e resistências:** sem print novo, seguem os da Rodada 6.b. **Defesa do início:** já modelada (+50 nos 2
  primeiros minutos, meio e Barão).

### Dragões (prints do usuário)
- **Só 3 elementos:** Oceano, Montanha e Infernal. Gelo saiu do `objetivos_7_3d.yml`, do seletor de Objetivos e da alma.
- **Por dragão abatido, acumula:** Oceano regenera 2,8% da Vida perdida a cada 5 s; Montanha dá +4 e mais 3% do total de
  Armadura e RM; Infernal dá 4% de DdA e PdH.
- **Montanha, como pedido:** `valor: 4` mais uma linha `ratio: 0.03` com `ref` e `escopo: total`.
  - **Ordem escolhida:** o +4 entra primeiro, e os 3% leem o total depois de todas as outras fontes, incluindo esse +4.
    É a ordem do motor para "% do total": a Manto da Aurora e a Rabadon leem o total depois das fontes fixas.
  - 🎯 Com 100 de Armadura, 1 dragão dá **100 + 4 + 3% de 104 = 107,12**. 2 dragões dão 100 + 8 + 6% de 108 = 114,48.
  - **Forja Viva:** os dragões entram depois da ficha, como buff de objetivo. A Forja não os amplifica, e a % lê o total
    já com a Forja, a mesma regra da Manto da Aurora.
  - O caminho genérico é `RiftRules.dragonDelta`, com o espelho `dragonDelta` em `ObjectivesPicker.jsx`. A Arena e a
    ficha do planejador usam o mesmo cálculo.
- **Na ficha:** o seletor "Objetivos" mostra os 3 elementos. O relatório de relevância separa cada fonte, por exemplo
  "Montanha ×2: 1.234 · Mão do Barão: 2.345".
- **Alma:** o 1º dragão abatido define o elemento, e a alma vai ao time que fizer 3 dragões.
  - Na Arena, a Infernal agora existe: uma explosão de raio 250 ao causar dano a um campeão, com 5 s de recarga. Antes
    ela não tinha efeito.
  - A do Oceano agora dispara ao **causar** dano a um campeão (antes era ao sofrer) e cura ao longo de 4 s.
  - A da Montanha conta só o dano de campeões e torres.
  - As almas e o Oceano agem também nos cenários sem mapa que têm objetivos.
  - Os números do print (518 de cura e 228 de dano) fecham com as fórmulas, por exemplo com 320 de PdH e 1.880 de Vida
    adicional, sem DdA adicional.
- **Ordem dos dragões:**
  - a partida sorteia a ordem dos 3 pela semente (`RiftRules.dragonOrder`), e o log diz "pegou o Dragão do Oceano" e
    "pegou a Alma do Oceano";
  - o cenário com mapa aceita `rift.dragons`, a ordem escolhida, ou `rift.dragonSeed`, a ordem sorteada;
  - o `rift.dragon` dos cenários salvos vira o primeiro da ordem.
- **Mão do Barão:** sem o print, segue a estimativa. **Barricadas:** nada novo do usuário.

### Números
- Testes: 368 → 376 (16 opcionais pulados), 0 falhas. Os golden não mudaram: a "Rota única" não usa o mapa.
- `npm run build` limpo.
- Partida de base, antes da Parte 2 (`MatchReportTest`, 3 sementes, com o mapa novo):

| semente | vencedor | duração | ouro/min médio | XP/min médio | mortes |
|---|---|---|---|---|---|
| 1 | vermelho | 15:24 | 537 | 356 | 30 |
| 2 | vermelho | 15:13 | 526 | 346 | 32 |
| 3 | azul | 15:45 | 511 | 353 | 31 |

## Parte 2 — IA que joga em dois tempos ✅

### Na macro da partida
- **`arena/rift/TwoTempo`** mede cada opção em ouro, sobre o mesmo horizonte (`horizonte_s: 40`). A macro só pensa em
  sair quando está ferida (abaixo de 80% da Vida) ou com ouro para o próximo item.
  - **troca contínua:** ganha a rota (ouro e XP das tropas, pela parte de últimos golpes da função). O risco de morrer
    vem da previsão da troca: o campeão inimigo mais forte por perto, pela mesma força das lutas, tira
    `dano_troca_por_s` × (força dele / sua) por segundo. A morte custa `custo_morte` mais a rota perdida enquanto
    espera para renascer;
  - **recuar e recuperar:** anda até o Frutomel mais próximo que esteja de pé (até 3.000), quebra e come os frutos, e
    volta. A partida agora tem os Frutomel (de pé aos 6:00, renascem em 6:00) e a regeneração fora da troca
    (`regen_por_s`);
  - **dar base e voltar a pé:** canal de 8 s, interrompido por luta, compra e volta andando;
  - **dar base e voltar de Teleporte:** se a build leva Teleporte e ele está pronto (150 s de recarga). Até 6:00 vai
    para a torre da frente da rota; depois, até um aliado fora da base (a regra do feitiço em `feiticos_7_3d.yml`);
  - **comprar sem voltar à base:** campo de ficha `compra_fora_da_base: true`, e `compra_para_aliados: true` para
    quem vende aos aliados num raio de 1.500. Nenhuma ficha usa ainda; o campo e o teste existem.
- **Custo e ganho:**
  - ouro e XP perdidos fora da rota, com a negação de XP (`negacao_xp`);
  - as placas em risco, quando há inimigo por perto;
  - o tempo até voltar;
  - a Vida recuperada (`ouro_por_vida`, além do risco);
  - o item comprado: metade do ganho dele (`peso_item`). O ganho vem de `World.itemGain`, que hoje é o custo dos itens
    que o ouro paga. **Desvio do pedido:** o comparador rápido roda uma linha do tempo por candidato e custaria tempo
    demais por decisão. O gancho existe para trocá-lo;
  - o risco de morrer se ficar.
- **Pesos** em `macro_7_3d.yml` (`dois_tempos` e as linhas abaixo dele), todos EST, para calibrar. `ligado: false`
  volta aos limiares antigos.
- **Calibragem feita:** com 0,4 de ouro por ponto de Vida, a Vida valia demais e a macro ia ao Frutomel até sem
  inimigo por perto. Com 0,1, a Vida quase só vale pelo risco de morrer.
- **No log da partida** (exemplos da semente 3):
  - "612 Renekton recuou para o Frutomel: deve ganhar 238 de vida e perder 4 tropas (ficar: −424; recuou: 432 de ouro
    em 40 s)", e depois "619 Renekton comeu o Frutomel: +142 de vida";
  - "300 Ornn voltou à base para voltar de Teleporte: deve ganhar 558 de vida e perder 0 tropas (ficar: 458; voltou:
    739 de ouro em 40 s)", e depois "311 Ornn voltou à rota de Teleporte";
  - "174 Darius voltou à base a pé: deve ganhar 353 de vida e perder 5 tropas (ficar: 876; voltou: 1197 de ouro em
    40 s)": aqui pesou o item.
- 🎯 `TwoTempoTest`:
  - com 35% de Vida e a troca tirando 2% por segundo, a vida acaba antes do horizonte, e a macro **recua para o
    Frutomel** (ficar −400, Frutomel acima de 400);
  - com a mesma rota sem inimigo pressionando, **fica**;
  - quase morto e sem Frutomel, volta a pé, ou de Teleporte se ele estiver pronto;
  - com a ficha que compra fora da base, compra onde está.

### Na Arena com mapa
- **Duas ações novas** no `ActionSpace`, anexadas ao fim (36 → 38):
  - **36 "recuar para recuperar":** anda até o Frutomel de pé mais próximo (até 2.500) e o quebra; sem Frutomel,
    fica atrás da torre aliada mais próxima;
  - **37 "voltar à base":** canal de 8 s (`fonte_base.volta_s`), que qualquer golpe interrompe. No fim, o campeão
    está na fonte.
- **Máscara:** as duas só valem com o mapa inteiro. "Recuperar" vale abaixo de 90% da Vida, com um Frutomel ou uma
  torre aliada; "voltar" vale abaixo de 95%, fora da fonte e sem campeão inimigo a 900. **Na Rota única nada muda.**
- **O Tático** volta à base abaixo de 30% sem inimigo a 1.300, e recupera no lugar de só recuar quando perde a troca
  abaixo de 25%.
- **A IA treinada:** as redes salvas com 36 ações migram como antes (`PolicyRepository.OLD_ACTIONS`), com as duas ações
  novas em −5, o que dá o mesmo comportamento de antes. O treino num cenário com mapa aprende quando usá-las.
- **Cenário-modelo novo:** "Troca na rota com Frutomel" (`x1-frutomel`): x1 no meio aos 7:00, plantas ligadas, o
  Frutomel no rio e a base a uma distância real.
- **Golden:** não mudou. A Rota única não tem as ações, e o golden das IAs não mudou com a camada de saída maior.
- Testes: 376 → 382 (16 opcionais pulados), 0 falhas. `npm run build` limpo.

### A partida antes e depois (`MatchReportTest`, as mesmas 3 sementes e builds)

| semente | ouro/min médio | XP/min médio | mortes | decisões de dois tempos |
|---|---|---|---|---|
| 1 | 537 → **557** | 356 → **387** | 30 → 29 | 31 voltas a pé, 2 de Teleporte |
| 2 | 526 → **551** | 346 → **361** | 32 → 39 | 26 a pé, 3 de Teleporte |
| 3 | 511 → **541** | 353 → **364** | 31 → 37 | 32 a pé, 3 de Teleporte, 2 Frutomel |

- Ouro e XP sobem de 4% a 9%: menos tempo morto na base, a regeneração fora da troca e o Teleporte.
- As mortes sobem em duas sementes: ficar na rota mais tempo dá mais lutas. O vencedor muda nas sementes 2 e 3.
- A distância para as builds continua: a partida rende uns 550 de ouro por minuto, e as builds supõem de 700 a 900.
