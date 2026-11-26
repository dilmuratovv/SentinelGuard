package uz.sentinel.guard.lpstub;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only LuckPerms stand-in. Offline users are "in storage": loadUser completes after the delay in
 * plugins/LuckPerms/delay-ms (default 1500, longer than any wait a main thread could afford), and users named in
 * plugins/LuckPerms/staff.txt have guard.staff. Every load is logged with the calling thread.
 */
public final class StubPlugin extends JavaPlugin {
    public static StubPlugin instance;
    final Set<UUID> staff = new HashSet<>();
    public long delayMs = 1500;

    @Override public void onEnable() {
        instance = this;
        try {
            Path dir = getDataFolder().toPath();
            Files.createDirectories(dir);
            Path f = dir.resolve("staff.txt"), d = dir.resolve("delay-ms");
            if (Files.exists(f)) for (String n : Files.readAllLines(f, StandardCharsets.UTF_8))
                if (!n.isBlank()) staff.add(UUID.nameUUIDFromBytes(("OfflinePlayer:" + n.trim()).getBytes(StandardCharsets.UTF_8)));
            if (Files.exists(d)) delayMs = Long.parseLong(Files.readString(d).trim());
        } catch (Exception e) { throw new IllegalStateException(e); }
        net.luckperms.api.LuckPermsProvider.api = new net.luckperms.api.LuckPerms(this);
        getLogger().info("LPSTUB ready staff=" + staff.size() + " delay=" + delayMs);
    }

    public boolean isStaff(UUID u) { return staff.contains(u); }
}
