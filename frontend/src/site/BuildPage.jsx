import { useEffect, useState } from 'react';
import { ChampionAvatar } from '../components/ChampionAvatar.jsx';
import { api } from '../api.js';
import ItemIcon from '../components/ItemIcon.jsx';
import { StatIcon } from '../components/StatIcon.jsx';
import { archetypeLabel } from '../archetypes.js';
import { statLabel } from '../i18n.js';
import { mmss, n0, n1, SHEET_STATS, statOf, statValue } from '../format.js';
import { findRune, findSpell, runeIcon, spellIcon } from '../runes.js';
import { buildLink } from '../shareLink.js';
import { atOf, KIND_LABEL, purchases, RUNES_FROM, weightedOf } from './data.js';
import { BuildNumbers, IndexBadge, Matchups } from './Champions.jsx';
import { siteHref, useSite } from './StaticApp.jsx';

const PACES = [['padrao', 'Padrão'], ['atras', 'Atrás'], ['frente', 'À frente']];
const TYPE = { fisico: 'físico', magico: 'mágico', verdadeiro: 'verdadeiro' };

/** The build as the visitor keeps it (a name, no id): to save, export or share. */
export function keepable(b) {
  const name = b.build.name || `${b.champion} · ${b.label}`;
  const { id, folderId, ...rest } = b.build;
  return { ...rest, name, note: b.kind === 'publica' ? rest.note : `Do site: ${KIND_LABEL[b.kind]} de ${b.champion}` };
}

