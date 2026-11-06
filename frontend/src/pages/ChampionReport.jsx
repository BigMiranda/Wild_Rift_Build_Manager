import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { statLabel, t } from '../i18n.js';
import { mmss, n0, n1 } from '../format.js';
import { useBuild } from '../buildContext.jsx';
import { effectMeta } from '../effectiveness.js';
import { planOf, rankOf } from '../skills.js';
import { Ability } from '../components/ChampionPanel.jsx';
import { StatIcon } from '../components/StatIcon.jsx';
import MinuteInput from '../components/MinuteInput.jsx';
import { BuildLink } from '../components/BuildPreview.jsx';

/**
 * Relatórios › Campeão: passiva e habilidades (Rodada 8, parte 2b). The champion's passive and 4 abilities read with the
 * open build at the minute selected (with the header's conditionals): rank by the build's ability plan, damage / shield
 * / heal with the build's stats term by term, cooldown with the build's haste, cost, range, damage type and control.
 * The numbers come from the Arena's own sheet formulas (`/api/lab/abilities`), so they are the fight's; the game's text
 * below each one is the ChampionPanel's, recomputed by abilityMath.js. Against a target, the damage after its
 * resistances and how many uses kill it.
 */

const SCOPE = { total: '', bonus: 'adicional', base: 'base', alvo: 'do alvo' };
const EFFECT_KEYS = ['lentidao', 'duracao', 'atordoa', 'arremessa', 'enraiza', 'puxa', 'empurra', 'empurrao', 'silencia',
  'provoca', 'amedronta', 'encanta', 'suprime', 'fragil', 'reduz_armadura', 'reduz_rm'];
const PCT_EFFECTS = new Set(['lentidao', 'reduz_armadura', 'reduz_rm']);
const CC = new Set(['atordoa', 'arremessa', 'enraiza', 'puxa', 'silencia', 'provoca', 'amedronta', 'encanta', 'suprime', 'duracao']);

/** The lab's ragdoll: 10000 health, 100 armor, 100 MR (the arena's default). */
const LAB_TARGET = { name: 'Boneco de pano', health: 10000, armor: 100, magicResist: 100 };

/** Which items and runes give a stat at the point (what the hover of a term lists). */
function sourcesOf(point, stat) {
  const out = [];
  for (const c of point?.contributions ?? []) {
    const v = (c.flat?.[stat] ?? 0) + (c.passives?.[stat] ?? 0);
    if (Math.abs(v) >= 0.5) out.push(`${c.itemName} ${n0(v)}`);
  }
  if ((point?.forge?.[stat] ?? 0) >= 0.5) out.push(`${t('wd.forge')} ${n0(point.forge[stat])}`);
  return out;
}

/** "W: escudo 535 = 200 base + 335 da Vida adicional (Coração de Aço 180, Armadura de Warmog 155)". */
function Terms({ r, point }) {
  if (!r.terms?.length) return null;
  return (
    <ul className="cr-terms">
      {r.terms.map((x, i) => {
        if (!x.stat) return <li key={i}>{t('cr.base', { v: n1(x.value), rank: Math.max(1, r.rank) })}</li>;
        const src = x.scope === 'alvo' ? [] : sourcesOf(point, x.stat);
        const label = x.scope === 'alvo' ? t('cr.targetHealth') : `${statLabel(x.stat)}${SCOPE[x.scope] ? ` ${SCOPE[x.scope]}` : ''}`;
        return (
          <li key={i} title={src.length ? t('cr.from', { list: src.join(', ') }) : undefined} className={src.length ? 'has-src' : ''}>
            {x.scope !== 'alvo' && <StatIcon stat={x.stat} size={13} />}
            {n1(100 * x.ratio)}% × {n0(x.amount)} {label} = <strong>{n1(x.value)}</strong>
            {src.length > 0 && <small className="muted"> ({src.join(', ')})</small>}
          </li>
        );
      })}
    </ul>
  );
}

