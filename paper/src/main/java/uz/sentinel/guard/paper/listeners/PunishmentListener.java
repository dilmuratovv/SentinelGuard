package uz.sentinel.guard.paper.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.config.ConfigManager;
import uz.sentinel.guard.paper.punishment.PunishmentRecord;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import uz.sentinel.guard.network.IpAddresses;
import uz.sentinel.guard.paper.util.DataFiles;

public class PunishmentListener implements Listener {
    private final GuardPlugin plugin;

    public PunishmentListener(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();
        InetAddress address = event.getAddress();
        String ip = address != null ? IpAddresses.normalize(address.getHostAddress()) : null;

        PunishmentRecord activeBan;
        try {
            // Async login thread: network mode queries SQL here, never on the server thread.
            activeBan = plugin.getPunishmentManager().checkLogin(uuid, ip);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Login punishment check failed for " + uuid, e);
            if (plugin.getConfigManager().getConfig().getBoolean("network.deny-login-on-database-error", true)) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, plugin.getConfigManager().colorize(
                        plugin.getConfigManager().getConfig().getString("network.database-error-login",
                                "&cModeratsiya xizmati vaqtincha ishlamayapti. Keyinroq qayta urinib ko'ring.")));
            }
            return;
        }
        if (activeBan != null) {
            String kickMessage = plugin.getPunishmentFormatter().buildBanScreen(activeBan);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, kickMessage);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String ip = DataFiles.ip(player);
        plugin.getAltManager().recordLogin(player.getUniqueId(), player.getName(), ip);
        if (plugin.getPunishmentManager().hasActiveFreeze(player.getUniqueId())) {
            plugin.getFreezeManager().freezePlayer(player, null);
        }

        // Check if player's IP has banned alts
        Set<UUID> alts = plugin.getAltManager().getAltsByIp(ip);
        if (alts.size() > 1) {
            for (UUID altUuid : alts) {
                if (altUuid.equals(player.getUniqueId())) continue;
                PunishmentRecord bannedAlt = plugin.getPunishmentManager().getActiveBan(altUuid, ip);
                if (bannedAlt != null) {
                    String altName = plugin.getAltManager().getNameByUuid(altUuid);
                    String alert = plugin.getConfigManager().colorize(
                            plugin.getConfigManager().getConfig().getString("alt-alert-format",
                                    "&8[&cALT-OGOHLANTIRISH&8] &e{player} &7o'yinchi banlangan &c{banned_player}&7 bilan bir xil IP orqali kirdi!"))
                            .replace("{player}", player.getName())
                            .replace("{banned_player}", altName);

                    for (Player staff : Bukkit.getOnlinePlayers()) {
                        if (staff.hasPermission("guard.staff") || staff.hasPermission("sus.admin") || staff.isOp()) {
                            staff.sendMessage(alert);
                        }
                    }
                    Bukkit.getConsoleSender().sendMessage(alert);
                    break;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPaperChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PunishmentRecord activeMute = plugin.getPunishmentManager().getActiveMute(player.getUniqueId());
        if (activeMute != null) {
            event.setCancelled(true);
            List<String> messages = plugin.getPunishmentFormatter().buildMuteAttemptMessage(activeMute);
            for (String msg : messages) {
                player.sendMessage(msg);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PunishmentRecord activeMute = plugin.getPunishmentManager().getActiveMute(player.getUniqueId());
        if (activeMute != null) {
            event.setCancelled(true);
            List<String> messages = plugin.getPunishmentFormatter().buildMuteAttemptMessage(activeMute);
            for (String msg : messages) {
                player.sendMessage(msg);
            }
        }
    }
}
