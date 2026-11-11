"""Retratos dos campeões: o ícone quadrado (120x120) de cada campeão da base, do LoL de PC (Data Dragon, o CDN público
da Riot), salvo em backend/src/main/resources/seed/campeoes/ com um manifest.json (nome da base -> arquivo).

O app nunca busca imagens fora em tempo de execução: este script roda uma vez (e de novo quando entra um campeão).
O nome da base (pt-BR do Wild Rift) é casado com o nome pt-BR do LoL; os que não casam pelo nome, pela chave em inglês
(ex.: K'Sante -> KSante). Campeões que só existem no Wild Rift ficam sem retrato e são listados no fim.

    python tools/campeoes_retratos.py                # baixa os que faltam
    python tools/campeoes_retratos.py --refazer      # baixa todos de novo (versão mais nova do Data Dragon)
"""
import argparse
import json
import pathlib
import re
import sys
import unicodedata
import urllib.request

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
SEED = ROOT / 'backend/src/main/resources/seed'
OUT = SEED / 'campeoes'
DD = 'https://ddragon.leagueoflegends.com'
# nomes que diferem entre a base (Wild Rift pt-BR) e o LoL de PC pt-BR, quando houver
ALIAS = {}


def norm(s):
    s = unicodedata.normalize('NFD', s)
    s = ''.join(c for c in s if unicodedata.category(c) != 'Mn')
    return re.sub(r'[^a-z0-9]', '', s.lower())


def slug(s):
    s = unicodedata.normalize('NFD', s)
    s = ''.join(c for c in s if unicodedata.category(c) != 'Mn')
    return re.sub(r'[^a-z0-9]+', '-', s.lower()).strip('-')


def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'wr-build-manager'})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--refazer', action='store_true')
    ap.add_argument('--campeoes', default=None, help='arquivo de campeões (padrão: o do patch atual em application.properties)')
    a = ap.parse_args()
    src = a.campeoes
    if src is None:
        props = (ROOT / 'backend/src/main/resources/application.properties').read_text(encoding='utf-8')
        src = SEED / re.search(r'planner\.seed\.champions=classpath:seed/(\S+)', props).group(1)
    names = list(yaml.safe_load(open(src, encoding='utf-8'))['campeoes'].keys())
    version = json.loads(get(f'{DD}/api/versions.json'))[0]
    data = json.loads(get(f'{DD}/cdn/{version}/data/pt_BR/champion.json'))['data']
    by = {}
    for key, c in data.items():
        by.setdefault(norm(c['name']), key)
        by.setdefault(norm(key), key)
    OUT.mkdir(parents=True, exist_ok=True)
    manifest_path = OUT / 'manifest.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8')) if manifest_path.exists() else {}
    missing = []
    got = 0
    for n in names:
        key = by.get(norm(ALIAS.get(n, n)))
        if key is None:
            missing.append(n)
            continue
        file = slug(n) + '.png'
        target = OUT / file
        if a.refazer or not target.exists():
            target.write_bytes(get(f"{DD}/cdn/{version}/img/champion/{data[key]['image']['full']}"))
            got += 1
        manifest[n] = file
    manifest = dict(sorted(manifest.items()))
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    print(f'Data Dragon {version}: {len(manifest)} retratos no manifest, {got} baixados agora')
    if missing:
        print('Sem retrato (não existem no LoL de PC ou o nome não casou):', ', '.join(missing))
    return 0


if __name__ == '__main__':
    sys.exit(main())
