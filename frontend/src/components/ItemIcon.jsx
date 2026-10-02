import { useState } from 'react';

/** Item icon served by the backend (bundled locally); renders an empty square when there is none. */
export default function ItemIcon({ id, size = 28 }) {
  const [failed, setFailed] = useState(false);
  const style = { width: size, height: size };
  if (id == null || failed) return <span className="item-icon empty" style={style} aria-hidden="true" />;
  return (
    <img
      className="item-icon" style={style} src={`/api/items/${id}/image`} alt="" loading="lazy"
      onError={() => setFailed(true)}
    />
  );
}
