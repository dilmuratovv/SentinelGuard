package uz.sentinel.guard.paper.alts;

import uz.sentinel.guard.network.IpAddresses;
import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.network.NetworkPlayer;
import uz.sentinel.guard.paper.network.NetworkService;
import uz.sentinel.guard.paper.util.DataFiles;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class AltManager {
    private final GuardPlugin plugin;
    private final File file;

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
    private final Object writeLock = new Object();
    private volatile NetworkService network;

    public AltManager(GuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "ip_history.yml");
        this.loadData();
    }

    /** Switches to the shared database: the in-memory index is rebuilt from every known player. */
    public synchronized void attachNetwork(NetworkService service, List<NetworkPlayer> players) {
        clearIndex();
        for (NetworkPlayer p : players) indexPlayer(p);
        this.network = service;
    }

    public void detachNetwork() {
        this.network = null;
    }

    private void clearIndex() {
        latestIps.clear();
        playerIps.clear();
        ipPlayers.clear();
        playerNames.clear();
        nameToUuid.clear();
    }

    private void indexPlayer(NetworkPlayer p) {
        String old = playerNames.put(p.uuid(), p.name());
        if (old != null && !old.equalsIgnoreCase(p.name())) nameToUuid.remove(old.toLowerCase(), p.uuid());
        nameToUuid.put(p.name().toLowerCase(), p.uuid());
        if (p.latestIp() != null) latestIps.put(p.uuid(), p.latestIp());
        Set<String> ips = ConcurrentHashMap.newKeySet(p.ips().size() + 2);
        ips.addAll(p.ips());
        if (p.latestIp() != null) ips.add(p.latestIp());
        playerIps.put(p.uuid(), ips);
        for (String ip : ips) ipPlayers.computeIfAbsent(ip, k -> ConcurrentHashMap.newKeySet(4)).add(p.uuid());
    }

    /** A player record written by any server (read from the outbox). */
    public synchronized void applyRemote(NetworkPlayer p) {
        indexPlayer(p);
    }

    public synchronized void recordLogin(UUID uuid, String name, String ip) {
        if (uuid == null || ip == null) return;
        String cleanIp = IpAddresses.normalize(ip);
        if (cleanIp == null) return;

        String oldName = playerNames.put(uuid, name);
        nameToUuid.put(name.toLowerCase(), uuid);
        String oldIp = latestIps.put(uuid, cleanIp);

        boolean added1 = playerIps.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet(4)).add(cleanIp);
        boolean added2 = ipPlayers.computeIfAbsent(cleanIp, k -> ConcurrentHashMap.newKeySet(4)).add(uuid);

        boolean changed = added1 || added2 || oldIp == null || !oldIp.equals(cleanIp) || !name.equals(oldName);
        if (changed) {
            dirty.set(true);
            NetworkService service = network;
            // Only a real change reaches the database; a returning player with the same name and address costs nothing.
            if (service != null) service.submit(store -> store.recordPlayer(uuid, name, cleanIp, Instant.now()), r -> { }, e -> { });
        }
        // Persisted by the periodic auto-save and on shutdown.
    }

    public String getLatestIp(UUID uuid) {
        return latestIps.get(uuid);
    }

    public Set<UUID> getAltsByIp(String ip) {
        if (ip == null) return Collections.emptySet();
        String cleanIp = IpAddresses.normalize(ip);
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

    /** Reloads from disk; unsaved logins are written first so a reload never discards them. */
    public void loadData() {
        if (network != null) return; // the shared database is the source of truth
        saveData(); // takes writeLock then this; never call it while holding this
        synchronized (this) {
            loadLocal();
        }
    }

    private void loadLocal() {
        clearIndex();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = config.getConfigurationSection("players");
        if (sec == null) return;

        for (String uuidStr : sec.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                String name = sec.getString(uuidStr + ".name", "Unknown");
                String latestIp = Objects.requireNonNullElse(IpAddresses.normalize(sec.getString(uuidStr + ".latest-ip", "")), "");
                List<String> ips = new ArrayList<>();
                for (String raw : sec.getStringList(uuidStr + ".ips")) {
                    String normalized = IpAddresses.normalize(raw);
                    if (normalized != null) ips.add(normalized);
                }

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

    public void saveData() {
        if (network != null) return;
        synchronized (writeLock) {
            String data;
            synchronized (this) {
                if (!dirty.getAndSet(false)) {
                    return; // Zero disk write if no new logins or alts registered
                }
                YamlConfiguration out = new YamlConfiguration();
                ConfigurationSection sec = out.createSection("players");
                for (Map.Entry<UUID, String> entry : playerNames.entrySet()) {
                    UUID uuid = entry.getKey();
                    String uKey = uuid.toString();
                    sec.set(uKey + ".name", entry.getValue());
                    sec.set(uKey + ".latest-ip", latestIps.getOrDefault(uuid, ""));
                    sec.set(uKey + ".ips", new ArrayList<>(playerIps.getOrDefault(uuid, Collections.emptySet())));
                }
                data = out.saveToString();
            }
            try {
                DataFiles.writeAtomically(file, data);
            } catch (IOException e) {
                dirty.set(true);
                plugin.getLogger().log(Level.SEVERE, "Could not save ip_history.yml", e);
            }
        }
    }
}
