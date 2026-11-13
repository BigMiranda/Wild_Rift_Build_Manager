# Rodada 16: atlas de interações de condições

Proposta a partir das conversas de 13/11/2026. A primeira parte já existe: o código geral e o piloto Ornn × Rumble,
descritos em "Parte 1" no fim deste documento.

## A ideia (do usuário)

A Descoberta busca builds e mede cada uma. O atlas mede **condições**: status e capacidades, interagindo entre si numa
simulação fechada. Não mede builds.

1. **Condições possíveis:** de todas as combinações de builds e de runas de cada campeão, os valores que cada condição
   pode atingir em cada minuto.
2. **Bonecos de condições:** cada combinação vira um boneco que carrega aqueles valores.
3. **Lutas:** os bonecos lutam, e cada condição, sozinha (busca linear) e em pares (busca quadrática), ganha uma **nota
   de interação**.
4. **Montagem:** com as notas, encontrar as condições mais eficazes contra um inimigo e então montar builds, runas e
   feitiços que as tenham, e propor campeões que as tenham.

A diferença para a Descoberta é que o mapa é das condições, não das builds. Ele é medido uma vez e reaproveitado por
qualquer campeão que tenha aquelas condições.

## O tamanho do espaço (números do catálogo 7.3f)

| O quê | Quantos |
|---|---|
| Itens finais | 109 |
| Conjuntos de 5 itens finais | 117 milhões; com botas, perto de 1 bilhão |
| Páginas de runas (13 fundamentais, 39 runas em 4 árvores) | da ordem de 10 mil |
| Builds × runas, por campeão | da ordem de 10¹³ |

Lutar cada combinação é impossível. Mas não é preciso: muitas builds levam aos mesmos valores de condição. O
espaço que importa é o das condições:

| Condições | Quantas |
|---|---|
| Tipos de status nos itens | 20 |
| Tipos de efeito de item (Feridas, reflexo, corte de escudo, redução de resistência, amplificação, queimadura, execução, redução de dano, redução de velocidade de ataque, ao contato, a cada ataque, golpe, crítico, lâmina, aura, ativo…) | 18 |
| Efeitos de runa modelados | 25 |
| Capacidades das fichas (avanços, invisibilidade, camuflagem, revelar, arremesso, atordoamento, supressão, inalvejável, estase, outra dimensão…) | cerca de 15 |
| **Total** | **70 a 80** |

Cada condição tem poucos valores possíveis. Com 3 a 4 níveis por condição (nenhum, típico, alto, máximo atingível no
minuto), o espaço fica assim:

| Busca | Combinações | Lutas (com níveis) |
|---|---|---|
| Linear (uma condição por vez) | 80 condições × 4 níveis | ~320 |
| Quadrática, mesmo lado | C(80,2) = 3.160 pares × 9 níveis | ~28 mil |
| Quadrática, cruzada (minha × do inimigo) | 80 × 80 × 9 | ~58 mil |
| Cúbica, para comparar | C(80,3) × 27 | ~2,2 milhões |

**Velocidade da Arena:** o benchmark da Rodada 4 mede cerca de 2.900 lutas de 60 s por segundo com 20 threads, com o
roteiro. Com o cérebro tático, sem medida ainda, deve ficar entre 300 e 600 por segundo. Isso dá, para um matchup:

| Busca | Tempo |
|---|---|
| Linear | segundos |
| Quadrática completa | 3 a 5 minutos |
| Quadrática com 3 sementes | 10 a 15 minutos |
| Cúbica | 1 a 2 horas |

A quadrática não precisa ser exaustiva. Um sorteio de condições com regressão (planejamento de experimentos) estima as
notas lineares e de pares com 25 a 40 mil lutas. Dá para rodar as duas formas e comparar.

## Kits genéricos por classe

São **viáveis** e até mais baratos. O problema é a fidelidade: o valor de uma condição depende do kit.

- A tenacidade vale conforme o controle que o inimigo tem.
- A redução de velocidade de ataque vale conforme ele dependa de ataques básicos.
- O Frágil do Ornn e o superaquecimento do Rumble não existem num "tanque genérico" nem num "mago de batalha
  genérico".

