import { useEffect, useRef, useState } from 'react';
import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { statLabel, t } from '../i18n.js';
import { CHART_STATS, PERCENT_STATS, SERIES_COLORS, mmss, n0, n1, statOf } from '../format.js';
import { PASSIVES_VIEW, acquiredPassives, passiveColor, pointItemIds } from '../passives.js';
import ItemIcon from './ItemIcon.jsx';
import PassiveCompare from './PassiveCompare.jsx';
import EffectiveBreakdown, { AlignToggle } from './EffectiveBreakdown.jsx';
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
export default function StatChart({ series, stat, onStatChange, xpTable, selectedMinute, onSelectMinute, itemsById, align, onAlignChange }) {
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
          <>
            <AlignToggle value={align} onChange={onAlignChange} />
            <EffectiveBars
              lines={lines} itemsById={itemsById} maxMinute={maxMinute} plotWidth={plotWidth} align={align}
              selectedMinute={selectedMinute} onSelectMinute={onSelectMinute}
            />
          </>
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
  const tip = useStickyTip();

  return (
    <div ref={tip.ref} className="sticky-host" style={{ width: '100%', height: 300 }} role="figure" aria-label={t('chart.passivesAria')}>
      <ResponsiveContainer>
        <BarChart
          data={data} margin={MARGIN} barGap={1} barSize={barSize}
          onClick={(e) => { if (e && e.activeLabel != null) onSelectMinute(Number(e.activeLabel)); }}
          onMouseMove={tip.onMove} onMouseLeave={tip.onLeave}
          style={{ cursor: 'crosshair' }}
        >
          <CartesianGrid stroke="var(--grid)" vertical={false} />
          <XAxis
            dataKey="minute" type="number" domain={[0, maxMinute]} padding={{ left: barSize, right: barSize }}
            tickFormatter={(v) => `${v}m`} stroke="var(--muted)" tick={{ fill: 'var(--text-2)', fontSize: 12 }}
          />
          <YAxis stroke="var(--muted)" tick={{ fill: 'var(--gold)', fontSize: 12 }} width={Y_AXIS_WIDTH} tickFormatter={(v) => n0(v)} />
          <Tooltip cursor={{ fill: 'rgba(200, 170, 110, .08)' }} content={() => null} />
          {tip.state?.frozen && <ReferenceLine x={tip.state.label} stroke="var(--gold-bright)" strokeDasharray="3 3" />}
          {selectedMinute != null && <ReferenceLine x={selectedMinute} stroke="var(--gold)" strokeWidth={1.5} />}
          {lines.flatMap((s) => order.get(s.key).map((key) => (
            <Bar
              key={`${s.key}|${key}`} dataKey={`${s.key}|${key}`} stackId={String(s.key)} name={names.get(key)?.name}
              fill={passiveColor(key)} stroke="var(--surface)" strokeWidth={1} isAnimationActive={false}
            />
          )))}
        </BarChart>
      </ResponsiveContainer>
      <StickyTip tip={tip} wide={lines.length > 1}>
        {(label) => (
          <PassiveCompare
            compact
            columns={lines.map((s) => ({
              key: s.key, name: s.name, color: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length],
              list: held.get(`${s.key}|${label}`) ?? [],
            }))}
          />
        )}
      </StickyTip>
    </div>
  );
}

/**
 * Tooltip that follows the bar under the mouse and stays put while the mouse is over it, so it can be scrolled to the
 * end and read; it closes when the mouse leaves it (or leaves the chart without going into it).
 */
function useStickyTip() {
  const ref = useRef(null);
  const [state, setState] = useState(null);  // { label, x, frozen }
  const over = useRef(false);
  const timer = useRef(null);
  const clear = () => { clearTimeout(timer.current); timer.current = null; };
  useEffect(() => clear, []);
  return {
    ref,
    state,
    onMove: (e) => {
      if (over.current || !e || e.activeLabel == null) return;
      clear();
      setState({ label: Number(e.activeLabel), x: e.chartX, frozen: false });
    },
    onLeave: () => {
      clear();
      timer.current = setTimeout(() => { if (!over.current) setState(null); }, 300);
    },
    enter: () => { over.current = true; clear(); setState((st) => (st ? { ...st, frozen: true } : st)); },
    leave: () => { over.current = false; setState(null); },
  };
}

