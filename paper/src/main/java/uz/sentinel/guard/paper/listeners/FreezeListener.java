package uz.sentinel.guard.paper.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.util.DataFiles;
import uz.sentinel.guard.paper.config.ConfigManager;
import uz.sentinel.guard.paper.punishment.PunishmentRecord;
import uz.sentinel.guard.paper.punishment.PunishmentType;
import uz.sentinel.guard.paper.sus.FreezeManager;
import org.bukkit.Bukkit;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;

import java.util.List;

public class FreezeListener implements Listener {
    private final GuardPlugin plugin;

    public FreezeListener(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        FreezeManager freezeManager = plugin.getFreezeManager();
        // 0-CPU Instant Guard: if no player is frozen on the server, return immediately!
        if (!freezeManager.hasAnyFrozen()) {
            return;
        }

        Player player = event.getPlayer();
        if (freezeManager.isFrozen(player.getUniqueId())) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (to == null) return;
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ() ||
                    from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) {
                Location freezeLoc = freezeManager.getFreezeLocation(player.getUniqueId());
                if (freezeLoc != null) {
                    event.setTo(freezeLoc.clone());
                } else {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPaperChat(AsyncChatEvent event) {
        if (!plugin.getFreezeManager().hasAnyFrozen()) return;
        Player player = event.getPlayer();
        if (plugin.getFreezeManager().isFrozen(player.getUniqueId())) {
            event.setCancelled(true);
            ConfigManager config = plugin.getConfigManager();
            player.sendMessage(config.getPrefix() + config.colorize("&cMuzlatilgan paytda umumiy chatdan foydalana olmaysiz!"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        if (!plugin.getFreezeManager().hasAnyFrozen()) return;
        Player player = event.getPlayer();
        if (plugin.getFreezeManager().isFrozen(player.getUniqueId())) {
            event.setCancelled(true);
            ConfigManager config = plugin.getConfigManager();
            player.sendMessage(config.getPrefix() + config.colorize("&cMuzlatilgan paytda umumiy chatdan foydalana olmaysiz!"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getFreezeManager().hasAnyFrozen()) return;
        Player player = event.getPlayer();
        if (plugin.getFreezeManager().isFrozen(player.getUniqueId())) {
            String message = event.getMessage().toLowerCase();
            String command = message.split(" ")[0];
            ConfigManager config = plugin.getConfigManager();
            List<String> allowed = config.getConfig().getStringList("freeze.allowed-commands");

            boolean isAllowed = false;
            for (String cmd : allowed) {
                if (command.equalsIgnoreCase(cmd)) {
                    isAllowed = true;
                    break;
                }
            }

            if (!isAllowed) {
                event.setCancelled(true);
                player.sendMessage(config.getPrefix() + config.colorize("&cMuzlatilgan paytda bu buyruqdan foydalana olmaysiz!"));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getFreezeManager().hasAnyFrozen()) return;
        if (plugin.getFreezeManager().isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getFreezeManager().hasAnyFrozen()) return;
        if (plugin.getFreezeManager().isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.getFreezeManager().hasAnyFrozen()) return;
        if (plugin.getFreezeManager().isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.getFreezeManager().hasAnyFrozen()) return;
        if (plugin.getFreezeManager().isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getFreezeManager().hasAnyFrozen()) return;
        if (event.getDamager() instanceof Player) {
            Player damager = (Player) event.getDamager();
            if (plugin.getFreezeManager().isFrozen(damager.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            if (plugin.getFreezeManager().isFrozen(victim.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    // ------------------------------------------------------------------ ways out of a freeze

    /** A frozen player cannot get into a boat, onto a horse or into a minecart and ride away. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMount(org.bukkit.event.entity.EntityMountEvent event) {
        if (plugin.getFreezeManager().hasAnyFrozen() && event.getEntity() instanceof Player p
                && plugin.getFreezeManager().isFrozen(p.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(org.bukkit.event.vehicle.VehicleEnterEvent event) {
        if (plugin.getFreezeManager().hasAnyFrozen() && event.getEntered() instanceof Player p
                && plugin.getFreezeManager().isFrozen(p.getUniqueId())) event.setCancelled(true);
    }

    /** Ender pearls, chorus fruit and portals move a player without a move event the freeze could undo. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!plugin.getFreezeManager().hasAnyFrozen() || !plugin.getFreezeManager().isFrozen(event.getPlayer().getUniqueId())) return;
        switch (event.getCause()) {
            case ENDER_PEARL, CHORUS_FRUIT, NETHER_PORTAL, END_PORTAL, END_GATEWAY, SPECTATE -> event.setCancelled(true);
            default -> { } // staff and plugin teleports stay possible
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (plugin.getFreezeManager().hasAnyFrozen() && plugin.getFreezeManager().isFrozen(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLaunch(org.bukkit.event.entity.ProjectileLaunchEvent event) {
        if (plugin.getFreezeManager().hasAnyFrozen() && event.getEntity().getShooter() instanceof Player p
                && plugin.getFreezeManager().isFrozen(p.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlide(org.bukkit.event.entity.EntityToggleGlideEvent event) {
        if (event.isGliding() && plugin.getFreezeManager().hasAnyFrozen() && event.getEntity() instanceof Player p
                && plugin.getFreezeManager().isFrozen(p.getUniqueId())) event.setCancelled(true);
    }

    /** What leaving while frozen leads to. */
    enum Leave { BAN_NOW, AWAIT_RETURN, KEEP_FROZEN, PROXY_DECIDES }

    /**
     * A plain disconnect is leaving on purpose: banned at once. A lost connection, a client error or a kick that
     * Guard's staff did not issue can be provoked by a cheat client (stop answering keep-alives, send a bad packet
     * to get an anticheat kick), so the player must come back within the grace period, frozen again, or is banned.
     * A staff kick and a server stop keep the freeze for the return without a deadline. On a network the proxy
     * decides: only it can tell leaving the network from switching servers, and it refuses switches while frozen.
     */
    static Leave leave(PlayerQuitEvent.QuitReason reason, boolean stopping, boolean network, boolean staffKick) {
        if (network) return Leave.PROXY_DECIDES;
        if (stopping) return Leave.KEEP_FROZEN;
        if (reason == PlayerQuitEvent.QuitReason.DISCONNECTED) return Leave.BAN_NOW;
        if (reason == PlayerQuitEvent.QuitReason.KICKED && staffKick) return Leave.KEEP_FROZEN;
        return Leave.AWAIT_RETURN;
    }

    static boolean leftOnPurpose(PlayerQuitEvent.QuitReason reason, boolean stopping, boolean network) {
        return leave(reason, stopping, network, false) == Leave.BAN_NOW;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getStaffRegistry().observe(event.getPlayer());
        Player player = event.getPlayer();
        boolean staffKick = plugin.getFreezeManager().takeStaffKick(player.getUniqueId());
        if (!plugin.getFreezeManager().hasAnyFrozen() || !plugin.getFreezeManager().isFrozen(player.getUniqueId())) return;
        ConfigManager config = plugin.getConfigManager();
        var cfg = config.getConfig();
        boolean autoBan = cfg.getBoolean("freeze.auto-ban-on-leave", true);
        long grace = Math.max(0, cfg.getLong("freeze.return-grace-seconds", 180));
        Leave leave = leave(event.getReason(), Bukkit.isStopping(), plugin.getNetwork() != null, staffKick);
        if (leave == Leave.AWAIT_RETURN && grace == 0) leave = Leave.BAN_NOW;
        if (!autoBan && (leave == Leave.BAN_NOW || leave == Leave.AWAIT_RETURN)) leave = Leave.KEEP_FROZEN;
        UUID uuid = player.getUniqueId();
        String name = player.getName(), ip = DataFiles.ip(player);

        if (cfg.getBoolean("settings.auto-unfreeze-on-leave", false)) {
            plugin.setFrozen(uuid, name, false, null, ok -> { });
            return;
        }
        plugin.getFreezeManager().playerLeft(player, plugin.getNetwork() == null && leave != Leave.BAN_NOW);
        switch (leave) {
            case BAN_NOW -> banForLeaving(uuid, name, ip, cfg.getString("freeze.auto-ban-reason", "Tekshiruv (SS) paytida serverdan chiqish"));
            case AWAIT_RETURN -> {
                alert(config.colorize("&8[&cOGOHLANTIRISH&8] &e" + name + " &cmuzlatilgan holatda " + why(event.getReason())
                        + "&c. &f" + grace + " &csoniya ichida qaytmasa avtomatik ban oladi; qaytsa yana muzlatiladi."));
                plugin.getFreezeManager().awaitReturn(uuid, grace, () -> banForLeaving(uuid, name, ip,
                        cfg.getString("freeze.auto-ban-reason-no-return", "Tekshiruv (SS) paytida chiqib, qaytmadi")));
            }
            case KEEP_FROZEN -> {
                if (!Bukkit.isStopping()) alert(config.colorize("&8[&cOGOHLANTIRISH&8] &e" + name + " &cmuzlatilgan holatda "
                        + why(event.getReason()) + " &7(avto-ban yo'q; qaytsa yana muzlatiladi)."));
            }
            case PROXY_DECIDES -> alert(config.colorize("&8[&cOGOHLANTIRISH&8] &e" + name
                    + " &cmuzlatilgan holatda serverdan chiqdi &7(tarmoq: qarorni proxy qiladi)."));
        }
    }

    private static String why(PlayerQuitEvent.QuitReason reason) {
        return switch (reason) {
            case KICKED -> "kick qilindi";
            case TIMED_OUT -> "aloqasi uzildi (timeout)";
            case ERRONEOUS_STATE -> "xato bilan uzildi";
            default -> "serverdan chiqdi";
        };
    }

    private void alert(String message) {
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("guard.staff") || staff.hasPermission("sus.admin") || staff.isOp()) staff.sendMessage(message);
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }

    /** One ban, never on top of an existing one (a staff ban that kicked the player must not be replaced by 30 days). */
    private void banForLeaving(UUID uuid, String name, String ip, String reason) {
        plugin.getFreezeManager().wasFrozen(uuid); // drop the pending re-freeze
        if (plugin.getPunishmentManager().getActiveBan(uuid, ip) != null) return;
        long duration = plugin.getConfigManager().getConfig().getLong("freeze.auto-ban-duration-days", 30L) * 24 * 60 * 60 * 1000L;
        plugin.getPunishmentManager().createPunishment(PunishmentType.BAN, uuid, name, ip, null, reason,
                duration <= 0 ? -1 : duration, false, record -> plugin.getPunishmentFormatter().broadcastPunishment(record));
    }

    /** Standalone mode: a player who was frozen when they lost connection or were kicked is frozen again on return. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.getNetwork() == null && plugin.getFreezeManager().wasFrozen(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.getFreezeManager().freezePlayer(event.getPlayer(), null));
        }
    }
}
