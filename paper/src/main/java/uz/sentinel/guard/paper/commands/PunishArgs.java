package uz.sentinel.guard.paper.commands;

import uz.sentinel.guard.paper.punishment.TimeParser;

import java.util.ArrayList;
import java.util.List;

/** The words after the player name of /ban, /mute, /ipban: [-s] [duration] [reason...]. */
public record PunishArgs(boolean silent, long durationMs, String reason, String badDuration) {

    public static PunishArgs parse(String[] args, int from, String defaultReason) {
        boolean silent = false;
        List<String> rest = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("-s") || args[i].equalsIgnoreCase("--silent")) silent = true;
            else rest.add(args[i]);
        }
        long duration = -1;
        if (!rest.isEmpty()) {
            TimeParser.Token token = TimeParser.classify(rest.get(0));
            switch (token.kind()) {
                case DURATION -> { duration = token.millis(); rest.remove(0); }
                case INVALID -> { return new PunishArgs(silent, 0, defaultReason, rest.get(0)); }
                case REASON -> { }
            }
        }
        String reason = rest.isEmpty() ? defaultReason : String.join(" ", rest).trim();
        return new PunishArgs(silent, duration, reason, null);
    }

    public String badDurationMessage() {
        return "&cNoto'g'ri vaqt: &e" + badDuration + "&c. To'g'ri misollar: &f30m, 12h, 7d, 1d12h, 2w, 1mo, 1y, perm";
    }
}
