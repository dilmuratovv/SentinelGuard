package uz.sentinel.guard.paper.sus;

import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.config.ConfigManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FreezeManager {
    private final GuardPlugin plugin;
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> freezeLocations = new ConcurrentHashMap<>();
    /** Standalone mode: frozen players who left without a ban (kick, lost connection); frozen again on return. */
    private final Set<UUID> pendingRefreeze = ConcurrentHashMap.newKeySet();
    /** Whether each frozen player could fly before (frozen in mid-air they get flight so they are not kicked). */
    private final Map<UUID, Boolean> hadFlight = new ConcurrentHashMap<>();
    private BukkitTask watchdog;
    private BukkitTask alertTask;

    public FreezeManager(GuardPlugin plugin) {
        this.plugin = plugin;
        this.startAlertTask();
        // Pulls frozen players back (riptide, knockback, a vehicle they were already in) and out of vehicles.
        this.watchdog = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            for (UUID uuid : this.frozenPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                Location at = this.freezeLocations.get(uuid);
                if (p == null || at == null) continue;
                if (p.isInsideVehicle()) p.leaveVehicle();
                if (p.isGliding()) p.setGliding(false);
                if (p.getWorld() != at.getWorld() || p.getLocation().distanceSquared(at) > 0.25) {
                    p.teleport(at, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                }
            }
        }, 10L, 10L);
    }

    public void remember(UUID uuid) { this.pendingRefreeze.add(uuid); }

    public boolean wasFrozen(UUID uuid) {
        BukkitTask deadline = this.returnDeadlines.remove(uuid);
        if (deadline != null) deadline.cancel();
        return this.pendingRefreeze.remove(uuid);
    }

    private final Set<UUID> staffKicks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> returnDeadlines = new ConcurrentHashMap<>();

    /** Guard's own /kick marks the player first, so the quit handler can tell a staff kick from an anticheat kick. */
    public void markStaffKick(UUID uuid) { this.staffKicks.add(uuid); }

    public boolean takeStaffKick(UUID uuid) { return this.staffKicks.remove(uuid); }

    /** Runs {@code missed} if the player is not back within the grace period (the join handler cancels it). */
    public void awaitReturn(UUID uuid, long graceSeconds, Runnable missed) {
        BukkitTask old = this.returnDeadlines.remove(uuid);
        if (old != null) old.cancel();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            this.returnDeadlines.remove(uuid);
            if (Bukkit.getPlayer(uuid) == null) missed.run();
        }, Math.max(1L, graceSeconds * 20L));
        this.returnDeadlines.put(uuid, task);
    }

    public boolean hasAnyFrozen() {
        return !this.frozenPlayers.isEmpty();
    }

    public boolean freezePlayer(Player player, CommandSender issuer) {
        if (this.frozenPlayers.contains(player.getUniqueId())) {
            return false;
        }
        // Out of any vehicle first: a boat or horse moves without the move event the freeze relies on.
        if (player.isInsideVehicle()) player.leaveVehicle();
        if (player.isGliding()) player.setGliding(false);
        player.setVelocity(new org.bukkit.util.Vector());
        this.frozenPlayers.add(player.getUniqueId());
        this.freezeLocations.put(player.getUniqueId(), player.getLocation().clone());
        if (!player.isOnGround() && !player.getAllowFlight()) {
            this.hadFlight.put(player.getUniqueId(), false);
            player.setAllowFlight(true); // held in mid-air: without this the server kicks them for flying
            player.setFlying(true);
        }
        ConfigManager config = this.plugin.getConfigManager();
        
        String title = config.colorize(config.getConfig().getString("freeze.title", "&c&lMUZLATILDINGIZ!"));
        String subtitle = config.colorize(config.getConfig().getString("freeze.subtitle", "&eTekshiruv uchun Discord serverimizga kiring!"));
        player.sendTitle(title, subtitle, 10, 70, 20);
        
        List<String> msgList = config.colorize(config.getConfig().getStringList("freeze.message"));
        for (String line : msgList) {
            player.sendMessage(line);
        }
        return true;
    }

    public boolean unfreezePlayer(Player player, CommandSender issuer) {
        if (!this.frozenPlayers.contains(player.getUniqueId())) {
            return false;
        }
        this.frozenPlayers.remove(player.getUniqueId());
        this.freezeLocations.remove(player.getUniqueId());
        this.pendingRefreeze.remove(player.getUniqueId());
        if (this.hadFlight.remove(player.getUniqueId()) != null) { player.setFlying(false); player.setAllowFlight(false); }
        ConfigManager config = this.plugin.getConfigManager();
        player.sendMessage(config.getPrefix() + config.colorize("&aMuzdan chiqarildingiz! Rahmat."));
        player.resetTitle();
        return true;
    }

    /**
     * The frozen player is leaving this server. Mid-air flight granted by the freeze is taken back before their data
     * is saved (otherwise they keep it), and the freeze is parked: re-applied, with title and position, when they
     * return (standalone) or by the network freeze row on join.
     */
    public void playerLeft(Player player, boolean keepForReturn) {
        UUID uuid = player.getUniqueId();
        if (this.hadFlight.remove(uuid) != null) { player.setFlying(false); player.setAllowFlight(false); }
        this.frozenPlayers.remove(uuid);
        this.freezeLocations.remove(uuid);
        if (keepForReturn) this.pendingRefreeze.add(uuid);
    }

    /** Forgets a freeze for a player who is not online here (it was lifted elsewhere in the network). */
    public void release(UUID uuid) {
        this.frozenPlayers.remove(uuid);
        this.freezeLocations.remove(uuid);
        this.pendingRefreeze.remove(uuid);
    }

    public boolean isFrozen(UUID uuid) {
        return this.frozenPlayers.contains(uuid);
    }

    public Location getFreezeLocation(UUID uuid) {
        return this.freezeLocations.get(uuid);
    }

    public Set<UUID> getFrozenPlayers() {
        return Collections.unmodifiableSet(this.frozenPlayers);
    }

    private void startAlertTask() {
        this.alertTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            if (this.frozenPlayers.isEmpty()) {
                return;
            }
            ConfigManager config = this.plugin.getConfigManager();
            String title = config.colorize(config.getConfig().getString("freeze.title", "&c&lMUZLATILDINGIZ!"));
            String subtitle = config.colorize(config.getConfig().getString("freeze.subtitle", "&eTekshiruv uchun Discord serverimizga kiring!"));
            String actionbar = config.colorize(config.getConfig().getString("freeze.actionbar", "&c&lHarakatlanish va chat yopildi!"));
            
            for (UUID uuid : this.frozenPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) continue;
                p.sendTitle(title, subtitle, 0, 40, 10);
                p.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(actionbar));
            }
        }, 20L, 40L);
    }

    public void shutdown() {
        if (this.alertTask != null) {
            this.alertTask.cancel();
        }
        if (this.watchdog != null) this.watchdog.cancel();
        for (BukkitTask task : this.returnDeadlines.values()) task.cancel();
        this.returnDeadlines.clear();
        this.frozenPlayers.clear();
        this.freezeLocations.clear();
    }
}
