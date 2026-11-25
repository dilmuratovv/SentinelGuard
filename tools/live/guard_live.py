#!/usr/bin/env python3
"""Live test of SentinelGuard on a disposable localhost Paper server with real protocol bots.

  NODE_PATH=.../node_modules python3 tools/live/guard_live.py --paper paper.jar --java java25 \
      --jar SentinelGuard-paper.jar --probe GuardProbe.jar [--via ViaVersion.jar --via ViaBackwards.jar] \
      [--ac SentinelAC.jar] --client-version 26.1 --work /disk/dir --accept-eula

Covers: freeze (quit, timeout, anticheat-style kick, staff kick, return, vehicle/pearl/portal/glide, mid-air flight,
offline unfreeze, server stop), mute (chat, private and party/clan commands, signs, books, anvil rename, with an
unmuted control), duration typos, offline staff immunity, unknown-name bans, IPv6 /64 IP bans and shared-IP guard.
The server listens on ::1 only. Never point the bots at a real server.
"""
import argparse, os, queue, re, shutil, subprocess, sys, threading, time, zipfile
from pathlib import Path

BOT = Path(__file__).resolve().parent / 'guard_bot.cjs'
GRACE = 20
results = []


def check(name, ok, detail=''):
    results.append((name, ok))
    print(('PASS ' if ok else 'FAIL ') + name + (('  ' + detail) if detail else ''), flush=True)


class Stream:
    """Lines from a process, searchable from a mark."""
    def __init__(self, proc, tag, log):
        self.proc, self.lines, self.tag, self.log = proc, [], tag, log
        self.cond = threading.Condition()
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.proc.stdout:
            with self.cond:
                self.lines.append(line.rstrip('\n'))
                self.log.append(f'[{self.tag}] {line}')
                self.cond.notify_all()
        with self.cond:
            self.lines.append('<<EOF>>')
            self.cond.notify_all()

    def mark(self):
        with self.cond:
            return len(self.lines)

    def expect(self, pattern, timeout, since=0):
        rx = re.compile(pattern)
        deadline = time.monotonic() + timeout
        with self.cond:
            i = since
            while True:
                while i < len(self.lines):
                    m = rx.search(self.lines[i])
                    i += 1
                    if m:
                        return m
                left = deadline - time.monotonic()
                if left <= 0:
                    return None
                self.cond.wait(left)

    def seen(self, pattern, since=0):
        rx = re.compile(pattern)
        with self.cond:
            return any(rx.search(l) for l in self.lines[since:])

    def send(self, text):
        try:
            self.proc.stdin.write(text + '\n')
            self.proc.stdin.flush()
        except (BrokenPipeError, OSError):
            self.lines.append('<<DEAD>>')


