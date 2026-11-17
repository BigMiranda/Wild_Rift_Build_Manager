import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { statLabel, t } from '../i18n.js';
import { CHART_STATS, PERCENT_STATS, SERIES_COLORS, SLOW_STAT, abilityCdr, cooldownCut, damageCut, mmss, n0, n1, statOf } from '../format.js';
import { PASSIVES_VIEW, acquiredPassives, passiveColor, pointItemIds } from '../passives.js';
import ItemIcon from './ItemIcon.jsx';
import PassiveCompare from './PassiveCompare.jsx';
import EffectiveBreakdown, { AlignToggle } from './EffectiveBreakdown.jsx';
import PassiveValueBar from './PassiveValueBar.jsx';
import { ALIGN_SLOT, EFFECTIVE_VIEW, FORGE_COLOR, effectiveGold, itemColor, itemPassiveColor } from '../effective.js';
import { StatIcon, statColor } from './StatIcon.jsx';

const MARGIN = { top: 8, right: 16, bottom: 4, left: 8 };
const Y_AXIS_WIDTH = 56;

/** Minutes at which each level is reached for an XP/min rate (level 1 excluded). */
export function levelUps(xpTable, xpPerMin, untilMinute) {
  if (!(xpPerMin > 0)) return [];
  return Object.entries(xpTable)
    .map(([level, xp]) => ({ level: Number(level), minute: xp / xpPerMin }))
    .filter((l) => l.level > 1 && l.minute <= untilMinute)
    .sort((a, b) => a.level - b.level);
}

/**
 * One stat over time, one line per build (active build first, then compared builds in the order they were added, so
 * each build keeps its color). Below the plot, an event lane per build marks purchases (item icon) and level ups,
 * like the in-game gold graph. Clicking the plot or an event selects that moment.
 * The "passives" view shows the passives held instead: stacked bars, one block per passive (its gold estimate), one
 * stack per build.
 */
