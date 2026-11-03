package me.azicoo.guard.commands;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.punishment.PunishmentRecord;
import me.azicoo.guard.punishment.TimeParser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

public class StaffHistoryCommand implements CommandExecutor, TabCompleter {
    private final GuardPlugin plugin;

    public StaffHistoryCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        if (!sender.hasPermission("guard.staffhistory") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            sender.sendMessage(config.getMessage("no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(config.getPrefix() + config.colorize("&cSintaksis: &f/" + label + " <moderator_nik>"));
            return true;
        }

        String staffName = args[0];
        List<PunishmentRecord> records = plugin.getPunishmentManager().getStaffHistory(staffName);

        sender.sendMessage(config.colorize("&8============================================="));
        sender.sendMessage(config.colorize("&b&lModerator Faoliyati: &e" + staffName));
        sender.sendMessage(config.colorize("&7Jami berilgan jazolar: &e" + records.size() + " ta"));
        sender.sendMessage(config.colorize("&7---------------------------------------------"));

        if (records.isEmpty()) {
            sender.sendMessage(config.colorize("&7Hech qanday jazo topilmadi."));
        } else {
            for (PunishmentRecord p : records) {
                String dur = p.isPermanent() ? "Abadiy" : TimeParser.formatDuration(p.getDurationMs());
                sender.sendMessage(config.colorize("&7▎ &e" + p.getType().getColoredName() + " &8-> &f" + p.getTargetName() + " &7| Sabab: &f" + p.getReason() + " &7| Muddat: &a" + dur + " &7| Vaqt: &8" + TimeParser.formatTimeAgo(p.getStartTime())));
            }
        }
        sender.sendMessage(config.colorize("&8============================================="));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
