package uz.sentinel.guard.velocity;
import com.google.inject.Inject;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import uz.sentinel.guard.network.*;
import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.util.Properties;

@Plugin(id="sentinelguard", name="SentinelGuard", version="2.0.0-SNAPSHOT", authors={"azicoo"})
public final class SentinelGuardVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private volatile JdbcPunishmentStore store;
    private HikariDataSource pool;
    private ScheduledTask poll;
    @Inject public SentinelGuardVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy=proxy; this.logger=logger; this.directory=directory;
    }
    @Subscribe public void initialize(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(directory);
            Path file=directory.resolve("database.properties");
            if (!Files.exists(file)) {
                Files.writeString(file,"jdbcUrl=jdbc:mariadb://127.0.0.1:3306/sentinelguard\nusername=sentinelguard\npassword=\n");
                logger.error("Configure database.properties before allowing network access. Logins will be denied until restart with a working database.");
                return;
            }
            Properties properties=new Properties();
            try (Reader reader=Files.newBufferedReader(file)) { properties.load(reader); }
            HikariConfig config=new HikariConfig();
            config.setJdbcUrl(properties.getProperty("jdbcUrl"));
            config.setUsername(properties.getProperty("username"));
            config.setPassword(properties.getProperty("password"));
            config.setMaximumPoolSize(4); config.setConnectionTimeout(3000); config.setValidationTimeout(2000);
            config.addDataSourceProperty("connectTimeout","3000");
            config.addDataSourceProperty("socketTimeout","5000");
            pool=new HikariDataSource(config);
            JdbcPunishmentStore ready=new JdbcPunishmentStore(pool); ready.initialize(); store=ready;
            poll=proxy.getScheduler().buildTask(this, this::reconcile).repeat(Duration.ofSeconds(2)).schedule();
        } catch(Exception e) { logger.error("SentinelGuard database initialization failed; logins denied.",e); }
    }
    @Subscribe public EventTask login(LoginEvent event) {
        return EventTask.async(() -> {
            JdbcPunishmentStore current=store;
            if (current == null) { event.setResult(ComponentResult.denied(Component.text("Moderation service unavailable. Please try later."))); return; }
            try {
                var bans=current.active(event.getPlayer().getUniqueId(),Instant.now()).stream().filter(p->p.type()==NetworkPunishment.Type.BAN).toList();
                if (!bans.isEmpty()) event.setResult(ComponentResult.denied(Component.text("SentinelGuard: " + bans.get(0).reason())));
            } catch(Exception e) {
                event.setResult(ComponentResult.denied(Component.text("Moderation service unavailable. Please try later.")));
                logger.warn("Could not check login punishment",e);
            }
        });
    }
    private void reconcile() {
        JdbcPunishmentStore current=store; if(current==null)return;
        for(var player:proxy.getAllPlayers()) {
            try {
                for(var p:current.active(player.getUniqueId(),Instant.now())) {
                    if(p.type()==NetworkPunishment.Type.BAN) { player.disconnect(Component.text("SentinelGuard: "+p.reason())); break; }
                }
            } catch(Exception e) { logger.warn("Network punishment reconciliation failed",e); return; }
        }
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        store=null; if(poll!=null)poll.cancel(); if(pool!=null)pool.close();
    }
}
