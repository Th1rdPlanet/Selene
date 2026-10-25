package ru.moonlightproject.dev.selene.velocity;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import ru.moonlightproject.bridge.client.MoonLightTelemetry;
import ru.moonlightproject.bridge.client.MoonLightTelemetryProvider;
import ru.moonlightproject.bridge.client.MoonLightTelemetry.RequestInfo;
import ru.moonlightproject.bridge.client.MoonLightTelemetry.RequestObservation;
import ru.moonlightproject.bridge.otel.MoonLightOpenTelemetry;
import ru.moonlightproject.dev.selene.core.CallScope;

/**
 * OpenTelemetry traces for Grafana Tempo. Every backend call becomes a Velocity span; the
 * MoonLightBridge client span below it carries the trace to the Rust backend, which continues it.
 */
final class VelocityTracing implements CallScope, AutoCloseable {
    /** Operations named in span names; anything else is reported as "other" to keep names bounded. */
    private static final Set<String> OPERATIONS = Set.of("profile", "begin", "answer", "login", "register", "close",
        "trust", "snapshot", "premium_trust", "interactive_complete", "interactive_mistake", "totp_status",
        "totp_setup_begin", "totp_setup_confirm", "totp_verify", "totp_disable", "totp_recovery_regenerate",
        "list", "info", "delete", "resetPassword", "resetTotp");
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("selene.op");
    private static final AttributeKey<String> USERNAME = AttributeKey.stringKey("selene.username");
    private static final AttributeKey<String> STATUS = AttributeKey.stringKey("selene.status");
    private final SdkTracerProvider provider;
    private final Tracer tracer;

    private VelocityTracing(SdkTracerProvider provider, OpenTelemetrySdk sdk) {
        this.provider = provider;
        tracer = sdk.getTracer("selene-velocity");
        Forwarding.delegate = new MoonLightOpenTelemetry(sdk);
    }

    static VelocityTracing start(String endpoint, String serviceName) {
        var exporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint).setTimeout(Duration.ofSeconds(5)).build();
        // Bounded queue: when the collector is down, spans are dropped instead of filling the heap.
        var processor = BatchSpanProcessor.builder(exporter).setMaxQueueSize(2048).setMaxExportBatchSize(512)
            .setScheduleDelay(Duration.ofSeconds(1)).build();
        var resource = Resource.getDefault().merge(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), serviceName)));
        var provider = SdkTracerProvider.builder().setResource(resource).addSpanProcessor(processor).build();
        return new VelocityTracing(provider, OpenTelemetrySdk.builder().setTracerProvider(provider).build());
    }

    @Override
    public <T> CompletableFuture<T> around(String kind, String operation, String username,
                                           Supplier<CompletableFuture<T>> call, Function<T, String> status) {
        String name = OPERATIONS.contains(operation) ? operation : "other";
        Span span = tracer.spanBuilder(kind + "/" + name).setAttribute(OPERATION, name).setAttribute(USERNAME, username).startSpan();
        CompletableFuture<T> result;
        // The MoonLightBridge client span starts inside call() and takes this span as its parent.
        try (Scope ignored = span.makeCurrent()) {
            result = call.get();
        } catch (RuntimeException error) {
            span.recordException(error);
            span.setStatus(StatusCode.ERROR);
            span.end();
            throw error;
        }
        return result.whenComplete((reply, error) -> {
            if (error != null) {
                span.recordException(error);
                span.setStatus(StatusCode.ERROR);
            } else {
                span.setAttribute(STATUS, status.apply(reply));
            }
            span.end();
        });
    }

    @Override
    public void close() {
        Forwarding.delegate = MoonLightTelemetry.disabled();
        provider.shutdown().join(5, TimeUnit.SECONDS);
    }

    /**
     * Found by MoonLightBridge through ServiceLoader. TLS clients take their telemetry only from this
     * discovery, so the provider forwards to whatever {@link #start} installed, or to nothing.
     */
    public static final class Forwarding implements MoonLightTelemetryProvider, MoonLightTelemetry {
        private static volatile MoonLightTelemetry delegate = MoonLightTelemetry.disabled();

        @Override public MoonLightTelemetry createTelemetry() { return this; }
        @Override public RequestObservation startRequest(RequestInfo request) { return delegate.startRequest(request); }
        @Override public void connectionOpened() { delegate.connectionOpened(); }
        @Override public void connectionClosed(Throwable cause) { delegate.connectionClosed(cause); }
    }
}
