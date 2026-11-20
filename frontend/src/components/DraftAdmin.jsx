import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { loadAliases } from './VoiceBar.jsx';

const KINDS = ['campeoes', 'feiticos', 'runas', 'itens', 'rotas', 'lados', 'objetivos', 'dragoes'];

/** Rodada 20, Parte 2: the voice reader's nicknames (seed apelidos_7_3f.yml + the user's edits, table draft_apelido). */
function AliasEditor() {
  const [list, setList] = useState([]);
  const [kind, setKind] = useState('campeoes');
  const [alias, setAlias] = useState('');
  const [target, setTarget] = useState('');
  const [query, setQuery] = useState('');
  const [error, setError] = useState(null);
  const refresh = (l) => { setList(l); loadAliases(true).catch(() => {}); };
  useEffect(() => { api.get('/api/draft/apelidos').then(setList).catch((e) => setError(e.message)); }, []);
  const shown = useMemo(() => list.filter((a) => a.kind === kind && (!query || `${a.alias} ${a.target}`.toLowerCase().includes(query.toLowerCase()))), [list, kind, query]);
  const act = async (fn) => { try { setError(null); refresh(await fn()); } catch (e) { setError(e.message); } };
  return (
    <section className="panel">
      <h3>{t('draftAdmin.aliases')}</h3>
      <p className="muted small">{t('draftAdmin.aliasesHelp')}</p>
      <div className="chips">
        {KINDS.map((k) => <button key={k} type="button" className={kind === k ? 'active' : ''} onClick={() => setKind(k)}>{t(`draftAdmin.kind.${k}`)}</button>)}
      </div>
      <form className="row" onSubmit={(e) => { e.preventDefault(); act(() => api.put('/api/draft/apelidos', { alias, target, kind })); setAlias(''); setTarget(''); }}>
        <input value={alias} onChange={(e) => setAlias(e.target.value)} placeholder={t('draftAdmin.said')} aria-label={t('draftAdmin.said')} />
        <span>→</span>
        <input value={target} onChange={(e) => setTarget(e.target.value)} placeholder={t('draftAdmin.means')} aria-label={t('draftAdmin.means')} />
        <button type="submit" className="primary" disabled={!alias.trim() || !target.trim()}>{t('draftAdmin.add')}</button>
        <input type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t('picker.search')} />
        <button type="button" onClick={() => window.confirm(t('draftAdmin.resetConfirm')) && act(() => api.post('/api/draft/apelidos/restaurar'))}>{t('draftAdmin.reset')}</button>
      </form>
      {error && <div className="notice error">{error}</div>}
      <div className="table-wrap"><table className="data">
        <thead><tr><th>{t('draftAdmin.said')}</th><th>{t('draftAdmin.means')}</th><th>{t('draftAdmin.source')}</th><th /></tr></thead>
        <tbody>
          {shown.map((a) => (
            <tr key={`${a.kind}|${a.alias}`}>
              <td>{a.alias}</td><td>{a.target}</td><td className="muted">{a.source === 'usuario' ? t('draftAdmin.user') : 'seed'}</td>
              <td><button type="button" className="link danger" onClick={() => act(() => api.del(`/api/draft/apelidos?tipo=${encodeURIComponent(a.kind)}&apelido=${encodeURIComponent(a.alias)}`))}>✕</button></td>
            </tr>
          ))}
        </tbody>
      </table></div>
    </section>
  );
}

const secs = (v) => (v == null ? '—' : `${(Math.round(v * 10) / 10).toLocaleString('pt-BR')} s`);

/** Rodada 20, Parte 3: the draft's precomputed tables (lane matchups, the Dragon duo, the composition calibration). */
function DraftTables() {
  const [st, setSt] = useState(null);
  const [error, setError] = useState(null);
  const [calibrate, setCalibrate] = useState(true);
  const load = () => api.get('/api/draft/tabelas').then(setSt).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);
  useEffect(() => {
    if (!st?.running) return undefined;
    const h = setInterval(load, 2000);
    return () => clearInterval(h);
  }, [st?.running]);
  const start = async () => {
    try { setError(null); setSt(await api.post(`/api/draft/tabelas?calibrar=${calibrate}`)); } catch (e) { setError(e.message); }
  };
  return (
    <section className="panel">
      <h3>{t('draftAdmin.tables')}</h3>
      <p className="muted small">{t('draftAdmin.tablesHelp')}</p>
      <div className="row">
        <button type="button" className="primary" disabled={st?.running} onClick={start}>{t('draftAdmin.tablesRun')}</button>
        <label className="live-check"><input type="checkbox" checked={calibrate} onChange={(e) => setCalibrate(e.target.checked)} /> {t('draftAdmin.calibrate')}</label>
        <button type="button" disabled={st?.running} title={t('draftAdmin.matchCalibrationHelp')}
          onClick={async () => { try { setError(null); setSt(await api.post('/api/draft/calibracao-partida')); } catch (e) { setError(e.message); } }}>
          {t('draftAdmin.matchCalibration')}
        </button>
        {st?.running && <button type="button" onClick={() => api.post('/api/draft/tabelas/cancelar').then(setSt)}>{t('draftAdmin.cancel')}</button>}
      </div>
      {error && <div className="notice error">{error}</div>}
      {st && (
        <p className="small">
          {st.running ? t('draftAdmin.running', { stage: st.stage, done: st.done, total: st.total }) : t('draftAdmin.rows', { n: st.rows, date: st.updatedAt ?? '—' })}
          {st.seconds != null && !st.running && ` · ${t('draftAdmin.took', { s: secs(st.seconds) })}`}
          {st.error && <span className="error"> · {st.error}</span>}
        </p>
      )}
      {st && Object.keys(st.times ?? {}).length > 0 && (
        <div className="table-wrap"><table className="data">
          <thead><tr><th className="l">{t('draftAdmin.stage')}</th><th>{t('draftAdmin.count')}</th><th>{t('draftAdmin.time')}</th></tr></thead>
          <tbody>{Object.entries(st.times).map(([k, v]) => <tr key={k}><td className="l">{k}</td><td>{st.counts?.[k] ?? ''}</td><td>{secs(v)}</td></tr>)}</tbody>
        </table></div>
      )}
    </section>
  );
}

/** Admin › Draft e voz: the nicknames of the voice reader (Parte 2) and the draft's precomputed tables (Parte 3). */
export default function DraftAdmin() {
  return (
    <>
      <DraftTables />
      <AliasEditor />
    </>
  );
}
