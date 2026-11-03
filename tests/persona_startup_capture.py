"""Allowlisted synthetic startup observations; never persist raw logs or environment data."""
from datetime import datetime
from pathlib import Path
import re

MAX_STARTUP_BYTES = 256 * 1024
_TIMESTAMP = re.compile(r'^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2}))\s')
_NUMBER = r'([0-9]{1,6}(?:\.[0-9]{1,9})?)'
_STARTED = re.compile(r'^Started Application in ' + _NUMBER + r' seconds \(process running for ' + _NUMBER + r'\)$')
_V005_CODES = frozenset('migration_v005_' + suffix for suffix in (
    'script_checksum_mismatch', 'script_unavailable', 'unsupported_context', 'history_mismatch',
    'non_prefix_schema', 'completed_schema_mismatch', 'column_mismatch', 'table_mismatch', 'constraint_mismatch'))
_CODE = re.compile(r'(?<![A-Za-z0-9_])(' + '|'.join(sorted(_V005_CODES)) + r')(?![A-Za-z0-9_])')
_PHASES = (
    ('Application', 'Starting Application ', 'first_application_log'),
    ('TomcatWebServer', 'Tomcat initialized with port ', 'tomcat_initialized'),
    ('ServletWebServerApplicationContext', 'Root WebApplicationContext: initialization completed', 'web_context_initialized'),
    ('HikariDataSource', 'HikariPool-1 - Starting...', 'datasource_start'),
    ('HikariPool', 'HikariPool-1 - Added connection ', 'datasource_connected'),
    ('HikariDataSource', 'HikariPool-1 - Start completed.', 'datasource_initialized'),
    ('WelcomePageHandlerMapping', 'Adding welcome page:', 'welcome_mapping'),
    ('TomcatWebServer', 'Tomcat started on port ', 'tomcat_started'),
    ('Application', 'Started Application in ', 'application_started'),
)


def _read_startup_prefix(path, offset):
    # Only the caller-created synthetic fixture console log is eligible. Do not follow links,
    # search for missing logs, or fall back to a database/another location.
    path = Path(path)
    if (path.name != 'runtime.log' or not path.parent.name.startswith('persona-e2e-')
            or path.is_symlink() or any(parent.is_symlink() for parent in path.absolute().parents)):
        return '', 'REFUSED_PATH'
    if not isinstance(offset, int) or offset < 0:
        return '', 'REFUSED_OFFSET'
    try:
        with path.open('rb') as stream:
            stream.seek(0, 2)
            if offset > stream.tell():
                return '', 'LOG_TRUNCATED_OR_REPLACED'
            stream.seek(offset)
            raw = stream.read(MAX_STARTUP_BYTES + 1)
    except OSError:
        return '', 'UNAVAILABLE'
    status = 'TRUNCATED_PREFIX' if len(raw) > MAX_STARTUP_BYTES else 'READ_PREFIX'
    return raw[:MAX_STARTUP_BYTES].decode('utf-8', errors='replace'), status


def capture_startup(path, *, offset, started_wall, elapsed, exit_code, healthy, attempt,
                    previous_api_stopped, synthetic=False):
    row = {'attempt': attempt, 'healthy': healthy, 'elapsed_seconds': round(elapsed, 3),
           'exit_code': exit_code}
    if not synthetic:
        # Real-mode logs are never opened, even if a caller accidentally invokes this helper.
        row['capture_status'] = 'DISABLED_REAL_MODE'
        return row
    row.update(capture_version=2, fixture_path_id='synthetic/persona-e2e/runtime-log',
               scenario='initial_start' if attempt == 1 else 'same_db_restart', deadline_seconds=30,
               previous_api_stopped_before_launch=previous_api_stopped, observed_before_cleanup=True,
               status='HEALTHY' if healthy else 'TIMEOUT_PROCESS_ALIVE' if exit_code is None else 'STARTUP_PROCESS_EXITED')
    text, row['capture_status'] = _read_startup_prefix(path, offset)
    phases, errors, failures = {}, set(), set()
    out_of_range = False
    for line in text.splitlines():
        errors.update(_CODE.findall(line))
        # Only fixed status markers are retained, never exception messages or SQL text.
        if 'Failed to execute SQL script' in line:
            failures.add('SQL_INITIALIZATION_FAILURE_OBSERVED')
        stamp = _TIMESTAMP.match(line)
        if not stamp:
            continue
        prefix, separator, message = line.partition(' : ')
        if not separator:
            continue
        logger = prefix.split()[-1]
        if logger.endswith('.SpringApplication') and message.startswith('Application run failed'):
            failures.add('APPLICATION_RUN_FAILURE_OBSERVED')
        if logger.endswith('.TomcatStarter') and message.startswith('Error starting Tomcat context.'):
            failures.add('WEB_CONTEXT_FAILURE_OBSERVED')
        try:
            relative = datetime.fromisoformat(stamp[1].replace('Z', '+00:00')).timestamp() - started_wall
        except ValueError:
            continue
        if not 0 <= relative <= elapsed + 2:
            out_of_range = True
            continue
        for suffix, marker, label in _PHASES:
            if logger.endswith('.' + suffix) and message.startswith(marker):
                phases.setdefault(label, round(relative, 3))
        if logger.endswith('.Application'):
            match = _STARTED.fullmatch(message)
            if match:
                application, process = map(float, match.groups())
                if 0 <= application <= 3600 and 0 <= process <= 3600:
                    row['jvm_reported_application_seconds'] = application
                    row['jvm_reported_process_seconds'] = process
    row['phases_seconds'] = phases
    row['fixed_error_codes'] = sorted(errors)
    row['failure_status_markers'] = sorted(failures)
    row['phase_timestamp_status'] = 'OUTSIDE_ATTEMPT_IGNORED' if out_of_range else 'WITHIN_ATTEMPT'
    intervals = {}
    for label, start, end in (
        ('first_log_to_datasource', 'first_application_log', 'datasource_start'),
        ('datasource_initialization', 'datasource_start', 'datasource_initialized'),
        ('datasource_ready_to_application', 'datasource_initialized', 'application_started'),
    ):
        if start in phases and end in phases and phases[end] >= phases[start]:
            intervals[label] = round(phases[end] - phases[start], 3)
    row['intervals_seconds'] = intervals
    return row


def record_cleanup(row, exit_code):
    """Add process state only; do not reread shutdown messages as original failure evidence."""
    row['exit_code_after_cleanup'] = exit_code
    row['cleanup_completed'] = exit_code is not None


def close_startup_fixture(stop, log, gateway):
    """Preserve failure propagation while always releasing caller-owned fixture resources."""
    try:
        stop()
    finally:
        try:
            log.close()
        finally:
            try:
                gateway.shutdown()
            finally:
                gateway.server_close()
