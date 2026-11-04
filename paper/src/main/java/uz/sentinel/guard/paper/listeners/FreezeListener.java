package uz.sentinel.guard.paper.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.util.DataFiles;
import uz.sentinel.guard.paper.config.ConfigManager;
import uz.sentinel.guard.paper.punishment.PunishmentRecord;
import uz.sentinel.guard.paper.punishment.PunishmentType;
import uz.sentinel.guard.paper.sus.FreezeManager;
import org.bukkit.Bukkit;
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getFreezeManager().hasAnyFrozen()) return;
        Player player = event.getPlayer();
        if (plugin.getFreezeManager().isFrozen(player.getUniqueId())) {
            ConfigManager config = plugin.getConfigManager();
            boolean autoBan = config.getConfig().getBoolean("freeze.auto-ban-on-leave", true);
            String reason = config.getConfig().getString("freeze.auto-ban-reason", "Tekshiruv (SS) paytida serverdan chiqish");

            if (autoBan) {
                long duration = config.getConfig().getLong("freeze.auto-ban-duration-days", 30L) * 24 * 60 * 60 * 1000L;
                plugin.getPunishmentManager().createPunishment(
                        PunishmentType.BAN, player.getUniqueId(), player.getName(), DataFiles.ip(player),
                        null, reason, duration, false, record -> plugin.getPunishmentFormatter().broadcastPunishment(record)
                );
            } else {
                String alert = config.colorize("&8[&cOGOHLANTIRISH&8] &e" + player.getName() + " &cmuzlatilgan holatda serverdan chiqib ketdi!");
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("guard.staff") || staff.hasPermission("sus.admin") || staff.isOp()) {
                        staff.sendMessage(alert);
                    }
                }
            }

            if (config.getConfig().getBoolean("settings.auto-unfreeze-on-leave", false)) {
                plugin.setFrozen(player.getUniqueId(), player.getName(), false, null, ok -> { });
            }
        }
    }
}
