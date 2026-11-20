import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { ChampionAvatar } from './ChampionAvatar.jsx';
import { SpellRuneIcons } from './LivePlayerEditor.jsx';
import { LikelyLane } from './PickLanes.jsx';
import { findRune, findSpell, runeIcon, spellIcon } from '../runes.js';
import { LANES, applyLikely, assignLane, teamKey, updatePlayer } from '../liveGame.js';
import { laneDistBySlot } from '../useDraft.js';

const pct = (v) => `${Math.round((v ?? 0) * 100)}%`;

/** Two spells and a keystone as icons. */
function Loadout({ spells = [], keystone }) {
  const r = keystone ? findRune(keystone) : null;
  return (
    <span className="ld-icons">
      {spells.map((s) => {
        const sp = findSpell(s);
        return sp ? <img key={s} src={spellIcon(sp)} alt={s} title={s} width={22} height={22} /> : <small key={s}>{s}</small>;
      })}
      {r ? <img src={runeIcon(r)} alt={keystone} title={keystone} width={22} height={22} /> : keystone ? <small>{keystone}</small> : null}
    </span>
  );
}

/**
 * Rodada 20.b, Parte 4: an ally's recommended spells and keystone even without the lane fixed: the best for each likely
 * lane, the one weighted by the lanes' chances and the most robust (POST /api/draft/carregamento). "Aplicar" fills the
 * card (the keystone with the champion's standard page when it is its own).
 */
