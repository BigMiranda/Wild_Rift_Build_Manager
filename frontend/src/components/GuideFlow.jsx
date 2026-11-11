import ItemIcon from './ItemIcon.jsx';
import { t } from '../i18n.js';

/** A condition in words: "3+ inimigos com dano alto (mágico)?", "minuto a partir dos 14?"... */
export function conditionText(c, itemName = (id) => `item ${id}`) {
  if (!c) return '?';
  const many = c.count && c.count > 1 ? `${c.count}+ ` : '';
  const dmg = { magico: t('guide.magic'), fisico: t('guide.physical'), misto: t('guide.mixed') };
  switch (c.type) {
    case 'inimigos_dano': return `${many}${t('guide.cond.inimigos_dano')} (${dmg[c.value] ?? c.value})?`;
    case 'inimigos_classe': return `${many}${t('guide.cond.inimigos_classe')} ${c.value || '—'}?`;
    case 'inimigos_arquetipo': return `${many}${t('guide.cond.inimigos_arquetipo')} ${String(c.value || '—').replace(/_/g, ' ')}?`;
    case 'adversario_rota': return `${t('guide.cond.adversario_rota')}: ${c.value || '—'}?`;
    case 'ouro': return `${c.value === 'atras' ? t('guide.behind') : t('guide.ahead')}?`;
    case 'minuto': return `${t('guide.cond.minuto')} ${c.op === '<' ? t('guide.before') : t('guide.from')} ${c.number ?? 10}?`;
    case 'item_adversario': return `${t('guide.cond.item_adversario')} ${c.itemId ? itemName(c.itemId) : '—'}?`;
    default: return `${String(c.type ?? '').replace(/_/g, ' ')}${c.value != null ? ` ${c.value}` : ''}?`;
  }
}

function Decision({ n, itemName }) {
  if (n.decision === 'runas' || (n.itemId == null && n.runePage)) {
    return <div className="gf-box gf-runes">{t('guide.runes')}: <b>{n.runePage?.keystone ?? '—'}</b></div>;
  }
  return (
    <div className="gf-box">
      {n.itemId != null && <ItemIcon id={n.itemId} size={26} />}
      <span>{n.itemId != null ? itemName(n.itemId) : n.decision}</span>
      {n.decision && n.decision !== 'item' && <small className="muted">{t(`guide.dec.${n.decision}`)}</small>}
      {n.note && <small className="muted">{n.note}</small>}
    </div>
  );
}

/** One column of the flow: the nodes one under the other, a condition splitting into "sim" and "não" side by side. */
function Column({ nodes, itemName }) {
  if (!nodes?.length) return <div className="gf-empty">—</div>;
  return (
    <div className="gf-col">
      {nodes.map((n, i) => (
        <div key={n.id ?? i} className="gf-step">
          {n.kind === 'condicao' ? (
            <div className="gf-cond">
              <div className="gf-diamond" title={n.frequency != null ? `${Math.round(n.frequency * 100)}% das partidas` : undefined}>
                {conditionText(n.cond, itemName)}
              </div>
              <div className="gf-branches">
                <div className="gf-branch"><span className="gf-label yes">{t('guide.then').replace(/→/, '').trim()}</span><Column nodes={n.then} itemName={itemName} /></div>
                <div className="gf-branch"><span className="gf-label no">{t('guide.else').replace(/→/, '').trim()}</span><Column nodes={n.otherwise} itemName={itemName} /></div>
              </div>
            </div>
          ) : <Decision n={n} itemName={itemName} />}
        </div>
      ))}
    </div>
  );
}

/**
 * A guide as a decision-tree flowchart (Rodada 13): the purchases one under the other, each condition a diamond whose
 * "sim" and "não" branches go side by side and both continue into what follows. Read-only; the editor stays a list.
 */
export default function GuideFlow({ nodes, itemName = (id) => `item ${id}` }) {
  if (!nodes?.length) return null;
  return (
    <div className="guide-flow">
      <div className="gf-start">{t('guide.flowStart')}</div>
      <Column nodes={nodes} itemName={itemName} />
      <div className="gf-start gf-end">{t('guide.flowEnd')}</div>
    </div>
  );
}
