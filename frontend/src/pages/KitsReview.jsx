import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { n0 } from '../format.js';
import { ChampionAvatar } from '../components/ChampionAvatar.jsx';
import { openInArena } from '../components/TestInArena.jsx';

const SLOTS = ['1', '2', '3', 'R'];
const QUALITY_COLOR = { A: 'var(--ok)', B: '#5ec5ff', C: 'var(--gold)', D: 'var(--error)' };
const TYPE = { fisico: ['Físico', 'var(--stat-ad)'], magico: ['Mágico', 'var(--stat-ap)'], verdadeiro: ['Verdadeiro', '#ffffff'], adaptativo: ['Adaptativo', 'var(--gold-bright)'] };

/** A per-rank value: 60/90/120, numbers rounded to 2 decimals. */
const ranks = (v) => (Array.isArray(v) ? v.map((x) => +Number(x).toFixed(2)).join('/') : v == null ? '' : typeof v === 'number' ? +v.toFixed(2) : String(v));
const pct = (v) => (Array.isArray(v) ? v.map((x) => +(x * 100).toFixed(1)).join('/') : +(Number(v) * 100).toFixed(1));
const first = (v) => (Array.isArray(v) ? v[v.length - 1] : v);

/** The damage terms of a sheet, in the shop's words. */
const RATIOS = [
  ['dda', '% DdA'], ['dda_bonus', '% DdA adicional'], ['dda_base', '% DdA base'], ['pdh', '% PdH'], ['vida_max', '% da Vida máx.'],
  ['vida_bonus', '% da Vida adicional'], ['arm_bonus', '% da Armadura adicional'], ['rm_bonus', '% da RM adicional'],
  ['vida_max_alvo', '% da Vida máx. do alvo'], ['vida_perdida_alvo', '% da Vida perdida do alvo'], ['vida_atual_alvo', '% da Vida atual do alvo'],
];

function Damage({ d }) {
  if (!d || typeof d !== 'object') return null;
  const [label, color] = TYPE[d.tipo] ?? [d.tipo, 'var(--text)'];
  const parts = [];
  if (d.base != null) parts.push(<b key="b">{ranks(d.base)}</b>);
  for (const [k, l] of RATIOS) if (d[k] != null) parts.push(<span key={k}>{parts.length ? ' + ' : ''}{pct(d[k])}{l}</span>);
  return (
    <div className="kit-damage" style={{ borderColor: color }}>
      <span className="kit-dtype" style={{ color }}>{label}</span> {parts}
    </div>
  );
}

/** Effects on whoever is hit, as colored tags. */
const CC = {
  atordoa: ['Atordoa', 'cc'], enraiza: ['Enraíza', 'cc'], arremessa: ['Arremessa', 'cc'], suprime: ['Suprime', 'cc'], provoca: ['Provoca', 'cc'],
  amedronta: ['Amedronta', 'cc'], encanta: ['Encanta', 'cc'], adormece: ['Adormece', 'cc'], polimorfa: ['Polimorfa', 'cc'], silencia: ['Silencia', 'cc'],
  cega: ['Cega', 'cc'], puxa: ['Puxa', 'move'], afasta: ['Afasta', 'move'], empurra: ['Empurra', 'move'], marca: ['Marca', 'mark'],
  executa_abaixo: ['Executa abaixo de', 'dmg'], fragil: ['Frágil', 'mark'], remove_escudos: ['Remove escudos', 'mark'],
};
const UNIT = { puxa: ' u', afasta: ' u', empurra: ' u' };

function EffectTags({ e }) {
  if (!e || typeof e !== 'object') return null;
  const tags = [];
  if (e.lentidao != null) tags.push(<span key="sl" className="kit-tag slow">Lentidão {pct(e.lentidao)}%{e.duracao != null ? ` · ${ranks(e.duracao)} s` : ''}</span>);
  for (const [k, [label, kind]] of Object.entries(CC)) {
    if (e[k] == null || e[k] === false) continue;
    const v = e[k] === true ? '' : k === 'executa_abaixo' ? ` ${pct(e[k])}%` : typeof e[k] === 'object' ? '' : ` ${ranks(e[k])}${UNIT[k] ?? (kind === 'cc' ? ' s' : '')}`;
    tags.push(<span key={k} className={`kit-tag ${kind}`}>{label}{v}</span>);
  }
  for (const k of ['reduz_arm', 'reduz_rm']) {
    if (e[k]) tags.push(<span key={k} className="kit-tag mark">−{pct(e[k].pct)}% {k === 'reduz_arm' ? 'Armadura' : 'RM'} · {ranks(e[k].duracao)} s</span>);
  }
  return tags.length ? <div className="kit-tags">{tags}</div> : null;
}

