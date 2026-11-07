import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import ItemIcon from './ItemIcon.jsx';
import Help from './Help.jsx';

/** The meta builds of a champion (Rodada 9, part 5): a list, and a plain text paste read by the shop's names. */
export default function MetaBuilds({ unitCode }) {
  const [list, setList] = useState([]);
  const [text, setText] = useState('');
  const [name, setName] = useState('');
  const [source, setSource] = useState('');
  const [parsed, setParsed] = useState(null);
  const [error, setError] = useState(null);
  const reload = () => api.get(`/api/meta-builds${unitCode ? `?unit=${encodeURIComponent(unitCode)}` : ''}`).then(setList).catch((e) => setError(e.message));
  useEffect(() => { reload(); setParsed(null); }, [unitCode]);
  const parse = async () => {
    setError(null);
    try {
      setParsed(await api.post('/api/meta-builds/parse', { text, unitCode, name, source }));
    } catch (e) {
      setError(e.message);
    }
  };
  const save = async () => {
    try {
      await api.post('/api/meta-builds', parsed.build);
      setParsed(null);
      setText('');
      reload();
    } catch (e) {
      setError(e.message);
    }
  };
  const remove = async (m) => {
    if (!window.confirm(t('meta.deleteConfirm', { name: m.name }))) return;
    await api.del(`/api/meta-builds/${m.id}`);
    reload();
  };
  return (
    <section className="panel">
      <h3>{t('meta.title', { unit: unitCode ?? '—' })} <Help text={t('meta.help')} /></h3>
      {!unitCode && <p className="muted">{t('meta.pickBuild')}</p>}
      {list.length > 0 && (
        <table className="table">
          <tbody>
            {list.map((m) => (
              <tr key={m.id}>
                <td>{m.name}</td>
                <td><span className="step-row">{[...m.items, ...(m.boots ? [m.boots] : [])].map((id) => <ItemIcon key={id} id={id} size={22} />)}</span></td>
                <td>{m.keystone ?? '—'}</td>
                <td><small className="muted">{m.source}</small></td>
                <td><button className="icon" onClick={() => remove(m)} aria-label={t('efx.remove')}>✕</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {unitCode && (
        <>
          <div className="row">
            <label className="inline">{t('efx.name')}<input value={name} onChange={(e) => setName(e.target.value)} /></label>
            <label className="inline grow">{t('meta.source')}<input value={source} placeholder={t('meta.sourcePh')} onChange={(e) => setSource(e.target.value)} style={{ width: 240 }} /></label>
          </div>
          <textarea rows={3} style={{ width: '100%' }} value={text} placeholder={t('meta.pastePh')} onChange={(e) => setText(e.target.value)} />
          <div className="row">
            <button onClick={parse} disabled={!text.trim()}>{t('meta.read')}</button>
            {parsed && <button className="primary" onClick={save} disabled={!parsed.build.items.length}>{t('meta.save')}</button>}
          </div>
          {parsed && (
            <p className="small">
              <span className="step-row">{[...parsed.build.items, ...(parsed.build.boots ? [parsed.build.boots] : [])].map((id) => <ItemIcon key={id} id={id} size={22} />)}</span>
              {' '}{parsed.build.keystone ?? t('meta.noKeystone')}
              {parsed.unknown.length > 0 && <span className="error"> · {t('meta.unknown', { list: parsed.unknown.join(', ') })}</span>}
            </p>
          )}
        </>
      )}
      {error && <p className="error">{error}</p>}
    </section>
  );
}
