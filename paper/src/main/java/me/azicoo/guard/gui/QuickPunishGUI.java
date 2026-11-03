package me.azicoo.guard.gui;

import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.punishment.PunishmentType;
import me.azicoo.guard.sus.SusEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class QuickPunishGUI implements InventoryHolder {
    private final GuardPlugin plugin;
    private final Inventory inventory;
    private final SusEntry targetEntry;
    private final int returnPage;

    public QuickPunishGUI(GuardPlugin plugin, SusEntry targetEntry, int returnPage) {
        this.plugin = plugin;
        this.targetEntry = targetEntry;
        this.returnPage = returnPage;

        ConfigManager config = plugin.getConfigManager();
        String title = config.colorize("&8&lTezkor Jazo: &c" + targetEntry.getPlayerName());
        this.inventory = Bukkit.createInventory(this, 36, title);

        Material fillerMat = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack filler = config.createItem(fillerMat, " ", null);
        for (int i = 0; i < 36; i++) {
            this.inventory.setItem(i, filler);
        }

        // Row 1: Warn & Mutes
        this.inventory.setItem(10, config.createItem(Material.YELLOW_DYE, "&e&lWarn (Ogohlantirish)",
                List.of("&7O'yinchiga rasmiy ogohlantirish berish.", "&7Avto-esklyatsiya tizimi ishlaydi.", "", "&a» Bosib ogohlantiring")));

        this.inventory.setItem(11, config.createItem(Material.ORANGE_DYE, "&6&lMute: 15 Daqiqa",
                List.of("&7Muddat: &e15m", "&7Sabab: &fChat qoidabuzarligi", "", "&a» Bosib mute qiling")));

        this.inventory.setItem(12, config.createItem(Material.ORANGE_DYE, "&6&lMute: 1 Soat",
                List.of("&7Muddat: &e1h", "&7Sabab: &fHaqorat / Spambot", "", "&a» Bosib mute qiling")));

        this.inventory.setItem(13, config.createItem(Material.RED_DYE, "&c&lMute: 1 Kun",
                List.of("&7Muddat: &e1d", "&7Sabab: &fJiddiy haqorat / Reklama", "", "&a» Bosib mute qiling")));

        this.inventory.setItem(14, config.createItem(Material.PURPLE_DYE, "&4&lMute: Abadiy (Permanent)",
                List.of("&7Muddat: &4Abadiy", "&7Sabab: &fDoimiy qoidabuzarlik", "", "&c» Bosib abadiy mute qiling")));

        // Row 2: Bans & Kick
        this.inventory.setItem(19, config.createItem(Material.IRON_SWORD, "&e&lBan: 1 Kun",
                List.of("&7Muddat: &e1d", "&7Sabab: &fQoidabuzarlik", "", "&a» Bosib ban qiling")));

        this.inventory.setItem(20, config.createItem(Material.GOLDEN_SWORD, "&6&lBan: 7 Kun",
                List.of("&7Muddat: &e7d", "&7Sabab: &fShubhali faoliyat / Bagdan foydalanish", "", "&a» Bosib ban qiling")));

        this.inventory.setItem(21, config.createItem(Material.DIAMOND_SWORD, "&c&lBan: 30 Kun",
                List.of("&7Muddat: &e30d", "&7Sabab: &fCheat / Noqonuniy dasturlar", "", "&a» Bosib ban qiling")));

        this.inventory.setItem(22, config.createItem(Material.NETHERITE_SWORD, "&4&lBan: Abadiy (Permanent)",
                List.of("&7Muddat: &4Abadiy", "&7Sabab: &fCheat / Serverga zarar yetkazish", "", "&c» Bosib abadiy ban qiling")));

        this.inventory.setItem(23, config.createItem(Material.TNT, "&c&lKick (Chiqarib yuborish)",
                List.of("&7O'yinchini hoziroq serverdan chiqarish.", "", "&c» Bosib kick qiling")));

        // Back Button (Slot 31)
        this.inventory.setItem(31, config.createItem(Material.BARRIER, "&c« Orqaga", List.of("&7Boshqaruv menyusiga qaytish")));
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
