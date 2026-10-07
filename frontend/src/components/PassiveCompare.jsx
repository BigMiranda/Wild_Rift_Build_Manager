import { t } from '../i18n.js';
import { mmss, n0 } from '../format.js';
import { passiveColor, passiveRows } from '../passives.js';
import ItemIcon from './ItemIcon.jsx';

/**
 * Passives of several builds side by side: one column per build, one row per passive, so the same passive sits on
 * the same line in every build ("—" where a build does not have it).
 * columns: [{ key, name, color, list: acquiredPassives(...), since?: Map(passive key -> minute) }]
 */
export default function PassiveCompare({ columns, compact = false }) {
  const rows = passiveRows(columns);
  return (
    <div className={`passive-compare${compact ? ' compact' : ''}`} style={{ gridTemplateColumns: `repeat(${columns.length}, ${compact ? 'minmax(150px, 230px)' : 'minmax(0, 1fr)'})` }}>
      {columns.map((c) => (
        <div key={`h${c.key}`} className="pc-head">
          <span className="swatch" style={{ background: c.color }} />
          <span className="pc-name" title={c.name}>{c.name}</span>
          <small className="muted">{t('passives.total', { n: c.list.length, gold: n0(c.list.reduce((a, x) => a + x.gold, 0)) })}</small>
        </div>
      ))}
      {rows.map((r) => r.cells.map((x, ci) => (
        <div key={`${r.key}|${columns[ci].key}`} className={`pc-cell${x ? '' : ' missing'}`}>
          {x ? (
            <>
              <span className="passive-block" style={{ background: passiveColor(x.key) }} aria-hidden="true" />
              {!compact && <ItemIcon id={x.itemId} size={18} />}
              <span className="pc-text">
                <span className="passive-name">{x.name}</span>
                <small className="muted"> · {x.itemName}{columns[ci].since?.has(x.key) ? ` · ${t('passives.since', { time: mmss(columns[ci].since.get(x.key)) })}` : ''}</small>
              </span>
            </>
          ) : '—'}
        </div>
      )))}
    </div>
  );
}