function StickyTip({ tip, wide, children }) {
  const st = tip.state;
  const box = useRef(null);
  // The wheel scrolls the tooltip first; once it reaches its end the page scrolls (the tooltip stays while hovered).
  useEffect(() => {
    const el = box.current;
    if (!el) return undefined;
    const onWheel = (e) => {
      const room = e.deltaY > 0 ? el.scrollHeight - el.clientHeight - el.scrollTop : el.scrollTop;
      if (room <= 0) return;
      e.preventDefault();
      el.scrollTop += Math.sign(e.deltaY) * Math.min(Math.abs(e.deltaY), room);
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [st != null]);
  if (!st) return null;
  const width = tip.ref.current?.clientWidth ?? 0;
  // Right next to the cursor (on the side with more room), so it can be reached without crossing other bars.
  const side = st.x > width / 2 ? { right: Math.max(0, width - st.x + 4) } : { left: st.x + 4 };
  return (
    <div
      ref={box} className={`chart-tip sticky-tip${wide ? ' wide' : ''}${st.frozen ? ' frozen' : ''}`} style={side}
      onMouseEnter={tip.enter} onMouseLeave={tip.leave}
    >
      <div className="sticky-tip-head">
        <strong>{t('chart.minute', { time: mmss(st.label) })}</strong>
        <small className="muted">{st.frozen ? t('chart.tipFrozen') : t('chart.tipHint')}</small>
      </div>
      {children(st.label)}
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
 * Effective gold over time as stacked bars, one stack per build: one block per item held -- as many as the inventory
 * slots in use -- (gold of its stats, its passives in a lighter tone on top of the same block), plus one block for the
 * champion's passive (Ornn's Living Forge) at the top.
 */
function EffectiveBars({ lines, itemsById, maxMinute, plotWidth, align, selectedMinute, onSelectMinute }) {
  const { minutes, at } = statesByMinute(lines, (s, p) => effectiveGold(p, itemsById, s.result.statPrices));
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
  const barSize = Math.max(2, Math.min(18, (plotWidth / Math.max(1, minutes.length) / lines.length) * 0.75));
  const tip = useStickyTip();

  return (
    <div ref={tip.ref} className="sticky-host" style={{ width: '100%', height: 340 }} role="figure" aria-label={t('chart.effectiveAria')}>
      <ResponsiveContainer>
        <BarChart
          data={data} margin={MARGIN} barGap={1} barSize={barSize}
          onClick={(e) => { if (e && e.activeLabel != null) onSelectMinute(Number(e.activeLabel)); }}
          onMouseMove={tip.onMove} onMouseLeave={tip.onLeave}
          style={{ cursor: 'crosshair' }}
        >
          <CartesianGrid stroke="var(--grid)" vertical={false} />
          <XAxis
            dataKey="minute" type="number" domain={[0, maxMinute]} padding={{ left: barSize, right: barSize }}
            tickFormatter={(v) => `${v}m`} stroke="var(--muted)" tick={{ fill: 'var(--text-2)', fontSize: 12 }}
          />
          <YAxis stroke="var(--muted)" tick={{ fill: 'var(--gold)', fontSize: 12 }} width={Y_AXIS_WIDTH} tickFormatter={(v) => n0(v)} />
          <Tooltip cursor={{ fill: 'rgba(200, 170, 110, .08)' }} content={() => null} />
          {tip.state?.frozen && <ReferenceLine x={tip.state.label} stroke="var(--gold-bright)" strokeDasharray="3 3" />}
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
      </ResponsiveContainer>
      <StickyTip tip={tip} wide>
        {(label) => (
          <EffectiveBreakdown
            compact align={align}
            columns={lines.map((s) => ({
              key: s.key, name: s.name, color: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length],
              eff: at.get(`${s.key}|${label}`),
            }))}
          />
        )}
      </StickyTip>
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
