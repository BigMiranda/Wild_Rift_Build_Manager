import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { mmss } from '../format.js';

/** The ragdoll the build is tested on by default: 10000 health, 100/100, hitting once a second (so "in combat" starts). */
export const ATTACKING_DUMMY = {
  name: 'Boneco de pano', health: 10000, armor: 100, magicResist: 100, radius: 80, moveSpeed: 350, behavior: 'ataca',
  attackDamage: 100, spells: [], appliesEffects: false, hitEvery: 1, atMax: false,
};

const plan = () => ({ focus: 'nearest', order: [], shared: true, switchBelow: 0.25, chainCc: false });
const slot = (team, patch) => ({
  team, buildId: null, minute: null, x: null, y: null, target: null, followTeam: true, autoAbilities: true,
  priority: ['R', '3', '1', '2'], brain: 'tactical', activations: {}, ...patch,
});

/** Opens the arena on an unsaved scenario, playing as soon as it has run (the arena reads its saved state). */
export function openInArena(scenario) {
  try {
    localStorage.setItem('ornn-planner-arena', JSON.stringify(scenario));
    localStorage.setItem('ornn-planner-arena-tab', 'scenario');
    localStorage.setItem('ornn-planner-arena-autoplay', '1');
  } catch {
    /* storage unavailable: the arena opens as it was */
  }
  window.dispatchEvent(new CustomEvent('ornn-open-tab', { detail: 'arena' }));
}

/**
 * "Testar na Arena": the open build (as saved) at the minute chosen on the chart, with the Tactical brain, against the
 * opponent picked next to the button — the attacking ragdoll, another saved build at the same minute, or a saved
 * scenario (the build takes the slot of its champion). The arena opens on a new scenario that is not saved.
 */
export default function TestInArena({ buildId, buildName, minute, builds, compact = false }) {
  const [opponent, setOpponent] = useState('dummy');
  const [options, setOptions] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    setOptions(null);
    if (!buildId) return undefined;
    let live = true;
    api.get(`/api/lab/test-options?buildId=${buildId}`).then((o) => { if (live) setOptions(o); })
      .catch(() => { if (live) setOptions({ scenarios: [] }); });
    return () => { live = false; };
  }, [buildId]);
  const at = minute == null ? null : Math.round(minute * 100) / 100;

  const go = async () => {
    setError(null);
    const me = slot(0, { buildId, minute: at, x: 4600, y: 0 });
    const name = t('arenaTest.name', { build: buildName, time: at == null ? t('report.minuteAuto') : mmss(at) });
    try {
      if (opponent === 'dummy') {
        openInArena({ id: null, name, note: '', duration: 30, actions: [], teams: [plan(), plan()],
          fighters: [me, slot(1, { dummy: { ...ATTACKING_DUMMY }, x: 5400, y: 0, brain: 'script' })] });
        return;
      }
      const [kind, id, index] = opponent.split(':');
      if (kind === 'b') {
        const other = builds.find((b) => b.id === Number(id));
        openInArena({ id: null, name: `${name} × ${other?.name ?? id}`, note: '', duration: 40, actions: [], teams: [plan(), plan()],
          fighters: [me, slot(1, { buildId: Number(id), minute: at, x: 5400, y: 0 })] });
        return;
      }
      const sc = await api.get(`/api/arena/scenarios/${id}`);
      const k = Number(index);
      openInArena({
        ...sc, id: null, name: `${sc.name} · ${buildName}`,
        fighters: sc.fighters.map((f, i) => (i === k ? { ...f, buildId, minute: at, brain: 'tactical' } : f)),
      });
    } catch (e) {
      setError(e.message);
    }
  };

  return (
    <span className={`test-arena${compact ? ' compact' : ''}`}>
      <select value={opponent} onChange={(e) => setOpponent(e.target.value)} aria-label={t('arenaTest.opponent')}
        title={t('arenaTest.opponent')} disabled={!buildId}>
        <option value="dummy">{t('arenaTest.dummy')}</option>
        {builds.some((b) => b.id !== buildId && b.unitCode !== 'RAGDOLL') && (
          <optgroup label={t('arenaTest.builds')}>
            {builds.filter((b) => b.id !== buildId && b.unitCode !== 'RAGDOLL').map((b) => (
              <option key={b.id} value={`b:${b.id}`}>{b.name} · {b.unitCode}</option>
            ))}
          </optgroup>
        )}
        {options?.scenarios?.length > 0 && (
          <optgroup label={t('arenaTest.scenarios')}>
            {options.scenarios.flatMap((s) => s.slots.map((sl) => (
              <option key={`${s.id}:${sl.index}`} value={`s:${s.id}:${sl.index}`}>
                {s.name}{s.slots.length > 1 ? ` · ${t('ptest.slot', { n: sl.index + 1, side: t(`report.team${sl.team}`) })}` : ''}
              </option>
            )))}
          </optgroup>
        )}
      </select>
      <button className="primary" onClick={go} disabled={!buildId}
        title={buildId ? t('arenaTest.title', { time: at == null ? t('report.minuteAuto') : mmss(at) }) : t('ptest.saveFirst')}>
        {t('arenaTest.button')}
      </button>
      {error && <small className="error">{error}</small>}
    </span>
  );
}
