import { useEffect, useState } from 'react';
import { t } from '../i18n.js';
import { findSpell, runeCatalog, spellIcon } from '../runes.js';

/**
 * Summoner spells of the build (2), laid out like the rune list: the two equipped slots on the left, every spell with
 * its description on the right. Click a slot, then a spell; a spell already in the other slot swaps places.
 */
export default function SpellEditor({ spells, onChange, onClose }) {
  const [slot, setSlot] = useState(0);
  const cat = runeCatalog();
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  if (!cat) return null;
  const current = [spells?.[0] ?? null, spells?.[1] ?? null];

  const choose = (name) => {
    const next = [...current];
    const other = 1 - slot;
    if (next[other] === name) next[other] = next[slot];
    next[slot] = name;
    onChange(next);
    if (next[other] == null) setSlot(other);
  };

  return (
    <div className="prep-overlay" role="dialog" aria-label={t('spells.title')}>
      <div className="prep-overlay-head">
        <button className="prep-back" onClick={onClose} aria-label={t('prep.back')}>
          <span aria-hidden="true">‹</span> {t('spells.title')}
        </button>
      </div>
      <div className="rune-list-view">
        <div className="rune-rail spell-rail">
          {current.map((name, i) => {
            const s = findSpell(name);
            return (
              <button key={i} className={`rail-spell${slot === i ? ' picking' : ''}`} onClick={() => setSlot(i)}
                title={s ? s.name : t('spells.empty')} aria-pressed={slot === i}>
                {s ? <img src={spellIcon(s)} alt={s.name} width={64} height={64} /> : <span className="spell-img empty" />}
                <small>{s ? s.name : t('spells.slot', { n: i + 1 })}</small>
              </button>
            );
          })}
        </div>
        <div className="rune-panel">
          <h3 className="rune-pick-title">{t('spells.pick', { n: slot + 1 })}</h3>
          <ul className="rune-choices">
            {cat.spells.map((s) => {
              const at = current.indexOf(s.name);
              return (
                <li key={s.name}>
                  <button className={`rune-choice${at >= 0 ? ' selected' : ''}`} onClick={() => choose(s.name)}>
                    <img className="spell-img" src={spellIcon(s)} alt="" width={56} height={56} />
                    <span className="rune-row-text">
                      <span className="rune-name">{s.name}{at >= 0 && <span className="spell-slot-tag">{t('spells.slot', { n: at + 1 })}</span>}</span>
                      <span className="rune-tags">{t('spells.cooldown', { s: s.cooldown })} · {t('spells.maps', { maps: s.maps })}</span>
                      <span className="rune-desc">{s.text}</span>
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </div>
      </div>
    </div>
  );
}
