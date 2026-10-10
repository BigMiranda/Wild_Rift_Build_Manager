import { statLabel, t } from '../i18n.js';
import { DERIVED_STATS, EFFECTIVE_STATS, PERCENT_STATS, SHEET_STATS, mmss, n0, n1, pct, statOf, statValue } from '../format.js';
import ItemIcon from './ItemIcon.jsx';
import { useItemReader } from './BuildDock.jsx';
import RichText from './RichText.jsx';
import { StatIcon, statColor } from './StatIcon.jsx';
import PassiveCompare from './PassiveCompare.jsx';
import { findRune, runeIcon } from '../runes.js';
import { PASSIVE_GOLD_BY_TIER, acquiredPassives, passiveColor, passiveSince, pointItemIds } from '../passives.js';

/** Series point at (or right before) a minute. */
export function pointAt(series, minute) {
  if (!series?.length) return null;
  let best = series[0];
  for (const p of series) {
    if (p.minute <= minute + 1e-9) best = p;
  }
  return best;
}

/** Champion stats at a moment, like the in-game stats tab: total (base + bonus). */
export function StatSheet({ point, itemsById }) {
  if (!point) return null;
  return (
    <section className="panel">
      <h3>{t('sheet.title', { time: mmss(point.minute), level: point.level })}</h3>
      <p className="muted" style={{ marginTop: 0 }}>
        {t('sheet.help')} {point.forgePct > 0 && t('sheet.forge', { pct: pct(point.forgePct * 100) })}
      </p>
      <div className="stat-sheet">
        {SHEET_STATS.map((s) => {
          const total = statOf(point.total, s) ?? 0;
          const base = DERIVED_STATS[s] ? undefined : point.base[s];
          const bonus = base != null ? total - base : total;
          const showSplit = base != null && !PERCENT_STATS.has(s);
          return (
            <div key={s} className={`sheet-row${DERIVED_STATS[s] ? ' derived' : ''}`}
              title={DERIVED_STATS[s] ? t('dr.title', { stat: statLabel(s === 'Physical Reduction' ? 'Armor' : 'Magic Resistance'), res: n0(point.total[s === 'Physical Reduction' ? 'Armor' : 'Magic Resistance']), pct: pct(total) }) : undefined}>
              <span className="sheet-label" style={{ color: statColor(s) }}>
                <StatIcon stat={s} />{statLabel(s)}
              </span>
              <span className="sheet-value">
                {statValue(s, total)}
                {showSplit && Math.abs(bonus) > 0.05 && (
                  <small> ({s.includes('Regen') ? n1(base) : n0(base)}<span className="bonus">+{s.includes('Regen') ? n1(bonus) : n0(bonus)}</span>)</small>
                )}
              </span>
            </div>
          );
        })}
      </div>
      <EffectiveSheet total={point.total} />
    </section>
  );
}

/** Effective stats of a moment (shields, heals, stasis, haste of one ability), with what they add up to in a fight. */
function EffectiveSheet({ total }) {
  const held = EFFECTIVE_STATS.filter((s) => (total[s] ?? 0) > 0.05);
  if (!held.length) return null;
  const v = (s) => total[s] ?? 0;
  const rows = held.map((s) => ({ key: s, stat: s, label: statLabel(s), value: statValue(s, v(s)) }));
  if (v('Shield') + v('Heal') > 0) {
    rows.unshift({ key: 'eff', stat: 'Max Health', label: t('sheet.effHealth'), value: n0(v('Max Health') + v('Shield') + v('Heal')), strong: true });
  }
  if (v('Ultimate Haste') > 0) rows.push({ key: 'ult', stat: 'Ability Haste', label: t('sheet.ultHaste'), value: n0(v('Ability Haste') + v('Ultimate Haste')) });
  if (v('Basic Ability Haste') > 0) rows.push({ key: 'basic', stat: 'Ability Haste', label: t('sheet.basicHaste'), value: n0(v('Ability Haste') + v('Basic Ability Haste')) });
  return (
    <>
      <h4 className="sheet-sub" title={t('sheet.effectiveHelp')}>{t('sheet.effective')}</h4>
      <div className="stat-sheet">
        {rows.map((r) => (
          <div key={r.key} className="sheet-row" title={r.key === 'eff' ? t('sheet.effectiveHelp') : undefined}>
            <span className="sheet-label" style={{ color: statColor(r.stat) }}><StatIcon stat={r.stat} />{r.label}</span>
            <span className="sheet-value">{r.strong ? <strong>{r.value}</strong> : r.value}</span>
          </div>
        ))}
      </div>
    </>
  );
}

