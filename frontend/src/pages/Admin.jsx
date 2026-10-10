import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { currentLang, statLabel } from '../i18n.js';
import { n0, n2, pct } from '../format.js';
import ItemIcon from '../components/ItemIcon.jsx';
import { StatIcon } from '../components/StatIcon.jsx';

/** Admin-only strings (kept here instead of the shared dictionary). */
const L = {
  pt: {
    items: 'Itens', ornn: 'Ornn & Forja Viva', xp: 'Tabela de XP', prices: 'Preço por status', catalog: 'Reimportar catálogo',
    saved: 'Salvo.', error: 'Erro: {msg}', close: 'fechar', save: 'Salvar', del: 'Apagar', search: 'Buscar…', newItem: '+ Novo',
    pickHint: 'Selecione um item para editar. Itens editados (✎) não são sobrescritos pela reimportação.',
    editing: 'Editar #{id}', creating: 'Novo item', deleteConfirm: 'Apagar "{name}"?', deleted: 'Item apagado.',
    name: 'Nome', cost: 'Custo total', tabs: 'Abas da loja (separadas por vírgula)', section: 'Seção', marker: 'Selo do patch',
    active: 'ativável', summary: 'Resumo', passivesText: 'Texto das passivas (como na loja{capture})', from: ', de {file}',
    secAprimorado: 'Aprimorado', secTier: 'Tier médio', secBasico: 'Básico', secPrep: 'Item inicial de suporte', secEvo: 'Evolução (não aparece na grade)',
    mNovo: 'novo', mReform: 'reformulado', mAlter: 'alterado',
    statLines: 'Linhas de status',
    statHelp: 'Linha plana: valor (e nv15 quando a loja mostra uma faixa por nível). Passiva percentual: ratio (0,3 = 30%) + ref_type. Escopo TOTAL = ratio × (base + adicional); BONUS = só o adicional; BASE = só o status base. Cond. = efeito condicional, ligado ou desligado em cada compra da build.',
    type: 'Tipo', value: 'Valor', lv15: 'nv15', passive: 'Passiva', ratio: 'Ratio', scope: 'Escopo', cond: 'Cond.',
    lv15Aria: 'Valor no nível 15', condAria: 'Condicional', removeLine: 'Remover linha', addLine: '+ Linha',
    recipe: 'Receita (componentes)', recipeHelp: 'Ao comprar este item, componentes já possuídos são consumidos e descontados do preço.',
    qty: 'Quantidade', removeComp: 'Remover componente', addComp: '+ Componente',
    ornnTitle: 'Status base do Ornn', ornnHelp: 'Valor no nível L = base + crescimento × (L − 1).',
    stat: 'Status', base: 'Base', growth: 'Crescimento/nível', lv15col: 'Nível 15',
    forgeTitle: 'Forja Viva (Living Forge)', forgeHelp: 'Percentual sobre vida, armadura e RM adicionais a partir do nível indicado. Aplicado por último.',
    fromLevel: 'A partir do nível', pctCol: 'Percentual (0,07 = 7%)', removeTier: 'Remover faixa', addTier: '+ Faixa',
    xpTitle: 'XP acumulado por nível',
    xpNote: 'Estimativa. Não há tabela pública confiável de XP do Wild Rift. Os valores iniciais são os 15 primeiros níveis da curva do LoL de PC (280 XP para o nível 2, +100 a cada nível). Corrija com base na sua experiência de jogo.',
    level: 'Nível', cumXp: 'XP acumulado para alcançar', levelXp: 'XP deste nível', loading: 'Carregando…',
    pricesTitle: 'Preço em ouro por ponto de status',
    pricesHelp: 'Metodologia de changchiyou/wildrift-gold-efficiency: first = item-base com um só status (custo ÷ quantidade); second = item com dois status, subtraindo o valor já conhecido dos outros; exclude = preço fixo manual; alias = mesmo preço de outro status, vezes o fator (Aceleração da Ultimate = 0,25 × AH; Escudo e Cura = Vida). Os itens-base usam os nomes da loja.',
    relevant: 'Relevante', baseType: 'Base', baseItem: 'Item-base', fixed: 'Preço fixo', alias: 'Alias', factor: 'Fator', perPoint: 'Ouro/ponto', formula: 'Fórmula',
    sample: 'Eficiência estática da Duplaguarda de Amaranto com estes preços: {pct}',
    catTitle: 'Reimportar o catálogo da loja',
    catHelp: 'Relê backend/src/main/resources/seed/loja_7_3.yml e precos_status.yml. Itens são atualizados pelo nome (os ids usados pelas builds salvas não mudam); itens fora do arquivo não são tocados.',
    overwrite: 'sobrescrever também itens editados manualmente', overwriteConfirm: 'Isso vai sobrescrever itens corrigidos manualmente. Continuar?',
    reimport: 'Reimportar', reimported: 'Catálogo reimportado.',
    report: 'Patch {patch}: {ins} inseridos, {upd} atualizados, {skip} preservados (editados), {stats} status.',
  },
  en: {
    items: 'Items', ornn: 'Ornn & Living Forge', xp: 'XP table', prices: 'Price per stat', catalog: 'Re-import catalog',
    saved: 'Saved.', error: 'Error: {msg}', close: 'close', save: 'Save', del: 'Delete', search: 'Search…', newItem: '+ New',
    pickHint: 'Select an item to edit. Edited items (✎) are not overwritten by a re-import.',
    editing: 'Edit #{id}', creating: 'New item', deleteConfirm: 'Delete "{name}"?', deleted: 'Item deleted.',
    name: 'Name', cost: 'Total cost', tabs: 'Shop tabs (comma separated)', section: 'Section', marker: 'Patch badge',
    active: 'active', summary: 'Summary', passivesText: 'Passive texts (as in the shop{capture})', from: ', from {file}',
    secAprimorado: 'Upgraded', secTier: 'Mid tier', secBasico: 'Basic', secPrep: 'Support starter item', secEvo: 'Evolution (not shown in the grid)',
    mNovo: 'new', mReform: 'reworked', mAlter: 'changed',
    statLines: 'Stat lines',
    statHelp: 'Plain line: value (and lv15 when the shop shows a range by level). Percentage passive: ratio (0.3 = 30%) + ref_type. Scope TOTAL = ratio × (base + bonus); BONUS = bonus only; BASE = base stat only. Cond. = conditional effect, switched on or off per purchase.',
    type: 'Type', value: 'Value', lv15: 'lv15', passive: 'Passive', ratio: 'Ratio', scope: 'Scope', cond: 'Cond.',
    lv15Aria: 'Value at level 15', condAria: 'Conditional', removeLine: 'Remove line', addLine: '+ Line',
    recipe: 'Recipe (components)', recipeHelp: 'When this item is bought, owned components are consumed and discounted from its price.',
    qty: 'Quantity', removeComp: 'Remove component', addComp: '+ Component',
    ornnTitle: 'Ornn base stats', ornnHelp: 'Value at level L = base + growth × (L − 1).',
    stat: 'Stat', base: 'Base', growth: 'Growth/level', lv15col: 'Level 15',
    forgeTitle: 'Living Forge', forgeHelp: 'Percentage on bonus health, armor and MR from the given level on. Applied last.',
    fromLevel: 'From level', pctCol: 'Percentage (0.07 = 7%)', removeTier: 'Remove tier', addTier: '+ Tier',
    xpTitle: 'Cumulative XP per level',
    xpNote: 'Estimate. There is no reliable public Wild Rift XP table. Initial values are the first 15 levels of the LoL PC curve (280 XP for level 2, +100 per level). Adjust them from your own games.',
    level: 'Level', cumXp: 'Cumulative XP to reach', levelXp: 'XP of this level', loading: 'Loading…',
    pricesTitle: 'Gold price per stat point',
    pricesHelp: 'Methodology of changchiyou/wildrift-gold-efficiency: first = base item with a single stat (cost ÷ amount); second = item with two stats, minus the known value of the others; exclude = fixed price; alias = same price as another stat, times the factor (Ultimate Haste = 0.25 × AH; Shield and Heal = Health). Base items use the shop (pt-BR) names.',
    relevant: 'Relevant', baseType: 'Base', baseItem: 'Base item', fixed: 'Fixed price', alias: 'Alias', factor: 'Factor', perPoint: 'Gold/point', formula: 'Formula',
    sample: 'Static efficiency of Duplaguarda de Amaranto with these prices: {pct}',
    catTitle: 'Re-import the shop catalog',
    catHelp: 'Reads backend/src/main/resources/seed/loja_7_3.yml and precos_status.yml again. Items are updated by name (ids used by saved builds do not change); items missing from the file are left alone.',
    overwrite: 'also overwrite items edited by hand', overwriteConfirm: 'This overwrites items corrected by hand. Continue?',
    reimport: 'Re-import', reimported: 'Catalog re-imported.',
    report: 'Patch {patch}: {ins} inserted, {upd} updated, {skip} kept (edited), {stats} stats.',
  },
};

