# Rodada das fichas — Lote 1 (16 toplaners)

Mordekaiser, Garen, Sett, Jax, Fiora, Camille, Irelia, Riven, Aatrox, Malphite, Nasus, Gwen, Kayle, Teemo, Shen, Sion.
Notas de mecânica: `docs/fichas/notas-lote-1.yml`. Gerador: `tools/fichas/gerar_rascunhos.py`.

## Parte 1 — Rascunho da wiki ✅

`python tools/fichas/gerar_rascunhos.py --lote 1 --saida backend/src/main/resources/seed/rascunhos` leu a wiki de
primeira (`wiki.leagueoflegends.com/en-us/api.php`): de 10 a 11 modelos `WR Data` e de 10 a 21 `Data` (PC) por campeão.
Rascunho em `seed/rascunhos/lote_1.yml`, lacunas em `lote_1_lacunas.json`. Cache em `capturas/wiki-cache/` (já coberto
por `capturas/*`; a linha ficou explícita no `.gitignore`).

**Ajustes no script:**
- `{{ap|7 to 4 4}}` é a interpolação da wiki por rank (4 valores de 7 a 4); o script lia `[7, 4, 4]`. Agora expande
  para `[7, 6, 5, 4]`.
- `target range` conta como alcance no relatório de lacunas.

**Surpresa boa:** as páginas `WR Data` trazem alcance, largura, raio e conjuração na maioria das habilidades. A
geometria é quase toda **WR**, não PC.

**Conferido à mão** (texto bruto do modelo, `--diagnostico`, contra o rascunho):

| campeão | habilidade | wiki | rascunho |
|---|---|---|---|
| Aatrox | W Correntes Infernais | alcance 800, largura 160, vel. 1800, conj. 0,25 | igual |
| Aatrox | Q Lâmina Darkin | alcance "Varied" (WR) | caiu no PC (650, raio 225 / interno 75): conferir |
| Irelia | R Gume da Vanguarda | alcance 1000, largura 320, vel. 2000, conj. 0,4 | igual |
| Irelia | E Dueto Impecável | alcance 775, vel. 2000, convergência "Global" | igual |
| Mordekaiser | Q Obliterar | alcance 625, largura 160, conj. 0,5 | igual |
| Mordekaiser | E Garra da Morte | alcance 700 (mira) / 900 (máx.), largura 200, vel. 3000 | igual |

**Leituras ruins do rascunho** (campos com `{{pp|...}}` de vários parâmetros, que viram listas sem sentido):
Sett W (`range: [-25, 720, 745]`), Gwen Q e R, Sion Q (alcance pela carga 200→675 em 0,75 s) e E. Nasus: o slot 2
saiu com o nome da E (os modelos do slot vêm em ordem alfabética), mas a geometria da W (600) está certa. Nessas, a
ficha usa o texto bruto do modelo.

## Parte 2 — Lacunas do motor ✅

Contagem pelos textos de `campeoes_7_3d.yml` (expressões em pt-BR, conferidas à mão), não só pelo lote. Viraram campo
genérico de ficha as que servem a 3 ou mais campeões da base; cada uma tem ficha sintética em
`src/test/resources/arena/fichas-sinteticas.yml` e teste em `MecanicasTest`.

