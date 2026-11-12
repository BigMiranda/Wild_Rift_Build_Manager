import { useEffect, useMemo, useState } from 'react';
import { ChampionAvatar } from '../components/ChampionAvatar.jsx';
import ItemIcon from '../components/ItemIcon.jsx';
import GuideFlow from '../components/GuideFlow.jsx';
import DiscoveryNotes from '../components/DiscoveryNotes.jsx';
import { archetypeLabel } from '../archetypes.js';
import { t } from '../i18n.js';
import { mmss, n0, n1 } from '../format.js';
import { filterBuilds, filterChampions, filterOptions, KIND_LABEL, KINDS, RUNES_FROM, sectionOf } from './data.js';
import { siteHref, useSite } from './StaticApp.jsx';

const GRADE = (g) => (g ? t(`efx.grade.${g}`) : '');
const gradeOf = (v, grades) => (grades ?? []).find((g) => g.from == null || v >= g.from)?.key ?? null;

/** Where the effectiveness grades come from: the basic report's grades (any build's outcome carries them). */
export function IndexBadge({ value, label, grade }) {
  if (value == null) return null;
  return (
    <span className={`site-index ${grade ? `g-${grade}` : ''}`} title={label}>
      {n1(value)}{grade ? ` · ${GRADE(grade)}` : ''}
    </span>
  );
}

function Select({ label, value, onChange, options, render = (x) => x }) {
  return (
    <label className="site-select">
      <span>{label}</span>
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">Todos</option>
        {options.map((o) => <option key={o} value={o}>{render(o)}</option>)}
      </select>
    </label>
  );
}

const archName = (key) => archetypeLabel({ key });

/** Campeões: a grid, with search and filters by class, lane and archetype. */
export function ChampionsPage() {
  const { manifest } = useSite();
  const [f, setF] = useState({ text: '', cls: '', lane: '', archetype: '' });
  const opts = useMemo(() => filterOptions(manifest.champions), [manifest]);
  const list = filterChampions(manifest.champions, f).sort((a, b) => a.champion.localeCompare(b.champion, 'pt-BR'));
  const set = (k) => (v) => setF((x) => ({ ...x, [k]: v }));
  return (
    <section>
      <h1 className="site-title">Campeões</h1>
      <p className="muted site-lead">
        Builds achadas pela Descoberta e builds públicas, com a linha do tempo, a efetividade e as habilidades já calculadas.
      </p>
      <div className="site-filters">
        <input type="search" placeholder="Buscar campeão" value={f.text} onChange={(e) => set('text')(e.target.value)} aria-label="Buscar campeão" />
        <Select label="Classe" value={f.cls} onChange={set('cls')} options={opts.classes} />
        <Select label="Rota" value={f.lane} onChange={set('lane')} options={opts.lanes} />
        <Select label="Arquétipo" value={f.archetype} onChange={set('archetype')} options={opts.archetypes} render={archName} />
      </div>
      {list.length === 0 && <p className="muted">Nenhum campeão com esses filtros.</p>}
      <ul className="site-grid">
        {list.map((c) => (
          <li key={c.unitCode}>
            <a className="site-card champ-card" href={siteHref('c', c.unitCode)}>
              <ChampionAvatar name={c.champion} size={44} />
              <strong>{c.champion}</strong>
              <small className="muted">{[c.championClass, ...(c.lanes ?? [])].filter(Boolean).join(' · ')}</small>
              <small>{c.builds} build{c.builds === 1 ? '' : 's'}{c.discovered ? ' · Descoberta' : ''}</small>
            </a>
          </li>
        ))}
      </ul>
    </section>
  );
}

const SCORE_HELP = 'Nota da build: a nota por classe (defensiva, ofensiva e utilidade pesadas pela classe do campeão), '
  + 'robusta nos minutos 10, 15, 20 e no fim. É a que ordenou as builds da Descoberta.';

/**
 * The numbers of a build: the class score that ranked it (when the Discovery measured it) first, then the defensive
 * effectiveness at the last item and the offensive one (Rodada 13: before, only the defensive index was shown, and a
 * build ranked low could look better than the standard).
 */
export function BuildNumbers({ b, grades }) {
  return (
    <div className="build-card-facts">
      {b.score != null && (
        <span title={SCORE_HELP}>Nota <IndexBadge value={b.score} label={SCORE_HELP} grade={b.scoreGrade ?? gradeOf(b.score, grades)} /></span>
      )}
      <span className={b.score != null ? 'muted' : ''} title="Efetividade defensiva no último item (relatório Básico)">
        Defensiva <IndexBadge value={b.index} label="Efetividade defensiva no último item" grade={b.grade ?? gradeOf(b.index, grades)} />
      </span>
      {b.offenseIndex != null && <span className="muted" title="Efetividade ofensiva no último item">Ofensiva {n0(b.offenseIndex)}</span>}
    </div>
  );
}

