package uz.sentinel.guard.paper.config;

import uz.sentinel.guard.paper.GuardPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ConfigManager {
    private final GuardPlugin plugin;
    private FileConfiguration config;
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    // High-performance In-Memory Caches for Strings and Messages to avoid regex/re-allocations
    private final Map<String, String> colorCache = new ConcurrentHashMap<>(256);
    private final Map<String, String> messageCache = new ConcurrentHashMap<>(128);
    private String cachedPrefix = "";

    public ConfigManager(GuardPlugin plugin) {
        this.plugin = plugin;
        this.reload();
    }

    public void reload() {
        this.plugin.saveDefaultConfig();
        this.plugin.reloadConfig();
        this.config = this.plugin.getConfig();
        this.colorCache.clear();
        this.messageCache.clear();
        this.cachedPrefix = colorize(this.config.getString("prefix", "&8[&cSentinelGuard&8] "));
    }

    public FileConfiguration getConfig() {
        return this.config;
    }

    public String colorize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // Return instantly from cache if previously computed
        String cached = colorCache.get(text);
        if (cached != null) {
            return cached;
        }

        String result;
        if (text.contains("&#")) {
            Matcher matcher = HEX_PATTERN.matcher(text);
            StringBuffer buffer = new StringBuffer(text.length() + 32);
            while (matcher.find()) {
                String hex = matcher.group(1);
                StringBuilder replacement = new StringBuilder("§x");
                for (char c : hex.toCharArray()) {
                    replacement.append('§').append(c);
                }
                matcher.appendReplacement(buffer, replacement.toString());
            }
            matcher.appendTail(buffer);
            result = ChatColor.translateAlternateColorCodes('&', buffer.toString());
        } else if (text.indexOf('&') != -1) {
            result = ChatColor.translateAlternateColorCodes('&', text);
        } else {
            result = text;
        }

        if (colorCache.size() < 1000) {
            colorCache.put(text, result);
        }
        return result;
    }

    public List<String> colorize(List<String> list) {
        if (list == null || list.isEmpty()) {
            return new ArrayList<>(0);
        }
        return list.stream().map(this::colorize).collect(Collectors.toList());
    }

    public String getPrefix() {
        return this.cachedPrefix;
    }

    public String getMessage(String path) {
        String cached = messageCache.get(path);
        if (cached != null) {
            return cached;
        }
        String msg = this.config.getString("messages." + path, "");
        if (msg.isEmpty()) {
            return "";
        }
        String full = this.getPrefix() + this.colorize(msg);
        messageCache.put(path, full);
        return full;
    }

    public String getRawMessage(String path) {
        return this.colorize(this.config.getString("messages." + path, ""));
    }

    public List<String> getMessageList(String path) {
        return this.colorize(this.config.getStringList(path));
    }

    public ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material != null ? material : Material.STONE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null && !name.isEmpty()) {
                meta.setDisplayName(this.colorize(name));
            }
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(this.colorize(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack createPlayerHead(OfflinePlayer player, String name, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(player);
            if (name != null && !name.isEmpty()) {
                meta.setDisplayName(this.colorize(name));
            }
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(this.colorize(lore));
            }
            head.setItemMeta(meta);
        }
        return head;
    }
}
