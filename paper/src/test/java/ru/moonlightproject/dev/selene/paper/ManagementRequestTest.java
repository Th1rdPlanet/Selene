package ru.moonlightproject.dev.selene.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagementRequestTest {
    @Test
    void decodesOnlyBoundFixedLengthMessages() {
        UUID token = UUID.randomUUID();
        byte[] payload = ByteBuffer.allocate(17).putLong(token.getMostSignificantBits())
            .putLong(token.getLeastSignificantBits()).put((byte) 0).array();
        ManagementRequest request = ManagementRequest.decode(payload, token.toString()).orElseThrow();
        assertEquals(ManagementRequest.Action.ENABLE, request.action());
        assertEquals("totp_setup_begin", request.initialOperation());
        assertTrue(ManagementRequest.decode(payload, UUID.randomUUID().toString()).isEmpty());
        assertTrue(ManagementRequest.decode(new byte[18], token.toString()).isEmpty());
        payload[16] = 127;
        assertTrue(ManagementRequest.decode(payload, token.toString()).isEmpty());
    }

    @Test
    void codeActionsCheckTheStatusFirst() {
        UUID token = UUID.randomUUID();
        for (int ordinal = 1; ordinal <= 3; ordinal++) {
            byte[] payload = ByteBuffer.allocate(17).putLong(token.getMostSignificantBits())
                .putLong(token.getLeastSignificantBits()).put((byte) ordinal).array();
            assertEquals("totp_status", ManagementRequest.decode(payload, token.toString()).orElseThrow().initialOperation());
        }
    }
}
