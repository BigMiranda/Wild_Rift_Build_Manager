import { useEffect, useState } from 'react';
import { t } from '../i18n.js';
import { findRune, findTree, runeCatalog, runeIcon, treeIcon, treeRow } from '../runes.js';

const VIEW_KEY = 'ornn-planner-rune-view';

function RuneImg({ rune, on, size = 56 }) {
  if (!rune) return <span className="rune-img empty" style={{ width: size, height: size }} />;
  return (
    <img className={`rune-img${on ? ' on' : ''}`} src={runeIcon(rune, !on)} alt={rune.name} width={size} height={size} draggable={false} />
  );
}

function TreeImg({ tree, on, size = 44 }) {
  if (!tree) return null;
  return <img className={`tree-img${on ? ' on' : ''}`} src={treeIcon(tree, !on)} alt={tree.name} width={size} height={size} draggable={false} />;
}

const tip = (r) => (r ? `${r.name}${r.tags ? ` — ${r.tags}` : ''}\n${r.text ?? ''}` : '');

/**
 * Rune page editor, like the game's: a list view (the page's slots with their descriptions; clicking a slot lists its
 * choices) and a grid view (every keystone and the rows of both trees). 1 keystone + 1 rune per row of the primary
 * tree + 1 rune of the secondary tree.
 */
export default function RuneEditor({ page, onChange, onClose }) {
  const [view, setView] = useState(() => {
    try { return localStorage.getItem(VIEW_KEY) || 'list'; } catch { return 'list'; }
  });
  const [picking, setPicking] = useState(null); // keystone | p0..p2 | secondary | primaryTree | secondaryTree
  const cat = runeCatalog();
  useEffect(() => {
    try { localStorage.setItem(VIEW_KEY, view); } catch { /* per-viewer convenience only */ }
  }, [view]);
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') (picking ? setPicking(null) : onClose()); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [picking, onClose]);
  if (!cat) return null;

  const set = (patch) => onChange({ ...page, ...patch });
  const setPrimaryRune = (i, name) => set({ primaryRunes: page.primaryRunes.map((r, k) => (k === i ? name : r)) });
  const setPrimaryTree = (tree) => {
    if (tree === page.primary) return;
    const secondary = page.secondary === tree ? cat.trees.find((x) => x.name !== tree).name : page.secondary;
    set({ primary: tree, primaryRunes: [null, null, null], secondary, secondaryRune: secondary === page.secondary ? page.secondaryRune : null });
  };
  const setSecondaryTree = (tree) => {
    if (tree === page.primary || tree === page.secondary) return;
    set({ secondary: tree, secondaryRune: null });
  };

  return (
    <div className="prep-overlay" role="dialog" aria-label={t('runes.title')}>
      <div className="prep-overlay-head">
        <button className="prep-back" onClick={picking ? () => setPicking(null) : onClose} aria-label={t('prep.back')}>
          <span aria-hidden="true">‹</span> {t('runes.title')}
        </button>
        <div className="view-toggle" role="group" aria-label={t('runes.view')}>
          <button className={view === 'list' ? 'active' : ''} aria-pressed={view === 'list'} onClick={() => setView('list')} title={t('runes.viewList')}>☰</button>
          <button className={view === 'grid' ? 'active' : ''} aria-pressed={view === 'grid'} onClick={() => { setView('grid'); setPicking(null); }} title={t('runes.viewGrid')}>▦</button>
        </div>
      </div>
      {view === 'list'
        ? <ListView page={page} picking={picking} setPicking={setPicking} set={set} setPrimaryRune={setPrimaryRune}
            setPrimaryTree={setPrimaryTree} setSecondaryTree={setSecondaryTree} />
        : <GridView page={page} set={set} setPrimaryRune={setPrimaryRune} setPrimaryTree={setPrimaryTree} setSecondaryTree={setSecondaryTree} />}
      <RuneOptions page={page} set={set} />
    </div>
  );
}

