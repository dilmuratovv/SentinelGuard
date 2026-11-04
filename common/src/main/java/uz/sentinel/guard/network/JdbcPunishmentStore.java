package uz.sentinel.guard.network;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/**
 * Authoritative punishment persistence. Every mutation writes its outbox rows in the same transaction.
 * Blocking API: adapters must invoke it on their bounded database executor, never on a server thread.
 */
public final class JdbcPunishmentStore {
    public static final int SCHEMA_VERSION = 3;
    private static final String COLUMNS = "id,player_uuid,player_name,ip,kind,reason,actor_uuid,actor,server_id,created_ms,expires_ms,silent,revoked,revoked_by,revoke_reason,revoked_ms";
    private static final String ACTIVE = "revoked=FALSE AND kind<>'KICK' AND (expires_ms IS NULL OR expires_ms>?)";
    private static final String[][] PUNISHMENT_COLUMNS = {
        {"player_name","VARCHAR(64) NULL"}, {"ip","VARCHAR(64) NULL"}, {"actor_uuid","VARCHAR(36) NULL"},
        {"silent","BOOLEAN NOT NULL DEFAULT FALSE"}, {"revoked_by","VARCHAR(128) NULL"},
        {"revoke_reason","VARCHAR(2048) NULL"}, {"revoked_ms","BIGINT NULL"}};
    private static final String[][] OUTBOX_COLUMNS = {{"punishment_id","VARCHAR(36) NULL"}, {"ip","VARCHAR(64) NULL"}};
    private final DataSource source;
    public JdbcPunishmentStore(DataSource source) { this.source = Objects.requireNonNull(source); }

