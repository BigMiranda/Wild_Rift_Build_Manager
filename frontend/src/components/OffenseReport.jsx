import { useEffect, useRef, useState } from 'react';
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { SERIES_COLORS, n0, n1 } from '../format.js';
import { useTask } from '../tasks.js';
import ItemIcon from './ItemIcon.jsx';
import Help from './Help.jsx';

let metaPromise = null;
/** The offensive tests' metadata (presets, the user's targets, sweeps, gold mixes, report), fetched once per load. */
export const offenseMeta = (reload) => {
  if (!metaPromise || reload) metaPromise = api.get('/api/offense/meta');
  return metaPromise;
};

/** The weighted offensive index of a set of outcomes (by the report's weights). */
export function weightedOffense(entries, outcomes) {
  let sum = 0;
  let w = 0;
  outcomes.forEach((o, i) => {
    if (o?.index != null) {
      sum += (entries[i]?.weight ?? 1) * o.index;
      w += entries[i]?.weight ?? 1;
    }
  });
  return w > 0 ? sum / w : null;
}

const ttk = (m, duration) => (m == null ? '—' : m.killed ? `${n1(m.timeToKill)} s` : t('off.notKilled', { s: n0(duration), pct: n0(m.healthLeftPct) }));
const pct = (v) => `${n0(v)}%`;
const mixLabel = (mix) => Object.entries(mix ?? {}).map(([k, v]) => `${Math.round(v * 100)}% ${k}`).join(' + ');

/** Runs jobs in batches (so the progress moves and Stop works). */
async function runBatches(base, jobs, onProgress, stopRef) {
  const out = [];
  const size = 6;
  for (let i = 0; i < jobs.length; i += size) {
    if (stopRef.current) break;
    const part = await api.post('/api/offense/evaluate', { ...base, jobs: jobs.slice(i, i + size) });
    out.push(...part);
    onProgress(Math.min(jobs.length, i + size), jobs.length);
  }
  return out;
}

/**
 * "Causar dano": the build attacks target ragdolls (Rodada 9, part 1b). The report over the targets, the sweeps of one
 * attribute of the target, and each item's worth (with × without, × only its stats, × its gold in pure stats).
 */