function ListView({ page, picking, setPicking, set, setPrimaryRune, setPrimaryTree, setSecondaryTree }) {
  const cat = runeCatalog();
  const primary = findTree(page.primary);
  const secondary = findTree(page.secondary);
  const slots = [
    { key: 'keystone', rune: findRune(page.keystone) },
    ...[0, 1, 2].map((i) => ({ key: `p${i}`, rune: findRune(page.primaryRunes[i]) })),
    { key: 'secondary', rune: findRune(page.secondaryRune) },
  ];
  const choose = (name) => {
    if (picking === 'keystone') set({ keystone: name });
    else if (picking === 'secondary') set({ secondaryRune: name });
    else setPrimaryRune(Number(picking.slice(1)), name);
    setPicking(null);
  };

  let title = null;
  let choices = null;
  if (picking === 'keystone') {
    title = <>{t('runes.pickKeystone')} <span className="rune-hl">{t('runes.keystone')}</span>:</>;
    choices = cat.keystones;
  } else if (picking?.startsWith('p')) {
    title = <>{t('runes.pickFrom')} <span className="rune-hl">{page.primary.toUpperCase()}</span>{t('runes.pickOne')}</>;
    choices = treeRow(page.primary, Number(picking.slice(1)) + 1);
  } else if (picking === 'secondary') {
    title = <>{t('runes.pickFrom')} <span className="rune-hl">{page.secondary.toUpperCase()}</span>{t('runes.pickOne')}</>;
    choices = [1, 2, 3].flatMap((row) => treeRow(page.secondary, row));
  }
  const selectedName = picking === 'keystone' ? page.keystone : picking === 'secondary' ? page.secondaryRune
    : picking?.startsWith('p') ? page.primaryRunes[Number(picking.slice(1))] : null;

  return (
    <div className="rune-list-view">
      <div className="rune-rail">
        <button className={`rail-rune${picking === 'keystone' ? ' picking' : ''}`} onClick={() => setPicking('keystone')} title={tip(slots[0].rune) || t('runes.keystone')}>
          <RuneImg rune={slots[0].rune} on size={64} />
        </button>
        <div className="rail-tree">
          <button className={`rail-diamond${picking === 'primaryTree' ? ' picking' : ''}`} onClick={() => setPicking('primaryTree')} title={primary?.name}>
            <TreeImg tree={primary} on />
          </button>
          <div className="rail-col">
            {[1, 2, 3].map((k) => (
              <button key={k} className={`rail-rune${picking === `p${k - 1}` ? ' picking' : ''}`} onClick={() => setPicking(`p${k - 1}`)} title={tip(slots[k].rune) || `${page.primary} ${k}`}>
                <RuneImg rune={slots[k].rune} on size={52} />
              </button>
            ))}
          </div>
        </div>
        <div className="rail-tree">
          <button className={`rail-diamond${picking === 'secondaryTree' ? ' picking' : ''}`} onClick={() => setPicking('secondaryTree')} title={secondary?.name}>
            <TreeImg tree={secondary} on />
          </button>
          <div className="rail-col">
            <button className={`rail-rune${picking === 'secondary' ? ' picking' : ''}`} onClick={() => setPicking('secondary')} title={tip(slots[4].rune) || page.secondary}>
              <RuneImg rune={slots[4].rune} on size={52} />
            </button>
          </div>
        </div>
      </div>

      <div className="rune-panel">
        {!picking && (
          <ul className="rune-summary">
            {slots.map((s, i) => (
              <li key={s.key} className={i === 1 || i === 4 ? 'group-start' : ''}>
                <button className="rune-row" onClick={() => setPicking(s.key)}>
                  <RuneImg rune={s.rune} on size={44} />
                  <span className="rune-row-text">
                    <span className="rune-name">{s.rune ? s.rune.name : t('runes.empty')}</span>
                    {s.rune?.tags && <span className="rune-tags">{s.rune.tags}</span>}
                    {s.rune && <span className="rune-desc clamp">{s.rune.text}</span>}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {(picking === 'primaryTree' || picking === 'secondaryTree') && (
          <>
            <h3 className="rune-pick-title">{t('runes.pickTree')} <span className="rune-hl">{t('runes.traits')}</span>:</h3>
            <ul className="rune-choices">
              {runeCatalog().trees.map((tree) => {
                const isPrimary = picking === 'primaryTree';
                const current = isPrimary ? page.primary : page.secondary;
                const disabled = !isPrimary && tree.name === page.primary;
                return (
                  <li key={tree.name}>
                    <button className={`rune-choice${tree.name === current ? ' selected' : ''}`} disabled={disabled}
                      onClick={() => { (isPrimary ? setPrimaryTree : setSecondaryTree)(tree.name); setPicking(null); }}>
                      <TreeImg tree={tree} on={!disabled} size={56} />
                      <span className="rune-row-text">
                        <span className="rune-name">{tree.name}</span>
                        <span className="rune-tags">{tree.summary}</span>
                        {/* the tree's runes, in colour, to help choosing */}
                        <span className="tree-preview">
                          {[1, 2, 3].map((row) => (
                            <span key={row} className="tree-preview-row">
                              {treeRow(tree.name, row).map((r) => (
                                <img key={r.name} className="rune-img" src={runeIcon(r, disabled)} alt={r.name} title={tip(r)} width={30} height={30} />
                              ))}
                            </span>
                          ))}
                        </span>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </>
        )}
        {choices && (
          <>
            <h3 className="rune-pick-title">{title}</h3>
            <ul className="rune-choices">
              {choices.map((r) => (
                <li key={r.name}>
                  <button className={`rune-choice${r.name === selectedName ? ' selected' : ''}`} onClick={() => choose(r.name)}>
                    <RuneImg rune={r} on={r.name === selectedName} size={64} />
                    <span className="rune-row-text">
                      <span className="rune-name">{r.name}{r.marker && <Marker m={r.marker} />}</span>
                      {r.tags && <span className="rune-tags">{r.tags}</span>}
                      <span className="rune-desc">{r.text}</span>
                      {r.modeled && <span className="rune-modeled">{t('runes.modeled')}</span>}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </div>
  );
}

function Marker({ m }) {
  return <span className={`rune-marker ${m}`} title={t(m === 'N' ? 'marker.N' : 'marker.refresh')}>{m === 'N' ? 'N' : '⟳'}</span>;
}

function GridView({ page, set, setPrimaryRune, setPrimaryTree, setSecondaryTree }) {
  const cat = runeCatalog();
  return (
    <div className="rune-grid-view">
      <div className="grid-keystones">
        {cat.keystones.map((r) => (
          <button key={r.name} className="grid-rune" onClick={() => set({ keystone: r.name })} title={tip(r)} aria-pressed={page.keystone === r.name}>
            <RuneImg rune={r} on={page.keystone === r.name} size={60} />
            {r.marker && <Marker m={r.marker} />}
          </button>
        ))}
      </div>
      <div className="grid-trees">
        <div className="grid-tree">
          <div className="grid-tabs">
            {cat.trees.map((tree) => (
              <button key={tree.name} className="grid-tab" onClick={() => setPrimaryTree(tree.name)} title={`${tree.name} — ${tree.summary}`} aria-pressed={page.primary === tree.name}>
                <TreeImg tree={tree} on={page.primary === tree.name} />
              </button>
            ))}
          </div>
          {[1, 2, 3].map((row) => (
            <div key={row} className="grid-row">
              {treeRow(page.primary, row).map((r) => (
                <button key={r.name} className="grid-rune" onClick={() => setPrimaryRune(row - 1, r.name)} title={tip(r)} aria-pressed={page.primaryRunes[row - 1] === r.name}>
                  <RuneImg rune={r} on={page.primaryRunes[row - 1] === r.name} size={60} />
                  {r.marker && <Marker m={r.marker} />}
                </button>
              ))}
            </div>
          ))}
        </div>
        <div className="grid-tree">
          <div className="grid-tabs">
            {cat.trees.map((tree) => (
              <button key={tree.name} className="grid-tab" disabled={tree.name === page.primary} onClick={() => setSecondaryTree(tree.name)}
                title={`${tree.name} — ${tree.summary}`} aria-pressed={page.secondary === tree.name}>
                <TreeImg tree={tree} on={page.secondary === tree.name} />
              </button>
            ))}
          </div>
          {[1, 2, 3].map((row) => (
            <div key={row} className="grid-row">
              {treeRow(page.secondary, row).map((r) => (
                <button key={r.name} className="grid-rune" onClick={() => set({ secondaryRune: r.name })} title={tip(r)} aria-pressed={page.secondaryRune === r.name}>
                  <RuneImg rune={r} on={page.secondaryRune === r.name} size={60} />
                  {r.marker && <Marker m={r.marker} />}
                </button>
              ))}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

/** Values of the chosen runes that enter the calculation: option (enemies nearby, stacks...) and conditional effects. */
function RuneOptions({ page, set }) {
  const chosen = [page.keystone, ...page.primaryRunes, page.secondaryRune].map(findRune).filter((r) => r && (r.option || r.hasConditional));
  if (!chosen.length) return null;
  return (
    <div className="rune-options">
      <span className="rune-options-title">{t('runes.options')}</span>
      {chosen.map((r) => {
        const value = page.options?.[r.name] ?? r.option?.defaultValue;
        const cond = page.conditional?.[r.name] !== false;
        return (
          <span key={r.name} className="rune-option">
            <img src={runeIcon(r)} alt="" width={22} height={22} />
            <strong>{r.name}</strong>
            {r.option && (
              <label>
                {r.option.name}
                <input type="number" min={r.option.min} max={r.option.max} value={value}
                  onChange={(e) => set({ options: { ...page.options, [r.name]: Math.max(r.option.min, Math.min(r.option.max, Number(e.target.value))) } })} />
              </label>
            )}
            {r.hasConditional && (
              <button className={`opt${cond ? ' on' : ''}`} aria-pressed={cond} title={t('bar.condTitle')}
                onClick={() => set({ conditional: { ...page.conditional, [r.name]: !cond } })}>
                ◐ {t('bar.cond')}
              </button>
            )}
          </span>
        );
      })}
    </div>
  );
}
