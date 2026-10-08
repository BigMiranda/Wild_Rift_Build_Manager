import { useEffect, useState } from 'react';
import { mmss } from '../format.js';
import { parseMinutes } from './BuildBar.jsx';

/** Game time typed as m:ss (or plain minutes); `value` in minutes, null when empty. */
export default function MinuteInput({ value, onChange, placeholder = '20:00', ...rest }) {
  const [text, setText] = useState(value != null ? mmss(value) : '');
  useEffect(() => {
    if (value == null) setText((t) => (t.trim() === '' ? t : ''));
    else if (parseMinutes(text) !== value) setText(mmss(value));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);
  return (
    <input
      {...rest}
      value={text}
      placeholder={placeholder}
      inputMode="decimal"
      aria-invalid={text.trim() !== '' && parseMinutes(text) == null}
      onChange={(e) => {
        setText(e.target.value);
        if (e.target.value.trim() === '') onChange(null);
        else {
          const v = parseMinutes(e.target.value);
          if (v != null) onChange(v);
        }
      }}
    />
  );
}
