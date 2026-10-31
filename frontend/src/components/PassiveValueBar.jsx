import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { n1 } from '../format.js';
import { effectMeta } from '../effectiveness.js';
import { PV_MEASURED, PV_MODES, PV_TIER } from '../passiveValues.js';

/** "Valor das passivas": by tier, estimated or measured in a scenario, with the legend of what is on screen. */
export default function PassiveValueBar({ pv }) {
  const [tests, setTests] = useState([]);
  const [saved, setSaved] = useState([]);
  useEffect(() => {
    if (pv.mode !== PV_MEASURED) return;
    Promise.all([effectMeta(), api.get('/api/effect/tests'), api.get('/api/arena/scenarios')]).then(([m, user, sc]) => {
      setTests([...m.presets.map((p) => ({ key: p.key, name: p.name })), ...user.map((u) => ({ key: `u:${u.id}`, name: u.name }))]);
      setSaved(sc);
    }).catch(() => {});
  }, [pv.mode]);
  const value = pv.scenario.kind === 'basico' ? 'basico' : pv.scenario.kind === 'arena' ? `a:${pv.scenario.id}` : `t:${pv.scenario.key}`;
  const pick = (v) => {
    if (v === 'basico') {
      pv.setScenario({ kind: 'basico', label: t('efx.kind.basico') });
    } else if (v.startsWith('a:')) {
      const id = Number(v.slice(2));
      pv.setScenario({ kind: 'arena', id, label: saved.find((s) => s.id === id)?.name });
    } else {
      const key = v.slice(2);
      pv.setScenario({ kind: 'teste', key, label: tests.find((x) => x.key === key)?.name });
    }
  };
  const shown = pv.mode === PV_TIER ? t('pv.legend.tier') : pv.measured
    ? t('pv.legend.measured', { name: pv.measured.label ?? t('efx.kind.basico') }) : t('pv.legend.estimated');
  return (
    <div className="pv-bar">
      <span className="chips" role="group" aria-label={t('pv.title')}>
        <small className="muted">{t('pv.title')}:</small>
        {PV_MODES.map((m) => (
          <button key={m} className={pv.mode === m ? 'active' : ''} aria-pressed={pv.mode === m} onClick={() => pv.setMode(m)} title={t(`pv.help.${m}`)}>
            {t(`pv.mode.${m}`)}
          </button>
        ))}
      </span>
      {pv.mode === PV_MEASURED && (
        <span className="row">
          <select value={value} onChange={(e) => pick(e.target.value)} aria-label={t('pv.scenario')}>
            <option value="basico">{t('pv.basicReport')}</option>
            {tests.map((x) => <option key={x.key} value={`t:${x.key}`}>{x.name}</option>)}
            {saved.map((s) => <option key={s.id} value={`a:${s.id}`}>{t('pv.arena', { name: s.name })}</option>)}
          </select>
          <button className="primary" onClick={pv.runMeasure} disabled={!!pv.busy}>{t('pv.measure')}</button>
          {pv.busy && (
            <>
              <progress value={pv.busy.done} max={pv.busy.total} />
              <small className="muted">{t('efx.progress', { done: pv.busy.done, total: pv.busy.total, s: n1(pv.busy.s ?? 0) })}</small>
              <button onClick={pv.stopMeasure}>{t('rank.stop')}</button>
            </>
          )}
        </span>
      )}
      <small className="muted pv-legend">{shown}{pv.measured ? ` · ${n1(pv.measured.s)} s` : ''}</small>
      {pv.error && <small className="error">{pv.error}</small>}
    </div>
  );
}

/** The mark of a passive: estimated, by tier or measured in a scenario, with its account in the title. */
export function PassiveSource({ passive }) {
  if (!passive.source || passive.source === 'status') return null;
  const text = passive.source === PV_MEASURED ? t('pv.src.measured', { name: passive.label ?? t('efx.kind.basico') })
    : t(`pv.src.${passive.source}`);
  return <small className={`pv-src ${passive.source}`} title={passive.detail ?? undefined}>{text}</small>;
}
