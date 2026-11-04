#!/usr/bin/env python3
"""Sets the project version everywhere it is declared: python3 tools/set_version.py 2.0.0"""
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
version = sys.argv[1] if len(sys.argv) == 2 else sys.exit('usage: set_version.py <version>')
if not re.fullmatch(r'\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?', version):
    sys.exit('invalid version: ' + version)
POM_VERSION = re.compile(r'(<artifactId>sentinelguard-parent</artifactId><version>)[^<]+(</version>)')
for pom in [root / 'pom.xml', *root.glob('*/pom.xml')]:
    text, n = POM_VERSION.subn(rf'\g<1>{version}\g<2>', pom.read_text())
    if n != 1:
        sys.exit(f'{pom}: expected exactly one parent version, found {n}')
    pom.write_text(text)
# Module poms depend on common with ${project.version}, so only the Velocity annotation remains.
velocity = root / 'velocity/src/main/java/uz/sentinel/guard/velocity/SentinelGuardVelocity.java'
text, n = re.subn(r'(@Plugin\(id="sentinelguard", name="SentinelGuard", version=")[^"]+(")', rf'\g<1>{version}\g<2>', velocity.read_text())
if n != 1:
    sys.exit('Velocity @Plugin version not found')
velocity.write_text(text)
print('version set to', version)
