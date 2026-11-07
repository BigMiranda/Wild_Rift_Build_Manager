/**
 * A build carried in a link (Rodada 11): `#/build/loja?b=<code>`. The code is the build's JSON, compressed with the
 * browser's own deflate when it has one ("z" + base64url) or as it is ("j" + base64url). Opening the link opens the
 * build as an unsaved draft; nothing goes to the server.
 */

const toB64 = (bytes) => {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};
const fromB64 = (text) => {
  const s = atob(text.replace(/-/g, '+').replace(/_/g, '/'));
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
};

async function pipe(bytes, stream) {
  const res = new Response(new Blob([bytes]).stream().pipeThrough(stream));
  return new Uint8Array(await res.arrayBuffer());
}

/** Only what makes the build: no ids, folder or dates. */
export function shareable(b) {
  const { id, folderId, createdAt, updatedAt, local, readOnly, ...rest } = b;
  return rest;
}

export async function encodeBuild(b) {
  const bytes = new TextEncoder().encode(JSON.stringify(shareable(b)));
  if (typeof CompressionStream === 'function') {
    return `z${toB64(await pipe(bytes, new CompressionStream('deflate-raw')))}`;
  }
  return `j${toB64(bytes)}`;
}

export async function decodeBuild(code) {
  if (!code || code.length < 2) throw new Error('Link de build vazio.');
  let bytes = fromB64(code.slice(1));
  if (code[0] === 'z') {
    if (typeof DecompressionStream !== 'function') throw new Error('Este navegador não abre links compactados.');
    bytes = await pipe(bytes, new DecompressionStream('deflate-raw'));
  } else if (code[0] !== 'j') {
    throw new Error('Link de build desconhecido.');
  }
  const b = JSON.parse(new TextDecoder().decode(bytes));
  if (!b || !Array.isArray(b.steps) || !b.unitCode) throw new Error('O link não traz uma build.');
  return b;
}

/** The whole link of a build, on this page's address. */
export async function buildLink(b) {
  const base = `${window.location.origin}${window.location.pathname}`;
  return `${base}#/build/loja?b=${await encodeBuild(b)}`;
}
