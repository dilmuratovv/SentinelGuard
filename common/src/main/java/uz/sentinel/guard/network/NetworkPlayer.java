package uz.sentinel.guard.network;
import java.util.Set;
import java.util.UUID;
/** Network-wide player identity: last known name, latest address and every address seen. */
public record NetworkPlayer(UUID uuid, String name, String latestIp, Set<String> ips) {
    public NetworkPlayer {
        ips = Set.copyOf(ips);
    }
}
