package me.azicoo.guard.punishment;

import me.azicoo.guard.GuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class PunishmentManager {
    private final GuardPlugin plugin;
    private final File file;
    private FileConfiguration config;

    // Primary store
    private final Map<String, PunishmentRecord> punishmentsById = new ConcurrentHashMap<>(128);

    // O(1) Secondary Indexes for instant non-blocking lookups
    private final Map<UUID, PunishmentRecord> activeBansByUuid = new ConcurrentHashMap<>(64);
    private final Map<String, PunishmentRecord> activeBansByIp = new ConcurrentHashMap<>(64);
    private final Map<UUID, PunishmentRecord> activeMutesByUuid = new ConcurrentHashMap<>(64);
    private final Map<UUID, List<PunishmentRecord>> historyByUuid = new ConcurrentHashMap<>(128);

    private final AtomicInteger idCounter = new AtomicInteger(1000);
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public PunishmentManager(GuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "punishments.yml");
        this.loadData();
    }

    public synchronized PunishmentRecord createPunishment(PunishmentType type, UUID targetUuid, String targetName,
                                                          String targetIp, CommandSender punisher, String reason,
                                                          long durationMs, boolean silent) {
        String id = "#" + idCounter.incrementAndGet();
        UUID punisherUuid = (punisher instanceof Player) ? ((Player) punisher).getUniqueId() : null;
        String punisherName = (punisher != null) ? punisher.getName() : "Console";
        long startTime = System.currentTimeMillis();
        long endTime = (durationMs <= 0) ? -1 : (startTime + durationMs);

        String cleanIp = (targetIp != null) ? targetIp.replace("/", "").split(":")[0].trim() : null;

        // Deactivate previous active punishments of same type if applicable
        if (type == PunishmentType.BAN || type == PunishmentType.IP_BAN) {
            getActiveBans(targetUuid, cleanIp).forEach(p -> deindexPunishment(p, "System", "Yangi jazo bilan almashtirildi"));
        } else if (type == PunishmentType.MUTE) {
            getActiveMutes(targetUuid).forEach(p -> deindexPunishment(p, "System", "Yangi jazo bilan almashtirildi"));
        }

        PunishmentRecord record = new PunishmentRecord(id, type, targetUuid, targetName, cleanIp,
                punisherUuid, punisherName, reason, durationMs, startTime, endTime, true, silent);

        punishmentsById.put(id, record);
        indexPunishment(record);
        dirty.set(true);

        // Async save immediately
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveData);

        // Handle Auto-warn escalation if this was a WARN
        if (type == PunishmentType.WARN) {
            checkWarnThresholds(targetUuid, targetName, cleanIp, punisher);
        }

        return record;
    }

    private void indexPunishment(PunishmentRecord record) {
        if (record.getTargetUuid() != null) {
            historyByUuid.computeIfAbsent(record.getTargetUuid(), k -> Collections.synchronizedList(new ArrayList<>(4))).add(0, record);
        }

        if (record.isActive()) {
            if (record.getType() == PunishmentType.BAN && record.getTargetUuid() != null) {
                activeBansByUuid.put(record.getTargetUuid(), record);
            } else if (record.getType() == PunishmentType.IP_BAN) {
                if (record.getTargetIp() != null) {
                    activeBansByIp.put(record.getTargetIp(), record);
                }
                if (record.getTargetUuid() != null) {
                    activeBansByUuid.put(record.getTargetUuid(), record);
                }
            } else if (record.getType() == PunishmentType.MUTE && record.getTargetUuid() != null) {
                activeMutesByUuid.put(record.getTargetUuid(), record);
            }
        }
    }

    private void deindexPunishment(PunishmentRecord record, String unpunisher, String reason) {
        record.unpunish(unpunisher, reason);
        if (record.getTargetUuid() != null) {
            if (record.getType() == PunishmentType.BAN || record.getType() == PunishmentType.IP_BAN) {
                activeBansByUuid.remove(record.getTargetUuid());
            }
            if (record.getType() == PunishmentType.MUTE) {
                activeMutesByUuid.remove(record.getTargetUuid());
            }
        }
        if (record.getTargetIp() != null) {
            activeBansByIp.remove(record.getTargetIp());
        }
    }

    private void checkWarnThresholds(UUID targetUuid, String targetName, String targetIp, CommandSender staff) {
        int activeWarns = getActiveWarnsCount(targetUuid);
        ConfigurationSection thresholds = plugin.getConfigManager().getConfig().getConfigurationSection("warn-settings.thresholds");
        if (thresholds == null) return;

        String action = thresholds.getString(String.valueOf(activeWarns));
        if (action == null || action.isEmpty()) return;

        String[] parts = action.trim().split("\\s+", 2);
        String actType = parts[0].toLowerCase();
        String durStr = parts.length > 1 ? parts[1] : "perm";
        long dur = TimeParser.parseDuration(durStr);
        String reason = "Avtomatik: " + activeWarns + " ta ogohlantirishga yetdi";

        if (actType.equals("mute")) {
            PunishmentRecord rec = createPunishment(PunishmentType.MUTE, targetUuid, targetName, targetIp, staff, reason, dur, false);
            plugin.getPunishmentFormatter().broadcastPunishment(rec);
            Player targetPlayer = Bukkit.getPlayer(targetUuid);
            if (targetPlayer != null) {
                targetPlayer.sendMessage(plugin.getConfigManager().getPrefix() + 
                        plugin.getConfigManager().colorize("&cKo'p ogohlantirish olganingiz sababli vaqtincha ovozingiz o'chirildi!"));
            }
        } else if (actType.equals("ban")) {
            PunishmentRecord rec = createPunishment(PunishmentType.BAN, targetUuid, targetName, targetIp, staff, reason, dur, false);
            plugin.getPunishmentFormatter().broadcastPunishment(rec);
            Player targetPlayer = Bukkit.getPlayer(targetUuid);
            if (targetPlayer != null) {
                String kickMsg = plugin.getPunishmentFormatter().buildBanScreen(rec);
                Bukkit.getScheduler().runTask(plugin, () -> targetPlayer.kickPlayer(kickMsg));
            }
        } else if (actType.equals("kick")) {
            PunishmentRecord rec = createPunishment(PunishmentType.KICK, targetUuid, targetName, targetIp, staff, reason, 0, false);
            plugin.getPunishmentFormatter().broadcastPunishment(rec);
            Player targetPlayer = Bukkit.getPlayer(targetUuid);
            if (targetPlayer != null) {
                Bukkit.getScheduler().runTask(plugin, () -> targetPlayer.kickPlayer(plugin.getConfigManager().colorize("&cKo'p ogohlantirish sababli serverdan chiqarildingiz!")));
            }
        }
    }

    /**
     * O(1) Instant Check for Active Bans (Pre-login)
     */
    public PunishmentRecord getActiveBan(UUID targetUuid, String ip) {
        if (targetUuid != null) {
            PunishmentRecord rec = activeBansByUuid.get(targetUuid);
            if (rec != null) {
                if (rec.isActive()) return rec;
                activeBansByUuid.remove(targetUuid);
            }
        }
        if (ip != null) {
            String cleanIp = ip.replace("/", "").split(":")[0].trim();
            PunishmentRecord rec = activeBansByIp.get(cleanIp);
            if (rec != null) {
                if (rec.isActive()) return rec;
                activeBansByIp.remove(cleanIp);
            }
        }
        return null;
    }

    public List<PunishmentRecord> getActiveBans(UUID targetUuid, String ip) {
        List<PunishmentRecord> list = new ArrayList<>(2);
        PunishmentRecord ban = getActiveBan(targetUuid, ip);
        if (ban != null) {
            list.add(ban);
        }
        return list;
    }

    /**
     * O(1) Instant Check for Active Mutes (Chat)
     */
    public PunishmentRecord getActiveMute(UUID targetUuid) {
        if (targetUuid == null) return null;
        PunishmentRecord rec = activeMutesByUuid.get(targetUuid);
        if (rec != null) {
            if (rec.isActive()) return rec;
            activeMutesByUuid.remove(targetUuid);
        }
        return null;
    }

    public List<PunishmentRecord> getActiveMutes(UUID targetUuid) {
        List<PunishmentRecord> list = new ArrayList<>(1);
        PunishmentRecord mute = getActiveMute(targetUuid);
        if (mute != null) {
            list.add(mute);
        }
        return list;
    }

    public int getActiveWarnsCount(UUID targetUuid) {
        if (targetUuid == null) return 0;
        List<PunishmentRecord> hist = historyByUuid.get(targetUuid);
        if (hist == null || hist.isEmpty()) return 0;
        int count = 0;
        synchronized (hist) {
            for (PunishmentRecord p : hist) {
                if (p.isActive() && p.getType() == PunishmentType.WARN) {
                    count++;
                }
            }
        }
        return count;
    }

    public synchronized boolean unban(UUID targetUuid, String unpunisher, String reason) {
        boolean found = false;
        if (targetUuid != null) {
            PunishmentRecord ban = activeBansByUuid.remove(targetUuid);
            if (ban != null) {
                deindexPunishment(ban, unpunisher, reason);
                found = true;
            }
        }
        for (PunishmentRecord rec : punishmentsById.values()) {
            if (rec.isActive() && (rec.getType() == PunishmentType.BAN || rec.getType() == PunishmentType.IP_BAN)) {
                if (targetUuid != null && targetUuid.equals(rec.getTargetUuid())) {
                    deindexPunishment(rec, unpunisher, reason);
                    found = true;
                }
            }
        }
        if (found) {
            dirty.set(true);
            saveData();
        }
        return found;
    }

    public synchronized boolean unbanIp(String ip, String unpunisher, String reason) {
        String cleanIp = ip.replace("/", "").split(":")[0].trim();
        boolean found = false;
        PunishmentRecord ipBan = activeBansByIp.remove(cleanIp);
        if (ipBan != null) {
            deindexPunishment(ipBan, unpunisher, reason);
            found = true;
        }
        for (PunishmentRecord rec : punishmentsById.values()) {
            if (rec.isActive() && rec.getType() == PunishmentType.IP_BAN) {
                if (cleanIp.equalsIgnoreCase(rec.getTargetIp())) {
                    deindexPunishment(rec, unpunisher, reason);
                    found = true;
                }
            }
        }
        if (found) {
            dirty.set(true);
            saveData();
        }
        return found;
    }

    public synchronized boolean unmute(UUID targetUuid, String unpunisher, String reason) {
        boolean found = false;
        if (targetUuid != null) {
            PunishmentRecord mute = activeMutesByUuid.remove(targetUuid);
            if (mute != null) {
                deindexPunishment(mute, unpunisher, reason);
                found = true;
            }
        }
        for (PunishmentRecord rec : punishmentsById.values()) {
            if (rec.isActive() && rec.getType() == PunishmentType.MUTE) {
                if (targetUuid != null && targetUuid.equals(rec.getTargetUuid())) {
                    deindexPunishment(rec, unpunisher, reason);
                    found = true;
                }
            }
        }
        if (found) {
            dirty.set(true);
            saveData();
        }
        return found;
    }

    public synchronized int unwarn(UUID targetUuid, String idOrAll, String unpunisher) {
        int count = 0;
        List<PunishmentRecord> hist = historyByUuid.get(targetUuid);
        if (hist != null) {
            synchronized (hist) {
                for (PunishmentRecord rec : hist) {
                    if (rec.isActive() && rec.getType() == PunishmentType.WARN) {
                        if (idOrAll.equalsIgnoreCase("all") || rec.getId().equalsIgnoreCase(idOrAll) || rec.getId().replace("#", "").equalsIgnoreCase(idOrAll)) {
                            rec.unpunish(unpunisher, "Ogohlantirish olib tashlandi");
                            count++;
                            if (!idOrAll.equalsIgnoreCase("all")) {
                                break;
                            }
                        }
                    }
                }
            }
        }
        if (count > 0) {
            dirty.set(true);
            saveData();
        }
        return count;
    }

    public int getTotalBansCount(UUID targetUuid) {
        if (targetUuid == null) return 0;
        List<PunishmentRecord> hist = historyByUuid.get(targetUuid);
        if (hist == null) return 0;
        int count = 0;
        synchronized (hist) {
            for (PunishmentRecord p : hist) {
                if (p.getType() == PunishmentType.BAN || p.getType() == PunishmentType.IP_BAN) count++;
            }
        }
        return count;
    }

    public int getTotalMutesCount(UUID targetUuid) {
        if (targetUuid == null) return 0;
        List<PunishmentRecord> hist = historyByUuid.get(targetUuid);
        if (hist == null) return 0;
        int count = 0;
        synchronized (hist) {
            for (PunishmentRecord p : hist) {
                if (p.getType() == PunishmentType.MUTE) count++;
            }
        }
        return count;
    }

    public int getTotalWarnsCount(UUID targetUuid) {
        if (targetUuid == null) return 0;
        List<PunishmentRecord> hist = historyByUuid.get(targetUuid);
        if (hist == null) return 0;
        int count = 0;
        synchronized (hist) {
            for (PunishmentRecord p : hist) {
                if (p.getType() == PunishmentType.WARN) count++;
            }
        }
        return count;
    }

    public int getTotalKicksCount(UUID targetUuid) {
        if (targetUuid == null) return 0;
        List<PunishmentRecord> hist = historyByUuid.get(targetUuid);
        if (hist == null) return 0;
        int count = 0;
        synchronized (hist) {
            for (PunishmentRecord p : hist) {
                if (p.getType() == PunishmentType.KICK) count++;
            }
        }
        return count;
    }

    public int getGlobalTotalBans() {
        return (int) punishmentsById.values().stream()
                .filter(rec -> rec.getType() == PunishmentType.BAN || rec.getType() == PunishmentType.IP_BAN)
                .count();
    }

    public int getGlobalActiveBans() {
        return activeBansByUuid.size();
    }

    public int getGlobalTotalMutes() {
        return (int) punishmentsById.values().stream()
                .filter(rec -> rec.getType() == PunishmentType.MUTE)
                .count();
    }

    public int getGlobalActiveMutes() {
        return activeMutesByUuid.size();
    }

    public int getGlobalTotalWarns() {
        return (int) punishmentsById.values().stream()
                .filter(rec -> rec.getType() == PunishmentType.WARN)
                .count();
    }

    public int getGlobalTotalKicks() {
        return (int) punishmentsById.values().stream()
                .filter(rec -> rec.getType() == PunishmentType.KICK)
                .count();
    }

    public List<PunishmentRecord> getHistory(UUID targetUuid) {
        if (targetUuid == null) return Collections.emptyList();
        List<PunishmentRecord> list = historyByUuid.get(targetUuid);
        if (list == null) return Collections.emptyList();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    public List<PunishmentRecord> getStaffHistory(String staffName) {
        if (staffName == null) return Collections.emptyList();
        return punishmentsById.values().stream()
                .filter(rec -> staffName.equalsIgnoreCase(rec.getPunisherName()))
                .sorted((a, b) -> Long.compare(b.getStartTime(), a.getStartTime()))
                .collect(Collectors.toList());
    }

    public PunishmentRecord getRecordById(String id) {
        return punishmentsById.get(id);
    }

    public synchronized void loadData() {
        punishmentsById.clear();
        activeBansByUuid.clear();
        activeBansByIp.clear();
        activeMutesByUuid.clear();
        historyByUuid.clear();

        if (!file.exists()) return;
        config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = config.getConfigurationSection("punishments");
        if (sec == null) return;

        int maxId = 1000;
        for (String idKey : sec.getKeys(false)) {
            try {
                ConfigurationSection itemSec = sec.getConfigurationSection(idKey);
                if (itemSec == null) continue;
                Map<String, Object> values = itemSec.getValues(false);
                values.put("id", idKey);
                PunishmentRecord record = PunishmentRecord.deserialize(values);
                punishmentsById.put(idKey, record);
                indexPunishment(record);

                try {
                    int num = Integer.parseInt(idKey.replace("#", ""));
                    if (num > maxId) maxId = num;
                } catch (NumberFormatException ignored) {}
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error loading punishment ID: " + idKey, e);
            }
        }
        idCounter.set(maxId);
        dirty.set(false);
    }

    public synchronized void saveData() {
        if (!dirty.getAndSet(false)) {
            return; // Zero disk I/O overhead if no changes were made
        }
        config = new YamlConfiguration();
        ConfigurationSection sec = config.createSection("punishments");
        for (Map.Entry<String, PunishmentRecord> entry : punishmentsById.entrySet()) {
            ConfigurationSection pSec = sec.createSection(entry.getKey());
            Map<String, Object> map = entry.getValue().serialize();
            for (Map.Entry<String, Object> val : map.entrySet()) {
                if (!val.getKey().equals("id") && val.getValue() != null) {
                    pSec.set(val.getKey(), val.getValue());
                }
            }
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save punishments.yml", e);
        }
    }
}
