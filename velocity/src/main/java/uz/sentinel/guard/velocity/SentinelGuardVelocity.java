package uz.sentinel.guard.velocity;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import uz.sentinel.guard.network.*;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Proxy enforcement. Reads the same MariaDB as the Paper servers: denies banned logins (account and
 * IP), disconnects players as soon as a ban commits (outbox), and periodically re-checks everyone online.
 * Moderation commands run on the Paper servers; the proxy only enforces.
 */
@Plugin(id="sentinelguard", name="SentinelGuard", version="2.3.0", authors={"azicoo"})
public final class SentinelGuardVelocity {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile JdbcPunishmentStore store;
    private volatile long lastSequence;
    private volatile boolean denyOnError = true;
    /** Per-server punishment scope published by the Paper servers (sg_servers), keyed by Paper server-id. */
    private volatile Map<String, ServerScope> scopes = Map.of();
    /** Velocity backend name -> Paper server-id; unlisted backends use their own name. */
    private final Map<String, String> serverIds = new ConcurrentHashMap<>();
    private volatile String unavailableMessage = "Moderation service unavailable. Please try later.";
    // Frozen players (an active FREEZE row) cannot switch servers; leaving the network on purpose is banned here,
    // because only the proxy can tell leaving from switching.
    private volatile boolean freezeAutoBan = true;
    private volatile long freezeBanDays = 30;
    private volatile String freezeBanReason = "Left during a screenshare check";
    private volatile String freezeSwitchMessage = "You are frozen for a check and cannot change servers.";
    private volatile long freezeGraceSeconds = 180;
    private volatile String freezeNoReturnReason = "Left during a screenshare check and did not return";
    private volatile boolean stopping;
    /** Last backend kick per player: a forced move to a fallback server is not a switch, a stopped server not a leave. */
    private record BackendKick(long at, RegisteredServer server) {}
    private final Map<UUID, BackendKick> backendKicks = new ConcurrentHashMap<>();
    private HikariDataSource pool;
    private ScheduledTask poll, reconcile, retry;

