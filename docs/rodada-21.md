# Rodada 21: IA que generaliza, treino em massa, IA padrão e objetivos visíveis

Prompt de 20/11/2026, a partir do `main` em ce5dcb6 (depois da Rodada 20.b). Lê o `CLAUDE.md` e as rodadas 1 a 3 (a
rede, o `Trainer`, o `ArenaChain`), 7 (Parte 6, o Automático), 18 (Parte 2b, as IAs de classe) e 19 (a progressão).
Nenhuma regra de luta mudou: os golden (`ArenaRegressionTest`, `ArenaPolicyMigrationTest`) continuam os mesmos.

**A pergunta do usuário:** o treino consegue uma IA que lida com vários tipos de campeão e várias formas de jogar?

## Parte 1: medir a generalização, antes de mudar (✅)

**O banco de validação** (`ia_validacao_7_3f.yml`, `arena/league/LeagueModel.bank`, `LeagueService`), fixo e nunca usado
no treino (sementes próprias; o treino em liga tira estes adversários do seu sorteio):

- **adversários:** 2 campeões de cada uma das 7 classes, pela ordem presença na meta (`data/meta/meta.json`, o maior das
  rotas) → qualidade da ficha → nome; só campeões com ficha. Com a meta importada hoje: Tanque Nautilus e Leona, Lutador
  Darius e Lee Sin, Assassino Master Yi e Yasuo, Mago Seraphine e Sylas, Atirador Caitlyn e Kai'Sa, Controlador Thresh e
  Blitzcrank, Especialista Graves e Cho'Gath. `fixos:` no seed troca a escolha de uma classe;
- **builds:** amostradas pela progressão natural (Rodada 19, `ProgressionService.sample`) aos 6, 12 e 20 minutos, contra
  o adversário da luta; runas, feitiços e ritmo de ouro/XP da primeira build salva do campeão (sem nenhuma: a base
  sintética da campanha, 700/700);
- **4 tipos de luta:** `rota` (1x1 numa rota do mapa inteiro, entre as torres externas, com tropas e torres no minuto da
  luta, sem visão; Barão, meio e Dragão em rodízio — `RiftTemplates.laneDuel`); `aberto` (1x1 na rota única, sem zona
  segura); `dupla` (2x2: o aliado de cada lado vem de outra classe do banco); `fuga` (quem foge começa com 40% de Vida,
  2600 da zona segura do seu lado, o outro 350 atrás com a Vida cheia; semente par o jogador foge, ímpar persegue);
- **3 sementes** por combinação: 7 × 2 × 3 minutos × 4 tipos × 3 = **504 lutas**, 18 por célula (classe adversária ×
  tipo de luta). Uma IA de classe joga, em rodízio, os 2 primeiros campeões da sua classe na mesma ordem.

**Êxito** = vitória limpa (o lado vence, o jogador vivo, todo inimigo morto); na fuga em que o jogador foge, chegar vivo à
zona segura também conta. Cada luta é preparada uma vez e jogada com a IA e com o **Tático** (mesmos lutadores, posições
e builds; todos os outros com o Tático, fixo, para a matriz não mudar com as IAs salvas). Determinístico em qualquer
número de threads. O `Gym` ganhou o mapa inteiro (`Episode.rift`).

**Tabela `ai_validation`** (schema v33): a matriz de cada IA, quem jogou, a impressão do banco (seed + campeões), o hash
da ficha, o patch e o tempo. **Relatório:** `LeagueReportTest` (opt-in `-Dleague.report=data/ornn-planner.db`, numa
cópia; `-Dleague.owners=`, `-Dleague.seeds=`, `-Dleague.threads=`).

**"No cenário em que treinou"**: o cenário salvo com o nome do registro (ou um salvo cujo nome começa o do registro,
como os da cadeia "… · Soldado v8.1"), 18 vezes com as posições deslocadas como o treino deslocava, a IA contra o Tático
no mesmo lugar, medido pelo **objetivo do treino** (o `win_fast` "melhor que o Tático" que o treino otimizou). As IAs de
classe usam os campeões com que a Rodada 18 as treinou (`jogadores_do_treino` no seed), com a build da progressão aos 15
minutos, no lugar 0 de "Darius ganha de Ornn".

### Linha de base (20/11/2026, banco de hoje, 18 threads; detalhe por célula em `docs/rodada-21/linha-de-base.md`)

| IA | êxito no banco | Tático no banco | diferença | no cenário de treino: vitória IA / Tático; melhor que o Tático | s |
|---|---|---|---|---|---|
| Darius v22 | 79,4 | 82,1 | −2,8 | (cenário "Novo cenário" não está mais salvo) | 39 |
| Mordekaiser v3 | 42,3 | 56,0 | −13,7 | (não salvo) | 20 |
| Ornn v121 | 61,3 | 72,2 | −10,9 | (não salvo: "Luta da partida aos 2:39") | 22 |
| Rumble v49 | 57,5 | 72,8 | −15,3 | 11% / 0%; **61%** | 16 |
| Yasuo v5 | 77,4 | 88,5 | −11,1 | (o cenário salvo não tem o Yasuo) | 12 |
| classe Assassino v1 | 46,4 | 58,5 | −12,1 | 0% / 0%; **61%** | 11 |
| classe Atirador v1 | 25,8 | 36,1 | −10,3 | 28% / 28%; 56% | 12 |
| classe Controlador v1 | 21,8 | 26,8 | −5,0 | 0% / 0%; **83%** | 15 |
| classe Especialista v1 | 33,3 | 36,7 | −3,4 | 0% / 0%; **83%** | 14 |
| classe Lutador v1 | 42,9 | 58,7 | −15,9 | 0% / 0%; **97%** | 19 |
| classe Mago v1 | 21,4 | 25,2 | −3,8 | 0% / 11%; 39% | 11 |
| classe Tanque v1 | 26,8 | 29,0 | −2,2 | 0% / 0%; 50% | 14 |

**Leitura.** Todas as 12 IAs em uso ficam **abaixo do Tático** no banco (−2 a −16 pontos), e a pior coluna é a fuga (a
IA de classe Tanque: −31 pontos na fuga, +5 a +7 na rota e no aberto): elas aprenderam a vencer **aquele** adversário
naquela luta (a Lutador "melhor que o Tático" em 97% do seu cenário). É o sobreajuste que a revisão apontou.

**Um achado que explica parte da diferença:** a rede zerada **não** jogava igual ao Tático, ao contrário do que a
`NeuralPolicy` promete ("zero weights == Tactical"): ela decide a cada 0,1 s e o Tático a cada passo (0,02 s). No banco,
uma rede zerada da classe Lutador fez 41,1% contra 46,4% do Tático: −5 pontos só pela demora em decidir. As IAs salvas
continuam com 0,1 s (o golden das IAs não muda); as da liga decidem a cada passo (`Weights.interval = 0`, Parte 2), e o
teste `zeroLeagueNetworkPlaysExactlyLikeTheTacticalRules` confere a igualdade célula a célula.
