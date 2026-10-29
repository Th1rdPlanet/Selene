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
    public void appendMetrics(StringBuilder out) {
        ThirdVelocityApi api = api();
        if (api == null) return;
        var t = api.protection().totals();
        counter(out, "thirdvelocity_connections_total", t.connections());
        counter(out, "thirdvelocity_refused_total", t.refused());
        out.append("# TYPE thirdvelocity_refused_by_reason_total counter\n");
        t.refusedByReason().forEach((reason, value) -> out.append("thirdvelocity_refused_by_reason_total{reason=\"")
            .append(reason.replaceAll("[^a-z0-9_-]", "_")).append("\"} ").append(value).append('\n'));
        counter(out, "thirdvelocity_handshakes_total", t.handshakes());
        counter(out, "thirdvelocity_pings_total", t.pings());
        counter(out, "thirdvelocity_inbound_bytes_total", t.inboundBytes());
        counter(out, "thirdvelocity_outbound_bytes_total", t.outboundBytes());
        counter(out, "thirdvelocity_outbound_packets_total", t.outboundPackets());
        counter(out, "thirdvelocity_temporary_blocks_total", t.temporaryBlocks());
        gauge(out, "thirdvelocity_temporary_blocks_active", t.activeTemporaryBlocks());
        gauge(out, "thirdvelocity_known_addresses", t.knownAddresses());
        gauge(out, "thirdvelocity_attack_level", api.protection().level().ordinal());
        gauge(out, "thirdvelocity_open_connections", api.protection().traffic().openConnections());
        gauge(out, "thirdvelocity_bans_active", api.bans().active().size());
    }

    private static void counter(StringBuilder out, String name, long value) {
        out.append("# TYPE ").append(name).append(" counter\n").append(name).append(' ').append(value).append('\n');
    }

    private static void gauge(StringBuilder out, String name, long value) {
        out.append("# TYPE ").append(name).append(" gauge\n").append(name).append(' ').append(value).append('\n');
    }

    @Override
    public String name() {
        return ThirdVelocity.find().map(api -> "ThirdVelocity " + api.version()).orElse("ThirdVelocity");
    }
}
