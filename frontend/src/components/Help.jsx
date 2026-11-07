/** A "?" that explains a number on hover or focus: what it is, where it comes from, whether more is better. */
export default function Help({ text }) {
  if (!text) return null;
  return <span className="help-q" title={text} tabIndex={0} role="img" aria-label={text}>?</span>;
}
