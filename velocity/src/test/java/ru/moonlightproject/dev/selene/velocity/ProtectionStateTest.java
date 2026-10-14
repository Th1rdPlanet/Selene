package ru.moonlightproject.dev.selene.velocity;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import ru.moonlightproject.dev.selene.core.AttackLevel;

class ProtectionStateTest {
    @Test void logsEveryActualTransitionOnlyOnce() {
        var changes = new ArrayList<ProtectionState.Change>();
        var state = new ProtectionState(changes::add, () -> {});
        state.update(AttackLevel.LOW, "automatic", "connections", 0);
        state.update(AttackLevel.LOW, "automatic", "connections", 1);
        state.update(AttackLevel.NORMAL, "automatic", "recovery", 2);
        assertEquals(2, changes.size());
        assertEquals(AttackLevel.NORMAL, changes.get(0).previous());
        assertEquals(AttackLevel.LOW, changes.get(0).current());
        assertEquals("recovery", changes.get(1).reason());
    }
    @Test void strongNeedsConfirmationAndRemindersAreThrottled() {
        var reminders = new ArrayList<Boolean>();
        var state = new ProtectionState(change -> {}, () -> reminders.add(true));
        assertEquals(AttackLevel.MEDIUM, state.update(AttackLevel.STRONG, "automatic", "burst", 0));
        state.update(AttackLevel.STRONG, "automatic", "burst", 9999);
        assertEquals(1, reminders.size());
        state.update(AttackLevel.STRONG, "automatic", "burst", 10000);
        assertEquals(2, reminders.size());
        assertTrue(state.confirm());
        assertEquals(AttackLevel.STRONG, state.level());
        assertFalse(state.confirm());
    }
    @Test void recoveryCancelsPendingAndNewAttackRequiresFreshApproval() {
        var state = new ProtectionState(change -> {}, () -> {});
        state.update(AttackLevel.STRONG, "fork", "signal", 0);
        state.update(AttackLevel.NORMAL, "fork", "recovery", 1);
        assertFalse(state.confirm());
        state.update(AttackLevel.STRONG, "fork", "signal", 2);
        assertTrue(state.confirm());
        state.update(AttackLevel.LOW, "fork", "recovery", 3);
        assertEquals(AttackLevel.MEDIUM, state.update(AttackLevel.STRONG, "fork", "signal", 4));
    }
}
