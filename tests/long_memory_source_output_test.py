"""Output isolation and Java launch regression; no external processes are started."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import long_memory_source_e2e as fixture


class LongSourceOutputTests(unittest.TestCase):
    def test_java_uses_path_when_optional_override_is_absent(self):
        env = {'PATH': '/synthetic/jdk/bin', 'PLATFORM_BIND': '127.0.0.1'}
        log = object()
        with patch.object(fixture.subprocess, 'Popen') as launch:
            result = fixture.launch_server(env, Path('/synthetic/runtime.jar'), log)
        launch.assert_called_once_with(
            ['java', '-jar', '/synthetic/runtime.jar'], env=env,
            stdout=log, stderr=fixture.subprocess.STDOUT)
        self.assertIs(result, launch.return_value)
        self.assertNotIn('JAVA_BIN', env)

    def test_java_explicit_override_is_preserved_without_jvm_flags(self):
        env = {'JAVA_BIN': '/synthetic/custom-jdk/bin/java', 'PATH': '/synthetic/bin'}
        log = object()
        with patch.object(fixture.subprocess, 'Popen') as launch:
            fixture.launch_server(env, Path('/synthetic/runtime.jar'), log)
        launch.assert_called_once_with(
            ['/synthetic/custom-jdk/bin/java', '-jar', '/synthetic/runtime.jar'],
            env=env, stdout=log, stderr=fixture.subprocess.STDOUT)

    def test_unavailable_explicit_java_is_not_silently_replaced(self):
        env = {'JAVA_BIN': '/synthetic/missing-java'}
        with patch.object(fixture.subprocess, 'Popen', side_effect=FileNotFoundError) as launch:
            with self.assertRaises(FileNotFoundError):
                fixture.launch_server(env, Path('/synthetic/runtime.jar'), object())
        self.assertEqual(launch.call_args.args[0][0], env['JAVA_BIN'])

    def test_default_runs_are_distinct_and_preserve_previous_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            base = root / 'server/target/long-memory-source-e2e'; base.mkdir(parents=True)
            sentinel = base / 'server.log'; sentinel.write_bytes(b'prior synthetic run evidence')
            with patch.object(fixture, 'ROOT', root):
                first = fixture.select_output(None)
                (first / 'server.log').write_bytes(b'first new run')
                second = fixture.select_output(None)
            self.assertNotEqual(first, second)
            self.assertEqual(first.parent, base)
            self.assertEqual(second.parent, base)
            self.assertTrue(second.is_dir())
            self.assertEqual(sentinel.read_bytes(), b'prior synthetic run evidence')
            self.assertEqual((first / 'server.log').read_bytes(), b'first new run')
            self.assertFalse((second / 'server.log').exists())

    def test_explicit_output_keeps_exact_path_and_existing_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            requested = Path(tmp) / 'chosen'; requested.mkdir()
            sentinel = requested / 'server.log'; sentinel.write_bytes(b'preserve')
            self.assertEqual(fixture.select_output(requested), requested.resolve())
            self.assertEqual(sentinel.read_bytes(), b'preserve')

    def test_allocation_error_does_not_remove_existing_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            base = root / 'server/target/long-memory-source-e2e'; base.parent.mkdir(parents=True)
            base.write_bytes(b'synthetic conflicting file')
            with patch.object(fixture, 'ROOT', root), self.assertRaises(FileExistsError):
                fixture.select_output(None)
            self.assertEqual(base.read_bytes(), b'synthetic conflicting file')


if __name__ == '__main__':
    unittest.main()
