import { useState } from 'react';
import { api } from '../api.js';
import { currentLang, t } from '../i18n.js';
import { ChampionAvatar } from './ChampionAvatar.jsx';
import { clone, localVars, teamKey } from '../liveGame.js';
import { useDraftSuggestions } from '../useDraft.js';
import { LANE_SHORT } from './PickLanes.jsx';

const n0 = (v) => (v == null ? '—' : Math.round(v).toLocaleString('pt-BR'));

/** One reason of a note, in words. */
export function reasonText(r) {
  const v = localVars(r.vars);
  const pred = v.predicted ? t('draft.predicted') : '';
  const src = v.measured === false ? t('draft.byProfile') : v.measured ? t('draft.measured') : '';
  const mechName = v.mech ? t(`draft.mech.${v.mech}`) : '';
  return t(`draft.r.${r.key}`, { ...v, need: v.need ? t(`draft.need.${v.need}`) : '', pred, src, mechName: mechName.startsWith('draft.mech.') ? v.mech : mechName });
}

/** Rodada 20.b: a candidate's likely lanes in short ("Bar 70% · Mei 30%"); one lane alone without the chance. */
const lanesText = (s) => {
  const e = Object.entries(s.lanes ?? {}).sort((a, b) => b[1] - a[1]).filter(([, v]) => v >= 0.1);
  if (e.length <= 1) return s.lane;
  return e.map(([l, v]) => `${LANE_SHORT[l] ?? l} ${Math.round(v * 100)}%`).join(' · ');
};

/** A candidate: portrait, name, lane, note and its reasons in one line; the parts of the note in the title. */
export function Candidate({ s, action, actionLabel, onAction }) {
  const parts = Object.entries(s.parts ?? {}).map(([k, v]) => `${t(`draft.part.${k}`)} ${v >= 0 ? '+' : ''}${(Math.round(v * 100) / 100).toLocaleString('pt-BR')}`).join(' · ');
  return (
    <li className="draft-cand" title={parts}>
      <ChampionAvatar name={s.champion} size={36} />
      <span className="draft-cand-text">
        <strong>{s.champion}</strong> <small className="muted">{lanesText(s)}</small> <span className="draft-score">{n0(s.score)}</span>
        <small className="draft-why">{(s.reasons ?? []).map(reasonText).join('; ')}</small>
      </span>
      {action && <button type="button" className="draft-act" onClick={() => onAction(s)} title={actionLabel}>{action}</button>}
    </li>
  );
}

/**
 * Rodada 20, Parte 3: the draft. For the next pick of each side, the enemy's 5 most likely (prediction) and the ally's
 * 5 best (recommendation), each with its reason. A prediction can be fixed on an empty enemy card ("acho que vão de
 * Darius"): both lists are recalculated as if it were true. The tree of scenarios (beam 5) opens one branch at a tap.
 */
