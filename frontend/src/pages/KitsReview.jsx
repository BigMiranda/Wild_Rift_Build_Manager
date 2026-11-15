import { Fragment, useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { n0 } from '../format.js';

const SLOTS = ['1', '2', '3', 'R'];

/** A sheet value in one line: lists by rank as a/b/c, maps as key: value, without quotes. */
function show(v) {
  if (v == null) return '';
  if (Array.isArray(v)) return v.every((x) => typeof x !== 'object') ? v.join('/') : v.map(show).join('; ');
  if (typeof v === 'object') return Object.entries(v).map(([k, x]) => `${k}: ${show(x)}`).join(', ');
  return String(v);
}

/** The fields of an ability that are not shown in their own column. */
const MAIN = ['nome', 'forma', 'recarga', 'dano', 'efeitos', 'nota', 'fontes'];

function Ability({ slot, a }) {
  const rest = Object.entries(a).filter(([k]) => !MAIN.includes(k));
  const est = String(a.fontes ?? '').includes('EST');
  return (
    <tr>
      <td><b>{slot}</b></td>
      <td>{a.nome}<div className="muted small">{a.forma}</div></td>
      <td>{show(a.recarga)}</td>
      <td className="small">{show(a.dano)}</td>
      <td className="small">{show(a.efeitos)}</td>
      <td className="small">{rest.map(([k, v]) => <div key={k}><span className="muted">{k}</span> {show(v)}</div>)}</td>
      <td className="small">{a.nota}<div className={est ? 'warn' : 'muted'}>{a.fontes}</div></td>
    </tr>
  );
}

function Duel({ champion }) {
  const [rival, setRival] = useState('Ornn');
  const [res, setRes] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const run = () => {
    setBusy(true);
    setError(null);
    api.get(`/api/arena/kits/duel?champion=${encodeURIComponent(champion)}&rival=${encodeURIComponent(rival)}`)
      .then(setRes).catch((e) => setError(e.message)).finally(() => setBusy(false));
  };
  return (
    <div className="kits-duel">
      <div className="row">
        <span>{t('kits.duel.vs')}</span>
        <input value={rival} onChange={(e) => setRival(e.target.value)} style={{ width: 140 }} />
        <button onClick={run} disabled={busy}>{busy ? t('kits.duel.running') : t('kits.duel.run')}</button>
        <span className="muted small">{t('kits.duel.hint')}</span>
      </div>
      {error && <div className="error">{error}</div>}
      {res && (
        <div className="row" style={{ alignItems: 'flex-start', gap: 24 }}>
          {res.sides.map((s) => (
            <div key={s.champion}>
              <b>{s.champion}</b> — {t('kits.duel.dealt')} {n0(s.dealt)} · {t('kits.duel.taken')} {n0(s.taken)} · {t('kits.duel.health')} {n0(s.health)}/{n0(s.maxHealth)}
              {s.deathTime != null && <span className="warn"> · {t('kits.duel.died', { s: s.deathTime.toFixed(1) })}</span>}
              <table className="table small">
                <tbody>
                  {Object.entries(s.bySource ?? {}).sort((a, b) => b[1] - a[1]).map(([k, v]) => (
                    <tr key={k}><td>{k}</td><td style={{ textAlign: 'right' }}>{n0(v)}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/** Admin › Fichas (Rodada 18): every Arena sheet, its gaps, estimated numbers and validator problems, and a test duel. */
export default function KitsReview() {
  const [rows, setRows] = useState(null);
  const [error, setError] = useState(null);
  const [filter, setFilter] = useState({ text: '', quality: '', only: '' });
  const [open, setOpen] = useState(null);
  const load = () => api.get('/api/arena/kits/review').then(setRows).catch((e) => setError(e.message));
  useEffect(() => {
    load();
  }, []);
  const shown = useMemo(() => (rows ?? []).filter((r) => {
    if (filter.text && !r.champion.toLowerCase().includes(filter.text.toLowerCase())) return false;
    if (filter.quality && r.quality !== filter.quality) return false;
    if (filter.only === 'problems' && !r.problems.length) return false;
    if (filter.only === 'gaps' && !r.gaps.length) return false;
    if (filter.only === 'est' && !r.estimated) return false;
    return true;
  }), [rows, filter]);
  const qualities = useMemo(() => [...new Set((rows ?? []).map((r) => r.quality).filter(Boolean))].sort(), [rows]);
  const count = (q) => (rows ?? []).filter((r) => r.quality === q).length;

  return (
    <div className="main">
      <h2>{t('kits.title')}</h2>
      <p className="muted">{t('kits.intro')}</p>
      {error && <div className="error">{error}</div>}
      {rows && (
        <p>
          {t('kits.summary', { n: rows.length })}{' '}
          {qualities.map((q) => <span key={q} className="chip" style={{ marginRight: 10 }}>{t('kits.quality')} {q}: {count(q)}</span>)}{' '}
          <span className="chip" style={{ marginRight: 10 }}>{t('kits.withProblems')}: {rows.filter((r) => r.problems.length).length}</span>
        </p>
      )}
      <div className="row">
        <input placeholder={t('kits.search')} value={filter.text} onChange={(e) => setFilter({ ...filter, text: e.target.value })} />
        <select value={filter.quality} onChange={(e) => setFilter({ ...filter, quality: e.target.value })}>
          <option value="">{t('kits.allQualities')}</option>
          {qualities.map((q) => <option key={q} value={q}>{t('kits.quality')} {q}</option>)}
        </select>
        <select value={filter.only} onChange={(e) => setFilter({ ...filter, only: e.target.value })}>
          <option value="">{t('kits.all')}</option>
          <option value="problems">{t('kits.onlyProblems')}</option>
          <option value="gaps">{t('kits.onlyGaps')}</option>
          <option value="est">{t('kits.onlyEst')}</option>
        </select>
        <button onClick={load}>{t('kits.reload')}</button>
      </div>
      <div className="table-scroll">
        <table className="table">
          <thead>
            <tr>
              <th>{t('kits.col.champion')}</th><th>{t('kits.quality')}</th><th>{t('kits.col.abilities')}</th><th>EST</th>
              <th>{t('kits.col.gaps')}</th><th>{t('kits.col.problems')}</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((r) => (
              <Fragment key={r.champion}>
                <tr onClick={() => setOpen(open === r.champion ? null : r.champion)} style={{ cursor: 'pointer' }}
                  className={open === r.champion ? 'selected' : ''}>
                  <td><b>{r.champion}</b></td>
                  <td>{r.quality ?? '—'}</td>
                  <td>{r.abilities}</td>
                  <td className={r.estimated ? 'warn' : ''}>{r.estimated}</td>
                  <td className="small">{r.gaps.join(', ')}</td>
                  <td className={r.problems.length ? 'error small' : 'muted small'}>{r.problems.length ? r.problems.join('; ') : 'ok'}</td>
                </tr>
                {open === r.champion && (
                  <tr>
                    <td colSpan={6}>
                      {r.note && <p><span className="muted">{t('kits.note')}</span> {r.note}</p>}
                      <p className="small">
                        {['raio', 'alcance', 'espera_ataque', 'velocidade_projetil', 'mobilidade', 'recurso', 'formas'].filter((k) => r.kit[k] != null)
                          .map((k) => <span key={k} style={{ marginRight: 14 }}><span className="muted">{k}</span> {show(r.kit[k])}</span>)}
                      </p>
                      <table className="table">
                        <thead>
                          <tr><th /><th>{t('kits.col.ability')}</th><th>{t('kits.col.cooldown')}</th><th>{t('kits.col.damage')}</th><th>{t('kits.col.effects')}</th><th>{t('kits.col.fields')}</th><th>{t('kits.col.sources')}</th></tr>
                        </thead>
                        <tbody>
                          {SLOTS.filter((s) => r.kit.habilidades?.[s]).map((s) => <Ability key={s} slot={s} a={r.kit.habilidades[s]} />)}
                          {SLOTS.filter((s) => !r.kit.habilidades?.[s]).map((s) => (
                            <tr key={s}><td><b>{s}</b></td><td colSpan={6} className="warn">{t('kits.missing')}</td></tr>
                          ))}
                        </tbody>
                      </table>
                      <Duel champion={r.champion} />
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
