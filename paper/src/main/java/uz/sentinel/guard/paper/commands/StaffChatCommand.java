package uz.sentinel.guard.paper.commands;

import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class StaffChatCommand implements CommandExecutor {
    private final GuardPlugin plugin;

    public StaffChatCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        if (!sender.hasPermission("guard.staffchat") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            sender.sendMessage(config.getMessage("no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(config.getPrefix() + config.colorize("&cSintaksis: &f/" + label + " <xabar>"));
            return true;
        }

        String message = String.join(" ", args);
        String senderName = (sender instanceof Player) ? sender.getName() : "Console";
        String format = config.colorize(config.getConfig().getString("staff-chat-format", "&8[&cStaffChat&8] &b{staff}&8: &f{message}"))
                .replace("{staff}", senderName)
                .replace("{message}", message);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("guard.staffchat") || p.hasPermission("sus.admin") || p.isOp()) {
                p.sendMessage(format);
            }
        }
        Bukkit.getConsoleSender().sendMessage(format);
        // Other servers receive it over Redis when network mode has it enabled; otherwise it stays local.
        if (plugin.getNetwork() != null) plugin.getNetwork().publishStaffChat(senderName, message);
        return true;
    }
}
