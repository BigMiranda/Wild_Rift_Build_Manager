import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import { statLabel, t } from '../i18n.js';
import { mmss, n0, statValue } from '../format.js';
import { useBuild } from '../buildContext.jsx';
import { pageSlots, runeIcon } from '../runes.js';
import { reportJobs, runJobs, weightedIndex } from '../effectiveness.js';
import { useTask } from '../tasks.js';
import RuneEditor from './RuneEditor.jsx';
import WholeDelta from './WholeDelta.jsx';

export const MAX_PAGES = 4;
const copy = (p) => JSON.parse(JSON.stringify(p));

/**
 * Build › Comparar runas (Rodada 8, parte 4): the quick item comparison's sibling for rune pages. The build's page is the
 * reference; up to 4 candidate pages are edited with the same rune editor (keystone and minor runes as in the game).
 * Each one goes through the same "swap and recalculate the whole build" (BuildDeltaService, change = the rune page) at
 * the minute selected: what the page adds in stats, the gold of the runes (the estimate of their passives) and the
 * physical, magic and mixed effective health. "Índice Básico" runs the effectiveness basic report for the pages only.
 */
export default function RuneCompare() {
  const { draft, payload, canCalculate, point, runesReady } = useBuild();
  const [pages, setPages] = useState([]);
  const [editing, setEditing] = useState(null);
  const [deltas, setDeltas] = useState([]);
  const [error, setError] = useState(null);
  const [basic, setBasic] = useState(null);
  const [busy, setBusy] = useState(null);
  const seq = useRef(0);
  useTask('rune-basic', !!busy, { label: t('rc.task'), done: busy?.done, total: busy?.total, path: '/build/comparar-runas' });

  const minute = point?.minute ?? null;
  // the reference (the build's page) and the candidates, all read the same way
  const all = useMemo(() => (draft ? [draft.runePage, ...pages] : []), [draft, pages]);
  const key = JSON.stringify([payload, all, minute]);
  useEffect(() => {
    if (!canCalculate || minute == null || !payload) return undefined;
    const mine = ++seq.current;
    setBasic(null);
    const timer = setTimeout(() => {
      Promise.all(all.map((page) => api.post('/api/quick/delta', { build: payload, change: { runePage: page }, minute })))
        .then((r) => { if (mine === seq.current) { setDeltas(r); setError(null); } })
        .catch((e) => { if (mine === seq.current) setError(e.message); });
    }, 200);
    return () => clearTimeout(timer);
  }, [key]);

  const runBasic = async () => {
    setError(null);
    setBusy({ done: 0, total: 0 });
    try {
      const report = await api.get('/api/effect/reports/basico');
      const out = [];
      for (let i = 0; i < all.length; i++) {
        const build = { ...payload, runePage: all[i] };
        const r = await runJobs({ build, minute, readMode: 'fixo' }, reportJobs(report, false), {
          size: 12, onProgress: (d, total) => setBusy({ done: i * total + d, total: all.length * total }),
        });
        out.push(weightedIndex(report, r));
      }
      setBasic(out);
    } catch (e) {
      setError(e.message);
    }
    setBusy(null);
  };

  if (!draft) return null;
  if (!canCalculate || minute == null) return <p className="muted">{t('timeline.needInputs')}</p>;
  if (!runesReady) return <p className="muted">{t('compare.calculating')}</p>;

  /** What the page's runes add in stats at the minute (the rune sources of the whole build). */
  const runeStats = (d) => Object.entries((d?.sources ?? []).filter((x) => x.kind === 'rune').reduce((acc, x) => {
    for (const [k, v] of Object.entries(x.statsAfter ?? {})) acc[k] = (acc[k] ?? 0) + v;
    return acc;
  }, {})).filter(([, v]) => Math.abs(v) >= 0.05);
  const runeGold = (d) => (d?.sources ?? []).filter((s) => s.kind === 'rune').reduce((a, s) => a + s.after, 0);
  const add = () => pages.length < MAX_PAGES && setPages([...pages, copy(draft.runePage)]);
  const setPage = (i, page) => setPages(pages.map((p, k) => (k === i ? page : p)));

  return (
    <section className="panel rune-compare">
      <div className="spread">
        <h3>{t('rc.title', { time: mmss(minute) })}</h3>
        <span className="row">
          <button onClick={add} disabled={pages.length >= MAX_PAGES}>{t('rc.add')}</button>
          <button onClick={runBasic} disabled={!!busy || deltas.length < all.length}>
            {busy ? t('qc.basicRunning', { done: busy.done, total: busy.total }) : t('qc.basic')}
          </button>
        </span>
      </div>
      <p className="muted small">{t('rc.help')}</p>
      {error && <p className="error">{error}</p>}
      <div className="qc-cards">
        {all.map((page, i) => {
          const d = deltas[i];
          return (
            <article key={i} className={`qc-card${i === 0 ? ' reference' : ''}`}>
              <header>
                <div>
                  <strong>{i === 0 ? t('rc.reference') : t('rc.candidate', { n: i })}</strong>
                </div>
                {i > 0 && <button className="icon" onClick={() => setPages(pages.filter((_, k) => k !== i - 1))} aria-label={t('qc.remove')}>✕</button>}
              </header>
              <button className="prep-row rc-page" onClick={() => i > 0 && setEditing(i - 1)} disabled={i === 0} title={i > 0 ? t('rc.edit') : t('rc.refHelp')}>
                <span className="prep-icons">
                  {pageSlots(page).map((s) => (s.rune
                    ? <img key={s.key} className="rune-img on" src={runeIcon(s.rune)} alt={s.rune.name} title={s.rune.name} width={36} height={36} />
                    : <span key={s.key} className="rune-img empty" style={{ width: 36, height: 36 }} />))}
                </span>
              </button>
              {d && (
                <dl>
                  <div><dt>{t('qc.row.mixed')}</dt><dd>{n0(d.effectiveMixedAfter)}</dd></div>
                  <div><dt>{t('qc.row.physical')}</dt><dd>{n0(d.effectivePhysicalAfter)}</dd></div>
                  <div><dt>{t('qc.row.magic')}</dt><dd>{n0(d.effectiveMagicAfter)}</dd></div>
                  <div title={t('rc.runeGoldHelp')}><dt>{t('rc.runeGold')}</dt><dd>{n0(runeGold(d))}</dd></div>
                  {basic?.[i] != null && <div><dt>{t('qc.row.basic')}</dt><dd>{n0(basic[i])}</dd></div>}
                </dl>
              )}
              {i > 0 && d && (
                <WholeDelta delta={d} compact title={t('rc.vsRef')} ownLabel={t('wd.ownRunes')}
                  show={['gold', 'effective', 'stats']} />
              )}
              {d && runeStats(d).length > 0 && (
                <>
                  <h5>{t('rc.stats')}</h5>
                  <ul className="qc-list">
                    {runeStats(d).map(([k, v]) => <li key={k}>+{statValue(k, v)} {statLabel(k)}</li>)}
                  </ul>
                </>
              )}
            </article>
          );
        })}
      </div>
      {editing != null && pages[editing] && (
        <RuneEditor page={pages[editing]} matchEnd={draft.matchEnd} onChange={(p) => setPage(editing, p)} onClose={() => setEditing(null)} />
      )}
    </section>
  );
}
