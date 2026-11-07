import { useEffect, useState } from 'react';

/**
 * A hand-written hash router: `#/<super>/<sub>?key=value`. The browser's back button walks the hashes, and a link opens
 * a screen directly with its build and minute (`#/relatorios/efetividade?build=12&min=15`).
 */

/** Super tabs and their sub tabs, in the bar's order (the first sub tab is the super tab's default). */
export const ROUTES = {
  builds: ['lista'],
  build: ['loja', 'sequencia', 'grafico', 'status', 'comparar-itens', 'comparar-runas', 'runas', 'habilidades', 'guias'],
  relatorios: ['analise', 'campeao', 'efetividade', 'relevancia', 'ouro', 'ranking', 'comparar-builds'],
  simulacoes: ['arena', 'laboratorio', 'partida', 'calculadora', 'descoberta'],
  admin: ['catalogo', 'efetividade', 'sobre'],
};
export const SUPERS = Object.keys(ROUTES);
export const DEFAULT_PATH = '/build/loja';

/**
 * Where the old screens went: the tab names of the single bar (still sent by `ornn-open-tab` and found in old links
 * such as `#arena`) → the new path.
 */
export const LEGACY = {
  planner: '/build/loja',
  arena: '/simulacoes/arena',
  match: '/simulacoes/partida',
  effectiveness: '/admin/efetividade',
  calculator: '/simulacoes/calculadora',
  ranking: '/relatorios/ranking',
  admin: '/admin/catalogo',
  about: '/admin/sobre',
};

/** Parses a hash (with or without '#'): { sup, sub, params, path }. Unknown paths fall back to the default. */
export function parseHash(hash) {
  let h = (hash ?? '').replace(/^#/, '');
  const [rawPath, query = ''] = h.split('?');
  let path = rawPath;
  if (!path.startsWith('/')) path = LEGACY[path] ?? (path ? `/${path}` : DEFAULT_PATH);
  let [, sup, sub] = path.split('/');
  if (!ROUTES[sup]) [, sup, sub] = DEFAULT_PATH.split('/');
  if (!ROUTES[sup].includes(sub)) sub = ROUTES[sup][0];
  const params = {};
  for (const part of query.split('&')) {
    if (!part) continue;
    const [k, v = ''] = part.split('=');
    params[decodeURIComponent(k)] = decodeURIComponent(v);
  }
  return { sup, sub, params, path: `/${sup}/${sub}` };
}

/** The hash of a route: `#/sup/sub?k=v` (null / empty params left out). */
export function makeHash(path, params) {
  const q = Object.entries(params ?? {}).filter(([, v]) => v != null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
  return `#${path}${q ? `?${q}` : ''}`;
}

const LAST_KEY = 'ornn-planner-route';

/** Goes to a path (new history entry), keeping the params given; `replace` changes the current entry instead. */
export function navigate(path, params, { replace = false } = {}) {
  const resolved = path.startsWith('/') ? path : LEGACY[path] ?? DEFAULT_PATH;
  const hash = makeHash(resolved, params);
  if (window.location.hash === hash) return;
  if (replace) window.history.replaceState(null, '', hash);
  else window.location.hash = hash;
  if (replace) window.dispatchEvent(new HashChangeEvent('hashchange'));
}

/** The current route, following the hash; the last one is remembered so a bare reload opens where it was. */
export function useRoute() {
  const [route, setRoute] = useState(() => {
    let h = window.location.hash;
    if (!h || h === '#' || h === '#/') {
      try {
        h = localStorage.getItem(LAST_KEY) ?? '';
      } catch {
        h = '';
      }
      if (h) window.history.replaceState(null, '', h.startsWith('#') ? h : `#${h}`);
    }
    return parseHash(h);
  });
  useEffect(() => {
    const on = () => {
      const r = parseHash(window.location.hash);
      // an old link (#arena) or an unknown one: show the canonical hash
      const canonical = makeHash(r.path, r.params);
      if (window.location.hash !== canonical) window.history.replaceState(null, '', canonical);
      setRoute(r);
      try {
        localStorage.setItem(LAST_KEY, canonical);
      } catch {
        /* session only */
      }
    };
    on();
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return route;
}