function Effects({ r }) {
  const fx = r.effects ?? {};
  const keys = Object.keys(fx).filter((k) => EFFECT_KEYS.includes(k));
  if (!keys.length && !r.shield && !r.heal) return null;
  const ten = (r.targetTenacity ?? 0) / 100;
  return (
    <ul className="cr-effects">
      {r.shield > 0 && <li>{t('cr.shield', { v: n0(r.shield) })}</li>}
      {r.heal > 0 && <li>{t('cr.heal', { v: n0(r.heal) })}</li>}
      {keys.map((k) => {
        const v = Number(fx[k]);
        if (!Number.isFinite(v)) return <li key={k}>{t(`cr.fx.${k}`)}</li>;
        const shown = PCT_EFFECTS.has(k) ? `${n0(100 * v)}%` : `${n1(v)} s`;
        const after = CC.has(k) && ten > 0 ? ` → ${n1(v * (1 - ten))} s ${t('cr.withTenacity', { pct: n0(100 * ten) })}` : '';
        return <li key={k}>{t(`cr.fx.${k}`)}: {shown}{after}</li>;
      })}
    </ul>
  );
}

/** One ability's card: the numbers of the reading and, unless compact, the game's text recomputed. */
function AbilityCard({ r, other, text, tag, stats, plan, level, point, compact }) {
  const diff = (a, b, show = n0, low = false) => {
    if (b == null || a == null || Math.abs(a - b) < 0.05) return null;
    const better = low ? a < b : a > b;
    return <small className={better ? 'delta-up' : 'delta-down'}> ({a >= b ? '+' : '−'}{show(Math.abs(a - b))})</small>;
  };
  return (
    <article className="cr-card">
      <header>
        <span className="champ-tag">{r.slot}</span>
        <strong>{r.name}</strong>
        {r.slot !== 'P' && (r.rank === 0
          ? <small className="warn">{t('champ.notLearned', { level })}</small>
          : <small className="muted">{t('champ.rankAt', { rank: r.rank, level })}</small>)}
        {r.fromText && <small className="muted" title={t('cr.fromTextHelp')}>{t('cr.fromText')}</small>}
      </header>
      <dl className="cr-numbers">
        {r.damage != null && (
          <div><dt>{t('cr.damage', { type: t(`cr.type.${r.damageType}`) })}</dt>
            <dd className={`dmg-${r.damageType}`}>{n0(r.damage)}{diff(r.damage, other?.damage)}</dd></div>
        )}
        {r.cooldownBase != null && (
          <div title={t('cr.cooldownHelp', { base: n1(r.cooldownBase), haste: n0(r.haste) })}>
            <dt>{t('cr.cooldown')}</dt><dd>{n1(r.cooldown)} s <small className="muted">({n1(r.cooldownBase)} s, {n0(r.haste)} AH)</small>{diff(r.cooldown, other?.cooldown, n1, true)}</dd>
          </div>
        )}
        {r.cost != null && <div><dt>{t('cr.cost')}</dt><dd>{n0(r.cost)} {r.resource ?? ''}</dd></div>}
        {r.range != null && <div><dt>{t('cr.range')}</dt><dd>{n0(r.range)}</dd></div>}
        {r.afterResist != null && (
          <div title={t('cr.targetHelp', { arm: n0(r.targetArmor), mr: n0(r.targetMagicResist), pct: n0(100 * r.targetMult) })}>
            <dt>{t('cr.after')}</dt><dd>{n0(r.afterResist)}{diff(r.afterResist, other?.afterResist)}</dd>
          </div>
        )}
        {r.usesToKill != null && <div><dt>{t('cr.toKill')}</dt><dd>{r.usesToKill}{diff(r.usesToKill, other?.usesToKill, n0, true)}</dd></div>}
      </dl>
      <Terms r={r} point={point} />
      <Effects r={r} />
      {!compact && text && <Ability a={text} tag={tag} stats={stats} planRank={tag === 'P' ? null : rankOf(plan, tag, level)} level={level} />}
    </article>
  );
}

