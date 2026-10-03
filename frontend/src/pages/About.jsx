export default function About() {
  return (
    <div className="main" style={{ maxWidth: 820 }}>
      <section className="panel">
        <h2>Sobre</h2>
        <p>
          Ferramenta pessoal para planejar e comparar a ordem de compra de itens do Ornn (tank/suporte) no League of Legends:
          Wild Rift. Não interage com o jogo de forma nenhuma.
        </p>
        <h3>Créditos dos dados</h3>
        <p>
          O catálogo de itens (patch 7.3, em português) foi transcrito de capturas da loja do próprio jogo, assim como
          os ícones. A metodologia de preço por status e de eficiência de ouro estática vem do projeto{' '}
          <a href="https://github.com/changchiyou/wildrift-gold-efficiency" target="_blank" rel="noreferrer">changchiyou/wildrift-gold-efficiency</a>,
          licença MIT — Copyright (c) 2024 changchiyou; o aviso completo está em{' '}
          <code>backend/src/test/resources/referencia/LICENSE-wildrift-gold-efficiency.txt</code>.
        </p>
        <p>Status base do Ornn e da passiva Forja Viva: wiki.leagueoflegends.com/en-us/WR:Ornn.</p>
        <p>Ícones e textos dos itens: Riot Games (League of Legends: Wild Rift), recortados das capturas da loja.</p>
        <h3>Aproximações</h3>
        <ul>
          <li><strong>Tabela de XP por nível</strong>: estimativa (primeiros 15 níveis da curva do LoL de PC), editável no Admin.</li>
          <li>Só as passivas que alteram status foram modeladas em números (aba “Considerado no cálculo” de cada item); efeitos de dano, cura e escudo aparecem só como texto.</li>
          <li>Efeitos <strong>condicionais</strong> (acúmulos em combate, vida baixa, carga de mana) podem ser ligados ou desligados em cada build.</li>
          <li>Status adaptativos (ex.: Grevas Vorazes) foram modelados como Dano de Ataque, que é o que o Ornn recebe.</li>
          <li>O tempo de compra considera apenas o ouro acumulado (não modela a volta à base).</li>
          <li>Passivas percentuais compõem na ordem de compra; o detalhamento aparece ao clicar numa linha da linha do tempo.</li>
        </ul>
      </section>
    </div>
  );
}
