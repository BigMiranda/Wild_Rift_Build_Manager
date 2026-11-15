# Rodada 18: fichas da Arena dos demais campeões da rota Barão

Pedido (tarefa agendada de 15/11/2026): "modele os demais campeões da Baron Lane na Arena para melhorar as estimativas".
Um campeão por vez, um commit por lote pequeno.

## Os campeões da rota Barão

`campeoes_7_3f.yml` tem 56 campeões com "Barão" em `rotas`. 22 já tinham ficha em `kits_7_3f.yml` (Ornn, Darius,
Renekton, Rumble, Vayne, K'Sante, Mordekaiser, Garen, Sett, Jax, Fiora, Camille, Irelia, Riven, Aatrox, Malphite, Nasus,
Gwen, Kayle, Teemo, Shen, Sion). Faltavam 34:

- com Barão como **primeira** rota (os mais jogados nela, feitos primeiro): Dr. Mundo, Volibear, Pantheon, Urgot,
  Cho'Gath, Kennen, Poppy, Singed, Gnar, Jayce;
- com Barão como segunda ou terceira rota: Olaf, Tryndamere, Wukong, Jarvan IV, Warwick, Nautilus, Gragas, Maokai,
  Amumu, Yasuo, Yone, Akali, Sylas, Ambessa, Diana, Fizz, Vladimir, Zed, Kassadin, Ekko, Graves, Skarner, Aurora, Viego.

## Método

O mesmo do lote 1 (`docs/fichas/lote-1.md`):

- números de dano, razões, recargas, durações e %: **texto do jogo** (`campeoes_7_3f.yml`, `niveis` por rank);
- geometria (alcance, largura, raio, ângulo, velocidade, conjuração): rascunho da wiki por
  `python tools/fichas/gerar_rascunhos.py --campeoes "…" --saida <pasta temporária>` (não versionado), marcada WR ou
  PC em `fontes`; sem número, `EST`;
- só mecânicas que o `Arena.java` já entende; o que falta fica em `lacunas` (por campeão) e na `nota` da habilidade;
- `qualidade`: C quando o kit principal está todo na ficha com aproximações; D quando falta uma mecânica que muda a
  luta (possessão, sombras, roubar a ultimate, formas sem alcance próprio).

Ataques corpo a corpo: alcance 175 (EST, como nas fichas do lote 1); à distância: o `alcance_ataque` (PC).

## Mecânica nova: `nao_morre`

`nao_morre: s` (habilidade `proprio`, ou estado de outra forma pela lista `SELF_STATES`): por s segundos a Vida não
cai abaixo de 1 (`Arena.U.undyingUntil`, evento `nao_morreu`). Serve à Fúria Sem Fim do Tryndamere e depois a Kindred
e Zilean. Ficha sintética `nao_morre` e teste `MecanicasTest.anUndyingChampionKeepsOneHealth`.

## Teste das fichas novas

`FichasBaraoTest` põe cada ficha da rodada num duelo real (30 s, piloto automático, contra o Ornn): a ficha existe, ao
menos uma habilidade causa dano (o Tryndamere só causa com a E) e o duelo é determinístico. O `SheetValidator` confere
os campos de toda ficha (`MecanicasTest.theRealAndSyntheticSheetsPassTheValidator`). Os goldens da Arena
(`src/test/resources/arena/`) não mudaram: as fichas novas não entram nos cenários gravados.

Dois ajustes que o duelo mostrou: habilidades de liga/desliga (Rastro de Veneno do Singed, Desespero do Amumu) com a
recarga de 1 s empilhavam auras; viraram auras de 8 s religadas ao acabar.

## Modelados nesta rodada

