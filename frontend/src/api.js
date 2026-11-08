import { PUBLIC, STATIC } from './publicMode.js';
import { handleLocal, inlineLocalBuilds } from './localStore.js';
import { staticFile } from './staticPath.js';

/** The static site's answer to anything that would calculate (Rodada 11.b). */
export const NOT_STATIC = 'Não existe no site estático: os números aparecem quando a build for calculada no app completo.';

/** An icon's address: the backend's, or the static site's file (relative, so the site works under any folder). */
export const asset = (url) => (STATIC ? staticFile(url, true) : url);

/** The static site: a GET reads the generated file; anything else does not exist. */
async function staticRequest(method, url) {
  if (method !== 'GET') throw new Error(NOT_STATIC);
  const res = await fetch(staticFile(url));
  if (!res.ok) throw new Error(res.status === 404 ? 'Ainda não calculado para o site.' : `${res.status} ${res.statusText}`);
  return res.json();
}

/** The public version's answers past its limits (429 / 503), in the visitor's words. */
const LATER = 'Muitos pedidos ou servidor ocupado: tente mais tarde.';

async function network(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let msg = `${res.status} ${res.statusText}`;
    try {
      const err = await res.json();
      if (err.message) msg = err.message;
    } catch {
      /* not json */
    }
    if (PUBLIC && (res.status === 429 || res.status === 503) && !/tente mais tarde/i.test(msg)) msg = LATER;
    throw new Error(msg);
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

async function request(method, url, body) {
  if (STATIC) {
    // the visitor's builds live in the browser, as in the public version; the rest is a file
    const local = await handleLocal(method, url, body, (m, u) => staticRequest(m, u));
    if (local) return local.value;
    return staticRequest(method, url);
  }
  if (PUBLIC) {
    // the visitor's folders and builds live in the browser (Rodada 11)
    const local = await handleLocal(method, url, body, network);
    if (local) return local.value;
    return network(method, url, method === 'POST' ? inlineLocalBuilds(body) : body);
  }
  return network(method, url, body);
}

export const api = {
  get: (url) => request('GET', url),
  post: (url, body) => request('POST', url, body ?? {}),
  put: (url, body) => request('PUT', url, body),
  del: (url) => request('DELETE', url),
};