    public void initialize() throws SQLException {
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS sg_schema (id INT PRIMARY KEY, version INT NOT NULL) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS sg_punishments (id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NULL, player_name VARCHAR(64) NULL, ip VARCHAR(64) NULL, kind VARCHAR(16) NOT NULL, reason VARCHAR(2048) NOT NULL, actor_uuid VARCHAR(36) NULL, actor VARCHAR(128) NOT NULL, server_id VARCHAR(128) NOT NULL, created_ms BIGINT NOT NULL, expires_ms BIGINT NULL, silent BOOLEAN NOT NULL DEFAULT FALSE, revoked BOOLEAN NOT NULL DEFAULT FALSE, revoked_by VARCHAR(128) NULL, revoke_reason VARCHAR(2048) NULL, revoked_ms BIGINT NULL, INDEX sg_player_active (player_uuid, kind, revoked)) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS sg_outbox (sequence_id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(36) NOT NULL UNIQUE, punishment_id VARCHAR(36) NULL, player_uuid VARCHAR(36) NULL, ip VARCHAR(64) NULL, action VARCHAR(16) NOT NULL, created_ms BIGINT NOT NULL) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS sg_players (player_uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, name_lower VARCHAR(64) NOT NULL, latest_ip VARCHAR(64) NULL, updated_ms BIGINT NOT NULL, INDEX sg_player_name (name_lower)) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS sg_player_ips (player_uuid VARCHAR(36) NOT NULL, ip VARCHAR(64) NOT NULL, first_ms BIGINT NOT NULL, PRIMARY KEY (player_uuid, ip), INDEX sg_ip_players (ip)) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS sg_sus (player_uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, is_active BOOLEAN NOT NULL, first_ms BIGINT NOT NULL, last_ms BIGINT NOT NULL) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS sg_sus_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, reason VARCHAR(512) NOT NULL, staff VARCHAR(128) NOT NULL, ts_ms BIGINT NOT NULL, INDEX sg_sus_log_player (player_uuid)) ENGINE=InnoDB");
            int version = schemaVersion(c);
            if (version > SCHEMA_VERSION) throw new SQLException("Database schema " + version + " is newer than this plugin supports (" + SCHEMA_VERSION + ")");
            if (version < 2) migrateToV2(c);
            if (version < 3) setVersion(c, 3); // v3 only adds the tables created above
        }
    }
    private int schemaVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT version FROM sg_schema WHERE id=1")) {
            return rs.next() ? rs.getInt(1) : 1;
        }
    }
    /** 2.0.0-SNAPSHOT tables lacked identity/IP/revocation columns and required a player UUID. */
    private void migrateToV2(Connection c) throws SQLException {
        addMissing(c, "sg_punishments", PUNISHMENT_COLUMNS);
        addMissing(c, "sg_outbox", OUTBOX_COLUMNS);
        try (Statement s = c.createStatement()) {
            s.executeUpdate("ALTER TABLE sg_punishments MODIFY COLUMN player_uuid VARCHAR(36) NULL");
            s.executeUpdate("ALTER TABLE sg_outbox MODIFY COLUMN player_uuid VARCHAR(36) NULL");
            s.executeUpdate("UPDATE sg_outbox SET punishment_id=event_id WHERE punishment_id IS NULL");
            createIndex(s, "CREATE INDEX sg_ip_active ON sg_punishments (ip, kind, revoked)");
            createIndex(s, "CREATE INDEX sg_actor ON sg_punishments (actor)");
            try { s.executeUpdate("INSERT INTO sg_schema (id, version) VALUES (1, 2)"); }
            catch (SQLException duplicate) { s.executeUpdate("UPDATE sg_schema SET version=2 WHERE id=1 AND version<2"); }
        }
    }
    private static void setVersion(Connection c, int version) throws SQLException {
        try (Statement s = c.createStatement()) {
            try { s.executeUpdate("INSERT INTO sg_schema (id, version) VALUES (1, " + version + ")"); }
            catch (SQLException exists) { s.executeUpdate("UPDATE sg_schema SET version=" + version + " WHERE id=1 AND version<" + version); }
        }
    }
    private static void createIndex(Statement s, String sql) {
        try { s.executeUpdate(sql); } catch (SQLException alreadyExists) { /* concurrent or repeated migration */ }
    }
    private static void addMissing(Connection c, String table, String[][] columns) throws SQLException {
        for (String[] column : columns) {
            if (hasColumn(c, table, column[0])) continue;
            try (Statement s = c.createStatement()) {
                s.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column[0] + " " + column[1]);
            } catch (SQLException e) {
                if (!hasColumn(c, table, column[0])) throw e; // another server migrated concurrently
            }
        }
    }
    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) if (meta.getColumnName(i).equalsIgnoreCase(column)) return true;
            return false;
        }
    }

    /**
     * Inserts the punishment and its CREATED event atomically. Active punishments it supersedes
     * (ban replaces ban/IP-ban, mute replaces mute) are revoked in the same transaction.
     * @return ids of the superseded punishments
     */
    public List<UUID> create(NetworkPunishment p) throws SQLException {
        if (p.revoked()) throw new IllegalArgumentException("Cannot create revoked punishment");
        return transaction(c -> {
            List<UUID> replaced = p.type().replaces().isEmpty() ? List.of()
                : revokeMatching(c, p.player(), p.type() == NetworkPunishment.Type.IP_BAN ? p.ip() : null, p.type().replaces(),
                    new NetworkPunishment.Revocation("System", "Replaced by " + p.displayId(), p.createdAt()), p.createdAt());
            insert(c, p);
            outbox(c, p.id(), p.player(), p.ip(), OutboxEvent.Action.CREATED, p.createdAt());
            return replaced;
        });
    }
    /** Idempotent import used by the legacy YAML migration; returns false when the id already exists. */
    public boolean importIfAbsent(NetworkPunishment p) throws SQLException {
        return transaction(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM sg_punishments WHERE id=?")) {
                s.setString(1, p.id().toString());
                try (ResultSet rs = s.executeQuery()) { if (rs.next()) return false; }
            }
            insert(c, p);
            outbox(c, p.id(), p.player(), p.ip(), p.revoked() ? OutboxEvent.Action.REVOKED : OutboxEvent.Action.CREATED, p.createdAt());
            return true;
        });
    }
    /** Revokes active punishments of the given types for a player and/or IP. */
    public List<UUID> revokeActive(UUID player, String ip, Set<NetworkPunishment.Type> types, NetworkPunishment.Revocation revocation) throws SQLException {
        return transaction(c -> revokeMatching(c, player, IpAddresses.normalize(ip), types, revocation, revocation.at()));
    }
    /** Revokes one punishment by id; false if it was not active. */
    public boolean revoke(UUID id, NetworkPunishment.Revocation revocation) throws SQLException {
        return transaction(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE sg_punishments SET revoked=TRUE, revoked_by=?, revoke_reason=?, revoked_ms=? WHERE id=? AND " + ACTIVE)) {
                s.setString(1, revocation.actor()); s.setString(2, revocation.reason()); s.setLong(3, revocation.at().toEpochMilli());
                s.setString(4, id.toString()); s.setLong(5, revocation.at().toEpochMilli());
                if (s.executeUpdate() == 0) return false;
            }
            NetworkPunishment p = find(c, id).orElseThrow();
            outbox(c, id, p.player(), p.ip(), OutboxEvent.Action.REVOKED, revocation.at());
            return true;
        });
    }
    private List<UUID> revokeMatching(Connection c, UUID player, String ip, Set<NetworkPunishment.Type> types,
                                      NetworkPunishment.Revocation revocation, Instant now) throws SQLException {
        if (types.isEmpty() || (player == null && ip == null)) return List.of();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM sg_punishments WHERE " + ACTIVE + " AND kind IN (");
        sql.append(String.join(",", Collections.nCopies(types.size(), "?"))).append(") AND (");
        if (player != null) sql.append("player_uuid=?");
        if (player != null && ip != null) sql.append(" OR ");
        if (ip != null) sql.append("ip=?");
        sql.append(") FOR UPDATE");
        List<NetworkPunishment> rows = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql.toString())) {
            int i = 1;
            s.setLong(i++, now.toEpochMilli());
            for (NetworkPunishment.Type t : types) s.setString(i++, t.name());
            if (player != null) s.setString(i++, player.toString());
            if (ip != null) s.setString(i, ip);
            try (ResultSet rs = s.executeQuery()) { while (rs.next()) rows.add(read(rs)); }
        }
        // IP matches only apply to IP bans; a plain ban on another account sharing the IP stays.
        rows.removeIf(p -> !Objects.equals(p.player(), player) && p.type() != NetworkPunishment.Type.IP_BAN);
        List<UUID> ids = new ArrayList<>(rows.size());
        try (PreparedStatement s = c.prepareStatement("UPDATE sg_punishments SET revoked=TRUE, revoked_by=?, revoke_reason=?, revoked_ms=? WHERE id=?")) {
            for (NetworkPunishment p : rows) {
                s.setString(1, revocation.actor()); s.setString(2, revocation.reason()); s.setLong(3, revocation.at().toEpochMilli());
                s.setString(4, p.id().toString()); s.executeUpdate();
                outbox(c, p.id(), p.player(), p.ip(), OutboxEvent.Action.REVOKED, revocation.at());
                ids.add(p.id());
            }
        }
        return List.copyOf(ids);
    }
    private static void insert(Connection c, NetworkPunishment p) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO sg_punishments (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1, p.id().toString()); setNullable(s, 2, p.player()); s.setString(3, p.playerName()); s.setString(4, p.ip());
            s.setString(5, p.type().name()); s.setString(6, p.reason()); setNullable(s, 7, p.actorId()); s.setString(8, p.actor());
            s.setString(9, p.server()); s.setLong(10, p.createdAt().toEpochMilli());
            if (p.expiresAt() == null) s.setNull(11, Types.BIGINT); else s.setLong(11, p.expiresAt().toEpochMilli());
            s.setBoolean(12, p.silent()); s.setBoolean(13, p.revoked());
            NetworkPunishment.Revocation r = p.revocation();
            s.setString(14, r == null ? null : r.actor()); s.setString(15, r == null ? null : r.reason());
            if (r == null) s.setNull(16, Types.BIGINT); else s.setLong(16, r.at().toEpochMilli());
            s.executeUpdate();
        }
    }
    private static void outbox(Connection c, UUID punishment, UUID player, String ip, OutboxEvent.Action action, Instant at) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO sg_outbox (event_id,punishment_id,player_uuid,ip,action,created_ms) VALUES (?,?,?,?,?,?)")) {
            s.setString(1, UUID.randomUUID().toString()); setNullable(s, 2, punishment); setNullable(s, 3, player);
            s.setString(4, ip); s.setString(5, action.name()); s.setLong(6, at.toEpochMilli()); s.executeUpdate();
        }
    }

    public Optional<NetworkPunishment> find(UUID id) throws SQLException {
        try (Connection c = source.getConnection()) { return find(c, id); }
    }
    private static Optional<NetworkPunishment> find(Connection c, UUID id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM sg_punishments WHERE id=?")) {
            s.setString(1, id.toString());
            try (ResultSet rs = s.executeQuery()) { return rs.next() ? Optional.of(read(rs)) : Optional.empty(); }
        }
    }
    public List<NetworkPunishment> active(UUID player, Instant now) throws SQLException {
        return query("SELECT " + COLUMNS + " FROM sg_punishments WHERE player_uuid=? AND " + ACTIVE + " ORDER BY created_ms DESC",
            player.toString(), now.toEpochMilli());
    }
    /** Active bans that must deny this login: account bans plus IP bans on the address or the account. */
    public List<NetworkPunishment> activeBans(UUID player, String ip, Instant now) throws SQLException {
        String normalized = IpAddresses.normalize(ip);
        if (normalized == null)
            return query("SELECT " + COLUMNS + " FROM sg_punishments WHERE player_uuid=? AND kind IN ('BAN','IP_BAN') AND " + ACTIVE + " ORDER BY created_ms DESC",
                player.toString(), now.toEpochMilli());
        return query("SELECT " + COLUMNS + " FROM sg_punishments WHERE ((player_uuid=? AND kind IN ('BAN','IP_BAN')) OR (ip=? AND kind='IP_BAN')) AND " + ACTIVE + " ORDER BY created_ms DESC",
            player.toString(), normalized, now.toEpochMilli());
    }
    /** Batched active-ban lookup for reconciliation of many online players. */
    public List<NetworkPunishment> activeBans(Collection<UUID> players, Instant now) throws SQLException {
        if (players.isEmpty()) return List.of();
        List<NetworkPunishment> result = new ArrayList<>();
        List<UUID> all = new ArrayList<>(players);
        for (int from = 0; from < all.size(); from += 500) {
            List<UUID> chunk = all.subList(from, Math.min(all.size(), from + 500));
            Object[] args = new Object[chunk.size() + 1];
            args[0] = now.toEpochMilli();
            for (int i = 0; i < chunk.size(); i++) args[i + 1] = chunk.get(i).toString();
            result.addAll(query("SELECT " + COLUMNS + " FROM sg_punishments WHERE kind IN ('BAN','IP_BAN') AND " + ACTIVE
                + " AND player_uuid IN (" + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")", args));
        }
        return List.copyOf(result);
    }
    /** Batched active IP-ban lookup for reconciliation of many online addresses. */
    public List<NetworkPunishment> activeIpBans(Collection<String> ips, Instant now) throws SQLException {
        List<String> all = ips.stream().map(IpAddresses::normalize).filter(Objects::nonNull).distinct().toList();
        List<NetworkPunishment> result = new ArrayList<>();
        for (int from = 0; from < all.size(); from += 500) {
            List<String> chunk = all.subList(from, Math.min(all.size(), from + 500));
            Object[] args = new Object[chunk.size() + 1];
            args[0] = now.toEpochMilli();
            for (int i = 0; i < chunk.size(); i++) args[i + 1] = chunk.get(i);
            result.addAll(query("SELECT " + COLUMNS + " FROM sg_punishments WHERE kind='IP_BAN' AND " + ACTIVE
                + " AND ip IN (" + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")", args));
        }
        return List.copyOf(result);
    }
    public List<NetworkPunishment> history(UUID player, int limit) throws SQLException {
        return query("SELECT " + COLUMNS + " FROM sg_punishments WHERE player_uuid=? ORDER BY created_ms DESC LIMIT " + Math.max(1, limit), player.toString());
    }
    public List<NetworkPunishment> byActor(String actor, int limit) throws SQLException {
        return query("SELECT " + COLUMNS + " FROM sg_punishments WHERE actor=? ORDER BY created_ms DESC LIMIT " + Math.max(1, limit), actor);
    }
    /** Most recent rows, newest first; used to warm adapter caches. */
    public List<NetworkPunishment> recent(int limit) throws SQLException {
        return query("SELECT " + COLUMNS + " FROM sg_punishments ORDER BY created_ms DESC LIMIT " + Math.max(1, limit));
    }
    public List<OutboxEvent> outboxAfter(long sequence, int limit) throws SQLException {
        List<OutboxEvent> events = new ArrayList<>();
        try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT sequence_id,event_id,punishment_id,player_uuid,ip,action,created_ms FROM sg_outbox WHERE sequence_id>? ORDER BY sequence_id LIMIT " + Math.max(1, limit))) {
            s.setLong(1, sequence);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    String player = rs.getString("player_uuid"), punishment = rs.getString("punishment_id");
                    events.add(new OutboxEvent(rs.getLong("sequence_id"), UUID.fromString(rs.getString("event_id")),
                        punishment == null ? null : UUID.fromString(punishment), player == null ? null : UUID.fromString(player),
                        rs.getString("ip"), OutboxEvent.Action.valueOf(rs.getString("action")), Instant.ofEpochMilli(rs.getLong("created_ms"))));
                }
            }
        }
        return List.copyOf(events);
    }
    public long latestSequence() throws SQLException {
        try (Connection c = source.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT MAX(sequence_id) FROM sg_outbox")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private List<NetworkPunishment> query(String sql, Object... args) throws SQLException {
        List<NetworkPunishment> result = new ArrayList<>();
        try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            try (ResultSet rs = s.executeQuery()) { while (rs.next()) result.add(read(rs)); }
        }
        return List.copyOf(result);
    }
    private static NetworkPunishment read(ResultSet rs) throws SQLException {
        long expiry = rs.getLong("expires_ms"); Instant end = rs.wasNull() ? null : Instant.ofEpochMilli(expiry);
        NetworkPunishment.Revocation revocation = null;
        if (rs.getBoolean("revoked")) {
            long at = rs.getLong("revoked_ms");
            String by = rs.getString("revoked_by"), why = rs.getString("revoke_reason");
            revocation = new NetworkPunishment.Revocation(by == null ? "Unknown" : by, why == null ? "Revoked" : why,
                rs.wasNull() ? Instant.ofEpochMilli(rs.getLong("created_ms")) : Instant.ofEpochMilli(at));
        }
        String player = rs.getString("player_uuid"), actorId = rs.getString("actor_uuid");
        return new NetworkPunishment(UUID.fromString(rs.getString("id")), player == null ? null : UUID.fromString(player),
            rs.getString("player_name"), rs.getString("ip"), NetworkPunishment.Type.valueOf(rs.getString("kind")), rs.getString("reason"),
            actorId == null ? null : UUID.fromString(actorId), rs.getString("actor"), rs.getString("server_id"),
            Instant.ofEpochMilli(rs.getLong("created_ms")), end, rs.getBoolean("silent"), revocation);
    }
    private static void setNullable(PreparedStatement s, int index, UUID value) throws SQLException {
        if (value == null) s.setNull(index, Types.VARCHAR); else s.setString(index, value.toString());
    }
    private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionOnce(work);
            } catch (SQLException e) {
                if (attempt >= 3 || !isDeadlock(e)) throw e;
                try { Thread.sleep(25L * attempt); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }
    private static boolean isDeadlock(SQLException e) { return "40001".equals(e.getSQLState()) || e.getErrorCode() == 1213; }
    private static boolean isDuplicate(SQLException e) { return e.getSQLState() != null && e.getSQLState().startsWith("23"); }
    private <T> T transactionOnce(Work<T> work) throws SQLException {
        try (Connection c = source.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            } finally {
                try { c.setAutoCommit(auto); } catch (SQLException ignored) { /* connection is returned to the pool */ }
            }
        }
    }

    // ---- network-wide player identity (name -> uuid, addresses) ----

    private static String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 64) throw new IllegalArgumentException("Invalid player name");
        return n;
    }
    /** Records a login. Writes (and publishes a PLAYER event) only when the name or an address is new. */
    public boolean recordPlayer(UUID player, String name, String ip, Instant now) throws SQLException {
        String cleanName = cleanName(name), cleanIp = IpAddresses.normalize(ip);
        return transaction(c -> {
            boolean changed = mergePlayer(c, player, cleanName, cleanIp, cleanIp == null ? List.of() : List.of(cleanIp), now, true);
            if (changed) outbox(c, null, player, cleanIp, OutboxEvent.Action.PLAYER, now);
            return changed;
        });
    }
    /** Idempotent import: adds what is missing and never overwrites an existing name or address. */
    public boolean importPlayer(NetworkPlayer p, Instant now) throws SQLException {
        String cleanName = cleanName(p.name());
        List<String> ips = p.ips().stream().map(IpAddresses::normalize).filter(Objects::nonNull).toList();
        String latest = IpAddresses.normalize(p.latestIp());
        return transaction(c -> {
            boolean changed = mergePlayer(c, p.uuid(), cleanName, latest, ips, now, false);
            if (changed) outbox(c, null, p.uuid(), latest, OutboxEvent.Action.PLAYER, now);
            return changed;
        });
    }
    private static boolean mergePlayer(Connection c, UUID player, String name, String latestIp, Collection<String> ips, Instant now, boolean overwrite) throws SQLException {
        boolean changed = false, exists = false;
        String oldName = null, oldIp = null;
        try (PreparedStatement s = c.prepareStatement("SELECT name, latest_ip FROM sg_players WHERE player_uuid=? FOR UPDATE")) {
            s.setString(1, player.toString());
            try (ResultSet rs = s.executeQuery()) { if (rs.next()) { exists = true; oldName = rs.getString(1); oldIp = rs.getString(2); } }
        }
        if (!exists) {
            try (PreparedStatement s = c.prepareStatement("INSERT INTO sg_players (player_uuid,name,name_lower,latest_ip,updated_ms) VALUES (?,?,?,?,?)")) {
                s.setString(1, player.toString()); s.setString(2, name); s.setString(3, name.toLowerCase(Locale.ROOT));
                s.setString(4, latestIp); s.setLong(5, now.toEpochMilli()); s.executeUpdate();
                changed = true;
            } catch (SQLException e) {
                if (!isDuplicate(e)) throw e; // another server inserted the same player first
            }
        } else if (overwrite && (!name.equals(oldName) || (latestIp != null && !latestIp.equals(oldIp)))) {
            String sql = latestIp != null
                ? "UPDATE sg_players SET name=?, name_lower=?, latest_ip=?, updated_ms=? WHERE player_uuid=?"
                : "UPDATE sg_players SET name=?, name_lower=?, updated_ms=? WHERE player_uuid=?";
            try (PreparedStatement s = c.prepareStatement(sql)) {
                int i = 1;
                s.setString(i++, name); s.setString(i++, name.toLowerCase(Locale.ROOT));
                if (latestIp != null) s.setString(i++, latestIp);
                s.setLong(i++, now.toEpochMilli()); s.setString(i, player.toString());
                s.executeUpdate();
                changed = true;
            }
        }
        for (String ip : ips) if (insertIpIfAbsent(c, player, ip, now)) changed = true;
        return changed;
    }
    private static boolean insertIpIfAbsent(Connection c, UUID player, String ip, Instant now) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM sg_player_ips WHERE player_uuid=? AND ip=?")) {
            s.setString(1, player.toString()); s.setString(2, ip);
            try (ResultSet rs = s.executeQuery()) { if (rs.next()) return false; }
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO sg_player_ips (player_uuid,ip,first_ms) VALUES (?,?,?)")) {
            s.setString(1, player.toString()); s.setString(2, ip); s.setLong(3, now.toEpochMilli());
            s.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (isDuplicate(e)) return false;
            throw e;
        }
    }
    /** Every known player, oldest activity first so that the newest holder of a name wins in a name index. */
    public List<NetworkPlayer> loadPlayers() throws SQLException {
        Map<UUID, Set<String>> ips = new HashMap<>();
        List<NetworkPlayer> players = new ArrayList<>();
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT player_uuid, ip FROM sg_player_ips")) {
                while (rs.next()) ips.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> new HashSet<>()).add(rs.getString(2));
            }
            try (ResultSet rs = s.executeQuery("SELECT player_uuid, name, latest_ip FROM sg_players ORDER BY updated_ms")) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString(1));
                    players.add(new NetworkPlayer(id, rs.getString(2), rs.getString(3), ips.getOrDefault(id, Set.of())));
                }
            }
        }
        return List.copyOf(players);
    }
    public Optional<NetworkPlayer> player(UUID player) throws SQLException {
        try (Connection c = source.getConnection()) {
            Set<String> ips = new HashSet<>();
            try (PreparedStatement s = c.prepareStatement("SELECT ip FROM sg_player_ips WHERE player_uuid=?")) {
                s.setString(1, player.toString());
                try (ResultSet rs = s.executeQuery()) { while (rs.next()) ips.add(rs.getString(1)); }
            }
            try (PreparedStatement s = c.prepareStatement("SELECT name, latest_ip FROM sg_players WHERE player_uuid=?")) {
                s.setString(1, player.toString());
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? Optional.of(new NetworkPlayer(player, rs.getString(1), rs.getString(2), ips)) : Optional.empty();
                }
            }
        }
    }

    // ---- network-wide suspicion list ----

    private static String trimTo(String value, int max) {
        String v = value == null ? "" : value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
    /** Adds a reason and returns the player's updated record. */
    public NetworkSus addSus(UUID player, String name, String reason, String staff, Instant now) throws SQLException {
        String cleanName = cleanName(name), cleanReason = trimTo(reason, 512).toLowerCase(Locale.ROOT), cleanStaff = trimTo(staff, 128);
        if (cleanReason.isEmpty() || cleanStaff.isEmpty()) throw new IllegalArgumentException("Reason and staff required");
        return transaction(c -> {
            boolean exists;
            try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM sg_sus WHERE player_uuid=? FOR UPDATE")) {
                s.setString(1, player.toString());
                try (ResultSet rs = s.executeQuery()) { exists = rs.next(); }
            }
            if (!exists) {
                try (PreparedStatement s = c.prepareStatement("INSERT INTO sg_sus (player_uuid,name,is_active,first_ms,last_ms) VALUES (?,?,TRUE,?,?)")) {
                    s.setString(1, player.toString()); s.setString(2, cleanName); s.setLong(3, now.toEpochMilli()); s.setLong(4, now.toEpochMilli());
                    s.executeUpdate();
                } catch (SQLException e) {
                    if (!isDuplicate(e)) throw e;
                    exists = true; // another server created the row first
                }
            }
            if (exists) {
                try (PreparedStatement s = c.prepareStatement("UPDATE sg_sus SET name=?, is_active=TRUE, last_ms=? WHERE player_uuid=?")) {
                    s.setString(1, cleanName); s.setLong(2, now.toEpochMilli()); s.setString(3, player.toString()); s.executeUpdate();
                }
            }
            insertSusLog(c, player, cleanReason, cleanStaff, now);
            outbox(c, null, player, null, OutboxEvent.Action.SUS, now);
            return readSus(c, player).orElseThrow();
        });
    }
    private static void insertSusLog(Connection c, UUID player, String reason, String staff, Instant at) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO sg_sus_log (player_uuid,reason,staff,ts_ms) VALUES (?,?,?,?)")) {
            s.setString(1, player.toString()); s.setString(2, reason); s.setString(3, staff); s.setLong(4, at.toEpochMilli()); s.executeUpdate();
        }
    }
    /** Marks a player no longer suspicious; false when they were not on the list. */
    public boolean removeSus(UUID player, Instant now) throws SQLException {
        return transaction(c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE sg_sus SET is_active=FALSE WHERE player_uuid=? AND is_active=TRUE")) {
                s.setString(1, player.toString());
                if (s.executeUpdate() == 0) return false;
            }
            outbox(c, null, player, null, OutboxEvent.Action.SUS, now);
            return true;
        });
    }
    public List<UUID> clearAllSus(Instant now) throws SQLException {
        return transaction(c -> {
            List<UUID> ids = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT player_uuid FROM sg_sus WHERE is_active=TRUE FOR UPDATE")) {
                while (rs.next()) ids.add(UUID.fromString(rs.getString(1)));
            }
            if (ids.isEmpty()) return List.<UUID>of();
            try (Statement s = c.createStatement()) { s.executeUpdate("UPDATE sg_sus SET is_active=FALSE WHERE is_active=TRUE"); }
            for (UUID id : ids) outbox(c, null, id, null, OutboxEvent.Action.SUS, now);
            return List.copyOf(ids);
        });
    }
    /** Idempotent import of a legacy record; false when the player already has one. */
    public boolean importSus(NetworkSus sus, Instant now) throws SQLException {
        String cleanName = cleanName(sus.name());
        return transaction(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM sg_sus WHERE player_uuid=? FOR UPDATE")) {
                s.setString(1, sus.player().toString());
                try (ResultSet rs = s.executeQuery()) { if (rs.next()) return false; }
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO sg_sus (player_uuid,name,is_active,first_ms,last_ms) VALUES (?,?,?,?,?)")) {
                s.setString(1, sus.player().toString()); s.setString(2, cleanName); s.setBoolean(3, sus.active());
                s.setLong(4, sus.firstAdded().toEpochMilli()); s.setLong(5, sus.lastAdded().toEpochMilli()); s.executeUpdate();
            }
            for (NetworkSus.Entry e : sus.history()) insertSusLog(c, sus.player(), trimTo(e.reason(), 512).toLowerCase(Locale.ROOT), trimTo(e.staff(), 128), e.at());
            outbox(c, null, sus.player(), null, OutboxEvent.Action.SUS, now);
            return true;
        });
    }
    public Optional<NetworkSus> sus(UUID player) throws SQLException {
        try (Connection c = source.getConnection()) { return readSus(c, player); }
    }
    private static Optional<NetworkSus> readSus(Connection c, UUID player) throws SQLException {
        String name; boolean active; Instant first, last;
        try (PreparedStatement s = c.prepareStatement("SELECT name,is_active,first_ms,last_ms FROM sg_sus WHERE player_uuid=?")) {
            s.setString(1, player.toString());
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                name = rs.getString(1); active = rs.getBoolean(2); first = Instant.ofEpochMilli(rs.getLong(3)); last = Instant.ofEpochMilli(rs.getLong(4));
            }
        }
        List<NetworkSus.Entry> log = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT reason,staff,ts_ms FROM sg_sus_log WHERE player_uuid=? ORDER BY id")) {
            s.setString(1, player.toString());
            try (ResultSet rs = s.executeQuery()) { while (rs.next()) log.add(new NetworkSus.Entry(rs.getString(1), rs.getString(2), Instant.ofEpochMilli(rs.getLong(3)))); }
        }
        return Optional.of(new NetworkSus(player, name, active, first, last, log));
    }
    public List<NetworkSus> allSus() throws SQLException {
        Map<UUID, List<NetworkSus.Entry>> logs = new HashMap<>();
        List<NetworkSus> result = new ArrayList<>();
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT player_uuid,reason,staff,ts_ms FROM sg_sus_log ORDER BY id")) {
                while (rs.next()) logs.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> new ArrayList<>())
                    .add(new NetworkSus.Entry(rs.getString(2), rs.getString(3), Instant.ofEpochMilli(rs.getLong(4))));
            }
            try (ResultSet rs = s.executeQuery("SELECT player_uuid,name,is_active,first_ms,last_ms FROM sg_sus")) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString(1));
                    result.add(new NetworkSus(id, rs.getString(2), rs.getBoolean(3), Instant.ofEpochMilli(rs.getLong(4)),
                        Instant.ofEpochMilli(rs.getLong(5)), logs.getOrDefault(id, List.of())));
                }
            }
        }
        return List.copyOf(result);
    }

    /** Deletes outbox rows older than the cutoff; consumers re-sync from SQL on restart, so old events are not needed. */
    public int pruneOutbox(Instant before) throws SQLException {
        return transaction(c -> {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM sg_outbox WHERE created_ms<?")) {
                s.setLong(1, before.toEpochMilli());
                return s.executeUpdate();
            }
        });
    }
}
