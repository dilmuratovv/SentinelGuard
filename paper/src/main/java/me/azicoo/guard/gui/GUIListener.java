package me.azicoo.guard.gui;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.punishment.PunishmentRecord;
import me.azicoo.guard.punishment.PunishmentType;
import me.azicoo.guard.sus.SusEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.Set;
import java.util.UUID;

public class GUIListener implements Listener {
    private final GuardPlugin plugin;

    public GUIListener(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof MainSusGUI || holder instanceof PlayerControlGUI ||
                holder instanceof QuickPunishGUI || holder instanceof HistoryGUI)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getInventory().getSize()) {
            return;
        }

        ConfigManager config = plugin.getConfigManager();
        ClickType clickType = event.getClick();
        String required = null;
        if (holder instanceof QuickPunishGUI) {
            required = switch (rawSlot) {
                case 10 -> "guard.warn";
                case 11,12,13,14 -> "guard.mute";
                case 19,20,21,22 -> "guard.ban";
                case 23 -> "guard.kick";
                default -> "sus.admin";
            };
        } else if (holder instanceof PlayerControlGUI) {
            required = switch (rawSlot) {
                case 11 -> "guard.history";
                case 12 -> "guard.freeze";
                case 16 -> "guard.kick";
                case 17 -> "guard.alts";
                default -> "sus.admin";
            };
        } else if (holder instanceof MainSusGUI) {
            required = clickType == ClickType.RIGHT && rawSlot < 45 ? "guard.freeze" : "sus.admin";
        } else if (holder instanceof HistoryGUI) required = "guard.history";
        if (required != null && !player.hasPermission(required) && !player.hasPermission("guard.admin")) {
            player.sendMessage(config.getMessage("no-permission"));
            player.closeInventory();
            return;
        }

        if (holder instanceof MainSusGUI) {
            MainSusGUI gui = (MainSusGUI) holder;
            if (rawSlot < 45) {
                SusEntry entry = gui.getEntryAtSlot(rawSlot);
                if (entry != null) {
                    Player targetPlayer = Bukkit.getPlayer(entry.getPlayerUuid());

                    if (clickType == ClickType.RIGHT) {
                        // Quick Freeze / Unfreeze
                        if (plugin.isImmune(player, entry.getPlayerUuid())) {
                            player.sendMessage(config.getMessage("cannot-target-staff"));
                            return;
                        }
                        if (targetPlayer != null && targetPlayer.isOnline()) {
                            boolean isFrozen = plugin.getFreezeManager().isFrozen(targetPlayer.getUniqueId());
                            if (!isFrozen) {
                                plugin.getFreezeManager().freezePlayer(targetPlayer, player);
                                player.sendMessage(config.getMessage("freeze-success").replace("{player}", targetPlayer.getName()));
                            } else {
                                plugin.getFreezeManager().unfreezePlayer(targetPlayer, player);
                                player.sendMessage(config.getMessage("unfreeze-success").replace("{player}", targetPlayer.getName()));
                            }
                            new MainSusGUI(plugin, gui.getPage()).open(player);
                        } else {
                            player.sendMessage(config.getMessage("player-not-found"));
                        }
                    } else if (clickType == ClickType.SHIFT_LEFT) {
                        // Direct Open History
                        new HistoryGUI(plugin, entry, entry.getPlayerUuid(), entry.getPlayerName(), gui.getPage(), 1).open(player);
                    } else if (clickType == ClickType.SHIFT_RIGHT) {
                        // Direct Teleport To
                        if (targetPlayer != null && targetPlayer.isOnline()) {
                            player.teleport(targetPlayer.getLocation());
                            player.sendMessage(config.getMessage("teleport-me-to-target").replace("{player}", targetPlayer.getName()));
                        } else {
                            player.sendMessage(config.getMessage("player-not-found"));
                        }
                    } else {
                        // Normal Left-click -> Player Control GUI
                        new PlayerControlGUI(plugin, entry, gui.getPage()).open(player);
                    }
                }
            } else if (rawSlot == config.getConfig().getInt("main-gui.previous-page.slot", 45)) {
                if (gui.getPage() > 1) {
                    new MainSusGUI(plugin, gui.getPage() - 1).open(player);
                }
            } else if (rawSlot == config.getConfig().getInt("main-gui.next-page.slot", 53)) {
                if (gui.getPage() < gui.getMaxPages()) {
                    new MainSusGUI(plugin, gui.getPage() + 1).open(player);
                }
            }
        } else if (holder instanceof PlayerControlGUI) {
            PlayerControlGUI gui = (PlayerControlGUI) holder;
            SusEntry entry = gui.getTargetEntry();
            Player targetPlayer = Bukkit.getPlayer(entry.getPlayerUuid());

            if (rawSlot == 10) { // Add Reason
                if (plugin.isImmune(player, entry.getPlayerUuid())) {
                    player.sendMessage(config.getMessage("cannot-target-staff"));
                    return;
                }
                player.closeInventory();
                plugin.getChatInputListener().startListening(player, entry, gui.getReturnPage());
                String promptMsg = config.getRawMessage("prompt-add-reason").replace("{player}", entry.getPlayerName());
                player.sendMessage(config.getPrefix() + promptMsg);
            } else if (rawSlot == 11) { // History
                new HistoryGUI(plugin, entry, entry.getPlayerUuid(), entry.getPlayerName(), gui.getReturnPage(), 1).open(player);
            } else if (rawSlot == 12) { // Freeze
                if (plugin.isImmune(player, entry.getPlayerUuid())) {
                    player.sendMessage(config.getMessage("cannot-target-staff"));
                    return;
                }
                if (targetPlayer != null && targetPlayer.isOnline()) {
                    boolean isFrozen = plugin.getFreezeManager().isFrozen(targetPlayer.getUniqueId());
                    if (!isFrozen) {
                        plugin.getFreezeManager().freezePlayer(targetPlayer, player);
                        player.sendMessage(config.getMessage("freeze-success").replace("{player}", targetPlayer.getName()));
                    } else {
                        plugin.getFreezeManager().unfreezePlayer(targetPlayer, player);
                        player.sendMessage(config.getMessage("unfreeze-success").replace("{player}", targetPlayer.getName()));
                    }
                    new PlayerControlGUI(plugin, entry, gui.getReturnPage()).open(player);
                } else {
                    player.sendMessage(config.getMessage("player-not-found"));
                }
            } else if (rawSlot == 13) { // TP Here
                if (targetPlayer != null && targetPlayer.isOnline()) {
                    targetPlayer.teleport(player.getLocation());
                    player.sendMessage(config.getMessage("teleport-target-to-me").replace("{player}", targetPlayer.getName()));
                } else {
                    player.sendMessage(config.getMessage("player-not-found"));
                }
            } else if (rawSlot == 14) { // TP To
                if (targetPlayer != null && targetPlayer.isOnline()) {
                    player.teleport(targetPlayer.getLocation());
                    player.sendMessage(config.getMessage("teleport-me-to-target").replace("{player}", targetPlayer.getName()));
                } else {
                    player.sendMessage(config.getMessage("player-not-found"));
                }
            } else if (rawSlot == 15) { // Quick Punish GUI
                if (plugin.isImmune(player, entry.getPlayerUuid())) {
                    player.sendMessage(config.getMessage("cannot-target-staff"));
                    return;
                }
                new QuickPunishGUI(plugin, entry, gui.getReturnPage()).open(player);
            } else if (rawSlot == 16) { // Kick
                if (plugin.isImmune(player, entry.getPlayerUuid())) {
                    player.sendMessage(config.getMessage("cannot-target-staff"));
                    return;
                }
                if (targetPlayer != null && targetPlayer.isOnline()) {
                    String kickReason = config.getRawMessage("kick-reason-default");
                    PunishmentRecord rec = plugin.getPunishmentManager().createPunishment(PunishmentType.KICK,
                            targetPlayer.getUniqueId(), targetPlayer.getName(), targetPlayer.getAddress().getAddress().getHostAddress(),
                            player, kickReason, 0, false);
                    plugin.getPunishmentFormatter().broadcastPunishment(rec);
                    targetPlayer.kickPlayer(config.colorize("&c" + kickReason));
                    player.closeInventory();
                } else {
                    player.sendMessage(config.getMessage("player-not-found"));
                }
            } else if (rawSlot == 17) { // Check Alts / IP
                player.closeInventory();
                String ip = plugin.getAltManager().getLatestIp(entry.getPlayerUuid());
                Set<UUID> alts = plugin.getAltManager().getAltsByUuid(entry.getPlayerUuid());
                player.sendMessage(config.colorize("&8-----------------------------------------"));
                player.sendMessage(config.colorize("&c&lAlt Tekshiruvi: &e" + entry.getPlayerName()));
                player.sendMessage(config.colorize("&7IP Manzili: &f" + (ip != null ? ip : "Noma'lum")));
                player.sendMessage(config.colorize("&7Bog'liq akkauntlar (&e" + alts.size() + "&7):"));
                for (UUID u : alts) {
                    String aName = plugin.getAltManager().getNameByUuid(u);
                    PunishmentRecord aBan = plugin.getPunishmentManager().getActiveBan(u, ip);
                    String banStatus = (aBan != null) ? "&c[BAN]" : "&a[TOZA]";
                    player.sendMessage(config.colorize("  &8- &f" + aName + " " + banStatus));
                }
                player.sendMessage(config.colorize("&8-----------------------------------------"));
            } else if (rawSlot == 18) { // Remove Sus
                plugin.getSusManager().removeSus(entry.getPlayerUuid());
                player.sendMessage(config.getMessage("removed-from-sus").replace("{player}", entry.getPlayerName()));
                new MainSusGUI(plugin, gui.getReturnPage()).open(player);
            } else if (rawSlot == 22) { // Back
                new MainSusGUI(plugin, gui.getReturnPage()).open(player);
            }
        } else if (holder instanceof QuickPunishGUI) {
            QuickPunishGUI gui = (QuickPunishGUI) holder;
            SusEntry entry = gui.getTargetEntry();
            String ip = plugin.getAltManager().getLatestIp(entry.getPlayerUuid());
            Player targetPlayer = Bukkit.getPlayer(entry.getPlayerUuid());

            if (plugin.isImmune(player, entry.getPlayerUuid())) {
                player.sendMessage(config.getMessage("cannot-target-staff"));
                player.closeInventory();
                return;
            }

            switch (rawSlot) {
                case 10 -> { // Warn
                    PunishmentRecord rec = plugin.getPunishmentManager().createPunishment(PunishmentType.WARN,
                            entry.getPlayerUuid(), entry.getPlayerName(), ip, player, "Qoidabuzarlik", 0, false);
                    plugin.getPunishmentFormatter().broadcastPunishment(rec);
                    player.closeInventory();
                }
                case 11 -> { // Mute 15m
                    PunishmentRecord rec = plugin.getPunishmentManager().createPunishment(PunishmentType.MUTE,
                            entry.getPlayerUuid(), entry.getPlayerName(), ip, player, "Chat qoidabuzarligi", 15 * 60 * 1000L, false);
                    plugin.getPunishmentFormatter().broadcastPunishment(rec);
                    player.closeInventory();
                }
                case 12 -> { // Mute 1h
                    PunishmentRecord rec = plugin.getPunishmentManager().createPunishment(PunishmentType.MUTE,
                            entry.getPlayerUuid(), entry.getPlayerName(), ip, player, "Haqorat / Spambot", 60 * 60 * 1000L, false);
                    plugin.getPunishmentFormatter().broadcastPunishment(rec);
                    player.closeInventory();
                }
                case 13 -> { // Mute 1d
                    PunishmentRecord rec = plugin.getPunishmentManager().createPunishment(PunishmentType.MUTE,
                            entry.getPlayerUuid(), entry.getPlayerName(), ip, player, "Jiddiy haqorat / Reklama", 24 * 60 * 60 * 1000L, false);
                    plugin.getPunishmentFormatter().broadcastPunishment(rec);
                    player.closeInventory();
                }
                case 14 -> { // Mute Perm
                    PunishmentRecord rec = plugin.getPunishmentManager().createPunishment(PunishmentType.MUTE,
                            entry.getPlayerUuid(), entry.getPlayerName(), ip, player, "Doimiy qoidabuzarlik", -1, false);
                    plugin.getPunishmentFormatter().broadcastPunishment(rec);
                    player.closeInventory();
                }
                case 19 -> { // Ban 1d
                    PunishmentRecord rec = plugin.getPunishmentManager().createPunishment(PunishmentType.BAN,
                            entry.getPlayerUuid(), entry.getPlayerName(), ip, player, "Qoidabuzarlik", 24 * 60 * 60 * 1000L, false);
                    plugin.getPunishmentFormatter().broadcastPunishment(rec);
                    if (targetPlayer != null && targetPlayer.isOnline()) {
                        targetPlayer.kickPlayer(plugin.getPunishmentFormatter().buildBanScreen(rec));
                    }
                    player.closeInventory();
                }
                case 20 -> { // Ban 7d
                    PunishmentRecord rec = plugin.getPunishmentManager().createPunishment(PunishmentType.BAN,
                            entry.getPlayerUuid(), entry.getPlayerName(), ip, player, "Shubhali faoliyat / Bag", 7 * 24 * 60 * 60 * 1000L, false);
                    plugin.getPunishmentFormatter().broadcastPunishment(rec);
                    if (targetPlayer != null && targetPlayer.isOnline()) {
                        targetPlayer.kickPlayer(plugin.getPunishmentFormatter().buildBanScreen(rec));
                    }
                    player.closeInventory();
                }
                case 21 -> { // Ban 30d
                    PunishmentRecord rec = plugin.getPunishmentManager().createPunishment(PunishmentType.BAN,
                            entry.getPlayerUuid(), entry.getPlayerName(), ip, player, "Cheat / Noqonuniy dasturlar", 30L * 24 * 60 * 60 * 1000L, false);
                    plugin.getPunishmentFormatter().broadcastPunishment(rec);
                    if (targetPlayer != null && targetPlayer.isOnline()) {
                        targetPlayer.kickPlayer(plugin.getPunishmentFormatter().buildBanScreen(rec));
                    }
                    player.closeInventory();
                }
                case 22 -> { // Ban Perm
                    PunishmentRecord rec = plugin.getPunishmentManager().createPunishment(PunishmentType.BAN,
                            entry.getPlayerUuid(), entry.getPlayerName(), ip, player, "Cheat / Serverga zarar yetkazish", -1, false);
                    plugin.getPunishmentFormatter().broadcastPunishment(rec);
                    if (targetPlayer != null && targetPlayer.isOnline()) {
                        targetPlayer.kickPlayer(plugin.getPunishmentFormatter().buildBanScreen(rec));
                    }
                    player.closeInventory();
                }
                case 23 -> { // Kick
                    if (targetPlayer != null && targetPlayer.isOnline()) {
                        String kickReason = config.getRawMessage("kick-reason-default");
                        PunishmentRecord rec = plugin.getPunishmentManager().createPunishment(PunishmentType.KICK,
                                targetPlayer.getUniqueId(), targetPlayer.getName(), ip, player, kickReason, 0, false);
                        plugin.getPunishmentFormatter().broadcastPunishment(rec);
                        targetPlayer.kickPlayer(config.colorize("&c" + kickReason));
                        player.closeInventory();
                    } else {
                        player.sendMessage(config.getMessage("player-not-found"));
                    }
                }
                case 31 -> { // Back
                    new PlayerControlGUI(plugin, entry, gui.getReturnPage()).open(player);
                }
            }
        } else if (holder instanceof HistoryGUI) {
            HistoryGUI gui = (HistoryGUI) holder;
            if (rawSlot == 45 && gui.getPage() > 1) {
                new HistoryGUI(plugin, gui.getTargetEntry(), gui.getTargetUuid(), gui.getTargetName(), gui.getReturnPage(), gui.getPage() - 1).open(player);
            } else if (rawSlot == 49) {
                if (gui.getTargetEntry() != null) {
                    new PlayerControlGUI(plugin, gui.getTargetEntry(), gui.getReturnPage()).open(player);
                } else {
                    player.closeInventory();
                }
            } else if (rawSlot == 53 && gui.getPage() < gui.getMaxPages()) {
                new HistoryGUI(plugin, gui.getTargetEntry(), gui.getTargetUuid(), gui.getTargetName(), gui.getReturnPage(), gui.getPage() + 1).open(player);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof MainSusGUI || holder instanceof PlayerControlGUI ||
                holder instanceof QuickPunishGUI || holder instanceof HistoryGUI) {
            event.setCancelled(true);
        }
    }
}
