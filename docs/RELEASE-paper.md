SentinelGuard 2.0 for Paper: 15 separate JARs, one per Paper version (1.21 – 1.21.11, 26.1.1 – 26.3).

The Velocity plugin is a separate release (`velocity-v*`). Network mode needs both plus one MariaDB database.

- **Shared database.** MariaDB is now the source of truth for bans, IP bans (IPv4 and IPv6), mutes, warnings, kicks and history across Velocity and every Paper server. Each change is written in the same transaction as its outbox row.
- **Velocity enforcement.**
  - Logins are denied for account bans and IP bans.
  - A player who is already online is disconnected about 1 s after a ban commits.
  - A batched re-check of everyone online runs periodically.
  - When the database is unreachable, logins are denied (configurable) and bans are never bypassed.
- **Everything in one database.**
  - Player names and UUIDs, every IP a player used, the `/sus` list, freeze and kick are shared, not only punishments.
  - A command on any server works on a player who joined another one.
  - `/freeze` and `/kick` act on players on other servers, and a frozen player stays frozen on the next server.
- **Robustness.**
  - A server with a fast clock no longer delays a ban elsewhere.
  - Database deadlocks are retried.
  - Old outbox rows are pruned (default 7 days).
- **Redis is optional.** It only sends wake signals and carries network staff chat. SQL polling keeps servers correct without it.
- **Legacy import.** Guard 1.x `punishments.yml` is imported once and left unchanged.
- **Fixes.**
  - IPv6 addresses are now handled correctly.
  - Saving is asynchronous and atomic.
  - Reloading no longer discards unsaved data.
  - Unmuting no longer clears an IP-ban index.
  - Custom control-GUI slots now run the right action and check the right permission.
  - A null address no longer crashes the GUI kick.
- **Optional SentinelAC.** SentinelAC is still optional and is detected automatically.

Pick the Paper JAR that matches your server version exactly. Checksums are in `SHA256SUMS.txt`.
