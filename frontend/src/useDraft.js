import { useEffect, useMemo, useState } from 'react';
import { api } from './api.js';

/** What the draft's lists depend on: picks, possible and fixed lanes, bans, predictions, the series, sides, mode, elo. */
export function draftKey(game) {
  if (!game) return '';
  const side = (tm) => tm.players.map((p) => [p.champion, p.lane, p.lanes]);
  return JSON.stringify({
    b: side(game.blue), r: side(game.red), bb: game.blue.bans, rb: game.red.bans, p: game.predictions,
    f: [game.fearless, game.series], a: game.allySide, m: game.mode, e: game.elo,
  });
}

/**
 * Rodada 20 / 20.b: the draft's suggestions (POST /api/draft/sugestoes) for a game after a branch of hypothetical picks,
 * debounced, only when what they depend on changes. {data, busy, error}.
 */
export function useDraftSuggestions(game, path = [], enabled = true) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const key = useMemo(() => draftKey(game), [game]);
  const pathKey = JSON.stringify(path);
  useEffect(() => {
    if (!game || !enabled) return undefined;
    let live = true;
    setBusy(true);
    const h = setTimeout(() => {
      api.post('/api/draft/sugestoes', { game, path })
        .then((d) => { if (live) { setData(d); setError(null); } })
        .catch((e) => live && setError(e.message))
        .finally(() => live && setBusy(false));
    }, 250);
    return () => { live = false; clearTimeout(h); };
    // the game's other fields (items, clock, events) do not change the draft
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, pathKey, enabled]);
  return { data, busy, error };
}

/** Rodada 20.b: a side's lane distribution per card: {slot: {lanes, likely, fixed}} from the suggestions. */
export function laneDistBySlot(data, side) {
  const out = {};
  for (const p of data?.lanes?.[side]?.picks ?? []) if (p.slot >= 0) out[p.slot] = p;
  return out;
}
