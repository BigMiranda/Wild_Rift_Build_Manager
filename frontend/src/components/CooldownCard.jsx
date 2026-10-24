import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { n0, n1 } from '../format.js';
import { findSpell, spellIcon } from '../runes.js';
import { planOf, rankOf } from '../skills.js';
import { pointAt } from './MomentPanels.jsx';
import { Ability } from './ChampionPanel.jsx';
import { ItemReader } from './BuildDock.jsx';
import ItemIcon from './ItemIcon.jsx';

export const ABILITY_KEYS = ['1', '2', '3', 'R'];

/** Item id of an item active of a fighter (by name), or null. */
const itemIdOf = (fighter, name) => fighter?.items?.find((i) => i.name === name)?.id ?? null;

/** Short tag of a cooldown: the slot for abilities, a 16 px icon for items and spells. */
export function CooldownTag({ fighter, name, size = 16 }) {
  if (ABILITY_KEYS.includes(name)) return <span className="cd-slot" style={{ width: size, height: size }}>{name}</span>;
  const id = itemIdOf(fighter, name);
  if (id != null) return <ItemIcon id={id} size={size} />;
  const spell = findSpell(name);
  if (spell) return <img className="spell-img" src={spellIcon(spell)} alt="" width={size} height={size} />;
  return <span className="cd-slot" style={{ width: size, height: size }}>◆</span>;
}

/** Display name of a cooldown key: the ability's name for 1/2/3/R, else the item or spell name. */
export const cooldownName = (fighter, key) => (ABILITY_KEYS.includes(key)
  ? fighter?.abilities?.find((a) => a.slot === key)?.name ?? key : key);

/** "352 damage (Rumble 352) · stun 1,00s on Rumble · 300 shield" — what one use did. */
function useText(u) {
  const parts = [];
  if (u.damage >= 0.5) {
    const by = Object.entries(u.targets ?? {}).map(([n, d]) => `${n} ${n0(d)}`).join(', ');
    parts.push(t('arena.card.damage', { n: n0(u.damage), by }));
  }
  (u.cc ?? []).forEach((c) => parts.push(c));
  if (u.shield >= 0.5) parts.push(t('arena.card.shield', { n: n0(u.shield) }));
  return parts.length ? parts.join(' · ') : t('arena.card.nothing');
}

/**
 * Card of one cooldown (ability, item active or summoner spell) of one fighter at a second of the fight, opened next to
 * where it was clicked: its state (ready, or how long it still waits of how long), charges, the last use and what it
 * did, the next use; "More details" shows the ability as the planner does (rank, values with the champion's stats at
 * the build's minute, cooldown with ability haste), or the planner's item reader.
 */
export default function CooldownCard({ result, sel, time, frame, bars, buildOf, onClose }) {
  const ref = useRef(null);
  const [pos, setPos] = useState({ left: sel.x, top: sel.y });
  const [more, setMore] = useState(false);
  useEffect(() => setMore(false), [sel.fighter, sel.key]);
  useLayoutEffect(() => {
    // Next to the click, kept inside the window (a phone's width included).
    const box = ref.current?.getBoundingClientRect();
    if (!box) return;
    const left = Math.max(8, Math.min(sel.x + 12, window.innerWidth - box.width - 8));
    const top = Math.max(8, Math.min(sel.y + 12, window.innerHeight - box.height - 8));
    setPos({ left, top });
  }, [sel.x, sel.y, more]);
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const f = result.fighters[sel.fighter];
  const unit = frame?.units?.[sel.fighter];
  const key = sel.key;
  const name = cooldownName(f, key);
  const left = unit?.cooldowns?.[key];
  const bar = (bars ?? []).find(([a, b]) => time >= a - 1e-6 && time <= b + 1e-6);
  const full = bar ? bar[1] - bar[0] : f.abilities?.find((a) => a.slot === key)?.cooldown ?? f.actives?.[key];
  const charges = unit?.charges?.[key];
  const uses = (f.uses ?? []).filter((u) => u.key === key);
  const last = [...uses].reverse().find((u) => u.t <= time + 1e-6);
  const next = uses.find((u) => u.t > time + 1e-6);
  return (
    <div className="cd-card" ref={ref} role="dialog" aria-label={`${name} · ${f.champion}`}
      style={{ left: pos.left, top: pos.top }} onClick={(e) => e.stopPropagation()}>
      <header className="cd-card-head">
        <CooldownTag fighter={f} name={key} size={28} />
        <div>
          <strong>{name}</strong>
          <small className="muted"> · {sel.fighter + 1} · {f.champion}</small>
          <div><small className="muted">{t('arena.card.at', { s: n1(time) })}</small></div>
        </div>
        <button className="icon" onClick={onClose} aria-label={t('close')}>✕</button>
      </header>
      <p className={left > 0.05 ? 'cd-state waiting' : 'cd-state ready'}>
        {left == null ? t('arena.card.unknown')
          : left > 0.05 ? t('arena.card.waiting', { left: n1(left), full: full != null ? n1(full) : '—' })
          : t('arena.card.ready')}
        {charges != null && <small> · {t('arena.card.charges', { n: charges })}</small>}
      </p>
      <p>
        {last ? <><strong>{t('arena.card.last', { s: n1(last.t) })}</strong> {useText(last)}</> : <span className="muted">{t('arena.card.never')}</span>}
      </p>
      <p className="muted">{next ? t('arena.card.next', { s: n1(next.t) }) : t('arena.card.noNext')}</p>
      <button className="link" onClick={() => setMore((m) => !m)} aria-expanded={more}>
        {more ? t('arena.card.less') : t('arena.card.more')}
      </button>
      {more && <CardDetails fighter={f} cdKey={key} build={buildOf(sel.fighter)} />}
    </div>
  );
}

