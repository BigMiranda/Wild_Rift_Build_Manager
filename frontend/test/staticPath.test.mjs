// The static site's file name rule (Rodada 11.b): the same cases as tools/test_site_estatico.py.
// Run: node --test test/   (node:test is built in: no dependency)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { staticFile } from '../src/staticPath.js';

const cases = JSON.parse(readFileSync(new URL('../../tools/caminhos_estaticos.json', import.meta.url), 'utf8'));

test('JSON answers: the same file as the Python script', () => {
  for (const [url, file] of cases.json) assert.equal(staticFile(url), file, url);
});

test('icons: the same file as the Python script', () => {
  for (const [url, file] of cases.binario) assert.equal(staticFile(url, true), file, url);
});
