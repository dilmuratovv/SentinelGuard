package uz.sentinel.guard.network;
import java.util.Objects;
/**
 * Which network punishments a server enforces. A server that does not enforce bans lets banned
 * players in (and the proxy lets them connect to it); one that does not enforce mutes lets muted
 * players chat. Servers also only announce the kinds they enforce. Unknown servers enforce everything.
 */
public record ServerScope(String serverId, boolean enforceBans, boolean enforceMutes) {
    public ServerScope {
        Objects.requireNonNull(serverId);
        if (serverId.isBlank() || serverId.length() > 128) throw new IllegalArgumentException("Invalid server id");
    }
    /** True when this server enforces (and announces) the given punishment type. */
    public boolean enforces(NetworkPunishment.Type type) {
        return switch (type) {
            case BAN, IP_BAN -> enforceBans;
            case MUTE -> enforceMutes;
            default -> true;
        };
    }
}
