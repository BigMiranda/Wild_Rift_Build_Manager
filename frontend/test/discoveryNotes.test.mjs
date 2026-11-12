// The Discovery's notes read back into data (frontend/src/discoveryNotes.js), with sentences from a real campaign.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { groupNotes, netSwaps, parseNote, splitNames } from '../src/discoveryNotes.js';

const ORNN = 'Runas de Manto da Meia-noite, Medalhão dos Solari de Ferro, Resplendor Vazio, Desespero Eterno, Duplaguarda de Amaranto: '
  + '74 páginas medidas, 66.5 → 72.9 (Suserano do Gelo → Eletrocutar; Eletrocutar → Agilidade nos Pés; Agilidade nos Pés → Conquistador; '
  + 'Conquistador → Ímpeto Gradual; Determinação → Feitiçaria, Coragem do Colosso → Arcanista do Axioma, Ventos Revigorantes → Transcendência, '
  + 'Crescimento Excessivo → Chamuscar, Fertilizador Ixtalense → Golpe Desleal; Golpe Desleal → Golpe de Misericórdia; '
  + 'Golpe de Misericórdia → Lenda: Espontaneidade; Lenda: Espontaneidade → Ventos Revigorantes)';

test('the rune path collapses into the net swaps', () => {
  const n = parseNote(ORNN);
  assert.equal(n.kind, 'runas');
  assert.equal(n.items.length, 5);
  assert.equal(n.measured, 74);
  assert.equal(n.from, 66.5);
  assert.equal(n.to, 72.9);
  assert.deepEqual(n.swaps, [
    { from: 'Suserano do Gelo', to: 'Ímpeto Gradual' },
    { from: 'Determinação', to: 'Feitiçaria' },
    { from: 'Coragem do Colosso', to: 'Arcanista do Axioma' },
    { from: 'Ventos Revigorantes', to: 'Transcendência' },
    { from: 'Crescimento Excessivo', to: 'Chamuscar' },
    { from: 'Fertilizador Ixtalense', to: 'Ventos Revigorantes' },
  ]);
});

test('a swap that comes back to where it started is no change', () => {
  assert.deepEqual(netSwaps('A → B; B → A, C → D'), [{ from: 'C', to: 'D' }]);
});

test('item lists keep names with a comma whole', () => {
  assert.deepEqual(splitNames('Tributo, Azuporim e Restrição, Coração Congelado', ['Tributo, Azuporim e Restrição']),
    ['Tributo, Azuporim e Restrição', 'Coração Congelado']);
});

test('the other kinds', () => {
  assert.deepEqual(parseNote('Com as runas novas, o melhor conjunto mudou: A, B'), { kind: 'conjunto', changed: true, items: ['A', 'B'] });
  assert.equal(parseNote('Runas de A, B: 30 páginas medidas, 60.0 → 60.0 (a página da build já era a melhor)').swaps.length, 0);
  const o = parseNote('Ordem de A, B: o orçamento da ordem acabou antes de uma ordem completa; ficam as 3 primeiras compras da calculadora e, depois delas, a ordem da triagem.');
  assert.deepEqual(o, { kind: 'ordem', items: ['A', 'B'], prefix: 3 });
  const r = parseNote('Descoberta retirada: com as runas e a ordem, A, B fica em 70.1, abaixo de Big Carry v5 como está salva (71.9).');
  assert.deepEqual(r, { kind: 'retirada', items: ['A', 'B'], score: 70.1, against: 'Big Carry v5', againstScore: 71.9 });
  assert.equal(parseNote('Só 1 build(s) conhecida(s) do campeão').kind, 'texto');
});

test('rune searches with the same swaps are grouped', () => {
  const g = groupNotes([ORNN, ORNN.replace('Resplendor Vazio', 'Rookern Lamúrico'), 'outra coisa']);
  assert.equal(g.runes.length, 1);
  assert.equal(g.runes[0].kits.length, 2);
  assert.equal(g.other.length, 1);
});

test('dropped discoveries are grouped by the saved build that beat them', () => {
  const d = (x) => `Descoberta retirada: com as runas e a ordem, A, B fica em ${x}, abaixo de Rumble Carry como está salva (72.6).`;
  const g = groupNotes([d('71.7'), d('70.0')]);
  assert.equal(g.dropped.length, 1);
  assert.deepEqual(g.dropped[0].kits.map((k) => k.score), [71.7, 70]);
  assert.equal(g.other.length, 0);
});
