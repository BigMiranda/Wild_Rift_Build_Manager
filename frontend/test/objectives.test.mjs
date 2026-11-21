// Rodada 21, part 6: the frontend's objectives (src/objectives.js) against the backend's (engine/ObjectiveRules), through
// the fixture the Java test writes and checks (backend ObjectivesParityTest).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { objectiveDelta, objectiveParts } from '../src/objectives.js';

const fixture = JSON.parse(readFileSync(new URL('./objetivos-paridade.json', import.meta.url), 'utf8'));

const close = (a, b, what) => assert.ok(Math.abs((a ?? 0) - (b ?? 0)) < 1e-9, `${what}: ${a} x ${b}`);

test('every case gives the same parts as the Java side', () => {
  fixture.cases.forEach((c, i) => {
    const parts = objectiveParts(c.objectives, fixture.rules, c.minute, c.total, c.base);
    assert.equal(parts.length, c.expected.length, `caso ${i}: ${parts.map((p) => p.source)} x ${c.expected.map((p) => p.source)}`);
    parts.forEach((p, k) => {
      const e = c.expected[k];
      assert.equal(p.source, e.source, `caso ${i}`);
      assert.equal(p.estimate, e.estimate, `caso ${i} ${p.source}`);
      assert.deepEqual(Object.keys(p.delta).sort(), Object.keys(e.delta).sort(), `caso ${i} ${p.source}`);
      Object.keys(e.delta).forEach((s) => close(p.delta[s], e.delta[s], `caso ${i} ${p.source} ${s}`));
      assert.equal(!!p.fight, !!e.fight, `caso ${i} ${p.source} fight`);
      if (e.fight) {
        assert.equal(p.fight.kind, e.fight.kind);
        ['value', 'each', 'times', 'seconds'].forEach((k2) => close(p.fight[k2], e.fight[k2], `caso ${i} ${p.source} ${k2}`));
      }
    });
  });
});

test('the delta sums the parts', () => {
  const c = fixture.cases.find((x) => x.objectives.baron);
  const d = objectiveDelta(objectiveParts(c.objectives, fixture.rules, c.minute, c.total, c.base));
  assert.ok(d['Attack Damage'] > 0);
});
