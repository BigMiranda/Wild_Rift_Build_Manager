import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { useBuild } from '../buildContext.jsx';
import { pageSlots, runeIcon } from '../runes.js';
import { itemEffectKey } from './ItemEffectsPanel.jsx';
import ItemIcon from './ItemIcon.jsx';

/**
 * The header's "Condicionais" (Rodada 8, parte 5): every conditional effect of the open build — the purchases' own
 * conditional lines (Duplaguarda after 5 s...), the items' modeled effects, the runes (Suserano when immobilizing...) and
 * the champion's (Poder de Noxus...) — each with a switch. The switches apply to the open build on every screen
 * (buildContext's `condOverride` over the draft, never saved until the build is): all on, all off, or as the build saved.
 * It extends what existed: `includeConditional` per purchase and the `conditional` maps of the item setup, the rune page
 * and the champion setup.
 */
export default function Conditionals() {
  const { draft, itemsById, condOverride, setCondOverride } = useBuild();
  const [champEffects, setChampEffects] = useState([]);
  useEffect(() => {
    if (!draft?.unitCode || draft.unitCode === 'RAGDOLL') { setChampEffects([]); return undefined; }
    let live = true;
    api.get(`/api/champions/${draft.unitCode}`).then((c) => { if (live) setChampEffects((c?.modelados ?? []).filter((e) => e.hasConditional)); })
      .catch(() => { if (live) setChampEffects([]); });
    return () => { live = false; };
  }, [draft?.unitCode]);
  if (!draft) return null;

  const rows = conditionalRows(draft, itemsById, champEffects);
  const value = (r) => condOverride?.[r.group]?.[r.key] ?? r.saved;
  const commit = (next) => {
    // nothing different from the saved build: no override at all (the header's warning goes away)
    const differs = rows.some((r) => (next?.[r.group]?.[r.key] ?? r.saved) !== r.saved);
    setCondOverride(differs ? next : null);
  };
  const toggle = (r) => commit({ ...(condOverride ?? {}), [r.group]: { ...(condOverride?.[r.group] ?? {}), [r.key]: !value(r) } });
  const setAll = (on) => {
    const next = {};
    for (const r of rows) next[r.group] = { ...(next[r.group] ?? {}), [r.key]: on };
    commit(next);
  };
  const groups = ['steps', 'items', 'runes', 'champion'];
  return (
    <div className="conditionals">
      <p className="muted small">{t('cond.help')}</p>
      <div className="row">
        <button onClick={() => setAll(true)}>{t('cond.allOn')}</button>
        <button onClick={() => setAll(false)}>{t('cond.allOff')}</button>
        <button onClick={() => setCondOverride(null)} disabled={!condOverride}>{t('cond.asSaved')}</button>
      </div>
      {condOverride && <p className="bh-warn small">{t('cond.changedHelp')}</p>}
      {rows.length === 0 && <p className="muted">{t('cond.none')}</p>}
      {groups.map((g) => {
        const list = rows.filter((r) => r.group === g);
        if (!list.length) return null;
        return (
          <div key={g} className="cond-group">
            <h4>{t(`cond.group.${g}`)}</h4>
            <ul className="cond-list">
              {list.map((r) => {
                const on = value(r);
                const changed = on !== r.saved;
                return (
                  <li key={`${g}:${r.key}`} className={changed ? 'changed' : ''}>
                    {r.itemId != null && <ItemIcon id={r.itemId} size={24} />}
                    {r.rune && <img className="rune-img on" src={runeIcon(r.rune)} alt="" width={24} height={24} />}
                    <span className="cond-label">{r.label}{r.detail && <small className="muted"> · {r.detail}</small>}</span>
                    <button className={`opt${on ? ' on' : ''}`} aria-pressed={on} onClick={() => toggle(r)}
                      title={changed ? t('cond.savedAs', { v: t(r.saved ? 'cond.on' : 'cond.off') }) : undefined}>
                      ◐ {t(on ? 'cond.on' : 'cond.off')}{changed ? ' •' : ''}
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>
        );
      })}
    </div>
  );
}

/** Every conditional of a build, with the value the build saved: { group, key, label, saved, itemId?, rune? }. */
export function conditionalRows(draft, itemsById, champEffects = []) {
  const rows = [];
  const seenItems = new Set();
  let number = 0;
  draft.steps.forEach((s, i) => {
    if (s.kind === 'moment') return;
    if (s.kind !== 'sell') number++;
    const item = s.kind ? null : itemsById.get(s.itemId);
    if (!item) return;
    const lines = (item.stats ?? []).filter((l) => l.conditional);
    if (lines.length) {
      rows.push({
        group: 'steps', key: String(i), label: `#${number} ${item.name}`, itemId: item.id, saved: s.includeConditional !== false,
        detail: [...new Set(lines.map((l) => l.passive).filter(Boolean))].join(', '),
      });
    }
    if (!seenItems.has(item.name)) {
      seenItems.add(item.name);
      for (const e of item.effects ?? []) {
        if (!e.hasConditional) continue;
        const key = itemEffectKey(item.name, e.name);
        rows.push({ group: 'items', key, label: `${item.name} · ${e.name}`, itemId: item.id, saved: draft.itemSetup?.conditional?.[key] ?? (e.defaultOn ?? true) });
      }
    }
  });
  for (const slot of pageSlots(draft.runePage)) {
    const r = slot.rune;
    if (!r?.hasConditional) continue;
    rows.push({ group: 'runes', key: r.name, label: r.name, rune: r, saved: draft.runePage?.conditional?.[r.name] ?? (r.defaultOn ?? true) });
  }
  for (const e of champEffects) {
    rows.push({ group: 'champion', key: e.name, label: `${e.ability ? `${e.ability} · ` : ''}${e.name}`, saved: draft.championSetup?.conditional?.[e.name] ?? (e.defaultOn ?? true) });
  }
  return rows;
}
