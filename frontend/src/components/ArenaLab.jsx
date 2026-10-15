import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import FightReport from './FightReport.jsx';
import { t } from '../i18n.js';
import { n0, n1 } from '../format.js';
import MinuteInput from './MinuteInput.jsx';
import { ArenaMap, frameAt, TEAM } from '../pages/Arena.jsx';

/**
 * The lab of the arena: a build at a minute against the ragdoll. Runs a combo of the champion's library (or one
 * written here) on the bench against several behaviors of the ragdoll, searches combos and calibrates their waits,
 * and keeps the library (origin, score, build and minute it was found for).
 */
const BEHAVIORS = ['parado', 'anda', 'foge', 'reage'];
const SLOTS = ['1', '2', '3', 'R'];
const UNTIL = ['', 'alvo_pousa', 'alvo_no_chao', 'marca_aplicada', 'acertaria', 'reconjuracao_acertaria', 'controle_restante', 'ataque_pronto', 'tempo'];
const STORE = 'ornn-planner-lab';
const champKey = (s) => (s ?? '').normalize('NFD').replace(/[^a-z]/gi, '').toLowerCase();

function loadLab() {
  try {
    return JSON.parse(localStorage.getItem(STORE)) ?? {};
  } catch {
    return {};
  }
}

const newDummy = () => ({ name: 'Tanque 100/100', health: 10000, armor: 100, magicResist: 100, radius: 80, moveSpeed: 350,
  behavior: 'parado', wearBuildId: null, wearMinute: null, spells: ['Flash', 'Purificar'], appliesEffects: false, hitEvery: 1, atMax: false });
const newBench = () => ({ mode: 'uma_vez', seconds: 12, distance: 300, behaviors: [...BEHAVIORS], aggregate: 'media', controlWeight: 400, markWeight: 150 });
const newStep = () => ({ action: 'habilidade:1', until: null, delta: 0, max: 2 });

