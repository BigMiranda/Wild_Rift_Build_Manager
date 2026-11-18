import { useEffect, useMemo, useRef, useState } from 'react';
import { t } from '../i18n.js';
import { buildTiles } from '../shopModel.js';
import { emptyRunePage, findRune, findSpell, runeCatalog, runeIcon, spellIcon } from '../runes.js';
import { ChampionAvatar } from './ChampionAvatar.jsx';
import ItemIcon from './ItemIcon.jsx';
import ShopBrowser from './ShopBrowser.jsx';
import RuneEditor from './RuneEditor.jsx';
import { LANES, buy } from '../liveGame.js';

const plain = (s) => String(s ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();

/**
 * Rodada 20: a champion grid with search, the unavailable ones (bans, picks, Fearless) greyed out, the lane filter on
 * top. onPick(name).
 */
export function ChampionGrid({ champions, lanesOf, unavailable = new Set(), fearless = new Set(), value, onPick, lane: startLane }) {
  const [query, setQuery] = useState('');
  const [lane, setLane] = useState(startLane ?? null);
  const input = useRef(null);
  useEffect(() => { setTimeout(() => input.current?.focus(), 0); }, []);
  const shown = useMemo(() => {
    const q = plain(query).trim();
    return champions.filter((c) => (!q || plain(c.nome).includes(q)) && (!lane || (lanesOf(c.nome) ?? []).includes(lane)));
  }, [champions, query, lane, lanesOf]);
  return (
    <>
      <div className="live-grid-tools">
        <input ref={input} type="search" value={query} placeholder={t('picker.search')} aria-label={t('picker.search')}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            const first = shown.find((c) => !unavailable.has(c.nome) || c.nome === value);
            if (e.key === 'Enter' && first) onPick(first.nome);
          }} />
        <div className="chips">
          <button type="button" className={!lane ? 'active' : ''} onClick={() => setLane(null)}>{t('live.allLanes')}</button>
          {LANES.map((l) => (
            <button key={l} type="button" className={lane === l ? 'active' : ''} onClick={() => setLane(l)}>{l}</button>
          ))}
        </div>
      </div>
      <ul className="champ-grid live-champ-grid">
        {shown.map((c) => {
          const off = unavailable.has(c.nome) && c.nome !== value;
          return (
            <li key={c.code}>
              <button type="button" className={`champ-tile${c.nome === value ? ' selected' : ''}${off ? ' live-off' : ''}`} disabled={off}
                title={fearless.has(c.nome) ? t('live.fearlessUsed') : off ? t('live.unavailable') : c.nome} onClick={() => onPick(c.nome)}>
                <ChampionAvatar name={c.nome} size={52} />
                <span className="champ-tile-name">{c.nome}</span>
                <small className="champ-tile-title">{(lanesOf(c.nome) ?? []).join(' · ')}</small>
              </button>
            </li>
          );
        })}
      </ul>
    </>
  );
}

const TABS = ['campeao', 'feiticos', 'runas', 'itens', 'placar'];

/**
 * Rodada 20: a player's card opened in the shop's style: champion, lane, spells, runes, items (purchases in order,
 * components apart, the starting item) and the score (level, gold, K/D/A). Every change goes through onChange(player).
 */
