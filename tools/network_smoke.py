#!/usr/bin/env python3
"""
Runtime network test: MariaDB (+ optional Redis) + Velocity + two Paper servers, all on localhost.
Drives real console commands on both Paper servers and real offline-mode clients through the proxy.

  python3 tools/network_smoke.py --accept-eula --reset-database \
      --jdbc-url jdbc:mariadb://127.0.0.1:3306/sentinelguard --db-user sg --db-password secret \
      [--redis-uri redis://127.0.0.1:6379] [--target 1.21.11] [--java java]

--reset-database drops the sg_* tables first: never point it at a production database.
"""
import argparse
import hashlib
import json
import os
import queue
import re
import secrets
import shutil
import socket
import subprocess
import threading
import time
import urllib.request
import uuid
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / 'tools/client/login.cjs'


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def fetch(entry, folder):
    path = folder / entry['filename']
    if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == entry['sha256']:
        return path
    request = urllib.request.Request(entry['url'], headers={'User-Agent': 'SentinelGuard-tests/2.0 (https://github.com/dilmuratovv/SentinelGuard)'})
    with urllib.request.urlopen(request, timeout=180) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != entry['sha256']:
        raise RuntimeError('Checksum mismatch for ' + entry['filename'])
    path.write_bytes(data)
    return path


