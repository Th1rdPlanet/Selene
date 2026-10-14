use anyhow::Context;
use moonlight_bridge_server::{Router, Server, ServerLimits};
use selene_backend::{
    AdminReply, AdminRequest, Reply, Request,
    admin::AdminService,
    service::{Policy, Service},
};
use serde::Deserialize;
use std::{net::SocketAddr, sync::Arc, time::Duration};
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
    #[serde(default = "default_admin_concurrency")]
    admin_concurrency: usize,
    #[serde(default = "default_admin_page_size")]
    admin_page_size: u32,
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
            for line in [
                "",
                "    .-''-.    Selene Rust backend",
                "   / .--'     MoonLightBridge",
                "   \\ '--.     ~VicTim~",
                "    '-..-'    dev.moonlightproject.ru",
                "",
            ] {
                println!("[Selene/INFO] {line}");
            }
        }
        self.info(
            &format!("Запуск • версия {}", env!("CARGO_PKG_VERSION")),
            &format!("Starting • version {}", env!("CARGO_PKG_VERSION")),
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
        eprintln!("[Selene/ERROR] Запуск остановлен / Startup stopped: {error}");
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
        .info("Конфигурация загружена.", "Configuration loaded.");
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
    config
        .console
        .info("Подключаем базу данных…", "Connecting to database…");
    let service = Arc::new(Service::new(&url, config.policy).await.with_context(|| {
        format!(
            "cannot initialize database configured by {} (value hidden)",
            config.database_url_env
        )
    })?);
    anyhow::ensure!(
        config.admin_concurrency > 0 && (1..=50).contains(&config.admin_page_size),
        "admin_concurrency must be positive and admin_page_size must be 1..=50"
    );
    config.console.info(
        "База данных готова • схема проверена.",
        "Database ready • schema checked.",
    );
    let admin = Arc::new(AdminService::new(
        service.storage_clone(),
        config.admin_page_size,
    ));
    let requests = Arc::new(tokio::sync::Semaphore::new(32));
    let admin_requests = Arc::new(tokio::sync::Semaphore::new(config.admin_concurrency));
    let router = Router::builder()
        .route(1397046273, move |body| {
            let service = service.clone();
            let requests = requests.clone();
            async move {
                let reply = if body.len() > 262144 {
                    Reply::status("INVALID_REQUEST", "")
                } else if let Ok(_permit) = requests.try_acquire_owned() {
                    match serde_json::from_slice::<Request>(&body) {
                        Ok(request) => service.handle(request).await,
                        Err(_) => Reply::status("INVALID_REQUEST", ""),
                    }
                } else {
                    Reply::status("BUSY", "")
                };
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
            async move {
                let reply = if body.len() > 65536 {
                    AdminReply {
                        status: "INVALID_REQUEST".into(),
                        ..Default::default()
                    }
                } else if let Ok(_permit) = requests.try_acquire_owned() {
                    match serde_json::from_slice::<AdminRequest>(&body) {
                        Ok(request) => admin.handle(request).await,
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
            "Готово • слушаем {} • транспорт: {}",
            config.listen,
            if config.development_plaintext {
                "TCP (development)"
            } else {
                "mTLS"
            }
        ),
        &format!(
            "Ready • listening on {} • transport: {}",
            config.listen,
            if config.development_plaintext {
                "TCP (development)"
            } else {
                "mTLS"
            }
        ),
    );
    server
        .with_hello_timeout(Duration::from_secs(5))
        .with_limits(ServerLimits {
            max_connections: 64,
            max_buffered_request_bytes: 8 * 1024 * 1024,
            frame_read_timeout: Duration::from_secs(120),
            ..ServerLimits::default()
        })
        .run()
        .await
        .context("MoonLightBridge backend listener stopped with an error")?;
    Ok(())
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
}