const formula = (x) => (x && typeof x === 'object'
  ? [x.base != null ? ranks(x.base) : null, ...RATIOS.filter(([k]) => x[k] != null).map(([k, l]) => `${pct(x[k])}${l}`)].filter(Boolean).join(' + ')
  : ranks(x));

/** What the ability does to the champion itself. */
function SelfTags({ a }) {
  const tags = [];
  if (a.escudo) tags.push(['Escudo', formula(a.escudo)]);
  if (a.cura) tags.push(['Cura', formula(a.cura)]);
  if (a.vida_extra) tags.push(['Vida extra', formula(a.vida_extra)]);
  if (a.velocidade_movimento) tags.push(['Vel. de Movimento', `+${pct(a.velocidade_movimento)}%`]);
  if (a.velocidade_ataque) tags.push(['Vel. de Ataque', `+${pct(a.velocidade_ataque)}%`]);
  if (a.reducao_dano) tags.push(['Redução de dano', `${pct(a.reducao_dano.pct)}% · ${ranks(a.reducao_dano.duracao)} s`]);
  if (a.invisivel) tags.push(['Invisível', `${ranks(a.invisivel.duracao)} s`]);
  if (a.invulneravel) tags.push(['Invulnerável', `${ranks(a.invulneravel)} s`]);
  if (a.nao_selecionavel) tags.push(['Inalvejável', `${ranks(a.nao_selecionavel)} s`]);
  if (a.nao_morre) tags.push(['Não morre', `${ranks(a.nao_morre)} s`]);
  if (a.vampirismo) tags.push(['Vampirismo', `${pct(a.vampirismo)}%`]);
  if (a.alcance_extra) tags.push(['Alcance', `+${ranks(a.alcance_extra)}`]);
  if (a.status) for (const [k, v] of Object.entries(a.status)) tags.push([k, `+${ranks(v)}`]);
  if (a.status_pct) for (const [k, v] of Object.entries(a.status_pct)) tags.push([k, `+${pct(v)}%`]);
  if (a.aura?.dano) tags.push([`Aura ${a.aura.raio ?? ''}`, `a cada ${a.aura.intervalo ?? 0.5} s`]);
  if (a.invoca) tags.push(['Invoca', a.invoca.nome ?? '']);
  if (a.troca_forma) tags.push(['Troca de forma', a.troca_forma]);
  return tags.length ? <div className="kit-tags">{tags.map(([l, v]) => <span key={l} className="kit-tag self">{l} {v}</span>)}</div> : null;
}

// ------------------------------------------------------------------ the shape sketch

function Sector({ cls, ox, oy, r, angle, inner = 0 }) {
  const a = (Math.min(angle, 359) / 2) * (Math.PI / 180);
  const p = (rad, s) => `${ox + rad * Math.cos(s * a)} ${oy + rad * Math.sin(s * a)}`;
  const large = angle > 180 ? 1 : 0;
  const d = inner > 0
    ? `M ${p(inner, -1)} L ${p(r, -1)} A ${r} ${r} 0 ${large} 1 ${p(r, 1)} L ${p(inner, 1)} A ${inner} ${inner} 0 ${large} 0 ${p(inner, -1)} Z`
    : `M ${ox} ${oy} L ${p(r, -1)} A ${r} ${r} 0 ${large} 1 ${p(r, 1)} Z`;
  return <path className={cls} d={d} />;
}

