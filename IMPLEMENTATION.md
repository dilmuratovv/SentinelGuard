# SentinelGuard 2.0.0 development

Status: incomplete; do not deploy as network moderation yet.

- [x] Back up Guard 1.2.1 sources and JAR.
- [x] Parent/common/Paper module structure and SentinelGuard plugin identity.
- [x] Import explicit Paper target profiles from SentinelAC; these require independent verification.
- [ ] SQL schema, migrations, transactional punishment service and outbox.
- [ ] Velocity module: SQL login enforcement and online ban reconciliation implemented; commands, IP-ban and runtime tests pending.
- [ ] Paper adapter: shared punishment store, mute/freeze, permissions and GUI.
- [ ] Redis invalidation plus SQL reconciliation on reconnect.
- [x] Optional automatic SentinelAC core-flag observer, bounded queue and per-check Sus threshold. Runtime verification pending.
- [ ] Import legacy YAML without changing original data.
- [ ] Fix IPv6, GUI permission checks, configurable slots, reload/save behavior.
- [ ] Integration tests: MariaDB + Redis + Velocity + two Paper servers.
- [ ] Verify all published Paper targets and current Velocity/Java compatibility.
- [ ] GitHub main source, version tag, release JARs and checksum manifest.

Database is authoritative. Persist punishment and outbox entry atomically. Redis signals changes, never owns state. Login database failure must produce a configurable explicit denial, never silently bypass bans. Commands acknowledge only committed changes. All JDBC/network calls off server thread. Event UUIDs prevent duplicate effects; expiry uses UTC instants. No player-supplied plugin messages authorize moderation.

Paper 1.21.x uses Java 21; current Paper 26.x and current Velocity require Java 25. Velocity has its own release numbering; do not label proxy artifacts with Minecraft versions.
