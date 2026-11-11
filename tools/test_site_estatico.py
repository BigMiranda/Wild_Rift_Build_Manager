"""Testes do script do site estático (Rodada 11.b). Só a biblioteca padrão:

    python -m unittest tools/test_site_estatico.py

A regra do nome de arquivo usa os mesmos casos do teste do frontend (tools/caminhos_estaticos.json).
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import site_estatico as se  # noqa: E402

CASES = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'caminhos_estaticos.json')


class RegraDoNome(unittest.TestCase):
    def test_json_igual_ao_frontend(self):
        with open(CASES, encoding='utf-8') as f:
            cases = json.load(f)
        for url, file in cases['json']:
            self.assertEqual(se.arquivo_estatico(url), file, url)

    def test_icones_iguais_ao_frontend(self):
        with open(CASES, encoding='utf-8') as f:
            cases = json.load(f)
        for url, file in cases['binario']:
            self.assertEqual(se.arquivo_estatico(url, binario=True), file, url)


class Builds(unittest.TestCase):
    def test_minutos_chave(self):
        tl = {'steps': [{'minute': 3.0}, {'minute': 22.43}]}
        self.assertEqual(se.key_minutes(tl), [10.0, 15.0, 22.43])
        self.assertEqual(se.key_minutes({'steps': [{'minute': 12.0}]}), [10.0, 12.0])
        self.assertEqual(se.key_minutes({'steps': [{'minute': 15.4}]}), [10.0, 15.4])

    def test_kits_iguais_viram_uma_build_com_os_dois_rotulos(self):
        kit = {'order': [1, 2, 3], 'boots': 9, 'archetype': 'tanque'}
        latest = {'discovery': {'standard': kit, 'niches': {'tanque': dict(kit), 'dano': {'order': [4, 5]}},
                                'discoveries': [{'kit': dict(kit), 'text': 'x'}]}}
        out = se.champion_builds('ORNN', latest, [{'id': 7, 'name': 'Pública', 'steps': [{'itemId': 1}]}], 700)
        self.assertEqual([e['kind'] for e in out], ['padrao', 'nicho', 'publica'])
        self.assertEqual(out[0]['also'], ['Melhor em tanque', 'Descoberta 1'])
        self.assertEqual(out[0]['key'], 'ornn-padrao-padrao-robusta')
        self.assertEqual(out[2]['key'], 'ornn-publica-7')
        self.assertEqual(len({e['key'] for e in out}), len(out))
        # the boots come in when the order does not have them
        self.assertIn(9, [s['itemId'] for s in out[0]['build']['steps']])

    def test_melhores_do_ranking_secoes_e_runas(self):
        page = {'keystone': 'Ímpeto Gradual'}
        std = {'order': [1, 2, 3], 'runePage': page, 'runesFrom': 'busca'}
        latest = {'discovery': {'standard': std, 'ranked': [dict(std), {'order': [4, 5]}, {'order': [6, 7]}, {'order': [8]}],
                                'niches': {'x': {'order': [9, 10]}}, 'support': {'order': [11, 4]}}}
        out = se.champion_builds('ORNN', latest, [], 700)
        self.assertEqual([(e['kind'], e['section']) for e in out],
                         [('padrao', 'melhores'), ('suporte', 'melhores'), ('melhor', 'melhores'), ('melhor', 'melhores'),
                          ('nicho', 'novidade')])
        self.assertEqual(out[1]['label'], 'Melhor de suporte')
        self.assertEqual(out[0]['also'], ['Melhor 1'])
        # only the first three of the ranking; the ones without their own runes take the standard's
        self.assertNotIn([8], [[st['itemId'] for st in e['build']['steps']] for e in out])
        self.assertEqual(out[2]['runesFrom'], 'padrao')
        self.assertEqual(out[2]['build']['runePage'], page)
        self.assertEqual(out[0]['runesFrom'], 'busca')
        # no standard page and no public build: no runes, and it says so
        alone = se.champion_builds('ORNN', {'discovery': {'niches': {'x': {'order': [9]}}}}, [], 700)
        self.assertIsNone(alone[0]['runesFrom'])


class Manifesto(unittest.TestCase):
    def write(self, root, url, value):
        path = os.path.join(root, *se.arquivo_estatico(url).split('/'))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(value, f)

    def test_o_manifesto_bate_com_os_arquivos(self):
        with tempfile.TemporaryDirectory() as root:
            self.write(root, '/api/manifest', {'format': se.FORMAT, 'patch': '7.3d', 'fights': 0,
                                               'champions': [{'unitCode': "K'SANTE", 'builds': 1}]})
            self.write(root, "/api/site/campeao/K'SANTE", {'builds': [{'key': 'ksante-padrao-padrao-robusta'}]})
            self.write(root, '/api/site/build/ksante-padrao-padrao-robusta',
                       {'timelines': {'padrao': {'steps': [{'minute': 1}]}}, 'minutes': [10.0], 'at': {'10': {}}})
            self.assertEqual(se.verificar(root), [])
            # a build missing, or one minute without its numbers, is reported
            self.write(root, "/api/site/campeao/K'SANTE", {'builds': [{'key': 'ksante-padrao-padrao-robusta'}, {'key': 'falta'}]})
            self.write(root, '/api/site/build/ksante-padrao-padrao-robusta',
                       {'timelines': {'padrao': {'steps': [{'minute': 1}]}}, 'minutes': [10.0, 15.0], 'at': {'10': {}}})
            problems = se.verificar(root)
            self.assertTrue(any('2 build(s) no arquivo' in p for p in problems), problems)
            self.assertTrue(any('falta: sem o arquivo' in p for p in problems), problems)
            self.assertTrue(any('minuto-chave sem cálculo' in p for p in problems), problems)


if __name__ == '__main__':
    unittest.main()
