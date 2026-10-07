import { statAbbr, statLabel, t } from '../i18n.js';
import { n0, n1, pct, statValue } from '../format.js';
import { FORGE_COLOR, UNKNOWN_PASSIVE_GOLD, itemColor, itemPassiveColor } from '../effective.js';
import ItemIcon from './ItemIcon.jsx';
import { statColor } from './StatIcon.jsx';

const signed = (v) => (v > 0 ? `+${n0(v)}` : n0(v));
const signedPct = (v) => (v == null ? '' : `${v > 0 ? '+' : ''}${pct(v)}`);

/** Profit in gold and %, green when the item is worth more than it cost. */
function Profit({ value, pctValue }) {
  return (
    <span className={value >= 0 ? 'profit-up' : 'profit-down'}>
      {signed(value)}{pctValue != null && ` (${signedPct(pctValue)})`}
    </span>
  );
}

/**
 * Effective gold of the items of one or more builds, side by side (one column per build, the same item on the same
 * line): what each stat and passive of each item is worth, gold spent vs gold worth, and profit per item.
 * columns: [{ key, name, color, eff: effectiveGold(...) }]
 */
export default function EffectiveBreakdown({ columns, compact = false }) {
  const label = compact ? statAbbr : statLabel;
  // Rows: same item name on the same row (2nd copy of an item -> its own row).
  const rows = new Map();
  columns.forEach((c, ci) => {
    const count = new Map();
    for (const x of c.eff.items) {
      const n = (count.get(x.itemName) ?? 0) + 1;
      count.set(x.itemName, n);
      const key = `${x.itemName}#${n}`;
      if (!rows.has(key)) rows.set(key, columns.map(() => null));
      rows.get(key)[ci] = x;
    }
  });
  const hasForge = columns.some((c) => c.eff.forge.gold > 0);
  const template = `repeat(${columns.length}, ${compact ? 'minmax(190px, 290px)' : 'minmax(0, 1fr)'})`;

  return (
    <div className={`eff-grid${compact ? ' compact' : ''}`} style={{ gridTemplateColumns: template }}>
      {columns.map((c) => (
        <div key={`h${c.key}`} className="eff-head">
          <div className="eff-build"><span className="swatch" style={{ background: c.color }} />{c.name}</div>
          <div>{t('eff.spentVsWorth', { spent: n0(c.eff.spent), worth: n0(c.eff.effective) })}</div>
          <div>{t('eff.profit')}: <Profit value={c.eff.profit} pctValue={c.eff.profitPct} /></div>
        </div>
      ))}
      {[...rows.entries()].map(([key, cells]) => cells.map((x, ci) => (
        <div key={`${key}|${columns[ci].key}`} className={`eff-cell${x ? '' : ' missing'}`} style={x ? { '--item-color': itemColor(x.itemName) } : undefined}>
          {x ? (
            <>
              <div className="eff-item">
                <span className="eff-swatch" aria-hidden="true">
                  <span style={{ background: itemPassiveColor(x.itemName) }} />
                  <span style={{ background: itemColor(x.itemName) }} />
                </span>
                {!compact && <ItemIcon id={x.itemId} size={20} />}
                <span className="eff-item-name">#{x.number}{x.implied ? '↳' : ''} {x.itemName}</span>
              </div>
              <div className="eff-line">
                {t('eff.worthOfCost', { worth: n0(x.effective), cost: n0(x.cost) })} · <Profit value={x.profit} pctValue={x.profitPct} />
              </div>
              <div className="eff-parts">
                {x.stats.map((s) => (
                  <span key={s.stat} style={{ color: statColor(s.stat) }}>
                    {label(s.stat)} +{statValue(s.stat, s.amount)} = {n0(s.gold)}
                  </span>
                ))}
                {x.passives.map((p) => (
                  <span key={p.name} className={p.known ? 'eff-passive' : 'eff-passive unknown'}
                    title={p.known ? undefined : t('eff.unknownTitle', { gold: n0(UNKNOWN_PASSIVE_GOLD) })}>
                    ✦ {p.name}
                    {p.known
                      ? `: ${p.parts.map((q) => `${label(q.stat)} +${n1(q.amount)}`).join(', ')} = ${n0(p.gold)}`
                      : ` ≈ ${n0(p.gold)}*`}
                  </span>
                ))}
              </div>
            </>
          ) : '—'}
        </div>
      )))}
      {hasForge && columns.map((c) => (
        <div key={`f${c.key}`} className="eff-cell eff-forge" style={{ '--item-color': FORGE_COLOR }}>
          <div className="eff-item">
            <span className="eff-swatch" aria-hidden="true"><span style={{ background: FORGE_COLOR }} /></span>
            <span className="eff-item-name">{t('eff.forge', { pct: pct(c.eff.forge.pct * 100) })}</span>
          </div>
          <div className="eff-line">{n0(c.eff.forge.gold)}</div>
          <div className="eff-parts">
            {c.eff.forge.stats.map((s) => (
              <span key={s.stat} style={{ color: statColor(s.stat) }}>{label(s.stat)} +{n1(s.amount)} = {n0(s.gold)}</span>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
