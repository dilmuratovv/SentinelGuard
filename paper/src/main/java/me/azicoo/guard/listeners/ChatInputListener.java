package me.azicoo.guard.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.azicoo.guard.GuardPlugin;
import me.azicoo.guard.config.ConfigManager;
import me.azicoo.guard.gui.PlayerControlGUI;
import me.azicoo.guard.sus.SusEntry;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChatInputListener implements Listener {
    private final GuardPlugin plugin;
    private final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();

    public ChatInputListener(GuardPlugin plugin) {
        this.plugin = plugin;
    }

    public void startListening(Player admin, SusEntry targetEntry, int returnPage) {
        this.pendingInputs.put(admin.getUniqueId(), new PendingInput(targetEntry, returnPage));
    }

    public boolean isListening(UUID adminUuid) {
        return this.pendingInputs.containsKey(adminUuid);
    }

    public void removeListening(UUID adminUuid) {
        this.pendingInputs.remove(adminUuid);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPaperChat(AsyncChatEvent event) {
        Player admin = event.getPlayer();
        PendingInput input = this.pendingInputs.remove(admin.getUniqueId());
        if (input == null) return;
        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        this.processInput(admin, input, message);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        Player admin = event.getPlayer();
        PendingInput input = this.pendingInputs.remove(admin.getUniqueId());
        if (input == null) return;
        event.setCancelled(true);
        String message = event.getMessage().trim();
        this.processInput(admin, input, message);
    }

    private void processInput(Player admin, PendingInput input, String message) {
        ConfigManager config = this.plugin.getConfigManager();
        if (message.equalsIgnoreCase("cancel")) {
            admin.sendMessage(config.getPrefix() + config.getRawMessage("add-reason-cancelled"));
        } else {
            SusEntry entry = input.getTargetEntry();
            int count = this.plugin.getSusManager().addSus(entry.getPlayerUuid(), entry.getPlayerName(), message, admin.getName());
            if (count == -1) {
                admin.sendMessage(config.getMessage("cannot-sus-admin"));
            } else {
                String successMsg = config.getRawMessage("add-reason-success").replace("{reason}", message);
                admin.sendMessage(config.getPrefix() + successMsg);
            }
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> new PlayerControlGUI(this.plugin, input.getTargetEntry(), input.getReturnPage()).open(admin));
    }

    public static class PendingInput {
        private final SusEntry targetEntry;
        private final int returnPage;

        public PendingInput(SusEntry targetEntry, int returnPage) {
            this.targetEntry = targetEntry;
            this.returnPage = returnPage;
        }

        public SusEntry getTargetEntry() {
            return this.targetEntry;
        }

        public int getReturnPage() {
            return this.returnPage;
        }
    }
}
