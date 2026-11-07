import { useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { mmss, n0, n1 } from '../format.js';
import { useTask } from '../tasks.js';
import Help from './Help.jsx';

const PARTS = ['defensiva', 'ofensiva', 'utilidade', 'duelos'];

/** One minute's score: class, archetype, the weights and parts, mean, P20, floor and total. */
export function ScoreRow({ s }) {
  return (
    <tr>
      <td>{mmss(s.minute)}</td>
      <td>{s.championClass}{s.archetype ? ` · ${s.archetype}` : ''}</td>
      {PARTS.map((p) => (
        <td key={p}>{s.parts[p] == null ? '—' : n1(s.parts[p])} <small className="muted">({n0((s.weights[p] ?? 0) * 100)}%)</small></td>
      ))}
      <td>{n1(s.mean)}</td>
      <td>{n1(s.p20)}</td>
      <td>{s.floorPenalty > 0 ? `−${n1(s.floorPenalty)}` : '—'}</td>
      <td><b>{n1(s.total)}</b></td>
    </tr>
  );
}

/** The class score of the open build (Rodada 9, part 1), robust over the minutes, against a reference build. */
export default function ScorePanel({ build, buildId, builds }) {
  const [ref, setRef] = useState('');
  const [res, setRes] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  useTask('score', busy, { label: t('score.task'), path: '/relatorios/efetividade' });
  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      setRes(await api.post('/api/score', { ...(buildId ? { buildId } : { build }), referenceBuildId: ref ? Number(ref) : null }));
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="panel">
      <h3>{t('score.title')} <Help text={t('score.help')} /></h3>
      <div className="row">
        <label className="inline">{t('score.reference')}
          <select value={ref} onChange={(e) => setRef(e.target.value)}>
            <option value="">—</option>
            {builds.filter((b) => b.unitCode !== 'RAGDOLL').map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select>
        </label>
        <button className="primary" disabled={busy} onClick={run}>{busy ? t('compare.calculating') : t('score.run')}</button>
      </div>
      {error && <p className="error">{error}</p>}
      {res && (
        <>
          <p>
            <b>{t('score.total')}: {n1(res.total)}</b> · {t('score.mean')} {n1(res.mean)} · P20 {n1(res.p20)}
            {res.beatsReference != null && <> · {t('score.beats', { pct: n0(res.beatsReference) })}</>}
          </p>
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>{t('score.minute')}</th><th>{t('score.class')}</th>
                  {PARTS.map((p) => <th key={p}>{t(`score.part.${p}`)} <Help text={t(`score.partHelp.${p}`)} /></th>)}
                  <th>{t('score.mean')}</th><th>P20 <Help text={t('score.p20Help')} /></th><th>{t('score.floor')} <Help text={t('score.floorHelp')} /></th><th>{t('score.total')}</th>
                </tr>
              </thead>
              <tbody>{res.minutes.map((s) => <ScoreRow key={s.minute} s={s} />)}</tbody>
            </table>
          </div>
        </>
      )}
    </section>
  );
}
