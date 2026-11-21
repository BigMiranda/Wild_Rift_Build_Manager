import { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { mmss, n0, n1 } from '../format.js';
import { useTask } from '../tasks.js';
import Help from './Help.jsx';

const duration = (s) => (s == null ? '—' : s >= 3600 ? `${n1(s / 3600)} h` : mmss(s / 60));
const pct = (v) => (v == null ? '—' : `${n1(v)}%`);
const owner = (o) => (o?.startsWith('class:') ? `${t('aicamp.class')} ${o.slice(6)}` : o);

/**
 * Rodada 21, part 4: the campaign "Treinar IAs de todos os campeões" in Admin › Campanhas: the queue (the class AIs,
 * then the champions), pause / stop after the current one / resume, the estimate measured on the first ones, and the
 * table of every champion's AI.
 */
export default function AiCampaign() {
  const [state, setState] = useState(null);
  const [table, setTable] = useState(null);
  const [form, setForm] = useState({ budget: 'rapido', classes: true, force: false, name: '' });
  const [preview, setPreview] = useState(null);
  const [filter, setFilter] = useState('');
  const [error, setError] = useState(null);
  const timer = useRef(null);
  const set = (p) => setForm((f) => ({ ...f, ...p }));
  const rows = state?.rows ?? [];
  const pending = rows.filter((r) => r.status === 'pendente' || r.status === 'rodando').length;
  const done = rows.filter((r) => ['aceita', 'recusada', 'erro', 'cancelado'].includes(r.status)).length;
  useTask('ai-campaign', !!state?.running, {
    label: t('aicamp.task', { who: owner(state?.currentOwner) ?? '…' }), done, total: done + pending, path: '/admin/campanhas',
  });

  const reload = () => api.get('/api/ai-campaign').then(setState).catch((e) => setError(e.message));
  const reloadTable = () => api.get('/api/ai-campaign/table').then(setTable).catch(() => {});
  useEffect(() => {
    reload();
    reloadTable();
    timer.current = setInterval(reload, 3000);
    return () => clearInterval(timer.current);
  }, []);
  useEffect(() => {
    if (!state?.running) reloadTable();
  }, [state?.running, done]);
  useEffect(() => {
    const id = setTimeout(() => api.post('/api/ai-campaign/preview', form).then(setPreview).catch(() => setPreview(null)), 400);
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
  const est = state?.estimate;
  const shown = (table ?? []).filter((c) => !filter || c.status === filter);

  return (
    <>
      <section className="panel">
        <h2>{t('aicamp.title')} <Help text={t('aicamp.help')} /></h2>
        <div className="row" style={{ gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
          <label>{t('aicamp.name')} <input value={form.name} onChange={(e) => set({ name: e.target.value })} placeholder={t('aicamp.namePh')} /></label>
          <label>{t('aicamp.budget')}{' '}
            <select value={form.budget} onChange={(e) => set({ budget: e.target.value })}>
              <option value="rapido">{t('aicamp.budget.rapido')}</option>
              <option value="completo">{t('aicamp.budget.completo')}</option>
            </select>
          </label>
          <label><input type="checkbox" checked={form.classes} onChange={(e) => set({ classes: e.target.checked })} /> {t('aicamp.classesFirst')}</label>
          <label><input type="checkbox" checked={form.force} onChange={(e) => set({ force: e.target.checked })} /> {t('aicamp.force')}</label>
          <button className="primary" disabled={!preview?.length} onClick={() => run(() => api.post('/api/ai-campaign', form))}>
            {t('aicamp.enqueue', { n: preview?.length ?? 0 })}
          </button>
        </div>
        {preview && <small className="muted">{t('aicamp.preview', { list: preview.slice(0, 12).map((p) => owner(p.owner)).join(', ') + (preview.length > 12 ? '…' : '') })}</small>}
        <div className="row" style={{ gap: 8, marginTop: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          {state?.running ? (
            <>
              <button onClick={() => run(() => api.post('/api/ai-campaign/pause', {}))}>{t('camp.pause')}</button>
              <button onClick={() => run(() => api.post('/api/ai-campaign/stop-after-current', {}))} disabled={state.stopAfterCurrent}>{t('aicamp.stopAfter')}</button>
            </>
          ) : <button onClick={() => run(() => api.post('/api/ai-campaign/start', {}))} disabled={!pending && !rows.some((r) => r.status === 'pausado')}>{t('camp.resume')}</button>}
          {state?.running && (
            <small>
              {t('aicamp.running', { who: owner(state.currentOwner) ?? '…', stage: state.stage ?? '…' })}
              {state.generations ? ` · ${t('aicamp.gen', { g: state.generation ?? 0, n: state.generations })}` : ''}
              {state.validationTotal ? ` · ${t('aicamp.val', { d: state.validationDone ?? 0, n: state.validationTotal })}` : ''}
              {state.stopAfterCurrent ? ` · ${t('aicamp.stopping')}` : ''}
            </small>
          )}
          {state && <small className="muted">{t(state.sharing ? 'aicamp.threadsShared' : 'aicamp.threads', { n: state.threads })}</small>}
        </div>
        {est && (
          <p className="small">
            {t('aicamp.estimate', {
              budget: t(`aicamp.budget.${est.budget}`), cls: duration(est.secondsPerClass), ch: duration(est.secondsPerChampion),
              nc: est.classesMeasured, nch: est.championsMeasured, left: duration(est.secondsLeft), pend: est.pending,
              all: duration(est.secondsAll), total: est.championsAll,
            })}
          </p>
        )}
        {error && <p className="error">{error}</p>}
      </section>
      {rows.length > 0 && (
        <section className="panel">
          <h3>{t('aicamp.queue')}</h3>
          <div className="table-scroll" style={{ maxHeight: 360 }}>
            <table className="table">
              <thead><tr><th>#</th><th>{t('aicamp.owner')}</th><th>{t('camp.status')}</th><th>{t('aicamp.reason')}</th><th>{t('aicamp.result')}</th><th>{t('camp.time')}</th><th>{t('calc.actions')}</th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id} className={state.current === r.id ? 'active' : ''}>
                    <td>{r.position + 1}</td>
                    <td>{owner(r.owner)}<br /><small className="muted">{t(`aicamp.budget.${r.budget}`)} · {r.patch}</small></td>
                    <td>{t(`aicamp.st.${r.status}`)}{r.error && <><br /><small className="muted">{r.error}</small></>}</td>
                    <td><small>{r.reason}</small></td>
                    <td><small>{r.result ? t('aicamp.resultText', {
                      v: r.result.version ?? '?', m: n1(r.result.mean), tac: n1(r.result.tacticalMean),
                      inc: r.result.incumbentMean == null ? t('aicamp.none') : n1(r.result.incumbentMean), why: r.result.reason ?? '',
                    }) : '—'}</small></td>
                    <td>{duration(r.seconds)}</td>
                    <td>
                      <div className="row" style={{ gap: 4 }}>
                        {['pausado', 'erro', 'cancelado'].includes(r.status) && <button className="small" onClick={() => run(() => api.post(`/api/ai-campaign/${r.id}/resume`, {}))}>{t('camp.resume')}</button>}
                        {['pendente', 'rodando', 'pausado'].includes(r.status) && <button className="small" onClick={() => run(() => api.post(`/api/ai-campaign/${r.id}/cancel`, {}))}>{t('camp.cancel')}</button>}
                        {state.current !== r.id && <button className="small danger" onClick={() => run(() => api.del(`/api/ai-campaign/${r.id}`))}>✕</button>}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
      {table && (
        <section className="panel">
          <h3>{t('aicamp.tableTitle', { n: table.length })} <Help text={t('aicamp.tableHelp')} /></h3>
          <div className="row" style={{ gap: 6, flexWrap: 'wrap' }}>
            {['', 'propria', 'na_fila', 'treinando', 'recusada', 'sem_treino'].map((k) => (
              <button key={k || 'all'} className={`small ${filter === k ? 'primary' : ''}`} onClick={() => setFilter(k)}>
                {k ? t(`aicamp.tst.${k}`) : t('aicamp.all')} ({k ? table.filter((c) => c.status === k).length : table.length})
              </button>
            ))}
          </div>
          <div className="table-scroll" style={{ maxHeight: 480 }}>
            <table className="table">
              <thead><tr><th>{t('camp.champion')}</th><th>{t('aicamp.class')}</th><th>{t('camp.status')}</th><th>{t('aicamp.version')}</th><th>{t('aicamp.valMine')}</th><th>{t('aicamp.valClass')}</th><th>{t('aicamp.valTac')}</th><th>{t('aicamp.reason')}</th></tr></thead>
              <tbody>
                {shown.map((c) => (
                  <tr key={c.champion}>
                    <td>{c.champion} <small className="muted">{c.quality}</small></td>
                    <td><small>{c.className}</small></td>
                    <td>{t(`aicamp.tst.${c.status}`)}{c.status === 'propria' && c.inUse === false && <><br /><small className="muted">{t('aicamp.notInUse')}</small></>}</td>
                    <td>{c.version ? `v${c.version}` : '—'}{c.date && <><br /><small className="muted">{c.date.slice(0, 10)}</small></>}</td>
                    <td>{pct(c.mean)}</td>
                    <td>{pct(c.classMean)}</td>
                    <td>{pct(c.tacticalMean)}</td>
                    <td><small>{c.stale ?? c.reason ?? ''}</small></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <small className="muted">{t('aicamp.tableNote', { n: n0(table.filter((c) => c.status === 'propria').length) })}</small>
        </section>
      )}
    </>
  );
}
