import { useEffect, useState } from 'react';
import ItemIcon from '../components/ItemIcon.jsx';
import { statLabel } from '../i18n.js';
import { mmss, n0, n1 } from '../format.js';
import { compareRows, KIND_LABEL } from './data.js';
import { siteHref, useSite } from './StaticApp.jsx';

const FORMAT = { n0, n1, mmss };

/**
 * Comparar: duas ou três builds pré-calculadas lado a lado. Cada número é o do último item de cada uma; nada é
 * recalculado. No celular cada linha é um cartão com os valores em colunas estreitas.
 */
export default function ComparePage({ keys }) {
  const { store, compare, setCompare } = useSite();
  const chosen = keys.length ? keys : compare;
  const [builds, setBuilds] = useState(null);
  const [error, setError] = useState(null);
  // a link with the builds ("#/comparar?k=a,b") becomes the visitor's choice too
  useEffect(() => {
    if (keys.length && keys.join(',') !== compare.join(',')) setCompare(keys.slice(0, 3));
  }, [keys.join(',')]);
  const drop = (key) => {
    const rest = chosen.filter((k) => k !== key);
    setCompare(rest);
    window.location.hash = siteHref('comparar', null, { k: rest.join(',') });
  };
  useEffect(() => {
    let live = true;
    if (!chosen.length) {
      setBuilds([]);
      return undefined;
    }
    setBuilds(null);
    store.builds(chosen).then((x) => { if (live) setBuilds(x); }).catch((e) => { if (live) setError(e.message); });
    return () => { live = false; };
  }, [store, chosen.join(',')]);
  if (error) return <p className="error">{error}</p>;
  if (!builds) return <p className="muted">Carregando…</p>;
  if (builds.length < 2) {
    return (
      <section>
        <h1 className="site-title">Comparar</h1>
        <p className="muted">Escolha duas ou três builds com o botão “Comparar” nas páginas dos campeões ou das builds.</p>
        {builds.length === 1 && <p>Escolhida: <a href={siteHref('b', builds[0].key)}>{builds[0].champion} · {builds[0].label}</a></p>}
      </section>
    );
  }
  const rows = compareRows(builds);
  const cols = { gridTemplateColumns: `minmax(0, 1.3fr) repeat(${builds.length}, minmax(0, 1fr))` };
  return (
    <section>
      <h1 className="site-title">Comparar</h1>
      <div className="cmp-heads" style={cols}>
        <span />
        {builds.map((b, i) => (
          <div key={b.key} className={`cmp-head c${i}`}>
            <a href={siteHref('b', b.key)}><strong>{b.label}</strong></a>
            <small className="muted">{b.champion} · {KIND_LABEL[b.kind]}</small>
            <button type="button" className="link" onClick={() => drop(b.key)}>tirar</button>
          </div>
        ))}
      </div>
      <div className="cmp-items" style={cols}>
        <span className="muted small">Itens</span>
        {builds.map((b) => (
          <div key={b.key} className="item-row">
            {(b.timelines?.padrao?.steps ?? []).filter((s) => !s.implied).map((s, i) => <ItemIcon key={i} id={s.itemId} size={22} />)}
          </div>
        ))}
      </div>
      <ul className="cmp-rows">
        {rows.map((r) => (
          <li key={r.label} style={cols}>
            <span className="cmp-label">{r.stat ? statLabel(r.label) : r.label}</span>
            {r.values.map((v, i) => (
              <span key={i} className={v != null && v === r.best ? 'best' : ''}>{v == null ? '—' : FORMAT[r.format](v)}</span>
            ))}
          </li>
        ))}
      </ul>
      <p className="muted small">Destacado: o melhor de cada linha. A efetividade e a ficha são as do último item de cada build.</p>
    </section>
  );
}
