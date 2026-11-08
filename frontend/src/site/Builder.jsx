import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import ItemIcon from '../components/ItemIcon.jsx';
import { n0 } from '../format.js';
import { decodeBuild } from '../shareLink.js';
import { keepable, ShareTools } from './BuildPage.jsx';
import { siteHref, useSite } from './StaticApp.jsx';

const SECTIONS = [['aprimorado', 'Itens finais'], ['tier_medio', 'Itens intermediários'], ['basico', 'Básicos'],
  ['evolucao', 'Evoluções'], ['preparacao', 'Preparação']];
const TABS = ['Defesa', 'Mágico', 'Atirador', 'Lutador', 'Assassino', 'Suporte', 'Botas'];
const NO_NUMBERS = 'Os números aparecem quando a build for calculada no app completo.';

const empty = () => ({ unitCode: '', name: '', goldPerMin: 700, xpPerMin: 700, steps: [], runePage: null, spells: null });

/** Lê um arquivo de build (o do app completo ou o deste site) e devolve a build. */
async function readBuildFile(f) {
  const data = JSON.parse(await f.text());
  const b = data?.build ?? data;
  if (!b || !Array.isArray(b.steps) || !b.unitCode) throw new Error('O arquivo não traz uma build.');
  return b;
}

/**
 * Montar: o visitante monta a sequência pela loja e guarda no navegador, exporta ou compartilha. Nada é calculado:
 * só a soma do preço dos itens, que é a da loja.
 */
