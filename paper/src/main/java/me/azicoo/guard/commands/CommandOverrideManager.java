package me.azicoo.guard.commands;

import me.azicoo.guard.GuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.SimpleCommandMap;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class CommandOverrideManager {
    private final GuardPlugin plugin;
    private static final List<String> OVERRIDDEN_COMMANDS = Arrays.asList(
            "ban", "tempban", "tban", "ipban", "ban-ip", "tempipban",
            "unban", "pardon", "unbanip", "pardonip",
            "mute", "tempmute", "tmute", "unmute",
            "kick", "warn", "unwarn", "warnings",
            "history", "check", "punishments", "modlogs",
            "freeze", "ss", "unfreeze", "sus", "addsus", "clearsus"
    );

    public CommandOverrideManager(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void overrideCommands() {
        try {
            Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            CommandMap commandMap = (CommandMap) commandMapField.get(Bukkit.getServer());

            if (commandMap instanceof SimpleCommandMap) {
                Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
                knownCommandsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);

                for (String cmdName : OVERRIDDEN_COMMANDS) {
                    Command guardCmd = plugin.getCommand(cmdName);
                    if (guardCmd != null) {
                        // Override default entry
                        knownCommands.put(cmdName.toLowerCase(), guardCmd);
                        knownCommands.put("sentinelguard:" + cmdName.toLowerCase(), guardCmd);
                    }
                }
            }
            plugin.getLogger().info("Buyruqlar (ban, mute, kick va boshqalar) ustunligi muvaffaqiyatli Guard plaginiga o'tkazildi!");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Buyruqlar ustunligini CommandMap orqali to'g'irlashda xatolik (Listener orqali himoyalanadi): " + e.getMessage());
        }
    }
}
