import { Fragment, Profiler, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { pageZoom } from '../zoom.js';
import { BuildLink } from '../components/BuildPreview.jsx';
import { useTask } from '../tasks.js';
import { api } from '../api.js';
import ObjectivesPicker from '../components/ObjectivesPicker.jsx';
import ItemPicker from '../components/ShopPicker.jsx';
import { t } from '../i18n.js';
import { n0, n1 } from '../format.js';
import MinuteInput from '../components/MinuteInput.jsx';
import { ChampionAvatar } from '../components/ChampionPicker.jsx';
import ArenaLab from '../components/ArenaLab.jsx';
import FightReport from '../components/FightReport.jsx';
import CooldownCard, { CooldownTag, cooldownName } from '../components/CooldownCard.jsx';
import { ATTACKING_DUMMY } from '../components/TestInArena.jsx';
import ItemIcon from '../components/ItemIcon.jsx';
import { pointAt } from '../components/MomentPanels.jsx';
import { mmss } from '../format.js';
import { PUBLIC } from '../publicMode.js';

/**
 * Orchestrated matchup: a scenario (champions from saved builds at a minute, where they start, and a timeline of
 * orders) run by the backend's arena. The timeline shows every cooldown at every instant; the map plays the fight with
 * circles for champions and simple shapes for the rest (hover them while paused to see what they are).
 */
export const TEAM = ['#4fa8ff', '#f06a5f'];
const TEAM_SOFT = ['rgba(79,168,255,.22)', 'rgba(240,106,95,.22)'];
const SLOTS = ['1', '2', '3', 'R'];
const ORDER_TYPES = ['ability', 'recast', 'item', 'spell', 'move', 'attack', 'stop'];
const SPELLS = ['Flash', 'Incendiar', 'Exaustão', 'Barreira', 'Curar', 'Fantasma', 'Purificar', 'Golpear', 'Teleporte'];
const STORE = 'ornn-planner-arena';
const PX_PER_S = 36;

const FOCUS = ['nearest', 'lowest_hp', 'lowest_effective_hp', 'most_damage', 'order'];
// Scenarios saved before team plans have none (teams: null): each champion picks its own target, as before.
const newPlan = () => ({ focus: 'nearest', order: [], shared: true, switchBelow: 0.25, chainCc: false });
const emptyScenario = () => ({ id: null, name: t('arena.newName'), note: '', duration: 40, fighters: [newSlot(0), newSlot(1)], actions: [], teams: [newPlan(), newPlan()] });
const newSlot = (team) => ({ team, buildId: null, minute: null, x: null, y: null, target: null, followTeam: true, autoAbilities: true, priority: ['R', '3', '1', '2'], brain: 'auto', activations: {} });

function loadDraft() {
  try {
    const s = JSON.parse(localStorage.getItem(STORE));
    if (s && Array.isArray(s.fighters)) return s;
  } catch {
    /* none */
  }
  return null;
}

/** Frame at (or right before) a time. */
/**
 * Render time of the stage (map + timeline) per commit, for measuring playback cost: set `window.__arenaRenders = []`
 * in the console, play, and read the durations (ms). Nothing is kept unless asked; production builds do not profile.
 */
const onStageRender = (id, phase, actualDuration) => {
  if (Array.isArray(window.__arenaRenders)) window.__arenaRenders.push(actualDuration);
};

export function frameAt(result, time) {
  if (!result?.frames?.length) return null;
  const i = Math.max(0, Math.min(result.frames.length - 1, Math.round(time / result.frameEvery)));
  return result.frames[i];
}

/** The map of a scenario: what is on the full map, the minute, and what each side starts with. */
function MapChoice({ sc, set, templates, onTemplate }) {
  const rift = sc.rift;
  const setRift = (patch) => set({ rift: { ...rift, ...patch } });
  const objectives = sc.objectives ?? [null, null];
  const setObjectives = (team, v) => set({ objectives: team === 0 ? [v, objectives[1]] : [objectives[0], v] });
  return (
    <details className="arena-map-choice">
      <summary>{t('arena.map')}: {rift ? t('arena.map.rift') : t('arena.map.lane')}</summary>
      <div className="row">
        <select value={rift ? 'rift' : 'lane'} aria-label={t('arena.map')}
          onChange={(e) => set({ rift: e.target.value === 'rift' ? { minute: 10, minions: true, towers: true, jungle: false, plants: true, objectives: true, vision: true } : null })}>
          <option value="lane">{t('arena.map.lane')}</option>
          <option value="rift">{t('arena.map.rift')}</option>
        </select>
        <select value="" aria-label={t('arena.template')} onChange={(e) => e.target.value && onTemplate(e.target.value)}>
          <option value="">{t('arena.template')}</option>
          {templates.map((x) => <option key={x.key} value={x.key}>{x.name}</option>)}
        </select>
      </div>
      {rift && (
        <div className="row">
          <label className="inline">{t('arena.map.minute')}<input type="number" min={0} max={40} value={rift.minute} style={{ width: 64 }}
            onChange={(e) => setRift({ minute: Number(e.target.value) || 0 })} /></label>
          {['minions', 'towers', 'jungle', 'plants', 'objectives', 'vision'].map((k) => (
            <label key={k} className="check"><input type="checkbox" checked={rift[k]} onChange={(e) => setRift({ [k]: e.target.checked })} />{t(`arena.map.${k}`)}</label>
          ))}
        </div>
      )}
      {[0, 1].map((team) => (
        <div key={team}>
          <small style={{ color: TEAM[team] }}>{t(`report.team${team}`)}</small>
          <ObjectivesPicker value={objectives[team]} onChange={(v) => setObjectives(team, v)} compact />
        </div>
      ))}
    </details>
  );
}

export default function Arena({ forcedTab = null }) {
  const [templates, setTemplates] = useState([]);
  const [builds, setBuilds] = useState([]);
  const [saved, setSaved] = useState([]);
  const [sc, setSc] = useState(() => loadDraft() ?? emptyScenario());
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [time, setTime] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(1);
  const [editing, setEditing] = useState(null);   // index of the action being edited
  const [pickPoint, setPickPoint] = useState(null); // action index waiting for a click on the map
  const [message, setMessage] = useState(null);
  const [policies, setPolicies] = useState([]);
  /** Cooldown card: {fighter, key, x, y} (where it was clicked), and whether clicking pauses at that second. */
  const [card, setCard] = useState(null);
  const [pauseOnClick, setPauseOnClick] = useState(() => {
    try {
      return localStorage.getItem(`${STORE}-pause-click`) !== 'off';
    } catch {
      return true;
    }
  });
  const togglePauseOnClick = (on) => {
    setPauseOnClick(on);
    try {
      localStorage.setItem(`${STORE}-pause-click`, on ? 'on' : 'off');
    } catch {
      /* session only */
    }
  };
  /** Opens the card of a cooldown (from a stripe or the map), pausing at the second clicked when chosen. */
  const openCard = (fighter, key, at, x, y) => {
    if (pauseOnClick) {
      setPlaying(false);
      if (at != null) setTime(at);
    }
    setCard({ fighter, key, x, y });
  };
  const [ownTab, setTab] = useState(() => {
    try {
      return localStorage.getItem(`${STORE}-tab`) ?? 'scenario';
    } catch {
      return 'scenario';
    }
  });
  // Simulações › Arena and › Laboratório are the same instance (one scenario), each showing its part
  const tab = forcedTab ?? ownTab;
  const pickTab = (v) => {
    setTab(v);
    try {
      localStorage.setItem(`${STORE}-tab`, v);
    } catch {
      /* session only */
    }
  };
  const loadPolicies = () => api.get('/api/arena/policies').then(setPolicies).catch(() => {});

  useEffect(() => {
    api.get('/api/builds').then(setBuilds).catch((e) => setError(e.message));
    api.get('/api/arena/scenarios').then(setSaved).catch(() => {});
    api.get('/api/arena/templates').then(setTemplates).catch(() => {});
    loadPolicies();
  }, []);
  useEffect(() => {
    try {
      localStorage.setItem(STORE, JSON.stringify(sc));
    } catch {
      /* session only */
    }
  }, [sc]);

  // Runs the scenario on every change (the engine is fast): debounced.
  const ready = sc.fighters.length > 1 && sc.fighters.every((f) => f.buildId != null || f.dummy)
    && sc.fighters.some((f) => f.team === 0) && sc.fighters.some((f) => f.team === 1)
    && (!PUBLIC || sc.id != null);   // the public version runs only its scenarios, as they were saved
  const seq = useRef(0);
  const ranWith = useRef(null);   // the scenario of the result shown (item values run it again)
  useEffect(() => {
    if (!ready) {
      setResult(null);
      return undefined;
    }
    const n = ++seq.current;
    const timer = setTimeout(async () => {
      setBusy(true);
      try {
        const r = await api.post('/api/arena/run', sc);
        if (n === seq.current) {
          ranWith.current = sc;
          setResult(r);
          setError(null);
        }
      } catch (e) {
        if (n === seq.current) setError(e.message);
      } finally {
        if (n === seq.current) setBusy(false);
      }
    }, 350);
    return () => clearTimeout(timer);
  }, [sc, ready]);

  // "Testar na Arena" from the planner: play the new scenario as soon as it has run.
  const autoplay = useRef(false);
  // The arena stays mounted while other tabs are open (Rodada 8): a scenario sent from elsewhere comes by event.
  useEffect(() => {
    const load = (e) => {
      if (!e.detail) return;
      setSc({ ...emptyScenario(), ...e.detail });
      setTime(0);
      setEditing(null);
      autoplay.current = true;
      try {
        localStorage.removeItem(`${STORE}-autoplay`);
      } catch {
        /* session only */
      }
    };
    window.addEventListener('ornn-arena-load', load);
    return () => window.removeEventListener('ornn-arena-load', load);
  }, []);
  useEffect(() => {
    try {
      // (set only: StrictMode runs this twice in development, and the second run finds the flag gone)
      if (localStorage.getItem(`${STORE}-autoplay`) === '1') autoplay.current = true;
      localStorage.removeItem(`${STORE}-autoplay`);
    } catch {
      /* session only */
    }
  }, []);
  useEffect(() => {
    if (result && autoplay.current) {
      autoplay.current = false;
      setTime(0);
      setPlaying(true);
    }
  }, [result]);

  // Playback.
  useEffect(() => {
    if (!playing || !result) return undefined;
    let last = performance.now();
    let raf;
    const tick = (now) => {
      const dt = (now - last) / 1000;
      last = now;
      setTime((tm) => {
        const next = tm + dt * speed;
        if (next >= result.duration) {
          setPlaying(false);
          return result.duration;
        }
        return next;
      });
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [playing, speed, result]);

  const set = (patch) => setSc((s) => ({ ...s, ...patch }));
  const setFighter = (i, patch) => set({ fighters: sc.fighters.map((f, k) => (k === i ? { ...f, ...patch } : f)) });
  const addFighter = (team) => {
    if (sc.fighters.filter((f) => f.team === team).length >= 6) return;
    set({ fighters: [...sc.fighters, newSlot(team)] });
  };
  const setPlan = (team, patch) => set({ teams: sc.teams.map((p, k) => (k === team ? { ...p, ...patch } : p)) });
  const removeFighter = (i) => set({
    fighters: sc.fighters.filter((_, k) => k !== i),
    teams: sc.teams?.map((p) => ({ ...p, order: (p.order ?? []).filter((k) => k !== i).map((k) => (k > i ? k - 1 : k)) })) ?? null,
    actions: sc.actions.filter((a) => a.fighter !== i).map((a) => ({
      ...a, fighter: a.fighter > i ? a.fighter - 1 : a.fighter, target: a.target == null ? null : a.target > i ? a.target - 1 : a.target === i ? null : a.target,
    })),
  });
  const addAction = (fighter, at) => {
    const enemy = sc.fighters.findIndex((f) => f.team !== sc.fighters[fighter].team);
    const action = { fighter, time: Math.round(at * 10) / 10, type: 'ability', slot: '1', target: enemy >= 0 ? enemy : null, x: null, y: null };
    set({ actions: [...sc.actions, action] });
    setEditing(sc.actions.length);
  };
  const setAction = (i, patch) => set({ actions: sc.actions.map((a, k) => (k === i ? { ...a, ...patch } : a)) });
  // What a tactical or learned brain did (casts, recasts, actives, spells) becomes timeline orders; the brain goes back
  // to the script, so the user can edit them (moves are not exported, so the replay can differ).
  const toTimeline = (i) => {
    const orders = (result?.policyOrders ?? []).filter((o) => o.fighter === i).map((o) => ({
      fighter: i, time: o.time, type: o.type, slot: o.slot ?? null, name: o.name ?? null, target: o.target ?? null, x: o.x ?? null, y: o.y ?? null,
    }));
    set({ actions: [...sc.actions, ...orders], fighters: sc.fighters.map((f, k) => (k === i ? { ...f, brain: 'script' } : f)) });
    setMessage(t('arena.toTimelineDone', { n: orders.length, i: i + 1 }));
  };
  const removeAction = (i) => {
    set({ actions: sc.actions.filter((_, k) => k !== i) });
    setEditing(null);
  };

  const save = async (asNew = false) => {
    try {
      const body = { ...sc, id: asNew ? null : sc.id };
      const out = body.id ? await api.put(`/api/arena/scenarios/${body.id}`, body) : await api.post('/api/arena/scenarios', body);
      set({ id: out.id, name: out.name });
      setSaved(await api.get('/api/arena/scenarios'));
      setMessage(t('arena.saved'));
    } catch (e) {
      setError(e.message);
    }
  };
  const open = async (id) => {
    if (!id) return;
    try {
      const s = await api.get(`/api/arena/scenarios/${id}`);
      setSc({ ...emptyScenario(), ...s });
      setTime(0);
      setEditing(null);
    } catch (e) {
      setError(e.message);
    }
  };
  const remove = async () => {
    if (!sc.id || !window.confirm(t('arena.deleteConfirm', { name: sc.name }))) return;
    await api.del(`/api/arena/scenarios/${sc.id}`);
    setSaved(await api.get('/api/arena/scenarios'));
    setSc(emptyScenario());
  };

  const frame = frameAt(result, time);
  const itemValues = useCallback(() => api.post('/api/arena/item-values', ranWith.current), []);
  const cardBars = useMemo(() => cooldownBars(result), [result]);
  useEffect(() => setCard(null), [result]);
  const nameList = sc.fighters.map((f, i) => result?.fighters?.[i]?.champion ?? (f.dummy ? f.dummy.name ?? 'Boneco de pano'
    : builds.find((b) => b.id === f.buildId)?.unitCode) ?? `#${i + 1}`);
  const names = useMemo(() => nameList, [nameList.join('|')]);

  const tabs = forcedTab ? null : (
    <div className="row arena-tabs" role="tablist">
      {['scenario', 'lab'].map((k) => (
        <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? 'primary' : ''} onClick={() => pickTab(k)}>
          {t(`arena.tab.${k}`)}
        </button>
      ))}
    </div>
  );
  if (tab === 'lab') {
    return (
      <div className="main arena-page">
        {tabs}
        <ArenaLab builds={builds} scenario={sc} names={names} />
      </div>
    );
  }

  return (
    <div className="main arena-page">
      {tabs}
      <section className="panel">
        <div className="spread">
          <h3>{t('arena.title')}</h3>
          <div className="row arena-files">
            <select value={sc.id ?? ''} onChange={(e) => open(Number(e.target.value))} aria-label={t('arena.saved')}>
              <option value="">{t('arena.open')}</option>
              {saved.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
            {!PUBLIC && <button onClick={() => { setSc(emptyScenario()); setTime(0); }}>{t('arena.new')}</button>}
            {!PUBLIC && <button className="primary" onClick={() => save(false)}>{t('arena.save')}</button>}
            {!PUBLIC && sc.id && <button onClick={() => save(true)}>{t('arena.saveCopy')}</button>}
            {!PUBLIC && sc.id && <button className="danger" onClick={remove}>{t('arena.delete')}</button>}
          </div>
        </div>
        <p className="muted" style={{ marginTop: 0 }}>{t(PUBLIC ? 'pub.arenaHelp' : 'arena.help')}</p>
        {PUBLIC && !sc.id && <p className="notice small">{t('pub.arenaPick')}</p>}
        {message && <p className="muted">{message}</p>}
        <fieldset className="plain" disabled={PUBLIC}>
        <div className="row arena-head">
          <label className="field grow">{t('arena.name')}<input value={sc.name} onChange={(e) => set({ name: e.target.value })} /></label>
          <label className="field narrow">{t('arena.duration')}
            <input type="number" min={5} max={180} value={sc.duration} onChange={(e) => set({ duration: Number(e.target.value) || 40 })} />
          </label>
        </div>
        <div className="row arena-zone" title={t('arena.zone.title')}>
          <label className="check"><input type="checkbox" checked={sc.safeZone != null}
            onChange={(e) => set({ safeZone: e.target.checked ? { team: 0, depth: 1200, damagePerSecond: 0 } : null })} />{t('arena.zone')}</label>
          {sc.safeZone && (
            <>
              <select value={sc.safeZone.team} onChange={(e) => set({ safeZone: { ...sc.safeZone, team: Number(e.target.value) } })} aria-label={t('arena.zone.side')}>
                <option value={0}>{t('report.team0')}</option>
                <option value={1}>{t('report.team1')}</option>
              </select>
              <label className="inline">{t('arena.zone.depth')}<input type="number" min={200} max={5000} step={100} value={sc.safeZone.depth} style={{ width: 80 }}
                onChange={(e) => set({ safeZone: { ...sc.safeZone, depth: Number(e.target.value) || 1200 } })} /></label>
              <label className="inline">{t('arena.zone.dps')}<input type="number" min={0} max={2000} step={50} value={sc.safeZone.damagePerSecond} style={{ width: 80 }}
                onChange={(e) => set({ safeZone: { ...sc.safeZone, damagePerSecond: Number(e.target.value) || 0 } })} /></label>
            </>
          )}
        </div>
        <MapChoice sc={sc} set={set} templates={templates}
          onTemplate={(key) => {
            const m = templates.find((x) => x.key === key);
            if (m) {
              setSc({ ...emptyScenario(), ...m.scenario, fighters: m.scenario.fighters.map((f) => ({ ...f, priority: f.priority ?? ['R', '3', '1', '2'] })) });
              setTime(0);
              setMessage(m.note);
            }
          }} />
        <div className="arena-sides">
          {[0, 1].map((team) => (
            <div key={team} className={`arena-side team-${team}`}>
              <h4 style={{ color: TEAM[team] }}>{t(`report.team${team}`)}</h4>
              <TeamPlanEditor team={team} plan={sc.teams?.[team]} fighters={sc.fighters} names={names}
                onChange={(p) => setPlan(team, p)} onEnable={() => set({ teams: [newPlan(), newPlan()] })} />
              {sc.fighters.map((f, i) => (f.team !== team ? null : (
                <FighterSlot key={i} index={i} slot={f} builds={builds} names={names} fighters={sc.fighters} planned={sc.teams != null}
                  stats={result?.fighters?.[i]} onChange={(p) => setFighter(i, p)} onRemove={() => removeFighter(i)}
                  policies={policies} brainOrders={(result?.policyOrders ?? []).filter((o) => o.fighter === i).length}
                  choice={result?.brainChoices?.find((c) => c.fighter === i)}
                  onToTimeline={() => toTimeline(i)} />
              )))}
              <button className="link" onClick={() => addFighter(team)}>{t('arena.addFighter')}</button>
            </div>
          ))}
        </div>
        </fieldset>
        {error && <p className="error">{error}</p>}
      </section>

      {!PUBLIC && <AutoTrain sc={sc} result={result} names={names} onDone={() => { loadPolicies(); setSc((s) => ({ ...s })); }} />}

      <Profiler id="arena-stage" onRender={onStageRender}>
      <section className="panel arena-stage">
        <div className="spread">
          <div className="row">
            <button className="primary" onClick={() => setPlaying((p) => !p)} disabled={!result}>
              {playing ? t('arena.pause') : t('arena.play')}
            </button>
            <button onClick={() => { setPlaying(false); setTime(0); }} disabled={!result}>⏮</button>
            <select value={speed} onChange={(e) => setSpeed(Number(e.target.value))} aria-label={t('arena.speed')}>
              {[0.25, 0.5, 1, 2, 4].map((s) => <option key={s} value={s}>{s}×</option>)}
            </select>
            <strong className="arena-clock">{n1(time)}s</strong>
            <label className="check" title={t('arena.card.pauseTitle')}>
              <input type="checkbox" checked={pauseOnClick} onChange={(e) => togglePauseOnClick(e.target.checked)} />{t('arena.card.pause')}
            </label>
            {busy && <small className="muted">{t('arena.running')}</small>}
          </div>
          {result && <Outcome result={result} />}
        </div>
        <ArenaMap result={result} frame={frame} paused={!playing} names={names} pickPoint={pickPoint} highlight={card}
          onPick={(x, y) => { if (pickPoint != null) { setAction(pickPoint, { x: Math.round(x), y: Math.round(y), target: null }); setPickPoint(null); } }}
          onSelect={(fighter, key, x, y) => openCard(fighter, key, null, x, y)} />
        <Timeline sc={sc} result={result} time={time} names={names} editing={editing} selected={card}
          onTime={(tm) => { setPlaying(false); setTime(tm); }} onAdd={PUBLIC ? () => {} : addAction} onEdit={PUBLIC ? () => {} : setEditing}
          onMove={(i, tm) => { if (!PUBLIC) setAction(i, { time: Math.max(0, Math.round(tm * 10) / 10) }); }}
          onPickCooldown={openCard} />
        {card && result && card.key && (
          <CooldownCard result={result} sel={card} time={time} frame={frame} bars={cardBars[card.fighter]?.find((c) => c.name === card.key)?.bars}
            buildOf={(i) => builds.find((b) => b.id === sc.fighters[i]?.buildId)} onClose={() => setCard(null)} />
        )}
        {editing != null && sc.actions[editing] && (
          <ActionEditor action={sc.actions[editing]} fighters={sc.fighters} names={names} stats={result?.fighters}
            onChange={(p) => setAction(editing, p)} onRemove={() => removeAction(editing)} onClose={() => setEditing(null)}
            onPickPoint={() => setPickPoint(editing)} picking={pickPoint === editing} />
        )}
      </section>
      </Profiler>

      {ready && !PUBLIC && (
        <TrainPanel sc={sc} names={names} policies={policies} onTrained={loadPolicies}
          onUse={(fighter, id) => setFighter(fighter, { brain: `ai:${id}` })}
          onUseMany={(latest) => {
            set({ fighters: sc.fighters.map((f, i) => (latest?.[i] != null ? { ...f, brain: `ai:${latest[i]}` } : f)) });
            setMessage(t('arena.chain.used', { n: Object.keys(latest ?? {}).length }));
          }} />
      )}
      {result && <FightReport result={result} names={names} time={time} onItemValues={PUBLIC ? null : itemValues} />}
    </div>
  );
}

/** An AI of a class: trained with every saved build of the class's champions that have an ability sheet. */
function ClassTrain({ sc, fighter, common, disabled, onJob, onError }) {
  const [profiles, setProfiles] = useState([]);
  const [builds, setBuilds] = useState([]);
  const [kits, setKits] = useState({});
  const [cls, setCls] = useState('Tanque');
  useEffect(() => {
    Promise.all([api.get('/api/champions/profiles'), api.get('/api/builds'), api.get('/api/arena/kits')]).then(([p, b, k]) => {
      setProfiles(p);
      setBuilds(b);
      setKits(k.campeoes ?? {});
    }).catch(() => {});
  }, []);
  const withSheet = profiles.filter((p) => kits[p.name]);
  const classes = [...new Set(withSheet.flatMap((p) => p.mainClasses))].sort();
  const champions = withSheet.filter((p) => p.mainClasses.includes(cls));
  const chosen = builds.filter((b) => champions.some((p) => p.code === b.unitCode));
  const start = async () => {
    try {
      onJob(await api.post('/api/arena/train/class', { ...common, fighter, className: cls, builds: chosen.map((b) => b.id) }));
    } catch (e) {
      onError(e.message);
    }
  };
  if (classes.length === 0) return null;
  return (
    <div className="row" title={t('arena.classTrain.title')}>
      <label className="inline">{t('arena.classTrain.label')}
        <select value={cls} onChange={(e) => setCls(e.target.value)} disabled={disabled}>
          {classes.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
      </label>
      <small className="muted">{t('arena.classTrain.builds', { n: chosen.length, list: champions.map((p) => p.name).join(', ') })}</small>
      <button onClick={start} disabled={disabled || chosen.length === 0}>{t('arena.classTrain.run')}</button>
    </div>
  );
}

const ALWAYS_KEY = `${STORE}-auto-train`;
const PENDING = ['queued', 'running', 'stopping'];

/**
 * "Treinar automaticamente": when the automatic brain played and its side lost, a training in the background from the
 * champion's or its class's best AI, until a clean victory or the end of its budget. One training at a time (the others
 * wait in line); "sempre treinar sozinho" asks by itself, once per scenario, and is remembered in the browser.
 */
function AutoTrain({ sc, result, names, onDone }) {
  const [always, setAlways] = useState(() => {
    try {
      return localStorage.getItem(ALWAYS_KEY) === '1';
    } catch {
      return false;
    }
  });
  const [line, setLine] = useState([]);
  const [note, setNote] = useState(null);
  const [error, setError] = useState(null);
  const asked = useRef(new Set());
  const mine = useRef(new Set());
  const lost = (result?.brainChoices ?? []).filter((c) => sc.fighters[c.fighter] && result.winner !== sc.fighters[c.fighter].team);

  const refresh = useCallback(async () => {
    const all = await api.get('/api/arena/train');
    setLine(all.filter((j) => PENDING.includes(j.status)));
    for (const j of all) {
      if (mine.current.has(j.id) && !PENDING.includes(j.status)) {
        mine.current.delete(j.id);
        const text = j.status === 'done'
          ? t(j.victory ? 'arena.autoTrain.won' : 'arena.autoTrain.notWon', { champion: j.champion, v: j.policy?.version ?? '?', s: n0((j.finishedAt - j.startedAt) / 1000) })
          : t('arena.autoTrain.stopped', { champion: j.champion, msg: j.message ?? j.status });
        setNote(text);
        try {
          if (window.Notification?.permission === 'granted') new Notification(t('arena.autoTrain.title'), { body: text });
        } catch {
          /* no notifications: the line on the page says it */
        }
        onDone();
      }
    }
  }, [onDone]);
  useEffect(() => { refresh().catch(() => {}); }, []); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (line.length === 0 && mine.current.size === 0) return undefined;
    const timer = setInterval(() => refresh().catch((e) => setError(e.message)), 1500);
    return () => clearInterval(timer);
  }, [line.length, refresh]);

  const train = useCallback(async (fighter) => {
    setError(null);
    asked.current.add(`${JSON.stringify(sc.fighters.map((f) => [f.buildId, f.minute, f.x, f.y]))}:${fighter}`);
    try {
      window.Notification?.requestPermission?.();
      const job = await api.post('/api/arena/train/auto', { scenario: sc, fighter });
      mine.current.add(job.id);
      await refresh();
    } catch (e) {
      setError(e.message);
    }
  }, [sc, refresh]);

  useEffect(() => {
    if (!always) return;
    for (const c of lost) {
      if (!asked.current.has(`${JSON.stringify(sc.fighters.map((f) => [f.buildId, f.minute, f.x, f.y]))}:${c.fighter}`)) train(c.fighter);
    }
  }, [always, result]); // eslint-disable-line react-hooks/exhaustive-deps

  const remember = (on) => {
    setAlways(on);
    try {
      localStorage.setItem(ALWAYS_KEY, on ? '1' : '0');
    } catch {
      /* session only */
    }
  };
  const cancel = async (j) => {
    await api.del(j.kind === 'chain' ? `/api/arena/train/chain/${j.id}` : `/api/arena/train/${j.id}`);
    await refresh();
  };
  if (lost.length === 0 && line.length === 0 && !note && !always) return null;
  return (
    <section className="panel arena-auto-train">
      {lost.length > 0 && <p className="warn">{t('arena.autoTrain.lost')}</p>}
      <div className="row">
        {lost.map((c) => (
          <button key={c.fighter} className="primary" onClick={() => train(c.fighter)} title={t('arena.autoTrain.buttonTitle')}>
            {t('arena.autoTrain.button', { name: names[c.fighter] })}
          </button>
        ))}
        <label className="check" title={t('arena.autoTrain.alwaysTitle')}>
          <input type="checkbox" checked={always} onChange={(e) => remember(e.target.checked)} />{t('arena.autoTrain.always')}
        </label>
      </div>
      {note && <p className="muted">{note}</p>}
      {error && <p className="error">{error}</p>}
      {line.length > 0 && (
        <ul className="arena-queue">
          {line.map((j) => (
            <li key={j.id}>
              <small>{t(`arena.autoTrain.${j.status === 'queued' ? 'queued' : 'running'}`, { name: j.champion ?? j.scenario ?? '' })}
                {j.generations ? ` · ${j.generation}/${j.generations}` : ''}{j.startedFrom ? ` · ${t('arena.autoTrain.from', { from: j.startedFrom })}` : ''}</small>
              {' '}<button className="link" onClick={() => cancel(j)}>{t('arena.train.cancel')}</button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** Champion names compared loosely (K'Sante / KSANTE). */
const champKey = (s) => (s ?? '').normalize('NFD').replace(/[^a-z]/gi, '').toLowerCase();
const pct = (x) => (x == null ? '—' : `${Math.round(x * 100)}%`);
const policyLabel = (p) => t('arena.brain.ai', { v: p.version, obj: t(`arena.obj.${p.objective}`), pct: pct(p.betterVsScript) })
  + (p.accepted ? '' : ` ${t('arena.train.notAccepted')}`) + (p.migratedFrom ? ` ${t('arena.brain.migrated', { v: p.migratedFrom })}` : '')
  + (p.otherPatch ? ` ${t('arena.brain.otherPatch', { patch: p.gamePatch })}` : '')
  + (p.oldElemental ? ` ${t('arena.brain.oldElemental')}` : '')
  + (p.oldBrittle ? ` ${t('arena.brain.oldBrittle')}` : '');

const OBJECTIVES = ['win_fast', 'damage', 'survive', 'team', 'escapar'];
const RUNNING = ['queued', 'running', 'stopping'];
const CHAIN_COLORS = ['#4fa8ff', '#f06a5f', '#5fbf6a', '#f0c75e', '#b48cf0', '#4fd1c5', '#f08cc0', '#9aa5b1'];

/**
 * Trains learned policies (backend, in the background): one champion, or a chain of them trained one after the other
 * round after round, each against the others' latest accepted versions. An evolution strategy over a small network
 * that starts from the tactical autopilot (or, in a chain, from the champion's previous version); a stage runs all its
 * generations, stops at a plateau, or stops when the champion's side wins the scenario's fight as this screen shows it
 * (then the slot takes the winning version and the fight runs again; in a chain, a champion whose version already wins
 * it keeps it). Shows the progress, the validation numbers and the saved versions.
 */
function TrainPanel({ sc, names, policies, onTrained, onUse, onUseMany }) {
  const [mode, setMode] = useState('single');
  const [fighter, setFighter] = useState(0);
  const [objective, setObjective] = useState('win_fast');
  const [generations, setGenerations] = useState(30);
  const [population, setPopulation] = useState(32);
  const [seed, setSeed] = useState(1);
  const [stopWhen, setStopWhen] = useState('generations');
  const [aliveToo, setAliveToo] = useState(false);
  const [plateau, setPlateau] = useState({ pct: 2, window: 6, min: 8 });
  const [order, setOrder] = useState([]);   // chain: slots in training order, with their checkbox
  const [perChampion, setPerChampion] = useState(false);
  const [objectives, setObjectives] = useState({});
  const [maxRounds, setMaxRounds] = useState(3);
  const [job, setJob] = useState(null);
  const [chain, setChain] = useState(null);
  const [error, setError] = useState(null);
  const [level, setLevel] = useState('sozinha');
  const [drills, setDrills] = useState(0.3);
  const [levels, setLevels] = useState(null);
  const [rules, setRules] = useState(null);   // the trained champion's guidelines, as saved
  const running = job != null && RUNNING.includes(job.status);
  const chainRunning = chain != null && RUNNING.includes(chain.status);
  useTask('arena-training', running || chainRunning, { label: t('tasks.training'), done: job?.generation, total: job?.generations, path: '/simulacoes/arena' });
  const busy = running || chainRunning;
  const saved = useRef(0);

  // the chain's list: the order kept so far (slots still in the scenario), then new slots, checked
  const slots = useMemo(() => {
    const known = order.filter((s) => s.slot < sc.fighters.length);
    const rest = sc.fighters.map((_, i) => i).filter((i) => !known.some((s) => s.slot === i)).map((slot) => ({ slot, on: true }));
    return [...known, ...rest];
  }, [order, sc.fighters.length]);

  useEffect(() => {
    if (!running) return undefined;
    const timer = setInterval(async () => {
      try {
        const j = await api.get(`/api/arena/train/${job.id}`);
        setJob(j);
        if (!RUNNING.includes(j.status)) {
          onTrained();
          // stopped by a victory: the slot takes the winning version and the screen runs the fight again
          if (j.status === 'done' && j.victory && j.policy && !j.champion?.startsWith('class:')) onUse(j.fighter, j.policy.id);
        }
      } catch (e) {
        setError(e.message);
      }
    }, 1000);
    return () => clearInterval(timer);
  }, [running, job?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!chainRunning) return undefined;
    const timer = setInterval(async () => {
      try {
        const c = await api.get(`/api/arena/train/chain/${chain.id}`);
        setChain(c);
        const n = c.stages.filter((s) => s.policy).length;
        if (n !== saved.current || !RUNNING.includes(c.status)) {
          saved.current = n;
          onTrained();   // a new version is in the list
        }
        if (!RUNNING.includes(c.status)) {
          // each champion whose last stage won the scenario (or kept a version that does) plays it in its slots
          const won = {};
          Object.entries(c.latest ?? {}).forEach(([slot, id]) => {
            const last = [...c.stages].reverse().find((s) => s.sameAs == null && s.policy
              && c.stages.some((x) => x.slot === Number(slot) && x.champion === s.champion));
            if (last?.victory) won[slot] = id;
          });
          if (Object.keys(won).length) onUseMany(won);
        }
      } catch (e) {
        setError(e.message);
      }
    }, 1000);
    return () => clearInterval(timer);
  }, [chainRunning, chain?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const common = {
    scenario: sc, objective, generations, population, episodesPerGeneration: 8, seed,
    stopWhen, aliveToo, plateauPct: plateau.pct, plateauWindow: plateau.window, minGenerations: plateau.min, level, drills,
  };
  const champion = names[fighter];
  useEffect(() => {
    if (!champion) return;
    api.get(`/api/arena/guidelines/${encodeURIComponent(champion)}`).then(setRules).catch(() => setRules(null));
  }, [champion]);
  const levelsRunning = levels != null && RUNNING.includes(levels.status);
  useEffect(() => {
    if (!levelsRunning) return undefined;
    const timer = setInterval(async () => {
      try {
        const j = await api.get(`/api/arena/train/levels/${levels.id}`);
        setLevels(j);
        if (!RUNNING.includes(j.status)) onTrained();
      } catch (e) {
        setError(e.message);
      }
    }, 1500);
    return () => clearInterval(timer);
  }, [levelsRunning, levels?.id]); // eslint-disable-line react-hooks/exhaustive-deps
  const compareLevels = async () => {
    setError(null);
    try {
      setLevels(await api.post('/api/arena/train/levels', { ...common, fighter }));
    } catch (e) {
      setError(e.message);
    }
  };
  const sameRules = (p) => JSON.stringify(p.guidelines ?? []) === JSON.stringify(rules?.guidelines ?? []);
  const start = async () => {
    setError(null);
    try {
      setJob(await api.post('/api/arena/train', { ...common, fighter }));
    } catch (e) {
      setError(e.message);
    }
  };
  const startChain = async () => {
    setError(null);
    const fighters = slots.filter((s) => s.on).map((s) => s.slot);
    if (!fighters.length) {
      setError(t('arena.chain.none'));
      return;
    }
    try {
      saved.current = 0;
      setChain(await api.post('/api/arena/train/chain', {
        ...common, fighters, maxRounds, objectives: perChampion ? Object.fromEntries(fighters.map((i) => [i, objectives[i] ?? objective])) : {},
      }));
    } catch (e) {
      setError(e.message);
    }
  };
  const call = async (fn) => {
    try {
      await fn();
    } catch (e) {
      setError(e.message);
    }
  };
  const cancel = () => call(async () => setJob(await api.del(`/api/arena/train/${job.id}`)));
  const stopChain = () => call(async () => setChain(await api.post(`/api/arena/train/chain/${chain.id}/stop-after-current`, {})));
  const cancelChain = () => call(async () => setChain(await api.del(`/api/arena/train/chain/${chain.id}`)));
  const remove = async (p) => {
    if (!window.confirm(t('arena.train.deleteConfirm', { v: p.version, champion: p.champion }))) return;
    call(async () => {
      await api.del(`/api/arena/policies/${p.id}`);
      onTrained();
    });
  };
  const mine = policies.filter((p) => champKey(p.champion) === champKey(names[fighter]));
  const done = job?.status === 'done' ? job.policy : null;
  const num = (v, fallback) => (Number.isFinite(Number(v)) && v !== '' ? Number(v) : fallback);

  return (
    <section className="panel arena-train">
      <div className="spread">
        <h3>{t('arena.train.title')}</h3>
        <div className="chips" role="tablist">
          {['single', 'chain'].map((m) => (
            <button key={m} role="tab" aria-selected={mode === m} className={mode === m ? 'active' : ''} onClick={() => setMode(m)}>
              {t(`arena.train.mode.${m}`)}
            </button>
          ))}
        </div>
      </div>
      <p className="muted" style={{ marginTop: 0 }}>{t(mode === 'chain' ? 'arena.train.chainHelp' : 'arena.train.help')}</p>
      {mode === 'chain' && (
        <div className="arena-chain-pick">
          <DragList items={slots.map((s) => s.slot)} onChange={(o) => setOrder(o.map((slot) => ({ slot, on: slots.find((s) => s.slot === slot).on })))}
            label={(slot) => {
              const s = slots.find((x) => x.slot === slot);
              return (
                <span className="arena-chain-item">
                  <input type="checkbox" checked={s.on} disabled={busy} aria-label={names[slot]}
                    onChange={(e) => setOrder(slots.map((x) => (x.slot === slot ? { ...x, on: e.target.checked } : x)))} />
                  <ChampionAvatar name={names[slot]} size={20} />
                  <span style={{ color: TEAM[sc.fighters[slot].team] }}>{slot + 1} · {names[slot]}</span>
                  {perChampion && (
                    <select value={objectives[slot] ?? objective} disabled={busy} onChange={(e) => setObjectives({ ...objectives, [slot]: e.target.value })}>
                      {OBJECTIVES.map((o) => <option key={o} value={o}>{t(`arena.obj.${o}`)}</option>)}
                    </select>
                  )}
                </span>
              );
            }} />
        </div>
      )}
      <div className="row">
        {mode === 'single' && (
          <label className="inline">{t('arena.train.fighter')}
            <select value={fighter} onChange={(e) => setFighter(Number(e.target.value))} disabled={busy}>
              {sc.fighters.map((f, i) => <option key={i} value={i}>{i + 1} · {names[i]}</option>)}
            </select>
          </label>
        )}
        <label className="inline">{t('arena.train.objective')}
          <select value={objective} onChange={(e) => setObjective(e.target.value)} disabled={busy}>
            {OBJECTIVES.map((o) => <option key={o} value={o}>{t(`arena.obj.${o}`)}</option>)}
          </select>
        </label>
        <label className="inline" title={t('arena.train.levelTitle')}>{t('arena.train.level')}
          <select value={level} onChange={(e) => setLevel(e.target.value)} disabled={busy}>
            {['sozinha', 'descobertos', 'dicas'].map((l) => <option key={l} value={l}>{t(`arena.level.${l}`)}</option>)}
          </select>
        </label>
        <label className="inline" title={t('arena.train.drillsTitle')}>{t('arena.train.drills')}
          <input type="number" min={0} max={100} step={5} value={Math.round(drills * 100)} disabled={busy} style={{ width: 60 }}
            onChange={(e) => setDrills(Math.max(0, Math.min(100, num(e.target.value, 30))) / 100)} />
        </label>
        {mode === 'chain' && (
          <label className="check">
            <input type="checkbox" checked={perChampion} disabled={busy} onChange={(e) => setPerChampion(e.target.checked)} />
            {t('arena.train.perChampion')}
          </label>
        )}
        <label className="inline" title={t('arena.train.generationsTitle')}>{t(mode === 'chain' ? 'arena.train.generationsMax' : 'arena.train.generations')}
          <input type="number" min={1} max={200} value={generations} disabled={busy} onChange={(e) => setGenerations(num(e.target.value, 30))} />
        </label>
        <label className="inline" title={t('arena.train.populationTitle')}>{t('arena.train.population')}
          <input type="number" min={4} max={128} step={2} value={population} disabled={busy} onChange={(e) => setPopulation(num(e.target.value, 32))} />
        </label>
        <label className="inline" title={t('arena.train.seedTitle')}>{t('arena.train.seed')}
          <input type="number" value={seed} disabled={busy} onChange={(e) => setSeed(num(e.target.value, 1))} />
        </label>
        {mode === 'chain' && (
          <label className="inline" title={t('arena.train.roundsTitle')}>{t('arena.train.rounds')}
            <input type="number" min={1} max={20} value={maxRounds} disabled={busy} onChange={(e) => setMaxRounds(num(e.target.value, 3))} />
          </label>
        )}
      </div>
      <div className="row">
        <label className="inline">{t('arena.train.stopWhen')}
          <select value={stopWhen} disabled={busy} onChange={(e) => setStopWhen(e.target.value)}>
            {['generations', 'plateau', 'victory', 'clean_victory'].map((w) => <option key={w} value={w}>{t(`arena.train.stopWhen.${w}`)}</option>)}
          </select>
        </label>
        {stopWhen === 'victory' && (
          <label className="inline" title={t('arena.train.aliveTooTitle')}>
            <input type="checkbox" checked={aliveToo} disabled={busy} onChange={(e) => setAliveToo(e.target.checked)} />
            {t('arena.train.aliveToo')}
          </label>
        )}
        {stopWhen === 'plateau' && (
          <>
            <label className="inline">{t('arena.train.plateau')}
              <input type="number" min={0} max={100} step={0.5} value={plateau.pct} disabled={busy} onChange={(e) => setPlateau({ ...plateau, pct: num(e.target.value, 0) })} />
            </label>
            <label className="inline">{t('arena.train.plateauWindow')}
              <input type="number" min={1} max={100} value={plateau.window} disabled={busy || !plateau.pct} onChange={(e) => setPlateau({ ...plateau, window: num(e.target.value, 6) })} />
            </label>
            <label className="inline">{t('arena.train.plateauMin')}
              <input type="number" min={1} max={200} value={plateau.min} disabled={busy || !plateau.pct} onChange={(e) => setPlateau({ ...plateau, min: num(e.target.value, 8) })} />
            </label>
          </>
        )}
        {mode === 'single' && (running
          ? <button className="danger" onClick={cancel}>{t('arena.train.cancel')}</button>
          : <button className="primary" onClick={start} disabled={busy || levelsRunning}>{t('arena.train.run')}</button>)}
        {mode === 'single' && (levelsRunning
          ? <button className="danger" onClick={() => call(async () => setLevels(await api.del(`/api/arena/train/levels/${levels.id}`)))}>{t('arena.train.cancel')}</button>
          : <button onClick={compareLevels} disabled={busy} title={t('arena.levels.title')}>{t('arena.levels.run')}</button>)}
        {mode === 'chain' && !chainRunning && <button className="primary" onClick={startChain} disabled={busy}>{t('arena.train.chainRun')}</button>}
      </div>
      <p className="muted"><small>
        {stopWhen === 'clean_victory' ? t('arena.train.cleanHelp') : stopWhen === 'victory' ? t(mode === 'chain' ? 'arena.train.victoryHelpChain' : 'arena.train.victoryHelp')
          : stopWhen === 'generations' ? t('arena.train.generationsHelp')
            : plateau.pct > 0 ? t('arena.train.plateauHelp', { p: plateau.pct, w: plateau.window, min: plateau.min }) : t('arena.train.plateauOff')}
      </small></p>
      {error && <p className="error">{error}</p>}
      {mode === 'single' && job && (
        <div className="arena-train-job">
          <div className="arena-train-bar"><div style={{ width: `${(100 * job.generation) / Math.max(1, job.generations)}%` }} /></div>
          <small className="muted">
            {t('arena.train.status', { g: job.generation, n: job.generations, msg: job.message ?? job.status })}
            {job.opponents?.length > 0 && ` · ${t('arena.train.opponents')}: ${job.opponents.join(', ')}`}
          </small>
          {job.progress?.length > 1 && <TrainChart points={job.progress} />}
          {done && (
            <p>
              <strong>{t('arena.train.saved', { v: done.version, champion: done.champion })}</strong>
              {job.plateau && <span className="muted"> ({t('arena.train.plateauHit', { g: done.generations })})</span>}
              {job.victory && <span className="good"> ({t('arena.train.victoryHit', { g: done.generations })})</span>}
              <br />
              <span className={done.winsScenario ? 'good' : 'muted'}>{t(done.winsScenario ? 'arena.train.winsScenario' : 'arena.train.notWinsScenario')}</span>
              {' · '}<span className={done.accepted ? 'good' : 'warn'}>{t(done.accepted ? 'arena.train.acceptedShort' : 'arena.train.notAcceptedShort')}</span>
              <br />
              {' — '}{t('arena.train.numbers', { script: pct(done.betterVsScript), tactical: pct(done.betterVsTactical), win: pct(done.winrate), scriptWin: pct(done.winrateScript) })}
              {done.betterVsPrevious != null && ` · ${t('arena.train.vsPrevious', { p: pct(done.betterVsPrevious) })}`}
              {!done.accepted && <span className="warn"> · {t('arena.train.notAcceptedLong')}</span>}
              <br />
              <button onClick={() => onUse(fighter, done.id)}>{t('arena.train.use', { n: fighter + 1 })}</button>
            </p>
          )}
        </div>
      )}
      {levels && <LevelsTable job={levels} />}
      {mode === 'single' && <ClassTrain sc={sc} fighter={fighter} common={common} disabled={busy} onJob={setJob} onError={setError} />}
      {mode === 'single' && rules && <GuidelinesEditor rules={rules} champion={champion}
        onSaved={setRules} onError={setError} />}
      {mode === 'chain' && chain && (
        <ChainProgress chain={chain} names={names} running={chainRunning} onStop={stopChain} onCancel={cancelChain}
          onUse={() => onUseMany(chain.latest)} />
      )}
      {mode === 'single' && mine.length > 0 && (
        <details className="report-details" open>
          <summary>{t('arena.train.savedList', { champion: names[fighter], n: mine.length })}</summary>
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr><th className="l">{t('arena.train.version')}</th><th>{t('arena.train.objective')}</th><th>{t('arena.train.vsScript')}</th>
                  <th>{t('arena.train.vsTactical')}</th><th>{t('arena.train.vsPreviousCol')}</th><th>{t('arena.train.wins')}</th><th /></tr>
              </thead>
              <tbody>
                {mine.map((p) => (
                  <tr key={p.id}>
                    <td className="l">v{p.version}{p.accepted ? ' ✓' : ''}
                      {p.winsScenario && <span className="good" title={t('arena.train.winsScenario')}> 🏆</span>} <small className="muted">{p.scenario}{p.chainRound != null ? ` · ${t('arena.chain.roundShort', { r: p.chainRound })}` : ''}
                      {` · ${t(`arena.level.${p.level ?? 'sozinha'}`)}`}</small>
                      {p.level === 'dicas' && !sameRules(p) && <small className="warn" title={t('arena.levels.otherRulesTitle')}> ⚠ {t('arena.levels.otherRules')}</small>}
                      {p.otherPatch && <small className="warn" title={t('arena.brain.otherPatchTitle')}> {t('arena.brain.otherPatch', { patch: p.gamePatch })}</small>}
                      {p.oldElemental && <small className="warn" title={t('arena.brain.oldElementalTitle')}> {t('arena.brain.oldElemental')}</small>}
                      {p.oldBrittle && <small className="warn" title={t('arena.brain.oldBrittleTitle')}> {t('arena.brain.oldBrittle')}</small>}
                    </td>
                    <td>{t(`arena.obj.${p.objective}`)}</td>
                    <td>{pct(p.betterVsScript)}</td><td>{pct(p.betterVsTactical)}</td><td>{pct(p.betterVsPrevious)}</td>
                    <td>{pct(p.winrate)} <small className="muted">({pct(p.winrateScript)})</small></td>
                    <td>
                      <button className="link" onClick={() => onUse(fighter, p.id)}>{t('arena.train.useShort')}</button>
                      <button className="icon danger" onClick={() => remove(p)} aria-label={t('arena.train.delete')}>✕</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}
    </section>
  );
}

/** "Comparar níveis": the same training at each help level, and its numbers per validation fight. */
function LevelsTable({ job }) {
  return (
    <div className="arena-train-job">
      <small className="muted">{job.message}{job.current && RUNNING.includes(job.status) ? ` (${t(`arena.level.${job.current}`)})` : ''}</small>
      {job.rows.length > 0 && (
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th className="l">{t('arena.train.level')}</th><th>{t('arena.levels.objective')}</th><th>{t('arena.train.wins')}</th>
              <th>{t('arena.levels.consumed')}</th><th>{t('arena.levels.overwritten')}</th><th>{t('arena.levels.chained')}</th>
              <th>{t('arena.levels.combos')}</th><th>{t('arena.train.vsScript')}</th></tr></thead>
            <tbody>
              {job.rows.map((r) => (
                <tr key={r.level}>
                  <td className="l">{t(`arena.level.${r.level}`)} <small className="muted">v{r.policy?.version}</small></td>
                  <td>{n0(r.objetivo)}</td><td>{pct(r.vitorias)}</td><td>{n1(r.marcas_consumidas)}</td><td>{n1(r.marcas_sobrescritas)}</td>
                  <td>{n1(r.controle_encadeado)}</td><td>{n1(r.combos_usados)}</td><td>{pct(r.policy?.betterVsScript)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

const EFFECTS = ['preferir', 'evitar', 'obrigar', 'proibir'];
const condText = (cs) => (cs ?? []).map((c) => `${c.fact} ${c.op} ${c.value}`).join(' e ');
const parseConds = (text) => text.split(/\s+e\s+|;/).map((x) => x.trim()).filter(Boolean).map((x) => {
  const m = x.match(/^(\S+)\s*(<=|>=|==|!=|<|>)\s*(-?[\d.,]+)$/);
  return m ? { fact: m[1], op: m[2], value: Number(m[3].replace(',', '.')) } : { fact: x, op: '>', value: 0 };
});

/** A champion's guidelines (used at the help level "com dicas do usuário"), with the one generic preset. */
function GuidelinesEditor({ rules, champion, onSaved, onError }) {
  const [list, setList] = useState(rules.guidelines ?? []);
  useEffect(() => setList(rules.guidelines ?? []), [rules]);
  const set = (i, p) => setList(list.map((g, k) => (k === i ? { ...g, ...p } : g)));
  const save = async () => {
    try {
      onSaved(await api.put(`/api/arena/guidelines/${encodeURIComponent(champion)}`, { ...rules, guidelines: list }));
    } catch (e) {
      onError(e.message);
    }
  };
  const preset = async () => setList([...list, ...(await api.get('/api/arena/guidelines-preset'))]);
  return (
    <details className="report-details">
      <summary>{t('arena.rules.title', { champion, n: list.length })}</summary>
      <p className="muted small">{t('arena.rules.help')}</p>
      {list.map((g, i) => (
        <div key={i} className="row arena-rule">
          <span className="muted">{t('arena.rules.when')}</span>
          <input style={{ flex: 2, minWidth: 220 }} defaultValue={condText(g.when)} key={`${i}-${condText(g.when)}`}
            onBlur={(e) => set(i, { when: parseConds(e.target.value) })} aria-label={t('arena.rules.when')} />
          <span className="muted">→</span>
          <select value={g.effect} onChange={(e) => set(i, { effect: e.target.value })}>
            {EFFECTS.map((x) => <option key={x} value={x}>{t(`arena.rules.effect.${x}`)}</option>)}
          </select>
          <input value={g.action ?? ''} onChange={(e) => set(i, { action: e.target.value })} style={{ width: 130 }} aria-label={t('arena.rules.action')} />
          <label className="inline">{t('arena.rules.strength')}<input type="number" step={0.5} value={g.strength} style={{ width: 55 }}
            onChange={(e) => set(i, { strength: Number(e.target.value) || 0 })} /></label>
          <label className="inline">{t('arena.rules.reward')}<input type="number" step={10} value={g.reward ?? ''} style={{ width: 60 }}
            onChange={(e) => set(i, { reward: e.target.value === '' ? null : Number(e.target.value) })} /></label>
          <button className="link danger" onClick={() => setList(list.filter((_, k) => k !== i))}>✕</button>
        </div>
      ))}
      <div className="row">
        <button className="link" onClick={() => setList([...list, { id: `regra${list.length + 1}`, when: [], effect: 'preferir', action: 'atacar', strength: 1, reward: null }])}>{t('arena.rules.add')}</button>
        <button className="link" onClick={preset}>{t('arena.rules.preset')}</button>
        <button onClick={save}>{t('arena.rules.save')}</button>
      </div>
    </details>
  );
}

/** A chain under way or finished: the round, the strength of each champion per round, one line per champion. */
function ChainProgress({ chain, names, running, onStop, onCancel, onUse }) {
  // one line per trained slot: its latest stage (the current round's, else the last one it had)
  const trained = [];
  chain.stages.forEach((s) => {
    if (s.sameAs == null && !trained.includes(s.slot)) trained.push(s.slot);
  });
  const latest = (slot) => [...chain.stages].reverse().find((s) => s.slot === slot && s.status !== 'waiting')
    ?? chain.stages.find((s) => s.slot === slot);
  const current = chain.current >= 0 ? chain.stages[chain.current] : null;
  const status = (s) => {
    if (s.status === 'accepted') return t('arena.chain.status.accepted', { v: s.policy?.version });
    if (s.status === 'kept') return t('arena.chain.status.kept', { v: s.policy?.version });
    if (s.status === 'won') return t('arena.chain.status.won', { v: s.policy?.version });
    if (s.status === 'training' && s.message?.startsWith('venceu')) return t('arena.chain.status.victory');
    if (s.status === 'training' && s.message?.startsWith('platô')) return t('arena.chain.status.plateau');
    return t(`arena.chain.status.${s.status}`);
  };
  const used = Object.keys(chain.latest ?? {}).length;
  return (
    <div className="arena-chain">
      <div className="spread">
        <strong>{t('arena.chain.round', { r: chain.round, n: chain.maxRounds })}</strong>
        {running && (
          <div className="row">
            <button onClick={onStop} disabled={chain.stopAfterCurrent}>
              {chain.stopAfterCurrent ? t('arena.chain.stopping', { champion: current?.champion ?? '' }) : t('arena.chain.stopAfter')}
            </button>
            <button className="danger" onClick={onCancel}>{t('arena.chain.cancel')}</button>
          </div>
        )}
      </div>
      {!running && (
        <p>
          <strong>{t(`arena.chain.end.${chain.status}`)}</strong> <small className="muted">{chain.message}</small>
          {used > 0 && <><br /><button className="primary" onClick={onUse}>{t('arena.chain.use')}</button></>}
        </p>
      )}
      {running && chain.message && <small className="muted">{chain.message}</small>}
      <RoundChart chain={chain} trained={trained} names={names} />
      <table className="data arena-chain-table">
        <tbody>
          {trained.map((slot) => {
            const s = latest(slot);
            const p = s.policy;
            return (
              <tr key={slot} className={current === s ? 'current' : ''}>
                <td className="l"><ChampionAvatar name={s.champion} size={22} /> {slot + 1} · {names[slot] ?? s.champion}</td>
                <td className="l">
                  {status(s)}{s.plateau && s.status !== 'training' ? ` · ${t('arena.chain.status.plateau')}` : ''}
                  {s.victory && s.status === 'accepted' && <span className="good"> · {t('arena.chain.status.victory')}</span>}
                  {s.warning && <><br /><small className="warn">{s.warning}</small></>}
                </td>
                <td className="arena-chain-gen">
                  <div className="arena-train-bar"><div style={{ width: `${(100 * s.generation) / Math.max(1, s.generations)}%` }} /></div>
                  <small className="muted">{s.generation}/{s.generations}</small>
                </td>
                <td><Sparkline values={s.progress.map((x) => x.metric)} /></td>
                <td className="l">
                  {p ? t('arena.chain.validation', { script: pct(p.betterVsScript), prev: pct(p.betterVsPrevious) }) : '—'}
                  {s.vsOldOpponents != null && <><br /><small className="muted">{t('arena.chain.vsOld', { p: pct(s.vsOldOpponents) })}</small></>}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {chain.stages.some((s) => s.sameAs != null) && (
        <small className="muted">{chain.stages.filter((s) => s.sameAs != null && s.round === 1)
          .map((s) => `${s.slot + 1} · ${s.champion}: ${t('arena.chain.sameAs', { n: s.sameAs + 1 })}`).join(' · ')}</small>
      )}
    </div>
  );
}

/**
 * The plateau metric of a stage, generation by generation, on a fixed 0–100% scale (100 always at the top, so 100 ->
 * 94 looks like a small dip and 0 -> 4 like a small rise), with the 55% acceptance line and the last value.
 */
function Sparkline({ values }) {
  const v = values.filter((x) => x != null).map((x) => Math.max(0, Math.min(100, x)));
  const W = 120;
  const H = 28;
  const y = (x) => H - 2 - ((H - 4) * x) / 100;
  const x = (i) => (v.length < 2 ? W / 2 : 1 + ((W - 2) * i) / (v.length - 1));
  const pts = v.map((val, i) => `${x(i).toFixed(1)},${y(val).toFixed(1)}`).join(' ');
  const last = v[v.length - 1];
  return (
    <span className="arena-spark-wrap">
      <svg className="arena-spark" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label={t('arena.chain.metric')}>
        <title>{`${t('arena.chain.metric')}: ${v.map((val) => Math.round(val)).join(' · ')}`}</title>
        <rect x="0" y="0" width={W} height={H} rx="3" fill="rgba(127,127,127,.08)" />
        <line x1="0" x2={W} y1={y(55)} y2={y(55)} stroke="var(--border)" strokeDasharray="3 2" />
        {v.length > 1 && (
          <>
            <polygon points={`${x(0)},${y(0)} ${pts} ${x(v.length - 1)},${y(0)}`} fill={TEAM[0]} opacity="0.18" />
            <polyline points={pts} fill="none" stroke={TEAM[0]} strokeWidth="1.5" />
          </>
        )}
        {v.length > 0 && <circle cx={x(v.length - 1)} cy={y(last)} r="2.5" fill={TEAM[0]} />}
      </svg>
      {v.length > 0 && <small className={last >= 55 ? 'good' : 'muted'}>{Math.round(last)}%</small>}
    </span>
  );
}

/** Each champion's validation against the script (0–100%) at the end of each round. */
function RoundChart({ chain, trained, names }) {
  const W = 600;
  const H = 110;
  const rounds = Math.max(2, chain.maxRounds);
  const x = (r) => 24 + ((W - 40) * (r - 1)) / (rounds - 1);
  const y = (v) => H - 14 - (H - 24) * v;
  const series = trained.map((slot, k) => ({
    slot,
    color: CHAIN_COLORS[k % CHAIN_COLORS.length],
    pts: chain.stages.filter((s) => s.slot === slot && s.policy).map((s) => [s.round, s.policy.betterVsScript ?? 0]),
  }));
  if (!series.some((s) => s.pts.length)) return null;
  return (
    <figure className="arena-train-chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={t('arena.chain.strength')}>
        {[0, 0.55, 1].map((v) => (
          <g key={v}>
            <line x1={20} x2={W - 12} y1={y(v)} y2={y(v)} stroke="var(--border)" strokeDasharray={v === 0.55 ? '4 3' : undefined} />
            <text x={2} y={y(v) + 4} fontSize="10" fill="var(--text-2)">{Math.round(v * 100)}</text>
          </g>
        ))}
        {Array.from({ length: rounds }, (_, i) => (
          <text key={i} x={x(i + 1)} y={H - 1} fontSize="10" textAnchor="middle" fill="var(--text-2)">{i + 1}</text>
        ))}
        {series.map((s) => (
          <g key={s.slot}>
            <polyline points={s.pts.map(([r, v]) => `${x(r)},${y(v)}`).join(' ')} fill="none" stroke={s.color} strokeWidth="2" />
            {s.pts.map(([r, v]) => (
              <circle key={r} cx={x(r)} cy={y(v)} r="3.5" fill={s.color}>
                <title>{`${names[s.slot]} · ${t('arena.chain.roundShort', { r })}: ${pct(v)}`}</title>
              </circle>
            ))}
          </g>
        ))}
      </svg>
      <figcaption className="muted"><small>
        {t('arena.chain.strength')}{' — '}
        {series.map((s) => <span key={s.slot} style={{ color: s.color, marginRight: 8 }}>● {names[s.slot]}</span>)}
      </small></figcaption>
    </figure>
  );
}

/** Mean and best fitness per generation (left scale) and the selection checks against the script (0–100%, dots). */
function TrainChart({ points }) {
  const W = 600;
  const H = 120;
  const vals = points.flatMap((p) => [p.mean, p.best]);
  const lo = Math.min(...vals);
  const hi = Math.max(...vals);
  const n = points[points.length - 1].generation;
  const x = (g) => 8 + ((W - 16) * (g - 1)) / Math.max(1, n - 1);
  const y = (v) => H - 8 - ((H - 16) * (v - lo)) / Math.max(1e-9, hi - lo);
  const line = (key) => points.map((p) => `${x(p.generation).toFixed(1)},${y(p[key]).toFixed(1)}`).join(' ');
  return (
    <figure className="arena-train-chart">
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img" aria-label={t('arena.train.chart')}>
        <polyline points={line('best')} fill="none" stroke="var(--text-3, #888)" strokeWidth="1" strokeDasharray="3 3" />
        <polyline points={line('mean')} fill="none" stroke={TEAM[0]} strokeWidth="2" />
        {points.filter((p) => p.selection != null).map((p) => (
          <circle key={p.generation} cx={x(p.generation)} cy={H - 8 - (H - 16) * p.selection} r="3.5" fill="#5fbf6a">
            <title>{t('arena.train.selectionDot', { g: p.generation, p: pct(p.selection) })}</title>
          </circle>
        ))}
      </svg>
      <figcaption className="muted"><small>{t('arena.train.legend')}</small></figcaption>
    </figure>
  );
}

function Outcome({ result }) {
  const text = result.winner < 0 ? t('report.draw', { s: n1(result.duration) })
    : t('report.win', { team: t(`report.team${result.winner}`), s: n1(result.duration) });
  return <strong>{text}</strong>;
}

/**
 * A short list reordered by dragging (or with the arrow buttons, for keyboards): the side's enemy priority, a
 * champion's ability order.
 */
function DragList({ items, label, onChange, className = '' }) {
  const [from, setFrom] = useState(null);
  const move = (i, j) => {
    if (j < 0 || j >= items.length || i === j) return;
    const next = [...items];
    const [x] = next.splice(i, 1);
    next.splice(j, 0, x);
    onChange(next);
  };
  const across = className.includes('inline');   // a row: earlier / later are left / right, else up / down
  return (
    <ol className={`arena-drag ${className}`}>
      {items.map((x, i) => (
        <li key={x} draggable onDragStart={() => setFrom(i)} onDragOver={(e) => e.preventDefault()}
          onDrop={() => { if (from != null) move(from, i); setFrom(null); }} className={from === i ? 'dragging' : ''}>
          <span className="arena-drag-handle" aria-hidden>⋮⋮</span>
          {label(x, i)}
          <button className="icon" onClick={() => move(i, i - 1)} disabled={i === 0} aria-label={t('arena.up')}>{across ? '‹' : '▲'}</button>
          <button className="icon" onClick={() => move(i, i + 1)} disabled={i === items.length - 1} aria-label={t('arena.down')}>{across ? '›' : '▼'}</button>
        </li>
      ))}
    </ol>
  );
}

/** How a side picks its targets: criterion, enemy order, shared focus, switch threshold, chained control. */
function TeamPlanEditor({ team, plan, fighters, names, onChange, onEnable }) {
  if (!plan) {
    return team !== 0 ? null : (
      <div className="arena-plan legacy">
        <small className="muted">{t('arena.team.legacy')}</small>{' '}
        <button className="link" onClick={onEnable}>{t('arena.team.enable')}</button>
      </div>
    );
  }
  const enemies = fighters.map((f, i) => ({ f, i })).filter(({ f }) => f.team !== team).map(({ i }) => i);
  // The saved order first (only enemies still in the scenario), then the others in slot order.
  const order = [...(plan.order ?? []).filter((i) => enemies.includes(i)), ...enemies.filter((i) => !(plan.order ?? []).includes(i))];
  const switching = plan.switchBelow != null;
  return (
    <div className="arena-plan">
      <strong className="arena-plan-title">{t('arena.team.title')}</strong>
      <div className="row">
        <label className="inline">{t('arena.team.focus')}
          <select value={plan.focus ?? 'nearest'} onChange={(e) => onChange({ focus: e.target.value })}>
            {FOCUS.map((f) => <option key={f} value={f}>{t(`arena.team.focus.${f}`)}</option>)}
          </select>
        </label>
        <label className="check" title={t('arena.team.sharedTitle')}>
          <input type="checkbox" checked={plan.shared} onChange={(e) => onChange({ shared: e.target.checked })} />
          {t('arena.team.shared')}
        </label>
        <label className="check" title={t('arena.team.switchTitle')}>
          <input type="checkbox" checked={switching} onChange={(e) => onChange({ switchBelow: e.target.checked ? 0.25 : null })} />
          {t('arena.team.switch')}
        </label>
        {switching && (
          <label className="inline">
            <input type="number" min={1} max={99} step={1} value={Math.round(plan.switchBelow * 100)} aria-label={t('arena.team.switch')}
              onChange={(e) => onChange({ switchBelow: Math.max(0.01, Math.min(0.99, (Number(e.target.value) || 25) / 100)) })} />%
          </label>
        )}
        <label className="check" title={t('arena.team.chainCcTitle')}>
          <input type="checkbox" checked={plan.chainCc} onChange={(e) => onChange({ chainCc: e.target.checked })} />
          {t('arena.team.chainCc')}
        </label>
      </div>
      <div className="row" title={t('arena.team.orderTitle')}>
        <small className="muted">{t('arena.team.order')}</small>
        {order.length === 0 ? <small className="muted">{t('arena.team.noEnemies')}</small>
          : <DragList className="inline" items={order} label={(i, k) => <span>{k + 1}. {names[i]}</span>} onChange={(o) => onChange({ order: o })} />}
      </div>
    </div>
  );
}

/** Why the automatic brain picked what it did, in words. */
function choiceText(c, name) {
  if (c.kind === 'tatico') return t('arena.autoBrain.tatico', { name });
  const how = c.time == null ? '' : t(c.clean && !c.died ? 'arena.autoBrain.clean' : !c.died ? 'arena.autoBrain.alive' : 'arena.autoBrain.won', { s: n1(c.time) });
  return t(`arena.autoBrain.${c.kind}`, { v: c.version, owner: c.owner, how });
}

function FighterSlot({ index, slot, builds, names, fighters, planned, stats, onChange, onRemove, policies, brainOrders, onToTimeline, choice }) {
  const enemies = fighters.map((f, i) => ({ f, i })).filter(({ f }) => f.team !== slot.team);
  const mine = policies.filter((p) => champKey(p.champion) === champKey(names[index]) || p.id === choice?.policyId);
  const brain = slot.brain ?? 'script';
  if (slot.dummy) return <DummySlot index={index} slot={slot} onChange={onChange} onRemove={onRemove} />;
  const build = builds.find((b) => b.id === slot.buildId);
  return (
    <div className="arena-slot" style={{ borderLeftColor: TEAM[slot.team] }}>
      <div className="row">
        <strong className="arena-idx">{index + 1}</strong>
        <select value={slot.buildId ?? ''} onChange={(e) => onChange({ buildId: e.target.value ? Number(e.target.value) : null })} aria-label={t('report.build')}>
          <option value="">{t('report.pickBuild')}</option>
          {builds.map((b) => <option key={b.id} value={b.id}>{b.name} · {b.unitCode}</option>)}
        </select>
        {build && <BuildLink id={build.id} name={build.name} minute={slot.minute ?? undefined}>{t('preview.short')}</BuildLink>}
        <label className="inline slot-minute" title={t('arena.slotMinuteTitle')}>{t('arena.slotMinute')}
          <MinuteInput value={slot.minute} placeholder={t('report.minuteAuto')} onChange={(minute) => onChange({ minute })} />
        </label>
        <button className="icon danger" onClick={onRemove} aria-label={t('arena.removeFighter')}>✕</button>
      </div>
      {build && <BuildAtMinute build={build} minute={slot.minute} onMinute={(minute) => onChange({ minute })} />}
      <div className="row">
        <label className="inline" title={t('arena.positionTitle')}>{t('arena.position')}
          <input type="number" value={slot.x ?? ''} placeholder="Nexus" onChange={(e) => onChange({ x: e.target.value === '' ? null : Number(e.target.value) })} aria-label="x" />
          <input type="number" value={slot.y ?? ''} placeholder="y" onChange={(e) => onChange({ y: e.target.value === '' ? null : Number(e.target.value) })} aria-label="y" />
        </label>
        <label className="inline">{t('report.target')}
          <select value={slot.target ?? ''} onChange={(e) => onChange({ target: e.target.value === '' ? null : Number(e.target.value) })}>
            <option value="">{t('arena.nearest')}</option>
            {enemies.map(({ i }) => <option key={i} value={i}>{i + 1} · {names[i]}</option>)}
          </select>
        </label>
        {planned && (
          <label className="check" title={t('arena.followTeamTitle')}>
            <input type="checkbox" checked={slot.followTeam ?? true} onChange={(e) => onChange({ followTeam: e.target.checked })} />
            {t('arena.followTeam')}
          </label>
        )}
        <label className="check" title={t('arena.autoTitle')}>
          <input type="checkbox" checked={slot.autoAbilities} onChange={(e) => onChange({ autoAbilities: e.target.checked })} />
          {t('arena.auto')}
        </label>
        <label className="inline" title={t('arena.brainTitle')}>{t('arena.brain')}
          <select value={brain} onChange={(e) => onChange({ brain: e.target.value })}>
            <option value="auto">{t('arena.brain.auto')}</option>
            <option value="script">{t('arena.brain.script')}</option>
            <option value="tactical">{t('arena.brain.tactical')}</option>
            {brain === 'tactical_stand' && <option value="tactical_stand">{t('arena.brain.stand')}</option>}
            {mine.map((p) => <option key={p.id} value={`ai:${p.id}`}>{policyLabel(p)}</option>)}
            {brain.startsWith('ai:') && !mine.some((p) => `ai:${p.id}` === brain) && <option value={brain}>{t('arena.brain.missing')}</option>}
          </select>
        </label>
        {brain !== 'script' && brainOrders > 0 && (
          <button className="link" title={t('arena.toTimelineTitle')} onClick={onToTimeline}>{t('arena.toTimeline', { n: brainOrders })}</button>
        )}
      </div>
      {brain === 'auto' && choice && (
        <div className="row">
          <small className="muted">{choiceText(choice, names[index])}</small>
          <button className="link" title={t('arena.autoBrain.fixTitle')} onClick={() => onChange({ brain: choice.brain })}>{t('arena.autoBrain.fix')}</button>
        </div>
      )}
      <div className="row" title={t('arena.priorityTitle')}>
        <small className="muted">{t('arena.priority')}</small>
        <DragList className="inline" items={slot.priority?.length ? slot.priority : ['R', '3', '1', '2']}
          label={(x) => <strong>{x}</strong>} onChange={(priority) => onChange({ priority })} />
      </div>
      {stats && (
        <small className="muted">
          {stats.champion} · {t('bar.lv', { n: stats.level })} · {stats.abilities.filter((a) => a.rank > 0).map((a) => `${a.slot} ${n1(a.cooldown ?? 0)}s`).join(' · ')}
          {stats.notes.length > 0 && ` — ${stats.notes.join(' · ')}`}
        </small>
      )}
    </div>
  );
}

/** A ragdoll slot (from "Testar na Arena"): its health, resistances, how it moves and the damage of its hits. */
function DummySlot({ index, slot, onChange, onRemove }) {
  const d = { ...ATTACKING_DUMMY, ...slot.dummy };
  const set = (patch) => onChange({ dummy: { ...d, ...patch } });
  const num = (k) => (e) => set({ [k]: Number(e.target.value) || 0 });
  return (
    <div className="arena-slot" style={{ borderLeftColor: TEAM[slot.team] }}>
      <div className="row">
        <strong className="arena-idx">{index + 1}</strong>
        <strong>{d.name}</strong>
        <button className="icon danger" onClick={onRemove} aria-label={t('arena.removeFighter')}>✕</button>
      </div>
      <div className="row">
        <label className="inline">{t('arena.dummy.health')}<input type="number" min={1} value={d.health} onChange={num('health')} style={{ width: 80 }} /></label>
        <label className="inline">{t('arena.dummy.res')}
          <input type="number" min={0} value={d.armor} onChange={num('armor')} style={{ width: 60 }} aria-label="Armor" />
          <input type="number" min={0} value={d.magicResist} onChange={num('magicResist')} style={{ width: 60 }} aria-label="MR" />
        </label>
        <label className="inline">{t('arena.dummy.behavior')}
          <select value={d.behavior} onChange={(e) => set({ behavior: e.target.value })}>
            {['ataca', 'parado', 'anda', 'foge', 'reage'].map((b) => <option key={b} value={b}>{t(`arena.dummy.b.${b}`)}</option>)}
          </select>
        </label>
        {d.behavior === 'ataca' && (
          <label className="inline" title={t('arena.dummy.adTitle')}>{t('arena.dummy.ad')}
            <input type="number" min={0} value={d.attackDamage} onChange={num('attackDamage')} style={{ width: 60 }} />
          </label>
        )}
        <ItemPicker value={d.items ?? []} max={6} filter={(i) => i.section === 'aprimorado'} onChange={(items) => set({ items })}
          label={d.items?.length ? t('arena.dummy.items', { n: d.items.length }) : t('efx.addItem')} />
      </div>
    </div>
  );
}

const timelines = new Map();
/** The planner's timeline of a saved build (cached by its last save). */
function useTimeline(build) {
  const [tl, setTl] = useState(null);
  const key = build ? `${build.id}|${build.updatedAt}` : null;
  useEffect(() => {
    if (!key) return undefined;
    let live = true;
    if (!timelines.has(key)) {
      const p = api.get(`/api/builds/${build.id}/timeline`);
      p.catch(() => timelines.delete(key));
      timelines.set(key, p);
    }
    timelines.get(key).then((r) => { if (live) setTl(r); }).catch(() => {});
    return () => { live = false; };
  }, [key]);
  return tl;
}

/**
 * What the slot's build has at its minute (level, items, gold spent) and its health over the match, where a click picks
 * the minute (like the planner's chart). An empty minute is the build's end.
 */
function BuildAtMinute({ build, minute, onMinute }) {
  const tl = useTimeline(build);
  if (!tl?.series?.length) return null;
  const last = tl.series.at(-1).minute;
  const at = minute ?? (tl.matchEnd ?? tl.steps.at(-1)?.minute ?? last);
  const p = pointAt(tl.series, at);
  const spent = tl.steps.filter((s) => s.minute <= at + 1e-9).reduce((sum, s) => sum + s.paidCost, 0);
  const ids = (p?.contributions ?? []).filter((c) => !c.rune).map((c) => c.itemId);
  const hp = tl.series.map((s) => s.total['Max Health'] ?? 0);
  const lo = Math.min(...hp);
  const hi = Math.max(...hp);
  const W = 180;
  const H = 30;
  const x = (m) => (m / Math.max(1e-9, last)) * W;
  const y = (v) => H - 2 - ((v - lo) / Math.max(1, hi - lo)) * (H - 4);
  const path = tl.series.map((s, i) => `${i ? 'L' : 'M'}${x(s.minute).toFixed(1)},${y(s.total['Max Health'] ?? 0).toFixed(1)}`).join(' ');
  const pick = (e) => {
    const box = e.currentTarget.getBoundingClientRect();
    const m = Math.max(0, Math.min(last, ((e.clientX - box.left) / box.width) * last));
    onMinute(Math.round(m * 60) / 60);
  };
  return (
    <div className="slot-build-at">
      <span className="slot-summary">
        <small>{t('arena.slotAt', { time: mmss(at), level: p?.level ?? '—', gold: n0(spent) })}</small>
        <span className="slot-items">{ids.map((id, k) => <ItemIcon key={k} id={id} size={20} />)}</span>
      </span>
      <svg className="slot-spark" viewBox={`0 0 ${W} ${H}`} width={W} height={H} onClick={pick} role="img"
        aria-label={t('arena.slotSparkTitle')}>
        <title>{t('arena.slotSparkTitle')}</title>
        <path d={path} fill="none" stroke="var(--stat-hp, #6fd486)" strokeWidth={1.5} />
        <line x1={x(at)} x2={x(at)} y1={0} y2={H} stroke="var(--gold-bright)" strokeWidth={1.5} />
      </svg>
    </div>
  );
}

/** The map: the lane, champions as circles, everything else as simple shapes; hover while paused to identify. */
export function ArenaMap({ result, frame, paused, names, pickPoint, onPick, onSelect, highlight }) {
  // where each champion was a moment ago: the trail of a push or a pull
  const before = frame ? frameAt(result, Math.max(0, frame.t - 0.3)) : null;
  const svgRef = useRef(null);
  const [hover, setHover] = useState(null);
  const [follow, setFollow] = useState(true);
  const [seeAll, setSeeAll] = useState(true);
  if (!result || !frame) {
    return <div className="arena-map empty muted">{t('arena.noRun')}</div>;
  }
  const H = 360;
  const W = 1000;
  const alive = frame.units.filter((u) => u.state !== 'dead');
  const xs = (alive.length ? alive : frame.units).map((u) => u.x);
  const world = result.world;
  const ys = (alive.length ? alive : frame.units).map((u) => u.y);
  const worldSpan = Math.max(2400, Math.max(Math.max(...xs) - Math.min(...xs), (Math.max(...ys) - Math.min(...ys)) * (W / H)) + 1400);
  const span = world ? (follow ? Math.min(world.size, worldSpan) : world.size) : follow ? Math.max(2200, Math.max(...xs) - Math.min(...xs) + 1200) : result.mapLength;
  const cx = follow ? (Math.max(...xs) + Math.min(...xs)) / 2 : result.mapLength / 2;
  const cy = world && follow ? (Math.max(...ys) + Math.min(...ys)) / 2 : world ? world.size / 2 : 0;
  const vw = span;
  const vh = world ? (span * H) / W : Math.max(result.mapWidth + 300, (span * H) / W);
  const view = `${cx - vw / 2} ${-cy - vh / 2} ${vw} ${vh}`;
  const toWorld = (ev) => {
    const pt = svgRef.current.createSVGPoint();
    pt.x = ev.clientX;
    pt.y = ev.clientY;
    const p = pt.matrixTransform(svgRef.current.getScreenCTM().inverse());
    return { x: p.x, y: -p.y };
  };
  const hits = (ev) => {
    const p = toWorld(ev);
    const found = [];
    frame.units.forEach((u, i) => {
      if (Math.hypot(u.x - p.x, u.y - p.y) <= 90 + span / 120) found.push({ unit: u, i });
    });
    frame.shapes.forEach((s) => {
      if (hitShape(s, p.x, p.y, span / 150)) found.push({ shape: s });
    });
    return found;
  };
  const onMove = (ev) => {
    if (!paused) return;
    const found = hits(ev);
    setHover(found.length ? { x: ev.clientX, y: ev.clientY, found } : null);
  };
  // A click (or tap) on a shape or a champion opens the cooldown card of what it is; a champion gives what it is
  // casting, else its last use.
  const onClick = (ev) => {
    if (pickPoint != null) {
      const p = toWorld(ev);
      onPick(p.x, p.y);
      return;
    }
    if (!onSelect) return;
    const found = hits(ev);
    const shape = found.find((h) => h.shape && shapeOwner(h.shape, result)?.key);
    if (shape) {
      const o = shapeOwner(shape.shape, result);
      onSelect(o.fighter, o.key, ev.clientX, ev.clientY);
      return;
    }
    const unit = found.find((h) => h.unit);
    if (unit) {
      const casting = unit.unit.state.match(/^(?:cast|channel):(.+)$/)?.[1];
      const uses = (result.fighters[unit.i].uses ?? []).filter((u) => u.t <= frame.t + 1e-6);
      const key = casting ?? uses.at(-1)?.key ?? Object.keys(unit.unit.cooldowns)[0];
      if (key) onSelect(unit.i, key, ev.clientX, ev.clientY);
    }
  };
  const lit = (s) => {
    if (!highlight) return false;
    const o = shapeOwner(s, result);
    return o != null && o.fighter === highlight.fighter && o.key === highlight.key;
  };
  return (
    <div className="arena-map">
      <div className="row arena-map-tools">
        <label className="check"><input type="checkbox" checked={follow} onChange={(e) => setFollow(e.target.checked)} />{t('arena.follow')}</label>
        {world && <label className="check"><input type="checkbox" checked={seeAll} onChange={(e) => setSeeAll(e.target.checked)} />{t('arena.seeAll')}</label>}
        {pickPoint != null && <small className="warn">{t('arena.pickHint')}</small>}
        {paused && <small className="muted">{t('arena.hoverHint')}</small>}
      </div>
      <svg ref={svgRef} viewBox={view} preserveAspectRatio="xMidYMid meet" width="100%" height={H}
        onMouseMove={onMove} onMouseLeave={() => setHover(null)} onClick={onClick}
        style={{ cursor: pickPoint != null ? 'crosshair' : 'pointer' }}>
        <g transform="scale(1,-1)">
          {world ? <RiftGround world={world} /> : (
            <>
              <rect x={0} y={-result.mapWidth / 2} width={result.mapLength} height={result.mapWidth} className="arena-lane" />
              {[0, 1].map((team) => (
                <circle key={team} cx={team === 0 ? 500 : result.mapLength - 500} cy={0} r={180} fill={TEAM_SOFT[team]} stroke={TEAM[team]} strokeWidth={8} />
              ))}
            </>
          )}
          {(result.terrain ?? []).filter((s) => s.id === 'zona-segura').map((s) => (
            <rect key={s.id} x={s.x - s.length / 2} y={-s.width / 2} width={s.length} height={s.width} fill={TEAM_SOFT[s.team] ?? 'none'}
              stroke={TEAM[s.team]} strokeDasharray="40 30" strokeWidth={10}><title>{s.label}</title></rect>
          ))}
          {frame.shapes.map((s) => <ShapeView key={s.id} s={s} lit={lit(s)} units={frame.units}
            hovered={Boolean(hover?.found?.some((h) => h.shape?.id === s.id))} />)}
          {frame.units.map((u, i) => ((seeAll || !u.invisible) ? <UnitView key={i} u={u} i={i} name={names[i]} team={result.fighters[i]?.team ?? 0} span={span}
            lit={highlight?.fighter === i} before={before?.units?.[i]} /> : null))}
        </g>
      </svg>
      {hover && (
        <div className="arena-tip" style={{ left: hover.x / pageZoom() + 14, top: hover.y / pageZoom() + 14 }}>
          {hover.found.map((h, k) => (h.unit ? (
            <div key={k}>
              <strong>{h.i + 1} · {names[h.i]}</strong> — {n0(h.unit.hp)}/{n0(h.unit.maxHp)}{h.unit.shield > 0 ? ` +${n0(h.unit.shield)} ${t('arena.shield')}` : ''}
              <div><small>{t(`arena.state.${h.unit.state.split(':')[0]}`)}{h.unit.state.includes(':') ? ` ${h.unit.state.split(':')[1]}` : ''}{h.unit.airLeft > 0 ? ` ${sec(h.unit.airLeft)}` : h.unit.stunLeft > 0 ? ` ${sec(h.unit.stunLeft)}` : ''}{h.unit.slow > 0 ? ` · ${t('arena.slowed', { pct: Math.round(h.unit.slow * 100) })}` : ''}{h.unit.resource != null ? ` · ${t('arena.resource')} ${n0(h.unit.resource)}` : ''}</small></div>
              {h.unit.marks.length > 0 && <div><small>{h.unit.marks.join(' · ')}</small></div>}
              <div><small>{Object.entries(h.unit.cooldowns).map(([k2, v]) => `${k2}: ${v > 0 ? `${n1(v)}s` : t('arena.ready')}`).join(' · ')}</small></div>
            </div>
          ) : (
            <div key={k}><small>{h.shape.label}</small></div>
          )))}
        </div>
      )}
    </div>
  );
}

function hitShape(s, x, y, pad) {
  if (s.kind === 'circle') return Math.hypot(s.x - x, s.y - y) <= (s.radius ?? 20) + pad;
  if (s.kind === 'rect' || s.kind === 'line') {
    const len = s.kind === 'line' ? s.length : s.length;
    const cx = s.kind === 'line' ? s.x + Math.cos(s.angle) * len / 2 : s.x;
    const cy = s.kind === 'line' ? s.y + Math.sin(s.angle) * len / 2 : s.y;
    const dx = x - cx;
    const dy = y - cy;
    const along = dx * Math.cos(s.angle) + dy * Math.sin(s.angle);
    const across = -dx * Math.sin(s.angle) + dy * Math.cos(s.angle);
    return Math.abs(along) <= len / 2 + pad && Math.abs(across) <= (s.width ?? 40) / 2 + pad;
  }
  if (s.kind === 'zone') return insideRings(s.rings, x, y);
  if (s.kind === 'cone') {
    const d = Math.hypot(x - s.x, y - s.y);
    let a = Math.atan2(y - s.y, x - s.x) - s.angle;
    a = Math.atan2(Math.sin(a), Math.cos(a));
    return d <= s.radius + pad && Math.abs(a) <= s.arc / 2;
  }
  return false;
}

/** Even-odd test of a point against the outline of a zone (its second ring, when there is one, is a hole). */
function insideRings(rings, x, y) {
  let inside = false;
  (rings ?? []).forEach((ring) => {
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      const [ax, ay] = ring[i];
      const [bx, by] = ring[j];
      if ((ay > y) !== (by > y) && x < ((bx - ax) * (y - ay)) / (by - ay) + ax) inside = !inside;
    }
  });
  return inside;
}

/** Which fighter and cooldown a map shape comes from: "Ruptura Vulcânica (Ornn)" -> Ornn's "1"; null when unknown. */
function shapeOwner(s, result) {
  const m = /^(.*) \(([^)]+)\)(?:,.*)?$/.exec(s.label ?? '');
  const what = (m ? m[1] : s.label ?? '').toLowerCase();
  const champ = m?.[2];
  const fi = result.fighters.findIndex((f) => (!champ || f.champion === champ) && (s.team < 0 || f.team === s.team));
  if (fi < 0 || !champ) return null;
  const f = result.fighters[fi];
  const ab = (f.abilities ?? []).find((a) => a.name && (what.includes(a.name.toLowerCase()) || a.name.toLowerCase().includes(what)));
  if (ab) return { fighter: fi, key: ab.slot };
  if (SLOTS.includes(s.id)) return { fighter: fi, key: s.id };
  if (String(s.id).startsWith('elemental') || what.includes('elemental')) return { fighter: fi, key: 'R' };
  if (what.includes('pilar')) return { fighter: fi, key: '1' };
  const use = (f.uses ?? []).find((u) => what.includes(String(u.name).toLowerCase()));
  return { fighter: fi, key: use?.key ?? null };
}

/** The ground of the full map: river, walls, bushes, pits and fountains. */
export function RiftGround({ world }) {
  const [a, b] = world.river;
  return (
    <>
      <rect x={0} y={0} width={world.size} height={world.size} className="arena-lane" />
      <line x1={a[0]} y1={a[1]} x2={b[0]} y2={b[1]} stroke="rgba(90,150,200,.25)" strokeWidth={world.riverWidth} />
      {world.walls.map((w, i) => <polygon key={i} points={w.map((p) => p.join(',')).join(' ')} fill="#2b3a33" stroke="#1a241f" strokeWidth={10} />)}
      {world.bushes.map((p, i) => <circle key={i} cx={p[0]} cy={p[1]} r={p[2]} fill="rgba(70,140,80,.45)" />)}
      {[world.baronPit, world.dragonPit].map((p, i) => <circle key={i} cx={p[0]} cy={p[1]} r={p[2]} fill="none" stroke="#9a8f7a" strokeDasharray="30 20" strokeWidth={8} />)}
      {world.fountains.map((f, i) => <circle key={i} cx={f[0]} cy={f[1]} r={f[2]} fill={TEAM_SOFT[i]} />)}
    </>
  );
}

function ShapeView({ s, lit, units, hovered }) {
  const color = lit ? '#ffffff' : s.team < 0 ? '#9a8f7a' : TEAM[s.team];
  const fill = s.team < 0 ? 'rgba(154,143,122,.6)' : TEAM_SOFT[s.team];
  const sw = lit ? 18 : 6;
  if (s.hit) return <HitView s={s} color={color} fill={fill} lit={lit} />;
  if (s.role === 'escombro') {
    // a fallen tower: no target any more, its rubble stays on the map
    const r = s.radius ?? 80;
    return (
      <g opacity={0.7}>
        <title>{s.label}</title>
        <polygon points={`${s.x - r},${s.y - r * 0.6} ${s.x - r * 0.2},${s.y + r * 0.7} ${s.x + r * 0.3},${s.y - r * 0.1} ${s.x + r},${s.y + r * 0.5} ${s.x + r * 0.8},${s.y - r * 0.8}`}
          fill="#3a3f45" stroke={TEAM[s.team] ?? '#9a8f7a'} strokeWidth={8} strokeDasharray="18 12" />
      </g>
    );
  }
  if (s.role) {
    const bar = s.role === 'torre' || s.role === 'nexo' || s.role === 'objetivo';
    // the tower's range: on hover / touch, and always while a champion is inside it
    const inside = s.reach > 0 && (units ?? []).some((u) => u.state !== 'dead' && Math.hypot(u.x - s.x, u.y - s.y) <= s.reach);
    return (
      <g>
        {s.reach > 0 && (hovered || inside) && (
          <circle cx={s.x} cy={s.y} r={s.reach} fill={inside ? 'rgba(255,90,70,.08)' : 'none'} stroke={color} strokeOpacity={0.7}
            strokeDasharray="40 24" strokeWidth={10} />
        )}
        <circle cx={s.x} cy={s.y} r={s.radius ?? 20} fill={s.role === 'tropa' ? color : fill} stroke={color} strokeWidth={s.role === 'tropa' ? 0 : 8} />
        {bar && s.maxHp > 0 && <rect x={s.x - 110} y={s.y + (s.radius ?? 20) + 24} width={(220 * s.hp) / s.maxHp} height={24} fill={color} />}
        {s.plates > 0 && Array.from({ length: s.plates }, (_, k) => (
          <rect key={k} x={s.x - 110 + k * 46} y={s.y + (s.radius ?? 20) + 56} width={38} height={14} fill={color} opacity={0.6} />
        ))}
      </g>
    );
  }
  if (s.kind === 'zone') {
    // Zones of one ability differ by outline and fill: the first is solid, the next ones dashed and lighter.
    const d = (s.rings ?? []).map((r) => `M ${r.map((p) => p.join(' ')).join(' L ')} Z`).join(' ');
    return <path d={d} fillRule="evenodd" fill={fill} fillOpacity={s.zone ? 0.45 : 1} stroke={color} strokeWidth={sw}
      strokeDasharray={s.zone ? '24 16' : undefined} />;
  }
  if (s.kind === 'circle') return <circle cx={s.x} cy={s.y} r={s.radius ?? 20} fill={fill} stroke={color} strokeWidth={sw} />;
  if (s.kind === 'rect') {
    const deg = (s.angle * 180) / Math.PI;
    return (
      <rect x={s.x - s.length / 2} y={s.y - s.width / 2} width={s.length} height={s.width} fill={fill} stroke={color} strokeWidth={sw}
        transform={`rotate(${deg} ${s.x} ${s.y})`} />
    );
  }
  if (s.kind === 'line') {
    const deg = (s.angle * 180) / Math.PI;
    return (
      <rect x={s.x} y={s.y - (s.width ?? 60) / 2} width={s.length} height={s.width ?? 60} fill={fill} stroke={color} strokeWidth={4} strokeDasharray="20 14"
        transform={`rotate(${deg} ${s.x} ${s.y})`} />
    );
  }
  if (s.kind === 'cone') {
    const a1 = s.angle - s.arc / 2;
    const a2 = s.angle + s.arc / 2;
    const p1 = [s.x + Math.cos(a1) * s.radius, s.y + Math.sin(a1) * s.radius];
    const p2 = [s.x + Math.cos(a2) * s.radius, s.y + Math.sin(a2) * s.radius];
    return <path d={`M ${s.x} ${s.y} L ${p1[0]} ${p1[1]} A ${s.radius} ${s.radius} 0 0 1 ${p2[0]} ${p2[1]} Z`} fill={fill} stroke={color} strokeWidth={sw} />;
  }
  return null;
}

/**
 * What hits, drawn on the map (Rodada 10): a ranged attack's missile with its tail, always, in the team's color (a
 * critical share glows); a melee attack's short segment from the attacker to its target; a dash's band (fainter when
 * it deals no damage); the area that hits on touch while it is out.
 */
function HitView({ s, color, fill, lit }) {
  const deg = ((s.angle ?? 0) * 180) / Math.PI;
  const glow = s.crit > 0 ? <circle cx={s.x} cy={s.y} r={46} fill="none" stroke="#ffd25a" strokeWidth={12} opacity={0.35 + 0.65 * s.crit} /> : null;
  if (s.hit === 'ataque' && s.kind === 'circle') {
    const tail = s.length ?? 0;
    const tx = s.x - Math.cos(s.angle ?? 0) * tail;
    const ty = s.y - Math.sin(s.angle ?? 0) * tail;
    return (
      <g>
        <title>{s.label}</title>
        <line x1={tx} y1={ty} x2={s.x} y2={s.y} stroke={color} strokeWidth={16} strokeLinecap="round" opacity={0.6} />
        <circle cx={s.x} cy={s.y} r={Math.max(22, s.radius ?? 20)} fill={color} stroke="#0b1621" strokeWidth={4} />
        {glow}
      </g>
    );
  }
  if (s.kind === 'rect') {
    const faint = s.hit === 'deslocamento';
    return (
      <g>
        <title>{s.label}</title>
        <rect x={s.x - s.length / 2} y={s.y - s.width / 2} width={s.length} height={s.width} fill={s.hit === 'ataque' ? color : fill}
          fillOpacity={s.hit === 'ataque' ? 0.55 : faint ? 0.18 : 0.5} stroke={s.crit > 0 ? '#ffd25a' : color} strokeOpacity={faint ? 0.35 : 1}
          strokeWidth={lit ? 18 : s.hit === 'ataque' ? 6 : 8} strokeDasharray={faint ? '22 16' : undefined} transform={`rotate(${deg} ${s.x} ${s.y})`} />
      </g>
    );
  }
  return (
    <g>
      <title>{s.label}</title>
      <circle cx={s.x} cy={s.y} r={s.radius ?? 60} fill={fill} fillOpacity={0.25} stroke={color} strokeWidth={6} strokeDasharray="14 10" />
    </g>
  );
}

const sec = (v) => (v == null ? '' : `${String(Math.round(v * 10) / 10).replace('.', ',')} s`);

/**
 * A champion with its control states readable on it: in the air the circle rises over a shadow with "no ar 0,8 s"
 * counting down; stunned, stars; slowed, an arrow down with the %; pushed or pulled, a trail; Brittle, a cracked ring.
 */
function UnitView({ u, i, name, team, span, lit, before }) {
  const r = 80;
  const dead = u.state === 'dead';
  const pct = u.maxHp > 0 ? Math.max(0, u.hp / u.maxHp) : 0;
  const font = Math.max(60, span / 45);
  const air = !dead && u.airLeft > 0;
  const lift = air ? 70 : 0;
  const cy = u.y + lift;
  const brittle = !dead && (u.marks ?? []).includes('Frágil');
  const moved = !dead && u.state === 'displaced' && before && Math.hypot(before.x - u.x, before.y - u.y) > 20;
  return (
    <g opacity={dead ? 0.3 : u.invisible ? 0.45 : 1}>
      {moved && <line x1={before.x} y1={before.y} x2={u.x} y2={u.y} stroke={TEAM[team]} strokeWidth={r * 1.2} strokeLinecap="round" opacity={0.25} />}
      {air && <ellipse cx={u.x} cy={u.y - 30} rx={r * 0.9} ry={r * 0.35} fill="#000" opacity={0.35} />}
      {lit && <circle cx={u.x} cy={cy} r={r + 34} fill="none" stroke="#ffffff" strokeWidth={10} strokeDasharray="24 14" />}
      {brittle && <circle cx={u.x} cy={cy} r={r + 22} fill="none" stroke="#9fd8ff" strokeWidth={10} strokeDasharray="34 8 14 10 22 6" />}
      <circle cx={u.x} cy={cy} r={r} fill={TEAM[team]} stroke={u.state === 'stun' || air ? '#fff' : '#0b1621'} strokeWidth={u.state === 'stun' || air ? 18 : 8}
        strokeDasharray={u.state === 'stasis' ? '20 12' : undefined} />
      <rect x={u.x - 110} y={cy + r + 30} width={220} height={26} fill="#0b1621" />
      <rect x={u.x - 110} y={cy + r + 30} width={220 * pct} height={26} fill={team === 0 ? '#6fd486' : '#f39a4a'} />
      {u.shield > 0 && <rect x={u.x - 110} y={cy + r + 60} width={Math.min(220, (220 * u.shield) / Math.max(1, u.maxHp))} height={14} fill="#e8e2d4" />}
      <g transform={`translate(${u.x},${cy}) scale(1,-1)`}>
        <text x={0} y={font / 3} textAnchor="middle" fontSize={font} fill="#fff" fontWeight="700">{i + 1}</text>
        <text x={0} y={-r - 70} textAnchor="middle" fontSize={font * 0.7} fill="#e8e2d4">{name}</text>
        {air && <text x={0} y={-r - 70 - font * 0.8} textAnchor="middle" fontSize={font * 0.6} fill="#ffffff">{t('arena.inAir', { s: sec(u.airLeft) })}</text>}
        {!dead && u.stunLeft > 0 && (
          <text x={0} y={-r - 70 - font * (air ? 1.5 : 0.8)} textAnchor="middle" fontSize={font * 0.7} fill="#ffd25a">★ ★ ★</text>
        )}
        {!dead && u.slow > 0 && (
          <text x={r + 30} y={font / 3} fontSize={font * 0.6} fill="#9fd8ff">↓ {Math.round(u.slow * 100)}%</text>
        )}
      </g>
    </g>
  );
}

/**
 * Timeline: one track per champion with its orders (drag to move, click to edit; click an empty place to add one) and,
 * under it, a stripe per ability / active / spell showing when it is on cooldown, from the run, with its short tag
 * (slot or 16 px icon) right before it. Clicking a stripe opens its card. The rows only change with the run (the
 * playback moves one cursor over all of them), so playing does not render them again.
 */
function Timeline({ sc, result, time, names, editing, selected, onTime, onAdd, onEdit, onMove, onPickCooldown }) {
  const width = sc.duration * PX_PER_S;
  const [drag, setDrag] = useState(null);
  const ref = useRef(null);
  // Latest handlers, read at event time, so the memoized rows do not depend on them.
  const on = useRef(null);
  on.current = { onTime, onAdd, onEdit, onMove, onPickCooldown, drag };
  const toTime = (ev) => {
    const box = ref.current.getBoundingClientRect();
    return Math.max(0, Math.min(sc.duration, ((ev.clientX - box.left) / pageZoom() + ref.current.scrollLeft - 110) / PX_PER_S));
  };
  const cds = useMemo(() => cooldownBars(result), [result]);
  // the attacks that landed, by fighter: once per result
  const hitMarks = useMemo(() => {
    const out = [];
    (result?.attackMarks ?? []).forEach((m) => { (out[m[1]] ??= []).push(m); });
    return out;
  }, [result]);
  const selKey = selected ? `${selected.fighter}|${selected.key}` : null;
  useEffect(() => {
    if (selKey) ref.current?.querySelector('.tl-cd.selected')?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, [selKey, selected]);
  const rows = useMemo(() => (
    <>
      <div className="tl-row tl-axis" style={{ width: width + 110 }}>
        <span className="tl-label" />
        <div className="tl-track" style={{ width }} onClick={(ev) => on.current.onTime(toTime(ev))}>
          {Array.from({ length: Math.floor(sc.duration) + 1 }, (_, s) => (
            <span key={s} className={`tl-tick${s % 5 === 0 ? ' major' : ''}`} style={{ left: s * PX_PER_S }}>{s % 5 === 0 ? `${s}s` : ''}</span>
          ))}
        </div>
      </div>
      {sc.fighters.map((f, fi) => (
        <Fragment key={fi}>
          <div className="tl-row" style={{ width: width + 110 }}>
            <span className="tl-label" style={{ color: TEAM[f.team] }}>{fi + 1} · {names[fi]}</span>
            <div className="tl-track orders" style={{ width }}
              onClick={(ev) => { if (ev.target === ev.currentTarget) on.current.onAdd(fi, toTime(ev)); }} title={t('arena.addHint')}>
              {sc.actions.map((a, ai) => (a.fighter !== fi ? null : (
                <button key={ai} className={`tl-order${editing === ai ? ' active' : ''}`} style={{ left: a.time * PX_PER_S }}
                  onMouseDown={(ev) => { ev.stopPropagation(); setDrag(ai); }} onClick={(ev) => { ev.stopPropagation(); on.current.onEdit(ai); }}
                  title={orderText(a, names)}>
                  {shortOrder(a)}
                </button>
              )))}
              {result && (result.skipped ?? []).filter((e) => e.fighter === fi).map((e, k) => (
                <span key={`s${k}`} className="tl-skip" style={{ left: e.t * PX_PER_S }} title={e.text}>⊘</span>
              ))}
              {(hitMarks[fi] ?? []).map((m, k) => (
                <span key={`h${k}`} className={`tl-hit${m[2] > 0 ? ' crit' : ''}`} style={{ left: m[0] * PX_PER_S }} />
              ))}
            </div>
          </div>
          {(cds[fi] ?? []).map((c) => {
            const fighter = result?.fighters?.[fi];
            const full = cooldownName(fighter, c.name);
            const sel = selKey === `${fi}|${c.name}`;
            return (
              <div key={c.name} className={`tl-row tl-cd${sel ? ' selected' : ''}`} style={{ width: width + 110 }}>
                <span className="tl-label sub" title={full}>
                  <span className="tl-cd-name">{full}</span><CooldownTag fighter={fighter} name={c.name} />
                </span>
                <div className="tl-track" style={{ width }} role="button" tabIndex={0} aria-label={t('arena.card.open', { name: full })}
                  onClick={(ev) => on.current.onPickCooldown(fi, c.name, toTime(ev), ev.clientX, ev.clientY)}
                  onKeyDown={(ev) => {
                    if (ev.key === 'Enter') {
                      const box = ev.currentTarget.getBoundingClientRect();
                      on.current.onPickCooldown(fi, c.name, null, box.left + 20, box.bottom);
                    }
                  }}>
                  {c.bars.map(([a, b], k) => (
                    <span key={k} className="tl-cdbar" style={{ left: a * PX_PER_S, width: Math.max(2, (b - a) * PX_PER_S) }} />
                  ))}
                </div>
              </div>
            );
          })}
        </Fragment>
      ))}
    </>
    // toTime reads the ref and the duration only; handlers come from `on`
  ), [sc.fighters, sc.actions, sc.duration, result, cds, hitMarks, names, editing, selKey, width]);
  return (
    <div className="arena-timeline" ref={ref}
      onMouseMove={(ev) => { if (on.current.drag != null) on.current.onMove(on.current.drag, toTime(ev)); }}
      onMouseUp={() => setDrag(null)} onMouseLeave={() => setDrag(null)}>
      {rows}
      <span className="tl-cursor tl-cursor-all" style={{ left: 110 + time * PX_PER_S }} />
    </div>
  );
}

/** Cooldown intervals of each ability / active of each fighter, from the frames. */
function cooldownBars(result) {
  if (!result?.frames?.length) return [];
  const out = result.fighters.map(() => new Map());
  result.frames.forEach((f, k) => {
    f.units.forEach((u, i) => {
      Object.entries(u.cooldowns).forEach(([name, left]) => {
        if (!out[i].has(name)) out[i].set(name, { name, bars: [], open: null });
        const c = out[i].get(name);
        if (left > 0.05 && c.open == null) c.open = f.t;
        if (left <= 0.05 && c.open != null) {
          c.bars.push([c.open, f.t]);
          c.open = null;
        }
        if (k === result.frames.length - 1 && c.open != null) c.bars.push([c.open, f.t + left]);
      });
    });
  });
  return out.map((m) => [...m.values()]);
}

const shortOrder = (a) => (a.type === 'ability' ? a.slot : a.type === 'recast' ? `${a.slot}²` : a.type === 'item' ? '◆'
  : a.type === 'spell' ? '✦' : a.type === 'move' ? '➜' : a.type === 'attack' ? '⚔' : '■');

function orderText(a, names) {
  const what = a.type === 'ability' || a.type === 'recast' ? `${t(`arena.type.${a.type}`)} ${a.slot}` : a.name ? `${t(`arena.type.${a.type}`)}: ${a.name}` : t(`arena.type.${a.type}`);
  const where = a.target != null ? ` → ${a.target + 1} · ${names[a.target]}` : a.x != null ? ` → (${n0(a.x)}, ${n0(a.y)})` : '';
  return `${n1(a.time)}s: ${what}${where}`;
}

function ActionEditor({ action, fighters, names, stats, onChange, onRemove, onClose, onPickPoint, picking }) {
  const me = stats?.[action.fighter];
  const enemies = fighters.map((f, i) => ({ f, i })).filter(({ f }) => f.team !== fighters[action.fighter].team);
  const allies = fighters.map((f, i) => ({ f, i })).filter(({ f, i }) => f.team === fighters[action.fighter].team && i !== action.fighter);
  return (
    <div className="arena-editor" role="dialog" aria-label={t('arena.editOrder')}>
      <div className="row">
        <strong>{action.fighter + 1} · {names[action.fighter]}</strong>
        <label className="inline">{t('arena.at')}
          <input type="number" step={0.1} min={0} value={action.time} onChange={(e) => onChange({ time: Math.max(0, Number(e.target.value)) })} />s
        </label>
        <select value={action.type} onChange={(e) => onChange({ type: e.target.value })} aria-label={t('arena.orderType')}>
          {ORDER_TYPES.map((k) => <option key={k} value={k}>{t(`arena.type.${k}`)}</option>)}
        </select>
        {(action.type === 'ability' || action.type === 'recast') && (
          <select value={action.slot ?? '1'} onChange={(e) => onChange({ slot: e.target.value })} aria-label={t('arena.slot')}>
            {SLOTS.map((s) => {
              const ab = me?.abilities?.find((a) => a.slot === s);
              return <option key={s} value={s}>{s}{ab ? ` · ${ab.name}` : ''}</option>;
            })}
          </select>
        )}
        {(action.type === 'item' || action.type === 'spell') && (
          <select value={action.name ?? ''} onChange={(e) => onChange({ name: e.target.value })} aria-label={t(`arena.type.${action.type}`)}>
            <option value="">—</option>
            {Object.entries(me?.actives ?? {}).filter(([n]) => SPELLS.includes(n) === (action.type === 'spell')).map(([n, cd]) => (
              <option key={n} value={n}>{n} · {n0(cd)}s</option>
            ))}
          </select>
        )}
        {(action.type === 'ability' && action.slot === '2' && me?.champion === "K'Sante") && (
          <label className="inline">{t('arena.charge')}
            <input type="number" step={0.1} min={0.4} max={1} value={action.charge ?? 1} onChange={(e) => onChange({ charge: Number(e.target.value) })} />s
          </label>
        )}
      </div>
      {action.type !== 'stop' && (
        <div className="row">
          <label className="inline">{t('arena.target')}
            <select value={action.target ?? ''} onChange={(e) => onChange({ target: e.target.value === '' ? null : Number(e.target.value), x: null, y: null })}>
              <option value="">{action.x != null ? t('arena.point', { x: n0(action.x), y: n0(action.y) }) : t('arena.current')}</option>
              {enemies.map(({ i }) => <option key={i} value={i}>{i + 1} · {names[i]}</option>)}
              {allies.map(({ i }) => <option key={i} value={i}>{i + 1} · {names[i]} ({t('arena.ally')})</option>)}
            </select>
          </label>
          <button className={picking ? 'active' : ''} onClick={onPickPoint}>{t('arena.pickPoint')}</button>
        </div>
      )}
      <div className="spread">
        <button className="link danger" onClick={onRemove}>{t('arena.removeOrder')}</button>
        <button className="link" onClick={onClose}>{t('close')}</button>
      </div>
    </div>
  );
}

