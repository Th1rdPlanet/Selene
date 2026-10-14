package ru.moonlightproject.dev.selene.velocity;

import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.core.AttackLevel;
import ru.moonlightproject.dev.selene.core.ProtectionSignals;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveSignalsTest {
    @Test
    void authenticationFailureRatioCanRaiseProtectionWithoutConnectionFlood() {
        var controller=controller();
        for(int i=0;i<10;i++)controller.observeAuthentication(i<8,1_000+i);

        assertEquals(AttackLevel.MEDIUM,controller.level(1_020,0,ProtectionSignals.UNAVAILABLE));
    }

    @Test
    void pendingSessionsAndForkPacketRateContributeIndependently() {
        var pending=controller();
        assertEquals(AttackLevel.MEDIUM,pending.level(1_000,25,ProtectionSignals.UNAVAILABLE));
        var packets=controller();
        assertEquals(AttackLevel.STRONG,packets.level(1_000,0,new ProtectionSignals(50_000,5_000_000)));
    }

    @Test
    void unavailableForkMetricsNeverTriggerProtection() {
        assertEquals(AttackLevel.NORMAL,controller().level(1_000,0,ProtectionSignals.UNAVAILABLE));
    }

    private static AutoAttackController controller(){
        return new AutoAttackController(64,Duration.ofSeconds(10),Duration.ofSeconds(10),30,60,100,8,4,
            10,50,75,20,40,10_000,25_000,50_000,1_000_000,2_500_000,5_000_000);
    }
}
