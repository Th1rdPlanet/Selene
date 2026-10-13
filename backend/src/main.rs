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
    let config: Config = toml::from_str(&std::fs::read_to_string(path)?)?;
    let address: SocketAddr = config.listen.parse()?;
    anyhow::ensure!(
        !config.development_plaintext || address.ip().is_loopback(),
        "development plaintext must bind loopback"
    );
    let url = std::env::var(&config.database_url_env)
        .map_err(|_| anyhow::anyhow!("database URL environment variable is missing"))?;
    let service = Arc::new(Service::new(&url, config.policy).await?);
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
        Server::bind_tcp(&config.listen, router).await?
    } else {
        let tls = moonlight_bridge_server::tls::load_mtls_server_config(
            config.certificate,
            config.private_key,
            config.client_ca,
        )?;
        Server::bind_tls(&config.listen, router, tls).await?
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
        .await?;
    Ok(())
}
