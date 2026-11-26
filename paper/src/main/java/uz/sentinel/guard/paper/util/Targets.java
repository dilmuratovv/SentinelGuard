package uz.sentinel.guard.paper.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.punishment.PunishmentRecord;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Turns a typed player name into a UUID without blocking the main thread.
 *
 * <p>Known names (online players, Guard's login records, punishment records, the server's user cache) resolve at once.
 * Anything else is looked up off the main thread (a Mojang profile request on online-mode or proxy-online servers)
 * and the command continues on the main thread. On an authenticating server an unknown name is reported as not found
 * instead of being punished under a made-up offline UUID that the real account would never match.
 */
public final class Targets {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.]{1,16}$");
    private static volatile Boolean authenticating;

    private Targets() {}

    /** Resolves now when the name is already known, else returns null. */
    public static Resolved known(GuardPlugin plugin, String name) {
        if (name == null || name.isEmpty()) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return new Resolved(online.getUniqueId(), online.getName());
        UUID logged = plugin.getAltManager().getUuidByName(name);
        if (logged != null) {
            String shown = plugin.getAltManager().getNameByUuid(logged);
            return new Resolved(logged, "Unknown".equals(shown) ? name : shown);
        }
        for (PunishmentRecord record : plugin.getPunishmentManager().getAllRecords()) {
            if (record.getTargetUuid() != null && name.equalsIgnoreCase(record.getTargetName())) {
                return new Resolved(record.getTargetUuid(), record.getTargetName());
            }
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) return new Resolved(cached.getUniqueId(), cached.getName() != null ? cached.getName() : name);
        return null;
    }

    /** Calls {@code then} on the main thread with the resolved player, or tells the sender the name is unknown. */
    public static void resolve(GuardPlugin plugin, CommandSender sender, String name, BiConsumer<UUID, String> then) {
        Resolved now = known(plugin, name);
        if (now != null) {
            // the staff check in {@code then} must not wait on LuckPerms storage on the main thread
            plugin.getStaffRegistry().whenKnown(now.uuid(), () -> then.accept(now.uuid(), now.name()));
            return;
        }
        if (!NAME.matcher(name).matches()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("player-not-found"));
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer looked;
            try {
                looked = Bukkit.getOfflinePlayer(name); // may call Mojang: never on the main thread
            } catch (RuntimeException e) {
                looked = null;
            }
            OfflinePlayer result = looked;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled()) return;
                if (result == null || (authenticating() && result.getUniqueId().version() == 3 && !result.hasPlayedBefore())) {
                    sender.sendMessage(plugin.getConfigManager().getMessage("player-not-found"));
                    return;
                }
                String shown = result.getName() != null ? result.getName() : name;
                plugin.getStaffRegistry().whenKnown(result.getUniqueId(), () -> then.accept(result.getUniqueId(), shown));
            });
        });
    }

    /** Whether player UUIDs come from Mojang (online mode, or online mode behind a Velocity/Bungee proxy). */
    public static boolean authenticating() {
        Boolean value = authenticating;
        if (value != null) return value;
        boolean result = Bukkit.getOnlineMode();
        if (!result) {
            try {
                Object global = Class.forName("io.papermc.paper.configuration.GlobalConfiguration").getMethod("get").invoke(null);
                Object proxies = global.getClass().getField("proxies").get(global);
                result = (Boolean) proxies.getClass().getMethod("isProxyOnlineMode").invoke(proxies);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // older layout: keep the plain online-mode answer
            }
        }
        authenticating = result;
        return result;
    }

    public record Resolved(UUID uuid, String name) {}
}
