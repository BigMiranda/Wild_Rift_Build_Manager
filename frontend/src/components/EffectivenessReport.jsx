import { useEffect, useMemo, useRef, useState } from 'react';
import { BuildLink } from './BuildPreview.jsx';
import { useTask } from '../tasks.js';
import { CartesianGrid, Line, LineChart, ReferenceDot, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { SERIES_COLORS, mmss, n0, n1 } from '../format.js';
import { READ_MODES, breakPoint, effectMeta, gain, indexText, overReference, reportJobs, runJobs, weakPoints, weightedGrade, weightedIndex } from '../effectiveness.js';
import ItemIcon from './ItemIcon.jsx';
import WholeDelta, { fightDelta } from './WholeDelta.jsx';
import { archetypeLabel, archetypeTitle, useArchetypes } from '../archetypes.js';
import { openInArena } from './TestInArena.jsx';
import { CandidatePicker } from './ShopPicker.jsx';

/** Names of the scenarios a report may hold (presets and the user's), by key. */
export function useTestNames() {
  const [names, setNames] = useState({});
  useEffect(() => {
    let live = true;
    Promise.all([effectMeta(), api.get('/api/effect/tests')]).then(([m, user]) => {
      if (!live) return;
      const n = {};
      m.presets.forEach((p) => { n[p.key] = p.name; });
      user.forEach((u) => { n[`u:${u.id}`] = u.name; });
      setNames(n);
    }).catch(() => {});
    return () => { live = false; };
  }, []);
  return names;
}

/** What the index is measured against: the same champion without items, another build, or a folder's mean. */
export function ReferencePicker({ value, onChange, builds = [], folders = [] }) {
  const kind = value?.kind ?? 'proprio';
  return (
    <span className="row eff-ref">
      <label className="inline">{t('efx.reference')}
        <select value={kind} onChange={(e) => onChange({ kind: e.target.value, buildId: null, folderId: null })}>
          <option value="proprio">{t('efx.ref.proprio')}</option>
          <option value="build">{t('efx.ref.build')}</option>
          <option value="pasta">{t('efx.ref.pasta')}</option>
        </select>
      </label>
      {kind === 'build' && (
        <select value={value.buildId ?? ''} onChange={(e) => onChange({ ...value, buildId: e.target.value ? Number(e.target.value) : null })}
          aria-label={t('efx.ref.build')}>
          <option value="">—</option>
          {builds.filter((b) => b.unitCode !== 'RAGDOLL').map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
        </select>
      )}
      {kind === 'pasta' && (
        <select value={value.folderId ?? ''} onChange={(e) => onChange({ ...value, folderId: e.target.value ? Number(e.target.value) : null })}
          aria-label={t('efx.ref.pasta')}>
          <option value="">—</option>
          {folders.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
        </select>
      )}
    </span>
  );
}

/** The time to die, or "> duration" when the build did not die in the fight. */
export const ttd = (m) => (m == null ? '—' : `${m.died ? '' : '> '}${n1(m.timeToDeath)} s`);

/** Damage taken net of healing and shields per second; below 0, what it heals beyond what it takes. */
export const netRate = (m) => (m == null ? '—' : `${n0(m.netRate)}/s`);

/** The score as text, with its grade, for tables. */
export const indexLabel = (o) => indexText(o, t, n1);

/** A weighted score as text, with its grade. */
export const weightedLabel = (value, outcomes) => {
  if (value == null) return '—';
  const g = weightedGrade(value, outcomes);
  return g ? `${n1(value)} · ${t(`efx.grade.${g}`)}` : n1(value);
};

/** "Como ler": the reading mode of a per-item reading, with what each one means in its title. */
export function ReadModePicker({ value, onChange }) {
  return (
    <label className="inline" title={t(`efx.read.${value}.help`)}>{t('efx.read')}
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        {READ_MODES.map((m) => <option key={m} value={m}>{t(`efx.read.${m}`)}</option>)}
      </select>
    </label>
  );
}

/** A horizontal bar of a score (0 = the reference, 100 = loses nothing). */
function IndexBar({ outcome }) {
  const value = outcome?.index;
  if (value == null) return <span className="muted">—</span>;
  const w = Math.max(2, Math.min(100, value / 1.1));
  return (
    <span className="eff-bar" title={t('efx.indexTitle', { v: n1(value) })}>
      <span className={value >= 50 ? 'up' : 'down'} style={{ width: `${w}%` }} />
      <strong>{indexLabel(outcome)}</strong>
    </span>
  );
}

/** The basic report: one row per scenario (its measures and index bar), the weighted index and the weak points. */
export function BasicResult({ report, outcomes, names }) {
  const mean = weightedIndex(report, outcomes);
  const weak = weakPoints(report, outcomes);
  return (
    <>
      <div className="table-wrap">
        <table className="data eff-table">
          <thead>
            <tr>
              <th>{t('efx.col.test')}</th><th>{t('efx.col.weight')}</th><th>{t('efx.col.index')}</th><th>{t('efx.col.ttd')}</th>
              <th>{t('efx.col.ref')}</th><th title={t('efx.col.netTitle')}>{t('efx.col.net')}</th><th>{t('efx.col.ehp')}</th><th>{t('efx.col.mitigated')}</th><th>{t('efx.col.shield')}</th>
              <th>{t('efx.col.heal')}</th><th>{t('efx.col.dealt')}</th><th title={t('efx.col.cutTitle')}>{t('efx.col.cut')}</th><th>{t('efx.col.cc')}</th><th>{t('efx.col.team')}</th>
            </tr>
          </thead>
          <tbody>
            {report.tests.map((e, i) => {
              const o = outcomes[i];
              const m = o?.build;
              return (
                <tr key={e.key}>
                  <td>{o?.testName ?? names[e.key] ?? e.key}</td>
                  <td>{n1(e.weight ?? 1)}</td>
                  <td>{o?.error ? <span className="error">{o.error}</span> : <IndexBar outcome={o} />}</td>
                  <td>{ttd(m)}</td>
                  <td>{ttd(o?.reference)}</td>
                  <td>{netRate(m)}</td>
                  <td>{m ? n0(m.effectiveHealth) : '—'}</td>
                  <td>{m ? `${n0(m.mitigatedPct)}%` : '—'}</td>
                  <td>{m ? n0(m.shieldAbsorbed) : '—'}</td>
                  <td>{m ? <>{n0(m.healed)}{m.healLost > 0.5 && <small className="muted"> (−{n0(m.healLost)})</small>}</> : '—'}</td>
                  <td title={m ? t('efx.returned', { thorns: n0(m.returnedThorns), auras: n0(m.returnedAuras), hits: n0(m.returnedHits) }) : undefined}>
                    {m ? <>{n0(m.dealt)}{m.returnedThorns > 0.5 && <small className="muted"> ({t('efx.returnedThorns', { v: n0(m.returnedThorns) })})</small>}</> : '—'}
                  </td>
                  <td>{m ? <>{n0(m.enemyHealCut)}{m.enemyHealed + m.enemyHealCut > 0.5 && <small className="muted"> / {n0(m.enemyHealed + m.enemyHealCut)}</small>}</> : '—'}</td>
                  <td>{m ? `${n1(m.controlApplied)} s` : '—'}</td>
                  <td>{m?.absorbedShare != null ? t('efx.teamCell', { share: n0(m.absorbedShare), alive: n1(m.alliesAlive) }) : '—'}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p>
        <strong>{t('efx.weighted', { v: weightedLabel(mean, outcomes) })}</strong>{' '}
        <small className="muted">{t('efx.estimatedNote')}</small>
      </p>
      <p className="eff-weak">
        {weak.length === 0 ? t('efx.noWeak')
          : t('efx.weak', { list: weak.map((x) => `${x.o.testName} (${n1(x.o.index)})`).join(', ') })}
      </p>
    </>
  );
}

/** The builds compared side by side: the index and time to die per scenario, the best of each row marked. */
export function ComparativeResult({ report, series, names }) {
  return (
    <div className="table-wrap">
      <p className="muted small">{t('efx.compareRefNote')}</p>
      <table className="data eff-table">
        <thead>
          <tr>
            <th>{t('efx.col.test')}</th>
            {series.map((s) => <th key={s.key} style={{ color: s.color }}>{s.buildId ? <BuildLink id={s.buildId} name={s.name} /> : s.name}</th>)}
          </tr>
        </thead>
        <tbody>
          {report.tests.map((e, i) => {
            const cells = series.map((s) => s.outcomes[i]);
            const best = Math.max(...cells.map((o) => (o?.index != null && Number.isFinite(o.index) ? o.index : -Infinity)));
            return (
              <tr key={e.key}>
                <td>{cells.find((o) => o?.testName)?.testName ?? names[e.key] ?? e.key}</td>
                {cells.map((o, k) => (
                  <td key={series[k].key} className={o?.index === best ? 'best' : ''}>
                    {o?.error ? <span className="error">{o.error}</span> : <>{indexLabel(o)} <small className="muted">{ttd(o?.build)}</small></>}
                  </td>
                ))}
              </tr>
            );
          })}
          <tr>
            <td><strong>{t('efx.weightedShort')}</strong></td>
            {series.map((s) => {
              const v = weightedIndex(report, s.outcomes);
              return <td key={s.key}><strong>{weightedLabel(v, s.outcomes)}</strong></td>;
            })}
          </tr>
        </tbody>
      </table>
    </div>
  );
}

/**
 * The complete report's sweeps: a line chart of the index per dimension, one line per build (`series`: {key, name, color,
 * outcomes, thin}), with the break points. A `thin` series is a build only in the charts: a light line, no break point
 * of its own, and its name in the legend, where a click hides or shows any build in every chart.
 */
export function SweepResult({ report, series: all }) {
  const [hidden, setHidden] = useState(() => new Set());
  const series = all.filter((b) => !hidden.has(b.key));
  const many = all.length > 8;
  const toggle = (key) => setHidden((h) => {
    const next = new Set(h);
    if (!next.delete(key)) next.add(key);
    return next;
  });
  const k0 = report.tests.length;
  let k = k0;
  return (
    <>
    {all.length > 1 && (
      <div className="eff-legend">
        {all.map((b) => (
          <button key={b.key} className={hidden.has(b.key) ? 'off' : ''} aria-pressed={!hidden.has(b.key)} onClick={() => toggle(b.key)}>
            <span className="dot" style={{ background: b.color }} />{b.name}
          </button>
        ))}
        {all.some((b) => b.thin) && (
          <button className="link" onClick={() => setHidden(hidden.size ? new Set() : new Set(all.filter((b) => b.thin).map((b) => b.key)))}>
            {t(hidden.size ? 'efx.legend.showAll' : 'efx.legend.onlyMain')}
          </button>
        )}
      </div>
    )}
    <div className="eff-sweeps">
      {(report.sweeps ?? []).map((s) => {
        const from = k;
        k += s.stages.length;
        const pts = s.stages.map((v, i) => {
          const p = { stage: Number(v) };
          series.forEach((b) => {
            const o = b.outcomes[from + i];
            p[b.key] = o?.index != null && Number.isFinite(o.index) ? Math.round(o.index * 10) / 10 : null;
          });
          return p;
        });
        const breaks = series.filter((b) => !b.thin).map((b) => ({ b, br: breakPoint(pts.map((p) => p.stage), pts.map((p) => p[b.key])) }));
        return (
          <div key={s.key} className="eff-sweep">
            <h5>{s.name}</h5>
            <div style={{ width: '100%', height: many ? 220 : 150 }}>
              <ResponsiveContainer>
                <LineChart data={pts} margin={{ top: 8, right: 12, bottom: 0, left: -12 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#2a3240" />
                  <XAxis dataKey="stage" tick={{ fontSize: 11 }} />
                  <YAxis tick={{ fontSize: 11 }} />
                  <Tooltip formatter={(v, n) => [n1(v), n]} labelFormatter={(l) => `${s.name}: ${l}`} itemSorter={(x) => -(x.value ?? 0)}
                    contentStyle={{ fontSize: 11, padding: '4px 8px' }} itemStyle={{ padding: 0 }} wrapperStyle={{ zIndex: 5 }} />
                  {series.map((b) => (
                    <Line key={b.key} type="monotone" dataKey={b.key} name={b.name} stroke={b.color} dot={!b.thin} isAnimationActive={false} connectNulls
                      strokeWidth={b.thin ? 1 : 2} strokeOpacity={b.thin ? 0.55 : 1} />
                  ))}
                  {breaks.filter((x) => x.br).map((x) => <ReferenceDot key={x.b.key} x={x.br.stage} y={x.br.to} r={5} fill="#d64545" stroke="none" />)}
                </LineChart>
              </ResponsiveContainer>
            </div>
            {breaks.map(({ b, br }) => (
              <small key={b.key} className={`eff-break ${br ? 'error' : 'muted'}`}>
                {series.length > 1 && <span style={{ color: b.color }}>{b.name}: </span>}
                {br ? t('efx.break', { stage: br.stage, from: n1(br.from), to: n1(br.to) }) : t('efx.noBreak')}
              </small>
            ))}
          </div>
        );
      })}
    </div>
    </>
  );
}

/** Progress of a run with its stop button. */
export function Progress({ busy, onStop }) {
  if (!busy) return null;
  return (
    <span className="row">
      <progress value={busy.done} max={busy.total} />
      <small className="muted">{t('efx.progress', { done: busy.done, total: busy.total, s: n1((busy.ms ?? 0) / 1000) })}</small>
      <button onClick={onStop}>{t('rank.stop')}</button>
    </span>
  );
}

const mixLabel = (mix) => Object.entries(mix ?? {}).filter(([, v]) => v > 0)
  .map(([k, v]) => `${Math.round(v * 100)}% ${k === 'Max Health' ? t('efx.mix.hp') : k === 'Armor' ? t('efx.mix.ar') : t('efx.mix.mr')}`)
  .join(' + ');

/**
 * The planner's "Efetividade" (moment tab): the basic or complete report of the build at the minute, the
 * effectiveness of each item (with × without, × only its flat stats, × the same gold in pure stats) and the
 * comparison of candidates for one purchase.
 */
export function EffectivenessPanel({ objectives, build, buildId, name, minute, items, builds, folders, compare = [], compareKey = '' }) {
  const names = useTestNames();
  const [kind, setKind] = useState('basico');
  const [report, setReport] = useState(null);
  const [ref, setRef] = useState({ kind: 'proprio' });
  const [readMode, setReadMode] = useState('fixo');
  const saved = useArchetypes(compareKey);
  const [archetype, setArchetype] = useState(null);
  useEffect(() => {
    if (!build) return undefined;
    let live = true;
    api.post('/api/archetypes', { build, minute }).then((a) => { if (live) setArchetype(a); }).catch(() => {});
    return () => { live = false; };
  }, [build, minute]);
  // Side by side, a build of another archetype is not a fair comparison: the screen says so.
  const mixed = compare.filter((c) => archetype && saved[c.id] && saved[c.id].key !== archetype.key)
    .map((c) => `${c.name} (${archetypeLabel(saved[c.id])})`);
  const [outcomes, setOutcomes] = useState(null);
  const [others, setOthers] = useState([]);
  const [chartOnly, setChartOnly] = useState([]);
  const [chartBuilds, setChartBuilds] = useState('todas');
  const [skipped, setSkipped] = useState(0);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);
  const [perItem, setPerItem] = useState(null);
  const [cmp, setCmp] = useState({ slot: '', candidates: [], test: 'atirador_alimentado', rows: null });
  const stop = useRef(false);
  const at = minute == null ? null : Math.round(minute * 100) / 100;
  const base = { build, buildId: build ? null : buildId, minute: at, reference: ref, objectives };
  const itemsById = useMemo(() => new Map((items ?? []).map((i) => [i.id, i])), [items]);
  useTask('effectiveness-panel', !!busy, { label: t('tasks.effectiveness', { name: name ?? '' }), done: busy?.done, total: busy?.total, path: '/relatorios/efetividade' });

  useEffect(() => {
    let live = true;
    api.get(`/api/effect/reports/${kind}`).then((r) => { if (live) setReport(r); }).catch((e) => setError(e.message));
    return () => { live = false; };
  }, [kind]);
  useEffect(() => { setOutcomes(null); setOthers([]); setChartOnly([]); setPerItem(null); setCmp((c) => ({ ...c, rows: null })); }, [at, kind, ref.kind, ref.buildId, ref.folderId, compareKey, chartBuilds]);

  const go = async (jobs, done) => {
    stop.current = false;
    setError(null);
    setBusy({ done: 0, total: jobs.length, ms: 0 });
    try {
      const out = await runJobs({ ...base, readMode }, jobs, {
        onProgress: (d, total, ms) => setBusy({ done: d, total, ms }), stop: () => stop.current,
      });
      done(out);
    } catch (e) {
      setError(e.message);
    }
    setBusy(null);
  };

  /** Saved builds that go only to the charts: all of them, or those of the same champion or archetype. */
  const extraBuilds = () => {
    if (kind !== 'completo' || chartBuilds === 'marcadas') return [];
    const taken = new Set([buildId, ...compare.map((c) => c.id)]);
    const open = builds.find((b) => b.id === buildId);
    return builds.filter((b) => !taken.has(b.id) && b.unitCode !== 'RAGDOLL'
      && (chartBuilds === 'todas' || (chartBuilds === 'campeao' ? b.unitCode === (open?.unitCode ?? build?.unitCode) : saved[b.id]?.key === archetype?.key)));
  };

  /**
   * The report of the open build, then of each build compared, with one progress bar; then the sweeps of the builds that
   * go only to the charts. Their scenarios are left out (the charts do not read them), and each joins the charts as it ends.
   */
  const runReport = async () => {
    const jobs = reportJobs(report, kind === 'completo');
    const sweepJobs = jobs.slice(report.tests.length);
    const extra = extraBuilds();
    const total = jobs.length * (1 + compare.length) + sweepJobs.length * extra.length;
    let before = 0;
    let spent = 0;
    const one = async (b, list = jobs, size = 6) => {
      let last = 0;
      const out = await runJobs({ ...b, minute: at, reference: ref }, list, {
        size, onProgress: (d, _, ms) => { last = ms; setBusy({ done: before + d, total, ms: spent + ms }); }, stop: () => stop.current,
      });
      before += list.length;
      spent += last;
      return out;
    };
    stop.current = false;
    setError(null);
    setOutcomes(null);
    setOthers([]);
    setChartOnly([]);
    setSkipped(0);
    setBusy({ done: 0, total, ms: 0 });
    try {
      setOutcomes(await one(build ? { build } : { buildId }));
      const rest = [];
      for (const c of compare) {
        if (stop.current) break;
        rest.push({ key: `b${c.id}`, buildId: c.id, name: c.name, color: c.color, outcomes: await one(await c.request()) });
        setOthers([...rest]);
      }
      const lines = [];
      for (let i = 0; i < extra.length && !stop.current; i++) {
        const b = extra[i];
        try {
          const out = await one({ buildId: b.id }, sweepJobs, 24);
          lines.push({ key: `c${b.id}`, name: b.name, color: `hsl(${(i * 137.5) % 360} 65% 62%)`, thin: true,
            outcomes: [...new Array(report.tests.length).fill(null), ...out] });
          setChartOnly([...lines]);
        } catch {
          before += sweepJobs.length;   // a build that cannot run yet (no gold or XP per minute) stays out of the charts
          setSkipped((n) => n + 1);
        }
      }
    } catch (e) {
      setError(e.message);
    }
    setBusy(null);
  };
  // Side by side, every build is measured against the open build's reference: each one's own would not compare.
  const onOpenRef = (list) => list.map((o, i) => overReference(o, outcomes[i]?.reference));
  const series = outcomes ? [{ key: 'open', name: name ?? '', color: SERIES_COLORS[0], outcomes },
    ...others.map((s) => ({ ...s, outcomes: onOpenRef(s.outcomes) }))] : [];
  const chartSeries = outcomes ? [...series, ...chartOnly.map((s) => ({ ...s, outcomes: onOpenRef(s.outcomes) }))] : [];

  const runPerItem = async () => {
    setError(null);
    let held;
    try {
      held = await api.post('/api/effect/items', base);
    } catch (e) {
      setError(e.message);
      return;
    }
    const meta = await effectMeta();
    const jobs = [];
    for (const e of report.tests) {
      jobs.push({ testKey: e.key });
      for (const it of held) {
        jobs.push({ testKey: e.key, variant: { kind: 'sem', itemId: it.id } });
        jobs.push({ testKey: e.key, variant: { kind: 'so_status', itemId: it.id } });
        meta.mixes.forEach((mix) => jobs.push({ testKey: e.key, variant: { kind: 'ouro', itemId: it.id, mix } }));
      }
    }
    go(jobs, (out) => {
      const rows = [];
      let k = 0;
      for (const e of report.tests) {
        const withIt = out[k++];
        for (const it of held) {
          const without = out[k++];
          const flat = out[k++];
          let best = null;
          let bestMix = null;
          for (const mix of meta.mixes) {
            const g = out[k++];
            if (g?.build && (!best || g.build.durability > best.build.durability)) {
              best = g;
              bestMix = mix;
            }
          }
          const w = withIt?.build?.durability;
          rows.push({
            item: it, test: withIt?.testName ?? names[e.key] ?? e.key,
            value: gain(w, without?.build?.durability), passive: gain(w, flat?.build?.durability),
            gold: gain(best?.build?.durability, without?.build?.durability), mix: bestMix,
          });
        }
      }
      setPerItem({ held, rows, readMode });
    });
  };

  const runCompare = () => {
    if (cmp.slot === '' || cmp.candidates.length === 0) return;
    const jobs = [];
    for (const e of report.tests.filter((x) => cmp.test === 'all' || x.key === cmp.test)) {
      for (const c of cmp.candidates) {
        jobs.push({ testKey: e.key, variant: { kind: 'candidato', slot: Number(cmp.slot), candidateId: c } });
      }
    }
    const ran = report.tests.filter((x) => cmp.test === 'all' || x.key === cmp.test);
    // the build as it is in the same scenarios: the fight's Δ of each candidate
    ran.forEach((e) => jobs.push({ testKey: e.key }));
    const slot = Number(cmp.slot);
    // the whole build's change by each swap: the same function as the quick comparison (BuildDeltaService)
    Promise.all(cmp.candidates.map((c) => api.post('/api/quick/delta', {
      build, buildId: build ? null : buildId, change: { slot, itemId: c }, minute: at,
    }).catch(() => null))).then((deltas) => setCmp((x) => ({ ...x, deltas })));
    go(jobs, (out) => setCmp((x) => ({
      ...x, rows: out.slice(0, ran.length * x.candidates.length), asIs: out.slice(ran.length * x.candidates.length), readMode, ranTests: ran,
    })));
  };

  const openFight = async (job) => {
    try {
      const sc = await api.post('/api/effect/scenario', { ...base, jobs: [job] });
      openInArena({ ...sc, id: null, actions: [], teams: [null, null] });
    } catch (e) {
      setError(e.message);
    }
  };

  const steps = (build?.steps ?? []).map((s, i) => ({ ...s, i })).filter((s) => s.kind !== 'moment' && s.itemId != null && !s.sell);
  return (
    <section className="panel eff-panel">
      <h3>{t('efx.panelTitle', { time: at == null ? '—' : mmss(at) })}</h3>
      <p className="muted small" style={{ marginTop: 0 }}>{t('efx.panelHelp')}</p>
      {archetype && <p className="small" title={archetypeTitle(archetype)}>{t('arch.of', { name: archetypeLabel(archetype) })}</p>}
      {mixed.length > 0 && <p className="warn small">{t('arch.mixed', { list: mixed.join(', ') })}</p>}
      <div className="row">
        <div className="chips" role="tablist">
          {['basico', 'completo'].map((k) => (
            <button key={k} role="tab" aria-selected={kind === k} className={kind === k ? 'active' : ''} onClick={() => setKind(k)}>{t(`efx.kind.${k}`)}</button>
          ))}
        </div>
        <ReferencePicker value={ref} onChange={setRef} builds={builds} folders={folders} />
        {kind === 'completo' && (
          <label className="inline" title={t('efx.chartBuildsTitle')}>{t('efx.chartBuilds')}
            <select value={chartBuilds} onChange={(e) => setChartBuilds(e.target.value)}>
              {['todas', 'campeao', 'arquetipo', 'marcadas'].map((k) => <option key={k} value={k}>{t(`efx.chartBuilds.${k}`)}</option>)}
            </select>
          </label>
        )}
        <button className="primary" onClick={runReport} disabled={!report || !!busy || !build}>{t('efx.run')}</button>
        <Progress busy={busy} onStop={() => { stop.current = true; }} />
      </div>
      {error && <p className="error">{error}</p>}
      {compare.length > 0 && <p className="muted small">{t('efx.comparing', { list: compare.map((c) => c.name).join(', ') })}</p>}
      {report && outcomes && (
        <>
          {others.length > 0 && (
            <>
              <h4>{t('efx.comparative')}</h4>
              <p className="muted small">{t('efx.comparativeHelp', { name: name ?? '' })}</p>
              <ComparativeResult report={report} series={series} names={names} />
              <h4>{t('efx.detailOf', { name: name ?? '' })}</h4>
            </>
          )}
          <BasicResult report={report} outcomes={outcomes} names={names} />
          {buildId && report.tests[0] && (
            <button className="link" onClick={() => openFight({ testKey: report.tests[0].key })}>{t('efx.openFirst', { name: outcomes[0]?.testName ?? '' })}</button>
          )}
          {kind === 'completo' && chartOnly.length > 0 && <p className="muted small">{t('efx.chartsHelp', { name: name ?? '', n: chartOnly.length })}</p>}
          {kind === 'completo' && skipped > 0 && <p className="muted small">{t('efx.chartsSkipped', { n: skipped })}</p>}
          {kind === 'completo' && <SweepResult report={report} series={chartSeries} />}
        </>
      )}

      <h4>{t('efx.perItem')}</h4>
      <p className="muted small">{t('efx.perItemHelp')}</p>
      <div className="row">
        <ReadModePicker value={readMode} onChange={setReadMode} />
        <button onClick={runPerItem} disabled={!report || !!busy || !build}>{t('efx.perItemRun')}</button>
      </div>
      {perItem && (
        <div className="table-wrap">
          <small className="muted">{t('efx.read.used', { mode: t(`efx.read.${perItem.readMode}`) })}</small>
          <table className="data eff-table">
            <thead><tr><th>{t('efx.col.item')}</th><th>{t('efx.col.test')}</th><th>{t('efx.col.value')}</th><th>{t('efx.col.passive')}</th><th>{t('efx.col.gold')}</th></tr></thead>
            <tbody>
              {perItem.rows.map((r, i) => (
                <tr key={i}>
                  <td><ItemIcon id={r.item.id} size={18} /> {r.item.name}</td>
                  <td>{r.test}</td>
                  <td>{pctCell(r.value)}</td>
                  <td>{pctCell(r.passive)}</td>
                  <td>{pctCell(r.gold)} <small className="muted">{mixLabel(r.mix)}</small></td>
                </tr>
              ))}
            </tbody>
          </table>
          <ul className="eff-sentences">
            {perItem.rows.filter((r) => r.passive != null && Math.abs(r.passive) >= 5).slice(0, 12).map((r, i) => (
              <li key={i}>{t('efx.sentence', { item: r.item.name, passive: signed(r.passive), test: r.test, gold: signed(r.gold), mix: mixLabel(r.mix) })}</li>
            ))}
          </ul>
        </div>
      )}

      <h4>{t('efx.compare')}</h4>
      <p className="muted small">{t('efx.compareHelp')}</p>
      <div className="row">
        <label className="inline">{t('efx.slot')}
          <select value={cmp.slot} onChange={(e) => setCmp({ ...cmp, slot: e.target.value, rows: null, deltas: null })}>
            <option value="">—</option>
            {steps.map((s) => <option key={s.i} value={s.i}>#{s.i + 1} {itemsById.get(s.itemId)?.name ?? s.itemId}</option>)}
          </select>
        </label>
        <label className="inline">{t('efx.col.test')}
          <select value={cmp.test} onChange={(e) => setCmp({ ...cmp, test: e.target.value, rows: null })}>
            <option value="all">{t('efx.allTests')}</option>
            {(report?.tests ?? []).map((e) => <option key={e.key} value={e.key}>{names[e.key] ?? e.key}</option>)}
          </select>
        </label>
        <CandidatePicker build={build} slot={cmp.slot === '' ? null : Number(cmp.slot)} items={items} value={cmp.candidates} label={t('efx.addCandidate')}
          onChange={(candidates) => setCmp({ ...cmp, candidates, rows: null, deltas: null })} />
        <button onClick={runCompare} disabled={!report || !!busy || cmp.slot === '' || cmp.candidates.length === 0}>{t('efx.compareRun')}</button>
      </div>
      <div className="chips">
        {cmp.candidates.map((c) => (
          <button key={c} className="chip-item" onClick={() => setCmp({ ...cmp, candidates: cmp.candidates.filter((x) => x !== c), rows: null, deltas: null })}
            title={t('efx.removeCandidate')}>
            <ItemIcon id={c} size={18} /> {itemsById.get(c)?.name ?? c} ✕
          </button>
        ))}
      </div>
      {cmp.rows && <small className="muted">{t('efx.read.used', { mode: t(`efx.read.${cmp.readMode}`) })}</small>}
      {cmp.rows && <CompareResult cmp={cmp} itemsById={itemsById} />}
    </section>
  );
}

const signed = (v) => (v == null ? '—' : `${v >= 0 ? '+' : '−'}${n0(Math.abs(v))}%`);
const pctCell = (v) => <span className={v == null ? 'muted' : v > 0.5 ? 'pos' : v < -0.5 ? 'neg' : ''}>{signed(v)}</span>;

/** Index of each candidate per scenario, the best of each scenario marked. */
function CompareResult({ cmp, itemsById }) {
  const tests = cmp.ranTests ?? [];
  const n = cmp.candidates.length;
  return (
    <div className="table-wrap">
      <table className="data eff-table">
        <thead><tr><th>{t('efx.col.test')}</th>{cmp.candidates.map((c) => <th key={c}><ItemIcon id={c} size={18} /> {itemsById.get(c)?.name ?? c}</th>)}</tr></thead>
        <tbody>
          {tests.map((e, ti) => {
            const row = cmp.rows.slice(ti * n, ti * n + n);
            const best = Math.max(...row.map((o) => o?.index ?? -Infinity));
            return (
              <tr key={e.key}>
                <td>{row[0]?.testName ?? e.key}</td>
                {row.map((o, ci) => (
                  <td key={ci} className={o?.index === best ? 'best' : ''} title={o?.held === false ? t('efx.notHeld') : undefined}>
                    {o?.error ? <span className="error">{o.error}</span> : <>{indexLabel(o)} <small className="muted">{ttd(o?.build)}</small>{o?.held === false && ' ⚠'}</>}
                  </td>
                ))}
              </tr>
            );
          })}
          {cmp.deltas && (
            <tr className="wd-row">
              <td>{t('wd.title')}</td>
              {cmp.candidates.map((c, ci) => (
                <td key={c}>
                  <WholeDelta delta={cmp.deltas[ci]} compact title=" "
                    fight={fightDelta(tests.map((_, ti) => cmp.rows[ti * n + ci]), cmp.asIs)} />
                </td>
              ))}
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
