import { Fragment, useState } from 'react';
import { statAbbr, statLabel, t, violationText } from '../i18n.js';
import { TABLE_STATS, damageReduction, mmss, n0, n1, pct } from '../format.js';
import ItemIcon from './ItemIcon.jsx';
import InventorySlots from './InventorySlots.jsx';
import { StatIcon, statColor } from './StatIcon.jsx';

/** Header cell with an explanation on hover (and for screen readers). */
function Th({ label, title, className = '' }) {
  return (
    <th className={className} title={title} scope="col">
      {label}
      {title && <span className="sr-only"> — {title}</span>}
    </th>
  );
}

/** Timeline of one build. `compact` hides some columns for side-by-side comparison. */
export default function TimelineTable({ result, itemsById, compact = false }) {
  const [open, setOpen] = useState(null);
  // Assumed purchases are listed right above the build purchase they lead to; each group can be collapsed.
  const [collapsed, setCollapsed] = useState(() => new Set());
  if (!result || result.steps.length === 0) return <p className="muted">{t('timeline.empty')}</p>;

  const stats = compact ? TABLE_STATS.slice(0, 4) : TABLE_STATS;
  const purchaseCols = compact ? 7 : 8;
  const effCols = compact ? 1 : 3;
  const cols = purchaseCols + stats.length + effCols;

  const groups = [];
  const byBuild = new Map();
  for (const s of result.steps) {
    let g = byBuild.get(s.buildIndex);
    if (!g) {
      g = { buildIndex: s.buildIndex, official: null, implied: [] };
      byBuild.set(s.buildIndex, g);
      groups.push(g);
    }
    if (s.implied) g.implied.push(s); else g.official = s;
  }
  const withImplied = groups.filter((g) => g.implied.length > 0).map((g) => g.buildIndex);
  const allCollapsed = withImplied.length > 0 && withImplied.every((b) => collapsed.has(b));
  const toggleGroup = (b) => setCollapsed((c) => {
    const next = new Set(c);
    if (next.has(b)) next.delete(b); else next.add(b);
    return next;
  });
  const rows = [];
  const moments = [...(result.moments ?? [])];
  for (const g of groups) {
    while (moments.length && moments[0].position < g.buildIndex) rows.push({ moment: moments.shift() });
    // Chronological: the assumed components first, then the item they build into (which holds the collapse toggle).
    const hidden = collapsed.has(g.buildIndex);
    if (!hidden || !g.official) g.implied.forEach((s) => rows.push({ step: s }));
    if (g.official) rows.push({ step: g.official, children: g.implied.length, hidden });
  }
  moments.forEach((m) => rows.push({ moment: m }));

  return (
    <div className="table-wrap">
      {withImplied.length > 0 && (
        <div className="timeline-tools">
          <button className="link" onClick={() => setCollapsed(allCollapsed ? new Set() : new Set(withImplied))}>
            {allCollapsed ? t('timeline.expandImplied') : t('timeline.collapseImplied')}
          </button>
        </div>
      )}
      <table className="data timeline">
        <thead>
          <tr className="group-row">
            <th colSpan={purchaseCols} className="l">{t('timeline.group.purchase')}</th>
            <th colSpan={stats.length} className="group-stats">{t('timeline.group.stats')}</th>
            <th colSpan={effCols} className="group-eff">{t('timeline.group.eff')}</th>
          </tr>
          <tr>
            <Th label={t('col.n')} />
            <Th label={t('col.item')} className="l" />
            <Th label={t('col.paid')} title={t('col.paidTitle')} />
            <Th label={t('col.cum')} title={t('col.cumTitle')} />
            <Th label={t('col.time')} title={t('col.timeTitle')} />
            <Th label={t('col.bag')} title={t('col.bagTitle')} />
            <Th label={t('col.level')} title={t('col.levelTitle')} />
            {!compact && <Th label={t('col.inventory')} title={t('col.inventoryTitle')} className="l" />}
            {stats.map((s, i) => (
              <th key={s} title={statLabel(s)} scope="col" className={i === 0 ? 'group-start' : ''} style={{ color: statColor(s) }}>
                <StatIcon stat={s} />{statAbbr(s)}
              </th>
            ))}
            {!compact && <Th label={t('col.static')} title={t('col.staticTitle')} className="group-start" />}
            <Th label={t('col.dynamic')} title={t('col.dynamicTitle')} className={compact ? 'group-start' : ''} />
            {!compact && <Th label={t('col.marginal')} title={t('col.marginalTitle')} />}
          </tr>
        </thead>
        <tbody>
          {rows.map(({ step: s, moment, children, hidden }) => {
            if (moment) {
              return (
                <tr key={`m${moment.position}`} className="moment-row">
                  <td colSpan={cols}>
                    <span className="moment-line">{t('moment.row', { time: mmss(moment.minute), gold: n0(moment.gold) })}</span>
                  </td>
                </tr>
              );
            }
            const e = s.efficiency;
            const up = e.dynamicPct != null && e.staticPct != null && e.dynamicPct - e.staticPct > 0.05;
            return (
              <Fragment key={s.index}>
                <tr className={`clickable${s.violations?.length ? ' violating' : ''}${s.implied ? ' implied' : ''}`} onClick={() => setOpen(open === s.index ? null : s.index)} aria-expanded={open === s.index}>
                  <td title={s.implied ? t('timeline.impliedTitle') : undefined}>
                    {s.implied ? '↴' : s.number}
                    {children > 0 && (
                      <button
                        className="icon group-toggle" aria-expanded={!hidden}
                        title={t(hidden ? 'timeline.expandGroup' : 'timeline.collapseGroup', { n: children })}
                        onClick={(ev) => { ev.stopPropagation(); toggleGroup(s.buildIndex); }}
                      >
                        {hidden ? `+${children}` : '−'}
                      </button>
                    )}
                  </td>
                  <td className="l">
                    <span className="with-icon">
                      <ItemIcon id={s.itemId} size={22} />
                      {s.itemName}
                      {s.implied && <small className="implied-tag">{t('timeline.implied')}</small>}
                      {s.warnings.length > 0 && <span title={s.warnings.join('\n')}>⚠</span>}
                      {s.violations?.length > 0 && (
                        <span className="violation" title={`${t('rule.title')}: ${s.violations.map(violationText).join(' · ')}`}>⛔</span>
                      )}
                    </span>
                  </td>
                  <td>{n0(s.paidCost)}</td>
                  <td>{n0(s.cumulativeGold)}</td>
                  <td>{mmss(s.minute)}</td>
                  <td className="bag">{n0(s.goldLeft)}</td>
                  <td>{s.level}</td>
                  {!compact && <td className="l"><InventorySlots ids={s.inventoryIds ?? []} itemsById={itemsById} /></td>}
                  {stats.map((st, i) => {
                    const v = s.stats.total[st];
                    const dr = st === 'Armor' || st === 'Magic Resistance' ? damageReduction(v ?? 0) : null;
                    return (
                      <td key={st} className={i === 0 ? 'group-start' : ''} style={{ color: statColor(st) }}
                        title={dr != null ? t('dr.title', { stat: statLabel(st), res: n0(v), pct: pct(dr) }) : undefined}>
                        {st === 'Health Regen' ? n1(v) : n0(v)}
                        {dr != null && <small className="dr">{pct(dr)}</small>}
                      </td>
                    );
                  })}
                  {!compact && <td className="group-start">{pct(e.staticPct)}</td>}
                  <td className={`${up ? 'eff-up' : ''} ${compact ? 'group-start' : ''}`}>{pct(e.dynamicPct)}</td>
                  {!compact && <td>{pct(e.marginalPct)}</td>}
                </tr>
                {open === s.index && (
                  <tr className="detail">
                    <td colSpan={cols}>
                      <StepDetail step={s} />
                    </td>
                  </tr>
                )}
              </Fragment>
            );
          })}
        </tbody>
      </table>
      <small>{t('timeline.clickHint')}</small>
    </div>
  );
}

