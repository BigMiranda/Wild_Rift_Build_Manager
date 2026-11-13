import { test } from 'node:test';
import assert from 'node:assert/strict';
import { interactions, note1, note2, note3, setKey, sideView } from '../src/atlasMath.js';

// the same numbers as the backend's AtlasMathTest
const races = {
  '': 50,
  'A:x': 60,
  'A:y': 55,
  'B:z': 40,
  [setKey(['A:x', 'A:y'])]: 70,
  [setKey(['A:x', 'B:z'])]: 45,
  [setKey(['A:y', 'B:z'])]: 45,
  [setKey(['A:x', 'A:y', 'B:z'])]: 62,
};

test('the notes are what each order adds', () => {
  assert.equal(note1(races, 50, 'A:x'), 10);
  assert.equal(note2(races, 50, 'A:x', 'A:y'), 5);
  assert.equal(note2(races, 50, 'B:z', 'A:x'), -5);
  assert.equal(note3(races, 50, 'A:x', 'A:y', 'B:z'), 7);
  assert.equal(note2(races, 50, 'A:x', 'A:w'), null);
});

test('side B reads its notes flipped', () => {
  assert.equal(sideView('B:z', note1(races, 50, 'B:z')), 10);
  assert.equal(sideView('A:x', 3), 3);
});

test('the interactions come largest first', () => {
  const two = interactions(races, 50, 2);
  assert.equal(two.length, 3);
  assert.deepEqual(two.map((x) => Math.abs(x.value)), [5, 5, 0]);
  assert.equal(interactions(races, 50, 3)[0].value, 7);
});