/** One hit zone drawn at (ox, oy) facing +x, in game units (the SVG scales). */
function Zone({ z, ox, oy, i }) {
  const cls = `kit-zone z${i % 4}`;
  const r = first(z.raio) ?? 100;
  if (z.forma === 'anel') {
    const ri = first(z.raio_interno) ?? r / 2;
    return <path className={cls} fillRule="evenodd" d={`M ${ox - r} ${oy} a ${r} ${r} 0 1 0 ${2 * r} 0 a ${r} ${r} 0 1 0 ${-2 * r} 0 M ${ox - ri} ${oy} a ${ri} ${ri} 0 1 0 ${2 * ri} 0 a ${ri} ${ri} 0 1 0 ${-2 * ri} 0`} />;
  }
  if (z.forma === 'setor') return <Sector cls={cls} ox={ox} oy={oy} r={r} angle={first(z.angulo) ?? 60} inner={first(z.raio_interno) ?? 0} />;
  if (z.forma === 'retangulo' || z.forma === 'capsula') {
    const len = first(z.comprimento) ?? 300;
    const w = first(z.largura) ?? 100;
    return <rect className={cls} x={z.centro ? ox - len / 2 : ox} y={oy - w / 2} width={len} height={w} rx={z.forma === 'capsula' ? w / 2 : 0} />;
  }
  if (z.forma === 'poligono' && Array.isArray(z.vertices)) {
    return <polygon className={cls} points={z.vertices.map(([x, y]) => `${ox + x},${oy + y}`).join(' ')} />;
  }
  if ((z.forma === 'arco' || z.forma === 'corrente') && Array.isArray(z.pontos)) {
    return <polyline className={`${cls} kit-stroke`} strokeWidth={first(z.largura) ?? 80} points={z.pontos.map(([x, y]) => `${ox + x},${oy + y}`).join(' ')} />;
  }
  return <circle className={cls} cx={ox} cy={oy} r={r} />;
}

const POINTED = ['alvo', 'linha', 'avanco', 'piscar', 'teleporte', 'invocacao', 'campo', 'cone', 'empurrao'];

/** The extent of an ability in game units, so the sketch fits it. */
function reach(a, kit) {
  const zr = (a.zonas ?? []).map((z) => Math.max(first(z.raio) ?? 0, (z.centro ? 0.5 : 1) * (first(z.comprimento) ?? 0))).reduce((x, y) => Math.max(x, y), 0);
  const range = first(a.alcance) ?? 0;
  const point = a.origem === 'ponto' || a.forma === 'campo' || a.forma === 'invocacao';
  return Math.max(300, (point ? range : 0) + zr, POINTED.includes(a.forma) ? range : 0, zr, first(a.raio) ?? 0, first(a.aura?.raio) ?? 0,
    a.forma === 'ataque' ? (kit.alcance ?? 175) + (first(a.alcance_extra) ?? 0) : 0, first(a.invoca?.raio_explosao) ?? 0);
}

