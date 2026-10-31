import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import { statLabel, t } from '../i18n.js';
import { mmss, n0, n1, statValue } from '../format.js';
import { reportJobs, runJobs, weightedIndex } from '../effectiveness.js';
import EffectPicker, { applied } from './EffectPicker.jsx';
import ItemIcon from './ItemIcon.jsx';
import MinuteInput from './MinuteInput.jsx';
import { CandidatePicker } from './ShopPicker.jsx';

export const MAX_COMPARED = 4;
const CRITERIA = ['mista', 'fisica', 'magica', 'ouro', 'indice'];

/** The number a criterion reads from a card (the basic index comes from the optional run). */
const measure = (c, criterion, basic) => {
  if (c.ignored) return null;
  if (criterion === 'fisica') return c.effectivePhysical;
  if (criterion === 'magica') return c.effectiveMagic;
  if (criterion === 'ouro') return c.efficiency;
  if (criterion === 'indice') return basic?.[c.itemId] ?? null;
  return c.effectiveMixed;
};

/** The rows of the cards: label, value, how to show it and which way is better. */
const ROWS = [
  { key: 'cost', get: (c) => c.cost, show: n0, low: true },
  { key: 'ready', get: (c) => c.readyAt, show: mmss, low: true },
  { key: 'next', get: (c) => c.nextDelay, show: (v) => delayText(v), low: true },
  { key: 'last', get: (c) => c.lastDelay, show: (v) => delayText(v), low: true },
  { key: 'mixed', get: (c) => c.effectiveMixed, show: n0, was: (c) => c.plain?.effectiveHpMixed },
  { key: 'physical', get: (c) => c.effectivePhysical, show: n0, was: (c) => c.plain?.effectiveHpPhysical },
  { key: 'magic', get: (c) => c.effectiveMagic, show: n0, was: (c) => c.plain?.effectiveHpMagic },
  { key: 'gold', get: (c) => c.goldValue, show: n0 },
  { key: 'efficiency', get: (c) => c.efficiency, show: (v) => `${n0(v)}%` },
];

/** "12.590 (−11%)": a value under the effects, with the change from the value without them. */
const change = (now, was) => (was > 0 && Math.abs(now - was) >= 0.5 ? ` (${now >= was ? '+' : '−'}${n0(Math.abs(100 * (now / was - 1)))}%)` : '');

/** What the effects did to this candidate: resistances, the start's shields and healing, without → under. */
function UnderEffects({ c }) {
  const { under: u, plain: p } = c;
  const rows = [
    { k: 'armor', from: p.armor, to: u.effectiveArmor, title: u.armorReasons.join(' · ') },
    { k: 'mr', from: p.magicResist, to: u.effectiveMagicResist, title: u.magicReasons.join(' · ') },
    { k: 'shield', from: u.shields, to: u.shieldsAfterCut, title: u.shieldCutBy },
  ];
  return (
    <>
      <h5>{t('qc.underTitle')}</h5>
      <ul className="qc-list">
        {rows.map((r) => (
          <li key={r.k} title={r.title || undefined} className={Math.abs(r.from - r.to) >= 0.5 ? 'qc-hit' : ''}>
            {t(`qc.under.${r.k}`)}: {n0(r.from)}{Math.abs(r.from - r.to) >= 0.5 ? ` → ${n0(r.to)}` : ''}
          </li>
        ))}
        {u.healingKept < 1 && <li className="qc-hit" title={u.healCutBy ?? undefined}>{t('qc.under.heal', { pct: n0(100 * u.healingKept) })}</li>}
      </ul>
    </>
  );
}

const delayText = (v) => (Math.abs(v) < 0.005 ? '=' : `${v > 0 ? '+' : '−'}${n1(Math.abs(v))} min`);

/** "X rende +9% ... e fica pronto 0,4 min antes; Y rende +6%", against the first card. */
function verdict(cards, criterion, basic, minute) {
  const ref = cards[0];
  const base = ref ? measure(ref, criterion, basic) : null;
  if (!base) return null;
  const rest = cards.slice(1).map((c) => ({ c, v: measure(c, criterion, basic) })).filter((x) => x.v != null)
    .sort((a, b) => b.v - a.v);
  if (rest.length === 0) return null;
  const pct = (v) => `${v >= base ? '+' : '−'}${n0(Math.abs(100 * (v / base - 1)))}%`;
  const crit = t(`qc.crit.${criterion}`);
  const time = mmss(minute);
  const [first, ...others] = rest;
  const dt = (first.c.readyAt ?? 0) - (ref.readyAt ?? 0);
  const when = Math.abs(dt) < 0.05 ? '' : t(dt < 0 ? 'qc.readySooner' : 'qc.readyLater', { min: n1(Math.abs(dt)) });
  const tail = others.map((x) => t('qc.alsoGives', { item: x.c.name, pct: pct(x.v) })).join('; ');
  return `${t('qc.verdict', { item: first.c.name, pct: pct(first.v), crit, time })}${when}${tail ? `; ${tail}` : ''}.`;
}

