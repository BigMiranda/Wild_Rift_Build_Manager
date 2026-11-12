import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { PUBLIC } from '../publicMode.js';
import { t } from '../i18n.js';
import { n1 } from '../format.js';
import { useBuild } from '../buildContext.jsx';
import { buildTiles } from '../shopModel.js';
import ShopBrowser from '../components/ShopBrowser.jsx';
import ItemIcon from '../components/ItemIcon.jsx';
import Help from '../components/Help.jsx';
import GuideFlow from '../components/GuideFlow.jsx';

const COND_TYPES = ['inimigos_dano', 'inimigos_classe', 'inimigos_subclasse', 'inimigos_arquetipo', 'adversario_rota', 'adversario_subclasse', 'ouro', 'minuto', 'item_adversario'];
const CLASSES = ['Tanque', 'Lutador', 'Assassino', 'Atirador', 'Mago', 'Controlador'];
const ARCHETYPES = ['tanque', 'lutador', 'critico', 'ao_acertar', 'letalidade', 'mago_continuo', 'mago_explosivo', 'suporte', 'hibrido'];

let seq = 0;
const newId = () => `n${Date.now().toString(36)}${(seq++).toString(36)}`;
const newCondition = () => ({ id: newId(), kind: 'condicao', cond: { type: 'inimigos_dano', value: 'magico', count: 2, op: '>=' }, then: [], otherwise: [] });
const newDecision = (itemId, decision = 'item') => ({ id: newId(), kind: 'decisao', decision, itemId });

/** A path to a list of nodes: [] = the root; [nodeId, 'then' | 'otherwise', ...] = a branch. */
function listAt(nodes, path) {
  let list = nodes;
  for (let i = 0; i < path.length; i += 2) {
    const n = list.find((x) => x.id === path[i]);
    if (!n) return null;
    list = n[path[i + 1]];
  }
  return list;
}

/** A copy of the tree with the list at `path` changed by `fn`. */
function editAt(nodes, path, fn) {
  if (path.length === 0) return fn(nodes);
  return nodes.map((n) => (n.id === path[0] ? { ...n, [path[1]]: editAt(n[path[1]], path.slice(2), fn) } : n));
}

function mapNode(nodes, id, fn) {
  return nodes.map((n) => {
    if (n.id === id) return fn(n);
    if (n.kind === 'condicao') return { ...n, then: mapNode(n.then, id, fn), otherwise: mapNode(n.otherwise, id, fn) };
    return n;
  });
}

function removeNode(nodes, id) {
  return nodes.filter((n) => n.id !== id).map((n) => (n.kind === 'condicao' ? { ...n, then: removeNode(n.then, id), otherwise: removeNode(n.otherwise, id) } : n));
}

const samePath = (a, b) => a && b && a.length === b.length && a.every((x, i) => x === b[i]);