function a(key, vars) {
  let s = (currentLang() === 'en' ? L.en : L.pt)[key] ?? L.pt[key] ?? key;
  if (vars) for (const [k, v] of Object.entries(vars)) s = s.split(`{${k}}`).join(String(v));
  return s;
}

const SUBTABS = ['items', 'ornn', 'xp', 'prices', 'catalog'];

export default function Admin({ meta, items, onChanged }) {
  const [tab, setTab] = useState('items');
  const [msg, setMsg] = useState(null);

  const run = async (fn, ok = a('saved')) => {
    try {
      const r = await fn();
      await onChanged();
      setMsg(ok);
      return r;
    } catch (e) {
      setMsg(a('error', { msg: e.message }));
      return undefined;
    }
  };

  return (
    <div className="main">
      <div className="subtabs">
        {SUBTABS.map((k) => (
          <button key={k} className={tab === k ? 'active' : ''} onClick={() => { setTab(k); setMsg(null); }}>{a(k)}</button>
        ))}
      </div>
      {msg && (
        <div className="notice spread"><span>{msg}</span><button className="link" onClick={() => setMsg(null)}>{a('close')}</button></div>
      )}
      {tab === 'items' && <ItemsAdmin items={items} run={run} />}
      {tab === 'ornn' && <OrnnAdmin meta={meta} run={run} />}
      {tab === 'xp' && <XpAdmin meta={meta} run={run} />}
      {tab === 'prices' && <PricesAdmin run={run} items={items} />}
      {tab === 'catalog' && <CatalogAdmin run={run} />}
    </div>
  );
}

