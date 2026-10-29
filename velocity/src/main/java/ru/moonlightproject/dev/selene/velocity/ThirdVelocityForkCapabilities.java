package ru.moonlightproject.dev.selene.velocity;

import java.net.InetAddress;
import ru.moonlightproject.dev.selene.core.AttackLevel;
import ru.moonlightproject.dev.selene.core.ForkCapabilities;
import ru.moonlightproject.dev.selene.core.ProtectionSignals;
import ru.moonlightproject.dev.thirdvelocity.api.ThirdVelocity;
import ru.moonlightproject.dev.thirdvelocity.api.ThirdVelocityApi;
import ru.moonlightproject.dev.thirdvelocity.api.protection.TrafficSnapshot;
import ru.moonlightproject.dev.thirdvelocity.api.net.IpRange;

/**
 * Bridge from Selene's fork contract to the ThirdVelocity API. Loaded only reflectively, when the
 * ThirdVelocity API classes exist. Every call reads ThirdVelocity memory and never blocks.
 */
public final class ThirdVelocityForkCapabilities implements ForkCapabilities {

    private ThirdVelocityApi api() {
        return ThirdVelocity.find().orElse(null);
    }

    @Override
    public boolean supportsEarlyAdmission() {
        return ThirdVelocity.isPresent();
    }

    @Override
    public boolean suspicious(String username, String ip) {
        ThirdVelocityApi api = api();
        if (api == null) return false;
        try {
            return api.protection().suspicious(username, IpRange.parseLiteral(ip));
        } catch (IllegalArgumentException notAnAddress) {
            return false;
        }
    }

    @Override
    public AttackLevel attackLevel() {
        ThirdVelocityApi api = api();
        if (api == null) return AttackLevel.NORMAL;
        return switch (api.protection().level()) {
            case NORMAL -> AttackLevel.NORMAL;
            case LOW -> AttackLevel.LOW;
            case MEDIUM -> AttackLevel.MEDIUM;
            case STRONG -> AttackLevel.STRONG;
        };
    }

    @Override
    public ProtectionSignals protectionSignals() {
        ThirdVelocityApi api = api();
        if (api == null) return ProtectionSignals.UNAVAILABLE;
        TrafficSnapshot traffic = api.protection().traffic();
        return new ProtectionSignals(traffic.outboundPackets(), traffic.outboundBytes());
    }

    @Override
    public void onAuthentication(String username, InetAddress address, String status) {
        ThirdVelocityApi api = api();
        if (api == null) return;
        switch (status) {
            case "AUTHENTICATED" -> {
                api.alts().recordAuthenticated(username, address);
                api.reputation().report(address, "auth.success");
            }
            case "CAPTCHA_INVALID" -> api.reputation().report(address, "captcha.failed");
            case "INVALID_CREDENTIALS" -> api.reputation().report(address, "auth.failed");
            case "RATE_LIMITED" -> api.reputation().report(address, "auth.rate-limited");
            default -> {
            }
        }
    }

    @Override
    public String name() {
        return ThirdVelocity.find().map(api -> "ThirdVelocity " + api.version()).orElse("ThirdVelocity");
    }
}
