# Rodada 12: o mapa pela tela "Guia do Rift" do jogo

Coleta feita em **07/11/2026**, no celular do usuário, pela tela **Guia do Rift**. Para abrir essa tela, toque no botão "i" da seleção de modo. Os prints e as fichas foram tirados por ADB (`screencap`, toques e arrastes só nessa tela) e estão em
`capturas/7.3d/guia-rift/`, fora do git. Esta coleta não altera o princípio do projeto: a ferramenta continua sem tocar no jogo. O ADB
serviu apenas para tirar os prints, como já se fazia com os da loja.

## O que a tela tem

- **Mapa do Rift**: o mapa inteiro numa vista 3D inclinada, com 2756 × 1268 px no celular. É bem mais nítido que o minimapa:
  mostra as moitas, as plantas, as torres e as bordas das paredes. A barra vertical ao lado do mapa controla o **zoom**; os prints
  foram tirados com o zoom no mínimo. Os quatro ícones da direita trocam o mapa: Rift normal, Infernal, Montanha e Oceano.
  - `normal_nov.png` / `normal_tudo.png`: o Rift normal nas abas "Novidades" (um balão por cima) e "Tudo" (os
    hexágonos dos marcadores);
  - `infernal_*`, `montanha_*`, `oceano_*`: as variantes. A legenda de cada uma, transcrita:
    - Infernal: os Dragões Infernais destroem o terreno em volta do Rubrivira, do Azuporã e do Covil do Dragão; surgem
      novos Pinstouros;
    - Montanha: os Dragões da Montanha criam terreno pelo Rift, na selva e perto dos covis do Dragão e do Barão;
    - Oceano: os Dragões do Oceano criam moitas em lugares cruciais; surgem novas plantas de Frutomel pelo mapa.
- **Fichas**: 24 fichas, percorridas pelas setas (`fichas/`). A tabela abaixo reúne os números delas.
- Abas "Rotas" e "Ritmo da partida": não foram abertas nesta rodada.

## Leitor: `tools/mapa_guia.py`

```bash
python tools/mapa_guia.py capturas/7.3d/guia-rift/mapa/normal_nov.png capturas/7.3d/guia-rift/mapa/normal_tudo.png --png moitas.png
```

1. **Homografia.** O print é endireitado para o referencial do `mapa_7_3d.yml` (12.000 unidades). A conta usa dez torres
   (as de base e as externas e internas das rotas laterais), cinco pares que se espelham pelo centro:
   - a primeira homografia saiu das posições do YAML, estimadas no minimapa;
   - no mapa endireitado, cada torre foi relida pelo centro do círculo escuro da base;
   - cada par foi levado à média simétrica, e a homografia foi refeita. O erro restante é de 20 a 40 px no print, cerca de 150 a 250 unidades.
   - **Diferenças medidas:** no mapa endireitado, o Nexo azul fica perto de (1500, 1550), contra (1260, 1280) no YAML. As torres das rotas laterais ficam
     mais junto das paredes (x ≈ 600–750 em vez de 800). **Nada disso foi alterado**: as estruturas seguem as do
     minimapa, para não mexer nas rotas e nas partidas. Fica como passo seguinte.
2. **Moitas.**
   - **Por que sementes:** a cor sozinha (capim verde-azulado: verde bem acima do vermelho e perto do azul) também pega copa de
     árvore e chão de grama iluminado. Por isso a leitura parte de uma **semente por moita** (`SEMENTES`), tirada do
     mapa endireitado. Cada semente foi conferida pelo par espelhado do outro lado.
   - **Extensão:** a moita cresce a partir da semente pelas células de 100 unidades com 40% ou mais de capim, até 450
     unidades. A mancha é coberta por círculos de até 220 de raio, levados ao lado azul, e os repetidos saem.
   - **Moitas na sombra:** as que ficam na sombra (no rio, sob as copas) não chegam ao limiar de cor e ficam com um círculo de 200.
3. **Saída:** as linhas de `moitas:`. Foram 35 círculos do lado azul, e o RiftMap espelha cada um. Antes eram 8 círculos de esquema.

**Limites:**
- a moita da metade da rota do Dragão fica sob a legenda do print e vem do espelho, da rota do Barão;
- as **plantas** seguem como esquema. Elas aparecem no mapa, mas mudam de lugar a cada partida e por variante;
- as **paredes** seguiam as do minimapa nesta parte; a parte 3 as traçou de novo pelo guia.

## Números das fichas (Guia do Rift, 07/11/2026)

Na coluna "base": **=** quer dizer que bate com a base; **≠** que diverge (vale o guia); **novo** que é dado que a base não tem.

