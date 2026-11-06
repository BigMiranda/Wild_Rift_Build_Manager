import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api.js';
import { loadSettings, saveSettings, setLanguage, t } from './i18n.js';
import { LEGACY, ROUTES, SUPERS, navigate, useRoute } from './router.js';
import { BuildProvider, useBuild } from './buildContext.jsx';
import { DrawerProvider, useDrawer } from './components/Drawer.jsx';
import { ItemReaderContext } from './components/BuildDock.jsx';
import BuildHeader, { ItemReaderLive } from './components/BuildHeader.jsx';
import TaskBell from './components/TaskBell.jsx';
import {
  BuildFields, BuildLabSection, BuildMessage, ChartSection, CompareItemsSection, EffectiveGoldSection, EffectivenessSection,
  ItemEffectsSection, PassivesSection, PrepEditors, PrepSection, RelevanceSection, SequenceBar, ShopSection, SkillsSection,
  StatusSection, TimelineSection, goCompare,
} from './pages/Planner.jsx';
import Builds from './pages/Builds.jsx';
import Analysis, { CompareBuilds } from './pages/Analysis.jsx';
import ChampionReport from './pages/ChampionReport.jsx';
import RuneCompare from './components/RuneCompare.jsx';
import Admin from './pages/Admin.jsx';
import About from './pages/About.jsx';
import Arena from './pages/Arena.jsx';
import Match from './pages/Match.jsx';
import Calculator from './pages/Calculator.jsx';
import Ranking from './pages/Ranking.jsx';
import Effectiveness from './pages/Effectiveness.jsx';

/** Interface scale (Rodada 8, parte 6): normal | grande | live, remembered in the browser. */
const SCALES = ['normal', 'grande', 'live'];

