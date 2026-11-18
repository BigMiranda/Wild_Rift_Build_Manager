import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { buildLexicon, normalize, parse, phonetic, similarity } from '../src/voiceParser.js';

/**
 * Rodada 20, Parte 2: the voice reader on 40 real phrases, written as Chrome's pt-BR recognition writes them (digits for
 * numbers, accents, the usual mistakes: "orne", "dárius", "flex", "lisin", "malfite"). The catalogs are the seeds the
 * backend reads (a small reader of their YAML keys: no YAML library in the frontend).
 */
const here = dirname(fileURLToPath(import.meta.url));
const seed = (f) => readFileSync(join(here, '../../backend/src/main/resources/seed', f), 'utf8');

/** The 2-space keys under a top-level section (`campeoes:`, `itens:`, `fundamentais:`, `runas:`, `feiticos:`). */
function keysUnder(text, section) {
  const out = [];
  let inside = false;
  for (const line of text.split(/\r?\n/)) {
    if (/^\S/.test(line)) inside = line.startsWith(`${section}:`);
    else if (inside) {
      const m = line.match(/^ {2}([^ #][^:]*):/);
      if (m) out.push(m[1].trim().replace(/^"|"$/g, ''));
    }
  }
  return out;
}

/** The nicknames: `apelidos:` -> kind (2 spaces) -> `alias: target` (4 spaces). */
function aliases(text) {
  const out = [];
  let kind = null;
  for (const line of text.split(/\r?\n/)) {
    const k = line.match(/^ {2}([a-z]+):\s*$/);
    if (k) { kind = k[1]; continue; }
    const a = line.match(/^ {4}([^:#]+):\s*(.+)$/);
    if (a && kind) out.push({ kind, alias: a[1].trim(), target: a[2].trim() });
  }
  return out;
}

const champions = keysUnder(seed('campeoes_7_3f.yml'), 'campeoes');
const items = keysUnder(seed('loja_7_3f.yml'), 'itens').map((name, i) => ({ id: i + 1, name }));
const itemId = (name) => items.find((i) => i.name === name)?.id;
const lexicon = buildLexicon({
  champions,
  spells: keysUnder(seed('feiticos_7_3f.yml'), 'feiticos'),
  keystones: keysUnder(seed('runas_7_3f.yml'), 'fundamentais'),
  runes: keysUnder(seed('runas_7_3f.yml'), 'runas'),
  items,
  aliases: aliases(seed('apelidos_7_3f.yml')),
});

const P = (champion, extra = {}) => ({ type: 'jogador', champion, ...extra });
const C = (champion, item) => ({ type: 'compra', champion, item: itemId(item) });

/** [phrase, expected intents (only the fields given are compared)]. */
const PHRASES = [
  ['time azul: Ornn barão, flash e teleporte, Aperto dos Mortos-Vivos, Fonte de Vida',
    [P('Ornn', { side: 'azul', lane: 'Barão', spells: ['Flash', 'Teleporte'], keystone: 'Aperto dos Mortos-Vivos', runes: ['Fonte da Vida'] })]],
  ['Lee Sin selva flash e golpear', [P('Lee Sin', { lane: 'Selva', spells: ['Flash', 'Golpear'] })]],
  ['inimigo Darius, flash e incendiar, Conquistador', [P('Darius', { side: 'inimigo', spells: ['Flash', 'Incendiar'], keystone: 'Conquistador' })]],
  ['orne barão flex e teleporte', [P('Ornn', { lane: 'Barão', spells: ['Flash', 'Teleporte'] })]],
  ['dárius comprou cutelo negro', [C('Darius', 'Cutelo Negro')]],
  ['Ornn botas de mercúrio', [C('Ornn', 'Passos de Mercúrio')]],
  ['Rumble comprou varinha explosiva', [C('Rumble', 'Varinha Explosiva')]],
  ['Ornn dois, um, cinco', [{ type: 'placar', champion: 'Ornn', kills: 2, deaths: 1, assists: 5 }]],
  ['Darius 4500 de ouro', [{ type: 'ouro', champion: 'Darius', gold: 4500 }]],
  ['Ornn morreu', [{ type: 'morte', champion: 'Ornn' }]],
  ['dragão infernal azul', [{ type: 'objetivo', kind: 'dragao', detail: 'Infernal', side: 'azul' }]],
  ['barão vermelho', [{ type: 'objetivo', kind: 'barao', side: 'vermelho' }]],
  ['arauto aliado', [{ type: 'objetivo', kind: 'arauto', side: 'aliado' }]],
  ['Darius matou Ornn', [{ type: 'abate', champion: 'Darius' }, { type: 'morte', champion: 'Ornn' }]],
  ['MF comprou gume do infinito', [C('Miss Fortune', 'Gume do Infinito')]],
  ['J4 morreu', [{ type: 'morte', champion: 'Jarvan IV' }]],
  ['lisin comprou cutelo negro', [C('Lee Sin', 'Cutelo Negro')]],
  ['Ornn 2 1 5', [{ type: 'placar', champion: 'Ornn', kills: 2, deaths: 1, assists: 5 }]],
  ['Ornn nível 6', [{ type: 'nivel', champion: 'Ornn', level: 6 }]],
  ['time vermelho: Darius barão, flash e teleporte; Vi selva, flash e golpear',
    [P('Darius', { side: 'vermelho', lane: 'Barão', spells: ['Flash', 'Teleporte'] }), P('Vi', { side: 'vermelho', lane: 'Selva', spells: ['Flash', 'Golpear'] })]],
  ['Yi selva golpear e flash', [P('Master Yi', { lane: 'Selva', spells: ['Golpear', 'Flash'] })]],
  ["Kai'Sa dragão, flash e curar", [P("Kai'Sa", { lane: 'Dragão', spells: ['Flash', 'Curar'] })]],
  ['Thresh suporte, flash e exaustão', [P('Thresh', { lane: 'Suporte', spells: ['Flash', 'Exaustão'] })]],
  ['Jinx comprou mata cráquens', [C('Jinx', 'Mata-Cráquens')]],
  ['o darius tá com 3 abates 1 morte e 2 assistências', [{ type: 'placar', champion: 'Darius', kills: 3, deaths: 1, assists: 2 }]],
  ['o ornn morreu pro darius', [{ type: 'morte', champion: 'Ornn' }, { type: 'abate', champion: 'Darius' }]],
  ['dragão de fogo pro azul', [{ type: 'objetivo', kind: 'dragao', detail: 'Infernal', side: 'azul' }]],
  ['time azul pegou o barão', [{ type: 'objetivo', kind: 'barao', side: 'azul' }]],
  // ambiguous on purpose (two items start with "capuz", two with "lâmina"): the reader does not guess, and these count as misses
  ['a ahri comprou o capuz', [C('Ahri', 'Capuz da Morte de Rabadon')]],
  ['zed comprou a lâmina', [C('Zed', 'Lâmina Fantasma de Youmuu')]],
  ['Syndra meio, flash e incendiar, Eletrocutar', [P('Syndra', { lane: 'Meio', spells: ['Flash', 'Incendiar'], keystone: 'Eletrocutar' })]],
  ['o Garen comprou botas de armadura', [C('Garen', 'Grevas Vorazes')]],
  ['malfite 2 0 1', [{ type: 'placar', champion: 'Malphite', kills: 2, deaths: 0, assists: 1 }]],
  ['o orne comprou forca da natureza', [C('Ornn', 'Força da Natureza')]],
  ['dárius comprou o cutelo', [C('Darius', 'Cutelo Negro')]],
  ['Darius 3 mil e 200 de ouro', [{ type: 'ouro', champion: 'Darius', gold: 3200 }]],
  ['Leona suporte, flash e exaustão, Aperto dos Mortos Vivos', [P('Leona', { lane: 'Suporte', spells: ['Flash', 'Exaustão'], keystone: 'Aperto dos Mortos-Vivos' })]],
  ['inimigo dárius flesh e ignite conquistador', [P('Darius', { side: 'inimigo', spells: ['Flash', 'Incendiar'], keystone: 'Conquistador' })]],
  ['kassa meio flash e incendiar', [P('Kassadin', { lane: 'Meio', spells: ['Flash', 'Incendiar'] })]],
  ['Ornn 5 mil de ouro', [{ type: 'ouro', champion: 'Ornn', gold: 5000 }]],
];

function matches(got, want) {
  if (got.length !== want.length) return false;
  return want.every((w, i) => Object.entries(w).every(([k, v]) => {
    const g = got[i][k];
    return Array.isArray(v) ? JSON.stringify(g) === JSON.stringify(v) : g === v;
  }));
}

test('the phonetic key and the similarity read the usual mistakes', () => {
  assert.equal(phonetic('orne'), phonetic('Ornn'));
  assert.ok(similarity('dárius', 'Darius') === 1);
  assert.ok(similarity('lisin', 'Lee Sin') >= 0.74);
  assert.ok(similarity('malfite', 'Malphite') >= 0.74);
  assert.equal(normalize('Darius 4.500 de ouro'), 'darius 4500 de ouro');
});

test('the 40 phrases: the rate of right readings', () => {
  assert.equal(PHRASES.length, 40);
  const wrong = [];
  const t0 = performance.now();
  for (const [phrase, want] of PHRASES) {
    const { intents } = parse(phrase, lexicon);
    if (!matches(intents, want)) wrong.push({ phrase, got: intents.map(({ parts, ...x }) => x) });
  }
  const ms = (performance.now() - t0) / PHRASES.length;
  const rate = (PHRASES.length - wrong.length) / PHRASES.length;
  console.log(`voiceParser: ${PHRASES.length - wrong.length}/${PHRASES.length} = ${(rate * 100).toFixed(1)}% (${ms.toFixed(1)} ms por frase)`);
  for (const w of wrong) console.log('  errou:', w.phrase, JSON.stringify(w.got));
  assert.ok(rate >= 0.9, `taxa de acerto ${rate}`);
});

test('every intent shows each field with what was heard', () => {
  const { intents } = parse('orne barão flex e teleporte', lexicon);
  const fields = intents[0].parts.map((p) => p.field);
  assert.deepEqual(fields, ['campeao', 'rota', 'feitico', 'feitico']);
  assert.equal(intents[0].parts[0].heard, 'orne');
  assert.ok(intents[0].parts[0].score < 1);
  // the side said before the champion shows too
  const side = parse('inimigo Darius, flash e incendiar', lexicon).intents[0].parts.map((p) => p.field);
  assert.deepEqual(side, ['campeao', 'lado', 'feitico', 'feitico']);
});

test('a nickname of the seed must point at something of the catalogs', () => {
  const names = new Set([...champions, ...items.map((i) => i.name), ...keysUnder(seed('feiticos_7_3f.yml'), 'feiticos'),
    ...keysUnder(seed('runas_7_3f.yml'), 'fundamentais'), ...keysUnder(seed('runas_7_3f.yml'), 'runas')]);
  const bad = aliases(seed('apelidos_7_3f.yml'))
    .filter((a) => ['campeoes', 'feiticos', 'runas', 'itens'].includes(a.kind) && !names.has(a.target));
  assert.deepEqual(bad, []);
});
