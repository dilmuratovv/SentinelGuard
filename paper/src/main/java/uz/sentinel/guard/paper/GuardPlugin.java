package uz.sentinel.guard.paper;

import uz.sentinel.guard.paper.alts.AltManager;
import uz.sentinel.guard.paper.commands.*;
import uz.sentinel.guard.paper.config.ConfigManager;
import uz.sentinel.guard.paper.gui.GUIListener;
import uz.sentinel.guard.paper.listeners.*;
import uz.sentinel.guard.paper.punishment.PunishmentFormatter;
import uz.sentinel.guard.paper.punishment.PunishmentManager;
import uz.sentinel.guard.paper.sus.FreezeManager;
import uz.sentinel.guard.paper.sus.SusManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;
import uz.sentinel.guard.paper.network.LegacyImporter;
import uz.sentinel.guard.paper.network.NetworkService;

import uz.sentinel.guard.network.NetworkPlayer;
import uz.sentinel.guard.network.NetworkSus;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

public class GuardPlugin extends JavaPlugin {
    private static GuardPlugin instance;

    private ConfigManager configManager;
    private PunishmentManager punishmentManager;
    private PunishmentFormatter punishmentFormatter;
    private AltManager altManager;
    private SusManager susManager;
    private FreezeManager freezeManager;
    private ChatInputListener chatInputListener;
    private BukkitTask autoSaveTask;
    private uz.sentinel.guard.paper.integration.SentinelAcHook sentinelAcHook;
    private volatile NetworkService network;
    private BukkitTask networkRetryTask;