| lote | campeão | qualidade | o que ficou de fora (lacunas) |
|---|---|---|---|
| 1 | Dr. Mundo | C | `resiste_primeiro_controle`, `escala_vida_perdida_propria`, `cura_pelo_dano_sofrido`, `minimo_de_dano` |
| 1 | Volibear | C | `frenesi_na_reconjuracao`, `buff_proprio_no_avanco` |
| 1 | Pantheon | C | `determinacao_mortal`, `bloqueio_por_direcao`, `critico_abaixo_da_vida`, `penetracao_passiva` |
| 1 | Urgot | C | `canhoes_das_pernas`, `alvo_mais_proximo`, `execucao_reconjurada` |
| 2 | Cho'Gath | C | `ataque_com_area`, `porcentagem_por_acumulo`, `tamanho_por_acumulo` |
| 2 | Kennen | C | `marca_da_tormenta`, `energia` |
| 2 | Poppy | C | `bloqueia_avancos`, `broquel_da_passiva`, `resistencias_passivas` |
| 2 | Olaf | C | `escala_vida_perdida_propria`, `pegar_o_machado`, `dano_em_si_mesmo`, `prolonga_com_ataques` |
| 2 | Tryndamere | C | `furia_em_critico`, `escala_vida_perdida_propria`, `reduz_dano_de_ataque` |
| 2 | Wukong | C | `clone_imita_habilidades`, `invisivel_no_avanco` |
| 2 | Singed | C | `rastro_no_chao`, `impede_avancos`, `enraiza_no_adesivo` |
| 3 | Jarvan IV | C | `combo_estandarte`, `arena_de_terreno`, `golpe_por_alvo_com_recarga` |
| 3 | Warwick | C | `cura_pelo_dano`, `bonus_contra_vida_baixa`, `temor_no_fim` |
| 3 | Nautilus | C | `enraiza_no_ataque`, `ataque_com_area`, `puxao_mutuo` |
| 3 | Gragas | C | `cura_ao_conjurar`, `ataque_com_area`, `carga_pelo_tempo_no_chao` |
| 3 | Maokai | C | `inalvejavel_no_avanco`, `enraiza_pela_distancia` |
| 3 | Amumu | C | `maldicao_verdadeira`, `puxar_a_si_mesmo`, `reducao_fixa_de_dano` |
| 3 | Yasuo | C | `critico_dobrado`, `escudo_do_fluxo`, `exige_arremessado`, `bloqueia_projeteis`, `recarga_pela_velocidade_de_ataque` |
| 3 | Yone | C | `critico_dobrado`, `forma_espiritual`, `teleporte_atras_do_alvo`, `recarga_pela_velocidade_de_ataque` |
| 3 | Akali | C | `marca_do_assassino`, `invisivel_so_na_fumaca`, `salto_para_tras`, `dano_pela_vida_perdida_ate_limite` |
| 3 | Sylas | D | `usurpar_ultimate`, `cargas_da_passiva`, `puxar_a_si_mesmo`, `cura_pela_vida_perdida_propria` |
| 3 | Ambessa | C | `finta_da_passiva`, `vampirismo_de_habilidade`, `teleporte_ate_o_alvo`, `energia` |
| 4 | Diana | C | `terceiro_ataque_em_area`, `recarga_minima_ao_zerar` |
| 4 | Fizz | C | `dano_ao_longo_do_tempo_no_ataque`, `tubarao_com_atraso`, `tamanho_do_tubarao` |
| 4 | Vladimir | C | `custo_de_vida`, `impeto_vermelho`, `amplifica_dano_sofrido`, `pacto_vermelho` |
| 4 | Zed | D | `sombras`, `marca_que_guarda_dano`, `golpe_por_alvo_com_recarga`, `energia` |
| 4 | Kassadin | C | `escudo_magico_ao_conjurar`, `custo_crescente_de_mana`, `fortalece_por_habilidades_proximas` |
| 4 | Ekko | C | `terceiro_golpe`, `entrar_na_esfera`, `dano_ao_voltar`, `ida_e_volta` |
| 4 | Graves | C | `escopeta_com_recarga`, `projeteis_bloqueados`, `visao_reduzida`, `coice` |
| 4 | Skarner | C | `tremor`, `arrasta_o_alvo`, `ataque_com_area`, `reconjurar_ataque` |
| 4 | Aurora | C | `exorcismo_da_passiva`, `area_de_convergencia`, `salto_para_tras` |
| 4 | Viego | D | `possessao`, `golpe_duplo_apos_habilidade`, `nevoa_no_terreno`, `carga_do_atordoamento` |
| 4 | Jayce | D | `alcance_por_forma`, `recargas_por_forma`, `portao_acelera_a_q`, `ataque_ao_trocar_de_forma` |
| 4 | Gnar | D | `furia_genetica`, `alcance_por_forma`, `bumerangue_volta`, `hiperativo_terceiro_golpe` |

Total: 34 fichas (29 C, 5 D). O estimado está em `fontes` (EST) e o aproximado na `nota` de cada habilidade. Os mais comuns:

- **passivas** quase todas fora (acúmulos, críticos, Fúria do Tryndamere e do Gnar, Marca da Tormenta do Kennen, Tremor do Skarner…): o motor não tem um gatilho genérico de "a cada N golpes" nem status por condição;
- **dois tempos numa habilidade** (golpe e explosão depois, ida e volta, Golpe dos Cinco Pontos no alcance máximo) saem juntos;
- **acúmulos que viram outra habilidade** (Tempestade de Aço, Aço Mortal, Golpe Ardiloso → Pancada Cortante) são reconjurações, sem exigir o acerto;
- **teleportes** (Kassadin, Viego) são avanços quase instantâneos até o ponto; Ekko usa `volta_no_tempo`;
- **liga/desliga** (Singed Q, Amumu W) são auras de 8 s religadas;
- **custos**: só a Energia da Akali (Q, 200 de máximo e 10 por s: EST); Mana, Energia dos outros e Vida (Vladimir) não entram, como no lote 1.

