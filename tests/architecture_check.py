#!/usr/bin/env python3
"""Fast source boundary guard. Reads only tracked-style source directories, never runtime data."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'server/src/main/java/internal/distilly'
failures = []
count = 0
for path in JAVA.rglob('*.java'):
    count += 1
    source = path.read_text()
    relative = path.relative_to(JAVA)
    imports = re.findall(r'^import\s+(?:static\s+)?([\w.]+)', source, re.M)
    layers = set(relative.parts)
    for dependency in imports:
        if 'controller' in layers and ('.repository.' in dependency or 'Jdbc' in dependency):
            failures.append(f'{relative}: controller directly depends on persistence: {dependency}')
        if 'controller' not in layers and '.controller.' in dependency:
            failures.append(f'{relative}: non-controller depends on controller: {dependency}')
        if 'domain' in layers and any(x in dependency for x in ('.repository.', '.service.', 'org.springframework.jdbc', 'jakarta.servlet')):
            failures.append(f'{relative}: domain depends on infrastructure: {dependency}')
    if ('controller' in layers or 'service' in layers) and re.search(r'"\s*(SELECT\s|INSERT\s+INTO\s|UPDATE\s+\w+\s+SET|DELETE\s+FROM\s)', source, re.I):
        failures.append(f'{relative}: SQL belongs in repository')

for name in ('Api.java', 'PlatformService.java', 'PersonaApi.java'):
    if (JAVA / name).exists():
        failures.append(f'{name}: centralized legacy implementation must not return')

STATIC = ROOT / 'server/src/main/resources/static'
for name in ('index.html', 'knowledge.html'):
    source = (STATIC / name).read_text()
    if source.count('href="/design-system.css?') != 1:
        failures.append(f'{name}: must load the shared design system exactly once')
    if re.search(r'<div[^>]+class="modal-overlay"', source):
        failures.append(f'{name}: use native dialog instead of overlay div')

if failures:
    print('\n'.join(failures), file=sys.stderr)
    sys.exit(1)
print(f'PASS: {count} Java sources, layer boundaries, shared styles and native modal wrappers')
