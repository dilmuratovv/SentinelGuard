package me.azicoo.guard.commands;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.punishment.PunishmentType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.*;
import java.util.stream.Collectors;

public class UnbanCommand implements CommandExecutor, TabCompleter {
    private final GuardPlugin plugin;

    public UnbanCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        if (!sender.hasPermission("guard.unban") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
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

        String reason = reasonTokens.isEmpty() ? "Bekor qilindi" : String.join(" ", reasonTokens).trim();
        UUID targetUuid = plugin.getAltManager().getUuidByName(targetName);
        OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
        if (targetUuid == null && op != null) {
            targetUuid = op.getUniqueId();
            if (op.getName() != null) targetName = op.getName();
        }

        boolean success = plugin.getPunishmentManager().unban(targetUuid, sender.getName(), reason);
        if (success) {
            plugin.getPunishmentFormatter().broadcastUnpunish(PunishmentType.BAN, targetName, sender.getName(), reason, silent);
        } else {
            sender.sendMessage(config.getPrefix() + config.colorize("&cO'yinchi &e" + targetName + " &cbanlanmagan yoki topilmadi!"));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("guard.unban") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Collections.emptyList();
        } else if (args.length == 2) {
            return List.of("-s", "Xato berilgan", "Apellyatsiya qabul qilindi");
        }
        return Collections.emptyList();
    }
}
