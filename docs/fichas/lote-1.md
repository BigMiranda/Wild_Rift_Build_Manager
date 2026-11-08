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
