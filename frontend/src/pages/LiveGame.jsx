import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { useBuild } from '../buildContext.jsx';
import { useRoute } from '../router.js';
import { loadChampions } from '../components/ChampionPicker.jsx';
import { ChampionAvatar } from '../components/ChampionAvatar.jsx';
import ItemIcon from '../components/ItemIcon.jsx';
import LivePlayerEditor, { ChampionGrid, SpellRuneIcons } from '../components/LivePlayerEditor.jsx';
import {
  BLUE, NO_BAN, RED, STAGES, addEvent, applyLikely, bannedChampions, cannotBan, clockSeconds, clone, emptyGame, enemyOf, formatOf,
  mmssOf, parseClock, pushHistory, seriesBanned, setClock, stageOf, teamKey, toggleClock, updatePlayer,
} from '../liveGame.js';
import LivePanels from '../components/LivePanels.jsx';
import VoiceBar from '../components/VoiceBar.jsx';
import PickLanes, { LikelyLane } from '../components/PickLanes.jsx';
import DraftSteps from '../components/DraftSteps.jsx';
import DraftPanel from '../components/DraftPanel.jsx';
import LoadingStage from '../components/LoadingStage.jsx';
import { laneDistBySlot, useDraftSuggestions } from '../useDraft.js';

/**
 * Rodada 20: Simulações › Draft e partida. For whoever WATCHES a match (replay, stream, championship, coaching): the
 * tool only uses what the user tells it (clicks, voice); nothing reads the game. One match (or a Fearless series)
 * from the draft to the end: two columns of five cards, a clock the user sets, quick actions and an always visible undo.
 *
 * Rodada 20.b, Parte 4: three stages with a step bar on top and "Próxima etapa" at the end of each: Draft (the bans and
 * picks in the format's order, the lists of the turn, the possible lanes of each pick; "Pular draft"), Carregamento
 * (fix each lane, spells and runes, the voice at hand, the ally's recommended loadout) and Partida (items, events, the
 * first encounter, likely builds and tips). Going back is always possible and nothing is lost: the stage is only a view.
 */
