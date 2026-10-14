package ru.moonlightproject.dev.selene.velocity;

import java.util.function.Consumer;
import ru.moonlightproject.dev.selene.core.AttackLevel;

/** Serializes effective transitions and scopes console approval to the current attack. */
final class ProtectionState {
    record Change(AttackLevel previous, AttackLevel current, String source, String reason) {}
    private final Consumer<Change> changes;
    private final Runnable reminder;
    private AttackLevel level = AttackLevel.NORMAL;
    private boolean pending;
    private boolean approved;
    private long nextReminder;

    ProtectionState(Consumer<Change> changes, Runnable reminder) {
        this.changes = changes;
        this.reminder = reminder;
    }

    synchronized AttackLevel update(AttackLevel desired, String source, String reason, long now) {
        if (desired != AttackLevel.STRONG) {
            pending = false;
            approved = false;
        } else if (!approved) {
            boolean first = !pending;
            pending = true;
            desired = AttackLevel.MEDIUM;
            transition(desired, source, reason + "; сильный режим ожидает подтверждения");
            if (first || now >= nextReminder) {
                nextReminder = now + 10_000;
                reminder.run();
            }
            return level;
        }
        transition(desired, source, reason);
        return level;
    }

    synchronized boolean confirm() {
        if (!pending) return false;
        approved = true;
        pending = false;
        transition(AttackLevel.STRONG, "консоль", "администратор подтвердил ограничение входа");
        return true;
    }

    synchronized AttackLevel level() { return level; }
    synchronized boolean pending() { return pending; }

    private void transition(AttackLevel next, String source, String reason) {
        if (next == level) return;
        AttackLevel previous = level;
        level = next;
        changes.accept(new Change(previous, next, source, reason));
    }
}
