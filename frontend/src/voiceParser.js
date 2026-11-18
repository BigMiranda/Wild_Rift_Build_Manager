/**
 * Rodada 20, Parte 2: the reader of dictated text (Simulações › Draft e partida). Pure JS, no DOM: it gets the text the
 * browser's speech recognition wrote and the catalogs (champions, spells, runes, items and the nicknames of
 * `apelidos_7_3f.yml`), and returns what it understood as intents, each with its fields, what was heard for each one
 * and how sure the match was. Nothing here writes the game: the screen shows every intent with ✓ / ✗ first.
 *
 * Matching: each span of up to 5 words is compared with every catalog name and nickname without accents or case, by
 * edit distance and by a simple phonetic key (Portuguese spelling of English names: "orne" ~ Ornn, "dárius" ~ Darius,
 * "lisin" ~ Lee Sin); the longest good span wins. No rule names a champion or an item: those come from the catalogs.
 *
 * The grammar (docs/rodada-20.md lists the phrases):
 *   loading screen   "time azul: Ornn barão, flash e teleporte, Aperto dos Mortos-Vivos, Fonte da Vida; Lee Sin selva..."
 *                    "inimigo Darius, flash e incendiar, Conquistador"
 *   purchases        "Darius comprou Cutelo Negro", "Ornn botas de mercúrio", "Rumble comprou Varinha Explosiva"
 *   score            "Ornn dois, um, cinco" (K/D/A), "Darius 4500 de ouro", "Ornn nível 6"
 *   events           "Ornn morreu", "Darius matou Ornn", "dragão infernal azul", "barão vermelho", "arauto aliado"
 */

