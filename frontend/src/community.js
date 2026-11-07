/**
 * "Compartilhar minhas builds para melhorar as guias" (Rodada 11, part 4): off by default. Only when the visitor turns
 * it on, each build they save in this browser goes to the server (the build only: champion, purchases, runes, spells,
 * pace) with a random anonymous id kept here. Turning it off stops the sending; "apagar o que enviei" erases on the
 * server what this id sent.
 */
import { PUBLIC } from './publicMode.js';

const CONSENT = 'ornn-community-share';
const ANON = 'ornn-community-anon';

export function sharing() {
  try {
    return PUBLIC && localStorage.getItem(CONSENT) === '1';
  } catch {
    return false;
  }
}

/** The anonymous id: random, made the first time the visitor allows the sharing, never anything about them. */
export function anonId(create = false) {
  try {
    let id = localStorage.getItem(ANON);
    if (!id && create) {
      id = crypto.randomUUID();
      localStorage.setItem(ANON, id);
    }
    return id;
  } catch {
    return null;
  }
}

export function setSharing(on) {
  try {
    if (on) {
      anonId(true);
      localStorage.setItem(CONSENT, '1');
    } else {
      localStorage.removeItem(CONSENT);
    }
  } catch {
    /* storage unavailable: nothing is shared */
  }
}

/** Only the build: no name, note, folder or ids. */
function onlyTheBuild(b) {
  return {
    unitCode: b.unitCode, goldPerMin: Number(b.goldPerMin), xpPerMin: Number(b.xpPerMin), matchEnd: b.matchEnd ?? null,
    assumeHalfItems: !!b.assumeHalfItems, assumeSmallItems: !!b.assumeSmallItems,
    steps: b.steps ?? [], runePage: b.runePage ?? null, spells: b.spells ?? [],
  };
}

/** Sends a saved build when (and only when) the visitor allowed it. Never throws: sharing never blocks a save. */
export async function shareIfAllowed(b) {
  if (!sharing() || !b || b.unitCode === 'RAGDOLL') return null;
  try {
    const res = await fetch('/api/community/builds', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ anonId: anonId(true), consent: true, build: onlyTheBuild(b) }),
    });
    return res.ok ? res.json() : null;
  } catch {
    return null;
  }
}

/** "Apagar o que enviei": erases on the server everything this browser's id sent. */
export async function forgetMine() {
  const id = anonId(false);
  if (!id) return 0;
  const res = await fetch('/api/community/mine', { method: 'DELETE', headers: { 'X-Anon-Id': id } });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return (await res.json()).removed ?? 0;
}