/** How much the Living Forge amplifies each item at the point (Ornn): the item's health, armor and MR × the forge's %. */
function ForgeTable({ point }) {
  const pct = 100 * (point?.forgePct ?? 0);   // forgePct is a fraction
  if (!(pct > 0)) return null;
  const stats = ['Max Health', 'Armor', 'Magic Resistance'];
  const rows = (point.contributions ?? []).filter((c) => !c.rune).map((c) => ({
    name: c.itemName, key: c.purchaseIndex,
    v: stats.map((s) => ((c.flat?.[s] ?? 0) + (c.passives?.[s] ?? 0)) * pct / 100),
  })).filter((r) => r.v.some((x) => x >= 0.5));
  return (
    <div className="table-wrap">
      <h4>{t('cr.forge', { pct: n1(pct) })}</h4>
      <table className="data">
        <thead><tr><th className="l">{t('efx.col.item')}</th>{stats.map((s) => <th key={s}><StatIcon stat={s} size={13} />{statLabel(s)}</th>)}</tr></thead>
        <tbody>
          {rows.map((r) => <tr key={r.key}><td className="l">{r.name}</td>{r.v.map((x, i) => <td key={i}>+{n0(x)}</td>)}</tr>)}
          <tr className="total"><td className="l">{t('cr.forgeTotal')}</td>{stats.map((s) => <td key={s}>+{n0(point.forge?.[s] ?? 0)}</td>)}</tr>
        </tbody>
      </table>
    </div>
  );
}

