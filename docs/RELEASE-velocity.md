SentinelGuard 2.1 for Velocity: follows the per-server scope.

- **One JAR for every Velocity version.** `SentinelGuard-2.1.0-velocity.jar` runs on Velocity 3.5.1 through 4.3.1 and on Java 17 or newer (previously 5 separate JARs).

- A server with `network.scope.enforce-bans: false` is found through the shared database. Banned players may connect to it; every other server refuses them and they land on the exempt server at login.
- A ban issued while the player is on an enforcing server moves them to a ban-exempt server instead of disconnecting. With no exempt server, bans stay full network bans.
- Optional `server.<velocity-name>=<paper-server-id>` lines in `database.properties` map differing names.
- Upgrade the proxy together with the Paper servers (database schema 4).

## Also in 2.0 (shared database)

Velocity 4.x itself needs Java 25; Velocity 3.5.1 runs on Java 17 or newer.

- Denies logins for account bans and IP bans (IPv4 and IPv6) from the shared database.
- Disconnects a player who is already online about a second after a ban or `/kick`.
- If the database is unreachable, logins are denied (configurable) and the connection is retried automatically.
- `/sgproxy` shows the connection status (permission `sentinelguard.proxy.admin`).

Setup: start the proxy once, edit `plugins/sentinelguard/database.properties` to use the same database as your Paper servers, then restart. The Paper servers are a separate release.

Checksums are in `SHA256SUMS.txt`.
