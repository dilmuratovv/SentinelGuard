package me.azicoo.guard.sus;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FreezeManager {
    private final GuardPlugin plugin;
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> freezeLocations = new ConcurrentHashMap<>();
    private BukkitTask alertTask;

    public FreezeManager(GuardPlugin plugin) {
        this.plugin = plugin;
        this.startAlertTask();
    }

    public boolean hasAnyFrozen() {
        return !this.frozenPlayers.isEmpty();
    }

    public boolean freezePlayer(Player player, CommandSender issuer) {
        if (this.frozenPlayers.contains(player.getUniqueId())) {
            return false;
        }
        this.frozenPlayers.add(player.getUniqueId());
        this.freezeLocations.put(player.getUniqueId(), player.getLocation().clone());
        ConfigManager config = this.plugin.getConfigManager();
        
        String title = config.colorize(config.getConfig().getString("freeze.title", "&c&lMUZLATILDINGIZ!"));
        String subtitle = config.colorize(config.getConfig().getString("freeze.subtitle", "&eTekshiruv uchun Discord serverimizga kiring!"));
        player.sendTitle(title, subtitle, 10, 70, 20);
        
        List<String> msgList = config.colorize(config.getConfig().getStringList("freeze.message"));
        for (String line : msgList) {
            player.sendMessage(line);
        }
        return true;
    }

    public boolean unfreezePlayer(Player player, CommandSender issuer) {
        if (!this.frozenPlayers.contains(player.getUniqueId())) {
            return false;
        }
        this.frozenPlayers.remove(player.getUniqueId());
        this.freezeLocations.remove(player.getUniqueId());
        ConfigManager config = this.plugin.getConfigManager();
        player.sendMessage(config.getPrefix() + config.colorize("&aMuzdan chiqarildingiz! Rahmat."));
        player.resetTitle();
        return true;
    }

    public boolean isFrozen(UUID uuid) {
        return this.frozenPlayers.contains(uuid);
    }

    public Location getFreezeLocation(UUID uuid) {
        return this.freezeLocations.get(uuid);
    }

    public Set<UUID> getFrozenPlayers() {
        return Collections.unmodifiableSet(this.frozenPlayers);
    }

    private void startAlertTask() {
        this.alertTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            if (this.frozenPlayers.isEmpty()) {
                return;
            }
            ConfigManager config = this.plugin.getConfigManager();
            String title = config.colorize(config.getConfig().getString("freeze.title", "&c&lMUZLATILDINGIZ!"));
            String subtitle = config.colorize(config.getConfig().getString("freeze.subtitle", "&eTekshiruv uchun Discord serverimizga kiring!"));
            String actionbar = config.colorize(config.getConfig().getString("freeze.actionbar", "&c&lHarakatlanish va chat yopildi!"));
            
            for (UUID uuid : this.frozenPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) continue;
                p.sendTitle(title, subtitle, 0, 40, 10);
                p.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(actionbar));
            }
        }, 20L, 40L);
    }

    public void shutdown() {
        if (this.alertTask != null) {
            this.alertTask.cancel();
        }
        this.frozenPlayers.clear();
        this.freezeLocations.clear();
    }
}
