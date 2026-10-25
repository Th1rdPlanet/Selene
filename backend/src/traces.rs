//! Distributed traces for Grafana Tempo.
//!
//! The Rust request span continues the Velocity client span propagated by MoonLightBridge, and
//! backend steps (database, Argon2, CAPTCHA) become its children with their real start and end
//! times, so the Tempo waterfall shows where a login spent its time. Without an installed tracer
//! every helper is a pass-through.
use moonlight_bridge_server::{
    Middleware, MiddlewareFuture, Next, RequestContext, RequestInfo, RequestObservation,
    RequestOutcome, Telemetry,
};
use opentelemetry::{
    Context, KeyValue,
    trace::{
        Span, SpanContext, SpanId, SpanKind, Status, TraceContextExt, TraceFlags, TraceId,
        TraceState, Tracer,
    },
};
use opentelemetry_sdk::trace::{SdkTracer, Span as SdkSpan};
use std::{
    cell::RefCell,
    future::Future,
    sync::{Arc, Mutex, OnceLock},
};

static TRACER: OnceLock<SdkTracer> = OnceLock::new();

type SharedSpan = Arc<Mutex<SdkSpan>>;

thread_local! {
    /// Hands the request span from `start_request` to the middleware. Both run in the same poll
    /// of the same task with no await in between; the request id guards against any mix-up.
    static STARTED: RefCell<Option<(u64, SharedSpan)>> = const { RefCell::new(None) };
}

tokio::task_local! {
    static REQUEST: SharedSpan;
    static PARENT: Context;
}

/// Installs the tracer and returns the request telemetry and the middleware that scopes it.
pub fn install(tracer: SdkTracer) -> (Arc<dyn Telemetry>, Arc<dyn Middleware>) {
    let _ = TRACER.set(tracer);
    (Arc::new(RequestTelemetry), Arc::new(RequestScope))
}

/// Runs `future` as a child span of the current request or step.
pub async fn span<T>(name: &'static str, future: impl Future<Output = T>) -> T {
    let Some(parent) = current() else {
        return future.await;
    };
    let tracer = TRACER.get().expect("parent exists only with a tracer");
    let context = parent.with_span(tracer.start_with_context(name, &parent));
    let output = PARENT.scope(context.clone(), future).await;
    context.span().end();
    output
}

/// Synchronous variant of [`span`] for short CPU work on the request task.
pub fn span_sync<T>(name: &'static str, function: impl FnOnce() -> T) -> T {
    let Some(parent) = current() else {
        return function();
    };
    let mut span = TRACER
        .get()
        .expect("parent exists only with a tracer")
        .start_with_context(name, &parent);
    let output = function();
    span.end();
    output
}

/// Names the request span after the operation and records its reply status.
/// `operation` must come from a closed list: span names are indexed by Tempo.
pub fn annotate(operation: impl Into<std::borrow::Cow<'static, str>>, attributes: Vec<KeyValue>) {
    let _ = REQUEST.try_with(|span| {
        if let Ok(mut span) = span.lock() {
            span.update_name(operation.into());
            span.set_attributes(attributes);
        }
    });
}

fn current() -> Option<Context> {
    PARENT.try_with(Context::clone).ok()
}

struct RequestTelemetry;

impl Telemetry for RequestTelemetry {
    fn start_request(&self, info: RequestInfo) -> Option<Box<dyn RequestObservation>> {
        let tracer = TRACER.get()?;
        let builder = tracer
            .span_builder(format!("moonlight.rpc.{:08x}", info.method_id))
            .with_kind(SpanKind::Server)
            .with_attributes([
                KeyValue::new("rpc.system", "moonlight_bridge"),
                KeyValue::new("rpc.method_id", i64::from(info.method_id)),
                KeyValue::new("rpc.request_id", info.request_id.to_string()),
                KeyValue::new("rpc.request.size", info.request_bytes as i64),
            ]);
        let span = match info.trace_context {
            Some(parent) => {
                let flags = if parent.sampled {
                    TraceFlags::SAMPLED
                } else {
                    TraceFlags::default()
                };
                let context = Context::new().with_remote_span_context(SpanContext::new(
                    TraceId::from_bytes(parent.trace_id),
                    SpanId::from_bytes(parent.parent_span_id),
                    flags,
                    true,
                    TraceState::default(),
                ));
                builder.start_with_context(tracer, &context)
            }
            None => builder.start(tracer),
        };
        let span = Arc::new(Mutex::new(span));
        STARTED.with(|started| *started.borrow_mut() = Some((info.request_id, span.clone())));
        Some(Box::new(RequestObservationSpan(span)))
    }
}

struct RequestObservationSpan(SharedSpan);

impl RequestObservation for RequestObservationSpan {
    fn finish(self: Box<Self>, outcome: RequestOutcome) {
        let Ok(mut span) = self.0.lock() else {
            return;
        };
        match outcome {
            RequestOutcome::Success { response_bytes } => {
                span.set_attribute(KeyValue::new("rpc.response.size", response_bytes as i64));
                span.set_status(Status::Ok);
            }
            RequestOutcome::Error { code } => {
                span.set_attribute(KeyValue::new("error.type", format!("{code:?}")));
                span.set_status(Status::error(format!("{code:?}")));
            }
        }
        span.end();
    }
}

struct RequestScope;

impl Middleware for RequestScope {
    fn call(&self, context: RequestContext, body: Vec<u8>, next: Next) -> MiddlewareFuture {
        let started = STARTED
            .with(|started| started.borrow_mut().take())
            .filter(|(id, _)| *id == context.request_id());
        let Some((_, span)) = started else {
            return next.run(context, body);
        };
        let parent = span
            .lock()
            .map(|span| Context::new().with_remote_span_context(span.span_context().clone()))
            .unwrap_or_default();
        Box::pin(REQUEST.scope(span, PARENT.scope(parent, next.run(context, body))))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn helpers_pass_through_without_a_request() {
        assert_eq!(span("db.account", async { 7 }).await, 7);
        assert_eq!(span_sync("captcha.generate", || 8), 8);
        annotate("Auth/login", vec![]);
    }
}