## Os que faltaram

Nenhum dos 34. Os D (Sylas, Zed, Viego, Jayce, Gnar) pedem mecânicas próprias antes de valerem para a partida:
usurpar a ultimate, sombras que repetem habilidades, possessão, alcance de ataque por forma e Fúria com dreno. Próximos
passos naturais: conferir no Treino os números EST mais sensíveis (alcances de avanço, raios), as passivas mais comuns
como mecânica genérica (o 3º golpe no mesmo alvo serve a Gnar, Ekko, Diana, Kennen e Vayne) e o `minimo` de dano na
fórmula (Dr. Mundo Q).

## Parte 2: os demais campeões (todas as rotas)

Pedido de 15/11/2026, às 11:55: "faça de todos os campeões restantes até 12h59" e, sobrando tempo, "uma tela de
revisão de cada campeão modelado na admin, para facilitar melhorias e correções na arena".

**As fichas.** Os 87 campeões de `campeoes_7_3f.yml` que ainda não tinham ficha ganharam uma: agora todos os 143 têm.
Seis commits de 12 a 16 campeões, em ordem alfabética. 77 ficaram com qualidade C e 10 com D (Heimerdinger, Hwei,
Katarina, Kayn, Nidalee, Norra, Orianna, Shyvana, Yunara, Yuumi: torres, temas, Adagas, formas, Esfera, Conexão).

O método foi o mesmo da parte 1, com uma diferença: o dano, as razões, as recargas, as durações e os % vêm do texto do
jogo, mas a **geometria** (alcance, largura, raio, ângulo, velocidade, conjuração) quase toda é `EST`: valores
próximos aos do PC, sem conferir no rascunho da wiki por falta de tempo. Cada habilidade diz em `fontes` o que é
estimado, e a tela de revisão conta e filtra essas habilidades. As simplificações mais comuns:

