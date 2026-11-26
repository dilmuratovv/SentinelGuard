package net.luckperms.api;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import uz.sentinel.guard.lpstub.StubPlugin;

/** Stand-in API object: getUserManager() returns this; users load from "storage" after a delay. */
public final class LuckPerms {
    private final StubPlugin plugin;
    private final Map<UUID, User> loaded = new ConcurrentHashMap<>();

    public LuckPerms(StubPlugin plugin) { this.plugin = plugin; }

    public LuckPerms getUserManager() { return this; }

    public User getUser(UUID uuid) {
        if (Bukkit.getPlayer(uuid) != null) return new User(plugin.isStaff(uuid));
        return loaded.get(uuid);
    }

    public CompletableFuture<User> loadUser(UUID uuid) {
        plugin.getLogger().info("LPSTUB loadUser " + uuid + " from " + Thread.currentThread().getName());
        return CompletableFuture.supplyAsync(() -> {
            User u = new User(plugin.isStaff(uuid));
            loaded.put(uuid, u);
            plugin.getLogger().info("LPSTUB loaded " + uuid + " staff=" + u.staff + " on " + Thread.currentThread().getName());
            return u;
        }, CompletableFuture.delayedExecutor(plugin.delayMs, TimeUnit.MILLISECONDS));
    }

    public static final class User {
        final boolean staff;
        User(boolean staff) { this.staff = staff; }
        public User getCachedData() { return this; }
        public User getPermissionData() { return this; }
        public Tristate checkPermission(String permission) { return new Tristate(staff && permission.equals("guard.staff")); }
    }

    public static final class Tristate {
        private final boolean value;
        Tristate(boolean value) { this.value = value; }
        public boolean asBoolean() { return value; }
    }
}
