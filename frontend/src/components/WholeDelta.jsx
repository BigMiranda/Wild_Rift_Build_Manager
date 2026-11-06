import { statLabel, t } from '../i18n.js';
import { n0, statValue } from '../format.js';
import ItemIcon from './ItemIcon.jsx';

const signed = (v, show = n0) => `${v >= 0 ? '+' : '−'}${show(Math.abs(v))}`;
const tone = (v) => (Math.abs(v) < 0.5 ? '' : v > 0 ? 'delta-up' : 'delta-down');

/**
 * The whole build's change by one swap (backend BuildDeltaService): effective gold split into the changed source's own
 * and its effect on the other items, the runes and the Living Forge (by source), every stat of the sheet, effective
 * health and the passives' gold; with a scenario run, the fight's damage dealt and taken. Every comparator shows this.
 * `show` picks the parts: gold | stats | effective | passives | fight (all by default).
 */
export default function WholeDelta({ delta, fight, show, title, compact, ownLabel }) {
  if (!delta) return null;
  const want = (k) => !show || show.includes(k);
  const others = delta.sources.filter((s) => !s.own);
  const stats = Object.entries(delta.statsDelta ?? {}).filter(([, v]) => Math.abs(v) >= 0.05);
  const eff = delta.effectiveMixedAfter - delta.effectiveMixedBefore;
  const pas = delta.passiveGoldAfter - delta.passiveGoldBefore;
  return (
    <div className={`whole-delta${compact ? ' compact' : ''}`}>
      <h5>{title ?? t('wd.title')}</h5>
      {delta.ignored && <p className="error small">{t('wd.ignored')}</p>}
      <dl>
        {want('gold') && (
          <>
            <div title={t('wd.goldHelp')}>
              <dt>{t('wd.gold')}</dt>
              <dd className={tone(delta.goldDelta)}>{signed(delta.goldDelta)} <small className="muted">({n0(delta.goldBefore)} → {n0(delta.goldAfter)})</small></dd>
            </div>
            <div><dt>{ownLabel ?? t('wd.own')}</dt><dd className={tone(delta.ownDelta)}>{signed(delta.ownDelta)}</dd></div>
            <div title={t('wd.othersHelp')}><dt>{t('wd.others')}</dt><dd className={tone(delta.othersDelta)}>{signed(delta.othersDelta)}</dd></div>
          </>
        )}
        {want('effective') && (
          <div title={t('wd.effectiveHelp')}><dt>{t('wd.effective')}</dt><dd className={tone(eff)}>{signed(eff)}</dd></div>
        )}
        {want('passives') && Math.abs(pas) >= 0.5 && (
          <div title={t('wd.passivesHelp')}><dt>{t('wd.passives')}</dt><dd className={tone(pas)}>{signed(pas)}</dd></div>
        )}
        {want('fight') && fight && (
          <>
            <div title={t('wd.fightHelp', { n: fight.n })}><dt>{t('wd.dealt')}</dt><dd className={tone(fight.dealt)}>{signed(fight.dealt)}</dd></div>
            <div title={t('wd.fightHelp', { n: fight.n })}><dt>{t('wd.taken')}</dt><dd className={tone(-fight.taken)}>{signed(fight.taken)}</dd></div>
          </>
        )}
      </dl>
      {want('gold') && others.length > 0 && (
        <details className="wd-sources">
          <summary>{t('wd.bySource', { n: others.length })}</summary>
          <ul className="qc-list">
            {others.map((s, i) => (
              <li key={i}>
                {s.itemId != null && <ItemIcon id={s.itemId} size={16} />}
                {s.kind === 'forge' ? t('wd.forge') : s.name}{s.kind === 'rune' ? ` (${t('wd.rune')})` : ''}: <span className={tone(s.delta)}>{signed(s.delta)}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
      {want('stats') && stats.length > 0 && (
        <details className="wd-stats" open={!compact || undefined}>
          <summary>{t('wd.stats', { n: stats.length })}</summary>
          <ul className="qc-list">
            {stats.map(([k, v]) => <li key={k} className={tone(v)}>{v >= 0 ? '+' : '−'}{statValue(k, Math.abs(v))} {statLabel(k)}</li>)}
          </ul>
        </details>
      )}
    </div>
  );
}

/** Mean damage dealt and taken of a set of outcomes (effectiveness runs), and its change from a reference set. */
export function fightDelta(outcomes, reference) {
  const mean = (list, f) => {
    const ok = (list ?? []).filter((o) => o?.build);
    return ok.length ? ok.reduce((a, o) => a + f(o.build), 0) / ok.length : null;
  };
  const d1 = mean(outcomes, (m) => m.dealt);
  const d0 = mean(reference, (m) => m.dealt);
  const t1 = mean(outcomes, (m) => m.taken);
  const t0 = mean(reference, (m) => m.taken);
  if (d1 == null || d0 == null) return null;
  return { dealt: d1 - d0, taken: t1 - t0, n: (outcomes ?? []).filter((o) => o?.build).length };
}
