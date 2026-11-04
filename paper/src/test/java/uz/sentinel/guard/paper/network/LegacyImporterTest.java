package uz.sentinel.guard.paper.network;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import uz.sentinel.guard.network.NetworkPlayer;
import uz.sentinel.guard.network.NetworkPunishment;
import uz.sentinel.guard.network.NetworkSus;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LegacyImporterTest {
    static final String PLAYER = "11111111-2222-3333-4444-555555555555";

    static ConfigurationSection yaml(String body) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(body);
        return yaml;
    }

    @Test void convertsIpv6IpBanAndKeepsIdsStable() throws Exception {
        var item = yaml("""
                type: IP_BAN
                target-uuid: %s
                target-name: Steve
                target-ip: "/2001:DB8::1"
                punisher-name: azicoo
                reason: Cheat
                duration-ms: -1
                start-time: 1000
                end-time: -1
                active: true
                silent: false
                """.formatted(PLAYER));
        NetworkPunishment a = LegacyImporter.convert("survival", "#1001", item);
        NetworkPunishment b = LegacyImporter.convert("survival", "#1001", item);
        NetworkPunishment other = LegacyImporter.convert("lobby", "#1001", item);
        assertEquals(a.id(), b.id(), "re-import must produce the same row id");
        assertNotEquals(a.id(), other.id(), "servers with the same legacy id must not collide");
        assertEquals(NetworkPunishment.Type.IP_BAN, a.type());
        assertEquals("2001:db8:0:0:0:0:0:1", a.ip());
        assertNull(a.expiresAt());
        assertTrue(a.activeAt(Instant.ofEpochMilli(5000)));
    }

    @Test void revokedMuteKeepsRevocationDetails() throws Exception {
        var item = yaml("""
                type: MUTE
                target-uuid: %s
                reason: Spam
                start-time: 1000
                end-time: 9000
                active: false
                unpunisher-name: admin
                unpunish-reason: appeal
                unpunish-time: 2000
                """.formatted(PLAYER));
        NetworkPunishment p = LegacyImporter.convert("survival", "#7", item);
        assertEquals(Instant.ofEpochMilli(9000), p.expiresAt());
        assertTrue(p.revoked());
        assertEquals("admin", p.revocation().actor());
        assertEquals(Instant.ofEpochMilli(2000), p.revocation().at());
    }

    @Test void warnsAndKicksNeverExpireAndBrokenRowsAreSkipped() throws Exception {
        var warn = yaml("type: WARN\ntarget-uuid: " + PLAYER + "\nstart-time: 1000\nend-time: -1\nactive: true\n");
        assertNull(LegacyImporter.convert("s", "#1", warn).expiresAt());
        var kick = yaml("type: KICK\ntarget-uuid: " + PLAYER + "\nstart-time: 1000\nend-time: 1000\n");
        assertFalse(LegacyImporter.convert("s", "#2", kick).activeAt(Instant.ofEpochMilli(1000)));
        assertNull(LegacyImporter.convert("s", "#3", yaml("type: IP_BAN\ntarget-ip: not-an-ip\nstart-time: 1\n")));
        assertNull(LegacyImporter.convert("s", "#4", yaml("type: BAN\nstart-time: 1\n")), "ban without uuid");
        assertNull(LegacyImporter.convert("s", "#5", yaml("type: UNKNOWN\ntarget-uuid: " + PLAYER + "\n")));
        var zeroLength = yaml("type: BAN\ntarget-uuid: " + PLAYER + "\nstart-time: 5000\nend-time: 4000\n");
        assertFalse(LegacyImporter.convert("s", "#6", zeroLength).activeAt(Instant.ofEpochMilli(6000)), "already-expired ban stays expired");
        assertEquals(UUID.fromString(PLAYER), LegacyImporter.convert("s", "#1", warn).player());
    }

    @Test void legacyPlayerNormalizesIpv6AndDropsGarbage() throws Exception {
        var item = yaml("""
                name: Steve
                latest-ip: "/2001:DB8::1"
                ips:
                  - 10.0.0.1
                  - "[2001:DB8::2]:25565"
                  - not-an-ip
                """);
        NetworkPlayer p = LegacyImporter.convertPlayer(PLAYER, item);
        assertEquals(UUID.fromString(PLAYER), p.uuid());
        assertEquals("2001:db8:0:0:0:0:0:1", p.latestIp());
        assertEquals(java.util.Set.of("10.0.0.1", "2001:db8:0:0:0:0:0:1", "2001:db8:0:0:0:0:0:2"), p.ips());
        assertNull(LegacyImporter.convertPlayer("not-a-uuid", item), "bad uuid is skipped");
        assertNull(LegacyImporter.convertPlayer(PLAYER, yaml("latest-ip: 10.0.0.1\n")), "missing name is skipped");
    }

    @Test void legacySusKeepsHistoryOrSynthesizesFromReasons() throws Exception {
        var withHistory = yaml("""
                name: Alex
                active: true
                first-added: 1000
                last-added: 3000
                reasons: {killaura: 2}
                history:
                  - {reason: killaura, staff: mod1, timestamp: 1000}
                  - {reason: killaura, staff: mod2, timestamp: 3000}
                """);
        NetworkSus a = LegacyImporter.convertSus(PLAYER, withHistory);
        assertEquals(2, a.history().size());
        assertEquals("mod2", a.history().get(1).staff());
        assertEquals(Instant.ofEpochMilli(3000), a.lastAdded());

        var onlyReasons = yaml("name: Alex\nactive: false\nfirst-added: 1000\nlast-added: 5000\nreasons: {fly: 1, reach: 2}\n");
        NetworkSus b = LegacyImporter.convertSus(PLAYER, onlyReasons);
        assertFalse(b.active());
        assertEquals(3, b.history().size());
        assertTrue(b.history().stream().allMatch(e -> e.staff().equals("Legacy") && e.at().equals(Instant.ofEpochMilli(5000))));
        assertEquals(1, b.history().stream().filter(e -> e.reason().equals("fly")).count());

        assertNull(LegacyImporter.convertSus("bad", withHistory), "bad uuid is skipped");
    }
}
