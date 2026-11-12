/**
 * The Discovery's notes (plain pt-BR sentences from DiscoveryService) read back into data, so the screens can show
 * item and rune icons instead of long lists of names. Pure: no React, no catalogs (the names are matched against the
 * lists passed in), so `node --test` covers it.
 *
 * Kinds: `runas` (a kit's rune search: start → end score and the net swaps), `conjunto` (the best kit with the new
 * runes), `ordem` (the order budget ran out), `retirada` (a discovery dropped against a saved build), `salva` (a saved
 * build beat the standard) and `texto` (anything else, shown as it is).
 */

const NUM = '(-?\\d+(?:\\.\\d+)?)';

/**
 * Splits "A, B, C" into names, preferring the longest known name at each point (an item name may hold a comma).
 * Unknown parts fall back to the text up to the next ", ".
 */
export function splitNames(text, known = []) {
  const names = [...known].filter(Boolean).sort((a, b) => b.length - a.length);
  const out = [];
  let rest = String(text ?? '').trim();
  while (rest.length > 0) {
    const hit = names.find((n) => rest === n || rest.startsWith(`${n}, `));
    const name = hit ?? (rest.includes(', ') ? rest.slice(0, rest.indexOf(', ')) : rest);
    out.push(name);
    rest = rest.slice(name.length).replace(/^, /, '');
  }
  return out;
}

/**
 * The rune search's path ("A → B; B → C, D → E") reduced to what changed from the build's page to the found one
 * ("A → C", "D → E"): the search walks one coordinate at a time and a rune can be swapped many times on the way.
 */
export function netSwaps(path) {
  const current = new Map(); // original -> current
  const origin = new Map(); // current -> original
  const order = [];
  for (const step of String(path ?? '').split('; ')) {
    for (const sub of step.split(', ')) {
      const m = sub.split(' → ');
      if (m.length !== 2) continue;
      const [from, to] = m.map((s) => s.trim());
      const first = origin.get(from) ?? from;
      if (!current.has(first)) order.push(first);
      origin.delete(from);
      current.set(first, to);
      origin.set(to, first);
    }
  }
  return order.map((from) => ({ from, to: current.get(from) })).filter((s) => s.from !== s.to);
}

/** One note as data. `itemNames` helps split item lists that hold commas. */
export function parseNote(text, itemNames = []) {
  const s = String(text ?? '');
  let m = s.match(new RegExp(`^Runas de (.+): (\\d+) páginas medidas, ${NUM} → ${NUM}(?: \\((.*)\\))?$`));
  if (m) {
    const same = !m[5] || m[5].startsWith('a página da build já era');
    return {
      kind: 'runas', items: splitNames(m[1], itemNames), measured: Number(m[2]), from: Number(m[3]), to: Number(m[4]),
      swaps: same ? [] : netSwaps(m[5]),
    };
  }
  m = s.match(/^Com as runas novas, o melhor conjunto mudou: (.+)$/);
  if (m) return { kind: 'conjunto', changed: true, items: splitNames(m[1], itemNames) };
  if (s.startsWith('Com as runas novas, o melhor conjunto continua o mesmo')) return { kind: 'conjunto', changed: false, items: [] };
  m = s.match(/^Ordem de (.+): o orçamento da ordem acabou antes de uma ordem completa; (.+)$/);
  if (m) {
    const prefix = m[2].match(/ficam as (\d+) primeiras compras/);
    return { kind: 'ordem', items: splitNames(m[1], itemNames), prefix: prefix ? Number(prefix[1]) : null };
  }
  m = s.match(new RegExp(`^Descoberta retirada: com as runas e a ordem, (.+) fica em ${NUM}, abaixo de (.+) como está salva \\(${NUM}\\)\\.$`));
  if (m) return { kind: 'retirada', items: splitNames(m[1], itemNames), score: Number(m[2]), against: m[3], againstScore: Number(m[4]) };
  m = s.match(new RegExp(`^A build salva (.+), como está \\(${NUM}\\), supera a padrão encontrada com as runas e a ordem dela \\(${NUM}\\)`));
  if (m) return { kind: 'salva', name: m[1], score: Number(m[2]), standard: Number(m[3]) };
  return { kind: 'texto', text: s };
}

/**
 * The notes grouped for the screen: the rune searches with the same swaps together (Ornn's campaign had eight kits
 * with the very same page change), the dropped discoveries by the saved build that beat them, then the other kinds
 * in the order the Discovery wrote them.
 */
export function groupNotes(notes, itemNames = []) {
  const parsed = (notes ?? []).map((n) => parseNote(n, itemNames));
  const runes = [];
  const byKey = new Map();
  for (const p of parsed.filter((x) => x.kind === 'runas')) {
    const key = p.swaps.map((x) => `${x.from}>${x.to}`).join('|');
    let g = byKey.get(key);
    if (!g) {
      g = { swaps: p.swaps, kits: [] };
      byKey.set(key, g);
      runes.push(g);
    }
    g.kits.push({ items: p.items, measured: p.measured, from: p.from, to: p.to });
  }
  // the discoveries dropped against a saved build, together (Rumble's campaign dropped nine against the same build)
  const dropped = [];
  for (const p of parsed.filter((x) => x.kind === 'retirada')) {
    let g = dropped.find((d) => d.against === p.against);
    if (!g) {
      g = { against: p.against, againstScore: p.againstScore, kits: [] };
      dropped.push(g);
    }
    g.kits.push({ items: p.items, score: p.score });
  }
  return { runes, dropped, other: parsed.filter((x) => x.kind !== 'runas' && x.kind !== 'retirada') };
}