| mecânica | campos | campeões da base (texto do jogo) |
|---|---|---|
| Vida do alvo no dano | `vida_perdida_alvo`, `vida_atual_alvo`, `vida_bonus_alvo` (termos de `dano`, também em escudo e cura) | perdida: Garen, Kayle, Riven, Akshan, Caitlyn, Jinx, Kindred, Lee Sin, Samira, Viego…; atual: Camille, Dr. Mundo, Jarvan IV, Senna, Viego…; adicional: Sett (vai junto, é o mesmo cálculo) |
| Devolver / zerar recarga | `ao_acertar: {devolve_recarga, zera_recarga_se: [marca, abate]}`; efeito `marca: s` (marca genérica) | Fiora, Irelia, Gwen, Akshan, Draven, Kalista, Kha'Zix, Nilah, Tristana, Yasuo… |
| Dano armazenado | `armazena` (campeão), termo `armazenado`, `consome_armazenado` | Mordekaiser, Sett, Pyke |
| Linha entre dois pontos | `deixa_marco`, `linha_entre` | Irelia (E), Shen (Q, a espada), Xayah (as plumas) |
| Acúmulos permanentes | `acumulos_permanentes` (campeão; termo de dano de mesmo nome); campo **acúmulos** do lutador na Arena (`Slot.permanentStacks`) | Nasus, Sion, Kayle, Kayn, Thresh, Viktor, Yunara, Cho'Gath, Veigar |
| Camuflagem | `invisivel.camuflagem` (raio de detecção), `so_em_moita`, `fora_da_moita`, `revela_ao_atacar`, `ao_revelar` | Teemo, Evelynn, Twitch, Akshan, Kha'Zix, Rengar, Pyke, Senna |
| Redução de dano | `reducao_dano: {pct, duracao}` (já existia no motor, só pela carga do K'Sante) | Garen W, Irelia W, Jax E (aproximação da esquiva), e muitos fora do lote |

**Ficaram como aproximação documentada** (1 ou 2 campeões na base): esquivar ataques (só Jax; Shen W bloqueia para
aliados), bônus de alvo único / alvo mais próximo, Pontos Vitais e aparar (Fiora), isolamento (Mordekaiser), zona
inescapável e gancho em terreno (Camille), condição dos dois lados (Sett), "ainda na zona" (Aatrox), dano ao atravessar
a borda (Irelia), ataque com área (Malphite), ataques alternados (Sett), status por nível na passiva (Kayle), névoa
(Gwen), reanimar (Sion), avanço dirigível (Sion), posição da espada além da W (Shen), Armadura/RM por alvos atingidos
(Jax).

**Decisões:**
- A marca genérica é `marca: <segundos>`; `marca: true` continua sendo a da Vayne e a do K'Sante. O teste da marca olha
  o alvo **antes** do acerto (senão a própria habilidade que marca zeraria a recarga).
- `devolve_recarga` vale uma vez por conjuração (a recarga já devolvida fica guardada por slot).
- Camuflagem: `so_em_moita` só vale no mapa inteiro (a rota única não tem moitas: ali começa em qualquer lugar).
  Inimigos no raio de detecção a veem (`visible()`), mas ela não acaba, ao contrário de `revela_proximidade`.
- Acúmulos permanentes não são simulados: `floor(por_minuto × (minuto − desde_min))` até `max`, ou o número do cenário.
- A camuflagem e o dano armazenado não mudam as observações da IA (`obs_schema` igual).

🎯 **Golden sem mudança.** Testes: 399 → 408 (9 novos em `MecanicasTest`), 16 opcionais pulados. `npm run build` limpo.

## Parte 3 — As 16 fichas ✅

As fichas estão em `kits_7_3d.yml`, depois do K'Sante. Números (dano, razões, recargas, durações, %) do texto do jogo;
geometria do rascunho (WR quase sempre); forma e mecânicas das notas. Cada habilidade tem `fontes`, e as aproximações
ficam em `nota` / `lacuna` (por habilidade) e `lacunas` (por campeão). A tabela completa está na Parte 5.

**Campos genéricos que as fichas pediram no caminho** (nenhum cita campeão; o golden não muda):
- `ataque` com `ataques: n` (vários ataques fortalecidos: Sett, Fiora, Camille, Shen), `velocidade_ataque` e
  `velocidade_movimento`; o ataque fortalecido também aplica `ao_acertar` (Gwen E).
- Termos de dano `base_nivel` (+ `_nv15`: o "({nível})" do Mordekaiser) e `arm` / `rm` (Armadura e RM **totais**: Malphite).
- `status` / `status_pct` numa habilidade: atributos por `duracao` (Nasus R, Gwen W, Jax R, Riven R, Aatrox R).
- Estados no lançador em qualquer forma (`invulneravel` na linha da Fiora, na zona da Kayle; `nao_selecionavel` no salto
  da Camille); `escudo` em formas que não são `proprio` nem `avanco` (Sett W).
- `carregar` com `reducao_dano` e `imparavel` (Irelia W).
- Efeito `reduz_vel_ataque` (Malphite E, Nasus W, Fiora W).
- `rouba` com uma lista de atributos e `pct` (% do atributo do alvo: Mordekaiser R).

**Custos:** nenhuma ficha tem `recurso` ainda (mana da Fiora / Jax / Irelia / Malphite / Nasus / Kayle / Teemo,
energia do Shen): sem custo, como as fichas antigas sem `recurso`.

## Parte 4 — Validar e testar ✅

`FichasLote1Test` (sem Spring, por tabela) e um teste novo em `ClassificationTest`:

- **Validador:** as 16 fichas passam no `SheetValidator` (as antigas e as sintéticas seguem passando).
- **Cada habilidade faz algo contra o boneco**, e cada versão de reconjuração também (conjurada 3 s depois): luta com e
  sem a conjuração, comparando posições, Vida, escudos, marcas, estados, formas e eventos. A W do Teemo só aparece
  andando de longe (o teste tenta a 200 e a 900), e os `status` contam o evento `status:<atributo>` (a W da Gwen).
- **Dano no rank máximo contra o boneco, ±1% do texto** (o `AbilityParser`, que lê o primeiro dano do texto), com 150 de
  DdA (50 adicional), 100 de PdH e o boneco sem resistências. Batem: Mordekaiser E, Garen Q, Sett E, Jax Q e W,
  Fiora Q e W, Camille E, Irelia Q, W (×2: carga cheia) e R, Aatrox W, Malphite Q, E e R, Gwen R, Kayle Q e R,
  Teemo Q e R, Shen E. As outras estão em `SKIP`, com o motivo: o parser não lê o texto (Sett Q e R, Camille R, Shen Q,
  Sion E), a ficha soma golpes ou ataques, a parte depende da Vida perdida ou do dano armazenado (zero no boneco), ou a
  habilidade não causa dano.
- **Achado no caminho:** o dano das invocações saía sempre no rank 1 (`blowUp` passava 1). Agora a invocação guarda o
  rank da habilidade (`summonRank`): a armadilha do Teemo no rank 3 dava 275 em vez de 525. As fichas antigas e as
  sintéticas não tinham dano por rank em invocação: o golden não muda.
- **Luta de 30 s do Tático contra o Darius** (os dois com 2500 de Vida, 120 de DdA, 60 de Armadura): os 16 rodam sem
  erro e usam habilidades (5 a 11 usos). O Darius vence 13 das 16 (Aatrox, Nasus e Sion sobrevivem). Era de esperar:
  quase todas as passivas do lote ficaram de fora, e a do Darius (Hemorragia) está na ficha dele.
- **Classificação (Rodada 7):** o perfil lê a ficha nova dos 16 (`abilityRange`), e o aviso só aparece quando a classe
  calculada difere da oficial.

| campeão | oficial | calculada | |
|---|---|---|---|
| Garen, Camille | Lutador | Lutador | bate |
| Jax | Assassino | Assassino | bate |
| Mordekaiser, Sett, Irelia, Aatrox | Lutador | Tanque | próxima (sem aviso) |
| Nasus | Lutador | Atirador | próxima (sem aviso); estranho, conferir o peso de `dano_ataque` |
| Fiora, Riven | Assassino | Lutador | próxima |
| Malphite, Shen | Tanque | Lutador | próxima |
| Kayle, Teemo | Especialista | Mago | próxima |
| **Gwen** | Assassino | Tanque | **aviso (divergente)**: sem a passiva (o dano dela), e a W virou resistências |
| **Sion** | Tanque | Controlador | **aviso (divergente)**: muito controle (Q, E, R) e sem a passiva |

**Na tela** (servidores `backend-dev` / `frontend-dev`): a Arena abre sem erro de React, o campo **acúmulos** aparece
em cada lutador, e uma luta Garen × Darius com builds reais mostra no registro a W, a R, a Q e a E do Garen. A build de
teste foi criada no banco de dev e apagada depois.

`./mvnw test`: 413 testes, 0 falhas, 16 opcionais pulados; golden intacto. `npm run build` limpo.

## Parte 5 — Relatório

### Por campeão

Geometria: **WR** = página `WR Data` da wiki; **PC** = página do LoL de PC; **EST** = estimativa. A fonte de cada
número está no campo `fontes` da habilidade.

| campeão | qual. | geometria | lacunas (aproximadas) | o que ficou de fora / aproximação principal |
|---|---|---|---|---|
| Mordekaiser | D | WR | isolamento, bonus_alvo_unico, aura_da_passiva | R sem isolar; Q sem +45% em alvo único; passiva fora; W com dano armazenado (`armazena`) |
| Garen | B | WR | bonus_alvo_mais_proximo, regeneração da passiva | E com 10 golpes fixos; R sem a escala por DdA adicional e sem os próximos |
| Sett | C | PC (W, E, R) | ataques_alternados, condicao_dois_lados | passiva fora; E sem atordoamento; R vira salto + supressão; W com a Ousadia (`armazena`) |
| Jax | C | WR + raio da R EST | esquivar_ataques, buff_por_alvos_atingidos | E sem esquiva (só −25% de dano); passivas fora |
| Fiora | D | WR | pontos_vitais, aparar | R = os 4 Pontos Vitais de uma vez; W invulnerável junto do golpe; passiva fora |
| Camille | C | WR + EST | precisa_terreno, zona_inescapavel | E sem parede (um avanço só); R sem área inescapável; passiva fora |
| Irelia | B | WR | dano_ao_atravessar_borda | R sem a parede; E com `linha_entre`; Q zera na marca |
| Riven | C | WR + EST | dano pela Vida perdida (Golpe de Vento) | passiva fora; Q em 3 versões; Golpe de Vento com o dano mínimo |
| Aatrox | C | PC/WR + EST | condicao_ainda_na_zona | Q em 3 versões com borda; W sem a puxada; passiva e vampirismo da E fora (exceto na R) |
| Malphite | C | WR (W: PC) | ataque_com_area | W só o 1º ataque; área da R ao longo do avanço; passiva fora |
| Nasus | C | WR (E: PC) | — | acúmulos da Q pelo minuto (30 por minuto, EST); passiva fora |
| Gwen | C | PC + EST | nao_selecionavel_de_fora | W só resistências; Q com 2 cargas fixas; R uma agulha por conjuração; passiva fora |
| Kayle | B | WR | status_por_nivel_na_passiva | alcance 525 sempre; passiva e passivo da E fora |
| Teemo | B | WR + detecção EST | — (camuflagem virou campo) | W sem o rolamento; R uma armadilha por conjuração; passiva fora |
| Shen | B | WR | esquivar_ataques, posicao_da_espada, escudo_pela_vida_perdida | W = −50% de dano (EST); R sem teleporte (sozinho, protege a si mesmo); passiva e Energia fora |
| Sion | D | WR + EST | reviver_em_forma, avanco_dirigivel | sem a reanimação; R reta até o alvo; Q sempre com carga cheia |

### Mecânicas novas e fichas sintéticas

Parte 2, cada uma com ficha sintética em `fichas-sinteticas.yml` e teste em `MecanicasTest`: termos de Vida do alvo,
`ao_acertar` (devolver / zerar recarga) com a marca genérica, dano armazenado, linha entre dois pontos (`deixa_marco` /
`linha_entre`), acúmulos permanentes (com o campo na Arena), camuflagem e `reducao_dano`.
Parte 3, exercitadas pelas fichas reais e pelo `FichasLote1Test`: `ataques: n`, velocidades no `ataque`, `base_nivel`,
`arm` / `rm`, `status` / `status_pct`, estados e escudo em qualquer forma, `carregar` com redução e imparável,
`reduz_vel_ataque`, `rouba` com lista e `pct`, e o rank das invocações.

### Divergências entre o texto do jogo e a wiki (para conferir)

- **Fiora W:** a wiki WR dá alcance 900; o LoL de PC tem 750. Usado 900 (WR).
- **Sion E:** a wiki traz um alcance ilegível (600 misturado a outros números); o PC tem 800. Usado 600.
- **Teemo R:** o cabeçalho do texto diz recarga 1; a tabela diz 30 / 25 / 20; a wiki WR fala em cargas. Usada a tabela.
- **Nasus:** o slot 2 do rascunho saiu com o nome da E (ordem dos modelos), mas a geometria é a da W.
- **Mordekaiser Q:** o texto mostra o valor do nível 1 já somado à escala por nível (`80 + (4–70)`); a tabela traz só a
  base por rank. A ficha separa (`base_nivel` de 4 a 70).
- **Camille Q:** o texto dá o dano do ataque inteiro (130–160% do DdA), não o adicional.
- **Malphite W:** a tabela chama de "Armadura" o % da passiva (25–40%), não um valor plano.
- **Raios do corpo e alcances de ataque:** nenhuma página WR deu; todos EST (175 corpo a corpo, Teemo 500, Kayle 525).

### O que o Tático não sabe usar destas fichas (para a rodada de IA)

- **Esperar o momento:** a esquiva do Jax (E antes do ataque inimigo), a aparada da Fiora (W contra um controle), o
  escudo do Mordekaiser e do Sett depois de acumular dano: o Tático usa assim que fica pronto.
- **Camuflagem do Teemo:** não procura moita nem fica parado, e não sabe que um inimigo perto o vê.
- **Duas conjurações em pontos diferentes:** a E da Irelia (a linha entre as lâminas) e a espada do Shen (W para
  posicionar, Q para puxar através do inimigo).
- **Reconjurações com tempo:** Q da Riven e do Aatrox (3 golpes), R da Gwen, detonação da W do Sion, Golpe de Vento da
  Riven (melhor com o inimigo com pouca Vida).
- **Zerar a recarga:** a Q da Irelia só zera no alvo marcado (E ou R antes); a Q da Fiora devolve metade ao acertar.
- **Carga:** a W da Irelia e a Q do Sion saem sempre com a carga cheia.
- **Aliados:** a W e a R da Kayle e a R do Shen miram aliado; sozinhos, caem neles mesmos.
- **Sion:** a reanimação não existe; quando existir, o Tático vai precisar de um comportamento para a segunda vida.
- As observações da IA não mudaram (`obs_schema` igual): a marca genérica, o dano armazenado e a camuflagem não aparecem
  nelas.

### Tempo e tokens (aproximados)

Uma sessão longa, interrompida uma vez pelo limite de uso: algumas horas de relógio. A maior parte dos tokens foi para
ler o `Arena.java` (9,6 mil linhas, em trechos) e os textos dos 16 campeões; as fichas em si são ~900 linhas de YAML.
Para os lotes 2 e 3, os campos genéricos já existem, então devem custar menos; o que pesa é ler os textos e decidir as
aproximações. Rodar `--diagnostico` só nas habilidades com alcance ilegível no rascunho economiza leitura.
