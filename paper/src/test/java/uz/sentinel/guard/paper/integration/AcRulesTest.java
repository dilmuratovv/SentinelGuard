package uz.sentinel.guard.paper.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AcRulesTest {
    private static SentinelAcHook.Rule rule(String check) { return SentinelAcHook.match(SentinelAcHook.DEFAULT_RULES, check); }

    @Test
    void rareCombatChecksCountFromTheFirstFlag() {
        for (String check : new String[]{"KillAuraA", "KillAuraC", "TrackingA", "BacktrackA", "AutoTotemA", "CrystalAuraA"}) {
            assertEquals(1, rule(check).threshold(), check);
            assertTrue(rule(check).windowMs() >= 600_000, check);
        }
        assertEquals(3, rule("Reach").threshold());
        assertEquals(2, rule("AutoClickerB").threshold());
    }

    @Test
    void movementNeedsABurstAndUnknownFallsThrough() {
        assertEquals(25, rule("Simulation").threshold());
        assertEquals(10, rule("ElytraG").threshold());
        assertNull(rule("BadPacketsA"), "falls back to sus-threshold");
        assertEquals(15, rule("Timer").threshold(), "exact Timer");
        assertEquals(15, rule("TickTimer").threshold());
    }

    @Test
    void longestPrefixAndExactWin() {
        List<Map.Entry<String, SentinelAcHook.Rule>> rules = List.of(
                Map.entry("Bad*", new SentinelAcHook.Rule(9, 1000)), Map.entry("BadPackets*", new SentinelAcHook.Rule(5, 1000)),
                Map.entry("BadPacketsA", new SentinelAcHook.Rule(2, 1000)));
        assertEquals(2, SentinelAcHook.match(rules, "badpacketsa").threshold());
        assertEquals(5, SentinelAcHook.match(rules, "BadPacketsB").threshold());
        assertEquals(9, SentinelAcHook.match(rules, "BadThing").threshold());
    }

    @Test
    void configSyntax() {
        assertEquals(new SentinelAcHook.Rule(2, 300_000), SentinelAcHook.parseRule("2/300"));
        assertEquals(new SentinelAcHook.Rule(4, 60_000), SentinelAcHook.parseRule(" 4 "));
        assertNull(SentinelAcHook.parseRule("0/10"));
        assertNull(SentinelAcHook.parseRule("x"));
    }
}
