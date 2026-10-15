import { Fragment, useEffect, useMemo, useState } from 'react';
import { CartesianGrid, Line, LineChart, ReferenceDot, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../api.js';
import { statAbbr, statLabel, t } from '../i18n.js';
import { n0, n1, statValue } from '../format.js';
import ItemIcon from './ItemIcon.jsx';
import { StatIcon, statColor } from './StatIcon.jsx';
import { findRune, findSpell, runeCatalog, runeIcon, setRuneCatalog, spellIcon } from '../runes.js';

/**
 * The report of one fight of the arena (a scenario or the lab's combo against the ragdoll): result with the health
 * chart, champions with their builds, damage by source, the ledger of items and effects (with the value of each item
 * on demand), triggers and runes by source, and the AI's decisions, events and skipped orders.
 */

export const TEAM = ['#4fa8ff', '#f06a5f'];
/** Shades of each side, one per champion of the side. */
const SHADES = [['#4fa8ff', '#8fd0ff', '#2f78d0', '#b7d8ff', '#1f5fae', '#6fc3ff'],
  ['#f06a5f', '#ffab8f', '#c8443a', '#ffc9b8', '#a4342b', '#ff8f6b']];
const TYPE_COLORS = { fisico: '#f08c3c', magico: '#a07af0', verdadeiro: '#e6e9ee' };
const TYPES = ['fisico', 'magico', 'verdadeiro'];
const START_STATS = ['Max Health', 'Armor', 'Magic Resistance', 'Attack Damage', 'Ability Power', 'Armor Penetration',
  '% Armor Penetration', 'Magic Penetration', '% Magic Penetration'];
const ICON = 22;

const signed = (v, digits = 0) => {
  if (v == null || Number.isNaN(v)) return '—';
  const s = digits ? n1(v) : n0(v);
  return v > 0 ? `+${s}` : s;
};

/** The rune / spell catalog (icons) is loaded by the planner; the arena loads it when it is not there yet. */
function useRuneCatalog() {
  const [, setReady] = useState(Boolean(runeCatalog()));
  useEffect(() => {
    if (runeCatalog()) return;
    api.get('/api/runes').then((c) => { setRuneCatalog(c); setReady(true); }).catch(() => {});
  }, []);
}

/** Colors per champion: its side's color, a different shade for each one of the same side. */
function colorsOf(fighters) {
  const seen = [0, 0];
  return fighters.map((f) => SHADES[f.team][(seen[f.team]++) % SHADES[f.team].length]);
}

/** A source's icon: item, rune, spell, ability key or the attack; the name always in the title. */
export function SourceIcon({ source, fighter, size = ICON }) {
  const item = fighter?.items?.find((x) => x.name === source);
  const style = { width: size, height: size };
  if (item) return <span title={source} className="src-icon"><ItemIcon id={item.id} size={size} /></span>;
  const rune = findRune(source);
  if (rune) return <img className="src-icon rune-img on" src={runeIcon(rune)} alt="" title={source} width={size} height={size} />;
  const spell = findSpell(source);
  if (spell) return <img className="src-icon spell-img" src={spellIcon(spell)} alt="" title={source} width={size} height={size} />;
  const slot = /^([123R]) ·/.exec(source ?? '');
  if (slot) return <span className="src-icon src-badge ability" style={style} title={source}>{slot[1]}</span>;
  if (source === 'Ataque') return <span className="src-icon src-badge attack" style={style} title={source}>⚔</span>;
  return <span className="src-icon src-badge" style={style} title={source}>{(source ?? '?').slice(0, 1)}</span>;
}

/** Items in slot order, keystone (and the other runes, smaller), spells. */
function BuildIcons({ f }) {
  const [keystone, ...others] = f.runes ?? [];
  return (
    <span className="build-icons">
      <span className="build-line">
        {(f.items ?? []).map((it, k) => <span key={k} title={it.name}><ItemIcon id={it.id} size={ICON} /></span>)}
      </span>
      <span className="build-line">
        {keystone && <SourceIcon source={keystone} fighter={f} />}
        {others.map((r) => <SourceIcon key={r} source={r} fighter={f} size={16} />)}
        {(f.spells ?? []).map((s) => <SourceIcon key={s} source={s} fighter={f} />)}
      </span>
    </span>
  );
}

export default function FightReport({ result, names, onItemValues }) {
  useRuneCatalog();
  const fr = result.fighters;
  const label = (i) => names?.[i] ?? fr[i].champion;
  const colors = useMemo(() => colorsOf(fr), [fr]);
  return (
    <>
      <ResultSection result={result} label={label} colors={colors} />
      <ChampionsSection result={result} label={label} colors={colors} />
      <DamageSection result={result} label={label} colors={colors} />
      <LedgerSection result={result} label={label} colors={colors} onItemValues={onItemValues} />
      <TriggerSection result={result} label={label} colors={colors} />
      <MoreSection result={result} label={label} colors={colors} />
    </>
  );
}

function ResultSection({ result, label, colors }) {
  const fr = result.fighters;
  const side = [0, 1].map((team) => fr.filter((f) => f.team === team));
  const data = useMemo(() => (result.frames ?? []).map((fm) => {
    const row = { t: fm.t };
    fm.units.forEach((u, i) => { row[`f${i}`] = Math.max(0, u.hp) + (u.shield ?? 0); });
    return row;
  }), [result]);
  const text = result.winner < 0 ? t('report.draw', { s: n1(result.duration) })
    : t('report.win', { team: t(`report.team${result.winner}`), s: n1(result.duration) });
  return (
    <section className="panel fight-report">
      <h3>{t('report.sec.result')}</h3>
      <p><strong>{text}</strong></p>
      <div className="side-health">
        {side.map((list, team) => list.length > 0 && (
          <span key={team} style={{ color: TEAM[team] }}>
            {t(`report.team${team}`)}: {n0(list.reduce((s, f) => s + Math.max(0, f.health), 0))} / {n0(list.reduce((s, f) => s + f.maxHealth, 0))} {statAbbr('Max Health')}
          </span>
        ))}
      </div>
      {data.length > 1 && (
        <figure className="health-chart">
          <ResponsiveContainer width="100%" height={220}>
            <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
              <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" />
              <XAxis dataKey="t" type="number" domain={[0, 'dataMax']} tickFormatter={(v) => `${n0(v)}s`} stroke="var(--muted)" />
              <YAxis stroke="var(--muted)" tickFormatter={n0} width={52} />
              <Tooltip formatter={(v, name) => [n0(v), name]} labelFormatter={(v) => `${n1(v)}s`}
                contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border-strong)' }} />
              {fr.map((f, i) => (
                <Line key={i} dataKey={`f${i}`} name={`${i + 1} · ${label(i)}`} stroke={colors[i]} dot={false} strokeWidth={2} isAnimationActive={false} />
              ))}
              {fr.map((f, i) => f.deathTime != null && (
                <ReferenceDot key={`d${i}`} x={f.deathTime} y={0} r={5} fill={colors[i]} stroke="var(--surface)"
                  label={{ value: '✝', position: 'top', fill: colors[i], fontSize: 13 }} />
              ))}
            </LineChart>
          </ResponsiveContainer>
          <figcaption className="muted small">
            {fr.map((f, i) => <span key={i} className="legend-item"><span className="swatch" style={{ background: colors[i] }} />{i + 1} · {label(i)}</span>)}
            <span>{t('report.chartNote')}</span>
          </figcaption>
        </figure>
      )}
    </section>
  );
}

