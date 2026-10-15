import { useEffect, useState } from 'react';
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { mmss, n0, n1 } from '../format.js';
import MinuteInput from './MinuteInput.jsx';

/**
 * The lab of the planner, for the open build (as saved) at the chosen minute: its best combo on the ragdoll, what each
 * item is worth in it, item × ragdoll scenario, and how another build sees it ("visto por"), now and minute by minute.
 */
const pct = (x) => (x == null ? '—' : `${Math.round(x * 100)}%`);
const signed = (v, digits = 0) => (v == null ? '—' : `${v > 0 ? '+' : ''}${digits ? n1(v) : n0(v)}`);

export default function PlannerLab({ buildId, minute, builds, unsaved }) {
  const [presets, setPresets] = useState([]);
  const [dummy, setDummy] = useState(null);
  const [combo, setCombo] = useState(null);
  const [comboId, setComboId] = useState(null);
  const [items, setItems] = useState(null);
  const [recalibrate, setRecalibrate] = useState(false);
  const [other, setOther] = useState(null);
  const [otherMinute, setOtherMinute] = useState(null);
  const [ally, setAlly] = useState(null);
  const [reverse, setReverse] = useState(false);
  const [seen, setSeen] = useState(null);
  const [scenarios, setScenarios] = useState(null);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    api.get('/api/arena/dummies').then((d) => {
      const all = [...d.presets, ...d.saved];
      setPresets(all);
      setDummy((cur) => cur ?? all[0] ?? null);
    }).catch(() => {});
  }, []);
  useEffect(() => {
    setCombo(null);
    setItems(null);
    setScenarios(null);
    setSeen(null);
  }, [buildId, minute]);

  const base = () => ({ buildId, minute, dummy, comboId });
  const run = async (what, fn) => {
    setBusy(what);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(null);
    }
  };
  const loadCombo = (id = comboId) => run('combo', async () => {
    const r = await api.post('/api/lab/combo', { ...base(), comboId: id });
    setCombo(r);
    setComboId(r.combo?.id ?? null);
  });
  const loadItems = () => run('items', async () => setItems(await api.post('/api/lab/items', {
    ...base(), recalibrate, otherBuildId: other, otherMinute,
  })));
  const loadScenarios = () => run('scenarios', async () => setScenarios(await api.post('/api/lab/scenarios', { ...base(), dummies: presets })));
  const loadSeen = () => run('seen', async () => setSeen(await api.post('/api/lab/seen-by', {
    buildId, minute, otherBuildId: other, otherMinute, allyWearBuildId: ally, reverse,
  })));

  if (!buildId) return <p className="muted">{t('plab.saveFirst')}</p>;
  const m = combo?.report ? Object.values(combo.report.byBehavior) : [];

  return (
    <div className="planner-lab">
      <p className="muted" style={{ marginTop: 0 }}>{t('plab.help', { time: mmss(minute) })}{unsaved ? ` ${t('plab.unsaved')}` : ''}</p>
      <div className="row">
        <label className="inline">{t('plab.dummy')}
          <select value={presets.indexOf(dummy)} onChange={(e) => setDummy(presets[Number(e.target.value)])}>
            {presets.map((d, i) => <option key={i} value={i}>{d.name}</option>)}
          </select>
        </label>
        <button className="primary" onClick={() => loadCombo()} disabled={busy != null}>{t('plab.combo')}</button>
        {combo?.combos?.length > 0 && (
          <select value={comboId ?? ''} onChange={(e) => { setComboId(Number(e.target.value)); loadCombo(Number(e.target.value)); }}
            aria-label={t('plab.pickCombo')}>
            {combo.combos.map((c) => <option key={c.id} value={c.id}>{c.name} ({n0(c.score ?? 0)})</option>)}
          </select>
        )}
        {busy && <small className="muted">{t('plab.busy')}</small>}
      </div>
      {combo?.generated && <p className="muted small">{t('plab.generated')}</p>}
      {m.length > 0 && (
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th className="l">{t('lab.behaviorCol')}</th><th>{t('lab.m.damage')}</th><th>{t('lab.m.control')}</th>
              <th>{t('lab.m.marks')}</th><th>{t('lab.m.duration')}</th><th>{t('lab.scoreCol')}</th></tr></thead>
            <tbody>
              {m.map((x) => (
                <tr key={x.behavior}><td className="l">{t(`lab.behavior.${x.behavior}`)}</td><td>{n0(x.damage)}</td><td>{n1(x.control)}s</td>
                  <td>{n0(x.marksConsumed)}</td><td>{n1(x.duration)}s</td><td>{n0(x.score)}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h4>{t('plab.items')}</h4>
      <div className="row">
        <label className="check"><input type="checkbox" checked={recalibrate} onChange={(e) => setRecalibrate(e.target.checked)} />{t('plab.recalibrate')}</label>
        <label className="inline" title={t('plab.otherTitle')}>{t('plab.other')}
          <select value={other ?? ''} onChange={(e) => setOther(e.target.value ? Number(e.target.value) : null)}>
            <option value="">{t('plab.none')}</option>
            {builds.filter((b) => b.id !== buildId && b.unitCode !== 'RAGDOLL').map((b) => <option key={b.id} value={b.id}>{b.unitCode} · {b.name}</option>)}
          </select>
        </label>
        <label className="inline">{t('plab.otherMinute')}<MinuteInput value={otherMinute} onChange={setOtherMinute} placeholder={mmss(minute)} style={{ width: 70 }} /></label>
        <button onClick={loadItems} disabled={busy != null}>{t('plab.itemsRun')}</button>
      </div>
      {items && (
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th className="l">{t('plab.item')}</th><th>{t('plab.dDamage')}</th><th>{t('plab.dControl')}</th><th>{t('plab.dDuration')}</th>
              <th>{t('plab.dScore')}</th>{recalibrate && <th>{t('plab.dRecal')}</th>}{items.received != null && <th>{t('plab.dReceived')}</th>}</tr></thead>
            <tbody>
              {items.items.map((r) => (
                <tr key={r.itemId}>
                  <td className="l">{r.item}</td><td>{signed(r.damage)}</td><td>{signed(r.control, 1)}s</td><td>{signed(r.duration, 1)}s</td>
                  <td>{signed(r.score)}</td>{recalibrate && <td>{signed(r.scoreRecalibrated)}</td>}
                  {items.received != null && <td title={t('plab.dReceivedTitle')}>{signed(r.received)}</td>}
                </tr>
              ))}
            </tbody>
          </table>
          <p className="muted small">{t('plab.itemsHelp', { damage: n0(items.with.damage), score: n0(items.score) })}
            {items.received != null && ` ${t('plab.receivedHelp', { v: n0(items.received) })}`}</p>
        </div>
      )}

      <h4>{t('plab.scenarios')}</h4>
      <button onClick={loadScenarios} disabled={busy != null}>{t('plab.scenariosRun')}</button>
      {scenarios && (
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th className="l">{t('plab.item')}</th>{scenarios.scenarios.map((s) => <th key={s.name}>{s.name}</th>)}</tr></thead>
            <tbody>
              <tr className="muted"><td className="l">{t('plab.comboDamage')}</td>{scenarios.scenarios.map((s) => <td key={s.name}>{n0(s.damage)}</td>)}</tr>
              {Object.entries(scenarios.items).map(([item, cols]) => (
                <tr key={item}><td className="l">{item}</td>{scenarios.scenarios.map((s) => <td key={s.name}>{signed(cols[s.name])}</td>)}</tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h4>{t('plab.seenBy')}</h4>
      <div className="row">
        <label className="inline">{t('plab.seenByBuild')}
          <select value={other ?? ''} onChange={(e) => setOther(e.target.value ? Number(e.target.value) : null)}>
            <option value="">—</option>
            {builds.filter((b) => b.id !== buildId && b.unitCode !== 'RAGDOLL').map((b) => <option key={b.id} value={b.id}>{b.unitCode} · {b.name}</option>)}
          </select>
        </label>
        <label className="inline" title={t('plab.allyTitle')}>{t('plab.ally')}
          <select value={ally ?? ''} onChange={(e) => setAlly(e.target.value ? Number(e.target.value) : null)}>
            <option value="">{t('plab.none')}</option>
            {builds.map((b) => <option key={b.id} value={b.id}>{b.unitCode} · {b.name}</option>)}
          </select>
        </label>
        <label className="check"><input type="checkbox" checked={reverse} onChange={(e) => setReverse(e.target.checked)} />{t('plab.reverse')}</label>
        <button onClick={loadSeen} disabled={busy != null || other == null}>{t('plab.seenByRun')}</button>
      </div>
      {seen && <SeenByView seen={seen} />}
      {error && <p className="error">{error}</p>}
    </div>
  );
}

function SeenByView({ seen }) {
  const s = seen.now;
  const rows = [
    [t('plab.s.health'), n0(s.health)],
    [t('plab.s.armor'), `${n0(s.armor)} → ${n0(s.effectiveArmor)}${s.armorShred ? ` (−${pct(s.armorShred)})` : ''}`],
    [t('plab.s.mr'), `${n0(s.magicResist)} → ${n0(s.effectiveMagicResist)}${s.magicShred ? ` (−${pct(s.magicShred)})` : ''}`],
    [t('plab.s.through'), `${pct(s.physicalThrough)} · ${pct(s.magicThrough)}`],
    [t('plab.s.attackCut'), pct(s.attackReduction)],
    [t('plab.s.shields'), `${n0(s.shields)} → ${n0(s.shieldsAfterCut)}${s.shieldCut ? ` (−${pct(s.shieldCut)})` : ''}`],
    [t('plab.s.healing'), pct(s.healingKept)],
    [t('plab.s.ehpPhys'), n0(s.effectiveHpPhysical)],
    [t('plab.s.ehpAttacks'), n0(s.effectiveHpAttacks)],
    [t('plab.s.ehpMagic'), n0(s.effectiveHpMagic)],
    [t('plab.s.ehpMixed', { p: pct(s.physicalShare) }), n0(s.effectiveHpMixed)],
  ];
  const data = (seen.series ?? []).map((x) => ({ minute: x.minute, phys: Math.round(x.effectiveHpPhysical), magic: Math.round(x.effectiveHpMagic), mixed: Math.round(x.effectiveHpMixed) }));
  return (
    <div className="lab-metrics">
      <dl>{rows.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl>
      {data.length > 1 && (
        <ResponsiveContainer width="100%" height={220}>
          <LineChart data={data} margin={{ top: 8, right: 12, bottom: 4, left: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--grid, #2a3240)" />
            <XAxis dataKey="minute" tickFormatter={(v) => `${v}'`} />
            <YAxis width={56} />
            <Tooltip formatter={(v) => n0(v)} labelFormatter={(v) => `${v} min`} />
            <Legend />
            <Line dataKey="phys" name={t('plab.s.ehpPhys')} stroke="#f0a35e" dot={false} />
            <Line dataKey="magic" name={t('plab.s.ehpMagic')} stroke="#4fa8ff" dot={false} />
            <Line dataKey="mixed" name={t('plab.s.ehpMixedShort')} stroke="#b48cf0" dot={false} />
          </LineChart>
        </ResponsiveContainer>
      )}
    </div>
  );
}
