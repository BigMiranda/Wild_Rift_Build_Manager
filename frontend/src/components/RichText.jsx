import { StatIcon, statColor } from './StatIcon.jsx';

/**
 * Passive texts of the shop with the stats highlighted like in the game: each stat name (and the amount right before
 * it, "20% de Tenacidade", "150 de Dano Mágico") in the stat's color with its icon.
 */
const TERMS = [
  ['Regeneração de Vida', '% Health Regen'],
  ['Roubo de Vida', '% Lifesteal'],
  ['Vampirismo (?:Universal|Físico)', '% Omnivamp'],
  ['Resistência Mágica(?: adicional)?', 'Magic Resistance'],
  ['Resistência a Lentidão', 'Move Speed'],
  ['Dano de Ataque(?: (?:adicional|base))?', 'Attack Damage'],
  ['Poder de Habilidade(?: adicional)?', 'Ability Power'],
  ['Aceleração d[ae] Habilidade(?: Ultimate| Básica)?', 'Ability Haste'],
  ['Velocidade de Movimento(?: adicional)?', 'Move Speed'],
  ['Velocidade de Ataque(?: adicional)?', '% Attack Speed'],
  ['Penetração de Armadura', 'Armor Penetration'],
  ['Penetração Mágica', 'Magic Penetration'],
  ['Potência de Escudo e Cura', '% Heal and shield strength'],
  ['Taxa de Crítico|Acertos? Críticos?|Dano Crítico', '% Critical Rate'],
  ['Dano Mágico', 'Ability Power'],
  ['Dano Físico', 'Attack Damage'],
  ['Dano Verdadeiro', null],
  ['Vida (?:máxima|Máxima|adicional|base|perdida|atual)', 'Max Health'],
  ['Mana (?:máximo|Máximo|usado|perdido)', 'Max Mana'],
  ['Armadura(?: adicional)?', 'Armor'],
  ['Tenacidade', '% Tenacity'],
  ['Escudos?(?: [Mm]ágico| físico| de Feitiço)?', 'Shield'],
  ['Estase', 'Stasis'],
  ['Vida', 'Max Health'],
  ['Mana', 'Max Mana'],
  ['cura(?:-se|ndo|m|r)?|restaura|Regenera', 'Heal'],
];

const NUM = String.raw`\d[\d.,]*(?:\s?[–-]\s?\d[\d.,]*)?%?(?:\s\(por nível\))?`;
const JOIN = String.raw`\s+(?:(?:de|da|do|das|dos|em)\s+)?`;
const RE = new RegExp(
  String.raw`(?<![\p{L}\d])(?:(${NUM})(${JOIN}))?(` + TERMS.map(([p]) => `(${p})`).join('|') + String.raw`)(?![\p{L}])`,
  'gu',
);

/** Text split into plain strings and highlighted stats {amount, joiner, term, stat}. */
export function tokens(text) {
  const src = String(text ?? '');
  const out = [];
  let last = 0;
  for (const m of src.matchAll(RE)) {
    const k = TERMS.findIndex((_, i) => m[4 + i] != null);
    out.push(src.slice(last, m.index));
    out.push({ amount: m[1], joiner: m[2] ?? '', term: m[3], stat: TERMS[k][1] });
    last = m.index + m[0].length;
  }
  out.push(src.slice(last));
  return out;
}

export default function RichText({ text }) {
  return (
    <>
      {tokens(text).map((x, i) => (typeof x === 'string' ? x : (
        <span key={i} className={`rich-stat${x.stat ? '' : ' true-dmg'}`} style={x.stat ? { color: statColor(x.stat) } : undefined}>
          {x.stat && <StatIcon stat={x.stat} size={12} />}
          {x.amount && <strong>{x.amount}</strong>}
          {x.joiner}{x.term}
        </span>
      )))}
    </>
  );
}
