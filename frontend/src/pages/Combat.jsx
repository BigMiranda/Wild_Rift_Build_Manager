import { Fragment, useEffect, useMemo, useState } from 'react';
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../api.js';
import { statAbbr, statLabel, t } from '../i18n.js';
import { mmss, n0, n1, statValue } from '../format.js';
import ItemIcon from '../components/ItemIcon.jsx';
import MinuteInput from '../components/MinuteInput.jsx';
import { ChampionAvatar } from '../components/ChampionPicker.jsx';
import { StatIcon, statColor } from '../components/StatIcon.jsx';

/** Actions a champion uses, in its order: abilities and the damage active of an item. */
const ACTIONS = ['R', '1', '2', '3', 'ATIVO'];
const MODES = { '1x1': [1, 1], '5x5': [5, 5] };
const MAX_TEAM = 6;
const TEAM_COLORS = [['#4fa8ff', '#6fc3ff', '#3d7fd6', '#8fb8ff', '#2f6bb8', '#a8d4ff'], ['#f06a5f', '#ff9a7a', '#d64a3d', '#ffb3a3', '#b83a2f', '#ff8f6b']];
const STORE = 'ornn-planner-combat';

const newSlot = (buildId = null) => ({ buildId, minute: null, order: [...ACTIONS], target: 'first' });

function loadConfig() {
  try {
    const c = JSON.parse(localStorage.getItem(STORE));
    if (c && Array.isArray(c.teams) && c.teams.length === 2) return c;
  } catch {
    /* no saved setup */
  }
  return null;
}

const signed = (v, digits = 0) => (v == null ? '—' : `${v > 0 ? '+' : ''}${digits ? n1(v) : n0(v)}`);

/**
 * Matchup simulator: two sides of saved builds (1x1, 5x5 or any size up to 5, 6 with clones), each at a game minute,
 * with its order of actions and its target. Shows the fight (health over time, who fell when), what each champion
 * did and, per item, what its effects did, what the enemy's items cancelled of it and how the fight goes without it.
 */
export default function Combat() {
  const saved = useMemo(loadConfig, []);
  const [builds, setBuilds] = useState([]);
  const [mode, setMode] = useState(saved?.mode ?? '1x1');
  const [teams, setTeams] = useState(saved?.teams ?? [[newSlot()], [newSlot()]]);
  const [duration, setDuration] = useState(saved?.duration ?? 30);
  const [counterfactual, setCounterfactual] = useState(saved?.counterfactual ?? true);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.get('/api/builds').then(setBuilds).catch((e) => setError(e.message));
  }, []);
  useEffect(() => {
    try {
      localStorage.setItem(STORE, JSON.stringify({ mode, teams, duration, counterfactual }));
    } catch {
      /* storage unavailable: the setup lives for this session only */
    }
  }, [mode, teams, duration, counterfactual]);

  const resize = (sizes) => setTeams((ts) => ts.map((team, k) => {
    const next = team.slice(0, sizes[k]);
    while (next.length < sizes[k]) next.push(newSlot());
    return next;
  }));
  const pickMode = (m) => {
    setMode(m);
    if (MODES[m]) resize(MODES[m]);
  };
  const setSlot = (team, idx, patch) => setTeams((ts) => ts.map((tm, k) => (k !== team ? tm
    : tm.map((s, i) => (i === idx ? { ...s, ...patch } : s)))));

  const ready = teams.every((tm) => tm.length > 0 && tm.every((s) => s.buildId != null));
  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      const body = {
        teamA: teams[0], teamB: teams[1], duration: Number(duration) || 30, counterfactual,
      };
      setResult(await api.post('/api/combat', body));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="main combat-page">
      <section className="panel">
        <h3>{t('combat.title')}</h3>
        <p className="muted" style={{ marginTop: 0 }}>{t('combat.help')}</p>
        <div className="row combat-setup">
          <div className="chips" role="group" aria-label={t('combat.mode')}>
            {['1x1', '5x5', 'custom'].map((m) => (
              <button key={m} className={mode === m ? 'active' : ''} aria-pressed={mode === m} onClick={() => pickMode(m)}>
                {t(`combat.mode.${m}`)}
              </button>
            ))}
          </div>
          {mode === 'custom' && [0, 1].map((k) => (
            <label key={k} className="field narrow">{t(`combat.team${k}`)}
              <input type="number" min={1} max={MAX_TEAM} value={teams[k].length}
                onChange={(e) => resize(teams.map((tm, j) => (j === k ? Math.max(1, Math.min(MAX_TEAM, Number(e.target.value) || 1)) : tm.length)))} />
            </label>
          ))}
          {mode === 'custom' && <small className="muted">{t('combat.sixNote')}</small>}
          <label className="field narrow">{t('combat.duration')}
            <input type="number" min={5} max={120} value={duration} onChange={(e) => setDuration(e.target.value)} />
          </label>
          <label className="check" title={t('combat.counterfactualTitle')}>
            <input type="checkbox" checked={counterfactual} onChange={(e) => setCounterfactual(e.target.checked)} />
            {t('combat.counterfactual')}
          </label>
          <button className="primary" onClick={run} disabled={!ready || busy}>{busy ? t('combat.running') : t('combat.run')}</button>
        </div>
        <div className="combat-teams">
          {[0, 1].map((k) => (
            <div key={k} className={`combat-team team-${k}`}>
              <h4>{t(`combat.team${k}`)}</h4>
              {teams[k].map((slot, i) => (
                <SlotEditor key={i} slot={slot} builds={builds} enemies={teams[1 - k].length}
                  color={TEAM_COLORS[k][i % 6]} onChange={(patch) => setSlot(k, i, patch)} />
              ))}
            </div>
          ))}
        </div>
        {error && <p className="error">{error}</p>}
      </section>
      {result && <CombatResult data={result} />}
    </div>
  );
}

