#!/usr/bin/env python3
"""Checks that a built plugin JAR only links against API that exists in another server/proxy version.

The plugin is compiled once against the oldest supported API; this proves it also runs against newer ones:
    python3 tools/api_compat.py --jar paper/target/SentinelGuard-*-paper.jar --classpath <api jar and dependencies>
Needs `javap` and a `java` new enough to load the classpath (JDK 25 for Paper 26.x / Velocity 4.x).
"""
import argparse, re, subprocess, sys, tempfile, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
# Only API owned by the platform is probed; the JDK and our own packages are not.
OWNERS = re.compile(r'^(org/bukkit|io/papermc|net/kyori|com/destroystokyo|net/md_5|org/spigotmc|com/velocitypowered)')
MEMBER = re.compile(r'= (Methodref|InterfaceMethodref|Fieldref)\s+#\d+\.#\d+\s+// +(\S+?)\.(\S+?):(\S+)')
CLASS = re.compile(r'= Class\s+#\d+\s+// +(\S+)')

def references(jar: Path):
    classes, members = set(), set()
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(jar) as z:
        files = []
        for name in z.namelist():
            # Shaded and relocated libraries are bundled, so only our own classes are probed.
            if name.endswith('.class') and name.startswith('uz/sentinel/guard/') and '/libs/' not in name:
                files.append(str(Path(z.extract(name, tmp))))
        for i in range(0, len(files), 60):
            out = subprocess.run(['javap', '-v', '-p', *files[i:i + 60]], capture_output=True, text=True, check=True).stdout
            for kind, owner, name, desc in MEMBER.findall(out):
                if OWNERS.match(owner):
                    members.add(('F' if kind == 'Fieldref' else 'M', owner, name.strip('"'), desc))
            classes.update(c for c in CLASS.findall(out) if OWNERS.match(c))
    return classes, members

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jar', type=Path, required=True)
    ap.add_argument('--classpath', required=True, help='API jar plus its dependencies, ":"-separated')
    ap.add_argument('--java', default='java')
    args = ap.parse_args()
    classes, members = references(args.jar)
    if not classes:
        sys.exit('no platform API references found; is this the right JAR?')
    with tempfile.NamedTemporaryFile('w', suffix='.tsv', delete=False) as f:
        for c in sorted(classes):
            f.write(f'C\t{c}\n')
        for kind, owner, name, desc in sorted(members):
            f.write(f'{kind}\t{owner}\t{name}\t{desc}\n')
    print(f'{args.jar.name}: {len(classes)} classes, {len(members)} members')
    sys.exit(subprocess.run([args.java, str(ROOT / 'ApiCompat.java'), f.name, args.classpath]).returncode)

if __name__ == '__main__':
    main()
