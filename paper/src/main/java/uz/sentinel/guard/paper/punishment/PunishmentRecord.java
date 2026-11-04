package uz.sentinel.guard.paper.punishment;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PunishmentRecord {
    private final String id;
    private final PunishmentType type;
    private final UUID targetUuid;
    private String targetName;
    private String targetIp;
    private final UUID punisherUuid;
    private final String punisherName;
    private final String reason;
    private final long durationMs;
    private final long startTime;
    private final long endTime; // -1 for permanent
    private boolean active;
    private final boolean silent;
    private String unpunisherName;
    private String unpunishReason;
    private long unpunishTime;
    private UUID networkId;

    public PunishmentRecord(String id, PunishmentType type, UUID targetUuid, String targetName, String targetIp,
                            UUID punisherUuid, String punisherName, String reason, long durationMs,
                            long startTime, long endTime, boolean active, boolean silent) {
        this.id = id;
        this.type = type;
        this.targetUuid = targetUuid;
        this.targetName = targetName != null ? targetName : "Unknown";
        this.targetIp = targetIp;
        this.punisherUuid = punisherUuid;
        this.punisherName = punisherName != null ? punisherName : "Console";
        this.reason = (reason != null && !reason.trim().isEmpty()) ? reason.trim() : "Qoidabuzarlik";
        this.durationMs = durationMs;
        this.startTime = startTime;
        this.endTime = endTime;
        this.active = active;
        this.silent = silent;
    }

    public boolean isPermanent() {
        return endTime == -1 || durationMs == -1;
    }

    public boolean isExpired() {
        if (!active) {
            return true;
        }
        if (type == PunishmentType.KICK) {
            return true;
        }
        if (isPermanent()) {
            return false;
        }
        return System.currentTimeMillis() >= endTime;
    }

    public void unpunish(String unpunisher, String reason) {
        this.active = false;
        this.unpunisherName = unpunisher;
        this.unpunishReason = (reason != null && !reason.trim().isEmpty()) ? reason.trim() : "Bekor qilindi";
        this.unpunishTime = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public PunishmentType getType() {
        return type;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public String getTargetIp() {
        return targetIp;
    }

    public void setTargetIp(String targetIp) {
        this.targetIp = targetIp;
    }

    public UUID getPunisherUuid() {
        return punisherUuid;
    }

    public String getPunisherName() {
        return punisherName;
    }

    public String getReason() {
        return reason;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public boolean isActive() {
        return active && !isExpired();
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isSilent() {
        return silent;
    }

    public String getUnpunisherName() {
        return unpunisherName;
    }

    public void setUnpunisherName(String unpunisherName) {
        this.unpunisherName = unpunisherName;
    }

    public String getUnpunishReason() {
        return unpunishReason;
    }

    public void setUnpunishReason(String unpunishReason) {
        this.unpunishReason = unpunishReason;
    }

    public long getUnpunishTime() {
        return unpunishTime;
    }

    public void setUnpunishTime(long unpunishTime) {
        this.unpunishTime = unpunishTime;
    }

    /** Id of the authoritative SQL row, or null for local (YAML) records. */
    public UUID getNetworkId() {
        return networkId;
    }

    /** Maps a network row; returns null for types this adapter does not display (freeze state). */
    public static PunishmentRecord fromNetwork(uz.sentinel.guard.network.NetworkPunishment p) {
        PunishmentType type = switch (p.type()) {
            case BAN -> PunishmentType.BAN;
            case IP_BAN -> PunishmentType.IP_BAN;
            case MUTE -> PunishmentType.MUTE;
            case WARN -> PunishmentType.WARN;
            case KICK -> PunishmentType.KICK;
            case FREEZE -> null;
        };
        if (type == null) return null;
        long start = p.createdAt().toEpochMilli();
        long end = p.expiresAt() == null ? -1 : p.expiresAt().toEpochMilli();
        long duration = type == PunishmentType.KICK || type == PunishmentType.WARN ? 0 : (end == -1 ? -1 : end - start);
        PunishmentRecord record = new PunishmentRecord(p.displayId(), type, p.player(), p.playerName(), p.ip(),
                p.actorId(), p.actor(), p.reason(), duration, start, end, !p.revoked(), p.silent());
        record.networkId = p.id();
        if (p.revoked()) {
            record.unpunisherName = p.revocation().actor();
            record.unpunishReason = p.revocation().reason();
            record.unpunishTime = p.revocation().at().toEpochMilli();
        }
        return record;
    }

    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("type", type.name());
        map.put("target-uuid", targetUuid != null ? targetUuid.toString() : null);
        map.put("target-name", targetName);
        map.put("target-ip", targetIp);
        map.put("punisher-uuid", punisherUuid != null ? punisherUuid.toString() : null);
        map.put("punisher-name", punisherName);
        map.put("reason", reason);
        map.put("duration-ms", durationMs);
        map.put("start-time", startTime);
        map.put("end-time", endTime);
        map.put("active", active);
        map.put("silent", silent);
        if (unpunisherName != null) {
            map.put("unpunisher-name", unpunisherName);
            map.put("unpunish-reason", unpunishReason);
            map.put("unpunish-time", unpunishTime);
        }
        return map;
    }

    public static PunishmentRecord deserialize(Map<String, Object> map) {
        String id = (String) map.get("id");
        PunishmentType type = PunishmentType.valueOf((String) map.get("type"));
        String targetUuidStr = (String) map.get("target-uuid");
        UUID targetUuid = targetUuidStr != null ? UUID.fromString(targetUuidStr) : null;
        String targetName = (String) map.get("target-name");
        String targetIp = (String) map.get("target-ip");
        String punisherUuidStr = (String) map.get("punisher-uuid");
        UUID punisherUuid = punisherUuidStr != null ? UUID.fromString(punisherUuidStr) : null;
        String punisherName = (String) map.get("punisher-name");
        String reason = (String) map.get("reason");
        long durationMs = ((Number) map.getOrDefault("duration-ms", 0L)).longValue();
        long startTime = ((Number) map.getOrDefault("start-time", 0L)).longValue();
        long endTime = ((Number) map.getOrDefault("end-time", 0L)).longValue();
        boolean active = (Boolean) map.getOrDefault("active", true);
        boolean silent = (Boolean) map.getOrDefault("silent", false);

        PunishmentRecord record = new PunishmentRecord(id, type, targetUuid, targetName, targetIp,
                punisherUuid, punisherName, reason, durationMs, startTime, endTime, active, silent);

        if (map.containsKey("unpunisher-name")) {
            record.setUnpunisherName((String) map.get("unpunisher-name"));
            record.setUnpunishReason((String) map.get("unpunish-reason"));
            record.setUnpunishTime(((Number) map.getOrDefault("unpunish-time", 0L)).longValue());
        }
        return record;
    }
}
