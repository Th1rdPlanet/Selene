//! Prometheus metrics for the Selene Grafana dashboard.
//! Every label comes from a closed list, so client input never creates a new time series.
use prometheus::{
    HistogramOpts, HistogramVec, IntCounterVec, IntGauge, IntGaugeVec, Opts, Registry,
};
use std::time::Duration;

const OPS: &[&str] = &[
    "profile",
    "begin",
    "answer",
    "login",
    "register",
    "close",
    "trust",
    "snapshot",
    "premium_trust",
    "interactive_complete",
    "interactive_mistake",
    "totp_status",
    "totp_setup_begin",
    "totp_setup_confirm",
    "totp_verify",
    "totp_disable",
    "totp_recovery_regenerate",
    "unparsed",
];
const ADMIN_OPS: &[&str] = &[
    "list",
    "info",
    "delete",
    "resetPassword",
    "resetTotp",
    "unparsed",
];
const STATUSES: &[&str] = &[
    "ACCOUNT_EXISTS",
    "ACCOUNT_NEW",
    "ACCOUNT_OFFLINE",
    "ACCOUNT_PREMIUM",
    "AUTHENTICATED",
    "BUSY",
    "CAPTCHA",
    "CAPTCHA_INTERACTIVE",
    "CAPTCHA_INVALID",
    "CLOSED",
    "DIALOG_FONT",
    "EXPIRED",
    "INTERACTIVE",
    "INVALID_CREDENTIALS",
    "INVALID_REQUEST",
    "LOGIN",
    "NOT_FOUND",
    "OK",
    "PREMIUM_TRUSTED",
    "RATE_LIMITED",
    "RECOVERY_INVALID",
    "REGISTER",
    "SNAPSHOT",
    "TOTP_ALREADY_ENABLED",
    "TOTP_DISABLED",
    "TOTP_INVALID",
    "TOTP_NOT_ENABLED",
    "TOTP_RECOVERY",
    "TOTP_REQUIRED",
    "TOTP_SETUP",
    "TOTP_STATUS",
    "TRUSTED",
    "UNAVAILABLE",
];

pub struct Metrics {
    requests: IntCounterVec,
    duration: HistogramVec,
    admin: IntCounterVec,
    sessions: IntGaugeVec,
}

impl Metrics {
    /// Registers the Selene series next to the MoonLightBridge transport series.
    pub fn register(registry: &Registry) -> prometheus::Result<Self> {
        let requests = IntCounterVec::new(
            Opts::new(
                "selene_auth_requests_total",
                "Authentication RPCs by operation and reply status",
            ),
            &["op", "status"],
        )?;
        // Argon2 dominates login and register, so the buckets reach several seconds.
        let duration = HistogramVec::new(
            HistogramOpts::new(
                "selene_auth_request_duration_seconds",
                "Backend time per authentication RPC, including password hashing",
            )
            .buckets(vec![
                0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0,
            ]),
            &["op"],
        )?;
        let admin = IntCounterVec::new(
            Opts::new(
                "selene_admin_requests_total",
                "Administrative RPCs by operation and reply status",
            ),
            &["op", "status"],
        )?;
        let sessions = IntGaugeVec::new(
            Opts::new("selene_sessions", "Live authentication sessions by state"),
            &["state"],
        )?;
        let build = IntGauge::with_opts(
            Opts::new("selene_build_info", "Selene backend build")
                .const_label("version", env!("CARGO_PKG_VERSION")),
        )?;
        build.set(1);
        registry.register(Box::new(requests.clone()))?;
        registry.register(Box::new(duration.clone()))?;
        registry.register(Box::new(admin.clone()))?;
        registry.register(Box::new(sessions.clone()))?;
        registry.register(Box::new(build))?;
        Ok(Self {
            requests,
            duration,
            admin,
            sessions,
        })
    }
    pub fn auth(&self, op: &str, status: &str, elapsed: Duration) {
        let op = known(op, OPS);
        self.requests
            .with_label_values(&[op, known(status, STATUSES)])
            .inc();
        self.duration
            .with_label_values(&[op])
            .observe(elapsed.as_secs_f64());
    }
    pub fn admin(&self, op: &str, status: &str) {
        self.admin
            .with_label_values(&[known(op, ADMIN_OPS), known(status, STATUSES)])
            .inc();
    }
    pub fn sessions(&self, pending: usize, authenticated: usize, maintenance: usize) {
        self.sessions
            .with_label_values(&["pending"])
            .set(pending as i64);
        self.sessions
            .with_label_values(&["authenticated"])
            .set(authenticated as i64);
        self.sessions
            .with_label_values(&["maintenance"])
            .set(maintenance as i64);
    }
}

/// The operation as a closed-list label: unknown client input becomes "other".
pub fn operation(op: &str) -> &'static str {
    OPS.iter()
        .find(|known| **known == op)
        .copied()
        .unwrap_or("other")
}
/// The administrative operation as a closed-list label.
pub fn admin_operation(op: &str) -> &'static str {
    ADMIN_OPS
        .iter()
        .find(|known| **known == op)
        .copied()
        .unwrap_or("other")
}
/// The reply status as a closed-list label.
pub fn status(value: &str) -> &'static str {
    STATUSES
        .iter()
        .find(|known| **known == value)
        .copied()
        .unwrap_or("other")
}
fn known<'a>(value: &'a str, list: &[&'static str]) -> &'a str {
    if list.contains(&value) {
        value
    } else {
        "other"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use prometheus::Encoder;
    #[test]
    fn unknown_labels_collapse_into_other() {
        let registry = Registry::new();
        let metrics = Metrics::register(&registry).unwrap();
        metrics.auth("login", "AUTHENTICATED", Duration::from_millis(40));
        metrics.auth("../../etc", "<script>", Duration::ZERO);
        metrics.admin("resetTotp", "OK");
        metrics.sessions(3, 2, 1);
        let mut text = Vec::new();
        prometheus::TextEncoder::new()
            .encode(&registry.gather(), &mut text)
            .unwrap();
        let text = String::from_utf8(text).unwrap();
        assert!(
            text.contains(r#"selene_auth_requests_total{op="login",status="AUTHENTICATED"} 1"#)
        );
        assert!(text.contains(r#"selene_auth_requests_total{op="other",status="other"} 1"#));
        assert!(text.contains(r#"selene_admin_requests_total{op="resetTotp",status="OK"} 1"#));
        assert!(text.contains(r#"selene_sessions{state="authenticated"} 2"#));
        assert!(text.contains(r#"selene_sessions{state="maintenance"} 1"#));
        assert!(!text.contains("etc"));
    }
}
