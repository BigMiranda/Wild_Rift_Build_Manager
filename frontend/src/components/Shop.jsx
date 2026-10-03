import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { mmss, n0, pct, statLabel } from '../format.js';
import { DEFAULT_TAB, MARKERS, TABS, buildTiles, describeStats, sectionsFor, variantLabel } from '../shopModel.js';
import ItemIcon from './ItemIcon.jsx';

/**
 * Wild Rift style shop: tab rail, icon grid and a detail panel.
 * `buildPayload` is the current build as the API expects it (or null when it cannot be calculated yet); it is used
 * to preview what the selected item would be worth if bought next.
 */
export default function Shop({ items, buildPayload, ownedIds, onAdd }) {
  const [tab, setTab] = useState(DEFAULT_TAB);
  const [query, setQuery] = useState('');
  const [selectedKey, setSelectedKey] = useState(null);
  const [variantId, setVariantId] = useState(null);

  const tiles = useMemo(() => buildTiles(items), [items]);
  const sections = useMemo(() => sectionsFor(tiles, tab, query), [tiles, tab, query]);
  const itemsById = useMemo(() => new Map(items.map((i) => [i.id, i])), [items]);
  const ownedByTile = useMemo(() => {
    const counts = new Map();
    for (const id of ownedIds) {
      const key = itemsById.get(id)?.group;
      if (key) counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return counts;
  }, [ownedIds, itemsById]);

  const selected = tiles.find((t) => t.key === selectedKey) ?? null;
  const variant = selected ? selected.variants.find((v) => v.id === variantId) ?? selected.base : null;

  const open = (item) => {
    const tile = tiles.find((t) => t.key === (item.group ?? item.name));
    if (tile) {
      setSelectedKey(tile.key);
      setVariantId(item.id);
    }
  };

  return (
    <div className="shop">
      <nav className="shop-rail" aria-label="Abas da loja">
        {TABS.map((t) => (
          <button key={t} className={!query && tab === t ? 'active' : ''} onClick={() => { setTab(t); setQuery(''); }}>
            {t}
          </button>
        ))}
      </nav>

      <div className="shop-grid-wrap">
        <input
          type="search" className="shop-search" placeholder="Buscar em todas as abas…" value={query}
          onChange={(e) => setQuery(e.target.value)} aria-label="Buscar item"
        />
        <div className="shop-grid-scroll">
          {sections.length === 0 && <p className="muted">Nenhum item encontrado.</p>}
          {sections.map((s) => (
            <section key={s.key}>
              <h4 className="shop-section">{s.label}</h4>
              <div className="shop-grid">
                {s.tiles.map((t) => {
                  const marker = MARKERS[t.base.marker];
                  return (
                    <button
                      key={t.key}
                      className={`tile${t.key === selectedKey ? ' selected' : ''}`}
                      onClick={() => { setSelectedKey(t.key); setVariantId(t.preferredId); }}
                      onDoubleClick={() => onAdd(t.key === selectedKey && variant ? variant.id : t.preferredId)}
                      title={`${t.name} — duplo clique adiciona à build`}
                    >
                      <span className="tile-icon">
                        <ItemIcon id={t.iconId} size={64} />
                        <span className="tile-cost">{t.cost}</span>
                        {ownedByTile.has(t.key) && <span className="tile-owned" aria-label="Já está na build">×{ownedByTile.get(t.key)}</span>}
                        {t.base.active && <span className="tile-active" title="Item ativável" aria-label="Item ativável">◆</span>}
                        {marker && <span className={`tile-marker ${t.base.marker}`} title={marker.label} aria-label={marker.label}>{marker.badge}</span>}
                      </span>
                      <span className="tile-name">{t.name}</span>
                    </button>
                  );
                })}
              </div>
            </section>
          ))}
        </div>
      </div>

      <aside className="shop-detail" aria-live="polite">
        {!selected && <p className="shop-empty">Selecione 1 item à esquerda</p>}
        {selected && variant && (
          <ItemDetail
            tile={selected} variant={variant} items={items} itemsById={itemsById} buildPayload={buildPayload}
            onVariant={setVariantId} onAdd={() => onAdd(variant.id)} onOpen={open}
          />
        )}
      </aside>
    </div>
  );
}

function ItemDetail({ tile, variant, items, itemsById, buildPayload, onVariant, onAdd, onOpen }) {
  const { flat, modeled } = describeStats(variant);
  const components = variant.components.map((c) => ({ item: itemsById.get(c.itemId), quantity: c.quantity })).filter((c) => c.item);
  const buildsInto = items.filter((i) => i.section !== 'evolucao' && i.components.some((c) => c.itemId === tile.base.id));
  const preview = usePurchasePreview(buildPayload, variant.id);
  const marker = MARKERS[tile.base.marker];

  return (
    <>
      <header className="detail-head">
        <ItemIcon id={tile.iconId} size={56} />
        <div>
          <h3>{variant.name}</h3>
          <div className="gold">{n0(variant.cost)} de ouro{tile.base.active ? ' · ativável' : ''}</div>
          {marker && <small className={`marker-text ${tile.base.marker}`}>{marker.label}</small>}
        </div>
      </header>

      {tile.variants.length > 1 && (
        <div className="detail-block">
          <h4>Estado</h4>
          <div className="chips">
            {tile.variants.map((v) => (
              <button key={v.id} className={v.id === variant.id ? 'active' : ''} aria-pressed={v.id === variant.id} onClick={() => onVariant(v.id)}>
                {variantLabel(v)}
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="detail-block">
        <ul className="stat-list">
          {flat.map((s, i) => <li key={i}><strong>+{n0(s.value)}</strong> {statLabel(s.type)}</li>)}
        </ul>
        {variant.passives.map((p, i) => (
          <p className="passive" key={i}>
            {p.name && <span className="passive-name">{p.name}: </span>}{p.text}
          </p>
        ))}
      </div>

      {modeled.length > 0 && (
        <div className="detail-block modeled">
          <h4>Considerado no cálculo</h4>
          <ul>
            {modeled.map((m, i) => (
              <li key={i}>
                <span className="passive-name">{m.name}:</span> {m.text}
                {m.conditional && <small className="cond"> · condicional</small>}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="detail-block">
        <h4>Receita</h4>
        {components.length === 0 && <small>Item básico (sem componentes).</small>}
        <div className="recipe">
          {components.map((c) => (
            <button key={c.item.id} className="recipe-item" onClick={() => onOpen(c.item)} title={c.item.name}>
              <ItemIcon id={c.item.id} size={36} />
              <span>{c.quantity > 1 ? `${c.quantity}× ` : ''}{n0(c.item.cost)}</span>
            </button>
          ))}
        </div>
        {buildsInto.length > 0 && (
          <>
            <h4>Fabrica</h4>
            <div className="recipe">
              {buildsInto.map((i) => (
                <button key={i.id} className="recipe-item" onClick={() => onOpen(i)} title={i.name}>
                  <ItemIcon id={i.id} size={36} />
                  <span>{n0(i.cost)}</span>
                </button>
              ))}
            </div>
          </>
        )}
      </div>

      <div className="detail-block preview">
        <h4>Se comprado agora, nesta build</h4>
        {!buildPayload && <small>Informe ouro/min e XP/min para ver a prévia.</small>}
        {buildPayload && !preview && <small>Calculando…</small>}
        {preview && (
          <dl>
            <dt>Compra em</dt><dd>{mmss(preview.minute)} · nível {preview.level}</dd>
            <dt>Ouro pago</dt><dd>{n0(preview.paidCost)}</dd>
            <dt>Eficiência estática</dt><dd>{pct(preview.efficiency.staticPct)}</dd>
            <dt>Eficiência dinâmica</dt><dd className="strong">{pct(preview.efficiency.dynamicPct)}</dd>
            <dt>Marginal</dt><dd>{pct(preview.efficiency.marginalPct)}</dd>
          </dl>
        )}
      </div>

      <button className="primary add" onClick={onAdd}>Adicionar à build</button>
    </>
  );
}

/** Timeline step the item would get if appended to the current build. */
function usePurchasePreview(buildPayload, itemId) {
  const [preview, setPreview] = useState(null);
  const key = buildPayload ? JSON.stringify(buildPayload) : null;
  useEffect(() => {
    setPreview(null);
    if (!buildPayload) return undefined;
    let cancelled = false;
    const timer = setTimeout(async () => {
      try {
        const r = await api.post('/api/calculate', { ...buildPayload, itemIds: [...buildPayload.itemIds, itemId] });
        if (!cancelled) setPreview(r.steps[r.steps.length - 1] ?? null);
      } catch {
        /* preview is best effort */
      }
    }, 200);
    return () => { cancelled = true; clearTimeout(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, itemId]);
  return preview;
}
