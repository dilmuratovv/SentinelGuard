package me.azicoo.guard.alts;

import me.azicoo.guard.GuardPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class AltManager {
    private final GuardPlugin plugin;
    private final File file;
    private FileConfiguration config;

    // UUID -> Latest IP
    private final Map<UUID, String> latestIps = new ConcurrentHashMap<>(128);
    // UUID -> Set of known IPs
    private final Map<UUID, Set<String>> playerIps = new ConcurrentHashMap<>(128);
    // IP -> Set of UUIDs
    private final Map<String, Set<UUID>> ipPlayers = new ConcurrentHashMap<>(128);
    // UUID -> Name
    private final Map<UUID, String> playerNames = new ConcurrentHashMap<>(128);
    // Name -> UUID (lowercase name)
    private final Map<String, UUID> nameToUuid = new ConcurrentHashMap<>(128);

    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public AltManager(GuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "ip_history.yml");
        this.loadData();
    }

    public synchronized void recordLogin(UUID uuid, String name, String ip) {
        if (uuid == null || ip == null) return;
        String cleanIp = ip.replace("/", "").split(":")[0].trim();
        if (cleanIp.isEmpty()) return;

        playerNames.put(uuid, name);
        nameToUuid.put(name.toLowerCase(), uuid);
        String oldIp = latestIps.put(uuid, cleanIp);

        boolean added1 = playerIps.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet(4)).add(cleanIp);
        boolean added2 = ipPlayers.computeIfAbsent(cleanIp, k -> ConcurrentHashMap.newKeySet(4)).add(uuid);

        if (added1 || added2 || oldIp == null || !oldIp.equals(cleanIp)) {
            dirty.set(true);
        }
    }

    public String getLatestIp(UUID uuid) {
        return latestIps.get(uuid);
    }

    public Set<UUID> getAltsByIp(String ip) {
        if (ip == null) return Collections.emptySet();
        String cleanIp = ip.replace("/", "").split(":")[0].trim();
        return ipPlayers.getOrDefault(cleanIp, Collections.emptySet());
    }

    public Set<UUID> getAltsByUuid(UUID uuid) {
        Set<String> ips = playerIps.get(uuid);
        if (ips == null || ips.isEmpty()) {
            return Collections.emptySet();
        }
        Set<UUID> allAlts = new HashSet<>(4);
        for (String ip : ips) {
            Set<UUID> playersOnIp = ipPlayers.get(ip);
            if (playersOnIp != null) {
                allAlts.addAll(playersOnIp);
            }
        }
        return allAlts;
    }

    public UUID getUuidByName(String name) {
        if (name == null) return null;
        return nameToUuid.get(name.toLowerCase());
    }

    public String getNameByUuid(UUID uuid) {
        return playerNames.getOrDefault(uuid, "Unknown");
    }

    public synchronized void loadData() {
        if (!file.exists()) {
            return;
        }
        config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = config.getConfigurationSection("players");
        if (sec == null) return;

        for (String uuidStr : sec.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                String name = sec.getString(uuidStr + ".name", "Unknown");
                String latestIp = sec.getString(uuidStr + ".latest-ip", "");
                List<String> ips = sec.getStringList(uuidStr + ".ips");

                playerNames.put(uuid, name);
                nameToUuid.put(name.toLowerCase(), uuid);
                if (!latestIp.isEmpty()) {
                    latestIps.put(uuid, latestIp);
                }

                Set<String> ipSet = ConcurrentHashMap.newKeySet(ips.size() + 2);
                ipSet.addAll(ips);
                if (!latestIp.isEmpty()) {
                    ipSet.add(latestIp);
                }
                playerIps.put(uuid, ipSet);

                for (String ip : ipSet) {
                    ipPlayers.computeIfAbsent(ip, k -> ConcurrentHashMap.newKeySet(4)).add(uuid);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not load alt record for: " + uuidStr, e);
            }
        }
        dirty.set(false);
    }

    public synchronized void saveData() {
        if (!dirty.getAndSet(false)) {
            return; // Zero disk write if no new logins or alts registered
        }
        config = new YamlConfiguration();
        ConfigurationSection sec = config.createSection("players");
        for (Map.Entry<UUID, String> entry : playerNames.entrySet()) {
            UUID uuid = entry.getKey();
            String name = entry.getValue();
            String latestIp = latestIps.getOrDefault(uuid, "");
            Set<String> ips = playerIps.getOrDefault(uuid, Collections.emptySet());

            String uKey = uuid.toString();
            sec.set(uKey + ".name", name);
            sec.set(uKey + ".latest-ip", latestIp);
            sec.set(uKey + ".ips", new ArrayList<>(ips));
        }

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save ip_history.yml", e);
        }
    }
}
