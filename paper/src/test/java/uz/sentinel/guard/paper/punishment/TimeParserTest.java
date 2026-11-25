package uz.sentinel.guard.paper.punishment;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import uz.sentinel.guard.paper.commands.PunishArgs;

class TimeParserTest {
    private static TimeParser.Kind kind(String s) { return TimeParser.classify(s).kind(); }

    @Test
    void validDurations() {
        assertEquals(86_400_000L, TimeParser.classify("1d").millis());
        assertEquals(86_400_000L + 12 * 3_600_000L, TimeParser.classify("1d12h").millis());
        assertEquals(30L * 86_400_000L, TimeParser.classify("1mo").millis());
        assertEquals(30L * 86_400_000L + 86_400_000L, TimeParser.classify("1mo1d").millis(), "mo is not m + o");
        assertEquals(600_000L, TimeParser.classify("10min").millis());
        assertEquals(-1, TimeParser.classify("perm").millis());
        assertEquals(TimeParser.Kind.DURATION, kind("2W"));
    }

    @Test
    void typosAreRejectedNotTurnedIntoPermanentBans() {
        for (String typo : new String[]{"1dd", "7", "0d", "1.5h", "2x", "12hh", "1d2", "99999999999999999999d", "999999999y", "200y"}) {
            assertEquals(TimeParser.Kind.INVALID, kind(typo), typo);
        }
    }

    @Test
    void wordsAreReasons() {
        for (String word : new String[]{"Cheating", "x-ray", "KillAura", "-s", "d1"}) assertEquals(TimeParser.Kind.REASON, kind(word), word);
    }

    @Test
    void commandArguments() {
        PunishArgs typo = PunishArgs.parse(new String[]{"Steve", "1dd", "Cheating"}, 1, "def");
        assertEquals("1dd", typo.badDuration());
        PunishArgs ok = PunishArgs.parse(new String[]{"Steve", "-s", "7d", "Kill", "aura"}, 1, "def");
        assertNull(ok.badDuration());
        assertTrue(ok.silent());
        assertEquals(7L * 86_400_000L, ok.durationMs());
        assertEquals("Kill aura", ok.reason());
        PunishArgs perm = PunishArgs.parse(new String[]{"Steve", "Griefing"}, 1, "def");
        assertEquals(-1, perm.durationMs());
        assertEquals("Griefing", perm.reason());
        assertEquals("def", PunishArgs.parse(new String[]{"Steve"}, 1, "def").reason());
    }
}
