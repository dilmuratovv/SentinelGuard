package me.azicoo.guard.commands;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.punishment.PunishmentRecord;
import me.azicoo.guard.punishment.PunishmentType;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class KickCommand implements CommandExecutor, TabCompleter {
    private final GuardPlugin plugin;

    public KickCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        if (!sender.hasPermission("guard.kick") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            sender.sendMessage(config.getMessage("no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(config.getPrefix() + config.colorize("&cSintaksis: &f/" + label + " <o'yinchi> [sabab] [-s]"));
            return true;
        }

        String targetName = args[0];
        boolean silent = false;
        List<String> reasonTokens = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("-s") || args[i].equalsIgnoreCase("--silent")) {
                silent = true;
            } else {
                reasonTokens.add(args[i]);
            }
        }

        String reason = reasonTokens.isEmpty() ? "Moderator tomonidan chiqarildingiz" : String.join(" ", reasonTokens).trim();
        Player targetPlayer = Bukkit.getPlayer(targetName);

        if (targetPlayer == null || !targetPlayer.isOnline()) {
            sender.sendMessage(config.getMessage("player-not-found"));
            return true;
        }

        if (plugin.isImmune(sender, targetPlayer.getUniqueId())) {
            sender.sendMessage(config.getMessage("cannot-target-staff"));
            return true;
        }

        if (sender instanceof Player && ((Player) sender).getUniqueId().equals(targetPlayer.getUniqueId())) {
            sender.sendMessage(config.getPrefix() + config.colorize("&cSiz o'zingizni kick qila olmaysiz!"));
            return true;
        }

        String ip = targetPlayer.getAddress() != null ? targetPlayer.getAddress().getAddress().getHostAddress() : "";
        PunishmentRecord record = plugin.getPunishmentManager().createPunishment(
                PunishmentType.KICK, targetPlayer.getUniqueId(), targetPlayer.getName(), ip, sender, reason, 0, silent
        );

        plugin.getPunishmentFormatter().broadcastPunishment(record);
        Bukkit.getScheduler().runTask(plugin, () -> targetPlayer.kickPlayer(config.colorize("&c" + reason)));

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("guard.kick") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        } else if (args.length == 2) {
            return List.of("AFK", "Qoidabuzarlik", "Noxush xulq", "-s");
        }
        return Collections.emptyList();
    }
}
