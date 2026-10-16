package ru.moonlightproject.dev.selene.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackDeliveryTest {
    @Test void parsesConfiguredItemsAdderPack() {
        var pack = ResourcePackDelivery.parse(
                "7e6ef4f1-1e38-4d09-8a04-35a74bd1fc14",
                "http://127.0.0.1:8164/generated.zip",
                "a933342c82dc479b46dfeae3219f586228b4a30e",
                "<aqua>Selene</aqua>");
        assertEquals(20, pack.sha1().length);
        assertEquals("http://127.0.0.1:8164/generated.zip", pack.url());
    }

    @Test void rejectsInvalidHashAndProtocol() {
        assertThrows(IllegalArgumentException.class, () -> ResourcePackDelivery.parse(
                "7e6ef4f1-1e38-4d09-8a04-35a74bd1fc14", "file:///tmp/pack.zip", "00", ""));
    }
}