function LoadoutRec({ game, side, index, onApply }) {
  const p = game[teamKey(side)].players[index];
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const key = JSON.stringify([p.champion, p.lane, game[teamKey(side)].players.map((x) => [x.champion, x.lane, x.lanes])]);
  useEffect(() => {
    let live = true;
    setError(null);
    api.post('/api/draft/carregamento', { game, side, index })
      .then((d) => live && setData(d))
      .catch((e) => live && setError(e.message));
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  if (error) return <small className="notice error">{error}</small>;
  if (!data) return <small className="muted">{t('stage.loading')}</small>;
  const apply = (x) => onApply(x, data);
  const row = (label, x, title) => (
    <li key={label} className="ld-rec" title={title}>
      <span className="ld-rec-label">{label}</span>
      <Loadout spells={x.spells} keystone={x.keystone} />
      <button type="button" onClick={() => apply(x)}>{t('stage.apply')}</button>
    </li>
  );
  return (
    <ul className="ld-recs">
      {data.byLane.length > 1 && row(t('stage.weighted'), data.weighted, t('stage.weightedHelp'))}
      {data.byLane.length > 1 && row(t('stage.robust'), data.robust, t('stage.robustHelp'))}
      {data.byLane.map((x) => row(`${x.lane} ${pct(x.probability)}`, x, t('stage.byLaneHelp')))}
    </ul>
  );
}

/**
 * Rodada 20.b, Parte 4: the loading screen. Each team's lanes are fixed by tapping a champion and then a lane (or
 * dragging it onto the lane); the likely assignment comes ready. Spells and runes per card, the voice at hand, and for
 * the ally the recommended spells and keystone even before the lane is fixed. What is left blank can be filled in the
 * match.
 */
export default function LoadingStage({ game, draftData, ally, enemy, onChange, onEdit, voice }) {
  const [held, setHeld] = useState(null); // {side, index}: the champion tapped, waiting for a lane
  const [recFor, setRecFor] = useState(null); // the ally card whose recommendation is open
  const place = (side, index, lane) => {
    onChange(assignLane(game, side, index, lane));
    setHeld(null);
  };
  const team = (side) => {
    const tm = game[teamKey(side)];
    const dist = laneDistBySlot(draftData, side);
    const loose = tm.players.map((p, i) => ({ p, i })).filter(({ p }) => p.champion && !p.lane);
    const onDrop = (lane) => (e) => {
      e.preventDefault();
      const [s, i] = String(e.dataTransfer.getData('text/plain')).split(':');
      if (s === side) place(side, Number(i), lane);
    };
    return (
      <section key={side} className={`live-team ld-team ${side}${side === ally ? ' ally' : ' enemy'}`} aria-label={t(`live.side.${side}`)}>
        <header className="live-team-head">
          <strong>{tm.name || t(side === ally ? 'live.allyTeam' : 'live.enemyTeam')}</strong>
          <small className={`live-side-tag ${side}`}>{t(`live.side.${side}`)}</small>
          <button type="button" className="ld-likely" onClick={() => onChange(applyLikely(game, side, draftData?.lanes?.[side]))}
            disabled={!loose.length || !draftData} title={t('stage.likelyHelp')}>{t('stage.likely')}</button>
        </header>
        {loose.length > 0 && (
          <div className="ld-loose" aria-label={t('stage.noLane')}>
            <small className="muted">{t('stage.noLane')}</small>
            {loose.map(({ p, i }) => (
              <button key={i} type="button" draggable className={`ld-chip${held?.side === side && held?.index === i ? ' held' : ''}`}
                onDragStart={(e) => e.dataTransfer.setData('text/plain', `${side}:${i}`)}
                onClick={() => setHeld(held?.side === side && held?.index === i ? null : { side, index: i })}>
                <ChampionAvatar name={p.champion} size={28} /> {p.champion}
                <small><LikelyLane player={p} dist={dist[i]?.lanes} unsureBelow={draftData?.unsureBelow} /></small>
              </button>
            ))}
          </div>
        )}
        <ul className="ld-lanes">
          {LANES.map((lane) => {
            const i = tm.players.findIndex((p) => p.lane === lane);
            const p = i >= 0 ? tm.players[i] : null;
            const isHeld = p && held?.side === side && held?.index === i;
            return (
              <li key={lane} className={`ld-lane${held?.side === side && !isHeld ? ' target' : ''}`}
                onDragOver={(e) => e.preventDefault()} onDrop={onDrop(lane)}>
                <button type="button" className="ld-lane-name" onClick={() => held?.side === side && place(side, held.index, lane)}
                  disabled={held?.side !== side}>{lane}</button>
                {p ? (
                  <>
                    <button type="button" draggable className={`ld-chip${isHeld ? ' held' : ''}`}
                      onDragStart={(e) => e.dataTransfer.setData('text/plain', `${side}:${i}`)}
                      onClick={() => (held?.side === side && !isHeld ? place(side, held.index, lane) : setHeld(isHeld ? null : { side, index: i }))}>
                      <ChampionAvatar name={p.champion} size={32} /> <span className="ld-chip-name">{p.champion}</span>
                    </button>
                    <span className="ld-setup">
                      <button type="button" className="ld-sr" onClick={() => onEdit(side, i, 'feiticos')} title={t('stage.spellsRunes')}>
                        {(p.spells ?? []).length || p.runes?.keystone ? <SpellRuneIcons player={p} size={22} /> : t('stage.spellsRunes')}
                      </button>
                      {side === ally && (
                        <button type="button" className={recFor === i ? 'active' : ''} onClick={() => setRecFor(recFor === i ? null : i)}>{t('stage.rec')}</button>
                      )}
                    </span>
                  </>
                ) : (
                  <span className="muted small ld-empty">{held?.side === side ? t('stage.tapToPlace') : '—'}</span>
                )}
              </li>
            );
          })}
        </ul>
        {held?.side === side && game[teamKey(side)].players[held.index]?.lane && (
          <button type="button" onClick={() => place(side, held.index, null)}>{t('stage.unfix')}</button>
        )}
        {side === ally && loose.map(({ i }) => (
          <div key={`r${i}`} className="ld-loose-rec">
            <button type="button" className={recFor === i ? 'active' : ''} onClick={() => setRecFor(recFor === i ? null : i)}>
              {t('stage.recFor', { name: tm.players[i].champion })}
            </button>
          </div>
        ))}
        {side === ally && recFor != null && tm.players[recFor]?.champion && (
          <div className="ld-rec-box">
            <strong className="small">{t('stage.recTitle', { name: tm.players[recFor].champion })}</strong>
            <LoadoutRec game={game} side={side} index={recFor} onApply={(x, data) => onChange(updatePlayer(game, side, recFor, (pl) => {
              pl.spells = [...x.spells];
              const own = data.standardPage && data.standardPage.keystone === x.keystone;
              pl.runes = own
                ? { keystone: x.keystone, primary: data.standardPage.primary, secondary: data.standardPage.secondary,
                  primaryRunes: [...(data.standardPage.primaryRunes ?? [])], secondaryRune: data.standardPage.secondaryRune }
                : { ...(pl.runes ?? {}), keystone: x.keystone };
            }))} />
          </div>
        )}
      </section>
    );
  };
  return (
    <div className="ld-stage">
      <p className="muted small">{t('stage.loadingHelp')}</p>
      {voice}
      <div className="live-teams">
        {team(ally)}
        {team(enemy)}
      </div>
    </div>
  );
}
