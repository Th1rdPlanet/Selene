package ru.moonlightproject.dev.selene.paper;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;

record ManagementRequest(Action action) {
    enum Action { ENABLE, STATUS, DISABLE, RECOVERY }

    static Optional<ManagementRequest> decode(byte[] payload, String expectedSession) {
        if (payload == null || payload.length != 17 || expectedSession == null) return Optional.empty();
        ByteBuffer bytes = ByteBuffer.wrap(payload);
        UUID token = new UUID(bytes.getLong(), bytes.getLong());
        int action = Byte.toUnsignedInt(bytes.get());
        if (!token.toString().equals(expectedSession) || action >= Action.values().length) {
            return Optional.empty();
        }
        return Optional.of(new ManagementRequest(Action.values()[action]));
    }

    String initialOperation() {
        return switch (action) {
            case ENABLE -> "totp_setup_begin";
            // Disable and recovery ask for a code, so first make sure 2FA is enabled at all.
            case STATUS, DISABLE, RECOVERY -> "totp_status";
        };
    }
}
