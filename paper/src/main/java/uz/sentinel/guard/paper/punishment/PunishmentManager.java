package uz.sentinel.guard.paper.punishment;

import uz.sentinel.guard.network.IpAddresses;
import uz.sentinel.guard.network.NetworkPunishment;
import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.network.NetworkService;
import uz.sentinel.guard.paper.util.DataFiles;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Punishment cache plus persistence. Local mode persists to punishments.yml; network mode treats
 * SQL as authoritative and only updates this cache from committed rows. Mutations report through
 * callbacks that run on the server thread after the change is durable.
 */
public class PunishmentManager {
    private final GuardPlugin plugin;
    private final File file;
    private volatile NetworkService network;
    private volatile boolean networkUnavailable;

    // Primary store
    private final Map<String, PunishmentRecord> punishmentsById = new ConcurrentHashMap<>(128);
    private final Map<UUID, PunishmentRecord> punishmentsByNetworkId = new ConcurrentHashMap<>(128);

    // O(1) Secondary Indexes for instant non-blocking lookups
    private final Map<UUID, PunishmentRecord> activeBansByUuid = new ConcurrentHashMap<>(64);
    private final Map<String, PunishmentRecord> activeBansByIp = new ConcurrentHashMap<>(64);
    private final Map<UUID, PunishmentRecord> activeMutesByUuid = new ConcurrentHashMap<>(64);
    private final Map<UUID, List<PunishmentRecord>> historyByUuid = new ConcurrentHashMap<>(128);
    // Network freeze state: player -> id of the active FREEZE row, and the reverse for revocations.
    private final Map<UUID, UUID> activeFreezeByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> freezePlayerById = new ConcurrentHashMap<>();

    private final AtomicInteger idCounter = new AtomicInteger(1000);
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Object writeLock = new Object();

