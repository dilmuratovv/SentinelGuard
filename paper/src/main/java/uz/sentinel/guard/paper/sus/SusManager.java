package uz.sentinel.guard.paper.sus;

import uz.sentinel.guard.network.NetworkSus;
import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.network.NetworkService;
import uz.sentinel.guard.paper.util.DataFiles;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;

public class SusManager {
    private final GuardPlugin plugin;
    private final Map<UUID, SusEntry> susEntries = new ConcurrentHashMap<>(64);
    private final File dataFile;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private volatile NetworkService network;

    public SusManager(GuardPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "sus_data.yml");
        this.loadData();
    }

    /** Switches to the shared database: the cache is rebuilt from every network record. */
    public synchronized void attachNetwork(NetworkService service, List<NetworkSus> all) {
        this.susEntries.clear();
        for (NetworkSus sus : all) cache(sus);
        this.network = service;
    }

    public void detachNetwork() {
        this.network = null;
    }

    /** Replaces or creates the cached entry from a network record. Server thread only. */
    public synchronized void applyRemote(UUID uuid, NetworkSus sus) {
        if (sus == null) return;
        cache(sus);
    }

    private void cache(NetworkSus sus) {
        LinkedHashMap<String, Integer> reasons = new LinkedHashMap<>();
        List<SusLogEntry> history = new ArrayList<>(sus.history().size());
        for (NetworkSus.Entry e : sus.history()) {
            reasons.merge(e.reason(), 1, Integer::sum);
            history.add(new SusLogEntry(e.reason(), e.staff(), e.at().toEpochMilli()));
        }
        this.susEntries.put(sus.player(), new SusEntry(sus.player(), sus.name(), sus.active(), reasons, history,
                sus.firstAdded().toEpochMilli(), sus.lastAdded().toEpochMilli()));
    }

    private void databaseError() {
        this.plugin.getLogger().warning("Sus list change was not saved: network database error");
    }

    public void addSus(UUID uuid, String name, String reason, String staff, IntConsumer onDone) {
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null && (onlinePlayer.isOp() || onlinePlayer.hasPermission("guard.admin.bypass") || onlinePlayer.hasPermission("guard.admin") || onlinePlayer.hasPermission("guard.staff") || onlinePlayer.hasPermission("sus.admin"))) {
            onDone.accept(-1);
            return;
        }
        NetworkService service = this.network;
        if (service == null) {
            onDone.accept(addLocal(uuid, name, reason, staff));
            return;
        }
        String clean = reason.trim().toLowerCase();
        service.submit(store -> store.addSus(uuid, name, reason, staff, Instant.now()), sus -> {
            synchronized (this) { cache(sus); }
            service.publishChange();
            int count = 0;
            for (NetworkSus.Entry e : sus.history()) if (e.reason().equals(clean)) count++;
            onDone.accept(count);
        }, error -> databaseError());
    }

    private synchronized int addLocal(UUID uuid, String name, String reason, String staff) {
        SusEntry entry = this.susEntries.computeIfAbsent(uuid, k -> new SusEntry(uuid, name));
        entry.setPlayerName(name);
        int newCount = entry.addReason(reason, staff);
        dirty.set(true);
        this.requestSave();
        return newCount;
    }

    public void removeSus(UUID uuid, Consumer<Boolean> onDone) {
        NetworkService service = this.network;
        if (service == null) {
            onDone.accept(removeLocal(uuid));
            return;
        }
        service.submit(store -> store.removeSus(uuid, Instant.now()), changed -> {
            synchronized (this) {
                SusEntry entry = this.susEntries.get(uuid);
                if (entry != null) entry.setActive(false);
            }
            if (changed) service.publishChange();
            onDone.accept(changed);
        }, error -> databaseError());
    }

    private synchronized boolean removeLocal(UUID uuid) {
        SusEntry entry = this.susEntries.get(uuid);
        if (entry != null && entry.isActive()) {
            entry.setActive(false);
            dirty.set(true);
            this.requestSave();
            return true;
        }
        return false;
    }

    public void clearAll(IntConsumer onDone) {
        NetworkService service = this.network;
        if (service == null) {
            onDone.accept(clearLocal());
            return;
        }
        service.submit(store -> store.clearAllSus(Instant.now()), ids -> {
            synchronized (this) {
                for (UUID id : ids) {
                    SusEntry entry = this.susEntries.get(id);
                    if (entry != null) entry.setActive(false);
                }
            }
            if (!ids.isEmpty()) service.publishChange();
            onDone.accept(ids.size());
        }, error -> databaseError());
    }

    private synchronized int clearLocal() {
        int count = 0;
        for (SusEntry entry : this.susEntries.values()) {
            if (!entry.isActive()) continue;
            entry.setActive(false);
            count++;
        }
        if (count > 0) {
            dirty.set(true);
            this.requestSave();
        }
        return count;
    }

    public SusEntry getEntry(UUID uuid) {
        return this.susEntries.get(uuid);
    }

    public List<SusEntry> getAllActiveEntries() {
        List<SusEntry> list = new ArrayList<>(susEntries.size());
        for (SusEntry entry : this.susEntries.values()) {
            if (!entry.isActive()) continue;
            list.add(entry);
        }
        list.sort((a, b) -> Integer.compare(b.getTotalFlags(), a.getTotalFlags()));
        return list;
    }

    public boolean isSus(UUID uuid) {
        SusEntry entry = this.susEntries.get(uuid);
        return entry != null && entry.isActive();
    }

    public int getTotalSusCount() {
        return (int) this.susEntries.values().stream().filter(SusEntry::isActive).count();
    }

    public int getTotalFlagsCount() {
        return this.susEntries.values().stream().filter(SusEntry::isActive).mapToInt(SusEntry::getTotalFlags).sum();
    }

    private void requestSave() {
        if (this.network != null) return;
        if (this.plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, this::saveData);
        } else {
            this.saveData();
        }
    }

    /** Reloads from disk; pending changes are written first so a reload never discards them. */
    public void loadData() {
        if (this.network != null) return; // the shared database is the source of truth
        this.saveData(); // takes writeLock then this; never call it while holding this
        synchronized (this) {
            this.loadLocal();
        }
    }

    private void loadLocal() {
        this.susEntries.clear();
        if (!this.dataFile.exists()) {
            return;
        }
        YamlConfiguration dataConfig = YamlConfiguration.loadConfiguration(this.dataFile);
        ConfigurationSection playersSec = dataConfig.getConfigurationSection("players");
        if (playersSec == null) {
            return;
        }
        for (String uuidStr : playersSec.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                ConfigurationSection pSec = playersSec.getConfigurationSection(uuidStr);
                if (pSec == null) continue;
                String name = pSec.getString("name", "Unknown");
                boolean active = pSec.getBoolean("active", true);
                long firstAdded = pSec.getLong("first-added", System.currentTimeMillis());
                long lastAdded = pSec.getLong("last-added", System.currentTimeMillis());
                
                LinkedHashMap<String, Integer> reasons = readReasons(pSec);

                List<SusLogEntry> history = new ArrayList<>();
                List<Map<?, ?>> historyList = pSec.getMapList("history");
                for (Map<?, ?> hMap : historyList) {
                    String r = (String) hMap.get("reason");
                    String s = (String) hMap.get("staff");
                    long t = hMap.containsKey("timestamp") ? ((Number) hMap.get("timestamp")).longValue() : System.currentTimeMillis();
                    if (r == null || s == null) continue;
                    history.add(new SusLogEntry(r, s, t));
                }
                
                SusEntry entry = new SusEntry(uuid, name, active, reasons, history, firstAdded, lastAdded);
                this.susEntries.put(uuid, entry);
            } catch (Exception e) {
                this.plugin.getLogger().log(Level.WARNING, "Error loading sus entry for UUID: " + uuidStr, e);
            }
        }
        dirty.set(false);
    }

    /**
     * Reasons are free text ("fly v2.0", "reach 3.5"), and a '.' in a YAML key is a path separator, so they are stored
     * as a list of {reason, count} maps instead of section keys.
     */
    static void writeReasons(ConfigurationSection pSec, Map<String, Integer> reasons) {
        List<Map<String, Object>> list = new ArrayList<>(reasons.size());
        for (Map.Entry<String, Integer> r : reasons.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>(2);
            m.put("reason", r.getKey());
            m.put("count", r.getValue());
            list.add(m);
        }
        pSec.set("reasons", list);
    }

    /**
     * Reads the list format, and the section format of older versions. In the old format a reason with a '.' was
     * split into nested sections; the full leaf path is exactly the original reason, so it is recovered as such.
     */
    static LinkedHashMap<String, Integer> readReasons(ConfigurationSection pSec) {
        LinkedHashMap<String, Integer> reasons = new LinkedHashMap<>();
        if (pSec.isList("reasons")) {
            for (Map<?, ?> m : pSec.getMapList("reasons")) {
                Object reason = m.get("reason"), count = m.get("count");
                if (reason == null) continue;
                reasons.merge(String.valueOf(reason), count instanceof Number n ? n.intValue() : 1, Integer::sum);
            }
            return reasons;
        }
        ConfigurationSection legacy = pSec.getConfigurationSection("reasons");
        if (legacy != null) {
            for (String path : legacy.getKeys(true)) {
                if (legacy.isConfigurationSection(path)) continue;
                reasons.merge(path, legacy.getInt(path), Integer::sum);
            }
        }
        return reasons;
    }

    public void saveData() {
        if (this.network != null) return;
        synchronized (writeLock) {
            String data;
            synchronized (this) {
                if (!dirty.getAndSet(false)) {
                    return; // Zero disk write if no sus flags changed
                }
                YamlConfiguration dataConfig = new YamlConfiguration();
                ConfigurationSection playersSec = dataConfig.createSection("players");
                for (Map.Entry<UUID, SusEntry> e : this.susEntries.entrySet()) {
                    String uuidStr = e.getKey().toString();
                    SusEntry entry = e.getValue();
                    ConfigurationSection pSec = playersSec.createSection(uuidStr);
                    pSec.set("name", entry.getPlayerName());
                    pSec.set("active", entry.isActive());
                    pSec.set("first-added", entry.getFirstAddedTime());
                    pSec.set("last-added", entry.getLastAddedTime());

                    writeReasons(pSec, entry.getReasons());

                    List<Map<String, Object>> historyList = new ArrayList<>();
                    for (SusLogEntry h : entry.getHistory()) {
                        Map<String, Object> hMap = new HashMap<>(4);
                        hMap.put("reason", h.getReason());
                        hMap.put("staff", h.getStaff());
                        hMap.put("timestamp", h.getTimestamp());
                        historyList.add(hMap);
                    }
                    pSec.set("history", historyList);
                }
                data = dataConfig.saveToString();
            }
            try {
                DataFiles.writeAtomically(this.dataFile, data);
            } catch (IOException ex) {
                dirty.set(true);
                this.plugin.getLogger().log(Level.SEVERE, "Could not save sus_data.yml", ex);
            }
        }
    }
}
