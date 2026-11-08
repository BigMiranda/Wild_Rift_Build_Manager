/**
 * The static site's file name of an API call (Rodada 11.b, part 3). The one rule shared with `tools/site_estatico.py`
 * (`arquivo_estatico`); both sides are tested against `tools/caminhos_estaticos.json`.
 *
 *   /api/x?y=1            -> api/x/y=1.json
 *   /api/guides?unit=K'SANTE&b=2 -> api/guides/unit=K~27SANTE_b=2.json
 *
 * - The path and the query are percent-decoded first, so "/api/a%20b" and "/api/a b" are the same file.
 * - Each piece keeps [A-Za-z0-9._=-]; any other character becomes "~" and the hex of its UTF-8 bytes ("é" -> ~C3~A9,
 *   " " -> ~20): ASCII only, the same on Windows, Linux and in a URL, and never a "%" a browser would decode again.
 * - The query's parameters, in their order, joined by "_", become one last path piece.
 * - A JSON answer gets ".json"; a binary (icon) keeps its extension, or gets ".png", and drops its query (a cache key).
 */

const SAFE = /[A-Za-z0-9._=-]/;

function decode(s) {
  try {
    return decodeURIComponent(s.replace(/\+/g, ' '));
  } catch {
    return s;
  }
}

/** One piece of a path, made safe. */
export function safePiece(s) {
  let out = '';
  for (const ch of s) {
    if (SAFE.test(ch)) {
      out += ch;
    } else {
      for (const b of new TextEncoder().encode(ch)) out += `~${b.toString(16).toUpperCase().padStart(2, '0')}`;
    }
  }
  return out;
}

/** The file (relative, no leading slash) of an API url; `binary` for icons. */
export function staticFile(url, binary = false) {
  const q = url.indexOf('?');
  const path = q < 0 ? url : url.slice(0, q);
  const query = q < 0 ? '' : url.slice(q + 1);
  const pieces = path.split('/').filter((p) => p !== '').map((p) => safePiece(decode(p)));
  if (binary) {
    const last = pieces[pieces.length - 1] ?? '';
    return pieces.join('/') + (/\.[A-Za-z0-9]{2,4}$/.test(last) ? '' : '.png');
  }
  if (query) {
    const params = query.split('&').filter((p) => p !== '').map((p) => {
      const eq = p.indexOf('=');
      return eq < 0 ? safePiece(decode(p)) : `${safePiece(decode(p.slice(0, eq)))}=${safePiece(decode(p.slice(eq + 1)))}`;
    });
    if (params.length) pieces.push(params.join('_'));
  }
  return `${pieces.join('/')}.json`;
}
