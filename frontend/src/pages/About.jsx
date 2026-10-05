import { currentLang } from '../i18n.js';

const LINK = (
  <a href="https://github.com/changchiyou/wildrift-gold-efficiency" target="_blank" rel="noreferrer">changchiyou/wildrift-gold-efficiency</a>
);
const LICENSE = <code>backend/src/test/resources/referencia/LICENSE-wildrift-gold-efficiency.txt</code>;

export default function About() {
  const en = currentLang() === 'en';
  return (
    <div className="main" style={{ maxWidth: 820 }}>
      <section className="panel">
        <h2>{en ? 'About' : 'Sobre'}</h2>
        <p>
          {en
            ? 'Personal tool to plan and compare Ornn\'s item purchase order (tank/support) in League of Legends: Wild Rift. It does not interact with the game in any way.'
            : 'Ferramenta pessoal para planejar e comparar a ordem de compra de itens do Ornn (tank/suporte) no League of Legends: Wild Rift. Não interage com o jogo de forma nenhuma.'}
        </p>
        <h3>{en ? 'Data credits' : 'Créditos dos dados'}</h3>
        <p>
          {en
            ? <>The item catalog (patch 7.3, in Portuguese) was transcribed from screenshots of the game's own shop, and so were the icons. Item names and passive texts stay in Portuguese, as in the shop. The price-per-stat and static gold efficiency methodology comes from {LINK}, MIT License — Copyright (c) 2024 changchiyou; full notice in {LICENSE}.</>
            : <>O catálogo de itens (patch 7.3, em português) foi transcrito de capturas da loja do próprio jogo, assim como os ícones. A metodologia de preço por status e de eficiência de ouro estática vem do projeto {LINK}, licença MIT — Copyright (c) 2024 changchiyou; o aviso completo está em {LICENSE}.</>}
        </p>
        <p>{en ? 'Ornn base stats and Living Forge: wiki.leagueoflegends.com/en-us/WR:Ornn.' : 'Status base do Ornn e da passiva Forja Viva: wiki.leagueoflegends.com/en-us/WR:Ornn.'}</p>
        <p>{en ? 'Item icons and texts: Riot Games (League of Legends: Wild Rift), cropped from the shop screenshots. Stat icons are original drawings in the game\'s colors.' : 'Ícones e textos dos itens: Riot Games (League of Legends: Wild Rift), recortados das capturas da loja. Os ícones de status são desenhos próprios nas cores do jogo.'}</p>
        <h3>{en ? 'Approximations' : 'Aproximações'}</h3>
        <ul>
          {en ? (
            <>
              <li><strong>XP per level table</strong>: an estimate (first 15 levels of the LoL PC curve), editable in Admin.</li>
              <li>Only passives that change stats are modeled as numbers (“Counted in the calculation” on each item); damage, healing and shield effects are text only.</li>
              <li><strong>Conditional</strong> effects (stacks in combat, low health, mana charge) can be switched on or off for each purchase.</li>
              <li>Evolving items (e.g. Aproximação Invernal → Fimbulwinter) can be counted in either state for each purchase.</li>
              <li>Adaptive stats (e.g. Grevas Vorazes) are modeled as Attack Damage, which is what Ornn gets.</li>
              <li>Purchase time only considers accumulated gold (it does not model going back to base).</li>
              <li>Stats depend only on the items held, never on purchase order: conversions (e.g. Mana → Health) read the final value of their source, and percentage increases of the same stat (item passives and Living Forge) are each taken from the value before multipliers and added up, as documented on the League of Legends wiki. Click a timeline row to see the breakdown.</li>
            </>
          ) : (
            <>
              <li><strong>Tabela de XP por nível</strong>: estimativa (primeiros 15 níveis da curva do LoL de PC), editável no Admin.</li>
              <li>Só as passivas que alteram status foram modeladas em números (“Considerado no cálculo” em cada item); efeitos de dano, cura e escudo aparecem só como texto.</li>
              <li>Efeitos <strong>condicionais</strong> (acúmulos em combate, vida baixa, carga de mana) podem ser ligados ou desligados em cada compra.</li>
              <li>Itens que evoluem (ex.: Aproximação Invernal → Fimbulwinter) podem ser contados em qualquer dos dois estados em cada compra.</li>
              <li>Status adaptativos (ex.: Grevas Vorazes) foram modelados como Dano de Ataque, que é o que o Ornn recebe.</li>
              <li>O tempo de compra considera apenas o ouro acumulado (não modela a volta à base).</li>
              <li>Os status dependem só dos itens que você tem, nunca da ordem de compra: conversões (ex.: Mana → Vida) leem o valor final do status de origem, e aumentos percentuais do mesmo status (passivas de itens e Forja Viva) são calculados sobre o valor antes dos multiplicadores e somados, como documenta a wiki do League of Legends. O detalhamento aparece ao clicar numa linha da linha do tempo.</li>
            </>
          )}
        </ul>
      </section>
    </div>
  );
}
