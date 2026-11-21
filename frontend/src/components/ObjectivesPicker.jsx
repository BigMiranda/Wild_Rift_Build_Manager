import { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { n0 } from '../format.js';
import {
  ELEMENTS, TRADE, beforeObjectives, emptyObjectives, hasObjectives, objectiveDelta, objectiveParts, soulOf, taken,
} from '../objectives.js';

export { ELEMENTS, emptyObjectives, hasObjectives };

let cached = null;
/** The rules of the objectives (the "objetivos" section of `objetivos_<patch>.yml`), read once. */
export function useObjectiveRules() {
  const [rules, setRules] = useState(cached);
  useEffect(() => {
    if (cached) return;
    api.get('/api/arena/rift-rules').then((r) => { cached = r; setRules(r); }).catch(() => {});
  }, []);
  return rules;
}

/** The point of a minute with the objectives on top, computed here (only where the backend did not: old results). */
export function withObjectives(point, objectives, rules) {
  if (!point || point.objectiveParts || !hasObjectives(objectives) || !rules) return point;
  const parts = objectiveParts(objectives, rules, point.minute, point.total, point.base);
  const delta = objectiveDelta(parts);
  const total = { ...point.total };
  Object.entries(delta).forEach(([k, v]) => { total[k] = (total[k] ?? 0) + v; });
  return { ...point, total, objectiveDelta: delta, objectiveParts: parts };
}

const COLORS = { oceano: '#3b8fd9', montanha: '#a0784a', infernal: '#e0632e', elder: '#8f6ad8', baron: '#9b4fc4' };

/** Simple icons, one per objective, until the game's own come (the user sends them). */
export function ObjectiveIcon({ kind, size = 22, soul = false }) {
  const c = COLORS[kind] ?? 'currentColor';
  const ring = soul ? <circle cx="12" cy="12" r="10.5" fill="none" stroke={c} strokeWidth="1.6" strokeDasharray="3 2" /> : null;
  const body = {
    oceano: <path d="M12 3c3 4 6 7.5 6 11a6 6 0 0 1-12 0c0-3.5 3-7 6-11z" fill={c} />,
    montanha: <path d="M2.5 19 9 7l3.5 6 2-3L21.5 19z" fill={c} />,
    infernal: <path d="M12 2.5c1 3.5 5 5.5 5 10.5a5 5 0 0 1-10 0c0-2.5 1.5-3.8 2.4-5.2.3 1.6 1 2.6 2 3.1C11 8.4 11.3 5.3 12 2.5z" fill={c} />,
    elder: <path d="m12 2.5 2.6 6.2 6.6.5-5 4.3 1.6 6.5L12 16.5 6.2 20l1.6-6.5-5-4.3 6.6-.5z" fill={c} />,
    baron: <g fill={c}><ellipse cx="12" cy="12" rx="9.5" ry="6" /><circle cx="12" cy="12" r="3" fill="#fff" /><circle cx="12" cy="12" r="1.4" fill={c} /></g>,
  }[kind];
  return (
    <svg className="obj-icon" width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      {ring}
      <g transform={soul ? 'translate(4.8 4.8) scale(.6)' : undefined}>{body}</g>
    </svg>
  );
}

const fmtDelta = (delta) => Object.entries(delta ?? {}).filter(([, v]) => Math.abs(v) >= 0.5)
  .map(([k, v]) => `+${n0(v)} ${t(`objectives.stat.${k}`) === `objectives.stat.${k}` ? k : t(`objectives.stat.${k}`)}`).join(', ');

/** One line: what the objective gives now, with the values at the minute when there is a build. */
function lineOf(part, key, withBuild) {
  if (!withBuild || !part) return t(`objectives.line.${key}`);
  const stats = key === 'soul:oceano' ? '' : fmtDelta(part.delta);
  const f = part.fight;
  const fight = f ? t(`objectives.fight.${f.kind}`, { v: n0(f.value), s: n0(f.seconds) }) : '';
  return [stats, fight].filter(Boolean).join(' · ') || t(`objectives.line.${key}`);
}

/** The whole description: the game's effect, the value with this build, and where the numbers come from. */
function fullOf(part, key, rules, withBuild) {
  const lines = [`${nameOf(key)}: ${t(`objectives.full.${key}`)}`];
  if (withBuild && part) lines.push(t('objectives.withBuild', { what: lineOf(part, key, true) }));
  const src = sourceOf(key, rules);
  if (src) lines.push(t('objectives.source', { src }));
  if (key === 'baron') lines.push(t('objectives.baronEst'));
  return lines.join('\n');
}

function sourceOf(key, rules) {
  if (!rules) return null;
  const drake = rules.dragao ?? {};
  if (ELEMENTS.includes(key)) return (drake.elementos ?? rules.elementos)?.[key]?.fonte;
  if (key.startsWith('soul:')) return (drake.almas ?? rules.almas)?.[key.slice(5)]?.fonte;
  if (key === 'elder') return rules.anciao?.buff?.fonte;
  if (key === 'baron') return rules.mao_do_barao?.dda_pdh_por_min?.fonte;
  return null;
}

export const nameOf = (key) => (key.startsWith('soul:') ? t('objectives.soulOf', { element: t(`objectives.${key.slice(5)}`) }) : t(`objectives.${key}`));

/**
 * Rodada 21, part 6: the objectives, one component for the planner, the Arena, the effectiveness and Draft e partida.
 * A card per objective (icon, name, the effect in one line with its value at the minute); the three drakes side by side;
 * the soul opens only with the three, to choose among them; Elder and Baron on one strip. Holding a card (or hovering
 * it) shows the whole description, with the value with the open build and the source. `point` (optional) is the minute
 * read by the planner: with it the values are the build's.
 */
export default function ObjectivesPicker({ value, onChange, compact, point, readOnly }) {
  const o = value ?? emptyObjectives();
  const rules = useObjectiveRules();
  const [open, setOpen] = useState(null);
  const hold = useRef(null);
  const set = (patch) => !readOnly && onChange({ ...o, ...patch });
  const allThree = ELEMENTS.every((e) => taken(o.dragons?.[e]) > 0);
  const soul = soulOf(o);
  const before = point ? beforeObjectives(point) : null;
  const minute = point?.minute ?? 0;
  // what each objective gives alone (on or off), so an off card says what it would give
  const alone = (objectives, key) => (rules ? objectiveParts(objectives, rules, minute, before?.total ?? {}, before?.base ?? {})
    .find((p) => p.source === key) : null);
  const three = { oceano: 1, montanha: 1, infernal: 1 };

  const press = (key) => ({
    onPointerDown: () => { clearTimeout(hold.current); hold.current = setTimeout(() => setOpen(key), 450); },
    onPointerUp: () => clearTimeout(hold.current),
    onPointerLeave: () => { clearTimeout(hold.current); setOpen((k) => (k === key ? null : k)); },
    onMouseEnter: () => setOpen(key),
    onContextMenu: (e) => e.preventDefault(),
  });

  const card = (key, on, toggle, part, { disabled, kind, isSoul, small } = {}) => (
    <div key={key} className={`obj-card${on ? ' on' : ''}${disabled ? ' disabled' : ''}${small ? ' small' : ''}`} {...press(key)}>
      <button type="button" aria-pressed={on} disabled={disabled || readOnly} onClick={toggle}
        title={fullOf(part, key, rules, !!point)} aria-describedby={open === key ? `obj-pop-${key}` : undefined}>
        <ObjectiveIcon kind={kind ?? key} soul={isSoul} size={small ? 18 : 24} />
        <span className="obj-text">
          <strong>{small ? t(`objectives.${kind}`) : nameOf(key)}</strong>
          {!small && <small>{lineOf(part, key, !!point)}</small>}
        </span>
      </button>
      {open === key && (
        <div className="obj-pop" role="tooltip" id={`obj-pop-${key}`}>{fullOf(part, key, rules, !!point)}</div>
      )}
    </div>
  );

  return (
    <div className={`obj-picker${compact ? ' compact' : ''}`} aria-label={t('objectives.title')}>
      <div className="obj-head"><strong>{t('objectives.title')}</strong>
        {point && <small className="muted"> {t('objectives.atMinute', { s: TRADE })}</small>}</div>
      <div className="obj-row obj-drakes">
        {ELEMENTS.map((e) => {
          const on = taken(o.dragons?.[e]) > 0;
          return card(e, on, () => set({ dragons: { ...o.dragons, [e]: on ? 0 : 1 } }), alone({ dragons: { [e]: 1 } }, e));
        })}
      </div>
      <div className={`obj-row obj-soul${allThree ? '' : ' locked'}`} title={allThree ? undefined : t('objectives.soulHelp')}>
        <span className="obj-label">{t('objectives.soul')}{!allThree && <small className="muted"> · {t('objectives.soulLocked')}</small>}</span>
        {ELEMENTS.map((e) => card(`soul:${e}`, soul === e, () => set({ soul: soul === e ? null : e }),
          alone({ dragons: three, soul: e }, `soul:${e}`), { disabled: !allThree, kind: e, isSoul: true, small: compact && !allThree }))}
      </div>
      <div className="obj-row obj-big">
        {card('elder', !!o.elder, () => set({ elder: !o.elder }), alone({ elder: true }, 'elder'))}
        {card('baron', !!o.baron, () => set({ baron: !o.baron }), alone({ baron: true }, 'baron'))}
      </div>
    </div>
  );
}