| ficha | dado | guia | base |
|---|---|---|---|
| Tropas | primeira onda / intervalo | 0:00 / 25 s | ≠ (0:25 / 25 s) |
| Tropas | velocidade | +25 de Velocidade de Movimento a partir de 8:00, sobe a cada 150 s até 18:00 | novo |
| Acuâmina, Grompe, Lobos, Krugue Ancião | surgem / voltam | 0:20 / 110 s | novo |
| Azuporã, Rubrivira | surgem / voltam / ativos | 0:20 / 150 s / até 6:00 | ≠ (surgem 0:47 na base; a volta, 2:30, bate) |
| Insígnia de Perspicácia (Azuporã) | efeito | 90 s; 15 de Aceleração de Habilidade; Mana ou Energia por segundo; passa para quem abate o portador | |
| Insígnia de Brasa (Rubrivira) | efeito | 90 s; corpo a corpo 20% de Lentidão por 3 s, à distância 6%; Dano Verdadeiro por segundo; passa para quem abate | |
| Aronguejo | surge / volta | 1:25 / 120 s; o primeiro par nos dois rios, depois em rio aleatório | ≠ (1:30) |
| Aronguejo | santuário | visão de 4,5 m no lado do rio; aliados ganham +40% de Velocidade de Movimento fora de combate por 2 s | novo |
| Arauto do Vale | surge / some | 6:00 (360 s), não volta; some sozinho se não for abatido em 690 s | |
| Dragão elemental | surge / volta / para | 6:00 / 4:15 (255 s) / 18:30; até 3 elementais por partida, sem repetir | |
| Dragão elemental | terreno | muda depois do 1º Dragão abatido, pelo atributo do próximo | |
| Matador de Dragões | | +4% de Dano de Ataque e Poder de Habilidade | |
| Alma Infernal | | explosão de 180 + 35% do Dano de Ataque adicional + 15% do Poder de Habilidade, recarga de 5 s | |
| Dragão Ancião | surge / volta | 19:00 / 4:30 (270 s); remove o elemental em campo | |
| Dragão Ancião | efeito | 120 s; 150 de Dano Verdadeiro por segundo por 3 s; executa campeão abaixo de 15% de Vida | |
| Barão Na'Shor | efeito | Mão do Barão por 120 s; Retorno de 3 s; fortalece as tropas próximas | |
| Portais | | surgem às 7:30; mudam de lugar; 2 cargas, uma a cada 10 s; o par fica parado 10 s depois de usado | novo |
| Zona de Vento | | 2:00 a 6:00, dos lados das torres da rota do meio; velocidade na direção do vento | novo |
| Frutomel | | volta como broto a cada 45 s e amadurece em 75 s (45 s na 1ª vez perto de torre); 3 frutos | |
| Pinstouro | | volta como semente a cada 240 s; abre em 20 s; empurra todas as unidades, menos armadilhas | |
| Florividente | | volta em 100 s; abre em 20 s; o pólen revela campeões por 3 s e o resto por 12 s | |
| Torres | barricadas | ao chegar a 90/75/55/30/0% de Vida, dão 120 de ouro aos aliados próximos | |
| Torres | ao cair a barricada | +20 de Armadura e RM (exterior: +30) acumulável por 30 s (exterior: 20 s), +5 por inimigo próximo | |
| Torres | dano extra | +20% de dano de campeões corpo a corpo | |
| Torres | cristalização | baixa nos primeiros 30 s; intermediária por 160 s; para com inimigo a até 850 | |
| Torre Exterior | | +25% de dano com 4 inimigos ao alcance, +50% com 5 (enquanto houver barricada) | |
| Torre Interior / do Inibidor | ouro | 25 de ouro a cada aliado ao destruir | |
| Torre do Inibidor | | ao cair, supertropas na rota; **volta depois de um tempo como inibidor, e então as supertropas param** | **≠** (a base diz que não há inibidores) |
| Fonte | | de 40% a 80% de Velocidade de Movimento entre a fonte e as torres do inibidor; +80% por 10 s até 0:30; +30% por 8 s ao usar o Retorno depois de 0:30 | novo |

## Parte 2: o que foi aplicado (`objetivos_7_3d.yml`)

Cada linha mudada cita "Guia do Rift (07/11/2026)" na `fonte`, junto com o valor anterior quando havia um.
`RiftTest.theRulesFollowTheInGameRiftGuide` confere os números.

| dado | antes | agora |
|---|---|---|
| Dragão elemental: volta / some | 270 s / 17:30 | 255 s / 18:30 (em luta: +20 s, como antes) |
| Dragão Ancião: volta | 360 s | 270 s |
| Mão do Barão: duração | 90 s | 120 s |
| Aronguejo: volta | 150 s | 120 s |
| Lobos, Aves, Krugs e Gromp: voltam | 135 s (EST) | 110 s |
| Frutomel / Pinstouro / Clarevidente: voltam | 360 / 360 / 330 s | 120 (45 + 75) / 260 (240 + 20) / 120 (100 + 20) s |
| Barricadas (as placas) | 5 em quintos iguais da Vida, 150 de ouro, +20 de Armadura e RM por 10 s | 5 em 90/75/55/30/0% da Vida (`limiares`), 120 de ouro, +30 por 20 s |
| Ouro global da torre interna e da base | 75 | 25 |
| Supertropas | da queda da torre da base até o fim da partida | da queda até o inibidor voltar (`inibidor_renasce`) |
| Bônus de velocidade das tropas | +10 aos 12:00 | +25 aos 8:00, a cada 150 s até 18:00 (só dado: o código não lê) |

