SentinelGuard 2.3.2 for Paper: the moderation hub of the Sentinel suite. This release closes the escape routes and
loopholes a determined player or a careless command could use.

**2.3.2:** vanilla's ban commands can no longer go around Guard: `/minecraft:ban`, `/minecraft:ban-ip`, `/minecraft:pardon`, `/minecraft:pardon-ip` and the unprefixed `/pardon-ip` are Guard commands now, from the console and command blocks too (before, only players typing them were redirected, and `/pardon-ip`, `/minecraft:ban-ip` were not), so every ban gets Guard's record, staff immunity and network sync. `/minecraft:kick` deliberately stays vanilla: plugins such as anticheats use it, and a frozen player kicked that way must still come back or be banned. Checking whether an offline player is staff with LuckPerms no longer stalls the server: the user is loaded from LuckPerms storage off the main thread and the command continues when the answer is there (before, the main thread waited up to 400 ms and then fell back to the local registry, so staff known only to LuckPerms storage could be banned when the storage was slower).

**2.3.1:** database connections are probed every 20 s and replaced after 240 s (`network.database.keepalive-seconds`, `max-lifetime-seconds`), so hosts that close idle connections no longer cause `4031 ... inactivity` warnings. Nothing else changed from 2.3.0.

**Freeze (screenshare checks)**
- A frozen player who **quits** is banned at once. One who **loses connection, crashes or is kicked by an anticheat**
  (all of which a cheat client can provoke on purpose: stop answering keep-alives, send a bad packet) must come back
  within `freeze.return-grace-seconds` (default 180), where they are frozen again, or is banned
  (`freeze.auto-ban-reason-no-return`). A kick through Guard's own `/kick` and a server restart never ban. Before,
  any kick or lost connection banned, and a freeze ended silently when the player came back.
- No way out while frozen: boats, horses and minecarts, ender pearls (throw and teleport), chorus fruit, portals,
  spectator teleports and elytra gliding are all blocked; a watchdog pulls the player back if anything else moves them.
  Staff and plugin teleports still work.
- Frozen in mid-air: held with temporary flight instead of being kicked for flying; the flight is taken back on
  unfreeze and before the player's data is saved on leaving.
- `/unfreeze` works on a player who is offline (it also cancels the return deadline).

**Mute**
- Signs are placed blank, books cannot be written or signed, and anvil renames are blocked while muted.
- The built-in command list now covers party, clan, guild, faction, town, island and channel chats of common plugins
  (`/party chat`, `/pc`, `/clan chat`, `/f c`, `/gc`, `/tc`, `/ch`, `/qm` ...), `/afk <message>`, `/nick` and item
  renames, with subcommand matching (`/party invite` is still allowed). `mute-blocked-commands` now adds to the built-in
  list, `mute-allowed-commands` removes entries from it.

**Commands**
- A mistyped duration (`/ban Steve 1dd cheating`, `7`, `1.5h`, `0d`) is rejected with a message instead of becoming a
  permanent ban with the typo in its reason. `1mo1d` and other combinations parse correctly; overflowing values are refused.
- Player names are resolved without blocking the server: known names at once, unknown ones off the main thread. On
  online-mode (or proxy online-mode) servers a name Mojang does not know is reported as not found instead of being
  punished under an offline UUID the real account would never match.
- Offline staff are protected: everyone seen with a staff permission is remembered (`staff.txt`, and the shared
  database in network mode), and LuckPerms is asked directly when installed. Before, only operators were protected
  while offline.

**IP bans and alts**
- IPv6 IP bans cover the player's whole /64 network (one home connection can pick any address in it); alt detection
  groups IPv6 the same way. Existing exact-address bans keep working.
- Shared addresses (mobile carrier NAT, schools, cafes, VPN exits: `alts.shared-ip-accounts`, default 6 accounts) no
  longer link unrelated players in `/alts` or raise alt alerts, and `/ipban` on one asks for `-f` because it would ban
  everyone behind it.

**SentinelAC flags**
- Each check has its own threshold and window. Rare, high-confidence combat checks (KillAura, Tracking, Backtrack,
  AutoTotem, CrystalAura...) reach `/sus` from the first flag; Reach and AutoClicker from a few; movement checks, which
  lag also trips, need a burst. Before, every check needed 10 flags in 60 seconds, which the rare checks never reach.
  Override with `integrations.sentinelac.checks.<check or prefix*>: "<flags>/<seconds>"`.

Tested on Paper 26.3, 1.21.11 and 1.21.1 with real protocol clients (freeze, mute, commands, IP and SentinelAC
scenarios, each with an unmuted or unfrozen control), and on a Velocity network with MariaDB.

One JAR for Paper 1.21 through 26.3 (Java 21; Java 25 on 26.x). Upgrade the Velocity plugin to 2.3.0 too: it now
enforces freeze across the network.

The attached JAR is signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed). Check with `jarsigner -verify -certs SentinelGuard-2.3.1-paper.jar`. Checksums are in `SHA256SUMS.txt`.
