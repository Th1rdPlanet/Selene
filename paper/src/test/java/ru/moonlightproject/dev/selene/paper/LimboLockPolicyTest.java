package ru.moonlightproject.dev.selene.paper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
class LimboLockPolicyTest {
    @Test
    void managementAlwaysKeepsThePlayerFrozen() {
        assertTrue(LimboLockPolicy.locked(AuthStage.WAITING_FOR_SESSION, true));
        assertTrue(LimboLockPolicy.locked(AuthStage.AUTHENTICATED, true));
        assertFalse(LimboLockPolicy.locked(AuthStage.AUTHENTICATED, false));
    }

    @Test
    void everyAuthenticationStageRemainsFrozenUntilAuthenticationCompletes() {
        for (AuthStage stage : AuthStage.values()) {
            if (stage == AuthStage.AUTHENTICATED) continue;
            assertTrue(LimboLockPolicy.locked(stage, false), stage.name());
        }
    }
}