Kits genéricos dão notas médias da classe: servem como ponto de partida, mas não dizem "contra o Rumble".

A proposta do usuário fica no meio-termo: bonecos **derivados de cada campeão**. O kit vira condições:

- dano por segundo de cada tipo;
- controle por segundo e de que tipo;
- avanços, cura e escudos próprios;
- dependência de ataques básicos.

Os itens e as runas vêm por cima. Cada boneco é simplificado, mas carrega o perfil do campeão.

## Riscos

1. **Boneco ≠ campeão.** Se as lutas de bonecos não acompanham as lutas de verdade, as notas não valem. Por isso o
   piloto mede a correlação entre as duas.
2. **Interações de ordem 3 ou mais** (crítico × velocidade de ataque × Gume do Infinito) ficam de fora da quadrática.
   - Pacotes conhecidos (crítico + Gume) podem entrar como uma condição composta.
   - A cúbica cabe num matchup, então dá para medir quanto ela acrescenta.
3. **Tempo da build.** As notas são por minuto: aos 10 minutos só existem 1 ou 2 itens, e o catálogo de cada minuto
   limita o que é atingível.
4. **Capacidades nos bonecos.** Os bonecos de hoje atacam, conjuram pulsos de dano, cura, escudo, controle e execução.
   Avanços, invisibilidade, inalvejável em janela e supressão precisam entrar neles. A Arena já tem tudo isso para os
   campeões.
5. **A rota reta.** A Arena é uma rota reta, então capacidades de posicionamento (avanço para escapar, invisibilidade)
   valem menos do que no jogo.

## Piloto: Ornn × Rumble

| Etapa | O quê | Custo estimado |
|---|---|---|
| 1. Catálogo | as condições e os níveis atingíveis por Ornn e Rumble nos minutos 10, 15 e 20, lidos das combinações de itens que cabem no ouro de cada minuto (aos 15 min, ~6 mil pares de itens; aos 20, ~200 mil trios, enumeráveis) e das runas | segundos |
| 2. Bonecos | o kit de cada um convertido em condições (pelo perfil das fichas e pelo `AbilityParser`); as capacidades que faltam nos bonecos | o maior trabalho de código |
| 3. Validação | o boneco do Ornn contra o do Rumble, comparado às lutas reais Ornn × Rumble das builds da campanha: a correlação diz se dá para confiar | minutos |
| 4. Linear e quadrática | as duas buscas, mais a amostrada com regressão, com tempos medidos | minutos a 1 hora |
| 5. Notas | as tabelas de notas (lineares e de pares) e as condições mais eficazes para cada lado | segundos |
| 6. Montagem | builds e runas do Ornn que juntam as melhores condições contra o Rumble (e o contrário), conferidas em lutas reais contra o que a Descoberta achou | minutos |

**Critério para seguir:** a correlação boneco × real alta o bastante, e as builds montadas pelas notas empatando ou
ganhando das da Descoberta, gastando menos tempo. Com isso, o atlas cresce para os 11 campeões, depois para lutas de
equipe, e vira uma busca no site estático.

## Parte 1: o código geral e o piloto Ornn × Rumble (✅ código; piloto rodando)

Decisões do usuário (13/11/2026):

- **Código geral:** o código abrange todos os campeões, mas só com as condições já configuradas. Invisibilidade,
  revelar e as outras capacidades ainda não modeladas ficam de fora.
- **Contexto fixado:** cada execução tem um contexto fixado na configuração, não no código: os campeões, o modo (x1) e
  os minutos.
- **Entregas:** um relatório de 1, 2 e 3 dimensões, as builds de cada lado e dois guias interativos. Neles se fixa um
  momento à frente (Ornn com Rookern, Rumble com Liandry) e as notas são recalculadas a partir daí.

**Como ficou** (pacote `arena/atlas`, seed `atlas_7_3d.yml`, tabela `atlas_run`, tela Simulações › Atlas):

- **Contexto da execução:** os dois campeões, o modo e os minutos vêm do seed (`execucoes`) ou do corpo do pedido. A
  base de cada lado é a padrão da última campanha (`CampaignService.standardBuild`), sem itens: ela dá runas, feitiço,
  ordem de habilidades e o nível no minuto.
