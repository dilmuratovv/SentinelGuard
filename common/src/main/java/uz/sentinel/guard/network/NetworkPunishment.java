package uz.sentinel.guard.network;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
/** Immutable network punishment row. Times are UTC instants; a null expiry means permanent. */
public record NetworkPunishment(UUID id, UUID player, String playerName, String ip, Type type, String reason,
        UUID actorId, String actor, String server, Instant createdAt, Instant expiresAt, boolean silent,
        Revocation revocation) {
    public enum Type {
        BAN, IP_BAN, MUTE, WARN, KICK, FREEZE;
        /** Types whose active rows are replaced when a new punishment of this type is created. */
        public java.util.Set<Type> replaces() {
            return switch (this) {
                case BAN, IP_BAN -> java.util.EnumSet.of(BAN, IP_BAN);
                case MUTE -> java.util.EnumSet.of(MUTE);
                case FREEZE -> java.util.EnumSet.of(FREEZE);
                default -> java.util.EnumSet.noneOf(Type.class);
            };
        }
    }
    public record Revocation(String actor, String reason, Instant at) {
        public Revocation {
            if (actor == null || actor.isBlank() || actor.length() > 128) throw new IllegalArgumentException("Invalid revoking actor");
            if (reason == null || reason.isBlank() || reason.length() > 2048) throw new IllegalArgumentException("Invalid revoke reason");
            Objects.requireNonNull(at);
        }
    }
    public NetworkPunishment {
        Objects.requireNonNull(id); Objects.requireNonNull(type); Objects.requireNonNull(createdAt);
        if (player == null && ip == null) throw new IllegalArgumentException("Player or IP required");
        if (type == Type.IP_BAN && ip == null) throw new IllegalArgumentException("IP ban requires an IP");
        if (type != Type.IP_BAN && player == null) throw new IllegalArgumentException(type + " requires a player");
        if (ip != null && !ip.equals(IpAddresses.normalize(ip))) throw new IllegalArgumentException("IP must be normalized");
        if (playerName != null && (playerName.isBlank() || playerName.length() > 64)) throw new IllegalArgumentException("Invalid player name");
        if (reason == null || reason.isBlank() || reason.length() > 2048) throw new IllegalArgumentException("Invalid reason");
        if (actor == null || actor.isBlank() || actor.length() > 128) throw new IllegalArgumentException("Invalid actor");
        if (server == null || server.isBlank() || server.length() > 128) throw new IllegalArgumentException("Invalid server");
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("Expiry must follow creation");
        if (type == Type.KICK && expiresAt != null) throw new IllegalArgumentException("Kick cannot expire");
    }
    public boolean revoked() { return revocation != null; }
    /**
     * Kicks are history-only and never active. The start time is deliberately not checked: servers
     * stamp rows with their own clock, and a clock a few seconds ahead must not delay a ban elsewhere.
     */
    public boolean activeAt(Instant now) {
        return type != Type.KICK && !revoked() && (expiresAt == null || now.isBefore(expiresAt));
    }
    public NetworkPunishment withRevocation(Revocation value) {
        return new NetworkPunishment(id, player, playerName, ip, type, reason, actorId, actor, server, createdAt, expiresAt, silent, value);
    }
    /** Short identifier shown to staff, e.g. #1a2b3c4d. */
    public String displayId() { return "#" + id.toString().substring(0, 8); }
}
