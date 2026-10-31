import { useEffect, useState } from 'react';
import { api } from './api.js';
import { t } from './i18n.js';

/** The archetype of a build in words: "Tanque (Armadura)". */
export const archetypeLabel = (a) => {
  if (!a) return '';
  const name = t(`arch.${a.key}`);
  return a.detail ? `${name} (${t(`arch.detail.${a.detail}`)})` : name;
};

/** What the archetype was read from, for a title. */
export const archetypeTitle = (a) => (a ? t('arch.title', {
  list: Object.entries(a.shares).filter(([, v]) => v >= 0.05).sort((x, y) => y[1] - x[1])
    .map(([k, v]) => `${t(`arch.family.${k}`)} ${Math.round(100 * v)}%`).join(', '),
}) : undefined);

/** Build id -> archetype of the saved builds; `key` changes when a build changes. */
export function useArchetypes(key) {
  const [map, setMap] = useState({});
  useEffect(() => {
    let live = true;
    api.get('/api/archetypes').then((m) => { if (live) setMap(m); }).catch(() => {});
    return () => { live = false; };
  }, [key]);
  return map;
}
