package uz.sentinel.guard.paper.commands;

import uz.sentinel.guard.paper.GuardPlugin;
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
    /**
     * Vanilla's ban commands and the Guard command that replaces each. Their namespaced forms (/minecraft:ban ...)
     * are taken over too, so neither a player, the console nor a command block can ban or unban past Guard's
     * records, immunity and network sync. /minecraft:kick stays vanilla on purpose: plugins (anticheats) use it for
     * kicks that are not staff kicks, and the freeze rules treat those differently (a frozen player kicked that way
     * must come back or is banned).
     */
    private static final Map<String, String> VANILLA = Map.of(
            "ban", "ban", "ban-ip", "ipban", "pardon", "unban", "pardon-ip", "unbanip");

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
                for (Map.Entry<String, String> vanilla : VANILLA.entrySet()) {
                    Command guardCmd = plugin.getCommand(vanilla.getValue());
                    if (guardCmd == null) continue;
                    knownCommands.put(vanilla.getKey(), guardCmd);
                    knownCommands.put("minecraft:" + vanilla.getKey(), guardCmd);
                }
            }
            plugin.getLogger().info("Buyruqlar (ban, mute, kick va boshqalar) ustunligi muvaffaqiyatli Guard plaginiga o'tkazildi!");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Buyruqlar ustunligini CommandMap orqali to'g'irlashda xatolik (Listener orqali himoyalanadi): " + e.getMessage());
        }
    }
}
