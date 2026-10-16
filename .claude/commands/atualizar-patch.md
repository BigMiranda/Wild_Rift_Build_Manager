---
description: Atualiza a base de um patch para outro pelas notas de patch oficiais (sem prints)
argument-hint: <patch de origem> <patch de destino>  (ex.: 7.3b 7.4)
---

# Atualizar a base do patch $1 para o $2 pelas notas de patch

Leia o `CLAUDE.md`, o `README.md` (regras modeladas), o `capturas/LEIA-ME.md` e o último levantamento em
`docs/patches/` (o do 7.3 → 7.3b é `docs/patches/7.3a-7.3b.md`: siga o mesmo formato). Execute as partes em
sequência, sem parar, com um commit por parte e sem push. Ao fim, entregue um relatório único.

`<o>` e `<d>` abaixo são os sufixos de arquivo dos patches de origem ($1) e de destino ($2), com `_` no lugar de
`.` (ex.: 7.3b → `7_3b`).

## Regras

- **A fonte desta atualização são só as notas de patch.** Nada de celular, ADB ou capturas do jogo: o programa
  continua sem nenhum contato com o jogo, como diz o README.
- **Prints prevalecem.** Se o usuário tiver prints do patch $2 (em `capturas/`), eles valem mais que as notas. Nesse
  caso, siga o `capturas/LEIA-ME.md` para o que tem print e use as notas só no resto.
- **Fontes, em ordem de preferência:**
  1. Notas oficiais em **pt-BR** no site do Wild Rift (Notícias › Atualizações do jogo,
     `https://wildrift.leagueoflegends.com/pt-br/news/game-updates/`), porque os nomes e textos saem iguais aos da
     loja.
  2. As notas oficiais em inglês (`/en-us/` no mesmo caminho).
  3. https://www.riftpatchnotes.com/patches como apoio.

  Guarde o link de cada mudança.
- **Nomes:** nunca traduza livremente. Mapeie para o nome que já está nos YAMLs, usando também os ícones em
  `seed/icones/` e o `manifest.json`. Se não achar o correspondente, registre como pendência e não invente.
- **Uma mudança só entra na base se:**
  - o valor "antes" da nota bate com o valor que a base tem hoje;
  - ou a base já tem o valor "depois" (a base foi transcrita depois do patch): marque "já estava".

  Se o valor da base não bate nem com o "antes" nem com o "depois", **não altere**: marque como divergente para o
  usuário conferir.
- Notas de patch às vezes trazem rótulos errados (ex.: uma recarga menor chamada de "nerf"). Use os números, não o
  rótulo.
- **Determinismo da Arena e golden:** não regrave o golden sem antes listar quais cenários mudaram e por qual linha
  do patch.

## Parte 1 — Coletar

Descubra quais patches saíram depois do $1 até o $2, inclusive os intermediários (a, b, c…). Use a lista de notícias
oficial e confira se não saiu outro desde então.

Produza `docs/patches/<intervalo>.md`, com uma tabela por categoria (itens, runas, feitiços, campeões, sistemas).
Colunas:

- patch, nome pt-BR e o que muda;
- antes → depois, conforme a nota;
- arquivo e chave na base, valor atual na base;
- situação: aplicar | já estava | divergente | fora do modelo;
- link da fonte.

Diga quais campeões com ficha na Arena (`kits_<o>.yml`) aparecem nas notas. As fichas e o perfil calibrado do Ornn só
mudam se a nota disser.

## Parte 2 — Aplicar

- **Arquivos do patch:** crie `loja_<d>.yml`, `efeitos_itens_<d>.yml`, `combate_<d>.yml`, `runas_<d>.yml`,
  `feiticos_<d>.yml`, `campeoes_<d>.yml` e `kits_<d>.yml`.
  - Copie os do $1, aplique só as linhas "aplicar" e atualize `patch: "$2"` e o cabeçalho, dizendo que as mudanças
    desde o $1 vieram das notas, não de prints.
  - Na loja, atualize `fonte: notas de patch` e `atualizado: "<data>"`. A tela Sobre e o Admin mostram esses campos.
  - Aponte `application.properties`, os `@Value` e os testes para os novos. Os do $1 ficam para histórico.
  - A importação reimporta a loja sozinha quando o patch do seed muda, e o Admin continua preservando os itens
    editados à mão.
- **Onde cada mudança entra:**
  - preços e status planos: `loja`;
  - efeitos numéricos: `efeitos_itens` e `combate`;
  - status de campeões: `campeoes`;
  - feitiços: `feiticos`.
- **Textos da loja:** nas passivas e resumos, troque **só os números** que a nota mudou, mantendo o resto do texto
  transcrito. Marque cada item, runa ou campeão alterado com `fonte_<d>: nota de patch`, para o usuário saber que
  aquele texto não veio de um print.
  - Selos `marcador`: marque `alterado` nos itens mudados e limpe os selos herdados que não se aplicam mais. Registre
    isso como dedução no doc.
- **Fora do modelo:** o que a base não modela (Nexo, torres, monstros da selva, modos de jogo) só vai para a tabela,
  com a situação "fora do modelo".
- **Campeões sem ficha na Arena:** atualize os status e, se a nota mudar dano ou recarga de habilidade, os textos e a
  tabela `niveis` lidos pelo `AbilityParser`.

## Parte 3 — Testes e efeitos colaterais

- `./mvnw test` e `npm run build`. O `LojaCatalogTest` precisa passar: componentes existem e custam menos.
- **Golden da Arena:** se mudar, liste quais cenários mudaram e por qual linha do patch antes de regravar com
  `-Darena.golden.write=true`.
- **Testes com números fixos:** atualize os que dependem dos valores antigos e registre o antes e o depois no doc.
  Para o efeito no Ornn, rode `OrnnProofTest` com `-Darena.proof=true` nos dois patches.
- **IAs salvas:** continuam abrindo. A tela avisa "treinada no patch …" pelo `arena_policy.game_patch`.
- **Builds salvas:** rode o planejador nas builds existentes, numa **cópia** do banco, nos dois patches. Liste as que
  mudaram de forma visível (minuto de compra, ouro efetivo, status num minuto-chave), com o motivo.
- Confira que Sobre e Admin mostram "Base: patch $2, pelas notas de patch (atualizada em <data>)".

## Parte 4 — Documentação

- Atualize o `CLAUDE.md`, com o sufixo do patch atual, e o `capturas/LEIA-ME.md`, se algo do processo mudou.

## Relatório final

- Quantas mudanças foram aplicadas, quantas já estavam, quantas divergentes e quantas fora do modelo, com a tabela
  das divergentes para o usuário conferir no jogo quando quiser.
- Os cenários do golden que mudaram e o efeito no Ornn.
- As builds salvas que mudaram.
- Itens das notas que não foi possível mapear para a base.
- Perguntas em aberto da Arena que as notas não resolvem continuam abertas. Veja `docs/proposta-ia-arena.md`.