/** A to-scale drawing of an ability: the caster (gold) on the left facing right, its range and its hit area. */
export function ShapeSketch({ a, kit }) {
  const R = Math.min(reach(a, kit), 3200);
  const back = Math.max(first(a.raio) ?? 0, first(a.aura?.raio) ?? 0, ...(a.origem === 'ponto' ? [] : (a.zonas ?? []).map((z) => first(z.raio) ?? 0)),
    a.forma === 'ataque' ? (kit.alcance ?? 175) + (first(a.alcance_extra) ?? 0) : 0, 120);
  const w = back + R + 160;
  const half = Math.max(R * 0.45, back, 260);
  const h = 2 * half + 60;
  const cx = back + 40;
  const cy = half + 10;
  const body = kit.raio ?? 65;
  const range = first(a.alcance) ?? 0;
  const point = a.origem === 'ponto' || a.forma === 'campo' || a.forma === 'invocacao';
  const ox = point ? cx + range : cx;
  const els = [];
  const zones = a.zonas?.length ? a.zonas : null;
  const width = first(a.largura) ?? 100;
  switch (a.forma) {
    case 'linha':
      els.push(<rect key="l" className="kit-zone z0" x={cx} y={cy - width / 2} width={range} height={width} rx={width / 2} />);
      els.push(<line key="la" className="kit-arrow" x1={cx} y1={cy} x2={cx + range} y2={cy} markerEnd="url(#kit-head)" />);
      break;
    case 'cone':
      els.push(<Sector key="c" cls="kit-zone z0" ox={cx} oy={cy} r={range} angle={first(a.angulo) ?? 60} />);
      break;
    case 'avanco':
    case 'piscar':
    case 'teleporte':
    case 'empurrao':
      if (a.raio) els.push(<rect key="dr" className="kit-zone z1" x={cx} y={cy - first(a.raio)} width={range} height={2 * first(a.raio)} rx={first(a.raio)} />);
      els.push(<line key="d" className="kit-arrow dash" x1={cx} y1={cy} x2={cx + range} y2={cy} markerEnd="url(#kit-head)" />);
      els.push(<circle key="de" className="kit-ghost" cx={cx + range} cy={cy} r={body} />);
      break;
    case 'alvo':
      els.push(<circle key="ar" className="kit-range" cx={cx} cy={cy} r={range} />);
      els.push(<line key="al" className="kit-arrow" x1={cx} y1={cy} x2={cx + range - 90} y2={cy} markerEnd="url(#kit-head)" />);
      els.push(<circle key="at" className="kit-target" cx={cx + range} cy={cy} r={80} />);
      break;
    case 'ataque':
      els.push(<circle key="aa" className="kit-zone z2" cx={cx} cy={cy} r={(kit.alcance ?? 175) + (first(a.alcance_extra) ?? 0)} />);
      break;
    default:
      break;
  }
  if (point && range) els.push(<line key="pt" className="kit-arrow dash" x1={cx} y1={cy} x2={ox} y2={cy} />);
  if (zones) zones.forEach((z, i) => els.push(<Zone key={`z${i}`} z={z} ox={ox} oy={cy} i={i} />));
  if (!zones && a.forma === 'invocacao' && a.invoca) {
    const r = first(a.invoca.raio_explosao ?? a.invoca.alcance ?? a.invoca.raio) ?? 120;
    els.push(<circle key="inv" className="kit-zone z3" cx={ox} cy={cy} r={r} />);
  }
  if (!zones && (a.forma === 'area' || a.forma === 'canal' || a.forma === 'proprio') && a.raio) els.push(<circle key="ar2" className="kit-zone z0" cx={ox} cy={cy} r={first(a.raio)} />);
  if (a.aura?.raio) els.push(<circle key="au" className="kit-zone z3" cx={cx} cy={cy} r={first(a.aura.raio)} />);
  return (
    <svg className="kit-sketch" viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="xMidYMid meet">
      <defs>
        <marker id="kit-head" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="4" markerHeight="4" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#f0d9a0" /></marker>
      </defs>
      <rect className="kit-floor" x="0" y="0" width={w} height={h} />
      {els}
      <circle className="kit-caster" cx={cx} cy={cy} r={body} />
      <line className="kit-scale" x1={cx} y1={h - 20} x2={cx + 500} y2={h - 20} />
      <text className="kit-scale-text" x={cx + 250} y={h - 32} textAnchor="middle" fontSize={Math.max(28, w / 22)}>500</text>
    </svg>
  );
}

// ------------------------------------------------------------------ preview in the arena

const DUMMY = { name: 'Boneco de pano', health: 20000, armor: 60, magicResist: 60, radius: 80, moveSpeed: 350, behavior: 'parado', spells: [], appliesEffects: false, hitEvery: 1, atMax: false, attackDamage: 0 };
const PLAN = { focus: 'nearest', order: [], shared: true, switchBelow: 0.25, chainCc: false };

/** Where the ragdoll stands: inside the range of what is shown (melee champions walk up anyway). */
function distanceFor(kit, slots) {
  const ranges = slots.map((s) => {
    const a = kit.habilidades?.[s];
    const r = first(a?.alcance) ?? 0;
    return r > 0 ? r * 0.8 : 300;
  });
  return Math.max(250, Math.min(700, (kit.alcance ?? 175) + 100, ...ranges));
}

