import { t } from '../i18n.js';
import ItemIcon from './ItemIcon.jsx';

const ITEM_SLOTS = 5;

const isBoots = (item) => item?.tabs?.includes('Botas');
const isFinished = (item) => item?.section === 'aprimorado' || item?.section === 'evolucao';

/**
 * The in-game inventory: 5 item slots + 1 boots slot. Completed items get a gold frame; items beyond the slots
 * (a build that breaks the limit) are shown after them, in red. With `onPick`, a held item is a button (e.g. to read it).
 */
export default function InventorySlots({ ids, itemsById, size = 20, onPick, picked }) {
  const items = ids.map((id) => itemsById.get(id)).filter(Boolean);
  const boots = items.filter(isBoots);
  const others = items.filter((i) => !isBoots(i));
  const slots = [...others.slice(0, ITEM_SLOTS)];
  while (slots.length < ITEM_SLOTS) slots.push(null);
  const overflow = [...others.slice(ITEM_SLOTS), ...boots.slice(1)];

  const slot = (item, key, extra = '') => {
    const props = {
      className: `inv-slot${item && isFinished(item) ? ' finished' : ''}${extra}${item && picked === item.id ? ' picked' : ''}`,
      title: item ? `${item.name}${isFinished(item) ? ` · ${t('slots.finished')}` : ''}` : t(extra.includes('boots') ? 'slots.boots' : 'slots.empty'),
      style: { width: size + 4, height: size + 4 },
    };
    if (item && onPick) {
      return (
        <button key={key} type="button" {...props} className={`${props.className} pickable`} aria-pressed={picked === item.id}
          onClick={() => onPick(item.id)} aria-label={t('dock.read', { name: item.name })}>
          <ItemIcon id={item.id} size={size} />
        </button>
      );
    }
    return <span key={key} {...props}>{item && <ItemIcon id={item.id} size={size} />}</span>;
  };

  return (
    <span className="inv" aria-label={items.map((i) => i.name).join(', ')}>
      {slots.map((item, k) => slot(item, `s${k}`))}
      <span className="inv-sep" />
      {slot(boots[0] ?? null, 'boots', ' boots')}
      {overflow.map((item, k) => slot(item, `o${k}`, ' overflow'))}
    </span>
  );
}
