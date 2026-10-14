package ru.moonlightproject.dev.selene.velocity;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import ru.moonlightproject.dev.selene.core.ProtectionSignals;
import ru.moonlightproject.dev.selene.core.AttackLevel;

/** Bounded, local-only signal aggregation. It performs no DNS, GeoIP or backend work. */
final class AutoAttackController {
    private static final class IpWindow {
        final ArrayDeque<Long> attempts = new ArrayDeque<>();
        final Map<String, Long> usernames = new HashMap<>();
    }

    private final int capacity;
    private final long windowMillis;
    private final long recoveryMillis;
    private final int lowThreshold;
    private final int mediumThreshold;
    private final int strongThreshold;
    private final int suspiciousAttempts;
    private final int suspiciousNames;
    private final int failureMinSamples, failureLowPercent, failureMediumPercent;
    private final int pendingMedium, pendingStrong;
    private final long packetsLow, packetsMedium, packetsStrong, bytesLow, bytesMedium, bytesStrong;
    private final ArrayDeque<Long> global = new ArrayDeque<>();
    private record Authentication(long time, boolean failure) {}
    private final ArrayDeque<Authentication> authentication = new ArrayDeque<>();
    private final Map<String, IpWindow> ips = new HashMap<>();
    private long overflowUntil;
    private long recoveryUntil;
    private int lastPending;
    private ProtectionSignals lastTraffic=ProtectionSignals.UNAVAILABLE;
    private AttackLevel current = AttackLevel.NORMAL;

    AutoAttackController(
        int capacity,
        Duration window,
        Duration recovery,
        int lowThreshold,
        int mediumThreshold,
        int strongThreshold,
        int suspiciousAttempts,
        int suspiciousNames
    ) {
        this(capacity,window,recovery,lowThreshold,mediumThreshold,strongThreshold,suspiciousAttempts,suspiciousNames,
            20,50,75,100,500,10_000,25_000,50_000,1_000_000,2_500_000,5_000_000);
    }

    AutoAttackController(
        int capacity, Duration window, Duration recovery, int lowThreshold, int mediumThreshold, int strongThreshold,
        int suspiciousAttempts, int suspiciousNames, int failureMinSamples, int failureLowPercent,
        int failureMediumPercent, int pendingMedium, int pendingStrong, long packetsLow, long packetsMedium,
        long packetsStrong, long bytesLow, long bytesMedium, long bytesStrong
    ) {
        if (capacity < 1 || window.isZero() || window.isNegative() || recovery.isNegative()
            || lowThreshold < 1 || mediumThreshold <= lowThreshold || strongThreshold <= mediumThreshold
            || suspiciousAttempts < 1 || suspiciousNames < 1 || failureMinSamples < 1
            || failureLowPercent < 1 || failureMediumPercent <= failureLowPercent || failureMediumPercent > 100
            || pendingMedium < 1 || pendingStrong <= pendingMedium
            || packetsLow < 1 || packetsMedium <= packetsLow || packetsStrong <= packetsMedium
            || bytesLow < 1 || bytesMedium <= bytesLow || bytesStrong <= bytesMedium) {
            throw new IllegalArgumentException("invalid automatic attack policy");
        }
        this.capacity = capacity;
        this.windowMillis = window.toMillis();
        this.recoveryMillis = recovery.toMillis();
        this.lowThreshold = lowThreshold;
        this.mediumThreshold = mediumThreshold;
        this.strongThreshold = strongThreshold;
        this.suspiciousAttempts = suspiciousAttempts;
        this.suspiciousNames = suspiciousNames;
        this.failureMinSamples=failureMinSamples;this.failureLowPercent=failureLowPercent;this.failureMediumPercent=failureMediumPercent;
        this.pendingMedium=pendingMedium;this.pendingStrong=pendingStrong;
        this.packetsLow=packetsLow;this.packetsMedium=packetsMedium;this.packetsStrong=packetsStrong;
        this.bytesLow=bytesLow;this.bytesMedium=bytesMedium;this.bytesStrong=bytesStrong;
    }

    synchronized void observe(String ip, String username, long nowMillis) {
        prune(nowMillis);
        if (global.size() >= strongThreshold) global.removeFirst();
        global.addLast(nowMillis);
        IpWindow window = ips.get(ip);
        if (window == null) {
            if (ips.size() >= capacity) {
                overflowUntil = Math.max(overflowUntil, nowMillis + windowMillis);
                return;
            }
            window = new IpWindow();
            ips.put(ip, window);
        }
        if (window.attempts.size() >= suspiciousAttempts) window.attempts.removeFirst();
        window.attempts.addLast(nowMillis);
        if (window.usernames.size() < suspiciousNames || window.usernames.containsKey(username.toLowerCase(java.util.Locale.ROOT))) {
            window.usernames.put(username.toLowerCase(java.util.Locale.ROOT), nowMillis);
        }
    }

