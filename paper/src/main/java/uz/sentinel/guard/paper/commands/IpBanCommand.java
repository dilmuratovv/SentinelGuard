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
        PunishArgs parsed = PunishArgs.parse(args, 1, "IP-Ban qoidabuzarlik");
        if (parsed.badDuration() != null) {
            sender.sendMessage(config.getPrefix() + config.colorize(parsed.badDurationMessage()));
            return true;
        }
        boolean force = java.util.Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("-f") || a.equalsIgnoreCase("--force"));
        String reason = parsed.reason().replaceAll("(?i)(^|\\s)(-f|--force)(?=\\s|$)", " ").trim();
        if (reason.isEmpty()) reason = "IP-Ban qoidabuzarlik";
        String finalReason = reason;

        if (IpAddresses.isIp(targetInput)) {
            ipBan(sender, null, targetInput, IpAddresses.normalize(targetInput), parsed, finalReason, force);
            return true;
        }
        Player online = Bukkit.getPlayerExact(targetInput);
        if (online != null && DataFiles.ip(online) != null) {
            ipBan(sender, online.getUniqueId(), online.getName(), DataFiles.ip(online), parsed, finalReason, force);
            return true;
        }
        uz.sentinel.guard.paper.util.Targets.resolve(plugin, sender, targetInput, (uuid, name) ->
                ipBan(sender, uuid, name, plugin.getAltManager().getLatestIp(uuid), parsed, finalReason, force));
        return true;
    }

    private void ipBan(CommandSender sender, UUID targetUuid, String targetName, String ip, PunishArgs parsed, String reason, boolean force) {
        ConfigManager config = plugin.getConfigManager();
        if (targetUuid != null && plugin.isImmune(sender, targetUuid)) {
            sender.sendMessage(config.getMessage("cannot-target-staff"));
            return;
        }
        if (ip == null || ip.isEmpty()) {
            sender.sendMessage(config.getPrefix() + config.colorize("&cO'yinchining IP manzili topilmadi!"));
            return;
        }
        // A shared address (CGNAT, school, cafe, VPN exit) carries many unrelated players: banning it bans them all.
        int accounts = plugin.getAltManager().accountsOn(ip);
        int sharedAt = plugin.getConfig().getInt("alts.shared-ip-accounts", 6);
        if (!force && sharedAt > 0 && accounts >= sharedAt) {
            sender.sendMessage(config.getPrefix() + config.colorize("&eDiqqat: &f" + ip + " &eumumiy IP ko'rinadi (&f" + accounts
                    + " &eta akkaunt). IP-ban ularning hammasini bloklaydi. Baribir qilish uchun buyruq oxiriga &f-f &eqo'shing."));
            return;
        }
        UUID finalTarget = targetUuid;
        plugin.getPunishmentManager().createPunishment(
                PunishmentType.IP_BAN, targetUuid, targetName, ip, sender, reason, parsed.durationMs(), parsed.silent(), record -> {
            plugin.getPunishmentFormatter().broadcastPunishment(record);
            String kickScreen = plugin.getPunishmentFormatter().buildBanScreen(record);
            for (Player p : plugin.getPunishmentManager().enforcesBans() ? Bukkit.getOnlinePlayers() : List.<Player>of()) {
                if (p.getUniqueId().equals(finalTarget) || IpAddresses.sameNetwork(record.getTargetIp(), DataFiles.ip(p))) {
                    p.kickPlayer(kickScreen);
                }
            }
        });
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
