#!/usr/bin/env python3
"""Dense-chunk test: sections filled with hundreds of block types (global palettes), then a real bot joins and
teleports around while SentinelAC reads every chunk packet. Counts chunk-reading errors in the server log.
  python3 stress_probe.py --paper P --java J --jar ... --work DIR [--variety 1100]
"""
import argparse, os, re, shutil, subprocess, sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import guard_live as g

BAD = re.compile(r'bitsPerEntry|BitStorage|VarInt is too large|IllegalArgumentException|IndexOutOfBounds|DecoderException|caught an unhandled exception', re.I)

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--paper', type=Path, required=True); p.add_argument('--java', required=True)
    p.add_argument('--jar', type=Path, action='append', required=True)
    p.add_argument('--via', type=Path, action='append', default=[])
    p.add_argument('--mojang-cache', type=Path); p.add_argument('--work', type=Path, required=True)
    p.add_argument('--client-version', default='26.1'); p.add_argument('--variety', default='1100')
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args(); a.work.mkdir(parents=True, exist_ok=True); a.ac = a.probe = None
    r = g.Run(a); server = r.server
    if server.exists(): shutil.rmtree(server)
    (server / 'plugins').mkdir(parents=True)
    for j in a.jar + a.via: shutil.copy2(j, server / 'plugins' / j.name)
    if a.mojang_cache: shutil.copytree(a.mojang_cache, server / 'cache')
    (server / 'eula.txt').write_text('eula=true\n'); (server / 'bukkit.yml').write_text('settings:\n  connection-throttle: -1\n')
    r.port = 27000 + os.getpid() % 2000
    (server / 'server.properties').write_text(
        f'server-ip=::1\nserver-port={r.port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\nmax-players=10\n'
        'view-distance=6\nsimulation-distance=4\nlevel-type=minecraft:flat\nspawn-protection=0\ndifficulty=peaceful\nspawn-monsters=false\nallow-flight=true\n')
    def boot():
        r.proc = subprocess.Popen([a.java, '-Xms768M', '-Xmx3G', '-jar', str(a.paper.resolve()), '--nogui'], cwd=server, stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        r.s = g.Stream(r.proc, 'server', r.log)
        if not r.s.expect(r'Done \(', 400): raise RuntimeError('no start')
    boot(); r.s.send('stop'); r.proc.wait(120); boot()   # second start: AX-ray settings applied
    try:
        r.s.send(f'stress 5 -60 40 {a.variety}')
        placed = r.s.expect(r'STRESS placed (\d+) blocks from (\d+) block types', 240)
        g.check('dense-chunks-built', placed is not None, placed.group(0) if placed else '')
        for round_ in range(4):
            bot = r.bot('Walker')
            for spot in ('0 100 0', '40 30 40', '-60 20 -50', '70 60 10'):
                r.s.send('gamemode creative Walker'); r.s.send(f'tp Walker {spot}'); time.sleep(5)
            bot.send('quit'); r.gone('Walker', 15); time.sleep(2)
    finally:
        r.stop(); (a.work / 'stress.log').write_text(''.join(r.log))
    bad = [l.strip() for l in r.log if '[server]' in l and BAD.search(l)]
    g.check('no-chunk-reading-errors', not bad, f'{len(bad)} errors' if bad else '')
    for l in bad[:4]: print('  ', l[:200])
    print(f'DONE {sum(1 for _, ok in g.results if ok)} / {len(g.results)}')
main()
