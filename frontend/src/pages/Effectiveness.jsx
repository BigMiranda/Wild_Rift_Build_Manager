import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { statLabel, t } from '../i18n.js';
import { n0, n1 } from '../format.js';
import { BOT_STATS, ROLES, effectMeta, newBot, newTest, testKey } from '../effectiveness.js';
import ItemIcon from '../components/ItemIcon.jsx';
import MinuteInput from '../components/MinuteInput.jsx';
import { ReferencePicker, ttd } from '../components/EffectivenessReport.jsx';
import { openInArena } from '../components/TestInArena.jsx';

/**
 * "Efetividade": the test scenarios (presets and the user's: create, duplicate, edit, save, delete; presets only
 * duplicate), the ragdoll editor, "Testar agora" with a build, the basic and complete reports' settings, and the help.
 */
export default function Effectiveness({ items }) {
  const [meta, setMeta] = useState(null);
  const [user, setUser] = useState([]);
  const [sel, setSel] = useState(null);
  const [draft, setDraft] = useState(null);
  const [error, setError] = useState(null);
  const [section, setSection] = useState('tests');

  const reloadUser = () => api.get('/api/effect/tests').then(setUser).catch((e) => setError(e.message));
  useEffect(() => {
    effectMeta().then(setMeta).catch((e) => setError(e.message));
    reloadUser();
  }, []);

  const all = useMemo(() => [...(meta?.presets ?? []), ...user], [meta, user]);
  const pick = (test) => {
    setSel(testKey(test));
    setDraft(JSON.parse(JSON.stringify(test)));
  };
  const duplicate = () => {
    const c = JSON.parse(JSON.stringify(draft));
    setSel(null);
    setDraft({ ...c, id: null, key: null, preset: false, name: t('efx.copyOf', { name: c.name }) });
  };
  const save = async () => {
    setError(null);
    try {
      const saved = draft.id ? await api.put(`/api/effect/tests/${draft.id}`, draft) : await api.post('/api/effect/tests', draft);
      await reloadUser();
      pick(saved);
    } catch (e) {
      setError(e.message);
    }
  };
  const remove = async () => {
    if (!draft?.id || !window.confirm(t('efx.deleteConfirm', { name: draft.name }))) return;
    await api.del(`/api/effect/tests/${draft.id}`);
    setSel(null);
    setDraft(null);
    reloadUser();
  };

  return (
    <div className="main effectiveness">
      <section className="panel">
        <h2>{t('efx.title')}</h2>
        <p className="muted small" style={{ marginTop: 0 }}>{t('efx.intro')}</p>
        <div className="chips" role="tablist">
          {['tests', 'reports', 'help'].map((k) => (
            <button key={k} role="tab" aria-selected={section === k} className={section === k ? 'active' : ''} onClick={() => setSection(k)}>{t(`efx.section.${k}`)}</button>
          ))}
        </div>
        {error && <p className="error">{error}</p>}
      </section>

      {section === 'tests' && meta && (
        <div className="eff-layout">
          <section className="panel eff-list">
            <h3>{t('efx.tests')}</h3>
            <button onClick={() => { setSel(null); setDraft(newTest()); }}>{t('efx.new')}</button>
            <h5>{t('efx.presets')}</h5>
            <ul>
              {meta.presets.map((p) => (
                <li key={p.key}><button className={`link${sel === p.key ? ' active' : ''}`} onClick={() => pick(p)}>{p.name}</button> <small className="muted">{t(`efx.group.${p.group}`)}</small></li>
              ))}
            </ul>
            <h5>{t('efx.mine')}</h5>
            {user.length === 0 && <small className="muted">{t('efx.noneMine')}</small>}
            <ul>
              {user.map((u) => (
                <li key={u.id}><button className={`link${sel === `u:${u.id}` ? ' active' : ''}`} onClick={() => pick(u)}>{u.name}</button></li>
              ))}
            </ul>
          </section>
          <div className="eff-main">
            {draft ? (
              <>
                <TestEditor test={draft} onChange={setDraft} items={items} meta={meta} readOnly={!!draft.key} />
                <div className="row">
                  {draft.key ? <button onClick={duplicate}>{t('efx.duplicate')}</button> : (
                    <>
                      <button className="primary" onClick={save}>{t('efx.save')}</button>
                      {draft.id && <button onClick={duplicate}>{t('efx.duplicate')}</button>}
                      {draft.id && <button className="danger" onClick={remove}>{t('efx.delete')}</button>}
                    </>
                  )}
                </div>
                <TryNow test={draft} />
              </>
            ) : <section className="panel"><p className="muted">{t('efx.pickTest')}</p></section>}
          </div>
        </div>
      )}
      {section === 'reports' && meta && <ReportsEditor all={all} meta={meta} />}
      {section === 'help' && <Help />}
    </div>
  );
}

