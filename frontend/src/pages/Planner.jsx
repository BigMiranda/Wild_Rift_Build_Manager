import { useRef, useState } from 'react';
import { statLabel, t } from '../i18n.js';
import FolderTree from '../components/FolderTree.jsx';
import Shop from '../components/Shop.jsx';
import BuildBar from '../components/BuildBar.jsx';
import TimelineTable from '../components/TimelineTable.jsx';
import StatChart from '../components/StatChart.jsx';
import { PassivesPanel, RelevanceReport, StatSheet, pointAt } from '../components/MomentPanels.jsx';
import ObjectivesPicker, { hasObjectives } from '../components/ObjectivesPicker.jsx';
import { PASSIVES_VIEW } from '../passives.js';
import { EFFECTIVE_VIEW, PASSIVE_GOLD_BY_TIER, effectiveGold } from '../effective.js';
import EffectiveBreakdown, { AlignToggle } from '../components/EffectiveBreakdown.jsx';
import { StatIcon } from '../components/StatIcon.jsx';
import { SERIES_COLORS, mmss, n0 } from '../format.js';
import PrepPanel from '../components/PrepPanel.jsx';
import MinuteInput from '../components/MinuteInput.jsx';
import ChampionPanel, { emptyChampionSetup } from '../components/ChampionPanel.jsx';
import ChampionPicker from '../components/ChampionPicker.jsx';
import ItemEffectsPanel from '../components/ItemEffectsPanel.jsx';
import BuildDock from '../components/BuildDock.jsx';
import RuneEditor from '../components/RuneEditor.jsx';
import SpellEditor from '../components/SpellEditor.jsx';
import PrepExport from '../components/PrepExport.jsx';
import SkillExport from '../components/SkillExport.jsx';
import BuildTestPanels from '../components/BuildTestPanels.jsx';
import TestInArena from '../components/TestInArena.jsx';
import { EffectivenessPanel } from '../components/EffectivenessReport.jsx';
import OffensePanel from '../components/OffenseReport.jsx';
import QuickCompare from '../components/QuickCompare.jsx';
import PassiveValueBar from '../components/PassiveValueBar.jsx';
import ItemSetupExport from '../components/ItemSetupExport.jsx';
import { loadFlag, saveFlag, useBuild } from '../buildContext.jsx';
import { navigate } from '../router.js';

/**
 * The open build's screens (Rodada 8): each section reads the app's open build (buildContext) and is shown by a sub
 * tab of "Build" or "Relatórios". Nothing here keeps the build's state: changing tabs loses nothing.
 */

const TIMELINE_KEY = 'ornn-planner-timeline';

/** The message line of the open build (saved, errors, shop rules). */
export function BuildMessage() {
  const { message, setMessage } = useBuild();
  if (!message) return null;
  return (
    <div className="notice spread">
      <span>{message}</span>
      <button className="link" onClick={() => setMessage(null)}>{t('close')}</button>
    </div>
  );
}

