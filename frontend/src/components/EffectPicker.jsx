import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { buildTiles } from '../shopModel.js';
import ItemIcon from './ItemIcon.jsx';
import ShopBrowser from './ShopBrowser.jsx';
import { ChosenChips, PickerPopover, useShopItems } from './ShopPicker.jsx';
import { ChampionAvatar } from './ChampionPicker.jsx';

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
const plain = (s) => String(s ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
const round = (v, d = 1) => Math.round(v * 10 ** d) / 10 ** d;

/** What an effect does, for the filters and the marks on the items. */
const GROUPS = {
  reduz_armadura: 'armadura', reduz_rm: 'rm', pen_armadura_pct: 'penf', pen_armadura: 'penf', pen_magica_pct: 'penm', pen_magica: 'penm',
  feridas: 'feridas', corta_escudo: 'escudo',
};
const FILTERS = ['armadura', 'rm', 'penf', 'penm', 'feridas', 'escudo'];
const groupsOf = (s) => [...new Set(s.effects.map((e) => GROUPS[e.kind]).filter(Boolean))];
const Mark = ({ g }) => <span className={`fx-mark fx-${g}`} title={t(`fx.filter.${g}`)}>{t(`fx.mark.${g}`)}</span>;

export default function EffectPicker({ chosen, onChange }) {
  const [sources, setSources] = useState([]);
  const [error, setError] = useState(null);
  const [filters, setFilters] = useState([]);
  const shop = useShopItems();
  useEffect(() => {
    effectSources().then(setSources).catch((e) => setError(e.message));
  }, []);

  const toggle = (s) => {
    if (chosen.some((c) => c.name === s.name)) onChange(chosen.filter((c) => c.name !== s.name));
    else onChange([...chosen, { ...s, effects: s.effects.map((e) => ({ ...e, stat: '' })) }]);
  };
  const remove = (name) => onChange(chosen.filter((c) => c.name !== name));
  const edit = (name, k, patch) => onChange(chosen.map((c) => (c.name !== name ? c
    : { ...c, effects: c.effects.map((e, i) => (i !== k ? e : { ...e, ...patch })) })));

  const pass = (s) => filters.length === 0 || groupsOf(s).some((g) => filters.includes(g));
  const byItem = useMemo(() => new Map(sources.filter((s) => s.itemId != null).map((s) => [s.itemId, s])), [sources]);
  const tiles = useMemo(() => buildTiles(shop.filter((i) => byItem.has(i.id))), [shop, byItem]);
  const sourceOf = (x) => byItem.get(x.preferredId);
  const others = sources.filter((s) => s.itemId == null || !shop.some((i) => i.id === s.itemId));
  const isOn = (s) => chosen.some((c) => c.name === s.name);
  return (
    <div className="effect-picker">
      <PickerPopover label={t('fx.add')} title={t('fx.addTitle')}>
        {() => (
          <>
            <ChosenChips chosen={chosen.map((c) => ({
              key: c.name, name: c.name, icon: c.itemId != null ? <ItemIcon id={c.itemId} size={18} /> : <ChampionAvatar name={c.champion ?? c.name} size={18} />,
              onRemove: () => remove(c.name),
            }))} />
            <div className="shop picker-shop">
              <ShopBrowser
                tiles={tiles} iconSize={52} autoFocus filter={(x) => pass(sourceOf(x))} marked={(x) => isOn(sourceOf(x))}
                heading={t('fx.items')} allTabs={filters.length > 0}
                toolbar={(
                  <div className="chips picker-filters" role="group" aria-label={t('fx.filters')}>
                    {FILTERS.map((g) => (
                      <button key={g} type="button" className={filters.includes(g) ? 'active' : ''} aria-pressed={filters.includes(g)}
                        onClick={() => setFilters((f) => (f.includes(g) ? f.filter((x) => x !== g) : [...f, g]))}>
                        <Mark g={g} /> {t(`fx.filter.${g}`)}
                      </button>
                    ))}
                  </div>
                )}
                decorate={(x) => <span className="fx-marks">{groupsOf(sourceOf(x)).map((g) => <Mark key={g} g={g} />)}</span>}
                tileProps={(x) => ({ type: 'button', title: x.name, onClick: () => toggle(sourceOf(x)) })}
                after={(query) => {
                  const q = plain(query.trim());
                  const list = others.filter((s) => pass(s) && (!q || plain(`${s.name} ${s.champion ?? ''}`).includes(q)));
                  if (list.length === 0) return null;
                  return (
                    <section>
                      <h4 className="picker-heading">{t('fx.championAbilities')}</h4>
                      <div className="fx-sources">
                        {list.map((s) => (
                          <button key={s.name} type="button" className={`tile fx-source${isOn(s) ? ' marked' : ''}`} aria-pressed={isOn(s)} onClick={() => toggle(s)}>
                            <ChampionAvatar name={s.champion ?? s.name} size={32} />
                            <span className="fx-source-text">
                              <span>{s.name}</span>
                              <small className="muted">{s.effects.map((e) => t(`fx.kind.${e.kind}`)).join(' · ')}</small>
                            </span>
                            <span className="fx-marks inline">{groupsOf(s).map((g) => <Mark key={g} g={g} />)}</span>
                          </button>
                        ))}
                      </div>
                    </section>
                  );
                }}
              />
            </div>
          </>
        )}
      </PickerPopover>
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