- **Condições** (`AtlasCatalog`), lidas dos seeds:
  - status em dois níveis (o que um item dá e o dobro);
  - a passiva de cada item final ou bota;
  - as runas fundamentais, as runas e os feitiços que a base não tem.

  No piloto são 157 para o Ornn e 158 para o Rumble.
- **Como cada condição entra na luta:**
  - o lutador segura exatamente os itens das condições (`Combatant.holdItems`), no lugar das compras da build;
  - um status entra como item sintético só com ele (`extraItems`);
  - uma passiva entra como o item de verdade com os status fixos dele tirados (`ItemEdit` negativo);
  - uma runa ocupa a linha dela da árvore principal ou a vaga secundária;
  - um feitiço ocupa o lugar do que não é o Flash.

  As regras da Arena não mudaram e o golden é o mesmo.
- **Medida:** a corrida das barras de vida (Rodada 15), na visão do lado A, média de `largadas` lutas.
  - Sozinhas: todas.
  - Pares: todos os compatíveis (`top_2d: 0`); dois níveis do mesmo status, duas fundamentais ou duas runas na mesma
    vaga nunca vão juntos.
  - Trios: entre as 12 mais relevantes de cada lado, pela nota e pelos pares.
  - Validação: 150 conjuntos sorteados de 3 a 8 condições, nunca medidos, comparados à previsão de cada ordem.
- **Previsão** (`AtlasMath`): inclusão-exclusão até a ordem pedida, multilinear nos pesos das condições. Um status
  entra pelo valor dividido entre os dois níveis; uma passiva entra inteira. O guia recalcula sem lutar: ordena itens
  (ganho por mil de ouro), condições, runas e feitiço de cada lado contra o que o outro fixou, e monta uma build.

**Números do piloto:**

- **Preparar cada lutador:** cerca de 50 ms, numa thread só. É o que custa.
- **Lutar:** cerca de 470 lutas de 60 s por segundo, em paralelo.
- **Ruído:** as notas sozinhas com 1 e com 3 largadas se correlacionaram 0,69. A luta fechada é sensível: um pouco
  mais de velocidade ou de aceleração muda as decisões da IA. O padrão passou a 6 largadas.
- **Tamanho:** 48.903 pares no minuto 15. A execução inteira, com sozinhas, pares, trios e validação, levou 31 min e
  307.206 lutas: 23 min preparando os lutadores e 8 min lutando.

**Resultado do piloto** (minuto 15, base: Ornn 47,3 × 52,7 Rumble):

- **Sozinhas, a favor do Ornn:** Manto da Meia-noite +15, Liandry +9,6, RM +80 +8,8, % de penetração de armadura +8,4,
  Terminus +7,6.
- **Sozinhas, a favor do Rumble:** Curar +24,8, Abraço de Seraph +21,7, Manto da Meia-noite +20,4, Muramana +16,9,
  Liandry +15,8.
- **Maiores interações:** quase todas envolvem o Curar do Rumble. Na base a luta é apertada e uma cura vira o
  resultado; quando o Ornn é mais forte, ela já não vira. É um efeito de limiar.

**A validação diz até onde a previsão vale:**

- **Primeira validação (otimista):** 150 conjuntos sorteados entre todas as 315 condições deram correlação 0,86 e erro
  de 2,7 pontos. Mas a maioria das condições quase não muda nada, então os conjuntos ficavam perto da base.
- **Segunda validação:** metade dos conjuntos entre as 12 condições mais fortes de cada lado, que é o que uma build de
  verdade carrega. Aí cai para correlação ~0,45 e erro ~15. As interações inteiras pioram, porque cada uma combina 4
  medidas com ruído e a corrida satura.
- **Previsão ajustada:**
  - **Escala:** pontos ou chances (logit, em que cada nota muda as chances de vencer e os ganhos encolhem perto de 0 e
    de 100).
  - **Fatores:** um por ordem (notas, pares, trios), escolhidos numa metade dos conjuntos e julgados na outra
    (validação cruzada).
  - **Escolha do piloto:** logit, notas × 0,75, pares × 0,5, trios × 0,25.
  - **Resultado:** erro de 8,95 pontos e correlação 0,65. Prever sempre a base erraria 9,9: o ganho existe, mas é
    modesto.
