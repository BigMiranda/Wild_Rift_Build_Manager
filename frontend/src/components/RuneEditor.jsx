import { useEffect, useState } from 'react';
import { statLabel, t } from '../i18n.js';
import { findRune, findTree, runeCatalog, runeIcon, treeIcon, treeRow } from '../runes.js';
import { mmss, n0, n1 } from '../format.js';
import MinuteInput from './MinuteInput.jsx';

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
export default function RuneEditor({ page, onChange, onClose, matchEnd }) {
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
      <RuneOptions page={page} set={set} matchEnd={matchEnd} />
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

  // The page: one column like the game's, each slot with its icon and full description (click to change it).
  if (!picking) {
    const row = (s, label) => (
      <button key={s.key} className="page-row" onClick={() => setPicking(s.key)} title={t('runes.change')}>
        <RuneImg rune={s.rune} on size={s.key === 'keystone' ? 72 : 60} />
        <span className="rune-row-text">
          <span className="rune-name">{s.rune ? s.rune.name : label}{s.rune?.marker && <Marker m={s.rune.marker} />}</span>
          {s.rune?.tags && <span className="rune-tags">{s.rune.tags}</span>}
          {s.rune && <span className="rune-desc">{s.rune.text}</span>}
        </span>
      </button>
    );
    const group = (tree, which, rows) => (
      <div className="page-group">
        <button className="page-diamond" onClick={() => setPicking(which)} title={t('runes.changeTree')}>
          <TreeImg tree={tree} on size={52} />
          <small>{tree?.name}</small>
        </button>
        <div className="page-group-rows">{rows}</div>
      </div>
    );
    return (
      <div className="rune-page">
        {row(slots[0], t('runes.empty'))}
        {group(primary, 'primaryTree', [1, 2, 3].map((k) => row(slots[k], t('runes.emptyRow', { n: k }))))}
        {group(secondary, 'secondaryTree', row(slots[4], t('runes.empty')))}
      </div>
    );
  }

  return (
    <div className="rune-picker">
      <div className="rune-panel">
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

/**
 * Values of the chosen runes that enter the calculation: option (enemies nearby, stacks...), conditional effects, the
 * end-of-match report of runes that scale without limit (spread over the match, with the estimate shown below) and the
 * gold earned by runes that give gold (at given minutes).
 */
function RuneOptions({ page, set, matchEnd }) {
  const chosen = [page.keystone, ...page.primaryRunes, page.secondaryRune].map(findRune)
    .filter((r) => r && (r.option || r.hasConditional || r.rate || r.extraGold));
  if (!chosen.length) return null;
  return (
    <div className="rune-options">
      <span className="rune-options-title">{t('runes.options')}</span>
      {chosen.map((r) => {
        const value = page.options?.[r.name] ?? r.option?.defaultValue;
        const cond = page.conditional?.[r.name] !== false;
        // Periods of activations / stacks per minute, each from its start until the next one (first one at 0:00).
        const saved = page.rates?.[r.name];
        const periods = Array.isArray(saved) && saved.length ? saved
          : typeof saved === 'number' ? [{ start: 0, perMinute: saved }] // older builds: a single rate
            : [{ start: 0, perMinute: null }];
        const setPeriods = (list) => set({ rates: { ...page.rates, [r.name]: list } });
        const events = page.gold?.[r.name] ?? [];
        const setEvents = (list) => set({ gold: { ...page.gold, [r.name]: list } });
        return (
          <div key={r.name} className="rune-option">
            <div className="rune-option-head">
              <img src={runeIcon(r)} alt="" width={22} height={22} />
              <strong>{r.name}</strong>
              {r.option && (
                <label>
                  {r.option.name}
                  <input type="number" min={r.option.min} max={r.option.max} value={value}
                    onChange={(e) => set({ options: { ...page.options, [r.name]: Math.max(r.option.min, Math.min(r.option.max, Number(e.target.value))) } })} />
                </label>
              )}
              {r.rate && <small className="muted">{t('runes.perStackFixed', { per: n0(r.rate.perStack), stat: statLabel(r.rate.stat) })}</small>}
              {r.hasConditional && (
                <button className={`opt${cond ? ' on' : ''}`} aria-pressed={cond} title={t('bar.condTitle')}
                  onClick={() => set({ conditional: { ...page.conditional, [r.name]: !cond } })}>
                  ◐ {t('bar.cond')}
                </button>
              )}
            </div>
            {r.rate && (
              <div className="rune-periods" title={t('runes.rateTitle')}>
                {periods.map((p, i) => (
                  <span key={i} className="rune-period">
                    <small>{t('runes.periodFrom')}</small>
                    {i === 0
                      ? <span className="period-start">0:00</span>
                      : <MinuteInput value={p.start} placeholder="10:00" aria-label={t('runes.periodFrom')}
                          onChange={(m) => setPeriods(periods.map((x, k) => (k === i ? { ...x, start: m ?? 0 } : x)))} />}
                    <input type="number" min={0} step={0.1} value={p.perMinute ?? ''} placeholder="0" aria-label={r.rate.name}
                      onChange={(e) => setPeriods(periods.map((x, k) => (k === i ? { ...x, perMinute: e.target.value === '' ? null : Math.max(0, Number(e.target.value)) } : x)))} />
                    <small>{r.rate.name.toLowerCase()}</small>
                    {i > 0 && <button className="icon danger" onClick={() => setPeriods(periods.filter((_, k) => k !== i))} aria-label={t('runes.periodRemove')}>✕</button>}
                  </span>
                ))}
                <button className="link" onClick={() => setPeriods([...periods, { start: (periods[periods.length - 1].start ?? 0) + 10, perMinute: periods[periods.length - 1].perMinute }])}>
                  {t('runes.periodAdd')}
                </button>
                <RateEstimate rune={r} periods={periods} matchEnd={matchEnd}
                  onUse={(v) => setPeriods([{ start: 0, perMinute: v }])} />
              </div>
            )}
            {r.extraGold && (
              <div className="rune-gold">
                <small className="muted">{t('runes.goldHelp')}</small>
                {events.map((g, i) => (
                  <span key={i} className="rune-gold-row">
                    <MinuteInput value={g.minute} placeholder="8:30" aria-label={t('runes.goldMinute')}
                      onChange={(m) => setEvents(events.map((x, k) => (k === i ? { ...x, minute: m ?? 0 } : x)))} />
                    <input type="number" min={0} value={g.gold} aria-label={t('runes.goldValue')}
                      onChange={(e) => setEvents(events.map((x, k) => (k === i ? { ...x, gold: Math.max(0, Number(e.target.value)) } : x)))} />
                    <small>{t('runes.goldUnit')}</small>
                    <button className="icon danger" onClick={() => setEvents(events.filter((_, k) => k !== i))} aria-label={t('runes.goldRemove')}>✕</button>
                  </span>
                ))}
                <button className="link" onClick={() => setEvents([...events, { minute: events.length ? events[events.length - 1].minute + 3 : 5, gold: 0 }])}>
                  {t('runes.goldAdd')}
                </button>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

/** Sum of per-minute periods ({start, perMinute}, each until the next start) up to a minute. */
function accumulated(periods, minute) {
  const list = [...periods].filter((p) => p.perMinute > 0 || p.perMinute === 0).sort((x, y) => x.start - y.start);
  let total = 0;
  list.forEach((p, i) => {
    const to = i + 1 < list.length ? list[i + 1].start : Infinity;
    total += (p.perMinute ?? 0) * Math.max(0, Math.min(minute, to) - p.start);
  });
  return total;
}

/** First minute the periods add up to `amount` stacks (null if never). */
function minuteReaching(periods, amount) {
  const list = [...periods].filter((p) => p.perMinute != null).sort((x, y) => x.start - y.start);
  let total = 0;
  for (let i = 0; i < list.length; i++) {
    const to = i + 1 < list.length ? list[i + 1].start : Infinity;
    const rate = list[i].perMinute;
    if (rate > 0 && total + rate * (to - list[i].start) >= amount - 1e-9) return list[i].start + (amount - total) / rate;
    total += rate * (to - list[i].start);
  }
  return null;
}

/**
 * What the periods mean over the match: the stat at a few moments, when the stack bonus kicks in, and a converter
 * from the end-of-match rune report ("Aumento de Vida: 410" in 22:00 -> an average rate for the whole match).
 */
function RateEstimate({ rune, periods, matchEnd, onUse }) {
  const [reported, setReported] = useState('');
  const { stat, perStack, bonusStacks, bonusRatio, report } = rune.rate;
  const until = matchEnd > 0 ? matchEnd : 30;
  const marks = [5, 10, 15, 20, 25, 30].filter((m) => m < until);
  const any = periods.some((p) => p.perMinute > 0);
  const reach = bonusStacks ? minuteReaching(periods, bonusStacks) : null;
  const fromReport = Number(reported) > 0 && matchEnd > 0 ? Number(reported) / perStack / matchEnd : null;
  return (
    <div className="rune-estimate">
      {any ? (
        <small>
          {t('runes.rateTotal', { stat: statLabel(stat) })}
          {marks.map((m) => ` · ${mmss(m)} → ${n0(accumulated(periods, m) * perStack)}`).join('')}
          {matchEnd > 0 && ` · ${mmss(matchEnd)} → ${n0(accumulated(periods, matchEnd) * perStack)}`}
          {bonusStacks && reach != null && ` · ${t('runes.rateBonus', { pct: n0(bonusRatio * 100), stat: statLabel(stat), time: mmss(reach), n: bonusStacks })}`}
        </small>
      ) : (
        <small className="muted">{t('runes.rateHint', { per: n0(perStack), stat: statLabel(stat) })}</small>
      )}
      {report && (
        <small className="rune-convert">
          {t('runes.rateFromReport', { report })}
          <input type="number" min={0} value={reported} placeholder="410" onChange={(e) => setReported(e.target.value)} aria-label={report} />
          {matchEnd > 0
            ? fromReport != null && (
              <>
                {` ÷ ${n0(perStack)} ÷ ${mmss(matchEnd)} = ${n1(fromReport)}/min `}
                <button className="link" onClick={() => onUse(Math.round(fromReport * 100) / 100)}>{t('runes.rateUse')}</button>
              </>
            )
            : <span className="warn"> {t('runes.rateNeedsEnd')}</span>}
        </small>
      )}
    </div>
  );
}
