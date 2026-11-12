# Rodada 14: notas legíveis, Anjo Guardião, suportes, subclasses, estilos de rota e guias por matchup

Levantado a partir do site publicado depois da campanha 7.3f com Tenacidade e Regeneração corrigidas (12/11/2026).

## O que estava errado

| Sintoma | Causa |
|---|---|
| Notas da Descoberta ilegíveis | Listas de nomes de itens e o caminho inteiro da busca de runas ("A → B; B → C; C → D"), repetidos por conjunto. |
| Anjo Guardião entre os 3 primeiros da triagem de quase todos (Darius, Garen, Irelia, Renekton em 2º) | Os 4 s de Estase contavam como tempo vivo sem dano sofrido: num teste de 10-20 s, 20-40% de durabilidade grátis. No jogo, nesse tempo o campeão não segura dano de ninguém e as recargas do inimigo voltam. |
| Suportes medidos quase só como tanques | A "utilidade" é o controle aplicado e a parte do dano do time absorvida. Não mede o carry vivo, o dano que ele consegue dar, a cura e o escudo nele, nem o dano inimigo cortado. |
| Guia automático "tem dano mágico? tem dano físico?" | `GuideService.suggest` só tem esses dois ramos. As subclasses da wiki (Colosso, Vanguarda, Assassino, Artilharia...) já estão na base e ninguém lê. |
| Nenhuma proposta de como jogar a rota | A partida simulada tem um só jeito de jogar a rota (troca a uma taxa fixa, volta por vida ou ouro). |

A pool pequena de campeões com ficha A/B (11) também pesa: os duelos e as matchups só veem esses campeões, e os
bonecos de teste são poucos e genéricos. Um item que vence esse grupo sobe para todos (o Manto da Meia-noite em 1º na
triagem de quase todos é o mesmo viés). As partes 4 e 5 aumentam a variedade de oponentes medidos.

## Partes

1. ✅ **Notas com ícones** (`frontend/src/discoveryNotes.js`, `components/DiscoveryNotes.jsx`): itens e runas viram
   ícones com o nome como legenda; o caminho da busca de runas vira a troca final, agrupada quando vários conjuntos
   tiveram a mesma; as descobertas retiradas, agrupadas pela build salva que as superou; os pares de itens (sinergias)
   no site, os 6 maiores à mostra.
2. ✅ **Anjo Guardião**: `FighterStats.outOfFight` (segundos na Estase do revive, cortados no fim da luta) sai do tempo
   vivo e da taxa na efetividade (`EffectTestService.measure`). Os duelos continuam pela vida que sobra (o revive vale
   lá como no jogo). Golden inalterado (nenhum cenário revive).
3. ✅ **Subclasses** — `ChampionProfiles.Profile.subclasses`: a da wiki (`classes` do seed), "Atirador" para atiradores
   e, para quem a wiki não dá uma (11 Especialistas, Skarner), a subclasse cujo centro do perfil (média em desvios dos
   campeões que a wiki põe nela) está mais perto (`subclassSource: perfil`, distâncias em `subclassDistance`). Saíram:
   Cho'Gath, Singed e Skarner Vanguarda; Zilean Encantador; Teemo, Kayle e Kennen Artilharia; Fiddlesticks,
   Heimerdinger e Nidalee Mago de Batalha; Graves Mergulhador; Gnar fica longe de todas (duas formas na mesma ficha).
4. ✅ **Bonecos por subclasse** — grupo `ameaca` em `efetividade_7_3d.yml` (10 cenários: assassinos de proximidade,
   colossos, tanques de iniciação, captores, artilharia, magos explosivos, magos de batalha, 2 e 3 atiradores, carry com
   encantador), com `ameaca` (subclasses) e `quantos`, no relatório `ameacas`. Os pulsos dos bonecos ganharam
   `controle` (atordoamento | arremesso | lentidao, `Arena.pulseControl`) e `tipo: controle`. **Não entram na nota**
   (a campanha seguiria o mesmo custo): a campanha ganhou o passo `ameacas`, que mede a padrão e os 5 seguintes do
   ranking aos 20 min em cada cenário (`CampaignService.threats`). Ornn no teste rápido: artilharia +14 com
   Morellonomicon no lugar da Máscara, explosivos +5; o resto empata com a padrão.
5. ✅ **Suportes** — grupo `suporte` (mergulho no carry, carry cutucado de longe, dupla contra dupla), com
   `alvo_aliado` (o atacante vai atrás do carry, `EffectTestService.focusIndex`) e as medidas `allyDealt` e
   `enemyDealt`. `BuildScore.support` (só para classe Controlador, rota Suporte ou arquétipo suporte, regras em
   `utilidade.suporte` de `nota_builds_7_3d.yml`): uma leitura de utilidade por cenário, a soma pelos pesos de carry vivo
   (0,35), dano do carry (0,25), controle (0,2) e dano inimigo cortado (0,2), cada parte de -100 a 100 contra a
   referência; `peso_utilidade` +0,25. Controlador passou a 0,30 / 0,55 / 0,15 e o arquétipo suporte a utilidade
   +0,30. Visão, previsibilidade e momento de gastar recursos não cabem numa luta: ficam na partida (parte 6).
6. ✅ **Estilos de rota na partida** — `MatchModel.Slot.style` / `Match.Seat.style`, números em `estilos_rota` de
   `macro_7_3d.yml`: `causa`/`sofre` na troca, `ultimos_golpes`, `negar` (o adversário de rota perde parte da XP e do
   ouro das tropas quando quem pressiona está à frente em Vida, `Match.denied`), janelas de roaming e de ganks
   (`ScriptedMacro.styled`: o meio vai ao barão e ao dragão, a selva ao rota inimigo com menos Vida) e `visao_raio` (o
   rota vê o gank chegando e recua, `Match.gankSeen`). O padrão continua idêntico (fatores 1, nenhum sorteio a mais:
   os testes de partida não mudaram). A campanha ganhou o passo `estilos`: partidas 1x1 na rota até os 10 min, a
   padrão em cada estilo de `estilos_avaliados` contra cada rival de rota jogável (até 8, 3 sementes), a vantagem em
   ouro mais a XP em ouro. Proxy fica de fora (precisa de ondas puxadas para trás da torre).
7. ✅ **Guias por matchup** — condições `inimigos_subclasse` (lista separada por vírgulas, com quantidade) e
   `adversario_subclasse`; decisão `estilo`. `GuideService.suggest` monta primeiro a fase de rotas (o estilo que mais
   rendeu contra cada subclasse de rival, só onde difere do melhor na média, que fecha a cadeia) e depois as ameaças
   (a build que ganha da padrão por 3 pontos ou mais, a maior vantagem primeiro), e por fim os ramos antigos de dano
   mágico e físico. O site mostra a subclasse, a tabela da fase de rotas e a melhor build contra cada tipo de time.

Cada parte, um commit, com a nota de progresso aqui. A campanha roda de novo no fim, antes de publicar o site.