- **Build inteira:** com 6 itens são umas 24 condições, muito além dos 3 a 8 da validação, e a previsão satura em 100.
  O botão "Lutar de verdade" do guia luta as duas builds montadas com os itens reais.
  - **Builds montadas pelas notas, com Rookern fixado no Ornn e Liandry no Rumble:**
    - Ornn: Rookern, Manto da Meia-noite, Botas Galvanizadas, Força da Natureza, Solari, Coração Congelado.
    - Rumble: Liandry, Ruptor Divino, Tocha de Chamas Negras, Botas Ionianas, Mandato Imperial, Dente de Na'Shor.
  - **Corrida real:** Ornn 80,9, com largadas entre 63,5 e 100. A prevista era 100.

**O que falta para a previsão de builds inteiras valer:**

1. Medir conjuntos do tamanho de uma build (sorteados) e ajustar um modelo regularizado sobre todos os conjuntos
   medidos, em vez de só inclusão-exclusão.
2. Mais largadas, para cortar o ruído das interações.
3. Mais minutos, para que um momento à frente seja também um momento no tempo.

As notas sozinhas e o ordenamento do próximo item já dão a direção. O número absoluto de uma build inteira, por
enquanto, só vale pela luta de verdade.

## Parte 2: builds inteiras e o relatório de condições (✅)

**O problema:** a previsão por inclusão-exclusão foi validada em conjuntos de 3 a 8 condições, mas uma build inteira
carrega umas 24. Nessas, ela saturava em 100, e as builds que o guia montava erravam ~20 pontos na luta real.

**O que mudou:**

- **Builds inteiras medidas** (`builds_inteiras: 600` no seed): builds de verdade, com os itens reais nos dois lados.
  - Metade é sorteada da loja toda. Cada lado troca às vezes uma runa fundamental, uma runa ou o feitiço.
  - Metade é guiada: os 30 melhores itens por ouro do guia vazio, com páginas de runas como as do guia (runa
    fundamental e feitiço em 60% das vezes, até 4 runas compatíveis entre as melhores pelas notas).
  - Os tamanhos dos dois lados são próximos (1 a 5 itens, mais botas em 70% das vezes).
  - Custo: ~10 minutos para 600 builds, quase tudo preparando os lutadores.
- **Modelo de builds inteiras** (`AtlasModel.BuildModel`), em chances (logit):
  - Fórmula: base + coeficientes × {notas de A, notas de B, pares, trios} + uma correção por condição × o peso dela.
  - Ajuste por regressão ridge. A força das correções é escolhida por validação cruzada em 5 partes, sobre o erro em
    pontos.
  - É a previsão "ajustada" dos guias e da luta de verdade. Sem builds medidas, valem os fatores por ordem da parte 1.
- **Execuções antigas:** o botão "Medir builds inteiras" (aba Validação, `POST /runs/{id}/builds`) mede em segundo
  plano e mantém o resto da execução. Se o programa parar no meio, a execução volta a "feito", não a "pendente".
- **Lutar de verdade:** 24 largadas com a margem de 95%, porque uma build sozinha oscila de 0 a 100 entre largadas.
  Antes eram só 7 largadas distintas: a partir da 8ª elas se repetiam. Agora cada volta de 7 desloca as posições
  laterais, e as 7 primeiras (as das medidas) não mudam.

**Piloto Ornn × Rumble (minuto 15), 600 builds:**

| Previsão das builds medidas | Erro médio (pontos) | Correlação |
|---|---|---|
| modelo de builds inteiras (validação cruzada) | 8,7 | 0,79 |
| sempre a corrida da base | 15,8 | 0 |
| fatores por ordem da parte 1 | 33,4 | 0,59 |
| só as notas, em chances | 33,3 | -0,13 |
| notas, pares e trios inteiros, em chances | 42,5 | 0,36 |

- **Coeficientes:** as notas do Ornn × 0,91, as do Rumble × 0,53, os pares × 0,08 e os trios × 0,03. Numa build
  inteira, as interações medidas em 2 e 3 dimensões quase não somam.
