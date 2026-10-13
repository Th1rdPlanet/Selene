package ru.moonlightproject.dev.selene.velocity;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import ru.moonlightproject.dev.selene.core.AttackLevel;

class AutoAttackControllerTest {
    @Test
    void suspiciousSingleIpEnablesLowWithoutReconnectRateLimitMode() {
        AutoAttackController controller = controller();
        controller.observe("192.0.2.1", "one", 1_000);
        controller.observe("192.0.2.1", "two", 1_100);
        controller.observe("192.0.2.1", "three", 1_200);

        assertTrue(controller.suspicious("192.0.2.1", 1_200));
        assertEquals(AttackLevel.LOW, controller.level(1_200));
    }

    @Test
    void globalBurstEscalatesAndRecoveryUsesHysteresis() {
        AutoAttackController controller = controller();
        for (int index = 0; index < 10; index++) {
            controller.observe("192.0.2." + index, "user" + index, 1_000 + index);
        }

        assertEquals(AttackLevel.STRONG, controller.level(1_020));
        assertEquals(AttackLevel.STRONG, controller.level(12_000));
        assertEquals(AttackLevel.NORMAL, controller.level(22_000));
    }

    @Test
    void boundedIpStateFailsNewAddressesSuspicious() {
        AutoAttackController controller = new AutoAttackController(
            1, Duration.ofSeconds(10), Duration.ofSeconds(10), 5, 7, 10, 3, 3);
        controller.observe("192.0.2.1", "one", 1_000);

        controller.observe("192.0.2.2", "two", 1_100);

        assertTrue(controller.suspicious("192.0.2.2", 1_100));
    }

    private static AutoAttackController controller() {
        return new AutoAttackController(
            64, Duration.ofSeconds(10), Duration.ofSeconds(10), 5, 7, 10, 3, 3);
    }
}
