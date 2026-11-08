import { siteHref, useSite } from './StaticApp.jsx';

/** Sobre: de onde vêm os números, o que não existe aqui e a escala da tela. */
export default function AboutPage({ scale, setScale }) {
  const { manifest } = useSite();
  return (
    <section className="about">
      <h1 className="site-title">Sobre</h1>
      <p>
        Um site estático com builds de League of Legends: Wild Rift calculadas num app local: a linha do tempo de compras,
        a efetividade em cenários de luta e as habilidades nos minutos-chave. Os números são do patch {manifest?.patch} e
        foram calculados no PC do autor{manifest?.export?.exported_at ? ` (exportação de ${manifest.export.exported_at.slice(0, 10)})` : ''}.
      </p>
      <h2>O que não existe aqui</h2>
      <p>Tudo o que precisa de servidor: a Arena ao vivo, o comparador que recalcula, uma Efetividade nova, o treino de IA,
        a Descoberta, a calculadora e a comunidade. Para mudar um item ou o ritmo e ver os números, use o app completo.</p>
      <h2>Enviar uma build</h2>
      <p>Monte em <a href={siteHref('montar')}>Montar</a>, copie o link ou exporte o arquivo, e envie pelo formulário
        “Enviar build” das issues do repositório deste site. As builds recebidas são revisadas à mão.</p>
      <h2>Tela</h2>
      <div className="chips">
        {[['normal', 'Normal'], ['grande', 'Grande']].map(([k, l]) => (
          <button key={k} type="button" className={scale === k ? 'on' : ''} onClick={() => setScale(k)}>{l}</button>
        ))}
      </div>
      <p className="muted small">{manifest?.champions?.length ?? 0} campeões, formato {manifest?.format}, gerado em {manifest?.generatedAt}.</p>
    </section>
  );
}