export default function StatChart({ series, stat, onStatChange, xpTable, selectedMinute, onSelectMinute, itemsById, align, onAlignChange, passiveValues }) {
  const lines = useMemo(() => series.filter((s) => s.result && s.result.series.length > 0), [series]);
  // the bar charts' legend is drawn here, under the event lanes (they stay lined up with the plot)
  const [legendHost, setLegendHost] = useState(null);
  const maxMinute = Math.ceil(Math.max(1, ...lines.map((s) => s.result.series[s.result.series.length - 1].minute)));
  const wrapRef = useRef(null);
  const [width, setWidth] = useState(0);

  useEffect(() => {
    if (!wrapRef.current) return undefined;
    // the observer's first callback may be delayed; clientWidth is in layout pixels, like the observer's (not zoomed)
    setWidth(wrapRef.current.clientWidth);
    const ro = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    ro.observe(wrapRef.current);
    return () => ro.disconnect();
  }, []);

  // Same horizontal mapping as the plot area (left margin + Y axis, right margin).
  const plotLeft = MARGIN.left + Y_AXIS_WIDTH;
  const plotWidth = Math.max(1, width - plotLeft - MARGIN.right);
  const x = (minute) => plotLeft + (Math.min(minute, maxMinute) / maxMinute) * plotWidth;
  const extra = extraLines(lines, stat);
  const lineVs = (key) => (key.startsWith('v') ? null : (EXTRA_LINES[stat] ?? []).find((v) => key.startsWith(v.prefix)));
  const lineLabel = (key) => lineVs(key)?.label() ?? statLabel(stat);
  // In the Ability Haste chart every line also says how much less cooldown its haste is (1 - 1 / (1 + haste / 100)).
  const cdrNote = (v) => (stat === 'Ability Haste' ? ` · ${t('chart.cdrOf', { pct: n0(100 * (1 - 1 / (1 + Math.max(0, v) / 100))) })}` : '');

  return (
    <div>
      <div className="chips" role="group" aria-label={t('chart.statGroup')} style={{ marginBottom: 10 }}>
        {CHART_STATS.map((s) => (
          <button key={s} className={`stat-chip${s === stat ? ' active' : ''}`} aria-pressed={s === stat}
            onClick={() => onStatChange(s)} style={s === stat ? { borderColor: statColor(s) } : undefined}>
            <StatIcon stat={s} /><span style={{ color: statColor(s) }}>{statLabel(s)}</span>
          </button>
        ))}
        <button className={`stat-chip${stat === PASSIVES_VIEW ? ' active' : ''}`} aria-pressed={stat === PASSIVES_VIEW}
          onClick={() => onStatChange(PASSIVES_VIEW)}>
          <span className="passive-chip-icon" aria-hidden="true">▤</span><span>{t('chart.passives')}</span>
        </button>
        <button className={`stat-chip${stat === EFFECTIVE_VIEW ? ' active' : ''}`} aria-pressed={stat === EFFECTIVE_VIEW}
          onClick={() => onStatChange(EFFECTIVE_VIEW)}>
          <span className="passive-chip-icon" aria-hidden="true">◧</span><span>{t('chart.effective')}</span>
        </button>
      </div>
      <div ref={wrapRef}>
        {stat === EFFECTIVE_VIEW ? (
          <>
            <AlignToggle value={align} onChange={onAlignChange} />
            <EffectiveBars
              lines={lines} itemsById={itemsById} maxMinute={maxMinute} plotWidth={plotWidth} align={align}
              selectedMinute={selectedMinute} onSelectMinute={onSelectMinute} overlayAt={passiveValues?.overlayAt}
              legendHost={legendHost}
            />
            {passiveValues && <PassiveValueBar pv={passiveValues} />}
          </>
        ) : stat === PASSIVES_VIEW ? (
          <PassiveBars
            lines={lines} itemsById={itemsById} maxMinute={maxMinute} plotWidth={plotWidth}
            selectedMinute={selectedMinute} onSelectMinute={onSelectMinute} legendHost={legendHost}
          />
        ) : (
        <div style={{ width: '100%', height: 300 }} role="img" aria-label={t('chart.aria', { stat: statLabel(stat) })}>
          <ResponsiveContainer>
            <LineChart
              data={lineData(lines, stat)}
              margin={MARGIN}
              onClick={(e) => { if (e && e.activeLabel != null) onSelectMinute(Number(e.activeLabel)); }}
              style={{ cursor: 'crosshair' }}
            >
              <CartesianGrid stroke="var(--grid)" vertical={false} />
              <XAxis
                dataKey="minute" type="number" domain={[0, maxMinute]} allowDuplicatedCategory={false}
                tickFormatter={(v) => `${v}m`} stroke="var(--muted)" tick={{ fill: 'var(--text-2)', fontSize: 12 }}
              />
              <YAxis
                stroke="var(--muted)" tick={{ fill: statColor(stat), fontSize: 12 }} width={Y_AXIS_WIDTH}
                domain={PERCENT_STATS.has(stat) ? [0, 'auto'] : ['auto', 'auto']}
                tickFormatter={(v) => (PERCENT_STATS.has(stat) ? `${n0(v)}%` : n0(v))}
              />
              <Tooltip
                contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text)' }}
                labelFormatter={(v) => t('chart.minute', { time: mmss(v) })}
                formatter={(v, name, p) => [`${n1(v)}${PERCENT_STATS.has(stat) ? '%' : ''} ${lineLabel(p.dataKey)}${cdrNote(v)} · ${t('chart.lv', { n: p.payload[`lv${p.dataKey.slice(lineVs(p.dataKey)?.prefix.length ?? 1)}`] })}`, name]}
                cursor={{ stroke: 'var(--muted)', strokeDasharray: '3 3' }}
              />
              {lines.length + extra.length >= 2 && <Legend wrapperStyle={{ color: 'var(--text-2)' }} />}
              {selectedMinute != null && <ReferenceLine x={selectedMinute} stroke="var(--gold)" strokeWidth={1.5} />}
              {lines[0]?.result.steps.filter((s) => s.sold).map((s) => (
                <ReferenceLine key={`sale${s.index}`} x={s.minute} stroke="var(--stat-hp)" strokeDasharray="4 3"
                  label={{ value: `$ ${s.itemName}`, position: 'insideTopLeft', fill: 'var(--stat-hp)', fontSize: 11 }} />
              ))}
              {lines.map((s) => (
                <Line
                  key={s.key}
                  name={s.name}
                  dataKey={`v${s.key}`}
                  type="stepAfter"
                  stroke={SERIES_COLORS[s.colorIndex % SERIES_COLORS.length]}
                  strokeWidth={2}
                  dot={false}
                  activeDot={{ r: 5, stroke: 'var(--surface)', strokeWidth: 2 }}
                  isAnimationActive={false}
                />
              ))}
              {extra.map(({ s, vs }) => (
                <Line
                  key={`${vs.prefix}${s.key}`}
                  name={`${s.name} · ${vs.label()}`}
                  dataKey={`${vs.prefix}${s.key}`}
                  type="stepAfter"
                  stroke={SERIES_COLORS[s.colorIndex % SERIES_COLORS.length]}
                  strokeWidth={1.5}
                  strokeDasharray={vs.dash}
                  dot={false}
                  activeDot={{ r: 4, stroke: 'var(--surface)', strokeWidth: 2 }}
                  isAnimationActive={false}
                />
              ))}
            </LineChart>
          </ResponsiveContainer>
        </div>
        )}

        {width > 0 && lines.map((s) => (
          <EventLane
            key={s.key} build={s} x={x} plotLeft={plotLeft} plotWidth={plotWidth}
            levels={levelUps(xpTable, s.result.xpPerMin, maxMinute)} onSelectMinute={onSelectMinute}
            selectedX={selectedMinute != null ? x(selectedMinute) : null}
          />
        ))}
        {extra.length > 0 && EXTRA_HINT[stat] && <small className="muted chart-note">{t(EXTRA_HINT[stat])}<br /></small>}
        {stat === SLOW_STAT && <small className="muted chart-note">{t('chart.slowHint')}<br /></small>}
        <small className="muted">{t('chart.clickHint')}</small>
        {stat === SLOW_STAT && <SlowLegend lines={lines} selectedMinute={selectedMinute} />}
        {(stat === EFFECTIVE_VIEW || stat === PASSIVES_VIEW) && <div ref={setLegendHost} />}
      </div>
    </div>
  );
}

