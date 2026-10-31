import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';

export const ELEMENTS = ['infernal', 'montanha', 'oceano', 'gelo'];

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

/** What the objectives add to the stats at a minute: stat -> amount, by the same rule as the fight. */
export function objectiveDelta(objectives, rules, minute, total) {
  const out = {};
  if (!hasObjectives(objectives) || !rules) return out;
  const add = (k, v) => { out[k] = (out[k] ?? 0) + v; };
  Object.entries(objectives.dragons ?? {}).forEach(([element, stacks]) => {
    const status = rules.elementos?.[element]?.status;
    if (!status || !stacks) return;
    Object.entries(status).forEach(([stat, list]) => {
      const v = list[Math.min(2, stacks - 1)];
      if (stat.startsWith('% ')) add(stat.slice(2), ((total?.[stat.slice(2)] ?? 0) * v) / 100);
      else add(stat, v);
    });
  });
  if (objectives.baron && rules.mao_do_barao) {
    const g = rules.mao_do_barao.dda_pdh_por_min;
    add('Attack Damage', g.base_dda + g.dda * minute);
    add('Ability Power', g.base_pdh + g.pdh * minute);
  }
  return out;
}

/** The point of a minute with the objectives on top: the sheet and the bar read it as any other. */
export function withObjectives(point, objectives, rules) {
  if (!point || !hasObjectives(objectives) || !rules) return point;
  const delta = objectiveDelta(objectives, rules, point.minute, point.total);
  const total = { ...point.total };
  Object.entries(delta).forEach(([k, v]) => { total[k] = (total[k] ?? 0) + v; });
  return { ...point, total, objectiveDelta: delta };
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
