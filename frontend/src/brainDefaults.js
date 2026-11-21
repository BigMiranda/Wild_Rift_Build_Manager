import { api } from './api.js';

/**
 * Rodada 21, part 5: the brain of each process from the backend's central setting (ia_padrao_<patch>.yml), read once.
 * Until it arrives (or where the endpoint does not exist, as in the public version) every process is the automatic
 * brain, which is what the file says for all the players.
 */
let loaded = null;
let asked = false;

export function loadBrainDefaults() {
  if (!asked) {
    asked = true;
    api.get('/api/arena/brain-defaults').then((m) => { loaded = m; }).catch(() => { loaded = {}; });
  }
}

/** The brain of a process: arena, testar_na_arena, partida... ('auto' until the setting is read). */
export const defaultBrain = (process) => {
  loadBrainDefaults();
  return loaded?.[process] ?? 'auto';
};
