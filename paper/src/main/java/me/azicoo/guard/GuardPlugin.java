package me.azicoo.guard;

import me.azicoo.guard.alts.AltManager;
import me.azicoo.guard.commands.*;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.gui.GUIListener;
import me.azicoo.guard.listeners.*;
import me.azicoo.guard.punishment.PunishmentFormatter;
import me.azicoo.guard.punishment.PunishmentManager;
import me.azicoo.guard.sus.FreezeManager;
import me.azicoo.guard.sus.SusManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

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
    private me.azicoo.guard.integration.SentinelAcHook sentinelAcHook;

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

        sentinelAcHook = new me.azicoo.guard.integration.SentinelAcHook(this);
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