/** A condition's fields. */
function ConditionEditor({ node, onChange, itemsById }) {
  const c = node.cond;
  const set = (p) => onChange({ ...node, cond: { ...c, ...p } });
  return (
    <span className="guide-cond">
      <select value={c.type} onChange={(e) => set({ type: e.target.value, value: e.target.value === 'inimigos_dano' ? 'magico' : e.target.value === 'ouro' ? 'frente' : '' })}>
        {COND_TYPES.map((x) => <option key={x} value={x}>{t(`guide.cond.${x}`)}</option>)}
      </select>
      {['inimigos_dano', 'inimigos_classe', 'inimigos_subclasse', 'inimigos_arquetipo'].includes(c.type) && (
        <input type="number" min={1} max={5} value={c.count ?? 1} style={{ width: 44 }} onChange={(e) => set({ count: Number(e.target.value) || 1 })} title={t('guide.count')} />
      )}
      {c.type === 'inimigos_dano' && (
        <select value={c.value} onChange={(e) => set({ value: e.target.value })}>
          <option value="magico">{t('guide.magic')}</option><option value="fisico">{t('guide.physical')}</option><option value="misto">{t('guide.mixed')}</option>
        </select>
      )}
      {c.type === 'inimigos_classe' && <select value={c.value ?? ''} onChange={(e) => set({ value: e.target.value })}>{['', ...CLASSES].map((x) => <option key={x} value={x}>{x || '—'}</option>)}</select>}
      {c.type === 'inimigos_arquetipo' && <select value={c.value ?? ''} onChange={(e) => set({ value: e.target.value })}>{['', ...ARCHETYPES].map((x) => <option key={x} value={x}>{x || '—'}</option>)}</select>}
      {['inimigos_subclasse', 'adversario_subclasse'].includes(c.type) && (
        <input value={c.value ?? ''} placeholder={t('guide.subclassHint')} onChange={(e) => set({ value: e.target.value })} style={{ width: 200 }} />
      )}
      {c.type === 'adversario_rota' && <input value={c.value ?? ''} placeholder={t('guide.laneHint')} onChange={(e) => set({ value: e.target.value })} style={{ width: 140 }} />}
      {c.type === 'ouro' && (
        <select value={c.value} onChange={(e) => set({ value: e.target.value })}>
          <option value="frente">{t('guide.ahead')}</option><option value="atras">{t('guide.behind')}</option>
        </select>
      )}
      {c.type === 'minuto' && (
        <>
          <select value={c.op ?? '>='} onChange={(e) => set({ op: e.target.value })}><option value=">=">{t('guide.from')}</option><option value="<">{t('guide.before')}</option></select>
          <input type="number" min={0} value={c.number ?? 10} style={{ width: 52 }} onChange={(e) => set({ number: Number(e.target.value) })} /> min
        </>
      )}
      {c.type === 'item_adversario' && (
        <span>
          {c.itemId && <ItemIcon id={c.itemId} size={18} />}
          <select value={c.itemId ?? ''} onChange={(e) => set({ itemId: e.target.value ? Number(e.target.value) : null })} aria-label={t('guide.pickEnemyItem')}>
            <option value="">{t('guide.pickEnemyItem')}</option>
            {[...itemsById.values()].filter((i) => i.section === 'aprimorado' && !(i.tabs ?? []).includes('Botas')).sort((a, b) => a.name.localeCompare(b.name)).map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}
          </select>
        </span>
      )}
      <label className="inline" title={t('guide.frequencyHelp')}>{t('guide.frequency')}
        <input type="number" min={0} max={1} step={0.1} value={node.frequency ?? ''} placeholder="0,5" style={{ width: 52 }}
          onChange={(e) => onChange({ ...node, frequency: e.target.value === '' ? null : Number(e.target.value) })} />
      </label>
    </span>
  );
}

