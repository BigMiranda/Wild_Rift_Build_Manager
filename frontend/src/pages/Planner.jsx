import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import { statLabel, t, violationText } from '../i18n.js';
import FolderTree from '../components/FolderTree.jsx';
import Shop from '../components/Shop.jsx';
import BuildBar from '../components/BuildBar.jsx';
import TimelineTable from '../components/TimelineTable.jsx';
import StatChart from '../components/StatChart.jsx';
import { PassivesPanel, RelevanceReport, StatSheet, pointAt } from '../components/MomentPanels.jsx';
import { PASSIVES_VIEW } from '../passives.js';
import { ALIGN_ITEM, EFFECTIVE_VIEW, PASSIVE_GOLD_BY_TIER, effectiveGold } from '../effective.js';
import EffectiveBreakdown, { AlignToggle } from '../components/EffectiveBreakdown.jsx';
import { StatIcon } from '../components/StatIcon.jsx';
import { SERIES_COLORS, mmss, n0 } from '../format.js';
import { evolutionPairs } from '../shopModel.js';
import { emptyRunePage, setRuneCatalog } from '../runes.js';
import PrepPanel from '../components/PrepPanel.jsx';
import MinuteInput from '../components/MinuteInput.jsx';
import ChampionPanel, { emptyChampionSetup } from '../components/ChampionPanel.jsx';
import ChampionPicker from '../components/ChampionPicker.jsx';
import ItemEffectsPanel, { emptyItemSetup } from '../components/ItemEffectsPanel.jsx';
import RuneEditor from '../components/RuneEditor.jsx';
import SpellEditor from '../components/SpellEditor.jsx';

const emptyBuild = (folderId) => ({
  id: null,
  folderId,
  name: t('build.new'),
  note: '',
  unitCode: 'ORNN',
  goldPerMin: '',
  xpPerMin: '',
  steps: [],
  assumeHalfItems: false,
  assumeSmallItems: false,
  runePage: emptyRunePage(),
  championSetup: emptyChampionSetup(),
  itemSetup: emptyItemSetup(),
  spells: [],
  ragdollStats: {},
});

const SIDEBAR_KEY = 'ornn-planner-sidebar';
const loadSidebar = () => {
  try {
    return localStorage.getItem(SIDEBAR_KEY) !== 'collapsed';
  } catch {
    return true;
  }
};

const numOrNull = (v) => (v === '' || v == null ? null : Number(v));

/** Converts the editor state to the API payload (numbers, blanks as null). */
function toPayload(d) {
  const ragdollStats = {};
  for (const [k, v] of Object.entries(d.ragdollStats ?? {})) {
    ragdollStats[k] = { base: numOrNull(v.base), growth: numOrNull(v.growth) };
  }
  return { ...d, goldPerMin: Number(d.goldPerMin), xpPerMin: Number(d.xpPerMin), ragdollStats };
}

function fromApi(b) {
  const ragdollStats = {};
  for (const [k, v] of Object.entries(b.ragdollStats ?? {})) {
    ragdollStats[k] = { base: v.base ?? '', growth: v.growth ?? '' };
  }
  return { ...b, note: b.note ?? '', steps: b.steps ?? [], ragdollStats, runePage: b.runePage ?? emptyRunePage(),
    championSetup: b.championSetup ?? emptyChampionSetup(), itemSetup: b.itemSetup ?? emptyItemSetup(), spells: b.spells ?? [] };
}