export default function LiveGame() {
  const { items } = useBuild();
  const [list, setList] = useState([]);
  const [game, setGame] = useState(null);
  const [history, setHistory] = useState([]);
  const [formats, setFormats] = useState(null);
  const [champions, setChampions] = useState([]);
  const [profiles, setProfiles] = useState([]);
  const [selected, setSelected] = useState(null); // {side, index}
  const [editing, setEditing] = useState(null); // {side, index, tab}
  const [banning, setBanning] = useState(null); // {side, index}
  const [objective, setObjective] = useState(null); // dragao | arauto | barao
  const [seriesAdd, setSeriesAdd] = useState(null); // the series game a champion is added to (by hand)
  const [clockEdit, setClockEdit] = useState(null);
  const [error, setError] = useState(null);
  const [saved, setSaved] = useState(true);
  const [, tick] = useState(0);
  const saveTimer = useRef(null);
  const route = useRoute();
  const [panel, setPanel] = useState(null);
  // the match's settings stay folded once the match has champions (the cards and the clock come first on a phone)
  const [setupOpen, setSetupOpen] = useState(true);
  // Rodada 20.b: the draft's lists and the lanes' distribution of the board as it is (the cards show the likely lane)
  const draft = useDraftSuggestions(game, [], !!game);

  useEffect(() => {
    loadChampions().then(setChampions).catch(() => {});
    api.get('/api/champions/profiles').then(setProfiles).catch(() => {});
    api.get('/api/draft/formatos').then(setFormats).catch((e) => setError(e.message));
    api.get('/api/draft/jogos').then(setList).catch(() => {});
  }, []);
  // a link opens a game directly: #/simulacoes/draft?jogo=3&sel=azul:0&painel=encontro
  useEffect(() => {
    const id = route.params?.jogo;
    if (!id || game?.id === Number(id)) return;
    open(Number(id)).then(() => {
      const [side, i] = String(route.params.sel ?? '').split(':');
      if (side && i != null) setSelected({ side, index: Number(i) });
      if (route.params.painel) setPanel(route.params.painel);
    });
    // only when the link changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [route.params?.jogo]);
  // the clock ticks on screen while running
  useEffect(() => {
    if (!game || game.clock?.paused) return undefined;
    const h = setInterval(() => tick((x) => x + 1), 1000);
    return () => clearInterval(h);
  }, [game?.clock?.paused, game?.clock?.setAt]);

  const lanesByName = useMemo(() => new Map(profiles.map((p) => [p.name, p.lanes])), [profiles]);
  const lanesOf = useCallback((name) => lanesByName.get(name), [lanesByName]);
  /** Rodada 20.b: the lanes a pick of a champion is prefilled with (the seed's and the meta's). */
  const possibleOf = useCallback((name) => formats?.possibleLanes?.[name] ?? lanesByName.get(name) ?? [], [formats, lanesByName]);

  /** Every change: the previous state goes to the undo history and the game is saved a moment later. */
  const change = (next) => {
    if (game) setHistory((h) => pushHistory(h, game));
    setGame(next);
    setSaved(false);
  };
  useEffect(() => {
    if (!game || saved) return undefined;
    clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(async () => {
      try {
        const out = game.id ? await api.put(`/api/draft/jogos/${game.id}`, game) : await api.post('/api/draft/jogos', game);
        if (!game.id) setGame((g) => ({ ...g, id: out.id, name: out.name }));
        setSaved(true);
        api.get('/api/draft/jogos').then(setList).catch(() => {});
      } catch (e) {
        setError(e.message);
      }
    }, 700);
    return () => clearTimeout(saveTimer.current);
  }, [game, saved]);

  const undo = () => {
    if (!history.length) return;
    setGame(history[history.length - 1]);
    setHistory((h) => h.slice(0, -1));
    setSaved(false);
  };
  const open = async (id) => {
    setError(null);
    setHistory([]);
    setSelected(null);
    if (id == null) {
      setSetupOpen(true);
      setGame(emptyGame());
      setSaved(false);
      return;
    }
    try {
      const g = await api.get(`/api/draft/jogos/${id}`);
      setGame(g);
      setSetupOpen(![...g.blue.players, ...g.red.players].some((p) => p.champion));
      setSaved(true);
    } catch (e) {
      setError(e.message);
    }
  };
  const remove = async () => {
    if (!game?.id || !window.confirm(t('live.deleteConfirm', { name: game.name }))) return;
    await api.del(`/api/draft/jogos/${game.id}`);
    setGame(null);
    setList(await api.get('/api/draft/jogos'));
  };

  // Rodada 20.b: the Fearless series bans its champions for both teams
  const fearlessSet = useCallback(() => (game ? seriesBanned(game, formats) : new Set()), [game, formats]);
  const unavailableFor = useCallback(() => {
    const out = fearlessSet();
    for (const k of ['blue', 'red']) {
      for (const p of game?.[k]?.players ?? []) if (p.champion) out.add(p.champion);
      for (const b of bannedChampions(game?.[k])) out.add(b);
    }
    return out;
  }, [game, fearlessSet]);
  /** What a ban slot cannot take (DraftCatalog.cannotBan): picked, the series' and, in a championship, the other bans. */
  const unbannableFor = useCallback((side, index) => {
    const out = new Set();
    if (!game) return out;
    for (const c of champions) if (cannotBan(game, formats, side, index, c.nome)) out.add(c.nome);
    return out;
  }, [game, formats, champions]);

  if (!game) {
    return (
      <div className="main live">
        <section className="panel">
          <h3>{t('live.title')}</h3>
          <p className="muted small">{t('live.help')}</p>
          <div className="row">
            <button type="button" className="primary live-big" onClick={() => open(null)}>{t('live.new')}</button>
          </div>
          {error && <div className="notice error">{error}</div>}
          {list.length > 0 && (
            <ul className="live-list">
              {list.map((g) => (
                <li key={g.id}>
                  <button type="button" onClick={() => open(g.id)}>
                    <strong>{g.name}</strong>
                    <small className="muted"> · {formats?.formats?.find((f) => f.key === g.mode)?.name ?? g.mode}</small>
                    <span className="live-list-champs">
                      {g.blue.map((c) => <ChampionAvatar key={`b${c}`} name={c} size={22} />)}
                      <span className="muted"> × </span>
                      {g.red.map((c) => <ChampionAvatar key={`r${c}`} name={c} size={22} />)}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    );
  }

  const ally = game.allySide ?? BLUE;
  const enemy = enemyOf(ally);
  const now = clockSeconds(game.clock);
  const sel = selected ? game[teamKey(selected.side)].players[selected.index] : null;
  const event = (ev) => change(addEvent(game, ev, items));
  const needSel = (fn) => () => (sel ? fn() : setError(t('live.pickFirst')));
  /** Rodada 20.b: a new game "in the same series" (the backend copies the earlier bans plus this game's ten). */
  const nextInSeries = async () => {
    if (!game.id || !window.confirm(t('live.series.nextConfirm', { n: (game.series?.length ?? 0) + 2 }))) return;
    try {
      await api.put(`/api/draft/jogos/${game.id}`, game);
      const out = await api.post(`/api/draft/jogos/${game.id}/proximo`, {});
      setList(await api.get('/api/draft/jogos'));
      await open(out.id);
    } catch (e) {
      setError(e.message);
    }
  };

  const stage = stageOf(game);
  /** Moves to a stage; entering the loading screen the first time fixes the likely assignment where nothing is fixed. */
  const goStage = (k) => {
    let g = { ...clone(game), stage: k };
    if (k === 'carregamento') {
      for (const side of [BLUE, RED]) {
        if (!g[teamKey(side)].players.some((p) => p.lane)) g = applyLikely(g, side, draft.data?.lanes?.[side]);
      }
    }
    change(g);
    window.scrollTo?.({ top: 0 });
  };
  const nextStage = () => goStage(STAGES[Math.min(STAGES.length - 1, STAGES.indexOf(stage) + 1)]);
  /** A step of the draft: a ban opens the ban picker, a pick the champion tab of its card. */
  const openTurn = (tn) => (tn.action === 'ban' ? setBanning({ side: tn.side, index: tn.index }) : setEditing({ side: tn.side, index: tn.index, tab: 'campeao', draft: true }));
  const skipBan = (tn) => {
    const g = clone(game);
    const bans = g[teamKey(tn.side)].bans ?? [];
    while (bans.length <= tn.index) bans.push(null);
    bans[tn.index] = NO_BAN;
    g[teamKey(tn.side)].bans = bans;
    change(g);
  };

  const team = (side) => {
    const tm = game[teamKey(side)];
    const dist = laneDistBySlot(draft.data, side);
    const inDraft = stage === 'draft';
    const bans = formatOf(game, formats)?.turns?.filter((x) => x.action === 'ban' && x.side === side).length ?? 5;
    return (
      <section className={`live-team ${side}${side === ally ? ' ally' : ' enemy'}`} aria-label={t(`live.side.${side}`)}>
        <header className="live-team-head">
          <input className="live-team-name" value={tm.name ?? ''} placeholder={t(side === ally ? 'live.allyTeam' : 'live.enemyTeam')}
            onChange={(e) => change({ ...clone(game), [teamKey(side)]: { ...tm, name: e.target.value } })} />
          <small className={`live-side-tag ${side}`}>{t(`live.side.${side}`)}</small>
          <span className="live-objs" title={t('live.objectives')}>
            {tm.dragons ? `🐉${tm.dragons} ` : ''}{tm.heralds ? `👁${tm.heralds} ` : ''}{tm.barons ? `🟣${tm.barons}` : ''}
          </span>
        </header>
        <div className="live-bans" aria-label={t('live.bans')}>
          {Array.from({ length: bans }, (_, i) => (
            <button key={i} type="button" className="live-ban" title={tm.bans?.[i] ?? t('live.ban')} onClick={() => setBanning({ side, index: i })}>
              {tm.bans?.[i] === NO_BAN ? <small className="muted">{t('live.noBanShort')}</small>
                : tm.bans?.[i] ? <ChampionAvatar name={tm.bans[i]} size={28} /> : <span aria-hidden="true">⊘</span>}
            </button>
          ))}
        </div>
        {tm.players.map((p, i) => {
          const isSel = selected?.side === side && selected?.index === i;
          const pred = !p.champion ? game.predictions?.[`${side}:${i}`] : null;
          return (
            <div key={i} className={`live-card${isSel ? ' selected' : ''}${p.champion ? '' : ' empty'}`}>
              <button type="button" className="live-card-main" aria-pressed={isSel}
                onClick={() => (p.champion && !inDraft ? setSelected(isSel ? null : { side, index: i }) : setEditing({ side, index: i, tab: 'campeao', draft: inDraft }))}>
                <ChampionAvatar name={p.champion ?? pred} size={44} />
                <span className="live-card-text">
                  <strong>{p.champion ?? (pred ? `${pred}?` : t('live.emptySlot'))}</strong>
                  <small className="muted">{t('live.pickN', { n: i + 1 })}{' '}
                    {(p.champion || pred) && <LikelyLane player={p} dist={dist[i]?.lanes} unsureBelow={draft.data?.unsureBelow ?? formats?.unsureBelow} />}
                    {p.level ? ` · ${t('live.lvl', { n: p.level })}` : ''}
                    {p.kills != null || p.deaths != null ? ` · ${p.kills ?? 0}/${p.deaths ?? 0}/${p.assists ?? 0}` : ''}
                    {p.gold ? ` · ${p.gold}g` : ''}</small>
                  <span className="live-card-items">
                    <SpellRuneIcons player={p} size={16} />
                    {(p.items ?? []).map((id, k) => <ItemIcon key={`${id}-${k}`} id={id} size={18} />)}
                    {(p.components ?? []).length > 0 && <small className="muted">+{p.components.length}</small>}
                  </span>
                </span>
              </button>
              <button type="button" className="live-card-edit" aria-label={t('live.edit')} title={t('live.edit')}
                onClick={() => setEditing({ side, index: i })}>✎</button>
              {inDraft && p.champion && !p.lane && (
                <PickLanes compact player={p} possible={possibleOf(p.champion)}
                  onChange={(lanes) => change(updatePlayer(game, side, i, (x) => { x.lanes = lanes; }))} />
              )}
            </div>
          );
        })}
      </section>
    );
  };

  const fmt = formatOf(game, formats);
  return (
    <div className="main live">
      <details className="panel live-setup" open={setupOpen} onToggle={(e) => setSetupOpen(e.currentTarget.open)}>
        <summary className="live-setup-sum">
          <button type="button" onClick={(e) => { e.preventDefault(); setGame(null); setHistory([]); }}>‹ {t('live.games')}</button>
          <strong className="live-setup-name">{game.name ?? t('live.name')}</strong>
          <small className="muted">{fmt?.name ?? game.mode}{game.fearless ? ' · Fearless' : ''} · {t('live.allyIs', { side: t(`live.side.${ally}`) })} · {saved ? t('live.saved') : t('live.saving')}</small>
          <span className="live-setup-gear" aria-hidden="true">⚙</span>
        </summary>
        <div className="row">
          <input className="live-name" value={game.name ?? ''} placeholder={t('live.name')} onChange={(e) => change({ ...clone(game), name: e.target.value })} />
          <select value={game.mode} aria-label={t('live.mode')}
            onChange={(e) => change({ ...clone(game), mode: e.target.value, fearless: !!formats?.formats?.find((f) => f.key === e.target.value)?.fearless })}>
            {(formats?.formats ?? []).map((f) => <option key={f.key} value={f.key}>{f.name}</option>)}
          </select>
          {(draft.data?.meta?.elos ?? []).length > 0 && (
            <select value={game.elo ?? ''} aria-label={t('live.metaElo')} title={t('live.metaEloHelp')}
              onChange={(e) => change({ ...clone(game), elo: e.target.value || null })}>
              <option value="">{t('live.metaEloDefault')}</option>
              {draft.data.meta.elos.map((e) => <option key={e} value={e}>{t('live.metaEloOf', { key: e.replace('fonte_', '') })}</option>)}
            </select>
          )}
          <button type="button" onClick={() => change({ ...clone(game), allySide: enemy })} title={t('live.swapSidesHelp')}>
            {t('live.allyIs', { side: t(`live.side.${ally}`) })} ⇄
          </button>
          {fmt?.fearless && game.id && <button type="button" onClick={nextInSeries}>{t('live.series.next')}</button>}
          {game.id && <button type="button" className="danger" onClick={remove}>{t('live.delete')}</button>}
        </div>
        {fmt?.source && <p className="muted small">{t('live.formatSource', { source: fmt.source })}</p>}
        {fmt && <p className="muted small">{t(fmt.bansRepeat ? 'live.banRule.repeat' : 'live.banRule.visible')}</p>}
      </details>
      {(game.notices ?? []).length > 0 && (
        <div className="notice live-upgrade">
          <strong>{t('live.upgrade.title')}</strong>
          <ul>{game.notices.map((k) => <li key={k}>{t(`live.upgrade.${k}`)}</li>)}</ul>
          <button type="button" onClick={() => setGame({ ...game, notices: [] })}>{t('live.upgrade.ok')}</button>
        </div>
      )}

      <nav className="stage-bar" aria-label={t('stage.bar')}>
        <ol>
          {STAGES.map((k, i) => (
            <li key={k}>
              <button type="button" className={`stage-step${stage === k ? ' active' : ''}${STAGES.indexOf(stage) > i ? ' done' : ''}`}
                aria-current={stage === k ? 'step' : undefined} onClick={() => goStage(k)}>
                <span className="stage-n">{i + 1}</span> {t(`stage.${k}`)}
              </button>
            </li>
          ))}
        </ol>
        <button type="button" className="live-undo" disabled={!history.length} onClick={undo} title={t('live.undo')}>↶ {t('live.undo')}</button>
      </nav>
      {error && <div className="notice error" onClick={() => setError(null)}>{error}</div>}

      {stage === 'draft' && (
        <>
          {fmt?.fearless && <SeriesStrip game={game} onChange={change} onAdd={(k) => setSeriesAdd(k)} />}
          <DraftSteps game={game} fmt={fmt} onTurn={openTurn} onSkipBan={skipBan} />
          <div className="live-teams">
            {team(ally)}
            {team(enemy)}
          </div>
          <DraftPanel game={game} onChange={change} root={draft} />
          <div className="stage-nav">
            <button type="button" onClick={() => goStage('carregamento')} title={t('stage.skipDraftHelp')}>{t('stage.skipDraft')}</button>
            <button type="button" className="primary live-big" onClick={nextStage}>{t('stage.next')} ›</button>
          </div>
        </>
      )}

      {stage === 'carregamento' && (
        <>
          <LoadingStage game={game} draftData={draft.data} ally={ally} enemy={enemy} onChange={change}
            onEdit={(side, index, tab) => setEditing({ side, index, tab })}
            voice={<div className="live-bar ld-voice"><VoiceBar game={game} champions={champions} items={items} onApply={change} onUndo={history.length ? undo : null} /></div>} />
          <div className="stage-nav">
            <button type="button" onClick={() => goStage('draft')}>‹ {t('stage.draft')}</button>
            <button type="button" className="primary live-big" onClick={nextStage}>{t('stage.next')} ›</button>
          </div>
        </>
      )}

      {stage === 'partida' && (
        <>
          <div className="live-bar">
            <div className="live-clock">
              {clockEdit != null ? (
                <form onSubmit={(e) => {
                  e.preventDefault();
                  const sec = parseClock(clockEdit);
                  if (sec != null) change(setClock(game, sec));
                  setClockEdit(null);
                }}>
                  <input autoFocus inputMode="numeric" value={clockEdit} placeholder="7:30" aria-label={t('live.clockSet')}
                    onChange={(e) => setClockEdit(e.target.value)} onBlur={() => setClockEdit(null)} />
                </form>
              ) : (
                <button type="button" className="live-time" title={t('live.clockSet')} onClick={() => setClockEdit('')}>{mmssOf(now)}</button>
              )}
              <button type="button" className="live-play" onClick={() => change(toggleClock(game))} aria-label={game.clock?.paused ? t('live.play') : t('live.pause')}>
                {game.clock?.paused ? '▶' : '⏸'}
              </button>
            </div>
            <div className="live-actions" role="toolbar" aria-label={t('live.quick')}>
              <button type="button" onClick={needSel(() => setEditing({ ...selected, tab: 'itens' }))}>{t('live.q.bought')}</button>
              <button type="button" onClick={needSel(() => event({ kind: 'morte', side: selected.side, player: selected.index }))}>{t('live.q.died')}</button>
              <button type="button" onClick={needSel(() => event({ kind: 'abate', side: selected.side, player: selected.index }))}>{t('live.q.killed')}</button>
              <button type="button" onClick={() => setObjective('dragao')}>{t('live.q.dragon')}</button>
              <button type="button" onClick={() => setObjective('arauto')}>{t('live.q.herald')}</button>
              <button type="button" onClick={() => setObjective('barao')}>{t('live.q.baron')}</button>
            </div>
            <VoiceBar game={game} champions={champions} items={items} onApply={change} onUndo={history.length ? undo : null} />
          </div>
          {sel && <p className="live-sel small">{t('live.selected', { name: sel.champion })}</p>}
          <div className="live-teams">
            {team(ally)}
            {team(enemy)}
          </div>
          <LivePanels game={game} champions={champions} profiles={profiles} items={items} formats={formats} selected={selected}
            unavailableFor={unavailableFor} onChange={change} panel={panel} onPanel={setPanel} draft={draft} />
          {game.events?.length > 0 && (
            <section className="panel live-events">
              <h4>{t('live.events')}</h4>
              <ul>
                {[...game.events].reverse().slice(0, 30).map((e, k) => {
                  const pl = e.side != null && e.player != null ? game[teamKey(e.side)].players[e.player] : null;
                  return (
                    <li key={k}><span className="muted">{mmssOf(e.t)}</span> {t(`live.ev.${e.kind}`)} {pl?.champion ?? (e.side ? t(`live.side.${e.side}`) : '')}
                      {e.item != null && <> <ItemIcon id={e.item} size={16} /> {items.find((i) => i.id === e.item)?.name}</>} {e.detail ?? ''}</li>
                  );
                })}
              </ul>
            </section>
          )}
          <div className="stage-nav">
            <button type="button" onClick={() => goStage('carregamento')}>‹ {t('stage.carregamento')}</button>
          </div>
        </>
      )}

      {editing && (
        <LivePlayerEditor
          player={game[teamKey(editing.side)].players[editing.index]} side={editing.side} startTab={editing.tab}
          champions={champions} lanesOf={lanesOf} possibleOf={possibleOf} unavailable={unavailableFor()} fearless={fearlessSet()} items={items}
          closeOnPick={!!editing.draft}
          onClose={() => setEditing(null)}
          onChange={(p) => {
            const before = game[teamKey(editing.side)].players[editing.index];
            let next = updatePlayer(game, editing.side, editing.index, (x) => Object.assign(x, p));
            // a purchase made in the editor is also an event at the clock's second
            const added = (p.items ?? []).filter((id) => !(before.items ?? []).includes(id));
            const comps = (p.components ?? []).length > (before.components ?? []).length ? [p.components[p.components.length - 1]] : [];
            for (const id of [...added, ...comps]) {
              next = { ...next, events: [...next.events, { t: Math.round(clockSeconds(next.clock)), kind: added.includes(id) ? 'compra' : 'componente', side: editing.side, player: editing.index, item: id }] };
            }
            change(next);
            if (p.champion && !selected) setSelected({ side: editing.side, index: editing.index });
          }} />
      )}

      {banning && (
        <div className="prep-overlay live-editor" role="dialog" aria-label={t('live.ban')}>
          <div className="prep-overlay-head">
            <button className="prep-back" onClick={() => setBanning(null)}><span aria-hidden="true">‹</span> {t('live.banOf', { side: t(`live.side.${banning.side}`) })}</button>
            {fmt?.banSkip && (
              <button type="button" title={t('live.noBanHelp')} onClick={() => {
                const g = clone(game);
                const bans = g[teamKey(banning.side)].bans ?? [];
                while (bans.length <= banning.index) bans.push(null);
                bans[banning.index] = NO_BAN;
                g[teamKey(banning.side)].bans = bans;
                change(g);
                setBanning(null);
              }}>{t('live.noBan')}</button>
            )}
            {game[teamKey(banning.side)].bans?.[banning.index] && (
              <button type="button" className="danger" onClick={() => {
                const g = clone(game);
                g[teamKey(banning.side)].bans[banning.index] = null;
                change(g);
                setBanning(null);
              }}>{t('live.clearBan')}</button>
            )}
          </div>
          <ChampionGrid champions={champions} lanesOf={lanesOf} unavailable={unbannableFor(banning.side, banning.index)} fearless={fearlessSet()}
            value={game[teamKey(banning.side)].bans?.[banning.index]}
            onPick={(name) => {
              const g = clone(game);
              const bans = g[teamKey(banning.side)].bans ?? [];
              while (bans.length <= banning.index) bans.push(null);
              bans[banning.index] = name;
              g[teamKey(banning.side)].bans = bans;
              change(g);
              setBanning(null);
            }} />
        </div>
      )}

      {seriesAdd != null && (
        <div className="prep-overlay live-editor" role="dialog" aria-label={t('live.series.addTo', { n: seriesAdd + 1 })}>
          <div className="prep-overlay-head">
            <button className="prep-back" onClick={() => setSeriesAdd(null)}><span aria-hidden="true">‹</span> {t('live.series.addTo', { n: seriesAdd + 1 })}</button>
          </div>
          <ChampionGrid champions={champions} lanesOf={lanesOf} unavailable={fearlessSet()} fearless={fearlessSet()}
            onPick={(name) => {
              const g = clone(game);
              const s = g.series[seriesAdd];
              (s.blue.length <= s.red.length ? s.blue : s.red).push(name);
              change(g);
              setSeriesAdd(null);
            }} />
        </div>
      )}

      {objective && (
        <div className="prep-overlay live-editor live-objective" role="dialog" aria-label={t(`live.ev.${objective}`)}>
          <div className="prep-overlay-head">
            <button className="prep-back" onClick={() => setObjective(null)}><span aria-hidden="true">‹</span> {t(`live.ev.${objective}`)}</button>
          </div>
          {[ally, enemy].map((side) => (
            <div key={side} className="live-obj-row">
              <strong className={`live-side-tag ${side}`}>{game[teamKey(side)].name || t(`live.side.${side}`)}</strong>
              {objective === 'dragao'
                ? (formats?.dragons ?? []).map((d) => (
                  <button key={d} type="button" className="live-big" onClick={() => { event({ kind: 'dragao', side, detail: d }); setObjective(null); }}>{d}</button>
                ))
                : <button type="button" className="live-big" onClick={() => { event({ kind: objective, side }); setObjective(null); }}>{t(`live.ev.${objective}`)}</button>}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * Rodada 20.b: the champions a Fearless series bans before the draft, grouped by earlier game, with hand corrections
 * (a series started outside the app): remove one, add one, add or remove a game.
 */
function SeriesStrip({ game, onChange, onAdd }) {
  const series = game.series ?? [];
  const total = series.reduce((n, s) => n + (s.blue?.length ?? 0) + (s.red?.length ?? 0), 0);
  const edit = (fn) => {
    const g = clone(game);
    fn(g.series);
    onChange(g);
  };
  return (
    <section className="panel live-series-strip" aria-label={t('live.series.title', { n: total })}>
      <div className="spread">
        <strong className="small">{t('live.series.title', { n: total })}</strong>
        <button type="button" title={t('live.series.addGameHelp')} onClick={() => edit((s) => s.push({ blue: [], red: [], gameId: null }))}>{t('live.series.addGame')}</button>
      </div>
      {series.map((s, k) => (
        <div key={k} className="live-series-game">
          <small className="muted">{t('live.series.game', { n: k + 1 })}</small>
          {['blue', 'red'].map((side) => (s[side] ?? []).map((c, j) => (
            <button key={`${side}${c}`} type="button" className={`live-series-champ ${side === 'blue' ? BLUE : RED}`} title={t('live.series.remove', { name: c })}
              onClick={() => edit((ss) => ss[k][side].splice(j, 1))}>
              <ChampionAvatar name={c} size={24} />
            </button>
          )))}
          <button type="button" className="live-series-add" onClick={() => onAdd(k)}>{t('live.series.add')}</button>
          <button type="button" className="live-series-del" title={t('live.series.removeGame', { n: k + 1 })} aria-label={t('live.series.removeGame', { n: k + 1 })}
            onClick={() => edit((ss) => ss.splice(k, 1))}>✕</button>
        </div>
      ))}
    </section>
  );
}
