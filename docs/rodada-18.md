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
