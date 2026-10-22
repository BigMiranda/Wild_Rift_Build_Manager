import { useEffect, useMemo, useState } from 'react';
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { mmss, n0, n1 } from '../format.js';
import EffectPicker, { applied } from './EffectPicker.jsx';
import ItemIcon from './ItemIcon.jsx';
import MinuteInput from './MinuteInput.jsx';

/**
 * The planner's two panels for the open build (as saved) at the chosen minute: test it in a saved arena scenario or a
 * library combo (compact result, the items that weighed most, a link to the arena), and see its stats under chosen
 * item and champion effects, each at its value. The arena keeps the full lab (search, ragdolls, the whole report).
 */
const pct = (x) => (x == null ? '—' : `${Math.round(x * 100)}%`);
const signed = (v, digits = 0) => (v == null ? '—' : `${v > 0 ? '+' : ''}${digits ? n1(v) : n0(v)}`);

/** Opens the arena on a scenario or on the lab, with this build already in place (the arena reads its saved state). */
function openArena(tab, state) {
  try {
    if (tab === 'scenario') {
      localStorage.setItem('ornn-planner-arena', JSON.stringify(state));
    } else {
      const lab = JSON.parse(localStorage.getItem('ornn-planner-lab') || '{}');
      localStorage.setItem('ornn-planner-lab', JSON.stringify({ ...lab, ...state }));
    }
    localStorage.setItem('ornn-planner-arena-tab', tab);
  } catch {
    /* storage unavailable: the arena opens as it was */
  }
  window.dispatchEvent(new CustomEvent('ornn-open-tab', { detail: 'arena' }));
}

/** A help sentence on top and a "how it works" link that opens a short explanation. */
function PanelHead({ title, help, how }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <h4>{title}</h4>
      <p className="muted small" style={{ marginTop: 0 }}>
        {help} <button className="link" onClick={() => setOpen((o) => !o)} aria-expanded={open}>{t('ptest.how')}</button>
      </p>
      {open && <p className="notice small">{how}</p>}
    </>
  );
}

export default function BuildTestPanels({ buildId, minute }) {
  if (!buildId) return <p className="muted">{t('ptest.saveFirst')}</p>;
  return (
    <div className="planner-lab">
      <TestPanel buildId={buildId} minute={minute} />
      <SeenByPanel buildId={buildId} minute={minute} />
    </div>
  );
}

// ------------------------------------------------------------------ 5.1 test this build in a scenario or combo

function TestPanel({ buildId, minute }) {
  const [options, setOptions] = useState(null);
  const [pick, setPick] = useState('');
  const [at, setAt] = useState(null);
  const [dummies, setDummies] = useState([]);
  const [dummy, setDummy] = useState(0);
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    setOptions(null);
    setResult(null);
    api.get(`/api/lab/test-options?buildId=${buildId}`).then((o) => {
      setOptions(o);
      const s = o.scenarios[0];
      setPick(s ? `s:${s.id}:${s.slots[0].index}` : o.combos[0] ? `c:${o.combos[0].id}` : '');
    }).catch((e) => setError(e.message));
  }, [buildId]);
  useEffect(() => {
    api.get('/api/arena/dummies').then((d) => setDummies([...d.presets, ...d.saved])).catch(() => {});
  }, []);
  useEffect(() => setResult(null), [minute, pick]);

  const [kind, id, slot] = pick.split(':');
  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      const body = { buildId, minute: at ?? minute };
      if (kind === 's') Object.assign(body, { scenarioId: Number(id), slot: Number(slot) });
      else Object.assign(body, { comboId: Number(id), dummy: dummies[dummy] ?? null });
      setResult(await api.post('/api/lab/test', body));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };
  const open = () => {
    if (result.kind === 'scenario') {
      openArena('scenario', { ...result.scenario, id: null, name: `${result.scenario.name} (${t('ptest.copy')})` });
    } else {
      openArena('lab', { buildId, minute: at ?? minute, combo: result.combo, dummy: result.dummy });
    }
  };
  const outcome = (w) => (w < 0 ? t('report.drawShort') : t('report.winShort', { team: t(`report.team${w}`) }));

  return (
    <section className="panel-sub">
      <PanelHead title={t('ptest.title')} help={t('ptest.help')} how={t('ptest.how.test')} />
      {options && !options.scenarios.length && !options.combos.length && <p className="muted small">{t('ptest.nothing', { champion: options.champion })}</p>}
      <div className="row">
        <select value={pick} onChange={(e) => setPick(e.target.value)} aria-label={t('ptest.pick')} disabled={!options}>
          {options?.scenarios.length > 0 && (
            <optgroup label={t('ptest.scenarios')}>
              {options.scenarios.flatMap((s) => s.slots.map((sl) => (
                <option key={`${s.id}:${sl.index}`} value={`s:${s.id}:${sl.index}`}>
                  {s.name}{s.slots.length > 1 ? ` · ${t('ptest.slot', { n: sl.index + 1, side: t(`report.team${sl.team}`) })}` : ''}
                </option>
              )))}
            </optgroup>
          )}
          {options?.combos.length > 0 && (
            <optgroup label={t('ptest.combos')}>
              {options.combos.map((c) => <option key={c.id} value={`c:${c.id}`}>{c.name}{c.score != null ? ` (${n0(c.score)})` : ''}</option>)}
            </optgroup>
          )}
        </select>
        <label className="inline">{t('ptest.minute')}<MinuteInput value={at} onChange={setAt} placeholder={mmss(minute)} style={{ width: 70 }} /></label>
        {kind === 'c' && (
          <label className="inline">{t('ptest.dummy')}
            <select value={dummy} onChange={(e) => setDummy(Number(e.target.value))}>
              {dummies.map((d, i) => <option key={i} value={i}>{d.name}</option>)}
            </select>
          </label>
        )}
        <button className="primary" onClick={run} disabled={busy || !pick}>{busy ? t('ptest.busy') : t('ptest.run')}</button>
      </div>
      {error && <p className="error">{error}</p>}
      {result && (
        <div className="test-result">
          <p>
            {result.kind === 'scenario'
              ? <strong className={result.winner === result.team ? 'good' : result.winner < 0 ? '' : 'warn'}>
                {result.winner === result.team ? t('ptest.won', { s: n1(result.duration) })
                  : result.winner < 0 ? t('ptest.draw', { s: n1(result.duration) }) : t('ptest.lost', { s: n1(result.duration) })}
              </strong>
              : <strong>{t('ptest.comboDamage', { v: n0(result.dealt), s: n1(result.duration) })}</strong>}
            {' · '}{t('ptest.dealtTaken', { dealt: n0(result.dealt), taken: n0(result.taken) })}
          </p>
          {result.items.length > 0 && (
            <>
              <small className="muted">{t('ptest.weighed')}</small>
              <ul className="test-items">
                {result.items.map((w) => (
                  <li key={w.itemId}>
                    <span title={w.item}><ItemIcon id={w.itemId} size={22} /></span> {w.item}:
                    {w.winnerWithout != null && w.winnerWithout !== result.winner && <span className="warn"> {t('ptest.without', { out: outcome(w.winnerWithout) })} ·</span>}
                    {' '}{t('ptest.dDealt', { v: signed(w.dealt) })}
                    {result.kind === 'scenario' && <> · {t('ptest.dSide', { v: signed(w.side) })} · {t('ptest.dAlive', { v: signed(w.alive, 1) })}</>}
                  </li>
                ))}
              </ul>
            </>
          )}
          <button className="link" onClick={open}>{t('ptest.open')}</button>
        </div>
      )}
    </section>
  );
}