**Inibidor:** `Arena` guarda quando a torre da base de cada rota caiu (`baseTowerDownAt`), e não mais só se caiu. As
supertropas do inimigo saem por `tropas.inibidor_renasce` segundos. A ficha diz apenas "depois de um tempo", por isso o tempo é **300 s,
o do LoL de PC (EST)**. O inibidor que volta não é uma unidade que se possa atacar de novo: fica fora do modelo.

**O que ficou de fora, e por quê:**
- **Primeira onda (guia 0:00, base 0:25), acampamentos (guia 0:20, base 0:46/0:47) e Aronguejo (guia 1:25, base
  1:30):** a base segue o que o usuário viu em partida (05/11/2026), que vale mais. Se o relógio do guia começasse 25 s
  depois do da partida, os acampamentos aos 0:20 + 25 = 0:45 bateriam com os 0:46 observados. Mas o Aronguejo e os
  objetivos não batem com esse atraso. Falta o usuário confirmar.
- **Arauto do Vale:** existe pelo guia (surge aos 6:00, some se não for abatido em 690 s, invocado arremete contra a torre mais próxima),
  mas falta modelar; `arauto.existe` segue `false`, com a nota.
- **Mecânicas novas** (Portais, Zona de Vento, velocidade da fonte, bônus das torres contra corpo a corpo e por inimigo
  perto, insígnias com o efeito à distância): só neste registro.
- **Nomes do jogo** (Azuporã, Rubrivira, Grande Lobo Trevoguari, Acuâmina Carmesim, Krugue Ancião, Grompe,
  Florividente): os YAML seguem com os nomes antigos, que o mapa e o código usam como chave.

## Parte 3: paredes, rotas e plantas pelo guia; o desenho do mapa

O usuário viu no mapa da Arena paredes faltando, paredes e moitas difíceis de distinguir, símbolos confusos (o
Frutomel) e o chão das rotas vazio.

**Paredes.** A grade de 200 do minimapa (`paredes_grade`) saía desalinhada do mapa do guia em quase toda a selva. Agora
`paredes:` tem **26 polígonos traçados à mão** sobre o mapa endireitado: as bordas, os muros da base, as cercas das
rotas, os anéis dos acampamentos, as ilhas e pedras do rio e o fosso do Dragão. Todos foram traçados na **metade de baixo** (y até ~6500), onde
o print é mais nítido porque está mais perto da câmera. O RiftMap espelha cada um pelo centro, e assim a metade de cima, borrada no print,
não precisa ser traçada. Para conferir: `python tools/mapa_guia.py <nov> <tudo> --paredes paredes.png` desenha as paredes
do seed por cima do mapa endireitado. Na vista 3D o topo das paredes sobe um pouco na tela, e por isso as paredes ficaram um
pouco mais grossas no sentido norte-sul.

**O que mudou junto**, porque o chão novo pedia:
- **rotas**: as laterais vão pelo meio do chão da rota no guia (x ≈ 920 na do Barão, y ≈ 950 na do Dragão) e fazem a
  curva da parede do canto. Antes passavam por (800, 10300) e (1700, 11200), que agora caem na parede;
- **fosso do Dragão**: (7950, 3750), raio 450, onde o dragão aparece no guia. Antes era (8060, 3360), raio 560, pelo
  minimapa. O do Barão é o espelho;
- **Espinhento Vermelho**: (6200, 2750), dentro do anel de pedra. **Gromp**: (1850, 6600), do lado da selva da cerca;
- **plantas**: o **Frutomel** nasce em quatro lugares, dois por lado. Um fica junto da parede das Aves, onde o guia põe o
  marcador; o outro fica na pedra do rio mais para o meio, abaixo da torre externa do meio, onde o guia mostra a planta
  amarela. Os dois descrevem o que o usuário viu no jogo. O Pinstouro diante do fosso do Dragão e a Florividente no
  recanto da selva junto da rota do Dragão também vêm do guia, cada um com o seu espelho. Dois Pinstouros seguem como
  esquema (EST).

As estruturas (Nexo e torres) seguem as do minimapa. `RiftMapTest` confere que todas as rotas, torres, acampamentos,
plantas e fossos dos dois lados ficam fora das paredes e têm caminho a pé. O desvio de parede de `RiftTest` passou a usar
(3500, 5300) → (5000, 5300), porque o ponto antigo caiu dentro da parede dos Lobos.

**Desenho** (`RiftGround`, `RiftLegend` e `NpcGlyph` em `pages/Arena.jsx`, usados também pela Partida):
- parede em pedra cinza com a borda clara; moita em capim verde com o contorno tracejado;
- chão das rotas numa faixa de terra de 900; rio azul; a base de cada time num tom da sua cor;
- as torres são quadrados; os monstros, círculos marrons (os objetivos com borda dourada);
- as plantas são uma flor da cor de cada uma: Frutomel amarelo, Pinstouro vermelho, Florividente ciano;
- uma legenda embaixo do mapa diz o que é cada símbolo.
