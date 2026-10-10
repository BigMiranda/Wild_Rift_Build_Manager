import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { currentLang, statLabel, t } from '../i18n.js';
import { StatIcon } from './StatIcon.jsx';

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

export default function ChampionPanel({ code }) {
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
      </summary>
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