export function BuilderPage({ params }) {
  const { store } = useSite();
  const [items, setItems] = useState(null);
  const [champions, setChampions] = useState([]);
  const [draft, setDraft] = useState(empty);
  const [section, setSection] = useState('aprimorado');
  const [tab, setTab] = useState('');
  const [text, setText] = useState('');
  const [message, setMessage] = useState(null);
  const file = useRef(null);

  useEffect(() => {
    store.items().then(setItems).catch((e) => setMessage(e.message));
    store.champions().then(setChampions).catch(() => {});
  }, [store]);
  // what to open: a shared link, a build of the site, or one of the visitor's
  useEffect(() => {
    let live = true;
    const open = (b) => { if (live) setDraft({ ...empty(), ...b }); };
    if (params?.b) decodeBuild(params.b).then(open).catch((e) => setMessage(e.message));
    else if (params?.k) store.build(params.k).then((b) => open(keepable(b))).catch((e) => setMessage(e.message));
    else if (params?.id) api.get(`/api/builds/${params.id}`).then(open).catch((e) => setMessage(e.message));
    return () => { live = false; };
  }, [params?.b, params?.k, params?.id, store]);

  const byId = useMemo(() => Object.fromEntries((items ?? []).map((i) => [i.id, i])), [items]);
  const norm = (s) => String(s).normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
  const shown = (items ?? []).filter((i) => (text ? norm(i.name).includes(norm(text)) : i.section === section)
    && (!tab || (i.tabs ?? []).includes(tab)));
  const total = draft.steps.reduce((s, st) => s + (byId[st.itemId]?.cost ?? 0), 0);
  const set = (patch) => setDraft((d) => ({ ...d, ...patch }));
  const add = (id) => setDraft((d) => ({ ...d, steps: [...d.steps, { itemId: id, includeConditional: true }] }));
  const move = (i, d) => {
    const s = [...draft.steps];
    const j = i + d;
    if (j < 0 || j >= s.length) return;
    [s[i], s[j]] = [s[j], s[i]];
    set({ steps: s });
  };
  const remove = (i) => setDraft((d) => ({ ...d, steps: d.steps.filter((_, k) => k !== i) }));
  const ready = draft.unitCode && draft.steps.length > 0;
  const named = { ...draft, name: draft.name.trim() || `${champions.find((c) => c.code === draft.unitCode)?.nome ?? 'Build'} do visitante` };

  const save = async () => {
    try {
      const saved = draft.id != null && Number(draft.id) < 0
        ? await api.put(`/api/builds/${draft.id}`, named)
        : await api.post('/api/builds', { ...named, id: null, folderId: null });
      setDraft({ ...empty(), ...saved });
      setMessage(`Guardada neste navegador como "${saved.name}".`);
    } catch (e) {
      setMessage(e.message);
    }
  };
  const importFile = async (f) => {
    try {
      setDraft({ ...empty(), ...(await readBuildFile(f)), id: null });
      setMessage('Build importada: guarde para mantê-la neste navegador.');
    } catch (e) {
      setMessage(`Não deu para importar: ${e.message}`);
    }
  };

  return (
    <section className="builder">
      <h1 className="site-title">Montar uma build</h1>
      <p className="notice">{NO_NUMBERS}</p>
      <div className="site-filters">
        <label className="site-select"><span>Campeão</span>
          <select value={draft.unitCode} onChange={(e) => set({ unitCode: e.target.value })}>
            <option value="">Escolha…</option>
            {champions.filter((c) => c.code !== 'RAGDOLL').map((c) => <option key={c.code} value={c.code}>{c.nome}</option>)}
          </select>
        </label>
        <label className="site-select"><span>Nome</span>
          <input value={draft.name ?? ''} onChange={(e) => set({ name: e.target.value })} placeholder="Minha build" />
        </label>
        <label className="site-select narrow"><span>Ouro/min</span>
          <input type="number" min="100" max="2000" value={draft.goldPerMin} onChange={(e) => set({ goldPerMin: Number(e.target.value) })} />
        </label>
        <label className="site-select narrow"><span>XP/min</span>
          <input type="number" min="0" max="2000" value={draft.xpPerMin} onChange={(e) => set({ xpPerMin: Number(e.target.value) })} />
        </label>
      </div>

      <h2>Sequência <small className="muted">{draft.steps.length} compra(s) · {n0(total)} de ouro</small></h2>
      {draft.steps.length === 0 && <p className="muted">Toque nos itens da loja abaixo para pôr na sequência.</p>}
      <ol className="sequence">
        {draft.steps.map((s, i) => (
          <li key={i}>
            <ItemIcon id={s.itemId} size={30} />
            <span className="buy-name">{byId[s.itemId]?.name ?? `item ${s.itemId}`}</span>
            <span className="seq-tools">
              <button type="button" onClick={() => move(i, -1)} aria-label="Subir" disabled={i === 0}>↑</button>
              <button type="button" onClick={() => move(i, 1)} aria-label="Descer" disabled={i === draft.steps.length - 1}>↓</button>
              <button type="button" onClick={() => remove(i)} aria-label="Tirar">✕</button>
            </span>
          </li>
        ))}
      </ol>
      <div className="actions">
        <button type="button" className="primary" disabled={!ready} onClick={save}>Guardar no navegador</button>
        {ready && <ShareTools build={named} onMessage={setMessage} noSave />}
        <button type="button" onClick={() => file.current?.click()}>Importar arquivo</button>
        <input ref={file} type="file" accept="application/json,.json" hidden
          onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) importFile(f); }} />
        <button type="button" onClick={() => setDraft(empty())}>Nova</button>
      </div>
      {message && <p className="small">{message}</p>}

      <h2>Loja</h2>
      {!items ? <p className="muted">Carregando a loja…</p> : (
        <>
          <div className="site-filters">
            <input type="search" placeholder="Buscar item" value={text} onChange={(e) => setText(e.target.value)} aria-label="Buscar item" />
            <label className="site-select"><span>Parte</span>
              <select value={section} onChange={(e) => setSection(e.target.value)} disabled={!!text}>
                {SECTIONS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
              </select>
            </label>
            <label className="site-select"><span>Aba</span>
              <select value={tab} onChange={(e) => setTab(e.target.value)}>
                <option value="">Todas</option>
                {TABS.map((x) => <option key={x} value={x}>{x}</option>)}
              </select>
            </label>
          </div>
          <ul className="shop-grid">
            {shown.map((i) => (
              <li key={i.id}>
                <button type="button" className="shop-item" onClick={() => add(i.id)} title={i.summary ?? i.name}>
                  <ItemIcon id={i.id} size={40} />
                  <span>{i.name}</span>
                  <small className="muted">{n0(i.cost)}</small>
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

/** Minhas: as builds guardadas neste navegador (as mesmas da versão pública), com abrir, exportar, link e apagar. */
export function MyBuildsPage() {
  const [list, setList] = useState(null);
  const [message, setMessage] = useState(null);
  const file = useRef(null);
  const load = () => api.get('/api/builds').then((l) => setList((l ?? []).filter((b) => Number(b.id) < 0))).catch((e) => setMessage(e.message));
  useEffect(() => { load(); }, []);
  const remove = async (b) => {
    if (!window.confirm(`Apagar "${b.name}" deste navegador?`)) return;
    await api.del(`/api/builds/${b.id}`);
    load();
  };
  const importFile = async (f) => {
    try {
      const b = await readBuildFile(f);
      const saved = await api.post('/api/builds', { ...b, id: null, folderId: null, createdAt: null, updatedAt: null });
      setMessage(`Importada: "${saved.name}".`);
      load();
    } catch (e) {
      setMessage(`Não deu para importar: ${e.message}`);
    }
  };
  return (
    <section>
      <h1 className="site-title">Minhas builds</h1>
      <p className="muted">Guardadas só neste navegador. Para levar a outro aparelho, exporte o arquivo ou copie o link.</p>
      <div className="actions">
        <a className="btn primary" href={siteHref('montar')}>Montar uma nova</a>
        <button type="button" onClick={() => file.current?.click()}>Importar arquivo</button>
        <input ref={file} type="file" accept="application/json,.json" hidden
          onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) importFile(f); }} />
      </div>
      {message && <p className="small">{message}</p>}
      {list && list.length === 0 && <p className="muted">Nenhuma build guardada ainda.</p>}
      <div className="site-list">
        {(list ?? []).map((b) => <MyBuild key={b.id} id={b.id} onRemove={() => remove(b)} onMessage={setMessage} />)}
      </div>
    </section>
  );
}

function MyBuild({ id, onRemove, onMessage }) {
  const [b, setB] = useState(null);
  useEffect(() => { api.get(`/api/builds/${id}`).then(setB).catch(() => {}); }, [id]);
  if (!b) return null;
  return (
    <article className="site-card build-card">
      <header><strong>{b.name}</strong> <small className="muted">{b.unitCode}</small></header>
      <div className="item-row">{(b.steps ?? []).filter((s) => s.itemId != null).map((s, i) => <ItemIcon key={i} id={s.itemId} size={28} />)}</div>
      <footer className="actions">
        <a className="btn primary" href={siteHref('montar', null, { id })}>Abrir</a>
        <ShareTools build={b} onMessage={onMessage} noSave />
        <button type="button" onClick={onRemove}>Apagar</button>
      </footer>
    </article>
  );
}
