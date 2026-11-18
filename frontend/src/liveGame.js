/**
 * Rodada 20: the followed match (Simulações › Draft e partida) as plain data, and the pure changes the screen makes to
 * it. Mirrors `LiveGameModel.Game` on the backend; everything is optional.
 */

export const BLUE = 'azul';
export const RED = 'vermelho';
export const LANES = ['Barão', 'Selva', 'Meio', 'Dragão', 'Suporte'];

export const emptyPlayer = (lane) => ({
  champion: null, lane, spells: [], runes: { keystone: null, primary: null, secondary: null, primaryRunes: [], secondaryRune: null },
  level: null, items: [], components: [], startItem: null, gold: null, kills: null, deaths: null, assists: null,
});

export const emptyTeam = () => ({ name: null, bans: [], players: LANES.map(emptyPlayer), dragons: null, heralds: null, barons: null });

export const emptyGame = () => ({
  id: null, name: null, mode: 'ranqueada', fearless: false, fearlessScope: null, series: [], allySide: BLUE, elo: null,
  blue: emptyTeam(), red: emptyTeam(), predictions: {}, clock: { seconds: 0, setAt: null, paused: true }, events: [],
});

export const teamKey = (side) => (side === RED ? 'red' : 'blue');
export const enemyOf = (side) => (side === RED ? BLUE : RED);

/** A deep copy (the game is plain JSON). */
export const clone = (g) => JSON.parse(JSON.stringify(g));

/** The game second now: the one set plus the wall time since, while running. */
export function clockSeconds(clock, now = Date.now()) {
  if (!clock) return 0;
  if (clock.paused || clock.setAt == null) return clock.seconds ?? 0;
  return (clock.seconds ?? 0) + (now - clock.setAt) / 1000;
}

/** "7:30", "730", "7.30" or "7" -> seconds (null when unreadable). */
export function parseClock(text) {
  const s = String(text ?? '').trim();
  let m = s.match(/^(\d{1,2})\s*[:.,h]\s*(\d{1,2})$/);
  if (m) return Number(m[1]) * 60 + Number(m[2]);
  m = s.match(/^(\d{3,4})$/);
  if (m) return Number(m[1].slice(0, -2)) * 60 + Number(m[1].slice(-2));
  m = s.match(/^(\d{1,2})$/);
  if (m) return Number(m[1]) * 60;
  return null;
}

export const mmssOf = (sec) => {
  const s = Math.max(0, Math.floor(sec ?? 0));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
};

/** Sets the clock to a game second, keeping it running or stopped. */
export function setClock(g, seconds, now = Date.now()) {
  const out = clone(g);
  out.clock = { seconds, setAt: out.clock?.paused ? null : now, paused: !!out.clock?.paused };
  return out;
}

export function toggleClock(g, now = Date.now()) {
  const out = clone(g);
  const sec = clockSeconds(out.clock, now);
  out.clock = out.clock?.paused ? { seconds: sec, setAt: now, paused: false } : { seconds: sec, setAt: null, paused: true };
  return out;
}

/** Changes one player (side, index) with a function of its copy. */
export function updatePlayer(g, side, index, fn) {
  const out = clone(g);
  const p = out[teamKey(side)].players[index];
  fn(p);
  return out;
}

/** Adds an event at the clock's second (and its effect on the player, for purchases and the score). */
export function addEvent(g, ev, items = [], now = Date.now()) {
  const out = clone(g);
  const e = { t: Math.round(clockSeconds(out.clock, now)), ...ev };
  out.events.push(e);
  if (e.player != null && e.side) {
    const p = out[teamKey(e.side)].players[e.player];
    if (e.kind === 'compra' && e.item != null) buy(p, e.item, items);
    if (e.kind === 'morte') p.deaths = (p.deaths ?? 0) + 1;
    if (e.kind === 'abate') p.kills = (p.kills ?? 0) + 1;
  }
  const team = e.side ? out[teamKey(e.side)] : null;
  if (team && e.kind === 'dragao') team.dragons = (team.dragons ?? 0) + 1;
  if (team && e.kind === 'arauto') team.heralds = (team.heralds ?? 0) + 1;
  if (team && e.kind === 'barao') team.barons = (team.barons ?? 0) + 1;
  return out;
}

/**
 * A purchase: a finished item or boots goes to `items` (its components held leave `components`, upgraded boots
 * replace the boots they come from); a component goes to `components`.
 */
export function buy(p, itemId, items = []) {
  const byId = new Map(items.map((i) => [i.id, i]));
  const it = byId.get(itemId);
  // the components held that go into it are spent (the shop charges only the rest)
  const recipe = componentIds(it, items);
  const comps = [...(p.components ?? [])];
  for (const c of recipe) {
    const k = comps.indexOf(c);
    if (k >= 0) comps.splice(k, 1);
  }
  if (it && !isFinished(it)) {
    p.components = [...comps, itemId];
    return p;
  }
  p.components = comps;
  // boots bought over boots: the old ones were part of the recipe (an upgrade) or are sold (a swap)
  if (it && (it.tabs ?? []).includes('Botas')) {
    p.items = (p.items ?? []).filter((id) => !(byId.get(id)?.tabs ?? []).includes('Botas'));
  } else {
    p.items = (p.items ?? []).filter((id) => !recipe.includes(id));
  }
  if (!p.items.includes(itemId) || !it) p.items = [...p.items, itemId];
  return p;
}

/** Every component id of an item, down the tree (`components`: [{itemId, quantity}], as /api/items sends them). */
export function componentIds(it, items) {
  if (!it) return [];
  const byId = new Map(items.map((i) => [i.id, i]));
  const out = [];
  const walk = (x, depth) => {
    for (const c of x?.components ?? []) {
      const comp = byId.get(c.itemId);
      if (!comp) continue;
      for (let k = 0; k < (c.quantity ?? 1); k++) out.push(comp.id);
      if (depth < 3) walk(comp, depth + 1);
    }
  };
  walk(it, 0);
  return out;
}

/** A finished item (or boots past the basic ones): the rest are components. */
export const isFinished = (it) => !!it && (it.section === 'aprimorado' || ((it.tabs ?? []).includes('Botas') && it.section !== 'basico'));

/** The undo history: at most `max` earlier states. */
export function pushHistory(history, g, max = 60) {
  const out = [...history, clone(g)];
  return out.length > max ? out.slice(out.length - max) : out;
}

/** Champions picked by a side, in card order. */
export const picks = (g, side) => g[teamKey(side)].players.map((p) => p.champion).filter(Boolean);
