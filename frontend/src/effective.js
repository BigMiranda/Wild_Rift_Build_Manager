/**
 * Effective gold of the items held at a point of the timeline: what their stats and passives are worth in gold
 * (stat prices of the gold-efficiency methodology), against what they cost.
 * - Stats: the item's flat stats x gold per point.
 * - Passives already modeled by the engine (they give stats): the gold of what they give at that moment.
 * - Passives not catalogued yet (text only): a provisional UNKNOWN_PASSIVE_GOLD each. Passives with the same name are
 *   unique: held twice, they count once.
 * - Ornn's Living Forge is shown apart (it belongs to the champion, amplifying the items).
 */
export const UNKNOWN_PASSIVE_GOLD = 100;

/** Chart "stat" that shows the effective gold of each item as stacked bars. */
export const EFFECTIVE_VIEW = 'EFFECTIVE';

const norm = (s) => (s ?? '').trim().toLowerCase();

export function effectiveGold(point, itemsById, prices) {
  const price = (stat) => prices?.[stat] ?? 0;
  const seenUnknown = new Set();
  const items = (point?.contributions ?? []).map((c) => {
    const item = itemsById.get(c.itemId);
    const stats = Object.entries(c.flat ?? {}).map(([stat, amount]) => ({ stat, amount, gold: amount * price(stat) }));
    const known = new Map();
    for (const p of c.passiveParts ?? []) {
      const k = norm(p.passive);
      if (!known.has(k)) known.set(k, { name: p.passive, parts: [], gold: 0, known: true });
      const e = known.get(k);
      e.parts.push({ stat: p.stat, amount: p.amount, gold: p.gold });
      e.gold += p.gold;
    }
    const passives = [...known.values()];
    (item?.passives ?? []).forEach((p, i) => {
      const k = p.name ? norm(p.name) : `${c.itemId}#${i}`;
      if (known.has(k) || seenUnknown.has(k)) return;
      seenUnknown.add(k);
      passives.push({ name: p.name || item.name, parts: [], gold: UNKNOWN_PASSIVE_GOLD, known: false });
    });
    const statGold = stats.reduce((a, s) => a + s.gold, 0);
    const passiveGold = passives.reduce((a, p) => a + p.gold, 0);
    const cost = item?.cost ?? 0;
    const effective = statGold + passiveGold;
    return {
      key: String(c.purchaseIndex), purchaseIndex: c.purchaseIndex, number: c.number, implied: c.implied,
      itemId: c.itemId, itemName: c.itemName, cost, stats, passives, statGold, passiveGold, effective,
      profit: effective - cost, profitPct: cost > 0 ? (effective / cost - 1) * 100 : null,
    };
  });
  const forgeStats = Object.entries(point?.forge ?? {})
    .filter(([, amount]) => amount > 0)
    .map(([stat, amount]) => ({ stat, amount, gold: amount * price(stat) }));
  const forge = { stats: forgeStats, gold: forgeStats.reduce((a, s) => a + s.gold, 0), pct: point?.forgePct ?? 0 };
  const spent = items.reduce((a, x) => a + x.cost, 0);
  const effective = items.reduce((a, x) => a + x.effective, 0) + forge.gold;
  return {
    items, forge, spent, effective,
    profit: effective - spent, profitPct: spent > 0 ? (effective / spent - 1) * 100 : null,
  };
}

/** Item color (same item, same hue in every build) and the lighter tone of its passives. */
export function itemHue(name) {
  let h = 7;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return h % 360;
}
export const itemColor = (name) => `hsl(${itemHue(name)} 50% 46%)`;
export const itemPassiveColor = (name) => `hsl(${itemHue(name)} 60% 70%)`;
export const FORGE_COLOR = 'hsl(28 85% 55%)';
