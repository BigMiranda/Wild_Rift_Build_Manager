import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api.js';
import { t } from '../i18n.js';
import { buildLexicon, parse } from '../voiceParser.js';
import { runeCatalog } from '../runes.js';
import { applyIntent } from '../liveGame.js';

const NOTICE_KEY = 'ornn-live-voice-notice';
let aliasCache = null;
export const loadAliases = (fresh = false) => {
  if (!aliasCache || fresh) aliasCache = api.get('/api/draft/apelidos').catch((e) => { aliasCache = null; throw e; });
  return aliasCache;
};

/** The browser's speech recognition (Chrome and Edge), or null. */
const Recognition = typeof window !== 'undefined' ? (window.SpeechRecognition ?? window.webkitSpeechRecognition ?? null) : null;

/**
 * Rodada 20, Parte 2: the microphone (the browser's Web Speech API, pt-BR, continuous, partial results) and a text box
 * that reads the same phrases. What the reader understood shows highlighted by field, each phrase with ✓ / ✗; nothing
 * is written before it is on the screen. On browsers without the API the microphone stays hidden, with a hint.
 */
export default function VoiceBar({ game, champions, items, onApply, onUndo }) {
  const [aliases, setAliases] = useState([]);
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState('');
  const [text, setText] = useState('');
  const [pending, setPending] = useState([]); // [{id, phrase, intent, unknown}]
  const [notice, setNotice] = useState(false);
  const [error, setError] = useState(null);
  const rec = useRef(null);
  const seq = useRef(0);
  useEffect(() => { loadAliases().then(setAliases).catch(() => {}); }, []);

  const lexicon = useMemo(() => {
    const cat = runeCatalog();
    return buildLexicon({
      champions: champions.map((c) => c.nome),
      spells: (cat?.spells ?? []).map((s) => s.name),
      keystones: (cat?.keystones ?? []).map((r) => r.name),
      runes: (cat?.runes ?? []).map((r) => r.name),
      items: items.map((i) => ({ id: i.id, name: i.name })),
      aliases,
    });
    // the rune catalog arrives once with the app; champions, items and nicknames are the inputs
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [champions, items, aliases, runeCatalog()]);

  const read = (phrase) => {
    const { intents, unknown } = parse(phrase, lexicon);
    if (!intents.length) {
      setPending((p) => [...p, { id: ++seq.current, phrase, intent: null, unknown }]);
      return;
    }
    setPending((p) => [...p, ...intents.map((intent) => ({ id: ++seq.current, phrase, intent, unknown }))]);
  };

  // the recognition's callbacks read the reader of the latest render
  const readRef = useRef(read);
  readRef.current = read;

  const runeInfo = (name) => {
    const r = runeCatalog()?.runes?.find((x) => x.name === name);
    return r ? { tree: r.tree, row: r.row } : null;
  };
  const accept = (ids) => {
    let g = game;
    const errors = [];
    for (const p of pending.filter((x) => ids.includes(x.id) && x.intent)) {
      const r = applyIntent(g, p.intent, items, runeInfo);
      if (r.error) errors.push(`${p.intent.champion ?? p.intent.kind}: ${t(`voice.err.${r.error}`)}`);
      g = r.game;
    }
    if (g !== game) onApply(g);
    setError(errors.length ? errors.join(' · ') : null);
    setPending((p) => p.filter((x) => !ids.includes(x.id)));
  };

  const start = () => {
    if (!Recognition) return;
    try {
      if (!localStorage.getItem(NOTICE_KEY)) {
        setNotice(true);
        return;
      }
    } catch { /* no storage: the notice shows every time */ }
    const r = new Recognition();
    r.lang = 'pt-BR';
    r.continuous = true;
    r.interimResults = true;
    r.onresult = (e) => {
      let partial = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const res = e.results[i];
        if (res.isFinal) readRef.current(res[0].transcript);
        else partial += res[0].transcript;
      }
      setInterim(partial);
    };
    r.onerror = (e) => setError(t('voice.recError', { msg: e.error }));
    r.onend = () => { setListening(false); setInterim(''); };
    rec.current = r;
    r.start();
    setListening(true);
  };
  const stop = () => rec.current?.stop();
  useEffect(() => () => rec.current?.abort?.(), []);

  const secure = typeof window !== 'undefined' && window.isSecureContext;
  return (
    <div className="live-voice">
      {Recognition && secure ? (
        <button type="button" className={`live-mic${listening ? ' on' : ''}`} aria-pressed={listening} onClick={listening ? stop : start}
          title={listening ? t('voice.stop') : t('voice.start')}>
          {listening ? '■' : '🎤'} {listening ? t('voice.listening') : t('voice.mic')}
        </button>
      ) : (
        <small className="muted live-voice-hint" title={Recognition ? t('voice.needSecure') : t('voice.noApi')}>ⓘ {t('voice.noMic')}</small>
      )}
      <form className="live-voice-text" onSubmit={(e) => { e.preventDefault(); if (text.trim()) read(text); setText(''); }}>
        <input value={text} onChange={(e) => setText(e.target.value)} placeholder={t('voice.typeHere')} aria-label={t('voice.typeHere')} />
      </form>
      {interim && <span className="live-interim">{interim}…</span>}
      {notice && (
        <div className="notice live-voice-notice" role="alertdialog">
          <p>{t('voice.notice')}</p>
          <button type="button" className="primary" onClick={() => {
            try { localStorage.setItem(NOTICE_KEY, '1'); } catch { /* ignore */ }
            setNotice(false);
            setTimeout(start, 0);
          }}>{t('voice.noticeOk')}</button>
          <button type="button" onClick={() => setNotice(false)}>{t('voice.noticeNo')}</button>
        </div>
      )}
      {(pending.length > 0 || error) && (
        <div className="live-voice-pending" aria-live="polite">
          {error && <div className="notice error small" onClick={() => setError(null)}>{error}</div>}
          {pending.map((p) => (
            <div key={p.id} className={`live-intent${p.intent ? '' : ' none'}`}>
              <small className="muted live-heard">“{p.phrase}”</small>
              {p.intent ? (
                <span className="live-parts">
                  <strong className="live-intent-type">{t(`voice.type.${p.intent.type}`)}</strong>
                  {p.intent.parts.map((x, k) => (
                    <span key={k} className={`live-part${x.score < 0.95 ? ' fuzzy' : ''}`} title={t('voice.heard', { heard: x.heard, pct: Math.round((x.score ?? 1) * 100) })}>
                      <small>{t(`voice.field.${x.field}`)}</small> {String(x.value)}
                    </span>
                  ))}
                </span>
              ) : <span className="muted">{t('voice.nothing')}{p.unknown?.length ? ` (${p.unknown.join(', ')})` : ''}</span>}
              {p.intent && <button type="button" className="live-ok" onClick={() => accept([p.id])} aria-label={t('voice.accept')}>✓</button>}
              <button type="button" className="live-no" onClick={() => setPending((x) => x.filter((y) => y.id !== p.id))} aria-label={t('voice.reject')}>✗</button>
            </div>
          ))}
          <div className="row">
            {pending.filter((p) => p.intent).length > 1 && (
              <button type="button" className="primary" onClick={() => accept(pending.filter((p) => p.intent).map((p) => p.id))}>✓ {t('voice.acceptAll')}</button>
            )}
            {onUndo && <button type="button" onClick={onUndo}>↶ {t('live.undo')}</button>}
          </div>
        </div>
      )}
    </div>
  );
}
