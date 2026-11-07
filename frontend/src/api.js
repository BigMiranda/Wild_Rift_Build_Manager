import { PUBLIC } from './publicMode.js';
import { handleLocal, inlineLocalBuilds } from './localStore.js';

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
