import { statLabel, t } from './i18n.js';

/**
 * Turns the flat item catalog into the shop layout: tabs -> sections -> tiles, following the Wild Rift shop.
 *
 * One tile per shop item. Evolutions (Fimbulwinter, Muramana...) are separate catalog items grouped under their base
 * item (`group`) and picked in the detail panel or per purchase, since the shop never sells them directly.
 */

/** Shop tabs in the game's order (values are the catalog's pt-BR tab names). */
export const TABS = ['Lutador', 'Assassino', 'Atirador', 'Mágico', 'Defesa', 'Suporte', 'Botas'];
export const DEFAULT_TAB = 'Defesa';

/** Section order as in the shop; support starters come after mid tier items. */
const SECTIONS = [
  { key: 'ativo', match: (i) => i.active && i.section === 'aprimorado' },
  { key: 'aprimorado', match: (i) => i.section === 'aprimorado' },
  { key: 'tier_medio', match: (i) => i.section === 'tier_medio' },
  { key: 'preparacao', match: (i) => i.section === 'preparacao' },
  { key: 'basico', match: (i) => i.section === 'basico' },
];
const BOOT_SECTIONS = { aprimorado: 'section.bootsUpgraded', tier_medio: 'section.boots', basico: 'section.bootsBasic' };

export const MARKERS = {
  novo: { badge: 'N' },
  reformulado: { badge: '⟳' },
  alterado: { badge: '!' },
};
export const markerLabel = (marker) => t(`marker.${marker}`);

/** Groups evolutions under their base item. Returns tiles sorted by cost, then name. */
export function buildTiles(items) {
  const byGroup = new Map();
  for (const item of items) {
    const key = item.group ?? item.name;
    if (!byGroup.has(key)) byGroup.set(key, []);
    byGroup.get(key).push(item);
  }
  const tiles = [];
  for (const [key, variants] of byGroup) {
    const base = variants.find((v) => v.section !== 'evolucao') ?? variants[0];
    variants.sort((a, b) => (a === base ? -1 : b === base ? 1 : a.name.localeCompare(b.name)));
    tiles.push({ key, name: base.name, cost: base.cost, base, variants, preferredId: base.id, iconId: base.id });
  }
  return tiles.sort((a, b) => a.cost - b.cost || a.name.localeCompare(b.name));
}

/** Map item id -> the other state of the same shop item (base <-> evolution), for items that evolve. */
export function evolutionPairs(items) {
  const pairs = new Map();
  for (const tile of buildTiles(items)) {
    const evo = tile.variants.find((v) => v.section === 'evolucao');
    if (evo) {
      pairs.set(tile.base.id, evo);
      pairs.set(evo.id, tile.base);
    }
  }
  return pairs;
}

/** Sections (with their tiles) of one tab; `query` searches every tab at once. */
export function sectionsFor(tiles, tab, query) {
  const q = query.trim().toLowerCase();
  if (q) {
    const found = tiles.filter((x) => x.variants.some((v) => v.name.toLowerCase().includes(q)));
    return found.length ? [{ key: 'search', label: t('shop.searchResult', { n: found.length }), tiles: found }] : [];
  }
  const visible = tiles.filter((x) => x.base.tabs.includes(tab));
  const out = SECTIONS.map((s) => ({
    key: s.key,
    label: t(tab === 'Botas' && BOOT_SECTIONS[s.key] ? BOOT_SECTIONS[s.key] : `section.${s.key}`),
    tiles: [],
  }));
  for (const tile of visible) {
    const idx = SECTIONS.findIndex((s) => s.match(tile.base));
    if (idx >= 0) out[idx].tiles.push(tile);
  }
  return out.filter((s) => s.tiles.length > 0);
}

export const variantLabel = (item) => (item.section === 'evolucao' ? t('shop.evolved', { name: item.name }) : t('shop.onBuy'));

export const hasConditional = (item) => item?.stats.some((s) => s.conditional) ?? false;

const pctOf = (r) => `${Math.round(r * 10000) / 100}%`;

/** Plain stats of an item and the passive effects the calculation models. */
export function describeStats(item) {
  const flat = [];
  const modeled = [];
  for (const s of item.stats) {
    if (s.ratio != null && s.refType) {
      modeled.push({
        name: s.passive ?? 'Passiva',
        stat: s.type,
        conditional: s.conditional,
        text: t('modeled.ratio', { pct: pctOf(s.ratio), ref: statLabel(s.refType), scope: t(`scope.${s.refScope ?? 'TOTAL'}`), stat: statLabel(s.type) }),
      });
    } else if (s.passive && s.value != null) {
      const range = s.valueMax != null ? `${s.value}–${s.valueMax} ${t('modeled.byLevel')}` : `${s.value}`;
      modeled.push({ name: s.passive, stat: s.type, conditional: s.conditional, text: `+${range} ${statLabel(s.type)}` });
    } else if (s.value != null) {
      flat.push({ type: s.type, value: s.value });
    }
  }
  return { flat, modeled };
}
