import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.js';
import ItemIcon from '../components/ItemIcon.jsx';
import { useBuild } from '../buildContext.jsx';
import { interactions, note1, sideView } from '../atlasMath.js';

/**
 * Rodada 16, the interaction atlas: runs (a closed fight between two champions, its context fixed by the run), the
 * report of conditions alone, in pairs and in triples, and two guides that rebuild the answer from whatever each side
 * fixes ahead (an item rushed, a keystone, a rune, a spell).
 */

const n1 = (v) => (v == null ? '—' : (Math.round(v * 10) / 10).toLocaleString('pt-BR'));
const n2 = (v) => (v == null ? '—' : (Math.round(v * 100) / 100).toLocaleString('pt-BR'));
const signed = (v) => (v == null ? '—' : `${v > 0 ? '+' : ''}${n1(v)}`);
const SOURCE = { status: 'status', passiva: 'passiva de item', fundamental: 'runa fundamental', runa: 'runa', feitico: 'feitiço' };
const emptyFixed = () => ({ items: [], keystone: null, runes: [], spell: null, conditions: [] });
const tone = (v) => (v > 0.05 ? 'atlas-up' : v < -0.05 ? 'atlas-down' : '');

function RunList({ runs, presets, selected, onSelect, onReload }) {
  const [preset, setPreset] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const keys = Object.keys(presets ?? {});
  useEffect(() => { if (!preset && keys.length) setPreset(keys[0]); }, [keys.join()]);
  const start = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await api.post(`/api/atlas/runs?preset=${encodeURIComponent(preset)}`);
      onSelect(r.id);
      onReload();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };
  const act = async (fn) => { try { await fn(); onReload(); } catch (e) { setError(e.message); } };
  return (
    <section className="panel">
      <h3>Execuções</h3>
      <p className="muted small">Cada execução tem o seu contexto fixado na configuração (seed <code>atlas_7_3d.yml</code>): os dois
        campeões, o modo (x1) e os minutos. A base de cada lado é a build padrão da última campanha, sem itens; as condições entram
        por cima, e as runas e o feitiço por troca.</p>
      <div className="row">
        <select value={preset} onChange={(e) => setPreset(e.target.value)}>
          {keys.map((k) => <option key={k} value={k}>{presets[k].name ?? k}</option>)}
        </select>
        <button onClick={start} disabled={busy || !preset}>Rodar</button>
      </div>
      {error && <p className="error">{error}</p>}
      <table className="data">
        <thead><tr><th>#</th><th>Execução</th><th>Situação</th><th>Etapa</th><th /></tr></thead>
        <tbody>
          {runs.map((r) => (
            <tr key={r.id} className={r.id === selected ? 'selected' : ''}>
              <td>{r.id}</td>
              <td><button className="link" onClick={() => onSelect(r.id)}>{r.name}</button></td>
              <td>{r.status}</td>
              <td>{r.stage ?? ''}{r.total > 0 && r.status === 'rodando' ? ` · ${r.done}/${r.total}` : ''}</td>
              <td>
                {(r.status === 'rodando' || r.status === 'pendente')
                  && <button className="link" onClick={() => act(() => api.post(`/api/atlas/runs/${r.id}/cancel`))}>cancelar</button>}
                {r.status !== 'rodando'
                  && <button className="link" onClick={() => act(() => api.del(`/api/atlas/runs/${r.id}`))}>apagar</button>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/** Names of the conditions of both sides, by side key. */
function useNames(m) {
  return useMemo(() => {
    const out = {};
    for (const [side, s] of [['A', m?.a], ['B', m?.b]]) {
      for (const c of s?.conditions ?? []) out[`${side}:${c.key}`] = c;
    }
    return out;
  }, [m]);
}

function CondName({ k, names, m }) {
  const c = names[k];
  const champ = k.startsWith('A:') ? m.a.champion : m.b.champion;
  return (
    <span className="atlas-cond" title={c ? SOURCE[c.source] : ''}>
      {c?.source === 'passiva' && <ItemIcon id={c.itemId} size={20} />}
      <small className="muted">{champ}:</small> {c?.name ?? k}
    </span>
  );
}

function OneDim({ m, names }) {
  const [source, setSource] = useState('');
  const rows = useMemo(() => {
    const out = [];
    for (const [side, s] of [['A', m.a], ['B', m.b]]) {
      for (const c of s.conditions) {
        const k = `${side}:${c.key}`;
        out.push({ k, side, c, note: sideView(k, note1(m.races, m.base, k)) });
      }
    }
    return out.filter((r) => !source || r.c.source === source);
  }, [m, source]);
  return (
    <>
      <div className="row">
        <label className="inline">Fonte <select value={source} onChange={(e) => setSource(e.target.value)}>
          <option value="">todas</option>
          {Object.entries(SOURCE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select></label>
      </div>
      <p className="muted small">A nota de cada condição sozinha: quanto ela muda a corrida a favor de quem a tem (pontos de 0 a 100;
        50 é empate). Corrida da base, sem nenhuma condição: {m.a.champion} {n1(m.base)} × {n1(100 - m.base)} {m.b.champion}.</p>
      <div className="atlas-two">
        {['A', 'B'].map((side) => (
          <div key={side}>
            <h4>{side === 'A' ? m.a.champion : m.b.champion}</h4>
            <table className="data">
              <tbody>
                {rows.filter((r) => r.side === side).sort((a, b) => (b.note ?? -1e9) - (a.note ?? -1e9)).map((r) => (
                  <tr key={r.k}>
                    <td><CondName k={r.k} names={names} m={m} /></td>
                    <td className="muted small">{SOURCE[r.c.source]}</td>
                    <td className={tone(r.note)}>{signed(r.note)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      </div>
    </>
  );
}

function ManyDims({ m, names, n }) {
  const [kind, setKind] = useState('');
  const all = useMemo(() => interactions(m.races, m.base, n), [m, n]);
  const rows = all.filter((x) => {
    const sides = new Set(x.keys.map((k) => k[0]));
    if (kind === 'A') return sides.size === 1 && sides.has('A');
    if (kind === 'B') return sides.size === 1 && sides.has('B');
    if (kind === 'x') return sides.size === 2;
    return true;
  }).slice(0, 300);
  return (
    <>
      <div className="row">
        <label className="inline">Mostrar <select value={kind} onChange={(e) => setKind(e.target.value)}>
          <option value="">todas</option>
          <option value="A">só de {m.a.champion}</option>
          <option value="B">só de {m.b.champion}</option>
          <option value="x">cruzadas (uma de cada lado)</option>
        </select></label>
        <span className="muted small">{all.length.toLocaleString('pt-BR')} medidas; as 300 maiores</span>
      </div>
      <p className="muted small">A interação é o que {n === 2 ? 'o par' : 'o trio'} faz além {n === 2 ? 'das duas notas sozinhas' : 'das notas e dos pares'}, na
        visão de {m.a.champion} (positivo = a favor dele). O total é a corrida do conjunto menos a da base.</p>
      <table className="data">
        <thead><tr><th>Condições</th><th>Interação</th><th>Total</th></tr></thead>
        <tbody>
          {rows.map((x) => (
            <tr key={x.keys.join('|')}>
              <td>{x.keys.map((k, i) => <span key={k}>{i > 0 && ' + '}<CondName k={k} names={names} m={m} /></span>)}</td>
              <td className={tone(x.value)}>{signed(x.value)}</td>
              <td className={tone(x.total)}>{signed(x.total)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

function Validation({ m, run, onDone, onStarted }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const redo = async () => {
    setBusy(true);
    setError(null);
    try { onDone(await api.post(`/api/atlas/runs/${run.id}/validate`)); } catch (e) { setError(e.message); } finally { setBusy(false); }
  };
  return (
    <>
      <button onClick={redo} disabled={busy}>{busy ? 'Validando…' : 'Refazer a validação'}</button>
      {error && <p className="error">{error}</p>}
      <p className="muted small">Conjuntos sorteados de 3 a 8 condições, nunca medidos, lutados de verdade e comparados com a previsão
        de cada ordem: só as notas sozinhas (1), com os pares (2) e com os trios (3).</p>
      <p className="muted small">Duas escalas: somar as notas em pontos, ou em chances (logit: cada nota muda as chances de vencer,
        e os ganhos encolhem perto de 0 e de 100, como numa corrida em que quem começa a ganhar ganha). O guia usa a de menor
        erro: <strong>{m.scale === 'pontos' ? 'pontos' : 'chances (logit)'}</strong>.</p>
      {m.factors && (
        <p className="small">Previsão ajustada: as notas × {n1(m.factors[0])}, os pares × {n1(m.factors[1])} e os trios × {n1(m.factors[2])}.
          As medidas têm ruído e a corrida satura, então as interações inteiras corrigem demais. Os fatores são escolhidos numa
          metade dos conjuntos e julgados na outra: erro médio {n1(m.fitted?.[0])} pontos, correlação {n1((m.fitted?.[1] ?? 0) * 100)}%.</p>
      )}
      <table className="data">
        <thead><tr><th>Escala</th><th>Ordem</th><th>Correlação</th><th>Erro médio (pontos)</th><th>Conjuntos</th></tr></thead>
        <tbody>
          {(m.validation ?? []).map((v) => (
            <tr key={`${v[4] ?? 0}-${v[0]}`}><td>{(v[4] ?? 0) === 1 ? 'chances (logit)' : 'pontos'}</td><td>{v[0]}</td>
              <td>{n1(v[1] * 100)}%</td><td>{n1(v[2])}</td><td>{v[3]}</td></tr>
          ))}
        </tbody>
      </table>
      <WholeBuilds m={m} run={run} onStarted={onStarted} />
      <p className="muted small">{m.fights.toLocaleString('pt-BR')} lutas em {Math.round(m.seconds / 60)} min (preparar os lutadores:
        {' '}{Math.round(m.prepareSeconds / 60)} min; lutar: {Math.round(m.fightSeconds / 60)} min).</p>
    </>
  );
}

const READINGS = {
  inBuilds: 'nas builds medidas',
  here: 'sobre o que está fixado',
  alone: 'sozinha, sobre a base',
};
const NEUTRAL = 0.5;

function Partners({ row }) {
  const parts = [
    ['sinergia', row.synergies, 'atlas-up'],
    ['sobrepõe', row.overlaps, 'atlas-down'],
    ['anulada por', row.counters, 'atlas-down'],
    ['pune', row.punishes, 'atlas-up'],
  ].filter(([, l]) => l?.length);
  if (!parts.length) return <span className="muted">—</span>;
  return (
    <span className="atlas-partners">
      {parts.map(([label, l, cls]) => (
        <span key={label}><small className="muted">{label}:</small>{' '}
          {l.map((p, i) => <span key={p.key}>{i > 0 && ', '}{p.name} <span className={cls}>{signed(p.value)}</span></span>)}
        </span>
      ))}
    </span>
  );
}

function ConditionTable({ rows, reading }) {
  return (
    <table className="data">
      <thead><tr><th>Condição</th><th>Sozinha</th><th>Nas builds</th><th>Com o fixado</th><th>Por mil de ouro</th><th>Com quem</th></tr></thead>
      <tbody>
        {rows.map((r) => {
          const v = r[reading] ?? r.here;
          return (
            <tr key={r.key}>
              <td><span className="atlas-cond">{r.itemId != null && <ItemIcon id={r.itemId} size={20} />} {r.name}
                <small className="muted"> {SOURCE[r.source]}</small></span></td>
              <td className={tone(r.alone)}>{signed(r.alone)}</td>
              <td className={tone(r.inBuilds)}>{signed(r.inBuilds)}</td>
              <td className={tone(r.here)}>{signed(r.here)}</td>
              <td className={tone(v)}>{r.gold ? signed((v / r.gold) * 1000) : '—'}</td>
              <td className="small"><Partners row={r} /></td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function SideConditions({ r, reading, source }) {
  const rows = r.rows.filter((x) => !source || x.source === source);
  const value = (x) => x[reading] ?? x.here;
  const fav = rows.filter((x) => value(x) > NEUTRAL).sort((x, y) => value(y) - value(x));
  const unf = rows.filter((x) => value(x) < -NEUTRAL).sort((x, y) => value(x) - value(y));
  const neutral = rows.filter((x) => Math.abs(value(x)) <= NEUTRAL);
  return (
    <div>
      <h4>{r.champion} <small className="muted">corrida com o fixado {n1(r.race)}</small></h4>
      <h5>Favoráveis ({fav.length})</h5>
      {fav.length ? <ConditionTable rows={fav} reading={reading} /> : <p className="muted small">Nenhuma.</p>}
      <h5>Desfavoráveis ({unf.length})</h5>
      {unf.length ? <ConditionTable rows={unf} reading={reading} /> : <p className="muted small">Nenhuma.</p>}
      <details className="small">
        <summary>Neutras ({neutral.length}): entre {signed(-NEUTRAL)} e {signed(NEUTRAL)}</summary>
        <p className="muted">{neutral.map((x) => x.name).join(' · ')}</p>
      </details>
    </div>
  );
}

/** The favorable and unfavorable status and passive conditions of each side, from what the guides fixed. */
function Report({ run, m, a, b }) {
  const [rep, setRep] = useState(null);
  const [error, setError] = useState(null);
  const [reading, setReading] = useState('inBuilds');
  const [source, setSource] = useState('');
  useEffect(() => {
    let live = true;
    setError(null);
    api.post(`/api/atlas/runs/${run.id}/report`, { minute: m.minute, a, b })
      .then((x) => { if (live) setRep(x); })
      .catch((e) => { if (live) setError(e.message); });
    return () => { live = false; };
  }, [run.id, m.minute, a, b]);
  const fixed = [a, b].some((f) => f.items.length || f.keystone || f.runes.length || f.spell || f.conditions.length);
  return (
    <>
      <p className="muted small">Cada status e cada passiva de item, de cada lado, e quanto ela muda a corrida a favor de quem a tem
        (pontos; 50 é empate). Três leituras: <strong>sozinha</strong> (a nota medida sobre a base sem itens);{' '}
        <strong>nas builds</strong> (a média do que ela acrescenta em cada build inteira medida, pelo modelo de builds
        inteiras: o que rende numa build de verdade); e <strong>com o fixado</strong> (o que ela acrescenta sobre o que está
        fixado nos guias{fixed ? '' : ': nada fixado agora, então é a base'}). Um status conta um nível a mais; uma passiva,
        tê-la contra não tê-la. "Com quem" são os pares medidos que mudam o valor dela em 1 ponto ou mais: sinergia e
        sobreposição com as condições do mesmo lado, e as do outro lado que a anulam ou que ela pune.</p>
      <div className="row">
        <label className="inline">Separar por <select value={reading} onChange={(e) => setReading(e.target.value)}>
          {Object.entries(READINGS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select></label>
        <label className="inline">Fonte <select value={source} onChange={(e) => setSource(e.target.value)}>
          <option value="">status e passivas</option><option value="status">status</option><option value="passiva">passivas</option>
        </select></label>
        {rep && !rep.model && <span className="small atlas-down">Sem builds inteiras medidas: "nas builds" fica vazio. Meça-as na aba Validação.</span>}
      </div>
      {error && <p className="error">{error}</p>}
      {rep && (
        <div className="atlas-report">
          <SideConditions r={rep.a} reading={reading} source={source} />
          <SideConditions r={rep.b} reading={reading} source={source} />
        </div>
      )}
    </>
  );
}

const ERRORS = [
  ['modelo', 'modelo de builds inteiras (validação cruzada)'],
  ['fatores', 'fatores por ordem (ajustados nos conjuntos)'],
  ['notas', 'só as notas, em chances'],
  ['inteiras', 'notas, pares e trios inteiros, em chances'],
  ['base', 'sempre a corrida da base'],
];

function WholeBuilds({ m, run, onStarted }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const md = m.model;
  const start = async () => {
    setBusy(true);
    setError(null);
    try { await api.post(`/api/atlas/runs/${run.id}/builds`); onStarted(); } catch (e) { setError(e.message); } finally { setBusy(false); }
  };
  const kinds = (m.builds ?? []).reduce((acc, x) => ({ ...acc, [x.kind]: (acc[x.kind] ?? 0) + 1 }), {});
  return (
    <>
      <h4>Builds inteiras</h4>
      <p className="muted small">Builds de verdade lutadas (itens reais nos dois lados, às vezes uma runa fundamental, uma runa ou um
        feitiço trocados): metade sorteada da loja toda, metade entre os melhores itens do guia vazio. Sobre elas se ajusta o
        modelo que os guias usam: as notas, os pares e os trios em chances, com um coeficiente por ordem e uma correção por
        condição (o que as notas não veem dela dentro de uma build), a força das correções escolhida por validação cruzada
        em cinco partes.</p>
      <button onClick={start} disabled={busy}>{busy ? 'Começando…' : md ? 'Medir as builds inteiras de novo' : 'Medir builds inteiras'}</button>
      {error && <p className="error">{error}</p>}
      {md && (
        <>
          <p className="small">{md.samples} builds ({kinds.sorteada ?? 0} sorteadas, {kinds.guiada ?? 0} guiadas). Notas de {m.a.champion} ×{' '}
            {n2(md.coef[0])}, notas de {m.b.champion} × {n2(md.coef[1])}, pares × {n2(md.coef[2])}, trios × {n2(md.coef[3])};{' '}
            {md.lambda == null ? 'sem correções por condição (a validação cruzada preferiu sem)'
              : `${Object.keys(md.theta ?? {}).length} correções por condição (penalidade ${md.lambda})`}.</p>
          <table className="data">
            <thead><tr><th>Previsão das builds medidas</th><th>Erro médio (pontos)</th><th>Correlação</th></tr></thead>
            <tbody>
              {ERRORS.filter(([k]) => md.errors?.[k]).map(([k, label]) => (
                <tr key={k}><td>{label}</td><td>{n1(md.errors[k][0])}</td><td>{n1(md.errors[k][1] * 100)}%</td></tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </>
  );
}

function OptionRow({ o, onPick, perGold }) {
  return (
    <li>
      <button className="link atlas-pick" onClick={onPick} title="Fixar">
        {o.itemId != null && <ItemIcon id={o.itemId} size={22} />} {o.name}
      </button>
      <span className={tone(o.gain)}>{signed(o.gain)}</span>
      {perGold && o.perThousand != null && <small className="muted"> ({signed(o.perThousand)} por mil de ouro)</small>}
    </li>
  );
}

function SideGuide({ side, m, g, fixed, setFixed, itemsList }) {
  const s = side === 'A' ? m.a : m.b;
  const [add, setAdd] = useState('');
  const fix = (patch) => setFixed({ ...fixed, ...patch });
  const keystones = s.conditions.filter((c) => c.source === 'fundamental');
  const runes = s.conditions.filter((c) => c.source === 'runa');
  const spells = s.conditions.filter((c) => c.source === 'feitico');
  return (
    <div className="atlas-guide">
      <h4>{s.champion} <small className="muted">corrida prevista {n1(g?.race)}</small></h4>
      <p className="muted small">Base: {s.baseName} · {s.keystone} · {s.runes.join(', ')} · {s.spells.join(' + ')}</p>
      <div className="atlas-fixed">
        <strong>Fixado</strong>
        <div className="chips">
          {fixed.items.map((id) => {
            const it = itemsList.find((x) => x.id === id);
            return (
              <button key={id} type="button" className="on" onClick={() => fix({ items: fixed.items.filter((x) => x !== id) })} title="Tirar">
                <ItemIcon id={id} size={20} /> {it?.name ?? id} ✕
              </button>
            );
          })}
        </div>
        <div className="row">
          <select value={add} onChange={(e) => { const id = Number(e.target.value); setAdd(''); if (id) fix({ items: [...fixed.items, id] }); }}>
            <option value="">+ item…</option>
            {itemsList.filter((it) => !fixed.items.includes(it.id)).map((it) => <option key={it.id} value={it.id}>{it.name}</option>)}
          </select>
          <select value={fixed.keystone ?? ''} onChange={(e) => fix({ keystone: e.target.value || null })}>
            <option value="">{s.keystone} (base)</option>
            {keystones.map((c) => <option key={c.key} value={c.rune}>{c.name}</option>)}
          </select>
          <select value="" onChange={(e) => e.target.value && fix({ runes: [...fixed.runes, e.target.value] })}>
            <option value="">+ runa…</option>
            {runes.filter((c) => !fixed.runes.includes(c.rune)).map((c) => <option key={c.key} value={c.rune}>{c.name}</option>)}
          </select>
          <select value={fixed.spell ?? ''} onChange={(e) => fix({ spell: e.target.value || null })}>
            <option value="">{s.spells.filter((x) => x !== 'Flash').join('') || 'feitiço'} (base)</option>
            {spells.map((c) => <option key={c.key} value={c.spell}>{c.name}</option>)}
          </select>
        </div>
        {fixed.runes.length > 0 && (
          <div className="chips">
            {fixed.runes.map((r) => <button key={r} type="button" className="on" onClick={() => fix({ runes: fixed.runes.filter((x) => x !== r) })}>{r} ✕</button>)}
          </div>
        )}
      </div>
      {g && (
        <>
          <h5>Build montada pelas notas <small className="muted">corrida {n1(g.buildRace)}</small></h5>
          <div className="atlas-build">
            {g.build.map((o, i) => (
              <span key={`${o.itemId}-${i}`} title={`${o.name}${o.key === 'fixo' ? ' (fixado)' : ` · ${signed(o.gain)}`}`} className={o.key === 'fixo' ? 'fixed' : ''}>
                <ItemIcon id={o.itemId} size={30} />
              </span>
            ))}
          </div>
          <p className="small">{g.buildKeystone}{g.buildRunes.length > 0 && ` · ${g.buildRunes.join(', ')}`}{g.buildSpell && ` · ${g.buildSpell}`}</p>
          <h5>Próximo item <small className="muted">por ganho a cada mil de ouro</small></h5>
          <ul className="atlas-options">
            {g.items.slice(0, 10).map((o) => <OptionRow key={o.key} o={o} perGold onPick={() => fix({ items: [...fixed.items, o.itemId] })} />)}
          </ul>
          <h5>Condições que mais rendem agora</h5>
          <ul className="atlas-options">
            {g.conditions.slice(0, 8).map((o) => <OptionRow key={o.key} o={o} onPick={() => fix({ conditions: [...fixed.conditions, o.key] })} />)}
          </ul>
          <h5>Runas e feitiço</h5>
          <ul className="atlas-options">
            {g.keystones.slice(0, 3).map((o) => <OptionRow key={o.key} o={o} onPick={() => fix({ keystone: o.name })} />)}
            {g.runes.slice(0, 4).map((o) => <OptionRow key={o.key} o={o} onPick={() => fix({ runes: [...fixed.runes, o.name] })} />)}
            {g.spells.slice(0, 2).map((o) => <OptionRow key={o.key} o={o} onPick={() => fix({ spell: o.name })} />)}
          </ul>
        </>
      )}
      {fixed.conditions.length > 0 && (
        <p className="small">Condições fixadas: {fixed.conditions.map((k) => (
          <button key={k} className="link" onClick={() => fix({ conditions: fixed.conditions.filter((x) => x !== k) })}>
            {s.conditions.find((c) => c.key === k)?.name ?? k} ✕
          </button>
        ))}</p>
      )}
    </div>
  );
}

/** The two assembled builds fought for real at the run's minute, next to the prediction. */
function Confirm({ run, m, g, order, scale }) {
  const [res, setRes] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const side = (sg) => ({
    items: sg.build.map((o) => o.itemId),
    keystone: sg.buildKeystone !== (sg === g.a ? m.a.keystone : m.b.keystone) ? sg.buildKeystone : null,
    runes: sg.buildRunes,
    spell: sg.buildSpell ?? null,
    conditions: [],
  });
  const fight = async () => {
    setBusy(true);
    setError(null);
    try {
      setRes(await api.post(`/api/atlas/runs/${run.id}/confirm`, { minute: m.minute, a: side(g.a), b: side(g.b), order, scale }));
    } catch (e) { setError(e.message); } finally { setBusy(false); }
  };
  useEffect(() => { setRes(null); }, [g]);
  return (
    <div className="row">
      <button onClick={fight} disabled={busy}>{busy ? 'Lutando…' : 'Lutar de verdade com as duas builds montadas'}</button>
      {res && <span className="small">Corrida real de {m.a.champion}: <strong>{n1(res.real)}</strong> ± {n1(res.margin)}
        {' '}(prevista {n1(res.predicted)}); {res.starts.length} largadas: {res.starts.map(n1).join(' · ')}</span>}
      {error && <span className="error">{error}</span>}
    </div>
  );
}

function Guides({ run, m, itemsList, a, setA, b, setB }) {
  const [order, setOrder] = useState(0);
  const [scale, setScale] = useState(null);
  const [g, setG] = useState(null);
  const [error, setError] = useState(null);
  useEffect(() => {
    let live = true;
    setError(null);
    api.post(`/api/atlas/runs/${run.id}/guide`, { minute: m.minute, a, b, order, scale })
      .then((x) => { if (live) setG(x); })
      .catch((e) => { if (live) setError(e.message); });
    return () => { live = false; };
  }, [run.id, m.minute, a, b, order, scale]);
  return (
    <>
      <p className="muted small">Fixe um momento à frente em cada lado (itens já comprados, runas, feitiço) e as notas são
        recalculadas a partir daí, pelas notas medidas (sozinhas, pares e trios), sem lutar de novo. Os itens entram pelos status
        deles (espalhados nos níveis medidos) e pela passiva. Clique numa sugestão para fixá-la.</p>
      <div className="row">
        <label className="inline">Previsão <select value={order} onChange={(e) => setOrder(Number(e.target.value))}>
          <option value={0}>{m.model ? 'ajustada (modelo de builds inteiras)' : 'ajustada pela validação'}</option>
          <option value={1}>só as notas (1 dimensão)</option><option value={2}>notas e pares inteiros (2)</option>
          <option value={3}>notas, pares e trios inteiros (3)</option>
        </select></label>
        <label className="inline">Escala <select value={scale ?? ''} onChange={(e) => setScale(e.target.value || null)}>
          <option value="">a da validação ({m.scale === 'pontos' ? 'pontos' : 'chances'})</option>
          <option value="logit">chances (logit)</option><option value="pontos">pontos</option>
        </select></label>
        <button onClick={() => { setA(emptyFixed()); setB(emptyFixed()); }}>Limpar fixações</button>
        {g && <span className="small">Corrida de {m.a.champion}: ajustada {n1(g.byOrder[0])} · 1D {n1(g.byOrder[1])} · 2D {n1(g.byOrder[2])} · 3D {n1(g.byOrder[3])}</span>}
      </div>
      {error && <p className="error">{error}</p>}
      {g && <Confirm run={run} m={m} g={g} order={order} scale={scale} />}
      <div className="atlas-two">
        <SideGuide side="A" m={m} g={g?.a} fixed={a} setFixed={setA} itemsList={itemsList} />
        <SideGuide side="B" m={m} g={g?.b} fixed={b} setFixed={setB} itemsList={itemsList} />
      </div>
    </>
  );
}

export default function Atlas() {
  const { items } = useBuild();
  const [runs, setRuns] = useState([]);
  const [presets, setPresets] = useState({});
  const [selected, setSelected] = useState(null);
  const [run, setRun] = useState(null);
  const [minute, setMinute] = useState(null);
  const [tab, setTab] = useState('guias');
  const [fa, setFa] = useState(emptyFixed);
  const [fb, setFb] = useState(emptyFixed);
  useEffect(() => { setFa(emptyFixed()); setFb(emptyFixed()); }, [selected]);
  const [tick, setTick] = useState(0);
  const reload = () => setTick((t) => t + 1);
  useEffect(() => { api.get('/api/atlas/presets').then(setPresets).catch(() => {}); }, []);
  useEffect(() => {
    api.get('/api/atlas/runs').then((r) => {
      setRuns(r);
      if (selected == null) setSelected(r.find((x) => x.status === 'feito')?.id ?? r[0]?.id ?? null);
    }).catch(() => {});
  }, [tick]);
  useEffect(() => {
    if (!runs.some((r) => r.status === 'rodando' || r.status === 'pendente')) return undefined;
    const t = setTimeout(reload, 5000);
    return () => clearTimeout(t);
  }, [runs]);
  const status = runs.find((r) => r.id === selected)?.status;
  useEffect(() => {
    if (selected == null || status !== 'feito') { setRun(null); return; }
    api.get(`/api/atlas/runs/${selected}`).then((r) => { setRun(r); setMinute(r.result?.minutes?.[0]?.minute ?? null); }).catch(() => {});
  }, [selected, status]);
  const m = run?.result?.minutes?.find((x) => x.minute === minute) ?? run?.result?.minutes?.[0];
  const names = useNames(m);
  const itemsList = useMemo(() => (items ?? [])
    .filter((i) => i.id > 0 && (i.section === 'aprimorado' || (i.tabs ?? []).includes('Botas')))
    .sort((x, y) => x.name.localeCompare(y.name)), [items]);
  return (
    <div className="atlas">
      <h2>Atlas de interações</h2>
      <p className="muted">As condições (status, passivas de itens, runas fundamentais, runas e feitiços) medidas sozinhas, em pares e
        em trios numa luta fechada entre dois campeões, pela corrida das barras de vida. Com as notas, as builds e os guias saem
        sem lutar build por build.</p>
      <RunList runs={runs} presets={presets} selected={selected} onSelect={setSelected} onReload={reload} />
      {run && m && (
        <section className="panel">
          <h3>{run.name}</h3>
          {run.result.minutes.length > 1 && (
            <div className="chips">
              {run.result.minutes.map((x) => (
                <button key={x.minute} type="button" className={x.minute === m.minute ? 'on' : ''} onClick={() => setMinute(x.minute)}>min {x.minute}</button>
              ))}
            </div>
          )}
          <div className="chips" role="tablist">
            {[['guias', 'Guias'], ['condicoes', 'Condições'], ['1d', '1 dimensão'], ['2d', '2 dimensões'], ['3d', '3 dimensões'], ['validacao', 'Validação']].map(([k, label]) => (
              <button key={k} type="button" className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{label}</button>
            ))}
          </div>
          {tab === 'guias' && <Guides run={run} m={m} itemsList={itemsList} a={fa} setA={setFa} b={fb} setB={setFb} />}
          {tab === 'condicoes' && <Report run={run} m={m} a={fa} b={fb} />}
          {tab === '1d' && <OneDim m={m} names={names} />}
          {tab === '2d' && <ManyDims m={m} names={names} n={2} />}
          {tab === '3d' && <ManyDims m={m} names={names} n={3} />}
          {tab === 'validacao' && <Validation m={m} run={run} onDone={setRun} onStarted={reload} />}
        </section>
      )}
    </div>
  );
}
