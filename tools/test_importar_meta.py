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

    def test_pinyin_matches_a_variant_name(self):
        """Rodada 20.b: a hero whose id and Chinese name are not in the map matches by its alias against the map's pinyin."""
        sys.path.insert(0, HERE)
        import importar_meta as im
        heroes = {
            '10001': {'name': '盖伦', 'alias': 'gailun'},
            '10002': {'name': '亚托克斯', 'alias': 'yatuokesi'},
            '10050': {'name': '暗裔剑魔', 'alias': 'yatuokesi'},
        }
        self.assertEqual(im.splits('yatuokesi', 4), [['ya', 'tuo', 'ke', 'si']])
        table = im.learn_pinyin(heroes)
        self.assertEqual(table['托'], 'tuo')
        self.assertEqual(im.pinyin_of('亚托克斯', table), 'yatuokesi')
        self.assertIsNone(im.pinyin_of('某', table), 'an unknown character is not guessed')
        with tempfile.TemporaryDirectory() as d:
            cache = os.path.join(d, 'cache')
            os.makedirs(cache)
            day = datetime.date.today().isoformat()
            rank = {'result': 0, 'data': {'0': {'1': [
                {'hero_id': '10050', 'appear_rate': '0.04', 'forbid_rate': '0.0', 'win_rate': '0.51', 'dtstatdate': '20261119'}]}}}
            with open(os.path.join(cache, f'{day}-rank.json'), 'w', encoding='utf-8') as f:
                json.dump(rank, f, ensure_ascii=False)
            with open(os.path.join(cache, f'{day}-hero_list.js'), 'w', encoding='utf-8') as f:
                json.dump({'heroList': {k: dict(v, lane='单人路') for k, v in heroes.items()}}, f, ensure_ascii=False)
            herois = os.path.join(d, 'herois.json')
            with open(herois, 'w', encoding='utf-8') as f:
                json.dump({'亚托克斯': 'Aatrox'}, f, ensure_ascii=False)
            out = os.path.join(d, 'meta.json')
            r = subprocess.run([sys.executable, os.path.join(HERE, 'importar_meta.py'), '--offline', '--cache', cache,
                                '--saida', out, '--herois', herois], capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(r.returncode, 0, r.stderr)
            meta = json.load(open(out, encoding='utf-8'))
            self.assertIn('Aatrox', meta['elos']['fonte_0'])
            self.assertEqual(meta['pelo_pinyin']['10050']['campeao'], 'Aatrox')
            self.assertEqual(meta['sem_correspondencia'], 0)


if __name__ == '__main__':
    unittest.main()
