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

/** Admin › Draft e voz: the nicknames of the voice reader (Parte 2) and the draft's precomputed tables (Parte 3). */
export default function DraftAdmin() {
  return (
    <>
      <AliasEditor />
    </>
  );
}
