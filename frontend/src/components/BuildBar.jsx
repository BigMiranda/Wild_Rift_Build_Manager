import { useState } from 'react';
import { t, violationText } from '../i18n.js';
import { mmss, n0 } from '../format.js';
import { hasConditional } from '../shopModel.js';
import ItemIcon from './ItemIcon.jsx';

/** "6:30" -> 6.5 minutes; plain numbers ("6.5", "6,5") are minutes. Null when it cannot be read. */
export function parseMinutes(text) {
  const s = String(text).trim();
  const m = s.match(/^(\d+):([0-5]?\d)$/);
  if (m) return Number(m[1]) + Number(m[2]) / 60;
  const n = Number(s.replace(',', '.'));
  return s !== '' && Number.isFinite(n) && n >= 0 ? n : null;
}

/** Purchase assumptions a range of the sequence can take (null = the build's). */
export const ASSUME_MODES = ['whole', 'half', 'small'];

/** "Selling X gives R back. To buy Y (cost C, D held in components) you need N in hand. Reached at mm:ss." */
export function saleAccount(s) {
  const st = s?.saleTiming;
  if (!st) return null;
  const text = t('sell.account', {
    sold: s.itemName, refund: n0(st.refund), next: st.nextItemName, cost: n0(st.nextCost),
    have: n0(st.nextDiscount + st.nextTradeIn), need: n0(st.goldNeeded),
  });
  return st.reachedAt != null ? `${text} ${t('sell.reachedAt', { time: mmss(st.reachedAt) })}` : `${text} ${t('sell.never')}`;
}

/** Gold in hand right after the previous purchase, and what is missing then. */
export function saleInHand(s) {
  const st = s?.saleTiming;
  if (!st) return null;
  const missing = st.goldNeeded - st.goldAfterPrevious;
  return t(missing > 0.5 ? 'sell.inHandShort' : 'sell.inHandEnough', {
    time: mmss(st.previousMinute), gold: n0(st.goldAfterPrevious), missing: n0(missing),
  });
}

/**
 * Purchase sequence as a strip of tiles, each showing when it is bought, with per-purchase choices:
 * evolved state (items that transform, e.g. Aproximação Invernal -> Fimbulwinter) and conditional effects on/off.
 * A tile can be selected (the shop then shows what it adds to the build) and dragged to another place in the order.
 * Purchases over the item limit at their place are kept but not counted (`ignored`, from the engine).
 * Purchase moments ("back to base") are thin markers: the items after one are bought from its minute on.
 */