// ----------------------------------------------------------------- items

const blankItem = { id: null, name: '', cost: 0, category: 'Defesa', tabs: ['Defesa'], section: 'aprimorado', active: false, marker: null, summary: '', passives: [], stats: [], components: [] };
const SECTIONS = [['aprimorado', 'secAprimorado'], ['tier_medio', 'secTier'], ['basico', 'secBasico'], ['preparacao', 'secPrep'], ['evolucao', 'secEvo']];
const num = (v) => (v === '' || v == null ? null : Number(v));

function ItemsAdmin({ items, run }) {
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState(null);
  const statTypes = useMemo(() => [...new Set(items.flatMap((i) => i.stats.map((s) => s.type)))].sort(), [items]);
  const list = items
    .filter((i) => !query || i.name.toLowerCase().includes(query.toLowerCase()))
    .sort((x, y) => x.name.localeCompare(y.name));

  const save = async () => {
    const tabs = (typeof editing.tabs === 'string' ? editing.tabs.split(',') : editing.tabs ?? []).map((x) => x.trim()).filter(Boolean);
    const body = {
      ...editing,
      cost: Number(editing.cost),
      tabs,
      category: tabs.join(', '),
      marker: editing.marker || null,
      stats: editing.stats.map((s) => ({
        ...s,
        value: num(s.value),
        valueMax: num(s.valueMax),
        ratio: num(s.ratio),
        ref: num(s.ref),
        refType: s.refType || null,
        passive: s.passive || null,
      })),
    };
    const saved = await run(() => (editing.id ? api.put(`/api/admin/items/${editing.id}`, body) : api.post('/api/admin/items', body)));
    if (saved) setEditing(saved);
  };

  const remove = async () => {
    if (!window.confirm(a('deleteConfirm', { name: editing.name }))) return;
    const ok = await run(() => api.del(`/api/admin/items/${editing.id}`), a('deleted'));
    if (ok !== undefined) setEditing(null);
  };

  const setStat = (idx, patch) => setEditing((e) => ({ ...e, stats: e.stats.map((s, k) => (k === idx ? { ...s, ...patch } : s)) }));
  const setComp = (idx, patch) => setEditing((e) => ({ ...e, components: e.components.map((c, k) => (k === idx ? { ...c, ...patch } : c)) }));

  return (
    <div className="grid-2" style={{ gridTemplateColumns: 'minmax(260px, 1fr) minmax(0, 3fr)' }}>
      <section className="panel">
        <div className="row" style={{ marginBottom: 8 }}>
          <input type="search" placeholder={a('search')} value={query} onChange={(e) => setQuery(e.target.value)} style={{ flex: 1 }} aria-label={a('search')} />
          <button onClick={() => setEditing({ ...blankItem })}>{a('newItem')}</button>
        </div>
        <div className="picker-list" style={{ maxHeight: 640 }}>
          {list.map((i) => (
            <div key={i.id} className="picker-item" style={{ cursor: 'pointer', background: editing?.id === i.id ? 'var(--surface-2)' : undefined }}
              onClick={() => setEditing(JSON.parse(JSON.stringify(i)))}>
              <span className="with-icon"><ItemIcon id={i.id} size={22} />{i.name}{i.edited && <small> ✎</small>}</span>
              <small>{n0(i.cost)}g</small>
              <span />
            </div>
          ))}
        </div>
      </section>

      <section className="panel">
        {!editing && <p className="muted">{a('pickHint')}</p>}
        {editing && (
          <>
            <div className="spread">
              <h3>{editing.id ? a('editing', { id: editing.id }) : a('creating')}</h3>
              <div className="row">
                {editing.id && <button className="danger" onClick={remove}>{a('del')}</button>}
                <button className="primary" onClick={save}>{a('save')}</button>
              </div>
            </div>
            <div className="fields">
              <label className="field">{a('name')}<input value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} /></label>
              <label className="field">{a('cost')}<input type="number" value={editing.cost} onChange={(e) => setEditing({ ...editing, cost: e.target.value })} /></label>
              <label className="field">{a('tabs')}
                <input value={Array.isArray(editing.tabs) ? editing.tabs.join(', ') : editing.tabs ?? ''} onChange={(e) => setEditing({ ...editing, tabs: e.target.value })} />
              </label>
              <label className="field">{a('section')}
                <select value={editing.section ?? 'aprimorado'} onChange={(e) => setEditing({ ...editing, section: e.target.value })}>
                  {SECTIONS.map(([v, l]) => <option key={v} value={v}>{a(l)}</option>)}
                </select>
              </label>
              <label className="field">{a('marker')}
                <select value={editing.marker ?? ''} onChange={(e) => setEditing({ ...editing, marker: e.target.value })}>
                  <option value="">—</option>
                  <option value="novo">{a('mNovo')}</option><option value="reformulado">{a('mReform')}</option><option value="alterado">{a('mAlter')}</option>
                </select>
              </label>
              <label className="check" style={{ alignSelf: 'end' }}>
                <input type="checkbox" checked={!!editing.active} onChange={(e) => setEditing({ ...editing, active: e.target.checked })} /> {a('active')}
              </label>
            </div>
            <label className="field" style={{ marginTop: 10 }}>{a('summary')}
              <input value={editing.summary ?? ''} onChange={(e) => setEditing({ ...editing, summary: e.target.value })} />
            </label>
            {editing.passives?.length > 0 && (
              <details style={{ marginTop: 10 }}>
                <summary className="muted">{a('passivesText', { capture: editing.capture ? a('from', { file: editing.capture }) : '' })}</summary>
                {editing.passives.map((p, i) => <p className="passive" key={i}>{p.name && <strong>{p.name}: </strong>}{p.text}</p>)}
              </details>
            )}
            <datalist id="stattypes">{statTypes.map((c) => <option key={c} value={c} />)}</datalist>

            <h4 style={{ marginTop: 14 }}>{a('statLines')}</h4>
            <p className="muted" style={{ marginTop: 0 }}>{a('statHelp')}</p>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th className="l">{a('type')}</th><th>{a('value')}</th><th>{a('lv15')}</th><th className="l">{a('passive')}</th>
                    <th>{a('ratio')}</th><th className="l">ref_type</th><th>{a('scope')}</th><th>{a('cond')}</th><th />
                  </tr>
                </thead>
                <tbody>
                  {editing.stats.map((s, idx) => (
                    <tr key={idx}>
                      <td style={{ minWidth: 170 }}><input list="stattypes" value={s.type ?? ''} onChange={(e) => setStat(idx, { type: e.target.value })} /></td>
                      <td style={{ minWidth: 80 }}><input type="number" value={s.value ?? ''} onChange={(e) => setStat(idx, { value: e.target.value })} /></td>
                      <td style={{ minWidth: 70 }}><input type="number" value={s.valueMax ?? ''} onChange={(e) => setStat(idx, { valueMax: e.target.value })} aria-label={a('lv15Aria')} /></td>
                      <td style={{ minWidth: 110 }}><input value={s.passive ?? ''} onChange={(e) => setStat(idx, { passive: e.target.value })} /></td>
                      <td style={{ minWidth: 70 }}><input type="number" step="0.01" value={s.ratio ?? ''} onChange={(e) => setStat(idx, { ratio: e.target.value })} /></td>
                      <td style={{ minWidth: 150 }}><input list="stattypes" value={s.refType ?? ''} onChange={(e) => setStat(idx, { refType: e.target.value })} /></td>
                      <td>
                        <select value={s.refScope ?? 'TOTAL'} onChange={(e) => setStat(idx, { refScope: e.target.value })}>
                          <option>TOTAL</option><option>BONUS</option><option>BASE</option>
                        </select>
                      </td>
                      <td><input type="checkbox" checked={!!s.conditional} onChange={(e) => setStat(idx, { conditional: e.target.checked })} aria-label={a('condAria')} /></td>
                      <td><button className="icon danger" aria-label={a('removeLine')} onClick={() => setEditing({ ...editing, stats: editing.stats.filter((_, k) => k !== idx) })}>✕</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <button style={{ marginTop: 6 }} onClick={() => setEditing({ ...editing, stats: [...editing.stats, { type: '', value: '', refScope: 'TOTAL' }] })}>{a('addLine')}</button>

            <h4 style={{ marginTop: 14 }}>{a('recipe')}</h4>
            <p className="muted" style={{ marginTop: 0 }}>{a('recipeHelp')}</p>
            {editing.components.map((c, idx) => (
              <div className="row" key={idx} style={{ marginBottom: 4 }}>
                <select value={c.itemId} onChange={(e) => setComp(idx, { itemId: Number(e.target.value) })} style={{ flex: 1 }}>
                  {items.filter((i) => i.id !== editing.id).sort((x, y) => x.name.localeCompare(y.name)).map((i) => (
                    <option key={i.id} value={i.id}>{i.name} ({n0(i.cost)}g)</option>
                  ))}
                </select>
                <input type="number" min="1" style={{ width: 70 }} value={c.quantity} onChange={(e) => setComp(idx, { quantity: Number(e.target.value) })} aria-label={a('qty')} />
                <button className="icon danger" aria-label={a('removeComp')} onClick={() => setEditing({ ...editing, components: editing.components.filter((_, k) => k !== idx) })}>✕</button>
              </div>
            ))}
            <button onClick={() => setEditing({ ...editing, components: [...editing.components, { itemId: items[0]?.id, quantity: 1 }] })}>{a('addComp')}</button>
          </>
        )}
      </section>
    </div>
  );
}

// ----------------------------------------------------------------- Ornn + forge

function OrnnAdmin({ meta, run }) {
  const ornn = meta.units.find((u) => u.code === 'ORNN');
  const [stats, setStats] = useState(() => JSON.parse(JSON.stringify(ornn.stats)));
  const [tiers, setTiers] = useState(() => meta.forgeTiers.map((x) => ({ ...x })));

  const saveStats = () => {
    const body = { name: ornn.name, stats: Object.fromEntries(Object.entries(stats).map(([k, v]) => [k, { base: Number(v.base), growth: Number(v.growth) }])) };
    run(() => api.put('/api/admin/units/ORNN', body));
  };
  const saveTiers = () => run(() => api.put('/api/admin/forge', tiers.map((x) => ({ minLevel: Number(x.minLevel), pct: Number(x.pct) }))));

  return (
    <div className="grid-2">
      <section className="panel">
        <div className="spread"><h3>{a('ornnTitle')}</h3><button className="primary" onClick={saveStats}>{a('save')}</button></div>
        <p className="muted" style={{ marginTop: 0 }}>{a('ornnHelp')}</p>
        <table className="data">
          <thead><tr><th className="l">{a('stat')}</th><th>{a('base')}</th><th>{a('growth')}</th><th>{a('lv15col')}</th></tr></thead>
          <tbody>
            {Object.entries(stats).map(([k, v]) => (
              <tr key={k}>
                <td className="l"><StatIcon stat={k} />{statLabel(k)}</td>
                <td><input type="number" value={v.base} onChange={(e) => setStats({ ...stats, [k]: { ...v, base: e.target.value } })} /></td>
                <td><input type="number" value={v.growth} onChange={(e) => setStats({ ...stats, [k]: { ...v, growth: e.target.value } })} /></td>
                <td>{n2(Number(v.base) + Number(v.growth) * 14)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <section className="panel">
        <div className="spread"><h3>{a('forgeTitle')}</h3><button className="primary" onClick={saveTiers}>{a('save')}</button></div>
        <p className="muted" style={{ marginTop: 0 }}>{a('forgeHelp')}</p>
        <table className="data">
          <thead><tr><th>{a('fromLevel')}</th><th>{a('pctCol')}</th><th /></tr></thead>
          <tbody>
            {tiers.map((x, idx) => (
              <tr key={idx}>
                <td><input type="number" value={x.minLevel} onChange={(e) => setTiers(tiers.map((y, k) => (k === idx ? { ...y, minLevel: e.target.value } : y)))} /></td>
                <td><input type="number" step="0.01" value={x.pct} onChange={(e) => setTiers(tiers.map((y, k) => (k === idx ? { ...y, pct: e.target.value } : y)))} /></td>
                <td><button className="icon danger" aria-label={a('removeTier')} onClick={() => setTiers(tiers.filter((_, k) => k !== idx))}>✕</button></td>
              </tr>
            ))}
          </tbody>
        </table>
        <button style={{ marginTop: 6 }} onClick={() => setTiers([...tiers, { minLevel: '', pct: '' }])}>{a('addTier')}</button>
      </section>
    </div>
  );
}

// ----------------------------------------------------------------- XP

function XpAdmin({ meta, run }) {
  const [rows, setRows] = useState(() => Object.entries(meta.xpTable).map(([level, xp]) => ({ level: Number(level), xp })));
  const save = () => run(() => api.put('/api/admin/xp', Object.fromEntries(rows.map((r) => [r.level, Number(r.xp)]))));
  return (
    <section className="panel" style={{ maxWidth: 560 }}>
      <div className="spread"><h3>{a('xpTitle')}</h3><button className="primary" onClick={save}>{a('save')}</button></div>
      <div className="notice">{a('xpNote')}</div>
      <table className="data">
        <thead><tr><th>{a('level')}</th><th>{a('cumXp')}</th><th>{a('levelXp')}</th></tr></thead>
        <tbody>
          {rows.map((r, idx) => (
            <tr key={r.level}>
              <td>{r.level}</td>
              <td><input type="number" value={r.xp} disabled={r.level === 1} onChange={(e) => setRows(rows.map((x, k) => (k === idx ? { ...x, xp: e.target.value } : x)))} /></td>
              <td>{idx === 0 ? '—' : n0(Number(r.xp) - Number(rows[idx - 1].xp))}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

// ----------------------------------------------------------------- prices

function PricesAdmin({ run, items }) {
  const [defs, setDefs] = useState(null);
  const [prices, setPrices] = useState(null);

  const load = async () => {
    const [d, p] = await Promise.all([api.get('/api/admin/stat-defs'), api.get('/api/stat-prices')]);
    setDefs(d);
    setPrices(p);
  };
  useEffect(() => { load(); }, [items]);

  if (!defs || !prices) return <p className="muted">{a('loading')}</p>;
  const set = (idx, patch) => setDefs(defs.map((d, k) => (k === idx ? { ...d, ...patch } : d)));
  const save = async () => {
    await run(() => api.put('/api/admin/stat-defs', defs.map((d) => ({ ...d, fixedPrice: d.fixedPrice === '' || d.fixedPrice == null ? null : Number(d.fixedPrice), baseType: d.baseType || null, baseItem: d.baseItem || null, alias: d.alias || null, factor: d.factor === '' || d.factor == null ? null : Number(d.factor) }))));
    await load();
  };

  return (
    <section className="panel">
      <div className="spread"><h3>{a('pricesTitle')}</h3><button className="primary" onClick={save}>{a('save')}</button></div>
      <p className="muted" style={{ marginTop: 0 }}>{a('pricesHelp')}</p>
      {prices.problems.length > 0 && <div className="notice"><ul>{prices.problems.map((p) => <li key={p}>{p}</li>)}</ul></div>}
      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th className="l">{a('stat')}</th><th>{a('relevant')}</th><th>{a('baseType')}</th><th className="l">{a('baseItem')}</th>
              <th>{a('fixed')}</th><th className="l">{a('alias')}</th><th>{a('factor')}</th><th>{a('perPoint')}</th><th className="l">{a('formula')}</th>
            </tr>
          </thead>
          <tbody>
            {defs.map((d, idx) => {
              const p = prices.prices[d.name];
              return (
                <tr key={d.name}>
                  <td className="l"><StatIcon stat={d.name} />{statLabel(d.name)}</td>
                  <td><input type="checkbox" checked={d.relevant} onChange={(e) => set(idx, { relevant: e.target.checked })} aria-label={a('relevant')} /></td>
                  <td>
                    <select value={d.baseType ?? ''} onChange={(e) => set(idx, { baseType: e.target.value })}>
                      <option value="">—</option><option>first</option><option>second</option><option>exclude</option>
                    </select>
                  </td>
                  <td className="l" style={{ minWidth: 200 }}><input value={d.baseItem ?? ''} onChange={(e) => set(idx, { baseItem: e.target.value })} /></td>
                  <td style={{ minWidth: 80 }}><input type="number" value={d.fixedPrice ?? ''} onChange={(e) => set(idx, { fixedPrice: e.target.value })} /></td>
                  <td className="l" style={{ minWidth: 120 }}><input value={d.alias ?? ''} onChange={(e) => set(idx, { alias: e.target.value })} /></td>
                  <td style={{ minWidth: 70 }}><input type="number" step="0.05" value={d.factor ?? ''} placeholder="1" disabled={!d.alias} onChange={(e) => set(idx, { factor: e.target.value })} /></td>
                  <td><strong>{p ? n2(p.price) : '—'}</strong></td>
                  <td className="l formula">{p ? `${p.formula} (${p.method})` : ''}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="muted">{a('sample', { pct: pct(items.find((i) => i.name === 'Duplaguarda de Amaranto')?.staticPct) })}</p>
    </section>
  );
}

// ----------------------------------------------------------------- catalog

function CatalogAdmin({ run }) {
  const [report, setReport] = useState(null);
  const [overwrite, setOverwrite] = useState(false);
  const reimport = async () => {
    if (overwrite && !window.confirm(a('overwriteConfirm'))) return;
    const r = await run(() => api.post(`/api/admin/catalog/reimport?overwriteEdited=${overwrite}`), a('reimported'));
    if (r) setReport(r);
  };
  return (
    <section className="panel" style={{ maxWidth: 640 }}>
      <h3>{a('catTitle')}</h3>
      <p>{a('catHelp')}</p>
      <label className="check"><input type="checkbox" checked={overwrite} onChange={(e) => setOverwrite(e.target.checked)} /> {a('overwrite')}</label>
      <div style={{ marginTop: 10 }}><button className="primary" onClick={reimport}>{a('reimport')}</button></div>
      {report && (
        <p>{a('report', { patch: report.patch, ins: report.inserted, upd: report.updated, skip: report.skippedEdited, stats: report.statDefs })}</p>
      )}
    </section>
  );
}
