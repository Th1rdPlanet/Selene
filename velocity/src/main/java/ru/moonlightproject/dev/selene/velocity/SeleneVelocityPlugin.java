package ru.moonlightproject.dev.selene.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import ru.moonlightproject.dev.selene.core.AdmissionPolicy;
import ru.moonlightproject.dev.selene.core.AttackLevel;
import ru.moonlightproject.dev.selene.core.AuthGateway;
import ru.moonlightproject.dev.selene.core.AuthReply;
import ru.moonlightproject.dev.selene.core.AuthRequest;
import ru.moonlightproject.dev.selene.core.BackendGateway;
import ru.moonlightproject.dev.selene.core.ForkCapabilities;
import ru.moonlightproject.dev.selene.core.RelayServer;
import ru.moonlightproject.dev.selene.core.TlsContexts;

@Plugin(
    id = "selene",
    name = "Selene",
    version = "0.1.0-dev",
    description = "Authentication and adaptive admission for Velocity",
    url = "https://dev.moonlightproject.ru",
    authors = {"~VicTim~"}
)
public final class SeleneVelocityPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<String, String> lastServers = new ConcurrentHashMap<>();
    private volatile Set<String> premiumNames = Set.of();
    private volatile RuntimeState runtime;

    @Inject
    public SeleneVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public EventTask initialize(ProxyInitializeEvent ignored) {
        return EventTask.async(() -> {
            try {
                Files.createDirectories(dataDirectory);
                Path configFile = dataDirectory.resolve("velocity.properties");
                installDefaultConfig(configFile);
                VelocityConfig config = VelocityConfig.load(configFile);
                ForkCapabilities fork = findForkCapabilities();
                if (config.forkEnabled() && !fork.supportsEarlyAdmission()) {
                    throw new IllegalStateException("fork-enabled requires a compatible Selene fork API provider");
                }
                if (config.mediumBeforeLogin() && !fork.supportsEarlyAdmission()) {
                    throw new IllegalStateException("medium-before-login requires the configured fork API");
                }
                SSLContext tls = TlsContexts.load(
                    config.keyStore(), config.keyStorePasswordEnv(),
                    config.trustStore(), config.trustStorePasswordEnv());
                AuthGateway backend = BackendGateway.connect(
                    config.backendEndpoint(), tls, config.allowLoopbackPlaintext(), Duration.ofSeconds(3));
                AdmissionPolicy admission = new AdmissionPolicy(
                    config.sessionCapacity(), config.perIpLimit(), config.perIpWindow());
                AutoAttackController attacks = new AutoAttackController(
                    config.sessionCapacity(), config.attackWindow(), config.attackRecovery(),
                    config.lowConnections(), config.mediumConnections(), config.strongConnections(),
                    config.suspiciousIpConnections(), config.suspiciousIpUsernames());
                ConnectionSessions sessions = new ConnectionSessions(config.sessionCapacity());
                RelayServer relay = new RelayServer(
                    tls, config.relayBind(), Duration.ofSeconds(3), 64,
                    (request, fingerprint) -> relay(request, fingerprint));
                runtime = new RuntimeState(config, backend, admission, attacks, sessions, relay, fork);
                refreshSnapshot();
                proxy.getScheduler().buildTask(this, this::refreshSnapshot)
                    .repeat(30, TimeUnit.SECONDS).schedule();
                logger.info("Selene Velocity started; auth relay listening on {}", config.relayBind());
            } catch (Exception error) {
                logger.error("Selene cannot start safely", error);
                proxy.shutdown(Component.text("Selene authentication is unavailable"));
            }
        });
    }

    @Subscribe
    public void preLogin(PreLoginEvent event) {
        RuntimeState state = runtime;
        if (state == null) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(text("backend-unavailable")));
            return;
        }
        String ip = event.getConnection().getRemoteAddress().getAddress().getHostAddress();
        String username = event.getUsername();
        long now = System.currentTimeMillis();
        state.attacks().observe(ip, username, now);
        AttackLevel level = currentLevel(state, now);
        boolean suspicious = state.fork().suspicious(username, ip) || state.attacks().suspicious(ip, now);
        if (level == AttackLevel.STRONG && !state.admission().isGreen(username, ip)) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(text("strong")));
            return;
        }
        if (!state.admission().admit(level, ip, now)) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(text("busy")));
            return;
        }
        if (premiumNames.contains(username.toLowerCase(Locale.ROOT))) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
        }
    }

    @Subscribe
    public void postLogin(PostLoginEvent event) {
        RuntimeState state = runtime;
        if (state == null) return;
        Player player = event.getPlayer();
        String ip = player.getRemoteAddress().getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        AttackLevel level = currentLevel(state, now);
        boolean suspicious = state.fork().suspicious(player.getUsername(), ip)
            || state.attacks().suspicious(ip, now);
        boolean captcha = state.admission().captcha(level, state.config().normalCaptcha(), suspicious);
        ConnectionSessions.Session session;
        try {
            session = state.sessions().open(
                player.getUniqueId(), player.getUsername(), player.getRemoteAddress().getAddress(),
                player.isOnlineMode(), captcha);
        } catch (IllegalStateException full) {
            player.disconnect(text("busy"));
            return;
        }
        if (player.isOnlineMode() && !captcha) {
            AuthRequest request = boundRequest("begin", session, "", "");
            state.backend().call(request).thenAccept(reply -> acceptAuthentication(player, session, reply));
        }
    }

    @Subscribe
    public void chooseInitialServer(PlayerChooseInitialServerEvent event) {
        RuntimeState state = runtime;
        if (state == null) return;
        String destination = authenticated.contains(event.getPlayer().getUniqueId())
            ? destinationFor(event.getPlayer(), state.config())
            : state.config().authServer();
        proxy.getServer(destination).ifPresent(event::setInitialServer);
    }

    @Subscribe
    public void preConnect(ServerPreConnectEvent event) {
        RuntimeState state = runtime;
        if (state == null) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }
        boolean loggedIn = authenticated.contains(event.getPlayer().getUniqueId());
        String target = event.getOriginalServer().getServerInfo().getName();
        if (!loggedIn && !target.equals(state.config().authServer())) {
            event.getPlayer().sendMessage(text("auth-required"));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    @Subscribe
    public void connected(ServerConnectedEvent event) {
        RuntimeState state = runtime;
        if (state == null || !authenticated.contains(event.getPlayer().getUniqueId())) return;
        String server = event.getServer().getServerInfo().getName();
        if (!server.equals(state.config().authServer()) && lastServers.size() < state.config().sessionCapacity()) {
            lastServers.put(event.getPlayer().getUsername().toLowerCase(Locale.ROOT), server);
        }
    }

    @Subscribe
    public void disconnect(DisconnectEvent event) {
        RuntimeState state = runtime;
        if (state == null) return;
        UUID connection = event.getPlayer().getUniqueId();
        state.sessions().get(connection).ifPresent(session -> {
            if (authenticated.remove(connection)) {
                state.backend().call(boundRequest("trust", session, "", ""));
            }
            state.backend().call(boundRequest("close", session, "", ""));
        });
        state.sessions().close(connection);
    }

    @Subscribe
    public void shutdown(ProxyShutdownEvent ignored) {
        RuntimeState state = runtime;
        runtime = null;
        if (state != null) {
            state.relay().close();
            state.backend().close();
        }
    }

    private CompletableFuture<AuthReply> relay(AuthRequest untrusted, String fingerprint) {
        RuntimeState state = runtime;
        if (state == null || !fingerprint.equalsIgnoreCase(state.config().authServerFingerprint())) {
            return CompletableFuture.completedFuture(AuthReply.status("INVALID_REQUEST", ""));
        }
        Optional<Player> player = proxy.getPlayer(untrusted.username());
        if (player.isEmpty() || player.get().getCurrentServer().isEmpty()
            || !player.get().getCurrentServer().get().getServerInfo().getName().equals(state.config().authServer())) {
            return CompletableFuture.completedFuture(AuthReply.status("EXPIRED", ""));
        }
        UUID connection = player.get().getUniqueId();
        Optional<AuthRequest> bound = state.sessions().authorizeRelay(connection, untrusted);
        if (bound.isEmpty()) return CompletableFuture.completedFuture(AuthReply.status("INVALID_REQUEST", ""));
        UUID sessionId = state.sessions().get(connection).orElseThrow().id();
        return state.backend().call(bound.get()).thenApply(reply -> {
            if (!state.sessions().isCurrent(connection, sessionId)) return AuthReply.status("EXPIRED", "");
            if (reply.status().equals("AUTHENTICATED")) acceptAuthentication(player.get(), state.sessions().get(connection).orElseThrow(), reply);
            return reply;
        });
    }

    private void acceptAuthentication(Player player, ConnectionSessions.Session session, AuthReply reply) {
        RuntimeState state = runtime;
        if (state == null || !reply.status().equals("AUTHENTICATED")
            || !state.sessions().isCurrent(player.getUniqueId(), session.id())) return;
        authenticated.add(player.getUniqueId());
        proxy.getServer(destinationFor(player, state.config()))
            .ifPresent(server -> player.createConnectionRequest(server).fireAndForget());
    }

    private void refreshSnapshot() {
        RuntimeState state = runtime;
        if (state == null) return;
        AuthRequest snapshot = new AuthRequest(
            "snapshot", UUID.randomUUID().toString(), "system", "127.0.0.1", "", "", false, false);
        state.backend().call(snapshot).thenAccept(reply -> {
            if (!reply.status().equals("SNAPSHOT")) return;
            state.admission().replaceGreen(reply.green());
            premiumNames = Set.copyOf(new HashSet<>(reply.premiumNames()));
        }).exceptionally(error -> {
            logger.warn("Selene snapshot refresh failed: {}", error.getClass().getSimpleName());
            return null;
        });
    }

    private String destinationFor(Player player, VelocityConfig config) {
        if (config.restoreLastServer()) {
            String previous = lastServers.get(player.getUsername().toLowerCase(Locale.ROOT));
            if (previous != null && proxy.getServer(previous).isPresent()) return previous;
        }
        return config.defaultServer();
    }

    private Component text(String key) {
        RuntimeState state = runtime;
        return Component.text(state == null ? "Authentication unavailable" : state.config().message(key));
    }

    private static AuthRequest boundRequest(
        String operation, ConnectionSessions.Session session, String password, String answer
    ) {
        return new AuthRequest(
            operation, session.id().toString(), session.username(), session.address().getHostAddress(),
            password, answer, session.premium(), session.captchaRequired());
    }

    private ForkCapabilities findForkCapabilities() {
        return ServiceLoader.load(ForkCapabilities.class, getClass().getClassLoader())
            .findFirst().orElseGet(() -> new ForkCapabilities() {});
    }

    private static AttackLevel currentLevel(RuntimeState state, long now) {
        if (state.config().forkEnabled()) return state.fork().attackLevel();
        return state.config().autoAttack() ? state.attacks().level(now) : state.config().attackLevel();
    }

    private void installDefaultConfig(Path file) throws IOException {
        if (Files.exists(file)) return;
        try (var source = getClass().getResourceAsStream("/velocity.properties")) {
            if (source == null) throw new IOException("Bundled Velocity config is missing");
            Files.copy(source, file);
        }
    }

    private record RuntimeState(
        VelocityConfig config,
        AuthGateway backend,
        AdmissionPolicy admission,
        AutoAttackController attacks,
        ConnectionSessions sessions,
        RelayServer relay,
        ForkCapabilities fork
    ) {}
}
