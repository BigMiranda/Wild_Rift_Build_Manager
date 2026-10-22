import { t } from '../i18n.js';
import { n0 } from '../format.js';
import ItemIcon from './ItemIcon.jsx';

/**
 * The visual part of "seen under effects": how much effective health each applied effect takes from the build (the
 * build under all the effects but that one, minus under all of them) and how much each item of the build holds under
 * them (with the item minus without it). `metric` picks the effective health: physical | magic | mixed.
 */
export const METRICS = ['mixed', 'physical', 'magic'];

/** Effective health for a metric: of a SeenBy, or the worth of an effect / item ({ physical, magic, mixed }). */
export const ehp = (s, metric) => (s == null ? null
  : s.effectiveHpMixed === undefined ? s[metric]
    : metric === 'physical' ? s.effectiveHpPhysical : metric === 'magic' ? s.effectiveHpMagic : s.effectiveHpMixed);

const pct = (v, of) => (of > 0 ? `${Math.round((v / of) * 100)}%` : '—');

/** Horizontal bars: label, bar (share of the largest), value. */
export function Bars({ rows, tone = 'gold', unit }) {
  const max = Math.max(1e-9, ...rows.map((r) => Math.abs(r.value)));
  return (
    <ul className="vbars">
      {rows.map((r) => (
        <li key={r.key ?? r.label}>
          <span className="vbars-label" title={r.label}>{r.icon}{r.label}</span>
          <span className="vbars-track">
            <span className={`vbars-fill ${r.value < 0 ? 'neg' : tone}`} style={{ width: `${(Math.abs(r.value) / max) * 100}%` }} />
          </span>
          <span className="vbars-value">{r.text ?? `${r.value > 0 ? '+' : ''}${n0(r.value)}${unit ?? ''}`}</span>
        </li>
      ))}
    </ul>
  );
}

/** Which effective health ranks the builds, and the physical part of the mixed one. */
export function MetricControls({ metric, onMetric, share, onShare }) {
  return (
    <div className="row">
      <label className="inline">{t('rank.metric')}
        <select value={metric} onChange={(e) => onMetric(e.target.value)}>
          {METRICS.map((m) => <option key={m} value={m}>{t(`fx.metric.${m}`)}</option>)}
        </select>
      </label>
      {metric === 'mixed' && (
        <label className="inline" title={t('rank.shareTitle')}>{t('rank.share', { p: share })}
          <input type="range" min="0" max="100" step="5" value={share} onChange={(e) => onShare(Number(e.target.value))} />
        </label>
      )}
    </div>
  );
}

export default function SeenByReport({ result, metric, idOf }) {
  if (!result) return null;
  const now = result.now;
  const plain = ehp(result.plain, metric);
  const total = ehp(now, metric);
  const contributions = (result.contributions ?? []).map((c) => ({ ...c, v: ehp(c, metric) }))
    .filter((c) => Math.abs(c.v) > 0.5).sort((a, b) => b.v - a.v);
  const items = (result.items ?? []).map((i) => ({ ...i, v: ehp(i, metric) })).sort((a, b) => b.v - a.v);
  return (
    <div className="seen-report">
      {plain != null && (
        <div className="seen-total">
          <span>{t(`fx.metric.${metric}`)}</span>
          <strong>{n0(plain)} → {n0(total)}</strong>
          <span className={total < plain ? 'warn' : ''}>{plain > 0 ? `${total >= plain ? '+' : '−'}${pct(Math.abs(plain - total), plain)}` : ''}</span>
        </div>
      )}
      {contributions.length > 0 && (
        <>
          <h5>{t('fx.report.effects')}</h5>
          <Bars tone="warn" rows={contributions.map((c) => ({
            key: c.source,
            label: c.source,
            icon: idOf?.get(c.source) != null ? <ItemIcon id={idOf.get(c.source)} size={18} /> : null,
            value: c.v,
            text: `−${n0(c.v)} (${pct(c.v, plain)})`,
          }))} />
        </>
      )}
      {items.length > 0 && (
        <>
          <h5>{t('fx.report.items')}</h5>
          <Bars rows={items.map((i) => ({
            key: i.itemId,
            label: i.item,
            icon: <ItemIcon id={i.itemId} size={18} />,
            value: i.v,
            text: `${i.v >= 0 ? '+' : '−'}${n0(Math.abs(i.v))} (${pct(Math.abs(i.v), total)})`,
          }))} />
        </>
      )}
    </div>
  );
}
