package uz.sentinel.guard.paper.sus;

public class SusLogEntry {
    private final String reason;
    private final String staff;
    private final long timestamp;

    public SusLogEntry(String reason, String staff) {
        this(reason, staff, System.currentTimeMillis());
    }

    public SusLogEntry(String reason, String staff, long timestamp) {
        this.reason = reason;
        this.staff = staff != null ? staff : "Console";
        this.timestamp = timestamp;
    }

    public String getReason() {
        return reason;
    }

    public String getStaff() {
        return staff;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
