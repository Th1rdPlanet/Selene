package ru.moonlightproject.dev.selene.api;

import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Public Velocity API. Maintenance has intentionally no command or HTTP entry point. */
public interface SeleneApi {
    MaintenanceState maintenance();
    CompletionStage<MaintenanceState> setMaintenance(boolean enabled, String reason, String actor);

    /** Finds the active Selene plugin instance without retaining global state across reloads. */
    static Optional<SeleneApi> find(ProxyServer proxy) {
        return proxy.getPluginManager().getPlugin("selene")
            .flatMap(container -> container.getInstance())
            .filter(SeleneApi.class::isInstance)
            .map(SeleneApi.class::cast);
    }
}
