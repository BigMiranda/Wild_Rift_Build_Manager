import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from './api.js';
import { t } from './i18n.js';
import { n0, n1 } from './format.js';

/**
 * How the passives without stats are worth gold in the effective gold, the relevance report and the fixed bar:
 * by the item's tier (the old way), estimated by the numbers of the moment, or measured in a scenario
 * (POST /api/passives/estimate and /measure). The backend answers for sampled minutes; between them values are
 * interpolated. In "by tier" there is no overlay at all, so the numbers are the ones of effective.js alone.
 */
export const PV_TIER = 'tier';
export const PV_ESTIMATED = 'estimado';
export const PV_MEASURED = 'medido';
export const PV_MODES = [PV_TIER, PV_ESTIMATED, PV_MEASURED];

const STORE = 'ornn-planner-passive-mode';
const loadMode = () => {
  try {
    const v = localStorage.getItem(STORE);
    return PV_MODES.includes(v) ? v : PV_ESTIMATED;
  } catch {
    return PV_ESTIMATED;
  }
};

/** Minutes sampled for a timeline: each purchase of the build and every 2 minutes up to the last one. */
export function sampleMinutes(result) {
  const buys = (result?.steps ?? []).filter((s) => !s.implied).map((s) => s.minute);
  const end = result?.series?.length ? result.series[result.series.length - 1].minute : 0;
  const all = new Set(buys.map((m) => Math.round(m * 100) / 100));
  for (let m = 2; m < end; m += 2) all.add(m);
  if (end > 0) all.add(Math.round(end * 100) / 100);
  return [...all].sort((a, b) => a - b);
}

/** Linear between the samples around the minute; the nearest one outside them. */
function interpolate(list, minute) {
  if (list.length === 0) return null;
  if (minute <= list[0].minute) return list[0].gold;
  for (let i = 1; i < list.length; i++) {
    if (minute <= list[i].minute) {
      const a = list[i - 1];
      const b = list[i];
      const k = (minute - a.minute) / Math.max(1e-9, b.minute - a.minute);
      return a.gold + (b.gold - a.gold) * k;
    }
  }
  return list[list.length - 1].gold;
}

const key = (item, passive) => `${item}|${passive}`;

/** { "item|passive": { gold samples, last entry } } of the estimate. */
function estimateTable(samples) {
  const table = new Map();
  for (const s of samples ?? []) {
    for (const item of s.items) {
      for (const p of item.passives) {
        const k = key(item.itemName, p.name);
        if (!table.has(k)) table.set(k, { list: [], last: null });
        const e = table.get(k);
        if (p.gold != null) e.list.push({ minute: s.minute, gold: p.gold });
        e.last = p;
      }
    }
  }
  return table;
}

function measuredTable(readings, itemsById) {
  const table = new Map();
  for (const r of readings ?? []) {
    const k = key(itemsById.get(r.itemId)?.name, r.passive);
    if (!table.has(k)) table.set(k, { list: [], last: null });
    const e = table.get(k);
    e.list.push({ minute: r.minute, gold: r.gold });
    if (!e.last || r.minute >= e.last.minute) e.last = r;
  }
  for (const e of table.values()) e.list.sort((a, b) => a.minute - b.minute);
  return table;
}

/** overlay(minute)(item, passive) = { gold (null: the tier's), source, detail, whole } or null. */
function makeOverlay(estimate, measured, label) {
  return (minute) => (item, passive) => {
    const m = measured?.get(key(item, passive));
    if (m) {
      const gold = interpolate(m.list, minute);
      return {
        gold, source: PV_MEASURED, whole: true, label,
        detail: t('pv.measuredDetail', { loss: n1(m.last.lossPct), gold: n0(gold) }),
      };
    }
    const e = estimate?.get(key(item, passive));
    if (!e) return null;
    return {
      gold: e.list.length ? interpolate(e.list, minute) : null, source: e.last?.source, detail: e.last?.detail,
      measurable: e.last?.measurable,
    };
  };
}

const JOBS_PER_BATCH = 8;

/**
 * The passive values of the open build and the ones compared. Estimates come by themselves; a measure is asked for
 * (it runs fights), and until it ends the previous values stay on screen. `build` is the open draft.
 */
