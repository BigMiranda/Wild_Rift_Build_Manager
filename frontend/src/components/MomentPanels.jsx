import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { useEffectValues } from '../effectValues.js';
import { statLabel, t } from '../i18n.js';
import { DERIVED_STATS, EFFECTIVE_STATS, PERCENT_STATS, SHEET_STATS, extraStats, mmss, n0, n1, pct, statOf, statValue } from '../format.js';
import ItemIcon from './ItemIcon.jsx';
import { useItemReader } from './BuildDock.jsx';
import RichText from './RichText.jsx';
import { StatIcon, statColor } from './StatIcon.jsx';
import PassiveCompare from './PassiveCompare.jsx';
import { findRune, runeIcon } from '../runes.js';
import { effectiveGold } from '../effective.js';
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
        {extraStats(point, SHEET_STATS).map((x) => (
          <div key={x.stat} className="sheet-row">
            <span className="sheet-label" style={{ color: statColor(x.stat) }}><StatIcon stat={x.stat} />{statLabel(x.stat)}</span>
            <span className="sheet-value">{statValue(x.stat, x.total)}</span>
          </div>
        ))}
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
  const eff = v('Max Health') + v('Shield') + v('Heal');
  if (v('Magic Shield') > 0) rows.unshift({ key: 'effM', stat: 'Magic Shield', label: t('sheet.effHealthMagic'), value: n0(eff + v('Magic Shield')) });
  if (v('Physical Shield') > 0) rows.unshift({ key: 'effP', stat: 'Physical Shield', label: t('sheet.effHealthPhysical'), value: n0(eff + v('Physical Shield')) });
  if (eff > v('Max Health') || v('Magic Shield') + v('Physical Shield') > 0) {
    rows.unshift({ key: 'eff', stat: 'Max Health', label: t('sheet.effHealth'), value: n0(eff), strong: true });
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
export function RelevanceReport({ point, prices, itemsById, draft, overlay }) {
  const read = useItemReader();
  const [passOpen, setPassOpen] = useState(false);
  const [enabled, setEnabled] = useState([]);
  const passed = usePassedRelevance(passOpen ? draft : null, point?.minute, enabled);
  if (!point) return null;
  const toggle = (key) => setEnabled((e) => (e.includes(key) ? e.filter((k) => k !== key) : [...e, key]));
  const passing = passOpen && enabled.length > 0 && passed.rel;
  const forgeGold = Object.entries(point.forge ?? {}).reduce((sum, [k, v]) => sum + v * (prices[k] ?? 0), 0);
  // With the passive values on, a passive without stats counts as in the effective gold; by tier the report is the stats'.
  const eff = overlay ? effectiveGold(point, itemsById, prices, overlay) : null;
  const rows = (point.contributions ?? []).map((c) => {
    const x = eff?.items.find((i) => i.purchaseIndex === c.purchaseIndex);
    const passiveGold = x ? x.passiveGold : c.passiveGold;
    return { ...c, passiveGold, gold: c.flatGold + passiveGold, passiveList: x?.passives };
  });
  const grand = rows.reduce((s, r) => s + r.gold, 0) + forgeGold;
  rows.sort((a, b) => b.gold - a.gold);
  const share = (g) => (grand > 0 ? (g / grand) * 100 : 0);

  return (
    <section className="panel">
      <h3>{t('report.title', { time: mmss(point.minute) })}</h3>
      <p className="muted" style={{ marginTop: 0 }}>{t('report.help')}</p>
      {draft && rows.length > 0 && (
        <div className="pass-sources">
          <label className="check" title={t('report.passHelp')}>
            <input type="checkbox" checked={passOpen} onChange={(e) => setPassOpen(e.target.checked)} />
            {t('report.pass')}
          </label>
          {passOpen && (
            <div className="chips" role="group" aria-label={t('report.pass')}>
              {(passed.rel?.sources ?? []).map((s) => (
                <button key={s.key} className={enabled.includes(s.key) ? 'active' : ''} aria-pressed={enabled.includes(s.key)}
                  onClick={() => toggle(s.key)} title={t('report.sourceTitle', { gold: n0(s.gold) })}>
                  {sourceLabel(s)} <small className="muted">{n0(s.gold)}</small>
                </button>
              ))}
              {!passed.rel && <small className="muted">{t('compare.calculating')}</small>}
              {passed.rel && passed.rel.sources.length === 0 && <small className="muted">{t('report.noSources')}</small>}
            </div>
          )}
        </div>
      )}
      {rows.length === 0 && <p className="muted">{t('report.empty')}</p>}
      {passing && <PassedTable rel={passed.rel} stale={passed.stale} itemsById={itemsById} read={read} />}
      {rows.length > 0 && !passing && (
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
                    <td title={r.passiveList?.map((p) => `${p.name}: ${n0(p.gold)}${p.detail ? ` (${p.detail})` : ''}`).join('\n')}>{r.passiveGold ? n0(r.passiveGold) : '—'}</td>
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

/** The relevance report from the backend with `enabled` sources passed on; the last one stays (stale) while loading. */
function usePassedRelevance(draft, minute, enabled) {
  const [loaded, setLoaded] = useState({ key: null, rel: null });
  const key = draft && minute != null ? JSON.stringify([draft, minute, enabled]) : null;
  useEffect(() => {
    if (!key) return undefined;
    let live = true;
    const timer = setTimeout(() => {
      api.post('/api/relevance', { build: draft, minute, sources: enabled })
        .then((rel) => { if (live) setLoaded({ key, rel }); })
        .catch(() => { if (live) setLoaded({ key, rel: null }); });
    }, 200);
    return () => { live = false; clearTimeout(timer); };
  }, [key]);
  return { rel: loaded.rel, stale: loaded.key !== key };
}

const sourceLabel = (s) => (s.forge ? t('report.forge') : `${s.owner}: ${s.passive}`);
const shareGold = (m) => Object.values(m ?? {}).reduce((a, sh) => a + sh.gold, 0);
const shareStats = (m) => {
  const out = {};
  for (const sh of Object.values(m ?? {})) for (const [k, v] of Object.entries(sh.stats)) out[k] = (out[k] ?? 0) + v;
  return out;
};

/** "includes 312 from the Living Forge, 46 from Colete Espinhoso" (by source, largest first). */
function receivedText(inherited, sources) {
  const byKey = new Map(sources.map((s) => [s.key, s]));
  const parts = Object.entries(inherited ?? {}).filter(([, sh]) => Math.abs(sh.gold) >= 0.5).sort((a, b) => b[1].gold - a[1].gold)
    .map(([k, sh]) => t('report.fromSource', { gold: n0(sh.gold), source: byKey.has(k) ? sourceLabel(byKey.get(k)) : k }));
  return parts.length ? t('report.includes', { list: parts.join(', ') }) : null;
}

/** Relevance with sources passed on: own value, what is received from passives, total (and efficiency on it). */
function PassedTable({ rel, stale, itemsById, read }) {
  const [open, setOpen] = useState(() => new Set());
  const flip = (k) => setOpen((o) => { const n = new Set(o); if (n.has(k)) n.delete(k); else n.add(k); return n; });
  const rows = rel.contributions.map((c) => {
    const own = c.flatGold + c.passiveGold - shareGold(c.repassed);
    const recv = shareGold(c.inherited);
    return { key: `i${c.purchaseIndex}`, c, own, recv, total: own + recv };
  });
  const forgeOwn = rel.forgeGold - (rel.forgeRepassed?.gold ?? 0);
  const forgeRecv = shareGold(rel.forgeInherited);
  if (rel.forgeGold > 0) rows.push({ key: 'forge', forge: true, own: forgeOwn, recv: forgeRecv, total: forgeOwn + forgeRecv });
  const champRecv = shareGold(rel.champion);
  if (Math.abs(champRecv) >= 0.5) rows.push({ key: 'champ', champ: true, own: 0, recv: champRecv, total: champRecv });
  rows.sort((a, b) => b.total - a.total);
  const share = (g) => (rel.grand > 0 ? (g / rel.grand) * 100 : 0);
  return (
    <div className={`table-wrap${stale ? ' stale' : ''}`}>
      <table className="data report">
        <thead>
          <tr>
            <th className="l">{t('report.item')}</th>
            <th>{t('report.own')}</th>
            <th>{t('report.received')}</th>
            <th>{t('report.total')}</th>
            <th className="l share-col">{t('report.share')}</th>
            <th>{t('report.eff')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => {
            const cost = r.c ? itemsById.get(r.c.itemId)?.cost : null;
            const text = r.c ? receivedText(r.c.inherited, rel.sources) : r.forge ? receivedText(rel.forgeInherited, rel.sources)
              : receivedText(rel.champion, rel.sources);
            const passedOn = r.c ? Object.keys(r.c.repassed ?? {}) : r.forge && rel.forgeRepassed ? ['forge'] : [];
            const stats = r.c ? shareStats(r.c.inherited) : r.forge ? shareStats(rel.forgeInherited) : shareStats(rel.champion);
            return [
              <tr key={r.key} className="report-item clickable" onClick={() => flip(r.key)} aria-expanded={open.has(r.key)}>
                <td className="l">
                  <span className="with-icon">
                    {r.forge ? <span className="passive-name">{t('report.forge')} · {pct(rel.forgePct * 100)}</span>
                      : r.champ ? <span className="passive-name"><span className="champ-tag">◆</span>{t('report.championBase')}</span>
                      : r.c.champion ? <><span className="champ-tag">◆</span>{t('champ.effect')} {r.c.itemName}</>
                      : r.c.rune ? <><img src={runeIcon(findRune(r.c.itemName))} alt="" width={24} height={24} className="rune-img on" />{t('runes.rune')} {r.c.itemName}</>
                      : (
                        <button className="link item-link" onClick={(e) => { e.stopPropagation(); read(r.c.itemId); }} title={t('dock.read', { name: r.c.itemName })}>
                          <ItemIcon id={r.c.itemId} size={24} />#{r.c.number}{r.c.implied ? '↳' : ''} {r.c.itemName}
                        </button>
                      )}
                    {passedOn.length > 0 && <small className="cond">{t('report.passedOn')}</small>}
                  </span>
                </td>
                <td>{n0(r.own)}</td>
                <td>{Math.abs(r.recv) >= 0.5 ? `+${n0(r.recv)}` : '—'}</td>
                <td><strong>{n0(r.total)}</strong></td>
                <td className="l share-col"><ShareBar value={share(r.total)} /></td>
                <td>{cost ? pct((r.total / cost) * 100) : '—'}</td>
              </tr>,
              open.has(r.key) && (
                <tr key={`${r.key}d`} className="report-passive">
                  <td className="l" colSpan={6}>
                    {text ?? <span className="muted">{t('report.nothingReceived')}</span>}
                    {Object.keys(stats).length > 0 && <> · <StatList stats={stats} /></>}
                    {passedOn.length > 0 && (
                      <div className="muted">
                        {t('report.passedOnDetail', {
                          list: passedOn.map((k) => {
                            const s = rel.sources.find((x) => x.key === k);
                            const g = r.c ? r.c.repassed[k].gold : rel.forgeRepassed.gold;
                            return `${s ? sourceLabel(s) : k} (${n0(g)})`;
                          }).join(', '),
                        })}
                      </div>
                    )}
                  </td>
                </tr>
              ),
            ];
          })}
          <tr className="report-total">
            <td className="l"><strong>{t('report.grand')}</strong></td>
            <td /><td /><td><strong>{n0(rel.grand)}</strong></td><td /><td />
          </tr>
        </tbody>
      </table>
      <small className="muted">{t('report.passNote')}</small>
    </div>
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
/** One effect's value line: the number in the damage type's color. */
export function EffectValue({ v }) {
  return (
    <li className={`effect-value${v.type ? ` dmg-${v.type}` : ''}`}>
      {v.text}
      {v.detail && <small className="effect-detail">{v.detail}</small>}
    </li>
  );
}

export function PassivesPanel({ point, series, itemsById, builds = [], draft }) {
  const read = useItemReader();
  const { values, stale } = useEffectValues(builds.length > 1 ? null : draft, point?.minute);
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
  const itemNames = new Set(byItem.map((g) => g.itemName));
  const valuesOf = (name) => (values ?? []).filter((v) => v.source === name);
  const others = [...new Set((values ?? []).map((v) => v.source).filter((s) => !itemNames.has(s)))];
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
            {valuesOf(g.itemName).length > 0 && (
              <ul className={`effect-values${stale ? ' stale' : ''}`} title={t('passives.valuesTitle')}>
                {valuesOf(g.itemName).map((v, k) => <EffectValue key={k} v={v} />)}
              </ul>
            )}
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
      {others.length > 0 && (
        <>
          <h4>{t('passives.otherValues')}</h4>
          <ul className="passive-groups">
            {others.map((name) => (
              <li key={name}>
                <span className="with-icon passive-item">{findRune(name) && <img src={runeIcon(findRune(name))} alt="" width={22} height={22} className="rune-img on" />}{name}</span>
                <ul className={`effect-values${stale ? ' stale' : ''}`}>{valuesOf(name).map((v, k) => <EffectValue key={k} v={v} />)}</ul>
              </li>
            ))}
          </ul>
        </>
      )}
      {values && <p className="muted small">{t('passives.valuesHelp')}</p>}
    </section>
  );
}
