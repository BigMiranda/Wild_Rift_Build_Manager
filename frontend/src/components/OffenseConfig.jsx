import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { offenseMeta } from './OffenseReport.jsx';

const FIELDS = [
  ['health', 'off.f.health', 100], ['armor', 'off.f.armor', 5], ['magicResist', 'off.f.mr', 5], ['moveSpeed', 'off.f.ms', 10],
  ['healPct', 'off.f.healPct', 0.5], ['shield', 'off.f.shield', 10], ['shieldEvery', 'off.f.shieldEvery', 0.5],
  ['shieldDuration', 'off.f.shieldDuration', 0.1], ['growSeconds', 'off.f.growSeconds', 0.5],
  ['dashEvery', 'off.f.dashEvery', 0.25], ['dashDistance', 'off.f.dashDistance', 25], ['retaliate', 'off.f.retaliate', 10],
];

const keyOf = (x) => (x.key ? x.key : `u:${x.id}`);

/** Admin › Configuração da Efetividade › Alvos (causar dano): the target ragdolls (presets read-only, the user's editable) and the report. */
export default function OffenseConfig() {
  const [meta, setMeta] = useState(null);
  const [sel, setSel] = useState(null);
  const [draft, setDraft] = useState(null);
  const [rep, setRep] = useState(null);
  const [msg, setMsg] = useState(null);
  const reload = async () => {
    const m = await offenseMeta(true);
    setMeta(m);
    setRep(m.report);
  };
  useEffect(() => { reload().catch((e) => setMsg(e.message)); }, []);
  if (!meta) return msg ? <p className="error">{msg}</p> : null;
  const presets = Object.values(meta.presets);
  const all = [...presets, ...meta.userTargets];
  const names = new Map(all.map((x) => [keyOf(x), x.name]));
  const pick = (x) => { setSel(keyOf(x)); setDraft(JSON.parse(JSON.stringify(x))); };
  const set = (patch) => setDraft({ ...draft, ...patch });
  const save = async () => {
    const s = draft.id ? await api.put(`/api/offense/targets/${draft.id}`, draft) : await api.post('/api/offense/targets', draft);
    await reload();
    pick(s);
  };
  const duplicate = () => { setSel(null); setDraft({ ...draft, id: null, key: null, preset: false, name: t('efx.copyOf', { name: draft.name }) }); };
  const remove = async () => {
    if (!window.confirm(t('efx.deleteConfirm', { name: draft.name }))) return;
    await api.del(`/api/offense/targets/${draft.id}`);
    setDraft(null);
    reload();
  };
  const unused = all.filter((x) => !rep.targets.some((e) => e.key === keyOf(x)));
  const setRepPatch = (p) => setRep({ ...rep, ...p });
  const readOnly = !!draft?.key;
  return (
    <>
      <div className="eff-layout">
        <section className="panel eff-list">
          <h3>{t('off.targets')}</h3>
          <button onClick={() => { setSel(null); setDraft({ name: t('off.newTarget'), health: 3000, armor: 50, magicResist: 50, moveSpeed: 350, behavior: 'parado', shieldEvery: 1, shieldDuration: 1.2, growSeconds: 5, dashDistance: 300 }); }}>{t('efx.new')}</button>
          <h5>{t('efx.presets')}</h5>
          <ul>{presets.map((p) => <li key={p.key}><button className={`link${sel === p.key ? ' active' : ''}`} onClick={() => pick(p)}>{p.name}</button></li>)}</ul>
          <h5>{t('efx.mine')}</h5>
          <ul>{meta.userTargets.map((u) => <li key={u.id}><button className={`link${sel === `u:${u.id}` ? ' active' : ''}`} onClick={() => pick(u)}>{u.name}</button></li>)}</ul>
        </section>
        <div className="eff-main">
          {draft ? (
            <section className="panel">
              <h3>{readOnly ? t('efx.presetTitle', { name: draft.name }) : t('off.editTarget')}</h3>
              {draft.note && <p className="muted small">{draft.note}</p>}
              <fieldset disabled={readOnly} className="eff-fields">
                <div className="row">
                  <label className="inline">{t('efx.name')}<input value={draft.name ?? ''} onChange={(e) => set({ name: e.target.value })} /></label>
                  <label className="inline">{t('off.f.behavior')}
                    <select value={draft.behavior ?? 'parado'} onChange={(e) => set({ behavior: e.target.value })}>
                      {['parado', 'anda', 'foge'].map((b) => <option key={b} value={b}>{t(`off.b.${b}`)}</option>)}
                    </select>
                  </label>
                  <label className="inline">{t('off.f.growTo')}
                    <input type="number" value={draft.growTo ?? ''} style={{ width: 70 }} onChange={(e) => set({ growTo: e.target.value === '' ? null : Number(e.target.value) })} />
                  </label>
                </div>
                <div className="row">
                  {FIELDS.map(([k, label, step]) => (
                    <label key={k} className="inline">{t(label)}
                      <input type="number" step={step} value={draft[k] ?? 0} style={{ width: 72 }} onChange={(e) => set({ [k]: Number(e.target.value) })} />
                    </label>
                  ))}
                </div>
              </fieldset>
              <div className="row">
                {readOnly ? <button onClick={duplicate}>{t('efx.duplicate')}</button> : (
                  <>
                    <button className="primary" onClick={save}>{t('efx.save')}</button>
                    {draft.id && <button onClick={duplicate}>{t('efx.duplicate')}</button>}
                    {draft.id && <button className="danger" onClick={remove}>{t('efx.delete')}</button>}
                  </>
                )}
              </div>
            </section>
          ) : <section className="panel"><p className="muted">{t('off.pickTarget')}</p></section>}
        </div>
      </div>
      <section className="panel">
        <h3>{t('off.reportTitle')}</h3>
        <p className="muted small">{t('off.reportHelp')} {rep.isDefault ? t('efx.isDefault') : ''}</p>
        <table className="data">
          <thead><tr><th>#</th><th>{t('off.target')}</th><th>{t('efx.col.weight')}</th><th /></tr></thead>
          <tbody>
            {rep.targets.map((e, i) => (
              <tr key={e.key}>
                <td>{i + 1}</td>
                <td>{names.get(e.key) ?? e.key}</td>
                <td><input type="number" step="0.5" min="0" value={e.weight} style={{ width: 70 }}
                  onChange={(ev) => setRepPatch({ targets: rep.targets.map((x, k) => (k === i ? { ...x, weight: Number(ev.target.value) } : x)) })} /></td>
                <td><button className="icon" onClick={() => setRepPatch({ targets: rep.targets.filter((_, k) => k !== i) })} aria-label={t('efx.remove')}>✕</button></td>
              </tr>
            ))}
          </tbody>
        </table>
        {unused.length > 0 && (
          <select value="" onChange={(e) => e.target.value && setRepPatch({ targets: [...rep.targets, { key: e.target.value, weight: 1 }] })} aria-label={t('off.addTarget')}>
            <option value="">{t('off.addTarget')}</option>
            {unused.map((x) => <option key={keyOf(x)} value={keyOf(x)}>{x.name}</option>)}
          </select>
        )}
        <h4>{t('efx.sweeps')}</h4>
        <table className="data">
          <thead><tr><th>{t('efx.dimension')}</th>{[1, 2, 3, 4, 5].map((k) => <th key={k}>{t('efx.stage', { n: k })}</th>)}</tr></thead>
          <tbody>
            {(rep.sweeps ?? []).map((s, i) => (
              <tr key={s.key}>
                <td>{s.name}</td>
                {s.stages.map((v, k) => (
                  <td key={k}><input type="number" value={v} style={{ width: 70 }}
                    onChange={(ev) => setRepPatch({ sweeps: rep.sweeps.map((x, j) => (j === i ? { ...x, stages: x.stages.map((y, q) => (q === k ? Number(ev.target.value) : y)) } : x)) })} /></td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        <div className="row">
          <button className="primary" onClick={async () => { setRep(await api.put('/api/offense/report', rep)); offenseMeta(true); setMsg(t('efx.saved')); }}>{t('efx.save')}</button>
          <button onClick={async () => { setRep(await api.del('/api/offense/report')); offenseMeta(true); }}>{t('efx.restore')}</button>
          {msg && <small className="muted">{msg}</small>}
        </div>
      </section>
    </>
  );
}
