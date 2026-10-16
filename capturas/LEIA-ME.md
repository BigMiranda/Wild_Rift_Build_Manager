# Capturas da loja do Wild Rift

Esta pasta recebe os prints da loja do jogo. A partir deles o catálogo é transcrito para
`backend/src/main/resources/seed/loja_<patch>.yml` e os ícones são recortados para `seed/icones/`. Os prints em si
**não** vão para o git — só este arquivo.

## O que capturar (formato usado no patch 7.3)

Para **cada aba** da loja (Lutador, Assassino, Atirador, Mágico, Defesa, Suporte, Botas):

- `Aba <Nome> completa Resumos.png` — a lista em modo resumo, com todos os itens da aba (nome, custo, resumo e selos).
- `Aba <Nome> completa Enxuta.png` — a grade de ícones da aba.

Para **cada item**, um único print com o painel de descrição e o de árvore lado a lado (nome, custo, status, passivas,
"Fabrica" e "Árvore de Construção"). Se o texto não couber, emende a continuação no mesmo arquivo.

Nome do arquivo: a posição do item em cada aba onde ele aparece, por exemplo `2 - Lutador, 2 - Assassino, 10 - Atirador.png`
(`ativavel` quando o item é ativável). Um item que aparece em várias abas só precisa de um print.

## Patches novos

Só os itens com selo (**N** = novo, setas azuis = reformulado, seta vermelha = alterado) e os itens removidos precisam
de print novo; o resto do catálogo continua valendo. Depois de atualizar o YAML:

```bash
python tools/capturas_receitas.py capturas/<patch>
```
```bash
python tools/capturas_icones.py loja_<patch>.yml capturas/<patch>
```

O primeiro confere as receitas pelos ícones das árvores e das listas "Fabrica"; o segundo recorta os ícones.

## Patches sem prints (notas de patch)

Quando não há prints de um patch, a base pode ser atualizada só pelas notas de patch oficiais, com o comando
`/atualizar-patch <origem> <destino>` (`.claude/commands/atualizar-patch.md`). O levantamento de cada atualização
fica em `docs/patches/` (o 7.3b, por exemplo, está em `docs/patches/7.3a-7.3b.md`). Os itens, runas e campeões
mudados por nota levam a marca `fonte_<patch>: nota de patch` no YAML, e os selos da loja (`marcador`) desses patches
são deduzidos das notas.

**Os prints prevalecem sobre as notas.** Quando você tirar prints de um patch que foi atualizado por notas:

- transcreva-os como de costume;
- onde o print diverge da nota, vale o print;
- apague a marca `fonte_<patch>: nota de patch` dos itens que passaram a ter print;
- confira os selos `marcador` e as linhas "divergente" do levantamento em `docs/patches/`.