function ChampionsSection({ result, label, colors }) {
  const fr = result.fighters;
  return (
    <section className="panel fight-report">
      <h3>{t('report.fighters')}</h3>
      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th className="l">{t('report.champion')}</th><th className="l">{t('report.build')}</th><th>{t('report.lvMin')}</th>
              <th>{statAbbr('Max Health')}</th><th>{t('report.dealt')}</th><th>{t('report.taken')}</th><th>{t('report.mitigated')}</th>
              <th>{t('report.shieldAbs')}</th><th>{t('report.healed')}</th><th>{t('report.healLost')}</th><th>{t('report.alive')}</th>
            </tr>
          </thead>
          <tbody>
            {fr.map((f, i) => (
              <tr key={i}>
                <td className="l nowrap"><span className="swatch" style={{ background: colors[i] }} />{i + 1} · {label(i)} <small className="muted">{f.build}</small></td>
                <td className="l"><BuildIcons f={f} /></td>
                <td>{f.level} · {n1(f.minute)}</td>
                <td>{n0(f.health)} / {n0(f.maxHealth)}</td>
                <td>{n0(f.dealt)}</td><td>{n0(f.taken)}</td><td>{n0(f.mitigated)}</td><td>{n0(f.shieldAbsorbed)}</td>
                <td>{n0(f.healed)}</td><td className={f.healLost > 0.5 ? 'warn' : ''}>{n0(f.healLost)}</td>
                <td>{f.deathTime != null ? `✝ ${n1(f.deathTime)}s` : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <details className="report-details">
        <summary>{t('report.details')}</summary>
        <div className="fighter-cards">
          {fr.map((f, i) => (
            <div key={i} className="fighter-card" style={{ borderTopColor: colors[i] }}>
              <strong>{i + 1} · {label(i)}</strong> <small className="muted">{f.build}</small>
              <div className="stat-inline">
                {START_STATS.filter((s) => Math.abs(f.startStats?.[s] ?? 0) > 1e-9).map((s) => (
                  <span key={s} style={{ color: statColor(s) }} title={statLabel(s)}><StatIcon stat={s} />{statValue(s, f.startStats[s])}</span>
                ))}
              </div>
              <ul>
                {(f.abilities ?? []).map((a) => (
                  <li key={a.slot}>
                    <SourceIcon source={`${a.slot} · ${a.name}`} fighter={f} size={18} /> {a.name}
                    <small className="muted"> · {t('report.rank', { n: a.rank })}{a.cooldown != null ? ` · ${t('report.cd', { s: n1(a.cooldown) })}` : ''}</small>
                  </li>
                ))}
              </ul>
              {f.notes?.length > 0 && <small className="muted">{f.notes.join(' · ')}</small>}
            </div>
          ))}
        </div>
      </details>
    </section>
  );
}

function DamageSection({ result, label, colors }) {
  const fr = result.fighters;
  const rows = fr.map((f) => Object.entries(f.damageBySource ?? {}).filter(([, v]) => v > 0.5).sort((a, b) => b[1] - a[1]));
  const max = Math.max(1, ...rows.flat().map(([, v]) => v));
  if (!rows.some((r) => r.length)) return null;
  return (
    <section className="panel fight-report">
      <h3>{t('report.sec.damage')}</h3>
      <div className="type-legend small">
        {TYPES.map((k) => <span key={k}><span className="swatch" style={{ background: TYPE_COLORS[k] }} />{t(`report.type.${k}`)}</span>)}
      </div>
      <div className="damage-bars">
        {fr.map((f, i) => rows[i].length > 0 && (
          <div key={i} className="damage-fighter">
            <h4 style={{ color: colors[i] }}>{i + 1} · {label(i)} <small className="muted">{n0(f.dealt)}</small></h4>
            {rows[i].map(([src, v]) => {
              const byType = f.damageBySourceType?.[src] ?? {};
              const known = TYPES.reduce((s, k) => s + (byType[k] ?? 0), 0);
              return (
                <div key={src} className="damage-row" title={`${src}: ${n0(v)}`}>
                  <SourceIcon source={src} fighter={f} size={20} />
                  <span className="damage-name">{src}</span>
                  <span className="damage-track">
                    <span className="damage-bar" style={{ width: `${(100 * v) / max}%` }}>
                      {known > 0
                        ? TYPES.filter((k) => byType[k] > 0).map((k) => (
                          <span key={k} style={{ width: `${(100 * byType[k]) / known}%`, background: TYPE_COLORS[k] }}
                            title={`${t(`report.type.${k}`)}: ${n0(byType[k])}`} />
                        ))
                        : <span style={{ width: '100%', background: 'var(--muted)' }} />}
                    </span>
                  </span>
                  <span className="damage-value">{n0(v)}</span>
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </section>
  );
}

const sumOf = (l, ...ks) => (l ? ks.reduce((s, k) => s + (l[k] ?? 0), 0) : 0);

function LedgerSection({ result, label, colors, onItemValues }) {
  const fr = result.fighters;
  const [values, setValues] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  useEffect(() => { setValues(null); setError(null); }, [result]);
  const byKey = useMemo(() => {
    const m = {};
    for (const l of result.ledger ?? []) m[`${l.fighter}|${l.source}`] = l;
    return m;
  }, [result]);
  const active = (l) => sumOf(l, 'damage', 'extraShred', 'extraAmp', 'prevented', 'shieldCut', 'healCut', 'shieldAbsorbed',
    'healed', 'shieldLost', 'healLost') > 0.5;
  // per champion: its items in slot order (even when their effects did nothing: they may still be worth something),
  // then the other sources with something in the ledger
  const groups = fr.map((f, i) => {
    const items = (f.items ?? []).map((it) => ({ source: it.name, itemId: it.id, l: byKey[`${i}|${it.name}`] }));
    const names = new Set(items.map((x) => x.source));
    const others = (result.ledger ?? []).filter((l) => l.fighter === i && !names.has(l.source) && active(l))
      .map((l) => ({ source: l.source, l }));
    return [...items, ...others];
  });
  const base = useMemo(() => {
    const health = (team) => fr.filter((f) => f.team === team).reduce((s, f) => s + Math.max(0, f.health), 0);
    return { health, alive: (f) => (f.deathTime != null ? f.deathTime : result.duration) };
  }, [result]);
  const valueOf = (i, source) => values?.find((v) => v.fighter === i && v.item === source);
  const calculate = async () => {
    setBusy(true);
    setError(null);
    try {
      setValues(await onItemValues());
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };
  const outcome = (w) => (w < 0 ? t('report.drawShort') : t('report.winShort', { team: t(`report.team${w}`) }));
  const cols = values ? 11 : 7;
  return (
    <section className="panel fight-report">
      <div className="spread">
        <h3>{t('report.sec.items')}</h3>
        {onItemValues && (
          <button onClick={calculate} disabled={busy} title={t('report.valuesHelp')}>
            {busy ? t('report.valuesBusy') : t('report.values')}
          </button>
        )}
      </div>
      <p className="muted small">{t('report.itemsHelp')}</p>
      {error && <div className="notice error">{error}</div>}
      <div className="table-wrap">
        <table className="data ledger-table">
          <thead>
            <tr>
              <th className="l">{t('arena.source')}</th>
              <th title={t('report.col.damageTitle')}>{t('report.col.damage')}</th>
              <th title={t('report.col.extraTitle')}>{t('report.col.extra')}</th>
              <th title={t('report.col.preventedTitle')}>{t('report.col.prevented')}</th>
              <th title={t('report.col.cancelTitle')}>{t('report.col.cancel')}</th>
              <th title={t('report.col.ownTitle')}>{t('report.col.own')}</th>
              <th title={t('report.col.lostTitle')}>{t('report.col.lost')}</th>
              {values && (
                <>
                  <th title={t('report.col.withoutTitle')}>{t('report.col.without')}</th>
                  <th title={t('report.col.dDealtTitle')}>{t('report.col.dDealt')}</th>
                  <th title={t('report.col.dAliveTitle')}>{t('report.col.dAlive')}</th>
                  <th title={t('report.col.dTeamTitle')}>{t('report.col.dTeam')}</th>
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {fr.map((f, i) => groups[i].length > 0 && (
              <Fragment key={i}>
                <tr className="group-row"><td className="l" colSpan={cols}><span className="swatch" style={{ background: colors[i] }} />{i + 1} · {label(i)} <small className="muted">{f.build}</small></td></tr>
                {groups[i].map(({ source, l }) => {
                  const v = valueOf(i, source);
                  const extra = sumOf(l, 'extraShred', 'extraAmp');
                  const cancel = sumOf(l, 'shieldCut', 'healCut');
                  const own = sumOf(l, 'shieldAbsorbed', 'healed');
                  const lost = sumOf(l, 'shieldLost', 'healLost');
                  return (
                    <tr key={source}>
                      <td className="l"><span className="with-icon"><SourceIcon source={source} fighter={f} />{source}</span></td>
                      <td>{l?.damage > 0.5 ? n0(l.damage) : '—'}</td>
                      <td title={l ? t('report.extraSplit', { shred: n0(l.extraShred), amp: n0(l.extraAmp) }) : ''}>{extra > 0.5 ? `+${n0(extra)}` : '—'}</td>
                      <td>{l?.prevented > 0.5 ? n0(l.prevented) : '—'}</td>
                      <td title={l ? t('report.cancelSplit', { shield: n0(l.shieldCut), heal: n0(l.healCut) }) : ''}>{cancel > 0.5 ? n0(cancel) : '—'}</td>
                      <td title={l ? t('report.ownSplit', { shield: n0(l.shieldAbsorbed), heal: n0(l.healed) }) : ''}>{own > 0.5 ? n0(own) : '—'}</td>
                      <td className={lost > 0.5 ? 'warn' : ''} title={l ? t('report.lostSplit', { shield: n0(l.shieldLost), heal: n0(l.healLost) }) : ''}>{lost > 0.5 ? n0(lost) : '—'}</td>
                      {values && (v ? (
                        <>
                          <td className={v.winner !== result.winner ? 'warn' : ''}>{outcome(v.winner)}</td>
                          <td>{signed(f.dealt - v.dealt)}</td>
                          <td>{signed(base.alive(f) - v.alive, 1)}s</td>
                          <td>{signed(base.health(f.team) - v.teamHealth)}</td>
                        </>
                      ) : <td colSpan={4} className="muted">—</td>)}
                    </tr>
                  );
                })}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/** Conditional effects and procs by source: one row per rune or item, with its activations counted once each. */
function TriggerSection({ result, label, colors }) {
  const fr = result.fighters;
  const byKey = {};
  for (const l of result.ledger ?? []) byKey[`${l.fighter}|${l.source}`] = l;
  const groups = new Map();
  for (const r of result.triggers ?? []) {
    const k = `${r.fighter}|${r.source}`;
    if (!groups.has(k)) groups.set(k, { fighter: r.fighter, source: r.source, kind: r.kind, parts: [] });
    groups.get(k).parts.push(r);
  }
  // runes and items that went off by their fight effect alone (Electrocute, an item's strike), without a trigger
  fr.forEach((f, i) => Object.keys(f.events ?? {}).forEach((e) => {
    const m = /^(runa|item)_ativou:(.+)$/.exec(e);
    if (m && !groups.has(`${i}|${m[2]}`)) groups.set(`${i}|${m[2]}`, { fighter: i, source: m[2], kind: m[1], parts: [] });
  }));
  const rows = [...groups.values()].sort((a, b) => a.fighter - b.fighter);
  if (!rows.length) return null;
  const counted = (r) => r.kind === 'runa' || r.kind === 'item' || r.kind === 'loja';
  const count = (r) => {
    const f = fr[r.fighter];
    const times = f.procTimes?.[r.source];
    if (times) return times.length;
    const e = f.events?.[`${r.kind === 'runa' ? 'runa' : 'item'}_ativou:${r.source}`];
    if (e != null) return e;
    return Math.max(0, ...r.parts.map((p) => p.activations));
  };
  const idle = (p) => p.mode === 'nunca' || (p.mode === 'estatico' && !p.buildOn);
  return (
    <section className="panel fight-report">
      <details className="report-details" open>
        <summary>{t('report.sec.triggers', { n: rows.length })}</summary>
        <p className="muted small">{t('arena.trig.help')} {t('report.trig.perSource')}</p>
        <div className="table-wrap">
          <table className="data arena-triggers">
            <thead>
              <tr>
                <th className="l">{t('arena.trig.effect')}</th><th className="l">{t('arena.trig.how')}</th>
                <th title={t('report.trig.timesTitle')}>{t('arena.trig.times')}</th><th>{t('arena.trig.first')}</th>
                <th title={t('arena.trig.onTitle')}>{t('arena.trig.on')}</th><th>{t('arena.trig.gave')}</th>
                <th className="l">{t('arena.trig.stats')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const f = fr[r.fighter];
                const live = r.parts.filter((p) => !idle(p));
                const fires = r.parts.length === 0 || live.some((p) => p.mode !== 'estatico' && p.mode !== 'modelado' && p.mode !== 'nunca')
                  || counted(r) && count(r) > 0;
                const n = counted(r) ? count(r) : Math.max(0, ...r.parts.map((p) => p.activations));
                const first = [...r.parts.map((p) => p.first).filter((x) => x != null), ...(f.procTimes?.[r.source]?.slice(0, 1) ?? [])];
                const on = Math.max(0, ...r.parts.map((p) => p.activeSeconds ?? 0));
                const l = byKey[`${r.fighter}|${r.source}`];
                const shield = r.parts.reduce((s, p) => s + (p.shield ?? 0), 0);
                const healed = r.parts.reduce((s, p) => s + (p.healed ?? 0), 0);
                const gave = [
                  shield > 0.5 && t('arena.trig.shield', { v: n0(shield) }),
                  healed > 0.5 && t('arena.trig.heal', { v: n0(healed) }),
                  l && l.damage > 0.5 && t('arena.trig.damage', { v: n0(l.damage) }),
                ].filter(Boolean).join(' · ');
                const stats = r.parts.flatMap((p) => Object.entries(p.delta ?? {}))
                  .map(([s, v]) => `${v > 0 ? '+' : ''}${statValue(s, v)} ${statAbbr(s)}`).join(', ');
                const how = r.parts.length ? [...new Set(r.parts.map((p) => (p.effect !== r.source ? `${p.effect}: ` : '') + p.how))].join(' · ')
                  : t('report.trig.modeled');
                return (
                  <tr key={`${r.fighter}|${r.source}`} className={live.length === 0 && r.parts.length > 0 ? 'muted' : ''}>
                    <td className="l">
                      <span className="with-icon">
                        <span className="swatch" style={{ background: colors[r.fighter] }} />
                        <SourceIcon source={r.source} fighter={f} />{r.fighter + 1} · {r.source}
                      </span>
                      <small className="muted"> ({t(`arena.trig.kind.${r.kind}`)}) · {label(r.fighter)}</small>
                    </td>
                    <td className="l small">{how}</td>
                    <td className={fires && n === 0 ? 'warn' : ''}>{fires ? n : '—'}</td>
                    <td>{first.length ? `${n1(Math.min(...first))}s` : '—'}</td>
                    <td>{on > 0.05 ? `${n1(on)}s` : '—'}</td>
                    <td>{gave || '—'}</td>
                    <td className="l small">{stats || '—'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </details>
    </section>
  );
}

function MoreSection({ result, label, colors }) {
  const fr = result.fighters;
  return (
    <section className="panel fight-report">
      <h3>{t('report.sec.more')}</h3>
      {fr.some((f) => f.eventSummary) && (
        <details className="report-details">
          <summary>{t('arena.events.title')}</summary>
          <p className="muted small">{t('arena.events.help')}</p>
          <ul className="report-events">
            {fr.map((f, i) => f.eventSummary && (
              <li key={i}><span className="swatch" style={{ background: colors[i] }} />{i + 1} · {label(i)}: {f.eventSummary}</li>
            ))}
          </ul>
        </details>
      )}
      {result.combos?.length > 0 && (
        <details className="report-details">
          <summary>{t('arena.combos.title', { n: result.combos.length })}</summary>
          <ul className="report-events">
            {result.combos.map((c, k) => (
              <li key={k}>
                <span className="swatch" style={{ background: colors[c.fighter] ?? TEAM[0] }} />
                {c.fighter + 1} · {label(c.fighter)}: <strong>{c.combo}</strong> {n1(c.start)}s → {c.end != null ? `${n1(c.end)}s` : '…'}{' '}
                <span className={c.outcome === 'done' ? '' : 'warn'}>
                  {c.outcome === 'done' ? t('arena.combos.done') : t('arena.combos.aborted', { why: c.reason })}
                </span>
                <ol className="small muted">{c.steps.map((s, j) => <li key={j}>{n1(s.t)}s: {s.text.replace(/^\d+\. /, '')}</li>)}</ol>
              </li>
            ))}
          </ul>
        </details>
      )}
      {result.decisions?.length > 0 && (
        <details className="report-details">
          <summary>{t('arena.decisions', { n: result.decisions.length })}</summary>
          <ul className="report-events">{result.decisions.map((e, k) => <li key={k}>{n1(e.t)}s: {e.text}</li>)}</ul>
        </details>
      )}
      <details className="report-details">
        <summary>{t('report.events')} ({result.events.length})</summary>
        <ul className="report-events">{result.events.map((e, k) => <li key={k}>{n1(e.t)}s: {e.text}</li>)}</ul>
      </details>
      {result.skipped?.length > 0 && (
        <details className="report-details">
          <summary className="warn">{t('arena.skipped', { n: result.skipped.length })}</summary>
          <ul className="report-events">{result.skipped.map((e, k) => <li key={k}>{e.text}</li>)}</ul>
        </details>
      )}
    </section>
  );
}
