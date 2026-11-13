"""Gera os JSONs do site estático (Rodada 11.b; docs/rodada-11b.md). Só a biblioteca padrão do Python.

O site só mostra o que foi calculado aqui. O script lê o **app local completo** (perfil normal, sem os limites do
público) rodando sobre **uma cópia** do banco exportado, e só usa endpoints de leitura e de cálculo:

    cd backend && ./mvnw -Pexport                                  # o banco público (data/publico/...)
    copie o banco exportado para outra pasta (o app normal grava ao subir: migrações, sementes)
    SERVER_PORT=8097 PLANNER_DB_PATH=<cópia> ./mvnw spring-boot:run
    python tools/site_estatico.py --api http://localhost:8097 --saida site-publico

O `tools/publicar_site.py` faz tudo isso num comando. Pelo perfil `publico` também funciona, mas sem a efetividade
ofensiva, e com os limites dele: suba-os só para a geração (PLANNER_PUBLIC_COMPUTE_PER_MINUTE=100000,
PLANNER_PUBLIC_REQUESTS_PER_MINUTE=100000, PLANNER_PUBLIC_COMPUTE_SECONDS=600, PLANNER_PUBLIC_EFFECT_JOBS=20).

O que sai, em <saida>/api/ (a regra do nome de arquivo é `arquivo_estatico`, a mesma do frontend):
- `manifest.json`: patch, data, versão do formato, campeões e quantas builds de cada um;
- catálogo: meta, itens, preços, runas e feitiços, campeões, fichas, perfis, arquétipos, relatórios da efetividade;
- por campeão (`site/campeao/<código>.json`): as builds (padrão robusta, melhor de cada nicho, descobertas, públicas),
  as matchups da campanha, as guias públicas e a sugerida pela Descoberta;
- por build (`site/build/<chave>.json`): a build, a linha do tempo em 3 ritmos, e nos minutos-chave (10, 15 e o último
  item) a Efetividade Básica, a ofensiva e as habilidades;
- as lutas dos cenários públicos, já rodadas (replay), e os ícones.
"""

import argparse
import json
import os
import re
import shutil
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

FORMAT = 1
SAFE = re.compile(r'[A-Za-z0-9._=-]')
#: The lab's ragdoll (ChampionReport.jsx): what the abilities are read against.
LAB_TARGET = {'name': 'Boneco de pano', 'health': 10000, 'armor': 100, 'magicResist': 100}
#: The standard pace of a discovered build, and the extra ones ("atrás", "à frente").
PACES = {'padrao': 700, 'atras': 600, 'frente': 800}


# ---------------------------------------------------------------------------------------------- the file name rule

def _piece(s):
    out = []
    for ch in s:
        if SAFE.fullmatch(ch):
            out.append(ch)
        else:
            out.extend('~%02X' % b for b in ch.encode('utf-8'))
    return ''.join(out)


def arquivo_estatico(url, binario=False):
    """O arquivo (relativo, sem barra no começo) de um endereço da API. A mesma regra de frontend/src/staticPath.js.

    /api/x?y=1 -> api/x/y=1.json; qualquer caractere fora de [A-Za-z0-9._=-] vira ~XX (bytes UTF-8); a query, na
    ordem, vira o último pedaço (parâmetros unidos por "_"); um ícone mantém a extensão (ou ganha .png) e perde a query.
    """
    path, _, query = url.partition('?')
    pieces = [_piece(urllib.parse.unquote_plus(p)) for p in path.split('/') if p != '']
    if binario:
        last = pieces[-1] if pieces else ''
        return '/'.join(pieces) + ('' if re.search(r'\.[A-Za-z0-9]{2,4}$', last) else '.png')
    if query:
        params = []
        for p in query.split('&'):
            if p == '':
                continue
            k, eq, v = p.partition('=')
            params.append(_piece(urllib.parse.unquote_plus(k)) + ('=' + _piece(urllib.parse.unquote_plus(v)) if eq else ''))
        if params:
            pieces.append('_'.join(params))
    return '/'.join(pieces) + '.json'


