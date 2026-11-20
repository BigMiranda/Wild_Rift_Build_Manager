import { t } from '../i18n.js';
import { ChampionAvatar } from './ChampionAvatar.jsx';
import { NO_BAN, nextTurn, turnValue } from '../liveGame.js';

/**
 * Rodada 20.b, Parte 4: the draft's bans and picks in the format's order, the current step highlighted. A tap on any step
 * opens its picker (a ban or a pick can be changed at any time); the banner says whose turn it is and offers
 * "Sem banimento" where the format allows it. Ranked blind bans can be filled in any order.
 */
export default function DraftSteps({ game, fmt, onTurn, onSkipBan }) {
  if (!fmt) return null;
  const cur = nextTurn(game, fmt);
  const groups = [];
  for (const tn of fmt.turns) {
    const key = `${tn.phase}|${tn.action}`;
    const last = groups[groups.length - 1];
    if (last && last.key === key) last.turns.push(tn);
    else groups.push({ key, phase: tn.phase, action: tn.action, turns: [tn] });
  }
  const phases = Math.max(1, ...fmt.turns.map((x) => x.phase ?? 1));
  const bansOf = (side) => fmt.turns.filter((x) => x.action === 'ban' && x.side === side).length;
  return (
    <section className="panel draft-steps" aria-label={t('stage.steps')}>
      <div className="draft-steps-now">
        {cur ? (
          <>
            <span className={`live-side-tag ${cur.side}`}>{t(`live.side.${cur.side}`)}</span>
            <strong>{cur.action === 'ban'
              ? t(cur.simultaneous ? 'stage.turnBlindBan' : 'stage.turnBan', { n: cur.index + 1, of: bansOf(cur.side) })
              : t('stage.turnPick', { n: cur.index + 1 })}</strong>
            {phases > 1 && <small className="muted">{t('stage.phase', { n: cur.phase })}</small>}
            {cur.source && <small className="muted" title={t('stage.estHelp')}>{cur.source}</small>}
            <button type="button" className="primary" onClick={() => onTurn(cur)}>{t(cur.action === 'ban' ? 'live.ban' : 'stage.pick')}</button>
            {cur.action === 'ban' && fmt.banSkip && <button type="button" onClick={() => onSkipBan(cur)} title={t('live.noBanHelp')}>{t('live.noBan')}</button>}
          </>
        ) : <strong>{t('stage.draftDone')}</strong>}
      </div>
      <ol className="draft-steps-list">
        {groups.map((gr, k) => (
          <li key={k} className={`draft-steps-group ${gr.action}`}>
            <small className="muted">{t(gr.action === 'ban' ? 'stage.bans' : 'stage.picks')}{phases > 1 ? ` ${gr.phase}` : ''}</small>
            <span className="draft-steps-chips">
              {gr.turns.map((tn) => {
                const v = turnValue(game, tn);
                const isCur = cur && cur.order === tn.order;
                return (
                  <button key={tn.order} type="button" className={`draft-step ${tn.side}${isCur ? ' current' : ''}${v ? ' done' : ''}`}
                    aria-current={isCur ? 'step' : undefined} onClick={() => onTurn(tn)}
                    title={`${t(`live.side.${tn.side}`)} · ${t(tn.action === 'ban' ? 'live.ban' : 'stage.pick')} ${tn.index + 1}${v ? ` · ${v === NO_BAN ? t('live.noBan') : v}` : ''}`}>
                    {v && v !== NO_BAN ? <ChampionAvatar name={v} size={26} /> : <span aria-hidden="true">{v === NO_BAN ? '–' : tn.action === 'ban' ? '⊘' : tn.index + 1}</span>}
                  </button>
                );
              })}
            </span>
          </li>
        ))}
      </ol>
    </section>
  );
}
