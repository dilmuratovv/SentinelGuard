package me.azicoo.guard.commands;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.gui.HistoryGUI;
import me.azicoo.guard.punishment.PunishmentRecord;
import me.azicoo.guard.punishment.TimeParser;
import me.azicoo.guard.sus.SusEntry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class HistoryCommand implements CommandExecutor, TabCompleter {
    private final GuardPlugin plugin;

    public HistoryCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        if (!sender.hasPermission("guard.history") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            sender.sendMessage(config.getMessage("no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(config.getPrefix() + config.colorize("&cSintaksis: &f/" + label + " <o'yinchi>"));
            return true;
        }

        String targetName = args[0];
        UUID targetUuid = plugin.getAltManager().getUuidByName(targetName);
        OfflinePlayer op = Bukkit.getOfflinePlayer(targetName);
        if (targetUuid == null && op != null) {
            targetUuid = op.getUniqueId();
            if (op.getName() != null) targetName = op.getName();
        }

        if (targetUuid == null) {
            targetUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + targetName).getBytes());
        }

        SusEntry susEntry = plugin.getSusManager().getEntry(targetUuid);

        if (sender instanceof Player) {
            Player player = (Player) sender;
            new HistoryGUI(plugin, susEntry, targetUuid, targetName, 1, 1).open(player);
            return true;
        }

        // Console Text Output
        List<PunishmentRecord> history = plugin.getPunishmentManager().getHistory(targetUuid);
        sender.sendMessage(config.colorize("&8============================================="));
        sender.sendMessage(config.colorize("&c&lJazo Tarixi: &e" + targetName));
        if (history.isEmpty()) {
            sender.sendMessage(config.colorize("&7Hech qanday jazo topilmadi."));
        } else {
            for (PunishmentRecord p : history) {
                String dur = p.isPermanent() ? "Abadiy" : TimeParser.formatDuration(p.getDurationMs());
                sender.sendMessage(config.colorize("&7▎ &e" + p.getType().getColoredName() + " &8[" + p.getId() + "&8] - Sabab: &f" + p.getReason() + " &7| Moderator: &b" + p.getPunisherName() + " &7| Muddat: &a" + dur + " &7| Vaqt: &8" + TimeParser.formatDate(p.getStartTime())));
            }
        }
        sender.sendMessage(config.colorize("&8============================================="));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("guard.history") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
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
