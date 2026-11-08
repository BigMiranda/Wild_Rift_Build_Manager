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
