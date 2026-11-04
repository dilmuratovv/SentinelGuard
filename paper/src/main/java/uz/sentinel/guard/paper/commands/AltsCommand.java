package uz.sentinel.guard.paper.commands;

import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.config.ConfigManager;
import uz.sentinel.guard.paper.punishment.PunishmentRecord;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class AltsCommand implements CommandExecutor, TabCompleter {
    private final GuardPlugin plugin;

    public AltsCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        if (!sender.hasPermission("guard.alts") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
            sender.sendMessage(config.getMessage("no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(config.getPrefix() + config.colorize("&cSintaksis: &f/" + label + " <o'yinchi|IP>"));
            return true;
        }

        String input = args[0];
        Set<UUID> alts;
        String displayTitle;
        String ip;

        if (input.matches("^(\\d{1,3}\\.){3}\\d{1,3}$")) {
            ip = input;
            alts = plugin.getAltManager().getAltsByIp(ip);
            displayTitle = "IP: " + ip;
        } else {
            UUID uuid = plugin.getAltManager().getUuidByName(input);
            OfflinePlayer op = Bukkit.getOfflinePlayer(input);
            if (uuid == null && op != null) {
                uuid = op.getUniqueId();
            }
            if (uuid == null) {
                sender.sendMessage(config.getPrefix() + config.colorize("&cO'yinchi topilmadi!"));
                return true;
            }
            ip = plugin.getAltManager().getLatestIp(uuid);
            alts = plugin.getAltManager().getAltsByUuid(uuid);
            displayTitle = "O'yinchi: " + input;
        }

        sender.sendMessage(config.colorize("&8============================================="));
        sender.sendMessage(config.colorize("&d&lAlt va IP Ma'lumotlari: &e" + displayTitle));
        sender.sendMessage(config.colorize("&7So'nggi IP: &f" + (ip != null ? ip : "Noma'lum")));
        sender.sendMessage(config.colorize("&7Topilgan hisoblar soni: &e" + alts.size() + " ta"));
        sender.sendMessage(config.colorize("&7---------------------------------------------"));

        if (alts.isEmpty()) {
            sender.sendMessage(config.colorize("&7Hech qanday boshqa akkaunt topilmadi."));
        } else {
            for (UUID u : alts) {
                String name = plugin.getAltManager().getNameByUuid(u);
                OfflinePlayer o = Bukkit.getOfflinePlayer(u);
                String onlineStatus = o.isOnline() ? "&a[Onlayn]" : "&7[Oflayn]";
                PunishmentRecord activeBan = plugin.getPunishmentManager().getActiveBan(u, ip);
                PunishmentRecord activeMute = plugin.getPunishmentManager().getActiveMute(u);

                StringBuilder badges = new StringBuilder();
                if (activeBan != null) badges.append(" &c&l[BAN]");
                if (activeMute != null) badges.append(" &e&l[MUTE]");

                sender.sendMessage(config.colorize("  &8- &f" + name + " " + onlineStatus + badges));
            }
        }
        sender.sendMessage(config.colorize("&8============================================="));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("guard.alts") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
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
