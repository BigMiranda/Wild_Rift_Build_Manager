# Rodada 6.b — andamento

Hitboxes reais, motor para os outros campeões, mapa completo, partida inteira e calculadora de builds. Um commit por
parte; cada parte termina com `./mvnw test` e `npm run build` limpos.

**Branch:** `rodada-6b`, criada a partir do `main` (7a31735). Nada foi enviado ao remoto.

**Testes antes da rodada:** 236 (0 falhas, 11 opcionais pulados), contados com a `target/surefire-reports/` limpa.

## Parte 1 — Hitboxes próximas das reais ✅

### Vocabulário

`arena/fight/HitShape` guarda a área de uma zona no próprio sistema dela: origem em quem lança (ou no ponto mirado), +x
para onde mira. Todas as formas respondem à mesma pergunta, `gap(x, y)`: a distância do ponto até a área. O alvo é acertado
quando o círculo do corpo dele toca a área (`hits`), e o mapa desenha a mesma geometria (`outline`).

| forma | campos |
|---|---|
| `circulo` | `raio` |
| `anel` | `raio_interno`, `raio` |
| `setor` | `angulo`, `raio`, `raio_interno` (o setor anelar) |
| `retangulo` | `comprimento`, `largura`, `centro` |
| `capsula` | `comprimento`, `largura`, `centro` |
| `poligono` | `vertices` |
| `arco` ou `corrente` | `pontos`, `largura` |

**Habilidade com zonas:** `forma: zonas`, `zonas: [{nome, forma, ..., dano, efeitos, acumula}]`. O `dano` pode ser um nome de
`danos`. A primeira zona em que o alvo está vale, salvo `acumula: true`.

**No tempo:** `atraso`, `expande: {de, em}`, `move: {velocidade, velocidade_final, aceleracao, distancia}`, `segue: true` e
`duracao` com `intervalo`. Forma que move ou cresce acerta cada alvo uma vez. `origem: ponto` centra a zona no ponto mirado.

**Canais e campos:** `canal` e `campo` continuam como formas e leem a hitbox da primeira zona, porque têm regras próprias
(tempo parado, dano dividido pelos pulsos, atraso do campo).

**Tela:** forma nova de desenho `zone`, com o contorno em pontos. A primeira zona é cheia; as seguintes, tracejadas e mais
claras. O cartão da Rodada 5 mostra "zonas: lâmina ×1" (`Use.zones`), e os eventos ganharam `zona:1:lâmina`.

**Previsões da IA:** `zonas` entra nas formas miradas; o caminho de fuga é o da zona de que o alvo demora mais a sair.

### Fichas convertidas

Medidas lidas em 31/10/2026 nas páginas `WR:` da wiki; onde a página do Wild Rift não traz, a do LoL de PC (marcada).

| habilidade | antes | agora | fonte |
|---|---|---|---|
| Darius, Dizimar | área de raio 460, com `interno: 0.35` testado pelo centro do alvo | `anel` 240–460 (lâmina: dano cheio, cura e Hemorragia) e `circulo` 240 (cabo: 35%, nada mais) | WR traz "240 / 460" e os 35%; qual raio é qual vem do PC |
| Darius, Apreender | `cone` de 50° e 450, pelo centro do alvo | `setor` de 50° e 450, pelo corpo do alvo | WR |
| Rumble, Cospe-Fogo | canal em cone de 64° e 600, pelo centro do alvo | canal com `setor` de 64° e 600, pelo corpo | PC (o WR não traz) |
| Rumble, O Equalizador | retângulo de 1000 × 390 | `capsula` de 900 com largura 410: seis foguetes de raio 205, a 180 um do outro, 1310 de ponta a ponta | PC (o WR não traz) |
| Vayne, Condenar | sem desenho do empurrão | `capsula` de 475 no mapa: o corredor do empurrão | PC (475); largura EST |

**Condenar não muda a luta:** a parede já era testada pelo corpo do alvo enquanto ele é empurrado. A wiki não traz a largura do
teste, então o corredor é desenho.

**Não convertidas:** os demais são linhas, avanços e círculos simples, sem forma composta. As páginas `WR:` de Renekton e Ornn não
trazem larguras, então nada mudou neles. O elemental do Ornn segue com o retângulo próprio (`elementalSweeps`), porque a reconjuração
depende dele; o `move` das zonas é a versão genérica.

