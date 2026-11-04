package uz.sentinel.guard.paper.sus;

import java.util.*;

public class SusEntry {
    private final UUID playerUuid;
    private String playerName;
    private boolean active;
    private final Map<String, Integer> reasons;
    private final List<SusLogEntry> history;
    private final long firstAddedTime;
    private long lastAddedTime;

    public SusEntry(UUID playerUuid, String playerName) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.active = true;
        this.reasons = new LinkedHashMap<>();
        this.history = new ArrayList<>();
        this.firstAddedTime = System.currentTimeMillis();
        this.lastAddedTime = System.currentTimeMillis();
    }

    public SusEntry(UUID playerUuid, String playerName, boolean active, Map<String, Integer> reasons,
                    List<SusLogEntry> history, long firstAddedTime, long lastAddedTime) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.active = active;
        this.reasons = (reasons != null) ? new LinkedHashMap<>(reasons) : new LinkedHashMap<>();
        this.history = (history != null) ? new ArrayList<>(history) : new ArrayList<>();
        this.firstAddedTime = firstAddedTime;
        this.lastAddedTime = lastAddedTime;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Map<String, Integer> getReasons() {
        return Collections.unmodifiableMap(reasons);
    }

    public List<SusLogEntry> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public long getFirstAddedTime() {
        return firstAddedTime;
    }

    public long getLastAddedTime() {
        return lastAddedTime;
    }

    public int addReason(String reason, String staff) {
        this.active = true;
        String cleanReason = reason.trim().toLowerCase();
        int count = this.reasons.getOrDefault(cleanReason, 0) + 1;
        this.reasons.put(cleanReason, count);
        this.history.add(new SusLogEntry(cleanReason, staff));
        this.lastAddedTime = System.currentTimeMillis();
        return count;
    }

    public int getTotalFlags() {
        int total = 0;
        for (int count : this.reasons.values()) {
            total += count;
        }
        return total;
    }

    public SusLogEntry getPreviousLogEntry() {
        if (this.history.size() < 2) {
            return null;
        }
        return this.history.get(this.history.size() - 2);
    }
}