export default function BuildBar({
  steps, itemsById, evoPairs, timeline, ignored, moments, selectedIdx, onSelect, onMove, onMoveTo, onRemove, onClear,
  onToggleCond, onToggleEvolved, onAddMoment, onUpdateMoment, onSell, onSellWhen, onAssumeRange, onCompare,
}) {
  const [dragFrom, setDragFrom] = useState(null);
  const [rangeOpen, setRangeOpen] = useState(false);
  const [dropAt, setDropAt] = useState(null);
  const [editing, setEditing] = useState(null);
  const last = timeline?.filter((x) => !x.implied).at(-1);
  const counted = timeline ? timeline.filter((x) => !x.implied).length : steps.filter((s) => s.kind !== 'moment').length;

  const endDrag = () => { setDragFrom(null); setDropAt(null); };
  const dragProps = (idx) => ({
    draggable: true,
    onDragStart: (e) => { setDragFrom(idx); setEditing(null); e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', String(idx)); },
    onDragOver: (e) => { if (dragFrom != null) { e.preventDefault(); setDropAt(idx); } },
    onDrop: (e) => { e.preventDefault(); if (dragFrom != null && dragFrom !== idx) onMoveTo(dragFrom, idx); endDrag(); },
    onDragEnd: endDrag,
  });
  const dragClasses = (idx) => {
    const cls = [];
    if (idx === dragFrom) cls.push('dragging');
    if (dropAt === idx && dragFrom != null && dragFrom !== idx) cls.push(dragFrom < idx ? 'drop-after' : 'drop-before');
    return cls;
  };

  let number = 0;
  return (
    <div className="buildbar">
      <div className="buildbar-head">
        <h4>{t('bar.title')}</h4>
        {last && <small>{t('bar.summary', { n: counted, gold: n0(last.cumulativeGold), time: mmss(last.minute) })}</small>}
        {steps.length > 1 && <small className="muted">{t('bar.dragHint')}</small>}
        <button className="link" onClick={onAddMoment} title={t('moment.addTitle')}>{t('moment.add')}</button>
        {onAssumeRange && steps.some((s) => !s.kind) && (
          <span className="range-anchor">
            <button className="link" onClick={() => setRangeOpen((o) => !o)} aria-expanded={rangeOpen} title={t('range.addTitle')}>
              {t('range.add')}
            </button>
            {rangeOpen && <RangeEditor steps={steps} itemsById={itemsById} onApply={onAssumeRange} onClose={() => setRangeOpen(false)} />}
          </span>
        )}
        {steps.length > 0 && <button className="link" onClick={onClear}>{t('bar.clear')}</button>}
      </div>
      {steps.length === 0 && <p className="muted">{t('bar.empty')}</p>}
      {onAssumeRange && <RangeList steps={steps} itemsById={itemsById} onRemove={(a, b) => onAssumeRange(a, b, null)} />}
      <ol className="buildbar-list">
        {steps.map((s, idx) => {
          if (s.kind === 'moment') {
            const m = moments?.find((x) => x.position === idx);
            return (
              <li key={idx} className={['buildbar-moment', ...dragClasses(idx), editing === idx ? 'editing' : ''].join(' ')} {...dragProps(idx)}>
                <button
                  className="moment-mark" onClick={() => setEditing(editing === idx ? null : idx)} aria-expanded={editing === idx}
                  title={momentTitle(s, m)} aria-label={momentTitle(s, m)}
                >
                  <span className="moment-time">{m ? mmss(m.minute) : '—'}</span>
                  <span className="moment-dash" aria-hidden="true" />
                  {m && <span className="moment-gold">{n0(m.gold)}</span>}
                </button>
                <button
                  className="icon danger moment-remove" onClick={() => { setEditing(null); onRemove(idx); }}
                  title={t('moment.remove')} aria-label={t('moment.remove')}
                >
                  ✕
                </button>
                {editing === idx && (
                  <MomentEditor
                    step={s} onChange={(patch) => onUpdateMoment(idx, patch)}
                    onRemove={() => { setEditing(null); onRemove(idx); }} onClose={() => setEditing(null)}
                  />
                )}
              </li>
            );
          }
          number += 1;
          if (s.kind === 'sell') {
            const sale = timeline?.find((x) => x.buildIndex === idx && x.sold);
            const notHeld = ignored?.find((x) => x.buildIndex === idx);
            const it = itemsById.get(s.itemId);
            const name = it?.name ?? `item ${s.itemId}`;
            const afford = s.sellWhen === 'afford';
            const timed = sale ?? notHeld;
            const account = saleAccount(timed);
            const title = (notHeld && notHeld.violations?.[0]?.code !== 'saleNeverAffords' ? t('sell.notHeld', { name })
              : notHeld ? violationText(notHeld.violations[0]) : t('sell.title', { name, gold: n0(-(sale?.paidCost ?? 0)) }))
              + (account ? `\n${account}\n${saleInHand(timed)}` : '');
            return (
              <li key={idx} className={['buildbar-item', 'sale', ...dragClasses(idx), notHeld ? 'ignored' : '', idx === selectedIdx ? 'selected' : ''].join(' ')}
                title={title} {...dragProps(idx)}>
                <button className="tile-icon tile-select" onClick={() => onSelect(idx === selectedIdx ? null : idx)}
                  aria-pressed={idx === selectedIdx} aria-label={title}>
                  <ItemIcon id={it ? s.itemId : null} size={56} />
                  <span className="tile-sale">{t('sell.tag')}</span>
                  <span className="tile-cost">{sale ? `+${n0(-sale.paidCost)}` : '—'}</span>
                  {notHeld && <span className="tile-ignored" aria-hidden="true">⊘</span>}
                </button>
                <span className="buildbar-when">
                  {sale ? <>{mmss(sale.minute)} <small>{t('bar.lv', { n: sale.level })}</small></> : <small>{t('bar.ignoredShort')}</small>}
                </span>
                {sale?.saleTiming && <span className="buildbar-bag" title={account}>{t('sell.needShort', { gold: n0(sale.saleTiming.goldNeeded) })}</span>}
                {onSellWhen && (
                  <span className="buildbar-opts">
                    <button className={`opt${afford ? ' on' : ''}`} aria-pressed={afford} title={t('sell.whenTitle')}
                      onClick={() => onSellWhen(idx, afford ? 'after' : 'afford')}>
                      {afford ? t('sell.whenAfford') : t('sell.whenAfter')}
                    </button>
                  </span>
                )}
                <span className="buildbar-actions">
                  <button className="icon" onClick={() => onMove(idx, -1)} disabled={idx === 0} aria-label={t('bar.before', { name })}>‹</button>
                  <button className="icon danger" onClick={() => onRemove(idx)} aria-label={t('bar.remove', { name })}>✕</button>
                  <button className="icon" onClick={() => onMove(idx, 1)} disabled={idx === steps.length - 1} aria-label={t('bar.after', { name })}>›</button>
                </span>
              </li>
            );
          }
          const item = itemsById.get(s.itemId);
          const step = timeline?.find((x) => x.buildIndex === idx && !x.implied);
          const skipped = ignored?.find((x) => x.buildIndex === idx);
          const implied = timeline?.filter((x) => x.buildIndex === idx && x.implied) ?? [];
          const other = evoPairs.get(s.itemId);
          const evolved = item?.section === 'evolucao';
          const name = item?.name ?? `item ${s.itemId}`;
          const title = skipped
            ? `${name}\n${t('bar.ignored', { reason: skipped.violations.map(violationText).join(' · ') })}`
            : step?.violations?.length ? `${name}\n⛔ ${step.violations.map(violationText).join('\n⛔ ')}` : name;
          const bag = (step ? `\n${t('bar.bagTitle', { gold: n0(step.goldLeft) })}` : '')
            + (step?.swappedOut ? `\n⇄ ${t('swap.title', { old: step.swappedOut, credit: n0(step.tradeIn), paid: n0(step.paidCost) })}` : '');
          const assumption = step?.assumption && step.assumption !== 'build' ? step.assumption : null;
          const fullTitle = (implied.length
            ? `${title}\n${t('bar.impliedTitle')}\n${implied.map((x) => `↳ ${mmss(x.minute)} ${x.itemName}`).join('\n')}`
            : title) + bag + (assumption ? `\n${t(`range.stepTitle.${assumption}`)}` : '');
          const cls = ['buildbar-item', ...dragClasses(idx)];
          if (step?.violations?.length) cls.push('blocked');
          if (skipped) cls.push('ignored');
          if (idx === selectedIdx) cls.push('selected');
          return (
            <li key={idx} className={cls.join(' ')} title={fullTitle} {...dragProps(idx)}>
              <button
                className="tile-icon tile-select" onClick={() => onSelect(idx === selectedIdx ? null : idx)}
                aria-pressed={idx === selectedIdx} aria-label={t('bar.select', { name, n: number })}
              >
                <ItemIcon id={item ? s.itemId : null} size={56} />
                <span className="tile-order">{number}</span>
                <span className="tile-cost">{n0(step ? step.paidCost : item?.cost)}</span>
                {skipped && <span className="tile-ignored" aria-hidden="true">⊘</span>}
                {implied.length > 0 && <span className="tile-implied">↳{implied.length}</span>}
                {assumption && <span className={`tile-assume assume-${assumption}`} aria-hidden="true">{t(`range.short.${assumption}`)}</span>}
                {step?.swappedOut && <span className="tile-swap" title={t('swap.title', { old: step.swappedOut, credit: n0(step.tradeIn), paid: n0(step.paidCost) })}>⇄</span>}
              </button>
              <span className="buildbar-when">
                {step ? <>{mmss(step.minute)} <small>{t('bar.lv', { n: step.level })}</small></>
                  : <small>{skipped ? t('bar.ignoredShort') : '—'}</small>}
              </span>
              {step && step.goldLeft >= 1 && <span className="buildbar-bag">{t('bar.bag', { gold: n0(step.goldLeft) })}</span>}
              <span className="buildbar-opts">
                {other && (
                  <button
                    className={`opt${evolved ? ' on' : ''}`} aria-pressed={evolved} onClick={() => onToggleEvolved(idx)}
                    title={t('bar.evolvedTitle', { name: evolved ? item.name : other.name })}
                  >
                    ⟳ {t('bar.evolved')}
                  </button>
                )}
                {step?.swappedOut && (
                  <span className="opt swap-note" title={t('swap.title', { old: step.swappedOut, credit: n0(step.tradeIn), paid: n0(step.paidCost) })}>
                    ⇄ {t('swap.tag')}
                  </span>
                )}
                {hasConditional(item) && (
                  <button
                    className={`opt${s.includeConditional ? ' on' : ''}`} aria-pressed={s.includeConditional}
                    onClick={() => onToggleCond(idx)} title={t('bar.condTitle')}
                  >
                    ◐ {t('bar.cond')}
                  </button>
                )}
              </span>
              <span className="buildbar-actions">
                <button className="icon" onClick={() => onMove(idx, -1)} disabled={idx === 0} aria-label={t('bar.before', { name })}>‹</button>
                <button className="icon danger" onClick={() => onRemove(idx)} aria-label={t('bar.remove', { name })}>✕</button>
                <button className="icon" onClick={() => onMove(idx, 1)} disabled={idx === steps.length - 1} aria-label={t('bar.after', { name })}>›</button>
                {onSell && item && <button className="icon sell" onClick={() => onSell(idx)} title={t('sell.addTitle', { name })} aria-label={t('sell.addTitle', { name })}>$</button>}
                {onCompare && item && <button className="icon" onClick={() => onCompare(idx)} title={t('qc.open', { name })} aria-label={t('qc.open', { name })}>⇄</button>}
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

/** Purchases of the sequence (no moments, no sales) as {idx, number, name}. */
function purchases(steps, itemsById) {
  const out = [];
  let number = 0;
  steps.forEach((s, idx) => {
    if (s.kind === 'moment') return;
    number += 1;
    if (s.kind !== 'sell') out.push({ idx, number, name: itemsById.get(s.itemId)?.name ?? `item ${s.itemId}`, assume: s.assume ?? null });
  });
  return out;
}

/** Ranges of the sequence with their own purchase assumption: consecutive purchases with the same one. */
function RangeList({ steps, itemsById, onRemove }) {
  const ranges = [];
  for (const p of purchases(steps, itemsById)) {
    const last = ranges.at(-1);
    if (p.assume && last && last.assume === p.assume && last.open) {
      last.to = p;
    } else if (p.assume) {
      ranges.push({ assume: p.assume, from: p, to: p, open: true });
    } else if (last) {
      last.open = false;
    }
  }
  if (ranges.length === 0) return null;
  return (
    <ul className="range-list">
      {ranges.map((r) => (
        <li key={r.from.idx} className={`assume-${r.assume}`}>
          <span title={t(`range.stepTitle.${r.assume}`)}>
            {r.from === r.to ? `#${r.from.number}` : `#${r.from.number}–#${r.to.number}`} · {t(`range.mode.${r.assume}`)}
          </span>
          <button className="icon danger" onClick={() => onRemove(r.from.idx, r.to.idx)} aria-label={t('range.remove')} title={t('range.remove')}>✕</button>
        </li>
      ))}
    </ul>
  );
}

/** Popover: from purchase a to purchase b, the assumption that holds there. */
function RangeEditor({ steps, itemsById, onApply, onClose }) {
  const list = purchases(steps, itemsById);
  const [from, setFrom] = useState(list[0]?.idx ?? 0);
  const [to, setTo] = useState(list.at(-1)?.idx ?? 0);
  const [mode, setMode] = useState('whole');
  const option = (p) => <option key={p.idx} value={p.idx}>#{p.number} {p.name}</option>;
  return (
    <div className="moment-editor range-editor" role="dialog" aria-label={t('range.title')}>
      <strong>{t('range.title')}</strong>
      <label className="field">{t('range.from')}
        <select value={from} onChange={(e) => setFrom(Number(e.target.value))}>{list.map(option)}</select>
      </label>
      <label className="field">{t('range.to')}
        <select value={to} onChange={(e) => setTo(Number(e.target.value))}>{list.map(option)}</select>
      </label>
      <label className="field">{t('range.modeLabel')}
        <select value={mode} onChange={(e) => setMode(e.target.value)}>
          <option value="build">{t('range.mode.build')}</option>
          {ASSUME_MODES.map((m) => <option key={m} value={m}>{t(`range.mode.${m}`)}</option>)}
        </select>
      </label>
      <small className="muted">{t('range.help')}</small>
      <div className="spread">
        <button className="link" onClick={onClose}>{t('close')}</button>
        <button className="primary" onClick={() => { onApply(Math.min(from, to), Math.max(from, to), mode === 'build' ? null : mode); onClose(); }}>
          {t('range.apply')}
        </button>
      </div>
    </div>
  );
}

function momentTitle(step, m) {
  const rule = step.atMinute != null
    ? t('moment.atRule', { time: mmss(step.atMinute) })
    : t('moment.afterRule', { time: mmss(step.afterMinutes ?? 0) });
  return m ? `${t('moment.title')}: ${rule}\n${t('moment.row', { time: mmss(m.minute), gold: n0(m.gold) })}` : `${t('moment.title')}: ${rule}`;
}

/** Popover to choose an exact minute or a time after the previous purchase. */
function MomentEditor({ step, onChange, onRemove, onClose }) {
  const exact = step.atMinute != null;
  const value = exact ? step.atMinute : step.afterMinutes ?? 0;
  const [text, setText] = useState(mmss(value));
  const commit = (raw, asExact = exact) => {
    const v = parseMinutes(raw);
    if (v == null) return;
    onChange(asExact ? { atMinute: v, afterMinutes: null } : { atMinute: null, afterMinutes: v });
  };
  return (
    <div className="moment-editor" role="dialog" aria-label={t('moment.title')}>
      <div className="chips">
        <button className={exact ? 'active' : ''} aria-pressed={exact} onClick={() => commit(text, true)}>{t('moment.exact')}</button>
        <button className={!exact ? 'active' : ''} aria-pressed={!exact} onClick={() => commit(text, false)}>{t('moment.after')}</button>
      </div>
      <label className="field">
        {exact ? t('moment.exactLabel') : t('moment.afterLabel')}
        <input
          value={text} placeholder="6:30" inputMode="decimal"
          onChange={(e) => { setText(e.target.value); commit(e.target.value); }}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === 'Escape') onClose(); }}
          aria-invalid={parseMinutes(text) == null}
        />
      </label>
      <div className="spread">
        <button className="link danger" onClick={onRemove}>{t('moment.remove')}</button>
        <button className="link" onClick={onClose}>{t('close')}</button>
      </div>
    </div>
  );
}