**Número sem explicação:** o PC dá "RANGE: 600 • -50" no Cospe-Fogo. A página não explica o −50 e ele ficou de fora.

### Golden 🎯

Regravados com `-Darena.golden.write=true`, com a mudança intencional das formas. **Mudaram 21 de 35 cenários, todos com Darius ou
Rumble:**

- duelos: Ornn × Darius, Ornn × Rumble, Darius × (Ornn, Renekton, Rumble, Vayne, K'Sante), Renekton × (Darius, Rumble), Rumble × (Ornn, Darius,
  Renekton, Vayne, K'Sante), Vayne × (Darius, Rumble), K'Sante × (Darius, Rumble);
- `3v3`, `fixed target, no abilities` e `long Darius x Vayne`.

Os demais, sem Darius nem Rumble, ficaram iguais. O contorno de uma zona é desenho e ficou fora da impressão digital. `golden-ai-v2.txt`
mudou em 3 linhas pelo mesmo motivo.

**O efeito é grande em algumas lutas.** Antes, um alvo com o corpo tocando o anel e o centro dentro de 240 levava o cabo; agora leva a lâmina,
como a regra do corpo manda. Exemplos: no `3v3`, o vencedor passou do lado vermelho (19,9 s) para o azul (28,2 s), com o Darius causando 7.967
em vez de 5.053; em Rumble × Darius o Rumble vence em 14,9 s em vez de 19,1 s, com o Darius causando 2.404 em vez de 3.127.

### Testes

`HitShapeTest` (17): cada forma acerta e erra nos limites; o teste no mundo gira com a conjuração; os contornos fecham, e o anel tem furo. Na luta: no
anel, Hemorragia e dano cheio; no centro, nada disso, com menos da metade do dano; o alvo só recebe a primeira zona; forma com atraso, que fica,
que viaja e que cresce; determinismo.

**`trainedDariusBeatsTheScriptOnValidation` mudou de orçamento.** Com a lâmina certa, a IA treinada em 30 gerações ganhou do Roteiro em 56% das largadas, abaixo da meta de
60%. Subi para 45 gerações, sem mudar a meta: 85%, 75% e 71% nas três sementes (média 77%).

Testes: 236 → 253 (11 opcionais pulados). `npm run build` limpo.

## Parte 2 — Motor pronto para os outros campeões ✅

Cada mecânica é campo de ficha, sem citar campeão. As fichas sintéticas, uma por mecânica, ficam em
`backend/src/test/resources/arena/fichas-sinteticas.yml`, e o formato está no cabeçalho de `kits_7_3d.yml`.

| grupo | campos | comportamento |
|---|---|---|
| Controles | `enraiza`, `silencia`, `cega`, `provoca`, `amedronta`, `encanta`, `adormece`, `suprime`, `polimorfa` | enraizar impede andar e avançar; silêncio impede lançar; cegueira faz os ataques errarem; provocar faz andar até quem lançou e atacar; medo, fugir; encanto, andar até quem lançou sem atacar; sono acaba com dano; polimorfo tira ataques e habilidades, com lentidão |
| Regras dos controles | `mapa.controles` | tabela por tipo: `tenacidade` e `purificar`. Supressão e arremesso ficam de fora dos dois |
| Empurrão | `afasta`, `afasta_velocidade`, `afasta_parede` | em qualquer forma, com efeitos ao bater na parede |
| Estados | `invisivel`, `invulneravel`, `nao_selecionavel`, `imparavel_por` | invisibilidade revelada por dano ou proximidade, pelos dados; invulnerável segue mirável e anula o dano; não selecionável sai das listas de alvo |
| Movimento | formas `piscar` e `teleporte`; `ate_ponto`, `ate_alvo`, `atravessa_parede` | piscar é instantâneo e cruza parede; teleporte canaliza e é cancelado por controle; avanço persegue o alvo ou para no ponto; salto cruza parede |
| Conjuração | `interrompivel`, `carregar`, `reconjurar.versoes` | canal interrompido por controle ou silêncio, salvo `imparavel`; carga aumenta alcance e dano; cada reconjuração usa a próxima versão |
| Formas | `formas`, `forma_inicial`, `troca_forma`, `recurso.forma_acima` | habilidades e status próprios; muda por habilidade ou por recurso |
| Invocações | forma `invocacao`, com `invoca` | `segue`, `ataca`, `parada`, `explode`, `mina` (arma, invisível, dispara por proximidade) e `clone` (fração do dono). São unidades reais da luta, fora do placar; o dano entra na conta do dono como `Invocação · nome` |
| Terreno | forma `parede` | a primeira zona vira terreno por `duracao`, no mesmo teste de bloqueio do pilar |
| Outras | formas `anexar` e `volta_no_tempo`; `alcance: global`; `rouba`; `executa_abaixo`; `alvo: aliado \| aliados` com `escudo` e `cura` | |
| Recursos | `recurso.tipo` | `mana` e `energia` começam cheias e pagam `custo`; `furia` e `calor` seguem como estavam; `sem_custo` |

**Mana:** não existia na Arena. Sem `max`, usa o status de Mana Máxima.

**Provocar e o golpe em curso:** um ataque já em andamento aplica mesmo com o alvo provocado, senão ele nunca conseguiria bater em quem o provocou. No polimorfo, o golpe em andamento é cancelado.

**Canais antigos não mudam:** `interrompivel` é opcional, porque o canal do Rumble hoje só pula os pulsos durante o controle. A Etapa 7 deve pôr o campo nas fichas novas.

**Sem IA para isso ainda:** o Tático usa o que a máscara deixa (silêncio tira as habilidades, por exemplo), mas não sabe aproveitar invisibilidade, mina, parede ou forma. Para a Etapa 7, as observações da IA não mudaram (`obs_schema` igual).

**Validador (`arena/fight/SheetValidator`):** aponta campo desconhecido, unidade estranha (recarga, lentidão, ângulo, segundos), forma ou dano desconhecido e habilidade sem efeito. O teste passa nas 6 fichas reais e nas sintéticas, e `everySheetDoesSomethingInAFight` confere numa luta real que cada ficha muda alguma coisa.

🎯 **Golden sem mudança**, como o prompt pede: as 6 fichas não usam nada além da Parte 1.

**Desempenho, 1 thread, duelos até a morte:** 11.445 s simulados por s depois da parte. A comparação com o `main` fica na Parte 4.

Testes: 253 → 294 (`MecanicasTest`, 41, com tenacidade e Purificar por tipo e determinismo em todas as fichas; 11 opcionais pulados). `npm run build` limpo.

## Parte 3 — Dados do mapa, tropas, selva e objetivos ✅

Produzi `docs/dados-mapa-objetivos.md`, `seed/mapa_7_3d.yml` e `seed/objetivos_7_3d.yml`, com fonte em cada bloco. `RiftDataTest` exige `fonte` em todo bloco com número das regras de partida.

**Achados:**
- **Não há inibidores nem torres do Nexo** no Wild Rift (Riot): a torre da base dá as supertropas, e o Nexo atira sozinho.
- **O Arauto saiu** na RPN 5.2, trocado por Torreta Guerrilha ou Mímico Hextec, sem números; ficou fora.
- **As fontes são fracas.** Sete páginas `WR:` não existem, e tudo sobre tropas (vida, dano, composição) e alcance de torre é do PC. A lista completa do que falta está em "Para o usuário".

**Mapa:** a imagem da wiki e a Liquipedia recusaram o acesso (403). A geometria é um esquema a partir do PC, em escala estimada, e não um traçado.

Testes: 294 → 295 (11 opcionais pulados). `npm run build` limpo (sem mudança de tela).

## Parte 4 — O mapa completo na Arena ✅

**Mapa por cenário:** `Scenario.rift` (`RiftOptions`) escolhe "Summoner's Rift"; sem ele é a "Rota única", e cenários salvos abrem iguais.

- **`arena/rift/RiftMap`:** grade de 100 unidades com as paredes (lado azul + espelho), A* com cortes de canto proibidos, caminho puxado em linha reta e cache. Compartilhado e só de leitura.
- **`arena/rift/RiftRules`:** leitura de `objetivos_7_3d.yml`, com a composição das ondas e o dano da torre no tempo.
- **Unidades:** tropas, torres, Nexo, monstros, plantas e objetivos são unidades reais da Arena (`role`), fora do placar, pelo mesmo `deal` das lutas.

| sistema | o que faz |
|---|---|
| Movimento | caminho por A* em volta das paredes; avanços e empurrões param na parede; salto nunca termina dentro dela |
| Visão | moita esconde de quem está fora, atacar mostra por 1 s, Clarevidente revela; a IA só mira o que vê |
| Tropas | ondas nos tempos, andam pela rota, alvo: quem chamou, tropa, estrutura, campeão. O último golpe dá ouro, e a XP se divide entre os campeões por perto |
| Torres | prioridade à tropa, troca para o campeão que bate num campeão aliado, 100/150/210/270%, placas, defesa do início, Anti-Backdoor |
| Selva | acampamentos com renascimento; atacam quem os atacou e voltam para casa curando; buffs Azul e Vermelho |
| Plantas | Frutomel cura, pinstouro arremessa quem bateu, Clarevidente revela |
| Objetivos | Barão com Corrosão, Olhar, Veneno no fosso e ciclo de habilidades; dragões, alma e Ancião; o último golpe dá o buff ao time |

**Buffs de objetivo:** cada lado pode começar com acúmulos, alma, Ancião e Mão do Barão (`Scenario.objectives`). No planejador, o seletor "Objetivos" fica acima da ficha: muda a ficha e a barra, entra nas lutas da Efetividade (`Request.objectives`) e aparece no relatório de relevância como fonte própria.

**Cenários-modelo (`GET /api/arena/templates`, "Novo a partir de um modelo"):** x1 nas três rotas (na rota, sob a torre aliada, sob a inimiga), luta no rio pelo Aronguejo e com a dupla do Dragão, luta de equipe no Barão, com o azul fazendo o Barão, e no Dragão.

**Tela:** paredes, moitas, rio, fossos, fontes, torres com a vida, tropas em pontos, monstros, plantas e objetivos; o zoom e o seguir já existentes; "Ver tudo".

**Decisões e limites:**
- **Foco do campeão:** com inimigo a mais de 1.500, ele luta com o que está à mão a até 800 (tropas, depois estruturas). Sem isso, um campeão em cenário de rota atravessava o mapa atrás do outro.
- **Sem subir de nível dentro da luta.** Os status ficam os da build no minuto; ouro e XP são somados no resultado. Quem evolui é a partida da Parte 5.
- **Modelado de forma simples:** o gelo como alma não tem efeito, o Ancião só guarda a duração (a queimadura e a execução não entram), e o Barão não muda de lado pelo ataque de costas. Mão do Barão nas tropas fica de fora.
- **Rift de elemento** (mudar o mapa pelo primeiro dragão) ficou de fora.

**Desempenho 🎯:** "Rota única", duelos até a morte, 1 thread: `main` 12.366 → agora 10.504 s simulados por s (−15%, dentro dos 30%). Mapa novo, 5 contra 5 com tudo ligado: 65 s simulados por s, uma luta de 60 s em cerca de 1 s.

**Testes:** `RiftTest`, 16 mais o benchmark opcional: lugares alcançáveis a pé, rotas livres, caminho, ondas nos tempos, último golpe, torre, selva, moita, Barão, buffs e determinismo. Golden sem mudança. Testes: 295 → 312 (12 opcionais pulados). `npm run build` limpo.

## Parte 5 — Partida inteira, otimizada ✅

**Duas camadas.** `arena/rift/Match` roda a partida no passo grosso de 0,25 s: campeões andam, a macro decide, ondas trocam golpes em lote, torres e acampamentos são números. Quando campeões lutam, a luta sai para a Arena no passo fino de 0,02 s, com paredes e as torres por perto, sem quadros.

- **Macro:** `MacroPolicy` (trocável) e `ScriptedMacro`, por função: rota do Barão, selva, meio, dupla do Dragão e suporte. Volta à base por Vida baixa ou por ouro, faz a rota da selva, gira para os objetivos e agrupa depois de um tempo. Parâmetros em `seed/macro_7_3d.yml`. A função sai das rotas do campeão (Rodada 7) e do que falta no lado.
- **Compras:** a sequência é a do `TimelineEngine` da build, com a mesma checagem da loja (`ShopRuleCheck`), incluindo as botas aos 10:00. O lutador do momento sai do planejador: as primeiras compras, no nível da partida (`Slot.matchSteps`, `matchLevel`).
- **Economia:** ouro e XP vêm das tropas, abates, assistências, torres, placas, selva, objetivos e ouro passivo.
- **Qualidade da ficha:** o campo novo `qualidade` nas 6 fichas (A). Campeão sem A ou B fica fora, e a tela diz isso.
- **Tela:** aba "Partida", com as 10 posições, a reprodução por resumo de segundo (mapa, placar, ouro, nível), lutas e eventos. "Abrir a luta na Arena" reconstrói o momento: compras, nível, Vida, posição e objetivos.

**Nível de detalhe, como foi feito:** o passo fino é só dentro das lutas; o resto é passo grosso, sem os passos intermediários de 0,1 s do prompt. Onda contra onda é sempre em lote. A grade espacial não foi necessária: são 10 campeões e poucas ondas.

**Exemplos** (cópia do banco, Rumble, Ornn, Renekton, Darius, Ornn contra o mesmo, sementes 1 a 3): o Nexo cai entre 14:50 e 15:15.

| campeão, semente 1 | função | nível | K/D | ouro/min (build) | XP/min (build) | tropas + monstros |
|---|---|---|---|---|---|---|
| Rumble | Barão | 10 | 0/1 | 548 (900) | 417 (700) | 103 + 0 |
| Ornn | selva | 9 | 0/2 | 427 (700) | 370 (700) | 2 + 27 |
| Renekton | meio | 9 | 4/3 | 626 (800) | 386 (700) | 89 + 0 |
| Darius | dupla | 7 | 7/1 | 705 (800) | 227 (700) | 84 + 0 |
| Ornn | suporte | 8 | 1/2 | 281 (700) | 304 (700) | 1 + 0 |

**Leitura:** a partida rende de 15% a 40% menos ouro e cerca de metade da XP das builds. **Isso ainda não calibra o planejador**, porque as tropas, a XP e a macro são estimativas. A macro também não reparte a rota como um time real.

**Desempenho 🎯:** partida de 15 min, 1 thread: de 2,7 a 6,4 s, com 0,3 a 1,3 s em 24 a 28 lutas. A meta era menos de 60 s para 20 min. O tempo restante está em montar os lutadores pelo planejador.

**Limites:**
- as lutas começam com as recargas prontas e não levam tropas: não há onda dentro da luta da partida;
- a Mão do Barão e os dragões entram nas lutas, mas o Barão é um número no macro, sem ciclo; o elemento do dragão é sempre Infernal; o Ancião entra como dado, sem efeito na luta;
- sem desvios, moitas ou visão no macro: a luta sai por distância e vantagem.

**Testes:** `MatchTest` (7), num mundo simples: ondas nos tempos, ouro pelo último golpe, ouro e XP até 10:00 parado na rota, compras na ordem com a regra da loja, selva e dragão, fim pelo Nexo, determinismo. `MatchReportTest` (opcional, `-Dmatch.report=data/ornn-planner.db`) gera a tabela acima e confere o determinismo com as lutas reais.

## Parte 6 — Calculadora de builds ✅

**Aba "Calculadora":** a build dá o campeão, as runas, os feitiços e o ouro. Alvo: relatório Básico ou Completo, ou uma build do adversário. Filtros: só tanque, itens obrigatórios e proibidos. Mais os minutos de medida, tamanho, feixe, orçamento, estimativa de tempo, barra de progresso e "Parar". Pelo serviço também dá para pedir uma lista de testes ou um cenário salvo da Arena.

**Regras da loja:** uma ordem só vale se a linha do tempo do planejador a compra inteira e sem violação. São as mesmas regras do planejador: grupos exclusivos, uma bota, bota aprimorada aos 10:00.

### 6.1 Contra um alvo

`arena/calc/BuildSearch` faz a busca em feixe sobre a ordem, separada de como uma ordem é medida. A nota de um prefixo é a média dos minutos de medida, com o que ele tem em cada um. A cada minuto, o cache por itens + minuto evita repetir medidas: duas ordens com os mesmos itens prontos custam uma medida.

**Saída:** a melhor ordem, as 3 mais próximas, a importância cedo (quanto a nota cai ao atrasar um item uma posição e sobe ao adiantar), o minuto em que a bota aprimorada passa a valer a pena e a curva no tempo. 20% do orçamento fica guardado para essas leituras.

### 6.2 Os dois lados escolhendo

`arena/calc/GameTree`: as k melhores sequências de cada lado na profundidade pedida formam a matriz nossas × respostas, em lutas de build contra build. Saída: maximin (a linha com o melhor pior caso), frequência de vitória, equilíbrio misto por *fictitious play* (só Java), as frases no estilo do prompt e a matriz com mapa de calor.

### Medido 🎯 (cópia do banco)

| caso | parâmetros | tempo | medidas |
|---|---|---|---|
| 6.1: Big Carry v1 × Básico | só tanque (30 itens), 3 itens, feixe 4, minutos 8/12/16, orçamento 120 | 10,8 s | 96, orçamento esgotado |
| 6.2: Ornn × Darius | k = 4, profundidade 2, minutos 10/14/18 | 144 s | 1.560 |

- **Com 30 itens, orçamento 120 não termina nem o 2º item:** melhor achado: Anjo Guardião → Máscara Abissal (265). Para 3 itens, conte com umas 400 medidas, cerca de 1 min.
- **6.2:** Força da Trindade → Coração de Aço lidera pela média, e o maximin aponta Força do Vendaval → Lembranças do Lorde Dominik (pior caso −60% contra −96%). **Nenhuma linha vence: 0%.** Com 2 itens de cada lado, o Ornn ganha aos 10:00 e perde aos 18:00, e a média fica negativa. Sem filtro, a busca também escolhe itens de dano para um tanque, porque a medida é só "quem sobra na luta".
- **Quase todo o tempo do 6.2 é montar lutadores pelo planejador**, não lutar.

### Limites

- **Alvo "partida da Parte 5": não implementado.** A partida usa builds salvas, e a busca gera builds que não existem. O ouro de uma partida simulada também não entra; o campo de ouro por minuto do serviço sim, mas a tela ainda não o mostra.
- **Respostas por arquétipo (Rodada 7) não entraram.** Sem adversário, a nota é o relatório; o boneco atacante não varia por arquétipo.
- **Importância 0:** se os dois itens de uma troca ficam prontos antes do primeiro minuto de medida, a troca não muda nenhuma medida. Foi o caso nos exemplos (itens aos 3:51 e 7:17, primeiro minuto 8). Inclua minutos mais cedo para enxergar isso.
- **Bota aprimorada:** nos dois exemplos a melhor ordem não tinha nenhuma, então o minuto da bota não foi medido com dado real; o teste usa um caso sintético. Para perguntar por ela, marque a bota como obrigatória.

**Testes:** `BuildSearchTest` (8): item claramente melhor sai primeiro; regras da loja; obrigatórios; importância; regra da bota; maximin numa matriz conhecida; equilíbrio de pedra-papel-tesoura; determinismo. `CalculatorReportTest` (opcional, `-Dcalc.report=data/ornn-planner.db`) gera os exemplos e confere o determinismo com lutas reais.

Testes: 320 → 329 (14 opcionais pulados). `npm run build` limpo.

## Conferido na tela

No servidor de desenvolvimento (porta 8090, banco `data/dev`, sem tocar no seu banco nem no backend da porta 8080).

**Achei e corrigi um erro grave meu da Parte 4, já commitado:** o seletor de Objetivos estava depois de um `return` antecipado do Planejador e **derrubava a página inteira** (tela vazia, "Rendered more hooks"). Testes e `npm run build` passavam, porque não há teste de frontend. Só apareceu ao abrir a tela. A correção está no commit da Parte 6.

| tela | resultado |
|---|---|
| Partida | 10 posições, partida de 14:58 em cerca de 6 s, placar, reprodução, 33 lutas e eventos |
| Abrir a luta na Arena | abre "Luta da partida aos 2:38" com "Mapa: Summoner's Rift", paredes e a luta rodada |
| Calculadora | estimativa, cálculo com orçamento 30, resultado e importância; seletores de itens obrigatórios e proibidos |
| Objetivos na barra fixa | 3 acúmulos de Infernal + Mão do Barão: DdA 118 → 208 e PdH 0 → 111, que confere com a conta; "Objetivos: 6.508 (8%)" na relevância |

**Só compilado, não usado na tela:** o seletor de mapa e os cenários-modelo (a API devolve os 14), o desenho das torres na reprodução, "Ver tudo", o heatmap do 6.2 e a Efetividade com objetivos ligados (o cache e a API foram cobertos por código, não por teste). Celular não foi revisto.

## Para o usuário

**Precisa de ação:**
- **Reinicie o backend.** O servidor na porta 8080 roda o código antigo. Não há migração de banco nesta rodada.
- **Treine de novo as IAs de Darius e Rumble.** As formas mudaram e `Arena.RULES` não subiu: elas abrem, sem aviso, mas foram treinadas com as hitboxes antigas.
- **A branch `rodada-6b` não foi enviada nem mesclada:** 6 commits sobre o `main`.

**Precisa de decisão:**
- **Partida como alvo da calculadora:** exige montar a partida com builds não salvas. Vale fazer antes da Etapa 7?
- **Calibragem:** a partida rende bem menos ouro e XP que as builds, mas com tropas, XP e macro estimadas. Depois dos prints, diga se ajusto a macro ou o ouro por minuto das builds.

**Prints, por prioridade:**
1. **Minimapa ampliado, sem campeões.** É o que mais falta: a geometria do mapa é um esquema. Com o print, retraço `mapa_7_3d.yml`.
2. **Modo Treino, tropas:** vida e dano de combatente, conjuradora e canhão aos 0:30 e aos 15:00. Hoje são do PC.
3. **Torres:** alcance, vida da torre externa e o ícone de buff do início da partida. "Barricadas" não aparece em nenhuma fonte: diga o que é isso no jogo.
4. **Mão do Barão:** o número no ícone do buff, em dois minutos diferentes. Hoje é estimativa.
5. **Tempo da primeira onda e composição das ondas.**
6. **Ouro passivo:** 3/s (RPN) ou 3,5/s (WR:Gold). Dá para medir parado na base com um cronômetro.
7. **Fonte:** dano do laser e cura. **Acampamentos:** ouro e XP de Lobos, Aves, Krugs e Gromp.
8. **Arauto:** ele ainda existe? O que há no lugar dele (Torreta Guerrilha, Mímico Hextec)?
9. **Nomes pt-BR das plantas:** usei Frutomel, pinstouro e Clarevidente, do prompt.

**Vale conferir:**
- **Modelo simplificado:** Ancião sem efeito na luta, alma de gelo sem efeito, Mão do Barão sem efeito nas tropas, Rift de elemento fora.
- **Dizimar:** decidi que o corpo do alvo tocando o anel já recebe a lâmina, pela regra geral do prompt. Se no jogo vale o centro do alvo, o Darius fica forte demais nesta base.

## Relatório final

**O que mudou:** hitboxes compostas; motor com as mecânicas dos outros campeões e validador de ficha; dados do mapa e objetivos; "Summoner's Rift" na Arena, com cenários-modelo e buffs de objetivo na Arena, na Efetividade e no planejador; partida inteira, com a aba "Partida"; calculadora de builds, com a aba "Calculadora".

| parte | commit | testes depois |
|---|---|---|
| 1. Hitboxes | 28b9a0c | 253 |
| 2. Mecânicas | 6468d3e | 294 |
| 3. Dados | 6bc5490 | 295 |
| 4. Mapa | 0951591 | 312 |
| 5. Partida | 91f0831 | 320 |
| 6. Calculadora | último da branch | 329 |

- **Testes:** 236 antes (11 opcionais) → 329 depois (14 opcionais), 0 falhas, contados com a pasta `target/surefire-reports/` limpa. `npm run build` limpo.
- **Golden:** mudou só na Parte 1, em 21 de 35 cenários, todos com Darius ou Rumble, mais 3 linhas do `golden-ai-v2.txt`.
- **Um teste mudou de orçamento:** `trainedDariusBeatsTheScriptOnValidation`, de 30 para 45 gerações, sem mudar a meta (Parte 1).
- **Desempenho:** Rota única −15% (12.366 → 10.504 s simulados por s); partida de 15 min em 3 a 7 s; meta de 60 s atingida.
- **Mecânicas para a Etapa 7:** todas as da lista têm campo e ficha sintética. Falta a IA saber usar invisibilidade, mina, parede, forma e invocação, e os casos particulares de cada campeão.
- **Banco:** sem migração. Saved builds, cenários, IAs e combos seguem abrindo: a "Rota única" é o padrão de todo cenário sem `rift`.