# ---------------------------------------------------------------------------------------------- reading the app

class Site:
    def __init__(self, api, out, pause=0.0):
        self.api = api.rstrip('/')
        self.out = out
        self.pause = pause
        self.files = {}
        self.calls = 0

    def request(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode('utf-8')
        for attempt in range(8):
            req = urllib.request.Request(self.api + path, data=data, method=method,
                                         headers={'Content-Type': 'application/json'} if data else {})
            try:
                self.calls += 1
                with urllib.request.urlopen(req, timeout=600) as res:
                    return res.read()
            except urllib.error.HTTPError as e:
                if e.code in (429, 503) and attempt < 7:
                    wait = int(e.headers.get('Retry-After') or 10)
                    print(f'  {e.code} em {path}: espera {wait} s (limite da versão pública)')
                    time.sleep(wait)
                    continue
                raise
        raise RuntimeError(f'{path}: sem resposta')

    def write(self, url, value, binario=False):
        """Grava uma resposta (minificada) no arquivo do endereço."""
        rel = arquivo_estatico(url, binario)
        raw = value if binario else json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        file = os.path.join(self.out, *rel.split('/'))
        os.makedirs(os.path.dirname(file), exist_ok=True)
        with open(file, 'wb') as f:
            f.write(raw)
        self.files[rel] = len(raw)
        return value

    def read(self, path, required=True):
        try:
            raw = self.request('GET', path)
        except urllib.error.HTTPError as e:
            if required:
                raise
            print(f'  {e.code} em {path}: ignorado')
            return None
        if self.pause:
            time.sleep(self.pause)
        return json.loads(raw) if raw else None

    def get(self, path, required=True):
        """Lê e grava com o mesmo endereço (o que as telas leem como está)."""
        v = self.read(path, required)
        if v is not None or required:
            self.write(path, v)
        return v

    def post(self, path, body):
        raw = self.request('POST', path, body)
        return json.loads(raw) if raw else None

    def binary(self, path):
        try:
            raw = self.request('GET', path)
        except urllib.error.HTTPError as e:
            if e.code != 404:   # a name of the runes' list is a rune's or a spell's icon: the other one is 404
                print(f'  {e.code} em {path}: ignorado')
            return
        self.write(path, raw, binario=True)


# ---------------------------------------------------------------------------------------------- the builds

def slug(s):
    """Um pedaço de chave legível: sem acentos ("Padrão robusta" -> "padrao-robusta")."""
    plain = unicodedata.normalize('NFKD', s or '').encode('ascii', 'ignore').decode('ascii')
    return re.sub(r'[^A-Za-z0-9]+', '-', plain).strip('-').lower() or 'x'


def kit_key(k):
    return (tuple(k.get('order') or k.get('items') or []), k.get('boots'))


def kit_runes(kit, standard=None, base=None):
    """A página de runas de um kit e de onde ela veio (Rodada 13): a buscada para ele ('busca'), a da padrão robusta
    ('padrao'), a da build pública ('publica') ou nenhuma."""
    if kit.get('runePage'):
        return kit['runePage'], kit.get('runesFrom') or 'busca'
    if (standard or {}).get('runePage'):
        return standard['runePage'], 'padrao'
    if (base or {}).get('runePage'):
        return base['runePage'], 'publica'
    return None, None


def kit_build(unit, kit, base, pace, standard=None):
    """Um kit da Descoberta como build: o campeão, as runas do kit (ou as da padrão, ou as da base pública), a ordem de
    compra."""
    steps = [{'itemId': i, 'includeConditional': True} for i in (kit.get('order') or kit.get('items') or [])]
    if kit.get('boots') and all(s['itemId'] != kit['boots'] for s in steps):
        steps.insert(min(1, len(steps)), {'itemId': kit['boots'], 'includeConditional': True})
    b = {'unitCode': unit, 'name': None, 'goldPerMin': pace, 'xpPerMin': pace, 'steps': steps,
         'runePage': kit_runes(kit, standard, base)[0], 'spells': (base or {}).get('spells'),
         'championSetup': (base or {}).get('championSetup'), 'folderId': 0}
    return b


def trim_timeline(t, series=True):
    """A linha do tempo sem o que a página não lê (o tamanho conta: o site tem um teto). Os ritmos extras ficam só com
    as compras; o padrão guarda a série (minuto, nível, ouro na mão e os totais), sem as contribuições de cada item,
    que vão só nos pontos dos minutos-chave."""
    if not t:
        return t
    keep_step = ('index', 'buildIndex', 'number', 'implied', 'itemId', 'itemName', 'category', 'itemCost', 'paidCost',
                 'cumulativeGold', 'minute', 'level', 'inventoryIds', 'violations')
    out = {k: t.get(k) for k in ('matchEnd', 'unitCode', 'unitName', 'startingGold', 'goldPerMin', 'xpPerMin', 'warnings', 'moments')}
    out['steps'] = [{k: s.get(k) for k in keep_step if k in s} for s in t.get('steps', [])]
    if series:
        out['series'] = [{k: p.get(k) for k in ('minute', 'level', 'goldInHand', 'stepIndex', 'total')} for p in t.get('series', [])]
    return out


def point_at(series, minute):
    """O ponto da série num minuto: o último que já aconteceu (MomentPanels.jsx pointAt)."""
    best = series[0] if series else None
    for p in series or []:
        if p.get('minute', 0) <= minute + 1e-9:
            best = p
    return best


def rounded(o, nd=2):
    """Números com 2 casas: o JSON fica bem menor e a tela mostra no máximo 1."""
    if isinstance(o, float):
        r = round(o, nd)
        return int(r) if r == int(r) and abs(r) < 1e15 else r
    if isinstance(o, dict):
        return {k: rounded(v, nd) for k, v in o.items()}
    if isinstance(o, list):
        return [rounded(v, nd) for v in o]
    return o


def key_minutes(timeline):
    """10, 15 e o minuto do último item (só os que a partida alcança; dois perto demais viram um)."""
    steps = (timeline or {}).get('steps') or []
    last = round(steps[-1]['minute'], 2) if steps else 15.0
    out = []
    for m in sorted({10.0, 15.0, last}):
        if m <= last + 0.01 and all(abs(m - x) >= 1 for x in out):
            out.append(m)
    if out and abs(out[-1] - last) > 0.01:
        out[-1] = last if abs(out[-1] - last) < 1 else out[-1]
    if last not in out:
        out.append(last)
    return out


class Calculator:
    """O que cada build ganha pré-calculado."""

    def __init__(self, site, basic, offense, quick, race=None):
        self.s = site
        self.basic = basic        # the basic report's tests [{key, weight}]
        self.race = race or []    # Rodada 15: the race report's tests (attackers that die)
        self.offense = offense    # the offensive report's targets [{key, weight}] (None = the app has no offense)
        self.quick = quick
        self.seconds = 0.0

    def effect(self, build, minute, tests=None):
        jobs = [{'tag': e['key'], 'testKey': e['key']} for e in (self.basic if tests is None else tests)]
        out = []
        for i in range(0, len(jobs), 6):
            r = self.s.post('/api/effect/evaluate', {'build': build, 'minute': minute, 'jobs': jobs[i:i + 6]})
            out.extend((r or {}).get('outcomes', r if isinstance(r, list) else []))
        return [slim_outcome(o) for o in out]

    def offense_at(self, build, minute):
        if not self.offense:
            return None
        jobs = [{'tag': e['key'], 'targetKey': e['key']} for e in self.offense]
        out = []
        try:
            for i in range(0, len(jobs), 6):
                out.extend(self.s.post('/api/offense/evaluate', {'build': build, 'minute': minute, 'readMode': 'como_joga',
                                                                 'starts': 6, 'jobs': jobs[i:i + 6]}) or [])
        except urllib.error.HTTPError as e:
            print(f'    ofensiva: {e.code}, fica de fora')
            self.offense = None
            return None
        return [slim_outcome(o) for o in out]

    def abilities(self, build, minute):
        try:
            return self.s.post('/api/lab/abilities', {'build': build, 'minute': minute, 'target': LAB_TARGET})
        except urllib.error.HTTPError as e:
            print(f'    habilidades: {e.code}')
            return None

    def run(self, entry, build, paces):
        """A build inteira: linhas do tempo, minutos-chave, efetividade, ofensiva, habilidades."""
        t0 = time.time()
        timelines = {}
        full = None
        for name, pace in paces.items():
            b = dict(build, goldPerMin=pace[0], xpPerMin=pace[1])
            try:
                raw = self.s.post('/api/calculate', b)
                full = raw if name == 'padrao' else full
                timelines[name] = trim_timeline(raw, series=name == 'padrao')
            except urllib.error.HTTPError as e:
                print(f'    linha do tempo {name}: {e.code}')
        build = dict(build, goldPerMin=paces['padrao'][0], xpPerMin=paces['padrao'][1])
        main = timelines.get('padrao')
        minutes = key_minutes(main)
        at = {}
        for m in minutes:
            k = f'{m:g}'
            at[k] = {'point': point_at((full or {}).get('series'), m), 'effect': self.effect(build, m)}
            if self.race:
                at[k]['race'] = self.effect(build, m, self.race)
            if not self.quick:
                at[k]['offense'] = self.offense_at(build, m)
                at[k]['abilities'] = self.abilities(build, m)
        entry.update({'build': build, 'paces': {k: {'gold': v[0], 'xp': v[1]} for k, v in paces.items()},
                      'timelines': timelines, 'minutes': minutes, 'at': at})
        try:
            arch = self.s.post('/api/archetypes', {'build': build, 'minute': minutes[-1] if minutes else None})
            entry['archetypeInfo'] = arch
            entry['archetype'] = (arch or {}).get('key') or entry.get('archetype')
        except urllib.error.HTTPError as e:
            print(f'    arquétipo: {e.code}')
        last_effect = at[f'{minutes[-1]:g}']['effect'] if minutes else []
        entry['index'] = weighted(self.basic, last_effect)
        grades = next((o.get('grades') for o in last_effect if o.get('grades')), None)
        entry['grade'] = grade_of(entry['index'], grades)
        # the class score that ranked the build (points, the index's scale): its grade by the same table (Rodada 13)
        entry['scoreGrade'] = grade_of(entry.get('score'), grades)
        offense_last = at[f'{minutes[-1]:g}'].get('offense') if minutes else None
        entry['offenseIndex'] = weighted(self.offense, offense_last) if offense_last else None
        self.seconds += time.time() - t0
        return entry


def slim_outcome(o):
    """Um resultado da Efetividade sem o replay da luta (só os números que a página mostra)."""
    if not isinstance(o, dict):
        return o
    drop = ('replay', 'frames', 'events', 'log', 'timeline', 'result', 'fight')
    return {k: v for k, v in o.items() if k not in drop}


def grade_of(value, grades):
    """A nota de um índice pelas notas do relatório (effectiveness.js gradeOf)."""
    if value is None or not grades:
        return None
    for g in grades:
        if g.get('from') is None or value >= g['from']:
            return g.get('key')
    return None


def weighted(entries, outcomes):
    """A média ponderada dos índices (EffectTestService / effectiveness.js weightedIndex)."""
    if not entries or not outcomes:
        return None
    w = s = 0.0
    for e, o in zip(entries, outcomes):
        v = (o or {}).get('index')
        if isinstance(v, (int, float)):
            s += e.get('weight', 1) * v
            w += e.get('weight', 1)
    return round(s / w, 2) if w else None


def summary(entry):
    """O que a lista do campeão mostra de cada build (o resto fica no arquivo da build)."""
    b = entry['build']
    tl = (entry.get('timelines') or {}).get('padrao') or {}
    names = {st.get('itemId'): st.get('itemName') for st in tl.get('steps') or []}
    items = [s['itemId'] for s in b.get('steps', []) if s.get('itemId')]
    return {k: entry.get(k) for k in ('key', 'kind', 'section', 'label', 'name', 'archetype', 'archetypeInfo', 'niche', 'score',
                                      'mean', 'p20', 'novelty', 'beatsKnown', 'closestKnown', 'index', 'grade', 'scoreGrade',
                                      'offenseIndex', 'text', 'runesFrom', 'orderCut')} | {
        'items': items, 'itemNames': [names.get(i) for i in items],
        'keystone': (b.get('runePage') or {}).get('keystone'),
        'lastMinute': (tl.get('steps') or [{}])[-1].get('minute') if tl.get('steps') else None,
    }


#: How many of the ranking's best go to the site besides the standard build (Rodada 13).
MELHORES = 3
#: Where each kind is shown: the best builds (the default) or the "Novidade" section (niches' best, discoveries).
SECAO = {'padrao': 'melhores', 'suporte': 'melhores', 'melhor': 'melhores', 'publica': 'melhores', 'nicho': 'novidade',
         'descoberta': 'novidade'}


def threat_summary(t):
    """A threat of the campaign (Rodada 14) without the whole kit: its items in order, the scores."""
    best = t.get('best') or {}
    order = best.get('order') or (best.get('items') or []) + ([best['boots']] if best.get('boots') else [])
    return {'scenario': t.get('scenario'), 'name': t.get('name'), 'subclasses': t.get('subclasses') or [],
            'count': t.get('count'), 'standardScore': t.get('standardScore'), 'bestScore': t.get('bestScore'),
            'bestItems': order if best else None, 'bestNames': best.get('names') if best else None}


def champion_builds(unit, latest, public, pace):
    """As builds de um campeão: a padrão robusta, as melhores do ranking, a melhor de cada nicho, as descobertas e as
    públicas. Cada uma diz a seção (melhores | novidade) e de onde vieram as runas."""
    out = []
    seen = {}
    base = public[0] if public else None
    d = (latest or {}).get('discovery') or {}
    standard = d.get('standard') or {}

    def add(kind, label, kit, extra=None):
        k = kit_key(kit)
        if not k[0]:
            return
        if k in seen:   # the same kit as another one: one build, both labels
            seen[k]['also'].append(label)
            return
        e = {'kind': kind, 'section': SECAO[kind], 'label': label, 'also': [], 'archetype': kit.get('archetype'),
             'niche': kit.get('niche'), 'score': kit.get('score'), 'mean': kit.get('mean'), 'p20': kit.get('p20'),
             'novelty': kit.get('novelty'), 'beatsKnown': kit.get('beatsKnown'), 'closestKnown': kit.get('closestKnown'),
             'runesFrom': kit_runes(kit, standard, base)[1], 'orderCut': kit.get('orderCut'),
             'build': kit_build(unit, kit, base, kit.get('goldPerMin') or pace, standard),
             'pace': (kit.get('goldPerMin') or pace, kit.get('xpPerMin') or pace)}
        e.update(extra or {})
        seen[k] = e
        out.append(e)

    if d.get('standard'):
        add('padrao', 'Padrão robusta', d['standard'])
    if d.get('support'):   # Rodada 13: ranked apart, it plays with the support pace
        add('suporte', 'Melhor de suporte', d['support'])
    for i, kit in enumerate((d.get('ranked') or [])[:MELHORES]):
        add('melhor', f'Melhor {i + 1}', kit)
    for niche, kit in (d.get('niches') or {}).items():
        add('nicho', f'Melhor em {niche}', kit)
    for i, f in enumerate(d.get('discoveries') or []):
        add('descoberta', f'Descoberta {i + 1}', f.get('kit') or {},
            {'text': f.get('text'), 'against': f.get('against'), 'wins': f.get('wins'), 'losses': f.get('losses'),
             'swaps': f.get('swaps'), 'synergy': f.get('synergy'), 'confirmed': f.get('confirmed')})
    for b in public:
        e = {'kind': 'publica', 'section': 'melhores', 'label': b.get('name'), 'name': b.get('name'), 'also': [],
             'buildId': b.get('id'),
             'build': b, 'pace': (b.get('goldPerMin') or pace, b.get('xpPerMin') or pace)}
        out.append(e)
    keys = set()
    for e in out:
        key = f"{slug(unit)}-{e['kind']}-{slug(e['label'])}" if e['kind'] != 'publica' else f"{slug(unit)}-publica-{e['buildId']}"
        base, n = key, 2
        while key in keys:   # two labels with the same slug: the second gets a number
            key = f'{base}-{n}'
            n += 1
        keys.add(key)
        e['key'] = key
        e['unitCode'] = unit
    return out


# ---------------------------------------------------------------------------------------------- checking

def verificar(pasta):
    """Confere que o manifesto bate com os arquivos: cada campeão, cada build da lista dele, os minutos-chave e as lutas.
    Devolve a lista de problemas (vazia = ok)."""
    def read(url):
        with open(os.path.join(pasta, *arquivo_estatico(url).split('/')), encoding='utf-8') as f:
            return json.load(f)
    problems = []
    try:
        m = read('/api/manifest')
    except (OSError, ValueError) as e:
        return [f'manifesto ilegível: {e}']
    if m.get('format') != FORMAT:
        problems.append(f"formato {m.get('format')} (esperado {FORMAT})")
    for c in m.get('champions', []):
        unit = c['unitCode']
        try:
            page = read(f'/api/site/campeao/{unit}')
        except (OSError, ValueError):
            problems.append(f'{unit}: sem o arquivo do campeão')
            continue
        if len(page.get('builds', [])) != c.get('builds'):
            problems.append(f"{unit}: {len(page.get('builds', []))} build(s) no arquivo, {c.get('builds')} no manifesto")
        for b in page.get('builds', []):
            try:
                full = read(f"/api/site/build/{b['key']}")
            except (OSError, ValueError):
                problems.append(f"{b['key']}: sem o arquivo da build")
                continue
            if not ((full.get('timelines') or {}).get('padrao') or {}).get('steps'):
                problems.append(f"{b['key']}: sem linha do tempo")
            if any(f'{x:g}' not in (full.get('at') or {}) for x in full.get('minutes') or []):
                problems.append(f"{b['key']}: minuto-chave sem cálculo")
    if m.get('fights'):
        try:
            if len(read('/api/site/lutas')) != m['fights']:
                problems.append('lutas: a lista não bate com o manifesto')
        except (OSError, ValueError):
            problems.append('lutas: sem a lista')
    return problems


def tamanho(pasta):
    """Arquivos e bytes de uma pasta (o site inteiro)."""
    n = total = 0
    for root, _, files in os.walk(pasta):
        for f in files:
            n += 1
            total += os.path.getsize(os.path.join(root, f))
    return n, total


# ---------------------------------------------------------------------------------------------- main

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--api', default='http://localhost:8097', help='o app local completo, sobre uma cópia do banco exportado')
    ap.add_argument('--saida', default='site-publico', help='pasta do site (os JSONs vão em <saida>/api)')
    ap.add_argument('--pausa', type=float, default=0.0, help='segundos entre leituras')
    ap.add_argument('--campeoes', default='', help='só estes códigos (ORNN,DARIUS); vazio = todos os do site')
    ap.add_argument('--sem-icones', action='store_true', help='não baixa os ícones de itens, runas, feitiços e campeões')
    ap.add_argument('--sem-lutas', action='store_true', help='não roda as lutas dos cenários públicos')
    ap.add_argument('--sem-ritmos', action='store_true', help='só o ritmo padrão (sem "atrás" e "à frente")')
    ap.add_argument('--rapido', action='store_true', help='só a linha do tempo e a Efetividade Básica (sem ofensiva e habilidades)')
    ap.add_argument('--limpar', action='store_true', help='apaga <saida>/api antes')
    a = ap.parse_args(argv)
    t0 = time.time()
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')

    if a.limpar and os.path.isdir(os.path.join(a.saida, 'api')):
        shutil.rmtree(os.path.join(a.saida, 'api'))
    s = Site(a.api, a.saida, a.pausa)
    info = s.read('/api/site/info')
    if not info.get('export'):
        print('Aviso: o banco lido não é uma exportação pública (sem public_export). Gere o site sobre o banco exportado.')
    patch = info['patch']
    print(f"Patch {patch}; exportação: {(info.get('export') or {}).get('exported_at', '—')}")

    # the catalog and the lists the pages read as they are
    for p in ['/api/meta', '/api/items', '/api/stat-prices', '/api/runes', '/api/champions', '/api/champions/profiles',
              '/api/effect/meta', '/api/effect/reports/basico', '/api/score/rules']:
        s.get(p)
    offense_meta = s.get('/api/offense/meta', required=False)
    champions = {c['code']: c for c in s.read('/api/champions') or []}
    profiles = {p['code']: p for p in s.read('/api/champions/profiles') or []}
    basic = s.read('/api/effect/reports/basico') or {}
    race = s.read('/api/effect/reports/corrida', required=False) or {}
    calc = Calculator(s, basic.get('tests') or [], ((offense_meta or {}).get('report') or {}).get('targets'), a.rapido,
                      race.get('tests') or [])
    print('Catálogo: ok')

    all_builds = s.read('/api/builds') or []
    wanted = {u.strip().upper() for u in a.campeoes.split(',') if u.strip()}
    units = [c for c in info['champions'] if not wanted or c['unitCode'] in wanted]
    manifest_champions = []
    for c in units:
        unit = c['unitCode']
        t1 = time.time()
        q = urllib.parse.quote(unit)
        latest = s.read(f'/api/campaigns/latest?unit={q}', required=False) if c.get('discovery') else None
        public = []
        for b in all_builds:
            if b.get('unitCode') == unit:
                full = s.read(f"/api/builds/{b['id']}")
                if full and full.get('steps'):
                    public.append(full)
        guides = s.get(f'/api/guides?unit={q}', required=False) or []
        suggested = None
        if latest and latest.get('discovery'):
            try:
                suggested = s.post('/api/guides/suggest', {'unitCode': unit, 'latest': True})
            except urllib.error.HTTPError as e:
                print(f'  guia sugerida de {unit}: {e.code}')
        champ = champions.get(unit) or {}
        name = c.get('champion') or champ.get('nome') or unit
        s.get(f'/api/champions/{q}', required=False)
        entries = champion_builds(unit, latest, public, PACES['padrao'])
        print(f'{name}: {len(entries)} build(s)')
        for e in entries:
            gold, xp = e.pop('pace')
            paces = {'padrao': (gold, xp)}
            if not a.sem_ritmos:
                paces['atras'] = (gold - (PACES['padrao'] - PACES['atras']), xp - (PACES['padrao'] - PACES['atras']))
                paces['frente'] = (gold + (PACES['frente'] - PACES['padrao']), xp + (PACES['frente'] - PACES['padrao']))
            calc.run(e, e['build'], paces)
            e['champion'] = name
            if e['kind'] == 'padrao' and latest:
                e['matchups'] = latest.get('matchups')
            s.write(f"/api/site/build/{e['key']}", rounded(e))
            print(f"  {e['label']}: índice {e.get('index')}, minutos {e.get('minutes')}")
        d = (latest or {}).get('discovery') or {}
        page = {
            'unitCode': unit, 'champion': name,
            'championClass': d.get('championClass') or ((profiles.get(unit) or {}).get('mainClasses') or [None])[0],
            'lanes': (profiles.get(unit) or {}).get('lanes') or [],
            'quality': d.get('quality'), 'lowConfidence': d.get('lowConfidence'), 'budget': d.get('budget'),
            'objective': d.get('objective') or 'novidade',
            'builds': [summary(e) | {'also': e.get('also')} for e in entries],
            'matchups': (latest or {}).get('matchups') or [],
            # Rodada 14: the champion's subclasses, the lane styles against each rival and the best kit per threat
            'subclasses': (profiles.get(unit) or {}).get('subclasses') or [],
            'styles': (latest or {}).get('styles') or [],
            'threats': [threat_summary(t) for t in (latest or {}).get('threats') or []],
            'synergies': d.get('synergies') or [], 'known': d.get('known') or [], 'notes': d.get('notes') or [],
            'guides': guides, 'suggestedGuide': suggested,
        }
        s.write(f'/api/site/campeao/{unit}', page)
        manifest_champions.append({'unitCode': unit, 'champion': name, 'championClass': page['championClass'], 'lanes': page['lanes'],
                                   'archetypes': sorted({b['archetype'] for b in page['builds'] if b.get('archetype')}),
                                   'quality': page['quality'], 'builds': len(entries),
                                   'discovered': bool(d), 'publicBuilds': sum(1 for e in entries if e['kind'] == 'publica'),
                                   'guides': len(guides), 'seconds': round(time.time() - t1, 1)})

    fights = []
    if not a.sem_lutas:
        for sc in s.read('/api/arena/scenarios') or []:
            full = s.get(f"/api/arena/scenarios/{sc['id']}")
            try:
                s.write(f"/api/arena/run/{sc['id']}", s.post('/api/arena/run', full))
                fights.append({'id': sc['id'], 'name': sc.get('name')})
            except urllib.error.HTTPError as e:
                print(f"  luta {sc.get('name')}: {e.code}")
        s.write('/api/site/lutas', fights)
        print(f'Lutas dos cenários públicos: {len(fights)}')

    if not a.sem_icones:
        for it in s.read('/api/items') or []:
            s.binary(f"/api/items/{it['id']}/image")
        icons = set()

        def walk(o):
            if isinstance(o, dict):
                for k, v in o.items():
                    if k in ('icon', 'iconGrey') and isinstance(v, str):
                        icons.add(v)
                    walk(v)
            elif isinstance(o, list):
                for v in o:
                    walk(v)
        walk(s.read('/api/runes') or {})
        for name in sorted(icons):
            s.binary(f'/api/runes/icons/{urllib.parse.quote(name)}')
            s.binary(f'/api/spells/icons/{urllib.parse.quote(name)}')
        champs = [c['icon'] for c in (s.read('/api/champions') or []) if c.get('icon')]
        for f in champs:
            s.binary(f'/api/champions/icons/{urllib.parse.quote(f)}')
        print(f'Ícones: {len(icons)} de runas e feitiços, {len(champs)} retratos de campeões, mais os dos itens')

    manifest = {
        'format': FORMAT, 'patch': patch, 'generatedAt': time.strftime('%Y-%m-%dT%H:%M:%S'),
        'export': info.get('export'), 'champions': manifest_champions, 'fights': len(fights),
        'icons': not a.sem_icones, 'quick': a.rapido, 'paces': {k: v for k, v in PACES.items() if not a.sem_ritmos or k == 'padrao'},
        'seconds': round(time.time() - t0, 1),
    }
    total = sum(s.files.values())
    manifest['files'] = len(s.files) + 1
    manifest['bytes'] = total
    s.write('/api/manifest', manifest)
    for p in verificar(a.saida):
        print('  problema:', p)
    print(f'{len(s.files)} arquivo(s), {total / 1024 / 1024:.1f} MB em {os.path.abspath(a.saida)}, '
          f'{time.time() - t0:.0f} s ({s.calls} pedidos)')
    return manifest


if __name__ == '__main__':
    sys.exit(0 if main() else 1)
