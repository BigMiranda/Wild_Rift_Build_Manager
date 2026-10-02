export const STAT_LABELS = {
  'Max Health': 'Vida',
  Armor: 'Armadura',
  'Magic Resistance': 'Resist. Mágica',
  'Ability Haste': 'Aceleração de Hab.',
  'Health Regen': 'Regen. de vida (/5s)',
  'Ability Power': 'Poder de Hab.',
  'Attack Damage': 'Dano de Ataque',
  'Max Mana': 'Mana',
  'Mana Regen': 'Regen. de mana (/5s)',
  'Move Speed': 'Vel. de movimento',
  '% Health Regen': '% Regen. de vida base',
  '% Mana Regeneration': '% Regen. de mana base',
};

export const SHORT_LABELS = {
  'Max Health': 'Vida',
  Armor: 'Arm',
  'Magic Resistance': 'RM',
  'Ability Haste': 'AH',
  'Health Regen': 'Regen',
  'Ability Power': 'AP',
  'Attack Damage': 'AD',
};

/** Stats shown in the timeline table and selectable in the chart. */
export const TABLE_STATS = ['Max Health', 'Armor', 'Magic Resistance', 'Ability Haste', 'Health Regen', 'Ability Power', 'Attack Damage'];

export const statLabel = (s) => STAT_LABELS[s] ?? s;

const nf0 = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 0 });
const nf1 = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 });
const nf2 = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2 });

export const n0 = (v) => (v == null || Number.isNaN(v) ? '—' : nf0.format(v));
export const n1 = (v) => (v == null || Number.isNaN(v) ? '—' : nf1.format(v));
export const n2 = (v) => (v == null || Number.isNaN(v) ? '—' : nf2.format(v));
export const pct = (v) => (v == null || Number.isNaN(v) ? '—' : `${nf1.format(v)}%`);

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
 * Validated palette from the dataviz reference instance; the dark column is used in dark mode.
 */
export const SERIES_COLORS = [
  'var(--series-1)', 'var(--series-2)', 'var(--series-3)', 'var(--series-4)',
  'var(--series-5)', 'var(--series-6)', 'var(--series-7)', 'var(--series-8)',
];