    public PunishmentManager(GuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "punishments.yml");
        this.loadData();
    }

    public boolean isNetwork() {
        return network != null;
    }

    public NetworkService getNetwork() {
        return network;
    }

    /** Switches to network mode and warms the cache with the most recent rows. */
    public void attachNetwork(NetworkService service, int cacheSize) {
        this.network = service;
        clearCache();
        service.submit(store -> store.recent(cacheSize), rows -> {
            for (int i = rows.size() - 1; i >= 0; i--) upsert(rows.get(i), false);
            plugin.getLogger().info("Loaded " + rows.size() + " punishments from the network database.");
        }, error -> plugin.getLogger().severe("Could not load punishments from the network database; lookups fall back to live queries."));
    }

    public void detachNetwork() {
        this.network = null;
    }

    /**
     * Network mode is configured but the database could not be reached. Logins are denied and
     * moderation commands refuse, instead of silently falling back to local data.
     */
    public void setNetworkUnavailable(boolean unavailable) {
        this.networkUnavailable = unavailable;
    }

    public boolean isNetworkUnavailable() {
        return networkUnavailable && network == null;
    }

    /** Tells a command sender that a network operation could not be saved. */
    public void reportDatabaseError(CommandSender sender) {
        fail(sender);
    }

    private void fail(CommandSender sender) {
        if (sender != null) {
            sender.sendMessage(plugin.getConfigManager().getPrefix() + plugin.getConfigManager().colorize(
                    plugin.getConfigManager().getConfig().getString("network.database-error-command",
                            "&cMa'lumotlar bazasi xatosi: amal saqlanmadi. Keyinroq qayta urinib ko'ring.")));
        }
    }

    public void createPunishment(PunishmentType type, UUID targetUuid, String targetName, String targetIp,
                                 CommandSender punisher, String reason, long durationMs, boolean silent,
                                 Consumer<PunishmentRecord> onCommitted) {
        UUID punisherUuid = (punisher instanceof Player) ? ((Player) punisher).getUniqueId() : null;
        String punisherName = (punisher != null) ? punisher.getName() : "Console";
        String cleanIp = IpAddresses.normalize(targetIp);
        String cleanReason = (reason != null && !reason.trim().isEmpty()) ? reason.trim() : "Qoidabuzarlik";
        NetworkService service = network;
        if (service == null && networkUnavailable) { fail(punisher); return; }

        if (service == null) {
            PunishmentRecord record = createLocal(type, targetUuid, targetName, cleanIp, punisherUuid, punisherName, cleanReason, durationMs, silent);
            onCommitted.accept(record);
            if (type == PunishmentType.WARN) checkWarnThresholds(targetUuid, targetName, cleanIp, punisher);
            return;
        }

        Instant now = Instant.now();
        boolean timed = type == PunishmentType.BAN || type == PunishmentType.IP_BAN || type == PunishmentType.MUTE;
        Instant expires = timed && durationMs > 0 ? now.plusMillis(durationMs) : null;
        String name = targetName == null || targetName.isBlank() || targetName.length() > 64 ? null : targetName;
        NetworkPunishment row;
        try {
            row = new NetworkPunishment(UUID.randomUUID(), targetUuid, name, cleanIp, toNetwork(type), cleanReason,
                    punisherUuid, punisherName, service.serverId(), now, expires, silent, null);
        } catch (IllegalArgumentException e) {
            if (punisher != null) punisher.sendMessage(plugin.getConfigManager().getPrefix() + plugin.getConfigManager().colorize("&c" + e.getMessage()));
            return;
        }
        service.submit(store -> {
            List<UUID> replaced = store.create(row);
            // Warn escalation needs every active warn, not only the cached window.
            List<NetworkPunishment> active = type == PunishmentType.WARN && targetUuid != null ? store.active(targetUuid, Instant.now()) : List.of();
            return new Object[]{replaced, active};
        }, result -> {
            @SuppressWarnings("unchecked") List<UUID> replaced = (List<UUID>) result[0];
            @SuppressWarnings("unchecked") List<NetworkPunishment> active = (List<NetworkPunishment>) result[1];
            markRevoked(replaced, "System", "Yangi jazo bilan almashtirildi");
            active.forEach(p -> upsert(p, false));
            PunishmentRecord record = upsert(row, false);
            service.publishChange();
            onCommitted.accept(record);
            if (type == PunishmentType.WARN) checkWarnThresholds(targetUuid, targetName, cleanIp, punisher);
        }, error -> fail(punisher));
    }

    private synchronized PunishmentRecord createLocal(PunishmentType type, UUID targetUuid, String targetName, String cleanIp,
                                                      UUID punisherUuid, String punisherName, String reason, long durationMs, boolean silent) {
        String id = "#" + idCounter.incrementAndGet();
        long startTime = System.currentTimeMillis();
        long endTime = (durationMs <= 0) ? -1 : (startTime + durationMs);

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
        requestSave();
        return record;
    }

    private static NetworkPunishment.Type toNetwork(PunishmentType type) {
        return switch (type) {
            case BAN -> NetworkPunishment.Type.BAN;
            case IP_BAN -> NetworkPunishment.Type.IP_BAN;
            case MUTE -> NetworkPunishment.Type.MUTE;
            case KICK -> NetworkPunishment.Type.KICK;
            case WARN -> NetworkPunishment.Type.WARN;
        };
    }

    /** Applies a committed row seen in the outbox (possibly from another server) and enforces it here. */
    public void applyRemote(NetworkPunishment row) {
        boolean known = punishmentsByNetworkId.containsKey(row.id());
        PunishmentRecord record = upsert(row, true);
        if (known || record == null) return;
        if (record.getType() == PunishmentType.KICK) {
            kickIfOnline(record, row);
            return;
        }
        if (!record.isActive()) return;
        if (plugin.getConfigManager().getConfig().getBoolean("network.broadcast-remote-punishments", false)
                && !row.server().equals(network == null ? "" : network.serverId())) {
            plugin.getPunishmentFormatter().broadcastPunishment(record);
        }
    }

    /** A kick issued on another server: carry it out here if the player is on this server and the kick is live. */
    private void kickIfOnline(PunishmentRecord record, NetworkPunishment row) {
        if (record.getTargetUuid() == null || Duration.between(row.createdAt(), Instant.now()).abs().getSeconds() > 30) return;
        Player online = Bukkit.getPlayer(record.getTargetUuid());
        if (online != null) online.kickPlayer(plugin.getConfigManager().colorize("&c" + record.getReason()));
    }

    /** Inserts or updates a network row in the cache; returns the cached record. Server thread only. */
    private PunishmentRecord upsert(NetworkPunishment row, boolean enforce) {
        if (row.type() == NetworkPunishment.Type.FREEZE) {
            syncFreeze(row, enforce);
            return null;
        }
        PunishmentRecord existing = punishmentsByNetworkId.get(row.id());
        if (existing != null) {
            if (row.revoked() && existing.getUnpunisherName() == null) {
                deindexPunishment(existing, row.revocation().actor(), row.revocation().reason());
            }
            return existing;
        }
        PunishmentRecord record = PunishmentRecord.fromNetwork(row);
        if (record == null) return null;
        punishmentsById.put(record.getId(), record);
        punishmentsByNetworkId.put(row.id(), record);
        indexPunishment(record);
        if (enforce && record.isActive()) enforce(record);
        return record;
    }

    /** Keeps the freeze index in step with a FREEZE row; applies the effect here when the player is online. */
    private void syncFreeze(NetworkPunishment row, boolean apply) {
        UUID player = row.player();
        if (player == null) return;
        if (row.activeAt(Instant.now())) {
            UUID previous = activeFreezeByPlayer.put(player, row.id());
            freezePlayerById.put(row.id(), player);
            if (previous != null && !previous.equals(row.id())) freezePlayerById.remove(previous);
            if (apply) applyFreezeLocal(player, true);
        } else {
            freezePlayerById.remove(row.id());
            activeFreezeByPlayer.remove(player, row.id());
            if (apply && !activeFreezeByPlayer.containsKey(player)) applyFreezeLocal(player, false);
        }
    }

    private void applyFreezeLocal(UUID uuid, boolean freeze) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null && online.isOnline()) {
            if (freeze) plugin.getFreezeManager().freezePlayer(online, null);
            else plugin.getFreezeManager().unfreezePlayer(online, null);
        } else if (!freeze) {
            plugin.getFreezeManager().release(uuid);
        }
    }

    public boolean hasActiveFreeze(UUID uuid) {
        return uuid != null && activeFreezeByPlayer.containsKey(uuid);
    }

    /** Network freeze; {@code done} receives false when the player was already frozen. */
    public void freeze(UUID target, String name, CommandSender issuer, Consumer<Boolean> done) {
        NetworkService service = network;
        if (service == null) { fail(issuer); return; }
        if (activeFreezeByPlayer.containsKey(target)) { done.accept(false); return; }
        UUID issuerId = issuer instanceof Player ? ((Player) issuer).getUniqueId() : null;
        String issuerName = issuer != null ? issuer.getName() : "Console";
        NetworkPunishment row = new NetworkPunishment(UUID.randomUUID(), target, name == null || name.isBlank() || name.length() > 64 ? null : name,
                null, NetworkPunishment.Type.FREEZE, "Freeze", issuerId, limit(issuerName, 128), service.serverId(), Instant.now(), null, false, null);
        service.submit(store -> store.create(row), replaced -> {
            markRevoked(replaced, "System", "Yangi jazo bilan almashtirildi");
            syncFreeze(row, true);
            service.publishChange();
            done.accept(true);
        }, error -> fail(issuer));
    }

    public void unfreeze(UUID target, CommandSender issuer, Consumer<Boolean> done) {
        if (network == null) { fail(issuer); return; }
        revokeNetwork(target, null, EnumSet.of(NetworkPunishment.Type.FREEZE), issuer != null ? issuer.getName() : "Console",
                "Muzdan chiqarildi", issuer, n -> {
            if (n > 0 && !activeFreezeByPlayer.containsKey(target)) applyFreezeLocal(target, false);
            done.accept(n > 0);
        });
    }

    private void markRevoked(Collection<UUID> ids, String by, String reason) {
        for (UUID id : ids) {
            UUID frozen = freezePlayerById.remove(id);
            if (frozen != null) activeFreezeByPlayer.remove(frozen, id);
            PunishmentRecord record = punishmentsByNetworkId.get(id);
            if (record != null && record.getUnpunisherName() == null) deindexPunishment(record, by, reason);
        }
    }

    /** Kicks online players affected by a ban that arrived from the network. */
    private void enforce(PunishmentRecord record) {
        if (record.getType() != PunishmentType.BAN && record.getType() != PunishmentType.IP_BAN) return;
        String screen = plugin.getPunishmentFormatter().buildBanScreen(record);
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean account = p.getUniqueId().equals(record.getTargetUuid());
            boolean address = record.getType() == PunishmentType.IP_BAN && record.getTargetIp() != null
                    && record.getTargetIp().equals(DataFiles.ip(p));
            if (account || address) p.kickPlayer(screen);
        }
    }

    private void indexPunishment(PunishmentRecord record) {
        if (record.getTargetUuid() != null) {
            List<PunishmentRecord> history = historyByUuid.computeIfAbsent(record.getTargetUuid(), k -> Collections.synchronizedList(new ArrayList<>(4)));
            synchronized (history) {
                int index = 0;
                while (index < history.size() && history.get(index).getStartTime() >= record.getStartTime()) index++;
                history.add(index, record); // newest first
            }
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
                activeBansByUuid.remove(record.getTargetUuid(), record);
            }
            if (record.getType() == PunishmentType.MUTE) {
                activeMutesByUuid.remove(record.getTargetUuid(), record);
            }
        }
        if (record.getTargetIp() != null && record.getType() == PunishmentType.IP_BAN) {
            activeBansByIp.remove(record.getTargetIp(), record);
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
            createPunishment(PunishmentType.MUTE, targetUuid, targetName, targetIp, staff, reason, dur, false, rec -> {
                plugin.getPunishmentFormatter().broadcastPunishment(rec);
                Player targetPlayer = Bukkit.getPlayer(targetUuid);
                if (targetPlayer != null) {
                    targetPlayer.sendMessage(plugin.getConfigManager().getPrefix() +
                            plugin.getConfigManager().colorize("&cKo'p ogohlantirish olganingiz sababli vaqtincha ovozingiz o'chirildi!"));
                }
            });
        } else if (actType.equals("ban")) {
            createPunishment(PunishmentType.BAN, targetUuid, targetName, targetIp, staff, reason, dur, false, rec -> {
                plugin.getPunishmentFormatter().broadcastPunishment(rec);
                Player targetPlayer = Bukkit.getPlayer(targetUuid);
                if (targetPlayer != null) targetPlayer.kickPlayer(plugin.getPunishmentFormatter().buildBanScreen(rec));
            });
        } else if (actType.equals("kick")) {
            createPunishment(PunishmentType.KICK, targetUuid, targetName, targetIp, staff, reason, 0, false, rec -> {
                plugin.getPunishmentFormatter().broadcastPunishment(rec);
                Player targetPlayer = Bukkit.getPlayer(targetUuid);
                if (targetPlayer != null) {
                    targetPlayer.kickPlayer(plugin.getConfigManager().colorize("&cKo'p ogohlantirish sababli serverdan chiqarildingiz!"));
                }
            });
        }
    }

    /**
     * Login decision. In network mode this queries SQL directly and must be called off the server
     * thread (AsyncPlayerPreLoginEvent); database errors propagate so the caller can deny the login.
     */
    public PunishmentRecord checkLogin(UUID uuid, String ip) throws Exception {
        NetworkService service = network;
        if (service == null && networkUnavailable) throw new IllegalStateException("Network database unavailable");
        if (service == null) return getActiveBan(uuid, ip);
        Instant now = Instant.now();
        List<NetworkPunishment> bans = service.store().activeBans(uuid, ip, now);
        List<NetworkPunishment> active = service.store().active(uuid, now);
        // Freeze state must be known before PlayerJoinEvent, so it is written here (maps are thread-safe).
        boolean frozen = false;
        for (NetworkPunishment p : active) {
            if (p.type() == NetworkPunishment.Type.FREEZE) {
                frozen = true;
                activeFreezeByPlayer.put(uuid, p.id());
                freezePlayerById.put(p.id(), uuid);
            }
        }
        if (!frozen) activeFreezeByPlayer.remove(uuid);
        List<NetworkPunishment> seen = new ArrayList<>(bans);
        seen.addAll(active);
        service.sync(() -> seen.forEach(p -> upsert(p, false))); // mute state ready before join
        return bans.isEmpty() ? null : PunishmentRecord.fromNetwork(bans.get(0));
    }

    /**
     * O(1) Instant Check for Active Bans (Pre-login)
     */
    public PunishmentRecord getActiveBan(UUID targetUuid, String ip) {
        if (targetUuid != null) {
            PunishmentRecord rec = activeBansByUuid.get(targetUuid);
            if (rec != null) {
                if (rec.isActive()) return rec;
                activeBansByUuid.remove(targetUuid, rec);
            }
        }
        String cleanIp = IpAddresses.normalize(ip);
        if (cleanIp != null) {
            PunishmentRecord rec = activeBansByIp.get(cleanIp);
            if (rec != null) {
                if (rec.isActive()) return rec;
                activeBansByIp.remove(cleanIp, rec);
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
            activeMutesByUuid.remove(targetUuid, rec);
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

    private void revokeNetwork(UUID player, String ip, Set<NetworkPunishment.Type> types, String unpunisher, String reason,
                               CommandSender sender, Consumer<Integer> onCommitted) {
        NetworkService service = network;
        NetworkPunishment.Revocation revocation = new NetworkPunishment.Revocation(limit(unpunisher, 128), limit(reason, 2048), Instant.now());
        service.submit(store -> store.revokeActive(player, ip, types, revocation), ids -> {
            markRevoked(ids, revocation.actor(), revocation.reason());
            if (!ids.isEmpty()) service.publishChange();
            onCommitted.accept(ids.size());
        }, error -> fail(sender));
    }

    private static String limit(String value, int max) {
        String v = value == null || value.isBlank() ? "Bekor qilindi" : value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    public void unban(UUID targetUuid, CommandSender sender, String reason, Consumer<Boolean> onCommitted) {
        String unpunisher = sender != null ? sender.getName() : "Console";
        if (isNetworkUnavailable()) { fail(sender); return; }
        if (network != null) {
            if (targetUuid == null) { onCommitted.accept(false); return; }
            revokeNetwork(targetUuid, null, EnumSet.of(NetworkPunishment.Type.BAN, NetworkPunishment.Type.IP_BAN), unpunisher, reason, sender, n -> onCommitted.accept(n > 0));
            return;
        }
        onCommitted.accept(unbanLocal(targetUuid, unpunisher, reason));
    }

    private synchronized boolean unbanLocal(UUID targetUuid, String unpunisher, String reason) {
        boolean found = false;
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
            requestSave();
        }
        return found;
    }

    public void unbanIp(String ip, CommandSender sender, String reason, Consumer<Boolean> onCommitted) {
        String unpunisher = sender != null ? sender.getName() : "Console";
        String cleanIp = IpAddresses.normalize(ip);
        if (cleanIp == null) { onCommitted.accept(false); return; }
        if (isNetworkUnavailable()) { fail(sender); return; }
        if (network != null) {
            revokeNetwork(null, cleanIp, EnumSet.of(NetworkPunishment.Type.IP_BAN), unpunisher, reason, sender, n -> onCommitted.accept(n > 0));
            return;
        }
        onCommitted.accept(unbanIpLocal(cleanIp, unpunisher, reason));
    }

    private synchronized boolean unbanIpLocal(String cleanIp, String unpunisher, String reason) {
        boolean found = false;
        for (PunishmentRecord rec : punishmentsById.values()) {
            if (rec.isActive() && rec.getType() == PunishmentType.IP_BAN && cleanIp.equals(rec.getTargetIp())) {
                deindexPunishment(rec, unpunisher, reason);
                found = true;
            }
        }
        if (found) {
            dirty.set(true);
            requestSave();
        }
        return found;
    }

    public void unmute(UUID targetUuid, CommandSender sender, String reason, Consumer<Boolean> onCommitted) {
        String unpunisher = sender != null ? sender.getName() : "Console";
        if (isNetworkUnavailable()) { fail(sender); return; }
        if (network != null) {
            if (targetUuid == null) { onCommitted.accept(false); return; }
            revokeNetwork(targetUuid, null, EnumSet.of(NetworkPunishment.Type.MUTE), unpunisher, reason, sender, n -> onCommitted.accept(n > 0));
            return;
        }
        onCommitted.accept(unmuteLocal(targetUuid, unpunisher, reason));
    }

    private synchronized boolean unmuteLocal(UUID targetUuid, String unpunisher, String reason) {
        boolean found = false;
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
            requestSave();
        }
        return found;
    }

    public void unwarn(UUID targetUuid, String idOrAll, CommandSender sender, Consumer<Integer> onCommitted) {
        String unpunisher = sender != null ? sender.getName() : "Console";
        if (targetUuid == null) { onCommitted.accept(0); return; }
        if (isNetworkUnavailable()) { fail(sender); return; }
        NetworkService service = network;
        if (service == null) {
            onCommitted.accept(unwarnLocal(targetUuid, idOrAll, unpunisher));
            return;
        }
        if (idOrAll.equalsIgnoreCase("all")) {
            revokeNetwork(targetUuid, null, EnumSet.of(NetworkPunishment.Type.WARN), unpunisher, "Ogohlantirish olib tashlandi", sender, onCommitted);
            return;
        }
        PunishmentRecord target = findActiveWarn(targetUuid, idOrAll);
        if (target == null || target.getNetworkId() == null) { onCommitted.accept(0); return; }
        NetworkPunishment.Revocation revocation = new NetworkPunishment.Revocation(limit(unpunisher, 128), "Ogohlantirish olib tashlandi", Instant.now());
        service.submit(store -> store.revoke(target.getNetworkId(), revocation), changed -> {
            if (changed) {
                markRevoked(List.of(target.getNetworkId()), revocation.actor(), revocation.reason());
                service.publishChange();
            }
            onCommitted.accept(changed ? 1 : 0);
        }, error -> fail(sender));
    }

    private PunishmentRecord findActiveWarn(UUID targetUuid, String id) {
        List<PunishmentRecord> hist = historyByUuid.get(targetUuid);
        if (hist == null) return null;
        synchronized (hist) {
            for (PunishmentRecord rec : hist) {
                if (rec.isActive() && rec.getType() == PunishmentType.WARN
                        && (rec.getId().equalsIgnoreCase(id) || rec.getId().replace("#", "").equalsIgnoreCase(id))) {
                    return rec;
                }
            }
        }
        return null;
    }

    private synchronized int unwarnLocal(UUID targetUuid, String idOrAll, String unpunisher) {
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
            requestSave();
        }
        return count;
    }

    /** Loads the full history of a player from SQL before running {@code then}; immediate in local mode. */
    public void refreshHistory(UUID targetUuid, Runnable then) {
        NetworkService service = network;
        if (service == null || targetUuid == null) { then.run(); return; }
        int limit = plugin.getConfigManager().getConfig().getInt("network.history-limit", 500);
        service.submit(store -> store.history(targetUuid, limit), rows -> {
            rows.forEach(p -> upsert(p, false));
            then.run();
        }, error -> then.run());
    }

    public void refreshStaffHistory(String staffName, Runnable then) {
        NetworkService service = network;
        if (service == null || staffName == null || staffName.isBlank() || staffName.length() > 128) { then.run(); return; }
        int limit = plugin.getConfigManager().getConfig().getInt("network.history-limit", 500);
        service.submit(store -> store.byActor(staffName, limit), rows -> {
            rows.forEach(p -> upsert(p, false));
            then.run();
        }, error -> then.run());
    }

    public int getTotalBansCount(UUID targetUuid) {
        return countHistory(targetUuid, PunishmentType.BAN, PunishmentType.IP_BAN);
    }

    public int getTotalMutesCount(UUID targetUuid) {
        return countHistory(targetUuid, PunishmentType.MUTE);
    }

    public int getTotalWarnsCount(UUID targetUuid) {
        return countHistory(targetUuid, PunishmentType.WARN);
    }

    public int getTotalKicksCount(UUID targetUuid) {
        return countHistory(targetUuid, PunishmentType.KICK);
    }

    private int countHistory(UUID targetUuid, PunishmentType... types) {
        if (targetUuid == null) return 0;
        List<PunishmentRecord> hist = historyByUuid.get(targetUuid);
        if (hist == null) return 0;
        Set<PunishmentType> wanted = EnumSet.copyOf(Arrays.asList(types));
        int count = 0;
        synchronized (hist) {
            for (PunishmentRecord p : hist) {
                if (wanted.contains(p.getType())) count++;
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

    private void clearCache() {
        punishmentsById.clear();
        punishmentsByNetworkId.clear();
        activeBansByUuid.clear();
        activeBansByIp.clear();
        activeMutesByUuid.clear();
        historyByUuid.clear();
        activeFreezeByPlayer.clear();
        freezePlayerById.clear();
    }

    /** Reloads local data. Pending local changes are written first so a reload never discards them. */
    public void loadData() {
        if (network != null) return; // network cache is driven by SQL and the outbox
        saveData(); // takes writeLock then this; never call it while holding this
        synchronized (this) {
            loadLocal();
        }
    }

    private void loadLocal() {
        clearCache();

        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
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
                if (record.getTargetIp() != null) record.setTargetIp(IpAddresses.normalize(record.getTargetIp()));
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

    private void requestSave() {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveData);
        } else {
            saveData();
        }
    }

    /** Snapshot under the manager lock, then write outside it so the server thread never waits on disk. */
    public void saveData() {
        if (network != null) return;
        synchronized (writeLock) {
            String data;
            synchronized (this) {
                if (!dirty.getAndSet(false)) {
                    return; // Zero disk I/O overhead if no changes were made
                }
                YamlConfiguration config = new YamlConfiguration();
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
                data = config.saveToString();
            }
            try {
                DataFiles.writeAtomically(file, data);
            } catch (IOException e) {
                dirty.set(true);
                plugin.getLogger().log(Level.SEVERE, "Could not save punishments.yml", e);
            }
        }
    }
}
