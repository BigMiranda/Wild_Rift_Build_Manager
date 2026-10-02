import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { SERIES_COLORS, TABLE_STATS, mmss, n1, statLabel } from '../format.js';

/**
 * One stat over time, one line per build (active build first, then compared builds in the order they were added,
 * so each build keeps its color). Step lines: stats only change at purchases and level-ups.
 */
export default function StatChart({ series, stat, onStatChange }) {
  const lines = series.filter((s) => s.result && s.result.series.length > 0);
  const maxMinute = Math.max(1, ...lines.map((s) => s.result.series[s.result.series.length - 1].minute));

  return (
    <div>
      <div className="chips" role="group" aria-label="Status do gráfico" style={{ marginBottom: 10 }}>
        {TABLE_STATS.map((s) => (
          <button key={s} className={s === stat ? 'active' : ''} aria-pressed={s === stat} onClick={() => onStatChange(s)}>
            {statLabel(s)}
          </button>
        ))}
      </div>
      <div style={{ width: '100%', height: 320 }} role="img" aria-label={`${statLabel(stat)} ao longo do tempo`}>
        <ResponsiveContainer>
          <LineChart margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
            <CartesianGrid stroke="var(--grid)" vertical={false} />
            <XAxis
              dataKey="minute" type="number" domain={[0, Math.ceil(maxMinute)]} allowDuplicatedCategory={false}
              tickFormatter={(v) => `${v}m`} stroke="var(--muted)" tick={{ fill: 'var(--text-2)', fontSize: 12 }}
            />
            <YAxis
              stroke="var(--muted)" tick={{ fill: 'var(--text-2)', fontSize: 12 }} width={56}
              domain={['auto', 'auto']} tickFormatter={(v) => n1(v)}
            />
            <Tooltip
              contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 8, color: 'var(--text)' }}
              labelFormatter={(v) => `Minuto ${mmss(v)}`}
              formatter={(v, name, p) => [`${n1(v)} (nv ${p.payload.level})`, name]}
              cursor={{ stroke: 'var(--muted)', strokeDasharray: '3 3' }}
            />
            {lines.length >= 2 && <Legend wrapperStyle={{ color: 'var(--text-2)' }} />}
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
    </div>
  );
}