- **Correções:** 300 correções por condição (penalidade 100).
- **Primeira medida, sem as páginas de runas:** erro de 7,5 (correlação 0,83). Mas as runas que o guia montava
  (4 runas, runa fundamental e feitiço juntos) ficavam fora do que o modelo tinha visto. Com Rookern fixado no Ornn e
  Liandry no Rumble, a build montada previa 80,1, e a luta real deu 42,7 ± 11,8. Isolando, só com itens a previsão
  acertava (59,0 previsto, 55,7 real): o erro vinha da página de runas do Ornn.
- **Depois das páginas de runas na metade guiada:**
  - Rookern × Liandry: a build montada inteira deu 68,5 ± 9,0 real contra 72,1 previsto.
  - As variações (sem a runa fundamental do Rumble, só itens de um lado, só itens dos dois) caíram todas dentro da
    margem, com erro de 3 a 7 pontos.
  - Sem nada fixado, as builds montadas ainda erram ~14 pontos (71,3 ± 3,6 real contra 85,1 previsto). Escolher
    sempre o item de maior ganho previsto puxa a previsão para cima: o guia escolhe justamente o que o modelo
    superestima.

**Relatório de condições** (aba Condições, `POST /runs/{id}/report`): cada status e passiva de cada lado, separados
em favoráveis, desfavoráveis e neutras (±0,5 ponto). Há três leituras, e a separação usa a que o usuário escolher:

- **sozinha:** a nota medida sobre a base sem itens;
- **nas builds:** a média do que a condição acrescenta em cada build medida, pelo modelo (calculada no ajuste);
- **com o fixado:** o que ela acrescenta sobre o que está fixado nos guias (as fixações são as mesmas das duas abas).

Um status conta um nível a mais; uma passiva, tê-la contra não tê-la. O cálculo é exato pela derivada
(`AtlasMath.partial`, `modelGrad`), porque a previsão é multilinear em cada peso.

Para status, há também o ganho por mil de ouro. "Com quem" lista os pares medidos que mudam o valor da condição em 1
ponto ou mais:

- do mesmo lado: sinergia e sobreposição;
- do outro lado: as que a anulam e as que ela pune.

**Leituras do piloto:**

- **Ornn, favoráveis nas builds:** Manto da Meia-noite +12,2, Resistência Mágica +80 +12,1, Ruptor Divino +9,5,
  Dano de Ataque +90 +8,9, Força da Natureza +8,1, Liandry +8.
- **Ornn, desfavoráveis:** Aceleração de Habilidade +15 (−10,2 sozinha, −2,5 nas builds), Cimitarra Mercurial e
  Velocidade de Movimento +45.
  - A aceleração e a velocidade piorarem o Ornn indica que a IA da Arena decide pior com elas (mais recargas e
    aproximações mal usadas). Fica como pergunta aberta da Arena, não como regra de build.
- **Rumble, favoráveis nas builds:** Manto da Meia-noite +9,2, Ruptor Divino +8,7, Liandry +7,5, Abraço de Seraph
  +6,8, Céu Dividido +6,6, Muramana +6,6.
- **Rumble, desfavoráveis:** só mana e regeneração de mana, perto de zero (o kit dele não usa mana).
- **O Curar do Rumble aparece em quase todo "pune":** é o efeito de limiar da parte 1. Com a base empatada, a cura
  decide a luta.

**O que falta:**

1. Montar a build do guia contra a build montada do outro lado (hoje cada lado monta contra o que o outro tem
   fixado).
2. Corrigir o otimismo da escolha gulosa, por exemplo com uma segunda rodada de builds medidas em volta das que o guia
   monta.
3. Mais minutos.

## Parte 3: o otimismo das builds montadas (✅)

**O problema:** com nada fixado, a build que o guia montava previa 85,1 e a luta real dava 71,3. O guia escolhe
justamente o que o modelo superestima. Além disso, cada lado montava contra o que o outro tinha fixado, não contra a
build que o outro montava.

**O que mudou:**

- **Montagem conjunta** (`joint`): A monta contra o que B fixou; depois B contra a build de A, e A de novo contra a de
  B (2 rodadas). A corrida das duas builds montadas é o duelo entre elas.
