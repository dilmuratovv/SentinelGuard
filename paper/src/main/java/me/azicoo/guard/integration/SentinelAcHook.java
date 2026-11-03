package me.azicoo.guard.integration;

import me.azicoo.guard.GuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

/** Optional observer. Never changes SentinelAC cancellation or enforcement. */
public final class SentinelAcHook implements Listener, AutoCloseable {
    private final GuardPlugin plugin;
    private final ArrayBlockingQueue<Flag> pending = new ArrayBlockingQueue<>(2048);
    private final Map<String, Counter> counts = new LinkedHashMap<>();
    private Object bus, owner;
    private Method unregister;
    private org.bukkit.scheduler.BukkitTask task;
    private long nextAttempt;
    private record Flag(UUID player, String name, String check) {}
    private record Counter(int count, long since) {}
    public SentinelAcHook(GuardPlugin plugin) { this.plugin = plugin; }
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 20);
    }
    @EventHandler public void onEnable(PluginEnableEvent e) {
        if (e.getPlugin().getName().equals("SentinelAC")) nextAttempt = 0;
    }
    @EventHandler public void onDisable(PluginDisableEvent e) {
        if (e.getPlugin().getName().equals("SentinelAC")) detach();
    }
    private void attach(Plugin ac) {
        try {
            ClassLoader cl = ac.getClass().getClassLoader();
            String root = "uz.sentinel.engine.api.";
            Class<?> apiType = cl.loadClass(root + "SentinelAbstractAPI");
            Object api = cl.loadClass(root + "SentinelAPIProvider").getMethod("get").invoke(null);
            if (api == null || !((Boolean)apiType.getMethod("hasStarted").invoke(api))) return;
            Object newOwner = apiType.getMethod("getSentinelPlugin", Object.class).invoke(api, plugin);
            Object newBus = apiType.getMethod("getEventBus").invoke(api);
            Class<?> busType = cl.loadClass(root + "event.EventBus");
            Class<?> event = cl.loadClass(root + "event.events.FlagEvent");
            Class<?> handler = cl.loadClass(root + "event.events.FlagEvent$SupplierHandler");
            Class<?> ownerType = cl.loadClass(root + "plugin.EnginePlugin");
            Class<?> userType = cl.loadClass(root + "SentinelUser");
            Class<?> checkType = cl.loadClass(root + "AbstractCheck");
            Method uuid = userType.getMethod("getUniqueId"), name = userType.getMethod("getName");
            Method checkName = checkType.getMethod("getCheckName");
            Object observer = Proxy.newProxyInstance(cl, new Class<?>[]{handler}, (proxy, method, args) -> {
                if (method.getName().equals("onFlag")) {
                    boolean cancelled = (Boolean)args[3];
                    if (!cancelled) {
                        try { pending.offer(new Flag((UUID)uuid.invoke(args[0]), (String)name.invoke(args[0]), (String)checkName.invoke(args[1]))); }
                        catch (ReflectiveOperationException ignored) { /* observer cannot disrupt enforcement */ }
                    }
                    return cancelled;
                }
                return switch(method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> "SentinelGuard flag observer";
                };
            });
            Object channel = busType.getMethod("get", Class.class).invoke(newBus, event);
            Method removal = busType.getMethod("unregisterAllListeners", ownerType);
            int priority = cl.loadClass(root + "event.ListenerPriority").getField("MONITOR").getInt(null);
            channel.getClass().getMethod("onFlagSupplier", ownerType, handler, int.class, boolean.class).invoke(channel, newOwner, observer, priority, true);
            bus = newBus; owner = newOwner; unregister = removal;
            plugin.getLogger().info("SentinelAC connected automatically.");
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().warning("SentinelAC API incompatible; independent moderation remains available: " + e.getClass().getSimpleName());
        }
    }
    private void tick() {
        if (!plugin.getConfig().getBoolean("integrations.sentinelac.enabled", true)) { detach(); return; }
        long now = System.currentTimeMillis();
        if (bus == null && now >= nextAttempt) {
            nextAttempt = now + 60000;
            Plugin ac = Bukkit.getPluginManager().getPlugin("SentinelAC");
            if (ac != null && ac.isEnabled()) attach(ac);
        }
        counts.entrySet().removeIf(e -> now - e.getValue().since() >= 60000);
        for (int i=0;i<100;i++) {
            Flag f = pending.poll(); if (f == null) break;
            String key = f.player() + ":" + f.check();
            Counter old = counts.getOrDefault(key, new Counter(0, now));
            int threshold = Math.max(1, plugin.getConfig().getInt("integrations.sentinelac.sus-threshold", 10));
            int count = old.count() + 1;
            if (counts.size() >= 4096 && !counts.containsKey(key)) counts.remove(counts.keySet().iterator().next());
            counts.put(key, new Counter(count, old.since()));
            if (count == threshold) plugin.getSusManager().addSus(f.player(), f.name(), "SentinelAC: " + f.check(), "SentinelAC");
        }
    }
    private void detach() {
        if (bus != null) try { unregister.invoke(bus, owner); } catch (ReflectiveOperationException e) { plugin.getLogger().warning("SentinelAC listener cleanup failed."); }
        bus = null; owner = null; unregister = null; pending.clear(); counts.clear();
    }
    @Override public void close() {
        if (task != null) task.cancel();
        detach(); HandlerList.unregisterAll(this);
    }
}
