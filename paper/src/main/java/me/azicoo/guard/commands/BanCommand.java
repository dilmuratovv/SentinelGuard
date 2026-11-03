package me.azicoo.guard.commands;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.punishment.PunishmentRecord;
import me.azicoo.guard.punishment.PunishmentType;
import me.azicoo.guard.punishment.TimeParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class BanCommand implements CommandExecutor, TabCompleter {
    private final GuardPlugin plugin;

    public BanCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        if (!sender.hasPermission("guard.ban") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            sender.sendMessage(config.getMessage("no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(config.getPrefix() + config.colorize("&cSintaksis: &f/" + label + " <o'yinchi> [vaqt] [sabab] [-s]"));
            sender.sendMessage(config.colorize("&7Misol (Abadiy): &f/" + label + " Steve Cheating"));
            sender.sendMessage(config.colorize("&7Misol (Vaqtinchalik): &f/" + label + " Steve 1d Cheating"));
            return true;
        }

        String targetName = args[0];
        boolean silent = false;
        List<String> remainingArgs = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("-s") || args[i].equalsIgnoreCase("--silent")) {
                silent = true;
            } else {
                remainingArgs.add(args[i]);
            }
        }

        long durationMs = -1; // Default: Permanent
        String reason = "Qoidabuzarlik";

        if (!remainingArgs.isEmpty()) {
            String firstToken = remainingArgs.get(0);
            if (TimeParser.isDuration(firstToken)) {
                durationMs = TimeParser.parseDuration(firstToken);
                remainingArgs.remove(0);
            }
            if (!remainingArgs.isEmpty()) {
                reason = String.join(" ", remainingArgs).trim();
            }
        }

        // Get target UUID
        UUID targetUuid = plugin.getAltManager().getUuidByName(targetName);
        OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
        if (targetUuid == null && op != null && (op.hasPlayedBefore() || op.isOnline() || op.getName() != null)) {
            targetUuid = op.getUniqueId();
            if (op.getName() != null) targetName = op.getName();
        } else if (targetUuid == null) {
            targetUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + targetName).getBytes());
        }

        // Prevent targeting immune staff/admin/bypass
        if (plugin.isImmune(sender, targetUuid)) {
            sender.sendMessage(config.getMessage("cannot-target-staff"));
            return true;
        }

        if (sender instanceof Player && ((Player) sender).getUniqueId().equals(targetUuid)) {
            sender.sendMessage(config.getPrefix() + config.colorize("&cSiz o'zingizni ban qila olmaysiz!"));
            return true;
        }

        String ip = plugin.getAltManager().getLatestIp(targetUuid);
        Player targetOnline = Bukkit.getPlayer(targetUuid);
        if (targetOnline != null && targetOnline.isOnline() && targetOnline.getAddress() != null) {
            ip = targetOnline.getAddress().getAddress().getHostAddress();
        }

        PunishmentRecord record = plugin.getPunishmentManager().createPunishment(
                PunishmentType.BAN, targetUuid, targetName, ip, sender, reason, durationMs, silent
        );

        plugin.getPunishmentFormatter().broadcastPunishment(record);

        if (targetOnline != null && targetOnline.isOnline()) {
            String kickScreen = plugin.getPunishmentFormatter().buildBanScreen(record);
            Bukkit.getScheduler().runTask(plugin, () -> targetOnline.kickPlayer(kickScreen));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("guard.ban") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            return filter(names, args[0]);
        } else if (args.length == 2) {
            List<String> durations = Arrays.asList("15m", "1h", "12h", "1d", "3d", "7d", "30d", "perm", "Hacking", "Cheating", "Griefing", "-s");
            return filter(durations, args[1]);
        } else if (args.length == 3) {
            List<String> reasons = Arrays.asList("Hacking", "Cheating", "Griefing", "Spam", "Insult", "Bug abuse", "-s");
            return filter(reasons, args[2]);
        } else if (args.length == 4) {
            return filter(List.of("-s"), args[3]);
        }

        return Collections.emptyList();
    }

    private List<String> filter(List<String> list, String query) {
        String lower = query.toLowerCase();
        return list.stream().filter(s -> s.toLowerCase().startsWith(lower)).collect(Collectors.toList());
    }
}
