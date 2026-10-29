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
import ru.moonlightproject.dev.selene.core.MaintenanceCodec;
import ru.moonlightproject.dev.selene.core.MaintenanceMessage;
import ru.moonlightproject.dev.selene.api.MaintenanceState;
import ru.moonlightproject.dev.selene.api.SeleneApi;

@Plugin(
    id = "selene",
    name = "Selene",
    version = "0.1.0-dev",
    description = "Authentication and adaptive admission for Velocity",
    url = "https://dev.moonlightproject.ru",
    authors = {"~VicTim~"}
)
public final class SeleneVelocityPlugin implements SeleneApi {
    private static final MinecraftChannelIdentifier SESSION_CHANNEL =
        MinecraftChannelIdentifier.create("selene", "session");
    private static final MinecraftChannelIdentifier MANAGEMENT_CHANNEL =
        MinecraftChannelIdentifier.create("selene", "management");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final MaintenanceController maintenance;
    private final WhitelistRegistry maintenanceWhitelists = new WhitelistRegistry();
    private final Map<String, Long> maintenanceAcknowledgements = new ConcurrentHashMap<>();
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> onlineProofConnections = ConcurrentHashMap.newKeySet();
    private final Set<String> trustedPremiumAdmissions = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> maintenanceAdmissions = new ConcurrentHashMap<>();
    private final Set<UUID> acknowledgedSessionTokens = ConcurrentHashMap.newKeySet();
    private final Map<String, String> lastServers = new ConcurrentHashMap<>();
    private volatile Set<String> premiumNames = Set.of();
    private volatile RuntimeState runtime;
    private volatile VelocityTracing tracing;
    private volatile PrometheusExporter prometheus;
    private final java.util.concurrent.atomic.AtomicReference<Boolean> backendAvailable=new java.util.concurrent.atomic.AtomicReference<>();

