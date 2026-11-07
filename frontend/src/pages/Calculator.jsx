import { useEffect, useRef, useState } from 'react';
import { BuildLink } from '../components/BuildPreview.jsx';
import { useTask } from '../tasks.js';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { mmss, n0, n1 } from '../format.js';
import ItemPicker from '../components/ShopPicker.jsx';
import ItemIcon from '../components/ItemIcon.jsx';
import WholeDelta from '../components/WholeDelta.jsx';
import Help from '../components/Help.jsx';
import { useBuild } from '../buildContext.jsx';
import { navigate } from '../router.js';

const STORE = 'ornn-planner-calculator';
const DEFAULT_MINUTES = '1, 2, 3, 4, 8, 12, 16, 20';

const initial = () => {
  const def = {
    buildId: '', target: 'basico', opponentBuildId: '', tanksOnly: false, forbidden: [], required: [], minutes: DEFAULT_MINUTES,
    length: 5, beam: 3, budget: 600, tree: false, k: 6, depth: 3, mode: 'moderado', noBoots: false, runes: false, measure: 'classe',
  };
  try {
    const saved = { ...def, ...JSON.parse(localStorage.getItem(STORE) ?? '{}') };
    // the old default left the early minutes out: an item ready before 8:00 had no importance
    if (saved.minutes === '8, 12, 16, 20') saved.minutes = DEFAULT_MINUTES;
    return saved;
  } catch {
    return def;
  }
};

const names = (l) => (l?.names ?? []).join(' → ');

/** The tree in a sentence: who leads, and why the next ones lose. */
function verdict(rows) {
  if (!rows?.length) return null;
  const parts = rows.slice(0, 3).map((r, i) => {
    const first = r.ours.names[0];
    if (i === 0) return t('calc.verdict.first', { item: first, pct: n0(r.winRate * 100) });
    return t('calc.verdict.next', { item: first, pos: i + 1, against: r.worstAnswer?.names?.[0] ?? '—' });
  });
  return parts.join('; ');
}

/** Red to green over the matrix's own range. */
function heat(v, top) {
  const share = top > 0 ? Math.max(-1, Math.min(1, v / top)) : 0;
  return share >= 0 ? `rgba(80,170,110,${0.15 + share * 0.5})` : `rgba(220,90,80,${0.15 - share * 0.5})`;
}

/** A step of an order: its icon, the minute it is ready and, for a component, what closes it. */
export function StepChip({ step, diff }) {
  const kind = step.kind === 'component' ? t('calc.kind.component') : step.kind === 'boots' || step.kind === 'boots3' ? t('calc.kind.boots') : null;
  const title = [step.name, kind, step.readyAt != null ? t('calc.readyAt', { t: mmss(step.readyAt) }) : null,
    step.kind === 'component' ? (step.closedBy ? t('calc.closedBy', { item: step.closedBy }) : t('calc.neverClosed')) : null].filter(Boolean).join(' · ');
  return (
    <span className={`step-chip kind-${step.kind}${diff ? ' diff' : ''}`} title={title}>
      <ItemIcon id={step.id} size={26} />
      <small>{step.readyAt != null ? mmss(step.readyAt) : '—'}</small>
      {kind && <small className="step-kind">{kind}</small>}
    </span>
  );
}

/** The steps of an order in a row of chips. */
export function StepRow({ line, against }) {
  const base = new Set(against?.order ?? []);
  return (
    <span className="step-row">
      {(line.steps?.length ? line.steps : line.order.map((id, i) => ({ id, name: line.names[i], kind: 'item' }))).map((s, i) => (
        <StepChip key={`${s.id}-${i}`} step={s} diff={against && !base.has(s.id)} />
      ))}
    </span>
  );
}

