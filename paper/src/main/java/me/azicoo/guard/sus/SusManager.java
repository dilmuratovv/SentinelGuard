package me.azicoo.guard.sus;

import me.azicoo.guard.GuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class SusManager {
    private final GuardPlugin plugin;
    private final Map<UUID, SusEntry> susEntries = new ConcurrentHashMap<>(64);
    private final File dataFile;
    private FileConfiguration dataConfig;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public SusManager(GuardPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "sus_data.yml");
        this.loadData();
    }

    public synchronized int addSus(UUID uuid, String name, String reason, String staff) {
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null && (onlinePlayer.isOp() || onlinePlayer.hasPermission("guard.admin.bypass") || onlinePlayer.hasPermission("guard.admin") || onlinePlayer.hasPermission("guard.staff") || onlinePlayer.hasPermission("sus.admin"))) {
            return -1;
        }
        SusEntry entry = this.susEntries.computeIfAbsent(uuid, k -> new SusEntry(uuid, name));
        entry.setPlayerName(name);
        int newCount = entry.addReason(reason, staff);
        dirty.set(true);
        this.saveData();
        return newCount;
    }

    public synchronized boolean removeSus(UUID uuid) {
        SusEntry entry = this.susEntries.get(uuid);
        if (entry != null && entry.isActive()) {
            entry.setActive(false);
            dirty.set(true);
            this.saveData();
            return true;
        }
        return false;
    }

    public synchronized int clearAll() {
        int count = 0;
        for (SusEntry entry : this.susEntries.values()) {
            if (!entry.isActive()) continue;
            entry.setActive(false);
            count++;
        }
        if (count > 0) {
            dirty.set(true);
            this.saveData();
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

    public synchronized void loadData() {
        this.susEntries.clear();
        if (!this.dataFile.exists()) {
            return;
        }
        this.dataConfig = YamlConfiguration.loadConfiguration(this.dataFile);
        ConfigurationSection playersSec = this.dataConfig.getConfigurationSection("players");
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
                
                LinkedHashMap<String, Integer> reasons = new LinkedHashMap<>();
                ConfigurationSection reasonsSec = pSec.getConfigurationSection("reasons");
                if (reasonsSec != null) {
                    for (String rKey : reasonsSec.getKeys(false)) {
                        reasons.put(rKey, reasonsSec.getInt(rKey));
                    }
                }
                
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

    public synchronized void saveData() {
        if (!dirty.getAndSet(false)) {
            return; // Zero disk write if no sus flags changed
        }
        this.dataConfig = new YamlConfiguration();
        ConfigurationSection playersSec = this.dataConfig.createSection("players");
        for (Map.Entry<UUID, SusEntry> e : this.susEntries.entrySet()) {
            String uuidStr = e.getKey().toString();
            SusEntry entry = e.getValue();
            ConfigurationSection pSec = playersSec.createSection(uuidStr);
            pSec.set("name", entry.getPlayerName());
            pSec.set("active", entry.isActive());
            pSec.set("first-added", entry.getFirstAddedTime());
            pSec.set("last-added", entry.getLastAddedTime());
            
            ConfigurationSection rSec = pSec.createSection("reasons");
            for (Map.Entry<String, Integer> r : entry.getReasons().entrySet()) {
                rSec.set(r.getKey(), r.getValue());
            }
            
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
        try {
            this.dataConfig.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not save sus_data.yml", ex);
        }
    }
}