/** The keystone and where the page came from. */
export function Keystone({ b }) {
  if (!b.keystone) return b.kind === 'publica' ? null : <span className="muted" title="Sem página de runas">Sem runas</span>;
  const from = RUNES_FROM[b.runesFrom];
  return <span title={from}>{b.keystone}{b.runesFrom && b.runesFrom !== 'busca' ? ' *' : ''}</span>;
}

/** A build in a list: name, kind, archetype, items in order, the score and the effectiveness. */
export function BuildCard({ b, grades }) {
  const { compare, toggleCompare } = useSite();
  const on = compare.includes(b.key);
  return (
    <article className="site-card build-card">
      <header>
        <span className={`kind kind-${b.kind}`}>{KIND_LABEL[b.kind]}</span>
        <a href={siteHref('b', b.key)}><strong>{b.label}</strong></a>
      </header>
      {(b.also ?? []).length > 0 && <small className="muted">Também: {b.also.join(', ')}</small>}
      <div className="item-row">
        {(b.items ?? []).map((id, i) => <ItemIcon key={`${id}-${i}`} id={id} size={30} />)}
      </div>
      <div className="build-card-facts">
        {b.archetype && <span>{archetypeLabel(b.archetypeInfo ?? { key: b.archetype })}</span>}
        <Keystone b={b} />
        {b.lastMinute != null && <span>Último item {mmss(b.lastMinute)}</span>}
      </div>
      <BuildNumbers b={b} grades={grades} />
      {b.orderCut && <p className="small muted">Ordem da triagem: a busca da ordem não terminou a tempo.</p>}
      {b.text && <p className="small">{b.text}</p>}
      <footer>
        <a className="btn primary" href={siteHref('b', b.key)}>Abrir</a>
        <button type="button" className={on ? 'on' : ''} onClick={() => toggleCompare(b.key)}>{on ? '✓ Comparando' : 'Comparar'}</button>
      </footer>
    </article>
  );
}

/** A guide's tree as a flowchart (Rodada 13: it was nested lists). */
export function GuideTree({ nodes, items }) {
  return <GuideFlow nodes={nodes} itemName={(id) => items?.[id] ?? `item ${id}`} />;
}

