import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import ItemIcon from './ItemIcon.jsx';

/**
 * Picks the effects a build is seen under: items and champion passives / abilities (GET /api/lab/effect-sources), each
 * effect with its value editable and, when it grows with a stat (Ocean Trident: AP; Serpent's Fang: bonus AD), a field
 * for that stat that computes the value (base + per x stat, up to the cap). The chosen list is
 * [{ name, group, itemId, effects: [{ kind, value, stat, base, per, max, scale, text }] }]; `applied` flattens it into
 * what the backend takes.
 */
let cache = null;
export function effectSources() {
  if (!cache) cache = api.get('/api/lab/effect-sources').catch((e) => { cache = null; throw e; });
  return cache;
}

/** Shares are fractions, flat penetration is in points. */
export const isShare = (kind) => kind !== 'pen_armadura' && kind !== 'pen_magica';

/** The chosen list as the backend takes it: [{ source, kind, value }]. */
export const applied = (chosen) => chosen.flatMap((s) => s.effects.map((e) => ({ source: s.name, kind: e.kind, value: e.value })));

const scaled = (e, stat) => {
  const v = (e.base ?? 0) + (e.per ?? 0) * (Number(stat) || 0);
  return e.max != null ? Math.min(e.max, v) : v;
};
const round = (v, d = 1) => Math.round(v * 10 ** d) / 10 ** d;

export default function EffectPicker({ chosen, onChange }) {
  const [sources, setSources] = useState([]);
  const [error, setError] = useState(null);
  useEffect(() => {
    effectSources().then(setSources).catch((e) => setError(e.message));
  }, []);

  const add = (name) => {
    const s = sources.find((x) => x.name === name);
    if (!s || chosen.some((c) => c.name === name)) return;
    onChange([...chosen, { ...s, effects: s.effects.map((e) => ({ ...e, stat: '' })) }]);
  };
  const remove = (name) => onChange(chosen.filter((c) => c.name !== name));
  const edit = (name, k, patch) => onChange(chosen.map((c) => (c.name !== name ? c
    : { ...c, effects: c.effects.map((e, i) => (i !== k ? e : { ...e, ...patch })) })));

  const free = sources.filter((s) => !chosen.some((c) => c.name === s.name));
  const items = free.filter((s) => s.group === 'item');
  const champs = free.filter((s) => s.group === 'campeao');
  return (
    <div className="effect-picker">
      <label className="inline" title={t('fx.addTitle')}>{t('fx.add')}
        <select value="" onChange={(e) => add(e.target.value)}>
          <option value="">+</option>
          {items.length > 0 && <optgroup label={t('fx.items')}>{items.map((s) => <option key={s.name} value={s.name}>{s.name}</option>)}</optgroup>}
          {champs.length > 0 && <optgroup label={t('fx.champions')}>{champs.map((s) => <option key={s.name} value={s.name}>{s.name}</option>)}</optgroup>}
        </select>
      </label>
      {error && <p className="error">{error}</p>}
      {chosen.length > 0 && (
        <ul className="effect-list">
          {chosen.map((c) => (
            <li key={c.name}>
              <div className="effect-head">
                {c.itemId != null && <ItemIcon id={c.itemId} size={20} />}
                <strong>{c.name}</strong>
                <button className="icon" onClick={() => remove(c.name)} aria-label={t('fx.remove', { name: c.name })}>✕</button>
              </div>
              {c.effects.map((e, k) => {
                const share = isShare(e.kind);
                return (
                  <div key={k} className="effect-row">
                    <span className="effect-kind">{t(`fx.kind.${e.kind}`)}</span>
                    <input type="number" min="0" step={share ? 0.5 : 1} aria-label={t('fx.value', { kind: t(`fx.kind.${e.kind}`) })}
                      value={share ? round(e.value * 100, 2) : round(e.value, 2)} style={{ width: 70 }}
                      onChange={(ev) => edit(c.name, k, { value: Math.max(0, Number(ev.target.value) || 0) / (share ? 100 : 1), stat: '' })} />
                    <span className="muted">{share ? '%' : ''}</span>
                    {e.scale && (
                      <label className="inline small" title={t('fx.scaleTitle', { stat: e.scale })}>{e.scale}
                        <input type="number" min="0" step="10" value={e.stat} placeholder="0" style={{ width: 70 }}
                          onChange={(ev) => edit(c.name, k, { stat: ev.target.value, value: scaled(e, ev.target.value) })} />
                      </label>
                    )}
                    {e.text && <small className="muted">{e.text}</small>}
                  </div>
                );
              })}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