export function usePassiveValues({ build, result, compareIds, compareResults, itemsById }) {
  const [mode, setModeState] = useState(loadMode);
  const [scenario, setScenario] = useState({ kind: 'basico', label: null });
  const [estimates, setEstimates] = useState({});
  const [measure, setMeasure] = useState(null);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);
  const stop = useRef(false);
  const buildKey = build ? JSON.stringify(build) : null;
  const minutesKey = result ? sampleMinutes(result).join(',') : '';

  const setMode = useCallback((m) => {
    setModeState(m);
    try {
      localStorage.setItem(STORE, m);
    } catch {
      /* this session only */
    }
  }, []);

  useEffect(() => {
    if (mode === PV_TIER || !buildKey || !minutesKey) return undefined;
    let live = true;
    const timer = setTimeout(() => {
      api.post('/api/passives/estimate', { build: JSON.parse(buildKey), minutes: minutesKey.split(',').map(Number) })
        .then((samples) => { if (live) setEstimates((e) => ({ ...e, active: { key: buildKey, samples } })); })
        .catch((e) => { if (live) setError(e.message); });
    }, 500);
    return () => { live = false; clearTimeout(timer); };
  }, [mode, buildKey, minutesKey]);

  const compareKey = compareIds.map((id) => `${id}:${compareResults[id] ? sampleMinutes(compareResults[id]).join('/') : ''}`).join('|');
  useEffect(() => {
    if (mode === PV_TIER) return undefined;
    let live = true;
    compareIds.forEach((id) => {
      if (!compareResults[id]) return;
      api.post('/api/passives/estimate', { buildId: id, minutes: sampleMinutes(compareResults[id]) })
        .then((samples) => { if (live) setEstimates((e) => ({ ...e, [id]: { samples } })); })
        .catch(() => {});
    });
    return () => { live = false; };
  }, [mode, compareKey]);

  const scenarioKey = JSON.stringify([scenario.kind, scenario.key, scenario.id]);
  const runMeasure = async () => {
    const samples = estimates.active?.samples;
    if (!samples || !build) return;
    const jobs = [];
    for (const s of samples) {
      for (const item of s.items) {
        for (const p of item.passives) {
          if (p.measurable) jobs.push({ minute: s.minute, itemId: item.itemId, passive: p.name });
        }
      }
    }
    stop.current = false;
    setError(null);
    const t0 = performance.now();
    const readings = [];
    setBusy({ done: 0, total: jobs.length });
    try {
      for (let k = 0; k < jobs.length && !stop.current; k += JOBS_PER_BATCH) {
        const out = await api.post('/api/passives/measure', { build, scenario, jobs: jobs.slice(k, k + JOBS_PER_BATCH) });
        readings.push(...out);
        setBusy({ done: Math.min(jobs.length, k + JOBS_PER_BATCH), total: jobs.length, s: (performance.now() - t0) / 1000 });
      }
      // stopped halfway: the previous mode stays; what was run is cached for the next time
      if (!stop.current) setMeasure({ buildKey, scenarioKey, readings, label: scenario.label, s: (performance.now() - t0) / 1000 });
    } catch (e) {
      setError(e.message);
    }
    setBusy(null);
  };

  const measuredNow = mode === PV_MEASURED && measure && measure.buildKey === buildKey && measure.scenarioKey === scenarioKey ? measure : null;
  const overlays = useMemo(() => {
    if (mode === PV_TIER) return {};
    const out = {};
    for (const [k, v] of Object.entries(estimates)) {
      out[k] = makeOverlay(estimateTable(v.samples), k === 'active' && measuredNow ? measuredTable(measuredNow.readings, itemsById) : null,
        measuredNow?.label);
    }
    return out;
  }, [mode, estimates, measuredNow, itemsById]);

  return {
    mode, setMode, scenario, setScenario, busy, error, measured: measuredNow, runMeasure, stopMeasure: () => { stop.current = true; },
    /** overlay of a series at a minute (undefined: effective.js alone, as by tier). */
    overlayAt: (seriesKey, minute) => overlays[seriesKey]?.(minute),
    overlayOf: (seriesKey) => overlays[seriesKey],
  };
}
