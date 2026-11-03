package uz.sentinel.guard.network;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
/** Blocking persistence API: adapters must invoke on their bounded database executor. */
public final class JdbcPunishmentStore {
    private final DataSource source;
    public JdbcPunishmentStore(DataSource source) { this.source = Objects.requireNonNull(source); }
    public void initialize() throws SQLException {
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS sg_punishments (id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, kind VARCHAR(16) NOT NULL, reason VARCHAR(2048) NOT NULL, actor VARCHAR(128) NOT NULL, server_id VARCHAR(128) NOT NULL, created_ms BIGINT NOT NULL, expires_ms BIGINT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE, INDEX sg_player_active (player_uuid, kind, revoked)) ENGINE=InnoDB");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS sg_outbox (sequence_id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(36) NOT NULL UNIQUE, player_uuid VARCHAR(36) NOT NULL, action VARCHAR(16) NOT NULL, created_ms BIGINT NOT NULL) ENGINE=InnoDB");
        }
    }
    public void create(NetworkPunishment p) throws SQLException {
        if (p.revoked()) throw new IllegalArgumentException("Cannot create revoked punishment");
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s = c.prepareStatement("INSERT INTO sg_punishments (id,player_uuid,kind,reason,actor,server_id,created_ms,expires_ms,revoked) VALUES (?,?,?,?,?,?,?,?,FALSE)")) {
                    s.setString(1,p.id().toString()); s.setString(2,p.player().toString()); s.setString(3,p.type().name());
                    s.setString(4,p.reason()); s.setString(5,p.actor()); s.setString(6,p.server()); s.setLong(7,p.createdAt().toEpochMilli());
                    if (p.expiresAt()==null) s.setNull(8,Types.BIGINT); else s.setLong(8,p.expiresAt().toEpochMilli());
                    s.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO sg_outbox (event_id,player_uuid,action,created_ms) VALUES (?,?,?,?)")) {
                    s.setString(1,p.id().toString()); s.setString(2,p.player().toString()); s.setString(3,"CREATED"); s.setLong(4,p.createdAt().toEpochMilli()); s.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) { try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); } throw e; }
        }
    }
    public List<NetworkPunishment> active(UUID player, Instant now) throws SQLException {
        List<NetworkPunishment> result = new ArrayList<>();
        try (Connection c=source.getConnection(); PreparedStatement s=c.prepareStatement("SELECT * FROM sg_punishments WHERE player_uuid=? AND revoked=FALSE AND created_ms<=? AND (expires_ms IS NULL OR expires_ms>?) ORDER BY created_ms DESC")) {
            s.setString(1,player.toString()); s.setLong(2,now.toEpochMilli()); s.setLong(3,now.toEpochMilli());
            try (ResultSet rs=s.executeQuery()) {
                while(rs.next()) {
                    long expiry=rs.getLong("expires_ms"); Instant end=rs.wasNull()?null:Instant.ofEpochMilli(expiry);
                    result.add(new NetworkPunishment(UUID.fromString(rs.getString("id")),player,NetworkPunishment.Type.valueOf(rs.getString("kind")),rs.getString("reason"),rs.getString("actor"),rs.getString("server_id"),Instant.ofEpochMilli(rs.getLong("created_ms")),end,rs.getBoolean("revoked")));
                }
            }
        }
        return List.copyOf(result);
    }
}
