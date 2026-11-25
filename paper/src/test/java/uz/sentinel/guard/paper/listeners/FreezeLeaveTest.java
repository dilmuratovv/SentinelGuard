package uz.sentinel.guard.paper.listeners;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.event.player.PlayerQuitEvent.QuitReason;
import org.junit.jupiter.api.Test;
import uz.sentinel.guard.paper.listeners.FreezeListener.Leave;

class FreezeLeaveTest {
    @Test
    void quittingIsBannedAtOnce() {
        assertEquals(Leave.BAN_NOW, FreezeListener.leave(QuitReason.DISCONNECTED, false, false, false));
        assertTrue(FreezeListener.leftOnPurpose(QuitReason.DISCONNECTED, false, false));
    }

    @Test
    void provokableLeavesMustReturn() {
        // a cheat client can stop answering keep-alives or send a bad packet to get an anticheat kick
        assertEquals(Leave.AWAIT_RETURN, FreezeListener.leave(QuitReason.TIMED_OUT, false, false, false));
        assertEquals(Leave.AWAIT_RETURN, FreezeListener.leave(QuitReason.ERRONEOUS_STATE, false, false, false));
        assertEquals(Leave.AWAIT_RETURN, FreezeListener.leave(QuitReason.KICKED, false, false, false));
    }

    @Test
    void staffKicksAndStopsNeverBan() {
        assertEquals(Leave.KEEP_FROZEN, FreezeListener.leave(QuitReason.KICKED, false, false, true));
        assertEquals(Leave.KEEP_FROZEN, FreezeListener.leave(QuitReason.DISCONNECTED, true, false, false));
        assertEquals(Leave.KEEP_FROZEN, FreezeListener.leave(QuitReason.KICKED, true, false, false));
    }

    @Test
    void networkLeavesAreTheProxysCall() {
        for (QuitReason r : QuitReason.values()) assertEquals(Leave.PROXY_DECIDES, FreezeListener.leave(r, false, true, false));
    }
}
