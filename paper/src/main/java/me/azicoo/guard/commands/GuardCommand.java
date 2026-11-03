package me.azicoo.guard.commands;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.punishment.PunishmentManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class GuardCommand implements CommandExecutor, TabCompleter {
    private final GuardPlugin plugin;

    public GuardCommand(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();

        if (args.length > 0) {
            String sub = args[0].toLowerCase();

            if (sub.equals("reload")) {
                if (!sender.hasPermission("guard.admin") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
                    sender.sendMessage(config.getMessage("no-permission"));
                    return true;
                }
                long start = System.currentTimeMillis();
                plugin.reloadPlugin();
                new CommandOverrideManager(plugin).overrideCommands();
                long took = System.currentTimeMillis() - start;

                sender.sendMessage(config.colorize("&8============================================="));
                sender.sendMessage(config.colorize("&a&l✔ SentinelGuard &fplagini muvaffaqiyatli qayta yuklandi! &7(" + took + "ms)"));
                sender.sendMessage(config.colorize("&7▎ &fKonfiguratsiya: &aYuklandi"));
                sender.sendMessage(config.colorize("&7▎ &fJazolar bazasi: &a" + plugin.getPunishmentManager().getGlobalTotalBans() + " banlar, " + plugin.getPunishmentManager().getGlobalTotalMutes() + " mutelar"));
                sender.sendMessage(config.colorize("&7▎ &fShubhali monitoring: &a" + plugin.getSusManager().getTotalSusCount() + " o'yinchi"));
                sender.sendMessage(config.colorize("&7▎ &fBuyruqlar ustunligi: &aFaollashtirildi"));
                sender.sendMessage(config.colorize("&8============================================="));
                return true;
            }

            if (sub.equals("version") || sub.equals("ver") || sub.equals("info")) {
                sender.sendMessage(config.colorize("&8============================================="));
                sender.sendMessage(config.colorize("&c&lSentinelGuard Moderation & Punishments"));
                sender.sendMessage(config.colorize("&7▎ &fVersiya: &a" + plugin.getDescription().getVersion()));
                sender.sendMessage(config.colorize("&7▎ &fMuallif: &e" + String.join(", ", plugin.getDescription().getAuthors())));
                sender.sendMessage(config.colorize("&7▎ &fServer: &b" + Bukkit.getName() + " " + Bukkit.getMinecraftVersion()));
                sender.sendMessage(config.colorize("&7▎ &fFaol Banlar: &c" + plugin.getPunishmentManager().getGlobalActiveBans() + " ta"));
                sender.sendMessage(config.colorize("&7▎ &fFaol Mutelar: &e" + plugin.getPunishmentManager().getGlobalActiveMutes() + " ta"));
                sender.sendMessage(config.colorize("&7▎ &fMuzlatilganlar: &3" + plugin.getFreezeManager().getFrozenPlayers().size() + " ta"));
                sender.sendMessage(config.colorize("&8============================================="));
                return true;
            }

            if (sub.equals("stats")) {
                if (!sender.hasPermission("guard.admin") && !sender.hasPermission("guard.admin") && !sender.isOp()) {
                    sender.sendMessage(config.getMessage("no-permission"));
                    return true;
                }
                PunishmentManager pm = plugin.getPunishmentManager();
                sender.sendMessage(config.colorize("&8============================================="));
                sender.sendMessage(config.colorize("&6&lSentinelGuard Server Moderatsiya Statistikasi"));
                sender.sendMessage(config.colorize("&7▎ &cFaol Banlar: &e" + pm.getGlobalActiveBans() + " &7(Jami berilgan: &c" + pm.getGlobalTotalBans() + "&7)"));
                sender.sendMessage(config.colorize("&7▎ &eFaol Mutelar: &e" + pm.getGlobalActiveMutes() + " &7(Jami berilgan: &e" + pm.getGlobalTotalMutes() + "&7)"));
                sender.sendMessage(config.colorize("&7▎ &dBerilgan Ogohlantirishlar (Warn): &e" + pm.getGlobalTotalWarns() + " ta"));
                sender.sendMessage(config.colorize("&7▎ &6Serverdan Chiqarishlar (Kick): &e" + pm.getGlobalTotalKicks() + " ta"));
                sender.sendMessage(config.colorize("&7▎ &3Shubhali O'yinchilar: &e" + plugin.getSusManager().getTotalSusCount() + " ta &7(Flaglar: &c" + plugin.getSusManager().getTotalFlagsCount() + "x&7)"));
                sender.sendMessage(config.colorize("&8============================================="));
                return true;
            }
        }

        // Help menu
        sender.sendMessage(config.colorize("&8============================================="));
        sender.sendMessage(config.colorize("&c&lSentinelGuard Moderation & Ban Plugin &7v" + plugin.getDescription().getVersion()));
        sender.sendMessage(config.colorize("&7▎ &e/ban <nik> [vaqt] [sabab] &8- &fBan berish (Doimiy/Vaqtincha)"));
        sender.sendMessage(config.colorize("&7▎ &e/unban <nik> [sabab] &8- &fBanni bekor qilish"));
        sender.sendMessage(config.colorize("&7▎ &e/ipban <nik|IP> [vaqt] [sabab] &8- &fIP ban berish"));
        sender.sendMessage(config.colorize("&7▎ &e/unbanip <IP> [sabab] &8- &fIP banni bekor qilish"));
        sender.sendMessage(config.colorize("&7▎ &e/mute <nik> [vaqt] [sabab] &8- &fChatni yopish (Doimiy/Vaqtincha)"));
        sender.sendMessage(config.colorize("&7▎ &e/unmute <nik> [sabab] &8- &fChatni ochish"));
        sender.sendMessage(config.colorize("&7▎ &e/kick <nik> [sabab] &8- &fServerdan chiqarish"));
        sender.sendMessage(config.colorize("&7▎ &e/warn <nik> [sabab] &8- &fOgohlantirish berish"));
        sender.sendMessage(config.colorize("&7▎ &e/unwarn <nik> <id|all> &8- &fOgohlantirishni o'chirish"));
        sender.sendMessage(config.colorize("&7▎ &e/history <nik> &8- &fJazo va shubha tarixi GUI"));
        sender.sendMessage(config.colorize("&7▎ &e/alts <nik|IP> &8- &fMulti-akkauntlarni tekshirish"));
        sender.sendMessage(config.colorize("&7▎ &e/freeze <nik> &8- &fO'yinchini muzlatish"));
        sender.sendMessage(config.colorize("&7▎ &e/unfreeze <nik> &8- &fMuzdan chiqarish"));
        sender.sendMessage(config.colorize("&7▎ &e/sus &8- &fShubhali monitoring GUI"));
        sender.sendMessage(config.colorize("&7▎ &e/sc <xabar> &8- &fStaff chat"));
        sender.sendMessage(config.colorize("&7▎ &e/sg reload &8- &fPlaginni qayta yuklash"));
        sender.sendMessage(config.colorize("&7▎ &e/sg stats &8- &fServer statistikasi"));
        sender.sendMessage(config.colorize("&7▎ &e/sg version &8- &fPlagin versiyasi"));
        sender.sendMessage(config.colorize("&8============================================="));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("reload", "version", "stats", "help", "info").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
