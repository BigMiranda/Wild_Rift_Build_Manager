import { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { mmss, n0, n1 } from '../format.js';
import { useTask } from '../tasks.js';
import Help from '../components/Help.jsx';
import { KitIcons } from './Discovery.jsx';

const duration = (s) => (s >= 3600 ? `${n1(s / 3600)} h` : mmss(s / 60));

/** Admin › Campanhas: the build discovery for a list of champions or all of them, in a resumable queue. */
export default function Campaigns() {
  const [champions, setChampions] = useState([]);
  const [form, setForm] = useState({ name: '', all: false, units: [], budget: 'completo', objective: 'melhores', runes: true, order: true, skipLowConfidence: true, saved: true, matchups: true, priority: 0 });
  const [estimate, setEstimate] = useState(null);
  const [state, setState] = useState(null);
  const [open, setOpen] = useState(null);
  const [error, setError] = useState(null);
  const timer = useRef(null);
  const set = (p) => setForm((f) => ({ ...f, ...p }));
  const pending = state?.rows?.filter((r) => r.status === 'pendente' || r.status === 'rodando').length ?? 0;
  useTask('campaigns', !!state?.running, { label: t('camp.task'), done: (state?.rows?.length ?? 0) - pending, total: state?.rows?.length ?? 0, path: '/admin/campanhas' });

  const reload = () => api.get('/api/campaigns').then(setState).catch((e) => setError(e.message));
  useEffect(() => {
    api.get('/api/champions').then((l) => setChampions(l)).catch(() => {});
    reload();
    timer.current = setInterval(reload, 3000);
    return () => clearInterval(timer.current);
  }, []);
  const request = () => ({
    name: form.name, all: form.all, units: form.units, skipLowConfidence: form.skipLowConfidence, saved: form.saved, matchups: form.matchups,
    priority: Number(form.priority) || 0, discovery: { budget: form.budget, objective: form.objective, runes: form.runes, order: form.order },
  });
  useEffect(() => {
    if (!form.all && form.units.length === 0) {
      setEstimate(null);
      return undefined;
    }
    const id = setTimeout(() => api.post('/api/campaigns/estimate', request()).then(setEstimate).catch(() => {}), 400);
    return () => clearTimeout(id);
  }, [form]);

  const run = async (fn) => {
    setError(null);
    try {
      await fn();
      reload();
    } catch (e) {
      setError(e.message);
    }
  };
  const enqueue = () => run(() => api.post('/api/campaigns', request()));
  const show = async (row) => setOpen(open?.id === row.id ? null : await api.get(`/api/campaigns/${row.id}`));
  const result = open?.result ? JSON.parse(open.result) : null;

  return (
    <div className="page">
      <section className="panel">
        <h2>{t('camp.title')} <Help text={t('camp.help')} /></h2>
        <div className="row">
          <label className="inline">{t('efx.name')}<input value={form.name} onChange={(e) => set({ name: e.target.value })} /></label>
          <label className="check"><input type="checkbox" checked={form.all} onChange={(e) => set({ all: e.target.checked })} />{t('camp.all')}</label>
          {!form.all && (
            <select multiple size={6} value={form.units} onChange={(e) => set({ units: [...e.target.selectedOptions].map((o) => o.value) })} aria-label={t('camp.champions')} style={{ minWidth: 220 }}>
              {champions.map((c) => <option key={c.code} value={c.code}>{c.nome}</option>)}
            </select>
          )}
        </div>
        <div className="row">
          <label className="field">{t('disc.budget')}
            <select value={form.budget} onChange={(e) => set({ budget: e.target.value })}>
              <option value="rapido">{t('disc.budget.rapido')}</option>
              <option value="completo">{t('disc.budget.completo')}</option>
            </select>
          </label>
          <label className="field">{t('disc.objective')} <Help text={t('disc.objectiveHelp')} />
            <select value={form.objective} onChange={(e) => set({ objective: e.target.value })}>
              <option value="melhores">{t('disc.objective.melhores')}</option>
              <option value="novidade">{t('disc.objective.novidade')}</option>
            </select>
          </label>
          <label className="check"><input type="checkbox" checked={form.saved} onChange={(e) => set({ saved: e.target.checked })} />{t('camp.saved')}</label>
          <label className="check"><input type="checkbox" checked={form.runes} onChange={(e) => set({ runes: e.target.checked })} />{t('disc.runes')}</label>
          <label className="check"><input type="checkbox" checked={form.order} onChange={(e) => set({ order: e.target.checked })} />{t('disc.order')}</label>
          <label className="check"><input type="checkbox" checked={form.matchups} onChange={(e) => set({ matchups: e.target.checked })} />{t('camp.matchups')}</label>
          <label className="check"><input type="checkbox" checked={form.skipLowConfidence} onChange={(e) => set({ skipLowConfidence: e.target.checked })} />{t('camp.skipLow')}</label>
          <label className="inline">{t('camp.priority')}<input type="number" value={form.priority} style={{ width: 60 }} onChange={(e) => set({ priority: e.target.value })} /></label>
        </div>
        {estimate && (
          <p className="muted">{t('camp.estimate', { n: estimate.champions, low: estimate.lowConfidence, done: estimate.alreadyDone, s: duration(estimate.seconds), per: duration(estimate.secondsPerChampion) })}</p>
        )}
        <div className="row">
          <button className="primary" disabled={!form.all && form.units.length === 0} onClick={enqueue}>{t('camp.enqueue')}</button>
          {state?.running ? <button onClick={() => run(() => api.post('/api/campaigns/pause', {}))}>{t('camp.pause')}</button>
            : <button onClick={() => run(() => api.post('/api/campaigns/start', {}))} disabled={!pending}>{t('camp.resume')}</button>}
          {state?.running && <small>{t('camp.running', { stage: state.stage ?? '…', d: state.done, tot: state.total })}</small>}
        </div>
        {error && <p className="error">{error}</p>}
      </section>
      {state?.rows?.length > 0 && (
        <section className="panel">
          <h3>{t('camp.queue')}</h3>
          <div className="table-scroll">
            <table className="table">
              <thead><tr><th>{t('camp.champion')}</th><th>{t('camp.campaign')}</th><th>{t('camp.status')}</th><th>{t('camp.step')}</th><th>{t('camp.priority')}</th><th>{t('camp.time')}</th><th>{t('calc.actions')}</th></tr></thead>
              <tbody>
                {state.rows.map((r) => (
                  <tr key={r.id} className={state.current === r.id ? 'active' : ''}>
                    <td>{r.champion}<br /><small className="muted">{r.patch}</small></td>
                    <td><small>{r.campaign ?? t('camp.alone')}</small></td>
                    <td>{t(`camp.st.${r.status}`)}{r.error && <><br /><small className="muted">{r.error}</small></>}</td>
                    <td>{t(`camp.stepName.${r.step ?? 'fim'}`)}</td>
                    <td><input type="number" defaultValue={r.priority} style={{ width: 56 }} onBlur={(e) => run(() => api.put(`/api/campaigns/${r.id}/priority?value=${Number(e.target.value) || 0}`, {}))} /></td>
                    <td>{duration(r.seconds)}</td>
                    <td>
                      <div className="row" style={{ gap: 4 }}>
                        {r.result !== undefined && (r.status === 'feito' || r.status === 'pausado') && <button className="small" onClick={() => show(r)}>{open?.id === r.id ? t('camp.hide') : t('camp.show')}</button>}
                        {['pausado', 'erro', 'cancelado', 'pulado'].includes(r.status) && <button className="small" onClick={() => run(() => api.post(`/api/campaigns/${r.id}/resume`, {}))}>{t('camp.resume')}</button>}
                        {['pendente', 'rodando', 'pausado'].includes(r.status) && <button className="small" onClick={() => run(() => api.post(`/api/campaigns/${r.id}/cancel`, {}))}>{t('camp.cancel')}</button>}
                        {state.current !== r.id && <button className="small danger" onClick={() => window.confirm(t('camp.deleteConfirm', { name: r.champion })) && run(() => api.del(`/api/campaigns/${r.id}`))}>✕</button>}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
      {result && (
        <section className="panel">
          <h3>{open.champion}</h3>
          {result.syntheticBase && <p className="notice">{t('camp.synthetic')}</p>}
          {result.discovery?.lowConfidence && <p className="notice">{t('disc.lowConfidence')}</p>}
          {result.discovery?.standard && (
            <p><b>{t('disc.standard')}</b> <KitIcons kit={result.discovery.standard} /> {n1(result.discovery.standard.score)}</p>
          )}
          {result.discovery?.discoveries?.map((f, i) => <p key={i} className="small">• {f.text}</p>)}
          {result.saved?.length > 0 && (
            <>
              <h4>{t('camp.savedTitle')}</h4>
              <ul className="small">{result.saved.map((s) => <li key={s.buildId}>{s.name}: {n1(s.score)} (P20 {n1(s.p20)})</li>)}</ul>
            </>
          )}
          {result.matchups?.length > 0 && (
            <>
              <h4>{t('camp.matchupsTitle')} <Help text={t('camp.matchupsHelp')} /></h4>
              <table className="table">
                <tbody>
                  {result.matchups.map((m, i) => (
                    <tr key={i}><td>{m.rival}</td><td><small>{m.build}</small></td>
                      {Object.entries(m.minutes).map(([k, v]) => <td key={k} className={v >= 0 ? 'delta-up' : 'delta-down'}>{mmss(Number(k))}: {n0(v)}</td>)}
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </section>
      )}
    </div>
  );
}