/**
 * Passives held over time as stacked bars on the same time axis as the stat chart (so the event lanes still line up):
 * a bar per series point and build, a block per passive, every block worth its gold estimate. A click marks the minute
 * (like the line charts); what the builds hold there is listed below the chart, so nothing follows the mouse.
 */
function PassiveBars({ lines, itemsById, maxMinute, plotWidth, selectedMinute, onSelectMinute, legendHost }) {
  const model = useMemo(() => {
    // Rows: every minute any build has a point; each build's state is its last point at or before that minute.
    const minutes = [...new Set(lines.flatMap((s) => s.result.series.map((p) => p.minute)))].sort((a, b) => a - b);
    const held = new Map();   // `${build}|${minute}` -> passives
    const order = new Map();  // build -> passive keys in order of acquisition
    for (const s of lines) {
      const keys = [];
      let k = 0;
      for (const m of minutes) {
        while (k + 1 < s.result.series.length && s.result.series[k + 1].minute <= m + 1e-9) k++;
        const p = s.result.series[k];
        const list = p.minute <= m + 1e-9 ? acquiredPassives(pointItemIds(p), itemsById) : [];
        held.set(`${s.key}|${m}`, list);
        for (const x of list) if (!keys.includes(x.key)) keys.push(x.key);
      }
      order.set(s.key, keys);
    }
    const data = minutes.map((m) => {
      const row = { minute: m };
      for (const s of lines) {
        for (const x of held.get(`${s.key}|${m}`)) row[`${s.key}|${x.key}`] = x.gold;
      }
      return row;
    });
    const names = new Map();
    for (const list of held.values()) for (const x of list) names.set(x.key, x);
    return { minutes, held, order, data, names };
  }, [lines, itemsById]);
  const { minutes, held, order, data, names } = model;
  const barSize = Math.max(2, Math.min(18, (plotWidth / Math.max(1, minutes.length) / lines.length) * 0.75));
  const label = markedMinute(minutes, selectedMinute);
  const columns = lines.map((s) => ({
    key: s.key, name: s.name, color: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length],
    list: held.get(`${s.key}|${label}`) ?? [],
  }));
  const chart = useMemo(() => (
    <BarChart
      data={data} margin={MARGIN} barGap={1} barSize={barSize}
      onClick={(e) => { if (e && e.activeLabel != null) onSelectMinute(Number(e.activeLabel)); }}
      style={{ cursor: 'crosshair' }}
    >
      <CartesianGrid stroke="var(--grid)" vertical={false} />
      <XAxis
        dataKey="minute" type="number" domain={[0, maxMinute]} padding={{ left: barSize, right: barSize }}
        tickFormatter={(v) => `${v}m`} stroke="var(--muted)" tick={{ fill: 'var(--text-2)', fontSize: 12 }}
      />
      <YAxis stroke="var(--muted)" tick={{ fill: 'var(--gold)', fontSize: 12 }} width={Y_AXIS_WIDTH} tickFormatter={(v) => n0(v)} />
      {selectedMinute != null && <ReferenceLine x={selectedMinute} stroke="var(--gold)" strokeWidth={1.5} />}
      {lines.flatMap((s) => order.get(s.key).map((key) => (
        <Bar
          key={`${s.key}|${key}`} dataKey={`${s.key}|${key}`} stackId={String(s.key)} name={names.get(key)?.name}
          fill={passiveColor(key)} stroke="var(--surface)" strokeWidth={1} isAnimationActive={false}
        />
      )))}
    </BarChart>
  ), [data, barSize, maxMinute, selectedMinute, lines, order, names, onSelectMinute]);

  return (
    <>
      <div style={{ width: '100%', height: 300 }} role="figure" aria-label={t('chart.passivesAria')}>
        <ResponsiveContainer>{chart}</ResponsiveContainer>
      </div>
      <ChartLegend label={label} host={legendHost}>
        <PassiveCompare compact={lines.length > 1} columns={columns} />
      </ChartLegend>
    </>
  );
}

