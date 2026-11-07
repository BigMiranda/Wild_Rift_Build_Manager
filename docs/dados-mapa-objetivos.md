# Dados do mapa, das tropas, da selva e dos objetivos (Rodada 6.b, Parte 3)

Consultado em **31/10/2026**; atualizado na **Rodada 10 (07/11/2026)** com os prints e os dados do usuário de 05/11/2026, que valem mais que as fontes online (marcados **USU**). O que vira dado está em `backend/src/main/resources/seed/mapa_7_3d.yml` e `objetivos_7_3d.yml`, cada bloco com a sua `fonte`.

**Atenção:** a maior parte deste material não tem fonte primária. A wiki não tem as páginas `WR:Minion`, `WR:Nexus`, `WR:Fountain`, `WR:Experience`, `WR:Jungle`,
`WR:Plant` e `WR:Summoner's_Rift`, e a `WR:Turret` veio sem o texto do artigo. Nenhuma fonte traz vida e dano das tropas, alcance das torres nem o tamanho do mapa. Os números abaixo são o melhor que há, e os marcados como **estimativa** vão mudar com prints.

## Fontes

| sigla | fonte | observação |
|---|---|---|
| RPN | [riftpatchnotes.com](https://www.riftpatchnotes.com): [torres](https://www.riftpatchnotes.com/system/turrets), [tropas](https://www.riftpatchnotes.com/system/minions), [ouro e XP](https://www.riftpatchnotes.com/system/gold-xp), [dragões](https://www.riftpatchnotes.com/system/dragons), [Barão](https://www.riftpatchnotes.com/system/baron-nashor) | histórico agregado de patches: é o último valor registrado, não dado do jogo |
| WR | [WR:Gold](https://wiki.leagueoflegends.com/en-us/WR:Gold), [WR:Baron_pit](https://wiki.leagueoflegends.com/en-us/WR:Baron_pit), [WR:Dragon_pit](https://wiki.leagueoflegends.com/en-us/WR:Dragon_pit), [WR:Dragon_Slayer](https://wiki.leagueoflegends.com/en-us/WR:Dragon_Slayer), [WR:Monster](https://wiki.leagueoflegends.com/en-us/WR:Monster), [WR:Map](https://wiki.leagueoflegends.com/en-us/WR:Map) | várias páginas de 2025; onde a RPN é mais nova, ela vale |
| NP | notas oficiais, em `docs/patches/` | |
| Riot | [Mapa e Modos](https://support.riotgames.com/pt-br/wild-rift/gameplay/map-and-modes), 2022 | sem inibidores: a torre da base dá as supertropas; o Nexo ataca sozinho |
| PC | [Jungle_plants](https://wiki.leagueoflegends.com/en-us/Jungle_plants) e o LoL de PC | último recurso: `fonte: PC (estimativa)` |
| EST | estimativa própria | sem fonte |

## Tropas

| dado | valor | fonte |
|---|---|---|
| primeira onda | **0:25** | USU: a contagem começa em 0:00 e nasce uma onda a cada 25 s |
| intervalo | **25 s, a partida inteira** | USU (a janela da RPN 4.2 saiu) |
| composição | 3 combatentes, 3 conjuradoras; canhão a cada 3 ondas | PC (estimativa) |
| supertropa | depois de cair a torre da base da rota | Riot |
| vida, dano, resistências, crescimento | todos | PC (estimativa): a RPN diz "não publicado" |
| ouro | combatente 60, conjuradora 40, canhão 80 | RPN 4.2; conjuradora: WR:Gold. A WR:Gold diz 65 e 85, valores antigos |
| XP | 60 / 30 / 90 | PC (estimativa) |
| alcance | conjuradora 525, canhão 300 | RPN 4.2 |
| regra de alvo | agro de 1 s; o resto é do PC | RPN 4.2; PC (estimativa) |

**A XP de 1800 do canhão não foi usada.** A RPN registra "2000 → 1800", mas a tabela de XP do planejador vai de 280 no nível 2 a 13.020 no nível 15, e esse número daria vários níveis por tropa.

## Torres, Nexo e fonte

| dado | externa | interna | da base | Nexo |
|---|---|---|---|---|
| Vida | 3.500 EST | 3.000 RPN 4.2 | 3.200 RPN 5.2 | 4.000 NP 7.3a |
| Armadura / RM | 60 RPN 6.2h | 15 RPN 6.2h | 30 RPN 6.2h | 30 EST |
| dano | 200 → 325 até 17:30, RPN 3.3b | 200 + 8 a cada 30 s desde 7:30, até 25 vezes, RPN 7.0 e 4.2 | igual à interna | 280 + 8 a cada 30 s, RPN 4.2 |
| ouro (local / global) | 200 / 75 | 350 / 75 | 200 / 75 | — |
| alcance | 750 PC (estimativa) | igual | igual | igual |

- **Aumento por golpe seguido:** 100/150/210/270%, NP 7.3d.
- **Placas:** 5, 150 de ouro cada, até 6:00 (RPN), +20 de Armadura e RM por 10 s ao cair (NP 7.3a).
- **Defesa do início:** +50 de Armadura e RM nos 2 primeiros minutos, meio e Barão (RPN 3.3b).
- **Anti-Backdoor:** 75% nas torres e 80% no Nexo (RPN 5.2). **Penetração de Armadura:** 50% (RPN 7.1).
- **Fora do modelo por falta de dado ou de uso:** Contra-Ataque (escudo por tempo), queima de base aos 25:00, proteção de queda em sequência, bônus por campeões por perto.
- **Barricadas:** nenhuma fonte usa esse nome. Pode ser a "Defesa do início" ou o "Contra-Ataque". Ver "Para o usuário" em `docs/rodada-6b.md`.
- **Troca de alvo para quem ataca um campeão aliado:** não aparece em fonte de Wild Rift; a regra é a do PC.
- **Não há inibidores nem torres do Nexo** (Riot): o Nexo atira sozinho.
- **Fonte:** nada encontrado. Laser, cura e alcance são PC (estimativa).

**Conflitos:** resistência das placas (265 ou 365, na mesma nota 6.2h), e o limite de acúmulos de Defesa Proativa (de 5 ou de 6 para 4). Nenhum dos dois entrou.

## Selva

| acampamento | Vida | dano | ouro | renasce | fonte |
|---|---|---|---|---|---|
| Espinhento Vermelho | 2.800–4.000 | 75–135 | 130 | 2:30 | WR:Monster |
| Sentinela Azul | 2.800–4.000 | 75–135 | 130 | 2:30 | WR:Monster |
| Lobos, Aves, Krugs, Gromp | | | | | EST |

Os acampamentos aparecem **quando as tropas estão prestes a se chocar no meio** (USU): a 1ª onda sai aos 0:25 e anda 6.703 unidades até o centro pela rota do meio do mapa retraçado, a 325/s, então **0:46** (45,6 s). **Vermelho e Azul nascem 1 s depois (0:47)**. O **Aronguejo nasce aos 1:30** (USU). **Buffs:** Azul dá 15 de aceleração, Vermelho dá lentidão e queimadura, por 90 s; as versões evoluídas vêm aos 8:00 (WR:Monster). XP dos acampamentos e o Aronguejo não têm fonte.

## Plantas

Os números são da página geral da wiki, marcados PC (estimativa). O Wild Rift confirmado: a planta de cura solta 3 frutos (WR:Map); no Rift de Oceano há mais delas; no de Gelo a planta explosiva vira outra (WR:Dragon_pit).

| nome usado | nome em inglês | aparece | renasce | efeito |
|---|---|---|---|---|
| Frutomel | Honeyfruit | 6:00 | 6:00 | cura ao pisar, com lentidão |
| pinstouro | Blast Cone | 1:15 | 6:00 | arremessa até 900 |
| Clarevidente | Scryer's Bloom | 3:00 | 5:30 | revela em cone |

**Os nomes em pt-BR são os do prompt:** nenhuma fonte os confirma.

## Objetivos

**Barão Na'Shor:** aparece aos 11:00 (RPN 6.2; a página da unidade ainda diz 13:00), renasce em 3:30. Vida base 9.500 (RPN 6.3), dano 200 + 22 por nível (RPN 4.2), Armadura 120, RM 70. Olhar: −50% de dano no Barão por 5 s. Corrosão Voraz: −3 de Armadura e RM por golpe, até −50, por 5 s. Veneno: 5 s, a cada 0,5 s, dentro do fosso. Ciclo: uma habilidade a cada 5 ou 6 ataques.

**Mão do Barão:** a primeira dura 90 s (RPN 6.2). **Os números por minuto não têm fonte:** a página do buff está vazia. Os valores de `objetivos_7_3d.yml` são EST. Tropas com o buff: faixas do 7.3d.

**Dragões:** aparecem aos 6:00, renascem em 4:30, somem aos 17:30 (RPN). Vida 4.000 + 180 por nível (RPN 4.2).

**Só há 3 dragões elementais no Wild Rift: Oceano, Montanha e Infernal** (prints do usuário, 05/11/2026, painel "Mata-Dragões"). Gelo e os outros elementos do PC saíram. **O 1º dragão abatido define o elemento da alma**, e a alma só vem ao time que pegar **os 3 dragões**. Cada dragão abatido dá ao time o efeito do seu elemento, que acumula:

| dragão | por dragão (acumula) | alma |
|---|---|---|
| Oceano | regenera 2,8% da Vida perdida a cada 5 s | recupera 250 + 48% do DdA adicional + 25% do PdH + 10% da Vida adicional ao longo de 4 s depois de causar dano a um campeão inimigo; 6 s de recarga |
| Montanha | +4 de Armadura e RM, mais 3% da Armadura e RM totais | depois de 5 s sem dano de campeões inimigos ou torres, escudo de 350 |
| Infernal | 4% de DdA e PdH | dano a um campeão inimigo cria uma explosão de raio 250 ao redor do alvo: 180 + 35% do DdA adicional + 15% do PdH, adaptativo; 5 s de recarga |

**Montanha, a ordem:** o +4 fixo entra primeiro e os 3% leem o total depois de todas as outras fontes, incluindo esse +4: com 100 de Armadura, 1 dragão dá 100 + 4 + 3% de 104 = 107,12; 2 dragões dão 100 + 8 + 6% de 108 = 114,48. Escolhi assim porque é a ordem do motor para "% do total" (a Manto da Aurora e a Rabadon leem o total depois das fontes fixas). **Forja Viva:** os dragões entram depois da ficha, como buff de objetivo, e a Forja não os amplifica; a % lê o total já com a Forja, a mesma regra da Manto da Aurora. Os números do print (518 de cura e 228 de dano) são o valor naquele momento; as fórmulas fecham, por exemplo, com 320 de PdH e 1.880 de Vida adicional, sem DdA adicional.

**Dragão Ancião:** depois do terceiro dragão, ou às 19:00 (RPN 6.2), renasce em 6:00. Buff: 450 de Dano Verdadeiro em 3 s e execução abaixo de 15%, por 120 s. Vida e dano: EST.

**Arauto:** a última menção explícita (RPN 5.2, 2024) diz que ele foi trocado por Torreta Guerrilha ou Mímico Hextec, sem números. A WR:Monster, de 2025, ainda o descreve. Ficou **fora**.

## Partida

| dado | valor | fonte |
|---|---|---|
| ouro inicial | 500 | WR:Gold; planejador |
| ouro passivo | 3/s desde 0:20 | RPN 4.2; a WR:Gold diz 3,5 |
| abate | 225/150/100/100 por sequência | RPN 7.0 |
| primeiro sangue | +150 | RPN 4.2 |
| assistência | 35% com um assistente | RPN 5.0 |
| XP | a tabela do planejador (`xp_level`) | conferida |

A curva de XP do planejador segue valendo. A RPN só traz totais de abate (1300–3550) sem dizer a escala; dividi por 10 para caber na tabela (EST).

## Mapa

**Rodada 10: o mapa foi retraçado pelo print do minimapa base** (`capturas/7.3d/mapa/minimapa-base.png`, sem moitas nem plantas). Escala, calibragem e o que mudou em `docs/rodada-10.md`. Moitas e plantas seguem como o esquema abaixo até o segundo print.

**Antes da Rodada 10: a geometria era um esquema, não um traçado.** Nenhuma fonte dá as medidas do mapa do Wild Rift. A imagem da wiki (`WR_Summoner's_Rift.jpg`) recusou o download (403), e a Liquipedia também (403). As posições de torres, acampamentos e fossos são as coordenadas do LoL de PC, lembradas de memória e levadas à escala 0,807 (de 14.870 para 12.000); o fator também é estimativa, porque a Riot só diz que o mapa é menor. Paredes e moitas são blocos que reproduzem a ideia das rotas e da selva.
