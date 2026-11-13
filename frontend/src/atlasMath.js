/**
 * Rodada 16, the atlas's notes (mirror of backend AtlasMath): a set's key is its side keys sorted and joined by "|";
 * a condition's note is its race minus the base, a pair's and a triple's interaction is what they do beyond their parts.
 */
export const setKey = (keys) => [...keys].sort().join('|');

export function note1(races, base, x) {
  const r = races[x];
  return r == null ? null : r - base;
}

export function note2(races, base, x, y) {
  const xy = races[setKey([x, y])];
  const rx = races[x];
  const ry = races[y];
  return xy == null || rx == null || ry == null ? null : xy - rx - ry + base;
}

export function note3(races, base, x, y, z) {
  const xyz = races[setKey([x, y, z])];
  const xy = races[setKey([x, y])];
  const xz = races[setKey([x, z])];
  const yz = races[setKey([y, z])];
  const rx = races[x];
  const ry = races[y];
  const rz = races[z];
  if ([xyz, xy, xz, yz, rx, ry, rz].some((v) => v == null)) return null;
  return xyz - xy - xz - yz + rx + ry + rz - base;
}

/** Side A's view → the side's own view (B's race is 100 minus A's, so its notes flip sign). */
export const sideView = (key, v) => (v == null ? null : key.startsWith('B:') ? -v : v);

/** Every measured set of a size, as lists of side keys (the base "" left out). */
export function setsOfSize(races, n) {
  const out = [];
  for (const k of Object.keys(races)) {
    if (!k) continue;
    const parts = k.split('|');
    if (parts.length === n) out.push(parts);
  }
  return out;
}

/** The interactions of every measured set of a size (2 or 3), each with its keys and value, largest first. */
export function interactions(races, base, n) {
  const out = [];
  for (const parts of setsOfSize(races, n)) {
    const v = n === 2 ? note2(races, base, parts[0], parts[1]) : note3(races, base, parts[0], parts[1], parts[2]);
    if (v != null) out.push({ keys: parts, value: v, total: races[setKey(parts)] - base });
  }
  out.sort((a, b) => Math.abs(b.value) - Math.abs(a.value) || a.keys.join().localeCompare(b.keys.join()));
  return out;
}
