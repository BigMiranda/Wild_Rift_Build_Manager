import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { statLabel, t, violationText } from '../i18n.js';
import { mmss, n0, pct, statValue } from '../format.js';
import { DEFAULT_TAB, MARKERS, TABS, buildTiles, describeStats, markerLabel, sectionsFor, variantLabel } from '../shopModel.js';
import ItemIcon from './ItemIcon.jsx';
import RichText from './RichText.jsx';
import { StatIcon, statColor } from './StatIcon.jsx';

/**
 * Wild Rift style shop: tab rail, icon grid and a detail panel.
 * `buildPayload` is the current build as the API expects it (or null when it cannot be calculated yet); it is used
 * to preview what the selected item would be worth if bought next.
 * `purchase` is the purchase selected in the sequence ({ index, itemId, step, ignored }): the shop opens its item and
 * shows what that purchase adds to the build instead of the preview. Picking another item clears it (`onPurchaseClear`).
 */
export default function Shop({ items, buildPayload, ownedIds, purchase, onPurchaseClear, onAdd }) {
  const [tab, setTab] = useState(DEFAULT_TAB);
  const [query, setQuery] = useState('');
  const [selectedKey, setSelectedKey] = useState(null);
  const [variantId, setVariantId] = useState(null);

  const tiles = useMemo(() => buildTiles(items), [items]);
  const sections = sectionsFor(tiles, tab, query);
  const itemsById = useMemo(() => new Map(items.map((i) => [i.id, i])), [items]);
  const ownedByTile = useMemo(() => {
    const counts = new Map();
    for (const id of ownedIds) {
      const key = itemsById.get(id)?.group;
      if (key) counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return counts;
  }, [ownedIds, itemsById]);

  const selected = tiles.find((x) => x.key === selectedKey) ?? null;
  const variant = selected ? selected.variants.find((v) => v.id === variantId) ?? selected.base : null;

  const show = (item) => {
    const tile = tiles.find((x) => x.key === (item.group ?? item.name));
    if (tile) {
      setSelectedKey(tile.key);
      setVariantId(item.id);
    }
  };
  const open = (item) => {
    onPurchaseClear();
    show(item);
  };

  // Selecting a purchase in the sequence opens its item here.
  const purchaseKey = purchase ? `${purchase.index}:${purchase.itemId}` : null;
  useEffect(() => {
    const item = purchase && itemsById.get(purchase.itemId);
    if (item) show(item);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [purchaseKey]);

  return (
    <div className="shop">
      <nav className="shop-rail" aria-label={t('settings.lang')}>
        {TABS.map((x) => (
          <button key={x} className={!query && tab === x ? 'active' : ''} onClick={() => { setTab(x); setQuery(''); }}>
            {t(`tab.${x}`)}
          </button>
        ))}
      </nav>

      <div className="shop-grid-wrap">
        <input
          type="search" className="shop-search" placeholder={t('shop.search')} value={query}
          onChange={(e) => setQuery(e.target.value)} aria-label={t('shop.search')}
        />
        <div className="shop-grid-scroll">
          {sections.length === 0 && <p className="muted">{t('shop.none')}</p>}
          {sections.map((s) => (
            <section key={s.key}>
              <h4 className="shop-section">{s.label}</h4>
              <div className="shop-grid">
                {s.tiles.map((x) => {
                  const marker = MARKERS[x.base.marker];
                  return (
                    <button
                      key={x.key}
                      className={`tile${x.key === selectedKey ? ' selected' : ''}`}
                      onClick={() => { onPurchaseClear(); setSelectedKey(x.key); setVariantId(x.preferredId); }}
                      onDoubleClick={() => onAdd(x.key === selectedKey && variant ? variant.id : x.preferredId)}
                      title={t('shop.dblclick', { name: x.name })}
                    >
                      <span className="tile-icon">
                        <ItemIcon id={x.iconId} size={64} />
                        <span className="tile-cost">{x.cost}</span>
                        {ownedByTile.has(x.key) && <span className="tile-owned" aria-label={t('shop.owned')}>×{ownedByTile.get(x.key)}</span>}
                        {x.base.active && <span className="tile-active" title={t('shop.activeItem')} aria-label={t('shop.activeItem')}>◆</span>}
                        {marker && <span className={`tile-marker ${x.base.marker}`} title={markerLabel(x.base.marker)} aria-label={markerLabel(x.base.marker)}>{marker.badge}</span>}
                      </span>
                      <span className="tile-name">{x.name}</span>
                    </button>
                  );
                })}
              </div>
            </section>
          ))}
        </div>
      </div>

      <aside className="shop-detail" aria-live="polite">
        {!selected && <p className="shop-empty">{t('shop.selectHint')}</p>}
        {selected && variant && (
          <ItemDetail
            tile={selected} variant={variant} items={items} itemsById={itemsById} buildPayload={buildPayload}
            purchase={purchase && purchase.itemId === variant.id ? purchase : null}
            onVariant={(id) => { onPurchaseClear(); setVariantId(id); }} onAdd={() => onAdd(variant.id)} onOpen={open}
          />
        )}
      </aside>
    </div>
  );
}

function ItemDetail({ tile, variant, items, itemsById, buildPayload, purchase, onVariant, onAdd, onOpen }) {
  const { flat, modeled } = describeStats(variant);
  const components = variant.components.map((c) => ({ item: itemsById.get(c.itemId), quantity: c.quantity })).filter((c) => c.item);
  const buildsInto = items.filter((i) => i.section !== 'evolucao' && i.components.some((c) => c.itemId === tile.base.id));
  const preview = usePurchasePreview(purchase ? null : buildPayload, variant.id);
  const current = preview && preview.itemId === variant.id ? preview : null;
  // Over the item limit the purchase is allowed (and left out of the calculation); other rules block it.
  const blocked = current && !current.ignored ? current.violations ?? [] : [];

  return (
    <>
      <header className="detail-head">
        <ItemIcon id={variant.id} size={56} />
        <div>
          <h3>{variant.name}</h3>
          <div className="gold">{t('shop.gold', { n: n0(variant.cost) })}{tile.base.active ? ` · ${t('shop.active')}` : ''}</div>
          {MARKERS[tile.base.marker] && <small className={`marker-text ${tile.base.marker}`}>{markerLabel(tile.base.marker)}</small>}
        </div>
      </header>

      {tile.variants.length > 1 && (
        <div className="detail-block">
          <h4>{t('shop.state')}</h4>
          <div className="chips">
            {tile.variants.map((v) => (
              <button key={v.id} className={v.id === variant.id ? 'active' : ''} aria-pressed={v.id === variant.id} onClick={() => onVariant(v.id)}>
                <ItemIcon id={v.id} size={18} /> {variantLabel(v)}
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="detail-block">
        <ul className="stat-list">
          {flat.map((s, i) => (
            <li key={i} style={{ color: statColor(s.type) }}>
              <StatIcon stat={s.type} />+{statValue(s.type, s.value)} {statLabel(s.type)}
            </li>
          ))}
        </ul>
        {variant.passives.map((p, i) => (
          <p className="passive" key={i}>
            {p.name && <span className="passive-name">{p.name}: </span>}<RichText text={p.text} />
          </p>
        ))}
      </div>

      {(modeled.length > 0 || variant.effects?.length > 0) && (
        <div className="detail-block modeled">
          <h4>{t('shop.modeled')}</h4>
          <ul>
            {modeled.map((m, i) => (
              <li key={i}>
                <StatIcon stat={m.stat} /><span className="passive-name">{m.name}:</span>{' '}
                <span style={{ color: statColor(m.stat) }}>{m.text}</span>
                {m.conditional && <small className="cond"> · {t('shop.conditional')}</small>}
              </li>
            ))}
            {(variant.effects ?? []).map((e) => (
              <li key={e.name} title={e.note ?? undefined}>
                <span className="passive-name">{e.name}:</span> {e.summary.join(' · ')}
                {e.option && <small className="muted"> · {t('shop.effectOption', { name: e.option.name })}</small>}
                {e.rate && <small className="muted"> · {e.rate.name}</small>}
                {e.note && <small className="muted"> — {e.note}</small>}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="detail-block">
        <h4>{t('shop.recipe')}</h4>
        {components.length === 0 && <small>{t('shop.basic')}</small>}
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
            <h4>{t('shop.buildsInto')}</h4>
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

      {purchase ? <PurchaseValue purchase={purchase} /> : (
        <div className="detail-block preview">
          <h4>{t('shop.preview')}</h4>
          {!buildPayload && <small>{t('shop.previewHint')}</small>}
          {buildPayload && !current && <small>{t('shop.calculating')}</small>}
          {current?.ignored && <p className="ignored-note">⊘ {t('shop.wouldBeIgnored')}</p>}
          {blocked.length > 0 && (
            <div className="violation">
              {blocked.map((v, i) => <p key={i}>⛔ {violationText(v)}</p>)}
            </div>
          )}
          {current && !current.ignored && <StepNumbers step={current} />}
        </div>
      )}

      <button className="primary add" onClick={onAdd} disabled={blocked.length > 0} title={blocked.length ? t('rule.title') : undefined}>
        {t('shop.add')}
      </button>
    </>
  );
}

/** What a purchase of the sequence adds to the build, at its place in the order. */
function PurchaseValue({ purchase }) {
  const { number, step, ignored, implied } = purchase;
  return (
    <div className="detail-block preview">
      <h4>{t('shop.purchaseValue', { n: number })}</h4>
      {!step && !ignored && <small>{t('shop.previewHint')}</small>}
      {ignored && <p className="ignored-note">{t('bar.ignored', { reason: ignored.violations.map(violationText).join(' · ') })}</p>}
      {step?.violations?.length > 0 && (
        <div className="violation">
          {step.violations.map((v, i) => <p key={i}>⛔ {violationText(v)}</p>)}
        </div>
      )}
      {step && <StepNumbers step={step} gold />}
      {implied?.length > 0 && (
        <div className="implied-list">
          <small>{t('bar.impliedTitle')}</small>
          <ul>
            {implied.map((x) => (
              <li key={x.index}>
                <ItemIcon id={x.itemId} size={18} /> {mmss(x.minute)} · {x.itemName} · {n0(x.paidCost)}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function StepNumbers({ step, gold }) {
  return (
    <dl>
      <dt>{t('shop.boughtAt')}</dt><dd>{mmss(step.minute)} · {t('bar.lv', { n: step.level })}</dd>
      <dt>{t('shop.paid')}</dt><dd>{n0(step.paidCost)}</dd>
      <dt>{t('shop.bag')}</dt><dd>{n0(step.goldLeft)}</dd>
      {gold && step.efficiency.marginalGold != null && <><dt>{t('shop.addedGold')}</dt><dd className="strong">{n0(step.efficiency.marginalGold)}</dd></>}
      <dt>{t('detail.static')}</dt><dd>{pct(step.efficiency.staticPct)}</dd>
      <dt>{t('detail.dynamic')}</dt><dd className={gold ? undefined : 'strong'}>{pct(step.efficiency.dynamicPct)}</dd>
      <dt>{t('detail.marginal')}</dt><dd>{pct(step.efficiency.marginalPct)}</dd>
    </dl>
  );
}

/** Timeline step the item would get if appended to the current build (flagged `ignored` when over the item limit). */
function usePurchasePreview(buildPayload, itemId) {
  const [preview, setPreview] = useState(null);
  const key = buildPayload ? JSON.stringify(buildPayload) : null;
  useEffect(() => {
    setPreview(null);
    if (!buildPayload) return undefined;
    let cancelled = false;
    const timer = setTimeout(async () => {
      try {
        const r = await api.post('/api/calculate', {
          ...buildPayload,
          steps: [...buildPayload.steps, { itemId, includeConditional: true }],
        });
        const index = buildPayload.steps.length;
        if (!cancelled) setPreview(r.steps.find((x) => x.buildIndex === index && !x.implied) ?? r.ignored?.find((x) => x.buildIndex === index) ?? null);
      } catch {
        /* preview is best effort */
      }
    }, 200);
    return () => { cancelled = true; clearTimeout(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, itemId]);
  return preview;
}