/** Name, folder, champion, gold and XP per minute, end of the match, the purchase assumptions and the note. */
export function BuildFields() {
  const { meta, folders, draft, dirty, update, save, saveAsCopy, remove, condOverride } = useBuild();
  const isRagdoll = draft.unitCode === 'RAGDOLL';
  return (
    <section className="panel build-head">
      <div className="fields">
        <label className="field grow">{t('build.name')}
          <input value={draft.name} onChange={(e) => update({ name: e.target.value })} />
        </label>
        <label className="field">{t('build.folder')}
          <select value={draft.folderId ?? ''} onChange={(e) => update({ folderId: Number(e.target.value) })}>
            {folders.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
          </select>
        </label>
        <div className="field champ-field">{t('build.unit')}
          <ChampionPicker units={meta.units} value={draft.unitCode} onChange={(unitCode) => update({ unitCode })} />
        </div>
        <label className="field narrow">{t('build.startGold')}
          <input type="number" value={meta.startingGold} disabled title={t('build.startGoldTitle')} />
        </label>
        <label className="field narrow">{t('build.gpm')}
          <input type="number" min="1" value={draft.goldPerMin} placeholder="350" onChange={(e) => update({ goldPerMin: e.target.value })} />
        </label>
        <label className="field narrow">{t('build.xpm')}
          <input type="number" min="0" value={draft.xpPerMin} placeholder="450" onChange={(e) => update({ xpPerMin: e.target.value })} />
        </label>
        <label className="field narrow" title={t('build.matchEndTitle')}>{t('build.matchEnd')}
          <MinuteInput value={draft.matchEnd ?? null} onChange={(matchEnd) => update({ matchEnd })} />
        </label>
        <div className="row build-actions">
          {(dirty || condOverride) && <small>{t('build.unsaved')}</small>}
          {draft.id && <button onClick={saveAsCopy}>{t('build.duplicate')}</button>}
          {draft.id && <button className="danger" onClick={remove}>{t('build.delete')}</button>}
          <button className="primary" onClick={save} disabled={!dirty && !condOverride && draft.id != null}>{t('build.save')}</button>
        </div>
      </div>
      <div className="row assume-opts">
        <label className="check" title={t('build.assumeHalfTitle')}>
          <input type="checkbox" checked={!!draft.assumeHalfItems} onChange={(e) => update({ assumeHalfItems: e.target.checked })} />
          {t('build.assumeHalf')}
        </label>
        <label className="check" title={t('build.assumeSmallTitle')}>
          <input type="checkbox" checked={!!draft.assumeSmallItems} onChange={(e) => update({ assumeSmallItems: e.target.checked })} />
          {t('build.assumeSmall')}
        </label>
      </div>
      <details className="build-extra" open={isRagdoll || undefined}>
        <summary>{isRagdoll ? t('build.noteAndRagdoll') : t('build.note')}{draft.note ? ' •' : ''}</summary>
        <textarea rows={2} value={draft.note} placeholder={t('build.notePlaceholder')} aria-label={t('build.note')} onChange={(e) => update({ note: e.target.value })} />
        {isRagdoll && (
          <RagdollEditor meta={meta} stats={draft.ragdollStats} onChange={(ragdollStats) => update({ ragdollStats })} />
        )}
      </details>
    </section>
  );
}

/** The champion's passive and abilities, skill order and choices. */
export function SkillsSection() {
  const { draft, builds, update, point, setPrepEditor } = useBuild();
  if (draft.unitCode === 'RAGDOLL') return <p className="muted">{t('build.ragdollNoSkills')}</p>;
  return (
    <ChampionPanel code={draft.unitCode} setup={draft.championSetup} matchEnd={draft.matchEnd} point={point}
      onChange={(championSetup) => update({ championSetup })}
      onExportSkills={builds.some((b) => b.id !== draft.id && b.unitCode !== 'RAGDOLL') ? () => setPrepEditor('skills') : null} />
  );
}

/** Rune page and summoner spells ("Preparações"). */
export function PrepSection() {
  const { draft, builds, runesReady, setPrepEditor } = useBuild();
  if (!runesReady) return <p className="muted">{t('compare.calculating')}</p>;
  return (
    <PrepPanel runePage={draft.runePage} spells={draft.spells}
      onRunes={() => setPrepEditor('runes')} onSpells={() => setPrepEditor('spells')}
      onExport={builds.some((b) => b.id !== draft.id) ? () => setPrepEditor('export') : null} />
  );
}

/** The sequence of purchases (drag, moments, sales, conditionals per purchase). */
export function SequenceBar({ onCompare }) {
  const b = useBuild();
  const { draft, itemsById, evoPairs, result, update, updateStep, selectedIdx, setSelectedIdx, moveStepTo, removeStep, addMoment, setQuick } = b;
  return (
    <BuildBar
      steps={draft.steps}
      itemsById={itemsById}
      evoPairs={evoPairs}
      timeline={result?.steps}
      ignored={result?.ignored}
      moments={result?.moments}
      onAddMoment={addMoment}
      onUpdateMoment={(idx, patch) => updateStep(idx, (s) => ({ ...s, ...patch }))}
      selectedIdx={selectedIdx}
      onSelect={setSelectedIdx}
      onCompare={(idx) => { setQuick((q) => ({ slot: idx, ids: q?.ids ?? [] })); onCompare?.(); }}
      onMove={(idx, delta) => moveStepTo(idx, idx + delta)}
      onMoveTo={moveStepTo}
      onRemove={removeStep}
      onClear={() => { update({ steps: [] }); setSelectedIdx(null); }}
      onToggleCond={(idx) => updateStep(idx, (s) => ({ ...s, includeConditional: !s.includeConditional }))}
      onSell={(idx) => {
        const at = draft.steps.length;
        update((d) => ({ steps: [...d.steps, { kind: 'sell', itemId: d.steps[idx].itemId, includeConditional: true, sellWhen: 'afford' }] }));
        setSelectedIdx(at);
      }}
      onSellWhen={(idx, sellWhen) => updateStep(idx, (s) => ({ ...s, sellWhen }))}
      onAssumeRange={(from, to, assume) => update((d) => ({
        steps: d.steps.map((s, k) => (k >= from && k <= to && !s.kind ? { ...s, assume } : s)),
      }))}
      onToggleEvolved={(idx) => updateStep(idx, (s) => ({ ...s, itemId: evoPairs.get(s.itemId)?.id ?? s.itemId }))}
    />
  );
}

/** The shop, with the purchase selected in its detail panel and the ⇄ of the quick comparison. */
export function ShopSection({ withSequence = true, onCompare }) {
  const { meta, items, draft, result, payload, canCalculate, selectedIdx, setSelectedIdx, addItem, quick, toggleQuickCandidate } = useBuild();
  return (
    <section className="panel shop-panel">
      <Shop
        items={items}
        shopRules={meta.shopRules}
        buildPayload={canCalculate ? payload : null}
        ownedIds={draft.steps.filter((s) => !s.kind).map((s) => s.itemId)}
        purchase={selectedIdx != null && draft.steps[selectedIdx] && !draft.steps[selectedIdx].kind ? {
          index: selectedIdx,
          number: draft.steps.slice(0, selectedIdx + 1).filter((s) => s.kind !== 'moment').length,
          itemId: draft.steps[selectedIdx].itemId,
          step: result?.steps.find((x) => x.buildIndex === selectedIdx && !x.implied) ?? null,
          ignored: result?.ignored?.find((x) => x.buildIndex === selectedIdx) ?? null,
          implied: result?.steps.filter((x) => x.buildIndex === selectedIdx && x.implied) ?? [],
        } : null}
        onPurchaseClear={() => setSelectedIdx(null)}
        onAdd={addItem}
        comparing={quick?.ids ?? []}
        onToggleCompare={toggleQuickCandidate}
      />
      {withSequence && <SequenceBar onCompare={onCompare} />}
      {quick && onCompare && (
        <p className="notice spread">
          <span>{t('route.comparingItems', { n: quick.ids.length })}</span>
          <button className="link" onClick={onCompare}>{t('route.openCompare')}</button>
        </p>
      )}
    </section>
  );
}

/** The quick item comparison of the open build (a purchase of the sequence, or the next one). */
export function CompareItemsSection() {
  const { quick, setQuick, canCalculate, payload, selectedMinute, items, itemsById } = useBuild();
  if (!canCalculate) return <p className="muted">{t('timeline.needInputs')}</p>;
  if (!quick) {
    return (
      <section className="panel">
        <p className="muted">{t('qc.empty')}</p>
        <button onClick={() => setQuick({ slot: null, ids: [] })}>{t('qc.titleNext')}</button>
      </section>
    );
  }
  return (
    <QuickCompare build={payload} slot={quick.slot} atMinute={selectedMinute} ids={quick.ids} items={items} itemsById={itemsById}
      onIds={(ids) => setQuick((q) => ({ ...q, ids }))} onClose={() => setQuick(null)} />
  );
}

/** The modeled effects of the build's items (options, conditionals, rates). */
export function ItemEffectsSection() {
  const { draft, itemsById, builds, update, setPrepEditor } = useBuild();
  return (
    <ItemEffectsPanel steps={draft.steps} itemsById={itemsById} setup={draft.itemSetup} matchEnd={draft.matchEnd}
      onChange={(itemSetup) => update({ itemSetup })}
      onExport={builds.some((b) => b.id !== draft.id) ? () => setPrepEditor('items') : null} />
  );
}

/** The purchase timeline: when each purchase happens, level and gold; side by side with the compared builds. */
export function TimelineSection({ collapsible = false }) {
  const { meta, result, calcError, lastStep, itemsById, compareIds, chartSeries } = useBuild();
  const [open, setOpen] = useState(() => (collapsible ? loadFlag(TIMELINE_KEY, false) : true));
  const toggle = () => setOpen((o) => { saveFlag(TIMELINE_KEY, !o); return !o; });
  const warnings = result?.warnings ?? [];
  return (
    <section className="panel timeline-panel">
      <div className="spread">
        {collapsible ? (
          <button className="collapse-head" onClick={toggle} aria-expanded={open}>
            <span aria-hidden="true">{open ? '▾' : '▸'}</span> <h3>{t('timeline.title')}</h3>
          </button>
        ) : <h3>{t('timeline.title')}</h3>}
        {lastStep && (
          <small className="muted">
            {t('timeline.summary', { n: result.steps.filter((x) => !x.implied).length, gold: n0(lastStep.cumulativeGold), time: mmss(lastStep.minute) })}
          </small>
        )}
      </div>
      {!result && !calcError && <p className="muted">{t('timeline.needInputs')}</p>}
      {calcError && <p className="error">{calcError}</p>}
      {warnings.length > 0 && <div className="notice"><ul>{warnings.map((w) => <li key={w}>{w}</li>)}</ul></div>}
      {open && <p className="muted" style={{ marginTop: 0 }} dangerouslySetInnerHTML={{ __html: t('timeline.xpNote', { gold: meta.startingGold }) }} />}
      {open && result && compareIds.length === 0 && <TimelineTable result={result} itemsById={itemsById} />}
      {open && result && compareIds.length > 0 && (
        <div className="compare-grid">
          {chartSeries.map((s) => (
            <div key={s.key}>
              <h4><span className="swatch" style={{ background: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length] }} />{s.name}</h4>
              {s.result ? <TimelineTable result={s.result} itemsById={itemsById} compact /> : <p className="muted">{t('compare.calculating')}</p>}
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

/** Stats over time (one stat, effective gold or passives), with the compared builds; a click selects the minute. */
export function ChartSection() {
  const { meta, result, chartStat, setChartStat, compareIds, sameConds, setSameConds, chartSeries, moment, setSelectedMinute,
    itemsById, effAlign, setEffAlign, passiveValues } = useBuild();
  if (!result) return <p className="muted">{t('timeline.needInputs')}</p>;
  return (
    <section className="panel">
      <div className="spread">
        <h3>
          {chartStat === EFFECTIVE_VIEW ? t('chart.effectiveTitle') : chartStat === PASSIVES_VIEW ? t('chart.passivesTitle')
            : <><StatIcon stat={chartStat} size={16} />{t('chart.title', { stat: statLabel(chartStat) })}</>}
        </h3>
        <span className="row chart-head-tools">
          {compareIds.length > 0 && (
            <label className="check" title={t('compare.sameCondsHint')}>
              <input type="checkbox" checked={sameConds} onChange={(e) => setSameConds(e.target.checked)} />
              {t('compare.sameConds')}
            </label>
          )}
          <small>{t('chart.compareHint')}</small>
        </span>
      </div>
      <StatChart
        series={chartSeries} stat={chartStat} onStatChange={setChartStat} xpTable={meta.xpTable}
        selectedMinute={moment} onSelectMinute={setSelectedMinute} itemsById={itemsById}
        align={effAlign} onAlignChange={setEffAlign} passiveValues={passiveValues}
      />
    </section>
  );
}

/** The champion's stat sheet at the minute, with the objectives and what the passives give (the old fixed bar's details). */
export function StatusSection() {
  const { point, itemsById, payload, result, passiveValues, objectives, setObjectives, selectedMinute } = useBuild();
  if (!point) return <p className="muted">{t('timeline.needInputs')}</p>;
  return (
    <div className="grid-2 moment-panels">
      <StatSheet point={point} itemsById={itemsById} />
      <div className="status-dock">
        <BuildDock point={point} itemsById={itemsById} selected={selectedMinute != null} draft={payload} inline
          overlay={passiveValues.overlayAt('active', point.minute)} prices={result?.statPrices}
          sales={[...(result?.steps ?? []), ...(result?.ignored ?? [])].filter((x) => x.saleTiming)}
          objectives={<ObjectivesPicker value={objectives} onChange={setObjectives} compact />} />
      </div>
    </div>
  );
}

/** Which items matter at the minute (gold value of what each adds, passed-on passives). */
export function RelevanceSection() {
  const { point, result, itemsById, payload, passiveValues } = useBuild();
  if (!point) return <p className="muted">{t('timeline.needInputs')}</p>;
  return <RelevanceReport point={point} prices={result.statPrices} itemsById={itemsById} draft={payload} overlay={passiveValues.overlayAt('active', point.minute)} />;
}

/** Effective gold of the items at the minute (stats and passives), side by side with the compared builds. */
export function EffectiveGoldSection() {
  const { point, compareIds, effAlign, setEffAlign, passiveValues, chartSeries, itemsById } = useBuild();
  if (!point) return <p className="muted">{t('timeline.needInputs')}</p>;
  return (
    <section className="panel">
      <h3>{t('eff.title', { time: mmss(point.minute), level: point.level })}</h3>
      <p className="muted" style={{ marginTop: 0 }}>{t('eff.help', { mid: n0(PASSIVE_GOLD_BY_TIER.tier_medio), full: n0(PASSIVE_GOLD_BY_TIER.aprimorado) })}</p>
      {compareIds.length > 0 && <AlignToggle value={effAlign} onChange={setEffAlign} />}
      <PassiveValueBar pv={passiveValues} />
      <EffectiveBreakdown
        align={effAlign}
        columns={chartSeries.filter((s) => s.result).map((s) => ({
          key: s.key, name: s.name, color: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length],
          eff: effectiveGold(pointAt(s.result.series, point.minute), itemsById, s.result.statPrices, passiveValues.overlayAt(s.key, point.minute)),
        }))}
      />
    </section>
  );
}

/** The passives held over time and at the minute. */
export function PassivesSection() {
  const { point, result, itemsById, payload, chartSeries } = useBuild();
  if (!point) return <p className="muted">{t('timeline.needInputs')}</p>;
  return (
    <PassivesPanel
      point={point} series={result.series} itemsById={itemsById} draft={payload}
      builds={chartSeries.filter((s) => s.result).map((s) => ({
        key: s.key, name: s.name, color: SERIES_COLORS[s.colorIndex % SERIES_COLORS.length], series: s.result.series,
      }))}
    />
  );
}

/** The effectiveness reports of the open build at the minute (test scenarios), with the compared builds. */
export function EffectivenessSection() {
  const { point, objectives, payload, draft, dirty, items, builds, folders, compareIds, condKey, compareForEffect } = useBuild();
  const [side, setSide] = useState('aguentar');
  if (!point) return <p className="muted">{t('timeline.needInputs')}</p>;
  return (
    <>
      <div className="chips" role="tablist">
        {['aguentar', 'causar'].map((k) => (
          <button key={k} role="tab" aria-selected={side === k} className={side === k ? 'active' : ''} onClick={() => setSide(k)}>{t(`off.side.${k}`)}</button>
        ))}
      </div>
      <div hidden={side !== 'aguentar'}>
        <EffectivenessPanel objectives={hasObjectives(objectives) ? objectives : null} build={payload} buildId={draft.id} name={draft.name}
          minute={point.minute} items={items} builds={builds} folders={folders}
          compareKey={`${compareIds.join(',')}|${condKey}`} compare={compareForEffect} />
      </div>
      {side === 'causar' && <OffensePanel build={payload} buildId={dirty ? null : draft.id} minute={point.minute} />}
    </>
  );
}

/** The open build tested at the minute: "Testar na Arena" and the stats under chosen effects. */
export function BuildLabSection() {
  const { point, draft, builds, items } = useBuild();
  if (!point) return <p className="muted">{t('timeline.needInputs')}</p>;
  return (
    <section className="panel">
      <h3>{t('ptest.panelTitle', { time: mmss(point.minute) })}</h3>
      <div className="row test-arena-row">
        <TestInArena buildId={draft.id} buildName={draft.name} minute={point.minute} builds={builds} />
        <small className="muted">{t('arenaTest.help')}</small>
      </div>
      <BuildTestPanels buildId={draft.id} minute={Math.round(point.minute * 100) / 100} items={items} />
    </section>
  );
}

/** The folders and builds: open, create, move, rename, mark for comparison. */
export function FoldersSection({ onOpened }) {
  const { archetypes, folders, builds, draft, compareIds, openBuild, newBuild, toggleCompare, loadTree, setMessage, setDraft } = useBuild();
  return (
    <FolderTree archetypes={archetypes}
      folders={folders}
      builds={builds}
      activeId={draft.id}
      compareIds={compareIds}
      onOpen={async (id) => { if (await openBuild(id)) onOpened?.(id); }}
      onNew={(f) => { newBuild(f); onOpened?.(null); }}
      onToggleCompare={toggleCompare}
      onChanged={loadTree}
      onError={setMessage}
      onMoved={(id, folderId) => setDraft((d) => (d && d.id === id ? { ...d, folderId } : d))}
    />
  );
}

/** The dialogs of the build's "Preparações": runes, spells and the copies to other builds. */
export function PrepEditors() {
  const { draft, builds, folders, prepEditor, setPrepEditor, update, setMessage, loadTree, itemsById } = useBuild();
  if (!draft || !prepEditor) return null;
  const done = (key) => async (n) => {
    setPrepEditor(null);
    setMessage(t(key, { n }));
    await loadTree();
  };
  return (
    <>
      {prepEditor === 'runes' && (
        <RuneEditor page={draft.runePage} matchEnd={draft.matchEnd} onChange={(runePage) => update({ runePage })} onClose={() => setPrepEditor(null)} />
      )}
      {prepEditor === 'spells' && (
        <SpellEditor spells={draft.spells} onChange={(spells) => update({ spells })} onClose={() => setPrepEditor(null)} />
      )}
      {prepEditor === 'items' && (
        <ItemSetupExport steps={draft.steps} setup={draft.itemSetup} itemsById={itemsById} builds={builds} folders={folders}
          currentId={draft.id} onClose={() => setPrepEditor(null)} onDone={done('itemExport.done')} />
      )}
      {prepEditor === 'skills' && (
        <SkillExport setup={draft.championSetup ?? emptyChampionSetup()} unitCode={draft.unitCode} builds={builds} folders={folders}
          currentId={draft.id} onClose={() => setPrepEditor(null)} onDone={done('skillExport.done')} />
      )}
      {prepEditor === 'export' && (
        <PrepExport page={draft.runePage} spells={draft.spells} builds={builds} folders={folders} currentId={draft.id}
          onClose={() => setPrepEditor(null)} onDone={done('prepExport.done')} />
      )}
    </>
  );
}

/** Whether the open build is loaded (the sections need it). */
export const useReady = () => !!useBuild()?.draft;

/** Opens the quick comparison sub tab. */
export const goCompare = () => navigate('/build/comparar-itens');

function RagdollEditor({ meta, stats, onChange }) {
  const set = (stat, field, value) => onChange({ ...stats, [stat]: { base: '', growth: '', ...stats[stat], [field]: value } });
  return (
    <div style={{ marginTop: 12 }}>
      <h4>{t('ragdoll.title')}</h4>
      <p className="muted" style={{ marginTop: 0 }}>{t('ragdoll.help')}</p>
      <div className="table-wrap">
        <table className="data">
          <thead><tr><th className="l">{t('ragdoll.stat')}</th><th>{t('ragdoll.base')}</th><th>{t('ragdoll.growth')}</th></tr></thead>
          <tbody>
            {meta.unitStats.map((s) => (
              <tr key={s}>
                <td className="l"><StatIcon stat={s} />{statLabel(s)}</td>
                <td><input type="number" value={stats[s]?.base ?? ''} onChange={(e) => set(s, 'base', e.target.value)} aria-label={`${statLabel(s)} ${t('ragdoll.base')}`} /></td>
                <td><input type="number" value={stats[s]?.growth ?? ''} onChange={(e) => set(s, 'growth', e.target.value)} aria-label={`${statLabel(s)} ${t('ragdoll.growth')}`} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
