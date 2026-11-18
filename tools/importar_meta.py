"""Rodada 20, Parte 3: importa as estatísticas públicas do servidor chinês do Wild Rift (presença, banimento e vitória por
campeão, rota e elo) para o draft de Simulações › Draft e partida. Só a biblioteca padrão do Python.

Rodado pelo usuário, quando quiser (nada roda sozinho):

    python tools/importar_meta.py                       # baixa, guarda em cache com a data e escreve o meta.json
    python tools/importar_meta.py --offline             # usa o último cache, sem rede
    python tools/importar_meta.py --herois tools/meta_herois.json --saida backend/data/meta/meta.json

As fontes (as mesmas que tier lists como a do Wild Legends usam), lidas em 18/11/2026:
  - ranking: https://mlol.qt.qq.com/go/lgame_battle_info/hero_rank_list_v2
      {"result": 0, "data": {<chave externa>: {<posição>: [ {hero_id, appear_rate, forbid_rate, win_rate, dtstatdate, ...} ]}}}
      appear_rate / forbid_rate / win_rate são frações em texto ("0.037698"). A resposta NÃO diz o que a chave externa é
      (provavelmente o elo) nem o que cada posição "1".."5" é: as duas coisas ficam marcadas EST abaixo.
  - heróis: https://game.gtimg.cn/images/lgamem/act/lrlib/js/heroList/hero_list.js
      {"heroList": {"10001": {heroId, name (chinês), alias (pinyin), lane ("单人路;打野"), ...}}}
      Não há nome em inglês nem em português.

Por isso:
  - o nome do campeão vem do arquivo de correspondência `--herois` (padrão tools/meta_herois.json), que o usuário
    mantém: {"10001": "Garen", "奥恩": "Ornn", ...} (chave = heroId ou o nome chinês; valor = o nome de
    campeoes_7_3f.yml). Os heróis sem correspondência vão para tools/meta_herois_faltando.json, para preencher;
  - a rota de cada posição sai da maioria das rotas dos heróis listados nela (o campo `lane` da lista de heróis:
    单人路 Barão, 打野 Selva, 中路 Meio, 射手 Dragão, 辅助 Suporte). É uma inferência (EST): o relatório mostra a
    contagem de cada posição para conferir. `--posicoes '{"1": "Meio", ...}'` impõe outra;
  - cada chave externa vira um elo chamado "fonte_<chave>" (EST: o significado não está na resposta).

Saída (lida pelo backend em planner.meta-path, padrão backend/data/meta/meta.json):
    {"fonte": "...", "data": "2026-11-17", "elos": {"fonte_0": {"Ornn": {"Barão": {"presenca": 0.05, "banimento": 0.01, "vitoria": 0.52}}}},
     "posicoes": {...}, "sem_correspondencia": N}
"""
import argparse
import datetime
import json
import os
import sys
import urllib.request
from collections import Counter

RANK_URL = 'https://mlol.qt.qq.com/go/lgame_battle_info/hero_rank_list_v2'
HEROES_URL = 'https://game.gtimg.cn/images/lgamem/act/lrlib/js/heroList/hero_list.js'
LANES = {'单人路': 'Barão', '打野': 'Selva', '中路': 'Meio', '射手': 'Dragão', '辅助': 'Suporte'}
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (planner local)'})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode('utf-8')


def loads_js(text):
    """The hero list is JSON, sometimes wrapped as JS: from the first '{' to the last '}'."""
    return json.loads(text[text.index('{'):text.rindex('}') + 1])