/** The build calculator: the best order against a target, and the tree of both sides' choices. */
export default function Calculator() {
  const { openDraft } = useBuild();
  const [form, setForm] = useState(initial);
  const [builds, setBuilds] = useState([]);
  const [estimate, setEstimate] = useState(null);
  const [job, setJob] = useState(null);
  const [mine, setMine] = useState({});
  useTask('calculator', job?.status === 'running', { label: t('tasks.calculator'), done: job?.done, total: job?.total, path: '/simulacoes/calculadora' });
  const [error, setError] = useState(null);
  const timer = useRef(null);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));

  useEffect(() => {
    api.get('/api/builds').then(setBuilds).catch((e) => setError(e.message));
    return () => clearInterval(timer.current);
  }, []);
  useEffect(() => {
    try {
      localStorage.setItem(STORE, JSON.stringify(form));
    } catch {
      /* session only */
    }
  }, [form]);

  const request = () => ({
    ...form,
    buildId: Number(form.buildId),
    opponentBuildId: form.opponentBuildId ? Number(form.opponentBuildId) : null,
    minutes: form.minutes.split(/[,;\s]+/).map(Number).filter((m) => m > 0),
  });
  useEffect(() => {
    if (!form.buildId) return undefined;
    const id = setTimeout(() => api.post('/api/calc/estimate', request()).then(setEstimate).catch(() => {}), 300);
    return () => clearTimeout(id);
  }, [form]);

  const start = async () => {
    setError(null);
    setMine({});
    if (estimate?.large && !window.confirm(t('calc.largeConfirm', { n: n0(estimate.measures), s: mmss(estimate.seconds / 60) }))) return;
    try {
      const j = await api.post('/api/calc/start', request());
      setJob(j);
      clearInterval(timer.current);
      timer.current = setInterval(async () => {
        const now = await api.get(`/api/calc/${j.id}`);
        setJob(now);
        if (now.status !== 'running') clearInterval(timer.current);
      }, 500);
    } catch (e) {
      setError(e.message);
    }
  };
  const a = job?.answer;
  const top = a?.matrix ? Math.max(...a.matrix.flat().map(Math.abs)) : 0;
  const lines = a?.best ? [a.best, ...a.alternatives] : [];
  const last = a?.minutes?.length ? a.minutes[a.minutes.length - 1] : 20;

  /** The line as a build: the base build with this order (and the page of runes the search chose), not saved. */
  const openAsBuild = async (l, i) => {
    try {
      const b = await api.get(`/api/builds/${form.buildId}`);
      const steps = l.order.map((id) => ({ itemId: id, includeConditional: true }));
      const name = `${b.name} · ${t('calc.title')} ${i === 0 ? t('calc.bestShort') : `#${i + 1}`}`;
      if (openDraft({ ...b, name, steps, runePage: l.runePage ?? b.runePage })) navigate('/build/loja');
    } catch (e) {
      setError(e.message);
    }
  };
  const compareWithMine = async (l, i) => {
    setMine((m) => ({ ...m, [i]: 'loading' }));
    try {
      const d = await api.post('/api/quick/delta', { buildId: Number(form.buildId), change: { order: l.order }, minute: last });
      setMine((m) => ({ ...m, [i]: d }));
    } catch (e) {
      setMine((m) => ({ ...m, [i]: { error: e.message } }));
    }
  };

  return (
    <div className="page">
      <section className="panel">
        <h2>{t('calc.title')}</h2>
        <p className="muted" style={{ marginTop: 0 }}>{t('calc.help')}</p>
        <div className="row">
          <label className="field grow">{t('calc.build')}
            <select value={form.buildId} onChange={(e) => set({ buildId: e.target.value })}>
              <option value="">—</option>
              {builds.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
            {form.buildId !== '' && <BuildLink id={Number(form.buildId)} name={builds.find((b) => String(b.id) === String(form.buildId))?.name}>{t('preview.short')}</BuildLink>}
          </label>
          <label className="field">{t('calc.target')}
            <select value={form.target} onChange={(e) => set({ target: e.target.value })}>
              <option value="basico">{t('calc.target.basico')}</option>
              <option value="completo">{t('calc.target.completo')}</option>
            </select>
          </label>
          <label className="field">{t('calc.measure')} <Help text={t('calc.measureHelp')} />
            <select value={form.measure} onChange={(e) => set({ measure: e.target.value })}>
              <option value="classe">{t('calc.measure.classe')}</option>
              <option value="relatorio">{t('calc.measure.relatorio')}</option>
            </select>
          </label>
          <label className="field grow">{t('calc.opponent')}
            <select value={form.opponentBuildId} onChange={(e) => set({ opponentBuildId: e.target.value })}>
              <option value="">{t('calc.noOpponent')}</option>
              {builds.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
            {form.opponentBuildId !== '' && <BuildLink id={Number(form.opponentBuildId)} name={builds.find((b) => String(b.id) === String(form.opponentBuildId))?.name}>{t('preview.short')}</BuildLink>}
          </label>
        </div>
        <div className="row">
          <label className="check"><input type="checkbox" checked={form.tanksOnly} onChange={(e) => set({ tanksOnly: e.target.checked })} />{t('calc.tanks')}</label>
          <label className="check" title={t('calc.noBootsHelp')}><input type="checkbox" checked={form.noBoots} onChange={(e) => set({ noBoots: e.target.checked })} />{t('calc.noBootsOpt')}</label>
          <label className="check" title={t('calc.runesHelp')}><input type="checkbox" checked={form.runes} onChange={(e) => set({ runes: e.target.checked })} />{t('calc.runes')}</label>
          <ItemPicker value={form.required} onChange={(v) => set({ required: v })} max={6} label={t('calc.required')} />
          <ItemPicker value={form.forbidden} onChange={(v) => set({ forbidden: v })} label={t('calc.forbidden')} />
        </div>
        <div className="row">
          <label className="field">{t('calc.mode')} <Help text={t('calc.modeHelp')} />
            <select value={form.mode} onChange={(e) => set({ mode: e.target.value })}>
              <option value="moderado">{t('calc.mode.moderado')}</option>
              <option value="agressivo">{t('calc.mode.agressivo')}</option>
            </select>
          </label>
          <label className="inline">{t('calc.minutes')}<input value={form.minutes} style={{ width: 170 }} onChange={(e) => set({ minutes: e.target.value })} /></label>
          <label className="inline" title={t('calc.lengthHelp')}>{t('calc.length')}<input type="number" min={1} max={5} value={form.length} style={{ width: 56 }} onChange={(e) => set({ length: Math.max(1, Math.min(5, Number(e.target.value) || 5)) })} /></label>
          <label className="inline">{t('calc.beam')}<input type="number" min={1} max={12} value={form.beam} style={{ width: 56 }} onChange={(e) => set({ beam: Number(e.target.value) || 3 })} /></label>
          <label className="inline">{t('calc.budget')}<input type="number" min={10} step={50} value={form.budget} style={{ width: 80 }} onChange={(e) => set({ budget: Number(e.target.value) || 100 })} /></label>
          <label className="check"><input type="checkbox" checked={form.tree} disabled={!form.opponentBuildId} onChange={(e) => set({ tree: e.target.checked })} />{t('calc.tree')}</label>
          {form.tree && (
            <>
              <label className="inline">k<input type="number" min={2} max={8} value={form.k} style={{ width: 56 }} onChange={(e) => set({ k: Number(e.target.value) || 4 })} /></label>
              <label className="inline">{t('calc.depth')}<input type="number" min={1} max={4} value={form.depth} style={{ width: 56 }} onChange={(e) => set({ depth: Number(e.target.value) || 2 })} /></label>
            </>
          )}
        </div>
        {estimate && (
          <p className="muted">
            {t('calc.estimate2', { n: n0(estimate.measures), c: estimate.candidates, s: mmss(estimate.seconds / 60) })}
            {estimate.space && ` ${t('calc.spaceLine', { i: estimate.space.item ?? 0, b: (estimate.space.boots ?? 0) + (estimate.space.boots3 ?? 0), c: estimate.space.component ?? 0 })}`}
          </p>
        )}
        {estimate?.large && <p className="notice">{t('calc.largeWarn')}</p>}
        {estimate?.needed > form.budget && <p className="notice">{t('calc.budgetShort', { n: n0(estimate.needed) })}</p>}
        <div className="row">
          <button className="primary" disabled={!form.buildId || job?.status === 'running'} onClick={start}>{t('calc.start')}</button>
          {job?.status === 'running' && <button onClick={() => api.post(`/api/calc/${job.id}/stop`, {})}>{t('calc.stop')}</button>}
          {job?.status === 'running' && <progress max={Math.max(1, job.total)} value={job.done} aria-label={t('calc.progress')} />}
          {job?.status === 'running' && <small className="muted">{job.done} / {job.total}</small>}
        </div>
        {(error || job?.error) && <p className="error">{error ?? job.error}</p>}
      </section>
      {a?.best && (
        <section className="panel">
          <h3>{t('calc.best')}</h3>
          <StepRow line={a.best} />
          {a.best.runePage && <p className="small">{t('calc.runePage')}: <b>{a.best.runePage.keystone}</b> · {[...(a.best.runePage.primaryRunes ?? []), a.best.runePage.secondaryRune].filter(Boolean).join(' · ')}</p>}
          <p className="muted">
            {t('calc.cost', { s: n1(a.seconds), n: n0(a.evaluations) })}{a.truncated ? ` · ${t('calc.truncated')}` : ''}
            {' · '}{t(`calc.mode.${a.mode ?? 'moderado'}`)}
          </p>
          <div className="table-scroll">
            <table className="table calc-lines">
              <thead>
                <tr>
                  <th>{t('calc.line')}</th>
                  {lines.map((l, i) => <th key={i}>{i === 0 ? t('calc.bestShort') : t('calc.alt', { n: i })}</th>)}
                </tr>
              </thead>
              <tbody>
                <tr>
                  <th>{t('calc.order')} <Help text={t('calc.orderHelp')} /></th>
                  {lines.map((l, i) => <td key={i}><StepRow line={l} against={i === 0 ? null : a.best} /></td>)}
                </tr>
                <tr>
                  <th>{t('calc.scoreLong')} <Help text={t(a.measure === 'relatorio' ? 'calc.scoreHelpReport' : 'calc.scoreHelp')} /></th>
                  {lines.map((l, i) => (
                    <td key={i}><b>{n1(l.score)}</b>{i > 0 && <small className={l.score < a.best.score ? 'delta-down' : 'delta-up'}> ({l.score - a.best.score >= 0 ? '+' : '−'}{n1(Math.abs(l.score - a.best.score))})</small>}</td>
                  ))}
                </tr>
                {a.minutes.map((m, k) => (
                  <tr key={m}>
                    <th>{t('calc.atMinute', { t: mmss(m) })} <Help text={t('calc.minuteHelp')} /></th>
                    {lines.map((l, i) => {
                      const d = l.curve[k] - a.best.curve[k];
                      return <td key={i}>{n1(l.curve[k])}{i > 0 && Math.abs(d) >= 0.05 && <small className={d < 0 ? 'delta-down' : 'delta-up'}> ({d >= 0 ? '+' : '−'}{n1(Math.abs(d))})</small>}</td>;
                    })}
                  </tr>
                ))}
                <tr>
                  <th>{t('wd.calcCol')} <Help text={t('wd.calcHelp')} /></th>
                  {lines.map((l, i) => (
                    <td key={i}>{l.delta ? <WholeDelta delta={l.delta} compact title=" " show={['gold', 'effective', 'stats']} ownLabel={t('wd.ownOrder')} /> : <small className="muted">{t('wd.calcBest')}</small>}</td>
                  ))}
                </tr>
                <tr>
                  <th>{t('calc.actions')}</th>
                  {lines.map((l, i) => (
                    <td key={i}>
                      <div className="row" style={{ gap: 4 }}>
                        <button className="small" onClick={() => openAsBuild(l, i)}>{t('calc.openAsBuild')}</button>
                        <button className="small" onClick={() => compareWithMine(l, i)}>{t('calc.compareMine')}</button>
                      </div>
                      {mine[i] === 'loading' && <small className="muted">{t('compare.calculating')}</small>}
                      {mine[i]?.error && <small className="error">{mine[i].error}</small>}
                      {mine[i]?.sources && <WholeDelta delta={mine[i]} compact title={t('calc.mineTitle')} show={['gold', 'effective', 'stats']} ownLabel={t('wd.ownOrder')} />}
                    </td>
                  ))}
                </tr>
              </tbody>
            </table>
          </div>
          {a.componentFirst?.length > 0 && (
            <>
              <h4>{t('calc.componentFirst')} <Help text={t('calc.componentFirstHelp')} /></h4>
              <ul>
                {a.componentFirst.map((c) => (
                  <li key={c.itemId}><ItemIcon id={c.componentId} size={20} /> {t('calc.componentFirstRow', { comp: c.component, item: c.item, g: n1(c.gain) })}</li>
                ))}
              </ul>
            </>
          )}
          <h4>{t('calc.early')} <Help text={t('calc.earlyHelp')} /></h4>
          <table className="table">
            <thead><tr><th>{t('calc.item')}</th><th>{t('calc.ready')}</th><th>{t('calc.delayed')} <Help text={t('calc.delayedHelp')} /></th><th>{t('calc.advanced')} <Help text={t('calc.advancedHelp')} /></th></tr></thead>
            <tbody>
              {a.importance.map((m, i) => (
                <tr key={`${m.itemId}-${i}`}>
                  <td><ItemIcon id={m.itemId} size={20} /> {m.item}</td>
                  <td>{m.readyAt != null && !Number.isNaN(m.readyAt) ? mmss(m.readyAt) : '—'}</td>
                  <td>{m.delayed == null ? '—' : `${-m.delayed >= 0 ? '+' : '−'}${n1(Math.abs(m.delayed))}`}</td>
                  <td>{m.advanced == null ? '—' : `${m.advanced >= 0 ? '+' : '−'}${n1(Math.abs(m.advanced))}`}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p>{a.boots ? t('calc.boots', { item: a.boots, t: a.bootsFrom != null ? mmss(a.bootsFrom) : '—' }) : t('calc.noBoots')}</p>
        </section>
      )}
      {a?.rows?.length > 0 && (
        <section className="panel">
          <h3>{t('calc.treeTitle')}</h3>
          <p>{verdict(a.rows)}.</p>
          <div className="table-scroll">
            <table className="table">
              <thead><tr><th>{t('calc.ours')}</th><th>{t('calc.wins')}</th>{a.answers.map((c, j) => <th key={j} title={names(c)}>{c.names[0]}</th>)}</tr></thead>
              <tbody>
                {a.rows.map((r, i) => (
                  <tr key={i}>
                    <td>{names(r.ours)}</td>
                    <td>{n0(r.winRate * 100)}%</td>
                    {a.matrix[r.matrixRow].map((v, j) => <td key={j} style={{ background: heat(v, top) }}>{n1(v * 100)}</td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}
