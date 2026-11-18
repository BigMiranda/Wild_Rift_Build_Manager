import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { useBuild } from '../buildContext.jsx';
import { loadChampions } from '../components/ChampionPicker.jsx';
import { ChampionAvatar } from '../components/ChampionAvatar.jsx';
import ItemIcon from '../components/ItemIcon.jsx';
import LivePlayerEditor, { ChampionGrid, SpellRuneIcons } from '../components/LivePlayerEditor.jsx';
import {
  BLUE, RED, addEvent, clockSeconds, clone, emptyGame, enemyOf, mmssOf, parseClock, picks, pushHistory, setClock, teamKey,
  toggleClock, updatePlayer,
} from '../liveGame.js';
import LivePanels from '../components/LivePanels.jsx';
import VoiceBar from '../components/VoiceBar.jsx';

/**
 * Rodada 20: Simulações › Draft e partida. For whoever WATCHES a match (replay, stream, championship, coaching): the
 * tool only uses what the user tells it (clicks, voice); nothing reads the game. One match (or a Fearless series)
 * from the draft to the end: two columns of five cards, a clock the user sets, quick actions and an always visible undo.
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
  const [clockEdit, setClockEdit] = useState(null);
  const [error, setError] = useState(null);
  const [saved, setSaved] = useState(true);
  const [, tick] = useState(0);
  const saveTimer = useRef(null);

  useEffect(() => {
    loadChampions().then(setChampions).catch(() => {});
    api.get('/api/champions/profiles').then(setProfiles).catch(() => {});
    api.get('/api/draft/formatos').then(setFormats).catch((e) => setError(e.message));
    api.get('/api/draft/jogos').then(setList).catch(() => {});
  }, []);
  // the clock ticks on screen while running
  useEffect(() => {
    if (!game || game.clock?.paused) return undefined;
    const h = setInterval(() => tick((x) => x + 1), 1000);
    return () => clearInterval(h);
  }, [game?.clock?.paused, game?.clock?.setAt]);

  const lanesByName = useMemo(() => new Map(profiles.map((p) => [p.name, p.lanes])), [profiles]);
  const lanesOf = useCallback((name) => lanesByName.get(name), [lanesByName]);

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
      setGame(emptyGame());
      setSaved(false);
      return;
    }
    try {
      setGame(await api.get(`/api/draft/jogos/${id}`));
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

  const fearlessSet = useCallback((side) => {
    if (!game?.fearless) return new Set();
    const scope = game.fearlessScope ?? formats?.fearless ?? 'ambos';
    const out = new Set();
    for (const s of game.series ?? []) {
      for (const c of scope === 'proprio' ? (side === RED ? s.red : s.blue) : [...s.blue, ...s.red]) out.add(c);
    }
    return out;
  }, [game, formats]);
  const unavailableFor = useCallback((side) => {
    const out = fearlessSet(side);
    for (const k of ['blue', 'red']) {
      for (const p of game?.[k]?.players ?? []) if (p.champion) out.add(p.champion);
      for (const b of game?.[k]?.bans ?? []) if (b) out.add(b);
    }
    return out;
  }, [game, fearlessSet]);

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
                    <small className="muted"> · {formats?.formats?.find((f) => f.key === g.mode)?.name ?? g.mode}{g.fearless ? ' · Fearless' : ''}</small>
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
  const nextInSeries = () => {
    if (!window.confirm(t('live.nextGameConfirm'))) return;
    const g = clone(game);
    g.series = [...(g.series ?? []), { blue: picks(g, BLUE), red: picks(g, RED) }];
    const fresh = emptyGame();
    g.blue = { ...fresh.blue, name: g.blue.name };
    g.red = { ...fresh.red, name: g.red.name };
    g.predictions = {};
    g.events = [];
    g.clock = fresh.clock;
    change(g);
  };

  const team = (side) => {
    const tm = game[teamKey(side)];
    const bans = formats?.formats?.find((f) => f.key === game.mode)?.turns?.filter((x) => x.action === 'ban' && x.side === side).length ?? 5;
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
              {tm.bans?.[i] ? <ChampionAvatar name={tm.bans[i]} size={28} /> : <span aria-hidden="true">⊘</span>}
            </button>
          ))}
        </div>
        {tm.players.map((p, i) => {
          const isSel = selected?.side === side && selected?.index === i;
          const pred = !p.champion ? game.predictions?.[`${side}:${i}`] : null;
          return (
            <div key={i} className={`live-card${isSel ? ' selected' : ''}${p.champion ? '' : ' empty'}`}>
              <button type="button" className="live-card-main" aria-pressed={isSel}
                onClick={() => (p.champion ? setSelected(isSel ? null : { side, index: i }) : setEditing({ side, index: i, tab: 'campeao' }))}>
                <ChampionAvatar name={p.champion ?? pred} size={44} />
                <span className="live-card-text">
                  <strong>{p.champion ?? (pred ? `${pred}?` : t('live.emptySlot'))}</strong>
                  <small className="muted">{p.lane}{p.level ? ` · ${t('live.lvl', { n: p.level })}` : ''}
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
            </div>
          );
        })}
      </section>
    );
  };

  const fmt = formats?.formats?.find((f) => f.key === game.mode);
  return (
    <div className="main live">
      <section className="panel live-setup">
        <div className="row">
          <button type="button" onClick={() => { setGame(null); setHistory([]); }}>‹ {t('live.games')}</button>
          <input className="live-name" value={game.name ?? ''} placeholder={t('live.name')} onChange={(e) => change({ ...clone(game), name: e.target.value })} />
          <select value={game.mode} onChange={(e) => change({ ...clone(game), mode: e.target.value })} aria-label={t('live.mode')}>
            {(formats?.formats ?? []).map((f) => <option key={f.key} value={f.key}>{f.name}</option>)}
          </select>
          <label className="live-check">
            <input type="checkbox" checked={!!game.fearless} onChange={(e) => change({ ...clone(game), fearless: e.target.checked })} /> Fearless
          </label>
          {game.fearless && (
            <select value={game.fearlessScope ?? formats?.fearless ?? 'ambos'} aria-label={t('live.fearlessScope')}
              onChange={(e) => change({ ...clone(game), fearlessScope: e.target.value })}>
              <option value="ambos">{t('live.fearless.ambos')}</option>
              <option value="proprio">{t('live.fearless.proprio')}</option>
            </select>
          )}
          <button type="button" onClick={() => change({ ...clone(game), allySide: enemy })} title={t('live.swapSidesHelp')}>
            {t('live.allyIs', { side: t(`live.side.${ally}`) })} ⇄
          </button>
          {game.fearless && <button type="button" onClick={nextInSeries}>{t('live.nextGame', { n: (game.series?.length ?? 0) + 2 })}</button>}
          {game.id && <button type="button" className="danger" onClick={remove}>{t('live.delete')}</button>}
          <small className="muted">{saved ? t('live.saved') : t('live.saving')}</small>
        </div>
        {fmt?.source && <p className="muted small">{t('live.formatSource', { source: fmt.source })}</p>}
        {game.fearless && (game.series ?? []).length > 0 && (
          <p className="small">{t('live.seriesUsed')} {(game.series ?? []).map((s, k) => (
            <span key={k} className="live-series">#{k + 1}: {[...s.blue, ...s.red].map((c) => <ChampionAvatar key={c} name={c} size={18} />)}</span>
          ))}</p>
        )}
      </section>

      <div className="live-bar">
        <div className="live-clock">
          {clockEdit != null ? (
            <form onSubmit={(e) => {
              e.preventDefault();
              const s = parseClock(clockEdit);
              if (s != null) change(setClock(game, s));
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
        <button type="button" className="live-undo" disabled={!history.length} onClick={undo} title={t('live.undo')}>↶ {t('live.undo')}</button>
        <VoiceBar game={game} champions={champions} items={items} onApply={change} />
      </div>
      {sel && <p className="live-sel small">{t('live.selected', { name: sel.champion })}</p>}
      {error && <div className="notice error" onClick={() => setError(null)}>{error}</div>}

      <div className="live-teams">
        {team(ally)}
        {team(enemy)}
      </div>

      <LivePanels game={game} champions={champions} profiles={profiles} items={items} formats={formats} selected={selected}
        unavailableFor={unavailableFor} onChange={change} />

      {game.events?.length > 0 && (
        <section className="panel live-events">
          <h4>{t('live.events')}</h4>
          <ul>
            {[...game.events].reverse().slice(0, 30).map((e, k) => {
              const p = e.side != null && e.player != null ? game[teamKey(e.side)].players[e.player] : null;
              return (
                <li key={k}><span className="muted">{mmssOf(e.t)}</span> {t(`live.ev.${e.kind}`)} {p?.champion ?? (e.side ? t(`live.side.${e.side}`) : '')}
                  {e.item != null && <> <ItemIcon id={e.item} size={16} /> {items.find((i) => i.id === e.item)?.name}</>} {e.detail ?? ''}</li>
              );
            })}
          </ul>
        </section>
      )}

      {editing && (
        <LivePlayerEditor
          player={game[teamKey(editing.side)].players[editing.index]} side={editing.side} startTab={editing.tab}
          champions={champions} lanesOf={lanesOf} unavailable={unavailableFor(editing.side)} fearless={fearlessSet(editing.side)} items={items}
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
            {game[teamKey(banning.side)].bans?.[banning.index] && (
              <button type="button" className="danger" onClick={() => {
                const g = clone(game);
                g[teamKey(banning.side)].bans[banning.index] = null;
                change(g);
                setBanning(null);
              }}>{t('live.clearBan')}</button>
            )}
          </div>
          <ChampionGrid champions={champions} lanesOf={lanesOf} unavailable={unavailableFor(banning.side)} fearless={fearlessSet(banning.side)}
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