def cached(cache, name, url, offline):
    os.makedirs(cache, exist_ok=True)
    today = datetime.date.today().isoformat()
    path = os.path.join(cache, f'{today}-{name}')
    if offline or os.path.exists(path):
        if not os.path.exists(path):
            older = sorted(f for f in os.listdir(cache) if f.endswith(name))
            if not older:
                sys.exit(f'Sem cache de {name} em {cache}: rode sem --offline.')
            path = os.path.join(cache, older[-1])
        with open(path, encoding='utf-8') as f:
            return f.read(), path
    text = fetch(url)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(text)
    return text, path


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--herois', default=os.path.join(ROOT, 'tools', 'meta_herois.json'))
    ap.add_argument('--saida', default=os.path.join(ROOT, 'backend', 'data', 'meta', 'meta.json'))
    ap.add_argument('--cache', default=os.path.join(ROOT, 'backend', 'data', 'meta', 'cache'))
    ap.add_argument('--posicoes', help='JSON {"1": "Meio", ...} para impor a rota de cada posição')
    ap.add_argument('--offline', action='store_true', help='usa o último cache, sem rede')
    args = ap.parse_args()

    rank_text, rank_path = cached(args.cache, 'rank.json', RANK_URL, args.offline)
    heroes_text, heroes_path = cached(args.cache, 'hero_list.js', HEROES_URL, args.offline)
    rank = json.loads(rank_text)
    heroes = loads_js(heroes_text).get('heroList', {})
    names = {}
    if os.path.exists(args.herois):
        with open(args.herois, encoding='utf-8') as f:
            names = {k: v for k, v in json.load(f).items() if not k.startswith('_')}

    def champion(hero_id):
        h = heroes.get(str(hero_id), {})
        return names.get(str(hero_id)) or names.get(h.get('name', ''))

    # the lane of each position: the majority of its heroes' lanes (EST), unless imposed
    counts = {}
    for outer in rank.get('data', {}).values():
        for pos, rows in outer.items():
            c = counts.setdefault(pos, Counter())
            for r in rows:
                lane = (heroes.get(str(r.get('hero_id')), {}).get('lane') or '').split(';')[0]
                if lane in LANES:
                    c[LANES[lane]] += 1
    positions = {p: c.most_common(1)[0][0] for p, c in counts.items() if c}
    if args.posicoes:
        positions.update(json.loads(args.posicoes))

    elos = {}
    missing = {}
    date = None
    for outer_key, outer in rank.get('data', {}).items():
        elo = elos.setdefault(f'fonte_{outer_key}', {})
        for pos, rows in outer.items():
            lane = positions.get(pos)
            if not lane:
                continue
            for r in rows:
                date = date or r.get('dtstatdate')
                name = champion(r.get('hero_id'))
                if not name:
                    h = heroes.get(str(r.get('hero_id')), {})
                    missing[str(r.get('hero_id'))] = {'nome_chines': h.get('name'), 'alias': h.get('alias'), 'rota': h.get('lane')}
                    continue
                elo.setdefault(name, {})[lane] = {
                    'presenca': float(r.get('appear_rate') or 0),
                    'banimento': float(r.get('forbid_rate') or 0),
                    'vitoria': float(r.get('win_rate') or 0.5),
                }

    when = f'{date[:4]}-{date[4:6]}-{date[6:]}' if date and len(date) == 8 else datetime.date.today().isoformat()
    out = {
        'fonte': f'{RANK_URL} + {HEROES_URL} (cache: {os.path.basename(rank_path)}, {os.path.basename(heroes_path)})',
        'data': when,
        'posicoes': {p: {'rota': positions.get(p), 'contagem': dict(counts.get(p, {}))} for p in sorted(counts)},
        'posicoes_fonte': 'imposta' if args.posicoes else 'EST: maioria das rotas dos heróis de cada posição',
        'elos': elos,
        'sem_correspondencia': len(missing),
    }
    os.makedirs(os.path.dirname(args.saida), exist_ok=True)
    with open(args.saida, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    falta = os.path.join(ROOT, 'tools', 'meta_herois_faltando.json')
    with open(falta, 'w', encoding='utf-8') as f:
        json.dump(missing, f, ensure_ascii=False, indent=1, sort_keys=True)
    mapped = sum(len(v) for v in elos.values())
    print(f'meta: {when}; {len(elos)} elo(s); {mapped} campeão-elo com dados; {len(missing)} herói(s) sem correspondência '
          f'(lista em {falta}: preencha {args.herois}).')
    for p in sorted(counts):
        print(f'  posição {p} -> {positions.get(p)} ({dict(counts[p])})')
    print(f'escrito em {args.saida}')


if __name__ == '__main__':
    main()
