SentinelGuard 2.0 for Velocity: one JAR per Velocity version (3.5.1, 4.1.1, 4.2.0, 4.3.0, 4.3.1).

Pick the file that matches your proxy. Velocity 3.5.1 needs Java 21; Velocity 4.x needs Java 25.

- Denies logins for account bans and IP bans (IPv4 and IPv6) from the shared database.
- Disconnects a player who is already online about a second after a ban or `/kick`.
- If the database is unreachable, logins are denied (configurable) and the connection is retried automatically.
- `/sgproxy` shows the connection status (permission `sentinelguard.proxy.admin`).

Setup: start the proxy once, edit `plugins/sentinelguard/database.properties` to use the same database as your Paper servers, then restart. The Paper servers are a separate release.

Checksums are in `SHA256SUMS.txt`.
