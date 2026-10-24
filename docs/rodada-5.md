# Rodada 5 — andamento

Venda de itens, relevância com passivas repassadas, legenda das recargas, elemental e "fotos de luta". Um commit por
parte; cada parte termina com `./mvnw test` e `npm run build` limpos.

**Decisão geral:** o prompt pede o branch `main`, mas o commit revisado (3f78d89) é a ponta do `arena-ia`, que ainda não
foi mesclado no `main`. A rodada segue no `arena-ia`.

**Testes antes da rodada:** 201 (8 opcionais pulados).

## Parte 1 — Venda que convive com a compra automática de meios itens ✅

**Motor (`TimelineEngine`):**
- Cada venda tem `sellWhen`: `after` (logo depois da compra anterior, o de antes) ou `afford` (quando der para
  comprar o próximo item inteiro). Vendas salvas antes ficam com `null`, que é `after`: o resultado é o mesmo (teste
  que compara o JSON inteiro da linha do tempo).
- `afford`: a venda acontece em `max(compra anterior, momento de compra, minuto em que o ouro ganho cobre o já pago +
  (C − D − troca de bota − R))`. `D` sai do mesmo `consume` que a compra usa, sobre o inventário já sem o item
  vendido. O próximo item cai no mesmo minuto sem conta nova: o acumulado depois da venda + o que ele custa é o mesmo
  alvo.
- O próximo item é comprado sem componentes supostos (`assumption = "sale"`). Um trecho manual que o cubra prevalece;
  a venda registra o trecho (`saleTiming.manualAssume`) e o aviso diz isso.
- Com fim de partida e ouro que não chega: a venda e a compra vão para `ignored` com `saleNeverAffords`; o item fica
  no inventário.
- **Trechos:** em vez de uma lista de intervalos na build, cada compra guarda a sua suposição (`build_step.assume`:
  `whole`, `half`, `small` ou nada = padrão). Decisão: assim o trecho acompanha os itens ao reordenar e remover, sem
  remapear posições. A tela agrupa as compras seguidas com a mesma suposição e mostra "#3–#5 · só itens inteiros".
  "meios itens" = só meios itens; "itens pequenos" = só itens básicos (o meio item que falta vira as suas partes),
  como as duas caixas da build.
- `MinutePoint.goldInHand`: o ouro em mãos em cada ponto da série (ganho − pago pelas compras e vendas feitas).
- Migração v21: `build_step.sell_when` e `build_step.assume` (TEXT, nulos).

**Tela:**
- Na venda da sequência, o botão "logo depois" / "próximo item inteiro" (vendas novas já nascem com o segundo),
  "precisa de 2.850" e, no título, a conta inteira e o ouro em mãos logo depois da compra anterior.
- "+ trecho de suposição": de / até / supor; a lista de trechos com ✕; nos itens, ▣ (inteiro), ½, · e ▣$ (inteiro por
  causa da venda).
- Linha do tempo: "precisa de N" na venda e a conta no detalhe.
- Barra fixa: entre a compra anterior e a venda, "ouro em mãos X de N · faltam Y · Chega a isso aos mm:ss".
- Gráfico: uma linha tracejada "$ Item" no minuto de cada venda, e o ícone da venda acinzentado na faixa de eventos.

**Exemplo real** (build salva "Sup Rush Warmog", 600 de ouro/min, meios e pequenos supostos; o Bastião da Montanha é
vendido antes da Duplaguarda de Amaranto):
- antes (`after`): o Bastião é vendido às 22:05, logo depois do Manto da Meia-noite; com os 350 o motor já compra
  Couraça de Pano (22:20) e Cota de Malha (23:00), e a Duplaguarda sai às 26:50 pagando 2.300;
- depois (`afford`): "Vender Bastião da Montanha devolve 350. Para comprar Duplaguarda de Amaranto (custo 3.200, já
  tem 0 em componentes), precisa de 2.850 de ouro em mãos. Chega a isso aos 26:50." Logo depois do Manto (22:05)
  há 0 em mãos, faltam 2.850. A venda e a Duplaguarda inteira (3.200) saem às 26:50, sem componentes no meio. O
  item de suporte fica no inventário de 22:05 a 26:50.

**Testes novos (`TimelineEngineTest`):** venda `afford` sem componentes no meio e no minuto exato; com um componente
no inventário (desconto); `after` sem mudança e igual ao de builds sem a escolha; trecho "só itens inteiros" numa
build com meios itens (e "itens pequenos", e o trecho manual que prevalece depois da venda); ouro que nunca chega.
Testes: 201 → 206. Golden sem mudança.