class Run:
    def __init__(self, a):
        self.a, self.log, self.bots = a, [], {}
        self.server = a.work / 'server'

    # ------------------------------------------------------------------ server
    def prepare(self):
        libs = self.a.work / 'libraries-cache'
        if (self.server / 'libraries').exists():
            if libs.exists(): shutil.rmtree(libs)
            shutil.move(str(self.server / 'libraries'), libs)
        if self.server.exists():
            shutil.rmtree(self.server)
        self.server.mkdir(parents=True)
        if libs.exists():
            shutil.copytree(libs, self.server / 'libraries')
        plugins = self.server / 'plugins'
        plugins.mkdir(parents=True)
        for jar in [self.a.jar, self.a.probe] + self.a.via + ([self.a.ac] if self.a.ac else []):
            shutil.copy2(jar, plugins / jar.name)
        if self.a.mojang_cache:
            shutil.copytree(self.a.mojang_cache, self.server / 'cache')
        (self.server / 'eula.txt').write_text('eula=true\n')
        (self.server / 'bukkit.yml').write_text('settings:\n  connection-throttle: -1\n')
        self.port = 25000 + os.getpid() % 5000
        (self.server / 'server.properties').write_text(
            f'server-ip=::1\nserver-port={self.port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\n'
            'max-players=40\nview-distance=3\nsimulation-distance=3\ngenerate-structures=false\nlevel-type=minecraft:flat\n'
            'spawn-protection=0\ndifficulty=peaceful\nspawn-monsters=false\nallow-flight=false\n')
        # Guard config from the jar with short test timings
        with zipfile.ZipFile(self.a.jar) as z:
            cfg = z.read('config.yml').decode()
        cfg = cfg.replace('return-grace-seconds: 180', f'return-grace-seconds: {GRACE}')
        cfg = cfg.replace('shared-ip-accounts: 6', 'shared-ip-accounts: 3')
        assert f'return-grace-seconds: {GRACE}' in cfg and 'shared-ip-accounts: 3' in cfg
        (plugins / 'SentinelGuard').mkdir()
        (plugins / 'SentinelGuard' / 'config.yml').write_text(cfg)

    def start(self):
        self.proc = subprocess.Popen([self.a.java, '-Xms512M', '-Xmx1536M', '-jar', str(self.a.paper.resolve()), '--nogui'],
                                     cwd=self.server, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1)
        self.s = Stream(self.proc, 'server', self.log)
        if not self.s.expect(r'Done \(', 300):
            raise RuntimeError('server did not start')
        if not self.s.expect(r'PROBE READY', 5):
            raise RuntimeError('probe not loaded')

    def stop(self):
        for b in list(self.bots.values()):
            try: b.proc.kill()
            except Exception: pass
        self.bots.clear()
        try:
            self.s.send('stop'); self.proc.wait(90)
        except Exception:
            self.proc.kill()

    def cmd(self, line):
        self.s.send(line)

    def gp(self, args, pattern=None, timeout=10):
        """Runs a probe command and returns its output line."""
        m = self.s.mark()
        sub = args.split()[0]
        self.cmd('gp ' + args)
        hit = self.s.expect(r'GP ' + re.escape(sub) + r' (.*)', timeout, m)
        return hit.group(1) if hit else ''

    def state(self, name):
        return self.gp('state ' + name)

    # ------------------------------------------------------------------ bots
    def bot(self, name, wait=True):
        proc = subprocess.Popen(['node', str(BOT), '::1', str(self.port), name, self.a.client_version], env=os.environ.copy(),
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        b = Stream(proc, name, self.log)
        self.bots[name] = b
        if wait:
            if not b.expect(r'^POS ', 40):
                raise RuntimeError(name + ' did not join: ' + ' / '.join(b.lines[-5:]))
            time.sleep(1.0)
        return b

    def gone(self, name, timeout=40):
        b = self.bots.pop(name, None)
        if b is None:
            return True
        ok = b.expect(r'^END$|<<EOF>>', timeout) is not None
        try: b.proc.kill()
        except Exception: pass
        return ok


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--paper', type=Path, required=True)
    p.add_argument('--java', required=True)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--via', type=Path, action='append', default=[])
    p.add_argument('--ac', type=Path)
    p.add_argument('--mojang-cache', type=Path)
    p.add_argument('--work', type=Path, required=True)
    p.add_argument('--client-version', default='26.1')
    p.add_argument('--only', default='')
    p.add_argument('--accept-eula', action='store_true')
    a = p.parse_args()
    if not a.accept_eula:
        p.error('Accept the Minecraft EULA to run an isolated test server')
    a.work.mkdir(parents=True, exist_ok=True)
    r = Run(a)
    r.prepare()
    r.start()
    try:
        r.gp('grant Helper guard.ban'); r.gp('grant Helper guard.mute'); r.gp('grant Helper guard.ipban')
        r.gp('grant Helper guard.warn'); r.gp('grant Helper sus.admin.none'); r.gp('grant Mod guard.staff')
        r.cmd('gamerule doDaylightCycle false')
        scenarios = [('walk', walk), ('commands', commands), ('mute', mute), ('freeze', freeze), ('ac', ac), ('ip', ip_rules)]
        for name, fn in scenarios:
            if (a.only and name not in a.only.split(',')) or (name == 'walk' and 'walk' not in a.only):
                continue
            if name == 'ac' and not a.ac:
                continue
            print(f'== {name}', flush=True)
            try:
                fn(r)
            except Exception as e:  # a crashed scenario is a failure, the rest still run
                check(name + '-scenario-crashed', False, repr(e))
        if not a.only or 'restart' in a.only.split(','):
            print('== restart', flush=True)
            restart(r)
        r.cmd('gp tick')
    finally:
        r.stop()
        (a.work / 'live.log').write_text(''.join(r.log))
    errors = [l for l in r.log if '[server]' in l and re.search(r'(ERROR\]|Exception)', l) and 'SentinelGuard' in l]
    check('no-guard-errors-in-log', not errors, errors[0].strip() if errors else '')
    passed = sum(1 for _, ok in results if ok)
    print(f'DONE {passed} / {len(results)}')
    return 0 if passed == len(results) else 1


# ---------------------------------------------------------------------- scenarios

def commands(r):
    helper = r.bot('Helper')
    victim = r.bot('Victim')
    m = helper.mark()
    helper.send('cmd ban Victim 1dd Cheating')
    check('ban-typo-is-rejected', helper.expect(r"Noto'g'ri vaqt: 1dd", 8, m) is not None)
    time.sleep(1)
    check('ban-typo-creates-no-ban', 'banned=false' in r.state('Victim'))
    m = victim.mark()
    helper.send('cmd ban Victim 2d Cheating hard')
    check('valid-ban-kicks', victim.expect(r'^KICK ', 10, m) is not None)
    st = r.state('Victim')
    check('valid-ban-duration-and-reason', 'banned=true' in st and 'banDuration=172800000' in st and 'Cheating hard' in st, st)
    r.gone('Victim')
    r.cmd('unban Victim'); time.sleep(1)
    m = helper.mark()
    helper.send('cmd mute Victim 1.5h spam')
    check('mute-typo-is-rejected', helper.expect(r"Noto'g'ri vaqt: 1\.5h", 8, m) is not None)

    # offline staff stay protected
    mod = r.bot('Mod')
    mod.send('quit'); r.gone('Mod'); time.sleep(1.5)
    check('offline-staff-registered', 'immune=true' in r.gp('staff Mod'))
    m = helper.mark()
    helper.send('cmd ban Mod 1d abuse')
    check('offline-staff-cannot-be-banned', helper.expect(r'Admin/Staff himoyasi', 8, m) is not None)
    check('offline-staff-ban-not-created', 'banned=false' in r.state('Mod'))
    check('staff-registry-on-disk', (r.server / 'plugins' / 'SentinelGuard' / 'staff.txt').exists())

    # a name never seen: resolved off the main thread, then banned (offline-mode server: offline UUID)
    m = r.s.mark()
    helper.send('cmd ban NeverSeenBefore 3d alt')
    time.sleep(2.5)
    check('unknown-name-ban-created', 'banned=true' in r.state('NeverSeenBefore'))
    r.cmd('unban NeverSeenBefore')
    helper.send('quit'); r.gone('Helper')


def mute(r):
    talker = r.bot('Talker')
    r.cmd('mute Talker 1h spam'); time.sleep(1.5)
    for label, line in [('chat', 'chat hello everyone'), ('msg', 'cmd msg Victim hi'), ('party-chat', 'cmd party chat hi'),
                        ('pc', 'cmd pc hi'), ('clan-chat', 'cmd clan chat buy'), ('afk-message', 'cmd afk selling coins')]:
        m = talker.mark(); sm = r.s.mark()
        talker.send(line)
        blocked = talker.expect(r'Mute|ovozi|gapira olmaysiz', 6, m) is not None
        leaked = r.s.seen(r'<Talker> hello everyone', sm)
        check(f'muted-{label}-blocked', blocked and not leaked)
    m = talker.mark()
    talker.send('cmd party invite Bob')
    time.sleep(2)
    check('muted-party-invite-not-blocked', not talker.seen(r'gapira olmaysiz', m))

    def sign_case(offset):
        m = talker.mark()
        place = r.gp(f'sign Talker {offset}')
        hit = re.search(r'at (-?\d+) (-?\d+) (-?\d+)', place)
        opened = talker.expect(r'^OPEN_SIGN', 8, m) is not None
        x, y, z = hit.groups()
        talker.send(f'sign {x} {y} {z} BUY COINS')
        time.sleep(1.5)
        return opened, r.gp(f'signtext {x} {y} {z}')

    opened, text = sign_case(1)
    check('muted-sign-is-blank', opened and 'BUY COINS' not in text and 'line two' not in text, text)
    r.gp('book Talker'); time.sleep(0.5)
    sm = r.s.mark()
    talker.send('book 0 free ranks at example')
    event = r.s.expect(r'GP bookevent Talker slot=0 cancelled=(\w+)', 8, sm)
    time.sleep(1)
    out = r.gp('bookcheck Talker')
    check('muted-book-not-written', event is not None and event.group(1) == 'true' and 'free ranks' not in out, out)
    r.gp('anvil Talker'); time.sleep(0.5)
    talker.send('rename Spam Name')
    time.sleep(1.5)
    out = r.gp('anvilresult Talker')
    check('muted-anvil-rename-blocked', 'Spam Name' not in out, out)

    # control: the same actions work once unmuted, so the test can see a leak
    r.cmd('unmute Talker'); time.sleep(1.5)
    opened, text = sign_case(2)
    check('control-unmuted-sign-written', 'BUY COINS' in text, text)
    r.gp('book Talker'); time.sleep(0.5)
    sm = r.s.mark()
    talker.send('book 0 free ranks at example')
    r.s.expect(r'GP bookevent Talker', 8, sm); time.sleep(1)
    out = r.gp('bookcheck Talker')
    check('control-unmuted-book-written', 'free ranks' in out, out)
    r.gp('anvil Talker'); time.sleep(0.5)
    talker.send('rename Spam Name'); time.sleep(1.5)
    out = r.gp('anvilresult Talker')
    check('control-unmuted-anvil-rename', 'Spam Name' in out, out)
    sm = r.s.mark()
    talker.send('chat hello everyone')
    check('control-unmuted-chat', r.s.expect(r'Talker.*hello everyone', 6, sm) is not None)
    talker.send('quit'); r.gone('Talker')


def walk(r):
    b = r.bot('Walker')
    a0 = loc(r.state('Walker'))
    b.send('move 3 0 0'); time.sleep(2)
    a1 = loc(r.state('Walker'))
    check('walk-unfrozen-moves', a0 and a1 and abs(a1[0] - a0[0] - 3) < 0.6, f'{a0} -> {a1}')
    b.send('quit'); r.gone('Walker')


def loc(state):
    m = re.search(r'loc=(-?[\d.]+),(-?[\d.]+),(-?[\d.]+)', state)
    return tuple(map(float, m.groups())) if m else None


def freeze(r):
    # a) holding still, then quitting = immediate ban
    q1 = r.bot('Quitter')
    start = loc(r.state('Quitter'))
    r.cmd('freeze Quitter'); time.sleep(1)
    q1.send('move 4 0 0'); time.sleep(2.5)
    now = loc(r.state('Quitter'))
    check('frozen-cannot-walk', start and now and abs(now[0] - start[0]) < 0.6, f'{start} -> {now}')
    q1.send('quit'); r.gone('Quitter'); time.sleep(2)
    st = r.state('Quitter')
    check('frozen-quit-banned-at-once', 'banned=true' in st and 'chiqish' in st, st)

    # b) ways out while frozen
    q2 = r.bot('Escaper')
    r.cmd('freeze Escaper'); time.sleep(1)
    check('frozen-no-boat', 'vehicle=false' in r.gp('mount Escaper'))
    check('frozen-no-pearl-throw', 'flying=false' in r.gp('pearl Escaper'))
    check('frozen-no-pearl-teleport', 'moved=false' in r.gp('tpcause Escaper ENDER_PEARL'))
    check('frozen-no-portal-teleport', 'moved=false' in r.gp('tpcause Escaper NETHER_PORTAL'))
    check('frozen-staff-teleport-allowed', 'moved=true' in r.gp('tpcause Escaper PLUGIN'))
    r.cmd('unfreeze Escaper'); time.sleep(1)
    # gliding away: control first (it works unfrozen), then a freeze mid-glide stops it and holds the player
    r.gp('elytra Escaper'); q2.send('air on'); time.sleep(0.3)
    q2.send('elytra'); time.sleep(0.3)
    glide_control = r.state('Escaper')
    check('control-unfrozen-can-glide', 'gliding=true' in glide_control, glide_control)
    r.cmd('freeze Escaper'); time.sleep(1.5)
    first = r.state('Escaper')
    q2.send('elytra'); time.sleep(2)
    second = r.state('Escaper')
    a1, a2 = loc(first), loc(second)
    check('freeze-stops-glide-and-holds', 'gliding=false' in first and 'gliding=false' in second and a1 and a2
          and abs(a1[0] - a2[0]) + abs(a1[1] - a2[1]) + abs(a1[2] - a2[2]) < 0.6, f'{first} / {second}')
    r.cmd('unfreeze Escaper'); q2.send('air off'); time.sleep(1)
    r.cmd('tp Escaper 0 -60 0'); time.sleep(1)
    check('unfrozen-can-mount-again', 'vehicle=true' in r.gp('mount Escaper'))
    q2.send('quit'); r.gone('Escaper')

    # c) kicked by something other than Guard staff (an anticheat, a plugin) and back in time: frozen again, no ban
    q3 = r.bot('Returner')
    r.cmd('freeze Returner'); time.sleep(1)
    sm = r.s.mark()
    r.cmd('minecraft:kick Returner flying is not enabled')
    r.gone('Returner')
    check('provoked-kick-starts-deadline', r.s.expect(r'soniya ichida qaytmasa', 8, sm) is not None)
    time.sleep(3)
    q3 = r.bot('Returner')
    time.sleep(1)
    check('returner-frozen-again', 'frozen=true' in r.state('Returner'))
    time.sleep(GRACE + 4)
    check('returner-not-banned', 'banned=false' in r.state('Returner'))
    r.cmd('unfreeze Returner'); q3.send('quit'); r.gone('Returner')

    # d) stops answering (a faked lost connection) and stays away: banned after the grace period
    q4 = r.bot('Hanger')
    r.cmd('freeze Hanger'); time.sleep(1)
    sm = r.s.mark()
    q4.send('hang')
    left = r.s.expect(r'Hanger lost connection', 70, sm)
    check('hang-times-out', left is not None, left.group(0) if left else '')
    try: q4.proc.kill()
    except Exception: pass
    r.bots.pop('Hanger', None)
    time.sleep(3)
    check('hanger-not-banned-before-deadline', 'banned=false' in r.state('Hanger'))
    time.sleep(GRACE + 3)
    st = r.state('Hanger')
    check('hanger-banned-after-deadline', 'banned=true' in st and 'qaytmadi' in st, st)

    # e) kicked by staff through Guard: never banned, frozen again on return
    q5 = r.bot('Kicked')
    r.cmd('freeze Kicked'); time.sleep(1)
    r.cmd('kick Kicked go to discord')
    r.gone('Kicked')
    time.sleep(GRACE + 3)
    check('staff-kick-not-banned', 'banned=false' in r.state('Kicked'))
    q5 = r.bot('Kicked')
    time.sleep(1)
    check('staff-kicked-frozen-on-return', 'frozen=true' in r.state('Kicked'))
    r.cmd('unfreeze Kicked'); time.sleep(1)
    check('unfreeze-works', 'frozen=false' in r.state('Kicked'))
    q5.send('quit'); r.gone('Kicked')

    # f) frozen in mid-air: held with flight, which is taken back on unfreeze and never kept after leaving
    q6 = r.bot('Flyer')
    r.gp('lift Flyer'); time.sleep(0.5)
    r.cmd('freeze Flyer'); time.sleep(1.5)
    st = r.state('Flyer')
    check('midair-freeze-holds-with-flight', 'flight=true' in st, st)
    r.cmd('kick Flyer check')
    r.gone('Flyer'); time.sleep(1.5)
    q6 = r.bot('Flyer')
    time.sleep(1)
    r.cmd('unfreeze Flyer'); time.sleep(1)
    check('unfreeze-removes-granted-flight', 'flight=false' in r.state('Flyer'))
    q6.send('quit'); r.gone('Flyer')

    # g) released by staff while offline: the deadline is cancelled
    q7 = r.bot('Released')
    r.cmd('freeze Released'); time.sleep(1)
    r.cmd('minecraft:kick Released lag')
    r.gone('Released'); time.sleep(1)
    r.cmd('unfreeze Released'); time.sleep(GRACE + 3)
    check('offline-unfreeze-cancels-deadline', 'banned=false' in r.state('Released'))

    # h) frozen when the server stops: no ban (checked after the restart)
    r.bot('Stopper')
    r.cmd('freeze Stopper'); time.sleep(1)


def ac(r):
    """SentinelAC flags reach Guard per check: rare combat flags count at once."""
    bot = r.bot('Reacher')
    m = r.s.mark()
    r.cmd('execute as Reacher at @s run summon minecraft:villager ~5 ~ ~ {NoAI:1b,Silent:1b,Tags:["target"]}')
    time.sleep(2)
    # the bot hits the villager from 5 blocks, three times
    hit = bot.expect(r'^ENTITY (\d+) villager', 5, 0)
    check('ac-test-target-seen', hit is not None)
    if hit:
        for _ in range(4):
            bot.send('attack ' + hit.group(1)); time.sleep(1.2)
    time.sleep(3)
    out = r.gp('sus Reacher')
    # far hits from a bot not looking at the target: SentinelAC calls them Reach or Hitboxes; either is a
    # rare-combat rule (3 or 2 flags), far below the old flat 10 flags per minute
    check('ac-combat-flags-reach-sus-list', re.search(r'sentinelac: (reach|hitboxes) \(\d+x / 5 daq\)', out.lower()) is not None, out)
    bot.send('quit'); r.gone('Reacher')


def ip_rules(r):
    names = ['AltOne', 'AltTwo', 'AltThree']
    bots = [r.bot(n) for n in names]
    out = r.gp('alts ::1')
    check('ipv6-accounts-grouped', re.search(r'accounts=([3-9]|\d\d)', out) is not None and 'shared=true' in out, out)
    helper = r.bot('Helper')
    m = helper.mark()
    helper.send('cmd ipban AltOne 1d evasion')
    check('shared-ip-ban-needs-force', helper.expect(r'umumiy IP', 8, m) is not None)
    time.sleep(1)
    check('shared-ip-ban-not-created', 'banned=false' in r.state('AltOne'))
    m = bots[1].mark()
    helper.send('cmd ipban AltOne 1d evasion -f')
    check('forced-ip-ban-kicks-same-network', bots[1].expect(r'^KICK ', 10, m) is not None)
    st = r.state('AltOne')
    check('ipv6-ban-stored-as-network', 'banned=true' in st, st)
    for n in names + ['Helper']:
        r.gone(n, 15)
    late = r.bot('AltFour', wait=False)
    check('ipv6-ban-denies-new-account', late.expect(r'^KICK ', 20, 0) is not None and late.expect(r'^POS ', 1, 0) is None)
    r.gone('AltFour', 10)
    r.cmd('unbanip ::1'); time.sleep(1.5)
    back = r.bot('AltFive', wait=False)
    check('unbanip-by-address-lifts-network-ban', back.expect(r'^POS ', 25, 0) is not None)
    back.send('quit'); r.gone('AltFive')


def restart(r):
    r.stop()
    r.start()
    st = r.state('Stopper')
    check('server-stop-while-frozen-not-banned', 'banned=false' in st, st)
    check('staff-registry-survives-restart', 'immune=true' in r.gp('staff Mod'))


if __name__ == '__main__':
    sys.exit(main())