/**
 * The planner's quick item comparison: the candidates in one purchase (the item there comes first, as the reference)
 * or as the next purchase, read in the timeline with the shop's rules, without a fight (the optional basic index runs
 * the effectiveness report). The answer comes on its own as anything changes.
 */
export default function QuickCompare({ build, slot, atMinute, ids, onIds, onClose, items, itemsById }) {
  const [criterion, setCriterion] = useState('mista');
  const [share, setShare] = useState(50);
  const [minute, setMinute] = useState(null);
  const [chosen, setChosen] = useState([]);
  const [fxOpen, setFxOpen] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [basic, setBasic] = useState(null);
  const [basicBusy, setBasicBusy] = useState(null);
  const [suggested, setSuggested] = useState(null);
  const seq = useRef(0);
  const effects = useMemo(() => applied(chosen), [chosen]);
  const ask = useMemo(() => ({
    build, slot, atMinute: slot == null ? atMinute : null, candidates: ids, minute, physicalShare: share / 100, effects,
  }), [build, slot, atMinute, ids, minute, share, effects]);
  const askKey = JSON.stringify(ask);

  useEffect(() => {
    const mine = ++seq.current;
    setBasic(null);
    setSuggested(null);
    if (slot == null && ids.length === 0) {
      setResult(null);
      return undefined;
    }
    setBusy(true);
    const timer = setTimeout(() => {
      api.post('/api/quick/compare', ask).then((r) => {
        if (mine !== seq.current) return;
        setResult(r);
        setError(null);
        setBusy(false);
      }).catch((e) => {
        if (mine !== seq.current) return;
        setError(e.message);
        setBusy(false);
      });
    }, 150);
    return () => clearTimeout(timer);
    // askKey stands for everything the question holds
  }, [askKey]);

  const runBasic = async () => {
    setBasicBusy({ done: 0, total: 0 });
    setError(null);
    try {
      const report = await api.get('/api/effect/reports/basico');
      const cards = result.cards.filter((c) => !c.ignored);
      const jobs = cards.flatMap((c) => reportJobs(report, false).map((j) => ({
        ...j, variant: { kind: 'candidato', slot, atMinute: slot == null ? atMinute : null, candidateId: c.itemId },
      })));
      const out = await runJobs({ build, minute: result.minute }, jobs, {
        size: 12, onProgress: (done, total) => setBasicBusy({ done, total }),
      });
      const n = report.tests.length;
      const idx = {};
      cards.forEach((c, i) => { idx[c.itemId] = weightedIndex(report, out.slice(i * n, i * n + n)); });
      setBasic(idx);
      setCriterion('indice');
    } catch (e) {
      setError(e.message);
    }
    setBasicBusy(null);
  };

  const suggest = async () => {
    setError(null);
    setSuggested({ loading: true });
    try {
      const tab = slot == null ? itemsById.get(ids[0])?.tabs?.[0] : null;
      const r = await api.post('/api/quick/suggest', { ...ask, tab, criterion: criterion === 'indice' ? 'mista' : criterion });
      setSuggested(r);
    } catch (e) {
      setError(e.message);
      setSuggested(null);
    }
  };

  const cards = result?.cards ?? [];
  const best = (row) => {
    const vals = cards.map((c) => (c.ignored ? null : row.get(c))).filter((v) => v != null);
    if (vals.length < 2) return null;
    return row.low ? Math.min(...vals) : Math.max(...vals);
  };
  const line = cards.length > 1 ? verdict(cards, criterion, basic, result.minute) : null;
  const add = (id) => { if (!ids.includes(id) && ids.length < MAX_COMPARED) onIds([...ids, id]); };
  const noCandidates = slot == null && ids.length === 0;
  return (
    <section className="qc" aria-label={t('qc.title')}>
      <header className="row qc-head">
        <h3>{slot == null ? t('qc.titleNext') : t('qc.titleSlot', { n: slot + 1 })}</h3>
        {busy && <small className="muted">{t('qc.loading')}</small>}
        {result && !busy && <small className="muted">{t('qc.time', { ms: n0(result.ms) })}</small>}
        <button className="icon reader-close" onClick={onClose} aria-label={t('close')}>✕</button>
      </header>
      {noCandidates && <p className="muted">{t('qc.empty')}</p>}
      <div className="row qc-controls">
        <label className="inline">{t('qc.criterion')}
          <select value={criterion} onChange={(e) => setCriterion(e.target.value)}>
            {CRITERIA.map((c) => <option key={c} value={c}>{t(`qc.opt.${c}`)}</option>)}
          </select>
        </label>
        <label className="inline" title={t('qc.shareHelp')}>{t('qc.share')}
          <input type="range" min="0" max="100" step="5" value={share} onChange={(e) => setShare(Number(e.target.value))} /> {share}%
        </label>
        <label className="inline" title={t('qc.minuteHelp')}>{t('qc.minute')}
          <MinuteInput value={minute} onChange={setMinute} placeholder={result ? mmss(result.minute) : '—'} style={{ width: 70 }} />
        </label>
        <button className={`opt${fxOpen || chosen.length ? ' on' : ''}`} onClick={() => setFxOpen((o) => !o)}>
          {t('qc.under')}{chosen.length > 0 ? ` (${chosen.length})` : ''}
        </button>
        <CandidatePicker build={build} slot={slot} atMinute={atMinute} value={ids} onChange={onIds} max={MAX_COMPARED} items={items} />
        <button onClick={suggest} disabled={noCandidates || suggested?.loading}>{t('qc.suggest')}</button>
        <button onClick={runBasic} disabled={!cards.length || !!basicBusy}>
          {basicBusy ? t('qc.basicRunning', { done: basicBusy.done, total: basicBusy.total }) : t('qc.basic')}
        </button>
      </div>
      {fxOpen && (
        <>
          <p className="muted small">{t('qc.underHelp')}</p>
          <EffectPicker chosen={chosen} onChange={setChosen} />
        </>
      )}
      {error && <p className="error">{error}</p>}
      {line && <p className="qc-verdict">{line}</p>}
      {suggested && !suggested.loading && (
        <div className="qc-suggest">
          <small className="muted">{t('qc.suggested', { time: mmss(suggested.minute), ms: n0(suggested.ms) })}</small>
          {suggested.cards.length === 0 && <small className="muted"> {t('qc.noSuggestion')}</small>}
          <div className="chips">
            {suggested.cards.map((c) => (
              <button key={c.itemId} className="chip-item" onClick={() => add(c.itemId)} disabled={ids.includes(c.itemId) || ids.length >= MAX_COMPARED}
                title={t('qc.addSuggestion')}>
                <ItemIcon id={c.itemId} size={20} /> {c.name} <small className="muted">{c.gainPct == null ? `${n0(c.efficiency)}%` : `${c.gainPct >= 0 ? '+' : '−'}${n0(Math.abs(c.gainPct))}%`}</small>
              </button>
            ))}
          </div>
        </div>
      )}
      {cards.length > 0 && (
        <div className="qc-cards">
          {cards.map((c) => (
            <article key={c.itemId} className={`qc-card${c.reference ? ' reference' : ''}`}>
              <header>
                <ItemIcon id={c.itemId} size={36} />
                <div>
                  <strong>{c.name}</strong>
                  <small className="muted">{c.reference ? t('qc.reference') : ''}</small>
                </div>
                {!c.reference && ids.includes(c.itemId) && (
                  <button className="icon" onClick={() => onIds(ids.filter((x) => x !== c.itemId))} aria-label={t('qc.remove')}>✕</button>
                )}
              </header>
              {c.ignored ? <p className="error">{t('qc.doesNotFit')}</p> : (
                <>
                  {c.violations.length > 0 && <p className="error">{t('qc.breaksRules')}</p>}
                  <dl>
                    {ROWS.map((r) => {
                      const v = r.get(c);
                      if (v == null) return null;
                      const b = best(r);
                      return (
                        <div key={r.key} className={b != null && Math.abs(v - b) < 1e-9 ? 'best' : ''}>
                          <dt>{t(`qc.row.${r.key}`, r.key === 'next' ? { item: c.nextItem ?? '' } : {})}</dt><dd>{r.show(v)}{r.was?.(c) != null && <small className="qc-hit">{change(v, r.was(c))}</small>}</dd>
                        </div>
                      );
                    })}
                    {basic?.[c.itemId] != null && <div><dt>{t('qc.row.basic')}</dt><dd>{n0(basic[c.itemId])}</dd></div>}
                  </dl>
                  {c.under && <UnderEffects c={c} />}
                  {c.waitedUntil != null && <small className="qc-rule">{t('qc.waited', { time: mmss(c.waitedUntil) })}</small>}
                  {!c.held && <small className="qc-rule">{t('qc.notHeld', { time: mmss(result.minute) })}</small>}
                  <StatList title={t('qc.statsReady', { time: mmss(c.readyAt) })} stats={c.statsAtReady} />
                  <StatList title={t('qc.statsAt', { time: mmss(result.minute) })} stats={c.statsAtMinute} />
                  {c.passives.length > 0 && (
                    <>
                      <h5>{t('qc.passives')}</h5>
                      <ul className="qc-list">{c.passives.map((p, i) => <li key={i}>{p.text}</li>)}</ul>
                    </>
                  )}
                </>
              )}
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function StatList({ title, stats }) {
  const list = Object.entries(stats ?? {}).filter(([, v]) => Math.abs(v) > 0.05);
  if (list.length === 0) return null;
  return (
    <>
      <h5>{title}</h5>
      <ul className="qc-list">
        {list.map(([k, v]) => <li key={k}>+{statValue(k, v)} {statLabel(k)}</li>)}
      </ul>
    </>
  );
}
