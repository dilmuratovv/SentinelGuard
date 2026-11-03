package me.azicoo.guard.commands;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.gui.MainSusGUI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class SusCommand implements CommandExecutor, TabCompleter {
    private final GuardPlugin plugin;

    public SusCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        String cmdName = command.getName().toLowerCase();

        if (!sender.hasPermission("sus.admin") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            sender.sendMessage(config.getMessage("no-permission"));
            return true;
        }

        switch (cmdName) {
            case "sus" -> {
                if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                    plugin.reloadPlugin();
                    sender.sendMessage(config.getMessage("reload-success"));
                    return true;
                }
                if (!(sender instanceof Player)) {
                    sender.sendMessage(config.getPrefix() + config.colorize("&cBu buyruqni faqat o'yinchi bajara oladi!"));
                    return true;
                }
                new MainSusGUI(plugin, 1).open((Player) sender);
                return true;
            }
            case "addsus" -> {
                if (args.length < 2) {
                    sender.sendMessage(config.getMessage("add-sus-usage"));
                    return true;
                }
                String targetName = args[0];
                String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                if (target == null || (target.getName() == null && !target.hasPlayedBefore())) {
                    sender.sendMessage(config.getMessage("player-not-found"));
                    return true;
                }
                if (plugin.isImmune(sender, target.getUniqueId())) {
                    sender.sendMessage(config.getMessage("cannot-target-staff"));
                    return true;
                }
                String staffName = (sender instanceof Player) ? sender.getName() : "Console";
                int count = plugin.getSusManager().addSus(
                        target.getUniqueId(),
                        target.getName() != null ? target.getName() : targetName,
                        reason,
                        staffName
                );
                if (count == -1) {
                    sender.sendMessage(config.getMessage("cannot-sus-admin"));
                    return true;
                }
                String msg = config.getMessage("add-sus-success")
                        .replace("{player}", target.getName() != null ? target.getName() : targetName)
                        .replace("{reason}", reason)
                        .replace("{count}", String.valueOf(count));
                sender.sendMessage(msg);
                return true;
            }
            case "clearsus" -> {
                int clearedCount = plugin.getSusManager().clearAll();
                sender.sendMessage(config.getMessage("clear-sus-success").replace("{count}", String.valueOf(clearedCount)));
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("sus.admin") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            return Collections.emptyList();
        }
        String cmdName = command.getName().toLowerCase();
        List<String> completions = new ArrayList<>();
        if (cmdName.equals("sus") && args.length == 1) {
            completions.add("reload");
        } else if (cmdName.equals("addsus")) {
            if (args.length == 1) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.isOp() || p.hasPermission("sus.admin")) continue;
                    completions.add(p.getName());
                }
            } else if (args.length == 2) {
                completions.addAll(Arrays.asList("killaura", "fly", "reach", "speed", "autoclicker", "velocity", "xray"));
            }
        }
        if (!args[args.length - 1].isEmpty()) {
            String last = args[args.length - 1].toLowerCase();
            return completions.stream().filter(s -> s.toLowerCase().startsWith(last)).collect(Collectors.toList());
        }
        return completions;
    }
}
