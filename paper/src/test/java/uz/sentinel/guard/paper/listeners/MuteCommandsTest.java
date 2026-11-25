package uz.sentinel.guard.paper.listeners;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MuteCommandsTest {
    private static final List<String> DEFAULTS = PunishmentListener.DEFAULT_MUTE_BLOCKED_COMMANDS;

    @Test
    void privateMessagesAndMeAreBlocked() {
        assertTrue(PunishmentListener.blockedWhileMuted("/msg Bob hi", DEFAULTS));
        assertTrue(PunishmentListener.blockedWhileMuted("/TELL Bob hi", DEFAULTS));
        assertTrue(PunishmentListener.blockedWhileMuted("/r ok", DEFAULTS));
        assertTrue(PunishmentListener.blockedWhileMuted("/me waves", DEFAULTS));
        assertTrue(PunishmentListener.blockedWhileMuted("/minecraft:say hello", DEFAULTS));
        assertTrue(PunishmentListener.blockedWhileMuted("/essentials:msg Bob hi", DEFAULTS));
    }

    @Test
    void otherCommandsStayAllowed() {
        assertFalse(PunishmentListener.blockedWhileMuted("/spawn", DEFAULTS));
        assertFalse(PunishmentListener.blockedWhileMuted("/helpop I am muted wrongly", DEFAULTS));
        assertFalse(PunishmentListener.blockedWhileMuted("/msgtoggle", DEFAULTS), "exact label, not a prefix");
        assertFalse(PunishmentListener.blockedWhileMuted("/", DEFAULTS));
    }

    @Test
    void configuredListIsUsedWithOrWithoutSlash() {
        List<String> custom = List.of("/pm", "Whisper");
        assertTrue(PunishmentListener.blockedWhileMuted("/pm Bob hi", custom));
        assertTrue(PunishmentListener.blockedWhileMuted("/whisper Bob hi", custom));
        assertFalse(PunishmentListener.blockedWhileMuted("/msg Bob hi", custom));
    }

    @Test
    void partyClanAndChannelChatsAreBlocked() {
        for (String line : new String[]{"/party chat hi all", "/p chat hi", "/pc hi", "/mcmmo:partychat hi", "/clan chat buy coins",
                "/f c", "/f chat hi", "/gc hello", "/ch g", "/qm g hi", "/tc hi", "/g hello", "/afk brb selling", "/itemname &cFree",
                "/nick BuyCoinsDotCom", "/is chat hi"}) {
            assertTrue(PunishmentListener.blockedWhileMuted(line, DEFAULTS), line);
        }
    }

    @Test
    void subcommandEntriesDoNotOverreach() {
        assertFalse(PunishmentListener.blockedWhileMuted("/party invite Bob", DEFAULTS));
        assertFalse(PunishmentListener.blockedWhileMuted("/p accept", DEFAULTS));
        assertFalse(PunishmentListener.blockedWhileMuted("/f home", DEFAULTS));
        assertFalse(PunishmentListener.blockedWhileMuted("/clan list", DEFAULTS));
        assertFalse(PunishmentListener.blockedWhileMuted("/afk", DEFAULTS), "plain AFK has no message");
        assertFalse(PunishmentListener.blockedWhileMuted("/is home", DEFAULTS));
        assertFalse(PunishmentListener.blockedWhileMuted("/trade Bob", DEFAULTS));
    }

    @Test
    void wildcardAndNamespaceInEntries() {
        List<String> custom = List.of("kingdom chat", "essentials:afk *");
        assertTrue(PunishmentListener.blockedWhileMuted("/kingdom chat hi", custom));
        assertTrue(PunishmentListener.blockedWhileMuted("/kingdoms:kingdom CHAT hi", custom));
        assertFalse(PunishmentListener.blockedWhileMuted("/kingdom claim", custom));
        assertTrue(PunishmentListener.blockedWhileMuted("/afk gone", custom));
    }
}
