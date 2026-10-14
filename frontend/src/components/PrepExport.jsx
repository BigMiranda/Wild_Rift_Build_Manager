import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { findSpell, pageSlots, runeIcon, spellIcon } from '../runes.js';

/**
 * The page's settings (options, conditional effects, stack rates, gold) only for the runes it has: a page keeps the
 * settings of runes swapped out, and those must not travel to other builds.
 */
export function exportablePage(page) {
  const names = new Set(pageSlots(page).map((s) => s.rune?.name).filter(Boolean));
  const only = (m) => Object.fromEntries(Object.entries(m ?? {}).filter(([k]) => names.has(k)));
  return {
    ...page,
    primaryRunes: [...(page.primaryRunes ?? [null, null, null])],
    options: only(page.options),
    conditional: only(page.conditional),
    rates: only(page.rates),
    gold: only(page.gold),
  };
}

/** A build's runes and spells as small icons: only the ones it has. */
function PrepIcons({ page, spells, size = 26 }) {
  const runes = pageSlots(page).filter((s) => s.rune);
  const sp = (spells ?? []).map(findSpell).filter(Boolean);
  if (!runes.length && !sp.length) return <small className="muted">{t('prepExport.none')}</small>;
  return (
    <span className="prep-export-icons">
      {runes.map((s) => <img key={s.key} className="rune-img on" src={runeIcon(s.rune)} alt={s.rune.name} title={s.rune.name} width={size} height={size} />)}
      {sp.length > 0 && runes.length > 0 && <span className="prep-export-sep" aria-hidden="true" />}
      {sp.map((s) => <img key={s.name} className="spell-img" src={spellIcon(s)} alt={s.name} title={s.name} width={size} height={size} />)}
    </span>
  );
}

/**
 * Copies the open build's rune page (with the settings of its runes) and / or its summoner spells to other saved
 * builds. Each build in the list shows only the runes and spells it has now, so it is clear what gets replaced.
 */
export default function PrepExport({ page, spells, builds, folders, currentId, onClose, onDone }) {
  const [selected, setSelected] = useState(() => new Set());
  const [query, setQuery] = useState('');
  const [withRunes, setWithRunes] = useState(true);
  const [withSpells, setWithSpells] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const folderName = useMemo(() => new Map(folders.map((f) => [f.id, f.name])), [folders]);
  const plain = (s) => String(s ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  const others = builds.filter((b) => b.id !== currentId);
  const shown = others.filter((b) => !query || plain(`${b.name} ${b.unitCode} ${folderName.get(b.folderId)}`).includes(plain(query)));
  const toggle = (id) => setSelected((s) => {
    const n = new Set(s);
    if (n.has(id)) n.delete(id); else n.add(id);
    return n;
  });
  const hasSpells = (spells ?? []).some(Boolean);
  const nothing = !withRunes && !withSpells;

  const run = async () => {
    const ids = [...selected];
    if (!ids.length || nothing) return;
    if (!window.confirm(t('prepExport.confirm', { n: ids.length }))) return;
    setBusy(true);
    setError(null);
    try {
      const r = await api.put('/api/builds/prep', {
        buildIds: ids,
        runePage: withRunes ? exportablePage(page) : null,
        spells: withSpells ? (spells ?? []).filter(Boolean) : null,
      });
      onDone(r.updated);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="prep-overlay" role="dialog" aria-label={t('prepExport.title')}>
      <div className="prep-overlay-head">
        <button className="prep-back" onClick={onClose} aria-label={t('prep.back')}>
          <span aria-hidden="true">‹</span> {t('prepExport.title')}
        </button>
      </div>
      <div className="prep-export">
        <div className="prep-export-source">
          <span className="muted">{t('prepExport.from')}</span>
          <PrepIcons page={page} spells={spells} size={34} />
          <small className="muted">{t('prepExport.unsaved')}</small>
        </div>
        <div className="row prep-export-what">
          <label className="check"><input type="checkbox" checked={withRunes} onChange={(e) => setWithRunes(e.target.checked)} />{t('prepExport.runes')}</label>
          <label className="check"><input type="checkbox" checked={withSpells} disabled={!hasSpells} onChange={(e) => setWithSpells(e.target.checked)} />{t('prepExport.spells')}</label>
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
                <PrepIcons page={b.runePage} spells={b.spells} />
              </label>
            </li>
          ))}
          {shown.length === 0 && <li className="muted">{t('prepExport.empty')}</li>}
        </ul>
        {error && <p className="error">{error}</p>}
        <div className="row">
          <button className="primary" onClick={run} disabled={busy || !selected.size || nothing}>
            {busy ? t('prepExport.running') : t('prepExport.run', { n: selected.size })}
          </button>
          <button onClick={onClose}>{t('prepExport.cancel')}</button>
        </div>
      </div>
    </div>
  );
}
