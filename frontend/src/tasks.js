import { useEffect, useSyncExternalStore } from 'react';

/**
 * Long jobs of any screen (effectiveness reports, the calculator, AI training, the basic score of the quick comparison):
 * pages stay mounted when another tab is open, so their jobs keep running; this store lists them for the global bell.
 */
const tasks = new Map();
const listeners = new Set();
let snapshot = [];

const emit = () => {
  snapshot = [...tasks.values()];
  listeners.forEach((l) => l());
};

const subscribe = (l) => {
  listeners.add(l);
  return () => listeners.delete(l);
};

/** The running jobs: [{ id, label, done, total, path }]. */
export const useTasks = () => useSyncExternalStore(subscribe, () => snapshot);

/**
 * Registers a job while `active`: `label` names it, `done/total` its progress (optional) and `path` the screen it
 * belongs to (the bell opens it).
 */
export function useTask(id, active, { label, done, total, path } = {}) {
  useEffect(() => {
    if (!active) return undefined;
    tasks.set(id, { id, label, done, total, path });
    emit();
    return () => {
      tasks.delete(id);
      emit();
    };
  }, [id, active, label, done, total, path]);
}
