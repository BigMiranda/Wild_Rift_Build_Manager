# Wild Rift Builds

Um site estático com builds de **League of Legends: Wild Rift**: a ordem de compra no tempo, a ficha do campeão, a
efetividade em cenários de luta, as habilidades e as matchups, nos minutos-chave da partida.

**Patch dos dados:** veja `api/manifest.json` (campo `patch`) ou o topo do site.

## De onde vêm os números

Nada é calculado neste site. Tudo foi calculado num app local (o *Wild Rift Build Manager*), no PC do autor:

- as builds de cada campeão saem da **Descoberta** (uma busca de builds que mede cada candidata em cenários de luta
  simulados) e das **builds públicas** que o autor escolheu mostrar;
- para cada build, o app calculou a linha do tempo de compras em três ritmos de ouro, e nos minutos-chave (10, 15 e o
  último item) a Efetividade Básica (defensiva), a ofensiva e as habilidades;
- o script `tools/publicar_site.py` do app gera esta pasta inteira: a página (`index.html`, `assets/`) e os dados
  (`api/`, um JSON por tela).

Um modelo é um modelo: os números servem para comparar builds entre si, não para prever uma partida.

## Enviar uma build

No site, monte a sua em **Montar**, use **Copiar link** ou **Exportar arquivo**, e abra uma issue com o formulário
**"Enviar uma build"**. As builds enviadas são revisadas à mão.

## Como atualizar (para o autor)

1. No app: rodar as campanhas do patch, marcar as pastas e guias públicas, exportar (`cd backend && ./mvnw -Pexport`).
2. `python tools/publicar_site.py` (no repositório do app).
3. Apagar tudo deste repositório **menos** `.git/`, `.github/`, `README.md` e `.nojekyll`, e copiar o conteúdo de
   `site-publico/` para cá.
4. `git add -A && git commit -m "Atualiza para o patch X" && git push`. O workflow `pages.yml` publica sozinho.

O passo a passo completo está em `docs/rodada-11b.md` do app.
