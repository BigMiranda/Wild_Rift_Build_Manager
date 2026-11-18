"""Rodada 20: tools/importar_meta.py sobre um cache sintético (sem rede): python -m unittest tools/test_importar_meta.py"""
import datetime
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


class ImportarMetaTest(unittest.TestCase):
    def test_offline_cache_to_meta(self):
        with tempfile.TemporaryDirectory() as d:
            cache = os.path.join(d, 'cache')
            os.makedirs(cache)
            day = datetime.date.today().isoformat()
            rank = {'result': 0, 'data': {'0': {
                '1': [{'hero_id': '10001', 'appear_rate': '0.10', 'forbid_rate': '0.02', 'win_rate': '0.53', 'dtstatdate': '20261117'},
                      {'hero_id': '10002', 'appear_rate': '0.05', 'forbid_rate': '0.01', 'win_rate': '0.49', 'dtstatdate': '20261117'},
                      {'hero_id': '10099', 'appear_rate': '0.01', 'forbid_rate': '0', 'win_rate': '0.5', 'dtstatdate': '20261117'}],
            }}}
            heroes = {'heroList': {
                '10001': {'heroId': '10001', 'name': '盖伦', 'alias': 'gailun', 'lane': '单人路'},
                '10002': {'heroId': '10002', 'name': '亚托克斯', 'alias': 'yatuokesi', 'lane': '单人路;打野'},
                '10099': {'heroId': '10099', 'name': '某', 'alias': 'mou', 'lane': '中路'},
            }}
            with open(os.path.join(cache, f'{day}-rank.json'), 'w', encoding='utf-8') as f:
                json.dump(rank, f, ensure_ascii=False)
            with open(os.path.join(cache, f'{day}-hero_list.js'), 'w', encoding='utf-8') as f:
                f.write('var x = ' + json.dumps(heroes, ensure_ascii=False) + ';')
            out = os.path.join(d, 'meta.json')
            r = subprocess.run([sys.executable, os.path.join(HERE, 'importar_meta.py'), '--offline', '--cache', cache,
                                '--saida', out, '--herois', os.path.join(HERE, 'meta_herois.json')],
                               capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(r.returncode, 0, r.stderr)
            meta = json.load(open(out, encoding='utf-8'))
            self.assertEqual(meta['data'], '2026-11-17')
            garen = meta['elos']['fonte_0']['Garen']['Barão']
            self.assertAlmostEqual(garen['presenca'], 0.10)
            self.assertAlmostEqual(garen['vitoria'], 0.53)
            self.assertIn('Aatrox', meta['elos']['fonte_0'])
            self.assertEqual(meta['sem_correspondencia'], 1)
            self.assertEqual(meta['posicoes']['1']['rota'], 'Barão')


if __name__ == '__main__':
    unittest.main()
