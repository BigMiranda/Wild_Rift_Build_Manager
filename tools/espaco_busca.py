"""Exact size of the build search space of a patch (Rodada 9): item sets by the shop's rules, orders and rune pages.

    python tools/espaco_busca.py [7_3d]

Rules (as the planner's timeline checks them): five items besides one pair of boots; one item per exclusive group;
an item that evolves (`evolucao`) is the same item as its evolved form; one active item at most is NOT a shop rule here
(the engine reports it, the game allows it), so it is ignored. Boots: tier 2 kept, or tier 3 (upgraded from 10:00).
Rune page: 1 keystone + 1 rune of each row of the primary tree + 1 rune of another tree (any row).
"""
import itertools
import math
import sys
from collections import Counter

import yaml

patch = sys.argv[1] if len(sys.argv) > 1 else '7_3d'
base = 'backend/src/main/resources/seed/'
shop = yaml.safe_load(open(base + 'loja_' + patch + '.yml', encoding='utf8'))['itens']
runes = yaml.safe_load(open(base + 'runas_' + patch + '.yml', encoding='utf8'))

items = {n: i for n, i in shop.items() if i.get('secao') == 'aprimorado' and 'Botas' not in (i.get('abas') or [])}
boots2 = [n for n, i in shop.items() if i.get('secao') == 'tier_medio' and 'Botas' in (i.get('abas') or [])]
boots3 = [n for n, i in shop.items() if i.get('secao') == 'aprimorado' and 'Botas' in (i.get('abas') or [])]
names = sorted(items)
groups = {n: set(items[n].get('exclusivo') or []) for n in names}

# sets of 5 with no exclusive group twice: count by inclusion over the groups (a DP over items)
# state: frozenset of groups used, count chosen
dp = Counter({(frozenset(), 0): 1})
for n in names:
    nxt = Counter(dp)
    for (used, k), c in dp.items():
        if k < 5 and not (groups[n] & used):
            nxt[(used | groups[n], k + 1)] += c
    dp = nxt
sets5 = sum(c for (used, k), c in dp.items() if k == 5)
free5 = math.comb(len(names), 5)
boot_final = len(boots2) + len(boots3)
unordered = sets5 * boot_final
# orders: 6 purchases in any order; a tier 3 boot also has its tier 2 step before it, anywhere before (7 steps)
orders_t2 = sets5 * len(boots2) * math.factorial(6)
orders_t3 = sets5 * len(boots3) * (math.factorial(7) // 2)
ordered = orders_t2 + orders_t3

trees = runes['arvores']
rows = Counter((r['arvore'], r['linha']) for r in runes['runas'].values())
per_tree = Counter(r['arvore'] for r in runes['runas'].values())
pages_no_key = 0
for tr in trees:
    prim = 1
    for row in (1, 2, 3):
        prim *= rows[(tr, row)]
    other = sum(c for t2, c in per_tree.items() if t2 != tr)
    pages_no_key += prim * other
keystones = len(runes['fundamentais'])
pages = pages_no_key * keystones

print('itens aprimorados (sem botas):', len(names))
print('botas tier 2:', len(boots2), '| tier 3:', len(boots3))
print('grupos exclusivos:', dict(Counter(g for n in names for g in groups[n])))
print('conjuntos de 5 itens, sem regra:', f'{free5:,}')
print('conjuntos de 5 itens, com os grupos exclusivos:', f'{sets5:,}')
print('builds sem ordem (5 itens + 1 bota final):', f'{unordered:,}')
print('builds com ordem (bota tier 2 como passo da tier 3):', f'{ordered:,}')
print('páginas de runas (fundamental + 3 da principal + 1 de outra árvore):', f'{pages:,}', f'({keystones} fundamentais x {pages_no_key:,})')
print('builds com ordem x páginas:', f'{ordered * pages:.3e}')
