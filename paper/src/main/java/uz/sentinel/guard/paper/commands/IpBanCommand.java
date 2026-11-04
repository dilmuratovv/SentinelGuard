package uz.sentinel.guard.paper.commands;

import uz.sentinel.guard.network.IpAddresses;
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

public class IpBanCommand implements CommandExecutor, TabCompleter {
    private final GuardPlugin plugin;

    public IpBanCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        if (!sender.hasPermission("guard.ipban") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            sender.sendMessage(config.getMessage("no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(config.getPrefix() + config.colorize("&cSintaksis: &f/" + label + " <o'yinchi|IP> [vaqt] [sabab] [-s]"));
            return true;
        }

        String targetInput = args[0];
        boolean silent = false;
        List<String> remaining = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("-s") || args[i].equalsIgnoreCase("--silent")) {
                silent = true;
            } else {
                remaining.add(args[i]);
            }
        }

        long durationMs = -1;
        String reason = "IP-Ban qoidabuzarlik";

        if (!remaining.isEmpty()) {
            String firstToken = remaining.get(0);
            if (TimeParser.isDuration(firstToken)) {
                durationMs = TimeParser.parseDuration(firstToken);
                remaining.remove(0);
            }
            if (!remaining.isEmpty()) {
                reason = String.join(" ", remaining).trim();
            }
        }

        String ip = null;
        UUID targetUuid = null;
        String targetName = targetInput;

        // Check if input is IP address
        if (IpAddresses.isIp(targetInput)) {
            ip = IpAddresses.normalize(targetInput);
        } else {
            Player online = Bukkit.getPlayer(targetInput);
            if (online != null && DataFiles.ip(online) != null) {
                ip = DataFiles.ip(online);
                targetUuid = online.getUniqueId();
                targetName = online.getName();
            } else {
                targetUuid = plugin.getAltManager().getUuidByName(targetInput);
                if (targetUuid == null) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(targetInput);
                    if (op != null) targetUuid = op.getUniqueId();
                }
                if (targetUuid != null) {
                    ip = plugin.getAltManager().getLatestIp(targetUuid);
                }
            }
        }

        if (targetUuid != null && plugin.isImmune(sender, targetUuid)) {
            sender.sendMessage(config.getMessage("cannot-target-staff"));
            return true;
        }

        if (ip == null || ip.isEmpty()) {
            sender.sendMessage(config.getPrefix() + config.colorize("&cO'yinchining IP manzili topilmadi!"));
            return true;
        }

        UUID finalTarget = targetUuid;
        plugin.getPunishmentManager().createPunishment(
                PunishmentType.IP_BAN, targetUuid, targetName, ip, sender, reason, durationMs, silent, record -> {
            plugin.getPunishmentFormatter().broadcastPunishment(record);
            // Kick all online players with this IP and the targeted account
            String kickScreen = plugin.getPunishmentFormatter().buildBanScreen(record);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getUniqueId().equals(finalTarget) || (record.getTargetIp() != null && record.getTargetIp().equals(DataFiles.ip(p)))) {
                    p.kickPlayer(kickScreen);
                }
            }
        });

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("guard.ipban") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
