import { createContext, useContext, useEffect, useState } from 'react';
import { statAbbr, statLabel, t } from '../i18n.js';
import { DERIVED_STATS, EFFECTIVE_STATS, mmss, n0, n1, pct, statOf, statValue } from '../format.js';
import { describeStats } from '../shopModel.js';
import ItemIcon from './ItemIcon.jsx';
import InventorySlots from './InventorySlots.jsx';
import RichText from './RichText.jsx';
import { StatIcon, statColor } from './StatIcon.jsx';

/** Opens an item in the dock's reader from anywhere on the page (reports, chart tooltips...): `open(itemId)`. */
export const ItemReaderContext = createContext(() => {});
export const useItemReader = () => useContext(ItemReaderContext);

/** Stats of the dock's collapsible row, then the effective ones held and the effective health. */
const DOCK_STATS = ['Max Health', 'Armor', 'Magic Resistance', 'Physical Reduction', 'Magic Reduction', 'Ability Haste',
  'Attack Damage', 'Ability Power', '% Attack Speed', 'Move Speed'];

const OPEN_KEY = 'ornn-planner-dock-stats';
const loadOpen = () => {
  try {
    return localStorage.getItem(OPEN_KEY) !== 'closed';
  } catch {
    return true;
  }
};

/**
 * The build always at hand: a bar fixed at the top of the page with the 6 inventory slots at the moment inspected (the
 * chart's selected minute, or the last purchase) and its stats in a collapsible row. Clicking an item opens its full
 * reading (stats, passives, modeled effects and what it adds at that moment) right there.
 */