/** Um campeão: as builds (descobertas, públicas), filtros, as matchups da campanha e as guias. */
export function ChampionPage({ unit }) {
  const { store, runesReady } = useSite();
  const [items, setItems] = useState(null);
  const [c, setC] = useState(null);
  const [error, setError] = useState(null);
  const [f, setF] = useState({ kind: '', archetype: '' });
  const [grades, setGrades] = useState(null);
  useEffect(() => {
    let live = true;
    setC(null);
    store.champion(unit).then((x) => { if (live) setC(x); }).catch((e) => { if (live) setError(e.message); });
    return () => { live = false; };
  }, [store, unit]);
  useEffect(() => {
    // the grades of the basic report (small): the same scale the full app shows
    store.basic().then((r) => setGrades(r?.grades ?? null)).catch(() => {});
  }, [store]);
  useEffect(() => {
    // the shop's names and ids, for the Discovery's notes (item names become icons)
    store.items().then((x) => setItems(Array.isArray(x) ? x : x?.items ?? [])).catch(() => {});
  }, [store]);
  if (error) return <p className="error">{error}</p>;
  if (!c) return <p className="muted">Carregando…</p>;
  const builds = filterBuilds(c.builds, f);
  const best = builds.filter((b) => sectionOf(b) === 'melhores');
  const novel = builds.filter((b) => sectionOf(b) === 'novidade');
  const kinds = KINDS.filter((k) => c.builds.some((b) => b.kind === k));
  const archetypes = [...new Set(c.builds.map((b) => b.archetype).filter(Boolean))];
  const itemNames = Object.fromEntries(c.builds.flatMap((b) => (b.items ?? []).map((id, i) => [id, b.itemNames?.[i]])));
  return (
    <section>
      <p><a href="#/">← Campeões</a></p>
      <h1 className="site-title site-title-champ"><ChampionAvatar name={c.champion} size={48} /> {c.champion}</h1>
      <p className="muted">{[c.championClass, ...(c.lanes ?? [])].filter(Boolean).join(' · ')}
        {c.subclasses?.length ? ` · ${c.subclasses.join(', ')}` : ''}
        {c.quality ? ` · ficha ${c.quality}` : ''}{c.budget ? ` · Descoberta ${c.budget === 'completo' ? 'completa' : 'rápida'}` : ''}</p>
      {c.lowConfidence && <p className="warn">A ficha deste campeão é de baixa confiança: leia os números com cuidado.</p>}
      <div className="site-filters">
        <Select label="Tipo" value={f.kind} onChange={(v) => setF({ ...f, kind: v })} options={kinds} render={(k) => KIND_LABEL[k]} />
        <Select label="Arquétipo" value={f.archetype} onChange={(v) => setF({ ...f, archetype: v })} options={archetypes} render={archName} />
      </div>
      <div className="site-list">
        {best.map((b) => <BuildCard key={b.key} b={b} grades={grades} />)}
        {best.length === 0 && <p className="muted">Nenhuma build com esses filtros.</p>}
      </div>
      {best.some((b) => b.runesFrom && b.runesFrom !== 'busca') && (
        <p className="small muted">* Runas não buscadas para aquela build: são as da padrão robusta ou as da build pública.</p>
      )}
      {novel.length > 0 && (
        <details className="site-section" open={c.objective === 'novidade'}>
          <summary><strong>Novidade</strong> <span className="muted">({novel.length}: melhor de cada nicho e descobertas)</span></summary>
          <p className="small muted">
            Builds diferentes das conhecidas: a melhor de cada estilo (nicho), mesmo quando o estilo não combina com o campeão,
            e as descobertas (nota maior que a da melhor build conhecida, com itens diferentes). Não são as recomendadas.
          </p>
          <div className="site-list">
            {novel.map((b) => <BuildCard key={b.key} b={b} grades={grades} />)}
          </div>
        </details>
      )}
      {c.matchups?.length > 0 && <Matchups list={c.matchups} />}
      {c.styles?.length > 0 && <LaneStyles list={c.styles} />}
      {c.threats?.length > 0 && <Threats list={c.threats} />}
      {(c.guides?.length > 0 || c.suggestedGuide?.nodes?.length > 0) && (
        <section className="site-section">
          <h2>Guias</h2>
          {c.guides.map((g) => (
            <details key={g.id} className="site-card">
              <summary><strong>{g.name}</strong></summary>
              <GuideTree nodes={g.nodes} items={itemNames} />
            </details>
          ))}
          {c.suggestedGuide?.nodes?.length > 0 && (
            <details className="site-card">
              <summary><strong>Sugerida pela Descoberta</strong></summary>
              <GuideTree nodes={c.suggestedGuide.nodes} items={itemNames} />
            </details>
          )}
        </section>
      )}
      {(c.notes?.length > 0 || c.synergies?.length > 0) && (
        <details className="site-section">
          <summary><strong>Como a Descoberta chegou nessas builds</strong></summary>
          {c.synergies?.length > 0 && <Synergies list={c.synergies} />}
          <DiscoveryNotes key={runesReady ? 'r' : 'n'} notes={c.notes} items={items} />
        </details>
      )}
    </section>
  );
}

/**
 * The pairs of items of the best kit: what the pair adds beyond each item alone. Negative (the usual) means the two
 * cover the same need, so the second one is worth less next to the first.
 */
function Synergies({ list }) {
  // the strongest effects first; the rest folded (Ornn's best kit has 21 pairs)
  const sorted = [...list].sort((a, b) => Math.abs(b.value) - Math.abs(a.value));
  const top = sorted.slice(0, 6);
  const rest = sorted.slice(6);
  return (
    <section className="disc-notes">
      <h4>Pares de itens</h4>
      <p className="small muted">
        No conjunto mais bem colocado, quanto cada par de itens soma além do que cada um soma sozinho. Negativo: os dois
        cobrem a mesma necessidade e o segundo vale menos ao lado do primeiro. Positivo: um potencializa o outro.
      </p>
      <ul className="note-list">{top.map(pairItem)}</ul>
      {rest.length > 0 && (
        <details>
          <summary className="small muted">Os outros {rest.length} pares</summary>
          <ul className="note-list">{rest.map(pairItem)}</ul>
        </details>
      )}
    </section>
  );
}

const pairItem = (s, i) => (
  <li key={i} className="note-pair">
    <span className="note-icon" title={s.nameA}><ItemIcon id={s.a} size={26} /></span>
    <span className="note-arrow">+</span>
    <span className="note-icon" title={s.nameB}><ItemIcon id={s.b} size={26} /></span>
    <b className={s.value >= 0 ? 'delta-up' : 'delta-down'}>{s.value >= 0 ? '+' : '−'}{n1(Math.abs(s.value))}</b>
    <small className="muted">{pairText(s.explain)}</small>
  </li>
);

/** "A + B: 73.0 juntos, 70.5 e 67.7 sozinhos, 60.7 sem os dois" without the names (the icons show them). */
const pairText = (t) => String(t ?? '').replace(/^.*?: /, '');

