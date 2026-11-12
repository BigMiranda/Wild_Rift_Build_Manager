# Rodada 14: notas legíveis, Anjo Guardião, suportes, subclasses, estilos de rota e guias por matchup

Levantado a partir do site publicado depois da campanha 7.3f com Tenacidade e Regeneração corrigidas (12/11/2026).

## O que estava errado

| Sintoma | Causa |
|---|---|
| Notas da Descoberta ilegíveis | Listas de nomes de itens e o caminho inteiro da busca de runas ("A → B; B → C; C → D"), repetidos por conjunto. |
| Anjo Guardião entre os 3 primeiros da triagem de quase todos (Darius, Garen, Irelia, Renekton em 2º) | Os 4 s de Estase contavam como tempo vivo sem dano sofrido: num teste de 10-20 s, 20-40% de durabilidade grátis. No jogo, nesse tempo o campeão não segura dano de ninguém e as recargas do inimigo voltam. |
| Suportes medidos quase só como tanques | A "utilidade" é o controle aplicado e a parte do dano do time absorvida. Não mede o carry vivo, o dano que ele consegue dar, a cura e o escudo nele, nem o dano inimigo cortado. |
| Guia automático "tem dano mágico? tem dano físico?" | `GuideService.suggest` só tem esses dois ramos. As subclasses da wiki (Colosso, Vanguarda, Assassino, Artilharia...) já estão na base e ninguém lê. |
| Nenhuma proposta de como jogar a rota | A partida simulada tem um só jeito de jogar a rota (troca a uma taxa fixa, volta por vida ou ouro). |

A pool pequena de campeões com ficha A/B (11) também pesa: os duelos e as matchups só veem esses campeões, e os
bonecos de teste são poucos e genéricos. Um item que vence esse grupo sobe para todos (o Manto da Meia-noite em 1º na
triagem de quase todos é o mesmo viés). As partes 4 e 5 aumentam a variedade de oponentes medidos.

## Partes

1. ✅ **Notas com ícones** (`frontend/src/discoveryNotes.js`, `components/DiscoveryNotes.jsx`): itens e runas viram
   ícones com o nome como legenda; o caminho da busca de runas vira a troca final, agrupada quando vários conjuntos
   tiveram a mesma; as descobertas retiradas, agrupadas pela build salva que as superou; os pares de itens (sinergias)
   no site, os 6 maiores à mostra.
2. ✅ **Anjo Guardião**: `FighterStats.outOfFight` (segundos na Estase do revive, cortados no fim da luta) sai do tempo
   vivo e da taxa na efetividade (`EffectTestService.measure`). Os duelos continuam pela vida que sobra (o revive vale
   lá como no jogo). Golden inalterado (nenhum cenário revive).
3. **Subclasses** — `ChampionProfiles` passa a dar a subclasse de cada campeão: a da wiki quando há, senão lida do
   perfil (Atirador e Especialista não têm subclasse na wiki), por regras em `classificacao_<patch>.yml`, nunca por nome.
   Também as etiquetas de jogo que as regras leem: corpo a corpo de proximidade, iniciador, peel, artilharia, sustento.
4. **Bonecos por subclasse** — cenários de teste novos em `efetividade_<patch>.yml` que imitam cada ameaça: assassino de
   proximidade (explosão e avanço), colosso (vida, cura e dano contínuo de perto), tanque de iniciação (controle
   encadeado), mago de artilharia (dano de longe), mago explosivo, dois e três atiradores. Eles entram na nota e dão os
   ramos do guia.
5. **Suportes** — cenários "proteger o carry" (um atirador aliado mergulhado por um assassino ou um lutador; o mesmo
   atirador cutucado de longe) e medidas novas de utilidade: tempo vivo do carry, dano que o carry conseguiu dar, cura
   e escudo nele, tempo de controle no inimigo (lentidão com peso menor), dano inimigo cortado. Pesos maiores para
   Controlador e builds de suporte em `nota_builds_<patch>.yml`.
6. **Estilos de rota na partida** — `Match` ganha um estilo por assento na fase de rotas: trocas curtas, trocas longas,
   só farmar recuado até as lutas, pressão constante (afastar o adversário até perto do raio de experiência para negar
   ouro e XP), roaming, priorizar ganks acima das ondas, proxy, controle de visão. Cada estilo é um conjunto de
   parâmetros (exposição na troca, distância à onda, chance de girar, visão) em `macro_<patch>.yml`. Por matchup, as
   partidas medem qual estilo rende mais (ouro, XP, mortes aos 10 min).
7. **Guias por matchup** — `GuideService.suggest` com condições por subclasse (`adversario_subclasse` na rota,
   `inimigos_subclasse` com quantidade no time) e duas seções: a fase de rotas (o estilo recomendado contra cada
   subclasse do adversário de rota, da parte 6) e as lutas em equipe (a build que melhor se sai contra cada tipo de
   time, dos cenários da parte 4).

Cada parte, um commit, com a nota de progresso aqui. A campanha roda de novo no fim, antes de publicar o site.