/** Without accents, lower case; "4.500" -> "4500"; separators ; . , kept as their own tokens. */
export function normalize(text) {
  return String(text ?? '')
    .normalize('NFD').replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/(\d)[.,](\d{3})\b/g, '$1$2')
    .replace(/\b(dr|sr|sra)\./g, '$1 ')
    .replace(/[:;]/g, ' ; ')
    .replace(/\.(?!\d)/g, ' ; ')
    .replace(/,/g, ' , ')
    .replace(/[^a-z0-9; ,]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export const tokenize = (text) => normalize(text).split(' ').filter(Boolean);

/** A name as the reader compares it: no accents, lower case, letters and digits only, single spaces. */
export const key = (s) => String(s ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
  .replace(/['’]/g, '').replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim();

/**
 * A simple phonetic key, Portuguese-reading of mostly English names: digraphs to one sound, c/k/q, s/z/ç, y/i, w/v,
 * silent h, doubled letters, and a trailing vowel the recognizer adds ("orne").
 */
export function phonetic(s) {
  let x = key(s).replace(/ /g, '');
  x = x.replace(/ph/g, 'f').replace(/th/g, 't').replace(/sh|ch/g, 'x').replace(/qu/g, 'k').replace(/gu(?=[ei])/g, 'g')
    .replace(/c(?=[eiy])/g, 's').replace(/[ckq]/g, 'k').replace(/z/g, 's').replace(/w/g, 'v').replace(/y/g, 'i')
    .replace(/h/g, '').replace(/ee/g, 'i').replace(/oo/g, 'u').replace(/(.)\1+/g, '$1');
  if (x.length >= 4) x = x.replace(/[aeiou]$/, '');
  return x.replace(/(.)\1+/g, '$1');
}

/** Levenshtein distance (small strings). */
export function distance(a, b) {
  if (a === b) return 0;
  if (!a.length) return b.length;
  if (!b.length) return a.length;
  let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) {
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    prev = cur;
  }
  return prev[b.length];
}

/** How alike a spoken span and a name are (0..1): the better of the spelling and the phonetic key. */
export function similarity(spoken, name) {
  const a = key(spoken).replace(/ /g, '');
  const b = key(name).replace(/ /g, '');
  if (!a || !b) return 0;
  if (a === b) return 1;
  const s1 = 1 - distance(a, b) / Math.max(a.length, b.length);
  const pa = phonetic(a);
  const pb = phonetic(b);
  const s2 = pa && pb ? (pa === pb ? 0.97 : 1 - distance(pa, pb) / Math.max(pa.length, pb.length)) : 0;
  return Math.max(s1, s2);
}

/** similarity() with the spoken span's phonetic key and the entry's computed once. */
function fastSimilarity(compact, phon, e) {
  const s1 = 1 - distance(compact, e.compact) / Math.max(compact.length, e.compact.length);
  const s2 = phon && e.phon ? (phon === e.phon ? 0.97 : 1 - distance(phon, e.phon) / Math.max(phon.length, e.phon.length)) : 0;
  return Math.max(s1, s2);
}

/** The least similarity accepted for a name of this many letters (short names must be near exact). */
export function threshold(len) {
  if (len <= 3) return 0.97;
  if (len <= 5) return 0.8;
  return 0.74;
}

const NUMBERS = {
  zero: 0, um: 1, uma: 1, dois: 2, duas: 2, tres: 3, quatro: 4, cinco: 5, seis: 6, sete: 7, oito: 8, nove: 9, dez: 10,
  onze: 11, doze: 12, treze: 13, quatorze: 14, catorze: 14, quinze: 15, dezesseis: 16, dezessete: 17, dezoito: 18,
  dezenove: 19, vinte: 20, trinta: 30, quarenta: 40, cinquenta: 50,
};
// grammar words (Portuguese verbs and markers of the phrases); names never live here
const VERBS = {
  compra: ['comprou', 'compra', 'comprar', 'comprando', 'pegou', 'fechou', 'fez', 'buy', 'bought'],
  morte: ['morreu', 'morre', 'morte', 'caiu', 'died'],
  abate: ['matou', 'abateu', 'abate', 'killed'],
  ouro: ['ouro', 'gold', 'ouros'],
  nivel: ['nivel', 'level', 'nv', 'lvl'],
  componente: ['componente'],
};
// "3 abates 1 morte e 2 assistências": the word after a number says which of K/D/A it is
const KDA = { abate: 'k', abates: 'k', kill: 'k', kills: 'k', morte: 'd', mortes: 'd', death: 'd', deaths: 'd', assistencia: 'a', assistencias: 'a', assist: 'a', assists: 'a' };
const STOP = new Set(['e', 'de', 'do', 'da', 'dos', 'das', 'com', 'o', 'a', 'os', 'as', 'no', 'na', 'time', 'tem', 'que', 'um', 'uma', 'agora', 'esta', 'ta', 'vai', 'foi', 'pra', 'para', 'ele', 'ela', 'mais', 'item', 'itens', 'runa', 'runas', 'feitico', 'feiticos', 'rota', 'mil']);

/**
 * The reader's catalogs, built once from what the screen has: champions (names), spells (names), keystones, runes,
 * items ({id, name, section, tabs}) and nicknames ([{alias, target, kind}]).
 */
export function buildLexicon({ champions = [], spells = [], keystones = [], runes = [], items = [], aliases = [] }) {
  const entries = [];
  const add = (kind, name, value, spoken = name, alias = false) => {
    const k = key(spoken);
    if (!k) return;
    entries.push({ kind, value, name, key: k, compact: k.replace(/ /g, ''), phon: phonetic(k), words: k.split(' ').length, alias });
  };
  champions.forEach((c) => add('champion', c, c));
  spells.forEach((s) => add('spell', s, s));
  keystones.forEach((r) => add('keystone', r, r));
  runes.forEach((r) => add('rune', r, r));
  items.forEach((i) => add('item', i.name, i.id));
  const byName = {
    champion: new Map(champions.map((c) => [key(c), c])),
    spell: new Map(spells.map((s) => [key(s), s])),
    keystone: new Map(keystones.map((r) => [key(r), r])),
    rune: new Map(runes.map((r) => [key(r), r])),
    item: new Map(items.map((i) => [key(i.name), i.id])),
  };
  const grammar = { lane: new Map(), side: new Map(), objective: new Map(), dragon: new Map() };
  const KIND = { campeoes: 'champion', feiticos: 'spell', itens: 'item', rotas: 'lane', lados: 'side', objetivos: 'objective', dragoes: 'dragon' };
  for (const a of aliases) {
    if (!a?.alias || !a?.target) continue;
    if (a.kind === 'runas') {
      const tk = key(a.target);
      if (byName.keystone.has(tk)) add('keystone', a.target, byName.keystone.get(tk), a.alias, true);
      else if (byName.rune.has(tk)) add('rune', a.target, byName.rune.get(tk), a.alias, true);
      continue;
    }
    const kind = KIND[a.kind];
    if (!kind) continue;
    if (grammar[kind]) {
      grammar[kind].set(key(a.alias), a.target);
      continue;
    }
    const v = byName[kind].get(key(a.target));
    if (v != null) add(kind, a.target, v, a.alias, true);
  }
  // a name said by its first word alone ("o cutelo", "a varinha"): only when one name of the catalogs starts with it
  const firstWord = new Map();
  for (const e of entries) {
    if (e.alias || e.words < 2) continue;
    const w = e.key.split(' ')[0];
    if (w.length < 5 || STOP.has(w)) continue;
    firstWord.set(w, firstWord.has(w) && firstWord.get(w) !== e ? null : e);
  }
  return { entries, grammar, firstWord, items: new Map(items.map((i) => [i.id, i])) };
}

const verbOf = (w) => Object.entries(VERBS).find(([, ws]) => ws.includes(w))?.[0] ?? null;

/** The number a word (or digits) says, or null. */
export function numberOf(w) {
  if (/^\d+$/.test(w)) return Number(w);
  return NUMBERS[w] ?? null;
}

/**
 * The tokens read as entities: separators, numbers (with "mil" and "e"), grammar words (verbs, lanes, sides,
 * objectives, dragon kinds) and catalog names (longest good span first). Unknown words are kept as `word`.
 */
export function lex(text, lexicon) {
  const toks = tokenize(text);
  const out = [];
  let i = 0;
  while (i < toks.length) {
    const w = toks[i];
    if (w === ';' || w === ',') {
      out.push({ kind: w === ';' ? 'end' : 'comma', heard: w });
      i++;
      continue;
    }
    // numbers: "4500", "quatro mil e quinhentos", "vinte e dois"
    if (numberOf(w) != null || (w === 'mil' && out.length && out[out.length - 1].kind === 'number')) {
      let n = numberOf(w) ?? 0;
      const heard = [w];
      let j = i + 1;
      if (toks[j] === 'mil') { n = (n || 1) * 1000; heard.push('mil'); j++; }
      while (toks[j] === 'e' && numberOf(toks[j + 1]) != null && numberOf(toks[j + 1]) < n) {
        let m = numberOf(toks[j + 1]);
        heard.push('e', toks[j + 1]);
        j += 2;
        if (toks[j] === 'mil') { m *= 1000; heard.push('mil'); j++; }
        n += m;
      }
      // "quinhentos" style hundreds
      out.push({ kind: 'number', value: n, heard: heard.join(' ') });
      i = j;
      continue;
    }
    const hundreds = { cem: 100, cento: 100, duzentos: 200, trezentos: 300, quatrocentos: 400, quinhentos: 500, seiscentos: 600, setecentos: 700, oitocentos: 800, novecentos: 900 }[w];
    if (hundreds != null) {
      const last = out[out.length - 1];
      if (last?.kind === 'number' && last.value >= 1000 && toks[i - 1] === 'e') last.value += hundreds;
      else out.push({ kind: 'number', value: hundreds, heard: w });
      i++;
      continue;
    }
    const prevNum = out.length && out[out.length - 1].kind === 'number';
    const kda = prevNum ? KDA[w] : null;
    if (kda) {
      out[out.length - 1].kda = kda;
      i++;
      continue;
    }
    const verb = verbOf(w);
    if (verb) {
      out.push({ kind: 'verb', value: verb, heard: w });
      i++;
      continue;
    }
    // catalog names: the longest span with a good match; grammar words compete at one word
    let best = null;
    for (let len = Math.min(5, toks.length - i); len >= 1; len--) {
      const span = toks.slice(i, i + len);
      if (span.some((x) => x === ';' || x === ',')) continue;
      if (STOP.has(span[0]) || STOP.has(span[span.length - 1]) && len > 1) continue;
      const spoken = span.join(' ');
      const compact = spoken.replace(/ /g, '');
      const phon = phonetic(compact);
      for (const e of lexicon.entries) {
        if (Math.abs(e.compact.length - compact.length) > Math.max(3, e.compact.length * 0.4)) continue;
        const s = e.compact === compact ? 1 : fastSimilarity(compact, phon, e);
        if (s < threshold(e.compact.length)) continue;
        // prefer the better match, then the longer span, then names over nicknames
        const rank = s + len * 0.02 + (e.alias ? 0 : 0.005);
        if (!best || rank > best.rank) best = { rank, score: s, len, entry: e, spoken };
      }
      if (best && best.len === len && best.score >= 0.97) break;
    }
    const grammarHit = (() => {
      for (const kind of ['side', 'objective', 'dragon', 'lane']) {
        if (lexicon.grammar[kind].has(w)) return { kind, value: lexicon.grammar[kind].get(w) };
      }
      return null;
    })();
    if (grammarHit && (!best || best.len === 1 && best.score < 1)) {
      // a lane word and an objective word ("barão", "dragão") stay both until the phrase decides
      const lane = lexicon.grammar.lane.get(w);
      const objective = lexicon.grammar.objective.get(w);
      out.push({ kind: grammarHit.kind, value: grammarHit.value, heard: w, lane, objective });
      i++;
      continue;
    }
    if (!best && lexicon.firstWord?.get(w)) {
      const e = lexicon.firstWord.get(w);
      best = { rank: 0.85, score: 0.85, len: 1, entry: e, spoken: w };
    }
    if (best) {
      out.push({ kind: best.entry.kind, value: best.entry.value, name: best.entry.name, heard: best.spoken, score: best.score });
      i += best.len;
      continue;
    }
    if (!STOP.has(w)) out.push({ kind: 'word', heard: w });
    i++;
  }
  return out;
}

/**
 * The intents of a phrase. Each: {type, side?, champion?, ... , parts: [{field, value, heard, score}]} where type is
 * jogador | compra | placar | ouro | nivel | morte | abate | objetivo. `unknown` lists the words not understood.
 */
export function parse(text, lexicon) {
  const ents = lex(text, lexicon);
  const intents = [];
  let side = null;            // the side said last ("time azul", "inimigo")
  let sideEnt = null;
  let cur = null;             // the clause of the current champion
  const unknown = [];
  const part = (field, e, value) => ({ field, value: value ?? e.name ?? e.value, heard: e.heard, score: e.score ?? 1 });

  const close = () => {
    if (!cur) return;
    const c = cur;
    cur = null;
    const champ = part('campeao', c.champ);
    const base = { champion: c.champ.value, side: c.side };
    if (c.verb === 'morte') {
      intents.push({ ...base, type: 'morte', parts: [champ, part('evento', c.verbEnt, 'morreu')] });
      if (c.victim) intents.push({ champion: c.victim.value, side: null, type: 'abate', parts: [part('campeao', c.victim), part('evento', c.verbEnt, 'matou')] });
      return;
    }
    if (c.verb === 'abate') {
      intents.push({ ...base, type: 'abate', parts: [champ, part('evento', c.verbEnt, 'matou')] });
      if (c.victim) intents.push({ champion: c.victim.value, side: null, type: 'morte', parts: [part('campeao', c.victim), part('evento', c.verbEnt, 'morreu')] });
      return;
    }
    if (c.nums.length && c.gold) {
      intents.push({ ...base, type: 'ouro', gold: c.nums[0].value, parts: [champ, part('ouro', c.nums[0], c.nums[0].value)] });
    } else if (c.nums.length && c.level) {
      intents.push({ ...base, type: 'nivel', level: c.nums[0].value, parts: [champ, part('nivel', c.nums[0], c.nums[0].value)] });
    } else if (c.nums.some((n) => n.kda)) {
      const by = Object.fromEntries(c.nums.filter((n) => n.kda).map((n) => [n.kda, n]));
      const o = { ...base, type: 'placar', parts: [champ] };
      if (by.k) { o.kills = by.k.value; o.parts.push(part('abates', by.k, by.k.value)); }
      if (by.d) { o.deaths = by.d.value; o.parts.push(part('mortes', by.d, by.d.value)); }
      if (by.a) { o.assists = by.a.value; o.parts.push(part('assistencias', by.a, by.a.value)); }
      intents.push(o);
    } else if (c.nums.length >= 3) {
      const [k, d, a] = c.nums;
      intents.push({ ...base, type: 'placar', kills: k.value, deaths: d.value, assists: a.value,
        parts: [champ, part('abates', k, k.value), part('mortes', d, d.value), part('assistencias', a, a.value)] });
    } else if (c.nums.length === 1 && c.nums[0].value >= 300) {
      intents.push({ ...base, type: 'ouro', gold: c.nums[0].value, parts: [champ, part('ouro', c.nums[0], c.nums[0].value)] });
    }
    const hasSetup = c.lane || c.spells.length || c.keystone || c.runes.length;
    if (c.items.length && (c.verb === 'compra' || !hasSetup)) {
      for (const it of c.items) {
        intents.push({ ...base, type: 'compra', item: it.value, parts: [champ, part(c.component ? 'componente' : 'item', it)] });
      }
    }
    if (hasSetup || (!c.items.length && !c.nums.length && !c.verb)) {
      const parts = [champ];
      if (c.sideEnt) parts.push(part('lado', c.sideEnt, c.side));
      if (c.lane) parts.push(part('rota', c.lane, c.lane.laneValue));
      c.spells.forEach((s) => parts.push(part('feitico', s)));
      if (c.keystone) parts.push(part('fundamental', c.keystone));
      c.runes.forEach((r) => parts.push(part('runa', r)));
      intents.push({ ...base, type: 'jogador', lane: c.lane?.laneValue ?? null, spells: c.spells.map((s) => s.value),
        keystone: c.keystone?.value ?? null, runes: c.runes.map((r) => r.value), parts });
    }
  };

  for (let k = 0; k < ents.length; k++) {
    const e = ents[k];
    const next = ents[k + 1];
    const next2 = ents[k + 2];
    if (e.kind === 'end') {
      close();
      continue;
    }
    if (e.kind === 'comma') continue;
    if (e.kind === 'side') {
      if (cur && !cur.sideEnt && !(next && next.kind === 'champion')) {
        cur.side = e.value;
        cur.sideEnt = e;
      } else {
        side = e.value;
        sideEnt = e;
        if (cur && next?.kind === 'champion') close();
      }
      continue;
    }
    if (e.kind === 'objective' || (e.kind === 'lane' && e.objective)) {
      // an objective when no champion is talking without a lane yet, or when a side / a dragon kind follows
      const sideAfter = [next, next2].find((x) => x?.kind === 'side');
      const dragonAfter = [next, next2].find((x) => x?.kind === 'dragon');
      const asLane = cur && !cur.lane && e.lane && !sideAfter && !dragonAfter && !cur.verb;
      if (!asLane && e.objective) {
        close();
        const parts = [part('objetivo', e, e.objective)];
        const o = { type: 'objetivo', kind: e.objective, side: null, detail: null, parts };
        let j = k + 1;
        while (j < ents.length && j <= k + 3 && ['dragon', 'side', 'comma', 'word'].includes(ents[j].kind)) {
          const x = ents[j];
          if (x.kind === 'dragon') { o.detail = x.value; parts.push(part('dragao', x, x.value)); }
          if (x.kind === 'side') { o.side = x.value; parts.push(part('lado', x, x.value)); }
          j++;
        }
        if (!o.side) o.side = side;
        intents.push(o);
        k = j - 1;
        continue;
      }
      if (cur) {
        cur.lane = e;
        cur.lane.laneValue = e.lane;
      }
      continue;
    }
    if (e.kind === 'lane') {
      if (cur) {
        cur.lane = e;
        cur.lane.laneValue = e.value;
      }
      continue;
    }
    if (e.kind === 'champion') {
      // "Darius matou Ornn" (the victim) and "Ornn morreu pro Darius" (the killer) stay in the clause
      if (cur && (cur.verb === 'abate' || cur.verb === 'morte') && !cur.victim) {
        cur.victim = e;
        continue;
      }
      close();
      cur = { champ: e, side, sideEnt: side ? sideEnt : null, lane: null, spells: [], keystone: null, runes: [], items: [], nums: [], verb: null, verbEnt: null, gold: false, level: false, component: false };
      continue;
    }
    if (!cur) {
      if (e.kind !== 'word' && e.kind !== 'dragon') unknown.push(e.heard);
      else if (e.kind === 'word') unknown.push(e.heard);
      continue;
    }
    switch (e.kind) {
      case 'spell': if (!cur.spells.includes(e)) cur.spells.push(e); break;
      case 'keystone': cur.keystone = e; break;
      case 'rune': cur.runes.push(e); break;
      case 'item': cur.items.push(e); break;
      case 'number': cur.nums.push(e); break;
      case 'verb':
        if (e.value === 'ouro') cur.gold = true;
        else if (e.value === 'nivel') cur.level = true;
        else if (e.value === 'componente') cur.component = true;
        else { cur.verb = e.value; cur.verbEnt = e; }
        break;
      case 'word': unknown.push(e.heard); break;
      default: break;
    }
  }
  close();
  return { intents, unknown };
}
