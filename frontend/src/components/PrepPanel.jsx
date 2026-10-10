import { t } from '../i18n.js';
import { findSpell, pageSlots, runeIcon, spellIcon } from '../runes.js';

/**
 * Rune page and summoner spells, like the game's "Preparações" screen, each row opening its editor (the items are in
 * the build dock fixed at the top of the page).
 */
export default function PrepPanel({ runePage, spells, onRunes, onSpells }) {
  return (
    <section className="panel prep-panel" aria-label={t('prep.title')}>
      <button className="prep-row" onClick={onRunes}>
        <span className="prep-label">{t('prep.runes')}</span>
        <span className="prep-icons">
          {pageSlots(runePage).map((s) => (s.rune
            ? <img key={s.key} className="rune-img on" src={runeIcon(s.rune)} alt={s.rune.name} title={s.rune.name} width={42} height={42} />
            : <span key={s.key} className="rune-img empty" style={{ width: 42, height: 42 }} />))}
        </span>
        <span className="prep-go" aria-hidden="true">›</span>
      </button>
      <button className="prep-row" onClick={onSpells}>
        <span className="prep-label">{t('prep.spells')}</span>
        <span className="prep-icons">
          {[0, 1].map((i) => {
            const s = findSpell(spells?.[i]);
            return s
              ? <img key={i} className="spell-img" src={spellIcon(s)} alt={s.name} title={s.name} width={42} height={42} />
              : <span key={i} className="spell-img empty" style={{ width: 42, height: 42 }} />;
          })}
        </span>
        <span className="prep-go" aria-hidden="true">›</span>
      </button>
    </section>
  );
}
