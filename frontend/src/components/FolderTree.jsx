import { api } from '../api.js';

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
    const name = window.prompt('Nome da nova pasta:');
    if (name) run(() => api.post('/api/folders', { name }));
  };
  const renameFolder = (f) => {
    const name = window.prompt('Novo nome da pasta:', f.name);
    if (name && name !== f.name) run(() => api.put(`/api/folders/${f.id}`, { name }));
  };
  const deleteFolder = (f) => {
    if (window.confirm(`Apagar a pasta "${f.name}"? (só funciona se estiver vazia)`)) run(() => api.del(`/api/folders/${f.id}`));
  };

  return (
    <div>
      <div className="spread" style={{ marginBottom: 10 }}>
        <h3 style={{ margin: 0 }}>Builds</h3>
        <button onClick={createFolder}>+ Pasta</button>
      </div>
      {folders.map((f) => {
        const inFolder = builds.filter((b) => b.folderId === f.id);
        return (
          <div className="folder" key={f.id}>
            <div className="folder-head">
              <span className="name" title={f.name}>📁 {f.name}</span>
              <button className="icon" title="Nova build nesta pasta" aria-label={`Nova build em ${f.name}`} onClick={() => onNew(f.id)}>+</button>
              <button className="icon" title="Renomear pasta" aria-label={`Renomear ${f.name}`} onClick={() => renameFolder(f)}>✎</button>
              <button className="icon danger" title="Apagar pasta" aria-label={`Apagar ${f.name}`} onClick={() => deleteFolder(f)}>✕</button>
            </div>
            <ul className="build-list">
              {inFolder.length === 0 && <li className="muted">vazia</li>}
              {inFolder.map((b) => (
                <li key={b.id} className={b.id === activeId ? 'active' : ''}>
                  <button className="open" title={b.note || b.name} onClick={() => onOpen(b.id)}>
                    {b.name}
                  </button>
                  {b.id !== activeId && (
                    <label className="check" title="Comparar com a build ativa">
                      <input type="checkbox" checked={compareIds.includes(b.id)} onChange={() => onToggleCompare(b.id)} />
                      <small>comparar</small>
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
