package ru.moonlightproject.dev.selene.core;

/** Instantaneous nonblocking metrics supplied by a compatible proxy fork. Negative means unavailable. */
public record ProtectionSignals(long outboundPacketsPerSecond, long outboundBytesPerSecond) {
    public static final ProtectionSignals UNAVAILABLE = new ProtectionSignals(-1, -1);

    public ProtectionSignals {
        if (outboundPacketsPerSecond < -1 || outboundBytesPerSecond < -1) {
            throw new IllegalArgumentException("Protection signal values must be nonnegative or -1");
        }
    }
}
