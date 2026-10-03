import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import FolderTree from '../components/FolderTree.jsx';
import Shop from '../components/Shop.jsx';
import BuildBar from '../components/BuildBar.jsx';
import TimelineTable from '../components/TimelineTable.jsx';
import StatChart from '../components/StatChart.jsx';
import { SERIES_COLORS, statLabel } from '../format.js';

const emptyBuild = (folderId) => ({
  id: null,
  folderId,
  name: 'Nova build',
  note: '',
  unitCode: 'ORNN',
  goldPerMin: '',
  xpPerMin: '',
  itemIds: [],
  ragdollStats: {},
  includeConditional: true,
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
  return { ...b, note: b.note ?? '', ragdollStats };
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
  const [message, setMessage] = useState(null);

  const itemsById = useMemo(() => new Map(items.map((i) => [i.id, i])), [items]);

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
    if (!draft) return;
    if (!(Number(draft.goldPerMin) > 0) || draft.xpPerMin === '' || Number(draft.xpPerMin) < 0) {
      setResult(null);
      setCalcError(null);
      return;
    }
    const seq = ++calcSeq.current;
    const t = setTimeout(async () => {
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
    return () => clearTimeout(t);
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

  const confirmDiscard = () => !dirty || window.confirm('Descartar alterações não salvas da build atual?');

  const openBuild = async (id) => {
    if (!confirmDiscard()) return;
    try {
      const b = await api.get(`/api/builds/${id}`);
      setDraft(fromApi(b));
      setDirty(false);
      setCompareIds((c) => c.filter((x) => x !== id));
    } catch (e) {
      setMessage(e.message);
    }
  };

  const newBuild = (folderId) => {
    if (!confirmDiscard()) return;
    setDraft(emptyBuild(folderId));
    setDirty(false);
  };

  const save = async () => {
    try {
      const payload = toPayload(draft);
      const saved = draft.id ? await api.put(`/api/builds/${draft.id}`, payload) : await api.post('/api/builds', payload);
      setDraft(fromApi(saved));
      setDirty(false);
      setMessage('Build salva.');
      await loadTree();
    } catch (e) {
      setMessage(`Erro ao salvar: ${e.message}`);
    }
  };

  const saveAsCopy = () => {
    setDraft((d) => ({ ...d, id: null, name: `${d.name} (cópia)` }));
    setDirty(true);
  };

  const remove = async () => {
    if (!draft.id || !window.confirm(`Apagar a build "${draft.name}"?`)) return;
    try {
      await api.del(`/api/builds/${draft.id}`);
      setDraft(emptyBuild(draft.folderId));
      setDirty(false);
      await loadTree();
    } catch (e) {
      setMessage(e.message);
    }
  };

  const moveItem = (idx, delta) =>
    update((d) => {
      const ids = [...d.itemIds];
      const j = idx + delta;
      if (j < 0 || j >= ids.length) return {};
      [ids[idx], ids[j]] = [ids[j], ids[idx]];
      return { itemIds: ids };
    });

  const toggleCompare = (id) => setCompareIds((c) => (c.includes(id) ? c.filter((x) => x !== id) : [...c, id]));

  if (!draft) return null;

  const buildName = (id) => builds.find((b) => b.id === id)?.name ?? `#${id}`;
  const chartSeries = [
    { key: 'active', name: draft.name + (dirty ? ' (não salva)' : ''), result, colorIndex: 0 },
    ...compareIds.map((id, i) => ({ key: id, name: buildName(id), result: compareResults[id], colorIndex: i + 1 })),
  ];
  const warnings = [...(result?.warnings ?? [])];
  const isRagdoll = draft.unitCode === 'RAGDOLL';
  const canCalculate = Number(draft.goldPerMin) > 0 && draft.xpPerMin !== '' && Number(draft.xpPerMin) >= 0;
  const payload = toPayload(draft);

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
            <button className="link" onClick={() => setMessage(null)}>fechar</button>
          </div>
        )}

        <section className="panel build-head">
          <div className="fields">
            <label className="field grow">Nome
              <input value={draft.name} onChange={(e) => update({ name: e.target.value })} />
            </label>
            <label className="field">Pasta
              <select value={draft.folderId ?? ''} onChange={(e) => update({ folderId: Number(e.target.value) })}>
                {folders.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
              </select>
            </label>
            <label className="field">Unidade
              <select value={draft.unitCode} onChange={(e) => update({ unitCode: e.target.value })}>
                {meta.units.map((u) => <option key={u.code} value={u.code}>{u.name}</option>)}
              </select>
            </label>
            <label className="field narrow">Ouro inicial
              <input type="number" value={meta.startingGold} disabled title="Fixo em 500" />
            </label>
            <label className="field narrow">Ouro/min
              <input type="number" min="1" value={draft.goldPerMin} placeholder="ex.: 350" onChange={(e) => update({ goldPerMin: e.target.value })} />
            </label>
            <label className="field narrow">XP/min
              <input type="number" min="0" value={draft.xpPerMin} placeholder="ex.: 450" onChange={(e) => update({ xpPerMin: e.target.value })} />
            </label>
            <label className="check toggle" title="Passivas que só valem em certas situações: acúmulos em combate, vida baixa, carga de mana...">
              <input type="checkbox" checked={draft.includeConditional !== false} onChange={(e) => update({ includeConditional: e.target.checked })} />
              efeitos condicionais
            </label>
            <div className="row build-actions">
              {dirty && <small>não salva</small>}
              {draft.id && <button onClick={saveAsCopy}>Duplicar</button>}
              {draft.id && <button className="danger" onClick={remove}>Apagar</button>}
              <button className="primary" onClick={save} disabled={!dirty && draft.id != null}>Salvar</button>
            </div>
          </div>
          <details className="build-extra" open={isRagdoll || undefined}>
            <summary>Nota{isRagdoll ? ' e status do boneco de pano' : ''}{draft.note ? ' •' : ''}</summary>
            <textarea rows={2} value={draft.note} placeholder="Anotações livres sobre esta build" aria-label="Nota" onChange={(e) => update({ note: e.target.value })} />
            {isRagdoll && (
              <RagdollEditor meta={meta} stats={draft.ragdollStats} onChange={(ragdollStats) => update({ ragdollStats })} />
            )}
          </details>
        </section>

        <section className="panel shop-panel">
          <Shop
            items={items}
            buildPayload={canCalculate ? payload : null}
            ownedIds={draft.itemIds}
            onAdd={(id) => update((d) => ({ itemIds: [...d.itemIds, id] }))}
          />
          <BuildBar
            itemIds={draft.itemIds}
            itemsById={itemsById}
            steps={result?.steps}
            onMove={moveItem}
            onRemove={(idx) => update((d) => ({ itemIds: d.itemIds.filter((_, k) => k !== idx) }))}
            onClear={() => update({ itemIds: [] })}
          />
        </section>

        <section className="panel">
          <h3>Linha do tempo</h3>
          <p className="muted" style={{ marginTop: 0 }}>
            Nível derivado de uma <strong>tabela de XP estimada</strong> (editável no Admin). Ouro inicial fixo em {meta.startingGold}.
          </p>
          {!result && !calcError && <p className="muted">Informe ouro/min e XP/min para calcular.</p>}
          {calcError && <p className="error">{calcError}</p>}
          {warnings.length > 0 && (
            <div className="notice"><ul>{warnings.map((w) => <li key={w}>{w}</li>)}</ul></div>
          )}
          {result && compareIds.length === 0 && <TimelineTable result={result} />}
          {result && compareIds.length > 0 && (
            <div className="compare-grid">
              {chartSeries.map((s) => (
                <div key={s.key}>
                  <h4><span className="swatch" style={{ background: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length] }} />{s.name}</h4>
                  {s.result ? <TimelineTable result={s.result} compact /> : <p className="muted">Calculando…</p>}
                </div>
              ))}
            </div>
          )}
        </section>

        {result && (
          <section className="panel">
            <div className="spread">
              <h3>{statLabel(chartStat)} ao longo do tempo</h3>
              <small>Marque “comparar” em builds salvas na barra lateral para sobrepor.</small>
            </div>
            <StatChart series={chartSeries} stat={chartStat} onStatChange={setChartStat} />
          </section>
        )}
      </main>
    </div>
  );
}

function RagdollEditor({ meta, stats, onChange }) {
  const set = (stat, field, value) => onChange({ ...stats, [stat]: { base: '', growth: '', ...stats[stat], [field]: value } });
  return (
    <div style={{ marginTop: 12 }}>
      <h4>Status do boneco de pano (por build, sem valores padrão)</h4>
      <p className="muted" style={{ marginTop: 0 }}>Campos em branco contam como 0. Sem Forja Viva. Valor no nível L = base + crescimento × (L − 1).</p>
      <div className="table-wrap">
        <table className="data">
          <thead><tr><th className="l">Status</th><th>Base</th><th>Crescimento/nível</th></tr></thead>
          <tbody>
            {meta.unitStats.map((s) => (
              <tr key={s}>
                <td className="l">{statLabel(s)}</td>
                <td><input type="number" value={stats[s]?.base ?? ''} onChange={(e) => set(s, 'base', e.target.value)} aria-label={`${statLabel(s)} base`} /></td>
                <td><input type="number" value={stats[s]?.growth ?? ''} onChange={(e) => set(s, 'growth', e.target.value)} aria-label={`${statLabel(s)} crescimento`} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
