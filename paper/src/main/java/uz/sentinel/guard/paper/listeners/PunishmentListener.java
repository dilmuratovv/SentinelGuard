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
        plugin.getStaffRegistry().observe(player);
        if (plugin.getPunishmentManager().hasActiveFreeze(player.getUniqueId())) {
            plugin.getFreezeManager().freezePlayer(player, null);
        }

        // Check if player's IP has banned alts
        Set<UUID> alts = plugin.getAltManager().getAltsByIp(ip);
        int accounts = alts.size();
        boolean shared = plugin.getAltManager().isShared(ip);
        if (shared && !plugin.getConfig().getBoolean("alts.alert-on-shared-ips", false)) return;
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
                            .replace("{banned_player}", altName)
                            + (shared ? plugin.getConfigManager().colorize(" &8(umumiy IP: " + accounts + " ta akkaunt, alt bo'lmasligi mumkin)") : "");

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
        PunishmentRecord activeMute = plugin.getPunishmentManager().getEnforcedMute(player.getUniqueId());
        if (activeMute != null) {
            event.setCancelled(true);
            List<String> messages = plugin.getPunishmentFormatter().buildMuteAttemptMessage(activeMute);
            for (String msg : messages) {
                player.sendMessage(msg);
            }
        }
    }

    /**
     * Everything else a muted player could talk through: private messages, /me and /say, party, clan, faction,
     * town and channel chats of common plugins. An entry with several words matches a subcommand ("party chat");
     * "*" stands for any one word, so "afk *" blocks an AFK message but not plain /afk.
     */
    public static final List<String> DEFAULT_MUTE_BLOCKED_COMMANDS = List.of(
            // vanilla and Essentials
            "msg", "tell", "w", "whisper", "m", "t", "pm", "dm", "r", "reply", "me", "say", "teammsg", "tm", "mail",
            "emsg", "etell", "ewhisper", "em", "er", "ereply", "eme", "esay", "email", "afk *", "eafk *", "away *",
            "broadcast", "bc", "shout", "yell",
            // party plugins (Parties, mcMMO, PartyAndFriends, Spigot party plugins)
            "pc", "pchat", "partychat", "party chat", "party msg", "party message", "p chat", "p msg", "p message",
            // clans, guilds, factions, towns, nations, teams
            "cc", "clanchat", "clan chat", "clan msg", "c chat", "gc", "guildchat", "guild chat", "g chat", "f chat",
            "f c", "f msg", "fc", "tc", "nc", "ac", "allychat", "ally chat", "town chat", "nation chat",
            "team chat", "team msg", "teamchat", "tchat", "is chat", "island chat", "ic", "coopchat",
            // channel chat plugins (VentureChat, ChatControl, HeroChat, TownyChat)
            "ch", "channel", "chat", "qm", "quickmessage", "local", "global", "l", "g",
            // item and pet text others can read
            "itemname", "rename", "itemrename", "lore", "itemlore", "nick", "nickname", "enick");

    /** The command label without slash and namespace, lower case ("/essentials:msg Bob hi" -> "msg"). */
    public static String commandLabel(String commandLine) {
        String line = commandLine.trim();
        if (line.startsWith("/")) line = line.substring(1);
        int space = line.indexOf(' ');
        String label = (space < 0 ? line : line.substring(0, space)).toLowerCase(java.util.Locale.ROOT);
        int colon = label.indexOf(':');
        return colon >= 0 ? label.substring(colon + 1) : label;
    }

    public static boolean blockedWhileMuted(String commandLine, List<String> blocked) {
        String label = commandLabel(commandLine);
        if (label.isEmpty()) return false;
        String line = commandLine.trim();
        if (line.startsWith("/")) line = line.substring(1);
        String[] words = line.toLowerCase(java.util.Locale.ROOT).split("\\s+");
        words[0] = label;
        for (String b : blocked) {
            String clean = b.trim().toLowerCase(java.util.Locale.ROOT);
            if (clean.startsWith("/")) clean = clean.substring(1);
            if (clean.isEmpty()) continue;
            String[] entry = clean.split("\\s+");
            int colon = entry[0].indexOf(':');
            if (colon >= 0) entry[0] = entry[0].substring(colon + 1);
            if (entry.length > words.length) continue;
            boolean match = true;
            for (int i = 0; i < entry.length && match; i++) match = entry[i].equals("*") || entry[i].equals(words[i]);
            if (match) return true;
        }
        return false;
    }

    /** The built-in list, plus mute-blocked-commands, minus mute-allowed-commands. */
    public static List<String> mutedCommands(org.bukkit.configuration.file.FileConfiguration config) {
        List<String> all = new java.util.ArrayList<>(DEFAULT_MUTE_BLOCKED_COMMANDS);
        all.addAll(config.getStringList("mute-blocked-commands"));
        List<String> allowed = config.getStringList("mute-allowed-commands");
        all.removeIf(entry -> allowed.stream().anyMatch(a -> a.trim().equalsIgnoreCase(entry.trim())));
        return all;
    }

    private void tellMuted(Player player, PunishmentRecord mute) {
        for (String msg : plugin.getPunishmentFormatter().buildMuteAttemptMessage(mute)) player.sendMessage(msg);
    }

    /** Sign text is public chat that stays: a muted player's signs are placed blank. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMutedSign(org.bukkit.event.block.SignChangeEvent event) {
        PunishmentRecord mute = plugin.getPunishmentManager().getEnforcedMute(event.getPlayer().getUniqueId());
        if (mute == null) return;
        boolean text = false;
        for (int i = 0; i < 4; i++) {
            String line = event.getLine(i);
            if (line != null && !line.isBlank()) { text = true; event.setLine(i, ""); }
        }
        if (text) tellMuted(event.getPlayer(), mute);
    }

    /** Books are handed around, put on lecterns and dropped: no writing or signing while muted. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMutedBook(org.bukkit.event.player.PlayerEditBookEvent event) {
        PunishmentRecord mute = plugin.getPunishmentManager().getEnforcedMute(event.getPlayer().getUniqueId());
        if (mute == null) return;
        event.setCancelled(true);
        tellMuted(event.getPlayer(), mute);
    }

    /** Anvil names show on dropped items, item frames and name-tagged mobs. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMutedRename(org.bukkit.event.inventory.PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        org.bukkit.inventory.ItemStack result = event.getResult();
        if (result == null || !result.hasItemMeta()) return;
        PunishmentRecord mute = plugin.getPunishmentManager().getEnforcedMute(player.getUniqueId());
        if (mute == null) return;
        org.bukkit.inventory.ItemStack input = event.getInventory().getItem(0);
        net.kyori.adventure.text.Component before = input != null && input.hasItemMeta() ? input.getItemMeta().displayName() : null;
        net.kyori.adventure.text.Component after = result.getItemMeta().displayName();
        if (after != null && !after.equals(before)) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMutedCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        PunishmentRecord activeMute = plugin.getPunishmentManager().getEnforcedMute(player.getUniqueId());
        if (activeMute == null) return;
        if (!blockedWhileMuted(event.getMessage(), mutedCommands(plugin.getConfigManager().getConfig()))) return;
        event.setCancelled(true);
        for (String msg : plugin.getPunishmentFormatter().buildMuteAttemptMessage(activeMute)) player.sendMessage(msg);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PunishmentRecord activeMute = plugin.getPunishmentManager().getEnforcedMute(player.getUniqueId());
        if (activeMute != null) {
            event.setCancelled(true);
            List<String> messages = plugin.getPunishmentFormatter().buildMuteAttemptMessage(activeMute);
            for (String msg : messages) {
                player.sendMessage(msg);
            }
        }
    }
}
