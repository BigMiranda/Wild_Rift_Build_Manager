import { useMemo } from 'react';
import ItemIcon from './ItemIcon.jsx';
import { findRune, findTree, runeCatalog, runeIcon, treeIcon } from '../runes.js';
import { groupNotes } from '../discoveryNotes.js';
import { n1 } from '../format.js';

/** An item by its name, as an icon with the name as the hover caption. */
function NamedItem({ name, ids, size = 26 }) {
  const id = ids.get(name);
  if (id == null) return <span className="note-chip">{name}</span>;
  return <span className="note-icon" title={name} aria-label={name}><ItemIcon id={id} size={size} /></span>;
}

/** A rune or a rune tree by its name, as an icon with the name as the hover caption. */
function NamedRune({ name, size = 28 }) {
  const rune = findRune(name);
  const tree = rune ? null : findTree(name);
  const src = rune ? runeIcon(rune) : tree ? treeIcon(tree) : null;
  if (!src) return <span className="note-chip">{name}</span>;
  return <span className="note-icon" title={name} aria-label={name}><img src={src} alt="" width={size} height={size} loading="lazy" /></span>;
}

/** What a swapped name is in the page: the keystone, a tree or a rune. */
function slotLabel(name) {
  if (findTree(name)) return 'Árvore';
  const r = findRune(name);
  if (!r) return '';
  return runeCatalog()?.keystones.some((k) => k.name === name) ? 'Runa-chave' : 'Runa';
}

const Items = ({ names, ids, size }) => (
  <span className="note-items">{names.map((n, i) => <NamedItem key={`${n}-${i}`} name={n} ids={ids} size={size} />)}</span>
);

const Gain = ({ from, to }) => {
  const d = to - from;
  return (
    <span>
      nota {n1(from)} → <b>{n1(to)}</b>{' '}
      <span className={d >= 0 ? 'delta-up' : 'delta-down'}>({d >= 0 ? '+' : '−'}{n1(Math.abs(d))})</span>
    </span>
  );
};

/**
 * The Discovery's notes with icons (Rodada 14): the rune searches as "what changed in the page" (the net swaps, not
 * the search's path), grouped when several kits got the same page, then the other notes. Names show on hover.
 * `items` is the shop list ({id, name}); the rune catalog must be loaded (`setRuneCatalog`) for the rune icons.
 */
export default function DiscoveryNotes({ notes, items }) {
  const ids = useMemo(() => new Map((items ?? []).map((i) => [i.name, i.id])), [items]);
  const g = useMemo(() => groupNotes(notes, [...ids.keys()]), [notes, ids]);
  if (!notes?.length) return null;
  return (
    <div className="disc-notes">
      {g.runes.length > 0 && (
        <section>
          <h4>Runas ajustadas</h4>
          <p className="small muted">
            Para cada conjunto final, a Descoberta testou páginas de runas, uma escolha por vez, e ficou com a de nota maior.
            Aqui está a troca da página original para a encontrada (passe o mouse num ícone para ver o nome).
          </p>
          {g.runes.map((grp, gi) => (
            <div key={gi} className="note-card">
              {grp.swaps.length === 0
                ? <p className="small">A página original já era a melhor.</p>
                : (
                  <ul className="note-swaps">
                    {grp.swaps.map((s, i) => (
                      <li key={i}>
                        <small className="muted">{slotLabel(s.to)}</small>
                        <NamedRune name={s.from} /> <span className="note-arrow">→</span> <NamedRune name={s.to} />
                      </li>
                    ))}
                  </ul>
                )}
              <p className="small muted">{grp.kits.length === 1 ? 'Conjunto:' : `Mesma troca em ${grp.kits.length} conjuntos:`}</p>
              <ul className="note-kits">
                {grp.kits.map((k, i) => (
                  <li key={i}>
                    <Items names={k.items} ids={ids} size={24} />
                    <small><Gain from={k.from} to={k.to} /> · {k.measured} páginas testadas</small>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </section>
      )}
      {g.dropped.map((d, i) => (
        <section key={`d${i}`}>
          <h4>Descobertas que não ficaram</h4>
          <p className="small muted">
            Conjuntos que pareciam melhores na triagem, mas que, já com runas e ordem de compra ajustadas, ficaram abaixo da
            build salva <b>{d.against}</b> ({n1(d.againstScore)}), medida como ela está.
          </p>
          <ul className="note-kits">
            {d.kits.map((k, j) => (
              <li key={j}>
                <Items names={k.items} ids={ids} size={24} />
                <small>nota {n1(k.score)} <span className="delta-down">(−{n1(d.againstScore - k.score)})</span></small>
              </li>
            ))}
          </ul>
        </section>
      ))}
      {g.other.length > 0 && (
        <section>
          <h4>Outras notas</h4>
          <ul className="note-list">
            {g.other.map((n, i) => <li key={i}><OtherNote n={n} ids={ids} /></li>)}
          </ul>
        </section>
      )}
    </div>
  );
}

function OtherNote({ n, ids }) {
  switch (n.kind) {
    case 'conjunto':
      return n.changed
        ? <>Com as runas novas, o melhor conjunto passou a ser <Items names={n.items} ids={ids} />.</>
        : <>Com as runas novas, o melhor conjunto continuou o mesmo.</>;
    case 'ordem':
      return (
        <>
          <Items names={n.items} ids={ids} /> — a busca da melhor ordem de compra não terminou a tempo:{' '}
          {n.prefix != null
            ? `valem as ${n.prefix} primeiras compras achadas e, depois delas, o item que mais vale primeiro.`
            : 'vale a ordem da triagem (o item que mais vale primeiro).'}
        </>
      );
    case 'retirada':
      return (
        <>
          <Items names={n.items} ids={ids} /> saiu das descobertas: com runas e ordem ficou em {n1(n.score)}, abaixo da
          build salva <b>{n.against}</b> ({n1(n.againstScore)}).
        </>
      );
    case 'salva':
      return <>A build salva <b>{n.name}</b> ({n1(n.score)}) ficou acima da padrão que a busca achou ({n1(n.standard)}) e virou a padrão.</>;
    default:
      return <>{n.text}</>;
  }
}
