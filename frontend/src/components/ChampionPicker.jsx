import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';

/** Champion summaries for the picker (code, nome, titulo, recurso, habilidades, efeitos), loaded once. */
let cache = null;
export function loadChampions() {
  if (!cache) cache = api.get('/api/champions').catch((e) => { cache = null; throw e; });
  return cache;
}

const plain = (s) => String(s ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();

/** Portrait placeholder: initials on a color derived from the name (an `src` image takes its place when there is one). */
export function ChampionAvatar({ name, src, size = 40 }) {
  if (src) return <img className="champ-avatar" src={src} alt="" width={size} height={size} />;
  let h = 0;
  for (const c of String(name ?? '')) h = (h * 31 + c.charCodeAt(0)) % 360;
  const words = String(name ?? '?').replace(/[^\p{L}\s]/gu, ' ').trim().split(/\s+/);
  const initials = (words.length > 1 ? words[0][0] + words[1][0] : words[0].slice(0, 2)).toUpperCase();
  return (
    <span className="champ-avatar" aria-hidden="true"
      style={{ width: size, height: size, fontSize: size * 0.38, background: `hsl(${h} 35% 30%)`, borderColor: `hsl(${h} 45% 50%)` }}>
      {initials}
    </span>
  );
}

/**
 * Unit field of the build: the current champion as a button, opening a searchable grid of every champion (name,
 * title or the name of a passive / ability) plus the other units (ragdoll).
 */
export default function ChampionPicker({ units, value, onChange }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [champs, setChamps] = useState([]);
  const input = useRef(null);
  useEffect(() => { loadChampions().then(setChamps).catch(() => setChamps([])); }, []);
  useEffect(() => {
    if (!open) return undefined;
    setQuery('');
    setTimeout(() => input.current?.focus(), 0);
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  const byCode = useMemo(() => new Map(champs.map((c) => [c.code, c])), [champs]);
  const entries = useMemo(() => units.map((u) => ({ ...u, champ: byCode.get(u.code) })), [units, byCode]);
  const shown = useMemo(() => {
    const q = plain(query).trim();
    if (!q) return entries;
    return entries.filter((e) => [e.name, e.champ?.titulo, ...(e.champ?.habilidades ?? [])].some((s) => plain(s).includes(q)));
  }, [entries, query]);
  const current = entries.find((e) => e.code === value);
  const pick = (code) => { onChange(code); setOpen(false); };

  return (
    <>
      <button type="button" className="champ-pick-btn" onClick={() => setOpen(true)} title={t('picker.change')}>
        <ChampionAvatar name={current?.name} size={26} />
        <span className="champ-pick-name">{current?.name ?? value}</span>
        <span aria-hidden="true">▾</span>
      </button>
      {open && (
        <div className="prep-overlay champ-picker" role="dialog" aria-label={t('picker.title')}>
          <div className="prep-overlay-head">
            <button className="prep-back" onClick={() => setOpen(false)} aria-label={t('prep.back')}>
              <span aria-hidden="true">‹</span> {t('picker.title')}
            </button>
            <input ref={input} className="champ-search" type="search" value={query} placeholder={t('picker.search')}
              aria-label={t('picker.search')} onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && shown[0]) pick(shown[0].code); }} />
            <small className="muted">{t('picker.count', { n: shown.length })}</small>
          </div>
          <ul className="champ-grid">
            {shown.map((e) => {
              const hit = query && e.champ?.habilidades?.find((s) => plain(s).includes(plain(query).trim()));
              return (
                <li key={e.code}>
                  <button className={`champ-tile${e.code === value ? ' selected' : ''}`} onClick={() => pick(e.code)}
                    title={e.champ ? `${e.name} — ${e.champ.titulo}\n${e.champ.habilidades.join(' · ')}` : e.name}>
                    <ChampionAvatar name={e.name} size={56} />
                    <span className="champ-tile-name">{e.name}</span>
                    <small className="champ-tile-title">{hit || e.champ?.titulo || ''}</small>
                    {e.champ?.efeitos > 0 && <span className="champ-tile-badge" title={t('champ.effectsCount', { n: e.champ.efeitos })}>{e.champ.efeitos}</span>}
                  </button>
                </li>
              );
            })}
          </ul>
          {!shown.length && <p className="muted">{t('picker.none')}</p>}
        </div>
      )}
    </>
  );
}