export default function OffensePanel({ build, buildId, minute }) {
  const [meta, setMeta] = useState(null);
  const [mode, setMode] = useState('como_joga');
  const [result, setResult] = useState(null);
  const [sweeps, setSweeps] = useState(null);
  const [perItem, setPerItem] = useState(null);
  const [mix, setMix] = useState(0);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);
  const stop = useRef(false);
  useTask('offense', !!busy, { label: t('off.task'), done: busy?.done, total: busy?.total, path: '/relatorios/efetividade' });

  useEffect(() => {
    offenseMeta().then(setMeta).catch((e) => setError(e.message));
  }, []);
  useEffect(() => {
    setResult(null);
    setSweeps(null);
    setPerItem(null);
  }, [buildId, minute, JSON.stringify(build?.steps ?? [])]);

  if (!meta) return error ? <p className="error">{error}</p> : <p className="muted">{t('compare.calculating')}</p>;
  const report = meta.report;
  const names = {};
  Object.entries(meta.presets).forEach(([k, p]) => { names[k] = p.name; });
  meta.userTargets.forEach((u) => { names[`u:${u.id}`] = u.name; });
  const base = { ...(buildId ? { buildId } : { build }), minute, readMode: mode, starts: 6 };
  const progress = (done, total) => setBusy({ done, total });

  const go = async (fn) => {
    setError(null);
    stop.current = false;
    try {
      await fn();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(null);
    }
  };
  const runReport = () => go(async () => {
    const jobs = report.targets.map((e) => ({ targetKey: e.key }));
    setBusy({ done: 0, total: jobs.length });
    const out = await runBatches(base, jobs, progress, stop);
    setResult(out);
  });
  const runSweeps = () => go(async () => {
    const jobs = [];
    report.sweeps.forEach((s) => s.stages.forEach((v) => jobs.push({ tag: `${s.key}|${v}`, stage: { dimension: s.key, value: v } })));
    setBusy({ done: 0, total: jobs.length });
    const out = await runBatches(base, jobs, progress, stop);
    setSweeps(report.sweeps.map((s) => ({ ...s, points: s.stages.map((v) => ({ v, o: out.find((o) => o.tag === `${s.key}|${v}`) })) })));
  });
  const runItems = () => go(async () => {
    const held = await api.post('/api/effect/items', { ...(buildId ? { buildId } : { build }), minute });
    const kinds = ['build', 'sem', 'so_status', 'ouro'];
    const jobs = [];
    report.targets.forEach((e) => {
      jobs.push({ tag: `build|${e.key}`, targetKey: e.key });
      held.forEach((h) => kinds.slice(1).forEach((k) => jobs.push({
        tag: `${k}|${h.id}|${e.key}`, targetKey: e.key,
        variant: { kind: k, itemId: h.id, ...(k === 'ouro' ? { mix: meta.mixes[mix] } : {}) },
      })));
    });
    setBusy({ done: 0, total: jobs.length });
    const out = await runBatches(base, jobs, progress, stop);
    const by = Object.fromEntries(out.map((o) => [o.tag, o]));
    setPerItem({ held, by, mix: meta.mixes[mix] });
  });

  const total = result ? weightedOffense(report.targets, result) : null;

  return (
    <section className="panel">
      <h3>{t('off.title')} <Help text={t('off.help')} /></h3>
      <div className="row">
        <label className="inline">{t('off.mode')}
          <select value={mode} onChange={(e) => setMode(e.target.value)}>
            <option value="como_joga">{t('off.mode.como_joga')}</option>
            <option value="largadas">{t('off.mode.largadas')}</option>
          </select>
        </label>
        <button className="primary" disabled={!!busy} onClick={runReport}>{t('off.run')}</button>
        <button disabled={!!busy} onClick={runSweeps}>{t('off.runSweeps')}</button>
        <label className="inline">{t('off.mix')}
          <select value={mix} onChange={(e) => setMix(Number(e.target.value))}>
            {meta.mixes.map((m, i) => <option key={i} value={i}>{mixLabel(m)}</option>)}
          </select>
        </label>
        <button disabled={!!busy} onClick={runItems}>{t('off.runItems')}</button>
        {busy && (
          <>
            <progress value={busy.done} max={Math.max(1, busy.total)} />
            <small className="muted">{busy.done} / {busy.total}</small>
            <button onClick={() => { stop.current = true; }}>{t('calc.stop')}</button>
          </>
        )}
      </div>
      {error && <p className="error">{error}</p>}
      {result && (
        <>
          <p><b>{t('off.index')}: {total == null ? '—' : n0(total)}</b> <Help text={t('off.indexHelp')} /></p>
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>{t('off.target')}</th>
                  <th>{t('off.ttk')} <Help text={t('off.ttkHelp')} /></th>
                  <th>{t('off.dps')} <Help text={t('off.dpsHelp')} /></th>
                  <th>{t('off.lost')} <Help text={t('off.lostHelp')} /></th>
                  <th>{t('off.aim')} <Help text={t('off.aimHelp')} /></th>
                  <th>{t('off.inRange')} <Help text={t('off.inRangeHelp')} /></th>
                  <th>{t('off.refTtk')}</th>
                  <th>{t('off.indexCol')} <Help text={t('off.indexHelp')} /></th>
                </tr>
              </thead>
              <tbody>
                {result.map((o, i) => (
                  <tr key={i}>
                    <td>{o.targetName ?? names[report.targets[i]?.key]}</td>
                    {o.error ? <td colSpan={7} className="error">{o.error}</td> : (
                      <>
                        <td>{ttk(o.build, meta.duration)}</td>
                        <td>{n0(o.build.effectiveDps)}/s</td>
                        <td><small>{t('off.lostLine', { r: pct(o.build.lostToResistPct), s: pct(o.build.lostToShieldPct), h: pct(o.build.lostToHealPct) })}</small></td>
                        <td><small>{t('off.aimLine', { h: n0(o.build.aimedHit), c: n0(o.build.aimedCast), a: n0(o.build.attacksHit), b: n0(o.build.attacks) })}</small></td>
                        <td>{n1(o.build.inRange)} s</td>
                        <td>{ttk(o.reference, meta.duration)}</td>
                        <td><b>{n0(o.index)}</b>{o.byRate && <small className="muted" title={t('off.byRateHelp')}> ({t('off.byRate')})</small>}</td>
                      </>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      {sweeps && (
        <div className="sweep-grid">
          {sweeps.map((s) => (
            <div key={s.key} className="sweep-card">
              <h5>{s.name}</h5>
              <ResponsiveContainer width="100%" height={160}>
                <LineChart data={s.points.map((p) => ({ v: p.v, idx: p.o?.index ?? null }))} margin={{ top: 6, right: 12, bottom: 4, left: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" opacity={0.3} />
                  <XAxis dataKey="v" />
                  <YAxis width={44} />
                  <Tooltip formatter={(v) => n0(v)} />
                  <Line type="monotone" dataKey="idx" stroke={SERIES_COLORS[0]} dot isAnimationActive={false} name={t('off.indexCol')} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          ))}
        </div>
      )}
      {perItem && (
        <>
          <h4>{t('off.perItem')} <Help text={t('off.perItemHelp')} /></h4>
          <p className="muted small">{t('off.mixUsed', { mix: mixLabel(perItem.mix) })}</p>
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>{t('off.item')}</th>
                  {report.targets.map((e) => <th key={e.key}>{names[e.key] ?? e.key}</th>)}
                </tr>
              </thead>
              <tbody>
                {perItem.held.map((h) => (
                  <tr key={h.id}>
                    <td><ItemIcon id={h.id} size={20} /> {h.name}</td>
                    {report.targets.map((e) => {
                      const full = perItem.by[`build|${e.key}`]?.index;
                      const cell = (k) => {
                        const v = perItem.by[`${k}|${h.id}|${e.key}`]?.index;
                        return full == null || v == null ? '—' : `${full - v >= 0 ? '+' : '−'}${n0(Math.abs(full - v))}`;
                      };
                      return (
                        <td key={e.key}>
                          <small title={t('off.cellHelp')}>{cell('sem')} · {cell('so_status')} · {cell('ouro')}</small>
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="muted small">{t('off.cellHelp')}</p>
        </>
      )}
    </section>
  );
}
