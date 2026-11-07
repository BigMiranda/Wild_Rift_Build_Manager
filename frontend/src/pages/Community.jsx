import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { mmss, n1 } from '../format.js';
import { useBuild } from '../buildContext.jsx';
import { navigate } from '../router.js';
import ItemIcon from '../components/ItemIcon.jsx';
import { forgetMine, setSharing, sharing } from '../community.js';

/**
 * Builds › Guias of the public version (Rodada 11, part 4): the administrator's guides (the public builds), the
 * community's (approved by the administrator), and the box that lets the visitor share their builds. Off by default.
 */
export default function Community() {
  const { meta, builds, openBuild, openDraft } = useBuild();
  const [on, setOn] = useState(sharing);
  const [community, setCommunity] = useState([]);
  const [msg, setMsg] = useState(null);

  useEffect(() => {
    api.get('/api/community/guides').then(setCommunity).catch(() => setCommunity([]));
  }, []);

  const unitName = (code) => meta?.units?.find((u) => u.code === code)?.name ?? code;
  const admin = useMemo(() => builds.filter((b) => b.readOnly && b.unitCode !== 'RAGDOLL'), [builds]);
  const byUnit = (list, unitOf) => {
    const m = new Map();
    list.forEach((x) => {
      const u = unitOf(x);
      if (!m.has(u)) m.set(u, []);
      m.get(u).push(x);
    });
    return [...m.entries()].sort((a, b) => unitName(a[0]).localeCompare(unitName(b[0])));
  };

  const toggle = (value) => {
    setSharing(value);
    setOn(value);
    setMsg(value ? t('comm.onNow') : t('comm.offNow'));
  };
  const forget = async () => {
    if (!window.confirm(t('comm.forgetConfirm'))) return;
    try {
      setMsg(t('comm.forgotten', { n: await forgetMine() }));
    } catch (e) {
      setMsg(e.message);
    }
  };
  const openGuide = (g) => {
    if (openDraft({ ...g.build, name: t('comm.guideName', { champion: unitName(g.unitCode) }) })) navigate('/build/loja');
  };
  const items = (steps) => (steps ?? []).filter((s) => s.kind !== 'moment' && s.kind !== 'sell' && s.itemId != null);

  return (
    <div className="page">
      <section className="panel">
        <h3>{t('comm.shareTitle')}</h3>
        <label className="check">
          <input type="checkbox" checked={on} onChange={(e) => toggle(e.target.checked)} />
          {t('comm.share')}
        </label>
        <p className="muted small">{t('comm.shareHelp')}</p>
        <div className="row">
          <button onClick={forget}>{t('comm.forget')}</button>
          {msg && <small>{msg}</small>}
        </div>
      </section>

      <section className="panel">
        <h3>{t('comm.adminTitle')}</h3>
        <p className="muted small">{t('comm.adminHelp')}</p>
        {admin.length === 0 && <p className="muted">{t('comm.none')}</p>}
        {byUnit(admin, (b) => b.unitCode).map(([unit, list]) => (
          <div key={unit}>
            <h4>{unitName(unit)}</h4>
            <ul className="plain-list">
              {list.map((b) => (
                <li key={b.id}><button className="link" onClick={async () => { if (await openBuild(b.id)) navigate('/build/loja'); }}>{b.name}</button></li>
              ))}
            </ul>
          </div>
        ))}
      </section>

      <section className="panel">
        <h3>{t('comm.communityTitle')}</h3>
        <p className="muted small">{t('comm.communityHelp')}</p>
        {community.length === 0 && <p className="muted">{t('comm.none')}</p>}
        {byUnit(community, (g) => g.unitCode).map(([unit, list]) => (
          <div key={unit}>
            <h4>{unitName(unit)}</h4>
            <table className="data">
              <thead><tr><th className="l">{t('comm.col.items')}</th><th>{t('comm.col.votes')}</th><th>{t('comm.col.score')}</th><th /></tr></thead>
              <tbody>
                {list.map((g) => (
                  <tr key={g.id}>
                    <td className="l"><span className="step-row">{items(g.build?.steps).map((s, i) => <ItemIcon key={i} id={s.itemId} size={22} />)}</span></td>
                    <td>{g.votes}</td>
                    <td>{g.score != null ? `${n1(g.score)}${g.grade ? ` · ${t(`efx.grade.${g.grade}`)}` : ''}` : '—'}
                      {g.minute != null && <small className="muted"> {t('comm.at', { time: mmss(g.minute) })}</small>}</td>
                    <td><button className="small" onClick={() => openGuide(g)}>{t('comm.open')}</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      </section>
    </div>
  );
}
