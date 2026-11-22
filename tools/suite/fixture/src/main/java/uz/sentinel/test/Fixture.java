package uz.sentinel.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class Fixture extends JavaPlugin {
    @Override public void onEnable() {
        if (!Files.isRegularFile(Path.of(".sentinel-test-world"))) Bukkit.getPluginManager().disablePlugin(this);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length < 2) return false;
        Player one = Bukkit.getPlayerExact(args[1]);
        if (one == null) return false;
        switch (args[0]) {
            case "oversize" -> one.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD, 2));
            case "oversize-cursor", "ordinary-cursor" -> {
                boolean oversize = args[0].startsWith("oversize");
                ItemStack cursor = new ItemStack(oversize ? Material.DIAMOND_SWORD : Material.STONE, 2);
                var event = new InventoryDragEvent(one.getOpenInventory(), null, cursor, false,
                        Map.of(9, cursor.asOne(), 10, cursor.asOne()));
                Bukkit.getPluginManager().callEvent(event);
                sender.sendMessage("drag cancel=" + event.isCancelled());
            }
            case "portal" -> {
                Location from = one.getLocation().clone().add(0, 6, 0);
                FallingBlock entity = from.getWorld().spawnFallingBlock(from, Material.SAND.createBlockData());
                try {
                    var event = new EntityPortalEvent(entity, from, Bukkit.getWorlds().get(1).getSpawnLocation());
                    Bukkit.getPluginManager().callEvent(event);
                    sender.sendMessage("portal cancel=" + event.isCancelled());
                } finally { entity.remove(); }
            }
            case "hide", "show" -> {
                Player two = Bukkit.getPlayerExact(args[2]);
                if (two == null) return false;
                if (args[0].equals("hide")) one.hideEntity(this, two); else one.showEntity(this, two);
                sender.sendMessage("fixture visibility changed");
            }
            default -> { return false; }
        }
        return true;
    }
}
