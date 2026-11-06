import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { statLabel, t } from '../i18n.js';
import { mmss, statValue } from '../format.js';
import { pageSlots, runeIcon, findSpell, spellIcon } from '../runes.js';
import { pointAt } from './MomentPanels.jsx';
import ItemIcon from './ItemIcon.jsx';
import { StatIcon, statColor } from './StatIcon.jsx';
import { useDrawer } from './Drawer.jsx';
import { useBuild } from '../buildContext.jsx';
import { navigate } from '../router.js';

const MAIN_STATS = ['Max Health', 'Armor', 'Magic Resistance', 'Attack Damage', 'Ability Power', 'Ability Haste', '% Attack Speed', 'Move Speed'];

/**
 * The preview of a saved build (Rodada 8, parte 3): its items in order with icons, runes, spells, level and the main
 * stats at a minute (the one given, or its last purchase), with "Abrir esta build".
 */
export default function BuildPreview({ id, minute }) {
  const { openBuild, draft, itemsById } = useBuild();
  const { close } = useDrawer();
  const [b, setB] = useState(null);
  const [tl, setTl] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    let live = true;
    setB(null);
    setTl(null);
    Promise.all([api.get(`/api/builds/${id}`), api.get(`/api/builds/${id}/timeline`).catch(() => null)])
      .then(([x, y]) => { if (live) { setB(x); setTl(y); } })
      .catch((e) => { if (live) setError(e.message); });
    return () => { live = false; };
  }, [id]);
  if (error) return <p className="error">{error}</p>;
  if (!b) return <p className="muted">{t('compare.calculating')}</p>;
  const last = tl?.steps?.[tl.steps.length - 1];
  const at = minute ?? last?.minute ?? null;
  const p = tl && at != null ? pointAt(tl.series, at) : null;
  const items = (b.steps ?? []).filter((s) => !s.kind && s.itemId != null);
  const open = async () => {
    if (await openBuild(id, { minute: minute ?? null })) {
      close();
      navigate('/build/loja');
    }
  };
  return (
    <div className="build-preview">
      <p className="muted small">{b.unitCode}{p ? ` · ${mmss(p.minute)} · ${t('bar.lv', { n: p.level })}` : ''} · {b.goldPerMin} {t('preview.gpm')}</p>
      <h4>{t('preview.items')}</h4>
      <div className="preview-items">
        {items.map((s, i) => (
          <span key={i} className="preview-item" title={`${i + 1}. ${itemsById.get(s.itemId)?.name ?? s.itemId}`}>
            <ItemIcon id={s.itemId} size={36} />
            <small>{i + 1}</small>
          </span>
        ))}
        {items.length === 0 && <span className="muted">—</span>}
      </div>
      <h4>{t('prep.runes')} · {t('prep.spells')}</h4>
      <div className="preview-items">
        {pageSlots(b.runePage).filter((x) => x.rune).map((x) => (
          <img key={x.key} className="rune-img on" src={runeIcon(x.rune)} alt={x.rune.name} title={x.rune.name} width={32} height={32} />
        ))}
        {(b.spells ?? []).map((n) => {
          const s = findSpell(n);
          return s ? <img key={n} className="spell-img" src={spellIcon(s)} alt={s.name} title={s.name} width={32} height={32} /> : null;
        })}
      </div>
      {p && (
        <>
          <h4>{t('preview.stats', { time: mmss(p.minute) })}</h4>
          <ul className="preview-stats">
            {MAIN_STATS.filter((s) => (p.total[s] ?? 0) > 0.05).map((s) => (
              <li key={s} style={{ color: statColor(s) }}><StatIcon stat={s} size={14} />{statLabel(s)} <strong>{statValue(s, p.total[s])}</strong></li>
            ))}
          </ul>
        </>
      )}
      {b.note && <p className="small">{b.note}</p>}
      <div className="row">
        <button className="primary" onClick={open} disabled={draft?.id === id}>{draft?.id === id ? t('preview.isOpen') : t('preview.open')}</button>
      </div>
    </div>
  );
}

/** A build's name that opens its preview in the drawer (every reference to a saved build). */
export function BuildLink({ id, name, minute, children }) {
  const { open } = useDrawer();
  if (id == null) return <>{children ?? name}</>;
  return (
    <button type="button" className="link build-link" title={t('preview.hint')}
      onClick={(e) => { e.stopPropagation(); open({ title: name ?? `#${id}`, render: () => <BuildPreview id={id} minute={minute} /> }); }}>
      {children ?? name}
    </button>
  );
}