    synchronized boolean suspicious(String ip, long nowMillis) {
        prune(nowMillis);
        IpWindow window = ips.get(ip);
        return nowMillis < overflowUntil || (window != null
            && (window.attempts.size() >= suspiciousAttempts || window.usernames.size() >= suspiciousNames));
    }

    synchronized AttackLevel level(long nowMillis) {
        return level(nowMillis,0,ProtectionSignals.UNAVAILABLE);
    }

    synchronized void observeAuthentication(boolean failure,long nowMillis) {
        prune(nowMillis);
        if(authentication.size()>=capacity)authentication.removeFirst();
        authentication.addLast(new Authentication(nowMillis,failure));
    }

    synchronized AttackLevel level(long nowMillis,int pending,ProtectionSignals traffic) {
        prune(nowMillis);
        lastPending=pending;lastTraffic=traffic;
        AttackLevel desired;
        if (global.size() >= strongThreshold) desired = AttackLevel.STRONG;
        else if (global.size() >= mediumThreshold) desired = AttackLevel.MEDIUM;
        else if (global.size() >= lowThreshold || hasSuspiciousIp(nowMillis)) desired = AttackLevel.LOW;
        else desired = AttackLevel.NORMAL;

        desired=max(desired,authenticationLevel());
        if(pending>=pendingStrong)desired=max(desired,AttackLevel.STRONG);
        else if(pending>=pendingMedium)desired=max(desired,AttackLevel.MEDIUM);
        desired=max(desired,trafficLevel(traffic));

        if (desired.ordinal() > current.ordinal()) {
            current = desired;
            recoveryUntil = 0;
        } else if (desired.ordinal() < current.ordinal()) {
            if (recoveryUntil == 0) recoveryUntil = nowMillis + recoveryMillis;
            if (nowMillis >= recoveryUntil) {
                current = desired;
                recoveryUntil = 0;
            }
        } else {
            recoveryUntil = 0;
        }
        return current;
    }

    private AttackLevel authenticationLevel(){
        if(authentication.size()<failureMinSamples)return AttackLevel.NORMAL;
        long failures=authentication.stream().filter(Authentication::failure).count();
        long percent=failures*100/authentication.size();
        if(percent>=failureMediumPercent)return AttackLevel.MEDIUM;
        if(percent>=failureLowPercent)return AttackLevel.LOW;
        return AttackLevel.NORMAL;
    }

    private AttackLevel trafficLevel(ProtectionSignals traffic){
        long packets=traffic.outboundPacketsPerSecond(),bytes=traffic.outboundBytesPerSecond();
        if(packets>=packetsStrong||bytes>=bytesStrong)return AttackLevel.STRONG;
        if(packets>=packetsMedium||bytes>=bytesMedium)return AttackLevel.MEDIUM;
        if(packets>=packetsLow||bytes>=bytesLow)return AttackLevel.LOW;
        return AttackLevel.NORMAL;
    }

    private static AttackLevel max(AttackLevel first,AttackLevel second){return first.ordinal()>=second.ordinal()?first:second;}

    private boolean hasSuspiciousIp(long nowMillis) {
        if (nowMillis < overflowUntil) return true;
        return ips.values().stream().anyMatch(window ->
            window.attempts.size() >= suspiciousAttempts || window.usernames.size() >= suspiciousNames);
    }

    private void prune(long nowMillis) {
        long cutoff = nowMillis - windowMillis;
        while (!global.isEmpty() && global.peekFirst() <= cutoff) global.removeFirst();
        while(!authentication.isEmpty()&&authentication.peekFirst().time()<=cutoff)authentication.removeFirst();
        ips.entrySet().removeIf(entry -> {
            IpWindow window = entry.getValue();
            while (!window.attempts.isEmpty() && window.attempts.peekFirst() <= cutoff) {
                window.attempts.removeFirst();
            }
            window.usernames.entrySet().removeIf(username -> username.getValue() <= cutoff);
            return window.attempts.isEmpty();
        });
        if (nowMillis >= overflowUntil) overflowUntil = 0;
    }

    synchronized String explanation(long nowMillis) {
        prune(nowMillis);
        long failures=authentication.stream().filter(Authentication::failure).count();
        String ratio=authentication.isEmpty()?"нет данных":(failures*100/authentication.size())+"% из "+authentication.size();
        return "подключений за окно: " + global.size() + " (пороги "
            + lowThreshold + "/" + mediumThreshold + "/" + strongThreshold
            + "); подозрительная активность IP: " + (hasSuspiciousIp(nowMillis) ? "да" : "нет")
            + "; ошибок авторизации: "+ratio+"; незавершённых сессий: "+lastPending
            + "; исходящих пакетов/с: "+metric(lastTraffic.outboundPacketsPerSecond())
            + "; исходящих байт/с: "+metric(lastTraffic.outboundBytesPerSecond())
            + (recoveryUntil > nowMillis ? "; ожидание восстановления" : "");
    }

    private static String metric(long value){return value<0?"недоступно":Long.toString(value);}

    synchronized int trackedAttemptCount() {
        return global.size() + ips.values().stream().mapToInt(window -> window.attempts.size()).sum();
    }
}
