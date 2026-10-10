import { t } from '../i18n.js';
import { EffectOptions } from './RuneEditor.jsx';

export const emptyItemSetup = () => ({ options: {}, conditional: {}, rates: {} });

/** Key of an item effect in the build's choices (same as the backend's ItemEffectCatalog.key). */
export const itemEffectKey = (item, effect) => `${item}|${effect}`;

/**
 * Modeled effects of the items in the build (shields and heals as temporary health, ultimate haste, stasis, combat
 * bonuses...): each one switched on or off for every purchase of the item, with its option (activations per fight,
 * allies...) and stack rate. Effects without a choice are listed as always on.
 */
export default function ItemEffectsPanel({ steps, itemsById, setup, matchEnd, onChange }) {
  const seen = new Set();
  const items = [];
  for (const s of steps) {
    const item = s.kind === 'moment' ? null : itemsById.get(s.itemId);
    if (item?.effects?.length && !seen.has(item.name)) {
      seen.add(item.name);
      items.push(item);
    }
  }
  if (!items.length) return null;
  const state = setup ?? emptyItemSetup();
  const entries = [];
  const fixed = [];
  for (const item of items) {
    for (const e of item.effects) {
      const entry = {
        ...e, name: itemEffectKey(item.name, e.name), label: `${item.name} · ${e.name}`,
        iconSrc: `/api/items/${item.id}/image`, iconClass: 'item',
      };
      if (e.option || e.hasConditional || e.rate) entries.push(entry);
      else fixed.push(entry);
    }
  }
  return (
    <details className="item-effects" open>
      <summary>{t('itemFx.title', { n: entries.length + fixed.length })}</summary>
      <p className="muted">{t('itemFx.help')}</p>
      {fixed.length > 0 && (
        <ul className="item-effects-fixed">
          {fixed.map((e) => (
            <li key={e.name}>
              <img src={e.iconSrc} alt="" width={18} height={18} />
              <strong>{e.label}</strong> <small className="muted">{e.summary.join(' · ')}</small>
            </li>
          ))}
        </ul>
      )}
      <EffectOptions entries={entries} state={state} set={(patch) => onChange({ ...state, ...patch })} matchEnd={matchEnd}
        icon={(r) => r.iconSrc} details />
    </details>
  );
}
