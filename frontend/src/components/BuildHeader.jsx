import { t } from '../i18n.js';
import { mmss } from '../format.js';
import { useBuild } from '../buildContext.jsx';
import { useDrawer } from './Drawer.jsx';
import { ChampionAvatar } from './ChampionPicker.jsx';
import InventorySlots from './InventorySlots.jsx';
import MinuteInput from './MinuteInput.jsx';
import TestInArena from './TestInArena.jsx';
import { RelevanceSection, StatusSection } from '../pages/Planner.jsx';
import ChampionReport from '../pages/ChampionReport.jsx';
import Conditionals from './Conditionals.jsx';
import { ItemReader } from './BuildDock.jsx';

/**
 * The thin fixed header of the open build (Rodada 8): name, champion, the items held at the minute, the minute selected
 * and the quick buttons — Status, Relevância, Habilidades, Condicionais and "Testar na Arena" — that open their content
 * in the side drawer without leaving the screen. It replaces the old fixed bar; its details went to "Status do campeão".
 */
export default function BuildHeader() {
  const b = useBuild();
  const { open } = useDrawer();
  if (!b?.draft) return null;
  const { draft, dirty, point, moment, setSelectedMinute, selectedMinute, itemsById, builds, condOverride, meta, result, passiveValues } = b;
  const unit = meta.units?.find((u) => u.code === draft.unitCode);
  const ids = (point?.contributions ?? []).filter((c) => !c.rune).map((c) => c.itemId);
  const readItem = (id) => open({
    title: itemsById.get(id)?.name ?? '',
    render: () => <ItemReaderLive id={id} />,
  });
  return (
    <div className="build-header" role="region" aria-label={t('header.label')}>
      <span className="bh-name" title={unit?.name ?? draft.unitCode}>
        <ChampionAvatar name={unit?.name ?? draft.unitCode} size={30} />
        <span className="bh-title">
          <strong>{draft.name}{dirty ? ' •' : ''}</strong>
          <small className="muted">{unit?.name ?? draft.unitCode}{point ? ` · ${t('bar.lv', { n: point.level })}` : ''}</small>
        </span>
      </span>
      <InventorySlots ids={ids} itemsById={itemsById} size={32} onPick={readItem} />
      <label className="bh-minute" title={t('header.minuteHelp')}>
        <span>{t('header.minute')}</span>
        <MinuteInput value={selectedMinute} onChange={setSelectedMinute} placeholder={moment != null ? mmss(moment) : '—'} />
      </label>
      {condOverride && <span className="bh-warn" title={t('cond.changedHelp')}>{t('cond.changed')}</span>}
      <span className="bh-buttons">
        <button onClick={() => open({ title: t('sub.build.status'), path: '/build/status', render: () => <StatusSection /> })} disabled={!point}>{t('header.status')}</button>
        <button onClick={() => open({ title: t('sub.relatorios.relevancia'), path: '/relatorios/relevancia', render: () => <RelevanceSection /> })} disabled={!point}>{t('header.relevance')}</button>
        <button onClick={() => open({ title: t('sub.relatorios.campeao'), path: '/relatorios/campeao', render: () => <ChampionReport compact /> })} disabled={!point || draft.unitCode === 'RAGDOLL'}>{t('header.skills')}</button>
        <button className={condOverride ? 'on' : ''} onClick={() => open({ title: t('cond.title'), render: () => <Conditionals /> })}>{t('header.conditionals')}</button>
        <TestInArena compact buildId={draft.id} buildName={draft.name} minute={point?.minute ?? null} builds={builds} />
      </span>
    </div>
  );
}

/** The item reader in the drawer, following the open build's minute. */
export function ItemReaderLive({ id }) {
  const { itemsById, point, result, passiveValues } = useBuild();
  const item = itemsById.get(id);
  if (!item) return null;
  return (
    <ItemReader item={item} point={point} onClose={null}
      overlay={point ? passiveValues.overlayAt('active', point.minute) : undefined} prices={result?.statPrices} />
  );
}
