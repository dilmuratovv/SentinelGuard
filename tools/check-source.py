from pathlib import Path
import xml.etree.ElementTree as E
r=Path(__file__).resolve().parents[1]
s=(r/'paper/src/main/resources/plugin.yml').read_text()
assert 'authors: [ azicoo ]' in s
assert '  sg:' in s and 'aliases: [ sentinelguard, sguard, sentinelg ]' in s
assert '  sus:' in s and 'softdepend: [ SentinelAC ]' in s
assert '\ndepend:' not in s
for p in r.rglob('pom.xml'): E.parse(p)
print('Author, commands, optional dependency, and POM XML checks passed')