/** The slow sources of each build at the marked minute (else the last), under the slow chart. */
function SlowLegend({ lines, selectedMinute }) {
  const cols = t('chart.slowCols').split('|');
  return lines.map((s) => {
    const series = s.result.series;
    let p = series[series.length - 1];
    if (selectedMinute != null) {
      p = series[0];
      for (const q of series) { if (q.minute <= selectedMinute + 1e-9) p = q; else break; }
    }
    const sources = p.slow?.sources ?? [];
    return (
      <div key={s.key} className="chart-legend">
        <strong style={{ color: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length] }}>{s.name}</strong>
        <small className="muted"> · {t('chart.minute', { time: mmss(p.minute) })} · {n1(p.slow?.total ?? 0)}%</small>
        {sources.length === 0 ? <div className="muted">{t('chart.slowNone')}</div> : (
          <table className="data">
            <thead><tr>{cols.map((c, i) => <th key={c} className={i === 0 ? 'l' : undefined}>{c}</th>)}</tr></thead>
            <tbody>
              {[...sources].sort((a, b) => b.value - a.value).map((x) => (
                <tr key={x.name}>
                  <td className="l">{x.name} <small className="muted">({t(`chart.slowKind.${x.kind}`)})</small></td>
                  <td>{n0(100 * x.slow)}%{x.share < 1 ? ` × ${n0(100 * x.share)}%` : ''}</td>
                  <td>{n1(x.duration)}s</td>
                  <td>{n1(x.interval)}s</td>
                  <td>{n1(x.targets)}</td>
                  <td>{n1(x.value)}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    );
  });
}

/** The chart's minute whose state the legend shows: the marked one (the last point at or before it), else the last. */
function markedMinute(minutes, selected) {
  if (!minutes.length) return null;
  if (selected == null) return minutes[minutes.length - 1];
  let at = minutes[0];
  for (const m of minutes) {
    if (m <= selected + 1e-9) at = m; else break;
  }
  return at;
}

/** The fixed legend under a bar chart (in `host`, below the event lanes): what the builds have at the marked minute. */
function ChartLegend({ label, host, children }) {
  if (label == null || !host) return null;
  return createPortal(
    <div className="chart-legend">
      <strong>{t('chart.minute', { time: mmss(label) })}</strong>
      <small className="muted"> · {t('chart.legendHint')}</small>
      {children}
    </div>,
    host,
  );
}

const has = (total, stat) => (total[stat] ?? 0) > 0.05;

/** Conditional parts of a stat at a point (the passives a build switched on), by passive: [{ passive, amount }]. */
const conditionalParts = (point, stat) => (point?.contributions ?? []).flatMap((c) => (c.passiveParts ?? [])
  .filter((p) => p.conditional && p.stat === stat && p.amount > 0));

/** Total move speed with the conditional % and flat parts scaled by `keep(part)` (1 = as is, 0 = off). */
function speedWith(total, point, keep) {
  const scaled = (stat) => conditionalParts(point, stat).reduce((s, p) => s - p.amount * (1 - keep(p)), total[stat] ?? 0);
  return scaled('Move Speed') * (1 + scaled('% Move Speed') / 100);
}

/**
 * Share of a fight Ímpeto Gradual's speed is on: 3s each time it is ready (21s at level 1 to 7s at 15, as in
 * combate_<patch>.yml), taken as soon as it is back.
 */
const momentumUptime = (level) => Math.min(1, 3 / (21 - (14 * (Math.max(1, Math.min(15, level)) - 1)) / 14));

/**
 * Lines of a chart's subtypes, dashed / dotted in the build's color, only for the builds that ever have that subtype:
 * - Shield: besides the shield against any damage, the shield against physical damage (shield + physical shield) and
 *   against magic damage (shield + magic shield);
 * - Physical / Magic Reduction: the damage cut with the % damage reductions of effects too (of all damage and of
 *   that type), and for physical damage against champions' attacks and critical strikes;
 * - Ability Haste (the cooldown chart): the haste of the ultimate and of the basic abilities (Ability Haste + Ultimate /
 *   Basic Ability Haste) with the % cooldown reductions in it (the haste that alone would cut as much), and an ability
 *   with a reduction of its own (Ornn's Investida on a pillar).
 */
const EXTRA_LINES = {
  'Physical Reduction': [
    { prefix: 'dr', dash: '6 3', label: () => t('chart.drAll'),
      has: (x) => has(x, '% Damage Reduction') || has(x, '% Physical Damage Reduction'), value: (x) => damageCut(x, 'physical').total },
    { prefix: 'at', dash: '2 3', label: () => t('chart.drAttack'),
      has: (x) => has(x, '% Attack Damage Reduction'), value: (x) => damageCut(x, 'physical', { attack: true }).total },
    { prefix: 'cr', dash: '8 3 2 3', label: () => t('chart.drCrit'),
      has: (x) => has(x, '% Critical Damage Reduction'), value: (x) => damageCut(x, 'physical', { attack: true, crit: true }).total },
  ],
  'Magic Reduction': [
    { prefix: 'dr', dash: '6 3', label: () => t('chart.drAll'),
      has: (x) => has(x, '% Damage Reduction') || has(x, '% Magic Damage Reduction'), value: (x) => damageCut(x, 'magic').total },
  ],
  Shield: [
    { prefix: 'ad', dash: '6 3', label: () => t('chart.shieldVsAd'), has: (x) => has(x, 'Physical Shield'),
      value: (x) => (x.Shield ?? 0) + (x['Physical Shield'] ?? 0) },
    { prefix: 'ap', dash: '2 3', label: () => t('chart.shieldVsAp'), has: (x) => has(x, 'Magic Shield'),
      value: (x) => (x.Shield ?? 0) + (x['Magic Shield'] ?? 0) },
  ],
  'Total Move Speed': [
    { prefix: 'nc', dash: '6 3', label: () => t('chart.msNoCond'),
      has: (x, p) => conditionalParts(p, '% Move Speed').length > 0 || conditionalParts(p, 'Move Speed').length > 0,
      value: (x, p) => speedWith(x, p, () => 0) },
    { prefix: 'fa', dash: '2 3', label: () => t('chart.msFight'),
      has: (x, p) => conditionalParts(p, '% Move Speed').some((c) => c.passive === 'Ímpeto Gradual'),
      value: (x, p) => speedWith(x, p, (c) => (c.passive === 'Ímpeto Gradual' ? momentumUptime(p?.level ?? 1) : 1)) },
  ],
  'Ability Haste': [
    { prefix: 'ul', dash: '6 3', label: () => t('chart.hasteUlt'),
      has: (x) => has(x, 'Ultimate Haste') || has(x, '% Ultimate Cooldown Reduction'), value: (x) => cooldownCut(x, 'R').effHaste },
    { prefix: 'ba', dash: '2 3', label: () => t('chart.hasteBasic'),
      has: (x) => has(x, 'Basic Ability Haste') || has(x, '% Basic Cooldown Reduction'), value: (x) => cooldownCut(x, null).effHaste },
    ...['1', '2', '3'].map((n) => ({
      prefix: `a${n}`, dash: '8 3 2 3', label: () => t('chart.hasteAbility', { n }),
      has: (x) => has(x, abilityCdr(n)), value: (x) => cooldownCut(x, n).effHaste,
    })),
  ],
};

/** The note under a chart that has subtype lines. */
const EXTRA_HINT = { 'Ability Haste': 'chart.hasteHint', 'Physical Reduction': 'chart.drHint', 'Magic Reduction': 'chart.drHint',
  'Total Move Speed': 'chart.msHint' };

function extraLines(lines, stat) {
  return lines.flatMap((s) => (EXTRA_LINES[stat] ?? [])
    .filter((vs) => s.result.series.some((p) => vs.has(p.total, p)))
    .map((vs) => ({ s, vs })));
}

/**
 * One row per minute any build has a point, with every build's value there (its last point at or before that minute,
 * up to its own last point), so the tooltip always lists every compared build.
 */
function lineData(lines, stat) {
  const minutes = [...new Set(lines.flatMap((s) => s.result.series.map((p) => p.minute)))].sort((a, b) => a - b);
  return minutes.map((m) => {
    const row = { minute: m };
    for (const s of lines) {
      const series = s.result.series;
      if (m > series[series.length - 1].minute + 1e-9) continue;
      let p = null;
      for (const q of series) {
        if (q.minute <= m + 1e-9) p = q; else break;
      }
      if (p) {
        row[`v${s.key}`] = stat === SLOW_STAT ? (p.slow?.total ?? 0) : statOf(p.total, stat);
        row[`lv${s.key}`] = p.level;
        for (const vs of EXTRA_LINES[stat] ?? []) row[`${vs.prefix}${s.key}`] = vs.value(p.total, p);
      }
    }
    return row;
  });
}

/** State of every build at every minute any build has a point (its last point at or before that minute). */
function statesByMinute(lines, fn) {
  const minutes = [...new Set(lines.flatMap((s) => s.result.series.map((p) => p.minute)))].sort((a, b) => a - b);
  const at = new Map();
  for (const s of lines) {
    let k = 0;
    for (const m of minutes) {
      while (k + 1 < s.result.series.length && s.result.series[k + 1].minute <= m + 1e-9) k++;
      const p = s.result.series[k];
      at.set(`${s.key}|${m}`, fn(s, p.minute <= m + 1e-9 ? p : null));
    }
  }
  return { minutes, at };
}

/**
 * Effective gold over time as stacked bars, one stack per build: one block per item held -- as many as the inventory
 * slots in use -- (gold of its stats, its passives in a lighter tone on top of the same block), plus one block for the
 * champion's passive (Ornn's Living Forge) at the top. A click marks the minute; its breakdown is listed below the chart.
 * The bars are worked out once per build set (not on every mouse move: that made the screen crawl).
 */
function EffectiveBars({ lines, itemsById, maxMinute, plotWidth, align, selectedMinute, onSelectMinute, overlayAt, legendHost }) {
  const model = useMemo(() => {
    const { minutes, at } = statesByMinute(lines, (s, p) => effectiveGold(p, itemsById, s.result.statPrices, p ? overlayAt?.(s.key, p.minute) : undefined));
    // Stack order (bottom to top): purchase order; in build order the boots go last, right below the champion's passive.
    const order = new Map();  // build -> [{ key, name }]
    for (const s of lines) {
      const seen = new Map();
      for (const m of minutes) for (const x of at.get(`${s.key}|${m}`).items) seen.set(x.purchaseIndex, x);
      const rank = (x) => (align === ALIGN_SLOT && x.boots ? 1 : 0);
      order.set(s.key, [...seen.values()]
        .sort((a, b) => rank(a) - rank(b) || a.purchaseIndex - b.purchaseIndex)
        .map((x) => ({ key: x.key, name: x.itemName })));
    }
    const data = minutes.map((m) => {
      const row = { minute: m };
      for (const s of lines) {
        const e = at.get(`${s.key}|${m}`);
        // Only values above zero: a zero-height segment would still draw a line and look like an extra block.
        for (const x of e.items) {
          if (x.statGold > 0) row[`${s.key}|${x.key}|s`] = x.statGold;
          if (x.passiveGold > 0) row[`${s.key}|${x.key}|p`] = x.passiveGold;
        }
        if (e.forge.gold > 0) row[`${s.key}|forge`] = e.forge.gold;
      }
      return row;
    });
    return { minutes, at, order, data };
  }, [lines, itemsById, align, overlayAt]);
  const { minutes, at, order, data } = model;
  const barSize = Math.max(2, Math.min(18, (plotWidth / Math.max(1, minutes.length) / lines.length) * 0.75));
  const label = markedMinute(minutes, selectedMinute);
  const columns = label == null ? [] : lines.map((s) => ({
    key: s.key, name: s.name, color: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length],
    eff: at.get(`${s.key}|${label}`),
  }));
  const chart = useMemo(() => (
    <BarChart
      data={data} margin={MARGIN} barGap={1} barSize={barSize}
      onClick={(e) => { if (e && e.activeLabel != null) onSelectMinute(Number(e.activeLabel)); }}
      style={{ cursor: 'crosshair' }}
    >
      <CartesianGrid stroke="var(--grid)" vertical={false} />
      <XAxis
        dataKey="minute" type="number" domain={[0, maxMinute]} padding={{ left: barSize, right: barSize }}
        tickFormatter={(v) => `${v}m`} stroke="var(--muted)" tick={{ fill: 'var(--text-2)', fontSize: 12 }}
      />
      <YAxis stroke="var(--muted)" tick={{ fill: 'var(--gold)', fontSize: 12 }} width={Y_AXIS_WIDTH} tickFormatter={(v) => n0(v)} />
      {selectedMinute != null && <ReferenceLine x={selectedMinute} stroke="var(--gold)" strokeWidth={1.5} />}
      {lines.flatMap((s) => [
        ...order.get(s.key).flatMap((x) => [
          <Bar key={`${s.key}|${x.key}|s`} dataKey={`${s.key}|${x.key}|s`} stackId={String(s.key)} name={x.name}
            fill={itemColor(x.name)} isAnimationActive={false} />,
          <Bar key={`${s.key}|${x.key}|p`} dataKey={`${s.key}|${x.key}|p`} stackId={String(s.key)} name={x.name}
            fill={itemPassiveColor(x.name)} isAnimationActive={false} />,
        ]),
        <Bar key={`${s.key}|forge`} dataKey={`${s.key}|forge`} stackId={String(s.key)} fill={FORGE_COLOR} isAnimationActive={false} />,
      ])}
    </BarChart>
  ), [data, barSize, maxMinute, selectedMinute, lines, order, onSelectMinute]);

  return (
    <>
      <div style={{ width: '100%', height: 340 }} role="figure" aria-label={t('chart.effectiveAria')}>
        <ResponsiveContainer>{chart}</ResponsiveContainer>
      </div>
      <ChartLegend label={label} host={legendHost}>
        <EffectiveBreakdown compact={lines.length > 1} align={align} columns={columns} />
      </ChartLegend>
    </>
  );
}

function EventLane({ build, x, plotLeft, plotWidth, levels, onSelectMinute, selectedX }) {
  const color = SERIES_COLORS[build.colorIndex % SERIES_COLORS.length];
  return (
    <div className="event-lane" style={{ '--lane-color': color }}>
      <div className="lane-label" style={{ width: plotLeft - 6 }} title={build.name}>
        <span className="swatch" style={{ background: color }} />{build.name}
      </div>
      <div className="lane-track" style={{ left: plotLeft, width: plotWidth }} />
      {selectedX != null && <div className="lane-cursor" style={{ left: selectedX }} />}
      {levels.map((l) => (
        <button
          key={`lv${l.level}`} className="lane-level" style={{ left: x(l.minute) }}
          title={t('chart.laneLevel', { time: mmss(l.minute), n: l.level })} onClick={() => onSelectMinute(l.minute)}
        >
          {l.level}
        </button>
      ))}
      {build.result.steps.map((s) => (
        <button
          key={`buy${s.index}`} className={`lane-item${s.implied ? ' implied' : ''}${s.sold ? ' sold' : ''}`} style={{ left: x(s.minute) }}
          title={s.sold ? t('chart.laneSale', { time: mmss(s.minute), name: s.itemName, gold: n0(-s.paidCost) })
            : t('chart.lanePurchase', { time: mmss(s.minute), name: s.itemName, paid: n0(s.paidCost) })}
          onClick={() => onSelectMinute(s.minute)}
        >
          <ItemIcon id={s.itemId} size={24} />
        </button>
      ))}
    </div>
  );
}
