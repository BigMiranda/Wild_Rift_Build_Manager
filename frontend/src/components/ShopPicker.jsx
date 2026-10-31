import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { mmss, n0 } from '../format.js';
import { buildTiles } from '../shopModel.js';
import ItemIcon from './ItemIcon.jsx';
import ShopBrowser from './ShopBrowser.jsx';

/** The shop's items for the pickers that do not get them from their page (loaded once). */
let shopItems = null;
export function useShopItems(given) {
  const [items, setItems] = useState(given ?? []);
  useEffect(() => {
    if (given) {
      setItems(given);
      return undefined;
    }
    let live = true;
    if (!shopItems) shopItems = api.get('/api/items').catch((e) => { shopItems = null; throw e; });
    shopItems.then((x) => { if (live) setItems(x); }).catch(() => {});
    return () => { live = false; };
  }, [given]);
  return items;
}

const WIDTH = 680;

/**
 * A button that opens a panel over the page, anchored to it: Esc or a click outside closes it. On a narrow screen the
 * panel is a sheet rising from the bottom (styles.css). children(close) is the panel's content.
 */
export function PickerPopover({ label, title, disabled, className = '', children, onOpen }) {
  const [open, setOpen] = useState(false);
  const [box, setBox] = useState(null);
  const button = useRef(null);
  const panel = useRef(null);
  const close = () => {
    setOpen(false);
    button.current?.focus();
  };
  useLayoutEffect(() => {
    if (!open || !button.current) return;
    const r = button.current.getBoundingClientRect();
    const vw = document.documentElement.clientWidth;
    const vh = window.innerHeight;
    const width = Math.min(WIDTH, vw - 16);
    const below = vh - r.bottom - 12;
    const above = r.top - 12;
    const height = Math.min(560, Math.max(below, above));
    setBox({
      width, height, left: Math.max(8, Math.min(vw - width - 8, r.left)),
      top: below >= above || below >= 420 ? r.bottom + 4 : r.top - 4 - height,
    });
  }, [open]);
  useEffect(() => {
    // the panel is hidden until it is placed, and a hidden field takes no focus
    if (box) panel.current?.querySelector('input[type=search]')?.focus();
  }, [box]);
  useEffect(() => {
    if (!open) {
      setBox(null);
      return undefined;
    }
    onOpen?.();
    const onKey = (e) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        close();
      }
    };
    const onDown = (e) => {
      if (!panel.current?.contains(e.target) && !button.current?.contains(e.target)) setOpen(false);
    };
    window.addEventListener('keydown', onKey, true);
    document.addEventListener('mousedown', onDown);
    return () => {
      window.removeEventListener('keydown', onKey, true);
      document.removeEventListener('mousedown', onDown);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  return (
    <>
      <button ref={button} type="button" className={`picker-open ${className}`} disabled={disabled} title={title} aria-haspopup="dialog"
        aria-expanded={open} onClick={() => (open ? close() : setOpen(true))}>
        {label}
      </button>
      {open && createPortal(
        <div ref={panel} className="picker-panel" role="dialog" aria-label={typeof label === 'string' ? label : title} style={box ?? { visibility: 'hidden' }}>
          <div className="picker-sheet-head">
            <button type="button" className="icon" onClick={close} aria-label={t('close')}>✕</button>
          </div>
          {children(close)}
        </div>,
        document.body,
      )}
    </>
  );
}

/** The chosen ones at the top of a panel: icon, name and × to take it off. */
export function ChosenChips({ chosen, label }) {
  if (chosen.length === 0) return null;
  return (
    <div className="picker-chosen" aria-label={label ?? t('picker.chosen')}>
      {chosen.map((c) => (
        <button key={c.key} type="button" className="chip-item" onClick={c.onRemove} title={t('picker.remove', { name: c.name })}>
          {c.icon} {c.name} ✕
        </button>
      ))}
    </div>
  );
}

/**
 * Picks items from the shop, in its tabs, search and grid. value: item ids; onChange(ids). `max` limits them; `single`
 * picks one and closes. filter(item), decorate(tile), caption(tile) and toolbar shape the grid.
 */
export default function ItemPicker({ items: given, value = [], onChange, max, single, filter, decorate, caption, toolbar, label, title, disabled, onOpen, className }) {
  const items = useShopItems(given);
  const tiles = useMemo(() => buildTiles(items), [items]);
  const byId = useMemo(() => new Map(items.map((i) => [i.id, i])), [items]);
  const held = (x) => x.variants.some((v) => value.includes(v.id));
  const full = max != null && value.length >= max;
  return (
    <PickerPopover label={label ?? t('picker.add')} title={title} disabled={disabled} onOpen={onOpen} className={className}>
      {(close) => (
        <>
          {!single && (
            <ChosenChips chosen={value.map((id) => ({
              key: id, name: byId.get(id)?.name ?? id, icon: <ItemIcon id={id} size={18} />, onRemove: () => onChange(value.filter((x) => x !== id)),
            }))} />
          )}
          {max != null && !single && <small className="muted picker-limit">{t('picker.limit', { n: value.length, max })}</small>}
          <div className="shop picker-shop">
            <ShopBrowser
              tiles={tiles} iconSize={52} autoFocus toolbar={toolbar} caption={caption} decorate={decorate}
              filter={(x) => held(x) || !filter || filter(x.base)}
              marked={held}
              tileProps={(x) => ({
                type: 'button',
                title: x.name,
                disabled: !single && !held(x) && full,
                onClick: () => {
                  if (single) {
                    onChange([x.preferredId]);
                    close();
                  } else if (held(x)) {
                    onChange(value.filter((id) => !x.variants.some((v) => v.id === id)));
                  } else {
                    onChange([...value, x.preferredId]);
                  }
                },
              })}
            />
          </div>
        </>
      )}
    </PickerPopover>
  );
}

/**
 * Candidates for a purchase of the build (or the next one): the shop's upgraded items, with what fits there by the
 * shop's rules (the same swap and timeline as the comparison, POST /api/quick/fits), the minute each would be ready and
 * the items of the same tab as the one in the slot.
 */
export function CandidatePicker({ build, slot, atMinute, value, onChange, max, items: given, label, disabled }) {
  const items = useShopItems(given);
  const [fits, setFits] = useState(null);
  const [onlyFits, setOnlyFits] = useState(true);
  const [sameTab, setSameTab] = useState(false);
  const current = slot != null ? build?.steps?.[slot]?.itemId : null;
  const tabs = items.find((i) => i.id === current)?.tabs ?? null;
  const load = () => {
    setFits(null);
    api.post('/api/quick/fits', { build, slot, atMinute: slot == null ? atMinute : null }).then(setFits).catch(() => setFits({}));
  };
  const filter = (item) => item.section === 'aprimorado'
    && (!onlyFits || !fits || fits[item.id]?.fits !== false)
    && (!sameTab || !tabs || item.tabs.some((x) => tabs.includes(x)));
  return (
    <ItemPicker
      items={items} value={value} onChange={onChange} max={max} label={label ?? t('qc.add')} disabled={disabled} onOpen={load} filter={filter}
      caption={(x) => {
        const m = fits?.[x.base.id]?.readyAt;
        return m != null ? <small className="tile-ready">{t('picker.ready', { time: mmss(m) })}</small> : null;
      }}
      decorate={(x) => (fits?.[x.base.id]?.fits === false ? <span className="tile-nofit" title={t('picker.noFit')}>⊘</span> : null)}
      toolbar={(
        <div className="chips picker-filters">
          <button type="button" className={onlyFits ? 'active' : ''} aria-pressed={onlyFits} onClick={() => setOnlyFits((v) => !v)} title={t('picker.fitsHelp')}>
            {t('picker.fits')}{fits ? '' : ' …'}
          </button>
          {tabs && (
            <button type="button" className={sameTab ? 'active' : ''} aria-pressed={sameTab} onClick={() => setSameTab((v) => !v)}>
              {t('picker.sameTab')}
            </button>
          )}
          {fits && <small className="muted">{t('picker.fitCount', { n: n0(Object.values(fits).filter((f) => f.fits).length) })}</small>}
        </div>
      )}
    />
  );
}
