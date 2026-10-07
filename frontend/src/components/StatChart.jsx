import { useEffect, useRef, useState } from 'react';
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { statLabel, t } from '../i18n.js';
import { CHART_STATS, PERCENT_STATS, SERIES_COLORS, mmss, n0, n1, statOf } from '../format.js';
import { PASSIVES_VIEW, acquiredPassives, passiveColor, pointItemIds } from '../passives.js';
import ItemIcon from './ItemIcon.jsx';
import PassiveCompare from './PassiveCompare.jsx';
import EffectiveBreakdown from './EffectiveBreakdown.jsx';
import { EFFECTIVE_VIEW, FORGE_COLOR, effectiveGold, itemColor, itemPassiveColor } from '../effective.js';
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
export default function StatChart({ series, stat, onStatChange, xpTable, selectedMinute, onSelectMinute, itemsById }) {
  const lines = series.filter((s) => s.result && s.result.series.length > 0);
  const maxMinute = Math.ceil(Math.max(1, ...lines.map((s) => s.result.series[s.result.series.length - 1].minute)));
  const wrapRef = useRef(null);
  const [width, setWidth] = useState(0);

  useEffect(() => {
    if (!wrapRef.current) return undefined;
    setWidth(wrapRef.current.getBoundingClientRect().width); // the observer's first callback may be delayed
    const ro = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    ro.observe(wrapRef.current);
    return () => ro.disconnect();
  }, []);

  // Same horizontal mapping as the plot area (left margin + Y axis, right margin).
  const plotLeft = MARGIN.left + Y_AXIS_WIDTH;
  const plotWidth = Math.max(1, width - plotLeft - MARGIN.right);
  const x = (minute) => plotLeft + (Math.min(minute, maxMinute) / maxMinute) * plotWidth;

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
          <EffectiveBars
            lines={lines} itemsById={itemsById} maxMinute={maxMinute} plotWidth={plotWidth}
            selectedMinute={selectedMinute} onSelectMinute={onSelectMinute}
          />
        ) : stat === PASSIVES_VIEW ? (
          <PassiveBars
            lines={lines} itemsById={itemsById} maxMinute={maxMinute} plotWidth={plotWidth}
            selectedMinute={selectedMinute} onSelectMinute={onSelectMinute}
          />
        ) : (
        <div style={{ width: '100%', height: 300 }} role="img" aria-label={t('chart.aria', { stat: statLabel(stat) })}>
          <ResponsiveContainer>
            <LineChart
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
                formatter={(v, name, p) => [`${n1(v)}${PERCENT_STATS.has(stat) ? '%' : ''} ${statLabel(stat)} · ${t('chart.lv', { n: p.payload.level })}`, name]}
                cursor={{ stroke: 'var(--muted)', strokeDasharray: '3 3' }}
              />
              {lines.length >= 2 && <Legend wrapperStyle={{ color: 'var(--text-2)' }} />}
              {selectedMinute != null && <ReferenceLine x={selectedMinute} stroke="var(--gold)" strokeWidth={1.5} />}
              {lines.map((s) => (
                <Line
                  key={s.key}
                  name={s.name}
                  data={s.result.series.map((p) => ({ minute: p.minute, level: p.level, value: statOf(p.total, stat) }))}
                  dataKey="value"
                  type="stepAfter"
                  stroke={SERIES_COLORS[s.colorIndex % SERIES_COLORS.length]}
                  strokeWidth={2}
                  dot={false}
                  activeDot={{ r: 5, stroke: 'var(--surface)', strokeWidth: 2 }}
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
        <small className="muted">{t('chart.clickHint')}</small>
      </div>
    </div>
  );
}

/**
 * Passives held over time as stacked bars on the same time axis as the stat chart (so the event lanes still line up):
 * a bar per series point and build, a block per passive, every block worth its gold estimate.
 */
function PassiveBars({ lines, itemsById, maxMinute, plotWidth, selectedMinute, onSelectMinute }) {
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
  const barSize = Math.max(2, Math.min(18, (plotWidth / Math.max(1, minutes.length) / lines.length) * 0.75));

  return (
    <div style={{ width: '100%', height: 300 }} role="img" aria-label={t('chart.passivesAria')}>
      <ResponsiveContainer>
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
          <Tooltip
            cursor={{ fill: 'rgba(200, 170, 110, .08)' }}
            content={({ active, label }) => (active && label != null ? (
              <div className={`chart-tip${lines.length > 1 ? ' wide' : ''}`}>
                <strong>{t('chart.minute', { time: mmss(label) })}</strong>
                <PassiveCompare
                  compact
                  columns={lines.map((s) => ({
                    key: s.key, name: s.name, color: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length],
                    list: held.get(`${s.key}|${label}`) ?? [],
                  }))}
                />
              </div>
            ) : null)}
          />
          {selectedMinute != null && <ReferenceLine x={selectedMinute} stroke="var(--gold)" strokeWidth={1.5} />}
          {lines.flatMap((s) => order.get(s.key).map((key) => (
            <Bar
              key={`${s.key}|${key}`} dataKey={`${s.key}|${key}`} stackId={String(s.key)} name={names.get(key)?.name}
              fill={passiveColor(key)} stroke="var(--surface)" strokeWidth={1} isAnimationActive={false}
            />
          )))}
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
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
 * Effective gold over time as stacked bars, one stack per build: a block per item held (gold of its stats) with a
 * lighter block on top for its passives, and Ornn's Living Forge at the top.
 */
function EffectiveBars({ lines, itemsById, maxMinute, plotWidth, selectedMinute, onSelectMinute }) {
  const { minutes, at } = statesByMinute(lines, (s, p) => effectiveGold(p, itemsById, s.result.statPrices));
  const order = new Map();  // build -> [{ key, name }] items in purchase order
  for (const s of lines) {
    const seen = new Map();
    for (const m of minutes) for (const x of at.get(`${s.key}|${m}`).items) seen.set(x.purchaseIndex, x.itemName);
    order.set(s.key, [...seen.entries()].sort((a, b) => a[0] - b[0]).map(([k, name]) => ({ key: String(k), name })));
  }
  const data = minutes.map((m) => {
    const row = { minute: m };
    for (const s of lines) {
      const e = at.get(`${s.key}|${m}`);
      for (const x of e.items) {
        row[`${s.key}|${x.key}|s`] = x.statGold;
        row[`${s.key}|${x.key}|p`] = x.passiveGold;
      }
      if (e.forge.gold > 0) row[`${s.key}|forge`] = e.forge.gold;
    }
    return row;
  });
  const barSize = Math.max(2, Math.min(18, (plotWidth / Math.max(1, minutes.length) / lines.length) * 0.75));

  return (
    <div style={{ width: '100%', height: 340 }} role="img" aria-label={t('chart.effectiveAria')}>
      <ResponsiveContainer>
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
          <Tooltip
            cursor={{ fill: 'rgba(200, 170, 110, .08)' }}
            wrapperStyle={{ zIndex: 30 }}
            content={({ active, label }) => (active && label != null ? (
              <div className="chart-tip wide">
                <strong>{t('chart.minute', { time: mmss(label) })}</strong>
                <EffectiveBreakdown
                  compact
                  columns={lines.map((s) => ({
                    key: s.key, name: s.name, color: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length],
                    eff: at.get(`${s.key}|${label}`),
                  }))}
                />
              </div>
            ) : null)}
          />
          {selectedMinute != null && <ReferenceLine x={selectedMinute} stroke="var(--gold)" strokeWidth={1.5} />}
          {lines.flatMap((s) => [
            ...order.get(s.key).flatMap((x) => [
              <Bar key={`${s.key}|${x.key}|s`} dataKey={`${s.key}|${x.key}|s`} stackId={String(s.key)} name={x.name}
                fill={itemColor(x.name)} isAnimationActive={false} />,
              <Bar key={`${s.key}|${x.key}|p`} dataKey={`${s.key}|${x.key}|p`} stackId={String(s.key)} name={x.name}
                fill={itemPassiveColor(x.name)} stroke="var(--surface)" strokeWidth={1} isAnimationActive={false} />,
            ]),
            <Bar key={`${s.key}|forge`} dataKey={`${s.key}|forge`} stackId={String(s.key)} fill={FORGE_COLOR} isAnimationActive={false} />,
          ])}
        </BarChart>
      </ResponsiveContainer>
    </div>
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
          key={`buy${s.index}`} className={`lane-item${s.implied ? ' implied' : ''}`} style={{ left: x(s.minute) }}
          title={t('chart.lanePurchase', { time: mmss(s.minute), name: s.itemName, paid: n0(s.paidCost) })}
          onClick={() => onSelectMinute(s.minute)}
        >
          <ItemIcon id={s.itemId} size={24} />
        </button>
      ))}
    </div>
  );
}
