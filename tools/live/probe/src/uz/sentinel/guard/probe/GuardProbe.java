package uz.sentinel.guard.probe;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;
import uz.sentinel.guard.paper.GuardPlugin;

import java.util.*;

/** Test-only helper for the SentinelGuard live test: console commands that set scenes and print state. */
public final class GuardProbe extends JavaPlugin implements Listener {
    private final Map<String, List<String>> grants = new HashMap<>();

    @Override public void onEnable() { Bukkit.getPluginManager().registerEvents(this, this); getLogger().info("PROBE READY"); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) { apply(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBook(org.bukkit.event.player.PlayerEditBookEvent event) {
        out("bookevent " + event.getPlayer().getName() + " slot=" + event.getSlot() + " cancelled=" + event.isCancelled());
    }

    private void apply(Player player) {
        for (String perm : grants.getOrDefault(player.getName().toLowerCase(), List.of())) player.addAttachment(this, perm, true);
        player.recalculatePermissions();
    }

    private void out(String s) { getLogger().info("GP " + s); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (!(sender instanceof ConsoleCommandSender) || a.length == 0) return true;
        Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
        GuardPlugin guard = GuardPlugin.getInstance();
        switch (a[0]) {
            case "grant" -> {
                grants.computeIfAbsent(a[1].toLowerCase(), k -> new ArrayList<>()).add(a[2]);
                if (p != null) apply(p);
                out("grant " + a[1] + " " + a[2]);
            }
            case "state" -> {
                OfflinePlayer op = p != null ? p : Bukkit.getOfflinePlayer(a[1]);
                UUID id = op.getUniqueId();
                String ip = guard.getAltManager().getLatestIp(id);
                var ban = guard.getPunishmentManager().getActiveBan(id, ip);
                var mute = guard.getPunishmentManager().getActiveMute(id);
                String where = p == null ? "offline" : String.format(Locale.ROOT, "%.2f,%.2f,%.2f", p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ());
                out("state " + a[1] + " frozen=" + guard.getFreezeManager().isFrozen(id) + " online=" + (p != null)
                        + " flight=" + (p != null && p.getAllowFlight()) + " vehicle=" + (p != null && p.isInsideVehicle())
                        + " gliding=" + (p != null && p.isGliding()) + " loc=" + where
                        + " banned=" + (ban != null) + (ban != null ? " banReason=\"" + ban.getReason() + "\" banDuration=" + ban.getDurationMs() : "")
                        + " muted=" + (mute != null) + " ip=" + ip);
            }
            case "mount" -> {
                var cart = p.getWorld().spawn(p.getLocation().add(1.5, 0, 0), org.bukkit.entity.minecart.RideableMinecart.class);
                boolean ok = cart.addPassenger(p);
                out("mount " + a[1] + " ok=" + ok + " vehicle=" + p.isInsideVehicle());
                p.leaveVehicle();
                cart.remove();
            }
            case "pearl" -> {
                EnderPearl pearl = p.launchProjectile(EnderPearl.class);
                Bukkit.getScheduler().runTask(this, () -> { out("pearl " + a[1] + " flying=" + pearl.isValid()); pearl.remove(); });
            }
            case "tpcause" -> {
                boolean ok = p.teleport(p.getLocation().add(4, 0, 0), PlayerTeleportEvent.TeleportCause.valueOf(a[2]));
                out("tpcause " + a[1] + " " + a[2] + " moved=" + ok);
            }
            case "elytra" -> {
                // a real start: elytra on, in the air; the bot then sends the start-gliding action itself
                p.getInventory().setChestplate(new ItemStack(Material.ELYTRA));
                p.teleport(p.getLocation().add(0, 8, 0));
                out("elytra " + a[1] + " ready");
            }
            case "lift" -> {
                Location up = p.getLocation().add(0, 6, 0);
                p.teleport(up);
                out("lift " + a[1] + " y=" + up.getY());
            }
            case "sign" -> {
                Block b = p.getLocation().getBlock().getRelative(a.length > 2 ? Integer.parseInt(a[2]) : 1, 0, 0);
                b.setType(Material.OAK_SIGN);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    p.openSign((Sign) b.getState(), Side.FRONT);
                    out("sign " + a[1] + " at " + b.getX() + " " + b.getY() + " " + b.getZ());
                }, 2);
            }
            case "signtext" -> {
                Block b = Bukkit.getWorlds().get(0).getBlockAt(Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                if (!(b.getState() instanceof Sign s)) { out("signtext none"); break; }
                out("signtext \"" + String.join("|", s.getSide(Side.FRONT).getLines()) + "\"");
            }
            case "book" -> {
                p.getInventory().setItem(0, new ItemStack(Material.WRITABLE_BOOK));
                p.getInventory().setHeldItemSlot(0);
                out("book " + a[1] + " given");
            }
            case "bookcheck" -> {
                ItemStack item = p.getInventory().getItem(0);
                String pages = item != null && item.getItemMeta() instanceof BookMeta m ? String.join("|", m.getPages()) : "";
                out("bookcheck " + a[1] + " type=" + (item == null ? "AIR" : item.getType()) + " pages=\"" + pages + "\"");
            }
            case "anvil" -> {
                p.setLevel(30);
                p.openAnvil(null, true);
                p.getOpenInventory().getTopInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
                out("anvil " + a[1] + " open=" + p.getOpenInventory().getType());
            }
            case "anvilresult" -> {
                ItemStack r = p.getOpenInventory().getTopInventory().getItem(2);
                String name = r != null && r.hasItemMeta() && r.getItemMeta().displayName() != null
                        ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(r.getItemMeta().displayName()) : "";
                out("anvilresult " + a[1] + " type=" + (r == null ? "AIR" : r.getType()) + " name=\"" + name + "\"");
                p.closeInventory();
            }
            case "alts" -> out("alts " + a[1] + " accounts=" + guard.getAltManager().accountsOn(a[1]) + " shared=" + guard.getAltManager().isShared(a[1]));
            case "staff" -> {
                OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
                out("staff " + a[1] + " immune=" + guard.getStaffRegistry().isStaff(op.getUniqueId()));
            }
            case "sus" -> {
                var entry = guard.getSusManager().getEntry(Bukkit.getOfflinePlayer(a[1]).getUniqueId());
                out("sus " + a[1] + " " + (entry == null ? "none" : "reasons=\"" + entry.getReasons().toString() + "\""));
            }
            case "tick" -> {
                // main-thread responsiveness probe: prints how long one scheduled tick took to come round
                long t0 = System.nanoTime();
                Bukkit.getScheduler().runTask(this, () -> out("tick " + (System.nanoTime() - t0) / 1_000_000 + "ms"));
            }
            default -> out("unknown " + a[0]);
        }
        return true;
    }
}
