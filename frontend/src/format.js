import { currentLang } from './i18n.js';

export { statLabel, statAbbr } from './i18n.js';

/** Stats shown in the timeline table and selectable in the chart. */
export const TABLE_STATS = ['Max Health', 'Armor', 'Magic Resistance', 'Ability Haste', 'Health Regen', 'Ability Power', 'Attack Damage'];

/** Stats of the champion sheet (in-game stats tab order). */
export const SHEET_STATS = [
  'Attack Damage', 'Ability Power', 'Max Health', 'Max Mana',
  'Armor', 'Magic Resistance', '% Attack Speed', 'Ability Haste',
  '% Critical Rate', 'Health Regen', 'Mana Regen', 'Move Speed',
  'Armor Penetration', '% Armor Penetration', 'Magic Penetration', '% Magic Penetration',
  '% Lifesteal', '% Tenacity', '% Heal and shield strength', '% Move Speed',
];

/** Stats whose value is a percentage. */
export const PERCENT_STATS = new Set(['% Attack Speed', '% Critical Rate', '% Armor Penetration', '% Magic Penetration',
  '% Lifesteal', '% Tenacity', '% Heal and shield strength', '% Move Speed', '% Health Regen', '% Mana Regeneration']);

const fmt = (digits) => (v) => {
  if (v == null || Number.isNaN(v)) return '—';
  return new Intl.NumberFormat(currentLang() === 'en' ? 'en-US' : 'pt-BR', { maximumFractionDigits: digits }).format(v);
};

export const n0 = fmt(0);
export const n1 = fmt(1);
export const n2 = fmt(2);
export const pct = (v) => (v == null || Number.isNaN(v) ? '—' : `${n1(v)}%`);

/** A stat value with its unit (percent stats get a % sign). */
export const statValue = (stat, v) => (PERCENT_STATS.has(stat) ? `${n1(v)}%` : stat.includes('Regen') ? n1(v) : n0(v));

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
