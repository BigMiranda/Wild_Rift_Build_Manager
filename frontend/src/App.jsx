import { useCallback, useEffect, useState } from 'react';
import { api } from './api.js';
import { loadSettings, saveSettings, setLanguage, t } from './i18n.js';
import Planner from './pages/Planner.jsx';
import Admin from './pages/Admin.jsx';
import About from './pages/About.jsx';
import Combat from './pages/Combat.jsx';

const TABS = ['planner', 'combat', 'admin', 'about'];

export default function App() {
  const [tab, setTab] = useState('planner');
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

  return (
    <>
      <header className="app-header">
        <h1>{t('app.title')}</h1>
        <nav>
          {TABS.map((key) => (
            <button key={key} className={tab === key ? 'active' : ''} onClick={() => setTab(key)}>
              {t(`nav.${key}`)}
            </button>
          ))}
        </nav>
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
        </div>
      </header>
      {error && (
        <div className="main">
          <div className="notice error">{t('error.backend', { msg: error })}</div>
        </div>
      )}
      {meta && tab === 'planner' && <Planner meta={meta} items={items} settings={settings} />}
      {meta && tab === 'combat' && <Combat />}
      {meta && tab === 'admin' && <Admin meta={meta} items={items} onChanged={reload} />}
      {tab === 'about' && <About />}
    </>
  );
}
