package uz.sentinel.guard.paper.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;
import uz.sentinel.guard.network.JdbcPunishmentStore;
import uz.sentinel.guard.network.NetworkPlayer;
import uz.sentinel.guard.network.NetworkPunishment;
import uz.sentinel.guard.network.NetworkSus;
import uz.sentinel.guard.network.OutboxEvent;
import uz.sentinel.guard.paper.GuardPlugin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Network mode: MariaDB is authoritative. All JDBC runs on a bounded executor; results are
 * handed back to the server thread. Redis (optional) only wakes the outbox poller and carries staff chat.
 */
public final class NetworkService implements AutoCloseable {
    @FunctionalInterface
    public interface SqlCall<T> { T call(JdbcPunishmentStore store) throws Exception; }

    private final GuardPlugin plugin;
    private final String serverId;
    private final HikariDataSource pool;
    private final JdbcPunishmentStore store;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile long lastSequence;
    private volatile boolean closed;
    private BukkitTask pollTask, pruneTask;
    private RedisBridge redis;

    private NetworkService(GuardPlugin plugin, ConfigurationSection section) throws Exception {
        this.plugin = plugin;
        this.serverId = section.getString("server-id", "server");
        if (serverId.isBlank() || serverId.length() > 128) throw new IllegalArgumentException("network.server-id must be 1-128 characters");
        ConfigurationSection db = section.getConfigurationSection("database");
        if (db == null) throw new IllegalArgumentException("network.database section missing");
        HikariConfig config = new HikariConfig();
        config.setPoolName("SentinelGuard-" + serverId);
        config.setDriverClassName(db.getString("driver", "org.mariadb.jdbc.Driver"));
        config.setJdbcUrl(db.getString("jdbc-url"));
        config.setUsername(db.getString("username"));
        config.setPassword(db.getString("password"));
        int poolSize = Math.max(2, db.getInt("pool-size", 4));
        config.setMaximumPoolSize(poolSize);
        config.setConnectionTimeout(3000);
        config.setValidationTimeout(2000);
        config.addDataSourceProperty("connectTimeout", "3000");
        config.addDataSourceProperty("socketTimeout", "5000");
        AtomicInteger threads = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(poolSize, poolSize, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2048), r -> {
            Thread t = new Thread(r, "SentinelGuard-DB-" + threads.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        HikariDataSource created = new HikariDataSource(config);
        try {
            JdbcPunishmentStore ready = new JdbcPunishmentStore(created);
            ready.initialize();
            this.store = ready;
            this.pool = created;
            this.lastSequence = ready.latestSequence();
        } catch (Exception e) {
            created.close();
            executor.shutdownNow();
            throw e;
        }
    }

    /** Blocking start; call from onEnable (database must be reachable before players join). */
    public static NetworkService start(GuardPlugin plugin, ConfigurationSection section) throws Exception {
        NetworkService service = new NetworkService(plugin, section);
        long interval = Math.max(10L, section.getLong("poll-interval-ticks", 40L));
        service.pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, service::poll, interval, interval);
        long retentionDays = Math.max(1L, section.getLong("outbox-retention-days", 7L));
        service.pruneTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> service.submit(store -> {
            int removed = store.pruneOutbox(Instant.now().minus(Duration.ofDays(retentionDays)));
            if (removed > 0) plugin.getLogger().fine("Pruned " + removed + " outbox rows");
            return removed;
        }), 20L * 60, 20L * 3600);
        ConfigurationSection redis = section.getConfigurationSection("redis");
        if (redis != null && redis.getBoolean("enabled", false)) {
            try {
                service.redis = new RedisBridge(plugin, service, redis.getString("uri", "redis://127.0.0.1:6379"),
                        redis.getString("channel", "sentinelguard"));
                service.redis.start();
            } catch (LinkageError | RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Redis unavailable; SQL polling remains authoritative.", e);
                service.redis = null;
            }
        }
        return service;
    }

    public String serverId() { return serverId; }
    public JdbcPunishmentStore store() { return store; }
    public long lastSequence() { return lastSequence; }
    public boolean redisConnected() { return redis != null && redis.connected(); }

    /** Runs blocking SQL on the database executor. */
    public <T> CompletableFuture<T> submit(SqlCall<T> call) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) { future.completeExceptionally(new IllegalStateException("Network service closed")); return future; }
        try {
            executor.execute(() -> {
                try { future.complete(call.call(store)); }
                catch (Throwable t) { future.completeExceptionally(t); }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /** Completes on the server thread; failures are logged and passed to onError. */
    public <T> void submit(SqlCall<T> call, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        submit(call).whenComplete((value, error) -> sync(() -> {
            if (error == null) { onSuccess.accept(value); return; }
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            plugin.getLogger().log(Level.WARNING, "SentinelGuard database operation failed", cause);
            onError.accept(cause);
        }));
    }

    public void sync(Runnable task) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) task.run(); else Bukkit.getScheduler().runTask(plugin, task);
    }

    private record Changes(List<NetworkPunishment> punishments, List<NetworkPlayer> players, Map<UUID, Optional<NetworkSus>> sus) {}

    /** Reads committed outbox events and applies them; safe to call from any thread. */
    public void poll() {
        if (closed || !polling.compareAndSet(false, true)) return;
        submit(s -> {
            List<NetworkPunishment> punishments = new ArrayList<>();
            Set<UUID> players = new LinkedHashSet<>(), sus = new LinkedHashSet<>();
            long sequence = lastSequence;
            while (true) {
                List<OutboxEvent> events = s.outboxAfter(sequence, 500);
                for (OutboxEvent event : events) {
                    switch (event.action()) {
                        case CREATED, REVOKED -> { if (event.punishmentId() != null) s.find(event.punishmentId()).ifPresent(punishments::add); }
                        case PLAYER -> { if (event.player() != null) players.add(event.player()); }
                        case SUS -> { if (event.player() != null) sus.add(event.player()); }
                    }
                    sequence = event.sequence();
                }
                if (events.size() < 500) break;
            }
            List<NetworkPlayer> playerRows = new ArrayList<>();
            for (UUID id : players) s.player(id).ifPresent(playerRows::add);
            Map<UUID, Optional<NetworkSus>> susRows = new LinkedHashMap<>();
            for (UUID id : sus) susRows.put(id, s.sus(id));
            lastSequence = sequence;
            return new Changes(punishments, playerRows, susRows);
        }).whenComplete((changes, error) -> {
            polling.set(false);
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Outbox poll failed; will retry", error);
                return;
            }
            if (changes.punishments().isEmpty() && changes.players().isEmpty() && changes.sus().isEmpty()) return;
            sync(() -> {
                changes.players().forEach(plugin.getAltManager()::applyRemote);
                changes.sus().forEach((id, row) -> plugin.getSusManager().applyRemote(id, row.orElse(null)));
                changes.punishments().forEach(plugin.getPunishmentManager()::applyRemote);
            });
        });
    }

    /** Signal other servers that a commit happened; they still read the change from SQL. */
    public void publishChange() {
        if (redis != null) redis.publish("wake");
    }

    /** Network staff chat; returns false when Redis is not connected (caller shows it locally only). */
    public boolean publishStaffChat(String sender, String message) {
        return redis != null && redis.publish("sc\t" + serverId + "\t" + sender + "\t" + message);
    }

    @Override
    public void close() {
        closed = true;
        if (pollTask != null) pollTask.cancel();
        if (pruneTask != null) pruneTask.cancel();
        if (redis != null) redis.close();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        pool.close();
    }
}
