import { useEffect, useMemo, useState } from 'react';
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { n0 } from '../format.js';
import EffectPicker, { applied } from './EffectPicker.jsx';
import SeenByReport, { MetricControls } from './SeenByReport.jsx';

/**
 * The planner's panel for the open build (as saved) at the chosen minute: its stats under chosen item and champion
 * effects, each at its value. Testing it in a fight is the "Testar na Arena" button (TestInArena), which opens the
 * arena itself.
 */
const pct = (x) => (x == null ? '—' : `${Math.round(x * 100)}%`);

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

export default function BuildTestPanels({ buildId, minute, items }) {
  const idOf = useMemo(() => new Map((items ?? []).map((i) => [i.name, i.id])), [items]);
  if (!buildId) return <p className="muted">{t('ptest.saveFirst')}</p>;
  return (
    <div className="planner-lab">
      <SeenByPanel buildId={buildId} minute={minute} idOf={idOf} />
    </div>
  );
}

// ------------------------------------------------------------------ 5.2 seen under chosen effects

function SeenByPanel({ buildId, minute, idOf }) {
  const [chosen, setChosen] = useState([]);
  const [metric, setMetric] = useState('mixed');
  const [share, setShare] = useState(50);
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
        const r = await api.post('/api/lab/seen-by', { buildId, minute, effects: fx, physicalShare: share / 100, items: true });
        if (live) setSeen(r);
      } catch (e) {
        if (live) setError(e.message);
      } finally {
        if (live) setBusy(false);
      }
    }, 300);
    return () => { live = false; clearTimeout(id); };
  }, [buildId, minute, key, share]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <section className="panel-sub">
      <PanelHead title={t('pseen.title')} help={t('pseen.help')} how={t('pseen.how')} />
      <EffectPicker chosen={chosen} onChange={setChosen} />
      <MetricControls metric={metric} onMetric={setMetric} share={share} onShare={setShare} />
      {busy && <small className="muted">{t('ptest.busy')}</small>}
      {error && <p className="error">{error}</p>}
      {seen && <SeenByReport result={seen} metric={metric} idOf={idOf} />}
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
    [t('plab.s.ehpMixed', { p: pct(s.physicalShare) }), `${n0(s.health)} → ${n0(s.effectiveHpMixed)}`],
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