const cache = new Map();
const cached = (url) => {
  if (!cache.has(url)) {
    const p = api.get(url);
    p.catch(() => cache.delete(url));
    cache.set(url, p);
  }
  return cache.get(url);
};

/**
 * The planner's reading of the cooldown: the ability text with the rank of the build's plan at the champion's level and
 * values with its stats at the build's minute, or the item reader, or the spell's text.
 */
function CardDetails({ fighter, cdKey, build }) {
  const [data, setData] = useState(null);
  const ability = ABILITY_KEYS.includes(cdKey);
  useEffect(() => {
    let live = true;
    setData(null);
    if (!build) return undefined;
    Promise.all([
      cached(`/api/builds/${build.id}`),
      cached(`/api/builds/${build.id}/timeline`),
      ability ? cached(`/api/champions/${build.unitCode}`) : cached('/api/items'),
    ]).then(([b, tl, extra]) => { if (live) setData({ b, tl, extra }); }).catch(() => { if (live) setData({ error: true }); });
    return () => { live = false; };
  }, [build?.id, build?.updatedAt, ability]);
  const spell = !ability ? findSpell(cdKey) : null;
  if (spell) {
    return (
      <div className="cd-card-more">
        <p><img className="spell-img" src={spellIcon(spell)} alt="" width={20} height={20} /> {t('spells.cooldown', { s: spell.cooldown })}</p>
        <p className="rune-desc">{spell.text}</p>
      </div>
    );
  }
  if (!build) return <p className="muted">{t('arena.card.noBuild')}</p>;
  if (!data) return <p className="muted">{t('compare.calculating')}</p>;
  if (data.error) return <p className="error">{t('arena.card.noDetails')}</p>;
  const minute = fighter.minute ?? data.tl.series.at(-1)?.minute ?? 0;
  const point = pointAt(data.tl.series, minute);
  if (!ability) {
    const item = data.extra.find((i) => i.name === cdKey);
    return item ? <div className="cd-card-more"><ItemReader item={item} point={point} onClose={() => {}} /></div>
      : <p className="muted">{t('arena.card.noDetails')}</p>;
  }
  const champ = data.extra;
  const idx = ABILITY_KEYS.indexOf(cdKey);
  const a = champ?.habilidades?.[idx];
  if (!a) return <p className="muted">{t('arena.card.noDetails')}</p>;
  const level = fighter.level ?? point?.level ?? 1;
  const plan = planOf(data.b.championSetup);
  const arenaCd = fighter.abilities?.find((x) => x.slot === cdKey)?.cooldown;
  const haste = point?.total?.['Ability Haste'] ?? 0;
  return (
    <div className="cd-card-more">
      <Ability a={a} tag={cdKey} stats={point ? { total: point.total, base: point.base } : null}
        planRank={rankOf(plan, cdKey, level)} level={level} />
      {arenaCd != null && <p className="muted">{t('arena.card.haste', { s: n1(arenaCd), ah: n0(haste) })}</p>}
    </div>
  );
}