/** Copy a link, export a file, keep in this browser: the tools that need no server. */
export function ShareTools({ build, onMessage, noSave = false }) {
  const copy = async () => {
    try {
      const link = await buildLink(build);
      try {
        await navigator.clipboard.writeText(link);
        onMessage('Link copiado: quem abrir vê a build no Montar.');
      } catch {
        window.prompt('Copie o link da build:', link);
      }
    } catch (e) {
      onMessage(e.message);
    }
  };
  const download = () => {
    const blob = new Blob([JSON.stringify({ format: 'ornn-planner-build', version: 1, build }, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${String(build.name).replace(/[^\p{L}\p{N} _-]/gu, '').trim() || 'build'}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  };
  const save = async () => {
    try {
      const saved = await api.post('/api/builds', { ...build, id: null, folderId: null });
      onMessage(`Guardada neste navegador como "${saved.name}" (em Minhas).`);
    } catch (e) {
      onMessage(e.message);
    }
  };
  return (
    <>
      <button type="button" onClick={copy}>Copiar link</button>
      <button type="button" onClick={download}>Exportar arquivo</button>
      {!noSave && <button type="button" onClick={save}>Guardar no navegador</button>}
    </>
  );
}

function RunePage({ page, spells }) {
  if (!page) return null;
  const slots = [page.keystone, ...(page.primaryRunes ?? []), page.secondaryRune].filter(Boolean);
  return (
    <div className="rune-row">
      {slots.map((name, i) => {
        const r = findRune(name);
        return (
          <span key={`${name}-${i}`} className={`rune ${i === 0 ? 'keystone' : ''}`}>
            {r && <img src={runeIcon(r)} alt="" width={i === 0 ? 34 : 26} height={i === 0 ? 34 : 26} loading="lazy" />}
            <small>{name}</small>
          </span>
        );
      })}
      {(spells ?? []).filter(Boolean).map((s) => {
        const sp = findSpell(s);
        return (
          <span key={s} className="rune spell">
            {sp && <img src={spellIcon(sp)} alt="" width={26} height={26} loading="lazy" />}
            <small>{s}</small>
          </span>
        );
      })}
    </div>
  );
}

function Sheet({ point }) {
  const totals = point?.total;
  if (!totals) return <p className="muted">Sem ficha neste minuto.</p>;
  const shown = SHEET_STATS.filter((s) => {
    const v = statOf(totals, s);
    return v != null && Math.abs(v) >= 0.05;
  });
  return (
    <dl className="sheet">
      {shown.map((s) => (
        <div key={s}>
          <dt><StatIcon stat={s} size={14} /> {statLabel(s)}</dt>
          <dd>{statValue(s, statOf(totals, s))}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Rodada 15: the lifebar race of a fight, 50 = even; the champion with no items after the slash. */
function Race({ o }) {
  const m = o?.build;
  if (m?.race == null) return null;
  const title = `Corrida das barras de vida: tirou ${n0(100 * m.enemyLost)}% da barra inimiga e perdeu ${n0(100 * m.teamLost)}% da sua`
    + (o.reference?.race != null ? `; sem itens: ${n0(o.reference.race)}` : '');
  return (
    <span className={`race-badge ${m.race >= 50 ? 'up' : 'down'}`} title={title}>
      corrida {n0(m.race)}{o.reference?.race != null && <small> / {n0(o.reference.race)}</small>}
    </span>
  );
}

function Outcomes({ list, name, weights, note = 'testNote', race = false }) {
  if (!list?.length) return <p className="muted">Não calculada.</p>;
  const total = weights ? weightedOf(weights, list) : null;
  const grades = list.find((o) => o?.grades)?.grades;
  const g = total != null && grades ? grades.find((x) => x.from == null || total >= x.from)?.key : null;
  return (
    <>
      {total != null && <p>Média ponderada: <IndexBadge value={total} grade={g} /></p>}
      <ul className="outcomes">
        {list.map((o, i) => (
          <li key={o.tag ?? i}>
            <span className="outcome-name">{o[name] ?? o.tag}
              {o[note] && <small className="muted test-note">{o[note]}</small>}</span>
            {o.error ? <small className="error">{o.error}</small>
              : <span className="outcome-values">{!race && <IndexBadge value={o.index} grade={o.grade} />}<Race o={o} /></span>}
          </li>
        ))}
      </ul>
    </>
  );
}

function Abilities({ list }) {
  if (!list?.length) return <p className="muted">Não calculadas.</p>;
  return (
    <div className="abilities">
      {list.map((r) => (
        <article key={r.slot} className="site-card ability">
          <header><span className="slot">{r.slot}</span> <strong>{r.name}</strong>
            {r.slot !== 'P' && <small className="muted"> nível {r.rank}</small>}</header>
          <dl>
            {r.damage != null && <div><dt>Dano {TYPE[r.damageType] ?? ''}</dt><dd>{n0(r.damage)}</dd></div>}
            {r.afterResist != null && <div><dt>No boneco</dt><dd>{n0(r.afterResist)}</dd></div>}
            {r.shield > 0 && <div><dt>Escudo</dt><dd>{n0(r.shield)}</dd></div>}
            {r.heal > 0 && <div><dt>Cura</dt><dd>{n0(r.heal)}</dd></div>}
            {r.cooldown != null && <div><dt>Recarga</dt><dd>{n1(r.cooldown)} s</dd></div>}
            {r.cost != null && <div><dt>Custo</dt><dd>{n0(r.cost)} {r.resource ?? ''}</dd></div>}
            {r.range != null && <div><dt>Alcance</dt><dd>{n0(r.range)}</dd></div>}
          </dl>
        </article>
      ))}
    </div>
  );
}

/** Uma build: itens na ordem (3 ritmos), runas, e nos minutos-chave a ficha, a efetividade e as habilidades. */
export default function BuildPage({ buildKey, params }) {
  const { store, compare, toggleCompare, runesReady } = useSite();
  const [b, setB] = useState(null);
  const [basic, setBasic] = useState(null);
  const [error, setError] = useState(null);
  const [pace, setPace] = useState('padrao');
  const [minute, setMinute] = useState(params?.m ?? null);
  const [message, setMessage] = useState(null);
  useEffect(() => {
    let live = true;
    setB(null);
    store.build(buildKey).then((x) => { if (live) setB(x); }).catch((e) => { if (live) setError(e.message); });
    store.basic().then((r) => { if (live) setBasic(r); }).catch(() => {});
    return () => { live = false; };
  }, [store, buildKey]);
  if (error) return <p className="error">{error}</p>;
  if (!b) return <p className="muted">Carregando…</p>;
  const keys = (b.minutes ?? []).map(String);
  const m = keys.includes(String(minute)) ? String(minute) : keys[keys.length - 1];
  const at = atOf(b, m) ?? {};
  const buys = purchases(b, pace);
  const paces = PACES.filter(([k]) => b.timelines?.[k]);
  const on = compare.includes(b.key);
  const keep = keepable(b);
  return (
    <article className="build-page">
      <p><a href={siteHref('c', b.unitCode)}>← {b.champion}</a></p>
      <header className="build-head">
        <span className={`kind kind-${b.kind}`}>{KIND_LABEL[b.kind]}</span>
        <h1 className="site-title">{b.label}</h1>
        <p className="muted build-champ">
          <ChampionAvatar name={b.champion} size={24} /> {b.champion}{b.archetype ? ` · ${archetypeLabel(b.archetypeInfo ?? { key: b.archetype })}` : ''}
          {(b.also ?? []).length ? ` · também ${b.also.join(', ')}` : ''}
        </p>
        <BuildNumbers b={b} />
        {b.kind !== 'publica' && (
          <p className="small muted">
            {b.build?.runePage?.keystone ? `${b.build.runePage.keystone}: ${RUNES_FROM[b.runesFrom] ?? 'runas da build base'}` : 'Sem página de runas.'}
            {b.orderCut ? ' · Ordem da triagem: a busca da ordem não terminou a tempo.' : ''}
          </p>
        )}
        <div className="actions">
          <button type="button" className={on ? 'on' : 'primary'} onClick={() => toggleCompare(b.key)}>{on ? '✓ Comparando' : 'Comparar'}</button>
          <ShareTools build={keep} onMessage={setMessage} />
        </div>
        {message && <p className="small">{message}</p>}
      </header>

      {b.kind === 'descoberta' && b.text && (
        <section className="site-section">
          <h2>Por que é uma descoberta</h2>
          <p>{b.text}</p>
          {/* the text already says where it wins and loses (Rodada 13); older files only had the lists */}
          {!/Ganha em|ganha em/.test(b.text) && b.wins?.length > 0 && <p className="small">Ganha em: {b.wins.join(', ')}</p>}
          {!/Perde em|perde em/.test(b.text) && b.losses?.length > 0 && <p className="small">Perde em: {b.losses.join(', ')}</p>}
        </section>
      )}

      <section className="site-section">
        <h2>Itens na ordem</h2>
        {paces.length > 1 && (
          <div className="chips" role="tablist">
            {paces.map(([k, label]) => (
              <button key={k} type="button" className={pace === k ? 'on' : ''} onClick={() => setPace(k)}>
                {label} <small>{n0(b.paces?.[k]?.gold)}/min</small>
              </button>
            ))}
          </div>
        )}
        <ol className="buys">
          {buys.map((s, i) => (
            <li key={i}>
              <ItemIcon id={s.itemId} size={34} />
              <span className="buy-name">{s.name}</span>
              <span className="buy-when">{mmss(s.minute)} <small className="muted">nv {s.level}</small></span>
            </li>
          ))}
        </ol>
        <p className="muted small">Ouro e XP por minuto: {n0(b.paces?.[pace]?.gold)} / {n0(b.paces?.[pace]?.xp)}. As compras de componentes
          que o planejador supõe ficam de fora da lista.</p>
      </section>

      <section className="site-section">
        <h2>Runas e feitiços</h2>
        {runesReady ? <RunePage page={b.build.runePage} spells={b.build.spells} /> : <p className="muted">Carregando…</p>}
      </section>

      <section className="site-section">
        <h2>No minuto</h2>
        <div className="chips" role="tablist">
          {keys.map((k, i) => (
            <button key={k} type="button" className={k === m ? 'on' : ''} onClick={() => setMinute(k)}>
              {mmss(Number(k))}{i === keys.length - 1 ? ' · último item' : ''}
            </button>
          ))}
        </div>
        <h3>Ficha {at.point ? <small className="muted">nível {at.point.level}</small> : null}</h3>
        <Sheet point={at.point} />
        <h3>Efetividade defensiva</h3>
        <p className="muted small">Quanto da taxa de dano líquido da referência (o campeão sem itens) a build cancela, nos cenários
          do relatório básico. Nesses testes os atacantes não morrem: a luta dura até a build morrer, e o número mede quanto ela
          aguenta. A "corrida" ao lado projeta, na mesma luta, quem zeraria a barra do outro primeiro (50 = empate), contando o
          dano que a build causa, o que devolve e a cura que corta.</p>
        <Outcomes list={at.effect} name="testName" weights={basic?.tests} />
        {at.race !== undefined && (
          <>
            <h3>Corrida das barras de vida</h3>
            <p className="muted small">Lutas em que os inimigos morrem de verdade, com a Vida e o dano de um campeão daquela fase:
              a luta acaba quando a barra de um dos lados zera. 50 = os dois lados caem no mesmo ritmo, 100 = a build vence sem
              perder Vida. Depois da barra, o mesmo campeão sem itens.</p>
            <Outcomes list={at.race} name="testName" race />
          </>
        )}
        {at.offense !== undefined && (
          <>
            <h3>Efetividade ofensiva</h3>
            <p className="muted small">A build ataca um boneco que não revida e morre de verdade; o número compara o tempo para
              matá-lo com o do campeão sem itens (100 = igual, 200 = na metade do tempo).</p>
            <Outcomes list={at.offense} name="targetName" note="targetNote" />
          </>
        )}
        {at.abilities !== undefined && (
          <>
            <h3>Habilidades</h3>
            <p className="muted small">Contra o boneco de pano (10.000 de vida, 100 de armadura e de resistência mágica).</p>
            <Abilities list={at.abilities} />
          </>
        )}
      </section>

      {b.matchups?.length > 0 && <Matchups list={b.matchups} />}
      <p className="muted small">Mudar um item, o minuto ou o ouro por minuto pede o app completo: aqui só há o que já foi calculado.
        {' '}<a href={siteHref('montar', null, { k: b.key })}>Abrir no Montar</a> para mudar a sequência.</p>
    </article>
  );
}

