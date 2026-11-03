package me.azicoo.guard.punishment;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

public class PunishmentFormatter {
    private final GuardPlugin plugin;

    public PunishmentFormatter(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Broadcasts punishment to chat according to config templates.
     */
    public void broadcastPunishment(PunishmentRecord record) {
        ConfigManager config = plugin.getConfigManager();
        String typeKey = record.getType().name().toLowerCase();
        String durationStr = record.isPermanent() ? 
                config.getConfig().getString("punishment-formats.permanent-text", "Abadiy") : 
                TimeParser.formatDuration(record.getDurationMs());
        String expireStr = record.isPermanent() ? 
                config.getConfig().getString("punishment-formats.never-text", "Hech qachon") : 
                TimeParser.formatDate(record.getEndTime());

        if (record.isSilent()) {
            String silentFormat = config.colorize(config.getConfig().getString("punishment-broadcasts.silent-format",
                    "&8[&7SILENT&8] &b{staff} &7o'yinchi &e{player}&7ni jazoladi (&c{type}&7) - Sabab: &f{reason}&7, Muddat: &a{duration}"));
            String msg = applyPlaceholders(silentFormat, record, durationStr, expireStr);
            broadcastToStaff(msg);
            Bukkit.getConsoleSender().sendMessage(msg);
            return;
        }

        List<String> broadcastLines = config.getConfig().getStringList("punishment-broadcasts." + typeKey);
        if (broadcastLines.isEmpty()) {
            broadcastLines = config.getConfig().getStringList("punishment-broadcasts.default");
        }

        if (broadcastLines.isEmpty()) {
            String fallback = config.getPrefix() + config.colorize("&e" + record.getTargetName() + " &cjazolandi (&e" + record.getType().getColoredName() + "&c)! Sabab: &f" + record.getReason());
            Bukkit.broadcastMessage(applyPlaceholders(fallback, record, durationStr, expireStr));
        } else {
            for (String line : broadcastLines) {
                String colored = config.colorize(line);
                Bukkit.broadcastMessage(applyPlaceholders(colored, record, durationStr, expireStr));
            }
        }
    }

    /**
     * Broadcasts unpunish action.
     */
    public void broadcastUnpunish(PunishmentType type, String targetName, String staffName, String reason, boolean silent) {
        ConfigManager config = plugin.getConfigManager();
        String typeKey = "un" + type.name().toLowerCase();
        if (type == PunishmentType.IP_BAN) {
            typeKey = "unipban";
        }

        if (silent) {
            String silentMsg = config.colorize(config.getConfig().getString("unpunish-broadcasts.silent-format",
                    "&8[&7SILENT&8] &b{staff} &7o'yinchi &e{player}&7ning &a{type}&7 jazosini bekor qildi. Sabab: &f{reason}"))
                    .replace("{staff}", staffName)
                    .replace("{player}", targetName)
                    .replace("{type}", type.getDefaultName())
                    .replace("{reason}", reason);
            broadcastToStaff(silentMsg);
            Bukkit.getConsoleSender().sendMessage(silentMsg);
            return;
        }

        List<String> broadcastLines = config.getConfig().getStringList("unpunish-broadcasts." + typeKey);
        if (broadcastLines.isEmpty()) {
            broadcastLines = config.getConfig().getStringList("unpunish-broadcasts.default");
        }

        if (broadcastLines.isEmpty()) {
            String fallback = config.getPrefix() + config.colorize("&e" + targetName + "&ning &a" + type.getDefaultName() + "&a jazosi &b" + staffName + " &atomonidan bekor qilindi!");
            Bukkit.broadcastMessage(fallback);
        } else {
            for (String line : broadcastLines) {
                String colored = config.colorize(line)
                        .replace("{staff}", staffName)
                        .replace("{player}", targetName)
                        .replace("{target}", targetName)
                        .replace("{type}", type.getDefaultName())
                        .replace("{reason}", reason);
                Bukkit.broadcastMessage(colored);
            }
        }
    }

    /**
     * Builds the disconnect / kick screen text when player is banned.
     */
    public String buildBanScreen(PunishmentRecord record) {
        ConfigManager config = plugin.getConfigManager();
        String durationStr = record.isPermanent() ? 
                config.getConfig().getString("punishment-formats.permanent-text", "Abadiy") : 
                TimeParser.formatDuration(record.getDurationMs());
        String expireStr = record.isPermanent() ? 
                config.getConfig().getString("punishment-formats.never-text", "Hech qachon") : 
                TimeParser.formatDate(record.getEndTime());
        String remainingStr = record.isPermanent() ? 
                config.getConfig().getString("punishment-formats.permanent-text", "Abadiy") : 
                TimeParser.formatRemaining(record.getEndTime());

        List<String> lines = config.getConfig().getStringList("ban-screen-format");
        if (lines.isEmpty()) {
            return config.colorize("&c&lSERVERDAN CHETLASHTIRILDINGIZ!\n\n" +
                    "&7Sabab: &f" + record.getReason() + "\n" +
                    "&7Moderator: &e" + record.getPunisherName() + "\n" +
                    "&7Muddat: &a" + durationStr + "\n" +
                    "&7Tugash vaqti: &b" + expireStr + "\n" +
                    "&7Jazo ID: &8#" + record.getId());
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = config.colorize(lines.get(i));
            sb.append(applyPlaceholders(line, record, durationStr, expireStr).replace("{remaining}", remainingStr));
            if (i < lines.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Builds the message shown to a muted player attempting to chat.
     */
    public List<String> buildMuteAttemptMessage(PunishmentRecord record) {
        ConfigManager config = plugin.getConfigManager();
        String durationStr = record.isPermanent() ? 
                config.getConfig().getString("punishment-formats.permanent-text", "Abadiy") : 
                TimeParser.formatDuration(record.getDurationMs());
        String expireStr = record.isPermanent() ? 
                config.getConfig().getString("punishment-formats.never-text", "Hech qachon") : 
                TimeParser.formatDate(record.getEndTime());
        String remainingStr = record.isPermanent() ? 
                config.getConfig().getString("punishment-formats.permanent-text", "Abadiy") : 
                TimeParser.formatRemaining(record.getEndTime());

        List<String> lines = config.getConfig().getStringList("mute-chat-blocked-message");
        if (lines.isEmpty()) {
            return List.of(
                    config.getPrefix() + config.colorize("&cChatdan chetlashtirilgansiz!"),
                    config.colorize("&7Sabab: &f" + record.getReason() + " &7| Qolgan vaqt: &e" + remainingStr)
            );
        }

        return lines.stream().map(line -> {
            String colored = config.colorize(line);
            return applyPlaceholders(colored, record, durationStr, expireStr).replace("{remaining}", remainingStr);
        }).toList();
    }

    /**
     * Replaces standard placeholders in a string.
     */
    public String applyPlaceholders(String text, PunishmentRecord record, String durationStr, String expireStr) {
        return text.replace("{player}", record.getTargetName())
                .replace("{target}", record.getTargetName())
                .replace("{staff}", record.getPunisherName())
                .replace("{executor}", record.getPunisherName())
                .replace("{reason}", record.getReason())
                .replace("{type}", record.getType().getDefaultName())
                .replace("{type_formatted}", record.getType().getColoredName())
                .replace("{duration}", durationStr)
                .replace("{expires}", expireStr)
                .replace("{date}", TimeParser.formatDate(record.getStartTime()))
                .replace("{time_ago}", TimeParser.formatTimeAgo(record.getStartTime()))
                .replace("{id}", record.getId());
    }

    public void broadcastToStaff(String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("guard.staff") || p.hasPermission("guard.admin") || p.isOp() || p.hasPermission("sus.admin")) {
                p.sendMessage(message);
            }
        }
    }
}
