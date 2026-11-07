/**
 * The public version (Rodada 11): built with `VITE_PUBLIC=1` (`npm run build:publico`). No admin, no training, no heavy
 * calculations; the visitor's builds live in the browser (localStore.js). The local app leaves it off.
 */
export const PUBLIC = import.meta.env.VITE_PUBLIC === '1';
