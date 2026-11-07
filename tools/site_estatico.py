"""Gera os JSONs de um site estático da versão pública (Rodada 11, opção 2 de docs/rodada-11.md).

Lê um servidor no perfil `publico` já rodando sobre o banco exportado (local, nunca o publicado) e grava cada resposta
de leitura num arquivo, com o mesmo formato da API, para uma página estática consultar sem servidor:

    cd backend && ./mvnw -Pexport
    (outro terminal) SPRING_PROFILES_ACTIVE=publico ./mvnw spring-boot:run
    python tools/site_estatico.py --api http://localhost:8080 --saida site-estatico

O que sai (em <saida>/api/...):
- catálogo: meta, itens (e os ícones), preços, runas e feitiços (e os ícones), campeões e fichas;
- builds públicas: lista, cada build e a linha do tempo dela (o planejador já calculado no ritmo salvo);
- arquétipos, Efetividade Básica pré-calculada, configuração dos relatórios, guias e o último resultado de Descoberta
  de cada campeão das builds públicas;
- Arena: kits, mapa, regras, cenários públicos e a luta de cada um, já rodada (respeitando o limite de cálculos).

Um caminho com parâmetros vira um nome de arquivo: /api/guides?unit=ORNN -> api/guides/unit=ORNN.json.
Só a biblioteca padrão do Python.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


def target(out, path):
    """O arquivo de um caminho da API: /api/x/y -> out/api/x/y.json; a query entra no nome."""
    parsed = urllib.parse.urlsplit(path)
    rel = parsed.path.lstrip('/')
    if parsed.query:
        rel += '/' + parsed.query.replace('&', '_')
    return os.path.join(out, *rel.split('/'))


class Site:
    def __init__(self, api, out, pause):
        self.api = api.rstrip('/')
        self.out = out
        self.pause = pause
        self.files = 0
        self.bytes = 0

    def request(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode('utf-8')
        for attempt in range(6):
            req = urllib.request.Request(self.api + path, data=data, method=method,
                                         headers={'Content-Type': 'application/json'} if data else {})
            try:
                with urllib.request.urlopen(req, timeout=120) as res:
                    return res.read()
            except urllib.error.HTTPError as e:
                if e.code in (429, 503) and attempt < 5:
                    wait = int(e.headers.get('Retry-After') or 10)
                    print(f'  {e.code} em {path}: espera {wait} s (limite da versão pública)')
                    time.sleep(wait)
                    continue
                raise
        raise RuntimeError(f'{path}: sem resposta')

    def save(self, path, raw, ext):
        file = target(self.out, path) + ext
        os.makedirs(os.path.dirname(file), exist_ok=True)
        with open(file, 'wb') as f:
            f.write(raw)
        self.files += 1
        self.bytes += len(raw)

    def get(self, path, required=True):
        try:
            raw = self.request('GET', path)
        except urllib.error.HTTPError as e:
            if required:
                raise
            print(f'  {e.code} em {path}: ignorado')
            return None
        self.save(path, raw, '.json')
        time.sleep(self.pause)
        return json.loads(raw) if raw else None

    def post(self, path, body, name):
        raw = self.request('POST', path, body)
        self.save(name, raw, '.json')
        return json.loads(raw) if raw else None

    def binary(self, path):
        try:
            raw = self.request('GET', path)
        except urllib.error.HTTPError as e:
            if e.code != 404:   # a name of the runes' list is a rune's or a spell's icon: the other one is 404
                print(f'  {e.code} em {path}: ignorado')
            return
        self.save(path, raw, '')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--api', default='http://localhost:8080', help='o servidor público (local) a ler')
    ap.add_argument('--saida', default='site-estatico', help='pasta dos arquivos')
    ap.add_argument('--pausa', type=float, default=0.05, help='segundos entre leituras')
    ap.add_argument('--sem-icones', action='store_true', help='não baixa os ícones de itens, runas e feitiços')
    ap.add_argument('--sem-lutas', action='store_true', help='não roda as lutas dos cenários públicos')
    a = ap.parse_args()

    s = Site(a.api, a.saida, a.pausa)
    info = s.get('/api/public/info')
    if not info or not info.get('public'):
        sys.exit('O servidor não está no perfil publico.')
    print('Exportação lida:', info.get('export'))

    for p in ['/api/meta', '/api/items', '/api/stat-prices', '/api/runes', '/api/champions', '/api/champions/profiles',
              '/api/folders', '/api/builds', '/api/archetypes', '/api/score/rules', '/api/effect/meta',
              '/api/effect/tests', '/api/effect/reports/basico', '/api/effect/reports/completo', '/api/public/effect',
              '/api/lab/effect-sources', '/api/arena/kits', '/api/arena/rift-rules', '/api/arena/templates',
              '/api/arena/facts', '/api/arena/policies', '/api/arena/combos', '/api/arena/scenarios',
              '/api/campaigns']:
        s.get(p)
    print('Catálogo e listas: ok')

    for c in s.get('/api/champions') or []:
        s.get(f"/api/champions/{urllib.parse.quote(str(c['code']))}", required=False)
    print('Fichas dos campeões: ok')

    units = set()
    for b in s.get('/api/builds') or []:
        units.add(b['unitCode'])
        s.get(f"/api/builds/{b['id']}")
        s.get(f"/api/builds/{b['id']}/timeline", required=False)
    for u in sorted(units):
        q = urllib.parse.quote(u)
        s.get(f'/api/guides?unit={q}', required=False)
        s.get(f'/api/meta-builds?unit={q}', required=False)
        s.get(f'/api/campaigns/latest?unit={q}', required=False)
    print(f'Builds públicas e o que foi calculado delas: {len(units)} campeão(ões)')

    if not a.sem_lutas:
        for sc in s.get('/api/arena/scenarios') or []:
            full = s.get(f"/api/arena/scenarios/{sc['id']}")
            s.post('/api/arena/run', full, f"/api/arena/run/{sc['id']}")
        print('Lutas dos cenários públicos: ok')

    if not a.sem_icones:
        for it in s.get('/api/items') or []:
            s.binary(f"/api/items/{it['id']}/image")
        runes = s.get('/api/runes') or {}
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
        walk(runes)
        for name in sorted(icons):
            s.binary(f'/api/runes/icons/{urllib.parse.quote(name)}')
            s.binary(f'/api/spells/icons/{urllib.parse.quote(name)}')
        print(f'Ícones: {len(icons)} de runas e feitiços, mais os dos itens')

    print(f'{s.files} arquivo(s), {s.bytes / 1024:.0f} KB em {os.path.abspath(a.saida)}')


if __name__ == '__main__':
    main()