**Para testar sem mexer no backend em execução:** `.claude/launch.json` ganhou `backend-dev` (porta 8090, banco em
`backend/data/dev/`) e `frontend-dev` (5174, `API_TARGET` no `vite.config.js`).

## Parte 2 — Relevância com o valor das passivas repassado aos itens ✅

**Conta no backend (`TimelineEngine`):**
- O `Resolver`, ao somar cada status, guarda de onde vem cada parte (`Model.Attribution`, fora do JSON): a composição do
  total de cada status ("base" do campeão, os status planos de cada dono, o valor de cada passiva, a Forja do
  status), a composição da Forja de cada status e, para cada passiva com `ref`, a composição do `ref` que ela leu. São
  os mesmos termos que já somam o valor; não há fórmula paralela.
- `TimelineEngine.relevance(ponto, fontes, preços)` reparte cada fonte ligada pelos termos do que ela lê:
  - **Forja Viva:** cada item recebe a Forja sobre os seus próprios status; a parte da Forja que amplifica o ganho de
    uma passiva de % do adicional (Duplaguarda) vai para essa passiva;
  - **passiva com `ref`:** `valor × (o que cada um dá do status ÷ o ref total)`; a base e o nível vão para a linha
    "Campeão (base e nível)", a parte de outra passiva vai para o dono dela, ou passa por ela quando ela também está
    ligada;
  - **ciclo** (a Forja amplifica a Duplaguarda, e a Duplaguarda lê a Forja): decisão — em vez de parar e deixar o
    resto com a fonte, a conta segue o ciclo rodada após rodada até a parte ficar desprezível (< 1e-9, ou 40 voltas).
    O que sobra é menos de 0,001 de ouro.
- Cada `contribution` ganha `inherited: {fonte: {stats, gold}}` e o dono de uma fonte repassada, `repassed` (o valor
  inteiro dela). `forgeRepassed`, `forgeInherited` e `champion` cobrem as linhas da Forja e do campeão; `grand` é a
  soma de tudo, igual com ou sem repasse.
- `POST /api/relevance {build, minute, sources}`: o rascunho da build, o minuto e as fontes; responde as fontes
  disponíveis (com o valor de cada uma) e o relatório.

**Tela (aba Relevância do momento):** a caixa "Repassar as passivas que escalam com outros status" mostra uma ficha
por fonte (Forja Viva e cada passiva com `ref`, com o valor). Desligada, ou sem ficha ligada, é o relatório de antes.
Com fichas ligadas: colunas Próprio, Recebido de passivas, Total, Fatia e Valor / custo (sobre o total), a linha
"repassado aos itens" na fonte, "Campeão (base e nível)" quando recebe algo e o "Total geral". Clicar numa linha
abre "inclui 2.373 de Forja Viva (Ornn), 862 de Duplaguarda de Amaranto: Tolerância" e os status recebidos.
A barra fixa não mostra o relatório de relevância (só o valor de cada item na leitura), então a opção fica só na aba.

**Big Carry v1 aos 23:30 (nível 15), Forja Viva repassada** (22%, 6.421 de ouro):
- Suserano do Gelo (runa, defesas condicionais ligadas) +2.373 (56 de Armadura, 38,9 de RM);
- Duplaguarda de Amaranto +1.565 (66 de Vida, 30,3 de Armadura, 23,5 de RM: a Forja sobre os status dela e sobre o
  ganho da Tolerância);
- Desespero Eterno +660; Armadura de Espinhos +559; Coração de Aço +513; Égide de Fogo Solar +476; Esmagadores
  Acorrentados +275. Soma: 6.421. O total geral fica em 44.956 com e sem repasse.
- Com a Tolerância também repassada (3.614): a Duplaguarda fica com 847 dela; Suserano 862, Desespero 677, Armadura
  de Espinhos 635, Égide 339, Esmagadores 254.

**Testes novos (`TimelineEngineTest`):** a Forja de um item é a Forja sobre os status dele e a soma repassada é o valor
da Forja; a passiva de 6% da Armadura adicional dividida entre os itens (o item sem Armadura recebe 0) e a base indo
para o campeão; fontes encadeadas mantêm o total geral, sem nada ligado as contribuições são idênticas às de antes.
Testes: 206 → 209. Golden sem mudança.

## Parte 3 — Arena: legenda das recargas por clique, ligada ao mapa ✅

