package ru.moonlightproject.dev.selene.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.nio.ByteBuffer;
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
    private static final MinecraftChannelIdentifier SESSION_CHANNEL =
        MinecraftChannelIdentifier.create("selene", "session");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> acknowledgedSessionTokens = ConcurrentHashMap.newKeySet();
    private final Map<String, String> lastServers = new ConcurrentHashMap<>();
    private volatile Set<String> premiumNames = Set.of();
    private volatile RuntimeState runtime;
    private final java.util.concurrent.atomic.AtomicReference<Boolean> backendAvailable=new java.util.concurrent.atomic.AtomicReference<>();

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
                VelocityConfiguration files = VelocityConfiguration.load(dataDirectory);
                VelocityConfig config = files.config();
                VelocityMessages messages = new VelocityMessages(files.languages());
                if(files.banner())for(String line:ru.moonlightproject.dev.selene.core.StartupBanner.lines("0.1.0-dev", "Velocity"))logger.info(line);
                logger.info(messages.plain("console-starting"));
                ForkCapabilities fork = findForkCapabilities();
                if (config.forkEnabled() && !fork.supportsEarlyAdmission()) {
                    throw new IllegalStateException("fork-enabled requires a compatible Selene fork API provider");
                }
                SSLContext tls = TlsContexts.load(
                    config.keyStore(), config.keyStorePasswordEnv(),
                    config.trustStore(), config.trustStorePasswordEnv());
                BackendGateway backend = BackendGateway.connect(
                    config.backendEndpoint(), tls, config.allowLoopbackPlaintext(), Duration.ofSeconds(3));
                AdmissionPolicy admission = new AdmissionPolicy(
                    config.sessionCapacity(), config.perIpLimit(), config.perIpWindow());
                AutoAttackController attacks = new AutoAttackController(
                    config.sessionCapacity(), config.attackWindow(), config.attackRecovery(),
                    config.lowConnections(), config.mediumConnections(), config.strongConnections(),
                    config.suspiciousIpConnections(), config.suspiciousIpUsernames(),
                    config.failureMinimumSamples(),config.failureRatioLowPercent(),config.failureRatioMediumPercent(),
                    config.pendingSessionsMedium(),config.pendingSessionsStrong(),config.outboundPacketsLow(),
                    config.outboundPacketsMedium(),config.outboundPacketsStrong(),config.outboundBytesLow(),
                    config.outboundBytesMedium(),config.outboundBytesStrong());
                ConnectionSessions sessions = new ConnectionSessions(config.sessionCapacity());
                ConnectionIdentities<Player> identities = new ConnectionIdentities<>(config.sessionCapacity());
                PremiumLookup premiumLookup = PremiumLookup.mojang(
                    config.premiumLookupCacheCapacity(), config.premiumLookupConcurrency(),
                    config.premiumLookupCacheTtl(), config.premiumLookupTimeout());
                RelayServer relay = new RelayServer(
                    tls, config.relayBind(), Duration.ofSeconds(3), 64,
                    (request, fingerprint) -> relay(request, fingerprint));
                runtime = new RuntimeState(
                    config, messages, backend, admission, attacks, sessions, identities, premiumLookup, relay, fork, new ProtectionState(
                        change -> logger.info(messages.plain("protection-change",
                            VelocityMessages.literal("previous",change.previous().name()),VelocityMessages.literal("current",change.current().name()),
                            VelocityMessages.literal("source",change.source()),VelocityMessages.literal("reason",change.reason()))),
                        () -> logger.warn(messages.plain("protection-pending"))));
                proxy.getChannelRegistrar().register(SESSION_CHANNEL);
                proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("selene").plugin(this).build(),new AccountAdminCommand(backend,messages,logger, this::protectionCommand));
                currentLevel(runtime, System.currentTimeMillis());
                proxy.getScheduler().buildTask(this, () -> {
                    RuntimeState active = runtime;
                    if (active != null) currentLevel(active, System.currentTimeMillis());
                }).repeat(1, TimeUnit.SECONDS).schedule();
                refreshSnapshot();
                proxy.getScheduler().buildTask(this, this::refreshSnapshot)
                    .repeat(30, TimeUnit.SECONDS).schedule();
                logger.info(messages.plain("console-ready",VelocityMessages.literal("auth",config.authServer()),VelocityMessages.literal("target",config.defaultServer()),VelocityMessages.literal("current",runtime.protection().level().name())));
            } catch (Exception error) {
                String reason=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
                logger.error("Selene startup failed: {}. Check plugins/selene/config.yml, connection.yml, attack.yml and premium.yml; the proxy will stop.",reason);
                logger.debug("Selene startup failure details",error);
                proxy.shutdown(Component.text("Selene authentication is unavailable"));
            }
        });
    }

    @Subscribe
    public EventTask preLogin(PreLoginEvent event) {
        RuntimeState state = runtime;
        if (state == null) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(text("backend-unavailable")));
            return null;
        }
        String ip = event.getConnection().getRemoteAddress().getAddress().getHostAddress();
        String username = event.getUsername();
        long now = System.currentTimeMillis();
        state.attacks().observe(ip, username, now);
        AttackLevel level = currentLevel(state, now);
        AttackProfile profile=state.config().attackProfiles().get(level);
        boolean suspicious = state.fork().suspicious(username, ip) || state.attacks().suspicious(ip, now);
        if (profile.greenlistOnly() && !state.admission().isGreen(username, ip)) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(text("strong")));
            return null;
        }
        if (profile.reconnectLimit() && !state.admission().admit(AttackLevel.MEDIUM, ip, now)) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(text("busy")));
            return null;
        }
        if (premiumNames.contains(username.toLowerCase(Locale.ROOT))) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
            return null;
        }
        AuthRequest profileRequest = new AuthRequest(
            "profile", UUID.randomUUID().toString(), username, ip, "", "", false, false);
        return EventTask.resumeWhenComplete(state.backend().call(profileRequest).thenCompose(reply -> switch (reply.status()) {
            case "ACCOUNT_PREMIUM" -> CompletableFuture.completedFuture(true);
            case "ACCOUNT_OFFLINE" -> CompletableFuture.completedFuture(false);
            case "ACCOUNT_NEW" -> state.config().premiumLookupEnabled()
                ? state.premiumLookup().hasProfile(username, now)
                : CompletableFuture.completedFuture(false);
            default -> CompletableFuture.failedFuture(
                new IllegalStateException("Unexpected profile status: " + reply.status()));
        }).handle((premium, error) -> {
            if (error != null) event.setResult(
                PreLoginEvent.PreLoginComponentResult.denied(text("premium-lookup-unavailable")));
            else if (premium) event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
            return null;
        }));
    }

    @Subscribe
    public void postLogin(PostLoginEvent event) {
        RuntimeState state = runtime;
        if (state == null) return;
        Player player = event.getPlayer();
        String ip = player.getRemoteAddress().getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        AttackLevel level = currentLevel(state, now);
        AttackProfile profile=state.config().attackProfiles().get(level);
        boolean suspicious = state.fork().suspicious(player.getUsername(), ip)
            || state.attacks().suspicious(ip, now);
        boolean captcha = profile.captcha(player.isOnlineMode(), suspicious);
        ConnectionSessions.Session session;
        try {
            UUID connectionId = state.identities().open(player);
            ConnectionSessions.Opened opened = state.sessions().open(
                connectionId, player.getUsername(), player.getRemoteAddress().getAddress(),
                player.isOnlineMode(), captcha);
            session = opened.session();
            if (opened.replaced() != null) closeReplacedSession(state, opened.replaced());
        } catch (IllegalStateException full) {
            player.disconnect(text(player,"busy"));
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
        boolean authenticated=isAuthenticated(event.getPlayer());
        String destination = AuthenticationRoute.initial(authenticated,state.config().authServer(),destinationFor(event.getPlayer(),state.config()));
        proxy.getServer(destination).ifPresent(event::setInitialServer);
    }

    @Subscribe
    public void preConnect(ServerPreConnectEvent event) {
        RuntimeState state = runtime;
        if (state == null) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }
        boolean loggedIn = isAuthenticated(event.getPlayer());
        String target = event.getOriginalServer().getServerInfo().getName();
        if (!AuthenticationRoute.mayConnect(loggedIn,target,state.config().authServer())) {
            event.getPlayer().sendMessage(text(event.getPlayer(),"auth-required"));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    @Subscribe
    public void connected(ServerConnectedEvent event) {
        RuntimeState state = runtime;
        if (state == null) return;
        String server = event.getServer().getServerInfo().getName();
        if (server.equals(state.config().authServer()) && !isAuthenticated(event.getPlayer())) return;
        if (!isAuthenticated(event.getPlayer())) return;
        if (!server.equals(state.config().authServer()) && lastServers.size() < state.config().sessionCapacity()) {
            lastServers.put(event.getPlayer().getUsername().toLowerCase(Locale.ROOT), server);
        }
    }

    @Subscribe
    public void postConnected(ServerPostConnectEvent event) {
        RuntimeState state = runtime;
        if (state == null || isAuthenticated(event.getPlayer())) return;
        event.getPlayer().getCurrentServer()
            .filter(connection -> connection.getServerInfo().getName().equals(state.config().authServer()))
            .ifPresent(connection -> state.identities().get(event.getPlayer())
                .flatMap(state.sessions()::get)
                .ifPresent(session -> deliverSessionToken(event.getPlayer(), connection, session, 0)));
    }

    @Subscribe
    public void pluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(SESSION_CHANNEL)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        RuntimeState state = runtime;
        if (state == null || event.getData().length != 16 || !(event.getSource() instanceof ServerConnection source)
            || !source.getServerInfo().getName().equals(state.config().authServer())) return;
        UUID token = uuid(event.getData());
        state.identities().get(source.getPlayer())
            .flatMap(state.sessions()::get)
            .filter(session -> session.id().equals(token))
            .ifPresent(session -> acknowledgedSessionTokens.add(token));
    }

    @Subscribe
    public void disconnect(DisconnectEvent event) {
        RuntimeState state = runtime;
        if (state == null) return;
        Optional<UUID> connection = state.identities().get(event.getPlayer());
        connection.ifPresent(connectionId -> state.sessions().get(connectionId).ifPresent(session -> {
            acknowledgedSessionTokens.remove(session.id());
            if (authenticated.remove(connectionId)) {
                state.backend().call(boundRequest("trust", session, "", ""))
                    .handle((ignored, error) -> null)
                    .thenCompose(ignored -> state.backend().call(boundRequest("close", session, "", "")));
            } else {
                state.backend().call(boundRequest("close", session, "", ""));
            }
            state.sessions().close(connectionId);
        }));
        state.identities().close(event.getPlayer());
    }

    @Subscribe
    public void shutdown(ProxyShutdownEvent ignored) {
        RuntimeState state = runtime;
        runtime = null;
        proxy.getChannelRegistrar().unregister(SESSION_CHANNEL);
        proxy.getCommandManager().unregister("selene");
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
        Optional<UUID> connectionIdentity = state.identities().get(player.get());
        if (connectionIdentity.isEmpty()) return CompletableFuture.completedFuture(AuthReply.status("EXPIRED", ""));
        UUID connection = connectionIdentity.get();
        Optional<AuthRequest> bound = state.sessions().authorizeRelay(connection, untrusted);
        if (bound.isEmpty()) return CompletableFuture.completedFuture(AuthReply.status("INVALID_REQUEST", ""));
        UUID sessionId = state.sessions().get(connection).orElseThrow().id();
        return state.backend().call(bound.get()).thenApply(reply -> {
            if (!state.sessions().observeReply(connection, sessionId, reply)) {
                return AuthReply.status("EXPIRED", "");
            }
            if(Set.of("AUTHENTICATED","CAPTCHA_INVALID","INVALID_CREDENTIALS","RATE_LIMITED").contains(reply.status()))
                state.attacks().observeAuthentication(!reply.status().equals("AUTHENTICATED"),System.currentTimeMillis());
            if (reply.status().equals("AUTHENTICATED")) acceptAuthentication(player.get(), state.sessions().get(connection).orElseThrow(), reply);
            return reply;
        });
    }

    private void acceptAuthentication(Player player, ConnectionSessions.Session session, AuthReply reply) {
        RuntimeState state = runtime;
        Optional<UUID> connection = state == null ? Optional.empty() : state.identities().get(player);
        if (state == null || !reply.status().equals("AUTHENTICATED") || connection.isEmpty()
            || !state.sessions().isCurrent(connection.get(), session.id())) return;
        authenticated.add(connection.get());
        proxy.getServer(destinationFor(player, state.config()))
            .ifPresent(server -> player.createConnectionRequest(server).fireAndForget());
    }

    private void closeReplacedSession(RuntimeState state, ConnectionSessions.Session replaced) {
        acknowledgedSessionTokens.remove(replaced.id());
        if (authenticated.remove(replaced.connectionId())) {
            state.backend().call(boundRequest("trust", replaced, "", ""))
                .handle((ignored, error) -> null)
                .thenCompose(ignored -> state.backend().call(boundRequest("close", replaced, "", "")));
        } else {
            state.backend().call(boundRequest("close", replaced, "", ""));
        }
    }

    private void deliverSessionToken(
        Player player,
        ServerConnection connection,
        ConnectionSessions.Session session,
        int attempt
    ) {
        RuntimeState state = runtime;
        if (state == null) return;
        boolean current = state.identities().get(player)
            .filter(connectionId -> state.sessions().isCurrent(connectionId, session.id()))
            .isPresent();
        SessionTokenDelivery.Outcome outcome = SessionTokenDelivery.attempt(
            state.config().authServer(),
            current,
            acknowledgedSessionTokens.contains(session.id()),
            attempt,
            4,
            new SessionTokenDelivery.Channel() {
                @Override
                public String serverName() {
                    return connection.getServerInfo().getName();
                }

                @Override
                public boolean send(byte[] payload) {
                    return connection.sendPluginMessage(SESSION_CHANNEL, payload);
                }
            },
            tokenBytes(session.id())
        );
        if (outcome == SessionTokenDelivery.Outcome.IGNORED) return;
        if (outcome == SessionTokenDelivery.Outcome.EXHAUSTED) {
            player.disconnect(text(player,"session-channel-unavailable"));
            return;
        }
        proxy.getScheduler().buildTask(this, () -> deliverSessionToken(player, connection, session, attempt + 1))
            .delay(250, TimeUnit.MILLISECONDS).schedule();
    }

    private static byte[] tokenBytes(UUID token) {
        return ByteBuffer.allocate(16)
            .putLong(token.getMostSignificantBits()).putLong(token.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private void refreshSnapshot() {
        RuntimeState state = runtime;
        if (state == null) return;
        AuthRequest snapshot = new AuthRequest(
            "snapshot", UUID.randomUUID().toString(), "system", "127.0.0.1", "", "", false, false);
        state.backend().call(snapshot).thenAccept(reply -> {
            if (!reply.status().equals("SNAPSHOT")) throw new IllegalStateException("Snapshot unavailable");
            if(!Boolean.TRUE.equals(backendAvailable.getAndSet(true)))logger.info(state.messages().plain("console-backend-ready"));
            state.admission().replaceGreen(reply.green());
            premiumNames = Set.copyOf(new HashSet<>(reply.premiumNames()));
        }).exceptionally(error -> {
            if(!Boolean.FALSE.equals(backendAvailable.getAndSet(false)))logger.warn(state.messages().plain("console-backend-offline"));
            logger.debug("Snapshot failure details",error);
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

    private boolean isAuthenticated(Player player) {
        RuntimeState state = runtime;
        return state != null && state.identities().get(player).filter(authenticated::contains).isPresent();
    }

    private Component text(String key) {
        RuntimeState state = runtime;
        return state == null ? Component.text("Authentication unavailable") : state.messages().component(key);
    }

    private Component text(Player player, String key) {
        RuntimeState state=runtime;
        if(state==null)return Component.text("Authentication unavailable");
        java.util.Locale locale=player.getPlayerSettings().getLocale();
        return state.messages().forLocale(locale==null?null:locale.toLanguageTag()).component(key);
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

    private AttackLevel currentLevel(RuntimeState state, long now) {
        synchronized (state.protection()) {
            AttackLevel desired = state.config().attackLevel();
            String source = "конфигурация";
            String reason = "заданный минимальный уровень";
            int pending=Math.max(0,state.sessions().size()-authenticated.size());
            if (state.config().autoAttack()) {
                var traffic=state.config().forkEnabled()?state.fork().protectionSignals():ru.moonlightproject.dev.selene.core.ProtectionSignals.UNAVAILABLE;
                AttackLevel detected = state.attacks().level(now,pending,traffic);
                if (detected.ordinal() >= desired.ordinal()) {
                    desired = detected; source = "автоматика";
                    reason = state.attacks().explanation(now);
                }
            }
            if (state.config().forkEnabled()) {
                AttackLevel detected = state.fork().attackLevel();
                if (detected.ordinal() >= desired.ordinal()) {
                    desired = detected; source = "ядро"; reason = "сигнал защиты ядра";
                }
            }
            return state.protection().update(desired, source, reason, now);
        }
    }

    private void protectionCommand(com.velocitypowered.api.command.SimpleCommand.Invocation invocation) {
        RuntimeState state = runtime;
        if (state == null) {
            invocation.source().sendMessage(Component.text("Selene ещё не готова."));
            return;
        }
        synchronized (state.protection()) {
            currentLevel(state, System.currentTimeMillis());
            String[] args = invocation.arguments();
            Component reply;
            if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
                reply = state.messages().component(state.protection().confirm()?"protection-confirmed":"protection-no-request");
            } else if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
                reply = state.messages().component("protection-status",VelocityMessages.literal("current",state.protection().level().name()),
                    VelocityMessages.literal("pending",Boolean.toString(state.protection().pending())));
            } else reply=state.messages().component("protection-help");
            invocation.source().sendMessage(reply);
        }
    }

    private record RuntimeState(
        VelocityConfig config,
        VelocityMessages messages,
        AuthGateway backend,
        AdmissionPolicy admission,
        AutoAttackController attacks,
        ConnectionSessions sessions,
        ConnectionIdentities<Player> identities,
        PremiumLookup premiumLookup,
        RelayServer relay,
        ForkCapabilities fork,
        ProtectionState protection
    ) {}
}
