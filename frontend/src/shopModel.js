import { statLabel } from './format.js';

/**
 * Turns the flat item catalog into the shop layout: category tabs -> sections -> tiles.
 *
 * One tile per base item: catalog variants such as "X (Passive)" or "X (lv15 ...)" are grouped under "X" and picked
 * in the detail panel. Tabs and sections are derived from the catalog `category` (comma separated tags), so they
 * follow whatever the data source provides.
 */

/** Tabs in display order. `match` receives the item's category tags. */
export const TABS = [
  { key: 'physical', label: 'Físico', match: (t) => t.includes('PHYSICAL DAMAGE ITEMS') },
  { key: 'magic', label: 'Mágico', match: (t) => t.includes('MAGIC DAMAGE ITEMS') },
  { key: 'defense', label: 'Defesa', match: (t) => t.includes('DEFENSE ITEMS') },
  { key: 'support', label: 'Suporte', match: (t) => t.includes('SUPPORT ITEMS') },
  { key: 'boots', label: 'Botas', match: (t) => t.includes('BOOTS') },
  { key: 'components', label: 'Componentes', match: (t) => t.includes('MID TIER ITEMS') || t.includes('BASIC ITEMS') },
];

export const DEFAULT_TAB = 'defense';

const SECTIONS = [
  { key: 'active', label: 'Ativo', match: (t) => t.includes('ACTIVE ITEMS') },
  { key: 'mid', label: 'Intermediário', match: (t) => t.includes('MID TIER ITEMS') },
  { key: 'basic', label: 'Básico', match: (t) => t.includes('BASIC ITEMS') },
  { key: 'boots', label: 'Botas', match: (t) => t.includes('BOOTS') },
  { key: 'upgraded', label: 'Aprimorado(a)', match: () => true },
];

const MARKER = "Unstable Passives' Stats";

export const tagsOf = (item) => item.category.split(',').map((s) => s.trim());

/** "Amaranth's Twinguard (Endurance)" -> "Amaranth's Twinguard" */
export const baseName = (name) => name.replace(/\s*\(.*\)\s*$/, '').trim();

/** Text inside the trailing parentheses, or null for the plain item. */
export const variantLabel = (name) => {
  const m = name.match(/\((.*)\)\s*$/);
  return m ? m[1] : null;
};

const hasPercentPassive = (item) => item.stats.some((s) => s.ratio != null && s.refType);

/** Groups catalog variants under their base item. Returns tiles sorted by cost, then name. */
export function buildTiles(items) {
  const byBase = new Map();
  for (const item of items) {
    const key = baseName(item.name);
    if (!byBase.has(key)) byBase.set(key, []);
    byBase.get(key).push(item);
  }
  const tiles = [];
  for (const [name, variants] of byBase) {
    variants.sort((a, b) => a.name.length - b.name.length || a.name.localeCompare(b.name));
    // The variant that models the percentage passive is the one this planner exists for.
    const preferred = variants.find(hasPercentPassive) ?? variants[0];
    tiles.push({ key: name, name, cost: variants[0].cost, tags: tagsOf(variants[0]), variants, preferredId: preferred.id, iconId: variants[0].id });
  }
  return tiles.sort((a, b) => a.cost - b.cost || a.name.localeCompare(b.name));
}

/** Sections (with their tiles) of one tab; `query` searches every tab at once. */
export function sectionsFor(tiles, tabKey, query) {
  const q = query.trim().toLowerCase();
  const tab = TABS.find((t) => t.key === tabKey);
  const visible = q
    ? tiles.filter((t) => t.name.toLowerCase().includes(q) || t.variants.some((v) => v.name.toLowerCase().includes(q)))
    : tiles.filter((t) => tab.match(t.tags));
  if (q) return visible.length ? [{ key: 'search', label: `Resultado da busca (${visible.length})`, tiles: visible }] : [];
  const out = SECTIONS.map((s) => ({ key: s.key, label: s.label, tiles: [] }));
  for (const tile of visible) {
    const idx = SECTIONS.findIndex((s) => s.match(tile.tags));
    out[idx].tiles.push(tile);
  }
  return out.filter((s) => s.tiles.length > 0);
}

/** Human readable stat lines of an item: { flat: [...], passives: [...] }. */
export function describeStats(item) {
  const flat = [];
  const passives = [];
  for (const s of item.stats) {
    if (s.type === MARKER) continue;
    if (s.ratio != null && s.refType) {
      passives.push({ name: s.passive ?? 'Passiva', text: s.refType === s.type
        ? `+${Math.round(s.ratio * 1000) / 10}% de ${statLabel(s.type)}`
        : `${Math.round(s.ratio * 1000) / 10}% de ${statLabel(s.refType)} como ${statLabel(s.type)}`, scope: s.refScope });
    } else if (s.ratio != null) {
      passives.push({ name: s.passive ?? 'Passiva', text: `+${s.value ?? '?'} ${statLabel(s.type)} (valor fixo da referência)` });
    } else if (s.value != null && s.passive) {
      passives.push({ name: s.passive, text: `+${s.value} ${statLabel(s.type)}` });
    } else if (s.value != null) {
      flat.push({ type: s.type, value: s.value });
    }
  }
  return { flat, passives, unstable: item.stats.some((s) => s.type === MARKER) };
}