/** Opens the arena: the champion (no items, minute 15) uses `slots` one after another on a still ragdoll, and attacks it. */
function preview(champion, code, kit, slots, label) {
  const at = 4400;
  const gap = 2.5;
  const actions = slots.map((s, i) => ({ fighter: 0, time: 0.6 + i * gap, type: 'ability', slot: s, target: 1 }));
  openInArena({
    id: null, name: `Ficha · ${champion} · ${label}`, note: '', duration: Math.max(6, 2 + slots.length * gap + 2), actions, teams: [PLAN, PLAN],
    fighters: [
      { team: 0, build: { name: `Ficha ${champion}`, unitCode: code, goldPerMin: 1000, xpPerMin: 650, steps: [] }, minute: 15, x: at, y: 0,
        target: 1, followTeam: true, autoAbilities: false, priority: [], brain: 'script', activations: {} },
      { team: 1, dummy: { ...DUMMY }, x: at + distanceFor(kit, slots), y: 0, brain: 'script', autoAbilities: false, priority: [], activations: {}, followTeam: true },
    ],
  });
}

// ------------------------------------------------------------------ cards

function CardHead({ slot, title, sub, right }) {
  return (
    <div className="kit-card-head">
      <span className={`kit-slot s${slot}`}>{slot}</span>
      <div className="kit-card-title"><b>{title}</b>{sub && <div className="muted small">{sub}</div>}</div>
      {right}
    </div>
  );
}

function AbilityCard({ slot, a, kit, game, onPreview }) {
  const est = String(a.fontes ?? '').includes('EST');
  const sub = [a.forma, a.origem === 'ponto' ? 'no ponto' : null, a.conjuracao ? `conjura ${ranks(a.conjuracao)} s` : null, a.atraso ? `atraso ${ranks(a.atraso)} s` : null]
    .filter(Boolean).join(' · ');
  return (
    <div className="kit-card">
      <CardHead slot={slot} title={a.nome} sub={sub} right={<span className="kit-cd" title={t('kits.col.cooldown')}>⟳ {ranks(a.recarga)} s</span>} />
      <ShapeSketch a={a} kit={kit} />
      <div className="kit-dims">
        {['alcance', 'largura', 'raio', 'angulo', 'velocidade', 'duracao', 'intervalo', 'cargas'].filter((k) => a[k] != null)
          .map((k) => <span key={k}>{k} <b>{ranks(a[k])}</b></span>)}
      </div>
      <Damage d={a.dano} />
      <EffectTags e={a.efeitos} />
      <SelfTags a={a} />
      {a.nota && <div className="kit-note">{a.nota}</div>}
      <div className="kit-src">{est && <span className="kit-tag est">EST</span>} {a.fontes}</div>
      {game && <details className="kit-game"><summary>{t('kits.gameText')}</summary>{game.texto}</details>}
      <button className="kit-play" onClick={onPreview}>▶ {t('kits.preview.one')}</button>
    </div>
  );
}

function AttackCard({ kit, onPreview }) {
  const ranged = kit.velocidade_projetil != null;
  return (
    <div className="kit-card">
      <CardHead slot="AA" title={t('kits.attack')} sub={ranged ? t('kits.ranged') : t('kits.melee')} />
      <ShapeSketch a={{ forma: 'ataque' }} kit={kit} />
      <div className="kit-dims">
        <span>alcance <b>{kit.alcance ?? 175}</b></span>
        {ranged && <span>projétil <b>{kit.velocidade_projetil}</b></span>}
        <span>espera <b>{kit.espera_ataque ?? 0.25}</b></span>
        <span>corpo <b>{kit.raio ?? 65}</b></span>
      </div>
      <button className="kit-play" onClick={onPreview}>▶ {t('kits.preview.attack')}</button>
    </div>
  );
}

function PassiveCard({ row, game }) {
  return (
    <div className="kit-card passive">
      <CardHead slot="P" title={game?.nome ?? t('kits.passive')} sub={t('kits.passive')} />
      {row.note && <div className="kit-note">{row.note}</div>}
      {row.gaps.length > 0 && (
        <>
          <div className="muted small">{t('kits.col.gaps')}</div>
          <div className="kit-tags">{row.gaps.map((g) => <span key={g} className="kit-tag gap">{g.replace(/_/g, ' ')}</span>)}</div>
        </>
      )}
      {game?.texto && <details className="kit-game" open><summary>{t('kits.gameText')}</summary>{game.texto}</details>}
    </div>
  );
}

