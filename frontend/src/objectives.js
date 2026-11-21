/**
 * Rodada 21, part 6: what the objectives give, the mirror of the backend's `engine/ObjectiveRules` (keep them in sync;
 * `test/objectives.test.mjs` checks both against the same fixture, `test/objetivos-paridade.json`). Pure: no React.
 *
 * `rules` is the "objetivos" section of `objetivos_<patch>.yml` as `/api/arena/rift-rules` sends it ({dragao, anciao,
 * mao_do_barao}; the older keys elementos / almas also work).
 */

/** The three elemental drakes of Wild Rift (prints of the user, 05/11/2026): no Cloud, Ice or Hextech. */
export const ELEMENTS = ['oceano', 'montanha', 'infernal'];
/** Seconds of trade the fight effects are valued in, and the share of health lost the Ocean drake is read at. */
export const TRADE = 10;
export const LOST = 0.5;

export const emptyObjectives = () => ({ dragons: {}, soul: null, elder: false, baron: false });

export const hasObjectives = (o) => Boolean(o && (Object.values(o.dragons ?? {}).some((n) => n > 0) || o.soul || o.elder || o.baron));

/** In Wild Rift a match has one drake of each element at most (3 in all, no repeats): any saved stack above 1 is 1. */
export const taken = (stacks) => Math.min(1, Math.max(0, Number(stacks) || 0));

/** The soul counts only with the three drakes taken. */
export const soulOf = (o) => (o?.soul && ELEMENTS.every((e) => taken(o.dragons?.[e]) > 0) ? o.soul : null);

const elementsOf = (rules) => rules?.dragao?.elementos ?? rules?.elementos ?? {};
const soulsOf = (rules) => rules?.dragao?.almas ?? rules?.almas ?? {};
const num = (v) => (typeof v === 'number' ? v : 0);

/**
 * What `stacks` drakes of an element add (ObjectiveRules.dragonDelta): the fixed lines first, then each % of the total,
 * read from the total with the other sources and the drakes' fixed part (Montanha: 4 + 3% of the total, per drake).
 */
export function dragonDelta(element, stacks, total) {
  const out = {};
  const lines = element?.status;
  if (!Array.isArray(lines) || !stacks) return out;
  lines.filter((l) => l.ratio == null).forEach((l) => { out[l.stat] = (out[l.stat] ?? 0) + num(l.valor) * stacks; });
  const fixed = { ...out };
  lines.filter((l) => l.ratio != null).forEach((l) => {
    const ref = l.ref ?? l.stat;
    out[l.stat] = (out[l.stat] ?? 0) + l.ratio * stacks * ((total?.[ref] ?? 0) + (fixed[ref] ?? 0));
  });
  return out;
}

/**
 * Each source of the objectives at a minute (ObjectiveRules.parts): [{ source, stacks, delta, fight, estimate }], the
 * drakes by element, the soul, the Elder, Baron. `total` / `base` are the stats before the objectives (the soul reads
 * the bonus stats). `fight` = { kind: dano | dano_verdadeiro | cura, value, seconds, each, times } in TRADE seconds.
 */
export function objectiveParts(objectives, rules, minute, total, base) {
  const parts = [];
  if (!hasObjectives(objectives) || !rules) return parts;
  const t = (k) => total?.[k] ?? 0;
  const bonus = (k) => Math.max(0, t(k) - (base?.[k] ?? 0));
  ELEMENTS.forEach((e) => {
    const n = taken(objectives.dragons?.[e]);
    if (n <= 0) return;
    const el = elementsOf(rules)[e] ?? {};
    const p = { source: e, stacks: n, delta: dragonDelta(el, n, total), fight: null, estimate: false };
    if (el.regeneracao_vida_perdida != null) {
      const every = el.a_cada ?? 5;
      const each = el.regeneracao_vida_perdida * LOST * t('Max Health') * n;
      const times = Math.floor(TRADE / every + 1e-9);
      p.fight = { kind: 'cura', each, times, value: each * times, seconds: TRADE };
    }
    parts.push(p);
  });
  const soul = soulOf(objectives);
  if (soul) {
    const s = soulsOf(rules)[soul] ?? {};
    const p = { source: `soul:${soul}`, stacks: null, delta: {}, fight: null, estimate: false };
    if (soul === 'montanha' && s.escudo != null) p.delta.Shield = s.escudo;
    if (soul === 'oceano' && s.cura) {
      const c = s.cura;
      const heal = num(c.base) + num(c.dda_bonus) * bonus('Attack Damage') + num(c.pdh) * t('Ability Power') + num(c.vida_bonus) * bonus('Max Health');
      p.delta.Heal = heal;
      p.fight = { kind: 'cura', each: heal, times: 1, value: heal, seconds: s.duracao ?? 4 };
    }
    if (soul === 'infernal' && s.dano) {
      const d = s.dano;
      const each = num(d.base) + num(d.dda_bonus) * bonus('Attack Damage') + num(d.pdh) * t('Ability Power');
      const times = Math.floor(TRADE / (s.recarga ?? 5) + 1e-9);
      p.fight = { kind: 'dano', each, times, value: each * times, seconds: TRADE };
    }
    parts.push(p);
  }
  if (objectives.elder) {
    const b = rules.anciao?.buff ?? {};
    const each = num(b.verdadeiro);
    const times = TRADE / (b.em ?? 3);
    parts.push({ source: 'elder', stacks: null, delta: {}, estimate: false, fight: { kind: 'dano_verdadeiro', each, times, value: each * times, seconds: TRADE } });
  }
  if (objectives.baron && rules.mao_do_barao) {
    const g = rules.mao_do_barao.dda_pdh_por_min ?? {};
    parts.push({
      source: 'baron', stacks: null, estimate: true, fight: null,
      delta: { 'Attack Damage': num(g.base_dda) + num(g.dda) * minute, 'Ability Power': num(g.base_pdh) + num(g.pdh) * minute },
    });
  }
  return parts;
}

/** Every part's stats summed: stat -> amount. */
export function objectiveDelta(parts) {
  const out = {};
  parts.forEach((p) => Object.entries(p.delta).forEach(([k, v]) => { out[k] = (out[k] ?? 0) + v; }));
  return out;
}

/** The stats of a point without what its objectives added (the backend's points already hold them). */
export function beforeObjectives(point) {
  if (!point) return { total: {}, base: {} };
  const total = { ...(point.total ?? {}) };
  Object.entries(point.objectiveDelta ?? {}).forEach(([k, v]) => { total[k] = (total[k] ?? 0) - v; });
  return { total, base: point.base ?? {} };
}
