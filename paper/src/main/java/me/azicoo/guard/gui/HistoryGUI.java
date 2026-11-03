package me.azicoo.guard.gui;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.punishment.PunishmentRecord;
import me.azicoo.guard.punishment.TimeParser;
import me.azicoo.guard.sus.SusEntry;
import me.azicoo.guard.sus.SusLogEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class HistoryGUI implements InventoryHolder {
    private final GuardPlugin plugin;
    private final Inventory inventory;
    private final SusEntry targetEntry;
    private final UUID targetUuid;
    private final String targetName;
    private final int returnPage;
    private final int page;
    private final int maxPages;

    public HistoryGUI(GuardPlugin plugin, SusEntry targetEntry, UUID targetUuid, String targetName, int returnPage, int page) {
        this.plugin = plugin;
        this.targetEntry = targetEntry;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.returnPage = returnPage;
        this.page = Math.max(1, page);

        ConfigManager config = plugin.getConfigManager();
        List<PunishmentRecord> punishments = plugin.getPunishmentManager().getHistory(targetUuid);
        List<SusLogEntry> susLogs = targetEntry != null ? targetEntry.getHistory() : List.of();

        List<ItemStack> allItems = new ArrayList<>();

        // Add Punishment items
        for (PunishmentRecord p : punishments) {
            Material mat = switch (p.getType()) {
                case BAN, IP_BAN -> Material.REDSTONE_BLOCK;
                case MUTE -> Material.BOOK;
                case WARN -> Material.PAPER;
                case KICK -> Material.LEATHER_BOOTS;
            };
            String status = p.isActive() ? "&c&lFAOL" : "&7Tugagan / Bekor qilingan";
            String duration = p.isPermanent() ? "Abadiy" : TimeParser.formatDuration(p.getDurationMs());
            List<String> lore = new ArrayList<>();
            lore.add("&7------------------------------");
            lore.add("&fJazo turi: " + p.getType().getColoredName());
            lore.add("&fHolati: " + status);
            lore.add("&fSabab: &e" + p.getReason());
            lore.add("&fModerator: &b" + p.getPunisherName());
            lore.add("&fMuddat: &a" + duration);
            lore.add("&fBerilgan vaqt: &7" + TimeParser.formatDate(p.getStartTime()) + " &8(" + TimeParser.formatTimeAgo(p.getStartTime()) + ")");
            if (p.getUnpunisherName() != null) {
                lore.add("&7Bekor qiluvchi: &f" + p.getUnpunisherName() + " (" + p.getUnpunishReason() + ")");
            }
            lore.add("&7Jazo ID: &8" + p.getId());
            lore.add("&7------------------------------");

            allItems.add(config.createItem(mat, "&e[" + p.getType().getDefaultName() + "] &c" + p.getId(), lore));
        }

        // Add Sus log items
        for (SusLogEntry s : susLogs) {
            List<String> lore = List.of(
                    "&7------------------------------",
                    "&fTuri: &6Shubha (Sus Flag)",
                    "&fSabab: &c" + s.getReason(),
                    "&fModerator: &b" + s.getStaff(),
                    "&fVaqti: &7" + TimeParser.formatDate(s.getTimestamp()) + " &8(" + TimeParser.formatTimeAgo(s.getTimestamp()) + ")",
                    "&7------------------------------"
            );
            allItems.add(config.createItem(Material.WRITABLE_BOOK, "&6[Sus Flag] &e" + s.getReason(), lore));
        }

        int totalItems = allItems.size();
        this.maxPages = Math.max(1, (int) Math.ceil((double) totalItems / 45.0));

        String title = config.colorize("&8&lTarix: &c" + targetName + " &7(" + this.page + "/" + this.maxPages + ")");
        this.inventory = Bukkit.createInventory(this, 54, title);

        Material fillerMat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack filler = config.createItem(fillerMat, " ", null);
        for (int i = 45; i < 54; i++) {
            this.inventory.setItem(i, filler);
        }

        int startIndex = (this.page - 1) * 45;
        int endIndex = Math.min(startIndex + 45, totalItems);

        if (totalItems == 0) {
            this.inventory.setItem(22, config.createItem(Material.BARRIER, "&cTarix mavjud emas", List.of("&7O'yinchida hech qanday jazo yoki shubha qayd etilmagan.")));
        } else {
            int slot = 0;
            for (int i = startIndex; i < endIndex; i++) {
                this.inventory.setItem(slot++, allItems.get(i));
            }
        }

        // Navigation
        if (this.page > 1) {
            this.inventory.setItem(45, config.createItem(Material.ARROW, "&a« Oldingi sahifa", List.of("&7Sahifa: &e" + (this.page - 1))));
        }

        // Back button
        this.inventory.setItem(49, config.createItem(Material.BARRIER, "&c« Orqaga", List.of("&7Ortga qaytish")));

        if (this.page < this.maxPages) {
            this.inventory.setItem(53, config.createItem(Material.ARROW, "&aKeyingi sahifa »", List.of("&7Sahifa: &e" + (this.page + 1))));
        }
    }

    public void open(Player player) {
        player.openInventory(this.inventory);
    }

    public SusEntry getTargetEntry() {
        return targetEntry;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public int getReturnPage() {
        return returnPage;
    }

    public int getPage() {
        return page;
    }

    public int getMaxPages() {
        return maxPages;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
