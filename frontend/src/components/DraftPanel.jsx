import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { ChampionAvatar } from './ChampionAvatar.jsx';
import { clone, teamKey } from '../liveGame.js';

const n0 = (v) => (v == null ? '—' : Math.round(v).toLocaleString('pt-BR'));

/** One reason of a note, in words. */
export function reasonText(r) {
  const v = r.vars ?? {};
  const pred = v.predicted ? t('draft.predicted') : '';
  const src = v.measured === false ? t('draft.byProfile') : v.measured ? t('draft.measured') : '';
  return t(`draft.r.${r.key}`, { ...v, need: v.need ? t(`draft.need.${v.need}`) : '', pred, src });
}

/** A candidate: portrait, name, lane, note and its reasons in one line; the parts of the note in the title. */
function Candidate({ s, action, actionLabel, onAction }) {
  const parts = Object.entries(s.parts ?? {}).map(([k, v]) => `${t(`draft.part.${k}`)} ${v >= 0 ? '+' : ''}${(Math.round(v * 100) / 100).toLocaleString('pt-BR')}`).join(' · ');
  return (
    <li className="draft-cand" title={parts}>
      <ChampionAvatar name={s.champion} size={36} />
      <span className="draft-cand-text">
        <strong>{s.champion}</strong> <small className="muted">{s.lane}</small> <span className="draft-score">{n0(s.score)}</span>
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
export default function DraftPanel({ game, onChange }) {
  const [data, setData] = useState(null);
  const [path, setPath] = useState([]);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [howOpen, setHowOpen] = useState(false);
  // what the lists depend on: picks, lanes, bans, predictions, Fearless, sides, mode
  const key = useMemo(() => JSON.stringify({
    b: game.blue.players.map((p) => [p.champion, p.lane]), r: game.red.players.map((p) => [p.champion, p.lane]),
    bb: game.blue.bans, rb: game.red.bans, p: game.predictions, f: [game.fearless, game.fearlessScope, game.series], a: game.allySide, m: game.mode, e: game.elo,
  }), [game]);
  useEffect(() => {
    let live = true;
    setBusy(true);
    const h = setTimeout(() => {
      api.post('/api/draft/sugestoes', { game, path })
        .then((d) => { if (live) { setData(d); setError(null); } })
        .catch((e) => live && setError(e.message))
        .finally(() => live && setBusy(false));
    }, 250);
    return () => { live = false; clearTimeout(h); };
    // the game's other fields (items, clock, events) do not change the draft
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, JSON.stringify(path)]);

  const ally = data?.ally ?? game.allySide;
  const enemy = data?.enemy;
  /** Fixes a predicted enemy pick on the empty card of its lane (else the first empty one). */
  const fix = (s) => {
    const g = clone(game);
    const team = g[teamKey(enemy)].players;
    let i = team.findIndex((p) => !p.champion && p.lane === s.lane && !g.predictions?.[`${enemy}:${team.indexOf(p)}`]);
    if (i < 0) i = team.findIndex((p, k) => !p.champion && !g.predictions?.[`${enemy}:${k}`]);
    if (i < 0) return;
    g.predictions = { ...(g.predictions ?? {}), [`${enemy}:${i}`]: s.champion };
    if (team[i].lane !== s.lane) {
      const other = team.findIndex((p) => p.lane === s.lane);
      if (other >= 0 && !team[other].champion) team[other].lane = team[i].lane;
      team[i].lane = s.lane;
    }
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
                onAction={(x) => setPath([...path, { side: ally, champion: x.champion, lane: x.lane, predicted: true }])} />
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
              {p.champion} ({p.lane})
            </button>
          ))}
        </div>
        {node?.side ? (
          <>
            <small className="muted">{t('draft.treeNext', { side: t(`live.side.${node.side}`) })}</small>
            <ul className="draft-list">
              {node.children.map((s) => (
                <Candidate key={s.champion} s={s} action="▸" actionLabel={t('draft.explore')}
                  onAction={(x) => setPath([...path, { side: node.side, champion: x.champion, lane: x.lane, predicted: true }])} />
              ))}
            </ul>
          </>
        ) : <small className="muted">{t('draft.treeEnd')}</small>}
      </div>
      <details open={howOpen} onToggle={(e) => setHowOpen(e.target.open)}>
        <summary>{t('draft.how')}</summary>
        <p className="small">{t('draft.howText')}</p>
        {data?.weights && (
          <p className="small">{['meta', 'lane', 'matchup', 'composition', 'counter'].map((k) => `${t(`draft.w.${k}`)} ${data.weights[k]}`).join(' · ')}</p>
        )}
        <p className="small muted">
          {data?.calibrationInfo?.amostras
            ? t(data.calibrationApplied ? 'draft.calibrated' : 'draft.calibratedWeak', { n: n0(data.calibrationInfo.amostras), r: (Math.round((data.calibrationInfo.correlacao_validada ?? 0) * 100) / 100).toLocaleString('pt-BR') })
            : t('draft.notCalibrated')}
        </p>
      </details>
    </section>
  );
}
