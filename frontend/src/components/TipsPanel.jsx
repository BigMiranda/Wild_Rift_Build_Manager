import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { ChampionAvatar } from './ChampionAvatar.jsx';
import { enemyOf, localVars, teamKey } from '../liveGame.js';

/** A tip in words: its template filled with the measured numbers. */
export function tipText(tip) {
  const v = { ...localVars(tip.vars) };
  if (v.who) v.who = t(`tip.who.${v.who}`);
  if (v.cc) v.cc = t(`tip.cc.${v.cc}`);
  if (v.mob) v.mob = t(`tip.mob.${v.mob}`);
  if (v.style) v.style = t(`tip.style.${v.style}`);
  if (v.turns === true) v.turns = t('tip.turns');
  else v.turns = '';
  if (v.range == null) v.range = '?';
  return t(`tip.${tip.kind}.${tip.key}`, v);
}

/**
 * Rodada 20, Parte 6: for the selected ally, tips against each enemy, the lane rival first (or the ones the user picks,
 * more than one). Windows of strength (Arena), threats of the kit (sheet: control, dashes, ranges and cooldowns at the
 * current rank) and the Rodada 14 lane-style guide; each tip says where it came from, and a sheet of quality C/D or
 * with PC/estimated numbers is marked.
 */
export default function TipsPanel({ game, selected }) {
  const [res, setRes] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [against, setAgainst] = useState([]);
  const p = selected ? game[teamKey(selected.side)].players[selected.index] : null;
  const enemies = selected ? game[teamKey(enemyOf(selected.side))].players.map((x, i) => ({ ...x, i })).filter((x) => x.champion) : [];
  useEffect(() => { setRes(null); setAgainst([]); }, [selected?.side, selected?.index]);
  async function run() {
    setBusy(true);
    setError(null);
    try {
      setRes(await api.post('/api/draft/dicas', { game, side: selected.side, index: selected.index, against }));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  if (!p?.champion) return <section className="panel"><p className="muted">{t('tip.pick')}</p></section>;
  const byEnemy = new Map();
  for (const tip of res?.tips ?? []) {
    if (!byEnemy.has(tip.enemy)) byEnemy.set(tip.enemy, []);
    byEnemy.get(tip.enemy).push(tip);
  }
  return (
    <section className="panel tips-panel">
      <div className="spread">
        <h4><ChampionAvatar name={p.champion} size={24} /> {t('tip.title', { champ: p.champion })}</h4>
        <button type="button" className="primary" disabled={busy || !enemies.length} onClick={run}>{busy ? t('tip.busy') : t('tip.run')}</button>
      </div>
      <p className="muted small">{t('tip.help')}</p>
      <div className="chips">
        <span className="muted small">{t('tip.against')}</span>
        <button type="button" className={against.length === 0 ? 'active' : ''} onClick={() => setAgainst([])}>{t('tip.laneFirst')}</button>
        {enemies.map((e) => (
          <button key={e.i} type="button" className={against.includes(e.i) ? 'active' : ''} aria-pressed={against.includes(e.i)}
            onClick={() => setAgainst((a) => (a.includes(e.i) ? a.filter((x) => x !== e.i) : [...a, e.i]))}>
            <ChampionAvatar name={e.champion} size={18} /> {e.champion}
          </button>
        ))}
      </div>
      {error && <div className="notice error">{error}</div>}
      {[...byEnemy.entries()].map(([enemy, tips]) => (
        <div key={enemy} className="tips-enemy">
          <h5><ChampionAvatar name={enemy} size={22} /> {t('tip.vs', { enemy })}</h5>
          <ul>
            {tips.map((tip, k) => (
              <li key={k} className={`tip tip-${tip.kind}`}>
                <span>{tipText(tip)}</span>
                <small className="tip-src" title={tip.estimated ? t('tip.estimatedHelp') : ''}>
                  {t(`tip.src.${tip.source}`)}{tip.quality ? ` ${tip.quality}` : ''}{tip.estimated ? ' ⚠' : ''}
                </small>
              </li>
            ))}
          </ul>
        </div>
      ))}
      {res && <small className="muted">{t('tip.cost', { s: (Math.round(res.seconds * 10) / 10).toLocaleString('pt-BR') })}</small>}
    </section>
  );
}
