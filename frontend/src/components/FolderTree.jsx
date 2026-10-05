import { api } from '../api.js';
import { t } from '../i18n.js';

/** Single-level folders with their builds. */
export default function FolderTree({ folders, builds, activeId, compareIds, onOpen, onNew, onToggleCompare, onChanged, onError }) {
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

  return (
    <div>
      <div className="spread folders-head" style={{ marginBottom: 10 }}>
        <h3 style={{ margin: 0 }}>{t('folders.title')}</h3>
        <button onClick={createFolder}>{t('folders.new')}</button>
      </div>
      {folders.map((f) => {
        const inFolder = builds.filter((b) => b.folderId === f.id);
        return (
          <div className="folder" key={f.id}>
            <div className="folder-head">
              <span className="name" title={f.name}>📁 {f.name}</span>
              <button className="icon" title={t('folders.newBuild')} aria-label={`${t('folders.newBuild')}: ${f.name}`} onClick={() => onNew(f.id)}>+</button>
              <button className="icon" title={t('folders.rename')} aria-label={`${t('folders.rename')}: ${f.name}`} onClick={() => renameFolder(f)}>✎</button>
              <button className="icon danger" title={t('folders.delete')} aria-label={`${t('folders.delete')}: ${f.name}`} onClick={() => deleteFolder(f)}>✕</button>
            </div>
            <ul className="build-list">
              {inFolder.length === 0 && <li className="muted">{t('folders.empty')}</li>}
              {inFolder.map((b) => (
                <li key={b.id} className={b.id === activeId ? 'active' : ''}>
                  <button className="open" title={b.note || b.name} onClick={() => onOpen(b.id)}>
                    {b.name}
                  </button>
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
