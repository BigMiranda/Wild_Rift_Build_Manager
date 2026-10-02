import { Fragment, useState } from 'react';
import ItemIcon from './ItemIcon.jsx';
import { SHORT_LABELS, TABLE_STATS, mmss, n0, n1, pct, statLabel } from '../format.js';

/** Timeline of one build. `compact` hides the efficiency formulas for side-by-side comparison. */
export default function TimelineTable({ result, compact = false }) {
  const [open, setOpen] = useState(null);
  if (!result || result.steps.length === 0) return <p className="muted">Nenhuma compra na sequência.</p>;

  const stats = compact ? TABLE_STATS.slice(0, 4) : TABLE_STATS;
  const cols = 6 + stats.length + (compact ? 1 : 3);

  return (
    <div className="table-wrap">
      <table className="data">
        <thead>
          <tr>
            <th>#</th>
            <th className="l">Item</th>
            <th title="Ouro efetivamente pago (desconta componentes já comprados)">Pago</th>
            <th title="Ouro acumulado gasto">Acum.</th>
            <th title="Minuto da compra">Min</th>
            <th title="Nível no minuto da compra">Nv</th>
            {stats.map((s) => (
              <th key={s} title={statLabel(s)}>{SHORT_LABELS[s] ?? s}</th>
            ))}
            {!compact && <th title="Eficiência estática (site de referência, sem contexto da build)">Efic. estática</th>}
            <th title="Eficiência dinâmica: status planos + passiva calculada no contexto real da build">Efic. dinâmica</th>
            {!compact && <th title="Ganho de valor em ouro da build inteira (inclui Forja Viva e passivas de outros itens) / ouro pago">Marginal</th>}
          </tr>
        </thead>
        <tbody>
          {result.steps.map((s) => {
            const e = s.efficiency;
            const up = e.dynamicPct != null && e.staticPct != null && e.dynamicPct - e.staticPct > 0.05;
            return (
              <Fragment key={s.index}>
                <tr className="clickable" onClick={() => setOpen(open === s.index ? null : s.index)} aria-expanded={open === s.index}>
                  <td>{s.index + 1}</td>
                  <td className="l">
                    <span className="with-icon"><ItemIcon id={s.itemId} size={22} />{s.itemName}</span>
                    {s.warnings.length > 0 && <span title={s.warnings.join('\n')}> ⚠</span>}
                  </td>
                  <td>{n0(s.paidCost)}</td>
                  <td>{n0(s.cumulativeGold)}</td>
                  <td>{mmss(s.minute)}</td>
                  <td>{s.level}</td>
                  {stats.map((st) => (
                    <td key={st}>{st === 'Health Regen' ? n1(s.stats.total[st]) : n0(s.stats.total[st])}</td>
                  ))}
                  {!compact && <td>{pct(e.staticPct)}</td>}
                  <td className={up ? 'eff-up' : ''}>{pct(e.dynamicPct)}</td>
                  {!compact && <td>{pct(e.marginalPct)}</td>}
                </tr>
                {open === s.index && (
                  <tr className="detail">
                    <td colSpan={cols}>
                      <StepDetail step={s} />
                    </td>
                  </tr>
                )}
              </Fragment>
            );
          })}
        </tbody>
      </table>
      <small>Clique numa linha para ver o detalhamento do cálculo.</small>
    </div>
  );
}

function StepDetail({ step }) {
  const st = step.stats;
  const e = step.efficiency;
  return (
    <div className="detail-grid">
      <div>
        <h4>Compra</h4>
        <div>Custo do item: {n0(step.itemCost)} · pago: {n0(step.paidCost)}</div>
        {step.consumedComponents.length > 0 && <div>Componentes usados: {step.consumedComponents.join(', ')}</div>}
        <div>Minuto {mmss(step.minute)} · XP {n0(step.xp)} · nível {step.level}</div>
        <div>Inventário: {step.inventory.join(', ')}</div>
        {step.warnings.length > 0 && (
          <ul className="error">
            {step.warnings.map((w) => <li key={w}>{w}</li>)}
          </ul>
        )}
      </div>
      <div>
        <h4>Composição dos status</h4>
        <table className="data">
          <thead>
            <tr><th className="l">Status</th><th>Base</th><th>Itens</th><th>Passivas</th><th>Forja</th><th>Total</th></tr>
          </thead>
          <tbody>
            {TABLE_STATS.map((k) => (
              <tr key={k}>
                <td className="l">{statLabel(k)}</td>
                <td>{n1(st.base[k])}</td>
                <td>
                  {k === 'Health Regen'
                    ? (st.itemFlat['% Health Regen'] ? `+${n0(st.itemFlat['% Health Regen'])}% da base` : '—')
                    : n1(st.itemFlat[k])}
                </td>
                <td>{n1(st.itemPassives[k])}</td>
                <td>{n1(st.forge[k])}</td>
                <td><strong>{n1(st.total[k])}</strong></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div>
        <h4>Passivas percentuais (ordem de compra)</h4>
        {st.passives.length === 0 && <div className="muted">Nenhuma.</div>}
        <ul>
          {st.passives.map((p, i) => (
            <li key={i} className={p.purchaseIndex === step.index ? 'eff-up' : ''}>
              #{p.purchaseIndex + 1} {p.itemName} — {p.passive}
              {p.refScope === 'BONUS' && <small> (só bônus)</small>}
              <div className="formula">{p.formula}</div>
            </li>
          ))}
        </ul>
        <h4>Forja Viva ({pct(st.forgePct * 100)})</h4>
        {st.forgeDetails.length === 0 ? (
          <div className="muted">Não se aplica a esta unidade.</div>
        ) : (
          <ul>
            {st.forgeDetails.map((f) => (
              <li key={f.stat} className="formula">
                {statLabel(f.stat)}: {n1(f.bonus)} bônus × {pct(f.pct * 100)} = {n1(f.value)}
              </li>
            ))}
          </ul>
        )}
      </div>
      <div>
        <h4>Eficiência de ouro</h4>
        <div>Estática: <strong>{pct(e.staticPct)}</strong></div>
        <div className="formula">{e.staticFormula}</div>
        <div style={{ marginTop: 6 }}>Dinâmica: <strong>{pct(e.dynamicPct)}</strong></div>
        <div className="formula">{e.dynamicFormula}</div>
        <div style={{ marginTop: 6 }}>Marginal: <strong>{pct(e.marginalPct)}</strong></div>
        <div className="formula">{e.marginalFormula}</div>
      </div>
    </div>
  );
}