/**
 * How much each item (and each of its passives) adds to the build at a moment, in gold value, plus Ornn's Living
 * Forge on top. Share = part of the build's whole bonus value.
 */
export function RelevanceReport({ point, prices, itemsById }) {
  const read = useItemReader();
  if (!point) return null;
  const forgeGold = Object.entries(point.forge ?? {}).reduce((sum, [k, v]) => sum + v * (prices[k] ?? 0), 0);
  const rows = (point.contributions ?? []).map((c) => ({ ...c, gold: c.flatGold + c.passiveGold }));
  const grand = rows.reduce((s, r) => s + r.gold, 0) + forgeGold;
  rows.sort((a, b) => b.gold - a.gold);
  const share = (g) => (grand > 0 ? (g / grand) * 100 : 0);

  return (
    <section className="panel">
      <h3>{t('report.title', { time: mmss(point.minute) })}</h3>
      <p className="muted" style={{ marginTop: 0 }}>{t('report.help')}</p>
      {rows.length === 0 && <p className="muted">{t('report.empty')}</p>}
      {rows.length > 0 && (
        <div className="table-wrap">
          <table className="data report">
            <thead>
              <tr>
                <th className="l">{t('report.item')}</th>
                <th className="l">{t('report.stats')}</th>
                <th>{t('report.flat')}</th>
                <th>{t('report.passive')}</th>
                <th>{t('report.total')}</th>
                <th className="l share-col">{t('report.share')}</th>
                <th>{t('report.eff')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const cost = itemsById.get(r.itemId)?.cost;
                return [
                  <tr key={`i${r.purchaseIndex}`} className="report-item">
                    <td className="l">
                      <span className="with-icon">
                        {r.champion
                          ? <><span className="champ-tag">◆</span>{t('champ.effect')} {r.itemName}</>
                          : r.rune
                          ? <><img src={runeIcon(findRune(r.itemName))} alt="" width={24} height={24} className="rune-img on" />{t('runes.rune')} {r.itemName}</>
                          : (
                            <button className="link item-link" onClick={() => read(r.itemId)} title={t('dock.read', { name: r.itemName })}>
                              <ItemIcon id={r.itemId} size={24} />#{r.number}{r.implied ? '↳' : ''} {r.itemName}
                            </button>
                          )}
                        {!r.conditionalIncluded && r.passiveParts.length === 0 && itemsById.get(r.itemId)?.stats.some((s) => s.conditional)
                          && <small className="cond">{t('report.condOff')}</small>}
                      </span>
                    </td>
                    <td className="l"><StatList stats={r.flat} /></td>
                    <td>{n0(r.flatGold)}</td>
                    <td>{r.passiveGold ? n0(r.passiveGold) : '—'}</td>
                    <td><strong>{n0(r.gold)}</strong></td>
                    <td className="l share-col"><ShareBar value={share(r.gold)} /></td>
                    <td>{cost ? pct((r.gold / cost) * 100) : '—'}</td>
                  </tr>,
                  ...r.passiveParts.map((p, i) => (
                    <tr key={`p${r.purchaseIndex}-${i}`} className="report-passive">
                      <td className="l">
                        <span className="passive-name">↳ {p.passive}</span>
                        {p.conditional && <small className="cond"> · {t('shop.conditional')}</small>}
                      </td>
                      <td className="l"><StatList stats={{ [p.stat]: p.amount }} /></td>
                      <td />
                      <td>{n0(p.gold)}</td>
                      <td />
                      <td className="l share-col"><ShareBar value={share(p.gold)} thin /></td>
                      <td />
                    </tr>
                  )),
                ];
              })}
              {forgeGold > 0 && (
                <tr className="report-item">
                  <td className="l"><span className="passive-name">{t('report.forge')} · {pct(point.forgePct * 100)}</span></td>
                  <td className="l"><StatList stats={point.forge} /></td>
                  <td />
                  <td>{n0(forgeGold)}</td>
                  <td><strong>{n0(forgeGold)}</strong></td>
                  <td className="l share-col"><ShareBar value={share(forgeGold)} /></td>
                  <td />
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function StatList({ stats }) {
  const entries = Object.entries(stats ?? {}).filter(([, v]) => Math.abs(v) > 0.05);
  if (entries.length === 0) return <span className="muted">—</span>;
  return (
    <span className="stat-inline">
      {entries.map(([k, v]) => (
        <span key={k} style={{ color: statColor(k) }} title={statLabel(k)}>
          <StatIcon stat={k} />+{statValue(k, v)}
        </span>
      ))}
    </span>
  );
}

function ShareBar({ value, thin = false }) {
  return (
    <span className="share">
      <span className={`share-bar${thin ? ' thin' : ''}`} style={{ width: `${Math.min(100, value)}%` }} />
      <span className="share-text">{n1(value)}%</span>
    </span>
  );
}

/**
 * Passives held at a moment, grouped by item, each with its gold estimate and since when it is held. With compared
 * builds (`builds`: [{ key, name, color, series }], the active one first) they are shown side by side instead.
 */
export function PassivesPanel({ point, series, itemsById, builds = [] }) {
  const read = useItemReader();
  if (!point) return null;
  if (builds.length > 1) {
    const columns = builds.map((b) => ({
      key: b.key, name: b.name, color: b.color,
      list: acquiredPassives(pointItemIds(pointAt(b.series, point.minute)), itemsById),
      since: passiveSince(b.series, itemsById),
    }));
    return (
      <section className="panel">
        <h3>{t('passives.title', { time: mmss(point.minute), level: point.level })}</h3>
        <p className="muted" style={{ marginTop: 0 }}>{t('passives.help', { mid: n0(PASSIVE_GOLD_BY_TIER.tier_medio), full: n0(PASSIVE_GOLD_BY_TIER.aprimorado) })}</p>
        <PassiveCompare columns={columns} />
      </section>
    );
  }
  const list = acquiredPassives(pointItemIds(point), itemsById);
  const since = passiveSince(series, itemsById);
  const byItem = [];
  for (const x of list) {
    const last = byItem[byItem.length - 1];
    if (last && last.itemId === x.itemId) last.passives.push(x);
    else byItem.push({ itemId: x.itemId, itemName: x.itemName, passives: [x] });
  }
  const gold = list.reduce((a, x) => a + x.gold, 0);
  return (
    <section className="panel">
      <h3>{t('passives.title', { time: mmss(point.minute), level: point.level })}</h3>
      <p className="muted" style={{ marginTop: 0 }}>{t('passives.help', { mid: n0(PASSIVE_GOLD_BY_TIER.tier_medio), full: n0(PASSIVE_GOLD_BY_TIER.aprimorado) })}</p>
      <p className="passives-total">{t('passives.total', { n: list.length, gold: n0(gold) })}</p>
      {list.length === 0 && <p className="muted">{t('passives.none')}</p>}
      <ul className="passive-groups">
        {byItem.map((g) => (
          <li key={g.itemId}>
            <button className="link with-icon passive-item item-link" onClick={() => read(g.itemId)} title={t('dock.read', { name: g.itemName })}>
              <ItemIcon id={g.itemId} size={24} />{g.itemName}
            </button>
            <ul>
              {g.passives.map((x) => (
                <li key={x.key} className="passive-entry">
                  <span className="passive-block" style={{ background: passiveColor(x.key) }} aria-hidden="true" />
                  <div>
                    <span className="passive-name">{x.name}</span>
                    <small className="muted"> · {n0(x.gold)} · {t('passives.since', { time: mmss(since.get(x.key) ?? point.minute) })}</small>
                    {x.text && <p className="passive-text"><RichText text={x.text} /></p>}
                  </div>
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </section>
  );
}
