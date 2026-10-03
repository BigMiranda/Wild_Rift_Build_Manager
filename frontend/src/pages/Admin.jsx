import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import { n0, n2, pct, statLabel } from '../format.js';
import ItemIcon from '../components/ItemIcon.jsx';

const SUBTABS = [
  ['items', 'Itens'],
  ['ornn', 'Ornn & Forja Viva'],
  ['xp', 'Tabela de XP'],
  ['prices', 'Preço por status'],
  ['catalog', 'Reimportar catálogo'],
];

export default function Admin({ meta, items, onChanged }) {
  const [tab, setTab] = useState('items');
  const [msg, setMsg] = useState(null);

  const run = async (fn, ok = 'Salvo.') => {
    try {
      const r = await fn();
      await onChanged();
      setMsg(ok);
      return r;
    } catch (e) {
      setMsg(`Erro: ${e.message}`);
      return undefined;
    }
  };

  return (
    <div className="main">
      <div className="subtabs">
        {SUBTABS.map(([k, l]) => (
          <button key={k} className={tab === k ? 'active' : ''} onClick={() => { setTab(k); setMsg(null); }}>{l}</button>
        ))}
      </div>
      {msg && (
        <div className="notice spread"><span>{msg}</span><button className="link" onClick={() => setMsg(null)}>fechar</button></div>
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
const SECTION_OPTIONS = [['aprimorado', 'Aprimorado'], ['tier_medio', 'Tier médio'], ['basico', 'Básico'], ['preparacao', 'Item inicial de suporte'], ['evolucao', 'Evolução (não aparece na grade)']];
const num = (v) => (v === '' || v == null ? null : Number(v));

function ItemsAdmin({ items, run }) {
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState(null);
  const statTypes = useMemo(() => [...new Set(items.flatMap((i) => i.stats.map((s) => s.type)))].sort(), [items]);
  const list = items
    .filter((i) => !query || i.name.toLowerCase().includes(query.toLowerCase()))
    .sort((a, b) => a.name.localeCompare(b.name));

  const save = async () => {
    const tabs = (typeof editing.tabs === 'string' ? editing.tabs.split(',') : editing.tabs ?? []).map((t) => t.trim()).filter(Boolean);
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
    if (!window.confirm(`Apagar "${editing.name}"?`)) return;
    const ok = await run(() => api.del(`/api/admin/items/${editing.id}`), 'Item apagado.');
    if (ok !== undefined) setEditing(null);
  };

  const setStat = (idx, patch) => setEditing((e) => ({ ...e, stats: e.stats.map((s, k) => (k === idx ? { ...s, ...patch } : s)) }));
  const setComp = (idx, patch) => setEditing((e) => ({ ...e, components: e.components.map((c, k) => (k === idx ? { ...c, ...patch } : c)) }));

  return (
    <div className="grid-2" style={{ gridTemplateColumns: 'minmax(260px, 1fr) minmax(0, 3fr)' }}>
      <section className="panel">
        <div className="row" style={{ marginBottom: 8 }}>
          <input type="search" placeholder="Buscar…" value={query} onChange={(e) => setQuery(e.target.value)} style={{ flex: 1 }} aria-label="Buscar item" />
          <button onClick={() => setEditing({ ...blankItem })}>+ Novo</button>
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
        {!editing && <p className="muted">Selecione um item para editar. Itens editados (✎) não são sobrescritos pela reimportação.</p>}
        {editing && (
          <>
            <div className="spread">
              <h3>{editing.id ? `Editar #${editing.id}` : 'Novo item'}</h3>
              <div className="row">
                {editing.id && <button className="danger" onClick={remove}>Apagar</button>}
                <button className="primary" onClick={save}>Salvar</button>
              </div>
            </div>
            <div className="fields">
              <label className="field">Nome<input value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} /></label>
              <label className="field">Custo total<input type="number" value={editing.cost} onChange={(e) => setEditing({ ...editing, cost: e.target.value })} /></label>
              <label className="field">Abas da loja (separadas por vírgula)
                <input value={Array.isArray(editing.tabs) ? editing.tabs.join(', ') : editing.tabs ?? ''} onChange={(e) => setEditing({ ...editing, tabs: e.target.value })} />
              </label>
              <label className="field">Seção
                <select value={editing.section ?? 'aprimorado'} onChange={(e) => setEditing({ ...editing, section: e.target.value })}>
                  {SECTION_OPTIONS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                </select>
              </label>
              <label className="field">Selo do patch
                <select value={editing.marker ?? ''} onChange={(e) => setEditing({ ...editing, marker: e.target.value })}>
                  <option value="">—</option><option value="novo">novo</option><option value="reformulado">reformulado</option><option value="alterado">alterado</option>
                </select>
              </label>
              <label className="check" style={{ alignSelf: 'end' }}>
                <input type="checkbox" checked={!!editing.active} onChange={(e) => setEditing({ ...editing, active: e.target.checked })} /> ativável
              </label>
            </div>
            <label className="field" style={{ marginTop: 10 }}>Resumo
              <input value={editing.summary ?? ''} onChange={(e) => setEditing({ ...editing, summary: e.target.value })} />
            </label>
            {editing.passives?.length > 0 && (
              <details style={{ marginTop: 10 }}>
                <summary className="muted">Texto das passivas (como na loja{editing.capture ? `, de ${editing.capture}` : ''})</summary>
                {editing.passives.map((p, i) => <p className="passive" key={i}>{p.name && <strong>{p.name}: </strong>}{p.text}</p>)}
              </details>
            )}
            <datalist id="stattypes">{statTypes.map((c) => <option key={c} value={c} />)}</datalist>

            <h4 style={{ marginTop: 14 }}>Linhas de status</h4>
            <p className="muted" style={{ marginTop: 0 }}>
              Linha plana: <em>valor</em> (e <em>nv15</em> quando a loja mostra uma faixa por nível). Passiva percentual:
              <em> ratio</em> (0,3 = 30%) + <em>ref_type</em>. Escopo TOTAL = ratio × (base + bônus); BONUS = só bônus
              (“adicional” na loja); BASE = só o status base. <em>Cond.</em> = só vale quando a build liga efeitos condicionais.
            </p>
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr><th className="l">Tipo</th><th>Valor</th><th>nv15</th><th className="l">Passiva</th><th>Ratio</th><th className="l">ref_type</th><th>Escopo</th><th>Cond.</th><th /></tr>
                </thead>
                <tbody>
                  {editing.stats.map((s, idx) => (
                    <tr key={idx}>
                      <td style={{ minWidth: 170 }}><input list="stattypes" value={s.type ?? ''} onChange={(e) => setStat(idx, { type: e.target.value })} /></td>
                      <td style={{ minWidth: 80 }}><input type="number" value={s.value ?? ''} onChange={(e) => setStat(idx, { value: e.target.value })} /></td>
                      <td style={{ minWidth: 70 }}><input type="number" value={s.valueMax ?? ''} onChange={(e) => setStat(idx, { valueMax: e.target.value })} aria-label="Valor no nível 15" /></td>
                      <td style={{ minWidth: 110 }}><input value={s.passive ?? ''} onChange={(e) => setStat(idx, { passive: e.target.value })} /></td>
                      <td style={{ minWidth: 70 }}><input type="number" step="0.01" value={s.ratio ?? ''} onChange={(e) => setStat(idx, { ratio: e.target.value })} /></td>
                      <td style={{ minWidth: 150 }}><input list="stattypes" value={s.refType ?? ''} onChange={(e) => setStat(idx, { refType: e.target.value })} /></td>
                      <td>
                        <select value={s.refScope ?? 'TOTAL'} onChange={(e) => setStat(idx, { refScope: e.target.value })}>
                          <option>TOTAL</option><option>BONUS</option><option>BASE</option>
                        </select>
                      </td>
                      <td><input type="checkbox" checked={!!s.conditional} onChange={(e) => setStat(idx, { conditional: e.target.checked })} aria-label="Condicional" /></td>
                      <td><button className="icon danger" aria-label="Remover linha" onClick={() => setEditing({ ...editing, stats: editing.stats.filter((_, k) => k !== idx) })}>✕</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <button style={{ marginTop: 6 }} onClick={() => setEditing({ ...editing, stats: [...editing.stats, { type: '', value: '', refScope: 'TOTAL' }] })}>+ Linha</button>

            <h4 style={{ marginTop: 14 }}>Receita (componentes)</h4>
            <p className="muted" style={{ marginTop: 0 }}>
              Ao comprar este item, componentes já possuídos são consumidos e descontados do preço.
            </p>
            {editing.components.map((c, idx) => (
              <div className="row" key={idx} style={{ marginBottom: 4 }}>
                <select value={c.itemId} onChange={(e) => setComp(idx, { itemId: Number(e.target.value) })} style={{ flex: 1 }}>
                  {items.filter((i) => i.id !== editing.id).sort((a, b) => a.name.localeCompare(b.name)).map((i) => (
                    <option key={i.id} value={i.id}>{i.name} ({n0(i.cost)}g)</option>
                  ))}
                </select>
                <input type="number" min="1" style={{ width: 70 }} value={c.quantity} onChange={(e) => setComp(idx, { quantity: Number(e.target.value) })} aria-label="Quantidade" />
                <button className="icon danger" aria-label="Remover componente" onClick={() => setEditing({ ...editing, components: editing.components.filter((_, k) => k !== idx) })}>✕</button>
              </div>
            ))}
            <button onClick={() => setEditing({ ...editing, components: [...editing.components, { itemId: items[0]?.id, quantity: 1 }] })}>+ Componente</button>
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
  const [tiers, setTiers] = useState(() => meta.forgeTiers.map((t) => ({ ...t })));

  const saveStats = () => {
    const body = { name: ornn.name, stats: Object.fromEntries(Object.entries(stats).map(([k, v]) => [k, { base: Number(v.base), growth: Number(v.growth) }])) };
    run(() => api.put('/api/admin/units/ORNN', body));
  };
  const saveTiers = () => run(() => api.put('/api/admin/forge', tiers.map((t) => ({ minLevel: Number(t.minLevel), pct: Number(t.pct) }))));

  return (
    <div className="grid-2">
      <section className="panel">
        <div className="spread"><h3>Status base do Ornn</h3><button className="primary" onClick={saveStats}>Salvar</button></div>
        <p className="muted" style={{ marginTop: 0 }}>Valor no nível L = base + crescimento × (L − 1). Fonte: wiki.leagueoflegends.com/en-us/WR:Ornn.</p>
        <table className="data">
          <thead><tr><th className="l">Status</th><th>Base</th><th>Crescimento/nível</th><th>Nível 15</th></tr></thead>
          <tbody>
            {Object.entries(stats).map(([k, v]) => (
              <tr key={k}>
                <td className="l">{statLabel(k)}</td>
                <td><input type="number" value={v.base} onChange={(e) => setStats({ ...stats, [k]: { ...v, base: e.target.value } })} /></td>
                <td><input type="number" value={v.growth} onChange={(e) => setStats({ ...stats, [k]: { ...v, growth: e.target.value } })} /></td>
                <td>{n2(Number(v.base) + Number(v.growth) * 14)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <section className="panel">
        <div className="spread"><h3>Forja Viva (Living Forge)</h3><button className="primary" onClick={saveTiers}>Salvar</button></div>
        <p className="muted" style={{ marginTop: 0 }}>Percentual sobre vida, armadura e RM <strong>bônus</strong> a partir do nível indicado. Aplicado por último.</p>
        <table className="data">
          <thead><tr><th>A partir do nível</th><th>Percentual (0,07 = 7%)</th><th /></tr></thead>
          <tbody>
            {tiers.map((t, idx) => (
              <tr key={idx}>
                <td><input type="number" value={t.minLevel} onChange={(e) => setTiers(tiers.map((x, k) => (k === idx ? { ...x, minLevel: e.target.value } : x)))} /></td>
                <td><input type="number" step="0.01" value={t.pct} onChange={(e) => setTiers(tiers.map((x, k) => (k === idx ? { ...x, pct: e.target.value } : x)))} /></td>
                <td><button className="icon danger" aria-label="Remover faixa" onClick={() => setTiers(tiers.filter((_, k) => k !== idx))}>✕</button></td>
              </tr>
            ))}
          </tbody>
        </table>
        <button style={{ marginTop: 6 }} onClick={() => setTiers([...tiers, { minLevel: '', pct: '' }])}>+ Faixa</button>
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
      <div className="spread"><h3>XP acumulado por nível</h3><button className="primary" onClick={save}>Salvar</button></div>
      <div className="notice">
        <strong>Estimativa.</strong> Não há tabela pública confiável de XP do Wild Rift. Os valores iniciais são os 15 primeiros
        níveis da curva do LoL de PC (280 XP para o nível 2, +100 a cada nível). Corrija com base na sua experiência de jogo.
      </div>
      <table className="data">
        <thead><tr><th>Nível</th><th>XP acumulado para alcançar</th><th>XP deste nível</th></tr></thead>
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

  if (!defs || !prices) return <p className="muted">Carregando…</p>;
  const set = (idx, patch) => setDefs(defs.map((d, k) => (k === idx ? { ...d, ...patch } : d)));
  const save = async () => {
    await run(() => api.put('/api/admin/stat-defs', defs.map((d) => ({ ...d, fixedPrice: d.fixedPrice === '' || d.fixedPrice == null ? null : Number(d.fixedPrice), baseType: d.baseType || null, baseItem: d.baseItem || null, alias: d.alias || null }))));
    await load();
  };

  return (
    <section className="panel">
      <div className="spread"><h3>Preço em ouro por ponto de status</h3><button className="primary" onClick={save}>Salvar</button></div>
      <p className="muted" style={{ marginTop: 0 }}>
        Metodologia de changchiyou/wildrift-gold-efficiency: <em>first</em> = item-base com um só status (custo ÷ quantidade);
        <em> second</em> = item com dois status, subtraindo o valor já conhecido dos outros; <em>exclude</em> = preço fixo manual;
        <em> alias</em> = mesmo preço de outro status. Linhas marcadas como relevantes são as usadas para o Ornn.
      </p>
      {prices.problems.length > 0 && <div className="notice"><ul>{prices.problems.map((p) => <li key={p}>{p}</li>)}</ul></div>}
      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr><th className="l">Status</th><th>Relevante</th><th>Base</th><th className="l">Item-base</th><th>Preço fixo</th><th className="l">Alias</th><th>Ouro/ponto</th><th className="l">Fórmula</th></tr>
          </thead>
          <tbody>
            {defs.map((d, idx) => {
              const p = prices.prices[d.name];
              return (
                <tr key={d.name}>
                  <td className="l">{d.name}</td>
                  <td><input type="checkbox" checked={d.relevant} onChange={(e) => set(idx, { relevant: e.target.checked })} aria-label="Relevante" /></td>
                  <td>
                    <select value={d.baseType ?? ''} onChange={(e) => set(idx, { baseType: e.target.value })}>
                      <option value="">—</option><option>first</option><option>second</option><option>exclude</option>
                    </select>
                  </td>
                  <td className="l" style={{ minWidth: 200 }}><input value={d.baseItem ?? ''} onChange={(e) => set(idx, { baseItem: e.target.value })} /></td>
                  <td style={{ minWidth: 80 }}><input type="number" value={d.fixedPrice ?? ''} onChange={(e) => set(idx, { fixedPrice: e.target.value })} /></td>
                  <td className="l" style={{ minWidth: 120 }}><input value={d.alias ?? ''} onChange={(e) => set(idx, { alias: e.target.value })} /></td>
                  <td><strong>{p ? n2(p.price) : '—'}</strong></td>
                  <td className="l formula">{p ? `${p.formula} (${p.method})` : ''}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="muted">Eficiência estática do Amaranth's Twinguard (Endurance) com estes preços: {pct(items.find((i) => i.name === "Amaranth's Twinguard (Endurance)")?.staticPct)}</p>
    </section>
  );
}

// ----------------------------------------------------------------- catalog

function CatalogAdmin({ run }) {
  const [report, setReport] = useState(null);
  const [overwrite, setOverwrite] = useState(false);
  const reimport = async () => {
    if (overwrite && !window.confirm('Isso vai sobrescrever itens corrigidos manualmente. Continuar?')) return;
    const r = await run(() => api.post(`/api/admin/catalog/reimport?overwriteEdited=${overwrite}`), 'Catálogo reimportado.');
    if (r) setReport(r);
  };
  return (
    <section className="panel" style={{ maxWidth: 640 }}>
      <h3>Reimportar catálogo dos YAMLs embarcados</h3>
      <p>Relê <code>backend/src/main/resources/seed/items_*.yml</code> e <code>stats_*.yml</code>. Itens são atualizados pelo nome
        (os ids usados pelas builds salvas não mudam). Receitas e itens não presentes no arquivo não são tocados.</p>
      <label className="check"><input type="checkbox" checked={overwrite} onChange={(e) => setOverwrite(e.target.checked)} /> sobrescrever também itens editados manualmente</label>
      <div style={{ marginTop: 10 }}><button className="primary" onClick={reimport}>Reimportar</button></div>
      {report && (
        <p>Patch {report.patch}: {report.inserted} inseridos, {report.updated} atualizados, {report.skippedEdited} preservados (editados), {report.statDefs} status.</p>
      )}
    </section>
  );
}
