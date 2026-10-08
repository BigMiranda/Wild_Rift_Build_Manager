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

/**
 * Purchase sequence as a strip of tiles, each showing when it is bought, with per-purchase choices:
 * evolved state (items that transform, e.g. Aproximação Invernal -> Fimbulwinter) and conditional effects on/off.
 * A tile can be selected (the shop then shows what it adds to the build) and dragged to another place in the order.
 * Purchases over the item limit at their place are kept but not counted (`ignored`, from the engine).
 * Purchase moments ("back to base") are thin markers: the items after one are bought from its minute on.
 */
export default function BuildBar({
  steps, itemsById, evoPairs, timeline, ignored, moments, selectedIdx, onSelect, onMove, onMoveTo, onRemove, onClear,
  onToggleCond, onToggleEvolved, onAddMoment, onUpdateMoment,
}) {
  const [dragFrom, setDragFrom] = useState(null);
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
        {steps.length > 0 && <button className="link" onClick={onClear}>{t('bar.clear')}</button>}
      </div>
      {steps.length === 0 && <p className="muted">{t('bar.empty')}</p>}
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
          const bag = step ? `\n${t('bar.bagTitle', { gold: n0(step.goldLeft) })}` : '';
          const fullTitle = (implied.length
            ? `${title}\n${t('bar.impliedTitle')}\n${implied.map((x) => `↳ ${mmss(x.minute)} ${x.itemName}`).join('\n')}`
            : title) + bag;
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
              </span>
            </li>
          );
        })}
      </ol>
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
