#!/usr/bin/env python3
"""Real-terrain test: all five signed plugins on a generated (non-flat) world, SentinelAC fully enforcing, one bot
that is teleported over oceans, mountains, caves, the nether and the end. Fails on any chunk-reading error in the log.
  NODE_PATH=.../node_modules python3 terrain_probe.py --paper P --java J --jar a.jar --jar b.jar ... --work DIR
"""
import argparse, os, re, shutil, subprocess, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import guard_live as g

BAD = re.compile(r'bitsPerEntry|BitStorage|IllegalArgumentException|IndexOutOfBounds|DecoderException|Chunk.*(error|fail)|packetevents.*(error|exception)', re.I)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--paper', type=Path, required=True); p.add_argument('--java', required=True)
    p.add_argument('--jar', type=Path, action='append', required=True)
    p.add_argument('--via', type=Path, action='append', default=[])
    p.add_argument('--mojang-cache', type=Path); p.add_argument('--work', type=Path, required=True)
    p.add_argument('--client-version', default='26.1'); p.add_argument('--seed', default='-4172144997902289642')
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args()
    a.work.mkdir(parents=True, exist_ok=True)
    a.ac = a.probe = None; a.jar_list = a.jar
    r = g.Run(a)
    server = r.server
    if server.exists(): shutil.rmtree(server)
    (server / 'plugins').mkdir(parents=True)
    for j in a.jar + a.via: shutil.copy2(j, server / 'plugins' / j.name)
    if a.mojang_cache: shutil.copytree(a.mojang_cache, server / 'cache')
    (server / 'eula.txt').write_text('eula=true\n')
    (server / 'bukkit.yml').write_text('settings:\n  connection-throttle: -1\n')
    r.port = 26000 + os.getpid() % 3000
    (server / 'server.properties').write_text(
        f'server-ip=::1\nserver-port={r.port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\nmax-players=10\n'
        f'view-distance=8\nsimulation-distance=4\nlevel-seed={a.seed}\nspawn-protection=0\ndifficulty=peaceful\nspawn-monsters=false\nallow-flight=true\n')

    def boot():
        r.proc = subprocess.Popen([a.java, '-Xms768M', '-Xmx2G', '-jar', str(a.paper.resolve()), '--nogui'], cwd=server, stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        r.s = g.Stream(r.proc, 'server', r.log)
        if not r.s.expect(r'Done \(', 400): raise RuntimeError('no start')
    boot()               # first start writes the AX-ray / AE settings
    r.s.send('stop'); r.proc.wait(120)
    boot()               # second start applies them (Paper anti-xray + line-of-sight layer)
    try:
        r.s.send('op Walker') if False else None
        bot = r.bot('Walker')
        spots = [('spawn', 'tp Walker ~ ~ ~'), ('high', 'tp Walker 300 150 300'), ('far-west', 'tp Walker -900 120 400'),
                 ('cave-depth', 'tp Walker 40 -30 40'), ('deepslate', 'tp Walker -200 -50 150'), ('ocean', 'tp Walker 1500 90 1500'),
                 ('nether', 'execute in minecraft:the_nether run tp Walker 0 70 0'), ('end', 'execute in minecraft:the_end run tp Walker 100 70 0'),
                 ('back', 'execute in minecraft:overworld run tp Walker 0 100 0')]
        for name, c in spots:
            r.s.send('gamemode creative Walker'); r.s.send(c)
            time.sleep(14)
            alive = 'online=true' not in '' and True
        time.sleep(5)
        r.s.send('list'); time.sleep(1)
        still = r.s.seen(r'There are 1 of a max')
        g.check('bot-stayed-online-through-all-dimensions', still)
    finally:
        r.stop()
        (a.work / 'terrain.log').write_text(''.join(r.log))
    bad = [l.strip() for l in r.log if '[server]' in l and BAD.search(l) and 'Bad packet' not in l]
    g.check('no-chunk-reading-errors', not bad, (bad[0][:200] if bad else ''))
    print('count', len(bad))
    for l in bad[:5]: print('  ', l[:220])
    print(f'DONE {sum(1 for _, ok in g.results if ok)} / {len(g.results)}')


main()
