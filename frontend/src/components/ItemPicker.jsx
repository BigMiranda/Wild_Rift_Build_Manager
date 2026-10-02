import { useMemo, useState } from 'react';
import { n0, pct } from '../format.js';
import ItemIcon from './ItemIcon.jsx';

const CATEGORY_ORDER = ['BOOTS', 'DEFENSE ITEMS', 'DEFENSE ITEMS, SUPPORT ITEMS', 'DEFENSE ITEMS, ACTIVE ITEMS', 'SUPPORT ITEMS',
  'SUPPORT ITEMS, ACTIVE ITEMS', 'MID TIER ITEMS', 'MID TIER ITEMS, ACTIVE ITEMS', 'BASIC ITEMS'];

export function statSummary(item) {
  return item.stats
    .filter((s) => s.type !== "Unstable Passives' Stats")
    .map((s) => {
      if (s.ratio != null && s.refType) return `${s.passive ?? 'passiva'}: ${Math.round(s.ratio * 1000) / 10}% de ${s.refType} → ${s.type}`;
      if (s.value == null) return null;
      return `${s.passive ? s.passive + ': ' : ''}+${n0(s.value)} ${s.type}`;
    })
    .filter(Boolean)
    .join(' · ');
}

export default function ItemPicker({ items, onAdd }) {
  const [showAll, setShowAll] = useState(false);
  const [query, setQuery] = useState('');

  const groups = useMemo(() => {
    const q = query.trim().toLowerCase();
    const visible = items.filter((i) => (showAll || i.relevant) && (!q || i.name.toLowerCase().includes(q)));
    const byCat = new Map();
    for (const i of visible) {
      if (!byCat.has(i.category)) byCat.set(i.category, []);
      byCat.get(i.category).push(i);
    }
    const rank = (c) => (CATEGORY_ORDER.includes(c) ? CATEGORY_ORDER.indexOf(c) : 100);
    return [...byCat.entries()]
      .sort((a, b) => rank(a[0]) - rank(b[0]) || a[0].localeCompare(b[0]))
      .map(([cat, list]) => [cat, list.sort((a, b) => a.name.localeCompare(b.name))]);
  }, [items, showAll, query]);

  return (
    <div>
      <div className="row" style={{ marginBottom: 8 }}>
        <input type="search" placeholder="Buscar item…" value={query} onChange={(e) => setQuery(e.target.value)} style={{ flex: 1 }} aria-label="Buscar item" />
        <label className="check">
          <input type="checkbox" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} />
          mostrar todos os itens
        </label>
      </div>
      <div className="picker-list">
        {groups.length === 0 && <div className="muted" style={{ padding: 8 }}>Nenhum item.</div>}
        {groups.map(([cat, list]) => (
          <div key={cat}>
            <div className="picker-cat">{cat}</div>
            {list.map((i) => (
              <div className="picker-item" key={i.id}>
                <span className="with-icon" title={i.relevanceReason ?? ''}><ItemIcon id={i.id} />{i.name}</span>
                <small title="Custo total · eficiência estática (referência)">
                  {n0(i.cost)}g · {pct(i.staticPct)}
                </small>
                <button className="icon" onClick={() => onAdd(i.id)} aria-label={`Adicionar ${i.name}`} title="Adicionar à sequência">+</button>
                <div className="stats">{statSummary(i)}</div>
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
