import { useEffect, useMemo, useRef, useState } from 'react';
import { BuildLink } from '../components/BuildPreview.jsx';
import { useTask } from '../tasks.js';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { mmss, n0 } from '../format.js';
import { RiftGround, RiftLegend, TEAM } from './Arena.jsx';

const SEATS = 5;
const STORE = 'ornn-planner-match';

/** A trained macro of this schema, action count, rules and patch (the others still list, with a warning). */
const current = (m, meta) => !meta || (m.obsSchema === meta.macroSchema && m.actions === meta.macroActions
  && m.rules === meta.macroRules && (!meta.patch || m.gamePatch === meta.patch));

const signed = (x) => `${x >= 0 ? '+' : ''}${x.toFixed(3).replace('.', ',')}`;

/**
 * "Treinar a macro" (proposta-macro-treinada.md): the picked builds of each side as one composition, the training in the
 * background with its progress, and the trained versions with how they did against the script.
 */
function MacroPanel({ picks, builds, meta, macros, reload }) {
  const [generations, setGenerations] = useState(30);
  const [population, setPopulation] = useState(24);
  const [episodes, setEpisodes] = useState(4);
  const [parent, setParent] = useState('');
  const [againstPrevious, setAgainstPrevious] = useState(false);
  const [job, setJob] = useState(null);
  const [error, setError] = useState(null);
  const running = !!job && (job.status === 'rodando' || job.status === 'validando');
  const last = job?.progress?.[job.progress.length - 1];
  useTask('macro', running, { label: t('tasks.macro'), done: last ? last[0] : 0, total: job?.generations, path: '/simulacoes/partida' });

  const drafts = useMemo(() => {
    const out = [];
    [0, 1].forEach((team) => {
      const ids = picks.slice(team * SEATS, team * SEATS + SEATS).filter(Boolean).map(Number);
      if (ids.length && !out.some((d) => d.join() === ids.join())) out.push(ids);
    });
    return out;
  }, [picks]);
  const name = (id) => builds.find((b) => b.id === id)?.name ?? id;

  useEffect(() => {
    api.get('/api/match/macros/train').then((jobs) => {
      const live = jobs.find((j) => j.status === 'rodando' || j.status === 'validando');
      if (live) setJob(live);
    }).catch(() => {});
  }, []);
  useEffect(() => {
    if (!running) return undefined;
    const id = setInterval(() => {
      api.get(`/api/match/macros/train/${job.id}`).then((j) => {
        setJob(j);
        if (j.status !== 'rodando' && j.status !== 'validando') reload();
      }).catch((e) => setError(e.message));
    }, 2000);
    return () => clearInterval(id);
  }, [running, job?.id]);

  const start = async () => {
    setError(null);
    try {
      setJob(await api.post('/api/match/macros/train', {
        drafts, generations, population, episodes, parentId: parent ? Number(parent) : null, againstPrevious,
      }));
    } catch (e) {
      setError(e.message);
    }
  };
  const stop = async () => {
    try {
      setJob(await api.post(`/api/match/macros/train/${job.id}/cancel`));
    } catch (e) {
      setError(e.message);
    }
  };
  const remove = async (id) => {
    try {
      await api.del(`/api/match/macros/${id}`);
      reload();
    } catch (e) {
      setError(e.message);
    }
  };
  const matches = generations * population * episodes * 2;

  return (
    <section className="panel">
      <h3>{t('macro.title')}</h3>
      <p className="muted" style={{ marginTop: 0 }}>{t('macro.help')}</p>
      <p>{drafts.length ? t('macro.drafts', { list: drafts.map((d) => d.map(name).join(', ')).join(' | ') }) : t('macro.none')}</p>
      <div className="row">
        <label className="inline">{t('macro.generations')}<input type="number" min={1} max={200} value={generations} style={{ width: 64 }} onChange={(e) => setGenerations(Number(e.target.value) || 1)} /></label>
        <label className="inline">{t('macro.population')}<input type="number" min={2} max={128} value={population} style={{ width: 64 }} onChange={(e) => setPopulation(Number(e.target.value) || 2)} /></label>
        <label className="inline">{t('macro.episodes')}<input type="number" min={1} max={32} value={episodes} style={{ width: 64 }} onChange={(e) => setEpisodes(Number(e.target.value) || 1)} /></label>
        <label className="inline">{t('macro.from')}
          <select value={parent} onChange={(e) => setParent(e.target.value)}>
            <option value="">{t('macro.fromZero')}</option>
            {macros.filter((m) => current(m, meta)).map((m) => <option key={m.id} value={m.id}>v{m.version}</option>)}
          </select>
        </label>
        <label className="inline"><input type="checkbox" checked={againstPrevious} onChange={(e) => setAgainstPrevious(e.target.checked)} />{t('macro.againstPrevious')}</label>
      </div>
      <div className="row">
        <span className="muted">{t('macro.estimate', { n: n0(matches), min: n0(matches / 10 / 60) })}</span>
        {running
          ? <button onClick={stop}>{t('macro.stop')}</button>
          : <button className="primary" disabled={!drafts.length} onClick={start}>{t('macro.start')}</button>}
      </div>
      {job && (
        <p className={job.status === 'erro' ? 'error' : 'muted'}>
          {job.status === 'validando' ? t('macro.validating') : last && job.status === 'rodando'
            ? t('macro.generation', { g: last[0], n: job.generations, mean: signed(last[1]), best: signed(last[2]), s: n0(last[3]) })
            : job.message}
        </p>
      )}
      {error && <p className="error">{error}</p>}
      <h4>{t('macro.versions')}</h4>
      {macros.length === 0 ? <p className="muted">{t('macro.empty')}</p> : (
        <div className="table-scroll">
          <table className="table">
            <thead><tr><th>{t('macro.col.version')}</th><th>{t('macro.col.winrate')}</th><th>{t('macro.col.gold')}</th><th>{t('macro.col.deviations')}</th><th>{t('macro.col.training')}</th><th>{t('macro.col.when')}</th><th /></tr></thead>
            <tbody>
              {macros.map((m) => (
                <tr key={m.id}>
                  <td>v{m.version} <small className="muted">{m.accepted ? t('macro.accepted') : t('macro.rejected')}</small>
                    {!current(m, meta) && <div className="warn"><small>{t('match.macro.old')}</small></div>}</td>
                  <td>{Math.round(m.winrate * 100)}% <small className="muted">({m.games})</small></td>
                  <td>{n0(m.goldPerMin)} <small className="muted">({n0(m.opponentGoldPerMin)})</small></td>
                  <td>{m.deviations.toFixed(1).replace('.', ',')}</td>
                  <td><small>{m.generations} × {m.population} × {m.episodes}{m.parentId ? ` · ← v${macros.find((x) => x.id === m.parentId)?.version ?? '?'}` : ''}</small></td>
                  <td><small>{m.createdAt?.slice(0, 16).replace('T', ' ')}</small></td>
                  <td><button className="link" onClick={() => remove(m.id)}>{t('macro.delete')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

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
  const [macroPick, setMacroPick] = useState(saved?.macros ?? ['script', 'script']);
  const [macros, setMacros] = useState([]);
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
    api.get('/api/match/macros').then(setMacros).catch(() => setMacros([]));
  }, []);
  const reloadMacros = () => api.get('/api/match/macros').then(setMacros).catch(() => {});
  useEffect(() => {
    try {
      localStorage.setItem(STORE, JSON.stringify({ picks, limit, seed, macros: macroPick }));
    } catch {
      /* session only */
    }
  }, [picks, limit, seed, macroPick]);
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
      const known = (m) => (m === 'script' || macros.some((x) => `macro:${x.id}` === m && current(x, meta)) ? m : 'script');
      const r = await api.post('/api/match/run', { slots, limit, seed, macroBlue: known(macroPick[0]), macroRed: known(macroPick[1]) });
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
              <label className="inline" style={{ display: 'flex', marginTop: 6 }}>{t('match.macro')}
                <select value={macros.some((x) => `macro:${x.id}` === macroPick[team] && current(x, meta)) ? macroPick[team] : 'script'} onChange={(e) => setMacroPick(macroPick.map((v, x) => (x === team ? e.target.value : v)))}>
                  <option value="script">{t('match.macro.script')}</option>
                  {macros.filter((m) => current(m, meta)).map((m) => (
                    <option key={m.id} value={`macro:${m.id}`}>{t('match.macro.version', { v: m.version, w: Math.round(m.winrate * 100) })}</option>
                  ))}
                </select>
              </label>
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
      <MacroPanel picks={picks} builds={builds} meta={meta} macros={macros} reload={reloadMacros} />
      {result && now && (
        <>
          <section className="panel">
            <h3>{result.winner < 0 ? t('match.limitReached', { t: mmss(result.seconds / 60) }) : t('match.winner', { side: t(`report.team${result.winner}`), t: mmss(result.seconds / 60) })}</h3>
            <p className="muted">{t('match.cost', { s: n0(result.runSeconds), fights: n0(result.fightSeconds), n: result.fights.length })}</p>
            {result.macros?.length === 2 && <p className="muted">{t('match.macros', { blue: result.macros[0], red: result.macros[1] })}</p>}
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
                  : <polygon key={i} points={`${tw[1] - 90},${tw[2] - 50} ${tw[1] - 20},${tw[2] + 70} ${tw[1] + 30},${tw[2] - 10} ${tw[1] + 90},${tw[2] + 40} ${tw[1] + 70},${tw[2] - 80}`}
                    fill="#3a3f45" stroke={TEAM[tw[0]]} strokeWidth={10} strokeDasharray="18 12" opacity={0.7}><title>{t('match.rubble')}</title></polygon>))}
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
            <RiftLegend />
          </section>
          <section className="panel table-scroll">
            <table className="table">
              <thead><tr><th>{t('match.player')}</th><th>{t('match.role')}</th><th>{t('match.level')}</th><th>K/D</th><th>{t('match.gold')}</th><th>{t('match.goldRate')}</th><th>{t('match.xpRate')}</th><th>{t('match.farm')}</th></tr></thead>
              <tbody>
                {result.players.map((p, i) => (
                  <tr key={i}>
                    <td style={{ color: TEAM[p.team] }}>{p.champion} <small className="muted"><BuildLink id={p.buildId} name={p.build} /></small></td>
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