- **passivas** de fora (como na parte 1);
- **aliados**: escudos, curas e fortalecimentos que no jogo vão para um aliado ficam no próprio campeão;
- **reconjurações, cargas e acúmulos** (Ahri R, Akshan, Kindred, Varus, Twitch, Kog'Maw) viram uma conjuração média;
- **controles condicionais** (o atordoamento do Brand com Labareda, o Florescer Mortal do Jhin, a Bomba-Relógio do
  Zilean) valem sempre;
- **habilidades com dano por tempo**: `canal` leva o dano total (repartido nos golpes), `campo` e `aura` o dano por
  pulso, como na Arena.

Reduções de dano que as fichas novas fazem na luta (Alistar R, Braum 3, Master Yi 2) passaram a `modelado` em
`combate_7_3f.yml`, para não contar duas vezes com a condicional da build (Rodada 17).

`FichasBaraoTest.PARTE_2` põe cada ficha nova no duelo de 30 s contra o Ornn, como a parte 1. A bateria inteira (`./mvnw test`) passou, e os goldens da Arena não mudaram.

**A tela de revisão (Admin › Fichas).** Lista as 143 fichas com qualidade, lacunas, quantas habilidades têm números
`EST` e o que o `SheetValidator` acusa, com filtros por nome, qualidade e "só com avisos / lacunas / EST". Clicar num
campeão abre as habilidades campo a campo (forma, recarga, dano, efeitos, os demais campos, nota e fontes, com as
fontes estimadas destacadas) e um **duelo de teste**: 30 s no piloto automático contra um rival à escolha (o Ornn por
padrão), os dois com os mesmos status, e o dano por fonte de cada lado. Isso mostra se cada habilidade sai e acerta.
As correções continuam no YAML. Endpoints `GET /api/arena/kits/review` e `GET /api/arena/kits/duel?champion=&rival=`,
só na versão local (fora de `PublicApi`).

Próximos passos: conferir no Treino a geometria `EST`, começando pelos campeões mais jogados de cada rota. Também dá
para tirar dos D as mecânicas que mais mudam a luta (as torres do Heimerdinger, a Esfera da Orianna, as formas de
Nidalee, Shyvana e Kayn).

## Parte 2b: IAs de classe para a campanha

Pedido de 15/11/2026, às 13:15: "garanta que IAs bem treinadas de cada campeão sejam usadas para a campanha".

A campanha usa o cérebro Automático (`AutoBrain`) nos duelos de confronto (passo 3: a build contra os representantes
de cada classe). Para cada campeão, ele escolhe a IA do próprio campeão; sem ela, a da classe dele (`class:<Classe>`);
sem nenhuma, o cérebro Tático. O banco tinha IAs só de Ornn, Darius, Rumble, Mordekaiser e Yasuo, e nenhuma de classe:
os outros 138 campeões lutavam com o Tático.

Treinei uma IA para cada uma das 7 classes principais (`ClassAiTrainTest`, numa cópia do banco, para não parar a
campanha que já rodava). Cada uma joga, em turnos, 3 campeões da classe com 4 itens típicos dela, num 1x1 aos 15
minutos contra o Darius do cenário 2. Depois, `tools/importar_ias_de_classe.py` copiou para o banco real só as
aprovadas pelo treino. Como o backend lê as IAs do banco a cada escolha, a campanha passou a usá-las na hora, sem
reiniciar.

| classe | campeões do treino | gerações | melhor que o Roteiro | melhor que o Tático | aceita |
|---|---|---|---|---|---|
| Tanque | Ornn, Malphite, K'Sante | 80 | 88% | 88% | sim |
| Lutador | Darius, Garen, Aatrox | 80 | 100% | 94% | sim |
| Assassino | Kha'Zix, Akali, Diana | 80 | 75% | 100% | sim |
| Controlador | Thresh, Morgana, Nami | 80 | 100% | 88% | sim |
| Especialista | Teemo, Singed, Kennen | 80 | 100% | 100% | sim |
| Atirador | Caitlyn, Jinx, Ezreal | 200 (com 80: 25% / 56%, recusada) | 94% | 94% | sim |
| Mago | Ahri, Lux, Annie | 200 (com 80: 50% / 69%, recusada) | 81% | 75% | sim |

Os 143 campeões têm uma classe principal, então todos agora lutam com uma IA treinada: a própria (os 5 acima) ou a da
classe. Conferido no backend da campanha: o Automático escolhe a IA da classe Lutador (`kind: classe`) para o Renekton.
O Aatrox, primeiro da fila, terminou antes das IAs entrarem e fez os confrontos com o Tático.

## Parte 2c: a tela Admin › Fichas visual, com prévia na Arena

Pedidos de 15/11/2026: "uma prévia visual do campeão de cada skill, AA e passiva vs um boneco de pano na arena para
checar se o formato das skills estaria certo" e "deixe essa tela mais visual, está muito difícil de ler com puro texto".

**A tela.** Os campeões viram uma grade de retratos, com a qualidade em cor (A verde, B azul, C dourado, D vermelho), um
ponto com o número de habilidades `EST` e um "!" quando o validador acusa algo. Os filtros são botões. Clicar num
campeão abre, acima da grade, um cartão por parte do kit:

- **Passiva**: o texto do jogo, a nota da ficha e as lacunas em etiquetas (as passivas quase todas ficam fora das fichas);
- **Ataque básico**: o alcance desenhado em volta do campeão, o projétil e a espera;
- **1, 2, 3 e R**: a forma desenhada em escala (`ShapeSketch`: o campeão dourado olhando para a direita, o alcance, a
  área de acerto de cada zona — círculo, anel, setor, retângulo, cápsula, polígono —, a linha, o cone, o avanço até o
  ponto final, o alvo; uma barra de 500 dá a escala), as medidas, o dano numa linha colorida pelo tipo
  ("MÁGICO 80/150/220/290 + 90% PdH"), os controles e os efeitos em etiquetas (atordoar e cia. em vermelho, lentidão em
  azul, deslocamentos em amarelo, marcas e reduções em roxo, o que vai no próprio campeão em verde), a nota, as fontes
  com `EST` destacado e o texto do jogo para comparar. Uma habilidade que falta na ficha aparece tracejada, com o texto
  do jogo.

O duelo de teste virou barras: a Vida no fim e o dano de cada fonte.

**A prévia na Arena.** Cada cartão tem "Ver na Arena", e o topo tem "Ver o kit inteiro na Arena": abre a Arena com o
campeão sem itens, no minuto 15, contra um boneco de pano parado (20000 de Vida, 60 de Armadura e RM). Ele usa a
habilidade (ou as quatro, uma a cada 2,5 s) no boneco e ataca entre elas. A reprodução desenha cada área de acerto no
instante em que sai; pausando, o mouse sobre a forma diz o que é. O boneco fica a 80% do alcance do que é mostrado.

Para isso, o campo `build` de um lutador do cenário (a build que não é salva) passou de `@JsonIgnore` a
`@JsonProperty(access = WRITE_ONLY)`: é lido do pedido e nunca escrito, então continua sem ir para o banco com o
cenário. A tela da Arena aceita esse lutador ("Ficha Malphite (sem itens, não salva)" no lugar da escolha de build).

Feito numa worktree (`fichas-visual`) para não recompilar `backend/target/classes`, de onde o backend da campanha
estava rodando.
