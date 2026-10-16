import { useEffect, useState } from 'react';
import { api } from './api.js';

/**
 * What each item and rune effect of a build is worth at a minute, by the arena's own formulas
 * (POST /api/lab/effect-values): [{ source, text, detail?, value?, type? }]. Debounced, and shared by the fixed build
 * bar and the passives tab through a small cache, so both ask once.
 */
const cache = new Map();
const MAX = 30;

function fetchValues(key, minute) {
  const k = `${minute}|${key}`;
  if (!cache.has(k)) {
    if (cache.size >= MAX) cache.delete(cache.keys().next().value);
    const p = api.post('/api/lab/effect-values', { build: JSON.parse(key), minute });
    p.catch(() => cache.delete(k));
    cache.set(k, p);
  }
  return cache.get(k);
}

/**
 * The values for the build and minute: { values, minute, stale }. While the minute (or the build) just changed, the
 * previous values stay, marked stale, until the new ones arrive.
 */
export function useEffectValues(draft, minute) {
  const [loaded, setLoaded] = useState({ key: null, minute: null, values: null });
  const key = draft ? JSON.stringify(draft) : null;
  useEffect(() => {
    if (!key || minute == null) return undefined;
    let live = true;
    const timer = setTimeout(() => {
      fetchValues(key, minute)
        .then((v) => { if (live) setLoaded({ key, minute, values: v }); })
        .catch(() => { if (live) setLoaded({ key, minute, values: null }); });
    }, 200);
    return () => { live = false; clearTimeout(timer); };
  }, [key, minute]);
  return { values: loaded.values, minute: loaded.minute, stale: loaded.key !== key || loaded.minute !== minute };
}

export const valuesOf = (values, source) => (values ?? []).filter((v) => v.source === source);
