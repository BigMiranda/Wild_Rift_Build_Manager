/**
 * Ability points by level (same rules as the backend's ChampionCatalog): a plan is the ability upgraded at each level
 * 1..15 ("1", "2", "3" or "R"). A basic ability reaches rank k from level 2k - 1 (max 4); the ultimate ranks up at
 * levels 5, 9 and 13 (max 3).
 */
export const ULT_LEVELS = [5, 9, 13];
export const ABILITIES = ['1', '2', '3', 'R'];
export const ORDERS = [[1, 2, 3], [1, 3, 2], [2, 1, 3], [2, 3, 1], [3, 1, 2], [3, 2, 1]];
const idx = (a) => (a === 'R' ? 3 : Number(a) - 1);

export const validOrder = (order) => (ORDERS.some((o) => o.join() === order?.join()) ? order : [1, 2, 3]);

/** Levels 1-3 learn the basic abilities in priority order, R at 5/9/13, the rest to the first not maxed. */
export function planFromOrder(order) {
  const prio = validOrder(order);
  const ranks = [0, 0, 0];
  const plan = [];
  let learned = 0;
  for (let l = 1; l <= 15; l++) {
    if (ULT_LEVELS.includes(l)) { plan.push('R'); continue; }
    const a = learned < 3 ? prio[learned++] : (prio.find((x) => ranks[x - 1] < 4) ?? prio[0]);
    ranks[a - 1]++;
    plan.push(String(a));
  }
  return plan;
}

export function validPlan(plan) {
  if (!Array.isArray(plan) || plan.length !== 15) return false;
  const r = [0, 0, 0, 0];
  for (let l = 1; l <= 15; l++) {
    const a = plan[l - 1];
    if (!ABILITIES.includes(a)) return false;
    const rank = ++r[idx(a)];
    if (a === 'R' ? rank > 3 || l < ULT_LEVELS[rank - 1] : rank > 4 || l < 2 * rank - 1) return false;
  }
  return true;
}

/** The build's plan: its own when valid, else the one from the priority order. */
export const planOf = (setup) => (validPlan(setup?.skillLevels) ? setup.skillLevels : planFromOrder(setup?.skillOrder));

/** Ranks of 1, 2, 3, R at a level. */
export function ranksAt(plan, level) {
  const r = [0, 0, 0, 0];
  plan.slice(0, level).forEach((a) => { r[idx(a)]++; });
  return r;
}

/** Rank of an ability tag (P, 1, 2, 3, R) at a level; the passive counts as rank 1. */
export const rankOf = (plan, tag, level) => (tag === 'P' ? 1 : ranksAt(plan, level)[idx(tag)]);

/**
 * The plan with level `level` given to ability `a`, swapping with another level that had `a` (the nearest later one
 * first) so every ability keeps its points; null when no swap gives a valid plan.
 */
export function assign(plan, level, a) {
  const cur = plan[level - 1];
  if (cur === a) return plan;
  const others = plan.map((x, i) => i).filter((i) => plan[i] === a);
  others.sort((x, y) => {
    const later = (i) => (i > level - 1 ? 0 : 1);
    return later(x) - later(y) || Math.abs(x - (level - 1)) - Math.abs(y - (level - 1));
  });
  for (const i of others) {
    const next = [...plan];
    next[level - 1] = a;
    next[i] = cur;
    if (validPlan(next)) return next;
  }
  return null;
}