function Duel({ champion }) {
  const [rival, setRival] = useState('Ornn');
  const [res, setRes] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  useEffect(() => setRes(null), [champion]);
  const run = () => {
    setBusy(true);
    setError(null);
    api.get(`/api/arena/kits/duel?champion=${encodeURIComponent(champion)}&rival=${encodeURIComponent(rival)}`)
      .then(setRes).catch((e) => setError(e.message)).finally(() => setBusy(false));
  };
  return (
    <div className="kit-duel">
      <div className="row">
        <b>{t('kits.duel.vs')}</b>
        <input value={rival} onChange={(e) => setRival(e.target.value)} style={{ width: 140 }} />
        <button onClick={run} disabled={busy}>{busy ? t('kits.duel.running') : t('kits.duel.run')}</button>
        <span className="muted small">{t('kits.duel.hint')}</span>
      </div>
      {error && <div className="error">{error}</div>}
      {res && (
        <div className="kit-duel-sides">
          {res.sides.map((s) => {
            const top = Math.max(1, ...Object.values(s.bySource ?? {}));
            return (
              <div key={s.champion} className="kit-duel-side">
                <div className="row">
                  <ChampionAvatar name={s.champion} size={28} /><b>{s.champion}</b>
                  <span className="muted small">{t('kits.duel.dealt')} {n0(s.dealt)}</span>
                  {s.deathTime != null && <span className="kit-tag cc">{t('kits.duel.died', { s: s.deathTime.toFixed(1) })}</span>}
                </div>
                <div className="kit-hp" title={`${n0(s.health)}/${n0(s.maxHealth)}`}><div style={{ width: `${Math.max(0, 100 * s.health / s.maxHealth)}%` }} /></div>
                {Object.entries(s.bySource ?? {}).sort((a, b) => b[1] - a[1]).map(([k, v]) => (
                  <div key={k} className="kit-bar"><span>{k}</span><div><div style={{ width: `${100 * v / top}%` }} /></div><b>{n0(v)}</b></div>
                ))}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function Detail({ row, code }) {
  const [game, setGame] = useState(null);
  const ref = useRef(null);
  useEffect(() => {
    ref.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [row.champion]);
  useEffect(() => {
    setGame(null);
    if (code) api.get(`/api/champions/${code}`).then(setGame).catch(() => setGame(null));
  }, [code]);
  const kit = row.kit;
  const have = SLOTS.filter((s) => kit.habilidades?.[s]);
  return (
    <div className="kit-detail" ref={ref}>
      <div className="kit-detail-head">
        <ChampionAvatar name={row.champion} size={64} />
        <div className="kit-detail-name">
          <h3>{row.champion} <span className="kit-quality big" style={{ background: QUALITY_COLOR[row.quality] ?? 'var(--muted)' }}>{row.quality ?? '?'}</span></h3>
          <div className="muted small">{game?.titulo}{game?.rotas ? ` · ${game.rotas.join(', ')}` : ''}</div>
          <div className="kit-tags">
            {row.problems.length ? row.problems.map((p) => <span key={p} className="kit-tag cc">{p}</span>) : <span className="kit-tag self">✓ {t('kits.validatorOk')}</span>}
            {row.estimated > 0 && <span className="kit-tag est">{t('kits.estCount', { n: row.estimated })}</span>}
          </div>
        </div>
        <div className="kit-detail-actions">
          <button className="kit-play big" onClick={() => preview(row.champion, code, kit, have, t('kits.preview.all'))} disabled={!code}>▶ {t('kits.preview.all')}</button>
          <span className="muted small">{t('kits.preview.hint')}</span>
        </div>
      </div>
      <div className="kit-cards">
        <PassiveCard row={row} game={game?.passiva} />
        <AttackCard kit={kit} onPreview={() => preview(row.champion, code, kit, [], t('kits.attack'))} />
        {SLOTS.map((s, i) => (kit.habilidades?.[s]
          ? <AbilityCard key={s} slot={s} a={kit.habilidades[s]} kit={kit} game={game?.habilidades?.[i]}
              onPreview={() => preview(row.champion, code, kit, [s], `${s} · ${kit.habilidades[s].nome}`)} />
          : (
            <div key={s} className="kit-card missing">
              <CardHead slot={s} title={game?.habilidades?.[i]?.nome ?? '—'} sub={t('kits.missing')} />
              {game?.habilidades?.[i] && <details className="kit-game" open><summary>{t('kits.gameText')}</summary>{game.habilidades[i].texto}</details>}
            </div>
          )))}
      </div>
      <Duel champion={row.champion} />
    </div>
  );
}

/** Admin › Fichas (Rodada 18): every Arena sheet as a card, its shapes drawn to scale, and a preview in the Arena. */
export default function KitsReview() {
  const [rows, setRows] = useState(null);
  const [codes, setCodes] = useState({});
  const [error, setError] = useState(null);
  const [filter, setFilter] = useState({ text: '', quality: '', only: '' });
  const [open, setOpen] = useState(null);
  const load = () => api.get('/api/arena/kits/review').then(setRows).catch((e) => setError(e.message));
  useEffect(() => {
    load();
    api.get('/api/champions').then((l) => setCodes(Object.fromEntries(l.map((c) => [c.nome, c.code])))).catch(() => {});
  }, []);
  const shown = useMemo(() => (rows ?? []).filter((r) => {
    if (filter.text && !r.champion.toLowerCase().includes(filter.text.toLowerCase())) return false;
    if (filter.quality && r.quality !== filter.quality) return false;
    if (filter.only === 'problems' && !r.problems.length) return false;
    if (filter.only === 'gaps' && !r.gaps.length) return false;
    if (filter.only === 'est' && !r.estimated) return false;
    return true;
  }), [rows, filter]);
  const qualities = useMemo(() => [...new Set((rows ?? []).map((r) => r.quality).filter(Boolean))].sort(), [rows]);
  const current = rows?.find((r) => r.champion === open);

  return (
    <div className="main kits-review">
      <h2>{t('kits.title')}</h2>
      <p className="muted">{t('kits.intro')}</p>
      {error && <div className="error">{error}</div>}
      <div className="row kit-filters">
        <input placeholder={t('kits.search')} value={filter.text} onChange={(e) => setFilter({ ...filter, text: e.target.value })} />
        {['', ...qualities].map((q) => (
          <button key={q || 'all'} className={`kit-qbtn ${filter.quality === q ? 'active' : ''}`} onClick={() => setFilter({ ...filter, quality: q })}>
            {q && <span className="kit-quality" style={{ background: QUALITY_COLOR[q] }}>{q}</span>}
            {q ? (rows ?? []).filter((r) => r.quality === q).length : `${t('kits.all')} · ${rows?.length ?? 0}`}
          </button>
        ))}
        <select value={filter.only} onChange={(e) => setFilter({ ...filter, only: e.target.value })}>
          <option value="">{t('kits.all')}</option>
          <option value="problems">{t('kits.onlyProblems')}</option>
          <option value="gaps">{t('kits.onlyGaps')}</option>
          <option value="est">{t('kits.onlyEst')}</option>
        </select>
        <button onClick={load}>{t('kits.reload')}</button>
      </div>
      {current && <Detail row={current} code={codes[current.champion]} />}
      <div className="kit-grid">
        {shown.map((r) => (
          <button key={r.champion} className={`kit-tile ${open === r.champion ? 'active' : ''}`} onClick={() => setOpen(open === r.champion ? null : r.champion)}
            title={r.gaps.join(', ')}>
            <ChampionAvatar name={r.champion} size={36} />
            <span className="kit-tile-name">{r.champion}</span>
            <span className="kit-quality" style={{ background: QUALITY_COLOR[r.quality] ?? 'var(--muted)' }}>{r.quality ?? '?'}</span>
            {r.problems.length > 0 && <span className="kit-dot cc" title={r.problems.join('; ')}>!</span>}
            {r.estimated > 0 && <span className="kit-dot est" title={t('kits.estCount', { n: r.estimated })}>{r.estimated}</span>}
          </button>
        ))}
      </div>
    </div>
  );
}
