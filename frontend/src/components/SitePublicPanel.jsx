import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { n0 } from '../format.js';
import { navigate } from '../router.js';

const mb = (b) => (b == null ? '—' : `${(b / 1024 / 1024).toFixed(1)} MB`);
const day = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '—');
const fmtTime = (s) => (s == null ? '—' : s >= 3600 ? `${(s / 3600).toFixed(1)} h` : `${Math.round(s / 60)} min`);

function Step({ n, ok, title, children }) {
  return (
    <li className="site-step">
      <strong>{ok ? '✅' : '⬜'} {n}. {title}</strong>
      <div className="small">{children}</div>
    </li>
  );
}

/**
 * Admin › Catálogo › "Site público" (Rodada 11.b, part 5): the five steps of feeding the static site, each with its
 * state for the current patch (GET /api/site/status): the campaigns, what is marked public, the export, the generated
 * site and the push to its repository.
 */
export default function SitePublicPanel() {
  const [s, setS] = useState(null);
  const [error, setError] = useState(null);
  const [budget, setBudget] = useState('rapido');
  const [estimate, setEstimate] = useState(null);
  const load = () => api.get('/api/site/status').then(setS).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);
  useEffect(() => {
    setEstimate(null);
    api.post('/api/campaigns/estimate', { all: true, discovery: { budget }, skipLowConfidence: true })
      .then(setEstimate).catch(() => {});
  }, [budget]);
  if (error) return <section className="panel"><h3>Site público</h3><p className="error">{error}</p></section>;
  if (!s) return null;
  const exp = s.export;
  const exportOk = exp && exp.patch === s.patch;
  const site = s.site;
  const siteOk = site && site.patch === s.patch && exp && site.export?.exported_at === exp.exportedAt;
  return (
    <section className="panel">
      <div className="spread">
        <h3>Site público <small className="muted">patch {s.patch}</small></h3>
        <button onClick={load}>Atualizar</button>
      </div>
      <p className="muted small">
        O site estático (GitHub Pages) mostra só o que foi calculado aqui. Hoje: {s.discovered.length} campeão(ões) com
        Descoberta terminada, {n0(s.publicBuilds)} build(s) pública(s), site estimado em {mb(s.estimatedBytes)}
        {site ? ` (o último gerado tem ${mb(site.bytes)})` : ''}, última exportação {exp ? day(exp.exportedAt) : 'nenhuma'}.
      </p>
      <ol className="site-steps">
        <Step n={1} ok={s.discovered.length > 0} title="Rodar as campanhas">
          Admin › Campanhas › “Todos os campeões”. {s.goodSheets.length} de {s.championsTotal} campeões têm ficha boa
          ({s.goodSheets.join(', ')}); os outros são pulados como “baixa confiança”.
          <div className="row">
            <label className="inline">Orçamento
              <select value={budget} onChange={(e) => setBudget(e.target.value)}>
                <option value="rapido">rápido</option>
                <option value="completo">completo</option>
              </select>
            </label>
            {estimate && <span>{estimate.champions} campeão(ões), ~{fmtTime(estimate.seconds)}{estimate.alreadyDone ? `, ${estimate.alreadyDone} já feito(s)` : ''}</span>}
            <button className="link" onClick={() => navigate('/admin/campanhas')}>abrir Campanhas</button>
          </div>
          {s.discovered.length > 0 && <div className="muted">Com Descoberta: {s.discovered.join(', ')}{s.queued ? ` · ${s.queued} na fila` : ''}.</div>}
        </Step>
        <Step n={2} ok={s.publicFolders.length > 0} title="Marcar as pastas e guias públicas (🔒/🌐)">
          {s.publicFolders.length ? `Pastas públicas: ${s.publicFolders.join(', ')} (${s.publicBuilds} builds).` : 'Nenhuma pasta pública: a exportação recusa.'}
          {' '}{s.publicGuides} guia(s) pública(s). As builds das pastas privadas nunca saem, nem dentro da Descoberta.
        </Step>
        <Step n={3} ok={!!exportOk} title="Exportar a base pública">
          {exp ? <>Última: {day(exp.exportedAt)}, patch {exp.patch}, {mb(exp.sizeBytes)}, pastas {exp.folders?.join(', ')}.
            {!exportOk && ' É de outro patch: exporte de novo.'}</> : 'Ainda não exportada.'}
          {' '}O botão “Exportar a base pública” fica logo abaixo (ou <code>cd backend &amp;&amp; ./mvnw -Pexport</code>).
        </Step>
        <Step n={4} ok={!!siteOk} title="Gerar o site">
          <code>python tools/publicar_site.py</code> (sobe o app sobre uma cópia da exportação, gera os JSONs, faz o build
          da página e monta <code>site-publico/</code>).
          {site ? <> Último: {day(site.generatedAt)}, {site.champions?.length} campeões, {site.files} arquivos, {mb(site.bytes)}
            {!siteOk && ' — anterior à última exportação: gere de novo.'}</> : ' Ainda não gerado.'}
        </Step>
        <Step n={5} ok={false} title="Copiar para o repositório do site e dar push">
          Copie o conteúdo de <code>site-publico/</code> para o repositório do site (modelo em <code>tools/site-repo/</code>),
          <code> git add -A</code>, commit e push: o Actions publica. Passo a passo em <code>docs/rodada-11b.md</code>.
        </Step>
      </ol>
    </section>
  );
}
