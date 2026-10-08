/**
 * Passives held at a point of the timeline. Every passive is worth the same gold for now (provisional estimate, to be
 * replaced by a value per passive). Passives with the same name are unique: held twice, they count once.
 */
export const PASSIVE_GOLD = 400;

/** Chart "stat" that shows passives as stacked bars instead of a stat line. */
export const PASSIVES_VIEW = 'PASSIVES';

/** Passives of the items held (item ids in inventory order), one entry per distinct passive. */
export function acquiredPassives(itemIds, itemsById) {
  const out = [];
  const seen = new Set();
  for (const id of itemIds) {
    const item = itemsById.get(id);
    (item?.passives ?? []).forEach((p, i) => {
      const key = p.name ? p.name.trim().toLowerCase() : `${item.name}#${i}`;
      if (seen.has(key)) return;
      seen.add(key);
      out.push({ key, name: p.name || item.name, text: p.text, itemId: id, itemName: item.name, gold: PASSIVE_GOLD });
    });
  }
  return out;
}

/** Item ids held at a series point. */
export const pointItemIds = (point) => (point?.contributions ?? []).filter((c) => !c.rune).map((c) => c.itemId);

/** Minute at which each passive key first appears in a series. */
export function passiveSince(series, itemsById) {
  const since = new Map();
  for (const p of series ?? []) {
    for (const x of acquiredPassives(pointItemIds(p), itemsById)) {
      if (!since.has(x.key)) since.set(x.key, p.minute);
    }
  }
  return since;
}

/** Stable color for a passive (same passive, same color in every build). */
export function passiveColor(key) {
  let h = 0;
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) >>> 0;
  return `hsl(${h % 360} 55% 58%)`;
}

/**
 * Rows to compare builds side by side: one row per distinct passive (same passive, same row), in the order they
 * first appear, with what each column (build) holds of it (null = not held).
 */
export function passiveRows(columns) {
  const rows = new Map();
  columns.forEach((c, ci) => {
    for (const x of c.list) {
      if (!rows.has(x.key)) rows.set(x.key, { key: x.key, cells: columns.map(() => null) });
      rows.get(x.key).cells[ci] = x;
    }
  });
  return [...rows.values()];
}
