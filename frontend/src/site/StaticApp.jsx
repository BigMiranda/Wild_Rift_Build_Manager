import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { setRuneCatalog } from '../runes.js';
import { makeStore } from './data.js';
import { ChampionsPage, ChampionPage } from './Champions.jsx';
import BuildPage from './BuildPage.jsx';
import ComparePage from './Compare.jsx';
import { BuilderPage, MyBuildsPage } from './Builder.jsx';
import { FightsPage, FightPage } from './Fights.jsx';
import AboutPage from './About.jsx';
import './site.css';

/**
 * The static site (Rodada 11.b, part 3): champions → builds → a build, the comparison, the builder and the visitor's
 * builds, the recorded fights. Everything shown was calculated on the author's PC; each screen reads only its JSON
 * files (data.js), on demand. Phone first, in portrait too.
 */

const SiteContext = createContext(null);
export const useSite = () => useContext(SiteContext);

const COMPARE_KEY = 'ornn-site-compare';
const SCALE_KEY = 'ornn-site-scale';
export const MAX_COMPARED = 3;

/** `#/c/ORNN`, `#/b/<key>?m=15`, `#/comparar?k=a,b`, `#/montar?b=<code>`; the app's own share link opens the builder. */
export function parseSiteHash(hash) {
  const h = (hash ?? '').replace(/^#/, '');
  const [path, query = ''] = h.split('?');
  const params = {};
  for (const part of query.split('&')) {
    if (!part) continue;
    const i = part.indexOf('=');
    const k = decodeURIComponent(i < 0 ? part : part.slice(0, i));
    params[k] = i < 0 ? '' : decodeURIComponent(part.slice(i + 1));
  }
  const parts = path.split('/').filter(Boolean).map(decodeURIComponent);
  if (parts[0] === 'build' && params.b) return { page: 'montar', params };   // the full app's "#/build/loja?b=..."
  const page = parts[0] ?? '';
  return { page: ['c', 'b', 'comparar', 'montar', 'minhas', 'lutas', 'luta', 'sobre'].includes(page) ? page : '', arg: parts[1], params };
}

export function siteHref(page, arg, params) {
  const q = Object.entries(params ?? {}).filter(([, v]) => v != null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
  return `#/${page}${arg != null ? `/${encodeURIComponent(arg)}` : ''}${q ? `?${q}` : ''}`;
}

function useHash() {
  const [route, setRoute] = useState(() => parseSiteHash(window.location.hash));
  useEffect(() => {
    const on = () => {
      setRoute(parseSiteHash(window.location.hash));
      window.scrollTo(0, 0);
    };
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return route;
}

const readList = (key) => {
  try {
    const v = JSON.parse(localStorage.getItem(key) ?? '[]');
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
};

export default function StaticApp() {
  const route = useHash();
  const store = useMemo(() => makeStore((url) => api.get(url)), []);
  const [manifest, setManifest] = useState(null);
  const [error, setError] = useState(null);
  const [compare, setCompare] = useState(() => readList(COMPARE_KEY).slice(0, MAX_COMPARED));
  const [scale, setScale] = useState(() => localStorage.getItem(SCALE_KEY) ?? 'grande');

  useEffect(() => {
    store.manifest().then(setManifest).catch((e) => setError(e.message));
  }, [store]);
  useEffect(() => {
    document.documentElement.dataset.scale = scale;
    document.documentElement.lang = 'pt-BR';
    document.title = 'Wild Rift Builds';
    try {
      localStorage.setItem(SCALE_KEY, scale);
    } catch {
      /* session only */
    }
  }, [scale]);
  useEffect(() => {
    try {
      localStorage.setItem(COMPARE_KEY, JSON.stringify(compare));
    } catch {
      /* session only */
    }
  }, [compare]);
  // the rune names and icons, for the pages that show a rune page (a small file, read once)
  const [runesReady, setRunesReady] = useState(false);
  useEffect(() => {
    if (['b', 'comparar', 'montar'].includes(route.page) && !runesReady) {
      store.runes().then((r) => { setRuneCatalog(r); setRunesReady(true); }).catch(() => {});
    }
  }, [route.page, runesReady, store]);

  const toggleCompare = (key) => setCompare((c) => (c.includes(key) ? c.filter((k) => k !== key)
    : c.length >= MAX_COMPARED ? [...c.slice(1), key] : [...c, key]));

  const ctx = { store, manifest, compare, toggleCompare, setCompare, runesReady };
  let body;
  if (error) {
    body = <p className="error">Não deu para abrir o site: {error}</p>;
  } else if (!manifest) {
    body = <p className="muted">Carregando…</p>;
  } else if (route.page === 'c') {
    body = <ChampionPage unit={route.arg} />;
  } else if (route.page === 'b') {
    body = <BuildPage buildKey={route.arg} params={route.params} />;
  } else if (route.page === 'comparar') {
    body = <ComparePage keys={(route.params.k ?? '').split(',').filter(Boolean)} />;
  } else if (route.page === 'montar') {
    body = <BuilderPage params={route.params} />;
  } else if (route.page === 'minhas') {
    body = <MyBuildsPage />;
  } else if (route.page === 'lutas') {
    body = <FightsPage />;
  } else if (route.page === 'luta') {
    body = <FightPage id={route.arg} />;
  } else if (route.page === 'sobre') {
    body = <AboutPage scale={scale} setScale={setScale} />;
  } else {
    body = <ChampionsPage />;
  }
  const tab = (page, label, extra) => (
    <a href={siteHref(page, null, extra)} className={route.page === page || (page === '' && route.page === 'c') ? 'on' : ''}>{label}</a>
  );
  return (
    <SiteContext.Provider value={ctx}>
      <header className="site-header">
        <a className="site-brand" href="#/">Wild Rift Builds <small>{manifest ? `patch ${manifest.patch}` : ''}</small></a>
        <nav className="site-nav">
          {tab('', 'Campeões')}
          {tab('comparar', `Comparar${compare.length ? ` (${compare.length})` : ''}`, { k: compare.join(',') })}
          {tab('montar', 'Montar')}
          {tab('minhas', 'Minhas')}
          {manifest?.fights > 0 && tab('lutas', 'Lutas')}
          {tab('sobre', 'Sobre')}
        </nav>
      </header>
      <main className="site-main">{body}</main>
      <footer className="site-footer muted">
        Números calculados no PC do autor{manifest?.export?.exported_at ? `, exportados em ${manifest.export.exported_at.slice(0, 10)}` : ''}.
        Nada é recalculado aqui.
      </footer>
    </SiteContext.Provider>
  );
}
