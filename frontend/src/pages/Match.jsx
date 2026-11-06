import { useEffect, useMemo, useRef, useState } from 'react';
import { useTask } from '../tasks.js';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { mmss, n0 } from '../format.js';
import { RiftGround, TEAM } from './Arena.jsx';

const SEATS = 5;
const STORE = 'ornn-planner-match';

const loadSetup = () => {
  try {
    return JSON.parse(localStorage.getItem(STORE)) ?? null;
  } catch {
    return null;
  }
};

/** Whole match: the two sides, the run, and its replay from the second-by-second summary. */
export default function Match() {
  const saved = loadSetup();
  const [builds, setBuilds] = useState([]);
  const [meta, setMeta] = useState(null);
  const [picks, setPicks] = useState(saved?.picks ?? Array(SEATS * 2).fill(''));
  const [limit, setLimit] = useState(saved?.limit ?? 20);
  const [seed, setSeed] = useState(saved?.seed ?? 1);
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  useTask('match', !!busy, { label: t('tasks.match'), path: '/simulacoes/partida' });
  const [error, setError] = useState(null);
  const [second, setSecond] = useState(0);
  const [playing, setPlaying] = useState(false);
  const timer = useRef(null);

  useEffect(() => {
    api.get('/api/builds').then(setBuilds).catch((e) => setError(e.message));
    api.get('/api/match/meta').then(setMeta).catch((e) => setError(e.message));
  }, []);
  useEffect(() => {
    try {
      localStorage.setItem(STORE, JSON.stringify({ picks, limit, seed }));
    } catch {
      /* session only */
    }
  }, [picks, limit, seed]);
  useEffect(() => {
    if (!playing || !result) return undefined;
    timer.current = setInterval(() => setSecond((s) => {
      if (s + 1 >= result.timeline.length) {
        setPlaying(false);
        return s;
      }
      return s + 1;
    }), 100);
    return () => clearInterval(timer.current);
  }, [playing, result]);

  const usable = useMemo(() => builds.filter((b) => !meta || meta.champions.includes(b.unitName ?? b.champion)), [builds, meta]);
  const options = usable.length ? usable : builds;

  const run = async () => {
    setBusy(true);
    setError(null);
    setPlaying(false);
    try {
      const slots = picks.map((id, i) => (id ? { buildId: Number(id), team: i < SEATS ? 0 : 1, brain: 'tactical' } : null)).filter(Boolean);
      const r = await api.post('/api/match/run', { slots, limit, seed });
      setResult(r);
      setSecond(0);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const openFight = (f) => {
    try {
      localStorage.setItem('ornn-planner-arena', JSON.stringify(f.scenario));
      localStorage.setItem('ornn-planner-arena-tab', 'scenario');
    } catch {
      /* session only */
    }
    window.dispatchEvent(new CustomEvent('ornn-arena-load', { detail: f.scenario }));
    window.dispatchEvent(new CustomEvent('ornn-open-tab', { detail: 'arena' }));
  };

  const now = result?.timeline[Math.min(second, result.timeline.length - 1)];
  const kills = (team) => result?.players.filter((p) => p.team === team).reduce((s, p) => s + p.kills, 0) ?? 0;

  return (
    <div className="page">
      <section className="panel">
        <h2>{t('match.title')}</h2>
        <p className="muted" style={{ marginTop: 0 }}>{t('match.help')}</p>
        {meta && <p className="muted">{t('match.only', { list: meta.champions.join(', ') })}</p>}
        <div className="arena-sides">
          {[0, 1].map((team) => (
            <div key={team} className={`arena-side team-${team}`}>
              <h4 style={{ color: TEAM[team] }}>{t(`report.team${team}`)}</h4>
              {Array.from({ length: SEATS }, (_, k) => {
                const i = team * SEATS + k;
                return (
                  <select key={i} value={picks[i]} aria-label={t('match.seat', { n: k + 1 })}
                    onChange={(e) => setPicks(picks.map((v, x) => (x === i ? e.target.value : v)))}>
                    <option value="">—</option>
                    {options.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
                  </select>
                );
              })}
            </div>
          ))}
        </div>
        <div className="row">
          <label className="inline">{t('match.limit')}<input type="number" min={1} max={40} value={limit} style={{ width: 64 }} onChange={(e) => setLimit(Number(e.target.value) || 20)} /></label>
          <label className="inline">{t('match.seed')}<input type="number" value={seed} style={{ width: 80 }} onChange={(e) => setSeed(Number(e.target.value) || 1)} /></label>
          <button className="primary" disabled={busy} onClick={run}>{busy ? t('compare.calculating') : t('match.run')}</button>
        </div>
        {error && <p className="error">{error}</p>}
        {result?.left?.map((l) => <p key={l} className="warn">{l}</p>)}
      </section>
      {result && now && (
        <>
          <section className="panel">
            <h3>{result.winner < 0 ? t('match.limitReached', { t: mmss(result.seconds / 60) }) : t('match.winner', { side: t(`report.team${result.winner}`), t: mmss(result.seconds / 60) })}</h3>
            <p className="muted">{t('match.cost', { s: n0(result.runSeconds), fights: n0(result.fightSeconds), n: result.fights.length })}</p>
            <div className="row">
              <button onClick={() => setPlaying((p) => !p)}>{playing ? t('match.pause') : t('match.play')}</button>
              <input type="range" min={0} max={result.timeline.length - 1} value={second} style={{ flex: 1 }} aria-label={t('match.time')}
                onChange={(e) => { setPlaying(false); setSecond(Number(e.target.value)); }} />
              <strong>{mmss(now.t / 60)}</strong>
              <span><span style={{ color: TEAM[0] }}>{kills(0)}</span> × <span style={{ color: TEAM[1] }}>{kills(1)}</span></span>
            </div>
            <svg viewBox={`0 ${-result.world.size} ${result.world.size} ${result.world.size}`} width="100%" style={{ maxHeight: 560 }} role="img" aria-label={t('match.map')}>
              <g transform="scale(1,-1)">
                <RiftGround world={result.world} />
                {result.towers.map((tw, i) => (now.towers[i] > 0
                  ? <rect key={i} x={tw[1] - 90} y={tw[2] - 90} width={180} height={180} fill={TEAM[tw[0]]} stroke="#0b1621" strokeWidth={12}><title>{n0(now.towers[i])}</title></rect>
                  : null))}
                {now.waves.map((w, i) => <circle key={i} cx={w[0]} cy={w[1]} r={70} fill={TEAM[w[2]]} opacity={0.6} />)}
                {now.xy.map((p, i) => (now.dead[i] ? null : (
                  <g key={i}>
                    <circle cx={p[0]} cy={p[1]} r={130} fill={TEAM[result.players[i].team]} stroke="#0b1621" strokeWidth={14} />
                    <g transform={`translate(${p[0]},${p[1]}) scale(1,-1)`}>
                      <text textAnchor="middle" y={55} fontSize={160} fill="#fff" fontWeight="700">{result.players[i].champion[0]}</text>
                    </g>
                  </g>
                )))}
              </g>
            </svg>
          </section>
          <section className="panel table-scroll">
            <table className="table">
              <thead><tr><th>{t('match.player')}</th><th>{t('match.role')}</th><th>{t('match.level')}</th><th>K/D</th><th>{t('match.gold')}</th><th>{t('match.goldRate')}</th><th>{t('match.xpRate')}</th><th>{t('match.farm')}</th></tr></thead>
              <tbody>
                {result.players.map((p, i) => (
                  <tr key={i}>
                    <td style={{ color: TEAM[p.team] }}>{p.champion} <small className="muted">{p.build}</small></td>
                    <td>{t(`match.role.${p.role}`)}</td>
                    <td>{now.level[i]}</td>
                    <td>{p.kills}/{p.deaths}</td>
                    <td>{n0(now.gold[i])}</td>
                    <td>{n0(p.goldPerMin)} <small className="muted">{t('match.planned', { n: n0(p.plannedGoldPerMin) })}</small></td>
                    <td>{n0(p.xpPerMin)} <small className="muted">{t('match.planned', { n: n0(p.plannedXpPerMin) })}</small></td>
                    <td>{p.minions} + {p.monsters}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
          <section className="panel">
            <h3>{t('match.fights')}</h3>
            <ul className="plain">
              {result.fights.map((f, i) => (
                <li key={i}>
                  <button className="link" onClick={() => setSecond(Math.round(f.t))}>{mmss(f.t / 60)}</button> {f.text}{' '}
                  <button className="link" onClick={() => openFight(f)}>{t('match.openFight')}</button>
                </li>
              ))}
            </ul>
            <h3>{t('match.events')}</h3>
            <ul className="plain">
              {result.events.map((e, i) => <li key={i}><small className="muted">{mmss(e.t / 60)}</small> {e.text}</li>)}
            </ul>
          </section>
        </>
      )}
    </div>
  );
}
