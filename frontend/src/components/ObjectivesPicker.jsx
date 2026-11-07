import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';

/** The three elemental drakes of Wild Rift (prints of the user, 05/11/2026): no Cloud, Ice or Hextech. */
export const ELEMENTS = ['oceano', 'montanha', 'infernal'];

let cached = null;
/** The numbers of the objectives (`objetivos_<patch>.yml`), read once. */
export function useObjectiveRules() {
  const [rules, setRules] = useState(cached);
  useEffect(() => {
    if (cached) return;
    api.get('/api/arena/rift-rules').then((r) => { cached = r; setRules(r); }).catch(() => {});
  }, []);
  return rules;
}

export const emptyObjectives = () => ({ dragons: {}, soul: null, elder: false, baron: false });

export const hasObjectives = (o) => Boolean(o && (Object.values(o.dragons ?? {}).some((n) => n > 0) || o.soul || o.elder || o.baron));

/**
 * What `stacks` drakes of an element add (mirror of RiftRules.dragonDelta): the fixed lines first, then each % of the
 * total, read from the total with the other sources and the drakes' fixed part (Montanha: 4 + 3% of the total, per drake).
 */
export function dragonDelta(element, stacks, total) {
  const out = {};
  const lines = element?.status;
  if (!Array.isArray(lines) || !stacks) return out;
  lines.filter((l) => l.ratio == null).forEach((l) => { out[l.stat] = (out[l.stat] ?? 0) + (l.valor ?? 0) * stacks; });
  const fixed = { ...out };
  lines.filter((l) => l.ratio != null).forEach((l) => {
    const ref = l.ref ?? l.stat;
    out[l.stat] = (out[l.stat] ?? 0) + l.ratio * stacks * ((total?.[ref] ?? 0) + (fixed[ref] ?? 0));
  });
  return out;
}

/** Each source of the objectives with what it adds: [{ source, stacks, delta }], the drakes by element, then Baron. */
export function objectiveParts(objectives, rules, minute, total) {
  const parts = [];
  if (!hasObjectives(objectives) || !rules) return parts;
  Object.entries(objectives.dragons ?? {}).forEach(([element, stacks]) => {
    const delta = dragonDelta(rules.elementos?.[element], stacks, total);
    if (Object.keys(delta).length) parts.push({ source: element, stacks, delta });
  });
  if (objectives.baron && rules.mao_do_barao) {
    const g = rules.mao_do_barao.dda_pdh_por_min;
    parts.push({ source: 'baron', delta: { 'Attack Damage': g.base_dda + g.dda * minute, 'Ability Power': g.base_pdh + g.pdh * minute } });
  }
  return parts;
}

/** What the objectives add to the stats at a minute: stat -> amount, by the same rule as the fight. */
export function objectiveDelta(objectives, rules, minute, total) {
  const out = {};
  objectiveParts(objectives, rules, minute, total).forEach((p) => {
    Object.entries(p.delta).forEach(([k, v]) => { out[k] = (out[k] ?? 0) + v; });
  });
  return out;
}

/** The point of a minute with the objectives on top: the sheet and the bar read it as any other. */
export function withObjectives(point, objectives, rules) {
  if (!point || !hasObjectives(objectives) || !rules) return point;
  const parts = objectiveParts(objectives, rules, point.minute, point.total);
  const delta = objectiveDelta(objectives, rules, point.minute, point.total);
  const total = { ...point.total };
  Object.entries(delta).forEach(([k, v]) => { total[k] = (total[k] ?? 0) + v; });
  return { ...point, total, objectiveDelta: delta, objectiveParts: parts };
}

/** Drake stacks by element, the soul, the Elder and the Hand of Baron. */
export default function ObjectivesPicker({ value, onChange, compact }) {
  const o = value ?? emptyObjectives();
  const set = (patch) => onChange({ ...o, ...patch });
  const setStacks = (element, n) => set({ dragons: { ...o.dragons, [element]: n } });
  return (
    <div className={`objectives-picker${compact ? ' compact' : ''}`} title={t('objectives.help')}>
      <strong>{t('objectives.title')}</strong>
      {ELEMENTS.map((e) => (
        <label key={e} className="inline">{t(`objectives.${e}`)}
          <select value={o.dragons?.[e] ?? 0} onChange={(ev) => setStacks(e, Number(ev.target.value))} aria-label={t(`objectives.${e}`)}>
            {[0, 1, 2, 3].map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
      ))}
      <label className="inline">{t('objectives.soul')}
        <select value={o.soul ?? ''} onChange={(ev) => set({ soul: ev.target.value || null })} aria-label={t('objectives.soul')}>
          <option value="">—</option>
          {ELEMENTS.map((e) => <option key={e} value={e}>{t(`objectives.${e}`)}</option>)}
        </select>
      </label>
      <label className="check"><input type="checkbox" checked={o.elder} onChange={(ev) => set({ elder: ev.target.checked })} />{t('objectives.elder')}</label>
      <label className="check"><input type="checkbox" checked={o.baron} onChange={(ev) => set({ baron: ev.target.checked })} />{t('objectives.baron')}</label>
    </div>
  );
}
