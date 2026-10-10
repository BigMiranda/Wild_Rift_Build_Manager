import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { currentLang, statLabel, t } from '../i18n.js';
import { mmss } from '../format.js';
import { computeText, fmt as fmtNum, MARKER_STAT } from '../abilityMath.js';
import { ABILITIES, ORDERS, assign, planOf, rankOf, ranksAt, validOrder, validPlan } from '../skills.js';
import { StatIcon } from './StatIcon.jsx';
import { EffectOptions } from './RuneEditor.jsx';
import { ChampionAvatar } from './ChampionPicker.jsx';

/**
 * Champion of the build's unit: the modeled effects with the build's choices (ability points by level, conditional
 * effects, options, stack rates), the stats measured in the training mode (levels 1 and 15) and the passive and
 * abilities as transcribed from the game's collection (pt-BR, like the item passives), whose values can be recomputed
 * with the stats of the selected moment or of a level without items.
 */

/** Stats-block key -> stat code (icon and label); the rest get a plain label. */
const STATUS_STAT = {
  Vida: 'Max Health', Mana: 'Max Mana', RegVida: 'Health Regen', RegMana: 'Mana Regen', Armadura: 'Armor',
  RM: 'Magic Resistance', DdA: 'Attack Damage', PdH: 'Ability Power', AH: 'Ability Haste', VdA: '% Attack Speed',
  Crit: '% Critical Rate', Tenacidade: '% Tenacity', PotEscudoCura: '% Heal and shield strength',
  RouboVida: '% Lifesteal', Letalidade: 'Armor Penetration', 'PenArm%': '% Armor Penetration',
  PenMag: 'Magic Penetration', 'PenMag%': '% Magic Penetration',
};
const STATUS_LABEL = { VdA: ['Velocidade de Ataque', 'Attack Speed'], VampFisico: ['Vampirismo Físico', 'Physical Vamp'],
  VampMagico: ['Vampirismo Mágico', 'Magic Vamp'], VampUniversal: ['Vampirismo Universal', 'Omnivamp'],
  DanoCrit: ['Dano Crítico', 'Critical Damage'] };
const PCT = new Set(['Crit', 'Tenacidade', 'PotEscudoCura', 'RouboVida', 'VampFisico', 'VampMagico', 'VampUniversal',
  'PenArm%', 'PenMag%', 'DanoCrit']);
/** Unit stats of the measured block used to recompute ability values in the "level without items" mode. */
const BASE_KEYS = ['Vida', 'Mana', 'Armadura', 'RM', 'DdA', 'PdH', 'AH'];

const OPEN_KEY = 'ornn-planner-champ-open';
const MODE_KEY = 'ornn-planner-champ-mode';
const load = (k, d) => { try { return localStorage.getItem(k) ?? d; } catch { return d; } };
const save = (k, v) => { try { localStorage.setItem(k, v); } catch { /* per-viewer convenience only */ } };

export const emptyChampionSetup = () => ({ skillOrder: [1, 2, 3], skillLevels: null, options: {}, conditional: {}, rates: {} });

function label(key) {
  if (STATUS_LABEL[key]) return STATUS_LABEL[key][currentLang() === 'en' ? 1 : 0];
  return STATUS_STAT[key] ? statLabel(STATUS_STAT[key]) : key;
}

/** Text with stat markers as icons and the recomputed values highlighted (tooltip: formula and the game's value). */
function Text({ parts }) {
  const markers = (s, k) => String(s).split(/(\{[^}]+\})/).map((p, i) => {
    const m = p.match(/^\{(.+)\}$/);
    if (!m) return p;
    return MARKER_STAT[m[1]]
      ? <StatIcon key={`${k}-${i}`} stat={MARKER_STAT[m[1]]} size={12} />
      : <span key={`${k}-${i}`} className="champ-marker">{m[1]}</span>;
  });
  return (
    <p className="champ-text">
      {parts.map((p, i) => (typeof p === 'string' ? markers(p, i) : (
        <span key={i}>
          <span className="champ-val" title={`${p.formula}\n${t('champ.gameValue', { v: fmtNum(p.original) })}`}>{fmtNum(p.value)}</span>
          {markers(p.rest, `r${i}`)}
        </span>
      )))}
    </p>
  );
}

