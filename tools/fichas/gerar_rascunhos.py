#!/usr/bin/env python3
"""Gera rascunhos de fichas da Arena a partir da wiki (Rodada das fichas).

Roda no PC do usuário (precisa de internet). Só a biblioteca padrão.

Para cada campeão do lote:
  1. lista na wiki as páginas de dados do Wild Rift ("Template:WR Data <Campeão>/...") e do PC ("Template:Data <Campeão>/...");
  2. baixa o conteúdo (em lotes de 50 páginas, com cache em disco para não baixar de novo);
  3. lê os campos de cada habilidade (alcance, largura, raio, ângulo, velocidade, conjuração, recarga, custo, alvo...);
  4. junta por slot (P, 1, 2, 3, R), preferindo o valor do Wild Rift e caindo no do PC;
  5. grava um rascunho YAML por lote e um relatório de lacunas.

Os números de dano, razões, recargas e efeitos NÃO vêm daqui: vêm do texto do jogo em campeoes_<patch>.yml (pt-BR),
que vale mais. Este script só traz a geometria e pistas de forma, sempre com a fonte de cada número.

Uso:
  python tools/fichas/gerar_rascunhos.py --lote 1 --saida backend/src/main/resources/seed/rascunhos
  python tools/fichas/gerar_rascunhos.py --campeoes "Mordekaiser,Garen" --saida /tmp/x
  python tools/fichas/gerar_rascunhos.py --diagnostico "Template:WR Data Garen/Judgment"   (mostra o texto bruto)
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

LOTES = {
    1: ['Mordekaiser', 'Garen', 'Sett', 'Jax', 'Fiora', 'Camille', 'Irelia', 'Riven', 'Aatrox', 'Malphite', 'Nasus',
        'Gwen', 'Kayle', 'Teemo', 'Shen', 'Sion'],
    2: ['Dr. Mundo', 'Urgot', 'Olaf', 'Tryndamere', 'Volibear', 'Gnar', 'Kennen', 'Jayce', "Cho'Gath", 'Poppy',
        'Singed', 'Yone', 'Yasuo', 'Pantheon', 'Ambessa', 'Gragas'],
    3: ['Maokai', 'Skarner', 'Vladimir', 'Wukong', 'Jarvan IV', 'Warwick', 'Akali', 'Aurora', 'Diana', 'Ekko',
        'Fizz', 'Kassadin', 'Nautilus', 'Amumu', 'Viego', 'Zed', 'Graves'],
}
# Nome do campeão na base (pt-BR) -> nome na wiki, quando diferente.
NOME_WIKI = {}

APIS = ['https://wiki.leagueoflegends.com/en-us/api.php', 'https://wiki.leagueoflegends.com/api.php']
AGENT = 'WildRiftBuildManager-fichas/1.0 (ferramenta pessoal; gerador de rascunhos)'
GEOMETRIA = ['range', 'target range', 'attack range', 'collision radius', 'effect radius', 'width', 'angle',
             'inner radius', 'tether radius', 'speed', 'cast time']
OUTROS = ['skill', 'targeting', 'affects', 'damagetype', 'spelleffects', 'projectile', 'cooldown', 'cdstart', 'cost',
          'costtype', 'recharge', 'grounded', 'knockdown', 'spellshield']
SLOT = {'I': 'P', 'P': 'P', 'Q': '1', '1': '1', 'W': '2', '2': '2', 'E': '3', '3': '3', 'R': 'R', '4': 'R'}


class Wiki:
    def __init__(self, cache, pausa):
        self.cache = cache
        self.pausa = pausa
        self.api = None
        os.makedirs(cache, exist_ok=True)

    def _get(self, params):
        key = urllib.parse.urlencode(sorted(params.items()))
        file = os.path.join(self.cache, str(abs(hash(key)) % 10 ** 12) + '_' + re.sub(r'\W+', '_', key)[:80] + '.json')
        if os.path.exists(file):
            with open(file, encoding='utf-8') as f:
                return json.load(f)
        apis = [self.api] if self.api else APIS
        last = None
        for api in apis:
            url = api + '?' + urllib.parse.urlencode(params)
            try:
                req = urllib.request.Request(url, headers={'User-Agent': AGENT})
                with urllib.request.urlopen(req, timeout=60) as r:
                    data = json.loads(r.read().decode('utf-8'))
                self.api = api
                with open(file, 'w', encoding='utf-8') as f:
                    json.dump(data, f)
                time.sleep(self.pausa)
                return data
            except Exception as e:   # tenta o próximo endereço da API
                last = e
        raise RuntimeError(f'API da wiki inacessível ({last})')

    def titulos(self, prefixo):
        out, cont = [], {}
        while True:
            p = {'action': 'query', 'list': 'allpages', 'apnamespace': '10', 'apprefix': prefixo,
                 'aplimit': '500', 'format': 'json', 'formatversion': '2', **cont}
            d = self._get(p)
            out += [x['title'] for x in d.get('query', {}).get('allpages', [])]
            if 'continue' not in d:
                return out
            cont = {'apcontinue': d['continue']['apcontinue']}

    def conteudos(self, titulos):
        res = {}
        for i in range(0, len(titulos), 50):
            p = {'action': 'query', 'prop': 'revisions', 'rvprop': 'content', 'rvslots': 'main',
                 'titles': '|'.join(titulos[i:i + 50]), 'format': 'json', 'formatversion': '2'}
            d = self._get(p)
            for pg in d.get('query', {}).get('pages', []):
                revs = pg.get('revisions') or []
                if revs:
                    res[pg['title']] = revs[0].get('slots', {}).get('main', {}).get('content', '')
        return res


def campos(texto):
    """Lê os pares `| chave = valor` do modelo (valores podem ter várias linhas)."""
    out, chave, buf = {}, None, []
    for linha in texto.splitlines():
        m = re.match(r'^\s*\|\s*([A-Za-z][A-Za-z0-9 _]*?)\s*=\s*(.*)$', linha)
        if m:
            if chave is not None:
                out[chave] = '\n'.join(buf).strip()
            chave, buf = m.group(1).strip().lower(), [m.group(2)]
        elif chave is not None:
            buf.append(linha)
    if chave is not None:
        out[chave] = '\n'.join(buf).strip()
    return out


def _ap(m):
    """{{ap|7 to 4 4}} = 4 values from 7 to 4, evenly spaced (the wiki's rank interpolation)."""
    a, b, n = float(m.group(1)), float(m.group(2)), int(m.group(3) or 0)
    if n < 2:
        return f'{m.group(1)} / {m.group(2)}'
    vs = [round(a + (b - a) * i / (n - 1), 4) for i in range(n)]
    return ' / '.join(str(int(x)) if x == int(x) else str(x) for x in vs)


def limpa(v):
    v = re.sub(r'<!--.*?-->', '', v, flags=re.S)
    v = re.sub(r'\{\{ap\|\s*(-?[\d.]+)\s+to\s+(-?[\d.]+)(?:\s+(\d+))?\s*\}\}', _ap, v)
    v = re.sub(r'\{\{(?:[^{}]|\{[^{}]*\})*\}\}', lambda m: ' '.join(re.findall(r'[-\d.]+', m.group(0))) or ' ', v)
    v = re.sub(r'\[\[(?:[^|\]]*\|)?([^\]]*)\]\]', r'\1', v)
    v = re.sub(r"<[^>]+>|'''?", '', v)
    return re.sub(r'\s+', ' ', v).strip(' }')


def numeros(v):
    """'175 / 200 / 225' -> [175, 200, 225]; '1000' -> 1000; texto -> None."""
    v = limpa(v)
    ns = [float(x) for x in re.findall(r'(?<![\w.])-?\d+(?:\.\d+)?', v)]
    if not ns:
        return None
    ns = [int(n) if n == int(n) else n for n in ns]
    return ns[0] if len(ns) == 1 else ns


def le_habilidades(titulos, textos, fonte):
    por_slot = {}
    for t in titulos:
        c = campos(textos.get(t, ''))
        if not c:
            continue
        slot = SLOT.get(limpa(c.get('skill', '')).upper()[:1] or '?', '?')
        hab = {'modelo': t, 'fonte': fonte, 'nome_en': t.split('/', 1)[-1]}
        for k in GEOMETRIA:
            n = numeros(c.get(k, ''))
            if n is not None:
                hab[k.replace(' ', '_')] = n
        for k in OUTROS:
            if c.get(k):
                hab[k.replace(' ', '_')] = numeros(c[k]) if k in ('cooldown', 'cost', 'recharge') else limpa(c[k])
        por_slot.setdefault(slot, []).append(hab)
    return por_slot


def forma_sugerida(h):
    alvo = (h.get('targeting') or '').lower()
    if 'passive' in alvo:
        return 'passiva'
    if h.get('angle'):
        return 'cone'
    if 'direction' in alvo:
        return 'linha' if h.get('width') else 'avanco?'
    if 'location' in alvo:
        return 'area (origem: ponto)'
    if 'unit' in alvo or 'auto' in alvo:
        return 'alvo' if not h.get('effect_radius') else 'area (no lançador)'
    return None


def junta(wr, pc):
    """Uma entrada por slot: campos do WR; o que faltar, do PC, com a fonte de cada campo."""
    out = {}
    for slot in sorted(set(wr) | set(pc), key=lambda s: 'P123R?'.index(s) if s in 'P123R?' else 9):
        a = (wr.get(slot) or [{}])[0]
        b = (pc.get(slot) or [{}])[0]
        m, fontes = {}, {}
        for k in [x.replace(' ', '_') for x in GEOMETRIA + OUTROS]:
            if k in a:
                m[k], fontes[k] = a[k], 'WR'
            elif k in b:
                m[k], fontes[k] = b[k], 'PC'
        m['nome_en'] = a.get('nome_en') or b.get('nome_en')
        m['forma_sugerida'] = forma_sugerida(m)
        m['fontes'] = fontes
        m['variantes'] = [x['modelo'] for x in (wr.get(slot) or []) + (pc.get(slot) or [])]
        out[slot] = m
    return out


def yaml(v, ind=0):
    pad = '  ' * ind
    if isinstance(v, dict):
        return ''.join(f'{pad}{json.dumps(str(k), ensure_ascii=False)}:' + (
            '\n' + yaml(x, ind + 1) if isinstance(x, (dict, list)) and x else ' ' + json.dumps(x, ensure_ascii=False) + '\n')
            for k, x in v.items())
    if isinstance(v, list):
        return ''.join(f'{pad}- ' + (('\n' + yaml(x, ind + 1)) if isinstance(x, (dict, list)) and x
                                    else json.dumps(x, ensure_ascii=False) + '\n') for x in v)
    return pad + json.dumps(v, ensure_ascii=False) + '\n'


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--lote', type=int)
    ap.add_argument('--campeoes', help='lista separada por vírgula (nomes da base)')
    ap.add_argument('--saida', default='backend/src/main/resources/seed/rascunhos')
    ap.add_argument('--cache', default='capturas/wiki-cache')
    ap.add_argument('--pausa', type=float, default=0.5, help='segundos entre pedidos à wiki')
    ap.add_argument('--diagnostico', help='mostra o texto bruto e os campos lidos de um modelo')
    a = ap.parse_args()
    w = Wiki(a.cache, a.pausa)

    if a.diagnostico:
        t = w.conteudos([a.diagnostico]).get(a.diagnostico, '')
        print(t[:4000])
        print('\nCAMPOS LIDOS:', json.dumps({k: limpa(v) for k, v in campos(t).items()}, ensure_ascii=False, indent=1)[:4000])
        return

    nomes = [x.strip() for x in a.campeoes.split(',')] if a.campeoes else LOTES.get(a.lote or 0)
    if not nomes:
        sys.exit('Diga --lote N ou --campeoes.')
    os.makedirs(a.saida, exist_ok=True)
    rasc, lacunas = {}, {}
    for n in nomes:
        wn = NOME_WIKI.get(n, n)
        t_wr = w.titulos(f'WR Data {wn}/')
        t_pc = w.titulos(f'Data {wn}/')
        txt = w.conteudos(t_wr + t_pc)
        wr = le_habilidades(t_wr, txt, 'WR')
        pc = le_habilidades(t_pc, txt, 'PC')
        j = junta(wr, pc)
        rasc[n] = {'wiki': wn, 'modelos_wr': len(t_wr), 'modelos_pc': len(t_pc), 'habilidades': j}
        falta = {s: [k for k in ('range', 'width', 'effect_radius', 'speed', 'cast_time')
                     if k not in h and not (k == 'range' and 'target_range' in h)]
                 for s, h in j.items() if s in '123R'}
        lacunas[n] = {s: f for s, f in falta.items() if f}
        print(f'{n}: {len(t_wr)} modelos WR, {len(t_pc)} PC, slots {sorted(j)}')
    nome = f'lote_{a.lote}' if a.lote else 'avulso'
    with open(os.path.join(a.saida, nome + '.yml'), 'w', encoding='utf-8') as f:
        f.write('# Rascunho gerado por tools/fichas/gerar_rascunhos.py (geometria da wiki; NÃO é ficha final).\n')
        f.write(yaml(rasc))
    with open(os.path.join(a.saida, nome + '_lacunas.json'), 'w', encoding='utf-8') as f:
        json.dump(lacunas, f, ensure_ascii=False, indent=1)
    print('Gravado em', os.path.join(a.saida, nome + '.yml'))


if __name__ == '__main__':
    main()
