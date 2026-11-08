import { useEffect, useMemo, useState } from 'react';
import ItemIcon from '../components/ItemIcon.jsx';
import { archetypeLabel } from '../archetypes.js';
import { t } from '../i18n.js';
import { mmss, n0, n1 } from '../format.js';
import { filterBuilds, filterChampions, filterOptions, KIND_LABEL, KINDS } from './data.js';
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
              <span className="champ-initial" aria-hidden="true">{c.champion.slice(0, 1)}</span>
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

/** A build in a list: name, kind, archetype, items in order, the effectiveness. */
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
        {b.keystone && <span>{b.keystone}</span>}
        {b.lastMinute != null && <span>Último item {mmss(b.lastMinute)}</span>}
      </div>
      <div className="build-card-facts">
        <IndexBadge value={b.index} label="Efetividade defensiva no último item" grade={b.grade ?? gradeOf(b.index, grades)} />
        {b.offenseIndex != null && <span className="muted" title="Efetividade ofensiva no último item">Ofensiva {n0(b.offenseIndex)}</span>}
      </div>
      {b.text && <p className="small">{b.text}</p>}
      <footer>
        <a className="btn primary" href={siteHref('b', b.key)}>Abrir</a>
        <button type="button" className={on ? 'on' : ''} onClick={() => toggleCompare(b.key)}>{on ? '✓ Comparando' : 'Comparar'}</button>
      </footer>
    </article>
  );
}

/** A guide's tree, as nested lists: "if … then … otherwise …", each decision an item or a rune page. */
export function GuideTree({ nodes, items }) {
  if (!nodes?.length) return null;
  return (
    <ul className="guide-tree">
      {nodes.map((n, i) => (
        <li key={n.id ?? i}>
          {n.kind === 'condicao' ? (
            <>
              <span>Se <strong>{String(n.cond?.type ?? '').replace(/_/g, ' ')}</strong>{n.cond?.value != null ? ` ${n.cond.value}` : ''}:</span>
              <GuideTree nodes={n.then} items={items} />
              {n.otherwise?.length > 0 && <><span>Senão:</span><GuideTree nodes={n.otherwise} items={items} /></>}
            </>
          ) : (
            <span className="guide-decision">
              {n.itemId != null && <ItemIcon id={n.itemId} size={22} />}
              {n.itemId != null ? items?.[n.itemId] ?? `item ${n.itemId}` : n.runePage?.keystone ?? n.decision}
              {n.note && <small className="muted"> — {n.note}</small>}
            </span>
          )}
        </li>
      ))}
    </ul>
  );
}

/** Um campeão: as builds (descobertas, públicas), filtros, as matchups da campanha e as guias. */
export function ChampionPage({ unit }) {
  const { store } = useSite();
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
  if (error) return <p className="error">{error}</p>;
  if (!c) return <p className="muted">Carregando…</p>;
  const builds = filterBuilds(c.builds, f);
  const kinds = KINDS.filter((k) => c.builds.some((b) => b.kind === k));
  const archetypes = [...new Set(c.builds.map((b) => b.archetype).filter(Boolean))];
  const itemNames = Object.fromEntries(c.builds.flatMap((b) => (b.items ?? []).map((id, i) => [id, b.itemNames?.[i]])));
  return (
    <section>
      <p><a href="#/">← Campeões</a></p>
      <h1 className="site-title">{c.champion}</h1>
      <p className="muted">{[c.championClass, ...(c.lanes ?? [])].filter(Boolean).join(' · ')}
        {c.quality ? ` · ficha ${c.quality}` : ''}{c.budget ? ` · Descoberta ${c.budget === 'completo' ? 'completa' : 'rápida'}` : ''}</p>
      {c.lowConfidence && <p className="warn">A ficha deste campeão é de baixa confiança: leia os números com cuidado.</p>}
      <div className="site-filters">
        <Select label="Tipo" value={f.kind} onChange={(v) => setF({ ...f, kind: v })} options={kinds} render={(k) => KIND_LABEL[k]} />
        <Select label="Arquétipo" value={f.archetype} onChange={(v) => setF({ ...f, archetype: v })} options={archetypes} render={archName} />
      </div>
      <div className="site-list">
        {builds.map((b) => <BuildCard key={b.key} b={b} grades={grades} />)}
        {builds.length === 0 && <p className="muted">Nenhuma build com esses filtros.</p>}
      </div>
      {c.matchups?.length > 0 && <Matchups list={c.matchups} />}
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
      {c.notes?.length > 0 && (
        <details className="site-section">
          <summary>Notas da Descoberta</summary>
          <ul className="small">{c.notes.map((n, i) => <li key={i}>{n}</li>)}</ul>
        </details>
      )}
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
