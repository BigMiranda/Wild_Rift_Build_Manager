/**
 * The visitor's folders and builds in the public version (Rodada 11): kept in this browser (localStorage), never sent
 * to the server unless the visitor allows the sharing (community.js). They answer the same calls the local app's
 * backend answers (`/api/folders`, `/api/builds`...), so the screens do not change. Local ids are negative; the public
 * folders and builds of the server (positive ids) are read-only: saving one makes a copy here.
 */

const KEY = 'ornn-public-store';

const now = () => new Date().toISOString();

function load() {
  try {
    const s = JSON.parse(localStorage.getItem(KEY) ?? 'null');
    if (s && Array.isArray(s.folders) && Array.isArray(s.builds)) return s;
  } catch {
    /* unreadable: start again */
  }
  return { next: 2, folders: [{ id: -1, name: 'Minhas builds', createdAt: now(), local: true }], builds: [] };
}

function store(s) {
  try {
    localStorage.setItem(KEY, JSON.stringify(s));
  } catch {
    throw new Error('O navegador não deixou guardar a build (armazenamento cheio ou bloqueado).');
  }
}

const isLocal = (id) => Number(id) < 0;

class HttpError extends Error {}
const fail = (msg) => { throw new HttpError(msg); };

/** The build as kept: the API's fields, without the summary's extras. */
function clean(b) {
  const { local, readOnly, ...rest } = b;
  return rest;
}

function checkBuild(b, s) {
  if (!b.name || !String(b.name).trim()) fail('Nome obrigatório.');
  if (!(Number(b.goldPerMin) > 0) || Number(b.xpPerMin) < 0) fail('Ouro/min deve ser > 0 e XP/min >= 0.');
  for (const st of b.steps ?? []) {
    if (st.kind !== 'moment' && st.itemId == null) fail('Compra sem item.');
  }
  // a build always goes to a folder of the visitor's
  const folderId = s.folders.some((f) => f.id === Number(b.folderId)) ? Number(b.folderId) : s.folders[0]?.id;
  return { ...clean(b), name: String(b.name).trim(), folderId };
}

function ensureFolder(s) {
  if (s.folders.length === 0) s.folders.push({ id: -s.next++, name: 'Minhas builds', createdAt: now(), local: true });
}

/** All the visitor's builds (for the export and the sharing). */
export function localBuilds() {
  return load().builds.map(clean);
}

export function localBuild(id) {
  const b = load().builds.find((x) => x.id === Number(id));
  return b ? clean(b) : null;
}

/**
 * Answers a call when it is about the visitor's folders and builds; `network(method, url, body)` reaches the server for
 * the rest (and the public ones). Returns `{ value }` when handled, null otherwise.
 */
export async function handleLocal(method, url, body, network) {
  const path = url.split('?')[0];
  let m;
  if (path === '/api/folders') {
    if (method === 'GET') {
      const remote = await network('GET', url).catch(() => []);
      return { value: [...remote.map((f) => ({ ...f, readOnly: true })), ...load().folders] };
    }
    if (method === 'POST') {
      const s = load();
      const name = String(body?.name ?? '').trim();
      if (!name) fail('Nome obrigatório.');
      if (s.folders.some((f) => f.name.toLowerCase() === name.toLowerCase())) fail('Já existe uma pasta com esse nome.');
      const id = -s.next++;
      s.folders.push({ id, name, createdAt: now(), local: true });
      store(s);
      return { value: { id } };
    }
  }
  if ((m = path.match(/^\/api\/folders\/(-?\d+)$/))) {
    const id = Number(m[1]);
    if (!isLocal(id)) fail('As pastas públicas não mudam.');
    const s = load();
    const f = s.folders.find((x) => x.id === id);
    if (!f) fail('Pasta não encontrada.');
    if (method === 'PUT') {
      const name = String(body?.name ?? '').trim();
      if (!name) fail('Nome obrigatório.');
      f.name = name;
      store(s);
      return { value: null };
    }
    if (method === 'DELETE') {
      if (s.builds.some((b) => b.folderId === id)) fail('A pasta não está vazia: mova ou apague as builds antes.');
      s.folders = s.folders.filter((x) => x.id !== id);
      ensureFolder(s);
      store(s);
      return { value: null };
    }
  }
  if (path === '/api/builds') {
    if (method === 'GET') {
      const remote = await network('GET', url).catch(() => []);
      return { value: [...remote.map((b) => ({ ...b, readOnly: true })), ...load().builds.map((b) => ({ ...b, local: true }))] };
    }
    if (method === 'POST') {
      const s = load();
      ensureFolder(s);
      const b = { ...checkBuild(body ?? {}, s), id: -s.next++, createdAt: now(), updatedAt: now() };
      s.builds.push(b);
      store(s);
      return { value: clean(b) };
    }
  }
  if ((m = path.match(/^\/api\/builds\/(-?\d+)\/timeline$/)) && isLocal(m[1]) && method === 'GET') {
    const b = localBuild(m[1]);
    if (!b) fail('Build não encontrada.');
    return { value: await network('POST', '/api/calculate', b) };
  }
  if ((m = path.match(/^\/api\/builds\/(-?\d+)\/folder$/)) && method === 'PUT') {
    const s = load();
    const b = s.builds.find((x) => x.id === Number(m[1]));
    if (!b) fail('As builds públicas ficam nas pastas delas: salve uma cópia para mudar.');
    if (!s.folders.some((f) => f.id === Number(body?.folderId))) fail('Pasta inválida.');
    b.folderId = Number(body.folderId);
    store(s);
    return { value: null };
  }
  if ((m = path.match(/^\/api\/builds\/(-?\d+)$/))) {
    const id = Number(m[1]);
    if (method === 'GET' && isLocal(id)) {
      const b = localBuild(id);
      if (!b) fail('Build não encontrada.');
      return { value: b };
    }
    if (method === 'PUT') {
      const s = load();
      ensureFolder(s);
      const i = s.builds.findIndex((x) => x.id === id);
      if (i < 0) {
        // a public build edited: saved as the visitor's copy
        const b = { ...checkBuild(body ?? {}, s), id: -s.next++, createdAt: now(), updatedAt: now() };
        s.builds.push(b);
        store(s);
        return { value: clean(b) };
      }
      const b = { ...checkBuild(body ?? {}, s), id, createdAt: s.builds[i].createdAt, updatedAt: now() };
      s.builds[i] = b;
      store(s);
      return { value: clean(b) };
    }
    if (method === 'DELETE') {
      if (!isLocal(id)) fail('As builds públicas não podem ser apagadas.');
      const s = load();
      s.builds = s.builds.filter((x) => x.id !== id);
      store(s);
      return { value: null };
    }
  }
  return null;
}

/**
 * The server does not know the visitor's builds: a body naming one by id (`buildId: -3`) carries the build itself
 * instead (`build: {...}`), at the top and in a `reference`.
 */
export function inlineLocalBuilds(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return body;
  const swap = (o) => {
    if (o && typeof o === 'object' && o.buildId != null && isLocal(o.buildId)) {
      const b = localBuild(o.buildId);
      if (b) {
        const { buildId, ...rest } = o;
        return { ...rest, build: b };
      }
    }
    return o;
  };
  const out = swap(body);
  if (out.reference) return { ...out, reference: swap(out.reference) };
  return out;
}
