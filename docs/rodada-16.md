# Rodada 16 (proposta): atlas de interações e busca de builds por matchup

Proposta a partir do pedido de 13/11/2026. Ainda não foi construída: este documento é para decidir o escopo do
primeiro marco.

## O problema

A Descoberta mede cada build candidata em lutas, uma por uma, e cada teste novo encarece a campanha: são 6 horas para
11 campeões. Ela também não aprende nada que sirva para o próximo campeão. Saber que "redução de velocidade de ataque
rende contra atirador de crítico" é redescoberto a cada build, e de forma implícita.

A ideia é medir uma vez, de forma genérica, como as condições interagem entre si. Depois, usar esse mapa para:

- propor builds, runas e feitiços sem lutar com cada candidata;
- propor drafts;
- responder no site a perguntas como "contra este campeão, com estas runas e este primeiro item, o que eu faço?".

## Etapas

### 1. Catálogo dos valores possíveis (sem lutas, segundos)

Para cada eixo, a faixa que o jogo permite em cada minuto (mínimo, típico e máximo) e quem a fornece (itens, runas,
feitiços, campeões). Tudo lido dos seeds (`loja`, `combate`, `runas`, `feiticos`, `kits`) e dos textos de habilidade
(`AbilityParser`).

- **Status:** Vida, Armadura, RM, DdA, PdH, velocidade de ataque, penetrações, crítico, roubo de vida e vampirismo,
  aceleração, tenacidade, velocidade de movimento.
- **Efeitos:**
  - Feridas Dolorosas e corte de escudo;
  - escudos e cura;
  - redução da velocidade de ataque inimiga: só o Coração Congelado, a Carapaça do Vigia e algumas passivas, como a do
    Malphite;
  - lentidão;
  - dano pela Vida (máxima, atual ou que falta);
  - Dano Verdadeiro e execução;
  - reflexo.
- **Capacidades**, coletadas de todos os campeões, com os limites de cada uma:
  - invisibilidade, camuflagem e revelar;
  - arremesso, atordoamento e supressão;
  - "outra dimensão", inalvejável e estase;
  - avanços por combo;
  - alcance.

É o que torna o resto viável: em vez de combinar valores livres, só combinamos o que é possível. A tenacidade, por
exemplo, tem um punhado de valores reais.

### 2. Treino genérico de interações (horas, em segundo plano)

1. **Perfis:** um perfil é um ponto dentro do catálogo (os eixos acima), não uma build. Sorteamos pares de perfis (meu
   × do inimigo) cobrindo o espaço, por hipercubo latino e não por todas as combinações.
2. **Lutas:** cada par luta um x1 com os kits reais. O primeiro marco usa os 11 campeões com ficha A/B. Os eixos entram
   como itens sintéticos, que a Arena já aceita (`Slot.replaceItems`).
3. **Medida:** a corrida das barras de vida da Rodada 15. A campanha é determinística por semente e retomável.
4. **Modelo:** um modelo que prevê a corrida a partir dos dois perfis. Uma regressão com termos de interação entre
   eixos (a `Ridge` da Descoberta, ampliada), ou árvores de gradiente em Java. Não precisa de aprendizado profundo; os
   pares de eixos são poucos dezenas.

Os coeficientes das interações **são** o catálogo: "Feridas × cura do inimigo", "tenacidade × controle encadeado",
"redução de velocidade de ataque × crítico", "Vida × dano pela Vida máxima", e assim por diante.

### 3. Guias gerais por objetivo (segundos, a partir do modelo)

Para cada tipo de inimigo (subclasse, ou um campeão), quais eixos subir e quanto rende cada ouro neles. Os eixos voltam
para itens, runas e feitiços pelo catálogo: o ouro de cada unidade de eixo, como na triagem da Descoberta.

Exemplo do formato: "contra atirador de crítico: redução de velocidade de ataque > Armadura > Vida; Feridas só se ele
tiver cura".

### 4. Proposta por matchup (segundos para propor, minutos para confirmar)

Responde a "eu com X contra Y". Cada restrição que se informa reduz o espaço de builds:

- do inimigo: runas, feitiços, meios itens, primeiros itens;
- minhas: as mesmas.

O caminho é este:

1. O guia da etapa 3 escolhe os itens que movem os eixos certos.
2. O modelo ordena as candidatas, sem luta.
3. Só as melhores vão para a luta de verdade.

Mostra dos dois lados o que é mais provável (as builds salvas e as padrão da campanha) e o que é mais eficaz. A mesma
conta ordena campeões contra Y, o que dá um começo de draft.

### 5. Site estático

Uma página nova, "Matchups", com as tabelas pré-calculadas: os 11 × 11 em alguns minutos e as restrições mais
prováveis do inimigo. O site só tem JSON, então só aparece o que foi calculado.

### 6. Depois

Lutas em equipe e a partida inteira (`Match`), drafts dinâmicos e mais campeões conforme as fichas chegarem a A/B.

## Primeiro marco sugerido

Etapas 1, 2 e 4, só com os 11 campeões, só x1, todos os itens, nos minutos 10, 15 e 20. O site fica para o segundo
marco.

Estimativa de cada luta x1 de 60 s: 50 a 200 ms. Com uns 300 pares de perfis por matchup, são 11 × 11 × 300 lutas por
minuto, entre 1 e 4 horas no total, em paralelo.

## Decisões em aberto

1. **Perfis:** sobre os 11 kits reais (mais fiel, cobre só esses) ou sobre kits genéricos por classe (cobre todos, mas
   menos fiel)? Sugestão: os kits reais primeiro, e os genéricos quando a ideia se provar.
2. **Lugar na Descoberta:** o modelo substitui as lutas rápidas, que viram o filtro, ou fica como ferramenta à parte?
   Sugestão: à parte no primeiro marco, e depois como filtro.
3. **Tela:** uma tela "Matchups" no app local antes do site.
