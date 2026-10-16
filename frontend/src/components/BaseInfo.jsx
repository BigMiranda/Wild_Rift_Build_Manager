import { currentLang, t } from '../i18n.js';

/** Which game data the app runs on: the patch, where its numbers came from and when (meta.base, from loja_<patch>.yml). */
export default function BaseInfo({ base }) {
  if (!base?.patch) {
    return null;
  }
  const [y, m, d] = String(base.atualizado ?? '').split('-');
  const date = d ? (currentLang() === 'en' ? `${y}-${m}-${d}` : `${d}/${m}/${y}`) : null;
  const source = base.fonte === 'notas de patch' ? t('base.fromNotes') : t('base.fromScreens');
  return <p className="muted">{t(date ? 'base.line' : 'base.lineNoDate', { patch: base.patch, source, date })}</p>;
}
