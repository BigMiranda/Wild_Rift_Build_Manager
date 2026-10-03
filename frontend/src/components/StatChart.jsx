import { useEffect, useRef, useState } from 'react';
import { CartesianGrid, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { statLabel, t } from '../i18n.js';
import { SERIES_COLORS, TABLE_STATS, mmss, n0, n1 } from '../format.js';
import ItemIcon from './ItemIcon.jsx';
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
 */
export default function StatChart({ series, stat, onStatChange, xpTable, selectedMinute, onSelectMinute }) {
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
        {TABLE_STATS.map((s) => (
          <button key={s} className={`stat-chip${s === stat ? ' active' : ''}`} aria-pressed={s === stat}
            onClick={() => onStatChange(s)} style={s === stat ? { borderColor: statColor(s) } : undefined}>
            <StatIcon stat={s} /><span style={{ color: statColor(s) }}>{statLabel(s)}</span>
          </button>
        ))}
      </div>
      <div ref={wrapRef}>
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
                domain={['auto', 'auto']} tickFormatter={(v) => n0(v)}
              />
              <Tooltip
                contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 6, color: 'var(--text)' }}
                labelFormatter={(v) => t('chart.minute', { time: mmss(v) })}
                formatter={(v, name, p) => [`${n1(v)} ${statLabel(stat)} · ${t('chart.lv', { n: p.payload.level })}`, name]}
                cursor={{ stroke: 'var(--muted)', strokeDasharray: '3 3' }}
              />
              {lines.length >= 2 && <Legend wrapperStyle={{ color: 'var(--text-2)' }} />}
              {selectedMinute != null && <ReferenceLine x={selectedMinute} stroke="var(--gold)" strokeWidth={1.5} />}
              {lines.map((s) => (
                <Line
                  key={s.key}
                  name={s.name}
                  data={s.result.series.map((p) => ({ minute: p.minute, level: p.level, value: p.total[stat] }))}
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
          key={`buy${s.index}`} className="lane-item" style={{ left: x(s.minute) }}
          title={t('chart.lanePurchase', { time: mmss(s.minute), name: s.itemName, paid: n0(s.paidCost) })}
          onClick={() => onSelectMinute(s.minute)}
        >
          <ItemIcon id={s.itemId} size={24} />
        </button>
      ))}
    </div>
  );
}
