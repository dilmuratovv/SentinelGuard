package me.azicoo.guard.commands;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class FreezeCommand implements CommandExecutor, TabCompleter {
    private final GuardPlugin plugin;

    public FreezeCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        String cmdName = command.getName().toLowerCase();

        if (!sender.hasPermission("sus.admin") && !sender.hasPermission("guard.freeze") && !sender.isOp()) {
            sender.sendMessage(config.getMessage("no-permission"));
            return true;
        }

        if (args.length < 1) {
            if (cmdName.equals("unfreeze")) {
                sender.sendMessage(config.getMessage("unfreeze-usage"));
            } else {
                sender.sendMessage(config.getMessage("freeze-usage"));
            }
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(config.getMessage("player-not-found"));
            return true;
        }

        if (cmdName.equals("unfreeze")) {
            if (plugin.getFreezeManager().unfreezePlayer(target, sender)) {
                sender.sendMessage(config.getMessage("unfreeze-success").replace("{player}", target.getName()));
            } else {
                sender.sendMessage(config.getMessage("not-frozen").replace("{player}", target.getName()));
            }
            return true;
        }

        // Freeze or ss
        if (sender instanceof Player && ((Player) sender).getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(config.getMessage("cannot-freeze-self"));
            return true;
        }

        if (plugin.isImmune(sender, target.getUniqueId())) {
            sender.sendMessage(config.getMessage("cannot-target-staff"));
            return true;
        }

        if (plugin.getFreezeManager().freezePlayer(target, sender)) {
            sender.sendMessage(config.getMessage("freeze-success").replace("{player}", target.getName()));
        } else {
            sender.sendMessage(config.getMessage("already-frozen").replace("{player}", target.getName()));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("sus.admin") && !sender.hasPermission("guard.freeze") && !sender.isOp()) {
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
