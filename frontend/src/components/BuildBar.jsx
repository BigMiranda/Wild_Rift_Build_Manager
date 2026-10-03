import { t, violationText } from '../i18n.js';
import { mmss, n0 } from '../format.js';
import { hasConditional } from '../shopModel.js';
import ItemIcon from './ItemIcon.jsx';

/**
 * Purchase sequence as a strip of tiles, each showing when it is bought, with per-purchase choices:
 * evolved state (items that transform, e.g. Aproximação Invernal -> Fimbulwinter) and conditional effects on/off.
 */
export default function BuildBar({ steps, itemsById, evoPairs, timeline, onMove, onRemove, onClear, onToggleCond, onToggleEvolved }) {
  const last = timeline?.[timeline.length - 1];
  return (
    <div className="buildbar">
      <div className="buildbar-head">
        <h4>{t('bar.title')}</h4>
        {last && <small>{t('bar.summary', { n: steps.length, gold: n0(last.cumulativeGold), time: mmss(last.minute) })}</small>}
        {steps.length > 0 && <button className="link" onClick={onClear}>{t('bar.clear')}</button>}
      </div>
      {steps.length === 0 && <p className="muted">{t('bar.empty')}</p>}
      <ol className="buildbar-list">
        {steps.map((s, idx) => {
          const item = itemsById.get(s.itemId);
          const step = timeline?.find((x) => x.index === idx);
          const other = evoPairs.get(s.itemId);
          const evolved = item?.section === 'evolucao';
          const name = item?.name ?? `item ${s.itemId}`;
          return (
            <li
              key={idx}
              className={`buildbar-item${step?.violations?.length ? ' blocked' : ''}`}
              title={step?.violations?.length ? `${name}\n⛔ ${step.violations.map(violationText).join('\n⛔ ')}` : name}
            >
              <span className="tile-icon">
                <ItemIcon id={item ? s.itemId : null} size={56} />
                <span className="tile-order">{idx + 1}</span>
                <span className="tile-cost">{n0(step ? step.paidCost : item?.cost)}</span>
              </span>
              <span className="buildbar-when">
                {step ? <>{mmss(step.minute)} <small>{t('bar.lv', { n: step.level })}</small></> : <small>—</small>}
              </span>
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