export default function ArenaLab({ builds, scenario, names }) {
  const stored = useMemo(loadLab, []);
  const [buildId, setBuildId] = useState(stored.buildId ?? null);
  const [minute, setMinute] = useState(stored.minute ?? null);
  const [dummy, setDummy] = useState(stored.dummy ?? newDummy());
  const [bench, setBench] = useState(stored.bench ?? newBench());
  const [combo, setCombo] = useState(stored.combo ?? { name: 'meu combo', steps: [newStep()], start: [], abort: [], maxTime: 6, origin: 'manual' });
  const [library, setLibrary] = useState([]);
  const [dummies, setDummies] = useState({ presets: [], saved: [] });
  const [run, setRun] = useState(null);
  const [show, setShow] = useState('parado');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [job, setJob] = useState(null);
  const [runes, setRunes] = useState(null);
  const [search, setSearch] = useState({ beam: 64, maxSteps: 8, budget: 4000 });
  const [time, setTime] = useState(0);
  const [playing, setPlaying] = useState(false);

  const build = builds.find((b) => b.id === buildId);
  const champion = run?.champion ?? build?.unitCode;
  useEffect(() => {
    try {
      localStorage.setItem(STORE, JSON.stringify({ buildId, minute, dummy, bench, combo }));
    } catch {
      /* session only */
    }
  }, [buildId, minute, dummy, bench, combo]);
  useEffect(() => {
    api.get('/api/arena/dummies').then(setDummies).catch(() => {});
  }, []);
  const loadLibrary = () => api.get('/api/arena/combos').then(setLibrary).catch(() => {});
  useEffect(() => {
    loadLibrary();
  }, [buildId]);
  const mine = library.filter((c) => champion && champKey(c.champion) === champKey(champion));

  // playback of the fight shown
  const fight = run?.fight;
  useEffect(() => {
    if (!playing || !fight) return undefined;
    let last = performance.now();
    let raf;
    const tick = (now) => {
      const dt = (now - last) / 1000;
      last = now;
      setTime((tm) => {
        const next = tm + dt;
        if (next >= fight.duration) {
          setPlaying(false);
          return fight.duration;
        }
        return next;
      });
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [playing, fight]);

  const request = (extra = {}) => ({ buildId, minute, dummy, bench, combo, show, ...extra });
  const call = async (url, body) => {
    setBusy(true);
    setError(null);
    try {
      const r = await api.post(url, body);
      setRun(r);
      setTime(0);
      return r;
    } catch (e) {
      setError(e.message);
      return null;
    } finally {
      setBusy(false);
    }
  };
  const execute = (c = combo) => call('/api/arena/lab/run', request({ combo: c }));
  const calibrate = async () => {
    const r = await call('/api/arena/lab/calibrate', request());
    if (r?.combo) setCombo(r.combo);
  };
  const compareRunes = async () => {
    setBusy(true);
    setError(null);
    try {
      setRunes(await api.post('/api/arena/lab/compare-runes', request({ search: { ...search, budget: Math.min(search.budget, 3000), threads: 8 } })));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };
  const startSearch = async () => {
    setError(null);
    try {
      const j = await api.post('/api/arena/lab/search', request({ search: { ...search, threads: 8 } }));
      setJob(j);
    } catch (e) {
      setError(e.message);
    }
  };
  useEffect(() => {
    if (!job || !['queued', 'running'].includes(job.status)) return undefined;
    const timer = setTimeout(async () => {
      try {
        const j = await api.get(`/api/arena/lab/search/${job.id}`);
        setJob(j);
        if (j.status === 'done') loadLibrary();
      } catch (e) {
        setError(e.message);
      }
    }, 1000);
    return () => clearTimeout(timer);
  }, [job]);

  const toggle = async (c) => {
    await api.put(`/api/arena/combos/${c.id}/active`, { active: !c.active });
    loadLibrary();
  };
  const remove = async (c) => {
    if (!window.confirm(t('lab.deleteCombo', { name: c.name }))) return;
    await api.del(`/api/arena/combos/${c.id}`);
    loadLibrary();
  };
  const saveCombo = async () => {
    try {
      const body = { ...combo, champion: combo.champion ?? champion };
      const saved = combo.id ? await api.put(`/api/arena/combos/${combo.id}`, body) : await api.post('/api/arena/combos', body);
      setCombo(saved);
      loadLibrary();
    } catch (e) {
      setError(e.message);
    }
  };
  const saveDummy = async () => {
    const d = await api.post('/api/arena/dummies', dummy);
    setDummy(d);
    setDummies(await api.get('/api/arena/dummies'));
  };
  /** The scenario's timeline of a champion of this build, as combo steps (waits by time between orders). */
  const fromTimeline = () => {
    const i = scenario.fighters.findIndex((f) => f.buildId === buildId);
    if (i < 0) {
      setError(t('lab.noTimeline'));
      return;
    }
    const orders = scenario.actions.filter((a) => a.fighter === i).sort((a, b) => a.time - b.time);
    const steps = [];
    let last = null;
    for (const a of orders) {
      const action = a.type === 'ability' ? `habilidade:${a.slot}` : a.type === 'recast' ? `reconjurar:${a.slot}`
        : a.type === 'item' || a.type === 'spell' ? `ativo:${a.name}` : a.type === 'attack' ? 'atacar' : null;
      if (!action) continue;
      const gap = last == null ? 0 : a.time - last;
      steps.push(gap > 0.1 ? { action, until: 'tempo', delta: Math.round(gap * 100) / 100, max: gap + 0.5 } : { action, until: null, delta: 0, max: 2 });
      last = a.time;
    }
    if (!steps.length) {
      setError(t('lab.noTimeline'));
      return;
    }
    setCombo({ name: t('lab.fromTimelineName', { name: names[i] }), steps, start: [], abort: [], maxTime: Math.max(3, (last ?? 0) + 2), origin: 'manual' });
  };

  const metrics = run?.report?.byBehavior?.[show] ?? (run ? Object.values(run.report.byBehavior)[0] : null);
  const frame = frameAt(fight, time);
  const fightNames = fight ? fight.fighters.map((f) => f.champion) : [];

  return (
    <div className="arena-lab">
      <section className="panel">
        <h3>{t('lab.title')}</h3>
        <p className="muted">{t('lab.help')}</p>
        <div className="row">
          <label className="field grow">{t('lab.build')}
            <select value={buildId ?? ''} onChange={(e) => setBuildId(e.target.value ? Number(e.target.value) : null)}>
              <option value="">{t('lab.pickBuild')}</option>
              {builds.filter((b) => b.unitCode !== 'RAGDOLL').map((b) => <option key={b.id} value={b.id}>{b.unitCode} · {b.name}</option>)}
            </select>
          </label>
          <label className="field narrow">{t('lab.minute')}<MinuteInput value={minute} onChange={setMinute} placeholder={t('lab.endOfMatch')} /></label>
        </div>
        <DummyEditor dummy={dummy} onChange={(p) => setDummy({ ...dummy, ...p })} builds={builds} dummies={dummies} onSave={saveDummy} />
        <div className="row lab-bench">
          <label className="field">{t('lab.mode')}
            <select value={bench.mode} onChange={(e) => setBench({ ...bench, mode: e.target.value })}>
              <option value="uma_vez">{t('lab.mode.once')}</option>
              <option value="repetir">{t('lab.mode.repeat')}</option>
            </select>
          </label>
          {bench.mode === 'repetir' && (
            <label className="field narrow">{t('lab.seconds')}<input type="number" min={2} max={60} value={bench.seconds}
              onChange={(e) => setBench({ ...bench, seconds: Number(e.target.value) || 12 })} /></label>
          )}
          <label className="field narrow">{t('lab.distance')}<input type="number" min={0} max={1500} value={bench.distance}
            onChange={(e) => setBench({ ...bench, distance: Number(e.target.value) || 0 })} /></label>
          <div className="field">{t('lab.behaviors')}
            <div className="row">
              {BEHAVIORS.map((b) => (
                <label key={b} className="check"><input type="checkbox" checked={bench.behaviors.includes(b)}
                  onChange={(e) => setBench({ ...bench, behaviors: e.target.checked ? [...bench.behaviors, b] : bench.behaviors.filter((x) => x !== b) })} />
                  {t(`lab.behavior.${b}`)}</label>
              ))}
            </div>
          </div>
          <label className="field">{t('lab.aggregate')}
            <select value={bench.aggregate} onChange={(e) => setBench({ ...bench, aggregate: e.target.value })}>
              <option value="media">{t('lab.aggregate.mean')}</option>
              <option value="pior">{t('lab.aggregate.worst')}</option>
            </select>
          </label>
        </div>
      </section>

      <section className="panel">
        <div className="spread">
          <h3>{t('lab.combo')}</h3>
          <div className="row">
            <select value="" onChange={(e) => { const c = mine.find((x) => x.id === Number(e.target.value)); if (c) setCombo(c); }}>
              <option value="">{t('lab.fromLibrary', { n: mine.length })}</option>
              {mine.map((c) => <option key={c.id} value={c.id}>{c.name} ({c.origin}, {n0(c.score ?? 0)})</option>)}
            </select>
            <button className="link" onClick={fromTimeline} title={t('lab.fromTimelineTitle')}>{t('lab.fromTimeline')}</button>
          </div>
        </div>
        <ComboEditor combo={combo} onChange={setCombo} />
        <div className="row">
          <button className="primary" disabled={!buildId || busy} onClick={() => execute()}>{t('lab.run')}</button>
          <button disabled={!buildId || busy} onClick={calibrate} title={t('lab.calibrateTitle')}>{t('lab.calibrate')}</button>
          <button disabled={!buildId || busy} onClick={compareRunes} title={t('lab.runesTitle')}>{t('lab.runes')}</button>
          <button disabled={!champion} onClick={saveCombo}>{combo.id ? t('lab.saveCombo') : t('lab.addCombo')}</button>
          <span className="muted">|</span>
          <label className="inline">{t('lab.beam')}<input type="number" min={4} max={256} value={search.beam} style={{ width: 60 }}
            onChange={(e) => setSearch({ ...search, beam: Number(e.target.value) || 64 })} /></label>
          <label className="inline">{t('lab.steps')}<input type="number" min={1} max={12} value={search.maxSteps} style={{ width: 50 }}
            onChange={(e) => setSearch({ ...search, maxSteps: Number(e.target.value) || 8 })} /></label>
          <label className="inline">{t('lab.budget')}<input type="number" min={50} max={200000} step={500} value={search.budget} style={{ width: 80 }}
            onChange={(e) => setSearch({ ...search, budget: Number(e.target.value) || 4000 })} /></label>
          <button disabled={!buildId || (job && ['queued', 'running'].includes(job.status))} onClick={startSearch} title={t('lab.searchTitle')}>{t('lab.search')}</button>
          {busy && <small className="muted">{t('arena.running')}</small>}
        </div>
        {job && (
          <p className="muted">
            {t('lab.searchStatus', { status: job.status, n: job.evaluated, budget: job.budget, depth: job.depth, best: n0(job.best) })} — {job.message}
            {['queued', 'running'].includes(job.status) && <button className="link" onClick={() => api.del(`/api/arena/lab/search/${job.id}`).then(setJob)}>{t('lab.cancel')}</button>}
          </p>
        )}
        {job?.status === 'done' && job.found?.length > 0 && <FoundTable found={job.found} onTry={(c) => { setCombo(c); execute(c); }} />}
        {runes && <RuneTable rows={runes} onTry={(c) => { setCombo(c); execute(c); }} />}
        {error && <p className="error">{error}</p>}
      </section>

      {run && (
        <section className="panel arena-stage">
          <div className="spread">
            <div className="row">
              <button className="primary" onClick={() => setPlaying((p) => !p)}>{playing ? t('arena.pause') : t('arena.play')}</button>
              <button onClick={() => { setPlaying(false); setTime(0); }}>⏮</button>
              <strong className="arena-clock">{n1(time)}s</strong>
              <label className="inline">{t('lab.show')}
                <select value={show} onChange={(e) => { setShow(e.target.value); execute(); }}>
                  {bench.behaviors.map((b) => <option key={b} value={b}>{t(`lab.behavior.${b}`)}</option>)}
                </select>
              </label>
            </div>
            <strong>{t('lab.score', { v: n0(run.report.score) })}</strong>
          </div>
          <ArenaMap result={fight} frame={frame} paused={!playing} names={fightNames} pickPoint={null} onPick={() => {}} />
          <ComboTimeline fight={fight} metrics={metrics} time={time} onTime={(tm) => { setPlaying(false); setTime(tm); }} />
          {metrics && <MetricsCard m={metrics} />}
          <BehaviorTable report={run.report} />
        </section>
      )}
      {run && fight && (
        <FightReport result={fight} names={fightNames}
          onItemValues={() => api.post('/api/arena/lab/item-values', request({ combo: run.combo }))} />
      )}

      <section className="panel">
        <h3>{t('lab.library', { champion: champion ?? '—' })}</h3>
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th className="l">{t('lab.comboName')}</th><th>{t('lab.origin')}</th><th>{t('lab.scoreCol')}</th>
              <th className="l">{t('lab.foundFor')}</th><th>{t('lab.active')}</th><th /></tr></thead>
            <tbody>
              {mine.map((c) => (
                <tr key={c.id} className={c.active ? '' : 'muted'}>
                  <td className="l">{c.name}</td>
                  <td>{c.origin === 'descoberto' ? t('lab.origin.found') : t('lab.origin.manual')}</td>
                  <td>{c.score != null ? n0(c.score) : '—'}</td>
                  <td className="l small">{c.build ? `${c.build}${c.minute != null ? ` · ${n1(c.minute)} min` : ''}` : '—'}</td>
                  <td><input type="checkbox" checked={c.active} onChange={() => toggle(c)} /></td>
                  <td className="row">
                    <button className="link" onClick={() => { setCombo(c); execute(c); }}>{t('lab.test')}</button>
                    <button className="link" onClick={() => setCombo(c)}>{t('lab.edit')}</button>
                    <button className="link danger" onClick={() => remove(c)}>{t('lab.delete')}</button>
                  </td>
                </tr>
              ))}
              {mine.length === 0 && <tr><td colSpan={6} className="muted">{t('lab.emptyLibrary')}</td></tr>}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function DummyEditor({ dummy, onChange, builds, dummies, onSave }) {
  const pick = (v) => {
    if (!v) return;
    const [kind, k] = v.split(':');
    const d = kind === 'p' ? dummies.presets[Number(k)] : dummies.saved.find((x) => x.id === Number(k));
    if (d) onChange({ ...newDummy(), ...d, id: kind === 'p' ? null : d.id });
  };
  const num = (k) => (
    <label className="field narrow">{t(`lab.dummy.${k}`)}
      <input type="number" value={dummy[k]} onChange={(e) => onChange({ [k]: Number(e.target.value) || 0 })} />
    </label>
  );
  return (
    <details className="report-details" open>
      <summary>{t('lab.dummy', { name: dummy.name })}</summary>
      <div className="row">
        <select value="" onChange={(e) => pick(e.target.value)} aria-label={t('lab.dummy.pick')}>
          <option value="">{t('lab.dummy.pick')}</option>
          {dummies.presets.map((d, i) => <option key={`p${i}`} value={`p:${i}`}>{d.name}</option>)}
          {dummies.saved.map((d) => <option key={d.id} value={`s:${d.id}`}>{d.name}</option>)}
        </select>
        <label className="field">{t('lab.dummy.name')}<input value={dummy.name ?? ''} onChange={(e) => onChange({ name: e.target.value })} /></label>
        {num('health')}{num('armor')}{num('magicResist')}{num('radius')}{num('moveSpeed')}
      </div>
      <div className="row">
        <label className="field grow">{t('lab.dummy.wear')}
          <select value={dummy.wearBuildId ?? ''} onChange={(e) => onChange({ wearBuildId: e.target.value ? Number(e.target.value) : null })}>
            <option value="">{t('lab.dummy.noBuild')}</option>
            {builds.map((b) => <option key={b.id} value={b.id}>{b.unitCode} · {b.name}</option>)}
          </select>
        </label>
        {dummy.wearBuildId != null && (
          <label className="field narrow">{t('lab.minute')}<MinuteInput value={dummy.wearMinute} onChange={(v) => onChange({ wearMinute: v })} /></label>
        )}
        <label className="check" title={t('lab.dummy.effectsTitle')}>
          <input type="checkbox" checked={dummy.appliesEffects} onChange={(e) => onChange({ appliesEffects: e.target.checked })} />{t('lab.dummy.effects')}
        </label>
        {dummy.appliesEffects && (
          <>
            <label className="check"><input type="checkbox" checked={dummy.atMax} onChange={(e) => onChange({ atMax: e.target.checked })} />{t('lab.dummy.atMax')}</label>
            {!dummy.atMax && (
              <label className="inline">{t('lab.dummy.every')}<input type="number" min={0.2} step={0.1} value={dummy.hitEvery} style={{ width: 60 }}
                onChange={(e) => onChange({ hitEvery: Number(e.target.value) || 1 })} /></label>
            )}
          </>
        )}
        <button className="link" onClick={onSave}>{t('lab.dummy.save')}</button>
      </div>
    </details>
  );
}

function ComboEditor({ combo, onChange }) {
  const setStep = (i, p) => onChange({ ...combo, steps: combo.steps.map((s, k) => (k === i ? { ...s, ...p } : s)) });
  const move = (i, d) => {
    const steps = [...combo.steps];
    const j = i + d;
    if (j < 0 || j >= steps.length) return;
    [steps[i], steps[j]] = [steps[j], steps[i]];
    onChange({ ...combo, steps });
  };
  const actions = [...SLOTS.map((s) => `habilidade:${s}`), ...SLOTS.map((s) => `reconjurar:${s}`), 'atacar', 'ativo:Flash',
    'mover:alvo', 'mover:longe', 'esperar'];
  return (
    <div className="lab-combo">
      <div className="row">
        <label className="field grow">{t('lab.comboName')}<input value={combo.name ?? ''} onChange={(e) => onChange({ ...combo, name: e.target.value })} /></label>
        <label className="field narrow">{t('lab.maxTime')}<input type="number" min={0.5} max={20} step={0.5} value={combo.maxTime}
          onChange={(e) => onChange({ ...combo, maxTime: Number(e.target.value) || 6 })} /></label>
        <small className="muted">{combo.origin === 'descoberto' ? t('lab.origin.found') : t('lab.origin.manual')}{combo.id ? ` · #${combo.id}` : ''}</small>
      </div>
      <ol className="lab-steps">
        {combo.steps.map((s, i) => (
          <li key={i} className="row">
            <select value={s.until ?? ''} onChange={(e) => setStep(i, { until: e.target.value || null })} aria-label={t('lab.until')}>
              {UNTIL.map((u) => <option key={u} value={u}>{u ? t(`lab.until.${u}`) : t('lab.until.none')}</option>)}
            </select>
            {s.until && !['marca_aplicada', 'acertaria', 'reconjuracao_acertaria', 'ataque_pronto'].includes(s.until) && (
              <input type="number" step={0.05} value={s.delta} style={{ width: 70 }} aria-label="Δ"
                onChange={(e) => setStep(i, { delta: Number(e.target.value) || 0 })} title={t('lab.deltaTitle')} />
            )}
            <span className="muted">→</span>
            <select value={s.action} onChange={(e) => setStep(i, { action: e.target.value })} aria-label={t('lab.action')}>
              {actions.includes(s.action) ? null : <option value={s.action}>{s.action}</option>}
              {actions.map((a) => <option key={a} value={a}>{t(`lab.action.${a.split(':')[0]}`, { s: a.split(':')[1] ?? '' })}</option>)}
            </select>
            <button className="link" onClick={() => move(i, -1)} aria-label="↑">↑</button>
            <button className="link" onClick={() => move(i, 1)} aria-label="↓">↓</button>
            <button className="link danger" onClick={() => onChange({ ...combo, steps: combo.steps.filter((_, k) => k !== i) })}>✕</button>
          </li>
        ))}
      </ol>
      <button className="link" onClick={() => onChange({ ...combo, steps: [...combo.steps, newStep()] })}>{t('lab.addStep')}</button>
      {(combo.start?.length > 0 || combo.abort?.length > 0) && (
        <p className="muted small">
          {combo.start?.length > 0 && `${t('lab.startWhen')}: ${combo.start.map((c) => `${c.fact} ${c.op} ${c.value}`).join(' e ')}. `}
          {combo.abort?.length > 0 && `${t('lab.abortWhen')}: ${combo.abort.map((c) => `${c.fact} ${c.op} ${c.value}`).join(' ou ')}.`}
        </p>
      )}
    </div>
  );
}

/** The combo's steps and the ragdoll's marks and control along the fight; click to jump. */
function ComboTimeline({ fight, metrics, time, onTime }) {
  const ref = useRef(null);
  if (!fight) return null;
  const W = 800;
  const end = Math.max(1, fight.duration);
  const x = (s) => (s / end) * W;
  const dummy = fight.fighters[1];
  const spans = dummy?.controlSpans ?? [];
  const brittle = [];
  let on = null;
  fight.frames.forEach((f) => {
    const has = f.units[1]?.marks?.some((m) => m === 'Frágil');
    if (has && on == null) on = f.t;
    if (!has && on != null) {
      brittle.push([on, f.t]);
      on = null;
    }
  });
  if (on != null) brittle.push([on, end]);
  return (
    <div className="lab-timeline">
      <svg ref={ref} viewBox={`0 0 ${W} 64`} width="100%" height={64}
        onClick={(e) => { const r = ref.current.getBoundingClientRect(); onTime(((e.clientX - r.left) / r.width) * end); }}>
        <text x={0} y={10} className="lab-tl-label">{t('lab.tl.steps')}</text>
        {(metrics?.steps ?? []).map((s, i) => (
          <g key={i}>
            <line x1={x(s.t)} x2={x(s.t)} y1={12} y2={26} stroke={TEAM[0]} strokeWidth={2} />
            <title>{`${n1(s.t)}s: ${s.text}`}</title>
          </g>
        ))}
        <text x={0} y={36} className="lab-tl-label">{t('lab.tl.control')}</text>
        {spans.map((s, i) => <rect key={i} x={x(s[0])} y={38} width={Math.max(1, x(s[1]) - x(s[0]))} height={8} fill={TEAM[1]} />)}
        <text x={0} y={56} className="lab-tl-label">{t('lab.tl.marks')}</text>
        {brittle.map((s, i) => <rect key={i} x={x(s[0])} y={58} width={Math.max(1, x(s[1]) - x(s[0]))} height={5} fill="#f0c75e" />)}
        <line x1={x(time)} x2={x(time)} y1={0} y2={64} stroke="currentColor" strokeWidth={1} />
      </svg>
    </div>
  );
}

function MetricsCard({ m }) {
  const rows = [
    [t('lab.m.damage'), n0(m.damage)], [t('lab.m.dps'), n0(m.dps)], [t('lab.m.duration'), `${n1(m.duration)}s`],
    [t('lab.m.control'), `${n1(m.control)}s`], [t('lab.m.free'), `${n1(m.longestFree)}s`],
    [t('lab.m.marks'), `${n0(m.marksConsumed)} / ${n0(m.marksApplied)}${m.marksOverwritten ? ` (${t('lab.m.overwritten', { n: m.marksOverwritten })})` : ''}`],
    [t('lab.m.chained'), `${n0(m.chained)}${m.overlapped ? ` · ${t('lab.m.overlapped', { n: m.overlapped })}` : ''}`],
    [t('lab.m.repeat'), `${n1(m.repeatIn)}s`],
    [t('lab.m.outcome'), m.done ? t('arena.combos.done') : t('arena.combos.aborted', { why: m.reason ?? '' })],
  ];
  return (
    <div className="lab-metrics">
      <dl>{rows.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl>
      <div className="table-wrap">
        <table className="data">
          <thead><tr><th className="l">{t('arena.source')}</th><th>{t('report.col.damage')}</th></tr></thead>
          <tbody>
            {Object.entries(m.bySource).sort((a, b) => b[1] - a[1]).map(([k, v]) => <tr key={k}><td className="l">{k}</td><td>{n0(v)}</td></tr>)}
            {Object.entries(m.byType).map(([k, v]) => <tr key={k} className="muted"><td className="l">{t('lab.m.type', { type: k })}</td><td>{n0(v)}</td></tr>)}
          </tbody>
        </table>
      </div>
      {Object.keys(m.procs).length > 0 && <p className="small muted">{Object.entries(m.procs).map(([k, v]) => `${k} ×${v}`).join(' · ')}</p>}
    </div>
  );
}

function BehaviorTable({ report }) {
  const rows = Object.values(report.byBehavior);
  return (
    <div className="table-wrap">
      <table className="data">
        <thead><tr><th className="l">{t('lab.behaviorCol')}</th><th>{t('lab.m.damage')}</th><th>{t('lab.m.control')}</th>
          <th>{t('lab.m.marks')}</th><th>{t('lab.m.duration')}</th><th>{t('lab.scoreCol')}</th><th className="l">{t('lab.m.outcome')}</th></tr></thead>
        <tbody>
          {rows.map((m) => (
            <tr key={m.behavior}>
              <td className="l">{t(`lab.behavior.${m.behavior}`)}</td><td>{n0(m.damage)}</td><td>{n1(m.control)}s</td>
              <td>{n0(m.marksConsumed)}</td><td>{n1(m.duration)}s</td><td>{n0(m.score)}</td>
              <td className={`l small ${m.done ? '' : 'warn'}`}>{m.done ? t('arena.combos.done') : m.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function FoundTable({ found, onTry }) {
  return (
    <div className="table-wrap">
      <table className="data">
        <thead><tr><th>#</th><th className="l">{t('lab.comboName')}</th><th>{t('lab.scoreCol')}</th><th>{t('lab.m.damage')}</th>
          <th>{t('lab.m.control')}</th><th>{t('lab.m.marks')}</th><th className="l">{t('lab.diff')}</th><th /></tr></thead>
        <tbody>
          {found.map((f, i) => {
            const m = Object.values(f.report.byBehavior)[0];
            return (
              <tr key={i}>
                <td>{i + 1}</td><td className="l">{f.combo.name}</td><td>{n0(f.score)}</td><td>{n0(m.damage)}</td><td>{n1(m.control)}s</td>
                <td>{n0(m.marksConsumed)}</td>
                <td className="l small">{Object.keys(f.diff).length ? Object.entries(f.diff).map(([k, v]) => `${Number(k) + 1}: ${v}`).join(' · ') : '—'}</td>
                <td><button className="link" onClick={() => onTry(f.combo)}>{t('lab.test')}</button></td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function RuneTable({ rows, onTry }) {
  return (
    <div className="table-wrap">
      <table className="data">
        <thead><tr><th className="l">{t('lab.rune')}</th><th>{t('lab.runeCombo')}</th><th>{t('lab.runeProcs')}</th>
          <th>{t('lab.runeBest')}</th><th>{t('lab.runeProcs')}</th><th className="l">{t('lab.runeBestSteps')}</th></tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>
              <td className="l">{r.rune ?? t('lab.noRune')}</td>
              <td>{n0(r.combo.score)}</td><td>{r.rune ? `${n0(r.procs)} · ${n0(r.runeDamage)}` : '—'}</td>
              <td>{r.bestReport ? n0(r.bestReport.score) : '—'}</td><td>{r.rune && r.bestReport ? `${n0(r.bestProcs)} · ${n0(r.bestRuneDamage)}` : '—'}</td>
              <td className="l small">{r.best ? <button className="link" onClick={() => onTry(r.best)}>{r.best.name}</button> : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