// ------------------------------------------------------------------ 5.2 seen under chosen effects

function SeenByPanel({ buildId, minute }) {
  const [chosen, setChosen] = useState([]);
  const [seen, setSeen] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const fx = useMemo(() => applied(chosen), [chosen]);
  const key = JSON.stringify(fx);

  useEffect(() => {
    let live = true;
    const id = setTimeout(async () => {
      setBusy(true);
      setError(null);
      try {
        const r = await api.post('/api/lab/seen-by', { buildId, minute, effects: fx });
        if (live) setSeen(r);
      } catch (e) {
        if (live) setError(e.message);
      } finally {
        if (live) setBusy(false);
      }
    }, 300);
    return () => { live = false; clearTimeout(id); };
  }, [buildId, minute, key]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <section className="panel-sub">
      <PanelHead title={t('pseen.title')} help={t('pseen.help')} how={t('pseen.how')} />
      <EffectPicker chosen={chosen} onChange={setChosen} />
      {busy && <small className="muted">{t('ptest.busy')}</small>}
      {error && <p className="error">{error}</p>}
      {seen && <SeenByView seen={seen} />}
    </section>
  );
}

function SeenByView({ seen }) {
  const s = seen.now;
  const why = (list) => (list?.length ? ` (${list.join(', ')})` : '');
  const rows = [
    [t('plab.s.health'), n0(s.health)],
    [t('plab.s.armor'), `${n0(s.armor)} → ${n0(s.effectiveArmor)}${why(s.armorReasons)}`],
    [t('plab.s.mr'), `${n0(s.magicResist)} → ${n0(s.effectiveMagicResist)}${why(s.magicReasons)}`],
    [t('pseen.through'), t('pseen.throughValue', { phys: pct(s.physicalThrough), magic: pct(s.magicThrough) })],
    s.attackReduction > 0 && [t('plab.s.attackCut'), pct(s.attackReduction)],
    s.shields > 0.5 && [t('plab.s.shields'), `${n0(s.shields)} → ${n0(s.shieldsAfterCut)}${s.shieldCutBy ? ` (${s.shieldCutBy} −${pct(s.shieldCut)})` : ''}`],
    s.healingKept < 1 && [t('plab.s.healing'), `100% → ${pct(s.healingKept)}${s.healCutBy ? ` (${s.healCutBy})` : ''}`],
    [t('plab.s.ehpPhys'), `${n0(s.health)} → ${n0(s.effectiveHpPhysical)}`],
    s.attackReduction > 0 && [t('plab.s.ehpAttacks'), `${n0(s.health)} → ${n0(s.effectiveHpAttacks)}`],
    [t('plab.s.ehpMagic'), `${n0(s.health)} → ${n0(s.effectiveHpMagic)}`],
  ].filter(Boolean);
  const data = (seen.series ?? []).map((x) => ({ minute: x.minute, phys: Math.round(x.effectiveHpPhysical), magic: Math.round(x.effectiveHpMagic) }));
  return (
    <div className="lab-metrics">
      <p className="small muted">{t('pseen.who')} {t('pseen.startNote')}</p>
      <dl>{rows.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl>
      {data.length > 1 && (
        <ResponsiveContainer width="100%" height={160}>
          <LineChart data={data} margin={{ top: 8, right: 12, bottom: 4, left: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--grid, #2a3240)" />
            <XAxis dataKey="minute" tickFormatter={(v) => `${v}'`} />
            <YAxis width={52} tickFormatter={n0} />
            <Tooltip formatter={(v) => n0(v)} labelFormatter={(v) => `${v} min`}
              contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border-strong)' }} />
            <Legend />
            <Line dataKey="phys" name={t('plab.s.ehpPhys')} stroke="#f0a35e" dot={false} isAnimationActive={false} />
            <Line dataKey="magic" name={t('plab.s.ehpMagic')} stroke="#4fa8ff" dot={false} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      )}
    </div>
  );
}