export default function BuildDock({ point, itemsById, selected, readerId, onRead, onShop }) {
  const [statsOpen, setStatsOpen] = useState(loadOpen);
  const toggleStats = () => setStatsOpen((o) => {
    try {
      localStorage.setItem(OPEN_KEY, o ? 'closed' : 'open');
    } catch {
      /* storage unavailable: keep it for this session only */
    }
    return !o;
  });
  useEffect(() => {
    if (readerId == null) return undefined;
    const onKey = (e) => { if (e.key === 'Escape') onRead(null); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [readerId, onRead]);

  const ids = (point?.contributions ?? []).filter((c) => !c.rune).map((c) => c.itemId);
  const total = point?.total ?? {};
  const reader = readerId != null ? itemsById.get(readerId) : null;
  return (
    <div className="build-dock" role="region" aria-label={t('dock.title')}>
      <div className="dock-row">
        <span className="dock-when">
          <strong>{t('dock.title')}</strong>
          {point ? <small>{mmss(point.minute)} · {t('bar.lv', { n: point.level })}{selected ? ` · ${t('dock.selected')}` : ''}</small>
            : <small className="muted">{t('dock.empty')}</small>}
        </span>
        <InventorySlots ids={ids} itemsById={itemsById} size={34} picked={readerId}
          onPick={(id) => onRead(readerId === id ? null : id)} />
        <button className="link dock-shop" onClick={onShop}>{t('dock.shop')}</button>
        {point && (
          <button className={`opt dock-toggle${statsOpen ? ' on' : ''}`} aria-expanded={statsOpen} onClick={toggleStats}>
            {t('dock.stats')} {statsOpen ? '▴' : '▾'}
          </button>
        )}
      </div>
      {point && statsOpen && (
        <div className="dock-stats">
          {DOCK_STATS.map((s) => {
            const v = statOf(total, s) ?? 0;
            const base = DERIVED_STATS[s] ? null : point.base?.[s];
            return (
              <span key={s} className="dock-stat" style={{ color: statColor(s) }}
                title={`${statLabel(s)}${base != null && Math.abs(v - base) > 0.05 ? ` — ${n0(base)} + ${n0(v - base)}` : ''}`}>
                <StatIcon stat={s} size={13} />{statAbbr(s)} <strong>{statValue(s, v)}</strong>
              </span>
            );
          })}
          {EFFECTIVE_STATS.filter((s) => (total[s] ?? 0) > 0.05).map((s) => (
            <span key={s} className="dock-stat effective" style={{ color: statColor(s) }} title={statLabel(s)}>
              <StatIcon stat={s} size={13} />{statAbbr(s)} <strong>{statValue(s, total[s])}</strong>
            </span>
          ))}
          {(total.Shield ?? 0) + (total.Heal ?? 0) > 0 && (
            <span className="dock-stat effective" style={{ color: statColor('Max Health') }} title={t('sheet.effectiveHelp')}>
              <StatIcon stat="Max Health" size={13} />{t('dock.effHealth')} <strong>{n0((total['Max Health'] ?? 0) + (total.Shield ?? 0) + (total.Heal ?? 0))}</strong>
            </span>
          )}
        </div>
      )}
      {reader && (
        <ItemReader item={reader} point={point} onClose={() => onRead(null)} />
      )}
    </div>
  );
}

/** Full reading of an item, as in the shop, plus what it adds to the build at the moment inspected. */
export function ItemReader({ item, point, onClose }) {
  const { flat, modeled } = describeStats(item);
  const held = (point?.contributions ?? []).filter((c) => !c.rune && c.itemId === item.id);
  const gold = held.reduce((a, c) => a + c.flatGold + c.passiveGold, 0);
  const parts = held.flatMap((c) => c.passiveParts ?? []);
  return (
    <div className="item-reader" role="dialog" aria-label={item.name}>
      <header className="detail-head">
        <ItemIcon id={item.id} size={48} />
        <div>
          <h3>{item.name}</h3>
          <div className="gold">{t('shop.gold', { n: n0(item.cost) })}{item.active ? ` · ${t('shop.active')}` : ''}</div>
        </div>
        <button className="icon reader-close" onClick={onClose} aria-label={t('close')}>✕</button>
      </header>
      <div className="reader-body">
        <div>
          <ul className="stat-list">
            {flat.map((s, i) => (
              <li key={i} style={{ color: statColor(s.type) }}>
                <StatIcon stat={s.type} />+{statValue(s.type, s.value)} {statLabel(s.type)}
              </li>
            ))}
          </ul>
          {item.passives.map((p, i) => (
            <p className="passive" key={i}>
              {p.name && <span className="passive-name">{p.name}: </span>}<RichText text={p.text} />
            </p>
          ))}
        </div>
        <div>
          {(modeled.length > 0 || item.effects?.length > 0) && (
            <div className="modeled">
              <h4>{t('shop.modeled')}</h4>
              <ul>
                {modeled.map((m, i) => (
                  <li key={i}>
                    <StatIcon stat={m.stat} /><span className="passive-name">{m.name}:</span>{' '}
                    <span style={{ color: statColor(m.stat) }}>{m.text}</span>
                    {m.conditional && <small className="cond"> · {t('shop.conditional')}</small>}
                  </li>
                ))}
                {(item.effects ?? []).map((e) => (
                  <li key={e.name}>
                    <span className="passive-name">{e.name}:</span> {e.summary.join(' · ')}
                    {e.note && <small className="muted"> — {e.note}</small>}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {point && (
            <div className="reader-value">
              <h4>{t('dock.valueAt', { time: mmss(point.minute) })}</h4>
              {held.length === 0 ? <small className="muted">{t('dock.notHeld')}</small> : (
                <>
                  <p>{t('eff.worthOfCost', { worth: n0(gold), cost: n0(item.cost) })}{item.cost > 0 ? ` · ${pct((gold / item.cost) * 100)}` : ''}</p>
                  <ul>
                    {parts.map((p, i) => (
                      <li key={i} style={{ color: statColor(p.stat) }}>
                        <StatIcon stat={p.stat} />{p.passive}: +{n1(p.amount)} {statLabel(p.stat)} = {n0(p.gold)}
                        {p.conditional && <small className="cond"> · {t('shop.conditional')}</small>}
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
