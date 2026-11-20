import { t } from '../i18n.js';
import { LANES } from '../liveGame.js';

/** Short lane names for the chips (the full name is in the title). */
export const LANE_SHORT = { Barão: 'Bar', Selva: 'Sel', Meio: 'Mei', Dragão: 'Dra', Suporte: 'Sup' };

/**
 * Rodada 20.b, Parte 2: a pick's most likely lane with its chance, or "?" when no lane passes `unsureBelow`; the fixed
 * lane (loading screen) wins. `dist` = {lane: chance} from /api/draft/sugestoes (lanes[side].picks[k].lanes).
 */
export function LikelyLane({ player, dist, unsureBelow = 0.6 }) {
  if (player?.lane) return <span className="pl-likely fixed" title={t('pl.fixedHelp')}>📌 {player.lane}</span>;
  const entries = Object.entries(dist ?? {}).sort((a, b) => b[1] - a[1]);
  if (!entries.length) return null;
  const [lane, p] = entries[0];
  const title = entries.map(([l, v]) => `${l} ${Math.round(v * 100)}%`).join(' · ');
  if (p < unsureBelow) {
    return <span className="pl-likely unsure" title={title}>? <small className="muted">{entries.slice(0, 2).map(([l, v]) => `${LANE_SHORT[l] ?? l} ${Math.round(v * 100)}%`).join(' · ')}</small></span>;
  }
  return <span className="pl-likely" title={title}>{lane} <small className="muted">{Math.round(p * 100)}%</small></span>;
}

/**
 * Rodada 20.b, Parte 2: the lanes a pick may play, toggled one by one (several at once), or undefined (any lane). A pick
 * not told yet shows its prefilled lanes (the seed's and the meta's). onChange(lanes): an array ([] = undefined).
 */
export default function PickLanes({ player, possible = [], onChange, compact }) {
  const told = player.lanes;
  const current = told == null ? possible : told;
  const undefinedLanes = Array.isArray(told) && told.length === 0;
  const toggle = (l) => {
    const base = undefinedLanes ? [] : [...current];
    const next = base.includes(l) ? base.filter((x) => x !== l) : [...base, l];
    onChange(LANES.filter((x) => next.includes(x)));
  };
  return (
    <div className={`chips pl-lanes${compact ? ' compact' : ''}`} role="group" aria-label={t('pl.possible')}>
      {LANES.map((l) => {
        const on = !undefinedLanes && current.includes(l);
        return (
          <button key={l} type="button" className={on ? 'active' : ''} aria-pressed={on} title={l} onClick={() => toggle(l)}>
            {compact ? LANE_SHORT[l] : l}
          </button>
        );
      })}
      <button type="button" className={undefinedLanes ? 'active' : ''} aria-pressed={undefinedLanes} title={t('pl.undefinedHelp')}
        onClick={() => onChange(undefinedLanes ? [...possible] : [])}>?</button>
    </div>
  );
}
