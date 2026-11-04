package uz.sentinel.guard.paper.gui;

import uz.sentinel.guard.paper.GuardPlugin;
import uz.sentinel.guard.paper.config.ConfigManager;
import uz.sentinel.guard.paper.punishment.PunishmentManager;
import uz.sentinel.guard.paper.punishment.PunishmentRecord;
import uz.sentinel.guard.paper.sus.SusEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class PlayerControlGUI implements InventoryHolder {
    private final GuardPlugin plugin;
    private final Inventory inventory;
    private final SusEntry targetEntry;
    private final int returnPage;

    public PlayerControlGUI(GuardPlugin plugin, SusEntry targetEntry, int returnPage) {
        this.plugin = plugin;
        this.targetEntry = targetEntry;
        this.returnPage = returnPage;

        ConfigManager config = plugin.getConfigManager();
        PunishmentManager pm = plugin.getPunishmentManager();

        String title = config.getConfig().getString("control-gui.title", "&8&lBoshqaruv: &c{player}")
                .replace("{player}", targetEntry.getPlayerName());
        title = config.colorize(title);

        this.inventory = Bukkit.createInventory(this, 27, title);

        Material fillerMat = Material.matchMaterial(config.getConfig().getString("control-gui.filler.material", "BLACK_STAINED_GLASS_PANE"));
        if (fillerMat == null) fillerMat = Material.BLACK_STAINED_GLASS_PANE;
        ItemStack filler = config.createItem(fillerMat, config.getConfig().getString("control-gui.filler.name", " "), null);

        for (int i = 0; i < 27; i++) {
            this.inventory.setItem(i, filler);
        }

        // Slot 4: Player Head with full details and stats
        int headSlot = config.getConfig().getInt("control-gui.target-head-slot", 4);
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetEntry.getPlayerUuid());
        boolean isOnline = target.isOnline();
        boolean isFrozen = plugin.getFreezeManager().isFrozen(targetEntry.getPlayerUuid());

        String ip = plugin.getAltManager().getLatestIp(targetEntry.getPlayerUuid());
        PunishmentRecord activeBan = pm.getActiveBan(targetEntry.getPlayerUuid(), ip);
        PunishmentRecord activeMute = pm.getActiveMute(targetEntry.getPlayerUuid());
        Set<UUID> alts = plugin.getAltManager().getAltsByUuid(targetEntry.getPlayerUuid());

        int totalBans = pm.getTotalBansCount(targetEntry.getPlayerUuid());
        int totalMutes = pm.getTotalMutesCount(targetEntry.getPlayerUuid());
        int totalWarns = pm.getTotalWarnsCount(targetEntry.getPlayerUuid());
        int totalKicks = pm.getTotalKicksCount(targetEntry.getPlayerUuid());

        List<String> headLore = new ArrayList<>();
        headLore.add("&7------------------------------");
        headLore.add("&fHolati: " + (isOnline ? "&aOnlayn" : "&7Oflayn"));
        headLore.add("&fMuzlatilgan: " + (isFrozen ? "&cHa" : "&aYo'q"));
        headLore.add("&fBan holati: " + (activeBan != null ? "&cBAN OLGAN" : "&aToza") + " &7(Jami: &e" + totalBans + "&7x)");
        headLore.add("&fMute holati: " + (activeMute != null ? "&eMUTE OLGAN" : "&aToza") + " &7(Jami: &e" + totalMutes + "&7x)");
        headLore.add("&fOgohlantirishlar: &d" + totalWarns + " ta &7| &fKicklar: &6" + totalKicks + " ta");
        headLore.add("&fJami flaglar: &c" + targetEntry.getTotalFlags() + "x");
        headLore.add("&fBog'liq altlar: &e" + alts.size() + " ta");
        headLore.add("&7------------------------------");

        ItemStack headItem = config.createPlayerHead(target, "&c&l" + targetEntry.getPlayerName(), headLore);
        this.inventory.setItem(headSlot, headItem);

        // Buttons
        setupButton(10, "control-gui.buttons.add-reason", "WRITABLE_BOOK", "&e&l1. Add Sus Reason", null);
        setupButton(11, "control-gui.buttons.history", "BOOK", "&b&l2. Log / History", null);
        
        List<String> freezeReplacements = new ArrayList<>();
        freezeReplacements.add(isFrozen ? "&cMUZLATILGAN" : "&aMuzlatilmagan");
        setupButton(12, "control-gui.buttons.freeze", "ICE", "&3&l3. Freeze / Unfreeze", freezeReplacements);
        
        setupButton(13, "control-gui.buttons.tp-here", "ENDER_PEARL", "&a&l4. TP (Player to Me)", null);
        setupButton(14, "control-gui.buttons.tp-to", "COMPASS", "&6&l5. TP (Me to Player)", null);
        setupButton(15, "control-gui.buttons.punish", "NETHERITE_SWORD", "&c&l6. Quick Punish (Ban/Mute/Warn)", null);
        setupButton(16, "control-gui.buttons.kick", "REDSTONE", "&e&l7. Kick", null);
        setupButton(17, "control-gui.buttons.alts", "SPYGLASS", "&d&l8. Check Alts / IP", null);
        setupButton(18, "control-gui.buttons.remove-sus", "ANVIL", "&4&l9. Remove from Sus", null);

        int backSlot = config.getConfig().getInt("control-gui.back-button.slot", 22);
        Material backMat = Material.matchMaterial(config.getConfig().getString("control-gui.back-button.material", "BARRIER"));
        String backName = config.getConfig().getString("control-gui.back-button.name", "&c« Orqaga");
        List<String> backLore = config.getConfig().getStringList("control-gui.back-button.lore");
        this.inventory.setItem(backSlot, config.createItem(backMat != null ? backMat : Material.BARRIER, backName, backLore));
    }

    private static final String[] BUTTONS = {"add-reason", "history", "freeze", "tp-here", "tp-to", "punish", "kick", "alts", "remove-sus"};

    /**
     * Maps a clicked slot to the built-in action slot (10-18, back = 22) so GUIListener behaves
     * correctly when button slots are customised in config. Returns -1 for non-button slots.
     */
    public static int actionSlot(ConfigManager config, int rawSlot) {
        for (int i = 0; i < BUTTONS.length; i++) {
            if (config.getConfig().getInt("control-gui.buttons." + BUTTONS[i] + ".slot", 10 + i) == rawSlot) return 10 + i;
        }
        if (config.getConfig().getInt("control-gui.back-button.slot", 22) == rawSlot) return 22;
        return -1;
    }

    private void setupButton(int defaultSlot, String configPath, String defaultMatStr, String defaultName, List<String> placeholders) {
        ConfigManager config = this.plugin.getConfigManager();
        int slot = config.getConfig().getInt(configPath + ".slot", defaultSlot);
        Material mat = Material.matchMaterial(config.getConfig().getString(configPath + ".material", defaultMatStr));
        if (mat == null) mat = Material.STONE;
        String name = config.getConfig().getString(configPath + ".name", defaultName);
        List<String> rawLore = config.getConfig().getStringList(configPath + ".lore");
        List<String> finalLore = new ArrayList<>();
        for (String line : rawLore) {
            String formatted = line;
            if (placeholders != null && !placeholders.isEmpty()) {
                formatted = formatted.replace("{frozen_status}", placeholders.get(0));
            }
            finalLore.add(config.colorize(formatted));
        }
        this.inventory.setItem(slot, config.createItem(mat, name, finalLore));
    }

    public void open(Player player) {
        player.openInventory(this.inventory);
    }

    public SusEntry getTargetEntry() {
        return targetEntry;
    }

    public int getReturnPage() {
        return returnPage;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