export default function App() {
  const [meta, setMeta] = useState(null);
  const [items, setItems] = useState([]);
  const [error, setError] = useState(null);
  const [settings, setSettings] = useState(loadSettings);

  // Module-level language used by t()/statLabel(); set before rendering children.
  setLanguage(settings);

  const changeSettings = (patch) => {
    const next = { ...settings, ...patch };
    saveSettings(next);
    setSettings(next);
  };

  useEffect(() => {
    document.documentElement.lang = settings.lang === 'en' ? 'en' : 'pt-BR';
  }, [settings.lang]);
  useEffect(() => {
    document.documentElement.dataset.scale = settings.scale ?? 'normal';
  }, [settings.scale]);

  // Reference data is reloaded after admin edits so the planner always calculates with the stored values.
  const reload = useCallback(async () => {
    try {
      const [m, it] = await Promise.all([api.get('/api/meta'), api.get('/api/items')]);
      setMeta(m);
      setItems(it);
      setError(null);
    } catch (e) {
      setError(e.message);
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  // Other screens may open a tab by its old name (the planner's "Abrir na Arena", the match's fights).
  useEffect(() => {
    const open = (e) => LEGACY[e.detail] && navigate(LEGACY[e.detail]);
    window.addEventListener('ornn-open-tab', open);
    return () => window.removeEventListener('ornn-open-tab', open);
  }, []);

  // The matchup tab is gone (round 4): drop the setup it kept in the browser.
  useEffect(() => {
    try {
      Object.keys(localStorage).filter((k) => k.startsWith('ornn-planner-combat')).forEach((k) => localStorage.removeItem(k));
    } catch {
      /* storage unavailable */
    }
  }, []);

  const settingsBox = (
    <div className="settings">
      <label>
        {t('settings.lang')}
        <select value={settings.lang} onChange={(e) => changeSettings({ lang: e.target.value })}>
          <option value="pt">Português</option>
          <option value="en">English</option>
        </select>
      </label>
      <label>
        {t('settings.abbr')}
        <select value={settings.abbr} onChange={(e) => changeSettings({ abbr: e.target.value })}>
          <option value="pt">DdA · PdH · RM</option>
          <option value="en">AD · AP · MR</option>
        </select>
      </label>
      <label title={t('settings.scaleHelp')}>
        {t('settings.scale')}
        <select value={settings.scale ?? 'normal'} onChange={(e) => changeSettings({ scale: e.target.value })}>
          {SCALES.map((s) => <option key={s} value={s}>{t(`settings.scale.${s}`)}</option>)}
        </select>
      </label>
    </div>
  );

  if (!meta) {
    return (
      <>
        <header className="app-header"><h1>{t('app.title')}</h1>{settingsBox}</header>
        <div className="main">
          {error ? <div className="notice error">{t('error.backend', { msg: error })}</div> : <p className="muted">{t('compare.calculating')}</p>}
          {error && <button onClick={reload}>{t('app.retry')}</button>}
        </div>
      </>
    );
  }
  return (
    <BuildProvider meta={meta} items={items}>
      <DrawerProvider>
        <Shell meta={meta} items={items} reload={reload} error={error} settingsBox={settingsBox} />
      </DrawerProvider>
    </BuildProvider>
  );
}

/** The screen of each route; routes with the same `pane` share one mounted instance. */
function paneOf(sup, sub) {
  if (sup === 'simulacoes' && (sub === 'arena' || sub === 'laboratorio')) return 'simulacoes/arena';
  return `${sup}/${sub}`;
}

function Shell({ meta, items, reload, error, settingsBox }) {
  const route = useRoute();
  const b = useBuild();
  const drawer = useDrawer();
  // Any item name of the reports opens its reading in the drawer (it was the old fixed bar's reader).
  const readItem = useCallback((id) => {
    if (id != null) drawer.open({ title: b.itemsById.get(id)?.name ?? '', render: () => <ItemReaderLive id={id} /> });
  }, [drawer.open, b.itemsById]);
  const active = paneOf(route.sup, route.sub);
  // Every screen visited stays mounted (hidden): results, drafts and running jobs survive a change of tab.
  const [visited, setVisited] = useState(() => new Set([active]));
  useEffect(() => {
    setVisited((v) => (v.has(active) ? v : new Set([...v, active])));
  }, [active]);
  useRouteSync(route);

  const pane = (key, render) => (visited.has(key) ? (
    <div key={key} className="pane" hidden={key !== active}>{render()}</div>
  ) : null);
  const ready = !!b.draft;
  const main = (children, cls = '') => <div className={`main ${cls}`}><BuildMessage />{ready ? children : null}</div>;

  return (
    <ItemReaderContext.Provider value={readItem}>
      <div className="app-top">
      <header className="app-header">
        <h1>{t('app.title')}</h1>
        <nav className="super-tabs" aria-label={t('nav.super')}>
          {SUPERS.map((key) => (
            <button key={key} className={route.sup === key ? 'active' : ''} aria-current={route.sup === key ? 'page' : undefined}
              onClick={() => navigate(`/${key}/${lastSub(key)}`)}>
              {t(`super.${key}`)}
            </button>
          ))}
        </nav>
        <TaskBell />
        {settingsBox}
      </header>
      <nav className="sub-tabs" aria-label={t(`super.${route.sup}`)}>
        {ROUTES[route.sup].map((sub) => (
          <button key={sub} className={route.sub === sub ? 'active' : ''} aria-current={route.sub === sub ? 'page' : undefined}
            onClick={() => navigate(`/${route.sup}/${sub}`)}>
            {t(`sub.${route.sup}.${sub}`)}
          </button>
        ))}
      </nav>
      {route.sup !== 'admin' && <BuildHeader />}
      </div>
      {error && <div className="main"><div className="notice error">{t('error.backend', { msg: error })}</div></div>}

      {pane('builds/lista', () => main(<Builds />))}

      {pane('build/loja', () => main(<><BuildFields /><ShopSection onCompare={goCompare} /><section className="panel"><ItemEffectsSection /></section></>))}
      {pane('build/sequencia', () => main(<><section className="panel"><SequenceBar onCompare={goCompare} /></section><TimelineSection /></>))}
      {pane('build/grafico', () => main(<ChartSection />))}
      {pane('build/status', () => main(<StatusSection />))}
      {pane('build/comparar-itens', () => main(<><section className="panel"><SequenceBar /></section><CompareItemsSection /></>))}
      {pane('build/comparar-runas', () => main(<RuneCompare />))}
      {pane('build/runas', () => main(<PrepSection />))}
      {pane('build/habilidades', () => main(<SkillsSection />))}
      {pane('build/guias', () => main(<section className="panel"><h3>{t('sub.build.guias')}</h3><p className="muted">{t('guides.soon')}</p></section>))}

      {pane('relatorios/analise', () => main(<><Analysis /><PassivesSection /></>))}
      {pane('relatorios/campeao', () => main(<ChampionReport />))}
      {pane('relatorios/efetividade', () => main(<EffectivenessSection />))}
      {pane('relatorios/relevancia', () => main(<RelevanceSection />))}
      {pane('relatorios/ouro', () => main(<EffectiveGoldSection />))}
      {pane('relatorios/ranking', () => <Ranking items={items} />)}
      {pane('relatorios/comparar-builds', () => main(<CompareBuilds />))}

      {pane('simulacoes/arena', () => (
        <>
          {route.sub === 'laboratorio' && ready && <div className="main lab-build"><BuildLabSection /></div>}
          <Arena forcedTab={route.sub === 'laboratorio' ? 'lab' : 'scenario'} />
        </>
      ))}
      {pane('simulacoes/partida', () => <Match />)}
      {pane('simulacoes/calculadora', () => <Calculator />)}

      {pane('admin/catalogo', () => <Admin meta={meta} items={items} onChanged={reload} />)}
      {pane('admin/efetividade', () => <Effectiveness items={items} />)}
      {pane('admin/sobre', () => <About base={meta?.base} />)}
      <PrepEditors />
    </ItemReaderContext.Provider>
  );
}

const LAST_SUB = 'ornn-planner-last-sub';
/** The sub tab last open in a super tab (a super tab opens where it was left). */
function lastSub(sup) {
  try {
    const m = JSON.parse(localStorage.getItem(LAST_SUB) ?? '{}');
    return ROUTES[sup].includes(m[sup]) ? m[sup] : ROUTES[sup][0];
  } catch {
    return ROUTES[sup][0];
  }
}

/**
 * The URL and the open build follow each other: `?build=12&min=15` opens that build at that minute (asking first when
 * the draft has unsaved changes), and opening a build or picking a minute writes them back (replacing the entry).
 */
function useRouteSync(route) {
  const { draft, builds, openBuild, selectedMinute, setSelectedMinute } = useBuild();
  const applied = useRef(false);
  const wantBuild = route.params.build != null && route.params.build !== '' ? Number(route.params.build) : null;
  const wantMin = route.params.min != null && route.params.min !== '' ? Number(route.params.min) : null;

  useEffect(() => {
    try {
      const m = JSON.parse(localStorage.getItem(LAST_SUB) ?? '{}');
      m[route.sup] = route.sub;
      localStorage.setItem(LAST_SUB, JSON.stringify(m));
    } catch {
      /* session only */
    }
  }, [route.sup, route.sub]);

  // URL → state
  useEffect(() => {
    if (!draft || builds.length === 0) return;
    (async () => {
      if (wantBuild != null && wantBuild !== draft.id && builds.some((x) => x.id === wantBuild)) {
        // declined (unsaved draft kept): the URL goes back to the open build
        if (!(await openBuild(wantBuild, { minute: wantMin }))) {
          navigate(route.path, { ...route.params, build: draft.id ?? undefined }, { replace: true });
        }
      } else if (wantMin != null && Number.isFinite(wantMin) && Math.abs((selectedMinute ?? -1) - wantMin) > 0.005) {
        setSelectedMinute(wantMin);
      }
      applied.current = true;
    })();
    // the URL's params only: the state's side is the effect below
  }, [wantBuild, wantMin, !!draft, builds.length]);

  // state → URL
  useEffect(() => {
    if (!applied.current || !draft) return;
    const params = { ...route.params, build: draft.id ?? undefined, min: selectedMinute != null ? Math.round(selectedMinute * 100) / 100 : undefined };
    if (String(params.build ?? '') !== String(route.params.build ?? '') || String(params.min ?? '') !== String(route.params.min ?? '')) {
      navigate(route.path, params, { replace: true });
    }
  }, [draft?.id, selectedMinute, route.path]);
}
