package uz.sentinel.guard.paper.network;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import uz.sentinel.guard.network.IpAddresses;
import uz.sentinel.guard.network.NetworkPlayer;
import uz.sentinel.guard.network.NetworkPunishment;
import uz.sentinel.guard.network.NetworkSus;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Copies Guard 1.x punishments.yml, ip_history.yml and sus_data.yml into SQL. The YAML files are only read, never modified.
 * Row ids are derived from server id + legacy id, so re-running the import inserts nothing twice.
 */
public final class LegacyImporter {
    public record Result(int imported, int existing, int skipped, int playersImported, int playersSkipped, int susImported, int susSkipped) {}

    private LegacyImporter() {}

    /** Imports whichever legacy files exist in the data folder; missing files count as zero. */
    public static CompletableFuture<Result> run(NetworkService network, File dataFolder, Logger logger) {
        return network.submit(store -> {
            int imported = 0, existing = 0, skipped = 0, playersImported = 0, playersSkipped = 0, susImported = 0, susSkipped = 0;
            Instant now = Instant.now();

            ConfigurationSection punishments = section(new File(dataFolder, "punishments.yml"));
            if (punishments != null) {
                for (String legacyId : punishments.getKeys(false)) {
                    ConfigurationSection item = punishments.getConfigurationSection(legacyId);
                    try {
                        NetworkPunishment row = item == null ? null : convert(network.serverId(), legacyId, item);
                        if (row == null) { skipped++; continue; }
                        if (store.importIfAbsent(row)) imported++; else existing++;
                    } catch (IllegalArgumentException e) {
                        skipped++;
                        logger.warning("Legacy punishment " + legacyId + " skipped: " + e.getMessage());
                    }
                }
            }

            ConfigurationSection players = section(new File(dataFolder, "ip_history.yml"));
            if (players != null) {
                for (String key : players.getKeys(false)) {
                    ConfigurationSection item = players.getConfigurationSection(key);
                    try {
                        NetworkPlayer player = item == null ? null : convertPlayer(key, item);
                        if (player == null) { playersSkipped++; continue; }
                        if (store.importPlayer(player, now)) playersImported++;
                    } catch (IllegalArgumentException e) {
                        playersSkipped++;
                        logger.warning("Legacy player " + key + " skipped: " + e.getMessage());
                    }
                }
            }

            ConfigurationSection sus = section(new File(dataFolder, "sus_data.yml"));
            if (sus != null) {
                for (String key : sus.getKeys(false)) {
                    ConfigurationSection item = sus.getConfigurationSection(key);
                    try {
                        NetworkSus entry = item == null ? null : convertSus(key, item);
                        if (entry == null) { susSkipped++; continue; }
                        if (store.importSus(entry, now)) susImported++;
                    } catch (IllegalArgumentException e) {
                        susSkipped++;
                        logger.warning("Legacy sus entry " + key + " skipped: " + e.getMessage());
                    }
                }
            }
            return new Result(imported, existing, skipped, playersImported, playersSkipped, susImported, susSkipped);
        });
    }

    /** The "players" (ip_history, sus_data) or "punishments" section of a legacy file, or null when absent. */
    private static ConfigurationSection section(File file) {
        if (!file.isFile()) return null;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection(file.getName().equals("punishments.yml") ? "punishments" : "players");
        return section;
    }

    static NetworkPlayer convertPlayer(String key, ConfigurationSection item) {
        UUID id = uuid(key);
        if (id == null) return null;
        String name = item.getString("name");
        if (name == null || name.isBlank() || name.length() > 64) return null;
        Set<String> ips = new LinkedHashSet<>();
        for (String raw : item.getStringList("ips")) {
            String ip = IpAddresses.normalize(raw);
            if (ip != null) ips.add(ip);
        }
        String latest = IpAddresses.normalize(item.getString("latest-ip"));
        if (latest != null) ips.add(latest);
        return new NetworkPlayer(id, name.trim(), latest, ips);
    }

    static NetworkSus convertSus(String key, ConfigurationSection item) {
        UUID id = uuid(key);
        if (id == null) return null;
        String name = item.getString("name");
        if (name == null || name.isBlank() || name.length() > 64) return null;
        long first = Math.max(0, item.getLong("first-added", 0));
        long last = Math.max(first, item.getLong("last-added", first));
        List<NetworkSus.Entry> history = new ArrayList<>();
        for (Map<?, ?> raw : item.getMapList("history")) {
            Object reason = raw.get("reason"), staff = raw.get("staff"), at = raw.get("timestamp");
            if (!(reason instanceof String r) || r.isBlank() || !(staff instanceof String st) || st.isBlank()) continue;
            history.add(new NetworkSus.Entry(r, st, Instant.ofEpochMilli(at instanceof Number n ? Math.max(0, n.longValue()) : last)));
        }
        if (history.isEmpty()) {
            ConfigurationSection reasons = item.getConfigurationSection("reasons");
            if (reasons != null) {
                for (String reason : reasons.getKeys(false)) {
                    if (reason.isBlank()) continue;
                    for (int i = 0; i < reasons.getInt(reason, 0); i++) history.add(new NetworkSus.Entry(reason, "Legacy", Instant.ofEpochMilli(last)));
                }
            }
        }
        return new NetworkSus(id, name.trim(), item.getBoolean("active", true), Instant.ofEpochMilli(first), Instant.ofEpochMilli(last), history);
    }

    static NetworkPunishment convert(String serverId, String legacyId, ConfigurationSection item) {
        String rawType = item.getString("type", "");
        NetworkPunishment.Type type;
        try { type = NetworkPunishment.Type.valueOf(rawType); }
        catch (IllegalArgumentException e) { return null; }
        UUID player = uuid(item.getString("target-uuid"));
        String ip = IpAddresses.normalize(item.getString("target-ip"));
        if (type == NetworkPunishment.Type.IP_BAN && ip == null) return null;
        if (type != NetworkPunishment.Type.IP_BAN && player == null) return null;
        Instant created = Instant.ofEpochMilli(Math.max(0, item.getLong("start-time", 0)));
        long end = item.getLong("end-time", -1);
        Instant expires = null;
        if (type != NetworkPunishment.Type.KICK && type != NetworkPunishment.Type.WARN && end > 0) {
            expires = end > created.toEpochMilli() ? Instant.ofEpochMilli(end) : created.plusMillis(1);
        }
        NetworkPunishment.Revocation revocation = null;
        if (!item.getBoolean("active", true) || item.contains("unpunisher-name")) {
            long at = item.getLong("unpunish-time", 0);
            revocation = new NetworkPunishment.Revocation(text(item.getString("unpunisher-name"), "Unknown", 128),
                    text(item.getString("unpunish-reason"), "Bekor qilindi", 2048),
                    at > 0 ? Instant.ofEpochMilli(at) : created);
        }
        String name = item.getString("target-name");
        if (name != null && (name.isBlank() || name.length() > 64)) name = null;
        UUID id = UUID.nameUUIDFromBytes(("sentinelguard-legacy:" + serverId + ":" + legacyId).getBytes(StandardCharsets.UTF_8));
        return new NetworkPunishment(id, player, name, ip, type, text(item.getString("reason"), "Qoidabuzarlik", 2048),
                uuid(item.getString("punisher-uuid")), text(item.getString("punisher-name"), "Console", 128), serverId,
                created, expires, item.getBoolean("silent", false), revocation);
    }

    private static UUID uuid(String value) {
        if (value == null) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException e) { return null; }
    }

    private static String text(String value, String fallback, int max) {
        String v = value == null || value.isBlank() ? fallback : value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
