import { useEffect, useRef, useState } from 'react';
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api } from '../api.js';
import { PUBLIC } from '../publicMode.js';
import { t } from '../i18n.js';
import { SERIES_COLORS, mmss, n0, n1 } from '../format.js';
import { useTask } from '../tasks.js';
import { useBuild } from '../buildContext.jsx';
import { navigate } from '../router.js';
import ItemIcon from '../components/ItemIcon.jsx';
import Help from '../components/Help.jsx';
import WholeDelta from '../components/WholeDelta.jsx';
import { BuildLink } from '../components/BuildPreview.jsx';
import MetaBuilds from '../components/MetaBuilds.jsx';
import DiscoveryNotes from '../components/DiscoveryNotes.jsx';

const STORE = 'ornn-planner-discovery';
const AXES = ['arquetipo', 'dano', 'durabilidade', 'passivas'];

const initial = () => {
  const def = { buildId: '', budget: 'rapido', objective: 'melhores', runes: true, order: true, seedsFromSaved: true, axes: ['arquetipo', 'dano', 'durabilidade'], archetype: '', seed: 1 };
  try {
    return { ...def, ...JSON.parse(localStorage.getItem(STORE) ?? '{}') };
  } catch {
    return def;
  }
};

/** A kit's items and boots as icons, in the order it is bought when known. */
export function KitIcons({ kit, size = 24 }) {
  const ids = kit.order?.length ? kit.order : [...kit.items, ...(kit.boots ? [kit.boots] : [])];
  return (
    <span className="step-row">
      {ids.map((id, i) => <span key={`${id}-${i}`} title={kit.names?.[kit.items.indexOf(id)] ?? (id === kit.boots ? kit.bootsName : '')}><ItemIcon id={id} size={size} /></span>)}
    </span>
  );
}

const pct = (v) => (v == null ? '—' : `${n0(v)}%`);
const num = (v) => (v == null ? '—' : n1(v));