export default function Planner({ meta, items }) {
  const [folders, setFolders] = useState([]);
  const [builds, setBuilds] = useState([]);
  const [draft, setDraft] = useState(null);
  const [dirty, setDirty] = useState(false);
  const [result, setResult] = useState(null);
  const [calcError, setCalcError] = useState(null);
  const [compareIds, setCompareIds] = useState([]);
  const [compareResults, setCompareResults] = useState({});
  const [chartStat, setChartStat] = useState('Max Health');
  /** Tab of the moment panel next to the stat sheet: item relevance or passives held. */
  const [momentTab, setMomentTab] = useState('relevance');
  /** Rune / spell catalog loaded, and which "Preparações" editor is open (runes | spells). */
  const [runesReady, setRunesReady] = useState(false);
  const [prepEditor, setPrepEditor] = useState(null);
  const shopRef = useRef(null);
  useEffect(() => {
    api.get('/api/runes').then((c) => { setRuneCatalog(c); setRunesReady(true); }).catch(() => {});
  }, []);
  /** Effective gold of compared builds: same item on the same row, or by place in the build. */
  const [effAlign, setEffAlign] = useState(ALIGN_ITEM);
  const [selectedMinute, setSelectedMinute] = useState(null);
  /** Purchase selected in the sequence (index in draft.steps), shown in the shop's detail panel. */
  const [selectedIdx, setSelectedIdx] = useState(null);
  const [message, setMessage] = useState(null);
  const [sidebarOpen, setSidebarOpen] = useState(loadSidebar);
  const toggleSidebar = () => {
    setSidebarOpen((open) => {
      try {
        localStorage.setItem(SIDEBAR_KEY, open ? 'collapsed' : 'open');
      } catch {
        /* storage unavailable: keep it for this session only */
      }
      return !open;
    });
  };

  const itemsById = useMemo(() => new Map(items.map((i) => [i.id, i])), [items]);
  const evoPairs = useMemo(() => evolutionPairs(items), [items]);

  const loadTree = useCallback(async () => {
    const [f, b] = await Promise.all([api.get('/api/folders'), api.get('/api/builds')]);
    setFolders(f);
    setBuilds(b);
    return f;
  }, []);

  useEffect(() => {
    loadTree().then((f) => setDraft((d) => d ?? emptyBuild(f[0]?.id)));
  }, [loadTree]);

  // Live recalculation of the (possibly unsaved) active build.
  const calcSeq = useRef(0);
  useEffect(() => {
    if (!draft) return undefined;
    if (!(Number(draft.goldPerMin) > 0) || draft.xpPerMin === '' || Number(draft.xpPerMin) < 0) {
      setResult(null);
      setCalcError(null);
      return undefined;
    }
    const seq = ++calcSeq.current;
    const timer = setTimeout(async () => {
      try {
        const r = await api.post('/api/calculate', toPayload(draft));
        if (seq === calcSeq.current) {
          setResult(r);
          setCalcError(null);
        }
      } catch (e) {
        if (seq === calcSeq.current) setCalcError(e.message);
      }
    }, 250);
    return () => clearTimeout(timer);
  }, [draft, items]);

  // Saved builds added for comparison.
  useEffect(() => {
    compareIds.forEach(async (id) => {
      try {
        const r = await api.get(`/api/builds/${id}/timeline`);
        setCompareResults((prev) => ({ ...prev, [id]: r }));
      } catch (e) {
        setMessage(e.message);
      }
    });
  }, [compareIds, items, builds]);

  /** `patch` is an object or a function of the current draft (use the latter when it depends on the draft). */
  const update = (patch) => {
    setDraft((d) => ({ ...d, ...(typeof patch === 'function' ? patch(d) : patch) }));
    setDirty(true);
  };
  const updateStep = (idx, fn) => update((d) => ({ steps: d.steps.map((s, k) => (k === idx ? fn(s) : s)) }));

  const confirmDiscard = () => !dirty || window.confirm(t('build.discardConfirm'));

  const openBuild = async (id) => {
    if (!confirmDiscard()) return;
    try {
      const b = await api.get(`/api/builds/${id}`);
      setDraft(fromApi(b));
      setDirty(false);
      setSelectedMinute(null);
      setSelectedIdx(null);
      setCompareIds((c) => c.filter((x) => x !== id));
    } catch (e) {
      setMessage(e.message);
    }
  };

  const newBuild = (folderId) => {
    if (!confirmDiscard()) return;
    setDraft(emptyBuild(folderId));
    setDirty(false);
    setSelectedMinute(null);
    setSelectedIdx(null);
  };

  const save = async () => {
    try {
      const payload = toPayload(draft);
      const saved = draft.id ? await api.put(`/api/builds/${draft.id}`, payload) : await api.post('/api/builds', payload);
      setDraft(fromApi(saved));
      setDirty(false);
      setMessage(t('build.saved'));
      await loadTree();
    } catch (e) {
      setMessage(t('build.saveError', { msg: e.message }));
    }
  };

  const saveAsCopy = () => {
    setDraft((d) => ({ ...d, id: null, name: `${d.name}${t('build.copySuffix')}` }));
    setDirty(true);
  };

  const remove = async () => {
    if (!draft.id || !window.confirm(t('build.deleteConfirm', { name: draft.name }))) return;
    try {
      await api.del(`/api/builds/${draft.id}`);
      setDraft(emptyBuild(draft.folderId));
      setDirty(false);
      await loadTree();
    } catch (e) {
      setMessage(e.message);
    }
  };

  /** Moves the purchase at `from` to position `to`; the selection follows the purchase it was on. */
  const moveStepTo = (from, to) => {
    if (to < 0 || to >= draft.steps.length || from === to) return;
    update((d) => {
      const steps = [...d.steps];
      const [moved] = steps.splice(from, 1);
      steps.splice(to, 0, moved);
      return { steps };
    });
    setSelectedIdx((sel) => {
      if (sel == null) return sel;
      if (sel === from) return to;
      if (from < sel && sel <= to) return sel - 1;
      if (to <= sel && sel < from) return sel + 1;
      return sel;
    });
  };

  /** Adds a purchase moment before the selected purchase (or at the end): by default 2 minutes after the previous one. */
  const addMoment = () => {
    const at = selectedIdx ?? draft.steps.length;
    update((d) => {
      const steps = [...d.steps];
      steps.splice(at, 0, { kind: 'moment', atMinute: null, afterMinutes: 2 });
      return { steps };
    });
    if (selectedIdx != null) setSelectedIdx(selectedIdx + 1);
  };

  const removeStep = (idx) => {
    update((d) => ({ steps: d.steps.filter((_, k) => k !== idx) }));
    setSelectedIdx((sel) => (sel == null || sel < idx ? sel : sel === idx ? null : sel - 1));
  };

  /**
   * Adds a purchase unless the game would not allow it (checked by the engine on the resulting inventory).
   * Over the item limit it is still added: the engine leaves it out of the calculation and the sequence marks it.
   */
  const addItem = async (id) => {
    const steps = [...draft.steps, { itemId: id, includeConditional: true }];
    if (canCalculate) {
      try {
        const r = await api.post('/api/calculate', { ...payload, steps });
        const last = r.steps.find((x) => x.buildIndex === steps.length - 1 && !x.implied);
        if (last?.violations?.length) {
          setMessage(t('rule.blocked', { name: itemsById.get(id)?.name ?? id, reason: last.violations.map(violationText).join(' · ') }));
          return;
        }
      } catch (e) {
        setMessage(e.message);
        return;
      }
    }
    update((d) => ({ steps: [...d.steps, { itemId: id, includeConditional: true }] }));
  };

  const toggleCompare = (id) => setCompareIds((c) => (c.includes(id) ? c.filter((x) => x !== id) : [...c, id]));

  if (!draft) return null;

  const buildName = (id) => builds.find((b) => b.id === id)?.name ?? `#${id}`;
  const chartSeries = [
    { key: 'active', name: draft.name + (dirty ? t('chart.unsaved') : ''), result, colorIndex: 0 },
    ...compareIds.map((id, i) => ({ key: id, name: buildName(id), result: compareResults[id], colorIndex: i + 1 })),
  ];
  const warnings = [...(result?.warnings ?? [])];
  const isRagdoll = draft.unitCode === 'RAGDOLL';
  const canCalculate = Number(draft.goldPerMin) > 0 && draft.xpPerMin !== '' && Number(draft.xpPerMin) >= 0;
  const payload = toPayload(draft);

  // Moment inspected by the stat sheet and the relevance report: the clicked one, or the last purchase.
  const lastStep = result?.steps[result.steps.length - 1];
  const moment = selectedMinute ?? lastStep?.minute ?? null;
  const point = result && moment != null ? pointAt(result.series, moment) : null;

  return (
    <div className={`layout${sidebarOpen ? '' : ' collapsed'}`}>
      <aside className="sidebar" aria-label={t('folders.title')}>
        <button
          className="sidebar-toggle icon" onClick={toggleSidebar} aria-expanded={sidebarOpen}
          title={t(sidebarOpen ? 'sidebar.collapse' : 'sidebar.expand')} aria-label={t(sidebarOpen ? 'sidebar.collapse' : 'sidebar.expand')}
        >
          {sidebarOpen ? '«' : '»'}
        </button>
        {!sidebarOpen && <span className="sidebar-rail-label">{t('folders.title')}</span>}
        {sidebarOpen && <FolderTree
          folders={folders}
          builds={builds}
          activeId={draft.id}
          compareIds={compareIds}
          onOpen={openBuild}
          onNew={newBuild}
          onToggleCompare={toggleCompare}
          onChanged={loadTree}
          onError={setMessage}
        />}
      </aside>

      <main className="main">
        {message && (
          <div className="notice spread">
            <span>{message}</span>
            <button className="link" onClick={() => setMessage(null)}>{t('close')}</button>
          </div>
        )}

        <section className="panel build-head">
          <div className="fields">
            <label className="field grow">{t('build.name')}
              <input value={draft.name} onChange={(e) => update({ name: e.target.value })} />
            </label>
            <label className="field">{t('build.folder')}
              <select value={draft.folderId ?? ''} onChange={(e) => update({ folderId: Number(e.target.value) })}>
                {folders.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
              </select>
            </label>
            <div className="field champ-field">{t('build.unit')}
              <ChampionPicker units={meta.units} value={draft.unitCode} onChange={(unitCode) => update({ unitCode })} />
            </div>
            <label className="field narrow">{t('build.startGold')}
              <input type="number" value={meta.startingGold} disabled title={t('build.startGoldTitle')} />
            </label>
            <label className="field narrow">{t('build.gpm')}
              <input type="number" min="1" value={draft.goldPerMin} placeholder="350" onChange={(e) => update({ goldPerMin: e.target.value })} />
            </label>
            <label className="field narrow">{t('build.xpm')}
              <input type="number" min="0" value={draft.xpPerMin} placeholder="450" onChange={(e) => update({ xpPerMin: e.target.value })} />
            </label>
            <label className="field narrow" title={t('build.matchEndTitle')}>{t('build.matchEnd')}
              <MinuteInput value={draft.matchEnd ?? null} onChange={(matchEnd) => update({ matchEnd })} />
            </label>
            <div className="row build-actions">
              {dirty && <small>{t('build.unsaved')}</small>}
              {draft.id && <button onClick={saveAsCopy}>{t('build.duplicate')}</button>}
              {draft.id && <button className="danger" onClick={remove}>{t('build.delete')}</button>}
              <button className="primary" onClick={save} disabled={!dirty && draft.id != null}>{t('build.save')}</button>
            </div>
          </div>
          <div className="row assume-opts">
            <label className="check" title={t('build.assumeHalfTitle')}>
              <input type="checkbox" checked={!!draft.assumeHalfItems} onChange={(e) => update({ assumeHalfItems: e.target.checked })} />
              {t('build.assumeHalf')}
            </label>
            <label className="check" title={t('build.assumeSmallTitle')}>
              <input type="checkbox" checked={!!draft.assumeSmallItems} onChange={(e) => update({ assumeSmallItems: e.target.checked })} />
              {t('build.assumeSmall')}
            </label>
          </div>
          <details className="build-extra" open={isRagdoll || undefined}>
            <summary>{isRagdoll ? t('build.noteAndRagdoll') : t('build.note')}{draft.note ? ' •' : ''}</summary>
            <textarea rows={2} value={draft.note} placeholder={t('build.notePlaceholder')} aria-label={t('build.note')} onChange={(e) => update({ note: e.target.value })} />
            {isRagdoll && (
              <RagdollEditor meta={meta} stats={draft.ragdollStats} onChange={(ragdollStats) => update({ ragdollStats })} />
            )}
          </details>
        </section>

        {!isRagdoll && (
          <ChampionPanel code={draft.unitCode} setup={draft.championSetup} matchEnd={draft.matchEnd} point={point}
            onChange={(championSetup) => update({ championSetup })} />
        )}

        {runesReady && (
          <PrepPanel
            inventoryIds={result?.steps?.[result.steps.length - 1]?.inventoryIds ?? []}
            itemsById={itemsById}
            runePage={draft.runePage}
            spells={draft.spells}
            onItems={() => shopRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
            onRunes={() => setPrepEditor('runes')}
            onSpells={() => setPrepEditor('spells')}
          />
        )}

        <section className="panel shop-panel" ref={shopRef}>
          <Shop
            items={items}
            buildPayload={canCalculate ? payload : null}
            ownedIds={draft.steps.filter((s) => s.kind !== 'moment').map((s) => s.itemId)}
            purchase={selectedIdx != null && draft.steps[selectedIdx] && draft.steps[selectedIdx].kind !== 'moment' ? {
              index: selectedIdx,
              number: draft.steps.slice(0, selectedIdx + 1).filter((s) => s.kind !== 'moment').length,
              itemId: draft.steps[selectedIdx].itemId,
              step: result?.steps.find((x) => x.buildIndex === selectedIdx && !x.implied) ?? null,
              ignored: result?.ignored?.find((x) => x.buildIndex === selectedIdx) ?? null,
              implied: result?.steps.filter((x) => x.buildIndex === selectedIdx && x.implied) ?? [],
            } : null}
            onPurchaseClear={() => setSelectedIdx(null)}
            onAdd={addItem}
          />
          <BuildBar
            steps={draft.steps}
            itemsById={itemsById}
            evoPairs={evoPairs}
            timeline={result?.steps}
            ignored={result?.ignored}
            moments={result?.moments}
            onAddMoment={addMoment}
            onUpdateMoment={(idx, patch) => updateStep(idx, (s) => ({ ...s, ...patch }))}
            selectedIdx={selectedIdx}
            onSelect={setSelectedIdx}
            onMove={(idx, delta) => moveStepTo(idx, idx + delta)}
            onMoveTo={moveStepTo}
            onRemove={removeStep}
            onClear={() => { update({ steps: [] }); setSelectedIdx(null); }}
            onToggleCond={(idx) => updateStep(idx, (s) => ({ ...s, includeConditional: !s.includeConditional }))}
            onToggleEvolved={(idx) => updateStep(idx, (s) => ({ ...s, itemId: evoPairs.get(s.itemId)?.id ?? s.itemId }))}
          />
          <ItemEffectsPanel steps={draft.steps} itemsById={itemsById} setup={draft.itemSetup} matchEnd={draft.matchEnd}
            onChange={(itemSetup) => update({ itemSetup })} />
        </section>

        <section className="panel">
          <h3>{t('timeline.title')}</h3>
          <p className="muted" style={{ marginTop: 0 }} dangerouslySetInnerHTML={{ __html: t('timeline.xpNote', { gold: meta.startingGold }) }} />
          {!result && !calcError && <p className="muted">{t('timeline.needInputs')}</p>}
          {calcError && <p className="error">{calcError}</p>}
          {warnings.length > 0 && (
            <div className="notice"><ul>{warnings.map((w) => <li key={w}>{w}</li>)}</ul></div>
          )}
          {result && compareIds.length === 0 && <TimelineTable result={result} itemsById={itemsById} />}
          {result && compareIds.length > 0 && (
            <div className="compare-grid">
              {chartSeries.map((s) => (
                <div key={s.key}>
                  <h4><span className="swatch" style={{ background: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length] }} />{s.name}</h4>
                  {s.result ? <TimelineTable result={s.result} itemsById={itemsById} compact /> : <p className="muted">{t('compare.calculating')}</p>}
                </div>
              ))}
            </div>
          )}
        </section>

        {result && (
          <section className="panel">
            <div className="spread">
              <h3>
                {chartStat === EFFECTIVE_VIEW ? t('chart.effectiveTitle') : chartStat === PASSIVES_VIEW ? t('chart.passivesTitle')
                  : <><StatIcon stat={chartStat} size={16} />{t('chart.title', { stat: statLabel(chartStat) })}</>}
              </h3>
              <small>{t('chart.compareHint')}</small>
            </div>
            <StatChart
              series={chartSeries} stat={chartStat} onStatChange={setChartStat} xpTable={meta.xpTable}
              selectedMinute={moment} onSelectMinute={setSelectedMinute} itemsById={itemsById}
              align={effAlign} onAlignChange={setEffAlign}
            />
          </section>
        )}

        {point && (
          <div className="grid-2 moment-panels">
            <StatSheet point={point} itemsById={itemsById} />
            <div className="moment-tabbed">
              <div className="chips moment-tabs" role="tablist" aria-label={t('moment.tabs')}>
                {['relevance', 'passives', 'effective'].map((k) => (
                  <button key={k} role="tab" aria-selected={momentTab === k} className={momentTab === k ? 'active' : ''} onClick={() => setMomentTab(k)}>
                    {t(`moment.tab.${k}`)}
                  </button>
                ))}
              </div>
              {momentTab === 'relevance' && <RelevanceReport point={point} prices={result.statPrices} itemsById={itemsById} />}
              {momentTab === 'effective' && (
                <section className="panel">
                  <h3>{t('eff.title', { time: mmss(point.minute), level: point.level })}</h3>
                  <p className="muted" style={{ marginTop: 0 }}>{t('eff.help', { mid: n0(PASSIVE_GOLD_BY_TIER.tier_medio), full: n0(PASSIVE_GOLD_BY_TIER.aprimorado) })}</p>
                  {compareIds.length > 0 && <AlignToggle value={effAlign} onChange={setEffAlign} />}
                  <EffectiveBreakdown
                    align={effAlign}
                    columns={chartSeries.filter((s) => s.result).map((s) => ({
                      key: s.key, name: s.name, color: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length],
                      eff: effectiveGold(pointAt(s.result.series, point.minute), itemsById, s.result.statPrices),
                    }))}
                  />
                </section>
              )}
              {momentTab === 'passives' && (
                  <PassivesPanel
                    point={point} series={result.series} itemsById={itemsById}
                    builds={chartSeries.filter((s) => s.result).map((s) => ({
                      key: s.key, name: s.name, color: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length], series: s.result.series,
                    }))}
                  />
                )}
            </div>
          </div>
        )}
      </main>
      {prepEditor === 'runes' && (
        <RuneEditor page={draft.runePage} matchEnd={draft.matchEnd} onChange={(runePage) => update({ runePage })} onClose={() => setPrepEditor(null)} />
      )}
      {prepEditor === 'spells' && (
        <SpellEditor spells={draft.spells} onChange={(spells) => update({ spells })} onClose={() => setPrepEditor(null)} />
      )}
    </div>
  );
}

function RagdollEditor({ meta, stats, onChange }) {
  const set = (stat, field, value) => onChange({ ...stats, [stat]: { base: '', growth: '', ...stats[stat], [field]: value } });
  return (
    <div style={{ marginTop: 12 }}>
      <h4>{t('ragdoll.title')}</h4>
      <p className="muted" style={{ marginTop: 0 }}>{t('ragdoll.help')}</p>
      <div className="table-wrap">
        <table className="data">
          <thead><tr><th className="l">{t('ragdoll.stat')}</th><th>{t('ragdoll.base')}</th><th>{t('ragdoll.growth')}</th></tr></thead>
          <tbody>
            {meta.unitStats.map((s) => (
              <tr key={s}>
                <td className="l"><StatIcon stat={s} />{statLabel(s)}</td>
                <td><input type="number" value={stats[s]?.base ?? ''} onChange={(e) => set(s, 'base', e.target.value)} aria-label={`${statLabel(s)} ${t('ragdoll.base')}`} /></td>
                <td><input type="number" value={stats[s]?.growth ?? ''} onChange={(e) => set(s, 'growth', e.target.value)} aria-label={`${statLabel(s)} ${t('ragdoll.growth')}`} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
