"""Privacy and attempt-isolation contracts for startup observations; synthetic strings only."""
import json
from datetime import datetime, timezone
from pathlib import Path
import tempfile
import unittest
from unittest import mock
from persona_startup_capture import capture_startup, record_cleanup, close_startup_fixture, MAX_STARTUP_BYTES

START = datetime(2026, 10, 27, 12, 0, tzinfo=timezone.utc).timestamp()


def line(seconds, logger, message, zone='Z'):
    return f'2026-10-27T12:00:{seconds:06.3f}{zone}  INFO 999 --- [main] {logger} : {message}\n'


class StartupCaptureTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='persona-e2e-')
        self.addCleanup(self.directory.cleanup)
        self.log = Path(self.directory.name) / 'runtime.log'
        self.log.write_text('')

    def capture(self, text=None, **kwargs):
        if text is not None:
            self.log.write_text(text)
        values = dict(offset=0, started_wall=START, elapsed=30, exit_code=None, healthy=False,
                      attempt=1, previous_api_stopped=True, synthetic=True)
        values.update(kwargs)
        return capture_startup(self.log, **values)

    def test_phase_numbers_and_intervals_without_paths(self):
        text = (line(2, 'internal.distilly.Application', 'Starting Application v0.1 using Java17 (/private/synthetic-canary/app.jar)')
                + line(4, 'com.zaxxer.hikari.HikariDataSource', 'HikariPool-1 - Starting...')
                + line(5, 'com.zaxxer.hikari.pool.HikariPool', 'HikariPool-1 - Added connection synthetic URL with canary')
                + line(6, 'com.zaxxer.hikari.HikariDataSource', 'HikariPool-1 - Start completed.')
                + line(8, 'o.s.b.w.embedded.tomcat.TomcatWebServer', 'Tomcat started on port 12345 (http)')
                + line(9, 'internal.distilly.Application', 'Started Application in 8.5 seconds (process running for 9.0)'))
        result = self.capture(text, healthy=True, elapsed=10)
        self.assertEqual('HEALTHY', result['status'])
        self.assertEqual(2.0, result['phases_seconds']['first_application_log'])
        self.assertEqual(2.0, result['intervals_seconds']['datasource_initialization'])
        self.assertEqual(3.0, result['intervals_seconds']['datasource_ready_to_application'])
        self.assertEqual(9.0, result['jvm_reported_process_seconds'])
        for forbidden in ('/private/', 'canary', self.directory.name, '12345', 'Java17'):
            self.assertNotIn(forbidden, json.dumps(result))
        self.assertEqual('synthetic/persona-e2e/runtime-log', result['fixture_path_id'])

    def test_new_attempt_offset_cannot_inherit_old_success_or_failure(self):
        old = line(1, 'internal.distilly.Application', 'Started Application in 1 seconds (process running for 1)') + 'migration_v005_column_mismatch\n'
        new = line(12, 'internal.distilly.Application', 'Starting Application v0.1')
        result = self.capture(old + new, offset=len(old.encode()), attempt=2, started_wall=START + 10)
        self.assertEqual({'first_application_log': 2.0}, result['phases_seconds'])
        self.assertEqual([], result['fixed_error_codes'])
        self.assertNotIn('jvm_reported_process_seconds', result)
        self.assertEqual('same_db_restart', result['scenario'])
        self.assertEqual('TIMEOUT_PROCESS_ALIVE', result['status'])

    def test_only_fixed_codes_and_statuses_survive_untrusted_text(self):
        text = ('MODEL_API_KEY=SYNTHETIC_PRIVATE_CANARY\n'
                'synthetic material body must never be retained\n'
                'Caused by: java.lang.IllegalStateException: migration_v005_column_mismatch /private/canary\n'
                'migration_v005_unknown_reason migration_v005_column_mismatch_suffix\n'
                'Failed to execute SQL script with synthetic SQL body\n'
                + line(8, 'o.s.boot.SpringApplication', 'Application run failed SYNTHETIC_PRIVATE_CANARY'))
        result = self.capture(text, exit_code=1)
        self.assertEqual(['migration_v005_column_mismatch'], result['fixed_error_codes'])
        self.assertEqual('STARTUP_PROCESS_EXITED', result['status'])
        self.assertEqual(['APPLICATION_RUN_FAILURE_OBSERVED', 'SQL_INITIALIZATION_FAILURE_OBSERVED'], result['failure_status_markers'])
        for forbidden in ('SYNTHETIC_PRIVATE_CANARY', 'MODEL_API_KEY', 'material body', '/private/', 'unknown_reason', 'SQL body'):
            self.assertNotIn(forbidden, json.dumps(result))

    def test_cleanup_does_not_reinterpret_shutdown_errors(self):
        result = self.capture(line(2, 'internal.distilly.Application', 'Starting Application v0.1'))
        with self.log.open('a') as stream:
            stream.write('migration_v005_constraint_mismatch\nFailed to execute SQL script\n')
        record_cleanup(result, 143)
        self.assertEqual([], result['fixed_error_codes'])
        self.assertEqual([], result['failure_status_markers'])
        self.assertEqual('TIMEOUT_PROCESS_ALIVE', result['status'])
        self.assertEqual(143, result['exit_code_after_cleanup'])
        self.assertTrue(result['cleanup_completed'])

    def test_real_mode_is_default_deny_without_any_log_read(self):
        with mock.patch('persona_startup_capture._read_startup_prefix', side_effect=AssertionError('must not read')):
            result = self.capture(synthetic=False)
        self.assertEqual('DISABLED_REAL_MODE', result['capture_status'])
        self.assertNotIn('phases_seconds', result)

    def test_links_and_non_fixture_files_are_refused_without_opening(self):
        other = Path(self.directory.name) / 'synthetic-database.mv.db'
        other.write_text('SYNTHETIC_PRIVATE_CANARY')
        self.log.unlink();self.log.symlink_to(other)
        with mock.patch.object(Path, 'open', side_effect=AssertionError('must not open')):
            result = self.capture()
        self.assertEqual('REFUSED_PATH', result['capture_status'])
        values = dict(offset=0, started_wall=START, elapsed=30, exit_code=None, healthy=False,
                      attempt=1, previous_api_stopped=True, synthetic=True)
        self.assertEqual('REFUSED_PATH', capture_startup(other, **values)['capture_status'])

    def test_capture_is_bounded_and_does_not_read_late_canaries(self):
        text = line(1, 'internal.distilly.Application', 'Starting Application v0.1')
        text += 'x' * MAX_STARTUP_BYTES + '\nmigration_v005_column_mismatch\n'
        result = self.capture(text)
        self.assertEqual('TRUNCATED_PREFIX', result['capture_status'])
        self.assertEqual([], result['fixed_error_codes'])

    def test_symlink_parent_is_refused_without_opening_target(self):
        with tempfile.TemporaryDirectory(prefix='capture-parent-') as directory:
            link = Path(directory) / 'persona-e2e-link'
            link.symlink_to(self.log.parent, target_is_directory=True)
            with mock.patch.object(Path, 'open', side_effect=AssertionError('must not open')):
                result = capture_startup(link / 'runtime.log', offset=0, started_wall=START,
                    elapsed=30, exit_code=None, healthy=False, attempt=1,
                    previous_api_stopped=True, synthetic=True)
            self.assertEqual('REFUSED_PATH', result['capture_status'])

    def test_rotated_offset_never_falls_back_to_old_log(self):
        result = self.capture('migration_v005_column_mismatch\n', offset=10000)
        self.assertEqual('LOG_TRUNCATED_OR_REPLACED', result['capture_status'])
        self.assertEqual([], result['fixed_error_codes'])
        self.assertEqual({}, result['phases_seconds'])

    def test_malformed_or_out_of_window_timestamps_do_not_invent_phases(self):
        text = line(2, 'internal.distilly.Application', 'Starting Application v0.1')
        text += line(45, 'com.zaxxer.hikari.HikariDataSource', 'HikariPool-1 - Start completed.')
        text += '2026-99-99T12:00:05Z INFO invalid.Application : Started Application in NaN seconds (process running for Infinity)\n'
        result = self.capture(text)
        self.assertEqual({'first_application_log': 2.0}, result['phases_seconds'])
        self.assertEqual('OUTSIDE_ATTEMPT_IGNORED', result['phase_timestamp_status'])
        self.assertNotIn('jvm_reported_process_seconds', result)

    def test_abbreviated_logger_and_explicit_timezone(self):
        text = '2026-10-27T20:00:03.000+08:00 INFO 999 --- [main] o.s.b.w.s.c.ServletWebServerApplicationContext : Root WebApplicationContext: initialization completed in 1000 ms\n'
        result = self.capture(text)
        self.assertEqual(3.0, result['phases_seconds']['web_context_initialized'])
        self.assertTrue(result['previous_api_stopped_before_launch'])

    def test_failed_evidence_write_still_closes_log_and_gateway(self):
        calls=[]
        def stop():
            calls.append('stop')
            raise OSError('synthetic evidence write failure')
        log=mock.Mock();log.close.side_effect=lambda:calls.append('log.close')
        gateway=mock.Mock();gateway.shutdown.side_effect=lambda:calls.append('gateway.shutdown')
        gateway.server_close.side_effect=lambda:calls.append('gateway.server_close')
        with self.assertRaisesRegex(OSError,'synthetic evidence write failure'):
            close_startup_fixture(stop,log,gateway)
        self.assertEqual(['stop','log.close','gateway.shutdown','gateway.server_close'],calls)

    def test_failed_log_close_still_closes_gateway(self):
        log=mock.Mock();log.close.side_effect=OSError('synthetic log close failure')
        gateway=mock.Mock()
        with self.assertRaisesRegex(OSError,'synthetic log close failure'):
            close_startup_fixture(mock.Mock(),log,gateway)
        gateway.shutdown.assert_called_once_with();gateway.server_close.assert_called_once_with()


if __name__ == '__main__':
    unittest.main()
