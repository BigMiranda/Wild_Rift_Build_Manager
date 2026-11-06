import { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { mmss, n0, n1 } from '../format.js';
import ItemPicker from '../components/ShopPicker.jsx';
import WholeDelta from '../components/WholeDelta.jsx';

const STORE = 'ornn-planner-calculator';

const initial = () => {
  const def = { buildId: '', target: 'basico', opponentBuildId: '', tanksOnly: false, forbidden: [], required: [], minutes: '8, 12, 16, 20', length: 3, beam: 3, budget: 400, tree: false, k: 6, depth: 3 };
  try {
    return { ...def, ...JSON.parse(localStorage.getItem(STORE) ?? '{}') };
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

/** The build calculator: the best order against a target, and the tree of both sides' choices. */
export default function Calculator() {
  const [form, setForm] = useState(initial);
  const [builds, setBuilds] = useState([]);
  const [estimate, setEstimate] = useState(null);
  const [job, setJob] = useState(null);
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
          </label>
          <label className="field">{t('calc.target')}
            <select value={form.target} onChange={(e) => set({ target: e.target.value })}>
              <option value="basico">{t('calc.target.basico')}</option>
              <option value="completo">{t('calc.target.completo')}</option>
            </select>
          </label>
          <label className="field grow">{t('calc.opponent')}
            <select value={form.opponentBuildId} onChange={(e) => set({ opponentBuildId: e.target.value })}>
              <option value="">{t('calc.noOpponent')}</option>
              {builds.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </label>
        </div>
        <div className="row">
          <label className="check"><input type="checkbox" checked={form.tanksOnly} onChange={(e) => set({ tanksOnly: e.target.checked })} />{t('calc.tanks')}</label>
          <ItemPicker value={form.required} onChange={(v) => set({ required: v })} max={6} label={t('calc.required')} />
          <ItemPicker value={form.forbidden} onChange={(v) => set({ forbidden: v })} label={t('calc.forbidden')} />
        </div>
        <div className="row">
          <label className="inline">{t('calc.minutes')}<input value={form.minutes} style={{ width: 120 }} onChange={(e) => set({ minutes: e.target.value })} /></label>
          <label className="inline">{t('calc.length')}<input type="number" min={1} max={6} value={form.length} style={{ width: 56 }} onChange={(e) => set({ length: Number(e.target.value) || 3 })} /></label>
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
        {estimate && <p className="muted">{t('calc.estimate', { n: n0(estimate.measures), c: estimate.candidates, s: n0(estimate.seconds) })}</p>}
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
          <h3>{t('calc.best')}: {names(a.best)}</h3>
          <p className="muted">{t('calc.cost', { s: n1(a.seconds), n: n0(a.evaluations) })}{a.truncated ? ` · ${t('calc.truncated')}` : ''}</p>
          <table className="table">
            <thead><tr><th>{t('calc.order')}</th><th>{t('calc.score')}</th>{a.minutes.map((m) => <th key={m}>{mmss(m)}</th>)}<th title={t('wd.calcHelp')}>{t('wd.calcCol')}</th></tr></thead>
            <tbody>
              {[a.best, ...a.alternatives].map((l, i) => (
                <tr key={i}>
                  <td>{names(l)}</td>
                  <td>{n1(l.score)}</td>
                  {l.curve.map((c, k) => <td key={k}>{n1(c)}</td>)}
                  <td>{l.delta ? <WholeDelta delta={l.delta} compact title=" " show={['gold', 'effective', 'stats']} /> : <small className="muted">{t('wd.calcBest')}</small>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <h4>{t('calc.early')}</h4>
          <table className="table">
            <thead><tr><th>{t('calc.item')}</th><th>{t('calc.ready')}</th><th>{t('calc.delayed')}</th><th>{t('calc.advanced')}</th></tr></thead>
            <tbody>
              {a.importance.map((m) => (
                <tr key={m.itemId}>
                  <td>{m.item}</td>
                  <td>{mmss(m.readyAt)}</td>
                  <td>{m.delayed == null ? '—' : `−${n1(m.delayed)}`}</td>
                  <td>{m.advanced == null ? '—' : `${m.advanced >= 0 ? '+' : ''}${n1(m.advanced)}`}</td>
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
