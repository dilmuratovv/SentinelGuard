package me.azicoo.guard.gui;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.punishment.PunishmentManager;
import me.azicoo.guard.punishment.PunishmentRecord;
import me.azicoo.guard.sus.SusEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class MainSusGUI implements InventoryHolder {
    private final GuardPlugin plugin;
    private final Inventory inventory;
    private final int page;
    private final int maxPages;
    private final Map<Integer, SusEntry> slotEntries = new HashMap<>();

    public MainSusGUI(GuardPlugin plugin, int page) {
        this.plugin = plugin;
        this.page = Math.max(1, page);

        List<SusEntry> entries = plugin.getSusManager().getAllActiveEntries();
        int total = entries.size();
        this.maxPages = Math.max(1, (int) Math.ceil((double) total / 45.0));

        ConfigManager config = plugin.getConfigManager();
        String title = config.getConfig().getString("main-gui.title", "&8&lSus Monitoring &7(Sahifa {page}/{max_pages})")
                .replace("{page}", String.valueOf(this.page))
                .replace("{max_pages}", String.valueOf(this.maxPages));
        title = config.colorize(title);

        this.inventory = Bukkit.createInventory(this, 54, title);

        Material fillerMat = Material.matchMaterial(config.getConfig().getString("main-gui.filler.material", "GRAY_STAINED_GLASS_PANE"));
        if (fillerMat == null) fillerMat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack filler = config.createItem(fillerMat, config.getConfig().getString("main-gui.filler.name", " "), null);

        for (int i = 45; i < 54; i++) {
            this.inventory.setItem(i, filler);
        }

        int startIndex = (this.page - 1) * 45;
        int endIndex = Math.min(startIndex + 45, total);

        if (total == 0) {
            Material emptyMat = Material.matchMaterial(config.getConfig().getString("main-gui.empty-item.material", "BARRIER"));
            if (emptyMat == null) emptyMat = Material.BARRIER;
            String emptyName = config.getConfig().getString("main-gui.empty-item.name", "&cShubhali o'yinchilar yo'q");
            List<String> emptyLore = config.getConfig().getStringList("main-gui.empty-item.lore");
            this.inventory.setItem(22, config.createItem(emptyMat, emptyName, emptyLore));
        } else {
            int slot = 0;
            for (int i = startIndex; i < endIndex; i++) {
                SusEntry entry = entries.get(i);
                this.slotEntries.put(slot, entry);
                this.inventory.setItem(slot, createPlayerHeadItem(entry));
                slot++;
            }
        }

        // Bottom Navigation Bar
        if (this.page > 1) {
            int prevSlot = config.getConfig().getInt("main-gui.previous-page.slot", 45);
            Material prevMat = Material.matchMaterial(config.getConfig().getString("main-gui.previous-page.material", "ARROW"));
            String prevName = config.getConfig().getString("main-gui.previous-page.name", "&a« Oldingi sahifa");
            List<String> prevLore = config.getConfig().getStringList("main-gui.previous-page.lore");
            List<String> formattedLore = new ArrayList<>();
            for (String l : prevLore) {
                formattedLore.add(l.replace("{prev_page}", String.valueOf(this.page - 1)));
            }
            this.inventory.setItem(prevSlot, config.createItem(prevMat != null ? prevMat : Material.ARROW, prevName, formattedLore));
        }

        // Slot 49: Info / Server Stats Button
        int infoSlot = config.getConfig().getInt("main-gui.info-button.slot", 49);
        Material infoMat = Material.matchMaterial(config.getConfig().getString("main-gui.info-button.material", "NETHER_STAR"));
        String infoName = config.getConfig().getString("main-gui.info-button.name", "&6&lServer Monitoring Statistikasi");
        List<String> infoLore = config.getConfig().getStringList("main-gui.info-button.lore");
        
        PunishmentManager pm = plugin.getPunishmentManager();
        List<String> formattedInfoLore = new ArrayList<>();
        if (infoLore.isEmpty()) {
            formattedInfoLore.add("&7------------------------------");
            formattedInfoLore.add("&fJami shubhali o'yinchilar: &e" + plugin.getSusManager().getTotalSusCount());
            formattedInfoLore.add("&fJami shubha flaglari: &c" + plugin.getSusManager().getTotalFlagsCount() + "x");
            formattedInfoLore.add("&fFaol banlar: &c" + pm.getGlobalActiveBans() + " &7(Jami: &e" + pm.getGlobalTotalBans() + "&7)");
            formattedInfoLore.add("&fFaol mutelar: &e" + pm.getGlobalActiveMutes() + " &7(Jami: &e" + pm.getGlobalTotalMutes() + "&7)");
            formattedInfoLore.add("&fBerilgan ogohlantirishlar: &d" + pm.getGlobalTotalWarns());
            formattedInfoLore.add("&fServerdan chiqarishlar: &6" + pm.getGlobalTotalKicks());
            formattedInfoLore.add("&7------------------------------");
            formattedInfoLore.add("&fJoriy sahifa: &a" + this.page + "/" + this.maxPages);
        } else {
            for (String l : infoLore) {
                formattedInfoLore.add(l.replace("{total_players}", String.valueOf(plugin.getSusManager().getTotalSusCount()))
                        .replace("{total_flags}", String.valueOf(plugin.getSusManager().getTotalFlagsCount()))
                        .replace("{active_bans}", String.valueOf(pm.getGlobalActiveBans()))
                        .replace("{total_bans}", String.valueOf(pm.getGlobalTotalBans()))
                        .replace("{active_mutes}", String.valueOf(pm.getGlobalActiveMutes()))
                        .replace("{total_mutes}", String.valueOf(pm.getGlobalTotalMutes()))
                        .replace("{total_warns}", String.valueOf(pm.getGlobalTotalWarns()))
                        .replace("{total_kicks}", String.valueOf(pm.getGlobalTotalKicks()))
                        .replace("{page}", String.valueOf(this.page))
                        .replace("{max_pages}", String.valueOf(this.maxPages)));
            }
        }
        this.inventory.setItem(infoSlot, config.createItem(infoMat != null ? infoMat : Material.NETHER_STAR, infoName, formattedInfoLore));

        // Slot 53: Next Page
        if (this.page < this.maxPages) {
            int nextSlot = config.getConfig().getInt("main-gui.next-page.slot", 53);
            Material nextMat = Material.matchMaterial(config.getConfig().getString("main-gui.next-page.material", "ARROW"));
            String nextName = config.getConfig().getString("main-gui.next-page.name", "&aKeyingi sahifa »");
            List<String> nextLore = config.getConfig().getStringList("main-gui.next-page.lore");
            List<String> formattedLore = new ArrayList<>();
            for (String l : nextLore) {
                formattedLore.add(l.replace("{next_page}", String.valueOf(this.page + 1)));
            }
            this.inventory.setItem(nextSlot, config.createItem(nextMat != null ? nextMat : Material.ARROW, nextName, formattedLore));
        }
    }

    private ItemStack createPlayerHeadItem(SusEntry entry) {
        ConfigManager config = plugin.getConfigManager();
        PunishmentManager pm = plugin.getPunishmentManager();
        OfflinePlayer op = Bukkit.getOfflinePlayer(entry.getPlayerUuid());
        boolean isOnline = op.isOnline();
        boolean isFrozen = plugin.getFreezeManager().isFrozen(entry.getPlayerUuid());

        String ip = plugin.getAltManager().getLatestIp(entry.getPlayerUuid());
        PunishmentRecord activeBan = pm.getActiveBan(entry.getPlayerUuid(), ip);
        PunishmentRecord activeMute = pm.getActiveMute(entry.getPlayerUuid());

        int totalBans = pm.getTotalBansCount(entry.getPlayerUuid());
        int totalMutes = pm.getTotalMutesCount(entry.getPlayerUuid());
        int totalWarns = pm.getTotalWarnsCount(entry.getPlayerUuid());
        int totalKicks = pm.getTotalKicksCount(entry.getPlayerUuid());

        String statusStr = isOnline ?
                config.getConfig().getString("main-gui.player-head.status-online", "&aOnlayn") :
                config.getConfig().getString("main-gui.player-head.status-offline", "&7Oflayn");
        String frozenStr = isFrozen ?
                config.getConfig().getString("main-gui.player-head.frozen-yes", "&cHa") :
                config.getConfig().getString("main-gui.player-head.frozen-no", "&aYo'q");

        String activeBanStr = (activeBan != null) ? "&cBAN OLGAN" : "&aToza";
        String activeMuteStr = (activeMute != null) ? "&eMUTE OLGAN" : "&aToza";

        String reasonFmt = config.getConfig().getString("main-gui.player-head.reason-format", " &7- &e{reason}: &c{count}x");
        List<String> reasonLines = new ArrayList<>();
        for (Map.Entry<String, Integer> r : entry.getReasons().entrySet()) {
            reasonLines.add(reasonFmt.replace("{reason}", r.getKey()).replace("{count}", String.valueOf(r.getValue())));
        }

        List<String> templateLore = config.getConfig().getStringList("main-gui.player-head.lore");
        List<String> finalLore = new ArrayList<>();
        for (String line : templateLore) {
            if (line.contains("{reasons}")) {
                finalLore.addAll(reasonLines);
            } else {
                finalLore.add(line.replace("{status}", statusStr)
                        .replace("{frozen}", frozenStr)
                        .replace("{total_flags}", String.valueOf(entry.getTotalFlags()))
                        .replace("{total_bans}", String.valueOf(totalBans))
                        .replace("{total_mutes}", String.valueOf(totalMutes))
                        .replace("{total_warns}", String.valueOf(totalWarns))
                        .replace("{total_kicks}", String.valueOf(totalKicks))
                        .replace("{active_ban}", activeBanStr)
                        .replace("{active_mute}", activeMuteStr));
            }
        }

        String headName = config.getConfig().getString("main-gui.player-head.name", "&c&l{player}")
                .replace("{player}", entry.getPlayerName());
        return config.createPlayerHead(op, headName, finalLore);
    }

    public void open(Player player) {
        player.openInventory(this.inventory);
    }

    public SusEntry getEntryAtSlot(int slot) {
        return slotEntries.get(slot);
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
