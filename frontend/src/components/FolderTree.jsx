import { useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';

/**
 * Single-level folders with their builds. A build goes to another folder with its ⇄ button (a folder list) or by
 * dragging it onto the folder.
 */
export default function FolderTree({ folders, builds, activeId, compareIds, onOpen, onNew, onToggleCompare, onChanged, onError, onMoved }) {
  const [moving, setMoving] = useState(null);
  const [dragged, setDragged] = useState(null);
  const [dropFolder, setDropFolder] = useState(null);
  const run = async (fn) => {
    try {
      await fn();
      await onChanged();
    } catch (e) {
      onError(e.message);
    }
  };

  const createFolder = () => {
    const name = window.prompt(t('folders.newPrompt'));
    if (name) run(() => api.post('/api/folders', { name }));
  };
  const renameFolder = (f) => {
    const name = window.prompt(t('folders.renamePrompt'), f.name);
    if (name && name !== f.name) run(() => api.put(`/api/folders/${f.id}`, { name }));
  };
  const deleteFolder = (f) => {
    if (window.confirm(t('folders.deleteConfirm', { name: f.name }))) run(() => api.del(`/api/folders/${f.id}`));
  };

  const moveBuild = (b, folderId) => {
    setMoving(null);
    if (!folderId || folderId === b.folderId) return;
    run(async () => {
      await api.put(`/api/builds/${b.id}/folder`, { folderId });
      onMoved?.(b.id, folderId);
    });
  };
  const dropProps = (f) => ({
    onDragOver: (e) => { if (dragged) { e.preventDefault(); setDropFolder(f.id); } },
    onDragLeave: () => setDropFolder((d) => (d === f.id ? null : d)),
    onDrop: (e) => {
      e.preventDefault();
      if (dragged) moveBuild(dragged, f.id);
      setDragged(null);
      setDropFolder(null);
    },
  });

  return (
    <div>
      <div className="spread folders-head" style={{ marginBottom: 10 }}>
        <h3 style={{ margin: 0 }}>{t('folders.title')}</h3>
        <button onClick={createFolder}>{t('folders.new')}</button>
      </div>
      {folders.map((f) => {
        const inFolder = builds.filter((b) => b.folderId === f.id);
        return (
          <div className={`folder${dropFolder === f.id && dragged?.folderId !== f.id ? ' drop' : ''}`} key={f.id} {...dropProps(f)}>
            <div className="folder-head">
              <span className="name" title={f.name}>📁 {f.name}</span>
              <button className="icon" title={t('folders.newBuild')} aria-label={`${t('folders.newBuild')}: ${f.name}`} onClick={() => onNew(f.id)}>+</button>
              <button className="icon" title={t('folders.rename')} aria-label={`${t('folders.rename')}: ${f.name}`} onClick={() => renameFolder(f)}>✎</button>
              <button className="icon danger" title={t('folders.delete')} aria-label={`${t('folders.delete')}: ${f.name}`} onClick={() => deleteFolder(f)}>✕</button>
            </div>
            <ul className="build-list">
              {inFolder.length === 0 && <li className="muted">{t('folders.empty')}</li>}
              {inFolder.map((b) => (
                <li key={b.id} className={b.id === activeId ? 'active' : ''} draggable
                  onDragStart={(e) => { setDragged(b); e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', String(b.id)); }}
                  onDragEnd={() => { setDragged(null); setDropFolder(null); }}>
                  {moving === b.id ? (
                    <select autoFocus value="" aria-label={t('folders.moveTo', { name: b.name })}
                      onChange={(e) => moveBuild(b, Number(e.target.value))} onBlur={() => setMoving(null)}
                      onKeyDown={(e) => { if (e.key === 'Escape') setMoving(null); }}>
                      <option value="">{t('folders.moveTo', { name: b.name })}</option>
                      {folders.filter((x) => x.id !== b.folderId).map((x) => <option key={x.id} value={x.id}>📁 {x.name}</option>)}
                    </select>
                  ) : (
                    <button className="open" title={b.note || b.name} onClick={() => onOpen(b.id)}>
                      {b.name}
                    </button>
                  )}
                  {folders.length > 1 && moving !== b.id && (
                    <button className="icon move" title={t('folders.move')} aria-label={t('folders.moveTo', { name: b.name })}
                      onClick={() => setMoving(b.id)}>⇄</button>
                  )}
                  {b.id !== activeId && (
                    <label className="check" title={t('folders.compareTitle')}>
                      <input type="checkbox" checked={compareIds.includes(b.id)} onChange={() => onToggleCompare(b.id)} />
                      <small>{t('folders.compare')}</small>
                    </label>
                  )}
                </li>
              ))}
            </ul>
          </div>
        );
      })}
    </div>
  );
}
