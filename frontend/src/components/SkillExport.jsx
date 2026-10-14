import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { planOf, validOrder, validPlan } from '../skills.js';

/** A build's skill order and its points by level (1..15), as small cells: only what it has. */
function SkillOrder({ setup }) {
  const custom = validPlan(setup?.skillLevels);
  const plan = planOf(setup);
  return (
    <span className="skill-export-order">
      <strong>{custom ? t('skillExport.custom') : validOrder(setup?.skillOrder).join(' › ')}</strong>
      <span className="skill-export-plan" aria-label={plan.join(' ')}>
        {plan.map((a, i) => <span key={i} className={a === 'R' ? 'ult' : ''} title={t('skillExport.level', { level: i + 1, a })}>{a}</span>)}
      </span>
    </span>
  );
}

/**
 * Copies the open build's skill order (and its point-by-point plan, when it has one) to other saved champion builds,
 * keeping their other champion choices. Each build in the list shows only the order it has now.
 */
export default function SkillExport({ setup, unitCode, builds, folders, currentId, onClose, onDone }) {
  const [selected, setSelected] = useState(() => new Set());
  const [query, setQuery] = useState('');
  const [sameChamp, setSameChamp] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const folderName = useMemo(() => new Map(folders.map((f) => [f.id, f.name])), [folders]);
  const plain = (s) => String(s ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  const others = builds.filter((b) => b.id !== currentId && b.unitCode !== 'RAGDOLL' && (!sameChamp || b.unitCode === unitCode));
  const shown = others.filter((b) => !query || plain(`${b.name} ${b.unitCode} ${folderName.get(b.folderId)}`).includes(plain(query)));
  const toggle = (id) => setSelected((s) => {
    const n = new Set(s);
    if (n.has(id)) n.delete(id); else n.add(id);
    return n;
  });
  // Builds hidden by the champion filter are not exported.
  const ids = [...selected].filter((id) => others.some((b) => b.id === id));

  const run = async () => {
    if (!ids.length) return;
    if (!window.confirm(t('skillExport.confirm', { n: ids.length }))) return;
    setBusy(true);
    setError(null);
    try {
      const r = await api.put('/api/builds/skills', {
        buildIds: ids,
        skillOrder: validOrder(setup?.skillOrder),
        skillLevels: validPlan(setup?.skillLevels) ? setup.skillLevels : null,
      });
      onDone(r.updated);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="prep-overlay" role="dialog" aria-label={t('skillExport.title')}>
      <div className="prep-overlay-head">
        <button className="prep-back" onClick={onClose} aria-label={t('prep.back')}>
          <span aria-hidden="true">‹</span> {t('skillExport.title')}
        </button>
      </div>
      <div className="prep-export">
        <div className="prep-export-source">
          <span className="muted">{t('prepExport.from')}</span>
          <SkillOrder setup={setup} />
          <small className="muted">{t('prepExport.unsaved')}</small>
        </div>
        <div className="row prep-export-what">
          <label className="check"><input type="checkbox" checked={sameChamp} onChange={(e) => setSameChamp(e.target.checked)} />{t('skillExport.sameChamp', { unit: unitCode })}</label>
        </div>
        <div className="row prep-export-tools">
          <input type="search" value={query} placeholder={t('prepExport.search')} onChange={(e) => setQuery(e.target.value)} aria-label={t('prepExport.search')} />
          <button className="link" onClick={() => setSelected(new Set([...selected, ...shown.map((b) => b.id)]))}>{t('prepExport.all')}</button>
          <button className="link" onClick={() => setSelected(new Set())} disabled={!selected.size}>{t('prepExport.clear')}</button>
        </div>
        <ul className="prep-export-list">
          {shown.map((b) => (
            <li key={b.id}>
              <label className={selected.has(b.id) ? 'on' : ''}>
                <input type="checkbox" checked={selected.has(b.id)} onChange={() => toggle(b.id)} />
                <span className="prep-export-name">
                  <strong>{b.name}</strong>
                  <small className="muted">{b.unitCode} · {folderName.get(b.folderId) ?? ''}</small>
                </span>
                <SkillOrder setup={b.championSetup} />
              </label>
            </li>
          ))}
          {shown.length === 0 && <li className="muted">{t('prepExport.empty')}</li>}
        </ul>
        {error && <p className="error">{error}</p>}
        <div className="row">
          <button className="primary" onClick={run} disabled={busy || !ids.length}>
            {busy ? t('prepExport.running') : t('prepExport.run', { n: ids.length })}
          </button>
          <button onClick={onClose}>{t('prepExport.cancel')}</button>
        </div>
      </div>
    </div>
  );
}
