import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { ChampionAvatar } from './ChampionAvatar.jsx';
import ItemIcon from './ItemIcon.jsx';
import { localVars, teamKey } from '../liveGame.js';

const n1 = (v) => (v == null ? '—' : (Math.round(v * 10) / 10).toLocaleString('pt-BR'));
const pct = (v) => `${Math.round((v ?? 0) * 100)}%`;
const tone = (race) => (race >= 55 ? 'atlas-up' : race <= 45 ? 'atlas-down' : '');
const toneTrade = (v) => (v >= 5 ? 'atlas-up' : v <= -5 ? 'atlas-down' : '');

/**
 * Rodada 20, Parte 4: the first encounter of the selected champion with its lane rival: every start that fits the
 * starting gold against the rival's likely starts, at levels 1, 2 and 3, in the three lane styles. Recalculated when the
 * user tells the rival's real start.
 */
export default function EncounterPanel({ game, selected }) {
  const [res, setRes] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [all, setAll] = useState(false);
  const p = selected ? game[teamKey(selected.side)].players[selected.index] : null;
  // what changes the encounter: the two lanes' champions, starts, runes and spells
  const rivalKey = JSON.stringify([game.blue.players, game.red.players].map((ps) => ps.map((x) => [x.champion, x.lane, x.startItem, x.spells, x.runes?.keystone])));
  useEffect(() => { setRes(null); }, [selected?.side, selected?.index]);
  useEffect(() => {
    if (res) run();
    // recalculated when the rival's start or a champion changes after a first calculation
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rivalKey, JSON.stringify(game.predictions)]);
  async function run() {
    if (!selected) return;
    setBusy(true);
    setError(null);
    try {
      setRes(await api.post('/api/draft/encontro', { game, side: selected.side, index: selected.index }));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  if (!p?.champion) return <section className="panel"><p className="muted">{t('enc.pick')}</p></section>;
  const best = res?.best;
  const styleRows = res?.rows?.filter((r) => r.itemId === best?.itemId) ?? [];
  const rows = all ? res?.rows ?? [] : (res?.rows ?? []).filter((r) => r.style === 'padrao');
  return (
    <section className="panel encounter-panel">
      <div className="spread">
        <h4><ChampionAvatar name={p.champion} size={24} /> {t('enc.title', { champ: p.champion, lane: p.lane })}</h4>
        <button type="button" className="primary" disabled={busy} onClick={run}>{busy ? t('enc.busy') : res ? t('enc.again') : t('enc.run')}</button>
      </div>
      <p className="muted small">{t('enc.help')}</p>
      {error && <div className="notice error">{error}</div>}
      {res && (
        <>
          <p className="small">
            {t(`enc.rivals.${res.rivalSource}`)} {res.rivals.map((c) => <span key={c} className="enc-rival"><ChampionAvatar name={c} size={20} /> {c}</span>)}
            {res.partners.length > 0 && <> · {t('enc.partner')} {res.partners.join(', ')}</>}
            {' · '}{t('enc.cost', { s: n1(res.seconds), n: res.fights, prep: n1(res.prepareSeconds) })}
          </p>
          <div className="enc-starts small">
            <strong>{res.enemyStartKnown ? t('enc.enemyKnown') : t('enc.enemyLikely')}</strong>
            {res.enemyStarts.slice(0, 5).map((s) => (
              <span key={s.itemId} className="chip-item"><ItemIcon id={s.itemId} size={18} /> {s.name} {res.enemyStartKnown ? '' : pct(s.probability)}</span>
            ))}
          </div>
          {best && (
            <div className="enc-best">
              <p>
                <strong>{t('enc.best')}</strong> <ItemIcon id={best.itemId} size={22} /> {best.name}
                {' '}<span className={tone(best.mean)}>{t('enc.mean', { v: n1(best.mean) })}</span>
              </p>
              {res.advice && <p className="enc-advice">{t(`enc.adv.${res.advice.key}`, { ...localVars(res.advice.vars), style: t(`enc.style.${res.advice.vars.style}`) })}</p>}
              <div className="table-wrap"><table className="data">
                <thead><tr><th className="l">{t('enc.style')}</th>{best.levels.map((l) => <th key={l.level} colSpan={2}>{t('enc.level', { n: l.level, m: n1(l.minute) })}</th>)}</tr>
                  <tr><th />{best.levels.map((l) => [<th key={`t${l.level}`}>{t('enc.trade')}</th>, <th key={`a${l.level}`}>{t('enc.allIn')}</th>])}</tr></thead>
                <tbody>
                  {styleRows.map((r) => (
                    <tr key={r.style}><td className="l">{t(`enc.style.${r.style}`)}</td>
                      {r.levels.map((l) => [<td key={`t${l.level}`} className={toneTrade(l.trade)}>{n1(l.trade)}</td>, <td key={`a${l.level}`} className={tone(l.allIn)}>{n1(l.allIn)}</td>])}
                    </tr>
                  ))}
                </tbody>
              </table></div>
            </div>
          )}
          <details>
            <summary>{t('enc.allStarts')}</summary>
            <label className="live-check"><input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} /> {t('enc.allStyles')}</label>
            <div className="table-wrap"><table className="data">
              <thead><tr><th className="l">{t('enc.start')}</th><th>{t('enc.style')}</th>{(best?.levels ?? []).map((l) => <th key={l.level}>{t('enc.lvShort', { n: l.level })}</th>)}<th>{t('enc.meanCol')}</th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={`${r.itemId}-${r.style}`}>
                    <td className="l"><ItemIcon id={r.itemId} size={18} /> {r.name}</td><td>{t(`enc.style.${r.style}`)}</td>
                    {r.levels.map((l) => <td key={l.level} className={tone(l.allIn)} title={`${t('enc.trade')} ${n1(l.trade)}`}>{n1(l.allIn)}</td>)}
                    <td className={tone(r.mean)}>{n1(r.mean)}</td>
                  </tr>
                ))}
              </tbody>
            </table></div>
          </details>
        </>
      )}
    </section>
  );
}
