import { statAbbr, statLabel } from '../i18n.js';

/**
 * Stat colors and icons in the spirit of the Wild Rift UI (green health, yellow armor, cyan MR, orange AD, purple AP).
 * Icons are simple original SVGs; colors are CSS variables so they can be tuned in one place (styles.css).
 */
const ICONS = {
  heart: 'M8 14.2 2.3 8.6C.6 6.9.8 4.2 2.7 3c1.6-1 3.6-.6 4.7.9L8 4.7l.6-.8c1.1-1.5 3.1-1.9 4.7-.9 1.9 1.2 2.1 3.9.4 5.6Z',
  drop: 'M8 1.5c2.6 3.3 4.6 5.9 4.6 8.4A4.6 4.6 0 0 1 3.4 9.9C3.4 7.4 5.4 4.8 8 1.5Z',
  blade: 'M13.8 1.6 7.2 8.2l1.6 1.6 6.6-6.6.2-1.8Zm-7.6 7.7L4.6 7.7 3.5 8.8l1.1 1.1-2.7 2.7.9.9 2.7-2.7 1.1 1.1 1.1-1.1Z',
  feather: 'M14.2 1.8C9.4 2 5.3 5.4 4.3 10.2L2 14l3.8-2.3c4.8-1 8.2-5.1 8.4-9.9ZM6 10l3.5-3.5',
  shield: 'M8 1.2 2.6 3.1v4.3c0 3.3 2.3 6.1 5.4 7.4 3.1-1.3 5.4-4.1 5.4-7.4V3.1Z',
  ring: 'M8 2a6 6 0 1 0 0 12A6 6 0 0 0 8 2Zm0 3.2a2.8 2.8 0 1 1 0 5.6 2.8 2.8 0 0 1 0-5.6Z',
  hourglass: 'M3.5 1.5h9v1.4c0 2-1.4 3.6-3.1 5.1 1.7 1.5 3.1 3.1 3.1 5.1v1.4h-9v-1.4c0-2 1.4-3.6 3.1-5.1C4.9 6.5 3.5 4.9 3.5 2.9Z',
  arrows: 'M2 3.5 6.5 8 2 12.5h2.6L9.1 8 4.6 3.5Zm5 0L11.5 8 7 12.5h2.6L14.1 8 9.6 3.5Z',
  boot: 'M5 1.5h4v6.2l4.4 2.3c.6.3 1 .9 1 1.6v1.9H2.6V9.6L5 7.8Z',
  crit: 'M8 .8 9.6 5.5l4.9.1-3.9 3 1.4 4.8L8 10.6l-4 2.8 1.4-4.8-3.9-3 4.9-.1Z',
  plus: 'M6.6 2h2.8v4.6H14v2.8H9.4V14H6.6V9.4H2V6.6h4.6Z',
  spiral: 'M8 2a6 6 0 1 0 6 6h-2.2A3.8 3.8 0 1 1 8 4.2a2.2 2.2 0 1 1-2.2 2.2H3.6A4.4 4.4 0 1 0 8 2Z',
  pierce: 'M1.5 14.5 10 6l-1.5-1.5 5.5-3-3 5.5L9.5 5.5ZM9 12.5l1.5-3 3 1.5-3 1.5L9 14.5Z',
  heal: 'M6.6 1.5h2.8v3.4h3.4v2.8H9.4v3.4H6.6V7.7H3.2V4.9h3.4Zm-3 10.2h8.8v2.8H3.6Z',
};

/** stat code -> { icon, color variable } */
export const STAT_META = {
  'Max Health': { icon: 'heart', color: 'var(--stat-hp)' },
  'Health Regen': { icon: 'plus', color: 'var(--stat-hp)' },
  '% Health Regen': { icon: 'plus', color: 'var(--stat-hp)' },
  'Max Mana': { icon: 'drop', color: 'var(--stat-mana)' },
  'Mana Regen': { icon: 'drop', color: 'var(--stat-mana)' },
  '% Mana Regeneration': { icon: 'drop', color: 'var(--stat-mana)' },
  'Attack Damage': { icon: 'blade', color: 'var(--stat-ad)' },
  'Ability Power': { icon: 'feather', color: 'var(--stat-ap)' },
  Armor: { icon: 'shield', color: 'var(--stat-armor)' },
  'Magic Resistance': { icon: 'ring', color: 'var(--stat-mr)' },
  'Ability Haste': { icon: 'hourglass', color: 'var(--stat-ah)' },
  '% Attack Speed': { icon: 'arrows', color: 'var(--stat-as)' },
  'Move Speed': { icon: 'boot', color: 'var(--stat-ms)' },
  '% Move Speed': { icon: 'boot', color: 'var(--stat-ms)' },
  '% Critical Rate': { icon: 'crit', color: 'var(--stat-crit)' },
  '% Lifesteal': { icon: 'drop', color: 'var(--stat-crit)' },
  '% Tenacity': { icon: 'spiral', color: 'var(--stat-ten)' },
  'Armor Penetration': { icon: 'pierce', color: 'var(--stat-ad)' },
  '% Armor Penetration': { icon: 'pierce', color: 'var(--stat-ad)' },
  'Magic Penetration': { icon: 'pierce', color: 'var(--stat-ap)' },
  '% Magic Penetration': { icon: 'pierce', color: 'var(--stat-ap)' },
  '% Heal and shield strength': { icon: 'heal', color: 'var(--stat-hp)' },
  'Physical Reduction': { icon: 'shield', color: 'var(--stat-armor)' },
  'Magic Reduction': { icon: 'ring', color: 'var(--stat-mr)' },
};

export const statColor = (code) => STAT_META[code]?.color ?? 'var(--text-2)';

export function StatIcon({ stat, size = 14 }) {
  const meta = STAT_META[stat];
  if (!meta) return null;
  return (
    <svg className="stat-icon" width={size} height={size} viewBox="0 0 16 16" aria-hidden="true" fill={meta.color}>
      <path d={ICONS[meta.icon]} />
    </svg>
  );
}

/** Icon + stat name (or abbreviation) in the stat's color. */
export function StatName({ stat, abbr = false, iconSize = 14 }) {
  return (
    <span className="stat-name" style={{ color: statColor(stat) }} title={abbr ? statLabel(stat) : undefined}>
      <StatIcon stat={stat} size={iconSize} />
      {abbr ? statAbbr(stat) : statLabel(stat)}
    </span>
  );
}
