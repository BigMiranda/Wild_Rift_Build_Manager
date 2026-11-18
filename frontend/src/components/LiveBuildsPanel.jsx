import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { ChampionAvatar } from './ChampionAvatar.jsx';
import ItemIcon from './ItemIcon.jsx';
import { teamKey } from '../liveGame.js';

const n1 = (v) => (v == null ? '—' : (Math.round(v * 10) / 10).toLocaleString('pt-BR'));
/** A probability in %, with 2 significant digits (a whole build is a product of 6 choices: often under 1%). */
const pct = (v) => `${((v ?? 0) * 100).toLocaleString('pt-BR', { maximumSignificantDigits: 2 })}%`;
const signed = (v) => (v == null ? '—' : `${v > 0 ? '+' : ''}${n1(v)}`);
const tone = (v) => (v > 2 ? 'atlas-up' : v < -2 ? 'atlas-down' : '');

/**
 * Rodada 20, Parte 5: the 10 most likely complete builds of the selected champion (the progression model from what it
 * bought, its runes and both teams), by importance for the near context (how much the next item changes the duel with
 * the lane rival and the Basic Effectiveness), with the probability beside; "ordenar por probabilidade" swaps the order.
 * On the ally side, the answer: the next item of each ally against the enemy's likely builds.
 */
export default function LiveBuildsPanel({ game, selected }) {
  const [res, setRes] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [byProb, setByProb] = useState(false);
  const p = selected ? game[teamKey(selected.side)].players[selected.index] : null;
  const key = JSON.stringify([selected, p?.champion, p?.items, p?.components, p?.gold, game.blue.players.map((x) => [x.champion, x.items]), game.red.players.map((x) => [x.champion, x.items])]);
  async function run(measure) {
    if (!selected || !p?.champion) return;
    setBusy(true);
    setError(null);
    try {
      setRes(await api.post(`/api/draft/builds?medir=${measure}`, { game, side: selected.side, index: selected.index }));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  // the chain alone is quick: it follows every purchase; the fights wait for the button
  useEffect(() => { run(false); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [key]);
  if (!p?.champion) return <section className="panel"><p className="muted">{t('lb.pick')}</p></section>;
  const builds = [...(res?.builds ?? [])];
  if (byProb) builds.sort((a, b) => b.probability - a.probability);
  return (
    <section className="panel live-builds">
      <div className="spread">
        <h4><ChampionAvatar name={p.champion} size={24} /> {t('lb.title', { champ: p.champion })}</h4>
        <div className="row">
          <button type="button" className={byProb ? 'active' : ''} aria-pressed={byProb} onClick={() => setByProb((v) => !v)}>{t('lb.byProb')}</button>
          <button type="button" className="primary" disabled={busy} onClick={() => run(true)}>{busy ? t('lb.busy') : t('lb.measure')}</button>
        </div>
      </div>
      <p className="muted small">{t('lb.help')}</p>
      {error && <div className="notice error">{error}</div>}
      {res && (
        <p className="small">
          {t('lb.now', { m: n1(res.minute) })} · {res.clock === 'ouro' ? t('lb.clockGold', { pace: Math.round(res.pace) }) : t('lb.clockPace')}
          {res.rival && <> · {t('lb.rival', { rival: res.rival })} {res.duelNow != null && t('lb.duelNow', { v: n1(res.duelNow) })}</>}
          {res.rivalNext && <> · {t('lb.rivalNext', { rival: res.rival, item: res.rivalNext })}</>}
          {res.seconds > 0.5 && ` · ${n1(res.seconds)} s`}
        </p>
      )}
      <ol className="lb-list">
        {builds.map((b, k) => {
          const next = b.steps[0];
          return (
            <li key={k} className="lb-build">
              <div className="lb-steps">
                {b.steps.map((s, i) => (
                  <span key={i} className={`lb-step${i === 0 ? ' next' : ''}`} title={`${s.name} · ${pct(s.probability)}${s.duel != null ? ` · ${t('lb.duel')} ${n1(s.duel)}` : ''}`}>
                    <ItemIcon id={s.itemId} size={i === 0 ? 30 : 24} />
                    <small>{Math.round(s.minute)}'</small>
                  </span>
                ))}
                <span className="lb-prob">{pct(b.probability)}</span>
              </div>
              {next && (
                <small className="lb-why">
                  {t('lb.next', { item: next.name, m: n1(next.minute) })}
                  {b.duelChange != null && <> · <span className={tone(b.duelChange)}>{t('lb.duelChange', { v: signed(b.duelChange) })}</span></>}
                  {b.effectChange != null && <> · <span className={tone(b.effectChange)}>{t('lb.effectChange', { v: signed(b.effectChange) })}</span></>}
                  {b.turnsAt && res?.rival && <> · <strong>{t(`lb.turns.${b.turnsTo}`, { champ: p.champion, rival: res.rival, n: b.turnsAt })}</strong></>}
                </small>
              )}
            </li>
          );
        })}
      </ol>
      {res?.answers?.length > 0 && (
        <div className="lb-answers">
          <h5>{t('lb.answers')}</h5>
          <ul>
            {res.answers.map((a) => (
              <li key={a.index}>
                <ChampionAvatar name={a.champion} size={20} /> {a.champion}: <ItemIcon id={a.itemId} size={20} /> <strong>{a.name}</strong>
                {' '}<small className="muted">{t('lb.answerWhy', { rival: a.rival, next: a.rivalNext ?? '—', v: n1(a.duel), m: Math.round(a.minute) })}</small>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
