import { useCallback, useEffect, useState } from 'react';
import { api } from './api.js';
import Planner from './pages/Planner.jsx';
import Admin from './pages/Admin.jsx';
import About from './pages/About.jsx';

const TABS = [
  ['planner', 'Planejador'],
  ['admin', 'Admin'],
  ['about', 'Sobre'],
];

export default function App() {
  const [tab, setTab] = useState('planner');
  const [meta, setMeta] = useState(null);
  const [items, setItems] = useState([]);
  const [error, setError] = useState(null);

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
        <h1>Ornn Build Timing Planner</h1>
        <nav>
          {TABS.map(([key, label]) => (
            <button key={key} className={tab === key ? 'active' : ''} onClick={() => setTab(key)}>
              {label}
            </button>
          ))}
        </nav>
      </header>
      {error && (
        <div className="main">
          <div className="notice error">
            Não foi possível falar com o backend ({error}). Ele está rodando em http://localhost:8080?
          </div>
        </div>
      )}
      {meta && tab === 'planner' && <Planner meta={meta} items={items} />}
      {meta && tab === 'admin' && <Admin meta={meta} items={items} onChanged={reload} />}
      {tab === 'about' && <About />}
    </>
  );
}
