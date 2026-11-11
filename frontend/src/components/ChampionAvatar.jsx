import { useState } from 'react';
import { asset } from '../api.js';

/**
 * A champion's portrait file, by its name: the same rule as `tools/campeoes_retratos.py` (accents out, lower case,
 * anything else "-"), so "K'Sante" -> k-sante.png and "Nunu e Willump" -> nunu-e-willump.png.
 */
export const championSlug = (name) => String(name ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
  .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');

export const championIcon = (name) => (name ? asset(`/api/champions/icons/${championSlug(name)}.png`) : null);

/**
 * A champion's portrait (the LoL PC square icon, bundled with the app); initials on a color derived from the name when
 * there is none (a Wild Rift-only champion, a test ragdoll) or it fails to load.
 */
export function ChampionAvatar({ name, src, size = 40 }) {
  const url = src ?? championIcon(name);
  const [failed, setFailed] = useState(null);
  if (url && failed !== url) {
    return <img className="champ-avatar champ-photo" src={url} alt="" width={size} height={size} loading="lazy"
      onError={() => setFailed(url)} />;
  }
  let h = 0;
  for (const c of String(name ?? '')) h = (h * 31 + c.charCodeAt(0)) % 360;
  const words = String(name ?? '?').replace(/[^\p{L}\s]/gu, ' ').trim().split(/\s+/);
  const initials = (words.length > 1 ? words[0][0] + words[1][0] : words[0].slice(0, 2)).toUpperCase();
  return (
    <span className="champ-avatar" aria-hidden="true"
      style={{ width: size, height: size, fontSize: size * 0.38, background: `hsl(${h} 35% 30%)`, borderColor: `hsl(${h} 45% 50%)` }}>
      {initials}
    </span>
  );
}
