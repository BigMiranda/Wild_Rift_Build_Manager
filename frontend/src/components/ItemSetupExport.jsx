import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { itemEffectKey } from './ItemEffectsPanel.jsx';

/** Items of a build's steps that have modeled effects with a choice (option, conditional or rate), once each. */
export function configurableItems(steps, itemsById) {
  const seen = new Map();
  for (const s of steps ?? []) {
    const item = s.kind === 'moment' || s.kind === 'sell' ? null : itemsById.get(s.itemId);
    if (item?.effects?.some((e) => e.option || e.hasConditional || e.rate) && !seen.has(item.name)) seen.set(item.name, item);
  }
  return [...seen.values()];
}

/**
 * The choices of the given items as explicit values: options and conditionals as on screen (their defaults when never
 * changed) and rates as set, null when not set (the target goes back to no rate), so the targets end up like the source.
 */
export function explicitItemSetup(items, setup) {
  const out = { options: {}, conditional: {}, rates: {} };
  for (const item of items) {
    for (const e of item.effects) {
      const key = itemEffectKey(item.name, e.name);
      if (e.option) out.options[key] = setup?.options?.[key] ?? e.option.defaultValue;
      if (e.hasConditional) out.conditional[key] = setup?.conditional?.[key] ?? (e.defaultOn ?? true);
      if (e.rate) out.rates[key] = setup?.rates?.[key] ?? null;
    }
  }
  return out;
}

/** Item icons, the ones in `common` lit and the others dimmed (they do not change). */
function ItemIcons({ items, common, size = 24 }) {
  if (!items.length) return <small className="muted">{t('itemExport.noItems')}</small>;
  return (
    <span className="prep-export-icons">
      {items.map((it) => {
        const on = !common || common.has(it.name);
        return (
          <img key={it.name} className={`item-export-icon${on ? '' : ' off'}`} src={`/api/items/${it.id}/image`} alt={it.name}
            title={on ? it.name : t('itemExport.notShared', { name: it.name })} width={size} height={size} />
        );
      })}
    </span>
  );
}

/**
 * Copies the choices of the open build's item effects (options, conditional effects, stack rates) to other saved builds,
 * only for the items each one also has; the rest of their choices stay. Each build in the list shows its configurable
 * items, those in common with the open build lit.
 */
export default function ItemSetupExport({ steps, setup, itemsById, builds, folders, currentId, onClose, onDone }) {
  const [selected, setSelected] = useState(() => new Set());
  const [query, setQuery] = useState('');
  const [onlyCommon, setOnlyCommon] = useState(true);
  const [details, setDetails] = useState(null);  // id -> configurable items
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const source = useMemo(() => configurableItems(steps, itemsById), [steps, itemsById]);
  const sourceNames = useMemo(() => new Set(source.map((i) => i.name)), [source]);
  const others = builds.filter((b) => b.id !== currentId);

  // The folder tree has no items: each build's steps come from its own record.
  useEffect(() => {
    let alive = true;
    Promise.all(others.map((b) => api.get(`/api/builds/${b.id}`).then((f) => [b.id, configurableItems(f.steps, itemsById)]).catch(() => [b.id, []])))
      .then((list) => alive && setDetails(new Map(list)));
    return () => { alive = false; };
  }, [builds, currentId, itemsById]);

  const folderName = useMemo(() => new Map(folders.map((f) => [f.id, f.name])), [folders]);
  const plain = (s) => String(s ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  const shared = (id) => (details?.get(id) ?? []).filter((i) => sourceNames.has(i.name));
  const eligible = others.filter((b) => !onlyCommon || shared(b.id).length > 0);
  const shown = eligible.filter((b) => !query || plain(`${b.name} ${b.unitCode} ${folderName.get(b.folderId)}`).includes(plain(query)));
  const toggle = (id) => setSelected((s) => {
    const n = new Set(s);
    if (n.has(id)) n.delete(id); else n.add(id);
    return n;
  });
  const ids = [...selected].filter((id) => eligible.some((b) => b.id === id));

  const run = async () => {
    if (!ids.length) return;
    if (!window.confirm(t('itemExport.confirm', { n: ids.length }))) return;
    setBusy(true);
    setError(null);
    try {
      const r = await api.put('/api/builds/item-setup', { buildIds: ids, itemSetup: explicitItemSetup(source, setup) });
      onDone(r.updated);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="prep-overlay" role="dialog" aria-label={t('itemExport.title')}>
      <div className="prep-overlay-head">
        <button className="prep-back" onClick={onClose} aria-label={t('prep.back')}>
          <span aria-hidden="true">‹</span> {t('itemExport.title')}
        </button>
      </div>
      <div className="prep-export">
        <div className="prep-export-source">
          <span className="muted">{t('prepExport.from')}</span>
          <ItemIcons items={source} size={32} />
          <small className="muted">{t('prepExport.unsaved')}</small>
        </div>
        <p className="muted">{t('itemExport.help')}</p>
        <div className="row prep-export-what">
          <label className="check"><input type="checkbox" checked={onlyCommon} onChange={(e) => setOnlyCommon(e.target.checked)} />{t('itemExport.onlyCommon')}</label>
        </div>
        <div className="row prep-export-tools">
          <input type="search" value={query} placeholder={t('prepExport.search')} onChange={(e) => setQuery(e.target.value)} aria-label={t('prepExport.search')} />
          <button className="link" onClick={() => setSelected(new Set([...selected, ...shown.map((b) => b.id)]))}>{t('prepExport.all')}</button>
          <button className="link" onClick={() => setSelected(new Set())} disabled={!selected.size}>{t('prepExport.clear')}</button>
        </div>
        {!details ? <p className="muted">{t('itemExport.loading')}</p> : (
          <ul className="prep-export-list">
            {shown.map((b) => (
              <li key={b.id}>
                <label className={selected.has(b.id) ? 'on' : ''}>
                  <input type="checkbox" checked={selected.has(b.id)} onChange={() => toggle(b.id)} />
                  <span className="prep-export-name">
                    <strong>{b.name}</strong>
                    <small className="muted">{b.unitCode} · {folderName.get(b.folderId) ?? ''} · {t('itemExport.common', { n: shared(b.id).length })}</small>
                  </span>
                  <ItemIcons items={details.get(b.id) ?? []} common={sourceNames} />
                </label>
              </li>
            ))}
            {shown.length === 0 && <li className="muted">{t('prepExport.empty')}</li>}
          </ul>
        )}
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
