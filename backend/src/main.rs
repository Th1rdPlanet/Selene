use anyhow::Context;
use moonlight_bridge_server::{Router, Server, ServerLimits};
use selene_backend::{
    Reply, Request,
    service::{Policy, Service},
};
use serde::Deserialize;
use std::{net::SocketAddr, sync::Arc, time::Duration};
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Config {
    listen: String,
    database_url_env: String,
    certificate: String,
    private_key: String,
    client_ca: String,
    #[serde(default)]
    development_plaintext: bool,
    #[serde(default)]
    policy: Policy,
}
#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let path = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "backend/config.toml".into());
    let source = std::fs::read_to_string(&path)
        .with_context(|| format!("cannot read backend config {path}"))?;
    let config: Config = toml::from_str(&source)
        .with_context(|| format!("invalid TOML in backend config {path}"))?;
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
    let service = Arc::new(Service::new(&url, config.policy).await.with_context(|| {
        format!(
            "cannot initialize database configured by {} (value hidden)",
            config.database_url_env
        )
    })?);
    let requests = Arc::new(tokio::sync::Semaphore::new(32));
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
        .build();
    let server = if config.development_plaintext {
        eprintln!("Selene development plaintext enabled on loopback");
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
    server
        .with_hello_timeout(Duration::from_secs(5))
        .with_limits(ServerLimits {
            max_connections: 64,
            max_buffered_request_bytes: 8 * 1024 * 1024,
            frame_read_timeout: Duration::from_secs(10),
            ..ServerLimits::default()
        })
        .run()
        .await
        .context("MoonLightBridge backend listener stopped with an error")?;
    Ok(())
}
