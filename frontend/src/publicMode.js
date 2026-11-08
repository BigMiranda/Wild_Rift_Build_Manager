/**
 * The public version (Rodada 11): built with `VITE_PUBLIC=1` (`npm run build:publico`). No admin, no training, no heavy
 * calculations; the visitor's builds live in the browser (localStore.js). The local app leaves it off.
 */
export const PUBLIC = import.meta.env.VITE_PUBLIC === '1';

/**
 * The static site (Rodada 11.b): built with `VITE_STATIC=1` (`npm run build:estatico`), served as plain files (GitHub
 * Pages). Every GET reads the JSON the generation script saved (staticPath.js); nothing is calculated.
 */
export const STATIC = import.meta.env.VITE_STATIC === '1';
