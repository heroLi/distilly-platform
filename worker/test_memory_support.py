"""Exposed synthetic regressions: candidate identity is not semantic support."""
import json
from pathlib import Path
import unittest
from unittest.mock import Mock
import persona
from worker import Model


class MemorySupportBoundaryTests(unittest.TestCase):
    def exposed_cases(self):
        data = json.loads((Path(__file__).resolve().parents[1] / 'tests/fixtures/memory_m0_dev_v1.json').read_text())
        rows = []
        for case in data['cases']:
            if case['category'] == 'strict_support':
                source = next(row for row in data['memories'] if row.get('family_id') == case['family_id']
                              and row['scope'] == 'self' and row['status'] == 'CONFIRMED')
                rows.append((case['id'], case['query'], source['body']))
        return rows

    def payload(self, query, body):
        return {'message': query, 'memory_enabled': False, 'history': [], 'knowledge': [],
                'experiences': [], 'memories': [{'id': 'synthetic-memory', 'body': body,
                                                'origin': 'confirmed_interaction'}]}

    def reference_only(self, query, body):
        result = persona.chat(Model(mode='mock'), self.payload(query, body))
        self.assertTrue(body in result['answer'], 'reference_body_preserved')
        self.assertTrue('相关不等于支持' in result['answer'], 'relevance_not_support_notice')
        self.assertTrue('模拟模式不判断' in result['answer'], 'mock_not_semantic_verifier_notice')
        self.assertFalse('我记得你确认过' in result['answer'], 'no_automatic_support_preface')
        self.assertEqual(result['memory_citations'], [])
        return result

    def test_three_exposed_strict_negatives_are_not_mock_support_assertions(self):
        cases = self.exposed_cases()
        self.assertEqual(len(cases), 3)
        for name, query, body in cases:
            with self.subTest(case=name):
                self.reference_only(query, body)

    def test_missing_condition_reference_is_not_silently_filled(self):
        self.reference_only('绒石飞轮在哪天使用雾陶外壳？', '合成回归：绒石飞轮使用雾陶外壳。')

    def test_explicit_negative_source_is_preserved_without_affirmative_inference(self):
        body = '合成回归：绒石飞轮不使用雾陶外壳。'
        self.reference_only('绒石飞轮使用雾陶外壳吗？', body)

    def test_legitimate_answerable_source_stays_visible_as_reference(self):
        body = '合成回归：绒石飞轮的外壳材质为雾陶。'
        self.reference_only('绒石飞轮的外壳材质是什么？', body)

    def test_empty_recall_does_not_invent_a_reference(self):
        result = persona.chat(Model(mode='mock'), {'message': '合成中性交流', 'memory_enabled': False, 'memories': []})
        self.assertNotIn('记忆原文', result['answer'])
        self.assertEqual(result['memory_citations'], [])

    def test_valid_id_validation_is_not_an_entailment_verifier(self):
        # Characterizes an unresolved boundary, NOT a successful factual answer.
        for name, query, body in self.exposed_cases():
            with self.subTest(case=name):
                model = Mock(mode='gateway')
                model.call.return_value = {'answer': '已确认：' + query + '。', 'memory_candidates': [],
                                           'memory_citations': ['synthetic-memory']}
                result = persona.chat(model, self.payload(query, body))
                self.assertEqual(result['answer'], model.call.return_value['answer'])
                self.assertEqual(result['memory_citations'], ['synthetic-memory'])

    def test_prompt_distinguishes_candidate_conditions_negation_and_support(self):
        self.assertTrue('召回仅提供候选' in persona.CHAT_SYSTEM, 'candidate_contract')
        self.assertTrue('明确否定' in persona.CHAT_SYSTEM, 'negation_contract')
        self.assertTrue('条件缺失' in persona.CHAT_SYSTEM, 'missing_condition_contract')
        self.assertTrue('ID 有效不等于' in persona.CHAT_SYSTEM, 'identity_not_support_contract')
        self.assertLessEqual(len(persona.CHAT_SYSTEM.encode()), 8192)


if __name__ == '__main__':
    unittest.main()