    @Override
    public void onEnable() {
        instance = this;

        // Initialize configuration and data managers
        this.configManager = new ConfigManager(this);
        this.punishmentManager = new PunishmentManager(this);
        this.punishmentFormatter = new PunishmentFormatter(this);
        this.altManager = new AltManager(this);
        this.susManager = new SusManager(this);
        this.freezeManager = new FreezeManager(this);

        // Network mode: SQL is authoritative. Starts before listeners so no login is checked against local data.
        ConfigurationSection networkConfig = getConfig().getConfigurationSection("network");
        if (networkConfig != null && networkConfig.getBoolean("enabled", false)) {
            if (!startNetwork(networkConfig)) {
                punishmentManager.setNetworkUnavailable(true);
                networkRetryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                    if (network == null) startNetwork(networkConfig);
                }, 600L, 600L);
            }
        }

        // Register listeners
        this.chatInputListener = new ChatInputListener(this);
        Bukkit.getPluginManager().registerEvents(new GUIListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PunishmentListener(this), this);
        Bukkit.getPluginManager().registerEvents(new FreezeListener(this), this);
        Bukkit.getPluginManager().registerEvents(this.chatInputListener, this);
        Bukkit.getPluginManager().registerEvents(new CommandOverrideListener(this), this);

        // Register commands
        registerCommands();

        // Override CommandMap entries after server registers all plugins
        Bukkit.getScheduler().runTask(this, () -> {
            new CommandOverrideManager(this).overrideCommands();
        });

        sentinelAcHook = new uz.sentinel.guard.paper.integration.SentinelAcHook(this);
        sentinelAcHook.start();

        // Periodic auto-save task
        long saveInterval = this.getConfig().getLong("settings.save-interval-minutes", 5L) * 1200L;
        if (saveInterval > 0) {
            this.autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                if (punishmentManager != null) punishmentManager.saveData();
                if (susManager != null) susManager.saveData();
                if (altManager != null) altManager.saveData();
            }, saveInterval, saveInterval);
        }

        getLogger().info("==================================================");
        getLogger().info(" SentinelGuard Moderation & Ban Plugin v" + getDescription().getVersion());
        getLogger().info(" Barcha tizimlar muvaffaqiyatli yuklandi!");
        getLogger().info("==================================================");
    }

    /** Blocking network start; safe from onEnable or an async retry task. */
    private synchronized boolean startNetwork(ConfigurationSection section) {
        if (network != null) return true;
        try {
            NetworkService service = NetworkService.start(this, section);
            List<NetworkPlayer> players;
            List<NetworkSus> sus;
            try {
                // Loaded here, in the starting thread, so names resolve from the first command onwards.
                players = service.store().loadPlayers();
                sus = service.store().allSus();
            } catch (Exception e) {
                service.close();
                throw e;
            }
            network = service;
            Runnable attach = () -> {
                if (!isEnabled()) { service.close(); return; }
                punishmentManager.attachNetwork(service, Math.max(100, section.getInt("cache-size", 20000)));
                altManager.attachNetwork(service, players);
                susManager.attachNetwork(service, sus);
                punishmentManager.setNetworkUnavailable(false);
                if (networkRetryTask != null) networkRetryTask.cancel();
                getLogger().info("Network database connected as server '" + service.serverId() + "'.");
                if (section.getBoolean("import-legacy-on-start", true)) importLegacy(null);
            };
            if (Bukkit.isPrimaryThread()) attach.run(); else Bukkit.getScheduler().runTask(this, attach);
            return true;
        } catch (Exception | LinkageError e) {
            getLogger().log(Level.SEVERE, "SentinelGuard network database unavailable; logins are denied until it connects. Retrying every 30s.", e);
            return false;
        }
    }

    /** Copies legacy punishments.yml into SQL (idempotent, original file untouched). */
    public void importLegacy(org.bukkit.command.CommandSender sender) {
        NetworkService service = network;
        if (service == null) {
            if (sender != null) sender.sendMessage(configManager.getPrefix() + configManager.colorize("&cTarmoq rejimi yoqilmagan yoki baza ulanmagan."));
            return;
        }
        File folder = getDataFolder();
        if (!new File(folder, "punishments.yml").isFile() && !new File(folder, "ip_history.yml").isFile() && !new File(folder, "sus_data.yml").isFile()) {
            if (sender != null) sender.sendMessage(configManager.getPrefix() + configManager.colorize("&7Eski fayllar topilmadi, import qilinadigan narsa yo'q."));
            return;
        }
        LegacyImporter.run(service, folder, getLogger()).whenComplete((result, error) -> service.sync(() -> {
            if (error != null) {
                getLogger().log(Level.SEVERE, "Legacy import failed", error);
                if (sender != null) sender.sendMessage(configManager.getPrefix() + configManager.colorize("&cImport xatosi, konsolni tekshiring."));
                return;
            }
            String line = "Legacy import: punishments " + result.imported() + " imported, " + result.existing() + " already present, " + result.skipped()
                    + " skipped; players " + result.playersImported() + " imported, " + result.playersSkipped()
                    + " skipped; sus " + result.susImported() + " imported, " + result.susSkipped() + " skipped.";
            getLogger().info(line);
            if (sender != null) sender.sendMessage(configManager.getPrefix() + configManager.colorize("&a" + line));
            if (result.imported() > 0 || result.playersImported() > 0 || result.susImported() > 0) service.poll();
        }));
    }

    /** Freezes or unfreezes a player; network-wide in network mode, on this server otherwise. */
    public void setFrozen(UUID uuid, String name, boolean freeze, org.bukkit.command.CommandSender issuer, Consumer<Boolean> done) {
        if (punishmentManager.isNetworkUnavailable()) {
            punishmentManager.reportDatabaseError(issuer);
            return;
        }
        if (network == null) {
            org.bukkit.entity.Player target = Bukkit.getPlayer(uuid);
            if (target == null) {
                done.accept(false);
                return;
            }
            done.accept(freeze ? freezeManager.freezePlayer(target, issuer) : freezeManager.unfreezePlayer(target, issuer));
            return;
        }
        if (freeze) punishmentManager.freeze(uuid, name, issuer, done);
        else punishmentManager.unfreeze(uuid, issuer, done);
    }

    public NetworkService getNetwork() {
        return network;
    }

    private void registerCommands() {
        BanCommand banCmd = new BanCommand(this);
        register("ban", banCmd, banCmd);
        register("tempban", banCmd, banCmd);

        UnbanCommand unbanCmd = new UnbanCommand(this);
        register("unban", unbanCmd, unbanCmd);
        register("pardon", unbanCmd, unbanCmd);

        IpBanCommand ipBanCmd = new IpBanCommand(this);
        register("ipban", ipBanCmd, ipBanCmd);
        register("ban-ip", ipBanCmd, ipBanCmd);

        UnbanIpCommand unbanIpCmd = new UnbanIpCommand(this);
        register("unbanip", unbanIpCmd, unbanIpCmd);
        register("pardonip", unbanIpCmd, unbanIpCmd);

        MuteCommand muteCmd = new MuteCommand(this);
        register("mute", muteCmd, muteCmd);
        register("tempmute", muteCmd, muteCmd);

        UnmuteCommand unmuteCmd = new UnmuteCommand(this);
        register("unmute", unmuteCmd, unmuteCmd);

        KickCommand kickCmd = new KickCommand(this);
        register("kick", kickCmd, kickCmd);

        WarnCommand warnCmd = new WarnCommand(this);
        register("warn", warnCmd, warnCmd);
        register("unwarn", warnCmd, warnCmd);
        register("warnings", warnCmd, warnCmd);

        HistoryCommand historyCmd = new HistoryCommand(this);
        register("history", historyCmd, historyCmd);
        register("check", historyCmd, historyCmd);
        register("punishments", historyCmd, historyCmd);

        AltsCommand altsCmd = new AltsCommand(this);
        register("alts", altsCmd, altsCmd);
        register("dupeip", altsCmd, altsCmd);

        StaffChatCommand staffChatCmd = new StaffChatCommand(this);
        register("staffchat", staffChatCmd, null);
        register("sc", staffChatCmd, null);

        StaffHistoryCommand staffHistCmd = new StaffHistoryCommand(this);
        register("staffhistory", staffHistCmd, staffHistCmd);

        SusCommand susCmd = new SusCommand(this);
        register("sus", susCmd, susCmd);
        register("addsus", susCmd, susCmd);
        register("clearsus", susCmd, susCmd);

        FreezeCommand freezeCmd = new FreezeCommand(this);
        register("freeze", freezeCmd, freezeCmd);
        register("unfreeze", freezeCmd, freezeCmd);
        register("ss", freezeCmd, freezeCmd);

        GuardCommand guardCmd = new GuardCommand(this);
        register("sg", guardCmd, guardCmd);
    }

    private void register(String name, CommandExecutor executor, TabCompleter completer) {
        PluginCommand cmd = this.getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(executor);
            if (completer != null) {
                cmd.setTabCompleter(completer);
            }
        }
    }

    @Override
    public void onDisable() {
        if (sentinelAcHook != null) sentinelAcHook.close();
        if (networkRetryTask != null) networkRetryTask.cancel();
        if (this.autoSaveTask != null) {
            this.autoSaveTask.cancel();
        }
        if (this.punishmentManager != null) {
            this.punishmentManager.saveData();
        }
        if (this.susManager != null) {
            this.susManager.saveData();
        }
        if (this.altManager != null) {
            this.altManager.saveData();
        }
        if (this.freezeManager != null) {
            this.freezeManager.shutdown();
        }
        NetworkService service = network;
        network = null;
        if (punishmentManager != null) punishmentManager.detachNetwork();
        if (altManager != null) altManager.detachNetwork();
        if (susManager != null) susManager.detachNetwork();
        if (service != null) service.close();
        getLogger().info("Guard plagini to'xtatildi va barcha ma'lumotlar saqlandi.");
        instance = null;
    }

    public void reloadPlugin() {
        if (this.configManager != null) {
            this.configManager.reload();
        }
        if (this.punishmentManager != null) {
            this.punishmentManager.loadData();
        }
        if (this.susManager != null) {
            this.susManager.loadData();
        }
        if (this.altManager != null) {
            this.altManager.loadData();
        }
    }

    public boolean isImmune(org.bukkit.command.CommandSender sender, java.util.UUID targetUuid) {
        if (targetUuid == null) return false;
        // Console can manage anyone
        if (!(sender instanceof org.bukkit.entity.Player)) return false;

        org.bukkit.entity.Player target = Bukkit.getPlayer(targetUuid);
        if (target != null) {
            return target.isOp() ||
                    target.hasPermission("guard.admin.bypass") ||
                    target.hasPermission("guard.admin") ||
                    target.hasPermission("guard.staff") ||
                    target.hasPermission("sus.admin");
        }
        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(targetUuid);
        return op.isOp();
    }

    public static GuardPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public PunishmentManager getPunishmentManager() {
        return punishmentManager;
    }

    public PunishmentFormatter getPunishmentFormatter() {
        return punishmentFormatter;
    }

    public AltManager getAltManager() {
        return altManager;
    }

    public SusManager getSusManager() {
        return susManager;
    }

    public FreezeManager getFreezeManager() {
        return freezeManager;
    }

    public ChatInputListener getChatInputListener() {
        return chatInputListener;
    }
}