/** The tree as an indented clickable list. */
function NodeList({ nodes, path, open, setOpen, onTree, tree, itemsById, depth = 0 }) {
  return (
    <ul className="guide-list" style={{ marginLeft: depth ? 16 : 0 }}>
      {nodes.map((n) => (
        <li key={n.id}>
          {n.kind === 'condicao' ? (
            <>
              <div className="guide-row">
                <b>{t('guide.if')}</b>
                <ConditionEditor node={n} itemsById={itemsById} onChange={(x) => onTree(mapNode(tree, n.id, () => x))} />
                <button className="icon" onClick={() => onTree(removeNode(tree, n.id))} aria-label={t('efx.remove')}>✕</button>
              </div>
              {['then', 'otherwise'].map((side) => {
                const p = [...path, n.id, side];
                return (
                  <div key={side} className={`guide-branch${samePath(open, p) ? ' open' : ''}`}>
                    <button className="link" onClick={() => setOpen(p)}>{side === 'then' ? t('guide.then') : t('guide.else')}{samePath(open, p) ? ` · ${t('guide.openBranch')}` : ''}</button>
                    <NodeList nodes={n[side]} path={p} open={open} setOpen={setOpen} onTree={onTree} tree={tree} itemsById={itemsById} depth={depth + 1} />
                  </div>
                );
              })}
            </>
          ) : (
            <div className="guide-row">
              {n.decision === 'runas' ? <span>{t('guide.runes')}: <b>{n.runePage?.keystone ?? '—'}</b></span> : (
                <span><ItemIcon id={n.itemId} size={22} /> {itemsById.get(n.itemId)?.name ?? n.itemId} <small className="muted">{t(`guide.dec.${n.decision ?? 'item'}`)}</small></span>
              )}
              <button className="icon" onClick={() => onTree(removeNode(tree, n.id))} aria-label={t('efx.remove')}>✕</button>
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}

/** Build › Guias: the champion's guides, an editor of conditions and decisions, the shop in "Guia" mode, evaluation and suggestions. */
export default function Guides() {
  const { draft, items, itemsById, builds } = useBuild();
  const unit = draft?.unitCode;
  const [list, setList] = useState([]);
  const [guide, setGuide] = useState(null);
  const [open, setOpen] = useState([]);
  const [msg, setMsg] = useState(null);
  const [evaluation, setEvaluation] = useState(null);
  const [busy, setBusy] = useState(false);
  const [match, setMatch] = useState({ enemies: [], laneOpponent: '', goldDiff: 0, minute: 0 });
  const [reading, setReading] = useState(null);
  const tiles = useMemo(() => buildTiles(items ?? []), [items]);

  const reload = () => unit && api.get(`/api/guides?unit=${encodeURIComponent(unit)}`).then(setList).catch((e) => setMsg(e.message));
  useEffect(() => { reload(); setGuide(null); setEvaluation(null); setReading(null); }, [unit]);
  if (!draft) return null;

  const setTree = (nodes) => { setGuide((g) => ({ ...g, nodes })); setEvaluation(null); setReading(null); };
  const addHere = (node) => {
    const target = listAt(guide.nodes, open) ? open : [];
    setTree(editAt(guide.nodes, target, (l) => [...l, node]));
  };
  /** The shop in "Guia" mode: a click puts the item in the open branch; with an "enemy item" condition open... */
  const pickItem = (id) => {
    const it = itemsById.get(id);
    addHere(newDecision(id, (it?.tabs ?? []).includes('Botas') ? 'bota' : it?.section && it.section !== 'aprimorado' ? 'componente' : 'item'));
  };
  const save = async () => {
    try {
      const body = { ...guide, unitCode: unit };
      setGuide(guide.id ? await api.put(`/api/guides/${guide.id}`, body) : await api.post('/api/guides', body));
      setMsg(t('efx.saved'));
      reload();
    } catch (e) {
      setMsg(e.message);
    }
  };
  const remove = async () => {
    if (!guide?.id || !window.confirm(t('guide.deleteConfirm', { name: guide.name }))) return;
    await api.del(`/api/guides/${guide.id}`);
    setGuide(null);
    reload();
  };
  const suggest = async () => {
    setBusy(true);
    try {
      const g = await api.post('/api/guides/suggest', { unitCode: unit, latest: true });
      setGuide({ ...g, nodes: withIds(g.nodes) });
      setOpen([]);
    } catch (e) {
      setMsg(e.message);
    } finally {
      setBusy(false);
    }
  };
  const evaluate = async () => {
    setBusy(true);
    try {
      setEvaluation(await api.post('/api/guides/evaluate', { guide, ...(draft.id ? { buildId: draft.id } : {}) }));
    } catch (e) {
      setMsg(e.message);
    } finally {
      setBusy(false);
    }
  };
  const read = async () => {
    try {
      setReading(await api.post('/api/guides/resolve', {
        guide, match: { buildId: draft.id, enemies: match.enemies.map(Number), laneOpponent: match.laneOpponent ? Number(match.laneOpponent) : null, goldDiff: Number(match.goldDiff) || 0, minute: Number(match.minute) || 0 },
      }));
    } catch (e) {
      setMsg(e.message);
    }
  };
  const addRunes = () => addHere({ id: newId(), kind: 'decisao', decision: 'runas', runePage: draft.runePage });

  return (
    <div className="guides">
      <section className="panel">
        <h3>{t('sub.build.guias')} · {unit} <Help text={t('guide.help')} /></h3>
        <div className="row">
          {list.map((g) => <button key={g.id} className={guide?.id === g.id ? 'active' : ''} onClick={() => { setGuide(g); setOpen([]); setEvaluation(null); }}>{g.name}</button>)}
          {!PUBLIC && <button onClick={() => { setGuide({ name: t('guide.new'), unitCode: unit, nodes: [] }); setOpen([]); }}>{t('guide.newBtn')}</button>}
          {!PUBLIC && <button disabled={busy} onClick={suggest} title={t('guide.suggestHelp')}>{t('guide.suggest')}</button>}
        </div>
        {PUBLIC && list.length === 0 && <p className="muted small">{t('pub.noGuides')}</p>}
        {msg && <p className="small muted">{msg}</p>}
      </section>
      {guide && (
        <div className="guide-layout">
          <section className="panel">
            {PUBLIC ? <h4>{guide.name}</h4> : (
              <div className="row">
                <label className="inline">{t('efx.name')}<input value={guide.name ?? ''} onChange={(e) => setGuide({ ...guide, name: e.target.value })} /></label>
                <label className="check" title={t('export.guideHelp')}>
                  <input type="checkbox" checked={!!guide.publico} onChange={(e) => setGuide({ ...guide, publico: e.target.checked })} />{t('export.guide')}
                </label>
                <button className="primary" onClick={save}>{t('efx.save')}</button>
                {guide.id && <button className="danger" onClick={remove}>{t('efx.delete')}</button>}
              </div>
            )}
            <div className={`guide-branch${open.length === 0 ? ' open' : ''}`}>
              <button className="link" onClick={() => setOpen([])}>{t('guide.root')}{open.length === 0 ? ` · ${t('guide.openBranch')}` : ''}</button>
              <NodeList nodes={guide.nodes} path={[]} open={open} setOpen={setOpen} onTree={PUBLIC ? () => {} : setTree} tree={guide.nodes} itemsById={itemsById} />
            </div>
            {guide.nodes.length > 0 && (
              <details open>
                <summary><b>{t('guide.flow')}</b></summary>
                <GuideFlow nodes={guide.nodes} itemName={(id) => itemsById.get(id)?.name ?? `item ${id}`} />
              </details>
            )}
            {!PUBLIC && (
            <>
            <div className="row">
              <button onClick={() => addHere(newCondition())}>{t('guide.addCondition')}</button>
              <button onClick={addRunes}>{t('guide.addRunes')}</button>
              <small className="muted">{t('guide.shopHint')}</small>
            </div>
            <h4>{t('guide.evaluate')} <Help text={t('guide.evaluateHelp')} /></h4>
            <button disabled={busy || !guide.nodes.length} onClick={evaluate}>{busy ? t('compare.calculating') : t('guide.evaluate')}</button>
            {evaluation && (
              <>
                <p><b>{t('score.total')}: {n1(evaluation.score)}</b></p>
                <ul className="small">
                  {evaluation.paths.map((p, i) => (
                    <li key={i}>{p.choices.join(' · ') || t('guide.noCondition')} — {t('guide.weight')} {n1(p.weight * 100)}% · {n1(p.score)}
                      {' '}<span className="step-row">{p.order.map((id) => <ItemIcon key={id} id={id} size={18} />)}</span></li>
                  ))}
                </ul>
              </>
            )}
            <h4>{t('guide.read')} <Help text={t('guide.readHelp')} /></h4>
            <div className="row">
              <select multiple size={4} value={match.enemies} onChange={(e) => setMatch({ ...match, enemies: [...e.target.selectedOptions].map((o) => o.value) })} aria-label={t('guide.enemies')}>
                {builds.filter((b) => b.unitCode !== 'RAGDOLL').map((b) => <option key={b.id} value={b.id}>{b.name} · {b.unitCode}</option>)}
              </select>
              <label className="field">{t('guide.lane')}
                <select value={match.laneOpponent} onChange={(e) => setMatch({ ...match, laneOpponent: e.target.value })}>
                  <option value="">—</option>
                  {match.enemies.map((id) => <option key={id} value={id}>{builds.find((b) => String(b.id) === String(id))?.name}</option>)}
                </select>
              </label>
              <label className="inline">{t('guide.gold')}<input type="number" value={match.goldDiff} style={{ width: 70 }} onChange={(e) => setMatch({ ...match, goldDiff: e.target.value })} /></label>
              <button disabled={!draft.id} onClick={read} title={draft.id ? '' : t('guide.needSaved')}>{t('guide.read')}</button>
            </div>
            {reading && (
              <p className="small">{t('guide.readOrder')}: <span className="step-row">{reading.order.map((id, i) => <ItemIcon key={`${id}-${i}`} id={id} size={22} />)}</span>
                {reading.runePage && ` · ${t('guide.runes')}: ${reading.runePage.keystone}`}</p>
            )}
            </>
            )}
          </section>
          {!PUBLIC && (
            <section className="panel guide-shop">
              <h4>{t('guide.shopMode')} <Help text={t('guide.shopModeHelp')} /></h4>
              <ShopBrowser tiles={tiles} iconSize={44} tileProps={(x) => ({ type: 'button', title: x.name, onClick: () => pickItem(x.preferredId) })} />
            </section>
          )}
        </div>
      )}
    </div>
  );
}

/** Ids for the nodes a suggestion brought (the server's have none). */
function withIds(nodes) {
  return (nodes ?? []).map((n) => ({ ...n, id: n.id ?? newId(), then: withIds(n.then), otherwise: withIds(n.otherwise) }));
}
