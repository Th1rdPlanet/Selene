package ru.moonlightproject.dev.selene.api;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Public Velocity API of Selene. Maintenance has intentionally no command or HTTP entry point.
 * Account methods call the backend over the authenticated RPC channel and complete off the
 * caller thread.
 */
public interface SeleneApi {
    /** Current network-wide maintenance state. */
    MaintenanceState maintenance();

    /** Enables or disables maintenance on every node. */
    CompletionStage<MaintenanceState> setMaintenance(boolean enabled, String reason, String actor);

    /** Whether the player finished Selene authentication on this connection. Fast. */
    boolean isAuthenticated(Player player);

    /** Looks up an account; empty when the name is not registered. */
    CompletionStage<Optional<AccountView>> account(String name);

    /** Forces a new password on the next login. Completes with false if the account is unknown. */
    CompletionStage<Boolean> resetPassword(String name);

    /** Removes TOTP from the account. Completes with false if the account is unknown. */
    CompletionStage<Boolean> resetTotp(String name);

    /** Deletes the account. Completes with false if the account is unknown. */
    CompletionStage<Boolean> deleteAccount(String name);

    /** Finds the active Selene plugin instance without retaining global state across reloads. */
    static Optional<SeleneApi> find(ProxyServer proxy) {
        return proxy.getPluginManager().getPlugin("selene")
            .flatMap(container -> container.getInstance())
            .filter(SeleneApi.class::isInstance)
            .map(SeleneApi.class::cast);
    }
}
