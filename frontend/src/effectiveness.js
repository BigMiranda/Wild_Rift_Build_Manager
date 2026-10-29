import { api } from './api.js';

/**
 * Client side of the effectiveness reports (backend: EffectTestService). The runs go in small batches so a screen can
 * show its progress and stop; the backend caches every fight, so running again is fast.
 */

let metaPromise = null;
/** Roles, presets, the sweeps' reference attacker and default stages, the gold mixes (cached for the session). */
export const effectMeta = () => {
  if (!metaPromise) metaPromise = api.get('/api/effect/meta').catch((e) => { metaPromise = null; throw e; });
  return metaPromise;
};

/** Stats a test ragdoll may hold on top (engine names), in the editor's order. */
export const BOT_STATS = ['Ability Power', '% Critical Rate', '% Armor Penetration', 'Armor Penetration',
  '% Magic Penetration', 'Magic Penetration', '% Lifesteal', '% Omnivamp', '% Attack Speed'];

export const ROLES = ['atirador', 'mago', 'lutador', 'assassino', 'tanque', 'suporte'];

/** The key a report entry uses for a scenario: the preset key, or "u:<id>" for one of the user's. */
export const testKey = (test) => (test.key ? test.key : `u:${test.id}`);

/**
 * Runs jobs for one build at one minute, `size` at a time: `onProgress(done, total)` after each batch, and `stop()`
 * returning true ends it early (the answers so far are kept). Returns the outcomes in the jobs' order (null = not run).
 */
export async function runJobs(base, jobs, { size = 6, onProgress, stop } = {}) {
  const out = new Array(jobs.length).fill(null);
  let ms = 0;
  for (let k = 0; k < jobs.length; k += size) {
    if (stop?.()) break;
    const batch = jobs.slice(k, k + size);
    const r = await api.post('/api/effect/evaluate', { ...base, jobs: batch });
    r.outcomes.forEach((o, i) => { out[k + i] = o; });
    ms += r.ms;
    onProgress?.(Math.min(jobs.length, k + size), jobs.length, ms);
  }
  return out;
}

/** The jobs of a report: its scenarios (the build as it is), then each sweep's 5 stages (complete). */
export function reportJobs(report, withSweeps) {
  const jobs = report.tests.map((e) => ({ tag: `t:${e.key}`, testKey: e.key }));
  if (withSweeps) {
    for (const s of report.sweeps ?? []) {
      s.stages.forEach((v, i) => jobs.push({ tag: `s:${s.key}:${i}`, stage: { dimension: s.key, value: Number(v) } }));
    }
  }
  return jobs;
}

/** Weighted mean of the indexes of a report's scenarios (outcomes in the order of report.tests). */
export function weightedIndex(report, outcomes) {
  let sum = 0;
  let w = 0;
  report.tests.forEach((e, i) => {
    const o = outcomes[i];
    if (o?.index != null && Number.isFinite(o.index)) {
      sum += (e.weight ?? 1) * o.index;
      w += e.weight ?? 1;
    }
  });
  return w > 0 ? sum / w : null;
}

/** The stage of a sweep where the index drops more than 25% from the stage before (null = none). */
export function breakPoint(stages, indexes) {
  for (let i = 1; i < indexes.length; i++) {
    const a = indexes[i - 1];
    const b = indexes[i];
    if (a != null && b != null && a > 0 && b < a * 0.75) return { stage: stages[i], index: i, from: a, to: b };
  }
  return null;
}

/** The scenarios where the build does worst: the lowest indexes, below 70% of its weighted mean. */
export function weakPoints(report, outcomes) {
  const mean = weightedIndex(report, outcomes);
  if (mean == null) return [];
  return report.tests.map((e, i) => ({ key: e.key, o: outcomes[i] }))
    .filter((x) => x.o?.index != null && x.o.index < mean * 0.7)
    .sort((a, b) => a.o.index - b.o.index);
}

/** Percent change of a time to die over another (+18 = 18% longer). */
export const gain = (a, b) => (a != null && b != null && b > 0 ? 100 * (a / b - 1) : null);

/** A new test ragdoll with a role's defaults (its pattern follows the role). */
export const newBot = (role = 'lutador', name) => ({
  name: name ?? null, role, health: 8000, armor: 60, magicResist: 60, attackDamage: 150, attackSpeed: 1,
  moveSpeed: 350, attackRange: null, stats: {}, items: [], pulses: null,
});

export const newTest = () => ({ id: null, key: null, name: '', note: '', group: '1x1', attackers: [newBot('lutador')], allies: [], duration: 180 });