**O que a luta passou a registrar (backend):** cada uso de habilidade, ativo de item e feitiço vira um `Use` do
lutador (`FighterStats.uses`: segundo, chave da recarga, nome, dano depois das resistências por alvo, controles
aplicados, escudo dado). Só com quadros (`recordFrames`): treino e busca não pagam nada. O dano de uma fonte "slot ·
nome" ou do nome do ativo / feitiço vai para o último uso dela; o controle, para a última habilidade conjurada. É
aproximado: um projétil lançado antes de um segundo uso e que acerta depois dele conta para o segundo. `uses` é
descritivo e fica fora da impressão digital do golden (`ArenaScenarios.DESCRIPTIVE`); o golden não mudou.

**Tela:**
- A coluna da esquerda das listras mostra o nome da habilidade em letra pequena e, colado à listra, o slot (1, 2, 3,
  R) ou o ícone de 16 px do item ou do feitiço. A coluna é fixa ao rolar, então o rótulo fica sempre no começo da
  listra. A faixa ficou um pouco mais alta (16 px) para o clique e o toque.
- Clicar numa listra abre o cartão (`CooldownCard`) junto ao ponto clicado, preso dentro da janela (também em tela
  estreita): ícone, nome, campeão, o estado naquele segundo, cargas, o último uso e o que fez, o próximo uso e "Mais
  detalhes". Os detalhes reaproveitam o `Ability` do `ChampionPanel` (exportado; rank do plano da build no nível da
  luta, valores com os status do ponto do planejador no minuto da build) e o `ItemReader` da barra fixa; para
  feitiços, o texto do catálogo. Acrescenta "Na luta: recarga de 48,2s com 16 de Aceleração de Habilidade".
- "Pausar ao clicar" ao lado do relógio, ligado por padrão e lembrado no navegador.
- Mapa: clicar numa forma (pelo rótulo "Nome (Campeão)", o id do slot, "elemental" → R, "pilar" → 1) ou num campeão
  (o que ele conjura, senão o último uso) abre o cartão e destaca a listra, rolando até ela. Com o cartão aberto, a
  forma daquela habilidade fica com contorno branco grosso e o campeão com um anel tracejado.
- Esc ou ✕ fecham o cartão; Enter numa listra (teclado) também o abre.

**Sem pesar a execução:** as listras são calculadas uma vez por resultado (memo) e, agora, as linhas da faixa de tempo
também: a reprodução só move um cursor único sobre todas elas (antes cada linha tinha o seu cursor e a faixa inteira
era desenhada a cada quadro). O cartão só existe aberto; não há handler por quadro.

**Tempo de quadro** (cenário 3v3 de 60 s: Ornn Big Carry, Darius, Renekton contra Rumble, Kalista, Ornn; cérebro
Tático; 59 listras). Medido com um `Profiler` do React em volta do palco (mapa + faixa), só em desenvolvimento
(`window.__arenaRenders = []` no console liga a coleta), em 150 atualizações de tempo ao longo da luta: o painel do
navegador estava oculto, e o `requestAnimationFrame` não anda em segundo plano, então medi o tempo de render de cada
atualização de tempo (o mesmo trabalho de um quadro da reprodução), não o intervalo entre quadros.
- antes: média 15,8 ms, mediana 16,6, p95 20,7, máximo 22,0;
- depois: média 7,3 ms, mediana 7,9, p95 10,5, máximo 13,4.
Build de desenvolvimento do React (a de produção é mais rápida nos dois casos).

**Teste novo (`ArenaRulesTest`):** sem quadros nada é registrado; com quadros, a soma do dano dos usos de cada slot é
o dano por fonte daquele slot. Testes: 209 → 210. Golden sem mudança.

## Parte 4 — Elemental de lava do Ornn: comprimento real ✅

**Antes:** desenho de 120 de comprimento por 340 de largura; acerto por um círculo de raio 170 varrido pelo caminho.

**Agora:**
- `comprimento: 480` na R do Ornn (`kits_7_3b.yml`), com `comprimento: usuário (≈ 4× o desenho anterior)` nas
  `fontes`. Sem o campo, 120 (o desenho de antes).
- **Decisão:** o ponto que anda (x, y) passa a ser o **meio do corpo**. Assim a ida e a volta usam o mesmo retângulo
  sem mudar de lugar: a cabeçada só o vira em torno do meio. (Com a frente no ponto de hoje, a volta teria de
  reposicionar o corpo inteiro.) A frente fica 240 à frente do ponto, contra 170 do círculo antigo.
- O mesmo retângulo no desenho e no acerto: comprimento ao longo do caminho, largura atravessada, varrido a cada passo
  (do fundo do corpo antes do passo à frente depois dele, alargado pelo raio do alvo; `elementalSweeps`).
