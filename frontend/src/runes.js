/** Runes and summoner spells catalog (loaded once from /api/runes) and helpers for a build's rune page. */

let catalog = null;

export const setRuneCatalog = (c) => { catalog = c; };
export const runeCatalog = () => catalog;

export const runeIcon = (rune, grey = false) =>
  (rune ? `/api/runes/icons/${grey ? rune.iconGrey : rune.icon}` : null);
export const treeIcon = (tree, grey = false) =>
  (tree ? `/api/runes/icons/${grey ? tree.iconGrey : tree.icon}?v=2` : null); // v2: diamond with transparent corners
export const spellIcon = (spell) => (spell ? `/api/spells/icons/${spell.icon}` : null);

export const findRune = (name) =>
  (catalog && name ? catalog.keystones.find((r) => r.name === name) ?? catalog.runes.find((r) => r.name === name) : null);
export const findTree = (name) => catalog?.trees.find((t) => t.name === name) ?? null;
export const findSpell = (name) => catalog?.spells.find((s) => s.name === name) ?? null;

/** Runes of a tree row (1..3). */
export const treeRow = (tree, row) => (catalog ? catalog.runes.filter((r) => r.tree === tree && r.row === row) : []);

export const emptyRunePage = () => ({
  primary: 'Determinação',
  secondary: 'Dominação',
  keystone: null,
  primaryRunes: [null, null, null],
  secondaryRune: null,
  options: {},
  conditional: {},
});

/** The page's 5 slots in game order: keystone, primary rows 1..3, secondary rune. */
export const pageSlots = (page) => [
  { key: 'keystone', rune: findRune(page?.keystone) },
  ...[0, 1, 2].map((i) => ({ key: `p${i}`, rune: findRune(page?.primaryRunes?.[i]) })),
  { key: 'secondary', rune: findRune(page?.secondaryRune) },
];