function StepDetail({ step }) {
  const st = step.stats;
  const e = step.efficiency;
  return (
    <div className="detail-grid">
      <div>
        <h4>{t('detail.purchase')}</h4>
        <div>{t('detail.itemCost', { cost: n0(step.itemCost), paid: n0(step.paidCost) })}</div>
        {step.consumedComponents.length > 0 && <div>{t('detail.components', { list: step.consumedComponents.join(', ') })}</div>}
        <div>{t('detail.when', { time: mmss(step.minute), xp: n0(step.xp), level: step.level })}</div>
        <div>{t('detail.inventory', { list: step.inventory.join(', ') })}</div>
        {step.warnings.length > 0 && (
          <ul className="error">
            {step.warnings.map((w) => <li key={w}>{w}</li>)}
          </ul>
        )}
        {step.violations?.length > 0 && (
          <>
            <h4 className="violation">⛔ {t('rule.title')}</h4>
            <ul className="violation">
              {step.violations.map((v, i) => <li key={i}>{violationText(v)}</li>)}
            </ul>
          </>
        )}
      </div>
      <div>
        <h4>{t('detail.composition')}</h4>
        <table className="data">
          <thead>
            <tr>
              <th className="l">{t('detail.stat')}</th><th>{t('detail.base')}</th><th>{t('detail.items')}</th>
              <th>{t('detail.passives')}</th><th>{t('detail.forge')}</th><th>{t('detail.total')}</th>
            </tr>
          </thead>
          <tbody>
            {TABLE_STATS.map((k) => (
              <tr key={k} style={{ color: statColor(k) }}>
                <td className="l"><StatIcon stat={k} />{statLabel(k)}</td>
                <td>{n1(st.base[k])}</td>
                <td>
                  {k === 'Health Regen'
                    ? (st.itemFlat['% Health Regen'] ? t('detail.ofBase', { n: n0(st.itemFlat['% Health Regen']) }) : '—')
                    : n1(st.itemFlat[k])}
                </td>
                <td>{n1(st.itemPassives[k])}</td>
                <td>{n1(st.forge[k])}</td>
                <td><strong>{n1(st.total[k])}</strong></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div>
        <h4>{t('detail.pctPassives')}</h4>
        {st.passives.length === 0 && <div className="muted">{t('detail.none')}</div>}
        <ul>
          {st.passives.map((p, i) => (
            <li key={i} className={p.purchaseIndex === step.index ? 'eff-up' : ''}>
              <StatIcon stat={p.stat} />#{p.number}{p.implied ? '↳' : ''} {p.itemName} — {p.passive}
              {p.refScope === 'BONUS' && <small> {t('detail.bonusOnly')}</small>}
              <div className="formula">{p.formula}</div>
            </li>
          ))}
        </ul>
        <h4>{t('detail.livingForge', { pct: pct(st.forgePct * 100) })}</h4>
        {st.forgeDetails.length === 0 ? (
          <div className="muted">{t('detail.noForge')}</div>
        ) : (
          <ul>
            {st.forgeDetails.map((f) => (
              <li key={f.stat} className="formula" style={{ color: statColor(f.stat) }}>
                <StatIcon stat={f.stat} />
                {t('detail.forgeLine', { stat: statLabel(f.stat), bonus: n1(f.bonus), pct: pct(f.pct * 100), value: n1(f.value) })}
              </li>
            ))}
          </ul>
        )}
      </div>
      <div>
        <h4>{t('detail.efficiency')}</h4>
        <div>{t('detail.static')}: <strong>{pct(e.staticPct)}</strong></div>
        <div className="formula">{e.staticFormula}</div>
        <div style={{ marginTop: 6 }}>{t('detail.dynamic')}: <strong>{pct(e.dynamicPct)}</strong></div>
        <div className="formula">{e.dynamicFormula}</div>
        <div style={{ marginTop: 6 }}>{t('detail.marginal')}: <strong>{pct(e.marginalPct)}</strong></div>
        <div className="formula">{e.marginalFormula}</div>
      </div>
    </div>
  );
}
