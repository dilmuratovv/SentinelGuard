package uz.sentinel.guard.paper.commands;

import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.config.ConfigManager;
import uz.sentinel.guard.paper.punishment.PunishmentRecord;
import uz.sentinel.guard.paper.punishment.PunishmentType;
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
            String idOrAll = args[1];
            uz.sentinel.guard.paper.util.Targets.resolve(plugin, sender, args[0], (finalTarget, targetName) ->
            plugin.getPunishmentManager().refreshHistory(finalTarget, () ->
                plugin.getPunishmentManager().unwarn(finalTarget, idOrAll, sender, removed -> {
                    if (removed > 0) {
                        sender.sendMessage(config.getPrefix() + config.colorize("&aO'yinchi &e" + targetName + " &adan &6" + removed + " &ata ogohlantirish olib tashlandi!"));
                    } else {
                        sender.sendMessage(config.getPrefix() + config.colorize("&cOgohlantirish topilmadi!"));
                    }
                })));
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
            uz.sentinel.guard.paper.util.Targets.resolve(plugin, sender, args[0], (finalTarget, targetName) ->
            plugin.getPunishmentManager().refreshHistory(finalTarget, () -> {
                int activeWarns = plugin.getPunishmentManager().getActiveWarnsCount(finalTarget);
                sender.sendMessage(config.getPrefix() + config.colorize("&e" + targetName + " &7o'yinchisining faol ogohlantirishlari: &c" + activeWarns + " ta"));
            }));
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
        boolean finalSilent = silent;
        uz.sentinel.guard.paper.util.Targets.resolve(plugin, sender, targetName, (targetUuid, shownName) -> {
            if (plugin.isImmune(sender, targetUuid)) {
                sender.sendMessage(config.getMessage("cannot-target-staff"));
                return;
            }
            if (sender instanceof Player && ((Player) sender).getUniqueId().equals(targetUuid)) {
                sender.sendMessage(config.getPrefix() + config.colorize("&cSiz o'zingizni ogohlantira olmaysiz!"));
                return;
            }
            String ip = plugin.getAltManager().getLatestIp(targetUuid);
            plugin.getPunishmentManager().createPunishment(
                    PunishmentType.WARN, targetUuid, shownName, ip, sender, reason, 0, finalSilent, record -> {
                plugin.getPunishmentFormatter().broadcastPunishment(record);
                Player targetOnline = Bukkit.getPlayer(targetUuid);
                if (targetOnline != null && targetOnline.isOnline()) {
                    int total = plugin.getPunishmentManager().getActiveWarnsCount(targetUuid);
                    targetOnline.sendMessage(config.getPrefix() + config.colorize("&c&lDIQQAT! &fSizga ogohlantirish berildi! &7(Jami: &e" + total + "&7)"));
                    targetOnline.sendMessage(config.colorize("&7Sabab: &e" + record.getReason()));
                }
            });
        });
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