    @Inject
    public SeleneVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.maintenance = new MaintenanceController(System::currentTimeMillis, state -> {
            logger.warn(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[!] maintenance » enabled:{}  revision:{}  actor:{}  reason:{}"),
                state.enabled(), state.revision(), state.actor(), state.reason());
            broadcastMaintenance(state);
        });
    }

    @Override public MaintenanceState maintenance() { return maintenance.maintenance(); }

    private java.util.concurrent.CompletableFuture<ru.moonlightproject.dev.selene.core.AdminReply> adminCall(String op, String name) {
        RuntimeState state = runtime;
        if (state == null || !(state.backend() instanceof ru.moonlightproject.dev.selene.core.AccountAdminGateway admin)) {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("Selene backend is not connected"));
        }
        return admin.admin(new ru.moonlightproject.dev.selene.core.AdminRequest(op, name, 0, 0));
    }

    @Override public java.util.concurrent.CompletionStage<Optional<ru.moonlightproject.dev.selene.api.AccountView>> account(String name) {
        return adminCall("info", name).thenApply(reply -> {
            var account = reply.account();
            if (!reply.status().equals("OK") || account == null || account.name().isEmpty()) return Optional.empty();
            return Optional.of(new ru.moonlightproject.dev.selene.api.AccountView(account.name(), account.premium(),
                account.trustedIp(), account.trustedUntil() > 0 ? java.time.Instant.ofEpochSecond(account.trustedUntil()) : null,
                account.passwordReset(), account.totpEnabled()));
        });
    }

    @Override public java.util.concurrent.CompletionStage<Boolean> resetPassword(String name) {
        return adminCall("resetPassword", name).thenApply(reply -> reply.status().equals("OK"));
    }

    @Override public java.util.concurrent.CompletionStage<Boolean> resetTotp(String name) {
        return adminCall("resetTotp", name).thenApply(reply -> reply.status().equals("OK"));
    }

    @Override public java.util.concurrent.CompletionStage<Boolean> deleteAccount(String name) {
        return adminCall("delete", name).thenApply(reply -> reply.status().equals("OK"));
    }
    @Override public java.util.concurrent.CompletionStage<MaintenanceState> setMaintenance(
        boolean enabled, String reason, String actor) {
        return maintenance.setMaintenance(enabled, reason, actor);
    }

    @Subscribe
    public EventTask initialize(ProxyInitializeEvent ignored) {
        // Compile the JSON codec before the first player connects; a proxy with few logins
        // would otherwise keep interpreting it for a few hundred microseconds per call.
        Thread.ofPlatform().daemon().priority(Thread.MIN_PRIORITY).name("selene-codec-warmup")
            .start(ru.moonlightproject.dev.selene.core.JsonCodec::warmUp);
        return EventTask.async(() -> {
            try {
                Files.createDirectories(dataDirectory);
                VelocityConfiguration files = VelocityConfiguration.load(dataDirectory);
                VelocityConfig config = files.config();
                VelocityMessages messages = new VelocityMessages(files.languages());
                if(files.banner())for(String line:ru.moonlightproject.dev.selene.core.StartupBanner.lines("0.1.0-dev", "Velocity"))logger.info(line);
                logger.info(messages.plain("console-starting"));
                ForkCapabilities fork = findForkCapabilities();
                if (config.forkRequired() && !fork.supportsEarlyAdmission()) {
                    throw new IllegalStateException("fork-enabled requires a compatible Selene fork API provider");
                }
                if (config.forkEnabled() && fork.supportsEarlyAdmission()) {
                    logger.info(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[*] core     » " + fork.name()));
                    if (fork.ownsAttackDetection()) {
                        logger.info(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[~] owners   » attack/limits/ip=" + fork.name() + "  captcha/password/2fa=Selene"));
                        logger.info(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[~] signals  » auth failures/pending ──▶ " + fork.name()));
                        logger.info(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[-] skip     » attack.yml: attack-auto attack-window/recovery"
                            + " attack-*, suspicious-ip-*, failure-*, pending-sessions-*, outbound-*, per-ip-limit, per-ip-window-seconds"));
                    }
                }
                SSLContext tls = TlsContexts.load(
                    config.keyStore(), config.keyStorePasswordEnv(),
                    config.trustStore(), config.trustStorePasswordEnv());
                BackendGateway backend = BackendGateway.connect(
                    config.backendEndpoint(), tls, config.allowLoopbackPlaintext(), Duration.ofSeconds(3));
                if (config.otelEnabled()) {
                    tracing = VelocityTracing.start(config.otlpEndpoint(), config.otelServiceName());
                    backend.scope(tracing);
                    logger.info(messages.plain("console-otel-enabled",
                        VelocityMessages.literal("endpoint", config.otlpEndpoint()),
                        VelocityMessages.literal("service", config.otelServiceName())));
                }
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
                AuthenticationGraceCache authenticationGrace = new AuthenticationGraceCache(config.sessionCapacity());
                TwoFactorRoutes twoFactorRoutes = new TwoFactorRoutes(config.sessionCapacity());
                ConnectionIdentities<Player> identities = new ConnectionIdentities<>(config.sessionCapacity());
                PremiumLookup premiumLookup = PremiumLookup.mojang(
                    config.premiumLookupCacheCapacity(), config.premiumLookupConcurrency(),
                    config.premiumLookupCacheTtl(), config.premiumLookupTimeout());
                PremiumTrustCache premiumTrust = new PremiumTrustCache(
                    config.premiumTrustCapacity(), config.premiumTrustSnapshotMaxAge(),
                    config.premiumTrustTtl(), config.premiumTrustEnabled());
                RelayServer relay = new RelayServer(
                    tls, config.relayBind(), Duration.ofSeconds(3), 64,
                    (request, fingerprint) -> relay(request, fingerprint));
                runtime = new RuntimeState(
                    config, messages, backend, admission, attacks, sessions, authenticationGrace, identities, premiumLookup,
                    premiumTrust, twoFactorRoutes, relay, fork, files.maintenance(), new ProtectionState(
                        change -> logger.info(messages.plain("protection-change",
                            VelocityMessages.literal("previous",change.previous().name()),VelocityMessages.literal("current",change.current().name()),
                            VelocityMessages.literal("source",change.source()),VelocityMessages.literal("reason",change.reason()))),
                        () -> logger.warn(messages.plain("protection-pending"))));
                proxy.getChannelRegistrar().register(SESSION_CHANNEL);
                proxy.getChannelRegistrar().register(MANAGEMENT_CHANNEL);
                proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("selene").plugin(this).build(),
                    new SeleneCommand(backend,()->runtime==null?messages:runtime.messages(),logger,new CommandOperations(),this::isAuthenticated,
                        ()->proxy.getAllPlayers().stream().map(Player::getUsername),
                        player->config.commandAdmins().contains(player.getUsername().toLowerCase(Locale.ROOT))
                            || maintenanceCommandAccess(player)));
                String[] twoFactorAliases = java.util.Arrays.stream(config.twoFactorCommandAliases().split(","))
                    .map(String::trim).filter(alias -> alias.matches("[a-zA-Z0-9_-]{1,24}"))
                    .distinct().toArray(String[]::new);
                if (twoFactorAliases.length == 0) throw new IllegalArgumentException("two-factor-command-aliases has no valid aliases");
                var commandMeta = proxy.getCommandManager().metaBuilder(twoFactorAliases[0]).plugin(this);
                if (twoFactorAliases.length > 1) commandMeta.aliases(java.util.Arrays.copyOfRange(twoFactorAliases, 1, twoFactorAliases.length));
                proxy.getCommandManager().register(commandMeta.build(), new TwoFactorCommand(messages, this::beginTwoFactor));
                currentLevel(runtime, System.currentTimeMillis());
                if (config.prometheusEnabled()) {
                    prometheus = new PrometheusExporter(config.prometheusBind(), this::renderMetrics);
                    logger.info(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[*] prom     » http://" + config.prometheusBind() + "/metrics"));
                }
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
                logger.error(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[x] init     » startup aborted: {}  proxy:shutdown"),reason);
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
        MaintenanceState maintenanceState = maintenance.maintenance();
        if (maintenanceState.enabled()) {
            AuthRequest request = new AuthRequest(
                "profile", UUID.randomUUID().toString(), username, ip, "", "", false, false);
            return EventTask.resumeWhenComplete(state.backend().call(request).handle((reply, error) -> {
                if (error != null || reply == null) {
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(text("backend-unavailable")));
                    return null;
                }
                boolean premium = reply.status().equals("ACCOUNT_PREMIUM");
                boolean known = premium || reply.status().equals("ACCOUNT_OFFLINE");
                MaintenanceAdmission.Decision decision = MaintenanceAdmission.decide(
                    state.maintenancePolicy(), maintenanceWhitelists.contains(username), known,
                    state.admission().isGreen(username, ip), premium);
                if (!decision.allowed()) {
                    logger.warn(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[-] maintenance » player:{}  reason:{}  revision:{}"),
                        username.replaceAll("[^A-Za-z0-9_]", "?"), decision.reason(), maintenanceState.revision());
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        Component.text("Server is in maintenance mode")));
                } else {
                    maintenanceAdmissions.put(premiumAdmissionKey(username, ip), maintenanceState.revision());
                    event.setResult(premium
                        ? PreLoginEvent.PreLoginComponentResult.forceOnlineMode()
                        : PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
                }
                return null;
            }));
        }
        state.attacks().observe(ip, username, now);
        AttackLevel level = currentLevel(state, now);
        AttackProfile profile=state.config().attackProfiles().get(level);
        boolean suspicious = state.fork().suspicious(username, ip)
            || (!coreOwnsAttack(state) && state.attacks().suspicious(ip, now));
        if (profile.greenlistOnly() && !state.admission().isGreen(username, ip)) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(text("strong")));
            return null;
        }
        // With ThirdVelocity the per-IP connection limits are the core's (protection.yml).
        if (profile.reconnectLimit() && !coreOwnsAttack(state) && !state.admission().admit(AttackLevel.MEDIUM, ip, now)) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(text("busy")));
            return null;
        }
        if (state.premiumTrust().trusted(username, event.getConnection().getRemoteAddress().getAddress(),
            now / 1000, now)) {
            trustedPremiumAdmissions.add(premiumAdmissionKey(username, ip));
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
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
        boolean trustedPremium = trustedPremiumAdmissions.remove(premiumAdmissionKey(player.getUsername(), ip));
        long now = System.currentTimeMillis();
        AttackLevel level = currentLevel(state, now);
        AttackProfile profile=state.config().attackProfiles().get(level);
        boolean suspicious = state.fork().suspicious(player.getUsername(), ip)
            || (!coreOwnsAttack(state) && state.attacks().suspicious(ip, now));
        boolean premium = player.isOnlineMode() || trustedPremium;
        AuthenticationGraceCache.Decision grace=state.authenticationGrace().decision(
            player.getUsername(),player.getRemoteAddress().getAddress(),profile);
        MaintenanceState activeMaintenance = maintenance.maintenance();
        boolean maintenanceSession = activeMaintenance.enabled();
        Long admittedRevision = maintenanceAdmissions.remove(premiumAdmissionKey(player.getUsername(), ip));
        if (maintenanceSession && (admittedRevision == null || admittedRevision != activeMaintenance.revision())) {
            player.disconnect(Component.text("Server is in maintenance mode"));
            return;
        }
        boolean captcha = maintenanceSession
            ? state.maintenancePolicy().enableCaptcha()
            : profile.captcha(premium, suspicious) && !grace.captcha();
        ConnectionSessions.Session session;
        try {
            UUID connectionId = state.identities().open(player);
            MaintenanceState maintenanceState = activeMaintenance;
            MaintenanceMessage.Policy sessionPolicy = maintenanceState.enabled()
                ? wirePolicy(state.maintenancePolicy())
                : new MaintenanceMessage.Policy(false,false,true,true,true,true,true,true,false);
            ConnectionSessions.Opened opened = state.sessions().open(
                connectionId, player.getUsername(), player.getRemoteAddress().getAddress(),
                premium, captcha,
                !maintenanceSession && !premium && grace.password(),
                !maintenanceSession && grace.totp(),
                maintenanceState.enabled() ? maintenanceState.revision() : 0, sessionPolicy);
            session = opened.session();
            if (player.isOnlineMode()) onlineProofConnections.add(connectionId);
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

    /**
     * In offline mode permissions are keyed by a UUID derived from the name, so anyone using an
     * admin's name would inherit the admin's proxy permissions before typing a password. Until
     * Selene authentication finishes every permission check answers FALSE.
     */
    @Subscribe(order = com.velocitypowered.api.event.PostOrder.LAST)
    public void permissionsSetup(com.velocitypowered.api.event.permission.PermissionsSetupEvent event) {
        if (!(event.getSubject() instanceof Player player)) return;
        com.velocitypowered.api.permission.PermissionProvider original = event.getProvider();
        event.setProvider(subject -> {
            com.velocitypowered.api.permission.PermissionFunction delegate = original.createFunction(subject);
            return permission -> isAuthenticated(player)
                ? delegate.getPermissionValue(permission)
                : com.velocitypowered.api.permission.Tristate.FALSE;
        });
    }

    /**
     * Proxy commands of unauthenticated players are never executed on the proxy; they are only
     * forwarded to the auth server, where /login and /register live.
     */
    @Subscribe(order = com.velocitypowered.api.event.PostOrder.FIRST)
    public void commandExecute(com.velocitypowered.api.event.command.CommandExecuteEvent event) {
        if (event.getCommandSource() instanceof Player player && runtime != null && !isAuthenticated(player)) {
            event.setResult(com.velocitypowered.api.event.command.CommandExecuteEvent.CommandResult.forwardToServer());
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
        boolean managing = state.identities().get(event.getPlayer()).flatMap(state.twoFactorRoutes()::get).isPresent();
        String target = event.getOriginalServer().getServerInfo().getName();
        if (!AuthenticationRoute.mayConnect(loggedIn,managing,target,state.config().authServer())) {
            event.getPlayer().sendMessage(text(event.getPlayer(),loggedIn?"auth-already-done":"auth-required"));
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
        if (state == null) return;
        event.getPlayer().getCurrentServer()
            .filter(connection -> connection.getServerInfo().getName().equals(state.config().authServer()))
            .ifPresent(connection -> state.identities().get(event.getPlayer())
                .flatMap(state.sessions()::get)
                .ifPresent(session -> {
                    deliverSessionToken(event.getPlayer(), connection, session, 0);
                }));
    }

    @Subscribe
    public void pluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(SESSION_CHANNEL)
            && !event.getIdentifier().equals(MANAGEMENT_CHANNEL)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        RuntimeState state = runtime;
        if (state == null || !(event.getSource() instanceof ServerConnection source)) return;
        if (event.getIdentifier().equals(MANAGEMENT_CHANNEL) && event.getData().length != 16) {
            receiveMaintenance(source, event.getData());
            return;
        }
        if (event.getData().length != 16
            || !source.getServerInfo().getName().equals(state.config().authServer())) return;
        UUID token = uuid(event.getData());
        Optional<UUID> identity = state.identities().get(source.getPlayer());
        identity
            .flatMap(state.sessions()::get)
            .filter(session -> session.id().equals(token))
            .ifPresent(session -> {
                if (event.getIdentifier().equals(SESSION_CHANNEL)) {
                    acknowledgedSessionTokens.add(token);
                    identity.ifPresent(connection -> TwoFactorDelivery.afterSessionAcknowledged(
                        state.twoFactorRoutes().get(connection), token,
                        (sessionToken, action) -> source.sendPluginMessage(
                            MANAGEMENT_CHANNEL, managementBytes(sessionToken, action))));
                }
                else identity.ifPresent(connection -> finishTwoFactor(source.getPlayer(), connection));
            });
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
            onlineProofConnections.remove(connectionId);
            state.twoFactorRoutes().clear(connectionId);
        }));
        state.identities().close(event.getPlayer());
    }

    @Subscribe
    public void shutdown(ProxyShutdownEvent ignored) {
        RuntimeState state = runtime;
        runtime = null;
        proxy.getChannelRegistrar().unregister(SESSION_CHANNEL);
        proxy.getChannelRegistrar().unregister(MANAGEMENT_CHANNEL);
        proxy.getCommandManager().unregister("selene");
        if (state != null) {
            state.relay().close();
            state.backend().close();
        }
        PrometheusExporter exporter = prometheus;
        prometheus = null;
        if (exporter != null) exporter.close();
        VelocityTracing spans = tracing;
        tracing = null;
        if (spans != null) spans.close();
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
            if(Set.of("AUTHENTICATED","CAPTCHA_INVALID","INVALID_CREDENTIALS","RATE_LIMITED").contains(reply.status())) {
                state.attacks().observeAuthentication(!reply.status().equals("AUTHENTICATED"),System.currentTimeMillis());
                if (state.config().forkEnabled()) {
                    ConnectionSessions.Session current = state.sessions().get(connection).orElse(null);
                    if (current != null) {
                        try {
                            state.fork().onAuthentication(current.username(), current.address(), reply.status());
                        } catch (RuntimeException error) {
                            logger.warn(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[x] core     » auth hook failed: " + error));
                        }
                    }
                }
            }
            if (reply.status().equals("AUTHENTICATED") && state.twoFactorRoutes().get(connection).isEmpty())
                acceptAuthentication(player.get(), state.sessions().get(connection).orElseThrow(), reply);
            return reply;
        });
    }

    private void acceptAuthentication(Player player, ConnectionSessions.Session session, AuthReply reply) {
        RuntimeState state = runtime;
        Optional<UUID> connection = state == null ? Optional.empty() : state.identities().get(player);
        if (state == null || !reply.status().equals("AUTHENTICATED") || connection.isEmpty()
            || !state.sessions().isCurrent(connection.get(), session.id())) return;
        state.authenticationGrace().remember(session.username(),session.address(),
            !session.premium()&&!session.passwordTrusted(),session.captchaRequired(),
            reply.totpEnabled()&&!session.totpTrusted());
        authenticated.add(connection.get());
        if (onlineProofConnections.remove(connection.get()) && session.premium()) {
            state.backend().call(boundRequest("premium_trust", session, "", ""))
                .exceptionally(error -> null);
        }
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

    private static byte[] managementBytes(UUID token, TwoFactorRoutes.Action action) {
        return ByteBuffer.allocate(17).put(tokenBytes(token)).put((byte) action.ordinal()).array();
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
            state.premiumTrust().replace(reply.premiumTrust(), System.currentTimeMillis());
        }).exceptionally(error -> {
            if(!Boolean.FALSE.equals(backendAvailable.getAndSet(false)))logger.warn(state.messages().plain("console-backend-offline"));
            logger.debug("Snapshot failure details",error);
            return null;
        });
    }

    private void receiveMaintenance(ServerConnection source, byte[] payload) {
        String actualServer = source.getServerInfo().getName();
        try {
            MaintenanceMessage message = MaintenanceCodec.decode(payload);
            if (message instanceof MaintenanceMessage.Whitelist whitelist
                && whitelist.serverId().equals(actualServer)) {
                maintenanceWhitelists.replace(actualServer, whitelist.revision(), whitelist.entries());
            } else if (message instanceof MaintenanceMessage.Ack ack
                && ack.serverId().equals(actualServer)
                && ack.revision() == maintenance.maintenance().revision()) {
                maintenanceAcknowledgements.put(actualServer, ack.revision());
            } else return;
            updateMaintenanceTopology();
        } catch (IllegalArgumentException malformed) {
            logger.warn(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[-] maintenance » malformed message ignored: {}"), actualServer);
        }
    }

    private void broadcastMaintenance(MaintenanceState state) {
        RuntimeState active = runtime;
        if (active == null) return;
        maintenanceAcknowledgements.clear();
        byte[] payload = MaintenanceCodec.encode(new MaintenanceMessage.State(
            state.revision(), state.enabled(), wirePolicy(active.maintenancePolicy()), state.actor(), state.reason()));
        for (RegisteredServer server : proxy.getAllServers()) server.sendPluginMessage(MANAGEMENT_CHANNEL, payload);
        updateMaintenanceTopology();
    }

    private void updateMaintenanceTopology() {
        long revision = maintenance.maintenance().revision();
        int acknowledged = (int) maintenanceAcknowledgements.values().stream()
            .filter(value -> value == revision).count();
        maintenance.updateTopology(maintenanceWhitelists.unionSize(), proxy.getAllServers().size(), acknowledged);
    }

    private boolean maintenanceCommandAccess(Player player) {
        RuntimeState state = runtime;
        return state != null && maintenance.maintenance().enabled()
            && state.maintenancePolicy().elevateSeleneCommands() && isAuthenticated(player);
    }

    private static MaintenanceMessage.Policy wirePolicy(MaintenancePolicy policy) {
        return new MaintenanceMessage.Policy(policy.requireTrustedIp(),policy.requirePremium(),
            policy.enableCaptcha(),policy.verifyCaptcha(),policy.enablePassword(),policy.verifyPassword(),
            policy.enableTotp(),policy.verifyTotp(),policy.elevateSeleneCommands());
    }

    private String destinationFor(Player player, VelocityConfig config) {
        if (config.restoreLastServer()) {
            String previous = lastServers.get(player.getUsername().toLowerCase(Locale.ROOT));
            if (previous != null && proxy.getServer(previous).isPresent()) return previous;
        }
        return config.defaultServer();
    }

    @Override public boolean isAuthenticated(Player player) {
        RuntimeState state = runtime;
        return state != null && state.identities().get(player).filter(authenticated::contains).isPresent();
    }

    private static String premiumAdmissionKey(String username, String ip) {
        return username.toLowerCase(Locale.ROOT) + '\0' + ip;
    }

    private void beginTwoFactor(Player player, TwoFactorRoutes.Action action) {
        RuntimeState state = runtime;
        Optional<UUID> connection = state == null ? Optional.empty() : state.identities().get(player);
        if (state == null || connection.isEmpty() || !authenticated.contains(connection.get())) {
            player.sendMessage(text(player, "two-factor-auth-required"));
            return;
        }
        ConnectionSessions.Session session = state.sessions().get(connection.get()).orElse(null);
        String previous = player.getCurrentServer().map(server -> server.getServerInfo().getName())
            .orElse(state.config().defaultServer());
        if (session == null || !state.twoFactorRoutes().begin(connection.get(), action, previous)
            || !state.sessions().grantManagement(connection.get(), session.id(),
                state.config().twoFactorManagementTimeout())) {
            state.twoFactorRoutes().clear(connection.get());
            player.sendMessage(text(player, "two-factor-busy"));
            return;
        }
        TwoFactorDelivery.prepare(acknowledgedSessionTokens, session.id());
        proxy.getServer(state.config().authServer()).ifPresentOrElse(
            server -> player.createConnectionRequest(server).connect().thenAccept(result -> {
                if (!result.isSuccessful()) {
                    state.twoFactorRoutes().clear(connection.get());
                    state.sessions().revokeManagement(connection.get());
                    player.sendMessage(text(player, "two-factor-unavailable"));
                }
            }),
            () -> {
                state.twoFactorRoutes().clear(connection.get());
                state.sessions().revokeManagement(connection.get());
                player.sendMessage(text(player, "two-factor-unavailable"));
            });
    }

    private void finishTwoFactor(Player player, UUID connection) {
        RuntimeState state = runtime;
        if (state == null) return;
        state.sessions().revokeManagement(connection);
        state.sessions().get(connection).ifPresent(session ->
            state.authenticationGrace().invalidate(session.username(),session.address()));
        state.twoFactorRoutes().complete(connection).ifPresent(route ->
            proxy.getServer(route.previousServer()).or(() -> proxy.getServer(state.config().defaultServer()))
                .ifPresent(server -> player.createConnectionRequest(server).fireAndForget()));
    }

    private Component text(String key) {
        RuntimeState state = runtime;
        return state == null ? Component.text("Authentication unavailable") : state.messages().component(key);
    }

    /** Messages in the language of the player who ran the command, or the default for the console. */
    private static VelocityMessages commandMessages(RuntimeState state, com.velocitypowered.api.command.CommandSource source) {
        if (!(source instanceof Player player)) return state.messages();
        java.util.Locale locale = player.getPlayerSettings().getLocale();
        return state.messages().forLocale(locale == null ? null : locale.toLanguageTag());
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
            password, answer, session.premium(), session.captchaRequired(), "", "",
            session.passwordTrusted(), session.totpTrusted(), "", "", session.maintenanceRevision(),
            session.maintenancePolicy().enableCaptcha(), session.maintenancePolicy().verifyCaptcha(),
            session.maintenancePolicy().enablePassword(), session.maintenancePolicy().verifyPassword(),
            session.maintenancePolicy().enableTotp(), session.maintenancePolicy().verifyTotp());
    }

    private void renderMetrics(StringBuilder out) {
        RuntimeState state = runtime;
        out.append("# TYPE selene_velocity_up gauge\nselene_velocity_up ").append(state == null ? 0 : 1).append('\n');
        if (state == null) return;
        out.append("# TYPE selene_protection_level gauge\nselene_protection_level ")
            .append(state.protection().level().ordinal()).append('\n');
        out.append("# TYPE selene_sessions gauge\nselene_sessions ").append(state.sessions().size()).append('\n');
        out.append("# TYPE selene_authenticated_players gauge\nselene_authenticated_players ")
            .append(authenticated.size()).append('\n');
        out.append("# TYPE selene_online_players gauge\nselene_online_players ").append(proxy.getPlayerCount()).append('\n');
        if (state.config().forkEnabled()) {
            try {
                state.fork().appendMetrics(out);
            } catch (RuntimeException error) {
                logger.warn(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[x] metrics  » core metrics unavailable: " + error));
            }
        }
    }

    private ForkCapabilities findForkCapabilities() {
        Optional<ForkCapabilities> provided = ServiceLoader.load(ForkCapabilities.class, getClass().getClassLoader())
            .findFirst();
        if (provided.isPresent()) return provided.get();
        // ThirdVelocity exposes its own API; the bridge is loaded reflectively so the plugin keeps
        // working on a plain Velocity where the API classes do not exist.
        try {
            Class.forName("ru.moonlightproject.dev.thirdvelocity.api.ThirdVelocity", false, getClass().getClassLoader());
            return (ForkCapabilities) Class.forName("ru.moonlightproject.dev.selene.velocity.ThirdVelocityForkCapabilities")
                .getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException | LinkageError absent) {
            return new ForkCapabilities() {};
        } catch (ReflectiveOperationException error) {
            logger.warn(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[x] core     » ThirdVelocity bridge startup failed: " + error));
            return new ForkCapabilities() {};
        }
    }

    private static boolean coreOwnsAttack(RuntimeState state) {
        return state.config().forkEnabled() && state.fork().ownsAttackDetection();
    }

    private AttackLevel currentLevel(RuntimeState state, long now) {
        synchronized (state.protection()) {
            AttackLevel desired = state.config().attackLevel();
            String source = "конфигурация";
            String reason = "заданный минимальный уровень";
            int pending=Math.max(0,state.sessions().size()-authenticated.size());
            if (coreOwnsAttack(state)) {
                // ThirdVelocity owns the network attack level; Selene only reports its signals.
                state.fork().reportPendingLogins(pending);
                AttackLevel detected = state.fork().attackLevel();
                if (detected.ordinal() >= desired.ordinal()) {
                    desired = detected; source = state.fork().name(); reason = "уровень атаки ядра";
                }
                return state.protection().update(desired, source, reason, now);
            }
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
            invocation.source().sendMessage(Component.text("Selene is not ready yet."));
            return;
        }
        synchronized (state.protection()) {
            currentLevel(state, System.currentTimeMillis());
            String[] args = invocation.arguments();
            Component reply;
            VelocityMessages m = commandMessages(state, invocation.source());
            if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
                reply = m.component(state.protection().confirm()?"protection-confirmed":"protection-no-request");
            } else if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
                reply = m.component("protection-status",VelocityMessages.literal("current",state.protection().level().name()),
                    m.flag("pending",state.protection().pending()));
            } else reply=m.component("protection-help");
            invocation.source().sendMessage(reply);
        }
    }

    /** Proxy-side state for {@code /selene status}, {@code session} and {@code logout}. */
    private final class CommandOperations implements SeleneCommand.Operations {
        @Override public void status(com.velocitypowered.api.command.CommandSource source) {
            RuntimeState state = runtime;
            if (state == null) { source.sendMessage(Component.text("Selene is not ready yet.")); return; }
            AttackLevel level = currentLevel(state, System.currentTimeMillis());
            String authServer = state.config().authServer();
            long onAuth = proxy.getAllPlayers().stream().filter(p -> p.getCurrentServer()
                .filter(s -> s.getServerInfo().getName().equals(authServer)).isPresent()).count();
            int loggedIn = authenticated.size(), pending = Math.max(0, state.sessions().size() - loggedIn);
            long started = System.nanoTime();
            // A one-row account page is the cheapest backend round trip that also touches the database.
            if (!(state.backend() instanceof ru.moonlightproject.dev.selene.core.AccountAdminGateway admin)) return;
            admin.admin(new ru.moonlightproject.dev.selene.core.AdminRequest("list", "", 1, 1))
                .whenComplete((reply, error) -> {
                    boolean up = error == null && reply != null && reply.status().equals("OK");
                    long millis = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
                    VelocityMessages m = commandMessages(state, source);
                    source.sendMessage(m.component("command-status",
                        VelocityMessages.literal("version", "0.1.0-dev"),
                        VelocityMessages.literal("level", level.name()),
                        m.flag("pending-confirm", state.protection().pending()),
                        up ? m.part("backend", "value-backend-up", VelocityMessages.literal("ms", Long.toString(millis)))
                            : m.part("backend", "value-backend-down"),
                        VelocityMessages.literal("online", Integer.toString(proxy.getPlayerCount())),
                        VelocityMessages.literal("on-auth", Long.toString(onAuth)),
                        VelocityMessages.literal("authenticated", Integer.toString(loggedIn)),
                        VelocityMessages.literal("pending", Integer.toString(pending)),
                        m.flag("otel", tracing != null)));
                });
        }
        @Override public void session(com.velocitypowered.api.command.CommandSource source, String username) {
            RuntimeState state = runtime;
            Optional<Player> player = proxy.getPlayer(username);
            if (state == null || player.isEmpty()) { source.sendMessage(state == null ? Component.text("Selene is not ready yet.")
                : state.messages().component("command-player-offline", VelocityMessages.player(username))); return; }
            Optional<UUID> connection = state.identities().get(player.get());
            Optional<ConnectionSessions.Session> session = connection.flatMap(state.sessions()::get);
            VelocityMessages m = commandMessages(state, source);
            source.sendMessage(m.component("command-session",
                VelocityMessages.player(player.get().getUsername()),
                m.flag("authenticated", isAuthenticated(player.get())),
                VelocityMessages.literal("server", player.get().getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("—")),
                VelocityMessages.literal("ip", player.get().getRemoteAddress().getAddress().getHostAddress()),
                session.map(s -> m.flag("premium", s.premium())).orElse(m.part("premium", "value-none")),
                session.map(s -> m.flag("captcha", s.captchaRequired())).orElse(m.part("captcha", "value-none")),
                m.flag("management", connection.flatMap(state.twoFactorRoutes()::get).isPresent()),
                VelocityMessages.literal("ping", Long.toString(player.get().getPing()))));
        }
        @Override public void logout(com.velocitypowered.api.command.CommandSource source, String username) {
            RuntimeState state = runtime;
            Optional<Player> player = proxy.getPlayer(username);
            if (state == null || player.isEmpty()) { source.sendMessage(state == null ? Component.text("Selene is not ready yet.")
                : state.messages().component("command-player-offline", VelocityMessages.player(username))); return; }
            Player target = player.get();
            // Forget the login before disconnecting, so the disconnect handler does not grant IP trust.
            state.identities().get(target).ifPresent(authenticated::remove);
            state.authenticationGrace().invalidate(target.getUsername(), target.getRemoteAddress().getAddress());
            target.disconnect(text(target, "command-logout-kick"));
            logger.info(ru.moonlightproject.dev.selene.core.ConsoleStyle.line("[*] logout   » {}  actor:{}"), target.getUsername(),
                source instanceof Player actor ? actor.getUsername() : "console");
            source.sendMessage(state.messages().component("command-logout-done", VelocityMessages.player(target.getUsername())));
        }
        @Override public void protection(com.velocitypowered.api.command.SimpleCommand.Invocation invocation) { protectionCommand(invocation); }
    }

    private record RuntimeState(
        VelocityConfig config,
        VelocityMessages messages,
        AuthGateway backend,
        AdmissionPolicy admission,
        AutoAttackController attacks,
        ConnectionSessions sessions,
        AuthenticationGraceCache authenticationGrace,
        ConnectionIdentities<Player> identities,
        PremiumLookup premiumLookup,
        PremiumTrustCache premiumTrust,
        TwoFactorRoutes twoFactorRoutes,
        RelayServer relay,
        ForkCapabilities fork,
        MaintenancePolicy maintenancePolicy,
        ProtectionState protection
    ) {}
}
