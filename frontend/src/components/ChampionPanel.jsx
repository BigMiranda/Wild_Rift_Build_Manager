import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { currentLang, statLabel, t } from '../i18n.js';
import { StatIcon } from './StatIcon.jsx';
import { EffectOptions } from './RuneEditor.jsx';

/**
 * Champion reference for the build's unit: stats measured in the training mode (levels 1 and 15), passive and
 * abilities as transcribed from the game's collection (pt-BR, not translated, like the item passives).
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

/** Scaling markers of the ability texts -> stat icon. */
const MARKER_STAT = { DdA: 'Attack Damage', PdH: 'Ability Power', Arm: 'Armor', RM: 'Magic Resistance',
  Vida: 'Max Health', Mana: 'Max Mana', VdM: 'Move Speed', Crit: '% Critical Rate', VdA: '% Attack Speed' };

function label(key) {
  if (STATUS_LABEL[key]) return STATUS_LABEL[key][currentLang() === 'en' ? 1 : 0];
  return STATUS_STAT[key] ? statLabel(STATUS_STAT[key]) : key;
}

function Text({ text }) {
  const parts = String(text ?? '').split(/(\{[^}]+\})/);
  return (
    <p className="champ-text">
      {parts.map((p, i) => {
        const m = p.match(/^\{(.+)\}$/);
        if (!m) return p;
        return MARKER_STAT[m[1]]
          ? <StatIcon key={i} stat={MARKER_STAT[m[1]]} size={12} />
          : <span key={i} className="champ-marker">{m[1]}</span>;
      })}
    </p>
  );
}

function Ability({ a, tag }) {
  const ranks = Object.entries(a.niveis ?? {});
  return (
    <div className="champ-ability">
      <div className="champ-ability-head">
        <span className="champ-tag">{tag}</span>
        <strong>{a.nome}</strong>
        {(a.tipos ?? []).map((x) => <span key={x} className="champ-type">{x}</span>)}
        {a.recarga != null && <small className="muted">{t('champ.cooldown', { s: a.recarga })}</small>}
      </div>
      <Text text={a.texto} />
      {ranks.length > 0 && (
        <table className="champ-ranks">
          <tbody>
            {ranks.map(([k, vals]) => (
              <tr key={k}><td className="l">{k}</td>{(Array.isArray(vals) ? vals : [vals]).map((v, i) => <td key={i}>{v}</td>)}</tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

export const emptyChampionSetup = () => ({ skillOrder: [1, 2, 3], options: {}, conditional: {}, rates: {} });

const ORDERS = [[1, 2, 3], [1, 3, 2], [2, 1, 3], [2, 3, 1], [3, 1, 2], [3, 2, 1]];
const ULT_LEVELS = [5, 9, 13];

/** Ability that gets the point at each level 1..15 (same rule as the backend's ChampionCatalog.ranks). */
function pointsByLevel(order) {
  const ranks = [0, 0, 0];
  const out = [];
  let learned = 0;
  for (let l = 1; l <= 15; l++) {
    if (ULT_LEVELS.includes(l)) { out.push('R'); continue; }
    const a = learned < 3 ? order[learned++] : order.find((x) => ranks[x - 1] < 4);
    ranks[a - 1]++;
    out.push(String(a));
  }
  return out;
}

/** Modeled effects of the champion and the build's choices for them (skill order, conditional, options, rates). */
function CalcEffects({ effects, abilities, setup, onChange, matchEnd }) {
  const order = ORDERS.some((o) => o.join() === setup.skillOrder?.join()) ? setup.skillOrder : [1, 2, 3];
  const set = (patch) => onChange({ ...setup, ...patch });
  const name = (a) => abilities?.[a - 1]?.nome ?? a;
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
      <div className="champ-order">
        <label title={t('champ.skillOrderHint')}>
          {t('champ.skillOrder')}{' '}
          <select value={order.join()} onChange={(ev) => set({ skillOrder: ev.target.value.split(',').map(Number) })}>
            {ORDERS.map((o) => <option key={o.join()} value={o.join()}>{o.map(name).join(' › ')}</option>)}
          </select>
        </label>
        <span className="champ-points" title={t('champ.byLevel')}>
          {pointsByLevel(order).map((a, i) => (
            <span key={i} className={`champ-point${a === 'R' ? ' ult' : ''}`}><small>{i + 1}</small>{a}</span>
          ))}
        </span>
      </div>
      {setup && (
        <EffectOptions entries={controls} state={setup} set={set} matchEnd={matchEnd} title={t('champ.choices')} />
      )}
    </div>
  );
}

export default function ChampionPanel({ code, setup, onChange, matchEnd }) {
  const [champ, setChamp] = useState(null);

  useEffect(() => {
    let alive = true;
    setChamp(null);
    api.get(`/api/champions/${code}`).then((c) => alive && setChamp(c)).catch(() => alive && setChamp(null));
    return () => { alive = false; };
  }, [code]);

  if (!champ) return null;
  const st = champ.status ?? {};
  const keys = [...new Set([...Object.keys(st.nv1 ?? {}), ...Object.keys(st.nv15 ?? {})])];
  const fmt = (k, v) => (v == null ? '—' : PCT.has(k) ? `${v}%` : k === 'VdA' ? Number(v).toFixed(2) : v);

  return (
    <details className="panel champ-panel">
      <summary>
        <strong>{champ.nome}</strong> <span className="muted">{champ.titulo}</span>
        {champ.modelados?.length > 0 && <small className="champ-count">{t('champ.effectsCount', { n: champ.modelados.length })}</small>}
      </summary>
      {onChange && (
        <CalcEffects effects={champ.modelados ?? []} abilities={champ.habilidades} setup={setup ?? emptyChampionSetup()}
          onChange={onChange} matchEnd={matchEnd} />
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
          {champ.passiva && <Ability a={champ.passiva} tag="P" />}
          {(champ.habilidades ?? []).map((a, i) => <Ability key={i} a={a} tag={i === 3 ? 'R' : String(i + 1)} />)}
          {champ.forma_alternativa && (
            <>
              <h4>{t('champ.altForm')}{champ.forma_alternativa.nome ? `: ${champ.forma_alternativa.nome}` : ''}</h4>
              {(champ.forma_alternativa.habilidades ?? []).map((a, i) => <Ability key={i} a={a} tag={i === 3 ? 'R' : String(i + 1)} />)}
            </>
          )}
        </div>
      </div>
    </details>
  );
}
