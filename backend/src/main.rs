use anyhow::Context;
use moonlight_bridge_observability::{otlp, prometheus::PrometheusSnapshotExporter};
use moonlight_bridge_server::{
    MoonLightMetrics, Router, Server, ServerLimits, Telemetry, TelemetryChain,
};
use opentelemetry::{KeyValue, trace::TracerProvider as _};
use opentelemetry_sdk::trace::SdkTracerProvider;
use selene_backend::{
    AdminReply, AdminRequest, Reply, Request,
    admin::AdminService,
    metrics::{self, Metrics},
    service::{Policy, Service, TotpKey, TotpPolicy},
    traces,
};
use serde::Deserialize;
use std::{net::SocketAddr, sync::Arc, time::Duration};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Config {
    #[serde(default)]
    console: Console,
    listen: String,
    database_url_env: String,
    certificate: String,
    private_key: String,
    client_ca: String,
    #[serde(default)]
    development_plaintext: bool,
    #[serde(default)]
    policy: Policy,
    #[serde(default)]
    totp: TotpConfig,
    #[serde(default)]
    telemetry: TelemetryConfig,
    #[serde(default = "default_admin_concurrency")]
    admin_concurrency: usize,
    #[serde(default = "default_admin_page_size")]
    admin_page_size: u32,
}
#[derive(Deserialize)]
#[serde(default, deny_unknown_fields)]
struct TotpConfig {
    enabled: bool,
    master_key_env: String,
    #[serde(flatten)]
    policy: TotpPolicy,
}
impl Default for TotpConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            master_key_env: "SELENE_TOTP_MASTER_KEY".into(),
            policy: TotpPolicy::default(),
        }
    }
}
impl TotpConfig {
    fn resolve_key<F>(&self, lookup: F) -> anyhow::Result<Option<TotpKey>>
    where
        F: Fn(&str) -> Option<String>,
    {
        if !self.enabled {
            return Ok(None);
        }
        anyhow::ensure!(
            !self.master_key_env.trim().is_empty(),
            "totp.master_key_env must not be empty"
        );
        let value = lookup(&self.master_key_env).ok_or_else(|| {
            anyhow::anyhow!(
                "TOTP master key environment variable {} is missing",
                self.master_key_env
            )
        })?;
        TotpKey::from_base64(&value).map(Some).with_context(|| {
            format!(
                "invalid TOTP master key in {} (value hidden)",
                self.master_key_env
            )
        })
    }
}
/// Optional OpenTelemetry traces for Grafana Tempo and a Prometheus endpoint; both are off by default.
#[derive(Deserialize)]
#[serde(default, deny_unknown_fields)]
struct TelemetryConfig {
    otel: bool,
    otlp_endpoint: String,
    service_name: String,
    prometheus: bool,
    prometheus_listen: String,
}
impl Default for TelemetryConfig {
    fn default() -> Self {
        Self {
            otel: false,
            otlp_endpoint: "http://127.0.0.1:4318/v1/traces".into(),
            service_name: "selene-backend".into(),
            prometheus: false,
            prometheus_listen: "127.0.0.1:9464".into(),
        }
    }
}
impl TelemetryConfig {
    /// Builds the caller-owned OTLP/HTTP exporter; the provider must be shut down to flush spans.
    fn provider(&self) -> anyhow::Result<Option<SdkTracerProvider>> {
        if !self.otel {
            return Ok(None);
        }
        anyhow::ensure!(
            self.otlp_endpoint.starts_with("http://") || self.otlp_endpoint.starts_with("https://"),
            "telemetry.otlp_endpoint must be an http:// or https:// OTLP traces URL"
        );
        anyhow::ensure!(
            !self.service_name.trim().is_empty(),
            "telemetry.service_name must not be empty"
        );
        otlp::tracer_provider_with_service(self.otlp_endpoint.clone(), self.service_name.clone())
            .map(Some)
            .with_context(|| format!("cannot create OTLP exporter for {}", self.otlp_endpoint))
    }
}
#[derive(Deserialize)]
#[serde(default, deny_unknown_fields)]
struct Console {
    language: String,
    banner: bool,
}
impl Default for Console {
    fn default() -> Self {
        Self {
            language: "ru".into(),
            banner: true,
        }
    }
}
impl Console {
    fn validate(&self) -> anyhow::Result<()> {
        anyhow::ensure!(
            matches!(self.language.as_str(), "ru" | "en"),
            "console.language must be ru or en"
        );
        Ok(())
    }
    fn info(&self, ru: &str, en: &str) {
        println!(
            "[Selene/INFO] {}",
            if self.language == "ru" { ru } else { en }
        );
    }
    fn startup(&self) {
        if self.banner {
            let purpose = if self.language == "ru" {
                "авторизация и контроль доступа"
            } else {
                "authentication & access control"
            };
            for (index, line) in [
                "",
                "       ▄▄██▀▀    Selene backend",
                "    ▄████▀       Rust • MoonLightBridge",
                "   ████▀",
                "  ████",
                "  ████",
                "   ████▄",
                "    ▀████▄       ~VicTim~",
                "       ▀▀██▄▄    dev.moonlightproject.ru",
                "",
            ]
            .into_iter()
            .enumerate()
            {
                let line = if index == 4 {
                    format!("{line}          {purpose}")
                } else {
                    line.to_owned()
                };
                println!("[Selene/INFO] {line}");
            }
        }
        self.info(
            &format!(
                "[*] init     » Selene backend {}",
                env!("CARGO_PKG_VERSION")
            ),
            &format!(
                "[*] init     » Selene backend {}",
                env!("CARGO_PKG_VERSION")
            ),
        );
    }
}
fn default_admin_concurrency() -> usize {
    4
}
fn default_admin_page_size() -> u32 {
    50
}
#[tokio::main]
async fn main() {
    if let Err(error) = run().await {
        // Only the contextual message: database drivers may put credentials in their error chain.
        eprintln!("[Selene/ERROR] [x] init     » startup aborted: {error}");
        std::process::exit(1);
    }
}
async fn run() -> anyhow::Result<()> {
    if rustls::crypto::CryptoProvider::get_default().is_none() {
        rustls::crypto::aws_lc_rs::default_provider()
            .install_default()
            .map_err(|_| anyhow::anyhow!("failed to install rustls AWS-LC crypto provider"))?;
    }

    let path = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "backend/config.toml".into());
    let source = std::fs::read_to_string(&path)
        .with_context(|| format!("cannot read backend config {path}"))?;
    let config: Config = toml::from_str(&source)
        .with_context(|| format!("invalid TOML in backend config {path}"))?;
    config.console.validate()?;
    config.console.startup();
    config
        .console
        .info("[+] config   » загружена", "[+] config   » loaded");
    let address: SocketAddr = config.listen.parse().with_context(|| {
        format!(
            "invalid listen address '{}' (expected numeric IP:port)",
            config.listen
        )
    })?;
    anyhow::ensure!(
        !config.development_plaintext || address.ip().is_loopback(),
        "development_plaintext=true is allowed only on a loopback listen address"
    );
    let url = std::env::var(&config.database_url_env).with_context(|| {
        format!(
            "database URL environment variable {} is missing",
            config.database_url_env
        )
    })?;
    anyhow::ensure!(
        !url.is_empty(),
        "database URL environment variable {} is empty",
        config.database_url_env
    );
    config.console.info(
        "[*] db       » подключение и проверка схемы…",
        "[*] db       » connecting & verifying schema…",
    );
    let totp_key = config.totp.resolve_key(|name| std::env::var(name).ok())?;
    let service = Arc::new(
        (if let Some(key) = totp_key {
            Service::new_with_totp(
                &url,
                config.policy.clone(),
                config.totp.policy.clone(),
                key.into_bytes(),
            )
            .await
        } else {
            Service::new(&url, config.policy.clone()).await
        })
        .with_context(|| {
            format!(
                "cannot initialize database configured by {} (value hidden)",
                config.database_url_env
            )
        })?,
    );
    anyhow::ensure!(
        config.admin_concurrency > 0 && (1..=50).contains(&config.admin_page_size),
        "admin_concurrency must be positive and admin_page_size must be 1..=50"
    );
    config.console.info(
        "[+] db       » готова, схема проверена",
        "[+] db       » ready, schema verified",
    );
    let admin = Arc::new(AdminService::new(
        service.storage_clone(),
        config.admin_page_size,
    ));
    let requests = Arc::new(tokio::sync::Semaphore::new(32));
    let admin_requests = Arc::new(tokio::sync::Semaphore::new(config.admin_concurrency));
    let tracing = config.telemetry.provider()?;
    let mut telemetry: Vec<Arc<dyn Telemetry>> = Vec::new();
    let mut metrics = None;
    if config.telemetry.prometheus {
        let address: SocketAddr =
            config
                .telemetry
                .prometheus_listen
                .parse()
                .with_context(|| {
                    format!(
                        "invalid telemetry.prometheus_listen '{}' (expected numeric IP:port)",
                        config.telemetry.prometheus_listen
                    )
                })?;
        let transport = MoonLightMetrics::default();
        let exporter = Arc::new(PrometheusSnapshotExporter::new()?);
        let selene = Arc::new(Metrics::register(exporter.registry())?);
        let listener = tokio::net::TcpListener::bind(address)
            .await
            .with_context(|| format!("cannot bind Prometheus listener on {address}"))?;
        telemetry.push(Arc::new(transport.clone()));
        tokio::spawn(serve_metrics(
            listener,
            transport,
            exporter,
            selene.clone(),
            service.clone(),
        ));
        metrics = Some(selene);
        config.console.info(
            &format!("[*] prom     » http://{address}/metrics"),
            &format!("[*] prom     » http://{address}/metrics"),
        );
    }
    let mut scope = None;
    if let Some(provider) = &tracing {
        let (requests, layer) = traces::install(provider.tracer("selene-backend"));
        telemetry.push(requests);
        scope = Some(layer);
        config.console.info(
            &format!(
                "[*] otel     » трейсы → {}  service:{}",
                config.telemetry.otlp_endpoint, config.telemetry.service_name
            ),
            &format!(
                "[*] otel     » traces → {}  service:{}",
                config.telemetry.otlp_endpoint, config.telemetry.service_name
            ),
        );
    }
    let mut router = Router::builder();
    if !telemetry.is_empty() {
        router = router.telemetry(Arc::new(TelemetryChain::new(telemetry)));
    }
    if let Some(layer) = scope {
        router = router.layer(layer);
    }
    let admin_metrics = metrics.clone();
    let router = router
        .route(1397046273, move |body| {
            let service = service.clone();
            let requests = requests.clone();
            let metrics = metrics.clone();
            async move {
                let started = std::time::Instant::now();
                let mut op = String::from("unparsed");
                let mut maintenance_revision = 0_u64;
                let reply = if body.len() > 262144 {
                    Reply::status("INVALID_REQUEST", "")
                } else if let Ok(_permit) = requests.try_acquire_owned() {
                    match serde_json::from_slice::<Request>(&body) {
                        Ok(request) => {
                            op.clone_from(&request.op);
                            maintenance_revision = request.maintenance_revision;
                            service.handle(request).await
                        }
                        Err(_) => Reply::status("INVALID_REQUEST", ""),
                    }
                } else {
                    Reply::status("BUSY", "")
                };
                if let Some(metrics) = &metrics {
                    metrics.auth(&op, &reply.status, started.elapsed());
                }
                traces::annotate(
                    format!("Auth/{}", metrics::operation(&op)),
                    vec![
                        KeyValue::new("selene.status", metrics::status(&reply.status)),
                        KeyValue::new("selene.maintenance", maintenance_revision > 0),
                        KeyValue::new("selene.maintenance_revision", maintenance_revision as i64),
                    ],
                );
                let encoded = serde_json::to_vec(&reply).expect("serializable reply");
                Ok(if encoded.len() <= 262144 {
                    encoded
                } else {
                    serde_json::to_vec(&Reply::status("UNAVAILABLE", &reply.session)).unwrap()
                })
            }
        })
        .route(1397046274, move |body| {
            let admin = admin.clone();
            let requests = admin_requests.clone();
            let metrics = admin_metrics.clone();
            async move {
                let mut op = String::from("unparsed");
                let reply = if body.len() > 65536 {
                    AdminReply {
                        status: "INVALID_REQUEST".into(),
                        ..Default::default()
                    }
                } else if let Ok(_permit) = requests.try_acquire_owned() {
                    match serde_json::from_slice::<AdminRequest>(&body) {
                        Ok(request) => {
                            op.clone_from(&request.op);
                            admin.handle(request).await
                        }
                        Err(_) => AdminReply {
                            status: "INVALID_REQUEST".into(),
                            ..Default::default()
                        },
                    }
                } else {
                    AdminReply {
                        status: "BUSY".into(),
                        ..Default::default()
                    }
                };
                if let Some(metrics) = &metrics {
                    metrics.admin(&op, &reply.status);
                }
                traces::annotate(
                    format!("Admin/{}", metrics::admin_operation(&op)),
                    vec![KeyValue::new(
                        "selene.status",
                        metrics::status(&reply.status),
                    )],
                );
                Ok(serde_json::to_vec(&reply).expect("serializable admin reply"))
            }
        })
        .build();
    let server = if config.development_plaintext {
        config.console.info(
            "Локальный режим разработки • шифрование отключено.",
            "Local development mode • encryption disabled.",
        );
        Server::bind_tcp(&config.listen, router)
            .await
            .with_context(|| {
                format!(
                    "cannot bind development backend listener on {}",
                    config.listen
                )
            })?
    } else {
        let tls = moonlight_bridge_server::tls::load_mtls_server_config(
            &config.certificate,
            &config.private_key,
            &config.client_ca,
        )
        .with_context(|| {
            format!(
                "cannot load mTLS files: certificate={}, private_key={}, client_ca={}",
                config.certificate, config.private_key, config.client_ca
            )
        })?;
        Server::bind_tls(&config.listen, router, tls)
            .await
            .with_context(|| format!("cannot bind TLS backend listener on {}", config.listen))?
    };
    config.console.info(
        &format!(
            "[+] ready    » слушаем {}  transport:{}",
            config.listen,
            if config.development_plaintext {
                "TCP (development)"
            } else {
                "mTLS"
            }
        ),
        &format!(
            "[+] ready    » listening {}  transport:{}",
            config.listen,
            if config.development_plaintext {
                "TCP (development)"
            } else {
                "mTLS"
            }
        ),
    );
    let stopped = server
        .with_hello_timeout(Duration::from_secs(5))
        .with_limits(ServerLimits {
            max_connections: 64,
            max_buffered_request_bytes: 8 * 1024 * 1024,
            frame_read_timeout: Duration::from_secs(120),
            ..ServerLimits::default()
        })
        .run()
        .await;
    if let Some(provider) = tracing {
        // Flushes the spans still queued in the batch exporter.
        let _ = provider.shutdown();
    }
    stopped.context("MoonLightBridge backend listener stopped with an error")?;
    Ok(())
}