function SlotEditor({ slot, builds, enemies, color, onChange }) {
  const build = builds.find((b) => b.id === slot.buildId);
  const move = (i, d) => {
    const order = [...slot.order];
    const j = i + d;
    if (j < 0 || j >= order.length) return;
    [order[i], order[j]] = [order[j], order[i]];
    onChange({ order });
  };
  const toggle = (a) => onChange({ order: slot.order.includes(a) ? slot.order.filter((x) => x !== a) : [...slot.order, a] });
  return (
    <div className="combat-slot" style={{ borderLeftColor: color }}>
      <div className="row">
        <ChampionAvatar name={build?.unitCode ?? '?'} size={28} />
        <select value={slot.buildId ?? ''} onChange={(e) => onChange({ buildId: e.target.value ? Number(e.target.value) : null })}
          aria-label={t('combat.build')}>
          <option value="">{t('combat.pickBuild')}</option>
          {builds.map((b) => <option key={b.id} value={b.id}>{b.name} · {b.unitCode}</option>)}
        </select>
        <label className="inline" title={t('combat.minuteTitle')}>
          {t('combat.minute')}
          <MinuteInput value={slot.minute} placeholder={build?.matchEnd != null ? mmss(build.matchEnd) : t('combat.minuteAuto')}
            onChange={(minute) => onChange({ minute })} />
        </label>
        <label className="inline">
          {t('combat.target')}
          <select value={slot.target} onChange={(e) => onChange({ target: e.target.value })}>
            <option value="first">{t('combat.target.first')}</option>
            <option value="lowest">{t('combat.target.lowest')}</option>
            {Array.from({ length: enemies }, (_, i) => <option key={i} value={String(i)}>{t('combat.target.enemy', { n: i + 1 })}</option>)}
          </select>
        </label>
      </div>
      <div className="combat-order" title={t('combat.orderTitle')}>
        <small className="muted">{t('combat.order')}</small>
        {slot.order.map((a, i) => (
          <span key={a} className="order-chip">
            <button className="icon" onClick={() => move(i, -1)} disabled={i === 0} aria-label={t('combat.earlier', { a })}>‹</button>
            <strong>{a === 'ATIVO' ? t('combat.active') : a}</strong>
            <button className="icon" onClick={() => move(i, 1)} disabled={i === slot.order.length - 1} aria-label={t('combat.later', { a })}>›</button>
            <button className="icon danger" onClick={() => toggle(a)} aria-label={t('combat.drop', { a })}>✕</button>
          </span>
        ))}
        {ACTIONS.filter((a) => !slot.order.includes(a)).map((a) => (
          <button key={a} className="opt" onClick={() => toggle(a)}>+ {a === 'ATIVO' ? t('combat.active') : a}</button>
        ))}
      </div>
    </div>
  );
}

