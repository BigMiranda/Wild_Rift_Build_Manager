import { createContext, useContext, useEffect, useState } from 'react';
import { statAbbr, statLabel, t } from '../i18n.js';
import { DERIVED_STATS, EFFECTIVE_STATS, extraStats, mmss, n0, n1, pct, statOf, statValue } from '../format.js';
import { describeStats } from '../shopModel.js';
import ItemIcon from './ItemIcon.jsx';
import { useEffectValues, valuesOf } from '../effectValues.js';
import { findRune, runeIcon } from '../runes.js';
import InventorySlots from './InventorySlots.jsx';
import RichText from './RichText.jsx';
import { saleAccount } from './BuildBar.jsx';
import { StatIcon, statColor } from './StatIcon.jsx';
import { effectiveGold } from '../effective.js';
import { PassiveSource } from './PassiveValueBar.jsx';

/** Opens an item in the dock's reader from anywhere on the page (reports, chart tooltips...): `open(itemId)`. */
export const ItemReaderContext = createContext(() => {});
export const useItemReader = () => useContext(ItemReaderContext);

/**
 * Stats of the dock's collapsible row, always shown; then every other stat that left the champion's own value at that
 * level (penetrations whenever not 0: `extraStats`), the effective ones held and the effective health.
 */
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
/** One effect's value line in the bar (the damage type's color on the left), with how it adds up. */
function DockValue({ v }) {
  return (
    <li className={`effect-value${v.type ? ` dmg-${v.type}` : ''}`}>
      {v.text}
      {v.detail && <small className="effect-detail">{v.detail}</small>}
    </li>
  );
}

const PASSIVES_KEY = 'ornn-planner-dock-passives';

/**
 * The sale timed to the next item that the moment inspected waits for (between the previous purchase and the sale),
 * with the gold in hand at that moment and what is still missing.
 */
function PendingSale({ point, sales }) {
  const m = point.minute;
  const s = sales.find((x) => m + 1e-6 >= x.saleTiming.previousMinute && (x.saleTiming.reachedAt == null || m <= x.minute + 1e-6));
  if (!s) return null;
  const missing = s.saleTiming.goldNeeded - (point.goldInHand ?? 0);
  return (
    <div className="dock-sale" title={saleAccount(s)}>
      <ItemIcon id={s.itemId} size={18} />→<ItemIcon id={s.saleTiming.nextItemId} size={18} />
      <span>{t('sell.dockLine', { gold: n0(point.goldInHand ?? 0), need: n0(s.saleTiming.goldNeeded) })}</span>
      <strong>{missing > 0.5 ? t('sell.dockMissing', { missing: n0(missing) }) : t('sell.dockReady')}</strong>
      <small className="muted">{s.saleTiming.reachedAt != null ? t('sell.reachedAt', { time: mmss(s.saleTiming.reachedAt) }) : t('sell.never')}</small>
    </div>
  );
}

export default function BuildDock({ point, itemsById, selected, readerId, onRead, onShop, draft, sales = [], testInArena, overlay, prices }) {
  const [statsOpen, setStatsOpen] = useState(loadOpen);
  const [passivesOpen, setPassivesOpen] = useState(() => {
    try {
      return localStorage.getItem(PASSIVES_KEY) === 'open';
    } catch {
      return false;
    }
  });
  const togglePassives = () => setPassivesOpen((o) => {
    try {
      localStorage.setItem(PASSIVES_KEY, o ? 'closed' : 'open');
    } catch {
      /* this session only */
    }
    return !o;
  });
  const { values, stale } = useEffectValues(point ? draft : null, point?.minute);
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
        {testInArena}
        {point && (
          <button className={`opt dock-toggle${passivesOpen ? ' on' : ''}`} aria-expanded={passivesOpen} onClick={togglePassives}
            title={t('passives.valuesTitle')}>
            {t('dock.passives')} {passivesOpen ? '▴' : '▾'}
          </button>
        )}
        {point && (
          <button className={`opt dock-toggle${statsOpen ? ' on' : ''}`} aria-expanded={statsOpen} onClick={toggleStats}>
            {t('dock.stats')} {statsOpen ? '▴' : '▾'}
          </button>
        )}
      </div>
      {point && sales.length > 0 && <PendingSale point={point} sales={sales} />}
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
          {extraStats(point, DOCK_STATS).map((x) => (
            <span key={x.stat} className="dock-stat extra" style={{ color: statColor(x.stat) }}
              title={t('dock.extraTitle', { stat: statLabel(x.stat), total: statValue(x.stat, x.total), base: statValue(x.stat, x.base) })}>
              <StatIcon stat={x.stat} size={13} />{statAbbr(x.stat)} <strong>{x.bonus >= 0 ? '+' : '−'}{statValue(x.stat, Math.abs(x.bonus))}</strong>
            </span>
          ))}
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
      {point && passivesOpen && (
        <div className={`dock-passives${stale ? ' stale' : ''}`}>
          <small className="dock-passives-head muted">
            {t('dock.passivesAt', { time: mmss(point.minute), level: point.level })}{stale ? ` · ${t('dock.passivesLoading')}` : ''}
          </small>
          {values == null && !stale && <small className="muted">{t('dock.passivesLoading')}</small>}
          {values != null && values.length === 0 && <small className="muted">{t('passives.none')}</small>}
          {[...new Set((values ?? []).map((v) => v.source))].map((source) => {
            const item = [...itemsById.values()].find((i) => i.name === source && ids.includes(i.id));
            const rune = item ? null : findRune(source);
            return (
              <div key={source} className="dock-passive">
                <span className="dock-passive-src" title={source}>
                  {item ? <ItemIcon id={item.id} size={20} /> : rune ? <img src={runeIcon(rune)} alt="" width={20} height={20} className="rune-img on" /> : null}
                  {source}
                </span>
                <ul className="effect-values">{valuesOf(values, source).map((v, k) => <DockValue key={k} v={v} />)}</ul>
              </div>
            );
          })}
        </div>
      )}
      {reader && (
        <ItemReader item={reader} point={point} values={valuesOf(values, reader.name)} onClose={() => onRead(null)} overlay={overlay} prices={prices} />
      )}
    </div>
  );
}

/** Full reading of an item, as in the shop, plus what it adds to the build at the moment inspected. */
export function ItemReader({ item, point, onClose, values = [], overlay, prices }) {
  const { flat, modeled } = describeStats(item);
  const held = (point?.contributions ?? []).filter((c) => !c.rune && c.itemId === item.id);
  // with the passive values on, the item's worth is the effective gold's; by tier, what it adds in stats
  const eff = overlay && point ? effectiveGold(point, new Map([[item.id, item]]), prices, overlay).items.filter((x) => x.itemId === item.id) : [];
  const gold = eff.length ? eff.reduce((a, x) => a + x.effective, 0) : held.reduce((a, c) => a + c.flatGold + c.passiveGold, 0);
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
                  {eff.flatMap((x) => x.passives).filter((p) => p.source && !p.known).map((p) => (
                    <p key={p.name} className="passive" title={p.detail ?? undefined}>{p.name}: {n0(p.gold)} <PassiveSource passive={p} /></p>
                  ))}
                  {values.length > 0 && (
                    <ul className="effect-values" title={t('passives.valuesTitle')}>
                      {values.map((v, k) => <DockValue key={k} v={v} />)}
                    </ul>
                  )}
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
