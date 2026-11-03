package uz.sentinel.guard.network;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
public record NetworkPunishment(UUID id, UUID player, Type type, String reason,
        String actor, String server, Instant createdAt, Instant expiresAt, boolean revoked) {
    public enum Type { BAN, MUTE, WARN, FREEZE }
    public NetworkPunishment {
        Objects.requireNonNull(id); Objects.requireNonNull(player); Objects.requireNonNull(type);
        Objects.requireNonNull(createdAt);
        if (reason == null || reason.isBlank() || reason.length() > 2048) throw new IllegalArgumentException("Invalid reason");
        if (actor == null || actor.isBlank() || actor.length() > 128) throw new IllegalArgumentException("Invalid actor");
        if (server == null || server.isBlank() || server.length() > 128) throw new IllegalArgumentException("Invalid server");
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("Expiry must follow creation");
    }
    public boolean activeAt(Instant now) {
        return !revoked && !now.isBefore(createdAt) && (expiresAt == null || now.isBefore(expiresAt));
    }
}