/** Simulações › Descoberta: the funnel over items, runes and order, the niches, the discoveries and the synergies. */
export default function Discovery() {
  const { openDraft, items, runesReady } = useBuild();
  const [form, setForm] = useState(initial);
  const [builds, setBuilds] = useState([]);
  const [estimate, setEstimate] = useState(null);
  const [job, setJob] = useState(null);
  const [error, setError] = useState(null);
  const [mine, setMine] = useState({});
  const [saved, setSaved] = useState({});
  const timer = useRef(null);
  useTask('discovery', job?.status === 'running', { label: t('disc.task'), done: job?.done, total: job?.total, path: '/simulacoes/descoberta' });
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));

  useEffect(() => {
    api.get('/api/builds').then(setBuilds).catch((e) => setError(e.message));
    return () => clearInterval(timer.current);
  }, []);
  useEffect(() => {
    try {
      localStorage.setItem(STORE, JSON.stringify(form));
    } catch {
      /* session only */
    }
  }, [form]);
  const request = () => ({ ...form, buildId: Number(form.buildId), archetype: form.archetype || null });
  useEffect(() => {
    if (!form.buildId || PUBLIC) return undefined;
    const id = setTimeout(() => api.post('/api/discovery/estimate', request()).then(setEstimate).catch(() => {}), 300);
    return () => clearTimeout(id);
  }, [form]);

  const follow = (id) => {
    clearInterval(timer.current);
    timer.current = setInterval(async () => {
      const now = await api.get(`/api/discovery/${id}`);
      setJob(now);
      if (now.status !== 'running') clearInterval(timer.current);
    }, 1000);
  };
  const start = async () => {
    setError(null);
    setMine({});
    setSaved({});
    try {
      const j = await api.post('/api/discovery/start', request());
      setJob(j);
      follow(j.id);
    } catch (e) {
      setError(e.message);
    }
  };
  const r = job?.result;
  const unitCode = builds.find((b) => String(b.id) === String(form.buildId))?.unitCode;
  const [latest, setLatest] = useState(null);
  useEffect(() => {
    setLatest(null);
    if (!unitCode) return;
    api.get(`/api/campaigns/latest?unit=${encodeURIComponent(unitCode)}`).then((l) => setLatest(l?.discovery ? l : null)).catch(() => {});
  }, [unitCode, job?.status]);
  // the public version only reads: the champion's last result shows by itself
  useEffect(() => {
    if (PUBLIC && latest) setJob({ status: 'done', result: latest.discovery });
  }, [latest]);
  const baseBuild = async () => api.get(`/api/builds/${form.buildId}`);
  const asBuild = (b, k, label) => ({
    ...b, name: `${b.name} · ${label}`, steps: (k.order?.length ? k.order : [...k.items, ...(k.boots ? [k.boots] : [])]).map((id) => ({ itemId: id, includeConditional: true })),
    runePage: k.runePage ?? b.runePage,
  });
  const open = async (k, label, to = '/build/loja') => {
    try {
      if (openDraft(asBuild(await baseBuild(), k, label))) navigate(to);
    } catch (e) {
      setError(e.message);
    }
  };
  const save = async (k, label, key) => {
    try {
      const b = await api.post('/api/discovery/save', { baseBuildId: Number(form.buildId), name: label, order: k.order?.length ? k.order : [...k.items, ...(k.boots ? [k.boots] : [])], runePage: k.runePage });
      setSaved((s) => ({ ...s, [key]: b }));
    } catch (e) {
      setError(e.message);
    }
  };
  const compare = async (k, key) => {
    setMine((m) => ({ ...m, [key]: 'loading' }));
    try {
      const d = await api.post('/api/quick/delta', { buildId: Number(form.buildId), change: { order: k.order?.length ? k.order : [...k.items, ...(k.boots ? [k.boots] : [])] }, minute: 20 });
      setMine((m) => ({ ...m, [key]: d }));
    } catch (e) {
      setMine((m) => ({ ...m, [key]: { error: e.message } }));
    }
  };

  const actions = (k, key, label) => (
    <>
      <div className="row" style={{ gap: 4 }}>
        <button className="small" onClick={() => open(k, label)}>{t('calc.openAsBuild')}</button>
        {!PUBLIC && <button className="small" onClick={() => save(k, label, key)} disabled={!!saved[key]}>{saved[key] ? t('disc.saved') : t('disc.save')}</button>}
        <button className="small" onClick={() => compare(k, key)}>{t('calc.compareMine')}</button>
        <button className="small" onClick={() => open(k, label, '/relatorios/efetividade')}>{t('disc.inEffect')}</button>
      </div>
      {saved[key] && <small className="muted">{t('disc.savedAs', { name: saved[key].name })}</small>}
      {mine[key] === 'loading' && <small className="muted">{t('compare.calculating')}</small>}
      {mine[key]?.error && <small className="error">{mine[key].error}</small>}
      {mine[key]?.sources && <WholeDelta delta={mine[key]} compact title={t('calc.mineTitle')} show={['gold', 'effective']} ownLabel={t('wd.ownOrder')} />}
    </>
  );

  const curveRows = () => {
    if (!r) return [];
    const series = [];
    if (r.standard) series.push({ name: t('disc.standard'), k: r.standard });
    const bestKnown = [...(r.known ?? [])].filter((k) => k.score).sort((a, b) => b.score - a.score)[0];
    if (bestKnown) series.push({ name: `${t('disc.bestKnown')}: ${bestKnown.label}`, k: bestKnown });
    r.ranked.slice(0, 3).forEach((k, i) => { if (k !== r.standard) series.push({ name: `#${i + 1}`, k }); });
    const minutes = [...new Set(series.flatMap((s) => Object.keys(s.k.curve ?? {})))].map(Number).sort((a, b) => a - b);
    return { series, data: minutes.map((m) => Object.fromEntries([['m', m], ...series.map((s, i) => [`s${i}`, s.k.curve?.[String(m)] ?? s.k.curve?.[m.toFixed(1)] ?? null])])) };
  };
  const curve = curveRows();

  return (
    <div className="page">
      <section className="panel">
        <h2>{t('disc.title')} <Help text={t('disc.help')} /></h2>
        <div className="row">
          <label className="field grow">{t('calc.build')}
            <select value={form.buildId} onChange={(e) => set({ buildId: e.target.value })}>
              <option value="">—</option>
              {builds.filter((b) => b.unitCode !== 'RAGDOLL').map((b) => <option key={b.id} value={b.id}>{b.name} · {b.unitCode}</option>)}
            </select>
            {form.buildId !== '' && <BuildLink id={Number(form.buildId)} name={builds.find((b) => String(b.id) === String(form.buildId))?.name}>{t('preview.short')}</BuildLink>}
          </label>
          {!PUBLIC && <>
          <label className="field">{t('disc.budget')} <Help text={t('disc.budgetHelp')} />
            <select value={form.budget} onChange={(e) => set({ budget: e.target.value })}>
              <option value="rapido">{t('disc.budget.rapido')}</option>
              <option value="completo">{t('disc.budget.completo')}</option>
            </select>
          </label>
          <label className="field">{t('disc.objective')} <Help text={t('disc.objectiveHelp')} />
            <select value={form.objective} onChange={(e) => set({ objective: e.target.value })}>
              <option value="melhores">{t('disc.objective.melhores')}</option>
              <option value="novidade">{t('disc.objective.novidade')}</option>
            </select>
          </label>
          <label className="field">{t('disc.archetype')}
            <select value={form.archetype} onChange={(e) => set({ archetype: e.target.value })}>
              <option value="">{t('disc.free')}</option>
              {['tanque', 'lutador', 'critico', 'ao_acertar', 'letalidade', 'mago_continuo', 'mago_explosivo', 'suporte', 'hibrido'].map((a) => <option key={a} value={a}>{a}</option>)}
            </select>
          </label>
          </>}
        </div>
        {PUBLIC && <p className="muted small">{t('pub.discoveryHelp')}</p>}
        {PUBLIC && form.buildId !== '' && !latest && <p className="notice small">{t('pub.discoveryNone')}</p>}
        {!PUBLIC && <>
        <div className="row">
          <label className="check"><input type="checkbox" checked={form.runes} onChange={(e) => set({ runes: e.target.checked })} />{t('disc.runes')}</label>
          <label className="check"><input type="checkbox" checked={form.order} onChange={(e) => set({ order: e.target.checked })} />{t('disc.order')}</label>
          <label className="check" title={t('disc.seedsHelp')}><input type="checkbox" checked={form.seedsFromSaved} onChange={(e) => set({ seedsFromSaved: e.target.checked })} />{t('disc.seeds')}</label>
          <span className="muted small">{t('disc.axes')}:</span>
          {AXES.map((a) => (
            <label key={a} className="check"><input type="checkbox" checked={form.axes.includes(a)}
              onChange={(e) => set({ axes: e.target.checked ? [...form.axes, a] : form.axes.filter((x) => x !== a) })} />{t(`disc.axis.${a}`)}</label>
          ))}
          <label className="inline">{t('disc.seed')}<input type="number" value={form.seed} style={{ width: 60 }} onChange={(e) => set({ seed: Number(e.target.value) || 1 })} /></label>
        </div>
        {estimate && <p className="muted">{t('disc.estimate', { f: estimate.full, q: estimate.quick, o: estimate.order, c: estimate.candidates, s: mmss(estimate.seconds / 60) })}</p>}
        <div className="row">
          <button className="primary" disabled={!form.buildId || job?.status === 'running'} onClick={start}>{t('disc.start')}</button>
          {job?.status === 'running' && (
            <>
              <button onClick={() => api.post(`/api/discovery/${job.id}/stop`, {})}>{t('calc.stop')}</button>
              <small>{t(`disc.stage.${job.stage}`) ?? job.stage}</small>
              <progress max={Math.max(1, job.total)} value={job.done} />
              <small className="muted">{job.done} / {job.total}</small>
            </>
          )}
        </div>
        </>}
        {(error || job?.error) && <p className="error">{error ?? job.error}</p>}
        {latest && job?.result !== latest.discovery && (
          <p className="small">{t('disc.latest', { s: n0(latest.discovery.seconds), b: t(`disc.budget.${latest.discovery.budget ?? 'rapido'}`) })}{' '}
            <button className="small" onClick={() => setJob({ status: 'done', result: latest.discovery })}>{t('disc.showLatest')}</button></p>
        )}
      </section>

      <MetaBuilds unitCode={builds.find((b) => String(b.id) === String(form.buildId))?.unitCode} />

      {r && (
        <>
          <section className="panel">
            <h3>{r.champion} · {r.championClass} {r.quality ? `· ${t('disc.quality', { q: r.quality })}` : ''}</h3>
            {r.lowConfidence && <p className="notice">{t('disc.lowConfidence')}</p>}
            <p className="muted small">
              {t('disc.cost', { s: n0(r.seconds), q: r.quickRuns, f: r.fullRuns, c: r.confirmRuns, o: r.orderMeasures })}
              {r.prunedByStart > 0 && ` · ${t('calc.pruned', { n: r.prunedByStart })}`}
              {r.surrogateCorrelation != null && ` · ${t('disc.surrogate', { r: r.surrogateCorrelation.toFixed(2) })}`}
              {r.stopped && ` · ${t('disc.stopped')}`}
            </p>
            {r.standard && (
              <div className="disc-standard">
                <h4>{t('disc.standard')} <Help text={t('disc.standardHelp')} /></h4>
                <KitIcons kit={r.standard} size={30} />
                <p>
                  <b>{num(r.standard.score)}</b> · {t('score.mean')} {num(r.standard.mean)} · P20 {num(r.standard.p20)}
                  {r.standard.beatsKnown != null && ` · ${t('disc.beatsKnown', { pct: n0(r.standard.beatsKnown) })}`}
                </p>
                {r.standard.runePage && <p className="small">{t('calc.runePage')}: <b>{r.standard.runePage.keystone}</b> · {[...(r.standard.runePage.primaryRunes ?? []), r.standard.runePage.secondaryRune].filter(Boolean).join(' · ')}</p>}
                {actions(r.standard, 'standard', t('disc.standard'))}
              </div>
            )}
            {r.support && (
              <div className="disc-standard">
                <h4>{t('disc.support')} <Help text={t('disc.supportHelp')} /></h4>
                <KitIcons kit={r.support} size={30} />
                <p>
                  <b>{num(r.support.score)}</b> · {t('score.mean')} {num(r.support.mean)} · P20 {num(r.support.p20)}
                  {r.support.goldPerMin != null && ` · ${n0(r.support.goldPerMin)} ${t('disc.goldMin')}`}
                </p>
                {r.support.runePage && <p className="small">{t('calc.runePage')}: <b>{r.support.runePage.keystone}</b></p>}
                {actions(r.support, 'support', t('disc.support'))}
              </div>
            )}
          </section>

          {r.discoveries?.length > 0 && (
            <section className="panel">
              <h3>{t('disc.discoveries')} <Help text={t('disc.discoveriesHelp')} /></h3>
              {r.discoveries.map((f, i) => (
                <div key={i} className="disc-finding">
                  <KitIcons kit={f.kit} />
                  <p>{f.text}</p>
                  <small className="muted">{t('disc.novelty', { n: n0((f.kit.novelty ?? 0) * 100), k: f.kit.closestKnown ?? '—' })}</small>
                  {actions(f.kit, `d${i}`, t('disc.discovery', { n: i + 1 }))}
                </div>
              ))}
            </section>
          )}

          <section className="panel">
            <h3>{t('disc.ranked')}</h3>
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>#</th><th>{t('disc.kit')}</th>
                    <th>{t('score.total')} <Help text={t('score.help')} /></th>
                    <th>{t('score.mean')}</th><th>P20</th>
                    <th>{t('disc.confirmed')} <Help text={t('disc.confirmedHelp')} /></th>
                    <th>{t('disc.versatile')} <Help text={t('disc.versatileHelp')} /></th>
                    <th>{t('disc.noveltyCol')} <Help text={t('disc.noveltyHelp')} /></th>
                    <th>{t('disc.beatsCol')}</th>
                    <th>{t('disc.niche')}</th><th>{t('disc.origin')}</th><th>{t('calc.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {r.ranked.map((k, i) => (
                    <tr key={i}>
                      <td>{i + 1}</td>
                      <td><KitIcons kit={k} /><br /><small className="muted">{[...k.names, k.bootsName].filter(Boolean).join(', ')}</small></td>
                      <td><b>{num(k.score)}</b></td><td>{num(k.mean)}</td><td>{num(k.p20)}</td><td>{num(k.confirmed)}</td>
                      <td>{num(k.versatile)}</td>
                      <td>{k.novelty == null ? '—' : pct(k.novelty * 100)}</td><td>{pct(k.beatsKnown)}</td>
                      <td><small>{k.niche}</small></td><td><small>{t(`disc.origin.${k.origin}`)}</small></td>
                      <td>{actions(k, `r${i}`, `#${i + 1}`)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {r.ranked[0]?.importance?.length > 0 && (
              <>
                <h4>{t('calc.early')} <Help text={t('calc.earlyHelp')} /></h4>
                <ul>
                  {r.ranked[0].importance.map((m, i) => (
                    <li key={i}><ItemIcon id={m.itemId} size={18} /> {m.item} · {m.readyAt != null ? mmss(m.readyAt) : '—'}
                      {m.delayed != null && ` · ${t('calc.delayed')} ${-m.delayed >= 0 ? '+' : '−'}${n1(Math.abs(m.delayed))}`}</li>
                  ))}
                </ul>
              </>
            )}
          </section>

          {curve.series?.length > 0 && (
            <section className="panel">
              <h3>{t('disc.curve')} <Help text={t('disc.curveHelp')} /></h3>
              <ResponsiveContainer width="100%" height={240}>
                <LineChart data={curve.data} margin={{ top: 8, right: 16, bottom: 4, left: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" opacity={0.3} />
                  <XAxis dataKey="m" tickFormatter={(m) => mmss(m)} />
                  <YAxis width={40} />
                  <Tooltip formatter={(v) => n1(v)} labelFormatter={(m) => mmss(m)} />
                  <Legend />
                  {curve.series.map((s, i) => <Line key={i} dataKey={`s${i}`} name={s.name} stroke={SERIES_COLORS[i % SERIES_COLORS.length]} dot isAnimationActive={false} />)}
                </LineChart>
              </ResponsiveContainer>
            </section>
          )}

          <section className="panel">
            <h3>{t('disc.niches')} <Help text={t('disc.nichesHelp')} /></h3>
            <table className="table">
              <tbody>
                {Object.entries(r.niches).map(([n, k]) => (
                  <tr key={n}><td>{n}</td><td><KitIcons kit={k} /></td><td><b>{num(k.score)}</b></td><td>{k.novelty == null ? '' : pct(k.novelty * 100)}</td><td>{actions(k, `n${n}`, n)}</td></tr>
                ))}
              </tbody>
            </table>
          </section>

          {r.synergies?.length > 0 && (
            <section className="panel">
              <h3>{t('disc.synergies')} <Help text={t('disc.synergiesHelp')} /></h3>
              <table className="table">
                <tbody>
                  {r.synergies.map((s, i) => (
                    <tr key={i}>
                      <td><ItemIcon id={s.a} size={20} /> + <ItemIcon id={s.b} size={20} /></td>
                      <td className={s.value >= 0 ? 'delta-up' : 'delta-down'}>{s.value >= 0 ? '+' : '−'}{n1(Math.abs(s.value))}</td>
                      <td><small>{s.explain}</small></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}

          <section className="panel">
            <h3>{t('disc.known')}</h3>
            <table className="table">
              <tbody>
                {r.known.map((k, i) => (
                  <tr key={i}><td>{k.label}</td><td><small>{t(`disc.origin.${k.origin}`)}</small></td><td><KitIcons kit={k} /></td><td>{num(k.score)}</td><td><small>{k.niche}</small></td></tr>
                ))}
              </tbody>
            </table>
            {r.notes?.length > 0 && <DiscoveryNotes key={runesReady ? 'r' : 'n'} notes={r.notes} items={items} />}
          </section>
        </>
      )}
    </div>
  );
}
