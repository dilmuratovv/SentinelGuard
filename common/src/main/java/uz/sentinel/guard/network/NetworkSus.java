package uz.sentinel.guard.network;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
/** Network-wide suspicion record. Per-reason counts are derived from the history. */
public record NetworkSus(UUID player, String name, boolean active, Instant firstAdded, Instant lastAdded, List<Entry> history) {
    public record Entry(String reason, String staff, Instant at) {}
    public NetworkSus {
        history = List.copyOf(history);
    }
}
