package me.azicoo.guard.punishment;

public enum PunishmentType {
    BAN("Ban", "&cBan", true),
    IP_BAN("IP-Ban", "&4IP-Ban", true),
    MUTE("Mute", "&eMute", true),
    KICK("Kick", "&6Kick", false),
    WARN("Warn", "&dOgohlantirish", false);

    private final String defaultName;
    private final String coloredName;
    private final boolean durational;

    PunishmentType(String defaultName, String coloredName, boolean durational) {
        this.defaultName = defaultName;
        this.coloredName = coloredName;
        this.durational = durational;
    }

    public String getDefaultName() {
        return defaultName;
    }

    public String getColoredName() {
        return coloredName;
    }

    public boolean isDurational() {
        return durational;
    }
}
