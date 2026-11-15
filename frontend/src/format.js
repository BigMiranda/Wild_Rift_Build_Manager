import { currentLang } from './i18n.js';

export { statLabel, statAbbr } from './i18n.js';

/** Stats shown in the timeline table. */
export const TABLE_STATS = ['Max Health', 'Armor', 'Magic Resistance', 'Ability Haste', 'Health Regen', 'Ability Power', 'Attack Damage'];

/**
 * Damage reduction from a resistance, as in the game: resistance / (100 + resistance).
 * 100 armor = 50% less physical damage. Negative resistance increases damage: 2 - 100 / (100 - resistance).
 */
export const damageReduction = (res) => (res >= 0 ? (res / (100 + res)) * 100 : (1 - 100 / (100 - res)) * 100);

/** Stats computed from others (not returned by the engine). */
export const DERIVED_STATS = {
  'Physical Reduction': (totals) => damageReduction(totals.Armor ?? 0),
  'Magic Reduction': (totals) => damageReduction(totals['Magic Resistance'] ?? 0),
};

/** Value of a stat from a totals map, including derived ones. */
export const statOf = (totals, stat) => (DERIVED_STATS[stat] ? DERIVED_STATS[stat](totals) : totals[stat]);

/** Stats selectable in the chart. */
export const CHART_STATS = [...TABLE_STATS.slice(0, 3), 'Physical Reduction', 'Magic Reduction', ...TABLE_STATS.slice(3),
  'Shield', 'Heal'];

/** Stats of the champion sheet (in-game stats tab order). */
export const SHEET_STATS = [
  'Attack Damage', 'Ability Power', 'Max Health', 'Max Mana',
  'Armor', 'Magic Resistance', 'Physical Reduction', 'Magic Reduction',
  '% Attack Speed', 'Ability Haste',
  '% Critical Rate', 'Health Regen', 'Mana Regen', 'Move Speed',
  'Armor Penetration', '% Armor Penetration', 'Magic Penetration', '% Magic Penetration',
  '% Lifesteal', '% Omnivamp', '% Tenacity', '% Heal and shield strength', '% Move Speed',
];

/**
 * Effective stats of modeled effects (items, runes, champion): what shields, heals, stasis and ability haste of one
 * ability are worth in a fight. They never change the max health or the ability haste shown.
 */
export const EFFECTIVE_STATS = ['Shield', 'Magic Shield', 'Physical Shield', 'Heal', 'Ally Shield', 'Ally Heal', 'Ultimate Haste', 'Basic Ability Haste',
  '% Basic Cooldown Reduction', '% Ultimate Cooldown Reduction', '% Ability 1 Cooldown Reduction', '% Ability 2 Cooldown Reduction',
  '% Ability 3 Cooldown Reduction', 'Stasis'];

/** % cooldown reduction of one ability only (slot 1, 2 or 3). */
export const abilityCdr = (slot) => `% Ability ${slot} Cooldown Reduction`;

/** Two % amounts of a stat whose sources each cut what is left (Tenacity, cooldown reductions): as Stats.combine. */
const cutTogether = (a, b) => 100 - ((100 - a) * (100 - b)) / 100;

/**
 * How an ability's cooldown is cut at a point (mirrors Stats.cooldownFactor): haste = Ability Haste + Ultimate / Basic
 * Ability Haste, pct = the % reductions (of the ultimate, or of the basics and of that ability, combined), factor = the
 * share of the base cooldown it waits, cut = how much less in %, effHaste = the haste that alone would cut as much.
 */
export function cooldownCut(total, slot) {
  const v = (s) => total?.[s] ?? 0;
  const ult = slot === 'R';
  const haste = v('Ability Haste') + v(ult ? 'Ultimate Haste' : 'Basic Ability Haste');
  const pct = ult ? v('% Ultimate Cooldown Reduction')
    : cutTogether(v('% Basic Cooldown Reduction'), slot ? v(abilityCdr(slot)) : 0);
  const factor = (1 / (1 + Math.max(0, haste) / 100)) * (1 - Math.max(0, Math.min(90, pct)) / 100);
  return { haste, pct, factor, cut: (1 - factor) * 100, effHaste: (1 / factor - 1) * 100 };
}

/** Penetration stats (flat armor penetration is the game's lethality): shown in the dock whenever they are not 0. */
export const PENETRATION_STATS = ['Armor Penetration', '% Armor Penetration', 'Magic Penetration', '% Magic Penetration'];

/**
 * Stats of a series point that left the champion's own value at that level (no items or runes: `point.base`), other
 * than the ones in `shown`, in TABLE_STATS order and then the sheet's: [{stat, total, base, bonus}]. Penetrations count
 * whenever they are not 0. Effective and derived stats are never extras.
 */
export function extraStats(point, shown = []) {
  if (!point?.total) return [];
  const skip = new Set([...shown, ...EFFECTIVE_STATS, ...Object.keys(DERIVED_STATS)]);
  const order = [...new Set([...TABLE_STATS, ...SHEET_STATS, ...Object.keys(point.total)])];
  const out = [];
  for (const stat of order) {
    if (skip.has(stat)) continue;
    const total = point.total[stat] ?? 0;
    const base = point.base?.[stat] ?? 0;
    const pen = PENETRATION_STATS.includes(stat);
    if (pen ? Math.abs(total) > 0.05 : Math.abs(total - base) > 0.05) out.push({ stat, total, base, bonus: total - base });
  }
  return out;
}

/** Stats whose value is a percentage. */
export const PERCENT_STATS = new Set(['Physical Reduction', 'Magic Reduction', '% Attack Speed', '% Critical Rate', '% Armor Penetration', '% Magic Penetration',
  '% Lifesteal', '% Omnivamp', '% Tenacity', '% Heal and shield strength', '% Move Speed', '% Health Regen', '% Mana Regeneration',
  '% Basic Cooldown Reduction', '% Ultimate Cooldown Reduction', '% Ability 1 Cooldown Reduction', '% Ability 2 Cooldown Reduction',
  '% Ability 3 Cooldown Reduction']);

const fmt = (digits) => (v) => {
  if (v == null || Number.isNaN(v)) return '—';
  return new Intl.NumberFormat(currentLang() === 'en' ? 'en-US' : 'pt-BR', { maximumFractionDigits: digits }).format(v);
};

export const n0 = fmt(0);
export const n1 = fmt(1);
export const n2 = fmt(2);
export const pct = (v) => (v == null || Number.isNaN(v) ? '—' : `${n1(v)}%`);

/** A stat value with its unit (percent stats get a % sign). */
export const statValue = (stat, v) => (PERCENT_STATS.has(stat) ? `${n1(v)}%` : stat === 'Stasis' ? `${n1(v)}s`
  : stat.includes('Regen') ? n1(v) : n0(v));

/** 12.57 -> "12:34" */
export function mmss(minutes) {
  if (minutes == null) return '—';
  const total = Math.round(minutes * 60);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

/**
 * Categorical series colors, fixed order (never cycled): one color per build, following the entity.
 * Validated palette from the dataviz reference instance (dark-surface steps).
 */
export const SERIES_COLORS = [
  'var(--series-1)', 'var(--series-2)', 'var(--series-3)', 'var(--series-4)',
  'var(--series-5)', 'var(--series-6)', 'var(--series-7)', 'var(--series-8)',
];