    @Inject public SentinelGuardVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy; this.logger = logger; this.directory = directory;
    }

    @Subscribe public void initialize(ProxyInitializeEvent event) {
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("sgproxy").plugin(this).build(), new StatusCommand());
        if (!connect()) retry = proxy.getScheduler().buildTask(this, () -> { if (store == null && connect()) retry.cancel(); })
                .delay(Duration.ofSeconds(30)).repeat(Duration.ofSeconds(30)).schedule();
    }

    private synchronized boolean connect() {
        if (store != null) return true;
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("database.properties");
            if (!Files.exists(file)) {
                Files.writeString(file, """
                    # SentinelGuard proxy: use the same database as the Paper servers.
                    jdbcUrl=jdbc:mariadb://127.0.0.1:3306/sentinelguard
                    username=sentinelguard
                    password=
                    # true = deny logins while the database is unreachable (bans are never bypassed)
                    denyLoginOnDatabaseError=true
                    unavailableMessage=Moderation service unavailable. Please try later.
                    # Milliseconds between outbox reads (new bans disconnect within this delay)
                    pollIntervalMs=1000
                    # Seconds between full re-checks of every online player
                    reconcileIntervalSeconds=60
                    # Servers that ignore bans (network.scope.enforce-bans: false on that Paper server) are
                    # found through the database. If a Velocity server name differs from the Paper
                    # network.server-id, map it: server.<velocity-name>=<paper-server-id>
                    #server.lobby=lobby-1
                    # A frozen player who leaves the network and does not return within the grace period is banned
                    # (a kick by staff through Guard and a proxy stop never ban). Frozen players cannot switch servers.
                    freezeAutoBanOnLeave=true
                    freezeAutoBanDays=30
                    freezeAutoBanReason=Left during a screenshare check
                    # seconds a frozen player has to come back (frozen again) before the ban; 0 = ban at once
                    freezeReturnGraceSeconds=180
                    freezeNoReturnReason=Left during a screenshare check and did not return
                    freezeSwitchMessage=You are frozen for a check and cannot change servers.
                    """);
                logger.error("Configure {} and restart. Logins are denied until the database is reachable.", file);
                return false;
            }
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(file)) { properties.load(reader); }
            denyOnError = Boolean.parseBoolean(properties.getProperty("denyLoginOnDatabaseError", "true"));
            serverIds.clear();
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith("server.") && key.length() > 7) serverIds.put(key.substring(7), properties.getProperty(key).trim());
            }
            unavailableMessage = properties.getProperty("unavailableMessage", unavailableMessage);
            freezeAutoBan = Boolean.parseBoolean(properties.getProperty("freezeAutoBanOnLeave", "true"));
            freezeBanDays = Math.max(0, Long.parseLong(properties.getProperty("freezeAutoBanDays", "30").trim()));
            freezeBanReason = properties.getProperty("freezeAutoBanReason", freezeBanReason);
            freezeSwitchMessage = properties.getProperty("freezeSwitchMessage", freezeSwitchMessage);
            freezeGraceSeconds = Math.max(0, Long.parseLong(properties.getProperty("freezeReturnGraceSeconds", "180").trim()));
            freezeNoReturnReason = properties.getProperty("freezeNoReturnReason", freezeNoReturnReason);
            HikariConfig config = new HikariConfig();
            config.setPoolName("SentinelGuard-proxy");
            config.setDriverClassName("org.mariadb.jdbc.Driver"); // relocated by the shade plugin
            config.setJdbcUrl(properties.getProperty("jdbcUrl"));
            config.setUsername(properties.getProperty("username"));
            config.setPassword(properties.getProperty("password"));
            config.setMaximumPoolSize(4); config.setConnectionTimeout(3000); config.setValidationTimeout(2000);
            config.addDataSourceProperty("connectTimeout", "3000");
            config.addDataSourceProperty("socketTimeout", "5000");
            HikariDataSource created = new HikariDataSource(config);
            try {
                JdbcPunishmentStore ready = new JdbcPunishmentStore(created);
                ready.initialize();
                lastSequence = ready.latestSequence();
                scopes = scopeMap(ready.servers());
                pool = created;
                store = ready;
            } catch (Exception e) {
                created.close();
                throw e;
            }
            long pollMs = Math.max(200, Long.parseLong(properties.getProperty("pollIntervalMs", "1000")));
            long reconcileSeconds = Math.max(10, Long.parseLong(properties.getProperty("reconcileIntervalSeconds", "60")));
            poll = proxy.getScheduler().buildTask(this, this::poll).repeat(Duration.ofMillis(pollMs)).schedule();
            reconcile = proxy.getScheduler().buildTask(this, this::reconcileAll).repeat(Duration.ofSeconds(reconcileSeconds)).schedule();
            logger.info("SentinelGuard connected to the network database.");
            return true;
        } catch (Exception e) {
            logger.error("SentinelGuard database initialization failed; logins denied. Retrying in 30s.", e);
            return false;
        }
    }

    @Subscribe public EventTask login(LoginEvent event) {
        return EventTask.async(() -> {
            JdbcPunishmentStore current = store;
            if (current == null) {
                if (denyOnError) event.setResult(ComponentResult.denied(Component.text(unavailableMessage, NamedTextColor.RED)));
                return;
            }
            try {
                var bans = current.activeBans(event.getPlayer().getUniqueId(), ip(event.getPlayer()), Instant.now());
                // With a ban-exempt server on the network the player may still connect to it; every other
                // server is blocked in preConnect. Without one, the ban is a full network ban as before.
                if (!bans.isEmpty() && exemptServers().isEmpty()) event.setResult(ComponentResult.denied(screen(bans.get(0))));
            } catch (Exception e) {
                logger.warn("Could not check login punishment", e);
                if (denyOnError) event.setResult(ComponentResult.denied(Component.text(unavailableMessage, NamedTextColor.RED)));
            }
        });
    }

    /**
     * Server switches and the first connection. A banned player may only be on servers that ignore bans:
     * they are sent to one on login, and kept out of the others afterwards.
     */
    @Subscribe public EventTask preConnect(ServerPreConnectEvent event) {
        return EventTask.async(() -> {
            JdbcPunishmentStore current = store;
            Optional<RegisteredServer> target = event.getResult().getServer();
            if (current == null || target.isEmpty()) return;
            Player player = event.getPlayer();
            BackendKick kick = backendKicks.get(player.getUniqueId());
            boolean forcedMove = kick != null && System.currentTimeMillis() - kick.at() < 10_000;
            if (event.getPreviousServer() != null && !forcedMove) {
                try {
                    if (frozen(current, player.getUniqueId())) {
                        event.setResult(ServerPreConnectEvent.ServerResult.denied());
                        player.sendMessage(Component.text(freezeSwitchMessage, NamedTextColor.RED));
                        return;
                    }
                } catch (Exception e) {
                    logger.warn("Could not check freeze state; server switch refused", e);
                    event.setResult(ServerPreConnectEvent.ServerResult.denied());
                    return;
                }
            }
            if (!enforcesBans(target.get())) return;
            List<NetworkPunishment> bans;
            try {
                bans = current.activeBans(player.getUniqueId(), ip(player), Instant.now());
            } catch (Exception e) {
                logger.warn("Could not check server connection punishment", e);
                if (denyOnError) deny(event, player, Component.text(unavailableMessage, NamedTextColor.RED));
                return;
            }
            if (bans.isEmpty()) return;
            Optional<RegisteredServer> previous = event.getPreviousServer() != null ? Optional.of(event.getPreviousServer()) : Optional.empty();
            Optional<RegisteredServer> refuge = exemptServers().stream().findFirst();
            if (previous.isEmpty() && refuge.isPresent()) {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(refuge.get())); // first join: land on a server that allows them
                return;
            }
            deny(event, player, screen(bans.get(0)));
        });
    }

    private static boolean frozen(JdbcPunishmentStore store, UUID player) throws Exception {
        Instant now = Instant.now();
        for (NetworkPunishment p : store.active(player, now)) if (p.type() == NetworkPunishment.Type.FREEZE && p.activeAt(now)) return true;
        return false;
    }

    /**
     * A frozen player who leaves the network must be back within the grace period (frozen again by the backend from
     * the network freeze row) or is banned. The proxy cannot tell a quit from a lost connection, and a cheat client
     * can fake either, so both get the same deadline; a kick issued by staff through Guard (a KICK row written just
     * before) and a proxy shutdown never ban.
     */
    @Subscribe public void kickedFromServer(KickedFromServerEvent event) {
        backendKicks.put(event.getPlayer().getUniqueId(), new BackendKick(System.currentTimeMillis(), event.getServer()));
    }

    @Subscribe public void disconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        BackendKick kick = backendKicks.remove(player.getUniqueId());
        if (stopping || !freezeAutoBan || event.getLoginStatus() != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN) return;
        UUID uuid = player.getUniqueId();
        String name = player.getUsername(), address = ip(player);
        long left = System.currentTimeMillis();
        proxy.getScheduler().buildTask(this, () -> {
            JdbcPunishmentStore current = store;
            if (current == null) return;
            try {
                if (!frozen(current, uuid)) return;
                if (staffKickedSince(current, uuid, left - 15_000)) {
                    logger.info("{} was kicked by staff while frozen: no ban.", name);
                    return;
                }
                if (kick != null && left - kick.at() < 10_000 && !reachable(kick.server())) {
                    logger.info("{} lost their server ({} is down) while frozen: no ban; the freeze stays.", name, kick.server().getServerInfo().getName());
                    return;
                }
                logger.info("{} left the network while frozen; waiting {} s for a return.", name, freezeGraceSeconds);
                proxy.getScheduler().buildTask(this, () -> banIfGone(uuid, name, address)).delay(Duration.ofSeconds(freezeGraceSeconds)).schedule();
            } catch (Exception e) {
                logger.warn("Could not check the freeze of " + name + " after leaving", e);
            }
        }).delay(Duration.ofSeconds(3)).schedule(); // a staff kick's KICK row is written alongside the kick
    }

    private void banIfGone(UUID uuid, String name, String address) {
        JdbcPunishmentStore current = store;
        if (current == null || stopping || proxy.getPlayer(uuid).isPresent()) return;
        try {
            Instant now = Instant.now();
            if (!frozen(current, uuid) || !current.activeBans(uuid, address, now).isEmpty()) return;
            String reason = freezeGraceSeconds == 0 ? freezeBanReason : freezeNoReturnReason;
            current.create(new NetworkPunishment(UUID.randomUUID(), uuid, name, address, NetworkPunishment.Type.BAN, reason,
                    null, "SentinelGuard", "proxy", now, freezeBanDays == 0 ? null : now.plus(Duration.ofDays(freezeBanDays)), false, null));
            logger.info("{} left the network while frozen and did not return: banned ({}).", name, reason);
        } catch (Exception e) {
            logger.warn("Could not ban " + name + " for leaving while frozen", e);
        }
    }

    private static boolean reachable(RegisteredServer server) {
        try {
            server.ping().get(3, java.util.concurrent.TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean staffKickedSince(JdbcPunishmentStore store, UUID player, long sinceMs) throws Exception {
        for (NetworkPunishment p : store.history(player, 5)) {
            if (p.type() == NetworkPunishment.Type.KICK && p.createdAt().toEpochMilli() >= sinceMs) return true;
        }
        return false;
    }

    private void deny(ServerPreConnectEvent event, Player player, Component message) {
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        if (event.getPreviousServer() == null) player.disconnect(message); else player.sendMessage(message);
    }

    private String serverId(RegisteredServer server) {
        String name = server.getServerInfo().getName();
        return serverIds.getOrDefault(name, name);
    }

    /** Unknown servers enforce bans, so a missing scope row never lets a banned player in. */
    private boolean enforcesBans(RegisteredServer server) {
        ServerScope scope = scopes.get(serverId(server));
        return scope == null || scope.enforceBans();
    }

    private List<RegisteredServer> exemptServers() {
        return proxy.getAllServers().stream().filter(server -> !enforcesBans(server)).toList();
    }

    private static Map<String, ServerScope> scopeMap(Collection<ServerScope> rows) {
        Map<String, ServerScope> map = new HashMap<>();
        for (ServerScope row : rows) map.put(row.serverId(), row);
        return Map.copyOf(map);
    }

    private void refreshScopes(JdbcPunishmentStore current) {
        try {
            scopes = scopeMap(current.servers());
        } catch (Exception e) {
            logger.warn("Could not refresh server punishment scopes; keeping the previous ones", e);
        }
    }

    /** Applies newly committed bans immediately; the outbox only names rows, state is re-read from SQL. */
    private void poll() {
        JdbcPunishmentStore current = store;
        if (current == null || !polling.compareAndSet(false, true)) return;
        try {
            List<OutboxEvent> events = current.outboxAfter(lastSequence, 500);
            Instant now = Instant.now();
            for (OutboxEvent e : events) {
                // PLAYER and SUS events carry no punishment id; only CREATED rows concern the proxy.
                if (e.action() == OutboxEvent.Action.CREATED && e.punishmentId() != null) {
                    current.find(e.punishmentId()).ifPresent(p -> {
                        if (p.type() == NetworkPunishment.Type.KICK) kick(p, now);
                        else if (p.activeAt(now)) enforce(p);
                    });
                }
                lastSequence = e.sequence();
            }
        } catch (Exception e) {
            logger.warn("Outbox poll failed; will retry", e);
        } finally {
            polling.set(false);
        }
    }

    /** Full re-check with two batched queries regardless of player count. */
    private void reconcileAll() {
        JdbcPunishmentStore current = store;
        if (current == null) return;
        refreshScopes(current);
        Collection<Player> online = proxy.getAllPlayers();
        if (online.isEmpty()) return;
        try {
            Instant now = Instant.now();
            List<NetworkPunishment> bans = new ArrayList<>(current.activeBans(online.stream().map(Player::getUniqueId).toList(), now));
            bans.addAll(current.activeIpBans(online.stream().map(SentinelGuardVelocity::ip).filter(Objects::nonNull).toList(), now));
            bans.forEach(this::enforce);
        } catch (Exception e) {
            logger.warn("Network punishment reconciliation failed", e);
        }
    }

    /** A kick issued on any server; only live kicks (not history replayed after a restart) disconnect anyone. */
    private void kick(NetworkPunishment p, Instant now) {
        if (p.player() == null || Duration.between(p.createdAt(), now).abs().getSeconds() > 30) return;
        proxy.getPlayer(p.player()).ifPresent(player -> player.disconnect(Component.text("Kicked: " + p.reason(), NamedTextColor.RED)));
    }

    private void enforce(NetworkPunishment p) {
        if (p.type() != NetworkPunishment.Type.BAN && p.type() != NetworkPunishment.Type.IP_BAN) return;
        for (Player player : proxy.getAllPlayers()) {
            boolean account = player.getUniqueId().equals(p.player());
            boolean address = p.type() == NetworkPunishment.Type.IP_BAN && p.ip() != null && IpAddresses.sameNetwork(p.ip(), ip(player));
            if (!account && !address) continue;
            // A player already on a ban-exempt server stays; only enforcing servers (or limbo) lose them.
            boolean exempt = player.getCurrentServer().map(connection -> !enforcesBans(connection.getServer())).orElse(false);
            if (exempt) continue;
            Optional<RegisteredServer> refuge = exemptServers().stream().findFirst();
            if (refuge.isPresent() && player.getCurrentServer().isPresent()) {
                // Moved, not disconnected: the ban does not apply on the exempt server.
                player.sendMessage(screen(p));
                player.createConnectionRequest(refuge.get()).fireAndForget();
            } else {
                player.disconnect(screen(p));
            }
        }
    }

    private static String ip(Player player) {
        var address = player.getRemoteAddress();
        return address == null || address.getAddress() == null ? null : IpAddresses.normalize(address.getAddress().getHostAddress());
    }

    private static Component screen(NetworkPunishment p) {
        String until = p.expiresAt() == null ? "Permanent" : DATE.format(p.expiresAt()) + " UTC";
        return Component.text("SentinelGuard", NamedTextColor.RED)
                .append(Component.newline()).append(Component.text(p.type() == NetworkPunishment.Type.IP_BAN ? "Your IP address is banned." : "You are banned from this network.", NamedTextColor.GRAY))
                .append(Component.newline()).append(Component.text("Reason: " + p.reason(), NamedTextColor.WHITE))
                .append(Component.newline()).append(Component.text("Expires: " + until, NamedTextColor.WHITE))
                .append(Component.newline()).append(Component.text("ID: " + p.displayId(), NamedTextColor.DARK_GRAY));
    }

    private final class StatusCommand implements SimpleCommand {
        @Override public void execute(Invocation invocation) {
            JdbcPunishmentStore current = store;
            invocation.source().sendMessage(Component.text("SentinelGuard proxy: " + (current == null ? "database NOT connected (logins " + (denyOnError ? "denied" : "allowed") + ")" : "connected, outbox sequence " + lastSequence),
                    current == null ? NamedTextColor.RED : NamedTextColor.GREEN));
        }
        @Override public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("sentinelguard.proxy.admin");
        }
    }

    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        stopping = true;
        store = null;
        if (retry != null) retry.cancel();
        if (poll != null) poll.cancel();
        if (reconcile != null) reconcile.cancel();
        if (pool != null) pool.close();
    }
}