/** The scenario's own fields and its ragdolls (1 to 5 attackers, 0 to 4 allies). */
function TestEditor({ test, onChange, items, meta, readOnly }) {
  const set = (patch) => onChange({ ...test, ...patch });
  const setBot = (side, i, bot) => set({ [side]: test[side].map((b, k) => (k === i ? bot : b)) });
  const add = (side) => set({ [side]: [...test[side], newBot(side === 'allies' ? 'suporte' : 'lutador')] });
  const del = (side, i) => set({ [side]: test[side].filter((_, k) => k !== i) });
  return (
    <section className="panel">
      <h3>{readOnly ? t('efx.presetTitle', { name: test.name }) : t('efx.editTitle')}</h3>
      {readOnly && <p className="muted small">{t('efx.presetHelp')}</p>}
      <fieldset disabled={readOnly} className="eff-fields">
        <div className="row">
          <label className="inline">{t('efx.name')}<input value={test.name ?? ''} onChange={(e) => set({ name: e.target.value })} /></label>
          <label className="inline">{t('efx.duration')}<input type="number" min={5} max={180} value={test.duration} onChange={(e) => set({ duration: Number(e.target.value) })} style={{ width: 70 }} /> s</label>
          <label className="inline">{t('efx.groupLabel')}
            <select value={test.group} onChange={(e) => set({ group: e.target.value })}>
              <option value="1x1">{t('efx.group.1x1')}</option>
              <option value="equipe">{t('efx.group.equipe')}</option>
            </select>
          </label>
        </div>
        <label className="block">{t('efx.note')}<input value={test.note ?? ''} onChange={(e) => set({ note: e.target.value })} style={{ width: '100%' }} /></label>
        {['attackers', 'allies'].map((side) => (
          <div key={side}>
            <h4>{t(`efx.${side}`)} <small className="muted">({test[side].length}/{side === 'attackers' ? meta.maxAttackers : meta.maxAllies})</small></h4>
            {test[side].map((b, i) => (
              <BotEditor key={i} bot={b} onChange={(x) => setBot(side, i, x)} items={items} meta={meta}
                onRemove={side === 'attackers' && test.attackers.length <= 1 ? null : () => del(side, i)} />
            ))}
            {test[side].length < (side === 'attackers' ? meta.maxAttackers : meta.maxAllies) && (
              <button onClick={() => add(side)}>{t(`efx.add.${side}`)}</button>
            )}
          </div>
        ))}
      </fieldset>
    </section>
  );
}

