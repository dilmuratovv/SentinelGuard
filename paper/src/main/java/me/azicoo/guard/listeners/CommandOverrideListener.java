package me.azicoo.guard.listeners;

import me.azicoo.guard.GuardPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class CommandOverrideListener implements Listener {
    private final GuardPlugin plugin;
    private final Set<String> interceptedPrefixes = new HashSet<>(Arrays.asList(
            "minecraft:ban", "essentials:ban", "minecraft:tempban", "essentials:tempban",
            "minecraft:unban", "essentials:unban", "minecraft:pardon", "essentials:pardon",
            "minecraft:mute", "essentials:mute", "minecraft:tempmute", "essentials:tempmute",
            "minecraft:unmute", "essentials:unmute", "minecraft:kick", "essentials:kick"
    ));

    public CommandOverrideListener(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        if (message.startsWith("/")) {
            message = message.substring(1);
        }
        String[] parts = message.split(" ", 2);
        String baseCmd = parts[0].toLowerCase();

        if (interceptedPrefixes.contains(baseCmd)) {
            String cleanCmd = baseCmd.substring(baseCmd.indexOf(":") + 1);
            String args = parts.length > 1 ? (" " + parts[1]) : "";
            event.setCancelled(true);
            player.performCommand("sentinelguard:" + cleanCmd + args);
        }
    }
}