- Também usam o comprimento: a previsão da conjuração (a frente chega meio corpo antes), a reconjuração —
  `reconjuracao_acertaria` (o alvo atrás do meio vale até meio corpo), o alcance da cabeçada (distância do Ornn ao
  retângulo, `elementalGap`, nos dois testes: esperar e devolver) — e o desvio (`threat`: medido da frente; quem o
  corpo já cobre conta como atingido agora).

**Golden:** mudaram os 13 cenários com a R do Ornn — os 10 duelos com o Ornn (Ornn × Darius, Renekton, Rumble,
Vayne, K'Sante e as voltas), "3v3", "orders + autopilot" e "long Ornn x K'Sante". Nos duelos o resumo (vencedor,
tempo, vida, dano) é igual, salvo Ornn × K'Sante (dano do Ornn 3.339 → 3.306); a diferença está nos quadros (o
desenho do corpo). No 3v3 a luta mudou: vitória do lado vermelho aos 19,9 s em vez de 16,5 s. Regravado com
`-Darena.golden.write=true`. O `golden-ai-v2.txt` (`ArenaPolicyMigrationTest`) mudou em "duel Ornn x Darius" e "3v3":
o teste ganhou o mesmo `-Darena.golden.write=true` (antes não tinha caminho para regravar) e o Javadoc diz que só se
regrava quando as regras mudam de propósito, nunca pela migração.

**Prova do Ornn** (`OrnnProofTest`, `-Darena.proof=true`; boneco de pano nos 4 comportamentos):

| | antes | depois |
|---|---|---|
| combo manual (calibrado): nota | 4.411 | 5.630 |
| manual, parado: dano / Frágeis / controle | 4.217 / 3 / 2,62 s | 4.217 / 3 / 2,56 s |
| manual, anda | 150 / 0 / 1,26 s — "passo impossível: reconjurar:R" | 4.064 / 3 / 2,72 s, feito |
| manual, foge | 4.217 / 3 / 2,24 s | 4.217 / 3 / 2,18 s |
| melhor achado: nota | 5.631 | 5.641 |
| melhor achado, parado: dano / Frágeis / controle | 4.370 / 3 / 2,06 s | 4.370 / 3 / 2,06 s |
| melhor / manual | 1,276 | 1,002 |

O ganho grande é o boneco que anda: antes o elemental de 120 não chegava perto o bastante para a cabeçada, e o combo
manual parava na reconjuração. A busca acha o mesmo combo (R → 1 → R² → 3 → 2 → ataques), agora praticamente
empatado com o manual. O teste do combo manual com 3 Frágeis segue passando.

**IAs salvas:** continuam abrindo e lutando. Migração v22: `arena_policy.arena_rules` (`Arena.RULES = 2`, gravado em
cada versão nova). Uma versão do Ornn sem a marca mostra "⚠ elemental antigo" na lista e no seletor de cérebro,
com o título "Treinada quando o elemental de lava do Ornn tinha 120 de comprimento (agora 480, Rodada 5)…". No banco
de teste, as 66 versões do Ornn ficam marcadas; as de Rumble e Darius não.

**Teste novo (`ArenaTest`):** um boneco parado 300 atrás do ponto onde o elemental surge é atingido (o círculo antigo,
170 + 80, não chegava; o corpo, 240 + 80, chega) e o desenho tem 480 × 340. Testes: 210 → 211 (os mesmos 8 opcionais
pulados).

## Parte 5 — "Fotos de luta": status realistas da build, do planejador para a Arena ✅