class Proc:
    def __init__(self, name, args, cwd):
        self.name = name
        self.log = []
        self.lines = queue.Queue()
        self.process = subprocess.Popen(args, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.process.stdout:
            self.lines.put(line)

    def wait_for(self, pattern, seconds=120, include_seen=False):
        """Waits for a regex in new output; include_seen also accepts lines already consumed."""
        regex = re.compile(pattern)
        if include_seen:
            for line in self.log:
                match = regex.search(line)
                if match:
                    return match
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                line = self.lines.get(timeout=0.25)
            except queue.Empty:
                if self.process.poll() is not None:
                    break
                continue
            self.log.append(line)
            print(f'[{self.name}] {line}', end='', flush=True)
            match = regex.search(line)
            if match:
                return match
        raise RuntimeError(f'{self.name} did not report /{pattern}/')

    def command(self, value):
        print(f'[{self.name}] > {value}', flush=True)
        self.process.stdin.write(value + '\n')
        self.process.stdin.flush()

    def stop(self, command, seconds=60):
        if self.process.poll() is None:
            try:
                self.command(command)
                self.process.wait(timeout=seconds)
            except Exception:
                self.process.kill()
                self.process.wait()
        while not self.lines.empty():
            self.log.append(self.lines.get_nowait())
        return self.process.returncode


def client(port, username, version, expect, timeout=25000):
    result = subprocess.run(['node', str(CLIENT), str(port), username, version, expect, str(timeout)],
                            text=True, capture_output=True, timeout=timeout / 1000 + 15)
    print(result.stdout + result.stderr, end='', flush=True)
    if result.returncode:
        raise RuntimeError(f'Client check failed: {username} {expect}')


CHAT_CLIENT = ROOT / 'tools/client/chat.cjs'


def hold_client(port, username, version, expect, timeout=40000):
    """Starts login.cjs in join-then-kick mode and returns it once the player is in game."""
    proc = subprocess.Popen(['node', str(CLIENT), str(port), username, version, expect, str(timeout)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    wait_joined(proc, username)
    return proc


def hold_chat(port, username, version):
    """Starts chat.cjs (stdin-controlled) and returns it once the player is in game."""
    proc = subprocess.Popen(['node', str(CHAT_CLIENT), str(port), username, version],
                            text=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=1)
    wait_joined(proc, username)
    return proc


def wait_joined(proc, username, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        line = proc.stdout.readline()
        if not line:
            break
        print(f'[client {username}] {line}', end='', flush=True)
        if 'JOINED' in line:
            return
    proc.kill()
    raise RuntimeError(f'{username} did not join')


def say(proc, text):
    proc.stdin.write('say ' + text + '\n')
    proc.stdin.flush()


def end_chat(proc):
    try:
        proc.stdin.write('quit\n')
        proc.stdin.flush()
        proc.wait(timeout=15)
    except Exception:
        proc.kill()


def stat(proc, command, pattern):
    """Runs a console command and returns the first integer captured by pattern from its output."""
    proc.command(command)
    return int(proc.wait_for(pattern, 20).group(1))


def offline_uuid(name):
    digest = bytearray(hashlib.md5(('OfflinePlayer:' + name).encode()).digest())
    digest[6] = (digest[6] & 0x0f) | 0x30
    digest[8] = (digest[8] & 0x3f) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--reset-database', action='store_true')
    parser.add_argument('--jdbc-url', required=True)
    parser.add_argument('--db-user', required=True)
    parser.add_argument('--db-password', default='')
    parser.add_argument('--redis-uri')
    parser.add_argument('--target', default='1.21.11')
    parser.add_argument('--client-version', default=None)
    parser.add_argument('--java', default='java')
    parser.add_argument('--paper-jar', type=Path)
    parser.add_argument('--velocity-jar', type=Path)
    parser.add_argument('--velocity-version', default='4.3.1')
    parser.add_argument('--sentinelac-jar', type=Path, help='optional: installed on server a only')
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error('Accept the Minecraft EULA to run isolated test servers')
    if not args.reset_database:
        parser.error('--reset-database is required (the test drops the sg_* tables)')

    matrix = json.loads((ROOT / 'tools/server-matrix.json').read_text())
    deps = ROOT / 'target/test-dependencies'
    deps.mkdir(parents=True, exist_ok=True)
    paper = fetch(next(s for s in matrix['servers'] if s['version'] == args.target), deps)
    velocity_entry = next(v for v in matrix['velocity'] if v['version'] == args.velocity_version)
    velocity = fetch(velocity_entry, deps)
    paper_plugin = args.paper_jar or next((ROOT / 'paper/target').glob(f'SentinelGuard-*-paper{args.target}.jar'))
    velocity_plugin = args.velocity_jar or next(p for p in (ROOT / 'velocity/target').glob(f'SentinelGuard-*-velocity{args.velocity_version}.jar') if not p.name.startswith('original-'))
    client_version = args.client_version or args.target

    reset_database(args)

    work = ROOT / 'target/network-smoke'
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    secret = secrets.token_hex(16)
    proxy_port, port_a, port_b = free_port(), free_port(), free_port()
    procs = []
    held = []  # long-lived test clients, killed on failure
    report = {'target': args.target, 'velocity': velocity_entry['version'], 'redis': bool(args.redis_uri), 'checks': []}

    def check(name):
        report['checks'].append(name)
        print(f'CHECK PASSED: {name}', flush=True)

    try:
        shared = work / 'shared'
        (shared / 'cache').mkdir(parents=True)
        (shared / 'libraries').mkdir()
        default_config = zipfile.ZipFile(paper_plugin).read('config.yml').decode()
        for server_id, port in (('a', port_a), ('b', port_b)):
            folder = work / server_id
            folder.mkdir()
            for name in ('cache', 'libraries'):
                (folder / name).symlink_to(shared / name, target_is_directory=True)
            (folder / 'eula.txt').write_text('eula=true\n')
            (folder / 'server.properties').write_text(
                f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nmax-players=10\n'
                'view-distance=2\nsimulation-distance=2\ngenerate-structures=false\nspawn-protection=0\n'
                'level-type=minecraft:flat\nenable-status=true\n')
            # First boot without plugins only generates Paper's configs and the world.
            init = Proc(server_id + '-init', [args.java, '-Xmx640M', '-jar', str(paper), '--nogui'], folder)
            init.wait_for(r'Done \(', 300)
            if init.stop('stop', 90) != 0:
                raise RuntimeError(server_id + ' first boot failed')
            (folder / 'plugins/SentinelGuard').mkdir(parents=True, exist_ok=True)
            shutil.copy2(paper_plugin, folder / 'plugins' / paper_plugin.name)
            if args.sentinelac_jar and server_id == 'a':
                shutil.copy2(args.sentinelac_jar, folder / 'plugins' / args.sentinelac_jar.name)
            global_config = folder / 'config/paper-global.yml'
            text = global_config.read_text()
            text, count = re.subn(r'(  velocity:\n    enabled: )false(\n    online-mode: )true(\n    secret: )\'\'',
                                  rf"\g<1>true\g<2>false\g<3>'{secret}'", text)
            if count != 1:
                raise RuntimeError('Could not enable Velocity forwarding in paper-global.yml')
            global_config.write_text(text)
            config = default_config.replace('network:\n  enabled: false', 'network:\n  enabled: true', 1)
            config = config.replace('server-id: "survival"', f'server-id: "{server_id}"', 1)
            config = config.replace('jdbc-url: "jdbc:mariadb://127.0.0.1:3306/sentinelguard"', f'jdbc-url: "{args.jdbc_url}"', 1)
            config = config.replace('username: "sentinelguard"', f'username: "{args.db_user}"', 1)
            config = config.replace('password: ""', f'password: "{args.db_password}"', 1)
            config = config.replace('poll-interval-ticks: 40', 'poll-interval-ticks: 10', 1)
            if args.redis_uri:
                config, redis_edits = re.subn(r'(  redis:\n    enabled: )false(\n(?:    #[^\n]*\n)*    uri: )"[^"]*"',
                                              lambda m: f'{m.group(1)}true{m.group(2)}"{args.redis_uri}"', config, count=1)
                if redis_edits != 1:
                    raise RuntimeError('Could not enable Redis in the default config.yml')
            (folder / 'plugins/SentinelGuard/config.yml').write_text(config)
        # Legacy 1.x data on server a; must be imported once and enforced network-wide.
        legacy_uuid = offline_uuid('LegacyGuy')
        (work / 'a/plugins/SentinelGuard/punishments.yml').write_text(
            "punishments:\n  '#1001':\n    type: BAN\n    target-uuid: " + legacy_uuid + "\n    target-name: LegacyGuy\n"
            "    punisher-name: azicoo\n    reason: legacy-ban\n    duration-ms: -1\n    start-time: 1000\n"
            "    end-time: -1\n    active: true\n    silent: false\n")
        sus_uuid = offline_uuid('LegacySus')
        (work / 'a/plugins/SentinelGuard/ip_history.yml').write_text(
            "players:\n  " + legacy_uuid + ":\n    name: LegacyGuy\n    latest-ip: 10.9.9.9\n    ips:\n    - 10.9.9.9\n    - 10.9.9.8\n")
        (work / 'a/plugins/SentinelGuard/sus_data.yml').write_text(
            "players:\n  " + sus_uuid + ":\n    name: LegacySus\n    active: true\n    first-added: 1000\n    last-added: 2000\n"
            "    reasons:\n      reach: 2\n    history: []\n")
        legacy_files = [work / 'a/plugins/SentinelGuard' / n for n in ('punishments.yml', 'ip_history.yml', 'sus_data.yml')]
        legacy_before = {f: f.read_bytes() for f in legacy_files}

        proxy_dir = work / 'proxy'
        (proxy_dir / 'plugins/sentinelguard').mkdir(parents=True)
        shutil.copy2(velocity_plugin, proxy_dir / 'plugins' / velocity_plugin.name)
        first = Proc('proxy-init', [args.java, '-Xmx256M', '-jar', str(velocity)], proxy_dir)
        first.wait_for(r'Done \(')
        first.stop('shutdown')
        toml = (proxy_dir / 'velocity.toml').read_text()
        toml = toml.replace('bind = "0.0.0.0:25565"', f'bind = "127.0.0.1:{proxy_port}"', 1)
        toml = toml.replace('online-mode = true', 'online-mode = false', 1)
        toml = re.sub(r'login-ratelimit = \d+', 'login-ratelimit = 0', toml, count=1)  # many test logins from 127.0.0.1
        toml = toml.replace('player-info-forwarding-mode = "NONE"', 'player-info-forwarding-mode = "MODERN"', 1)
        servers = (f'[servers]\na = "127.0.0.1:{port_a}"\nb = "127.0.0.1:{port_b}"\ntry = [\n    "a"\n]\n\n'
                   '[forced-hosts]\n\n[advanced]')
        toml = re.sub(r'\[servers\].*?\[advanced\]', lambda _: servers, toml, count=1, flags=re.S)
        (proxy_dir / 'velocity.toml').write_text(toml)
        (proxy_dir / 'forwarding.secret').write_text(secret)
        (proxy_dir / 'plugins/sentinelguard/database.properties').write_text(
            f'jdbcUrl={args.jdbc_url}\nusername={args.db_user}\npassword={args.db_password}\n'
            'denyLoginOnDatabaseError=true\npollIntervalMs=500\nreconcileIntervalSeconds=10\n')

        a = Proc('a', [args.java, '-Xmx640M', '-jar', str(paper), '--nogui'], work / 'a'); procs.append(a)
        a.wait_for(r"Network database connected as server 'a'", 240)
        a.wait_for(r'Legacy import: punishments 1 imported, 0 already present, 0 skipped; players \d+ imported, 0 skipped; sus \d+ imported, 0 skipped', 240, include_seen=True)
        a.wait_for(r'Done \(', 240, include_seen=True)
        if args.sentinelac_jar:
            a.wait_for(r'SentinelAC connected automatically', 120, include_seen=True)
        b = Proc('b', [args.java, '-Xmx640M', '-jar', str(paper), '--nogui'], work / 'b'); procs.append(b)
        b.wait_for(r"Network database connected as server 'b'", 240)
        b.wait_for(r'Done \(', 240)
        proxy = Proc('proxy', [args.java, '-Xmx256M', '-jar', str(velocity)], proxy_dir); procs.append(proxy)
        proxy.wait_for(r'SentinelGuard connected to the network database', 60)
        proxy.wait_for(r'Done \(', 60)
        for f, before in legacy_before.items():
            if f.read_bytes() != before:
                raise RuntimeError(f'Legacy {f.name} was modified by the import')
        check('startup: two Paper servers + Velocity connected to the shared database; legacy YAML (punishments, IP history, sus list) imported unchanged')
        if args.sentinelac_jar:
            report['sentinelac'] = args.sentinelac_jar.name
            check('optional SentinelAC hook attached on server a; server b runs without SentinelAC')

        client(proxy_port, 'CleanOne', client_version, 'join')
        check('clean player joins through Velocity')
        client(proxy_port, 'LegacyGuy', client_version, 'kick:legacy-ban')
        check('imported legacy ban is enforced by the proxy')

        a.command('ban BanProbe 1h smoke-ban')
        a.wait_for(r'BanProbe', 20)
        client(proxy_port, 'BanProbe', client_version, 'kick:smoke-ban')
        check('ban issued on server a denies login at the proxy')
        b.command('unban BanProbe appeal')
        b.wait_for(r'BanProbe', 20)
        client(proxy_port, 'BanProbe', client_version, 'join')
        check('unban issued on server b is global')

        b.command('ipban 127.0.0.1 1h ip-smoke')
        b.wait_for(r'127\.0\.0\.1', 20)
        client(proxy_port, 'IpProbe', client_version, 'kick:ip-smoke')
        check('IP ban issued on server b denies any account from that address')
        a.command('unbanip 127.0.0.1 done')
        a.wait_for(r'127\.0\.0\.1', 20)
        client(proxy_port, 'IpProbe', client_version, 'join')
        check('IP unban issued on server a is global')

        stay = hold_client(proxy_port, 'OnlineProbe', client_version, 'join-then-kick:online-ban')
        held.append(stay)
        a.wait_for(r'OnlineProbe', 30)
        started = time.monotonic()
        b.command('ban OnlineProbe 1h online-ban')  # player is on server a, command runs on server b
        output, _ = stay.communicate(timeout=40)
        print('[client] ' + output, end='', flush=True)
        if stay.returncode:
            raise RuntimeError('Online player was not disconnected by a ban from another server')
        report['online_ban_disconnect_seconds'] = round(time.monotonic() - started, 2)
        check(f'online player on server a disconnected by ban from server b in {report["online_ban_disconnect_seconds"]}s')

        a.command('mute MuteProbe 1h mute-smoke')
        a.wait_for(r'MuteProbe', 20)
        b.command('history MuteProbe')
        b.wait_for(r'mute-smoke', 20)
        check('history on server b shows a mute issued on server a')

        a.command('warn WarnProbe first-warning')
        a.wait_for(r'WarnProbe', 20)
        b.command('warnings WarnProbe')
        b.wait_for(r'WarnProbe.*: .*1 ta', 20)
        b.command('unwarn WarnProbe all')
        b.wait_for(r'1 .*ogohlantirish olib tashlandi', 20)
        a.command('warnings WarnProbe')
        a.wait_for(r'WarnProbe.*: .*0 ta', 20)
        check('warnings are counted and removed network-wide')

        # --- everything-in-one-database checks ---
        time.sleep(1.5)  # PLAYER events from the earlier logins have been committed and polled
        names = {row[0] for row in query(args, "SELECT name FROM sg_players")}
        if not {'CleanOne', 'LegacyGuy'} <= names:
            raise RuntimeError(f'sg_players is missing players: {sorted(names)}')
        ip_rows = query(args, "SELECT COUNT(*) FROM sg_player_ips i JOIN sg_players p ON p.player_uuid=i.player_uuid WHERE p.name='CleanOne'")
        if int(ip_rows[0][0]) < 1:
            raise RuntimeError('CleanOne has no address row in sg_player_ips')
        legacy_ips = {row[0] for row in query(args, "SELECT i.ip FROM sg_player_ips i JOIN sg_players p ON p.player_uuid=i.player_uuid WHERE p.name='LegacyGuy'")}
        if legacy_ips != {'10.9.9.9', '10.9.9.8'}:
            raise RuntimeError(f'legacy addresses not imported: {legacy_ips}')
        check('sg_players / sg_player_ips hold the joined player and the imported legacy player')

        b.command('warn CleanOne smoke-name-warn')  # CleanOne only ever joined through server a
        b.wait_for(r'CleanOne', 20)
        time.sleep(1.5)
        a.command('warnings CleanOne')
        a.wait_for(r'CleanOne.*: .*1 ta', 20)
        check('server b resolves a player who only joined server a (shared names)')

        chat = hold_chat(proxy_port, 'ChatProbe', client_version)
        held.append(chat)
        a.wait_for(r'ChatProbe', 30)
        time.sleep(1.5)  # server b learns the name
        b.command('mute ChatProbe 1h chat-mute')
        b.wait_for(r'ChatProbe', 20)
        time.sleep(1.5)
        say(chat, 'blocked-msg-1')
        time.sleep(1.5)
        b.command('unmute ChatProbe')
        b.wait_for(r'ChatProbe', 20)
        time.sleep(1.5)
        say(chat, 'allowed-msg-2')
        a.wait_for(r'<ChatProbe> allowed-msg-2', 20)
        if any('blocked-msg-1' in line for line in a.log):
            raise RuntimeError('Muted player chat was not blocked on server a')
        end_chat(chat)
        check('mute from server b blocks chat on server a, unmute restores it')

        kick = hold_client(proxy_port, 'KickProbe', client_version, 'join-then-kick:net-kick')
        held.append(kick)
        a.wait_for(r'KickProbe', 30)
        time.sleep(1.5)
        b.command('kick KickProbe net-kick')  # player is on server a
        output, _ = kick.communicate(timeout=40)
        print('[client] ' + output, end='', flush=True)
        if kick.returncode:
            raise RuntimeError('Player was not kicked by a /kick issued on another server')
        check('kick issued on server b disconnects a player on server a')

        before = stat(b, 'sg stats', r"Shubhali O.yinchilar: (\d+) ta")
        a.command('addsus CleanOne test-reason')
        time.sleep(1.5)
        after = stat(b, 'sg stats', r"Shubhali O.yinchilar: (\d+) ta")
        if after != before + 1:
            raise RuntimeError(f'sus list did not propagate to server b: {before} -> {after}')
        b.command('clearsus')
        time.sleep(1.5)
        cleared = stat(a, 'sg stats', r"Shubhali O.yinchilar: (\d+) ta")
        if cleared != 0:
            raise RuntimeError(f'clearsus on server b did not reach server a: {cleared} left')
        check('sus list is shared: add on a is seen on b, clearsus on b empties a')

        freeze = hold_chat(proxy_port, 'FreezeProbe', client_version)
        held.append(freeze)
        a.wait_for(r'FreezeProbe', 30)
        time.sleep(1.5)
        b.command('freeze FreezeProbe')
        time.sleep(1.5)
        if stat(a, 'sg info', r"Muzlatilganlar: (\d+) ta") != 1:
            raise RuntimeError('freeze from server b did not reach server a')
        b.command('unfreeze FreezeProbe')
        time.sleep(1.5)
        if stat(a, 'sg info', r"Muzlatilganlar: (\d+) ta") != 0:
            raise RuntimeError('unfreeze from server b did not reach server a')
        end_chat(freeze)
        check('freeze and unfreeze issued on server b act on a player on server a')

        b.command('sg status')
        b.wait_for(r'Tarmoq \(MariaDB\)', 10)
        proxy.command('sgproxy')
        proxy.wait_for(r'connected, outbox sequence', 10)
        if args.redis_uri:
            b.wait_for(r'Redis: .*ulangan', 10)
            a.command('sc hello-network')
            b.wait_for(r'\[a\].*hello-network', 15)
            check('staff chat crosses servers over Redis')
        check('status commands report the network connection')

        a.command('sg reload')
        a.wait_for(r'qayta yuklandi', 20)
        client(proxy_port, 'BanProbe', client_version, 'join')
        check('reload keeps network mode working')

        for name, proc, cmd in (('a', a, 'stop'), ('b', b, 'stop'), ('proxy', proxy, 'shutdown')):
            code = proc.stop(cmd, 90)
            if code != 0:
                raise RuntimeError(f'{name} exited with {code}')
        logs = {p.name: ''.join(p.log) for p in procs}
        for name, text in logs.items():
            bad = [l for l in text.splitlines() if 'SentinelGuard' in l and ('Exception' in l or 'SEVERE' in l or 'ERROR' in l)]
            if bad:
                raise RuntimeError(f'{name} logged SentinelGuard errors:\n' + '\n'.join(bad[:10]))
        check('clean shutdown without SentinelGuard errors')
        report['result'] = 'passed'
        (work / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    finally:
        for h in held:
            if h.poll() is None:
                h.kill()
        for p in procs:
            if p.process.poll() is None:
                p.process.kill()
                p.process.wait()
            (work / f'{p.name}.log').write_text(''.join(p.log))


def velocity_jar(args):
    return args.velocity_jar or next(p for p in (ROOT / 'velocity/target').glob(f'SentinelGuard-*-velocity{args.velocity_version}.jar') if not p.name.startswith('original-'))


def query(args, sql):
    """Runs one SELECT through the shaded (relocated) MariaDB driver; returns rows as lists of strings."""
    source = ROOT / 'target/Query.java'
    source.parent.mkdir(exist_ok=True)
    source.write_text('''
import java.sql.*;
public class Query { public static void main(String[] a) throws Exception {
  Class.forName("uz.sentinel.guard.libs.mariadb.Driver");
  try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(a[3])) {
    int n = rs.getMetaData().getColumnCount();
    while (rs.next()) {
      StringBuilder row = new StringBuilder();
      for (int i = 1; i <= n; i++) { if (i > 1) row.append('\\t'); row.append(rs.getString(i)); }
      System.out.println(row);
    }
  }
}}''')
    out = subprocess.run([args.java, '-cp', str(velocity_jar(args)), str(source), args.jdbc_url, args.db_user, args.db_password, sql],
                         check=True, capture_output=True, text=True, timeout=60).stdout
    return [line.split('\t') for line in out.splitlines()]


def reset_database(args):
    """Drops the test tables through the plugin's own JDBC driver (no client tools needed)."""
    jar = velocity_jar(args)
    source = ROOT / 'target/ResetDb.java'
    source.parent.mkdir(exist_ok=True)
    source.write_text('''
import java.sql.*;
public class ResetDb { public static void main(String[] a) throws Exception {
  Class.forName("uz.sentinel.guard.libs.mariadb.Driver");
  try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement s = c.createStatement()) {
    for (String t : new String[]{"sg_outbox", "sg_punishments", "sg_schema", "sg_players", "sg_player_ips", "sg_sus", "sg_sus_log"}) s.execute("DROP TABLE IF EXISTS " + t);
  }
}}''')
    subprocess.run([args.java, '-cp', str(jar), str(source), args.jdbc_url,
                    args.db_user, args.db_password], check=True, timeout=60)


if __name__ == '__main__':
    main()
