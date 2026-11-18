import { useState } from 'react';
import { t } from '../i18n.js';
import DraftPanel from './DraftPanel.jsx';
import EncounterPanel from './EncounterPanel.jsx';
import LiveBuildsPanel from './LiveBuildsPanel.jsx';

const PANELS = ['draft', 'encontro', 'builds'];

/**
 * Rodada 20: the panels under the two teams, one at a time (the screen stays short on a phone): the draft (Parte 3),
 * the first encounter (Parte 4), the likely builds (Parte 5) and the tips (Parte 6).
 */
export default function LivePanels(props) {
  const [own, setOwn] = useState('draft');
  const panel = props.panel ?? own;
  const setPanel = (k) => (props.onPanel ? props.onPanel(k) : setOwn(k));
  return (
    <div className="live-panels">
      <div className="chips live-panel-tabs" role="tablist">
        {PANELS.map((k) => (
          <button key={k} type="button" role="tab" aria-selected={panel === k} className={panel === k ? 'active' : ''} onClick={() => setPanel(k)}>
            {t(`live.panel.${k}`)}
          </button>
        ))}
      </div>
      {panel === 'draft' && <DraftPanel game={props.game} onChange={props.onChange} />}
      {panel === 'encontro' && <EncounterPanel game={props.game} selected={props.selected} />}
      {panel === 'builds' && <LiveBuildsPanel game={props.game} selected={props.selected} />}
    </div>
  );
}
