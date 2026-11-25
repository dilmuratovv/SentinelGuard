package uz.sentinel.guard.paper.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Who is staff, for players who are not online. Bukkit only answers permission questions for online players, so an
 * offline moderator used to be protected only when opped: any helper could ban an offline admin.
 *
 * <p>Two sources: every player seen online with a staff permission is remembered on disk (and forgotten when seen
 * without one), and when LuckPerms is installed it is asked directly, which also covers staff who never joined
 * this server. In network mode the list is shared through the database (sg_staff), so a moderator seen on any
 * server is protected on all of them.
 */
public final class StaffRegistry {
    public static final String[] STAFF_PERMISSIONS = {"guard.admin.bypass", "guard.admin", "guard.staff", "sus.admin"};

    private final JavaPlugin plugin;
    private final Path file;
    private final Set<UUID> staff = ConcurrentHashMap.newKeySet();
    /** Network mode: staff seen on any server (sg_staff), refreshed every minute. */
    private final Set<UUID> networkStaff = ConcurrentHashMap.newKeySet();
    private volatile uz.sentinel.guard.network.JdbcPunishmentStore network;
    private org.bukkit.scheduler.BukkitTask refresh;

    public void attachNetwork(uz.sentinel.guard.network.JdbcPunishmentStore store) {
        this.network = store;
        Set<UUID> mine = ConcurrentHashMap.newKeySet(); mine.addAll(staff);
        if (refresh != null) refresh.cancel();
        refresh = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            var current = network;
            if (current == null) return;
            try {
                for (UUID uuid : mine) current.setStaff(uuid, true, java.time.Instant.now()); // once per attach is enough
                mine.clear();
                Set<UUID> fresh = current.staff();
                networkStaff.retainAll(fresh);
                networkStaff.addAll(fresh);
            } catch (Exception e) {
                plugin.getLogger().log(Level.FINE, "Could not refresh the shared staff list", e);
            }
        }, 1L, 1200L);
    }

    public void detachNetwork() {
        this.network = null;
        if (refresh != null) refresh.cancel();
    }

    public StaffRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("staff.txt");
        load();
    }

    public static boolean isStaff(Player player) {
        if (player.isOp()) return true;
        for (String permission : STAFF_PERMISSIONS) if (player.hasPermission(permission)) return true;
        return false;
    }

    /** Called on join and quit, when permissions are known. */
    public void observe(Player player) {
        boolean now = isStaff(player);
        UUID uuid = player.getUniqueId();
        boolean changed = now ? staff.add(uuid) : staff.remove(uuid);
        boolean sharedChange = now ? networkStaff.add(uuid) : networkStaff.remove(uuid);
        if (changed) Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
        var current = network;
        if (current != null && (changed || sharedChange)) Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { current.setStaff(uuid, now, java.time.Instant.now()); }
            catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Could not share staff status of " + player.getName(), e); }
        });
    }

    public boolean isStaff(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return isStaff(online);
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid); // by UUID: no lookup
        if (offline.isOp()) return true;
        // LuckPerms knows the current groups (a demotion while offline counts); the registry is the fallback.
        Boolean luckPerms = luckPerms(uuid);
        if (luckPerms != null) {
            boolean changed = luckPerms ? staff.add(uuid) : staff.remove(uuid);
            if (changed) Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
            return luckPerms;
        }
        return staff.contains(uuid) || networkStaff.contains(uuid);
    }

    /** LuckPerms answer for an offline user, or null when LuckPerms is absent or slow. Read reflectively. */
    private Boolean luckPerms(UUID uuid) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) return null;
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider", true,
                    Bukkit.getPluginManager().getPlugin("LuckPerms").getClass().getClassLoader());
            Object api = provider.getMethod("get").invoke(null);
            Object users = api.getClass().getMethod("getUserManager").invoke(api);
            Object user = users.getClass().getMethod("getUser", UUID.class).invoke(users, uuid);
            if (user == null) {
                Object future = users.getClass().getMethod("loadUser", UUID.class).invoke(users, uuid);
                // a staff command waits at most this long for LuckPerms storage
                user = ((java.util.concurrent.CompletableFuture<?>) future).get(400, TimeUnit.MILLISECONDS);
            }
            if (user == null) return null;
            Object cached = user.getClass().getMethod("getCachedData").invoke(user);
            Object data = cached.getClass().getMethod("getPermissionData").invoke(cached);
            for (String permission : STAFF_PERMISSIONS) {
                Object tristate = data.getClass().getMethod("checkPermission", String.class).invoke(data, permission);
                if ((Boolean) tristate.getClass().getMethod("asBoolean").invoke(tristate)) return true;
            }
            return false;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                try { staff.add(UUID.fromString(trimmed)); } catch (IllegalArgumentException ignored) { }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read staff.txt", e);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("staff.txt.tmp");
            StringBuilder out = new StringBuilder("# Players seen with a staff permission; they stay protected while offline.\n");
            for (UUID uuid : staff) out.append(uuid).append('\n');
            Files.writeString(tmp, out, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save staff.txt", e);
        }
    }
}