function Ability({ a, tag, stats, planRank, level }) {
  const [picked, setPicked] = useState(null);
  const rows = Object.entries(a.niveis ?? {});
  const maxRank = Math.max(1, ...rows.map(([, v]) => (Array.isArray(v) ? v.length : 1)));
  const rank = picked ?? Math.max(1, Math.min(maxRank, planRank ?? 1));
  const parts = useMemo(() => computeText(a.texto, a.niveis, rank, stats), [a, rank, stats]);
  return (
    <div className="champ-ability">
      <div className="champ-ability-head">
        <span className="champ-tag">{tag}</span>
        <strong>{a.nome}</strong>
        {(a.tipos ?? []).map((x) => <span key={x} className="champ-type">{x}</span>)}
        {a.recarga != null && <small className="muted">{t('champ.cooldown', { s: a.recarga })}</small>}
        {tag !== 'P' && maxRank > 1 && (
          <span className="champ-rank-pick" role="group" aria-label={t('champ.rank')}>
            {Array.from({ length: maxRank }, (_, i) => i + 1).map((r) => (
              <button key={r} className={r === rank ? 'active' : ''} aria-pressed={r === rank}
                onClick={() => setPicked(r === picked ? null : r)} title={t('champ.rankTitle')}>
                {t('champ.rankShort', { n: r })}
              </button>
            ))}
          </span>
        )}
        {stats && tag !== 'P' && planRank != null && (
          <small className={planRank === 0 ? 'warn' : 'muted'}>
            {planRank === 0 ? t('champ.notLearned', { level }) : t('champ.rankAt', { rank: planRank, level })}
          </small>
        )}
      </div>
      <Text parts={parts} />
      {rows.length > 0 && (
        <table className="champ-ranks">
          <tbody>
            {rows.map(([k, vals]) => (
              <tr key={k}>
                <td className="l">{k}</td>
                {(Array.isArray(vals) ? vals : [vals]).map((v, i) => <td key={i} className={i === rank - 1 ? 'on' : ''}>{v}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

/**
 * Ability points by level, editable point by point: a grid of levels x abilities. Clicking a cell gives that level's
 * point to the ability, swapping with another level of it so the plan stays one the game allows (cells that cannot be
 * chosen are disabled). The priority order fills a whole plan at once.
 */
function SkillPlan({ abilities, setup, set }) {
  const plan = planOf(setup);
  const custom = validPlan(setup.skillLevels);
  const name = (a) => (a === 'R' ? abilities?.[3]?.nome : abilities?.[Number(a) - 1]?.nome) ?? a;
  const order = validOrder(setup.skillOrder);
  return (
    <div className="champ-plan">
      <div className="champ-order">
        <label title={t('champ.skillOrderHint')}>
          {t('champ.skillOrder')}{' '}
          <select value={custom ? '' : order.join()} title={custom ? undefined : order.map((x) => `${x} ${name(String(x))}`).join(' › ')}
            onChange={(ev) => set({ skillOrder: ev.target.value.split(',').map(Number), skillLevels: null })}>
            {custom && <option value="">{t('champ.customPlan')}</option>}
            {ORDERS.map((o) => (
              <option key={o.join()} value={o.join()} title={o.map((x) => name(String(x))).join(' › ')}>{o.join(' › ')}</option>
            ))}
          </select>
        </label>
        {custom && <button className="link" onClick={() => set({ skillLevels: null })}>{t('champ.resetPlan')}</button>}
        <small className="muted">{t('champ.planHint')}</small>
      </div>
      <div className="champ-plan-scroll">
        <table className="champ-plan-grid">
          <thead>
            <tr><th className="l">{t('champ.levelShort')}</th>{plan.map((_, i) => <th key={i}>{i + 1}</th>)}</tr>
          </thead>
          <tbody>
            {ABILITIES.map((a) => {
              let rank = 0;
              return (
                <tr key={a}>
                  <th className="l" title={name(a)}><span className="champ-tag">{a}</span> <span className="champ-plan-name">{name(a)}</span></th>
                  {plan.map((x, i) => {
                    if (x === a) rank++;
                    const next = x === a ? plan : assign(plan, i + 1, a);
                    return (
                      <td key={i}>
                        <button className={`champ-plan-cell${x === a ? ' on' : ''}${a === 'R' ? ' ult' : ''}`} disabled={!next}
                          aria-pressed={x === a} title={x === a ? t('champ.cellOn', { a: name(a), rank, level: i + 1 }) : t('champ.cellSet', { a: name(a), level: i + 1 })}
                          onClick={() => next && next !== plan && set({ skillLevels: next })}>
                          {x === a ? rank : ''}
                        </button>
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** Modeled effects of the champion and the build's choices for them. */
function CalcEffects({ effects, abilities, setup, onChange, matchEnd }) {
  const set = (patch) => onChange({ ...setup, ...patch });
  const controls = effects.filter((e) => e.option || e.hasConditional || e.rate);
  return (
    <div className="champ-calc">
      <h4>{t('champ.calc')}</h4>
      {!effects.length ? <p className="muted">{t('champ.none')}</p> : (
        <ul className="champ-effects">
          {effects.map((e) => (
            <li key={e.name}>
              <span className="champ-tag">{e.ability}</span> <strong>{e.name}</strong>
              {!e.hasConditional && !e.rate && <small className="muted"> · {t('champ.always')}</small>}
              <span className="champ-effect-lines">{e.summary.join(' · ')}</span>
              {e.note && <small className="muted"> ({e.note})</small>}
            </li>
          ))}
        </ul>
      )}
      <SkillPlan abilities={abilities} setup={setup} set={set} />
      <EffectOptions entries={controls} state={setup} set={set} matchEnd={matchEnd} title={t('champ.choices')} />
    </div>
  );
}

/** Stats of the measured block at a level, without items (base only). */
function levelStats(status, level) {
  const total = {};
  for (const k of BASE_KEYS) {
    const a = status?.nv1?.[k];
    const b = status?.nv15?.[k];
    if (a != null && b != null) total[STATUS_STAT[k]] = a + ((b - a) * (level - 1)) / 14;
  }
  if (status?.VdM != null) total['Move Speed'] = status.VdM;
  return { total, base: { ...total } };
}

export default function ChampionPanel({ code, setup, onChange, matchEnd, point }) {
  const [champ, setChamp] = useState(null);
  const [open, setOpen] = useState(() => load(OPEN_KEY, '1') === '1');
  const [mode, setMode] = useState(() => load(MODE_KEY, 'moment'));
  const [level, setLevel] = useState(1);
  const [tab, setTab] = useState('all');

  useEffect(() => {
    let alive = true;
    setChamp(null);
    setTab('all');
    api.get(`/api/champions/${code}`).then((c) => alive && setChamp(c)).catch(() => alive && setChamp(null));
    return () => { alive = false; };
  }, [code]);
  useEffect(() => save(MODE_KEY, mode), [mode]);

  const s = setup ?? emptyChampionSetup();
  const plan = planOf(s);
  const effMode = mode === 'moment' && !point ? 'raw' : mode;
  const atLevel = effMode === 'moment' ? point.level : effMode === 'level' ? level : null;
  const stats = useMemo(() => {
    if (effMode === 'moment') return { total: point.total, base: point.base };
    if (effMode === 'level' && champ) return levelStats(champ.status, level);
    return null;
  }, [effMode, point, level, champ]);

  if (!champ) return null;
  const st = champ.status ?? {};
  const keys = [...new Set([...Object.keys(st.nv1 ?? {}), ...Object.keys(st.nv15 ?? {})])];
  const fmt = (k, v) => (v == null ? '—' : PCT.has(k) ? `${v}%` : k === 'VdA' ? Number(v).toFixed(2) : v);
  const list = [
    ...(champ.passiva ? [{ a: champ.passiva, tag: 'P', key: 'P' }] : []),
    ...(champ.habilidades ?? []).map((a, i) => ({ a, tag: i === 3 ? 'R' : String(i + 1), key: i === 3 ? 'R' : String(i + 1) })),
    ...(champ.forma_alternativa?.habilidades ?? []).map((a, i) => ({ a, tag: i === 3 ? 'R' : String(i + 1), key: `alt${i}`, alt: true })),
  ];
  const tabs = [['all', t('champ.tabAll')], ...list.filter((x) => !x.alt).map((x) => [x.key, x.tag]),
    ...(champ.forma_alternativa ? [['alt', t('champ.altForm')]] : [])];
  const shown = list.filter((x) => tab === 'all' || (tab === 'alt' ? x.alt : !x.alt && x.key === tab));
  const ranks = atLevel ? ranksAt(plan, atLevel) : null;

  return (
    <details className="panel champ-panel" open={open} onToggle={(e) => { setOpen(e.target.open); save(OPEN_KEY, e.target.open ? '1' : '0'); }}>
      <summary>
        <ChampionAvatar name={champ.nome} size={28} />
        <strong>{champ.nome}</strong> <span className="muted">{champ.titulo}</span>
        {champ.modelados?.length > 0 && <small className="champ-count">{t('champ.effectsCount', { n: champ.modelados.length })}</small>}
      </summary>
      {onChange && (
        <CalcEffects effects={champ.modelados ?? []} abilities={champ.habilidades} setup={s} onChange={onChange} matchEnd={matchEnd} />
      )}
      <div className="champ-body">
        <div className="champ-stats">
          <table>
            <thead><tr><th className="l">{t('champ.stat')}</th><th>{t('champ.level', { n: 1 })}</th><th>{t('champ.level', { n: 15 })}</th></tr></thead>
            <tbody>
              {keys.map((k) => (
                <tr key={k}>
                  <td className="l">{STATUS_STAT[k] && <StatIcon stat={STATUS_STAT[k]} />}{label(k)}</td>
                  <td>{fmt(k, st.nv1?.[k])}</td><td>{fmt(k, st.nv15?.[k])}</td>
                </tr>
              ))}
              {st.VdM != null && (
                <tr><td className="l"><StatIcon stat="Move Speed" />{statLabel('Move Speed')}</td><td colSpan={2}>{st.VdM}</td></tr>
              )}
              {st.recurso && (
                <tr><td className="l">{t('champ.resource')}</td><td colSpan={2}>{st.recurso}</td></tr>
              )}
            </tbody>
          </table>
          <p className="muted small">{t('champ.statsNote')}</p>
        </div>
        <div className="champ-abilities">
          <div className="champ-ab-toolbar">
            <div className="view-toggle" role="group" aria-label={t('champ.values')}>
              <button className={effMode === 'raw' ? 'active' : ''} aria-pressed={effMode === 'raw'} onClick={() => setMode('raw')}
                title={t('champ.modeRawTitle')}>{t('champ.modeRaw')}</button>
              <button className={effMode === 'moment' ? 'active' : ''} aria-pressed={effMode === 'moment'} disabled={!point}
                onClick={() => setMode('moment')} title={point ? t('champ.modeMomentTitle') : t('champ.modeMomentNone')}>
                {point ? t('champ.modeMoment', { time: mmss(point.minute), level: point.level }) : t('champ.modeMomentShort')}
              </button>
              <button className={effMode === 'level' ? 'active' : ''} aria-pressed={effMode === 'level'} onClick={() => setMode('level')}
                title={t('champ.modeLevelTitle')}>{t('champ.modeLevel')}</button>
            </div>
            {effMode === 'level' && (
              <select value={level} onChange={(e) => setLevel(Number(e.target.value))} aria-label={t('champ.modeLevel')}>
                {Array.from({ length: 15 }, (_, i) => i + 1).map((l) => <option key={l} value={l}>{t('champ.level', { n: l })}</option>)}
              </select>
            )}
            {stats && <small className="muted">{t('champ.valuesHint')}</small>}
          </div>
          <div className="champ-tabs" role="tablist">
            {tabs.map(([k, l]) => (
              <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? 'active' : ''} onClick={() => setTab(k)}
                title={list.find((x) => x.key === k)?.a.nome}>
                {l}
              </button>
            ))}
          </div>
          {shown.map((x) => (
            <div key={`${code}-${x.key}`}>
              {x.alt && x === list.find((y) => y.alt) && (
                <h4>{t('champ.altForm')}{champ.forma_alternativa.nome ? `: ${champ.forma_alternativa.nome}` : ''}</h4>
              )}
              <Ability a={x.a} tag={x.tag} stats={stats} level={atLevel}
                planRank={ranks ? (x.tag === 'P' ? 1 : rankOf(plan, x.tag, atLevel)) : null} />
            </div>
          ))}
        </div>
      </div>
    </details>
  );
}

