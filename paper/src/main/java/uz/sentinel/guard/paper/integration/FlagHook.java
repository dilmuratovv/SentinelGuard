package uz.sentinel.guard.paper.integration;

import uz.sentinel.guard.paper.GuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Optional observer of a Sentinel companion's flag event (SentinelAD's DupeFlagEvent, SentinelAX-ray's
 * XrayFlagEvent...). Never changes what the companion blocks or logs. The companion is read reflectively, so Guard
 * does not need it at compile time; every such event exposes getPlayerId, getPlayerName, getTypeId, getConfidence,
 * getDetail and isTest.
 */
public final class FlagHook implements Listener, AutoCloseable {
    private static final long WINDOW_MS = 10 * 60_000L;

    private final GuardPlugin plugin;
    private final String pluginName;   // Bukkit name of the companion, e.g. "SentinelAD"
    private final String eventName;    // fully qualified event class
    private final String configKey;    // integrations.<key>.*
    private final int defaultMinConfidence;
    private final Map<String, Counter> counts = new LinkedHashMap<>();
    private final Listener observer = new Listener() { };
    private Class<?> eventClass;
    private Method playerId, playerName, typeId, confidence, test, detail;
    private org.bukkit.scheduler.BukkitTask task;
    private long nextAttempt;
    private record Counter(int count, long since) {}

    public FlagHook(GuardPlugin plugin, String pluginName, String eventName, String configKey, int defaultMinConfidence) {
        this.plugin = plugin;
        this.pluginName = pluginName;
        this.eventName = eventName;
        this.configKey = configKey;
        this.defaultMinConfidence = defaultMinConfidence;
    }

    public String pluginName() { return pluginName; }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 20);
    }

    @EventHandler public void onEnable(PluginEnableEvent e) {
        if (e.getPlugin().getName().equals(pluginName)) nextAttempt = 0;
    }

    @EventHandler public void onDisable(PluginDisableEvent e) {
        if (e.getPlugin().getName().equals(pluginName)) detach();
    }

    @SuppressWarnings("unchecked")
    private void attach(Plugin ad) {
        try {
            ClassLoader cl = ad.getClass().getClassLoader();
            Class<?> type = cl.loadClass(eventName);
            Method pid = type.getMethod("getPlayerId"), name = type.getMethod("getPlayerName"),
                    id = type.getMethod("getTypeId"), conf = type.getMethod("getConfidence"), isTest = type.getMethod("isTest"),
                    info = type.getMethod("getDetail");
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) type, observer, EventPriority.MONITOR,
                    (listener, event) -> { if (type.isInstance(event)) handle(event); }, plugin, false);
            eventClass = type; playerId = pid; playerName = name; typeId = id; confidence = conf; test = isTest; detail = info;
            plugin.getLogger().info(pluginName + " connected automatically.");
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            plugin.getLogger().warning(pluginName + " API incompatible; independent moderation remains available: " + e.getClass().getSimpleName());
        }
    }

    private void handle(Object event) {
        try {
            if (!plugin.getConfig().getBoolean("integrations." + configKey + ".enabled", true)) return;
            if ((Boolean) test.invoke(event)) return; // /sad testflag never punishes
            int minConfidence = plugin.getConfig().getInt("integrations." + configKey + ".min-confidence", defaultMinConfidence);
            if ((Integer) confidence.invoke(event) < minConfidence) return;
            UUID uuid = (UUID) playerId.invoke(event);
            String name = (String) playerName.invoke(event), type = (String) typeId.invoke(event);

            long now = System.currentTimeMillis();
            counts.entrySet().removeIf(e -> now - e.getValue().since() >= WINDOW_MS);
            String key = uuid + ":" + type;
            Counter old = counts.getOrDefault(key, new Counter(0, now));
            int threshold = Math.max(1, plugin.getConfig().getInt("integrations." + configKey + ".sus-threshold", 1));
            int count = old.count() + 1;
            if (counts.size() >= 4096 && !counts.containsKey(key)) counts.remove(counts.keySet().iterator().next());
            counts.put(key, new Counter(count, old.since()));
            if (count == threshold) {
                // The finding's own description tells staff what to look at; keep it well inside the 512-char column.
                String text = String.valueOf(detail.invoke(event)).replaceAll("\\s+", " ").trim();
                if (text.length() > 200) text = text.substring(0, 197) + "...";
                String reason = pluginName + ": " + type + (text.isEmpty() ? "" : " - " + text);
                plugin.getSusManager().addSus(uuid, name, reason, pluginName, added -> { });
            }
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            // an observer must never disrupt the companion
        }
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("integrations." + configKey + ".enabled", true)) { detach(); return; }
        long now = System.currentTimeMillis();
        if (eventClass == null && now >= nextAttempt) {
            nextAttempt = now + 60000;
            Plugin companion = Bukkit.getPluginManager().getPlugin(pluginName);
            if (companion != null && companion.isEnabled()) attach(companion);
        }
    }

    private void detach() {
        if (eventClass != null) {
            try {
                ((org.bukkit.event.HandlerList) eventClass.getMethod("getHandlerList").invoke(null)).unregister(observer);
            } catch (ReflectiveOperationException | ClassCastException e) {
                plugin.getLogger().warning(pluginName + " listener cleanup failed.");
            }
        }
        eventClass = null; playerId = playerName = typeId = confidence = test = detail = null;
        counts.clear();
    }

    public boolean isConnected() { return eventClass != null; }

    @Override public void close() {
        if (task != null) task.cancel();
        detach(); HandlerList.unregisterAll(this);
    }
}
