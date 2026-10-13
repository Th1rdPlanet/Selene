package ru.moonlightproject.dev.selene.core;

import java.time.Duration;
import java.util.UUID;

public final class BackendSmokeMain {
    private BackendSmokeMain() {}

    public static void main(String[] args) throws Exception {
        try (BackendGateway gateway = BackendGateway.connect(args[0], null, true, Duration.ofSeconds(3))) {
            String snapshotSession = UUID.randomUUID().toString();
            AuthReply snapshot = gateway.call(new AuthRequest(
                "snapshot", snapshotSession, "system", "127.0.0.1", "", "", false, false)).join();
            if (!snapshot.status().equals("SNAPSHOT") || !snapshot.session().equals(snapshotSession)) {
                throw new AssertionError("unexpected snapshot reply: " + snapshot.status());
            }
            String loginSession = UUID.randomUUID().toString();
            AuthReply premium = gateway.call(new AuthRequest(
                "begin", loginSession, "SmokeUser", "127.0.0.1", "", "", true, false)).join();
            if (!premium.status().equals("AUTHENTICATED") || !premium.session().equals(loginSession)) {
                throw new AssertionError("unexpected premium reply: " + premium.status());
            }
        }
    }
}
