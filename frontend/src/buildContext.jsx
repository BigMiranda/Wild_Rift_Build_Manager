import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { api } from './api.js';
import { t, violationText } from './i18n.js';
import { pointAt } from './components/MomentPanels.jsx';
import { emptyObjectives, useObjectiveRules, withObjectives } from './components/ObjectivesPicker.jsx';
import { ALIGN_ITEM } from './effective.js';
import { SERIES_COLORS } from './format.js';
import { evolutionPairs } from './shopModel.js';
import { emptyRunePage, setRuneCatalog } from './runes.js';
import { emptyChampionSetup } from './components/ChampionPanel.jsx';
import { emptyItemSetup } from './components/ItemEffectsPanel.jsx';
import { configurableItems, explicitItemSetup } from './components/ItemSetupExport.jsx';
import { useArchetypes } from './archetypes.js';
import { usePassiveValues } from './passiveValues.js';
import { MAX_COMPARED } from './components/QuickCompare.jsx';

/**
 * The open build of the whole app (Rodada 8): its draft, the minute selected, the builds in comparison, the quick
 * comparison and the tree of folders. Every super tab reads the same build, so changing tabs never changes it; the draft
 * of the open build stays in the browser, so a reload comes back to it.
 */

export const emptyBuild = (folderId) => ({
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

const SAME_CONDS_KEY = 'ornn-planner-same-conds';
const DRAFT_KEY = 'ornn-planner-draft';

export const loadFlag = (key, fallback) => {
  try {
    const v = localStorage.getItem(key);
    return v == null ? fallback : v === 'open';
  } catch {
    return fallback;
  }
};
export const saveFlag = (key, open) => {
  try {
    localStorage.setItem(key, open ? 'open' : 'closed');
  } catch {
    /* storage unavailable: keep it for this session only */
  }
};

const loadDraft = () => {
  try {
    const s = JSON.parse(localStorage.getItem(DRAFT_KEY) ?? 'null');
    return s && s.draft ? s : null;
  } catch {
    return null;
  }
};
const saveDraft = (value) => {
  try {
    if (value) localStorage.setItem(DRAFT_KEY, JSON.stringify(value));
    else localStorage.removeItem(DRAFT_KEY);
  } catch {
    /* storage full or unavailable: the draft lives in this session only */
  }
};

const numOrNull = (v) => (v === '' || v == null ? null : Number(v));

/** Converts the editor state to the API payload (numbers, blanks as null). */
export function toPayload(d) {
  const ragdollStats = {};
  for (const [k, v] of Object.entries(d.ragdollStats ?? {})) {
    ragdollStats[k] = { base: numOrNull(v.base), growth: numOrNull(v.growth) };
  }
  return { ...d, goldPerMin: Number(d.goldPerMin), xpPerMin: Number(d.xpPerMin), ragdollStats };
}

/** Choices maps (options, conditional, rates, gold) of `from` over those of `to`, by key; a null value removes the key. */
export function mergeChoices(to, from) {
  const out = { ...to };
  for (const k of ['options', 'conditional', 'rates', 'gold']) {
    if (!from?.[k]) continue;
    const m = { ...(to?.[k] ?? {}) };
    for (const [key, v] of Object.entries(from[k])) {
      if (v == null) delete m[key]; else m[key] = v;
    }
    out[k] = m;
  }
  return out;
}

/**
 * A compared build with the open build's conditionals: the choices of the items both have (explicit, defaults included),
 * the rune choices set on the open build and, for the same champion, its champion choices. The compared build's items,
 * runes and skills stay its own.
 */
export function withConditionals(b, d, itemsById) {
  const items = configurableItems(d.steps, itemsById);
  return {
    ...b,
    itemSetup: mergeChoices(b.itemSetup, explicitItemSetup(items, d.itemSetup)),
    runePage: b.runePage ? mergeChoices(b.runePage, d.runePage) : b.runePage,
    championSetup: b.unitCode === d.unitCode ? mergeChoices(b.championSetup, { ...d.championSetup, gold: undefined }) : b.championSetup,
  };
}

export function fromApi(b) {
  const ragdollStats = {};
  for (const [k, v] of Object.entries(b.ragdollStats ?? {})) {
    ragdollStats[k] = { base: v.base ?? '', growth: v.growth ?? '' };
  }
  return { ...b, note: b.note ?? '', steps: b.steps ?? [], ragdollStats, runePage: b.runePage ?? emptyRunePage(),
    championSetup: b.championSetup ?? emptyChampionSetup(), itemSetup: b.itemSetup ?? emptyItemSetup(), spells: b.spells ?? [] };
}

const BuildContext = createContext(null);
export const useBuild = () => useContext(BuildContext);

export function BuildProvider({ meta, items, children }) {
  const [folders, setFolders] = useState([]);
  const [builds, setBuilds] = useState([]);
  const archetypes = useArchetypes(builds);
  const stored = useMemo(loadDraft, []);
  const [draft, setDraft] = useState(stored?.draft ?? null);
  const [dirty, setDirty] = useState(!!stored?.dirty);
  const [result, setResult] = useState(null);
  const [calcError, setCalcError] = useState(null);
  const [compareIds, setCompareIds] = useState([]);
  const [compareResults, setCompareResults] = useState({});
  const [sameConds, setSameConds] = useState(() => loadFlag(SAME_CONDS_KEY, false));
  const [chartStat, setChartStat] = useState('Max Health');
  /** What the "Objectives" selector puts on top of the sheet and of the effectiveness fights. */
  const [objectives, setObjectives] = useState(emptyObjectives);
  const objectiveRules = useObjectiveRules();
  // The quick item comparison: a purchase of the sequence (null = the next purchase) and the extra candidates.
  const [quick, setQuick] = useState(null);
  const [runesReady, setRunesReady] = useState(false);
  const [prepEditor, setPrepEditor] = useState(null);
  const [effAlign, setEffAlign] = useState(ALIGN_ITEM);
  const [selectedMinute, setSelectedMinute] = useState(stored?.minute ?? null);
  /** Purchase selected in the sequence (index in draft.steps), shown in the shop's detail panel. */
  const [selectedIdx, setSelectedIdx] = useState(null);
  const [message, setMessage] = useState(null);
  /**
   * The conditionals switched in the header (Parte 5), over the build as saved: null = as the build has them. Applied
   * to every reading of the open build, never saved until the build is.
   */
  const [condOverride, setCondOverride] = useState(stored?.condOverride ?? null);

  useEffect(() => {
    api.get('/api/runes').then((c) => { setRuneCatalog(c); setRunesReady(true); }).catch(() => {});
  }, []);

  const itemsById = useMemo(() => new Map(items.map((i) => [i.id, i])), [items]);
  const evoPairs = useMemo(() => evolutionPairs(items), [items]);

  const loadTree = useCallback(async () => {
    const [f, b] = await Promise.all([api.get('/api/folders'), api.get('/api/builds')]);
    setFolders(f);
    setBuilds(b);
    return f;
  }, []);

  useEffect(() => {
    loadTree().then((f) => setDraft((d) => d ?? emptyBuild(f[0]?.id))).catch((e) => setMessage(e.message));
  }, [loadTree]);

  // The draft of the open build (and the minute) stays in the browser: a reload opens it where it was.
  useEffect(() => {
    if (!draft) return undefined;
    const timer = setTimeout(() => saveDraft({ draft, dirty, minute: selectedMinute, condOverride }), 300);
    return () => clearTimeout(timer);
  }, [draft, dirty, selectedMinute, condOverride]);

  /** The build as every screen reads it: the draft with the conditionals of the header. */
  const effective = useMemo(() => (draft && condOverride ? applyConditionals(draft, condOverride) : draft), [draft, condOverride]);

  // Live recalculation of the (possibly unsaved) active build.
  const calcSeq = useRef(0);
  useEffect(() => {
    if (!effective) return undefined;
    if (!(Number(effective.goldPerMin) > 0) || effective.xpPerMin === '' || Number(effective.xpPerMin) < 0) {
      setResult(null);
      setCalcError(null);
      return undefined;
    }
    const seq = ++calcSeq.current;
    const timer = setTimeout(async () => {
      try {
        const r = await api.post('/api/calculate', toPayload(effective));
        if (seq === calcSeq.current) {
          setResult(r);
          setCalcError(null);
        }
      } catch (e) {
        if (seq === calcSeq.current) setCalcError(e.message);
      }
    }, 250);
    return () => clearTimeout(timer);
  }, [effective, items]);

  // Saved builds added for comparison: as saved, or with the open build's conditionals ("same conditionals").
  const condSource = sameConds && effective
    ? { steps: effective.steps, itemSetup: effective.itemSetup, runePage: effective.runePage, championSetup: effective.championSetup, unitCode: effective.unitCode }
    : null;
  const condKey = JSON.stringify(condSource);
  const compareSeq = useRef(0);
  useEffect(() => {
    const seq = ++compareSeq.current;
    const timer = setTimeout(() => {
      compareIds.forEach(async (id) => {
        try {
          const r = condSource
            ? await api.get(`/api/builds/${id}`).then((b) => api.post('/api/calculate', toPayload(withConditionals(fromApi(b), condSource, itemsById))))
            : await api.get(`/api/builds/${id}/timeline`);
          if (seq === compareSeq.current) setCompareResults((prev) => ({ ...prev, [id]: r }));
        } catch (e) {
          setMessage(e.message);
        }
      });
    }, condSource ? 300 : 0);
    return () => clearTimeout(timer);
    // condKey stands for the open build's choices, so editing its other fields does not recalculate the compared builds
  }, [compareIds, items, builds, condKey]);

  const payload = effective ? toPayload(effective) : null;
  const passiveValues = usePassiveValues({ build: payload, result, compareIds, compareResults, itemsById });

  /** `patch` is an object or a function of the current draft (use the latter when it depends on the draft). */
  const update = useCallback((patch) => {
    setDraft((d) => ({ ...d, ...(typeof patch === 'function' ? patch(d) : patch) }));
    setDirty(true);
  }, []);
  const updateStep = (idx, fn) => update((d) => ({ steps: d.steps.map((s, k) => (k === idx ? fn(s) : s)) }));

  const confirmDiscard = () => !dirty || window.confirm(t('build.discardConfirm'));

  const openBuild = async (id, { minute, force } = {}) => {
    if (!force && !confirmDiscard()) return false;
    try {
      const b = await api.get(`/api/builds/${id}`);
      setDraft(fromApi(b));
      setDirty(false);
      setCondOverride(null);
      setSelectedMinute(minute ?? null);
      setSelectedIdx(null);
      setCompareIds((c) => c.filter((x) => x !== id));
      return true;
    } catch (e) {
      setMessage(e.message);
      return false;
    }
  };

  const newBuild = (folderId) => {
    if (!confirmDiscard()) return;
    setDraft(emptyBuild(folderId ?? folders[0]?.id));
    setDirty(false);
    setCondOverride(null);
    setSelectedMinute(null);
    setSelectedIdx(null);
  };

  const save = async () => {
    try {
      // the header's conditionals become the build's when it is saved
      const payloadToSave = toPayload(effective);
      const saved = draft.id ? await api.put(`/api/builds/${draft.id}`, payloadToSave) : await api.post('/api/builds', payloadToSave);
      setDraft(fromApi(saved));
      setDirty(false);
      setCondOverride(null);
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

  const canCalculate = !!draft && Number(draft.goldPerMin) > 0 && draft.xpPerMin !== '' && Number(draft.xpPerMin) >= 0;

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
        const skipped = r.ignored?.find((x) => x.buildIndex === steps.length - 1);
        const blocking = last?.violations?.length ? last.violations : skipped?.violations?.filter((v) => v.code === 'bootsTier') ?? [];
        if (blocking.length) {
          setMessage(t('rule.blocked', { name: itemsById.get(id)?.name ?? id, reason: blocking.map(violationText).join(' · ') }));
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
  const toggleQuickCandidate = (id) => setQuick((q) => {
    const ids = q?.ids ?? [];
    if (ids.includes(id)) return { slot: q.slot, ids: ids.filter((x) => x !== id) };
    return ids.length >= MAX_COMPARED ? q : { slot: q?.slot ?? null, ids: [...ids, id] };
  });

  const buildName = (id) => builds.find((b) => b.id === id)?.name ?? `#${id}`;
  const chartSeries = draft ? [
    { key: 'active', name: draft.name + (dirty ? t('chart.unsaved') : ''), result, colorIndex: 0 },
    ...compareIds.map((id, i) => ({ key: id, name: buildName(id), result: compareResults[id], colorIndex: i + 1 })),
  ] : [];

  // Moment inspected by the stat sheet and the relevance report: the clicked one, or the last purchase.
  const lastStep = result?.steps[result.steps.length - 1];
  const moment = selectedMinute ?? lastStep?.minute ?? null;
  const rawPoint = result && moment != null ? pointAt(result.series, moment) : null;
  const point = withObjectives(rawPoint, objectives, objectiveRules);

  /** The compared builds, as the effectiveness panel wants them (as saved, or with the open build's conditionals). */
  const compareForEffect = compareIds.map((id, i) => ({
    id, name: buildName(id), color: SERIES_COLORS[(i + 1) % SERIES_COLORS.length],
    request: async () => (condSource
      ? { build: toPayload(withConditionals(fromApi(await api.get(`/api/builds/${id}`)), condSource, itemsById)) }
      : { buildId: id }),
  }));

  const value = {
    meta, items, itemsById, evoPairs, folders, builds, archetypes, loadTree,
    draft, dirty, update, updateStep, setDraft, effective, payload, canCalculate,
    result, calcError, lastStep, moment, point, rawPoint,
    compareIds, setCompareIds, compareResults, toggleCompare, sameConds,
    setSameConds: (on) => { setSameConds(on); saveFlag(SAME_CONDS_KEY, on); },
    condSource, condKey, compareForEffect, chartSeries, buildName,
    chartStat, setChartStat, objectives, setObjectives, quick, setQuick, toggleQuickCandidate,
    runesReady, prepEditor, setPrepEditor, effAlign, setEffAlign,
    selectedMinute, setSelectedMinute, selectedIdx, setSelectedIdx, message, setMessage,
    passiveValues, openBuild, newBuild, save, saveAsCopy, remove, moveStepTo, addMoment, removeStep, addItem,
    condOverride, setCondOverride, confirmDiscard,
  };
  return <BuildContext.Provider value={value}>{children}</BuildContext.Provider>;
}

/**
 * The draft with the header's conditionals (Parte 5): `steps` (index → includeConditional), `runes` (rune name →
 * conditional), `champion` (effect name → conditional) and `items` (item setup's conditional choices).
 */
export function applyConditionals(d, o) {
  return {
    ...d,
    steps: d.steps.map((s, i) => (o.steps && o.steps[i] != null && !s.kind ? { ...s, includeConditional: o.steps[i] } : s)),
    runePage: o.runes ? { ...d.runePage, conditional: { ...(d.runePage?.conditional ?? {}), ...o.runes } } : d.runePage,
    championSetup: o.champion ? { ...d.championSetup, conditional: { ...(d.championSetup?.conditional ?? {}), ...o.champion } } : d.championSetup,
    itemSetup: o.items ? { ...d.itemSetup, conditional: { ...(d.itemSetup?.conditional ?? {}), ...o.items } } : d.itemSetup,
  };
}