export default function DraftPanel({ game, onChange, root }) {
  const [path, setPath] = useState([]);
  const [howOpen, setHowOpen] = useState(false);
  // Rodada 20.b: the page already asks for the lists of the board as it is (root); only a branch asks again
  const branch = useDraftSuggestions(game, path, path.length > 0 || !root);
  const cur = path.length > 0 || !root ? branch : root;
  const { data, busy, error } = cur;

  const ally = data?.ally ?? game.allySide;
  const enemy = data?.enemy;
  /** Fixes a predicted enemy pick on its next empty card (Rodada 20.b: cards are picks in order; its lanes stay open). */
  const fix = (s) => {
    const g = clone(game);
    const team = g[teamKey(enemy)].players;
    const i = team.findIndex((p, k) => !p.champion && !g.predictions?.[`${enemy}:${k}`]);
    if (i < 0) return;
    g.predictions = { ...(g.predictions ?? {}), [`${enemy}:${i}`]: s.champion };
    onChange(g);
  };
  const unfix = (k) => {
    const g = clone(game);
    delete g.predictions[k];
    onChange(g);
  };
  const fixed = Object.entries(game.predictions ?? {}).filter(([, v]) => v);
  const next = data?.next;
  const node = data?.node;

  return (
    <section className="panel draft-panel">
      <div className="spread">
        <h4>{t('draft.title')}</h4>
        <small className="muted">{busy ? t('draft.busy') : ''}</small>
      </div>
      <p className="small">
        {next ? t('draft.next', { action: t(`draft.action.${next.action}`), side: t(`live.side.${next.side}`), n: next.index + 1 }) : t('draft.done')}
        {' · '}
        {data?.meta ? t('draft.meta', { date: data.meta.data }) : t('draft.noMeta')}
        {' · '}
        {data?.measuredRows ? t('draft.tables', { n: n0(data.measuredRows) }) : t('draft.noTables')}
        {data && !data.mechanics ? ` · ${t('draft.mechPending')}` : ''}
      </p>
      {error && <div className="notice error">{error}</div>}
      {fixed.length > 0 && (
        <div className="chips draft-fixed">
          <span className="muted small">{t('draft.fixed')}</span>
          {fixed.map(([k, v]) => <button key={k} type="button" className="chip-item" onClick={() => unfix(k)}><ChampionAvatar name={v} size={18} /> {v}? ✕</button>)}
        </div>
      )}
      <div className="draft-lists">
        <div>
          <h5>{t('draft.predictions', { side: t(`live.side.${enemy ?? 'vermelho'}`) })}</h5>
          <ul className="draft-list">
            {(data?.predictions ?? []).map((s) => <Candidate key={s.champion} s={s} action="📌" actionLabel={t('draft.fix')} onAction={fix} />)}
          </ul>
        </div>
        <div>
          <h5>{t('draft.recommendations', { side: t(`live.side.${ally}`) })}</h5>
          <ul className="draft-list">
            {(data?.recommendations ?? []).map((s) => (
              <Candidate key={s.champion} s={s} action="▸" actionLabel={t('draft.explore')}
                onAction={(x) => setPath([...path, { side: ally, champion: x.champion, lane: null, predicted: true }])} />
            ))}
          </ul>
        </div>
      </div>
      <details className="draft-bans">
        <summary>{t('draft.bans')}</summary>
        <ul className="draft-list">{(data?.bans ?? []).map((s) => <Candidate key={s.champion} s={s} />)}</ul>
      </details>
      <div className="draft-tree">
        <h5>{t('draft.tree')}</h5>
        <p className="muted small">{t('draft.treeHelp')}</p>
        <div className="chips">
          <button type="button" className={path.length === 0 ? 'active' : ''} onClick={() => setPath([])}>{t('draft.treeRoot')}</button>
          {path.map((p, k) => (
            <button key={k} type="button" className={`live-side-tag ${p.side}`} onClick={() => setPath(path.slice(0, k + 1))}>
              {p.champion}
            </button>
          ))}
        </div>
        {node?.side ? (
          <>
            <small className="muted">{t('draft.treeNext', { side: t(`live.side.${node.side}`) })}</small>
            <ul className="draft-list">
              {node.children.map((s) => (
                <Candidate key={s.champion} s={s} action="▸" actionLabel={t('draft.explore')}
                  onAction={(x) => setPath([...path, { side: node.side, champion: x.champion, lane: null, predicted: true }])} />
              ))}
            </ul>
          </>
        ) : <small className="muted">{t('draft.treeEnd')}</small>}
      </div>
      <details open={howOpen} onToggle={(e) => setHowOpen(e.target.open)}>
        <summary>{t('draft.how')}</summary>
        <p className="small">{t('draft.howText')}</p>
        {data?.weights && (
          <p className="small">{['meta', 'lane', 'matchup', 'composition', 'counter', 'flex'].map((k) => `${t(`draft.w.${k}`)} ${data.weights[k]}`).join(' · ')}</p>
        )}
        <p className="small muted">
          {data?.calibrationInfo?.amostras
            ? t(data.calibrationInfo.partida
              ? (data.calibrationApplied ? 'draft.calibratedMatch' : 'draft.calibratedMatchWeak')
              : (data.calibrationApplied ? 'draft.calibrated' : 'draft.calibratedWeak'), { n: n0(data.calibrationInfo.amostras), r: (Math.round((data.calibrationInfo.correlacao_validada ?? 0) * 100) / 100).toLocaleString('pt-BR') })
            : t('draft.notCalibrated')}
        </p>
        <MechanicsTable />
      </details>
    </section>
  );
}

/**
 * Rodada 20.b, Parte 3: the mechanics that punish (contra_mecanicas_7_3f.yml), for the user to check: each vulnerability,
 * what punishes it and its weight, and the champions that have it. Loaded on demand (GET /api/draft/mecanicas).
 */
function MechanicsTable() {
  const [info, setInfo] = useState(null);
  const [error, setError] = useState(null);
  const en = currentLang() === 'en';
  if (!info) {
    return (
      <div className="draft-mech">
        <strong className="small">{t('draft.mech.title')}</strong>{' '}
        <button type="button" onClick={() => api.get('/api/draft/mecanicas').then(setInfo).catch((e) => setError(e.message))}>{t('draft.mech.load')}</button>
        {error && <div className="notice error">{error}</div>}
      </div>
    );
  }
  const champs = Object.entries(info.campeoes ?? {});
  return (
    <div className="draft-mech">
      <strong className="small">{t('draft.mech.title')}</strong>
      <p className="muted small">{t('draft.mech.help')}</p>
      <div className="table-wrap">
        <table className="small">
          <thead><tr><th>{t('draft.mech.vuln')}</th><th>{t('draft.mech.punishedBy')}</th><th>{t('draft.mech.weight')}</th><th>{t('draft.mech.champs')}</th></tr></thead>
          <tbody>
            {Object.values(info.vulnerabilidades ?? {}).map((v) => {
              const who = champs.filter(([, x]) => x.vulnerabilities.includes(v.key)).map(([n]) => n);
              const by = v.punishedBy.map((p) => {
                const pn = info.punicoes?.[p];
                const n = champs.filter(([, x]) => x.punishments.includes(p)).length;
                return `${(en ? pn?.nome_en : pn?.nome) ?? p} (${n})`;
              });
              return (
                <tr key={v.key}>
                  <td>{en ? v.nameEn : v.name}</td>
                  <td>{by.join('; ')}</td>
                  <td>{v.weight}</td>
                  <td title={who.join(', ')}>{who.length}: {who.slice(0, 12).join(', ')}{who.length > 12 ? '…' : ''}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
