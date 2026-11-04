package uz.sentinel.guard.paper.commands;

import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
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

        Player online = Bukkit.getPlayer(args[0]);
        UUID targetUuid;
        String targetName;
        if (online != null && online.isOnline()) {
            targetUuid = online.getUniqueId();
            targetName = online.getName();
        } else if (plugin.getNetwork() != null) {
            // The player may be on another server of the network.
            targetUuid = plugin.getAltManager().getUuidByName(args[0]);
            targetName = args[0];
        } else {
            targetUuid = null;
            targetName = args[0];
        }
        if (targetUuid == null) {
            sender.sendMessage(config.getMessage("player-not-found"));
            return true;
        }

        if (cmdName.equals("unfreeze")) {
            plugin.setFrozen(targetUuid, targetName, false, sender, ok -> {
                if (ok) {
                    sender.sendMessage(config.getMessage("unfreeze-success").replace("{player}", targetName));
                } else {
                    sender.sendMessage(config.getMessage("not-frozen").replace("{player}", targetName));
                }
            });
            return true;
        }

        // Freeze or ss
        if (sender instanceof Player && ((Player) sender).getUniqueId().equals(targetUuid)) {
            sender.sendMessage(config.getMessage("cannot-freeze-self"));
            return true;
        }

        if (plugin.isImmune(sender, targetUuid)) {
            sender.sendMessage(config.getMessage("cannot-target-staff"));
            return true;
        }

        plugin.setFrozen(targetUuid, targetName, true, sender, ok -> {
            if (ok) {
                sender.sendMessage(config.getMessage("freeze-success").replace("{player}", targetName));
            } else {
                sender.sendMessage(config.getMessage("already-frozen").replace("{player}", targetName));
            }
        });
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
