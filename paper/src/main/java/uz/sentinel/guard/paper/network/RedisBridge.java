package uz.sentinel.guard.paper.network;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;
import uz.sentinel.guard.paper.GuardPlugin;

import java.net.URI;
import java.util.logging.Level;

/**
 * Redis carries signals only. A "wake" message triggers an immediate SQL outbox poll; it never
 * contains punishment state, so a lost or forged message cannot apply or remove a punishment.
 */
final class RedisBridge implements AutoCloseable {
    private final GuardPlugin plugin;
    private final NetworkService network;
    private final URI uri;
    private final String channel;
    private final JedisPooled publisher;
    private volatile boolean running = true;
    private volatile boolean connected;
    private volatile JedisPubSub subscription;
    private Thread thread;

    RedisBridge(GuardPlugin plugin, NetworkService network, String uri, String channel) {
        this.plugin = plugin;
        this.network = network;
        this.uri = URI.create(uri);
        this.channel = channel;
        this.publisher = new JedisPooled(this.uri);
    }

    void start() {
        thread = new Thread(this::run, "SentinelGuard-Redis");
        thread.setDaemon(true);
        thread.start();
    }

    boolean connected() { return connected; }

    private void run() {
        long backoff = 1000;
        while (running) {
            try (Jedis jedis = new Jedis(uri)) {
                JedisPubSub listener = new JedisPubSub() {
                    @Override public void onSubscribe(String ch, int count) {
                        connected = true;
                        // Reconnected: anything published while we were away is recovered from SQL.
                        network.poll();
                    }
                    @Override public void onMessage(String ch, String message) { handle(message); }
                };
                subscription = listener;
                jedis.subscribe(listener, channel);
                backoff = 1000;
            } catch (Exception e) {
                if (running) plugin.getLogger().log(Level.FINE, "Redis subscription lost", e);
            } finally {
                connected = false;
            }
            if (!running) break;
            try { Thread.sleep(backoff); } catch (InterruptedException e) { return; }
            backoff = Math.min(30000, backoff * 2);
        }
    }

    private void handle(String message) {
        if (message.equals("wake")) { network.poll(); return; }
        String[] parts = message.split("\t", 4);
        if (parts.length == 4 && parts[0].equals("sc") && !parts[1].equals(network.serverId())) {
            network.sync(() -> {
                String line = plugin.getConfigManager().colorize(plugin.getConfigManager().getConfig()
                        .getString("network.staff-chat-format", "&8[&cStaffChat&8] &7[{server}] &b{staff}&8: &f{message}"))
                        .replace("{server}", parts[1]).replace("{staff}", parts[2]).replace("{message}", parts[3]);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("guard.staffchat") || p.hasPermission("sus.admin") || p.isOp()) p.sendMessage(line);
                }
                Bukkit.getConsoleSender().sendMessage(line);
            });
        }
    }

    boolean publish(String message) {
        if (!connected) return false;
        network.submit(s -> publisher.publish(channel, message));
        return true;
    }

    @Override
    public void close() {
        running = false;
        JedisPubSub current = subscription;
        if (current != null) {
            try { current.unsubscribe(); } catch (Exception ignored) { /* connection already gone */ }
        }
        if (thread != null) thread.interrupt();
        publisher.close();
    }
}
