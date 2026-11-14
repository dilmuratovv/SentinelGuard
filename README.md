<div align="center">

# SentinelGuard

### One database. Every server. Every punishment.

A Minecraft moderation project by **azicoo** for Paper servers and Velocity networks: bans, mutes, warnings, freeze, staff chat, alt detection and a suspicious-player list that all share a single MariaDB database.

[![Build](https://github.com/dilmuratovv/SentinelGuard/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/dilmuratovv/SentinelGuard/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/dilmuratovv/SentinelGuard?color=ec4899&label=release)](https://github.com/dilmuratovv/SentinelGuard/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Author](https://img.shields.io/badge/author-azicoo-8b5cf6.svg)](https://github.com/dilmuratovv/SentinelGuard)

[Download](https://github.com/dilmuratovv/SentinelGuard/releases) · [Installation](#installation) · [Network mode](#network-mode) · [Commands](#commands) · [Compatibility](#compatibility)

</div>

---

## About

SentinelGuard works on a single Paper server out of the box. When you run several servers behind Velocity, it switches to **network mode**: everything is stored in one MariaDB database, so a ban given on one server applies on all of them and at the proxy.

**One JAR for Paper and one for Velocity**, published as two separate releases. The Paper JAR runs on every supported Paper version (1.21 to 26.3) and the Velocity JAR on every supported Velocity version (3.5.1 to 4.3.1).

[SentinelAC](https://github.com/dilmuratovv/SentinelAC) is **optional**. If it is installed, SentinelGuard detects it and adds players to `/sus` after repeated flags. It never changes SentinelAC enforcement, and every moderation feature works without it.

**Created and maintained by azicoo.**

## Features

| Area | What SentinelGuard provides |
|---|---|
| **Punishments** | Ban, IP ban (IPv4 and IPv6), mute, warn, kick and their reversals, with durations, reasons and silent (`-s`) mode. |
| **History** | `/history` GUI, `/staffhistory`, warnings list, and a control GUI for each player. |
| **Alt detection** | Every IP a player used is stored, so `/alts` and join alerts see accounts from all servers. |
| **Suspicious list** | `/sus` GUI with reasons and history; optionally fed by SentinelAC flags. |
| **Freeze** | `/freeze` blocks movement and chat; a frozen player stays frozen after joining another server. |
| **Staff chat** | `/staffchat` on one server; network-wide when Redis is enabled. |
| **Shared database** | Punishments, player names and UUIDs, IPs, `/sus`, freeze and kick live in one MariaDB. |
| **Proxy enforcement** | The Velocity plugin denies banned logins and disconnects online players about a second after a ban. |
| **Safe by default** | If the database is down, logins are denied rather than silently bypassing bans. |

## Installation

### Single server

1. Download the Paper JAR from [Releases](https://github.com/dilmuratovv/SentinelGuard/releases).
2. Put it in `plugins/` and restart.

Data is stored in `punishments.yml`, `sus_data.yml` and `ip_history.yml` inside `plugins/SentinelGuard/`. Saves are asynchronous and atomic.

### Network mode

In network mode one MariaDB database holds everything, so an action on one server applies on every server and at the proxy.

**Shared across all servers:**

- bans, IP bans, mutes, warnings, kicks and history;
- player names and UUIDs, so a command works on a player who only ever joined another server;
- every IP a player used, so `/alts` sees accounts from all servers;
- the `/sus` list and its reasons;
- freeze, and `/kick` for a player on any server.

**What to expect:**

- If the database is unreachable, logins are denied instead of letting banned players in (`network.deny-login-on-database-error`, on by default). Paper reconnects every 30 seconds.
- A command reports success only after the change is saved.
- A banned player who is already online is disconnected by the proxy within about a second.

### Per-server scope (new in 2.1)

By default a ban or mute applies on every server. Set the scope on each Paper server to exclude it, for example a lobby or an appeal server where banned players are still allowed:

```yaml
network:
  scope:
    enforce-bans: false    # banned players may join and play on this server
    enforce-mutes: true    # mutes still silence players here
    announce-remote: true  # tell this server's players about bans/mutes/unbans/unmutes from other servers
```

- Every server publishes its scope to the shared database when it starts, so the Velocity proxy follows it without extra setup. If a Velocity server name differs from the Paper `network.server-id`, map it in `database.properties`: `server.<velocity-name>=<paper-server-id>`.
- With at least one ban-exempt server, a banned player can still connect to the proxy but only to ban-exempt servers. On login they are sent to one; a ban that lands while they are on another server moves them there instead of disconnecting. Without such a server, a ban stays a full network ban.
- Announcements follow the scope: a ban or unban is shown only on servers that enforce bans, a mute or unmute only on servers that enforce mutes. A server that does not enforce a kind shows it to its staff and console only. Silent (`-s`) actions, including silent unbans, reach staff only on every server.
- A server missing from the database (not started yet, or running 2.0) enforces everything, so a ban is never skipped by mistake.
- Restart the Paper server after changing `network.scope`. All servers and the proxy must run 2.1 (the shared database moves to schema 4, which 2.0 refuses to open).

### Redis (optional)

Redis is a fast in-memory store. SentinelGuard uses it only to deliver changes to other servers instantly and to carry network staff chat. It never stores punishments, so nothing is lost if it is off: servers then pick up changes every couple of seconds and staff chat stays on one server.

```yaml
network:
  redis:
    enabled: true
    uri: "redis://:password@127.0.0.1:6379"
```

Put the password in the URI and keep Redis off public networks.

### Good to know

- **Keep server clocks synchronized (NTP).** Temporary bans and mutes can end a few seconds early or late if server clocks differ.
- **Immunity:** `guard.admin.bypass` is checked on the server where the target is online. A target on another server is only protected if they are an operator, so give ban commands only to people you trust.
- **Unknown players:** a name that has never joined any server running SentinelGuard falls back to Bukkit's lookup (Mojang in online mode, an offline UUID otherwise).
- **Old data:** the database cleans up old internal records automatically (`network.outbox-retention-days`, default 7).

## Commands

**Main command:** `/sg`  
**Aliases:** `/sentinelguard`, `/sguard`, `/sentinelg`

| Command | Purpose | Permission |
|---|---|---|
| `/sg status` · `stats` · `reload` · `import-legacy` · `version` | Plugin status, statistics, reload, legacy import. | `guard.admin` |
| `/ban <player> [duration] [reason] [-s]` | Ban a player. | `guard.ban` |
| `/unban <player> [reason] [-s]` | Remove a ban. | `guard.unban` |
| `/ipban <player\|ip> [duration] [reason] [-s]` | Ban an IP address. | `guard.ipban` |
| `/unbanip <ip> [reason] [-s]` | Remove an IP ban. | `guard.unbanip` |
| `/mute <player> [duration] [reason] [-s]` · `/unmute` | Mute or unmute a player. | `guard.mute` · `guard.unmute` |
| `/kick <player> [reason] [-s]` | Kick a player, on any server. | `guard.kick` |
| `/warn <player> [reason] [-s]` · `/unwarn <player> <id\|all>` · `/warnings <player>` | Manage warnings. | `guard.warn` · `guard.unwarn` |
| `/history <player>` · `/staffhistory <staff>` | Punishment history. | `guard.history` · `guard.staffhistory` |
| `/alts <player\|ip>` | Alternate accounts. | `guard.alts` |
| `/staffchat <message>` | Staff chat. | `guard.staffchat` |
| `/sus [reload]` · `/addsus <player> <reason>` · `/clearsus` | Suspicious players list. | `sus.admin` |
| `/freeze <player>` · `/unfreeze <player>` | Freeze a player. | `sus.admin` |
| `/sgproxy` (Velocity) | Proxy connection status. | `sentinelguard.proxy.admin` |

## Compatibility

There is a single JAR per platform: no need to pick one for your version.

| Platform | File | Supported versions | Java |
|---|---|---|---|
| **Paper** | `SentinelGuard-<version>-paper.jar` | 1.21, 1.21.1, 1.21.3 – 1.21.11, 26.1.1, 26.1.2, 26.2, 26.3 | 21 on 1.21.x, 25 on 26.x (what the server itself needs) |
| **Velocity** | `SentinelGuard-<version>-velocity.jar` | 3.5.1, 4.1.1, 4.2.0, 4.3.0, 4.3.1 | 17 or newer (4.x needs 25 itself) |

Paper has no 1.21.2 or exactly 26.1 server build. Paper versions before 1.21 (including 1.8 to 1.20), other forks and Folia are not supported.

**How one JAR covers every version.** The Paper JAR is compiled against the oldest supported API (Paper 1.21) and the Velocity JAR against the oldest (Velocity 3.5.1). CI then checks, for every supported version, that each class, method and field the JAR uses exists there with the same signature (`tools/api_compat.py`), and boots the same JARs on a real Paper server with every Velocity version. A newer API version is added to the CI lists only after that check passes.

## Build from source

You need **Maven 3.9+**, **Python 3** and **JDK 21 or newer** (JDK 25 recommended, it can also run the compatibility check against Paper 26.x and Velocity 4.x).

```sh
git clone https://github.com/dilmuratovv/SentinelGuard.git
cd SentinelGuard
mvn -B verify
```

The JARs appear in `paper/target/SentinelGuard-<version>-paper.jar` and `velocity/target/SentinelGuard-<version>-velocity.jar`.

To check a JAR against another API version (needs JDK 25 for Paper 26.x / Velocity 4.x):

```sh
mvn -B -pl common install -DskipTests
mvn -B -q -pl paper -Ppaper-26.3 org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath -Dmdep.outputFile=classpath.txt
python3 tools/api_compat.py --jar paper/target/SentinelGuard-*-paper.jar --classpath "$(cat classpath.txt)"
```

## Releases

Paper and Velocity are published as two separate releases (tags `paper-vX.Y.Z` and `velocity-vX.Y.Z`). Each contains one JAR and a `SHA256SUMS.txt`.

## Credits & license

**SentinelGuard — created and maintained by azicoo.**

Distributed under **[GPL-3.0](LICENSE)**. Thanks to the maintainers of Paper, Velocity, HikariCP, the MariaDB Connector/J and Jedis.