/** A test ragdoll: stats, shop items as icon chips, its damage pattern (its role's, or its own pulses) and its role. */
function BotEditor({ bot, onChange, items, meta, onRemove }) {
  const set = (patch) => onChange({ ...bot, ...patch });
  const num = (k) => (e) => set({ [k]: e.target.value === '' ? 0 : Number(e.target.value) });
  const role = meta.roles[bot.role];
  const pulses = bot.pulses ?? role?.pulses ?? [];
  const setPulse = (i, patch) => set({ pulses: pulses.map((p, k) => (k === i ? { ...p, ...patch } : p)) });
  const shop = (items ?? []).filter((i) => i.section === 'aprimorado' || i.section === 'evolucao');
  const byId = new Map((items ?? []).map((i) => [i.id, i]));
  return (
    <div className="eff-bot">
      <div className="row">
        <input value={bot.name ?? ''} placeholder={t(`efx.role.${bot.role}`)} onChange={(e) => set({ name: e.target.value })} aria-label={t('efx.name')} />
        <label className="inline">{t('efx.roleLabel')}
          <select value={bot.role} onChange={(e) => set({ role: e.target.value })}>
            {ROLES.map((r) => <option key={r} value={r}>{t(`efx.role.${r}`)}</option>)}
          </select>
        </label>
        {onRemove && <button className="icon" onClick={onRemove} aria-label={t('efx.removeBot')}>✕</button>}
      </div>
      <div className="eff-stats">
        <label>{statLabel('Max Health')}<input type="number" value={bot.health} onChange={num('health')} /></label>
        <label>{statLabel('Armor')}<input type="number" value={bot.armor} onChange={num('armor')} /></label>
        <label>{statLabel('Magic Resistance')}<input type="number" value={bot.magicResist} onChange={num('magicResist')} /></label>
        <label>{statLabel('Attack Damage')}<input type="number" value={bot.attackDamage} onChange={num('attackDamage')} /></label>
        <label title={t('efx.asHelp')}>{t('efx.attackSpeed')}<input type="number" step="0.05" value={bot.attackSpeed} onChange={num('attackSpeed')} /></label>
        <label title={t('efx.rangeHelp', { n: n0(role?.attackRange ?? 0) })}>{t('efx.range')}
          <input type="number" value={bot.attackRange ?? ''} placeholder={n0(role?.attackRange ?? 0)}
            onChange={(e) => set({ attackRange: e.target.value === '' ? null : Number(e.target.value) })} />
        </label>
        {BOT_STATS.map((s) => (
          <label key={s}>{statLabel(s)}
            <input type="number" value={bot.stats?.[s] ?? ''} placeholder="0"
              onChange={(e) => {
                const stats = { ...(bot.stats ?? {}) };
                if (e.target.value === '' || Number(e.target.value) === 0) delete stats[s]; else stats[s] = Number(e.target.value);
                set({ stats });
              }} />
          </label>
        ))}
      </div>
      <div className="row eff-items">
        {(bot.items ?? []).map((id, k) => (
          <button key={`${id}-${k}`} className="chip-item" title={t('efx.removeItem')} onClick={() => set({ items: bot.items.filter((_, j) => j !== k) })}>
            <ItemIcon id={id} size={20} /> {byId.get(id)?.name ?? id} ✕
          </button>
        ))}
        {(bot.items ?? []).length < 6 && (
          <select value="" onChange={(e) => e.target.value && set({ items: [...(bot.items ?? []), Number(e.target.value)] })} aria-label={t('efx.addItem')}>
            <option value="">{t('efx.addItem')}</option>
            {shop.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
          </select>
        )}
      </div>
      <div>
        <label className="check">
          <input type="checkbox" checked={bot.pulses == null} onChange={(e) => set({ pulses: e.target.checked ? null : JSON.parse(JSON.stringify(role?.pulses ?? [])) })} />
          {t('efx.rolePattern')}
        </label>
        <table className="data eff-pulses">
          <thead><tr><th>{t('efx.pulse.name')}</th><th>{t('efx.pulse.kind')}</th><th>{t('efx.pulse.type')}</th><th>{t('efx.pulse.base')}</th><th>{t('efx.pulse.ad')}</th><th>{t('efx.pulse.ap')}</th><th>{t('efx.pulse.every')}</th><th>{t('efx.pulse.range')}</th><th /></tr></thead>
          <tbody>
            {pulses.map((p, i) => (
              <tr key={i}>
                <td><input value={p.name} disabled={bot.pulses == null} onChange={(e) => setPulse(i, { name: e.target.value })} /></td>
                <td>
                  <select value={p.kind} disabled={bot.pulses == null} onChange={(e) => setPulse(i, { kind: e.target.value })}>
                    {['dano', 'escudo', 'cura'].map((k) => <option key={k} value={k}>{t(`efx.pulseKind.${k}`)}</option>)}
                  </select>
                </td>
                <td>
                  <select value={p.type} disabled={bot.pulses == null || p.kind !== 'dano'} onChange={(e) => setPulse(i, { type: e.target.value })}>
                    {['fisico', 'magico', 'verdadeiro'].map((k) => <option key={k} value={k}>{t(`efx.dmg.${k}`)}</option>)}
                  </select>
                </td>
                {['base', 'ad', 'ap', 'every', 'range'].map((k) => (
                  <td key={k}><input type="number" step={k === 'ad' || k === 'ap' ? 0.05 : 1} value={p[k]} disabled={bot.pulses == null}
                    onChange={(e) => setPulse(i, { [k]: Number(e.target.value) })} style={{ width: 64 }} /></td>
                ))}
                <td>{bot.pulses != null && <button className="icon" onClick={() => set({ pulses: pulses.filter((_, k) => k !== i) })} aria-label={t('efx.removePulse')}>✕</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {bot.pulses != null && (
          <button className="link" onClick={() => set({ pulses: [...pulses, { name: t('efx.pulse.new'), kind: 'dano', type: 'magico', base: 100, ad: 0, ap: 0.5, every: 6, range: 600, duration: 2.5 }] })}>
            {t('efx.addPulse')}
          </button>
        )}
      </div>
    </div>
  );
}

/** "Testar agora": the scenario on a saved build at a minute, with the index and the link to the fight in the Arena. */
function TryNow({ test }) {
  const [builds, setBuilds] = useState([]);
  const [folders, setFolders] = useState([]);
  const [buildId, setBuildId] = useState('');
  const [minute, setMinute] = useState(20);
  const [ref, setRef] = useState({ kind: 'proprio' });
  const [out, setOut] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  useEffect(() => {
    Promise.all([api.get('/api/builds'), api.get('/api/folders')]).then(([b, f]) => {
      setBuilds(b.filter((x) => x.unitCode !== 'RAGDOLL'));
      setFolders(f);
    }).catch(() => {});
  }, []);
  const job = { test: { ...test, preset: false } };
  const req = { buildId: Number(buildId), minute, reference: ref, jobs: [job] };
  const go = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await api.post('/api/effect/evaluate', req);
      setOut({ ...r.outcomes[0], ms: r.ms });
    } catch (e) {
      setError(e.message);
    }
    setBusy(false);
  };
  const open = async () => {
    try {
      const sc = await api.post('/api/effect/scenario', req);
      openInArena({ ...sc, id: null, actions: [] });
    } catch (e) {
      setError(e.message);
    }
  };
  const m = out?.build;
  return (
    <section className="panel">
      <h3>{t('efx.tryNow')}</h3>
      <div className="row">
        <select value={buildId} onChange={(e) => { setBuildId(e.target.value); setOut(null); }} aria-label={t('efx.build')}>
          <option value="">{t('efx.build')}</option>
          {builds.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
        </select>
        <label className="inline">{t('ptest.minute')}<MinuteInput value={minute} onChange={(v) => { setMinute(v); setOut(null); }} placeholder="20:00" style={{ width: 70 }} /></label>
        <ReferencePicker value={ref} onChange={setRef} builds={builds} folders={folders} />
        <button className="primary" onClick={go} disabled={!buildId || busy}>{busy ? t('efx.running') : t('efx.tryNowRun')}</button>
        <button onClick={open} disabled={!buildId}>{t('efx.openArena')}</button>
      </div>
      {error && <p className="error">{error}</p>}
      {out?.error && <p className="error">{out.error}</p>}
      {m && (
        <dl className="eff-measures">
          <dt>{t('efx.col.index')}</dt><dd><strong>{out.index == null ? '—' : n0(out.index)}</strong> <small className="muted">({n0(out.ms)} ms)</small></dd>
          <dt>{t('efx.col.ttd')}</dt><dd>{ttd(m)} · {t('efx.col.ref')} {ttd(out.reference)}</dd>
          <dt>{t('efx.col.ehp')}</dt><dd>{n0(m.effectiveHealth)}</dd>
          <dt>{t('efx.col.mitigated')}</dt><dd>{n0(m.mitigatedPct)}%</dd>
          <dt>{t('efx.col.shield')}</dt><dd>{n0(m.shieldAbsorbed)}</dd>
          <dt>{t('efx.col.heal')}</dt><dd>{n0(m.healed)}{m.healLost > 0.5 ? ` (−${n0(m.healLost)})` : ''}</dd>
          <dt>{t('efx.col.dealt')}</dt><dd>{n0(m.dealt)}</dd>
          <dt>{t('efx.col.cc')}</dt><dd>{n1(m.controlApplied)} s</dd>
          {m.absorbedShare != null && <><dt>{t('efx.col.team')}</dt><dd>{t('efx.teamCell', { share: n0(m.absorbedShare), alive: n1(m.alliesAlive) })}</dd></>}
        </dl>
      )}
    </section>
  );
}

/** The basic and complete reports' settings: scenarios with weights, the sweeps' stages; saved, or back to the default. */
function ReportsEditor({ all, meta }) {
  const [kind, setKind] = useState('basico');
  const [rep, setRep] = useState(null);
  const [saved, setSaved] = useState(false);
  useEffect(() => { api.get(`/api/effect/reports/${kind}`).then(setRep); setSaved(false); }, [kind]);
  if (!rep) return null;
  const names = new Map(all.map((x) => [testKey(x), x.name]));
  const set = (patch) => { setRep({ ...rep, ...patch }); setSaved(false); };
  const save = async () => { setRep(await api.put(`/api/effect/reports/${kind}`, rep)); setSaved(true); };
  const restore = async () => { setRep(await api.del(`/api/effect/reports/${kind}`)); setSaved(false); };
  const unused = all.filter((x) => !rep.tests.some((e) => e.key === testKey(x)));
  const unusedSweeps = meta.sweeps.filter((s) => !(rep.sweeps ?? []).some((x) => x.key === s.key));
  return (
    <section className="panel">
      <div className="chips" role="tablist">
        {['basico', 'completo'].map((k) => (
          <button key={k} role="tab" aria-selected={kind === k} className={kind === k ? 'active' : ''} onClick={() => setKind(k)}>{t(`efx.kind.${k}`)}</button>
        ))}
      </div>
      <p className="muted small">{t(`efx.reportHelp.${kind}`)} {rep.isDefault ? t('efx.isDefault') : ''}</p>
      <table className="data">
        <thead><tr><th>#</th><th>{t('efx.col.test')}</th><th>{t('efx.col.weight')}</th><th /></tr></thead>
        <tbody>
          {rep.tests.map((e, i) => (
            <tr key={e.key}>
              <td>{i + 1}</td>
              <td>{names.get(e.key) ?? e.key}</td>
              <td><input type="number" step="0.5" min="0" value={e.weight} style={{ width: 70 }}
                onChange={(ev) => set({ tests: rep.tests.map((x, k) => (k === i ? { ...x, weight: Number(ev.target.value) } : x)) })} /></td>
              <td>
                <button className="icon" disabled={i === 0} onClick={() => { const v = [...rep.tests]; [v[i - 1], v[i]] = [v[i], v[i - 1]]; set({ tests: v }); }} aria-label={t('efx.up')}>▲</button>
                <button className="icon" onClick={() => set({ tests: rep.tests.filter((_, k) => k !== i) })} aria-label={t('efx.remove')}>✕</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {unused.length > 0 && (
        <select value="" onChange={(e) => e.target.value && set({ tests: [...rep.tests, { key: e.target.value, weight: 1 }] })} aria-label={t('efx.addTest')}>
          <option value="">{t('efx.addTest')}</option>
          {unused.map((x) => <option key={testKey(x)} value={testKey(x)}>{x.name}</option>)}
        </select>
      )}
      {kind === 'completo' && (
        <>
          <h4>{t('efx.sweeps')}</h4>
          <p className="muted small">{t('efx.sweepsHelp', { name: meta.sweepAttacker?.name ?? '' })}</p>
          <table className="data">
            <thead><tr><th>{t('efx.dimension')}</th>{[1, 2, 3, 4, 5].map((k) => <th key={k}>{t('efx.stage', { n: k })}</th>)}<th /></tr></thead>
            <tbody>
              {(rep.sweeps ?? []).map((s, i) => (
                <tr key={s.key}>
                  <td>{s.name}</td>
                  {s.stages.map((v, k) => (
                    <td key={k}><input type="number" value={v} style={{ width: 70 }}
                      onChange={(ev) => set({ sweeps: rep.sweeps.map((x, j) => (j === i ? { ...x, stages: x.stages.map((y, q) => (q === k ? Number(ev.target.value) : y)) } : x)) })} /></td>
                  ))}
                  <td><button className="icon" onClick={() => set({ sweeps: rep.sweeps.filter((_, j) => j !== i) })} aria-label={t('efx.remove')}>✕</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          {unusedSweeps.length > 0 && (
            <select value="" onChange={(e) => e.target.value && set({ sweeps: [...(rep.sweeps ?? []), meta.sweeps.find((s) => s.key === e.target.value)] })} aria-label={t('efx.addSweep')}>
              <option value="">{t('efx.addSweep')}</option>
              {unusedSweeps.map((s) => <option key={s.key} value={s.key}>{s.name}</option>)}
            </select>
          )}
        </>
      )}
      <div className="row">
        <button className="primary" onClick={save}>{t('efx.save')}</button>
        <button onClick={restore}>{t('efx.restore')}</button>
        {saved && <small className="muted">{t('efx.saved')}</small>}
      </div>
    </section>
  );
}

function Help() {
  return (
    <section className="panel eff-help">
      {['how', 'bots', 'measures', 'index', 'reference', 'reports', 'items', 'cost'].map((k) => (
        <div key={k}>
          <h4>{t(`efx.help.${k}.title`)}</h4>
          <p>{t(`efx.help.${k}`)}</p>
        </div>
      ))}
    </section>
  );
}
