package uz.sentinel.guard.network;
import java.time.Instant;
import java.util.UUID;
/**
 * Committed change notification. Consumers track the highest sequence they applied and re-read the
 * named row from SQL; an event never carries state. {@code punishmentId} is null for PLAYER and SUS.
 */
public record OutboxEvent(long sequence, UUID eventId, UUID punishmentId, UUID player, String ip, Action action, Instant createdAt) {
    public enum Action { CREATED, REVOKED, PLAYER, SUS }
}
