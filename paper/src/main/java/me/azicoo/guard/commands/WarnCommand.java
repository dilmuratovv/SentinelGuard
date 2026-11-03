package me.azicoo.guard.commands;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.punishment.PunishmentRecord;
import me.azicoo.guard.punishment.PunishmentType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class WarnCommand implements CommandExecutor, TabCompleter {
    private final GuardPlugin plugin;

    public WarnCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        String cmdName = command.getName().toLowerCase();

        if (cmdName.equals("unwarn")) {
            if (!sender.hasPermission("guard.unwarn") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
                sender.sendMessage(config.getMessage("no-permission"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(config.getPrefix() + config.colorize("&cSintaksis: &f/unwarn <o'yinchi> <warn_id|all>"));
                return true;
            }
            String targetName = args[0];
            String idOrAll = args[1];
            UUID targetUuid = plugin.getAltManager().getUuidByName(targetName);
            if (targetUuid == null) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
                if (op != null) targetUuid = op.getUniqueId();
            }
            int removed = plugin.getPunishmentManager().unwarn(targetUuid, idOrAll, sender.getName());
            if (removed > 0) {
                sender.sendMessage(config.getPrefix() + config.colorize("&aO'yinchi &e" + targetName + " &adan &6" + removed + " &ata ogohlantirish olib tashlandi!"));
            } else {
                sender.sendMessage(config.getPrefix() + config.colorize("&cOgohlantirish topilmadi!"));
            }
            return true;
        }

        if (cmdName.equals("warnings")) {
            if (!sender.hasPermission("guard.warn") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
                sender.sendMessage(config.getMessage("no-permission"));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(config.getPrefix() + config.colorize("&cSintaksis: &f/warnings <o'yinchi>"));
                return true;
            }
            String targetName = args[0];
            UUID targetUuid = plugin.getAltManager().getUuidByName(targetName);
            if (targetUuid == null) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
                if (op != null) targetUuid = op.getUniqueId();
            }
            int activeWarns = plugin.getPunishmentManager().getActiveWarnsCount(targetUuid);
            sender.sendMessage(config.getPrefix() + config.colorize("&e" + targetName + " &7o'yinchisining faol ogohlantirishlari: &c" + activeWarns + " ta"));
            return true;
        }

        // Standard /warn
        if (!sender.hasPermission("guard.warn") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
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

        String reason = reasonTokens.isEmpty() ? "Qoidabuzarlik" : String.join(" ", reasonTokens).trim();
        UUID targetUuid = plugin.getAltManager().getUuidByName(targetName);
        OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
        if (targetUuid == null && op != null) {
            targetUuid = op.getUniqueId();
            if (op.getName() != null) targetName = op.getName();
        }

        if (plugin.isImmune(sender, targetUuid)) {
            sender.sendMessage(config.getMessage("cannot-target-staff"));
            return true;
        }

        if (sender instanceof Player && targetUuid != null && ((Player) sender).getUniqueId().equals(targetUuid)) {
            sender.sendMessage(config.getPrefix() + config.colorize("&cSiz o'zingizni ogohlantira olmaysiz!"));
            return true;
        }

        String ip = plugin.getAltManager().getLatestIp(targetUuid);
        PunishmentRecord record = plugin.getPunishmentManager().createPunishment(
                PunishmentType.WARN, targetUuid, targetName, ip, sender, reason, 0, silent
        );

        plugin.getPunishmentFormatter().broadcastPunishment(record);

        Player targetOnline = (targetUuid != null) ? Bukkit.getPlayer(targetUuid) : null;
        if (targetOnline != null && targetOnline.isOnline()) {
            int total = plugin.getPunishmentManager().getActiveWarnsCount(targetUuid);
            targetOnline.sendMessage(config.getPrefix() + config.colorize("&c&lDIQQAT! &fSizga ogohlantirish berildi! &7(Jami: &e" + total + "&7)"));
            targetOnline.sendMessage(config.colorize("&7Sabab: &e" + reason));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("guard.warn") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        } else if (args.length == 2) {
            return List.of("Spam", "Haqorat", "Nojo'ya so'z", "Qoidabuzarlik", "-s");
        }
        return Collections.emptyList();
    }
}
