package uz.sentinel.guard.paper.commands;

import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.config.ConfigManager;
import uz.sentinel.guard.paper.punishment.PunishmentType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

public class UnmuteCommand implements CommandExecutor, TabCompleter {
    private final GuardPlugin plugin;

    public UnmuteCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        if (!sender.hasPermission("guard.unmute") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
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

        String finalName = targetName;
        UUID finalTarget = targetUuid;
        boolean finalSilent = silent;
        plugin.getPunishmentManager().unmute(targetUuid, sender, reason, success -> {
            if (success) {
                plugin.getPunishmentFormatter().broadcastUnpunish(PunishmentType.MUTE, finalName, sender.getName(), reason, finalSilent);
                Player targetOnline = (finalTarget != null) ? Bukkit.getPlayer(finalTarget) : null;
                if (targetOnline != null && targetOnline.isOnline()) {
                    targetOnline.sendMessage(config.getPrefix() + config.colorize("&aOvozingiz qayta ochildi! Xushmuomala bo'lishni unutmang."));
                }
            } else {
                sender.sendMessage(config.getPrefix() + config.colorize("&cO'yinchi &e" + finalName + " &cmute qilinmagan yoki topilmadi!"));
            }
        });

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("guard.unmute") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            return Collections.emptyList();
        }
        if (args.length == 2) {
            return List.of("-s", "Apellyatsiya qabul qilindi", "Kechirildi");
        }
        return Collections.emptyList();
    }
}