/// Minimal `/metrics` responder: one short request per connection, no keep-alive.
async fn serve_metrics(
    listener: tokio::net::TcpListener,
    transport: MoonLightMetrics,
    exporter: Arc<PrometheusSnapshotExporter>,
    selene: Arc<Metrics>,
    service: Arc<Service>,
) {
    loop {
        let Ok((mut stream, _)) = listener.accept().await else {
            continue;
        };
        let (transport, exporter, selene, service) = (
            transport.clone(),
            exporter.clone(),
            selene.clone(),
            service.clone(),
        );
        tokio::spawn(async move {
            let mut request = [0u8; 1024];
            let _ = tokio::time::timeout(Duration::from_secs(5), stream.read(&mut request)).await;
            if let Some((pending, authenticated, maintenance)) = service.session_counts() {
                selene.sessions(pending, authenticated, maintenance);
            }
            exporter.try_update(transport.snapshot());
            let body = exporter.render().unwrap_or_default();
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: text/plain; version=0.0.4\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            let _ = stream.write_all(response.as_bytes()).await;
        });
    }
}

#[cfg(test)]
mod console_tests {
    use super::*;
    #[test]
    fn console_defaults_and_language_validation() {
        let console: Console = toml::from_str("").unwrap();
        assert_eq!(console.language, "ru");
        assert!(console.banner);
        assert!(console.validate().is_ok());
        let invalid: Console = toml::from_str("language='xx'").unwrap();
        assert!(invalid.validate().is_err());
    }
    #[test]
    fn telemetry_is_off_by_default_and_validates_when_enabled() {
        let telemetry: TelemetryConfig = toml::from_str("").unwrap();
        assert!(!telemetry.otel);
        assert!(telemetry.provider().unwrap().is_none());
        let endpoint: TelemetryConfig =
            toml::from_str("otel=true\notlp_endpoint='127.0.0.1:4318'").unwrap();
        assert!(endpoint.provider().is_err());
        let name: TelemetryConfig = toml::from_str("otel=true\nservice_name=' '").unwrap();
        assert!(name.provider().is_err());
        assert!(toml::from_str::<TelemetryConfig>("otle=true").is_err());
        assert!(!telemetry.prometheus);
        assert_eq!(telemetry.prometheus_listen, "127.0.0.1:9464");
    }
    #[test]
    fn enabled_totp_requires_a_valid_master_key_environment_value() {
        let config: TotpConfig =
            toml::from_str("enabled=true\nmaster_key_env='SELENE_TEST_TOTP_KEY'").unwrap();
        assert!(config.resolve_key(|_| None).is_err());
        assert!(config.resolve_key(|_| Some("YWJj".into())).is_err());
        let valid = base64::Engine::encode(&base64::engine::general_purpose::STANDARD, [7; 32]);
        assert_eq!(
            config
                .resolve_key(|_| Some(valid.clone()))
                .unwrap()
                .unwrap()
                .into_bytes(),
            [7; 32]
        );
        let disabled: TotpConfig = toml::from_str("").unwrap();
        assert!(disabled.resolve_key(|_| None).unwrap().is_none());
    }
}
