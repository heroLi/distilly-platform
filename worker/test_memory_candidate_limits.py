"""Exposed synthetic extraction boundaries; actual mock, no invented model output."""
from types import SimpleNamespace
import unittest
import persona


class MemoryCandidateLimitsTest(unittest.TestCase):
    def candidate(self, message, enabled=True):
        return persona.chat(SimpleNamespace(mode='mock'), {
            'message': message, 'memory_enabled': enabled, 'memories': [], 'knowledge': []})['memory_candidates']

    def test_mock_quote_fits_java_utf16_limit_without_changing_candidate_body(self):
        for message in ('请记住🙂：' + '合成填充' * 75 + '但从未执行。',
                        '请记住：' + '🙂' * 155 + '仅在预约成功时参加。'):
            with self.subTest(kind='supplementary_unicode'):
                candidate, = self.candidate(message)
                self.assertEqual(candidate['body'], message)
                self.assertIn(candidate['quote'], message)
                self.assertLessEqual(len(candidate['quote'].encode('utf-16-le')) // 2, 300)

    def test_negation_condition_time_and_internal_spacing_are_not_automatically_rewritten(self):
        for suffix in ('但从未执行。', '仅在预约成功时参加。', '仅在2027年3月有效。',
                       '\r\n但不代表已执行。', '仅\u3000在预约成功时参加。'):
            message = '请记住：合成青砾展曾列入计划。' + suffix
            candidate, = self.candidate(message)
            self.assertEqual(candidate['body'], message)
            self.assertIn(suffix, candidate['body'])

    def test_quote_prefix_is_not_used_to_truncate_candidate_body(self):
        suffix = '仅在预约成功时有效。'
        for length in (1000, 1001):
            message = '请记住：' + '甲' * (length - len('请记住：') - len(suffix)) + suffix
            candidate, = self.candidate(message)
            self.assertEqual(len(candidate['body']), length)
            self.assertTrue(candidate['body'].endswith(suffix))
            self.assertNotIn(suffix, candidate['quote'])

    def test_disabled_or_unrequested_memory_stays_empty(self):
        self.assertEqual(self.candidate('请记住：合成记录。', False), [])
        self.assertEqual(self.candidate('合成青砾展从未参加。'), [])

    def test_quote_prefix_stops_before_supplementary_character_that_cannot_fit(self):
        message = '请记住：' + '甲' * 295 + '🙂仅在预约成功时参加。'
        candidate, = self.candidate(message)
        self.assertEqual(candidate['quote'], message[:299])
        self.assertEqual(candidate['body'], message)
        self.assertEqual(persona._utf16_units('甲🙂乙'), 4)
        self.assertEqual(persona._utf16_units(candidate['quote']), 299)


if __name__ == '__main__':
    unittest.main()