/** The lane styles in words (the campaign's keys, macro_<patch>.yml). */
export const STYLE_LABEL = {
  padrao: 'Padrão',
  trocas_curtas: 'Trocas curtas',
  trocas_longas: 'Trocas longas',
  recuado: 'Farmar recuado',
  pressao: 'Pressão constante',
};
const STYLE_HELP = {
  padrao: 'trocar quando der e farmar',
  trocas_curtas: 'entrar, soltar o dano e sair antes da resposta',
  trocas_longas: 'ficar na troca até o fim',
  recuado: 'farmar de longe e esperar as lutas em equipe',
  pressao: 'afastar o adversário da onda até a borda do raio de XP, para negar XP e ouro',
};

/**
 * Rodada 14: the lane phase against each rival, from the campaign's 1v1 lane matches to 10 minutes: the lead (gold plus
 * experience in gold) in each style, the best one marked.
 */
export function LaneStyles({ list }) {
  const styles = Object.keys(list[0]?.leads ?? {});
  return (
    <section className="site-section">
      <h2>Fase de rotas</h2>
      <p className="muted small">
        A build padrão na rota contra cada rival, até os 10 minutos, jogando de cada jeito (o rival joga o padrão): a vantagem
        em ouro, com a XP convertida em ouro. Passe o mouse no nome do estilo para ver o que ele faz. Roaming, ganks e visão
        precisam das outras rotas e não entram nesta conta.
      </p>
      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr><th>Rival</th>{styles.map((st) => <th key={st} title={STYLE_HELP[st]}>{STYLE_LABEL[st] ?? st}</th>)}</tr>
          </thead>
          <tbody>
            {list.map((l, i) => (
              <tr key={i}>
                <td><ChampionAvatar name={l.rival} size={22} /> {l.rival}<br /><small className="muted">{(l.subclasses ?? []).join(', ')}</small></td>
                {styles.map((st) => {
                  const v = l.leads[st];
                  return (
                    <td key={st} className={st === l.best ? 'best-style' : ''}>
                      <span className={v >= 0 ? 'delta-up' : 'delta-down'}>{v >= 0 ? '+' : '−'}{n0(Math.abs(v))}</span>
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/**
 * Rodada 14: against each kind of enemy team (the threat scenarios: assassins, colossi, engage tanks...), the build of
 * the ranking that held best at minute 20, when it beats the standard.
 */
export function Threats({ list }) {
  return (
    <section className="site-section">
      <h2>Contra cada tipo de time</h2>
      <p className="muted small">
        Times inimigos que imitam cada subclasse de campeão, aos 20 minutos: a efetividade da build padrão e a da build que
        melhor se saiu ali entre as mais bem colocadas. O guia sugerido usa as que ganham da padrão por 3 pontos ou mais.
      </p>
      <ul className="note-list">
        {list.map((t, i) => {
          const gain = t.bestScore != null ? t.bestScore - t.standardScore : 0;
          return (
            <li key={i} className="threat-row">
              <strong>{t.name}</strong>{' '}
              <small className="muted">({t.count > 1 ? `${t.count}+ ` : ''}{(t.subclasses ?? []).join(' ou ')})</small>
              <div>
                padrão {n1(t.standardScore)}
                {t.bestItems?.length > 0 && gain > 0 ? (
                  <>
                    {' · '}melhor <b>{n1(t.bestScore)}</b>{' '}
                    <span className={gain >= 3 ? 'delta-up' : 'muted'}>(+{n1(gain)})</span>{' '}
                    <span className="note-items">
                      {t.bestItems.map((id, k) => <span key={k} className="note-icon" title={t.bestNames?.[k] ?? ''}><ItemIcon id={id} size={24} /></span>)}
                    </span>
                  </>
                ) : <span className="muted"> · a padrão já é a melhor</span>}
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/** As matchups da campanha: a build padrão contra cada rival, a vida que sobra (minha − dele) aos 10, 15 e 20. */
export function Matchups({ list }) {
  return (
    <section className="site-section">
      <h2>Matchups</h2>
      <p className="muted small">A build padrão em 1 contra 1 com o cérebro automático: a vida que sobra, a minha menos a do rival (em % da vida máxima).</p>
      <ul className="matchups">
        {list.map((m, i) => (
          <li key={i} className="site-card">
            <strong>{m.rival}</strong>{m.build ? <small className="muted"> ({m.build})</small> : null}
            <span className={m.mean >= 0 ? 'delta-up' : 'delta-down'}>{m.mean >= 0 ? '+' : ''}{n0(m.mean)}</span>
            <small className="muted">{Object.entries(m.minutes ?? {}).map(([k, v]) => `${n0(Number(k))}min ${v >= 0 ? '+' : ''}${n0(v)}`).join(' · ')}</small>
          </li>
        ))}
      </ul>
    </section>
  );
}
