# Capturas da loja do Wild Rift

Esta pasta recebe os prints da loja do jogo. A partir deles o catálogo do projeto é transcrito para um arquivo de dados
versionado (os prints em si **não** vão para o git — só este arquivo).

## O que capturar

Para **cada aba** da loja (Lutador, Assassino, Atirador, Mágico, Defesa, Suporte, Botas…):

1. **A grade da aba** — quantos prints forem necessários para mostrar todos os itens (rolando a lista). Serve para
   saber em que aba e seção (Ativo, Aprimorado…) cada item aparece e a ordem.

Para **cada item**:

2. **Descrição** — o item selecionado, com o painel da direita na aba de descrição (nome, custo, status, passivas).
   Se o texto não couber na tela, role e tire mais um print.
3. **Árvore** — o mesmo item com o painel na aba de árvore (receita / em que ele se transforma).

Um item que aparece em mais de uma aba só precisa dos prints 2 e 3 uma vez.

## Como organizar

- Uma subpasta por patch, por exemplo `capturas/7.3/`.
- Não precisa renomear os arquivos; a ordem em que foram tirados já ajuda. Se quiser, separe por aba em subpastas
  (`capturas/7.3/defesa/` …).
- Print em tela cheia, sem cortar. Evite capturar no meio de uma animação.

## Antes de fazer tudo: piloto

Comece com **3 ou 4 itens** (por exemplo, um com passiva percentual, um com ativo, um componente e uma bota) mais o
print da grade de uma aba. Com isso validamos o que fica legível e fechamos o formato antes de você capturar o resto.
Vale testar também o modo de **lista** da loja (o botão ao lado da grade, no canto superior direito): se ele mostrar
nome e status de vários itens por tela, pode poupar muitos prints.
