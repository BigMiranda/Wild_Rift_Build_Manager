import { t } from '../i18n.js';
import { SERIES_COLORS, mmss, n0, statLabel, statValue } from '../format.js';
import { useBuild } from '../buildContext.jsx';
import { effectiveGold } from '../effective.js';
import { findSpell, pageSlots, runeIcon, spellIcon } from '../runes.js';
import { archetypeLabel, archetypeTitle } from '../archetypes.js';
import ItemIcon from '../components/ItemIcon.jsx';
import { StatIcon, statColor } from '../components/StatIcon.jsx';
import { BuildLink } from '../components/BuildPreview.jsx';
import { ChartSection, TimelineSection, EffectiveGoldSection } from './Planner.jsx';

const KEY_STATS = ['Max Health', 'Armor', 'Magic Resistance', 'Attack Damage', 'Ability Power', 'Ability Haste', '% Attack Speed', 'Move Speed'];

/**
 * Relatórios › Análise da build: the summary of the moment — level, gold, the items held and what they are worth,
 * runes and spells, the key stats and the archetype (the details are in the other reports).
 */
export default function Analysis() {
  const { draft, point, result, itemsById, passiveValues, archetypes } = useBuild();
  if (!point) return <p className="muted">{t('timeline.needInputs')}</p>;
  const eff = effectiveGold(point, itemsById, result.statPrices, passiveValues.overlayAt('active', point.minute));
  const arch = draft.id ? archetypes[draft.id] : null;
  return (
    <section className="panel analysis">
      <h3>{t('analysis.title', { time: mmss(point.minute), level: point.level })}</h3>
      {arch && <p className="small" title={archetypeTitle(arch)}>{t('arch.of', { name: archetypeLabel(arch) })}</p>}
      <div className="analysis-grid">
        <div>
          <h4>{t('analysis.items')}</h4>
          <ul className="analysis-items">
            {eff.items.map((x) => (
              <li key={x.key}>
                <ItemIcon id={x.itemId} size={32} />
                <span>{x.itemName}</span>
                <small className="muted">{n0(x.effective)} / {n0(x.cost)}{x.profitPct != null ? ` (${x.profitPct >= 0 ? '+' : '−'}${n0(Math.abs(x.profitPct))}%)` : ''}</small>
              </li>
            ))}
          </ul>
          <p className="big-number">{t('analysis.gold', { eff: n0(eff.effective), spent: n0(eff.spent) })}</p>
          <p className="muted small">{t('analysis.inHand', { gold: n0(point.goldInHand ?? 0) })}</p>
        </div>
        <div>
          <h4>{t('analysis.stats')}</h4>
          <ul className="preview-stats">
            {KEY_STATS.filter((s) => (point.total[s] ?? 0) > 0.05).map((s) => (
              <li key={s} style={{ color: statColor(s) }}><StatIcon stat={s} size={16} />{statLabel(s)} <strong>{statValue(s, point.total[s])}</strong></li>
            ))}
          </ul>
          <h4>{t('prep.runes')} · {t('prep.spells')}</h4>
          <div className="preview-items">
            {pageSlots(draft.runePage).filter((x) => x.rune).map((x) => (
              <img key={x.key} className="rune-img on" src={runeIcon(x.rune)} alt={x.rune.name} title={x.rune.name} width={32} height={32} />
            ))}
            {(draft.spells ?? []).map((n) => {
              const s = findSpell(n);
              return s ? <img key={n} className="spell-img" src={spellIcon(s)} alt={s.name} title={s.name} width={32} height={32} /> : null;
            })}
          </div>
        </div>
      </div>
    </section>
  );
}

/**
 * Relatórios › Comparar builds (it left the list of builds): the builds marked for comparison, side by side with the
 * open one — chart, timelines and effective gold.
 */
export function CompareBuilds() {
  const { builds, compareIds, toggleCompare, draft } = useBuild();
  const others = builds.filter((x) => x.id !== draft.id && !compareIds.includes(x.id));
  return (
    <>
      <section className="panel">
        <h3>{t('sub.relatorios.comparar-builds')}</h3>
        <p className="muted small">{t('cmpb.help')}</p>
        <div className="chips">
          {compareIds.map((id, i) => {
            const x = builds.find((y) => y.id === id);
            return (
              <span key={id} className="chip-item">
                <span className="swatch" style={{ background: SERIES_COLORS[(i + 1) % SERIES_COLORS.length] }} />
                <BuildLink id={id} name={x?.name ?? `#${id}`} />
                <button className="icon" onClick={() => toggleCompare(id)} aria-label={t('cmpb.remove', { name: x?.name ?? id })}>✕</button>
              </span>
            );
          })}
          <label className="inline">{t('cmpb.add')}
            <select value="" onChange={(e) => e.target.value && toggleCompare(Number(e.target.value))}>
              <option value="">—</option>
              {others.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          </label>
        </div>
      </section>
      <ChartSection />
      <TimelineSection />
      <EffectiveGoldSection />
    </>
  );
}
