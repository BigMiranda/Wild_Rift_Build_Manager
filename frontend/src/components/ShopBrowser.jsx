import { useRef, useState } from 'react';
import { t } from '../i18n.js';
import { DEFAULT_TAB, TABS, sectionsFor } from '../shopModel.js';
import ItemIcon from './ItemIcon.jsx';

/** Moves the focus between tiles with the arrow keys: left / right in order, up / down to the nearest one in that row. */
function moveFocus(e, root) {
  const dir = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -2, ArrowDown: 2 }[e.key];
  if (!dir || !root) return;
  const tiles = [...root.querySelectorAll('.tile:not(:disabled)')];
  const at = tiles.indexOf(document.activeElement);
  if (tiles.length === 0) return;
  e.preventDefault();
  if (at < 0) {
    tiles[0].focus();
    return;
  }
  if (Math.abs(dir) === 1) {
    tiles[Math.max(0, Math.min(tiles.length - 1, at + dir))].focus();
    return;
  }
  const from = tiles[at].getBoundingClientRect();
  const down = dir > 0;
  let best = null;
  let score = Infinity;
  for (const x of tiles) {
    const r = x.getBoundingClientRect();
    if (down ? r.top <= from.top + 4 : r.top >= from.top - 4) continue;
    const s = Math.abs(r.top - from.top) * 1000 + Math.abs(r.left - from.left);
    if (s < score) {
      score = s;
      best = x;
    }
  }
  best?.focus();
}

/**
 * The shop's tab rail, search and icon grid, one for the planner's shop and every picker in its style.
 * tiles: buildTiles(items). filter(tile) hides tiles. decorate(tile): marks over the icon; caption(tile): a line under
 * the name; tileProps(tile): the button's props (onClick, title, disabled...); marked(tile) and focused key style it.
 * `after(query)` renders below the grid (what is not an item), `toolbar` and `heading` above it; `allTabs` lists every
 * tile that passes the filter, ignoring the tab.
 */
export default function ShopBrowser({ tiles, filter, decorate, caption, tileProps, marked, selectedKey, iconSize = 64, toolbar, after, autoFocus = false, heading, allTabs = false }) {
  const [tab, setTab] = useState(DEFAULT_TAB);
  const [query, setQuery] = useState('');
  const scroll = useRef(null);
  const shown = filter ? tiles.filter(filter) : tiles;
  // `allTabs`: a filter is on, so what passes shows whatever its tab
  const sections = allTabs && !query.trim() ? [{ key: 'all', label: t('picker.allTabs', { n: shown.length }), tiles: shown }].filter((s) => s.tiles.length)
    : sectionsFor(shown, tab, query);
  return (
    <>
      <nav className="shop-rail" aria-label={t('picker.tabs')}>
        {TABS.map((x) => (
          <button key={x} className={!query && tab === x ? 'active' : ''} onClick={() => { setTab(x); setQuery(''); }}>
            {t(`tab.${x}`)}
          </button>
        ))}
      </nav>

      <div className="shop-grid-wrap">
        <input
          type="search" className="shop-search" placeholder={t('shop.search')} value={query} autoFocus={autoFocus}
          onChange={(e) => setQuery(e.target.value)} aria-label={t('shop.search')}
          onKeyDown={(e) => { if (e.key === 'ArrowDown') moveFocus(e, scroll.current); }}
        />
        {toolbar}
        <div className="shop-grid-scroll" ref={scroll} onKeyDown={(e) => moveFocus(e, scroll.current)}>
          {heading && <h4 className="picker-heading">{heading}</h4>}
          {sections.length === 0 && <p className="muted">{t('shop.none')}</p>}
          {sections.map((s) => (
            <section key={s.key}>
              <h4 className="shop-section">{s.label}</h4>
              <div className="shop-grid">
                {s.tiles.map((x) => (
                  <button
                    key={x.key}
                    className={`tile${x.key === selectedKey ? ' selected' : ''}${marked?.(x) ? ' marked' : ''}`}
                    aria-pressed={marked ? !!marked(x) : undefined}
                    {...tileProps(x)}
                  >
                    <span className="tile-icon">
                      <ItemIcon id={x.iconId} size={iconSize} />
                      <span className="tile-cost">{x.cost}</span>
                      {decorate?.(x)}
                    </span>
                    <span className="tile-name">{x.name}</span>
                    {caption?.(x)}
                  </button>
                ))}
              </div>
            </section>
          ))}
          {after?.(query)}
        </div>
      </div>
    </>
  );
}
