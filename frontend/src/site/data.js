/**
 * What each screen of the static site reads (Rodada 11.b, part 3). Pure: no React and no browser, so a Node test can
 * walk the screens over the generated folder. Every read goes through `get(url)` with the API's own address
 * (`/api/site/build/x`); the caller maps it to a file (api.js → staticPath.js).
 */

export const URLS = {
  manifest: '/api/manifest',
  champion: (unit) => `/api/site/campeao/${encodeURIComponent(unit)}`,
  build: (key) => `/api/site/build/${encodeURIComponent(key)}`,
  items: '/api/items',
  runes: '/api/runes',
  champions: '/api/champions',
  basic: '/api/effect/reports/basico',
  fights: '/api/site/lutas',
  fight: (id) => `/api/arena/run/${id}`,
  scenario: (id) => `/api/arena/scenarios/${id}`,
};

/** Kinds of build, in the order the champion's page lists them. */
export const KINDS = ['padrao', 'suporte', 'melhor', 'publica', 'nicho', 'descoberta'];
export const KIND_LABEL = {
  padrao: 'Padrão robusta',
  suporte: 'Melhor de suporte',
  melhor: 'Melhor do ranking',
  nicho: 'Melhor do nicho',
  descoberta: 'Descoberta',
  publica: 'Build pública',
};

/**
 * Where a build is listed (Rodada 13): the best builds, or the "Novidade" section (niches' best and discoveries).
 * Files written before the section existed fall back by their kind.
 */
export const sectionOf = (b) => b?.section ?? (b?.kind === 'nicho' || b?.kind === 'descoberta' ? 'novidade' : 'melhores');

/** Where a build's rune page came from (Rodada 13), as the page says it. */
export const RUNES_FROM = {
  busca: 'runas buscadas para esta build',
  padrao: 'runas da padrão robusta (não buscadas para esta build)',
  publica: 'runas da build pública (não buscadas para esta build)',
};

/** The reads of the site, each one fetched once (the catalog is cached by the browser too). */
export function makeStore(get) {
  const cache = new Map();
  const once = (url) => {
    if (!cache.has(url)) {
      const p = get(url);
      cache.set(url, p);
      p.catch(() => cache.delete(url));
    }
    return cache.get(url);
  };
  return {
    manifest: () => once(URLS.manifest),
    champion: (unit) => once(URLS.champion(unit)),
    build: (key) => once(URLS.build(key)),
    builds: (keys) => Promise.all(keys.map((k) => once(URLS.build(k)))),
    items: () => once(URLS.items),
    runes: () => once(URLS.runes),
    champions: () => once(URLS.champions),
    basic: () => once(URLS.basic),
    fights: () => once(URLS.fights),
    fight: (id) => once(URLS.fight(id)),
    scenario: (id) => once(URLS.scenario(id)),
  };
}

const norm = (s) => String(s ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

/** The champions of the manifest that pass the filters (text, class, lane, archetype of any of their builds). */
export function filterChampions(list, { text = '', cls = '', lane = '', archetype = '' } = {}) {
  const q = norm(text);
  return (list ?? []).filter((c) => (!q || norm(c.champion).includes(q) || norm(c.unitCode).includes(q))
    && (!cls || c.championClass === cls)
    && (!lane || (c.lanes ?? []).includes(lane))
    && (!archetype || (c.archetypes ?? []).includes(archetype)));
}

/** The values a filter can take, from the manifest. */
export function filterOptions(list) {
  const set = (f) => [...new Set((list ?? []).flatMap(f).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'pt-BR'));
  return {
    classes: set((c) => [c.championClass]),
    lanes: set((c) => c.lanes ?? []),
    archetypes: set((c) => c.archetypes ?? []),
  };
}

/** A champion's builds that pass the filters, discovered ones first. */
export function filterBuilds(builds, { kind = '', archetype = '' } = {}) {
  return (builds ?? []).filter((b) => (!kind || b.kind === kind) && (!archetype || b.archetype === archetype))
    .sort((a, b) => KINDS.indexOf(a.kind) - KINDS.indexOf(b.kind));
}

/** The weighted index of a set of outcomes (EffectTestService / effectiveness.js weightedIndex). */
export function weightedOf(entries, outcomes) {
  let s = 0;
  let w = 0;
  (entries ?? []).forEach((e, i) => {
    const v = outcomes?.[i]?.index;
    if (typeof v === 'number' && Number.isFinite(v)) {
      s += (e.weight ?? 1) * v;
      w += e.weight ?? 1;
    }
  });
  return w > 0 ? s / w : null;
}

/** The key minutes of a build as their keys in `at` ("10", "15", "24.14"). */
export const minuteKeys = (b) => (b?.minutes ?? []).map((m) => String(m));
export const atOf = (b, m) => b?.at?.[String(m)] ?? null;

/** The minute a build buys each item, at a pace ("padrao" | "atras" | "frente"): the purchases that are not implied. */
export function purchases(b, pace = 'padrao') {
  const t = b?.timelines?.[pace] ?? b?.timelines?.padrao;
  return (t?.steps ?? []).filter((s) => !s.implied).map((s) => ({ itemId: s.itemId, name: s.itemName, minute: s.minute, level: s.level }));
}

const STATS = ['Max Health', 'Armor', 'Magic Resistance', 'Attack Damage', 'Ability Power', 'Ability Haste'];

/**
 * The side-by-side comparison of two or three precomputed builds: one row per number, each build's value at the
 * same kind of minute (its last purchase), and which one is best. Nothing is recalculated.
 */
export function compareRows(builds) {
  const last = (b) => atOf(b, b?.minutes?.[b.minutes.length - 1]);
  const rows = [];
  const add = (label, values, better = 'high', format = 'n1', stat = false) => {
    const nums = values.map((v) => (typeof v === 'number' && Number.isFinite(v) ? v : null));
    const present = nums.filter((v) => v != null);
    const best = present.length < 2 || better === 'none' ? null : better === 'high' ? Math.max(...present) : Math.min(...present);
    rows.push({ label, values: nums, best, format, stat });
  };
  add('Efetividade defensiva (último item)', builds.map((b) => b.index));
  add('Efetividade ofensiva (último item)', builds.map((b) => b.offenseIndex));
  add('Último item (minuto)', builds.map((b) => b.minutes?.[b.minutes.length - 1]), 'low', 'mmss');
  add('Custo total', builds.map((b) => (b.timelines?.padrao?.steps ?? []).reduce((s, x) => (x.implied ? s : s + (x.itemCost ?? 0)), 0)), 'none', 'n0');
  for (const stat of STATS) add(stat, builds.map((b) => last(b)?.point?.total?.[stat]), 'high', 'n0', true);
  return rows;
}

/** Every address the screens of one champion, one of its builds and the comparison of two read (the Node test). */
export async function walk(store, unit) {
  const m = await store.manifest();
  const c = await store.champion(unit ?? m.champions[0].unitCode);
  const keys = c.builds.map((b) => b.key);
  await store.build(keys[0]);
  await store.runes();
  await store.basic();
  await store.builds(keys.slice(0, Math.min(3, keys.length)));
  return { manifest: m, champion: c, keys };
}
