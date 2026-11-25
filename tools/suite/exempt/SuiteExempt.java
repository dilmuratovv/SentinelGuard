package uz.sentinel.test;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only: scripted bots (teleporting, not vanilla-like) are exempt from SentinelAC's checks; AC still runs. */
public final class SuiteExempt extends JavaPlugin implements Listener {
    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); getLogger().info("EXEMPT READY"); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        var attachment = event.getPlayer().addAttachment(this);
        attachment.setPermission("sentinel.exempt", true);
        attachment.setPermission("grim.exempt", true);
        event.getPlayer().recalculatePermissions();
    }
}
