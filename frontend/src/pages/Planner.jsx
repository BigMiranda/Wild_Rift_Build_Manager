import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import { statLabel, t, violationText } from '../i18n.js';
import FolderTree from '../components/FolderTree.jsx';
import Shop from '../components/Shop.jsx';
import BuildBar from '../components/BuildBar.jsx';
import TimelineTable from '../components/TimelineTable.jsx';
import StatChart from '../components/StatChart.jsx';
import { RelevanceReport, StatSheet, pointAt } from '../components/MomentPanels.jsx';
import { StatIcon } from '../components/StatIcon.jsx';
import { SERIES_COLORS } from '../format.js';
import { evolutionPairs } from '../shopModel.js';

const emptyBuild = (folderId) => ({
  id: null,
  folderId,
  name: t('build.new'),
  note: '',
  unitCode: 'ORNN',
  goldPerMin: '',
  xpPerMin: '',
  steps: [],
  ragdollStats: {},
});

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
  return { ...b, note: b.note ?? '', steps: b.steps ?? [], ragdollStats };
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
  const [selectedMinute, setSelectedMinute] = useState(null);
  const [message, setMessage] = useState(null);

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

  const moveStep = (idx, delta) =>
    update((d) => {
      const steps = [...d.steps];
      const j = idx + delta;
      if (j < 0 || j >= steps.length) return {};
      [steps[idx], steps[j]] = [steps[j], steps[idx]];
      return { steps };
    });

  /** Adds a purchase unless the game would not allow it (checked by the engine on the resulting inventory). */
  const addItem = async (id) => {
    const steps = [...draft.steps, { itemId: id, includeConditional: true }];
    if (canCalculate) {
      try {
        const r = await api.post('/api/calculate', { ...payload, steps });
        const last = r.steps[r.steps.length - 1];
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
    <div className="layout">
      <aside className="sidebar">
        <FolderTree
          folders={folders}
          builds={builds}
          activeId={draft.id}
          compareIds={compareIds}
          onOpen={openBuild}
          onNew={newBuild}
          onToggleCompare={toggleCompare}
          onChanged={loadTree}
          onError={setMessage}
        />
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
            <label className="field">{t('build.unit')}
              <select value={draft.unitCode} onChange={(e) => update({ unitCode: e.target.value })}>
                {meta.units.map((u) => <option key={u.code} value={u.code}>{u.name}</option>)}
              </select>
            </label>
            <label className="field narrow">{t('build.startGold')}
              <input type="number" value={meta.startingGold} disabled title={t('build.startGoldTitle')} />
            </label>
            <label className="field narrow">{t('build.gpm')}
              <input type="number" min="1" value={draft.goldPerMin} placeholder="350" onChange={(e) => update({ goldPerMin: e.target.value })} />
            </label>
            <label className="field narrow">{t('build.xpm')}
              <input type="number" min="0" value={draft.xpPerMin} placeholder="450" onChange={(e) => update({ xpPerMin: e.target.value })} />
            </label>
            <div className="row build-actions">
              {dirty && <small>{t('build.unsaved')}</small>}
              {draft.id && <button onClick={saveAsCopy}>{t('build.duplicate')}</button>}
              {draft.id && <button className="danger" onClick={remove}>{t('build.delete')}</button>}
              <button className="primary" onClick={save} disabled={!dirty && draft.id != null}>{t('build.save')}</button>
            </div>
          </div>
          <details className="build-extra" open={isRagdoll || undefined}>
            <summary>{isRagdoll ? t('build.noteAndRagdoll') : t('build.note')}{draft.note ? ' •' : ''}</summary>
            <textarea rows={2} value={draft.note} placeholder={t('build.notePlaceholder')} aria-label={t('build.note')} onChange={(e) => update({ note: e.target.value })} />
            {isRagdoll && (
              <RagdollEditor meta={meta} stats={draft.ragdollStats} onChange={(ragdollStats) => update({ ragdollStats })} />
            )}
          </details>
        </section>

        <section className="panel shop-panel">
          <Shop
            items={items}
            buildPayload={canCalculate ? payload : null}
            ownedIds={draft.steps.map((s) => s.itemId)}
            onAdd={addItem}
          />
          <BuildBar
            steps={draft.steps}
            itemsById={itemsById}
            evoPairs={evoPairs}
            timeline={result?.steps}
            onMove={moveStep}
            onRemove={(idx) => update((d) => ({ steps: d.steps.filter((_, k) => k !== idx) }))}
            onClear={() => update({ steps: [] })}
            onToggleCond={(idx) => updateStep(idx, (s) => ({ ...s, includeConditional: !s.includeConditional }))}
            onToggleEvolved={(idx) => updateStep(idx, (s) => ({ ...s, itemId: evoPairs.get(s.itemId)?.id ?? s.itemId }))}
          />
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
              <h3><StatIcon stat={chartStat} size={16} />{t('chart.title', { stat: statLabel(chartStat) })}</h3>
              <small>{t('chart.compareHint')}</small>
            </div>
            <StatChart
              series={chartSeries} stat={chartStat} onStatChange={setChartStat} xpTable={meta.xpTable}
              selectedMinute={moment} onSelectMinute={setSelectedMinute}
            />
          </section>
        )}

        {point && (
          <div className="grid-2 moment-panels">
            <StatSheet point={point} itemsById={itemsById} />
            <RelevanceReport point={point} prices={result.statPrices} itemsById={itemsById} />
          </div>
        )}
      </main>
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
