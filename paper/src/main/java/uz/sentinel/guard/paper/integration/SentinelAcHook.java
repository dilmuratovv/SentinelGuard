package uz.sentinel.guard.paper.integration;

import uz.sentinel.guard.paper.GuardPlugin;
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
    private record Counter(int count, long since, long window) {}
    /** How many flags of one check, inside how long, put a player on the sus list. */
    public record Rule(int threshold, long windowMs) {}
    private final Map<String, Rule> ruleCache = new HashMap<>();

    /**
     * Built-in rules. Rare, high-confidence combat and client checks count from the first flag; reach and
     * autoclicker from a few; movement checks, which lag and packet loss also trip, need a burst.
     * Patterns end with * for a prefix. config: integrations.sentinelac.checks.<pattern>: "threshold/window-seconds".
     */
    static final List<Map.Entry<String, Rule>> DEFAULT_RULES = List.of(
        Map.entry("KillAura*", new Rule(1, 600_000)), Map.entry("TrackingA", new Rule(1, 600_000)),
        Map.entry("BacktrackA", new Rule(1, 600_000)), Map.entry("AutoTotem*", new Rule(1, 600_000)),
        Map.entry("CrystalAura*", new Rule(1, 600_000)), Map.entry("AimDuplicateLook", new Rule(2, 600_000)),
        Map.entry("AimModulo360", new Rule(1, 600_000)), Map.entry("Baritone", new Rule(1, 600_000)),
        Map.entry("Hitboxes", new Rule(2, 300_000)), Map.entry("FabricatedPlace", new Rule(1, 600_000)),
        Map.entry("Exploit*", new Rule(1, 600_000)), Map.entry("Crash*", new Rule(1, 600_000)),
        Map.entry("AutoClicker*", new Rule(2, 300_000)), Map.entry("Reach", new Rule(3, 300_000)),
        Map.entry("Simulation", new Rule(25, 60_000)), Map.entry("Phase", new Rule(10, 60_000)),
        Map.entry("Timer", new Rule(15, 60_000)), Map.entry("NegativeTimer", new Rule(15, 60_000)),
        Map.entry("TickTimer", new Rule(15, 60_000)), Map.entry("TimerLimit", new Rule(15, 60_000)),
        Map.entry("NoFall", new Rule(10, 60_000)), Map.entry("GroundSpoof", new Rule(10, 60_000)),
        Map.entry("Elytra*", new Rule(10, 60_000)), Map.entry("Vehicle*", new Rule(10, 60_000)),
        Map.entry("NoSlow", new Rule(10, 60_000)), Map.entry("AntiKB", new Rule(5, 120_000)),
        Map.entry("Sprint*", new Rule(15, 60_000)), Map.entry("Chat*", new Rule(5, 60_000)));

    /** The rule for one check: config pattern first (exact, then longest prefix), then the built-in table, then the default. */
    Rule rule(String check) {
        Rule cached = ruleCache.get(check);
        if (cached != null) return cached;
        org.bukkit.configuration.ConfigurationSection section = plugin.getConfig().getConfigurationSection("integrations.sentinelac.checks");
        List<Map.Entry<String, Rule>> configured = new ArrayList<>();
        if (section != null) for (String key : section.getKeys(false)) {
            Rule parsed = parseRule(section.getString(key));
            if (parsed != null) configured.add(Map.entry(key, parsed));
        }
        Rule result = match(configured, check);
        if (result == null) result = match(DEFAULT_RULES, check);
        if (result == null) result = new Rule(Math.max(1, plugin.getConfig().getInt("integrations.sentinelac.sus-threshold", 10)),
                Math.max(1, plugin.getConfig().getLong("integrations.sentinelac.window-seconds", 60)) * 1000L);
        if (ruleCache.size() > 512) ruleCache.clear();
        ruleCache.put(check, result);
        return result;
    }

    static Rule match(List<Map.Entry<String, Rule>> rules, String check) {
        Rule best = null;
        int bestLength = -1;
        for (Map.Entry<String, Rule> e : rules) {
            String pattern = e.getKey();
            if (pattern.equalsIgnoreCase(check)) return e.getValue();
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (check.regionMatches(true, 0, prefix, 0, prefix.length()) && prefix.length() > bestLength) {
                    best = e.getValue();
                    bestLength = prefix.length();
                }
            }
        }
        return best;
    }

    /** "3/300" = 3 flags within 300 seconds; "3" = 3 flags within the default window. */
    static Rule parseRule(String raw) {
        if (raw == null) return null;
        try {
            String[] parts = raw.trim().split("\\s*/\\s*");
            int threshold = Integer.parseInt(parts[0]);
            long window = parts.length > 1 ? Long.parseLong(parts[1]) : 60;
            if (threshold < 1 || window < 1) return null;
            return new Rule(threshold, window * 1000L);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Called on config reload. */
    public void clearRules() { ruleCache.clear(); }
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
        counts.entrySet().removeIf(e -> now - e.getValue().since() >= e.getValue().window());
        for (int i=0;i<500;i++) {
            Flag f = pending.poll(); if (f == null) break;
            String key = f.player() + ":" + f.check();
            Rule rule = rule(f.check());
            Counter old = counts.get(key);
            if (old == null || now - old.since() >= rule.windowMs()) old = new Counter(0, now, rule.windowMs());
            int count = old.count() + 1;
            if (counts.size() >= 8192 && !counts.containsKey(key)) counts.remove(counts.keySet().iterator().next());
            counts.put(key, new Counter(count, old.since(), rule.windowMs()));
            if (count == rule.threshold()) {
                String window = rule.windowMs() >= 60_000 ? (rule.windowMs() / 60_000) + " daq" : (rule.windowMs() / 1000) + " s";
                plugin.getSusManager().addSus(f.player(), f.name(),
                        "SentinelAC: " + f.check() + " (" + count + "x / " + window + ")", "SentinelAC", added -> { });
            }
        }
    }
    private void detach() {
        if (bus != null) try { unregister.invoke(bus, owner); } catch (ReflectiveOperationException e) { plugin.getLogger().warning("SentinelAC listener cleanup failed."); }
        bus = null; owner = null; unregister = null; pending.clear(); counts.clear();
    }
    public boolean isConnected() { return bus != null; }

    @Override public void close() {
        if (task != null) task.cancel();
        detach(); HandlerList.unregisterAll(this);
    }
}
