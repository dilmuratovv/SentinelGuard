package uz.sentinel.guard.paper.punishment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeParser {
    private static final Pattern TIME_PATTERN = Pattern.compile("(?i)^(\\d+)\\s*(s|sec|seconds?|m|min|minutes?|h|hour|hours?|d|day|days?|w|week|weeks?|mo|month|months?|y|year|years?)$");
    private static final Pattern COMBINED_PATTERN = Pattern.compile("(?i)(\\d+)(mo|s|m|h|d|w|y)");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    /**
     * Checks whether a single token or string represents a time duration.
     */
    public static boolean isDuration(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        String clean = input.trim().toLowerCase();
        if (clean.equals("perm") || clean.equals("permanent") || clean.equals("abadiy")) {
            return true;
        }
        if (TIME_PATTERN.matcher(clean).matches()) {
            return true;
        }
        Matcher matcher = COMBINED_PATTERN.matcher(clean);
        int matchedLength = 0;
        while (matcher.find()) {
            matchedLength += matcher.group().length();
        }
        return matchedLength == clean.length() && matchedLength > 0;
    }

    private static final Pattern ATTEMPT = Pattern.compile("(?i)^\\d+([.,]\\d+)?[a-z]*(\\d+[a-z]*)*$");
    private static final long MAX_MS = 100L * 365L * 86400L * 1000L;

    /** What the first argument after a player name is: a duration, an invalid duration (typo), or the reason. */
    public enum Kind { DURATION, INVALID, REASON }

    public record Token(Kind kind, long millis) {}

    /**
     * Classifies a token. A token that starts with digits is a duration attempt: when it is not exactly a valid
     * duration ("1dd", "7", "0d", "1.5h", overflow) it is INVALID, so a typo never silently becomes a permanent
     * punishment with the typo in its reason.
     */
    public static Token classify(String input) {
        if (input == null || input.isEmpty()) return new Token(Kind.REASON, 0);
        String clean = input.trim().toLowerCase();
        if (clean.equals("perm") || clean.equals("permanent") || clean.equals("abadiy") || clean.equals("forever")) {
            return new Token(Kind.DURATION, -1);
        }
        if (!ATTEMPT.matcher(clean).matches()) return new Token(Kind.REASON, 0);
        if (!isDuration(clean)) return new Token(Kind.INVALID, 0);
        long ms = parseDuration(clean);
        if (ms <= 0 || ms > MAX_MS) return new Token(Kind.INVALID, 0);
        return new Token(Kind.DURATION, ms);
    }

    /**
     * Parses duration string into milliseconds.
     * Returns -1 if permanent or 0 if invalid.
     */
    public static long parseDuration(String input) {
        if (input == null || input.isEmpty()) {
            return 0;
        }
        String clean = input.trim().toLowerCase();
        if (clean.equals("perm") || clean.equals("permanent") || clean.equals("abadiy") || clean.equals("forever")) {
            return -1;
        }

        Matcher singleMatcher = TIME_PATTERN.matcher(clean);
        if (singleMatcher.matches()) {
            try {
                long amount = Long.parseLong(singleMatcher.group(1));
                String unit = singleMatcher.group(2).toLowerCase();
                return unitToMillis(amount, unit);
            } catch (NumberFormatException | ArithmeticException e) {
                return 0;
            }
        }

        Matcher matcher = COMBINED_PATTERN.matcher(clean);
        long totalMillis = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            try {
                long amount = Long.parseLong(matcher.group(1));
                String unit = matcher.group(2).toLowerCase();
                totalMillis = Math.addExact(totalMillis, unitToMillis(amount, unit));
            } catch (NumberFormatException | ArithmeticException e) {
                return 0;
            }
        }

        return found ? totalMillis : 0;
    }

    private static long unitToMillis(long amount, String unit) {
        if (unit.startsWith("s")) {
            return Math.multiplyExact(amount, 1000L);
        } else if (unit.startsWith("m") && !unit.startsWith("mo")) {
            return Math.multiplyExact(amount, 60_000L);
        } else if (unit.startsWith("h")) {
            return Math.multiplyExact(amount, 3_600_000L);
        } else if (unit.startsWith("d")) {
            return Math.multiplyExact(amount, 86_400_000L);
        } else if (unit.startsWith("w")) {
            return Math.multiplyExact(amount, 7L * 86_400_000L);
        } else if (unit.startsWith("mo")) {
            return Math.multiplyExact(amount, 30L * 86_400_000L);
        } else if (unit.startsWith("y")) {
            return Math.multiplyExact(amount, 365L * 86_400_000L);
        }
        return 0;
    }

    /**
     * Formats duration milliseconds into human-readable string.
     */
    public static String formatDuration(long millis) {
        if (millis <= 0) {
            return "Abadiy";
        }
        long seconds = millis / 1000L;
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(" kun ");
        }
        if (hours > 0) {
            sb.append(hours).append(" soat ");
        }
        if (minutes > 0) {
            sb.append(minutes).append(" daqiqa ");
        }
        if (seconds > 0 && days == 0 && hours == 0) {
            sb.append(seconds).append(" soniya");
        }

        String res = sb.toString().trim();
        return res.isEmpty() ? "1 soniya" : res;
    }

    /**
     * Formats remaining time relative to current timestamp.
     */
    public static String formatRemaining(long expireTimestamp) {
        if (expireTimestamp <= 0) {
            return "Abadiy";
        }
        long diff = expireTimestamp - System.currentTimeMillis();
        if (diff <= 0) {
            return "Tugagan";
        }
        return formatDuration(diff);
    }

    /**
     * Formats timestamp into date string.
     */
    public static String formatDate(long timestamp) {
        if (timestamp <= 0) {
            return "Hech qachon";
        }
        return DATE_FORMAT.format(new Date(timestamp));
    }

    /**
     * Formats elapsed time since timestamp (e.g. 5 daqiqa oldin).
     */
    public static String formatTimeAgo(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        if (diff < 0) diff = 0;
        long seconds = diff / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long days = hours / 24L;

        if (seconds < 60) return "Hozirgina";
        if (minutes < 60) return minutes + " daqiqa oldin";
        if (hours < 24) return hours + " soat oldin";
        if (days < 30) return days + " kun oldin";
        return (days / 30) + " oy oldin";
    }
}