export default function ChampionReport({ compact = false }) {
  const { draft, payload, point, builds } = useBuild();
  const [champ, setChamp] = useState(null);
  const [targets, setTargets] = useState([LAB_TARGET]);
  const [targetIdx, setTargetIdx] = useState(0);
  const [reading, setReading] = useState(null);
  const [error, setError] = useState(null);
  const [cmp, setCmp] = useState({ kind: 'none', minute: null, buildId: '' });
  const [other, setOther] = useState(null);
  const code = draft?.unitCode;

  useEffect(() => {
    if (!code || code === 'RAGDOLL') return undefined;
    let live = true;
    api.get(`/api/champions/${code}`).then((c) => { if (live) setChamp(c); }).catch(() => { if (live) setChamp(null); });
    return () => { live = false; };
  }, [code]);

  // Targets: the lab's ragdoll, the arena's ragdolls (presets and saved) and the effectiveness scenarios' attackers.
  useEffect(() => {
    let live = true;
    Promise.all([api.get('/api/arena/dummies').catch(() => null), effectMeta().catch(() => null)]).then(([d, m]) => {
      if (!live) return;
      const list = [LAB_TARGET, ...(d?.presets ?? []), ...(d?.saved ?? [])];
      for (const p of m?.presets ?? []) {
        (p.attackers ?? []).forEach((a, i) => list.push({ ...a, name: `${p.name}${p.attackers.length > 1 ? ` #${i + 1}` : ''} (${t('cr.effTarget')})` }));
      }
      setTargets(list);
    });
    return () => { live = false; };
  }, []);

  const target = targets[targetIdx] ?? LAB_TARGET;
  const targetBody = useMemo(() => ({
    name: target.name, health: target.health, armor: target.armor, magicResist: target.magicResist,
    stats: target.stats ?? {}, behavior: 'parado',
  }), [target]);
  const minute = point?.minute;
  const key = JSON.stringify([payload, minute, targetBody]);
  useEffect(() => {
    if (!payload || minute == null || code === 'RAGDOLL') return undefined;
    let live = true;
    const timer = setTimeout(() => {
      api.post('/api/lab/abilities', { build: payload, minute, target: targetBody })
        .then((r) => { if (live) { setReading(r); setError(null); } })
        .catch((e) => { if (live) setError(e.message); });
    }, 200);
    return () => { live = false; clearTimeout(timer); };
  }, [key]);

  // The comparison: the same build at another minute, or another saved build at the same minute.
  const cmpKey = JSON.stringify([cmp, payload, minute, targetBody]);
  useEffect(() => {
    setOther(null);
    if (cmp.kind === 'none' || !payload) return undefined;
    let live = true;
    const body = cmp.kind === 'minute'
      ? (cmp.minute == null ? null : { build: payload, minute: cmp.minute, target: targetBody })
      : (cmp.buildId === '' ? null : { buildId: Number(cmp.buildId), minute, target: targetBody });
    if (!body) return undefined;
    api.post('/api/lab/abilities', body).then((r) => { if (live) setOther(r); }).catch((e) => { if (live) setError(e.message); });
    return () => { live = false; };
  }, [cmpKey]);

  if (code === 'RAGDOLL') return <p className="muted">{t('build.ragdollNoSkills')}</p>;
  if (!point) return <p className="muted">{t('timeline.needInputs')}</p>;
  const plan = planOf(draft.championSetup);
  const stats = { total: point.total, base: point.base };
  const textOf = (slot) => (slot === 'P' ? champ?.passiva : champ?.habilidades?.[slot === 'R' ? 3 : Number(slot) - 1]);
  const otherOf = (slot) => other?.find((x) => x.slot === slot);
  // what the champion's own passive and abilities add to the sheet (the planner's champion contributions)
  const passiveGives = Object.entries((point.contributions ?? []).filter((c) => c.champion)
    .reduce((acc, c) => {
      for (const [k, v] of Object.entries({ ...(c.flat ?? {}) })) acc[k] = (acc[k] ?? 0) + v;
      for (const [k, v] of Object.entries(c.passives ?? {})) acc[k] = (acc[k] ?? 0) + v;
      return acc;
    }, { ...(point.forge ?? {}) })).filter(([, v]) => Math.abs(v) >= 0.5);

  return (
    <section className="panel champion-report">
      <h3>{t('cr.title', { name: champ?.nome ?? code, time: mmss(point.minute), level: point.level })}</h3>
      {!compact && <p className="muted small">{t('cr.help')}</p>}
      <div className="row cr-controls">
        <label className="inline" title={t('cr.targetPick')}>{t('cr.target')}
          <select value={targetIdx} onChange={(e) => setTargetIdx(Number(e.target.value))}>
            {targets.map((x, i) => <option key={i} value={i}>{x.name} — {n0(x.health)} / {n0(x.armor)} / {n0(x.magicResist)}</option>)}
          </select>
        </label>
        {!compact && (
          <>
            <label className="inline">{t('cr.compare')}
              <select value={cmp.kind} onChange={(e) => setCmp({ ...cmp, kind: e.target.value })}>
                <option value="none">—</option>
                <option value="minute">{t('cr.cmpMinute')}</option>
                <option value="build">{t('cr.cmpBuild')}</option>
              </select>
            </label>
            {cmp.kind === 'minute' && <MinuteInput value={cmp.minute} onChange={(m) => setCmp({ ...cmp, minute: m })} style={{ width: 80 }} />}
            {cmp.kind === 'build' && (
              <select value={cmp.buildId} onChange={(e) => setCmp({ ...cmp, buildId: e.target.value })} aria-label={t('cr.cmpBuild')}>
                <option value="">—</option>
                {builds.filter((b) => b.id !== draft.id && b.unitCode === draft.unitCode).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
              </select>
            )}
            {cmp.kind === 'build' && cmp.buildId !== '' && <BuildLink id={Number(cmp.buildId)} name={t('preview.hint')} minute={minute} />}
          </>
        )}
      </div>
      {other && <p className="small muted">{t('cr.cmpNote')}</p>}
      {error && <p className="error">{error}</p>}
      {!reading && !error && <p className="muted">{t('compare.calculating')}</p>}
      <div className="cr-grid">
        {champ?.passiva && (
          <article className="cr-card cr-passive">
            <header><span className="champ-tag">P</span><strong>{champ.passiva.nome}</strong></header>
            {passiveGives.length > 0 && (
              <ul className="cr-terms">
                {passiveGives.map(([s, v]) => <li key={s}><StatIcon stat={s} size={13} />+{n0(v)} {statLabel(s)}</li>)}
              </ul>
            )}
            <ForgeTable point={point} />
            {!compact && <Ability a={champ.passiva} tag="P" stats={stats} level={point.level} />}
          </article>
        )}
        {(reading ?? []).map((r) => (
          <AbilityCard key={r.slot} r={r} other={otherOf(r.slot)} text={r.slot === 'P' ? null : textOf(r.slot)} tag={r.slot}
            stats={stats} plan={plan} level={point.level} point={point} compact={compact} />
        ))}
      </div>
    </section>
  );
}
