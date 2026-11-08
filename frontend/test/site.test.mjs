// The generated static site (Rodada 11.b): served from its folder, the screens of a champion, one of its builds and
// the comparison read only generated files, and the manifest matches the files. Run after `tools/publicar_site.py`
// (or with SITE_DIR=<folder>); skipped when there is no site.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { extname, join, normalize, resolve } from 'node:path';
import { staticFile } from '../src/staticPath.js';
import { makeStore, walk, compareRows, URLS } from '../src/site/data.js';

const DIR = resolve(process.env.SITE_DIR ?? new URL('../../site-publico', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const HAS_SITE = existsSync(join(DIR, 'api', 'manifest.json'));
const TYPES = { '.json': 'application/json', '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png' };

/** A plain static server over the folder, like GitHub Pages: every request is logged. */
function serve() {
  const log = [];
  const server = createServer((req, res) => {
    const path = decodeURIComponent(req.url.split('?')[0]);
    const file = normalize(join(DIR, path === '/' ? 'index.html' : path));
    const ok = file.startsWith(DIR) && existsSync(file) && statSync(file).isFile();
    log.push({ path, status: ok ? 200 : 404 });
    if (!ok) {
      res.writeHead(404);
      res.end();
      return;
    }
    res.writeHead(200, { 'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream' });
    res.end(readFileSync(file));
  });
  return new Promise((done) => server.listen(0, '127.0.0.1', () => done({ server, log, base: `http://127.0.0.1:${server.address().port}/` })));
}

test('the champion, a build and the comparison read only the generated JSON files', { skip: !HAS_SITE && 'no site generated' }, async () => {
  const { server, log, base } = await serve();
  try {
    // the page itself loads with relative paths only (GitHub Pages serves it under /<repo>/)
    const html = await (await fetch(base)).text();
    assert.doesNotMatch(html, /(src|href)="\/(?!\/)/, 'index.html must use relative paths');
    const store = makeStore(async (url) => {
      const res = await fetch(base + staticFile(url));
      if (!res.ok) throw new Error(`${res.status} ${url}`);
      return res.json();
    });
    const manifest = await store.manifest();
    for (const c of manifest.champions) {
      log.length = 0;
      const { champion, keys } = await walk(store, c.unitCode);
      assert.equal(champion.unitCode, c.unitCode);
      if (keys.length >= 2) {
        const rows = compareRows(await store.builds(keys.slice(0, 2)));
        assert.ok(rows.some((r) => r.values.every((v) => v != null)), 'the comparison has numbers');
      }
      for (const r of log) {
        assert.equal(r.status, 200, `${r.path} not generated`);
        assert.match(r.path, /^\/api\/.+\.json$/, `${r.path}: only the generated JSON files`);
      }
    }
  } finally {
    server.close();
  }
});

test('the manifest matches the files', { skip: !HAS_SITE && 'no site generated' }, () => {
  const read = (url) => JSON.parse(readFileSync(join(DIR, staticFile(url)), 'utf8'));
  const manifest = read(URLS.manifest);
  assert.equal(manifest.format, 1);
  assert.ok(manifest.patch);
  let builds = 0;
  for (const c of manifest.champions) {
    const page = read(URLS.champion(c.unitCode));
    assert.equal(page.builds.length, c.builds, `${c.unitCode}: builds in the manifest`);
    for (const b of page.builds) {
      const full = read(URLS.build(b.key));
      assert.equal(full.key, b.key);
      assert.ok(full.timelines?.padrao?.steps?.length > 0, `${b.key}: timeline`);
      assert.ok(full.minutes?.length > 0 && full.minutes.every((m) => full.at[String(m)]), `${b.key}: key minutes`);
      builds++;
    }
  }
  assert.ok(builds > 0);
  if (manifest.fights) assert.equal(read(URLS.fights).length, manifest.fights);
});