function CombatResult({ data }) {
  const { result, fighters } = data;
  const colors = fighters.map((f) => TEAM_COLORS[f.team][fighters.filter((x) => x.team === f.team).indexOf(f) % 6]);
  const series = result.series.map((row) => {
    const p = { t: row[0] };
    fighters.forEach((f, i) => { p[`f${i}`] = row[i + 1]; });
    return p;
  });
  const fr = result.fighters;
  const outcome = result.winner < 0 ? t('combat.draw', { s: n1(result.duration) })
    : t('combat.win', { team: t(`combat.team${result.winner}`), s: n1(result.duration) });
  return (
    <>
      <section className="panel">
        <div className="spread">
          <h3>{outcome}</h3>
          <small className="muted">{t('combat.healthLeft', { a: n0(data.teamHealth), b: n0(data.enemyHealth) })}</small>
        </div>
        <ResponsiveContainer width="100%" height={260}>
          <LineChart data={series} margin={{ top: 8, right: 16, bottom: 4, left: 8 }}>
            <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" />
            <XAxis dataKey="t" type="number" domain={[0, 'dataMax']} tickFormatter={(v) => `${n0(v)}s`} stroke="var(--muted)" />
            <YAxis stroke="var(--muted)" tickFormatter={n0} width={56} />
            <Tooltip formatter={(v, name) => [n0(v), name]} labelFormatter={(v) => `${n1(v)}s`}
              contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border-strong)' }} />
            <Legend />
            {fighters.map((f, i) => (
              <Line key={i} dataKey={`f${i}`} name={`${f.champion} (${f.build})`} stroke={colors[i]} dot={false} strokeWidth={2} isAnimationActive={false} />
            ))}
          </LineChart>
        </ResponsiveContainer>
        <small className="muted">{t('combat.chartNote')}</small>
      </section>

      <section className="panel">
        <h3>{t('combat.fighters')}</h3>
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th className="l">{t('combat.champion')}</th><th>{t('combat.lvMin')}</th><th>{statAbbr('Max Health')}</th>
                <th>{t('combat.dealt')}</th><th>{t('combat.taken')}</th><th>{t('combat.mitigated')}</th>
                <th>{t('combat.shieldAbs')}</th><th>{t('combat.healed')}</th><th>{t('combat.healLost')}</th><th>{t('combat.alive')}</th>
              </tr>
            </thead>
            <tbody>
              {fighters.map((f, i) => (
                <tr key={i}>
                  <td className="l"><span className="swatch" style={{ background: colors[i] }} />{f.champion} <small className="muted">{f.build}</small></td>
                  <td>{f.level} · {mmss(f.minute)}</td>
                  <td>{n0(fr[i].health)} / {n0(fr[i].maxHealth)}</td>
                  <td>{n0(fr[i].dealt)}</td>
                  <td>{n0(fr[i].taken)}</td>
                  <td>{n0(fr[i].mitigated)}</td>
                  <td>{n0(fr[i].shieldAbsorbed)}</td>
                  <td>{n0(fr[i].healed)}</td>
                  <td className={fr[i].healLost > 0 ? 'warn' : ''}>{n0(fr[i].healLost)}</td>
                  <td>{fr[i].deathTime != null ? `✝ ${n1(fr[i].deathTime)}s` : `${n1(fr[i].alive)}s`}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <details className="combat-details">
          <summary>{t('combat.detailsTitle')}</summary>
          <div className="combat-fighter-grid">
            {fighters.map((f, i) => (
              <div key={i} className="combat-fighter" style={{ borderTopColor: colors[i] }}>
                <strong>{f.champion}</strong> <small className="muted">{f.build} · {t('combat.as', { v: n1(f.attackSpeed) })}</small>
                <div className="stat-inline">
                  {Object.entries(f.stats).map(([k, v]) => (
                    <span key={k} style={{ color: statColor(k) }} title={statLabel(k)}><StatIcon stat={k} />{statValue(k, v)}</span>
                  ))}
                </div>
                <ul>
                  {f.abilities.map((a) => (
                    <li key={a.slot} title={a.source ?? ''}>
                      <strong>{a.slot}</strong> {a.name} · {t('combat.rank', { n: a.rank })}
                      {a.damage != null ? ` · ${n0(a.damage)} ${t(`combat.type.${a.type}`)}` : a.rank > 0 ? ` · ${t('combat.noDamage')}` : ''}
                      {a.cooldown != null ? ` · ${n1(a.cooldown)}s` : ''}
                    </li>
                  ))}
                </ul>
                {Object.keys(fr[i].damageBySource).length > 0 && (
                  <div className="combat-sources">
                    {Object.entries(fr[i].damageBySource).sort((x, y) => y[1] - x[1]).map(([k, v]) => (
                      <span key={k}>{k}: <strong>{n0(v)}</strong></span>
                    ))}
                  </div>
                )}
                {fr[i].notes.length > 0 && <small className="muted">{fr[i].notes.join(' · ')}</small>}
              </div>
            ))}
          </div>
        </details>
      </section>

      <ItemReport data={data} colors={colors} />

      {result.events.length > 0 && (
        <section className="panel">
          <details>
            <summary><h3 style={{ display: 'inline' }}>{t('combat.events')}</h3></summary>
            <ul className="combat-events">{result.events.map((e, i) => <li key={i}>{e}</li>)}</ul>
          </details>
        </section>
      )}
    </>
  );
}

/** Per item: what its effects did in the fight, what the enemy cancelled of it, and the fight without it. */
function ItemReport({ data, colors }) {
  const { fighters, items, result } = data;
  const fr = result.fighters;
  const outcome = (w) => (w < 0 ? t('combat.drawShort') : t('combat.winShort', { team: t(`combat.team${w}`) }));
  const v = (l, k) => (l ? l[k] : 0);
  return (
    <section className="panel">
      <h3>{t('combat.items')}</h3>
      <p className="muted" style={{ marginTop: 0 }}>{t('combat.itemsHelp')}</p>
      <div className="table-wrap">
        <table className="data combat-items">
          <thead>
            <tr>
              <th className="l">{t('combat.item')}</th>
              <th title={t('combat.col.damageTitle')}>{t('combat.col.damage')}</th>
              <th title={t('combat.col.extraTitle')}>{t('combat.col.extra')}</th>
              <th title={t('combat.col.preventedTitle')}>{t('combat.col.prevented')}</th>
              <th title={t('combat.col.cancelTitle')}>{t('combat.col.cancel')}</th>
              <th title={t('combat.col.ownTitle')}>{t('combat.col.own')}</th>
              <th title={t('combat.col.lostTitle')}>{t('combat.col.lost')}</th>
              {data.items.some((x) => x.winnerWithout != null) && (
                <>
                  <th title={t('combat.col.withoutTitle')}>{t('combat.col.without')}</th>
                  <th title={t('combat.col.dDealtTitle')}>{t('combat.col.dDealt')}</th>
                  <th title={t('combat.col.dAliveTitle')}>{t('combat.col.dAlive')}</th>
                  <th title={t('combat.col.dTeamTitle')}>{t('combat.col.dTeam')}</th>
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {fighters.map((f, fi) => {
              const rows = items.filter((x) => x.fighter === fi);
              if (!rows.length) return null;
              const team = f.team;
              const own = team === 0 ? data.teamHealth : data.enemyHealth;
              return (
                <Fragment key={fi}>
                  <tr className="report-item"><td className="l" colSpan={11}><span className="swatch" style={{ background: colors[fi] }} />{f.champion} <small className="muted">{f.build}</small></td></tr>
                  {rows.map((x) => {
                    const l = x.ledger;
                    const extra = v(l, 'extraShred') + v(l, 'extraAmp');
                    const cancel = v(l, 'shieldCut') + v(l, 'healCut');
                    const ownFx = v(l, 'shieldAbsorbed') + v(l, 'healed');
                    const lost = v(l, 'shieldLost') + v(l, 'healLost');
                    const alive = fr[fi].alive;
                    return (
                      <tr key={x.itemId}>
                        <td className="l"><span className="with-icon"><ItemIcon id={x.itemId} size={22} />{x.item}</span></td>
                        <td>{l?.damage ? n0(l.damage) : '—'}</td>
                        <td title={l ? t('combat.extraSplit', { shred: n0(l.extraShred), amp: n0(l.extraAmp) }) : ''}>{extra ? `+${n0(extra)}` : '—'}</td>
                        <td>{l?.prevented ? n0(l.prevented) : '—'}</td>
                        <td title={l ? t('combat.cancelSplit', { shield: n0(l.shieldCut), heal: n0(l.healCut) }) : ''}>{cancel ? n0(cancel) : '—'}</td>
                        <td title={l ? t('combat.ownSplit', { shield: n0(l.shieldAbsorbed), heal: n0(l.healed) }) : ''}>{ownFx ? n0(ownFx) : '—'}</td>
                        <td className={lost > 0 ? 'warn' : ''} title={l ? t('combat.lostSplit', { shield: n0(l.shieldLost), heal: n0(l.healLost) }) : ''}>{lost ? n0(lost) : '—'}</td>
                        {x.winnerWithout != null && (
                          <>
                            <td className={x.winnerWithout !== result.winner ? 'warn' : ''}>{outcome(x.winnerWithout)}</td>
                            <td>{signed(fr[fi].dealt - x.dealtWithout)}</td>
                            <td>{signed(alive - x.aliveWithout, 1)}s</td>
                            <td>{signed(own - x.teamHealthWithout)}</td>
                          </>
                        )}
                      </tr>
                    );
                  })}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
      {data.others.length > 0 && (
        <details className="combat-details">
          <summary>{t('combat.others')}</summary>
          <div className="combat-sources">
            {data.others.filter((l) => l.damage + l.shieldAbsorbed + l.healed + l.healCut + l.shieldCut > 0.5).map((l, i) => (
              <span key={i}>
                <span className="swatch" style={{ background: colors[l.fighter] }} />
                {l.source}: {[
                  l.damage > 0.5 && t('combat.oDamage', { v: n0(l.damage) }),
                  l.shieldAbsorbed > 0.5 && t('combat.oShield', { v: n0(l.shieldAbsorbed) }),
                  l.healed > 0.5 && t('combat.oHeal', { v: n0(l.healed) }),
                  l.healCut > 0.5 && t('combat.oHealCut', { v: n0(l.healCut) }),
                ].filter(Boolean).join(' · ')}
              </span>
            ))}
          </div>
        </details>
      )}
    </section>
  );
}
