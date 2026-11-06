import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { t } from '../i18n.js';
import { navigate } from '../router.js';

/**
 * The side drawer (Rodada 8, parte 3): another sub tab's content over the screen, without leaving it. It never changes
 * the screen below, closes with Esc or ×, and "abrir como aba" takes the content to its full tab.
 *
 * `open({ title, render, path, params })`: `render()` gives the content, `path` the tab it comes from (optional).
 */
const DrawerContext = createContext({ open: () => {}, close: () => {} });
export const useDrawer = () => useContext(DrawerContext);

export function DrawerProvider({ children }) {
  const [panel, setPanel] = useState(null);
  const open = useCallback((p) => setPanel(p), []);
  const close = useCallback(() => setPanel(null), []);
  useEffect(() => {
    if (!panel) return undefined;
    const onKey = (e) => { if (e.key === 'Escape') setPanel(null); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [panel]);
  return (
    <DrawerContext.Provider value={{ open, close, panel }}>
      {children}
      {panel && (
        <>
          <div className="drawer-scrim" onClick={close} aria-hidden="true" />
          <aside className="drawer" role="dialog" aria-modal="false" aria-label={panel.title}>
            <header className="drawer-head">
              <h3>{panel.title}</h3>
              {panel.path && (
                <button className="link" onClick={() => { navigate(panel.path, panel.params); close(); }}>{t('drawer.asTab')}</button>
              )}
              <button className="icon" onClick={close} aria-label={t('close')} title={t('drawer.closeHint')}>✕</button>
            </header>
            <div className="drawer-body">{panel.render()}</div>
          </aside>
        </>
      )}
    </DrawerContext.Provider>
  );
}