- **Trocas pela corrida** (`improve`): a escolha gulosa por ganho a cada mil de ouro dá a ordem de compra, mas não
  monta a melhor build inteira. Depois dela, cada item escolhido (nunca um fixado; botas por botas) é trocado pelo que
  mais sobe a corrida prevista, até nenhuma troca ajudar. Na tela, o ganho de cada item é contado sobre os anteriores.
- **Refino** (`refino_rodadas: 3`, `refino_builds: 120`; o botão "Refinar com as builds do guia" chama
  `?refine=true`). Cada rodada:
  - monta as duas builds, uma contra a outra, sem nada fixado e a partir de primeiros itens sorteados;
  - acrescenta vizinhas (um item trocado de um lado, um lado sem a página de runas);
  - prevê essas builds antes de vê-las, luta, guarda o erro e o viés (previsto menos real) e ajusta o modelo de novo.
- **Melhorar lutando** (`POST /runs/{id}/improve`, um botão por lado no guia):
  - parte das duas builds montadas;
  - item por item, as 4 trocas que o modelo mais indica são lutadas de verdade (12 largadas) contra a build do outro
    lado;
  - fica a troca que ganha 1 ponto ou mais na luta real;
  - tudo o que foi lutado entra nas builds medidas e o modelo é ajustado de novo.

  O modelo propõe; quem decide é a luta. Custa ~45 s e ~600 lutas por lado.
- **Velocidade:** os trios só são procurados entre as condições que estão em algum trio medido (`AtlasMath.triples`).
  O resultado é o mesmo, mas um guia com a montagem conjunta e as trocas leva ~7 s.

**Piloto, refino** (erro do modelo nas builds do guia antes de vê-las):

| Rodada | Montagem | Erro médio | Viés (previsto − real) |
|---|---|---|---|
| 1 | conjunta, gulosa | 22,4 | +17,5 |
| 2 | conjunta, gulosa | 12,4 | −3,0 |
| 3 | conjunta, gulosa | 10,1 | +3,0 |
| 4 | conjunta, gulosa | 8,8 | −0,4 |
| 5 | com as trocas | 17,9 | −13,6 |
| 6 | com as trocas | 15,3 | −9,4 |

- **Rodadas 1 a 4:** o otimismo (+17,5) sumiu em duas rodadas.
- **Builds montadas pela gulosa depois do refino, lutadas com 24 largadas:**
  - nada fixado: 32,8 previsto contra 32,5 ± 8,0 real;
  - Força da Natureza × Ruptor: 30,4 contra 33,3 ± 4,0;
  - Rookern × Liandry: 27,2 contra 37,7 ± 0,9;
  - Coração Congelado × Seraph: 38,8 contra 56,0 ± 11,6.
- **Rodadas 5 e 6:** as trocas levam o guia a regiões que o modelo viu pouco, e lá ele erra para baixo (as builds
  saem melhores do que ele prevê).
  - Exemplo: contra o Rumble tanque (Randuin, Mobilização Blindada, Manto da Aurora, Liandry, Desespero Eterno,
    Solari), a build AD do guia para o Ornn deu 67 real (79,6 com as runas da base) contra 35 previsto.
  - A build tanque padrão da campanha deu 4 real (10,5 previsto).
  - Ou seja: a direção acerta, mas o número de uma build específica ainda pode errar 30 pontos.
- **Melhorar lutando, sem nada fixado:**
  - Ornn: 49,3 → 56,8 ± 6,6 real, com Lembrete Mortal → Lord Dominik e Espada do Rei Destruído → Malmortius.
  - Rumble: 50,7 → 85,3 ± 8,6 real contra a build montada do Ornn. Ele termina com Armadura de Espinhos, Mobilização
    Blindada, Ruptor, Cajado do Vazio, Manto da Aurora e Manto da Meia-noite.
- **Builds medidas no fim:** 1.320 do refino e das buscas, mais as lutas de "Melhorar lutando". Erro do modelo por
  validação cruzada: 10,6 (a base erra 16,2).

**Leitura:**

- O modelo serve para ordenar e propor.
- O número absoluto de uma build específica pode errar 10 a 30 pontos, por causa do ruído das lutas e de regiões
  pouco vistas.
- Para uma build final, use "Melhorar lutando" ou "Lutar de verdade".
- Cada uso deixa o modelo melhor naquela região.

