from pathlib import Path
import xml.etree.ElementTree as E
r=Path(__file__).resolve().parents[1]
s=(r/'paper/src/main/resources/plugin.yml').read_text()
assert 'authors: [ azicoo ]' in s
assert '  sg:' in s and 'aliases: [ sentinelguard, sguard, sentinelg ]' in s
assert '  sus:' in s and 'softdepend: [ SentinelAC ]' in s
assert '\ndepend:' not in s
assert 'main: uz.sentinel.guard.paper.GuardPlugin' in s
assert (r/'paper/src/main/java/uz/sentinel/guard/paper/GuardPlugin.java').is_file()
for lib in ('com.zaxxer:HikariCP:', 'org.mariadb.jdbc:mariadb-java-client:', 'redis.clients:jedis:'):
    assert lib in s, lib
for p in r.rglob('pom.xml'):
    if 'node_modules' not in p.parts and 'target' not in p.parts: E.parse(p)
for p in list(r.glob('*/src/**/*.java')) + list(r.glob('*/src/**/*.yml')):
    assert 'me.azicoo' not in p.read_text(), f'legacy package reference in {p}'
# Network moderation must never block the server thread on JDBC.
for p in (r/'paper/src/main/java').rglob('*.java'):
    text = p.read_text()
    if 'network/' in p.as_posix(): continue
    assert 'java.sql' not in text, f'JDBC outside the network package: {p}'
print('Author, commands, optional dependency, package, library and POM checks passed')
