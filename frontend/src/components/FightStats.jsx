import { useMemo } from 'react';
import { Line, LineChart, ReferenceArea, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { statLabel, t } from '../i18n.js';
import { n0, n1 } from '../format.js';
import { StatIcon, statColor } from './StatIcon.jsx';

/** Stats the arena follows over a fight (ArenaModel.TRACKED), in its order. */
export const TRACKED = ['Max Health', 'Armor', 'Magic Resistance', 'Attack Damage', 'Ability Power', 'attackSpeed',
  'moveSpeed', 'shield', 'damageReduction'];
const STAT_OF = { attackSpeed: '% Attack Speed', moveSpeed: 'Move Speed', shield: 'Shield', damageReduction: 'Armor' };
const SPAN_COLORS = ['#f0c75e', '#5fbf6a', '#b48cf0', '#4fd1c5', '#f08cc0', '#9aa5b1', '#f08c3c'];

const keyLabel = (k) => (STAT_OF[k] && !['moveSpeed'].includes(k) ? t(`fstats.k.${k}`) : statLabel(STAT_OF[k] ?? k));
const fmt = (k, v) => (v == null || Number.isNaN(v) ? '—' : k === 'attackSpeed' ? v.toFixed(2).replace('.', ',')
  : k === 'damageReduction' ? `${n0(v * 100)}%` : n0(v));

/** Each stat as change points [{t, v}] from the fight's written changes (the first point has them all). */
function seriesOf(track) {
  const out = {};
  for (const p of track ?? []) {
    for (const [k, v] of Object.entries(p.v)) (out[k] ??= []).push({ t: p.t, v });
  }
  return out;
}

const valueAt = (points, time) => {
  let v = points?.[0]?.v;
  for (const p of points ?? []) {
    if (p.t > time + 1e-9) break;
    v = p.v;
  }
  return v;
};

/** Time-weighted mean of a step series over [0, end]. */
function mean(points, end) {
  if (!points?.length || end <= 0) return null;
  let sum = 0;
  points.forEach((p, i) => {
    const to = i + 1 < points.length ? Math.min(end, points[i + 1].t) : end;
    sum += p.v * Math.max(0, to - p.t);
  });
  return sum / end;
}

/** When each conditional effect was on: {source: [[from, to]]} (to = the fight's end when it never turned off). */
function spansOf(toggles, end) {
  const out = {};
  const open = {};
  for (const x of toggles ?? []) {
    if (x.on) open[x.source] = x.t;
    else if (open[x.source] != null) {
      (out[x.source] ??= []).push([open[x.source], x.t]);
      delete open[x.source];
    }
  }
  Object.entries(open).forEach(([s, from]) => (out[s] ??= []).push([from, end]));
  return out;
}

const onAt = (spans, time) => Object.entries(spans).filter(([, list]) => list.some(([a, b]) => time >= a - 1e-9 && time < b - 1e-9))
  .map(([s]) => s);

/**
 * The real simultaneous peak: the moment the stats are highest together, as the sum of each defensive and damage stat
 * over its value at the start (health counting its shield); the effects on at that moment are what made it.
 */
const PEAK_KEYS = ['Max Health', 'Armor', 'Magic Resistance', 'Attack Damage', 'Ability Power'];
function peakOf(series, end) {
  const times = [...new Set(Object.values(series).flat().map((p) => p.t))].filter((x) => x <= end).sort((a, b) => a - b);
  let best = null;
  for (const time of times) {
    const score = PEAK_KEYS.reduce((sum, k) => {
      const v0 = series[k]?.[0]?.v;
      const v = (valueAt(series[k], time) ?? 0) + (k === 'Max Health' ? valueAt(series.shield, time) ?? 0 : 0);
      return sum + (v0 > 0 ? v / v0 : 0);
    }, 0);
    if (!best || score > best.score + 1e-9) best = { time, score };
  }
  return best;
}

const spanText = (list, end) => list.map(([a, b]) => (b >= end - 1e-9
  ? t('fstats.onFrom', { s: n1(a) }) : `${n1(a)}–${n1(b)} s`)).join(', ');

/**
 * "Stats over the fight", one block per champion: the "photo" at the playback cursor (its stats and the conditional
 * effects on), a chart per stat that changes with the spans of each conditional effect, and the planner's sheet with
 * every effect on and off next to the fight's mean and its real simultaneous peak.
 */
export default function FightStats({ result, label, colors, time }) {
  const end = result.duration;
  const data = useMemo(() => result.fighters.map((f) => {
    const series = seriesOf(f.statTrack);
    const spans = spansOf(f.toggles, end);
    return { series, spans, peak: peakOf(series, end) };
  }), [result, end]);
  if (!result.fighters.some((f) => f.statTrack?.length)) return null;
  return (
    <section className="panel fight-report fight-stats">
      <h3>{t('fstats.title')}</h3>
      <p className="muted small" style={{ marginTop: 0 }}>{t('fstats.help')}</p>
      {result.fighters.map((f, i) => (f.statTrack?.length ? (
        <FighterStats key={i} f={f} i={i} label={label(i)} color={colors[i]} d={data[i]} end={end} time={time} />
      ) : null))}
    </section>
  );
}

function FighterStats({ f, i, label, color, d, end, time }) {
  const { series, spans, peak } = d;
  const sources = Object.keys(spans);
  const colorOf = (s) => SPAN_COLORS[sources.indexOf(s) % SPAN_COLORS.length];
  const active = onAt(spans, time);
  const changing = TRACKED.filter((k) => new Set((series[k] ?? []).map((p) => p.v)).size > 1);
  const steady = TRACKED.filter((k) => !changing.includes(k) && series[k]?.length);
  const hasSheet = f.sheetOn && Object.keys(f.sheetOn).length > 0;
  return (
    <details className="fstats-fighter" open={i === 0}>
      <summary><span className="swatch" style={{ background: color }} /><strong>{i + 1} · {label}</strong></summary>
      <p className="fstats-photo">
        <strong>{t('fstats.photo', { s: n1(time) })}</strong>{' '}
        {TRACKED.filter((k) => series[k]).map((k) => (
          <span key={k} className="fstats-val" style={{ color: statColor(STAT_OF[k] ?? k) }} title={keyLabel(k)}>
            <StatIcon stat={STAT_OF[k] ?? k} size={12} />{fmt(k, valueAt(series[k], time))}
          </span>
        ))}
        <br />
        <small className="muted">{active.length ? t('fstats.activeNow', { list: active.join(', ') }) : t('fstats.noneNow')}</small>
      </p>
      {sources.length > 0 && (
        <ul className="fstats-spans">
          {sources.map((s) => (
            <li key={s}><span className="swatch" style={{ background: colorOf(s) }} />{s}: {spanText(spans[s], end)}</li>
          ))}
        </ul>
      )}
      <div className="fstats-table table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th className="l">{t('fstats.stat')}</th>
              {hasSheet && <th title={t('fstats.sheetOnTitle')}>{t('fstats.sheetOn')}</th>}
              {hasSheet && <th title={t('fstats.sheetOffTitle')}>{t('fstats.sheetOff')}</th>}
              <th>{t('fstats.mean')}</th>
              <th title={t('fstats.peakTitle')}>{peak ? t('fstats.peak', { s: n1(peak.time) }) : t('fstats.peakNone')}</th>
            </tr>
          </thead>
          <tbody>
            {TRACKED.filter((k) => series[k]).map((k) => (
              <tr key={k}>
                <td className="l" style={{ color: statColor(STAT_OF[k] ?? k) }}><StatIcon stat={STAT_OF[k] ?? k} size={12} />{keyLabel(k)}</td>
                {hasSheet && <td>{k in f.sheetOn ? fmt(k, f.sheetOn[k]) : '—'}</td>}
                {hasSheet && <td>{k in f.sheetOff ? fmt(k, f.sheetOff[k]) : '—'}</td>}
                <td>{fmt(k, mean(series[k], end))}</td>
                <td>{peak ? fmt(k, valueAt(series[k], peak.time)) : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {peak && <small className="muted">{t('fstats.peakOn', { list: onAt(spans, peak.time).join(', ') || '—' })}</small>}
      </div>
      {changing.length > 0 && (
        <div className="fstats-charts">
          {changing.map((k) => (
            <StatChartSmall key={k} k={k} points={series[k]} end={end} spans={spans} colorOf={colorOf} time={time} />
          ))}
        </div>
      )}
      {steady.length > 0 && (
        <small className="muted">{t('fstats.steady', { list: steady.map((k) => `${keyLabel(k)} ${fmt(k, series[k][0].v)}`).join(' · ') })}</small>
      )}
    </details>
  );
}

function StatChartSmall({ k, points, end, spans, colorOf, time }) {
  const data = useMemo(() => [...points, { t: end, v: points.at(-1).v }], [points, end]);
  const stat = STAT_OF[k] ?? k;
  return (
    <figure className="fstats-chart">
      <figcaption style={{ color: statColor(stat) }}><StatIcon stat={stat} size={12} />{keyLabel(k)}</figcaption>
      <ResponsiveContainer width="100%" height={110}>
        <LineChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
          <XAxis dataKey="t" type="number" domain={[0, end]} tickFormatter={(v) => `${n0(v)}s`} stroke="var(--muted)" fontSize={10} />
          <YAxis stroke="var(--muted)" width={44} fontSize={10} domain={['auto', 'auto']} tickFormatter={(v) => fmt(k, v)} />
          {Object.entries(spans).flatMap(([s, list]) => list.map(([a, b], j) => (
            <ReferenceArea key={`${s}${j}`} x1={a} x2={Math.min(b, end)} fill={colorOf(s)} fillOpacity={0.22} ifOverflow="hidden" />
          )))}
          <ReferenceLine x={time} stroke="var(--gold-bright)" strokeWidth={1.5} />
          <Tooltip formatter={(v) => [fmt(k, v), keyLabel(k)]} labelFormatter={(v) => `${n1(v)}s`}
            contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border-strong)' }} />
          <Line dataKey="v" type="stepAfter" stroke={statColor(stat)} dot={false} strokeWidth={2} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
    </figure>
  );
}
