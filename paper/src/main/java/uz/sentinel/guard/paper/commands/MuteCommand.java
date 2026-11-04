package uz.sentinel.guard.paper.commands;

import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.util.DataFiles;
import uz.sentinel.guard.paper.config.ConfigManager;
import uz.sentinel.guard.paper.punishment.PunishmentRecord;
import uz.sentinel.guard.paper.punishment.PunishmentType;
import uz.sentinel.guard.paper.punishment.TimeParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class MuteCommand implements CommandExecutor, TabCompleter {
    private final GuardPlugin plugin;

    public MuteCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        if (!sender.hasPermission("guard.mute") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            sender.sendMessage(config.getMessage("no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(config.getPrefix() + config.colorize("&cSintaksis: &f/" + label + " <o'yinchi> [vaqt] [sabab] [-s]"));
            sender.sendMessage(config.colorize("&7Misol (Abadiy): &f/" + label + " Steve Haqorat"));
            sender.sendMessage(config.colorize("&7Misol (Vaqtinchalik): &f/" + label + " Steve 1h Spambot"));
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
        String reason = "Chat qoidabuzarligi";

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
            sender.sendMessage(config.getPrefix() + config.colorize("&cSiz o'zingizni mute qila olmaysiz!"));
            return true;
        }

        String ip = plugin.getAltManager().getLatestIp(targetUuid);
        Player targetOnline = Bukkit.getPlayer(targetUuid);
        if (targetOnline != null && targetOnline.isOnline() && targetOnline.getAddress() != null) {
            ip = DataFiles.ip(targetOnline);
        }

        String finalReason = reason;
        plugin.getPunishmentManager().createPunishment(
                PunishmentType.MUTE, targetUuid, targetName, ip, sender, reason, durationMs, silent, record -> {
            plugin.getPunishmentFormatter().broadcastPunishment(record);
            if (targetOnline != null && targetOnline.isOnline()) {
                String durText = record.isPermanent() ? "Abadiy" : TimeParser.formatDuration(record.getDurationMs());
                targetOnline.sendMessage(config.getPrefix() + config.colorize("&cChatdan chetlashtirildingiz! Muddat: &e" + durText + " &7| Sabab: &f" + finalReason));
            }
        });

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("guard.mute") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> names = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            return filter(names, args[0]);
        } else if (args.length == 2) {
            List<String> durations = Arrays.asList("15m", "30m", "1h", "6h", "12h", "1d", "7d", "perm", "Haqorat", "Spam", "Reklama", "-s");
            return filter(durations, args[1]);
        } else if (args.length == 3) {
            List<String> reasons = Arrays.asList("Haqorat", "Spam", "Reklama", "Nojo'ya so'zlar", "Kamsitish", "-s");
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
