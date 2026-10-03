import { statLabel } from './format.js';

/**
 * Turns the flat item catalog into the shop layout: tabs -> sections -> tiles, following the Wild Rift shop.
 *
 * One tile per shop item. Evolutions (Fimbulwinter, Muramana...) are separate catalog items grouped under their base
 * item (`group`) and picked in the detail panel, since the shop never sells them directly.
 */

/** Shop tabs in the game's order. */
export const TABS = ['Lutador', 'Assassino', 'Atirador', 'Mágico', 'Defesa', 'Suporte', 'Botas'];
export const DEFAULT_TAB = 'Defesa';

const SECTIONS = [
  { key: 'ativo', label: 'Ativo', match: (i) => i.active && i.section === 'aprimorado' },
  { key: 'aprimorado', label: 'Aprimorado', match: (i) => i.section === 'aprimorado' },
  { key: 'preparacao', label: 'Item inicial de suporte', match: (i) => i.section === 'preparacao' },
  { key: 'tier_medio', label: 'Tier médio', match: (i) => i.section === 'tier_medio' },
  { key: 'basico', label: 'Básico', match: (i) => i.section === 'basico' },
];
const BOOT_LABELS = { aprimorado: 'Botas aprimoradas', tier_medio: 'Botas', basico: 'Básica' };

export const MARKERS = {
  novo: { badge: 'N', label: 'Novo neste patch' },
  reformulado: { badge: '⟳', label: 'Reformulado neste patch' },
  alterado: { badge: '!', label: 'Alterado neste patch' },
};

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

/** Sections (with their tiles) of one tab; `query` searches every tab at once. */
export function sectionsFor(tiles, tab, query) {
  const q = query.trim().toLowerCase();
  if (q) {
    const found = tiles.filter((t) => t.variants.some((v) => v.name.toLowerCase().includes(q)));
    return found.length ? [{ key: 'search', label: `Resultado da busca (${found.length})`, tiles: found }] : [];
  }
  const visible = tiles.filter((t) => t.base.tabs.includes(tab));
  const out = SECTIONS.map((s) => ({ key: s.key, label: tab === 'Botas' ? BOOT_LABELS[s.key] ?? s.label : s.label, tiles: [] }));
  for (const tile of visible) {
    const idx = SECTIONS.findIndex((s) => s.match(tile.base));
    if (idx >= 0) out[idx].tiles.push(tile);
  }
  return out.filter((s) => s.tiles.length > 0);
}

export const variantLabel = (item) => (item.section === 'evolucao' ? `${item.name} (evoluído)` : 'Ao comprar');

const pctOf = (r) => `${Math.round(r * 10000) / 100}%`;
const SCOPE_TEXT = { BONUS: 'adicional', BASE: 'base', TOTAL: 'total' };

/** Plain stats of an item and the passive effects the calculation models. */
export function describeStats(item) {
  const flat = [];
  const modeled = [];
  for (const s of item.stats) {
    if (s.ratio != null && s.refType) {
      modeled.push({
        name: s.passive ?? 'Passiva',
        conditional: s.conditional,
        text: `${pctOf(s.ratio)} de ${statLabel(s.refType)} (${SCOPE_TEXT[s.refScope] ?? 'total'}) como ${statLabel(s.type)}`,
      });
    } else if (s.passive && s.value != null) {
      const range = s.valueMax != null ? `${s.value}–${s.valueMax} (por nível)` : `${s.value}`;
      modeled.push({ name: s.passive, conditional: s.conditional, text: `+${range} ${statLabel(s.type)}` });
    } else if (s.value != null) {
      flat.push({ type: s.type, value: s.value });
    }
  }
  return { flat, modeled };
}
