import { useRef, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import FolderTree from '../components/FolderTree.jsx';
import BuildPreview from '../components/BuildPreview.jsx';
import { useBuild } from '../buildContext.jsx';
import { navigate } from '../router.js';
import { PUBLIC } from '../publicMode.js';
import { buildLink } from '../shareLink.js';

/**
 * The "Builds" super tab (Rodada 8): the folders and builds that were the planner's side column, with a preview of the
 * build picked, and create, duplicate, import and export. Opening a build makes it the app's open build.
 */
export default function Builds() {
  const { archetypes, folders, builds, draft, compareIds, newBuild, toggleCompare, loadTree, setMessage, setDraft, message } = useBuild();
  const [picked, setPicked] = useState(null);
  const [error, setError] = useState(null);
  const file = useRef(null);

  const duplicate = async (id) => {
    try {
      const b = await api.get(`/api/builds/${id}`);
      const copy = await api.post('/api/builds', { ...b, id: null, name: `${b.name}${t('build.copySuffix')}` });
      await loadTree();
      setPicked(copy.id);
      setMessage(t('builds.duplicated', { name: copy.name }));
    } catch (e) {
      setError(e.message);
    }
  };

  const exportBuild = async (id) => {
    try {
      const b = await api.get(`/api/builds/${id}`);
      const blob = new Blob([JSON.stringify({ format: 'ornn-planner-build', version: 1, build: b }, null, 2)], { type: 'application/json' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = `${b.name.replace(/[^\p{L}\p{N} _-]/gu, '').trim() || 'build'}.json`;
      a.click();
      URL.revokeObjectURL(a.href);
    } catch (e) {
      setError(e.message);
    }
  };

  const copyLink = async (id) => {
    try {
      const link = await buildLink(await api.get(`/api/builds/${id}`));
      try {
        await navigator.clipboard.writeText(link);
        setMessage(t('pub.linkCopied'));
      } catch {
        window.prompt(t('pub.linkCopy'), link);
      }
    } catch (e) {
      setError(e.message);
    }
  };

  const importBuild = async (f) => {
    setError(null);
    try {
      const data = JSON.parse(await f.text());
      const b = data?.build ?? data;
      if (!b || !Array.isArray(b.steps)) throw new Error(t('builds.importBad'));
      const folderId = folders.some((x) => x.id === b.folderId) ? b.folderId : folders[0]?.id;
      const saved = await api.post('/api/builds', { ...b, id: null, folderId, createdAt: null, updatedAt: null });
      await loadTree();
      setPicked(saved.id);
      setMessage(t('builds.imported', { name: saved.name }));
    } catch (e) {
      setError(t('builds.importError', { msg: e.message }));
    }
  };

  const pickedBuild = builds.find((b) => b.id === picked);
  return (
    <div className="builds-page">
      <section className="panel builds-tree">
        <div className="row">
          <button className="primary" onClick={() => { newBuild(); navigate('/build/loja'); }}>{t('builds.new')}</button>
          <button onClick={() => file.current?.click()}>{t('builds.import')}</button>
          <input ref={file} type="file" accept="application/json,.json" hidden
            onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) importBuild(f); }} />
        </div>
        {message && <p className="notice small">{message}</p>}
        {error && <p className="error">{error}</p>}
        <p className="muted small">{t(PUBLIC ? 'pub.buildsHelp' : 'builds.help')}</p>
        <FolderTree archetypes={archetypes}
          folders={folders}
          builds={builds}
          activeId={picked ?? draft?.id}
          compareIds={compareIds}
          onOpen={(id) => setPicked(id)}
          onNew={(f) => { newBuild(f); navigate('/build/loja'); }}
          onToggleCompare={toggleCompare}
          onChanged={loadTree}
          onError={setMessage}
          onMoved={(id, folderId) => setDraft((d) => (d && d.id === id ? { ...d, folderId } : d))}
        />
        {compareIds.length > 0 && (
          <p className="small">
            {t('builds.comparing', { n: compareIds.length })}{' '}
            <button className="link" onClick={() => navigate('/relatorios/comparar-builds')}>{t('builds.openCompare')}</button>
          </p>
        )}
      </section>
      <section className="panel builds-preview">
        {!pickedBuild && <p className="muted">{t('builds.pickOne')}</p>}
        {pickedBuild && (
          <>
            <div className="spread">
              <h3>{pickedBuild.name}</h3>
              <span className="row">
                <button onClick={() => duplicate(pickedBuild.id)}>{t('build.duplicate')}</button>
                <button onClick={() => exportBuild(pickedBuild.id)}>{t('builds.export')}</button>
                <button onClick={() => copyLink(pickedBuild.id)} title={t('pub.linkHelp')}>{t('pub.link')}</button>
              </span>
            </div>
            <BuildPreview id={pickedBuild.id} />
          </>
        )}
      </section>
    </div>
  );
}
