/**
 * Recomputes the values of the champion's ability texts with given stats and ability rank.
 *
 * The game's texts show the value at rank 1 with the champion's level-1 stats, followed by its formula:
 * "57 de Dano Físico (10 + 75%{DdA})", "um Escudo que absorve 40 de dano (40 + 40% de {DdA} adicional)". A formula is
 * recomputed when every term is understood: a number (taken from the rank table when a row starts with it, e.g.
 * "Dano base: 10, 40, 70, 100") or "X%" of a stat marker, total or "adicional" (bonus = total - base); its ratio also
 * follows the rank table when a row starts with "X%". Anything else ("{nível}" terms, "por Névoa"...) leaves the text
 * as the game shows it.
 */

/** Text markers -> stat code. */
export const MARKER_STAT = {
  DdA: 'Attack Damage', PdH: 'Ability Power', Arm: 'Armor', RM: 'Magic Resistance', Vida: 'Max Health',
  Mana: 'Max Mana', VdM: 'Move Speed', Crit: '% Critical Rate', VdA: '% Attack Speed', Let: 'Armor Penetration',
};

const NUM = String.raw`\d+(?:\.\d{3})*(?:,\d+)?`;
/** "1.200" -> 1200, "4,5" -> 4.5, also accepts table values like "4.5" / "75%". */
export function parseNum(s) {
  const t = String(s).trim().replace('%', '');
  if (/^\d+(\.\d{3})+(,\d+)?$/.test(t)) return Number(t.replace(/\./g, '').replace(',', '.'));
  return Number(t.replace(',', '.'));
}

const tableRows = (niveis) => Object.entries(niveis ?? {}).map(([label, vals]) => [label, Array.isArray(vals) ? vals : [vals]]);

/** Value of a rank-1 number at another rank, from the first table row starting with it (and a percent sign or not). */
function byRank(rows, value, pct, rank, labelHint) {
  const match = (row) => {
    const first = String(row[1][0]);
    return first.includes('%') === pct && Math.abs(parseNum(first) - value) < 1e-6;
  };
  const candidates = rows.filter(match);
  const row = candidates.find(([label]) => labelHint && label.includes(labelHint)) ?? candidates[0];
  if (!row) return value;
  return parseNum(row[1][Math.max(0, Math.min(row[1].length, rank) - 1)]);
}

/** One formula term -> {kind: 'const', value} | {kind: 'ratio', ratio, stat, bonus, marker}; null when not understood. */
function parseTerm(term) {
  const t = term.trim();
  if (new RegExp(`^${NUM}$`).test(t)) return { kind: 'const', value: parseNum(t) };
  const m = t.match(new RegExp(`^(${NUM})%\\s*(?:(?:d[aoe]s?|de)\\s+)?(adicion\\w*\\s*)?\\{(\\w+)\\}\\s*(adicion\\w*)?$`))
    ?? t.match(new RegExp(`^(${NUM})%\\s*(?:d[aoe]s?\\s+)?(adicion\\w*\\s*)?\\{(\\w+)\\}`));
  if (!m || !MARKER_STAT[m[3]] || /\{nível\}/.test(t)) return null;
  return { kind: 'ratio', ratio: parseNum(m[1]) / 100, marker: m[3], stat: MARKER_STAT[m[3]], bonus: !!(m[2] || m[4]) };
}

/**
 * Splits a text into parts: plain strings and computed values {shown, original, formula, value}. `stats` = {total, base}
 * by stat code; with null stats nothing is recomputed (the game's text).
 */
export function computeText(text, niveis, rank, stats) {
  const src = String(text ?? '');
  if (!stats) return [src];
  const rows = tableRows(niveis);
  const re = new RegExp(`(${NUM})(%?)([^()\\d{}]{0,60}?)\\(([^()]*\\{[^()]*)\\)`, 'g');
  const out = [];
  let last = 0;
  let m;
  while ((m = re.exec(src))) {
    const [all, num, pct, between, formula] = m;
    const terms = formula.split(' + ');
    const parsed = terms.map(parseTerm);
    if (pct || formula.includes(' - ') || parsed.some((p) => !p)) continue;
    let value = 0;
    const parts = [];
    for (const p of parsed) {
      if (p.kind === 'const') {
        const v = byRank(rows, p.value, false, rank);
        value += v;
        parts.push(fmt(v));
      } else {
        const ratio = byRank(rows, p.ratio * 100, true, rank, p.marker) / 100;
        const total = stats.total?.[p.stat] ?? 0;
        const amount = p.bonus ? total - (stats.base?.[p.stat] ?? 0) : total;
        value += ratio * amount;
        parts.push(`${fmt(ratio * 100)}% × ${fmt(amount)} ${p.marker}${p.bonus ? ' adicional' : ''}`);
      }
    }
    out.push(src.slice(last, m.index));
    out.push({ value, original: parseNum(num), formula: `${parts.join(' + ')} = ${fmt(value)}`, rest: `${between}(${formula})` });
    last = m.index + all.length;
  }
  out.push(src.slice(last));
  return out;
}

export const fmt = (v) => (Math.abs(v - Math.round(v)) < 0.05 ? String(Math.round(v)) : v.toFixed(1).replace('.', ','));
