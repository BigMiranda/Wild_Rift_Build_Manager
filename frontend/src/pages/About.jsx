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
          O catálogo de itens (patch 7.3) e a metodologia de preço por status / eficiência de ouro estática vêm do projeto{' '}
          <a href="https://github.com/changchiyou/wildrift-gold-efficiency" target="_blank" rel="noreferrer">changchiyou/wildrift-gold-efficiency</a>,
          licença MIT — Copyright (c) 2024 changchiyou. O aviso de licença completo está em{' '}
          <code>backend/src/main/resources/seed/LICENSE-wildrift-gold-efficiency.txt</code>.
        </p>
        <p>Status base do Ornn e da passiva Forja Viva: wiki.leagueoflegends.com/en-us/WR:Ornn.</p>
        <p>Ícones dos itens: arte da Riot Games, obtida via wiki.leagueoflegends.com e guardada localmente no projeto.</p>
        <h3>Aproximações</h3>
        <ul>
          <li><strong>Tabela de XP por nível</strong>: estimativa (primeiros 15 níveis da curva do LoL de PC), editável no Admin.</li>
          <li><strong>Receitas</strong> (componentes de cada item) não existem na fonte de dados; cadastre-as no Admin para modelar compra de “meio item”.</li>
          <li>O tempo de compra considera apenas o ouro acumulado (não modela a volta à base).</li>
          <li>Passivas percentuais compõem na ordem de compra; o detalhamento aparece ao clicar numa linha da linha do tempo.</li>
        </ul>
      </section>
    </div>
  );
}
