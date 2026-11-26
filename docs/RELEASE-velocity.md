SentinelGuard 2.3.1 for Velocity: the proxy now enforces freeze across the network.

- 2.3.1: database connections are probed every 20 s and replaced after 240 s (no more idle-timeout warnings).
- **No server switches while frozen.** A player with an active network freeze cannot move to another server
  (`freezeSwitchMessage`).
- **Leaving the network while frozen.** Only the proxy can tell leaving the network from switching servers, so it
  decides: a frozen player who leaves must come back within `freezeReturnGraceSeconds` (default 180; they are frozen
  again on any server) or is banned for `freezeAutoBanDays` (default 30) with `freezeNoReturnReason`. A kick by staff
  through Guard and a proxy shutdown never ban; an existing ban is never replaced. `freezeAutoBanOnLeave=false` turns
  it off. Set the grace to 0 to ban at once.
- IP bans match the IPv6 /64 network, the same as on Paper.
- Same database schema (4) plus one new table (`sg_staff`, created automatically); 2.2 servers keep working with it.
- One JAR for Velocity 3.5.1 through 4.3.1 (Java 17 or newer; Velocity 4.x itself needs Java 25).

The attached JAR is signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed). Checksums are in `SHA256SUMS.txt`.