### 5.1 "Testar na Arena"
- O painel "Testar esta build num cenário ou combo" saiu; no lugar, o botão **"Testar na Arena"** com o seletor do
  oponente (`TestInArena`), na barra fixa da build e no topo da aba Testar do momento. Usa a build salva, no minuto
  selecionado no gráfico, com o cérebro **Tático**; abre a Arena num cenário novo (sem `id`, "Teste: Big Carry v1
  aos 23:30"), que roda e toca sozinho. Oponentes:
  - **boneco de pano** (padrão): 10000 de vida, 100/100, comportamento novo **"ataca"** (anda até o campeão e acerta
    um ataque básico de 100 de dano físico a cada 1 s; `Dummy.attackDamage`, velocidade de ataque 1,0). Os dois começam
    a 800 um do outro, no meio do mapa, e a luta dura 30 s;
  - **outra build salva** no mesmo minuto (também Tático), 40 s;
  - **um cenário salvo**: a build entra no slot do seu campeão (as opções vêm de `/api/lab/test-options`, como antes).
- A Arena aceita slots de boneco nos cenários (antes todo slot precisava de build): o slot mostra vida, resistências,
  comportamento e o dano do ataque, editáveis.
- **Decisão:** sai também o "combo da biblioteca contra o boneco" que o painel antigo tinha; o laboratório da Arena
  continua com ele. O `POST /api/lab/test` fica (o Ranking usa).
- A "Visão sob efeitos" continua como estava.

### 5.2 Status ao longo da luta
- **Backend:** em cada quadro (`frame()`), `track(u)` grava os status efetivos que a luta usa
  (`ArenaModel.TRACKED`: Vida máx., Armadura, RM, DdA, PdH, ataques por segundo, velocidade de movimento com lentidões
  e acelerações, escudo restante, redução de dano ativa), **só o que mudou** desde o quadro anterior
  (`FighterStats.statTrack`), e quando cada efeito condicional liga e desliga (`toggles`: gatilhos do planejador,
  status atrasados e formas). Sem quadros, nada é calculado. `FighterBuilder.arenaBuild` agora guarda a ficha do
  planejador com todos os efeitos que a luta decide desligados (`sheetAllOff`, o ponto de partida da luta) e todos
  ligados ao mesmo tempo (`sheetAllOn`, uma consulta a mais ao planejador por lutador), convertidas para as mesmas
  chaves (`sheetOn` / `sheetOff`). Os quatro campos são descritivos e ficam fora da impressão digital do golden.
- **Tela (`FightStats`, no relatório):** por campeão, a "foto" no segundo do cursor (status e efeitos ligados), as
  faixas de cada efeito ("Suserano do Gelo: 2,5–5 s", "Duplaguarda de Amaranto · Tolerância: liga aos 5,9s"), um
  gráfico por status que muda (com as faixas coloridas e o cursor), os que não mudam numa linha, e a tabela ficha tudo
  ligado / tudo desligado / média na luta (ponderada pelo tempo) / pico real com o segundo.
- **Decisão (pico):** o pico simultâneo real é o segundo em que os status estão mais altos juntos (soma de Vida com
  escudo, Armadura, RM, DdA e PdH, cada um sobre o valor do início). Contar só quantos efeitos estão ligados escolhia o
  segundo errado: na Big Carry, o Suserano sozinho dá mais Armadura que as duas linhas da Duplaguarda.
- O relatório da luta passou a ser desenhado uma vez por resultado; só esta seção acompanha a reprodução.

**Big Carry v1 (23:30, nível 15) contra o boneco que ataca** (Tático, vitória aos 16,1 s):

| | ficha, tudo ligado | ficha, tudo desligado | média na luta | pico real (2,5 s) |
|---|---|---|---|---|
| Vida | 7.326 | 7.326 | 7.326 | 7.326 |
| Armadura | 826 | 366 | 472 | 677 |
| RM | 541 | 214 | 282 | 430 |
| Escudo | 1.131 | 0 | 22 | 118 |

No pico estão as defesas do Suserano do Gelo (2,5–5 s, depois de imobilizar); a Duplaguarda liga aos 5,9 s (5 s depois
de entrar em combate) e fica até o fim. Os dois nunca valem juntos nessa luta: o Suserano volta só 20 s depois, e a luta
acaba antes. A ficha "tudo ligado" (826 de Armadura) nunca acontece; a média fica perto da ficha desligada.

### 5.3 Minuto da build no slot
- "aos" (com o `MinuteInput`) ao lado do seletor de build; embaixo, "aos 23:30: nível 15 · 16.950 de ouro gasto" e os
  ícones dos itens nesse minuto, e um mini-gráfico da Vida máxima ao longo da partida, em que clicar escolhe o minuto
  (a linha dourada marca o escolhido). A linha do tempo de cada build vem do planejador, em cache pela data da última
  gravação. Vale para todos os slots, inclusive os dos cenários do "Testar na Arena".

**Testes novos (`ArenaTest`):**
- a Armadura da Duplaguarda sobe só depois de 5 s em combate, e cada golpe do boneco (100 físico) tira
  `100 × 100 / (100 + Armadura do quadro)` — os status do quadro são os que o dano usa naquele instante; sem quadros,
  nada é gravado;
- as defesas do Suserano duram 2,5 s e não voltam antes de 20 s (o segundo atordoamento, aos ~10 s, não liga nada).
Testes: 211 → 213. Golden sem mudança nesta parte.

## Fechamento
- Testes: 201 antes da rodada → 213 depois (os mesmos 8 opcionais pulados); `npm run build` limpo em todas as partes.
- Golden: mudou só na Parte 4 (elemental), regravado; as Partes 3 e 5 acrescentaram campos descritivos fora do hash.
- Migrações: v21 (`build_step.sell_when`, `build_step.assume`) e v22 (`arena_policy.arena_rules`).
