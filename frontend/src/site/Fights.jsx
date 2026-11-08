import { useEffect, useMemo, useRef, useState } from 'react';
import { n0, n1 } from '../format.js';
import { siteHref, useSite } from './StaticApp.jsx';

const COLORS = ['#4aa3ff', '#ff6b6b'];

/** Lutas: os cenários públicos da Arena, cada um com a luta já rodada pelo script (só assistir). */
export function FightsPage() {
  const { store } = useSite();
  const [list, setList] = useState(null);
  useEffect(() => { store.fights().then(setList).catch(() => setList([])); }, [store]);
  return (
    <section>
      <h1 className="site-title">Lutas</h1>
      <p className="muted">Lutas da Arena gravadas no PC do autor. Aqui só dá para assistir: nada roda de novo.</p>
      {list && list.length === 0 && <p className="muted">Nenhuma luta pública.</p>}
      <ul className="site-list">
        {(list ?? []).map((f) => <li key={f.id}><a className="site-card" href={siteHref('luta', f.id)}>{f.name}</a></li>)}
      </ul>
    </section>
  );
}

/** Uma luta: o replay (as posições e a vida de cada um, quadro a quadro) e o resumo. */
export function FightPage({ id }) {
  const { store } = useSite();
  const [r, setR] = useState(null);
  const [error, setError] = useState(null);
  const [time, setTime] = useState(0);
  const [playing, setPlaying] = useState(false);
  const raf = useRef(0);
  const [name, setName] = useState(null);
  useEffect(() => { store.fight(id).then(setR).catch((e) => setError(e.message)); }, [store, id]);
  useEffect(() => { store.fights().then((l) => setName(l.find((f) => String(f.id) === String(id))?.name ?? null)).catch(() => {}); }, [store, id]);
  useEffect(() => {
    if (!playing || !r) return undefined;
    let last = performance.now();
    const tick = (now) => {
      setTime((t) => {
        const next = t + (now - last) / 1000;
        if (next >= r.duration) {
          setPlaying(false);
          return r.duration;
        }
        return next;
      });
      last = now;
      raf.current = requestAnimationFrame(tick);
    };
    raf.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf.current);
  }, [playing, r]);
  const box = useMemo(() => {
    if (!r?.frames?.length) return null;
    let x0 = Infinity; let x1 = -Infinity; let y0 = Infinity; let y1 = -Infinity;
    for (const f of r.frames) for (const u of f.units) { x0 = Math.min(x0, u.x); x1 = Math.max(x1, u.x); y0 = Math.min(y0, u.y); y1 = Math.max(y1, u.y); }
    const pad = 200;
    const w = Math.max(400, x1 - x0 + 2 * pad);
    const h = Math.max(300, y1 - y0 + 2 * pad, w * 0.45);   // a lane is long and thin: keep the picture readable
    return { x: x0 - pad, y: (y0 + y1) / 2 - h / 2, w, h };
  }, [r]);
  if (error) return <p className="error">{error}</p>;
  if (!r) return <p className="muted">Carregando…</p>;
  const frame = r.frames?.length ? r.frames[Math.max(0, Math.min(r.frames.length - 1, Math.round(time / r.frameEvery)))] : null;
  const fighters = r.fighters ?? [];
  return (
    <section>
      <p><a href={siteHref('lutas')}>← Lutas</a></p>
      <h1 className="site-title">{name ?? `Luta ${id}`}</h1>
      <p>{r.winner >= 0 ? `Vitória do lado ${r.winner === 0 ? 'azul' : 'vermelho'}` : 'Sem vencedor'} em {n1(r.duration)} s.</p>
      {box && frame && (
        <svg className="replay" viewBox={`${box.x} ${box.y} ${box.w} ${box.h}`} preserveAspectRatio="xMidYMid meet" role="img" aria-label="Replay da luta">
          <rect x={box.x} y={box.y} width={box.w} height={box.h} fill="#0b1826" />
          {frame.units.map((u, i) => {
            const f = fighters[i];
            const team = f?.team ?? (i % 2);
            const hp = u.maxHp > 0 ? Math.max(0, u.hp / u.maxHp) : 0;
            const rr = Math.max(40, box.w / 60);
            return (
              <g key={i} opacity={u.hp <= 0 ? 0.3 : 1}>
                <circle cx={u.x} cy={u.y} r={rr} fill={COLORS[team] ?? '#ccc'} />
                <rect x={u.x - rr * 1.5} y={u.y - rr * 2.2} width={rr * 3} height={rr * 0.45} fill="#222" />
                <rect x={u.x - rr * 1.5} y={u.y - rr * 2.2} width={rr * 3 * hp} height={rr * 0.45} fill="#5ad16a" />
              </g>
            );
          })}
        </svg>
      )}
      <div className="replay-tools">
        <button type="button" className="primary" onClick={() => { if (time >= r.duration) setTime(0); setPlaying(!playing); }}>{playing ? 'Pausar' : 'Assistir'}</button>
        <input type="range" min="0" max={r.duration} step={r.frameEvery || 0.1} value={time} onChange={(e) => { setPlaying(false); setTime(Number(e.target.value)); }} aria-label="Tempo da luta" />
        <span>{n1(time)} s</span>
      </div>
      <ul className="site-list">
        {fighters.map((f, i) => (
          <li key={i} className="site-card">
            <strong style={{ color: COLORS[f.team ?? i % 2] }}>{f.name ?? f.champion ?? `Lutador ${i + 1}`}</strong>
            <small className="muted"> vida {n0(Math.max(0, frame?.units?.[i]?.hp ?? f.health ?? 0))} / {n0(f.maxHealth ?? frame?.units?.[i]?.maxHp ?? 0)}</small>
            {f.dealt != null && <small> · dano causado {n0(f.dealt)}</small>}
          </li>
        ))}
      </ul>
    </section>
  );
}
