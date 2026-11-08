# Rodada 11.b — Site estático no GitHub Pages (sem servidor, bom no celular)

Prompt: `prompt-rodada-11b-site-estatico.md`, depois da Rodada 12. Uma parte por commit, sem push, no `main`.
**Decisão do usuário:** a versão pública é um **site estático no GitHub Pages, num repositório à parte**, só com o que
foi calculado no PC. O perfil `publico` e o `Dockerfile` da Rodada 11 continuam, mas não são o alvo. **Nada foi
publicado:** tudo fica pronto para o usuário criar o repositório e dar push.

## Parte 1 — Privacidade na exportação ✅

### O vazamento
O resultado da Descoberta guarda as **builds conhecidas** do campeão (`DiscoveryModel.Result.known`: o nome e os itens de
cada build salva, de **qualquer** pasta), e o resultado de uma campanha guarda ainda as builds salvas medidas (`saved`,
com id e nome) e o nome da build de cada rival das matchups. O `PublicExporter` mantinha as linhas `build_discovery`
terminadas sem limpar esse conteúdo: uma build de pasta privada podia sair no site.

### A correção (`publico/PublicScrubber`, chamada pelo `PublicExporter` logo depois dos cortes)
- **Em cada linha `build_discovery` exportada:**
  - `known`: as builds **salvas** que não são públicas saem **por inteiro** (nome, itens, leituras). Uma conhecida só
    fica se for uma build pública do mesmo campeão (mesmo nome e os itens do kit dentro dos da build). As **builds da
    meta** (com fonte) ficam.
  - `saved` (o passo "salvas" da campanha): só as linhas de builds públicas.
  - `matchups`: a rival privada mantém o campeão e a medida; o nome da build vira "build (privada)".
  - O pedido (`config`): a build enviada sai, e o id da build base, se privado, vira `null`.
  - **A build base privada** (a do pedido; numa campanha, a primeira build salva do campeão): o nome dela vira
    "build base (privada)" onde aparecer — "mais próxima", "supera a conhecida" (`against`), o texto da descoberta, as
    notas. Os itens dela saem com as conhecidas.
- **Depois, uma varredura do banco copiado inteiro:** toda coluna de texto de toda tabela, menos as do catálogo (itens,
  campeões, níveis). O nome de uma build ou pasta privada dentro de qualquer texto vira "build (privada)"; o JSON é
  percorrido (continua válido, inclusive nas chaves) e um `buildId` privado vira `null`. Cobre as **guias sugeridas
  pela Descoberta** (`build_guide.tree`), os **combos** (`arena_combo`: nome, dados), o nome da campanha, as notas das
  builds públicas e o que mais aparecer depois.
- **Não troca** o nome privado que também é publicado de qualquer jeito: igual a (ou dentro de) uma build, pasta ou
  guia pública, ou um nome do catálogo (item, campeão). Nomes com menos de 3 letras também não (não identificam nada e
  estragariam qualquer texto). O relatório da exportação conta o que foi trocado, por tabela e coluna.

### Teste (`PublicDiscoveryPrivacyTest`)
Uma pasta pública e uma privada (com nomes que não existem em mais lugar nenhum), uma Descoberta que conhece as duas
(a base é a privada), uma matchup contra a privada, uma guia pública que a cita por nome e por id e um combo achado
nela. Exportado:
- **nenhum nome da build privada nem da pasta privada aparece no arquivo** (busca nos bytes do banco inteiro);
- nenhum `buildId` privado em `build_discovery.config/result`, `build_guide.tree` e `arena_combo.data`, e
  `arena_combo.build_id` vazio;
- ficam a conhecida pública e a da meta; "mais próxima" e "supera" viram "build base (privada)"; a matchup mantém o
  rival (Darius) e perde o nome da build; o id da build pública na guia continua.
