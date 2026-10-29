import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { n0, n1 } from '../format.js';
import EffectPicker, { applied } from '../components/EffectPicker.jsx';
import ItemIcon from '../components/ItemIcon.jsx';
import MinuteInput from '../components/MinuteInput.jsx';
import SeenByReport, { Bars, MetricControls, ehp } from '../components/SeenByReport.jsx';
import { reportJobs, runJobs, weightedIndex } from '../effectiveness.js';
import { ReferencePicker, ttd, useTestNames } from '../components/EffectivenessReport.jsx';

/**
 * Ranking of builds: the builds of a folder (or picked by hand) at the same minute, either under chosen effects (the
 * planner's "visão sob efeitos": effective health, what each effect takes from each build, what each item holds) or in
 * a saved arena scenario (each build in its champion's slot: the outcome and the weight of each item). Builds run one
 * by one, so the rows fill in as they come.
 */
const STORE = 'ornn-planner-ranking';
const load = () => {
  try {
    return JSON.parse(localStorage.getItem(STORE) || '{}');
  } catch {
    return {};
  }
};

export default function Ranking({ items }) {
  const saved = useMemo(load, []);
  const [mode, setMode] = useState(saved.mode ?? 'effects');
  const [chosen, setChosen] = useState(saved.chosen ?? []);
  const [share, setShare] = useState(saved.share ?? 50);
  const [metric, setMetric] = useState(saved.metric ?? 'mixed');
  const [minute, setMinute] = useState(saved.minute ?? 15);
  const [scenarioId, setScenarioId] = useState(saved.scenarioId ?? '');
  const [folder, setFolder] = useState(saved.folder ?? 'all');
  const [excluded, setExcluded] = useState(saved.excluded ?? []);
  const [folders, setFolders] = useState([]);
  const [builds, setBuilds] = useState([]);
  const [scenarios, setScenarios] = useState([]);
  const [rows, setRows] = useState([]);
  const [ran, setRan] = useState(null);
  const [busy, setBusy] = useState(null);
  const [open, setOpen] = useState(null);
  const [error, setError] = useState(null);
  const [effKind, setEffKind] = useState(saved.effKind ?? 'basico');
  const [effRef, setEffRef] = useState({ kind: 'proprio' });
  const stop = useRef(false);
  const idOf = useMemo(() => new Map((items ?? []).map((i) => [i.name, i.id])), [items]);

  useEffect(() => {
    Promise.all([api.get('/api/folders'), api.get('/api/builds'), api.get('/api/arena/scenarios')])
      .then(([f, b, s]) => {
        setFolders(f);
        setBuilds(b.filter((x) => x.unitCode !== 'RAGDOLL'));
        setScenarios(s);
      }).catch((e) => setError(e.message));
  }, []);
  useEffect(() => {
    try {
      localStorage.setItem(STORE, JSON.stringify({ mode, chosen, share, metric, minute, scenarioId, folder, excluded, effKind }));
    } catch {
      /* storage unavailable */
    }
  }, [mode, chosen, share, metric, minute, scenarioId, folder, excluded, effKind]);

  const inFolder = builds.filter((b) => folder === 'all' || String(b.folderId) === String(folder));
  const picked = inFolder.filter((b) => !excluded.includes(b.id));
  const toggle = (id) => setExcluded((x) => (x.includes(id) ? x.filter((y) => y !== id) : [...x, id]));
  const all = (on) => setExcluded((x) => (on ? x.filter((id) => !inFolder.some((b) => b.id === id))
    : [...new Set([...x, ...inFolder.map((b) => b.id)])]));

  const run = async () => {
    stop.current = false;
    setError(null);
    setRows([]);
    setOpen(null);
    const fx = applied(chosen);
    const report = mode === 'effectiveness' ? await api.get(`/api/effect/reports/${effKind}`).catch(() => null) : null;
    setRan({ mode, metric, report, sources: [...new Set(fx.map((e) => e.source))], scenario: scenarios.find((s) => String(s.id) === String(scenarioId)) });
    for (let k = 0; k < picked.length && !stop.current; k++) {
      const b = picked[k];
      setBusy({ done: k, total: picked.length, name: b.name });
      let row = { build: b };
      try {
        if (mode === 'effectiveness') {
          row.outcomes = await runJobs({ buildId: b.id, minute, reference: effRef }, reportJobs(report, false), { size: 12, stop: () => stop.current });
          row.index = weightedIndex(report, row.outcomes);
        } else if (mode === 'effects') {
          row.seen = await api.post('/api/lab/seen-by', { buildId: b.id, minute, effects: fx, physicalShare: share / 100, series: false, items: true });
        } else {
          row.test = await api.post('/api/lab/test', { buildId: b.id, minute, scenarioId: Number(scenarioId) });
        }
      } catch (e) {
        row.error = e.message;
      }
      setRows((r) => [...r, row]);
    }
    setBusy(null);
  };

  const ready = mode === 'effects' || mode === 'effectiveness' ? true : scenarioId !== '';
  return (
    <div className="main ranking">
      <section className="panel">
        <h2>{t('rank.title')}</h2>
        <p className="muted small" style={{ marginTop: 0 }}>{t('rank.help')}</p>
        <div className="chips" role="tablist">
          {['effects', 'scenario', 'effectiveness'].map((m) => (
            <button key={m} role="tab" aria-selected={mode === m} className={mode === m ? 'active' : ''} onClick={() => setMode(m)}>{t(`rank.mode.${m}`)}</button>
          ))}
        </div>
        <div className="rank-setup">
          <div>
            <h4>{mode === 'effects' ? t('rank.effects') : mode === 'effectiveness' ? t('rank.effectiveness') : t('rank.scenario')}</h4>
            {mode === 'effectiveness' ? (
              <>
                <div className="chips" role="tablist">
                  {['basico', 'completo'].map((k) => (
                    <button key={k} role="tab" aria-selected={effKind === k} className={effKind === k ? 'active' : ''} onClick={() => setEffKind(k)}>{t(`efx.kind.${k}`)}</button>
                  ))}
                </div>
                <ReferencePicker value={effRef} onChange={setEffRef} builds={builds} folders={folders} />
                <p className="muted small">{t('rank.effectivenessHelp')}</p>
              </>
            ) : mode === 'effects' ? (
              <>
                <EffectPicker chosen={chosen} onChange={setChosen} />
                <MetricControls metric={metric} onMetric={setMetric} share={share} onShare={setShare} />
              </>
            ) : (
              <>
                <select value={scenarioId} onChange={(e) => setScenarioId(e.target.value)} aria-label={t('rank.scenario')}>
                  <option value="">—</option>
                  {scenarios.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
                <p className="muted small">{t('rank.scenarioHelp')}</p>
              </>
            )}
            <label className="inline">{t('ptest.minute')}<MinuteInput value={minute} onChange={setMinute} placeholder="15:00" style={{ width: 70 }} /></label>
          </div>
          <div>
            <h4>{t('rank.builds', { n: picked.length, of: inFolder.length })}</h4>
            <div className="row">
              <select value={folder} onChange={(e) => setFolder(e.target.value)} aria-label={t('rank.folder')}>
                <option value="all">{t('rank.allFolders')}</option>
                {folders.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
              </select>
              <button className="link" onClick={() => all(true)}>{t('rank.checkAll')}</button>
              <button className="link" onClick={() => all(false)}>{t('rank.checkNone')}</button>
            </div>
            <ul className="rank-checks">
              {inFolder.map((b) => (
                <li key={b.id}>
                  <label><input type="checkbox" checked={!excluded.includes(b.id)} onChange={() => toggle(b.id)} /> <span className="muted">{b.unitCode}</span> {b.name}</label>
                </li>
              ))}
            </ul>
          </div>
        </div>
        <div className="row">
          <button className="primary" onClick={run} disabled={!!busy || !ready || picked.length === 0}>{t('rank.run')}</button>
          {busy && (
            <>
              <progress value={busy.done} max={busy.total} />
              <small className="muted">{t('rank.progress', { done: busy.done, total: busy.total, name: busy.name })}</small>
              <button onClick={() => { stop.current = true; }}>{t('rank.stop')}</button>
            </>
          )}
        </div>
        {error && <p className="error">{error}</p>}
      </section>
      {ran?.mode === 'effects' && rows.length > 0 && (
        <EffectsResult rows={rows} sources={ran.sources} metric={metric} idOf={idOf} open={open} setOpen={setOpen} />
      )}
      {ran?.mode === 'effectiveness' && ran.report && rows.length > 0 && <EffectivenessResult rows={rows} report={ran.report} />}
      {ran?.mode === 'scenario' && rows.length > 0 && (
        <ScenarioResult rows={rows} scenario={ran.scenario} open={open} setOpen={setOpen} />
      )}
    </div>
  );
}

/** Background of a heat cell: stronger for a larger share (0..1). */
const heat = (rgb, share, cap = 0.75) => ({ background: `rgba(${rgb}, ${Math.min(cap, 0.08 + Math.abs(share) * 1.6)})` });
const RED = '255, 120, 110';
const GREEN = '111, 207, 151';

function EffectsResult({ rows, sources, metric, idOf, open, setOpen }) {
  const ok = rows.filter((r) => r.seen);
  const ranked = [...ok].sort((a, b) => ehp(b.seen.now, metric) - ehp(a.seen.now, metric));
  const top = Math.max(1, ...ok.map((r) => ehp(r.seen.plain, metric)));
  const best = (r) => [...(r.seen.items ?? [])].sort((a, b) => ehp(b, metric) - ehp(a, metric))[0];
  return (
    <>
      <section className="panel">
        <h3>{t('rank.ranking', { metric: t(`fx.metric.${metric}`) })}</h3>
        <p className="muted small">{t('rank.rankingHelp')}</p>
        <ol className="rank-list">
          {ranked.map((r, k) => {
            const now = ehp(r.seen.now, metric);
            const plain = ehp(r.seen.plain, metric);
            const it = best(r);
            return (
              <li key={r.build.id} className={open === r.build.id ? 'open' : ''}>
                <button className="rank-row" onClick={() => setOpen(open === r.build.id ? null : r.build.id)} aria-expanded={open === r.build.id}>
                  <span className="rank-pos">{k + 1}</span>
                  <span className="rank-name"><span className="muted">{r.build.unitCode}</span> {r.build.name}</span>
                  <span className="rank-bar" title={t('rank.barTitle')}>
                    <span className="rank-bar-plain" style={{ width: `${(plain / top) * 100}%` }} />
                    <span className="rank-bar-now" style={{ width: `${(now / top) * 100}%` }} />
                  </span>
                  <span className="rank-value"><strong>{n0(now)}</strong> <small className="muted">{t('rank.from', { v: n0(plain) })}</small></span>
                  <span className="rank-best" title={t('rank.bestTitle')}>
                    {it && <><ItemIcon id={it.itemId} size={20} /> <small>+{n0(ehp(it, metric))}</small></>}
                  </span>
                </button>
                {open === r.build.id && (
                  <div className="rank-detail">
                    <SeenByReport result={r.seen} metric={metric} idOf={idOf} />
                  </div>
                )}
              </li>
            );
          })}
        </ol>
        <Failed rows={rows} />
      </section>
      {sources.length > 0 && (
        <section className="panel">
          <h3>{t('rank.heatEffects')}</h3>
          <p className="muted small">{t('rank.heatEffectsHelp')}</p>
          <div className="table-scroll">
            <table className="data heat">
              <thead>
                <tr>
                  <th className="l">{t('rank.build')}</th>
                  {sources.map((s) => (
                    <th key={s} title={s}>{idOf.get(s) != null ? <ItemIcon id={idOf.get(s)} size={22} /> : null}<span className="heat-head">{s}</span></th>
                  ))}
                  <th>{t('rank.allEffects')}</th>
                </tr>
              </thead>
              <tbody>
                {ranked.map((r) => {
                  const plain = ehp(r.seen.plain, metric);
                  const lost = plain - ehp(r.seen.now, metric);
                  return (
                    <tr key={r.build.id}>
                      <td className="l"><span className="muted">{r.build.unitCode}</span> {r.build.name}</td>
                      {sources.map((s) => {
                        const c = r.seen.contributions.find((x) => x.source === s);
                        const v = c ? ehp(c, metric) : 0;
                        return <td key={s} style={heat(RED, plain > 0 ? v / plain : 0)} title={`${s}: −${n0(v)}`}>−{pctOf(v, plain)}</td>;
                      })}
                      <td style={heat(RED, plain > 0 ? lost / plain : 0)}><strong>−{pctOf(lost, plain)}</strong></td>
                    </tr>
                  );
                })}
                <tr className="heat-total">
                  <td className="l">{t('rank.average')}</td>
                  {sources.map((s) => {
                    const avg = mean(ok.map((r) => {
                      const c = r.seen.contributions.find((x) => x.source === s);
                      const plain = ehp(r.seen.plain, metric);
                      return c && plain > 0 ? ehp(c, metric) / plain : 0;
                    }));
                    return <td key={s} style={heat(RED, avg)}><strong>−{Math.round(avg * 100)}%</strong></td>;
                  })}
                  <td />
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      )}
      <section className="panel">
        <h3>{t('rank.heatItems')}</h3>
        <p className="muted small">{t('rank.heatItemsHelp')}</p>
        <div className="rank-items">
          {ranked.map((r) => {
            const now = ehp(r.seen.now, metric);
            const its = [...(r.seen.items ?? [])].sort((a, b) => ehp(b, metric) - ehp(a, metric));
            return (
              <div key={r.build.id} className="rank-items-row">
                <span className="rank-name"><span className="muted">{r.build.unitCode}</span> {r.build.name}</span>
                <span className="rank-chips">
                  {its.map((i, k) => {
                    const v = ehp(i, metric);
                    return (
                      <span key={i.itemId} className={`rank-chip${k === 0 ? ' best' : ''}`} style={heat(GREEN, now > 0 ? v / now : 0, 0.35)} title={`${i.item}: ${v >= 0 ? '+' : '−'}${n0(Math.abs(v))}`}>
                        <ItemIcon id={i.itemId} size={22} />
                        <small>{v >= 0 ? '+' : '−'}{pctOf(Math.abs(v), now)}</small>
                      </span>
                    );
                  })}
                </span>
              </div>
            );
          })}
        </div>
      </section>
    </>
  );
}

function ScenarioResult({ rows, scenario, open, setOpen }) {
  const ok = rows.filter((r) => r.test);
  // won first, then the health left on its side, then the time alive, then the damage dealt
  const outcome = (x) => (x.winner === x.team ? 2 : x.winner < 0 ? 1 : 0);
  const ranked = [...ok].sort((a, b) => outcome(b.test) - outcome(a.test) || (b.test.side ?? 0) - (a.test.side ?? 0)
    || (b.test.alive ?? 0) - (a.test.alive ?? 0) || b.test.dealt - a.test.dealt);
  const topAlive = Math.max(1, ...ok.map((r) => r.test.alive ?? 0));
  const byHold = (x) => [...x.items].sort((a, b) => (flips(x, b) - flips(x, a)) || holds(x, b) - holds(x, a));
  return (
    <>
      <section className="panel">
        <h3>{t('rank.scenarioRanking', { name: scenario?.name ?? '' })}</h3>
        <p className="muted small">{t('rank.scenarioRankingHelp')}</p>
        <ol className="rank-list">
          {ranked.map((r, k) => {
            const x = r.test;
            const won = x.winner === x.team;
            const it = byHold(x)[0];
            return (
              <li key={r.build.id} className={open === r.build.id ? 'open' : ''}>
                <button className="rank-row scenario" onClick={() => setOpen(open === r.build.id ? null : r.build.id)} aria-expanded={open === r.build.id}>
                  <span className="rank-pos">{k + 1}</span>
                  <span className="rank-name"><span className="muted">{r.build.unitCode}</span> {r.build.name}</span>
                  <span className={`rank-outcome ${won ? 'good' : x.winner < 0 ? '' : 'warn'}`}>
                    {won ? t('ptest.won', { s: n1(x.duration) }) : x.winner < 0 ? t('ptest.draw', { s: n1(x.duration) }) : t('ptest.lost', { s: n1(x.duration) })}
                  </span>
                  <span className="rank-bar" title={t('rank.aliveTitle', { s: n1(x.alive ?? 0) })}>
                    <span className="rank-bar-now" style={{ width: `${((x.alive ?? 0) / topAlive) * 100}%` }} />
                  </span>
                  <span className="rank-value"><small>{t('ptest.dealtTaken', { dealt: n0(x.dealt), taken: n0(x.taken) })}</small></span>
                  <span className="rank-best" title={t('rank.bestScenarioTitle')}>
                    {it && <><ItemIcon id={it.itemId} size={20} /> <small>{holdText(x, it)}</small></>}
                  </span>
                </button>
                {open === r.build.id && (
                  <div className="rank-detail">
                    <h5>{t(won ? 'rank.itemsSide' : 'rank.itemsAlive')}</h5>
                    <Bars rows={byHold(x).map((w) => ({
                      key: w.itemId,
                      label: w.item,
                      icon: <ItemIcon id={w.itemId} size={18} />,
                      value: holds(x, w),
                      text: [flips(x, w) ? t('rank.flips') : null, t('ptest.dAlive', { v: signed(w.alive, 1) }),
                        t('ptest.dSide', { v: signed(w.side) }), t('ptest.dDealt', { v: signed(w.dealt) })].filter(Boolean).join(' · '),
                    }))} />
                  </div>
                )}
              </li>
            );
          })}
        </ol>
        <Failed rows={rows} />
      </section>
      <section className="panel">
        <h3>{t('rank.heatItemsScenario')}</h3>
        <p className="muted small">{t('rank.heatItemsScenarioHelp')}</p>
        <div className="rank-items">
          {ranked.map((r) => {
            const x = r.test;
            const items = byHold(x);
            const top = Math.max(1e-9, ...items.map((w) => Math.abs(holds(x, w))));
            return (
              <div key={r.build.id} className="rank-items-row">
                <span className="rank-name"><span className="muted">{r.build.unitCode}</span> {r.build.name}</span>
                <span className="rank-chips">
                  {items.map((w, k) => (
                    <span key={w.itemId} className={`rank-chip${k === 0 ? ' best' : ''}`} style={heat(GREEN, holds(x, w) / top / 2, 0.35)} title={w.item}>
                      <ItemIcon id={w.itemId} size={22} />
                      <small>{holdText(x, w)}</small>
                    </span>
                  ))}
                </span>
              </div>
            );
          })}
        </div>
      </section>
    </>
  );
}

/** The builds that could not run (no gold yet, a champion the scenario does not have). */
function Failed({ rows }) {
  const bad = rows.filter((r) => r.error);
  if (!bad.length) return null;
  return (
    <details className="small">
      <summary className="muted">{t('rank.failed', { n: bad.length })}</summary>
      <ul>{bad.map((r) => <li key={r.build.id}>{r.build.name}: {r.error}</li>)}</ul>
    </details>
  );
}

/**
 * What an item holds in a scenario: when the build's side wins, the health its side keeps with it (minus without);
 * when it loses, every variant ends at 0 health, so the seconds it keeps the build alive.
 */
const holds = (x, w) => (x.winner === x.team ? w.side : w.alive);
const holdText = (x, w) => (x.winner === x.team ? signed(w.side) : `${signed(w.alive, 1)}s`);
const flips = (x, w) => (w.winnerWithout != null && w.winnerWithout !== x.winner ? 1 : 0);
const mean = (xs) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0);
const pctOf = (v, of) => (of > 0 ? `${Math.round((v / of) * 100)}%` : '—');
const signed = (v, digits = 0) => (v == null ? '—' : `${v > 0 ? '+' : ''}${digits ? n1(v) : n0(v)}`);

/** Builds by their weighted effectiveness index, and the heat map scenario × build (green above the reference). */
function EffectivenessResult({ rows, report }) {
  const names = useTestNames();
  const sorted = [...rows].sort((a, b) => (b.index ?? -1) - (a.index ?? -1));
  const ok = sorted.filter((r) => r.outcomes);
  const max = Math.max(100, ...ok.flatMap((r) => r.outcomes.map((o) => (o?.index != null && Number.isFinite(o.index) ? o.index : 0))));
  const cell = (v) => {
    if (v == null) return {};
    const s = Math.log(Math.max(1, v) / 100) / Math.log(Math.max(1.01, max / 100));
    return v >= 100 ? heat(GREEN, s * 0.45) : heat(RED, Math.min(1, (100 - v) / 100) * 0.45);
  };
  return (
    <section className="panel">
      <h3>{t('rank.effectivenessTitle', { kind: t(`efx.kind.${report.kind}`) })}</h3>
      <div className="table-wrap">
        <table className="data heatmap">
          <thead>
            <tr>
              <th>#</th><th>{t('rank.build')}</th><th>{t('efx.weightedShort')}</th>
              {report.tests.map((e) => <th key={e.key} title={names[e.key] ?? e.key}>{names[e.key] ?? e.key}</th>)}
            </tr>
          </thead>
          <tbody>
            {sorted.map((r, i) => (
              <tr key={r.build.id}>
                <td>{i + 1}</td>
                <td>{r.build.name}</td>
                <td><strong>{r.index == null ? '—' : n0(r.index)}</strong></td>
                {r.error ? <td colSpan={report.tests.length} className="error">{r.error}</td> : report.tests.map((e, k) => {
                  const o = r.outcomes?.[k];
                  return (
                    <td key={e.key} style={cell(o?.index)} title={o?.build ? `${t('efx.col.ttd')} ${ttd(o.build)} · ${t('efx.col.ref')} ${ttd(o.reference)}` : o?.error}>
                      {o?.index == null ? '—' : n0(o.index)}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <small className="muted">{t('efx.estimatedNote')}</small>
    </section>
  );
}
