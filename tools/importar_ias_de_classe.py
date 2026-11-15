#!/usr/bin/env python3
"""Copia as IAs de classe treinadas numa cópia do banco para o banco real (Rodada 18, parte 2).

O ClassAiTrainTest treina as IAs de classe ("class:Mago"...) em backend/target/classai/copy.db, para não parar o
backend nem uma campanha em andamento. Este script copia para o banco real as que valem: aceitas pelo treino, ou
melhores que o cérebro Tático em mais da metade das lutas de validação. Uma inserção curta: o backend que estiver
rodando lê as IAs do banco a cada escolha do cérebro Automático, então passa a usá-las na hora.

Uso: python tools/importar_ias_de_classe.py [--copia backend/target/classai/copy.db] [--banco backend/data/ornn-planner.db]
     [--minimo-tatico 0.5] [--simular]
"""
import argparse
import sqlite3
import sys


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--copia', default='backend/target/classai/copy.db')
    ap.add_argument('--banco', default='backend/data/ornn-planner.db')
    ap.add_argument('--minimo-tatico', type=float, default=0.5)
    ap.add_argument('--simular', action='store_true', help='só mostra o que copiaria')
    a = ap.parse_args()
    src = sqlite3.connect(f'file:{a.copia}?mode=ro', uri=True)
    cols = [r[1] for r in src.execute('PRAGMA table_info(arena_policy)')]
    keep = [c for c in cols if c not in ('id', 'chain_id', 'chain_round', 'parent_id')]
    rows = src.execute(f"SELECT {', '.join(keep)} FROM arena_policy WHERE champion LIKE 'class:%' ORDER BY champion, version").fetchall()
    dst = sqlite3.connect(a.banco, timeout=30)
    have = {(r[0], r[1]) for r in dst.execute("SELECT champion, created_at FROM arena_policy WHERE champion LIKE 'class:%'")}
    best = {}
    for r in rows:
        d = dict(zip(keep, r))
        good = bool(d['accepted']) or (d['better_vs_tactical'] or 0) > a.minimo_tatico
        print(f"{d['champion']:20} v{d['version']}  aceita={bool(d['accepted'])}  melhor que o Tático {100 * (d['better_vs_tactical'] or 0):.0f}%"
              f"  que o Roteiro {100 * (d['better_vs_script'] or 0):.0f}%  -> {'copia' if good else 'fica de fora'}")
        if good and (d['champion'], d['created_at']) not in have:
            best[d['champion']] = d
    if a.simular:
        print(f'{len(best)} IAs seriam copiadas.')
        return
    with dst:
        for d in best.values():
            dst.execute(f"INSERT INTO arena_policy ({', '.join(keep)}) VALUES ({', '.join('?' for _ in keep)})", [d[c] for c in keep])
    print(f'{len(best)} IAs copiadas para {a.banco}.')


if __name__ == '__main__':
    sys.exit(main())