export default function LivePlayerEditor({ player, side, champions, lanesOf, unavailable, fearless, items, onChange, onClose, startTab }) {
  const [tab, setTab] = useState(startTab ?? (player.champion ? 'itens' : 'campeao'));
  const [runes, setRunes] = useState(false);
  const [startMode, setStartMode] = useState(false);
  const tiles = useMemo(() => buildTiles(items), [items]);
  const byId = useMemo(() => new Map(items.map((i) => [i.id, i])), [items]);
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape' && !runes) onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, runes]);
  const set = (patch) => onChange({ ...player, ...patch });
  const cat = runeCatalog();
  const page = { ...emptyRunePage(), ...Object.fromEntries(Object.entries(player.runes ?? {}).filter(([, v]) => v != null)) };
  if (!page.primaryRunes || page.primaryRunes.length < 3) page.primaryRunes = [...(page.primaryRunes ?? []), null, null, null].slice(0, 3);
  const toggleSpell = (name) => {
    const s = player.spells ?? [];
    if (s.includes(name)) set({ spells: s.filter((x) => x !== name) });
    else set({ spells: [...s, name].slice(-2) });
  };
  const stepper = (key, label, step = 1) => (
    <div className="live-stepper">
      <span>{label}</span>
      <button type="button" onClick={() => set({ [key]: Math.max(0, (player[key] ?? 0) - step) })} aria-label={`${label} −`}>−</button>
      <input type="number" inputMode="numeric" value={player[key] ?? ''} placeholder="—"
        onChange={(e) => set({ [key]: e.target.value === '' ? null : Number(e.target.value) })} />
      <button type="button" onClick={() => set({ [key]: (player[key] ?? 0) + step })} aria-label={`${label} +`}>+</button>
    </div>
  );

  if (runes) {
    return (
      <RuneEditor page={page} onClose={() => setRunes(false)} onChange={(p) => set({
        runes: { keystone: p.keystone, primary: p.primary, secondary: p.secondary, primaryRunes: p.primaryRunes, secondaryRune: p.secondaryRune },
      })} />
    );
  }
  return (
    <div className="prep-overlay live-editor" role="dialog" aria-label={player.champion ?? t('live.emptySlot')}>
      <div className="prep-overlay-head">
        <button className="prep-back" onClick={onClose} aria-label={t('prep.back')}>
          <span aria-hidden="true">‹</span> <ChampionAvatar name={player.champion} size={28} /> {player.champion ?? t('live.emptySlot')}
          <small className={`live-side-tag ${side}`}>{t(`live.side.${side}`)}</small>
        </button>
        <div className="chips live-tabs" role="tablist">
          {TABS.map((k) => (
            <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'active' : ''} onClick={() => setTab(k)}>
              {t(`live.tab.${k}`)}
            </button>
          ))}
        </div>
      </div>

      {tab === 'campeao' && (
        <>
          <div className="chips live-lane-pick">
            <span className="muted">{t('live.lane')}</span>
            {LANES.map((l) => (
              <button key={l} type="button" className={player.lane === l ? 'active' : ''} onClick={() => set({ lane: l })}>{l}</button>
            ))}
            {player.champion && <button type="button" className="danger" onClick={() => set({ champion: null })}>{t('live.clearChampion')}</button>}
          </div>
          <ChampionGrid champions={champions} lanesOf={lanesOf} unavailable={unavailable} fearless={fearless} value={player.champion}
            lane={player.champion ? null : player.lane}
            onPick={(name) => { set({ champion: name }); setTab('feiticos'); }} />
        </>
      )}

      {tab === 'feiticos' && (
        <>
          <p className="muted small">{t('live.spellsHelp')}</p>
          <div className="live-spells">
            {(cat?.spells ?? []).map((s) => (
              <button key={s.name} type="button" className={`live-spell${(player.spells ?? []).includes(s.name) ? ' active' : ''}`}
                aria-pressed={(player.spells ?? []).includes(s.name)} onClick={() => toggleSpell(s.name)}>
                <img src={spellIcon(s)} alt="" width={44} height={44} />
                <span>{s.name}</span>
              </button>
            ))}
          </div>
          <div className="row">
            <button type="button" onClick={() => setRunes(true)}>{t('live.openRunes')}</button>
          </div>
        </>
      )}

      {tab === 'runas' && (
        <>
          <div className="live-runes-view">
            {[page.keystone, ...page.primaryRunes, page.secondaryRune].map((name, i) => {
              const r = findRune(name);
              return (
                <span key={i} className="live-rune" title={name ?? '—'}>
                  {r ? <img src={runeIcon(r)} alt="" width={i === 0 ? 48 : 34} height={i === 0 ? 48 : 34} /> : <span className="item-icon empty" style={{ width: 34, height: 34 }} />}
                  <small>{name ?? '—'}</small>
                </span>
              );
            })}
          </div>
          <button type="button" className="primary" onClick={() => setRunes(true)}>{t('live.editRunes')}</button>
        </>
      )}

      {tab === 'itens' && (
        <>
          <div className="live-held">
            <span className="muted small">{t('live.items')}</span>
            {(player.items ?? []).map((id, k) => (
              <button key={`${id}-${k}`} type="button" className="chip-item" title={t('picker.remove', { name: byId.get(id)?.name ?? id })}
                onClick={() => set({ items: player.items.filter((_, j) => j !== k) })}>
                <ItemIcon id={id} size={22} /> {byId.get(id)?.name ?? id} ✕
              </button>
            ))}
            {(player.components ?? []).length > 0 && <span className="muted small">{t('live.components')}</span>}
            {(player.components ?? []).map((id, k) => (
              <button key={`c${id}-${k}`} type="button" className="chip-item live-comp" onClick={() => set({ components: player.components.filter((_, j) => j !== k) })}>
                <ItemIcon id={id} size={18} /> {byId.get(id)?.name ?? id} ✕
              </button>
            ))}
            {player.startItem != null && (
              <button type="button" className="chip-item live-start" onClick={() => set({ startItem: null })} title={t('live.startItemHelp')}>
                {t('live.startItem')}: <ItemIcon id={player.startItem} size={18} /> {byId.get(player.startItem)?.name} ✕
              </button>
            )}
          </div>
          <div className="row">
            <p className="muted small" style={{ flex: 1 }}>{t('live.buyHelp')}</p>
            <button type="button" className={startMode ? 'active' : ''} aria-pressed={startMode} onClick={() => setStartMode((v) => !v)}>
              {t('live.markStart')}
            </button>
          </div>
          <div className="shop picker-shop live-shop">
            <ShopBrowser tiles={tiles} iconSize={52}
              marked={(x) => x.variants.some((v) => (player.items ?? []).includes(v.id))}
              tileProps={(x) => ({
                type: 'button',
                title: x.name,
                onClick: (e) => {
                  const id = x.preferredId;
                  if (startMode || e.altKey) {
                    set({ startItem: id });
                    setStartMode(false);
                    return;
                  }
                  onChange({ ...player, ...buyInto(player, id, items) });
                },
              })}
            />
          </div>
        </>
      )}

      {tab === 'placar' && (
        <div className="live-score-edit">
          {stepper('level', t('live.level'))}
          {stepper('gold', t('live.gold'), 100)}
          {stepper('kills', t('live.kills'))}
          {stepper('deaths', t('live.deaths'))}
          {stepper('assists', t('live.assists'))}
        </div>
      )}
    </div>
  );
}

/** A purchase made in the editor (the same rule as the quick action). */
function buyInto(player, id, items) {
  return buy(JSON.parse(JSON.stringify(player)), id, items);
}

/** Small row of a player's spells and runes, for the cards. */
export function SpellRuneIcons({ player, size = 18 }) {
  return (
    <span className="live-icons">
      {(player.spells ?? []).map((s) => {
        const sp = findSpell(s);
        return sp ? <img key={s} src={spellIcon(sp)} alt={s} title={s} width={size} height={size} /> : <small key={s}>{s}</small>;
      })}
      {player.runes?.keystone && findRune(player.runes.keystone) && (
        <img src={runeIcon(findRune(player.runes.keystone))} alt={player.runes.keystone} title={player.runes.keystone} width={size} height={size} />
      )}
    </span>
  );
}

