import { statLabel, t } from '../i18n.js';
import { PERCENT_STATS, SHEET_STATS, mmss, n0, n1, pct, statValue } from '../format.js';
import ItemIcon from './ItemIcon.jsx';
import InventorySlots from './InventorySlots.jsx';
import { StatIcon, statColor } from './StatIcon.jsx';

/** Series point at (or right before) a minute. */
export function pointAt(series, minute) {
  if (!series?.length) return null;
  let best = series[0];
  for (const p of series) {
    if (p.minute <= minute + 1e-9) best = p;
  }
  return best;
}

/** Champion stats at a moment, like the in-game stats tab: total (base + bonus). */
export function StatSheet({ point, itemsById }) {
  if (!point) return null;
  return (
    <section className="panel">
      <h3>{t('sheet.title', { time: mmss(point.minute), level: point.level })}</h3>
      <p className="muted" style={{ marginTop: 0 }}>
        {t('sheet.help')} {point.forgePct > 0 && t('sheet.forge', { pct: pct(point.forgePct * 100) })}
      </p>
      <div className="sheet-inventory">
        <span className="muted">{t('sheet.inventory')}</span>
        <InventorySlots ids={(point.contributions ?? []).map((c) => c.itemId)} itemsById={itemsById} size={30} />
      </div>
      <div className="stat-sheet">
        {SHEET_STATS.map((s) => {
          const total = point.total[s] ?? 0;
          const base = point.base[s];
          const bonus = base != null ? total - base : total;
          const showSplit = base != null && !PERCENT_STATS.has(s);
          return (
            <div key={s} className="sheet-row">
              <span className="sheet-label" style={{ color: statColor(s) }}>
                <StatIcon stat={s} />{statLabel(s)}
              </span>
              <span className="sheet-value">
                {statValue(s, total)}
                {showSplit && Math.abs(bonus) > 0.05 && (
                  <small> ({s.includes('Regen') ? n1(base) : n0(base)}<span className="bonus">+{s.includes('Regen') ? n1(bonus) : n0(bonus)}</span>)</small>
                )}
              </span>
            </div>
          );
        })}
      </div>
    </section>
  );
}

/**
 * How much each item (and each of its passives) adds to the build at a moment, in gold value, plus Ornn's Living
 * Forge on top. Share = part of the build's whole bonus value.
 */
export function RelevanceReport({ point, prices, itemsById }) {
  if (!point) return null;
  const forgeGold = Object.entries(point.forge ?? {}).reduce((sum, [k, v]) => sum + v * (prices[k] ?? 0), 0);
  const rows = (point.contributions ?? []).map((c) => ({ ...c, gold: c.flatGold + c.passiveGold }));
  const grand = rows.reduce((s, r) => s + r.gold, 0) + forgeGold;
  rows.sort((a, b) => b.gold - a.gold);
  const share = (g) => (grand > 0 ? (g / grand) * 100 : 0);

  return (
    <section className="panel">
      <h3>{t('report.title', { time: mmss(point.minute) })}</h3>
      <p className="muted" style={{ marginTop: 0 }}>{t('report.help')}</p>
      {rows.length === 0 && <p className="muted">{t('report.empty')}</p>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table className="data report">
            <thead>
              <tr>
                <th className="l">{t('report.item')}</th>
                <th className="l">{t('report.stats')}</th>
                <th>{t('report.flat')}</th>
                <th>{t('report.passive')}</th>
                <th>{t('report.total')}</th>
                <th className="l share-col">{t('report.share')}</th>
                <th>{t('report.eff')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const cost = itemsById.get(r.itemId)?.cost;
                return [
                  <tr key={`i${r.purchaseIndex}`} className="report-item">
                    <td className="l">
                      <span className="with-icon">
                        <ItemIcon id={r.itemId} size={24} />#{r.purchaseIndex + 1} {r.itemName}
                        {!r.conditionalIncluded && r.passiveParts.length === 0 && itemsById.get(r.itemId)?.stats.some((s) => s.conditional)
                          && <small className="cond">{t('report.condOff')}</small>}
                      </span>
                    </td>
                    <td className="l"><StatList stats={r.flat} /></td>
                    <td>{n0(r.flatGold)}</td>
                    <td>{r.passiveGold ? n0(r.passiveGold) : '—'}</td>
                    <td><strong>{n0(r.gold)}</strong></td>
                    <td className="l share-col"><ShareBar value={share(r.gold)} /></td>
                    <td>{cost ? pct((r.gold / cost) * 100) : '—'}</td>
                  </tr>,
                  ...r.passiveParts.map((p, i) => (
                    <tr key={`p${r.purchaseIndex}-${i}`} className="report-passive">
                      <td className="l">
                        <span className="passive-name">↳ {p.passive}</span>
                        {p.conditional && <small className="cond"> · {t('shop.conditional')}</small>}
                      </td>
                      <td className="l"><StatList stats={{ [p.stat]: p.amount }} /></td>
                      <td />
                      <td>{n0(p.gold)}</td>
                      <td />
                      <td className="l share-col"><ShareBar value={share(p.gold)} thin /></td>
                      <td />
                    </tr>
                  )),
                ];
              })}
              {forgeGold > 0 && (
                <tr className="report-item">
                  <td className="l"><span className="passive-name">{t('report.forge')} · {pct(point.forgePct * 100)}</span></td>
                  <td className="l"><StatList stats={point.forge} /></td>
                  <td />
                  <td>{n0(forgeGold)}</td>
                  <td><strong>{n0(forgeGold)}</strong></td>
                  <td className="l share-col"><ShareBar value={share(forgeGold)} /></td>
                  <td />
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function StatList({ stats }) {
  const entries = Object.entries(stats ?? {}).filter(([, v]) => Math.abs(v) > 0.05);
  if (entries.length === 0) return <span className="muted">—</span>;
  return (
    <span className="stat-inline">
      {entries.map(([k, v]) => (
        <span key={k} style={{ color: statColor(k) }} title={statLabel(k)}>
          <StatIcon stat={k} />+{statValue(k, v)}
        </span>
      ))}
    </span>
  );
}

function ShareBar({ value, thin = false }) {
  return (
    <span className="share">
      <span className={`share-bar${thin ? ' thin' : ''}`} style={{ width: `${Math.min(100, value)}%` }} />
      <span className="share-text">{n1(value)}%</span>
    </span>
  );
}
