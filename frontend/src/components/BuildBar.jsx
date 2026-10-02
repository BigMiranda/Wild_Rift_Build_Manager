import { mmss, n0 } from '../format.js';
import ItemIcon from './ItemIcon.jsx';

/** Purchase sequence as a strip of tiles, each showing when it is bought. */
export default function BuildBar({ itemIds, itemsById, steps, onMove, onRemove, onClear }) {
  const last = steps?.[steps.length - 1];
  return (
    <div className="buildbar">
      <div className="buildbar-head">
        <h4>Sequência de compras</h4>
        {last && <small>{itemIds.length} compras · {n0(last.cumulativeGold)} de ouro · completa em {mmss(last.minute)}</small>}
        {itemIds.length > 0 && <button className="link" onClick={onClear}>limpar</button>}
      </div>
      {itemIds.length === 0 && <p className="muted">Selecione um item na loja e clique em “Adicionar à build” (ou dê duplo clique no ícone).</p>}
      <ol className="buildbar-list">
        {itemIds.map((id, idx) => {
          const item = itemsById.get(id);
          const step = steps?.find((s) => s.index === idx);
          return (
            <li key={`${idx}-${id}`} className="buildbar-item" title={item?.name ?? `item ${id} (removido)`}>
              <span className="tile-icon">
                <ItemIcon id={item ? id : null} size={56} />
                <span className="tile-order">{idx + 1}</span>
                <span className="tile-cost">{n0(step ? step.paidCost : item?.cost)}</span>
              </span>
              <span className="buildbar-when">
                {step ? <>{mmss(step.minute)} <small>nv {step.level}</small></> : <small>—</small>}
              </span>
              <span className="buildbar-actions">
                <button className="icon" onClick={() => onMove(idx, -1)} disabled={idx === 0} aria-label={`Mover ${item?.name ?? 'item'} para antes`}>‹</button>
                <button className="icon danger" onClick={() => onRemove(idx)} aria-label={`Remover ${item?.name ?? 'item'}`}>✕</button>
                <button className="icon" onClick={() => onMove(idx, 1)} disabled={idx === itemIds.length - 1} aria-label={`Mover ${item?.name ?? 'item'} para depois`}>›</button>
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
