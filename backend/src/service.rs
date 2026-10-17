use crate::{
    PremiumTrust, Reply, Request,
    captcha::{self, Challenge},
    storage::Storage,
    totp::{SecretCipher, TotpEngine, generate_recovery_codes, recovery_hash},
};
use argon2::{
    Argon2, PasswordHash, PasswordHasher, PasswordVerifier,
    password_hash::{SaltString, rand_core::OsRng},
};
use base64::Engine;
use data_encoding::BASE32_NOPAD;
use serde::Deserialize;
use std::{
    collections::{HashMap, VecDeque},
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    sync::{Mutex, Semaphore},
    time::Instant,
};
#[derive(Clone, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Policy {
    pub session_capacity: usize,
    pub pending_seconds: u64,
    pub authenticated_seconds: u64,
    pub captcha_seconds: u64,
    pub max_attempts: u32,
    pub trust_qualify_seconds: u64,
    pub trust_ttl_seconds: u64,
    pub password_workers: usize,
    pub tombstone_capacity: usize,
}
#[derive(Clone, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct TotpPolicy {
    pub issuer: String,
    pub period_seconds: u64,
    pub accepted_window: u64,
    pub setup_seconds: u64,
    pub max_attempts: u32,
    pub recovery_codes: usize,
    pub premium_trust_seconds: u64,
    pub snapshot_capacity: usize,
}
impl Default for TotpPolicy {
    fn default() -> Self {
        Self {
            issuer: "Selene".into(),
            period_seconds: 30,
            accepted_window: 1,
            setup_seconds: 300,
            max_attempts: 3,
            recovery_codes: 10,
            premium_trust_seconds: 2_592_000,
            snapshot_capacity: 4096,
        }
    }
}
#[derive(Clone)]
pub struct TotpKey([u8; 32]);
impl TotpKey {
    pub fn from_base64(value: &str) -> anyhow::Result<Self> {
        let decoded = base64::engine::general_purpose::STANDARD
            .decode(value)
            .map_err(|_| anyhow::anyhow!("TOTP master key must be valid Base64"))?;
        anyhow::ensure!(
            decoded.len() == 32,
            "TOTP master key must decode to exactly 32 bytes"
        );
        let mut key = [0; 32];
        key.copy_from_slice(&decoded);
        Ok(Self(key))
    }
    pub fn into_bytes(self) -> [u8; 32] {
        self.0
    }
}
impl Default for Policy {
    fn default() -> Self {
        Self {
            session_capacity: 10000,
            pending_seconds: 180,
            authenticated_seconds: 86400,
            captcha_seconds: 120,
            max_attempts: 3,
            trust_qualify_seconds: 600,
            trust_ttl_seconds: 604800,
            password_workers: 2,
            tombstone_capacity: 20000,
        }
    }
}
pub(crate) struct Session {
    name: String,
    ip: String,
    premium: bool,
    pub(crate) expires: Instant,
    challenge_expires: Instant,
    pub(crate) challenge: Option<Challenge>,
    attempts: u32,
    second_factor_attempts: u32,
    identity_verified: bool,
    pending_totp: Option<PendingTotp>,
    pub(crate) authenticated: Option<Instant>,
}
struct PendingTotp {
    secret: zeroize::Zeroizing<Vec<u8>>,
    expires: Instant,
}
struct TotpRuntime {
    policy: TotpPolicy,
    engine: TotpEngine,
    cipher: SecretCipher,
    key: [u8; 32],
}
pub struct Service {
    storage: Storage,
    pub(crate) sessions: Mutex<HashMap<String, Session>>,
    tombstones: Mutex<Tombstones>,
    policy: Policy,
    workers: Arc<Semaphore>,
    totp: Option<TotpRuntime>,
}
fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}
impl Service {
    #[cfg(test)]
    pub(crate) fn storage(&self) -> &Storage {
        &self.storage
    }
    pub fn storage_clone(&self) -> Storage {
        self.storage.clone()
    }
    pub async fn new(url: &str, policy: Policy) -> anyhow::Result<Self> {
        Self::new_internal(url, policy, None).await
    }
    pub async fn new_with_totp(
        url: &str,
        policy: Policy,
        totp_policy: TotpPolicy,
        key: [u8; 32],
    ) -> anyhow::Result<Self> {
        anyhow::ensure!(
            totp_policy.setup_seconds > 0
                && totp_policy.max_attempts > 0
                && (1..=32).contains(&totp_policy.recovery_codes)
                && totp_policy.premium_trust_seconds > 0
                && totp_policy.snapshot_capacity > 0,
            "invalid TOTP policy limits"
        );
        let engine = TotpEngine::new(
            &totp_policy.issuer,
            totp_policy.period_seconds,
            totp_policy.accepted_window,
        )?;
        Self::new_internal(
            url,
            policy,
            Some(TotpRuntime {
                policy: totp_policy,
                engine,
                cipher: SecretCipher::new(key),
                key,
            }),
        )
        .await
    }
    async fn new_internal(
        url: &str,
        policy: Policy,
        totp: Option<TotpRuntime>,
    ) -> anyhow::Result<Self> {
        anyhow::ensure!(
            policy.session_capacity > 0
                && policy.password_workers > 0
                && policy.tombstone_capacity > 0
                && policy.max_attempts > 0
                && policy.pending_seconds > 0
                && policy.captcha_seconds > 0
                && policy.authenticated_seconds > policy.trust_qualify_seconds
                && policy.trust_ttl_seconds > 0,
            "invalid policy limits"
        );
        Ok(Self {
            storage: Storage::open(url).await?,
            sessions: Mutex::new(HashMap::new()),
            tombstones: Mutex::new(Tombstones::default()),
            workers: Arc::new(Semaphore::new(policy.password_workers)),
            policy,
            totp,
        })
    }
    pub async fn handle(&self, mut r: Request) -> Reply {
        let id = r.session.clone();
        if r.op == "snapshot" {
            return match (
                self.storage.snapshot(now()).await,
                self.premium_snapshot().await,
            ) {
                (Ok((green, premium_names)), Ok(premium_trust)) => Reply {
                    green,
                    premium_names,
                    premium_trust,
                    ..Reply::status("SNAPSHOT", &id)
                },
                _ => Reply::status("UNAVAILABLE", &id),
            };
        }
        if !(3..=16).contains(&r.username.len())
            || !r
                .username
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_')
        {
            return Reply::status("INVALID_REQUEST", &id);
        }
        r.username.make_ascii_lowercase();
        if r.op == "profile" {
            return match self.storage.account(&r.username).await {
                Ok(a) => Reply::status(
                    match a {
                        None => "ACCOUNT_NEW",
                        Some(a) if a.premium => "ACCOUNT_PREMIUM",
                        _ => "ACCOUNT_OFFLINE",
                    },
                    &id,
                ),
                Err(_) => Reply::status("UNAVAILABLE", &id),
            };
        }
        let Ok(ip) = r.ip.parse::<std::net::IpAddr>() else {
            return Reply::status("INVALID_REQUEST", &id);
        };
        r.ip = ip.to_string();
        if uuid::Uuid::parse_str(&id).is_err()
            || r.password.len() > 256
            || r.answer.len() > 32
            || r.totp_code.len() > 64
            || r.recovery_code.len() > 128
        {
            return Reply::status("INVALID_REQUEST", &id);
        }
        // One state transition at a time; overload is rejected, never queued without bound.
        let Ok(mut sessions) = self.sessions.try_lock() else {
            return Reply::status("BUSY", &id);
        };
        let time = Instant::now();
        let expired: Vec<String> = sessions
            .iter()
            .filter(|(_, session)| time >= session.expires)
            .map(|(id, _)| id.clone())
            .collect();
        sessions.retain(|_, session| time < session.expires);
        let mut tombstones = self.tombstones.lock().await;
        tombstones.prune(time);
        for expired_id in expired {
            remember_tombstone(&mut tombstones, expired_id, time, &self.policy);
        }
        if tombstones.contains(&id) {
            return Reply::status("EXPIRED", &id);
        }
        if r.op == "begin" && !sessions.contains_key(&id) {
            if sessions.len() >= self.policy.session_capacity {
                return Reply::status("BUSY", &id);
            }
            sessions.insert(
                id.clone(),
                Session {
                    name: r.username.clone(),
                    ip: r.ip.clone(),
                    premium: r.premium,
                    expires: time + Duration::from_secs(self.policy.pending_seconds),
                    challenge_expires: time + Duration::from_secs(self.policy.captcha_seconds),
                    challenge: if r.captcha_required {
                        Some(captcha::generate())
                    } else {
                        None
                    },
                    attempts: 0,
                    second_factor_attempts: 0,
                    identity_verified: false,
                    pending_totp: None,
                    authenticated: None,
                },
            );
        }
        if r.op == "close" {
            let Some(s) = sessions.get(&id) else {
                return Reply::status("EXPIRED", &id);
            };
            if s.name != r.username || s.ip != r.ip {
                return Reply::status("INVALID_REQUEST", &id);
            }
            sessions.remove(&id);
            remember_tombstone(&mut tombstones, id.clone(), time, &self.policy);
            return Reply::status("CLOSED", &id);
        }
        drop(tombstones);
        let Some(s) = sessions.get_mut(&id) else {
            return Reply::status("EXPIRED", &id);
        };
        if s.name != r.username || s.ip != r.ip {
            return Reply::status("INVALID_REQUEST", &id);
        }
        if s.attempts >= self.policy.max_attempts {
            let mut reply = Reply::status("RATE_LIMITED", &id);
            reply.retry_after = s
                .expires
                .saturating_duration_since(time)
                .as_secs()
                .max(1)
                .min(u32::MAX as u64) as u32;
            return reply;
        }
        if r.op == "trust" {
            if s.authenticated.is_some_and(|t| {
                time.duration_since(t) >= Duration::from_secs(self.policy.trust_qualify_seconds)
            }) {
                return Reply::status(
                    if self
                        .storage
                        .trust(
                            &s.name,
                            &s.ip,
                            now().saturating_add(self.policy.trust_ttl_seconds as i64),
                        )
                        .await
                        .is_ok()
                    {
                        "TRUSTED"
                    } else {
                        "UNAVAILABLE"
                    },
                    &id,
                );
            }
            return Reply::status("INVALID_REQUEST", &id);
        }
        if !matches!(
            r.op.as_str(),
            "begin"
                | "answer"
                | "login"
                | "register"
                | "totp_verify"
                | "totp_status"
                | "totp_setup_begin"
                | "totp_setup_confirm"
                | "totp_disable"
                | "totp_recovery_regenerate"
                | "premium_trust"
        ) {
            return Reply::status("INVALID_REQUEST", &id);
        }
        if s.authenticated.is_some() {
            return self.handle_authenticated_totp(s, &r, &id, time).await;
        }
        if r.op == "totp_verify" {
            return self.verify_login_totp(s, &r, &id, time).await;
        }
        if r.op.starts_with("totp_") || r.op == "premium_trust" {
            return Reply::status("INVALID_REQUEST", &id);
        }
        if let Some(c) = &s.challenge {
            if time >= s.challenge_expires {
                return Reply::status("EXPIRED", &id);
            }
            if r.op == "begin" {
                return Reply {
                    image: c.image.clone(),
                    captcha_glyphs: c.glyphs.clone(),
                    ..Reply::status("CAPTCHA", &id)
                };
            }
            if r.op != "answer" {
                return Reply::status("INVALID_REQUEST", &id);
            }
            if r.answer != c.answer {
                s.attempts += 1;
                return Reply::status(
                    if s.attempts >= self.policy.max_attempts {
                        "RATE_LIMITED"
                    } else {
                        "CAPTCHA_INVALID"
                    },
                    &id,
                );
            }
            s.challenge = None;
        }
        let account = match self.storage.account(&s.name).await {
            Ok(a) => a,
            Err(_) => return Reply::status("UNAVAILABLE", &id),
        };
        if account.as_ref().is_some_and(|a| a.premium) && !s.premium {
            return Reply::status("INVALID_CREDENTIALS", &id);
        }
        if s.premium && account.as_ref().is_none_or(|a| a.premium) {
            if account.is_none() {
                match self.storage.register(&s.name, "", true).await {
                    Ok(true) => {}
                    Ok(false) => return Reply::status("ACCOUNT_EXISTS", &id),
                    Err(_) => return Reply::status("UNAVAILABLE", &id),
                }
            }
            return self.finish_identity(s, &id, time).await;
        }
        if matches!(r.op.as_str(), "begin" | "answer") {
            return Reply::status(
                if account
                    .as_ref()
                    .is_some_and(|account| !account.hash.is_empty())
                {
                    "LOGIN"
                } else {
                    "REGISTER"
                },
                &id,
            );
        }
        if !(8..=256).contains(&r.password.len()) {
            return Reply::status("INVALID_REQUEST", &id);
        }
        let reset_account = account
            .as_ref()
            .is_some_and(|account| account.hash.is_empty());
        if r.op == "login" && reset_account {
            return Reply::status("INVALID_REQUEST", &id);
        }
        if r.op == "register" && account.is_some() && !reset_account {
            return Reply::status("ACCOUNT_EXISTS", &id);
        }
        s.attempts += 1;
        let Ok(permit) = self.workers.clone().try_acquire_owned() else {
            return Reply::status("BUSY", &id);
        };
        let hash = account.map(|a| a.hash);
        let registering = r.op == "register";
        let password = r.password;
        let result = tokio::task::spawn_blocking(move || {
            let _permit = permit;
            let argon = Argon2::default();
            if registering {
                argon
                    .hash_password(password.as_bytes(), &SaltString::generate(&mut OsRng))
                    .map(|h| Some(h.to_string()))
                    .map_err(|_| ())
            } else {
                let valid = hash
                    .and_then(|h| {
                        PasswordHash::new(&h).ok().map(|parsed| {
                            argon.verify_password(password.as_bytes(), &parsed).is_ok()
                        })
                    })
                    .unwrap_or(false);
                if valid { Ok(None) } else { Err(()) }
            }
        })
        .await;
        if Instant::now() >= s.expires {
            return Reply::status("EXPIRED", &id);
        }
        match result {
            Ok(Ok(hash)) => {
                if let Some(hash) = hash {
                    let stored = if reset_account {
                        self.storage.register_reset(&s.name, &hash).await
                    } else {
                        self.storage.register(&s.name, &hash, false).await
                    };
                    match stored {
                        Ok(true) => {}
                        Ok(false) => return Reply::status("ACCOUNT_EXISTS", &id),
                        Err(_) => return Reply::status("UNAVAILABLE", &id),
                    }
                }
                s.attempts = 0;
                self.finish_identity(s, &id, Instant::now()).await
            }
            Ok(Err(_)) => Reply::status("INVALID_CREDENTIALS", &id),
            Err(_) => Reply::status("UNAVAILABLE", &id),
        }
    }

    async fn premium_snapshot(
        &self,
    ) -> anyhow::Result<std::collections::BTreeMap<String, PremiumTrust>> {
        let Some(totp) = &self.totp else {
            return Ok(std::collections::BTreeMap::new());
        };
        Ok(self
            .storage
            .premium_snapshot(now(), totp.policy.snapshot_capacity)
            .await?
            .into_iter()
            .map(|(name, trust)| {
                (
                    name,
                    PremiumTrust {
                        ip: trust.ip,
                        expires_at: trust.expires_at,
                    },
                )
            })
            .collect())
    }

    async fn finish_identity(&self, s: &mut Session, id: &str, time: Instant) -> Reply {
        if self.totp.is_some() {
            match self.storage.totp_state(&s.name).await {
                Ok(Some(_)) => {
                    s.identity_verified = true;
                    s.second_factor_attempts = 0;
                    let mut reply = Reply::status("TOTP_REQUIRED", id);
                    reply.totp_enabled = true;
                    return reply;
                }
                Ok(None) => {}
                Err(_) => return Reply::status("UNAVAILABLE", id),
            }
        }
        authenticate(s, id, time, &self.policy)
    }

    async fn verify_login_totp(
        &self,
        s: &mut Session,
        r: &Request,
        id: &str,
        time: Instant,
    ) -> Reply {
        let Some(runtime) = &self.totp else {
            return Reply::status("INVALID_REQUEST", id);
        };
        if !s.identity_verified {
            return Reply::status("INVALID_REQUEST", id);
        }
        if s.second_factor_attempts >= runtime.policy.max_attempts {
            return Reply::status("RATE_LIMITED", id);
        }
        match self
            .verify_credential(&s.name, &r.totp_code, &r.recovery_code, true)
            .await
        {
            Ok(()) => {
                s.second_factor_attempts = 0;
                authenticate(s, id, time, &self.policy)
            }
            Err(status) => {
                s.second_factor_attempts += 1;
                Reply::status(
                    if s.second_factor_attempts >= runtime.policy.max_attempts {
                        "RATE_LIMITED"
                    } else {
                        status
                    },
                    id,
                )
            }
        }
    }

    async fn handle_authenticated_totp(
        &self,
        s: &mut Session,
        r: &Request,
        id: &str,
        time: Instant,
    ) -> Reply {
        let Some(runtime) = &self.totp else {
            return Reply::status("AUTHENTICATED", id);
        };
        match r.op.as_str() {
            "totp_status" => {
                let mut reply = Reply::status("TOTP_STATUS", id);
                match self.storage.totp_state(&s.name).await {
                    Ok(state) => reply.totp_enabled = state.is_some(),
                    Err(_) => return Reply::status("UNAVAILABLE", id),
                }
                reply
            }
            "totp_setup_begin" => {
                match self.storage.totp_state(&s.name).await {
                    Ok(Some(_)) => return Reply::status("TOTP_ALREADY_ENABLED", id),
                    Err(_) => return Reply::status("UNAVAILABLE", id),
                    _ => {}
                }
                let secret = runtime.engine.generate_secret();
                let uri = match runtime.engine.provisioning_uri(&s.name, &secret) {
                    Ok(uri) => uri,
                    Err(_) => return Reply::status("UNAVAILABLE", id),
                };
                let encoded = BASE32_NOPAD.encode(&secret);
                s.pending_totp = Some(PendingTotp {
                    secret,
                    expires: time + Duration::from_secs(runtime.policy.setup_seconds),
                });
                let mut reply = Reply::status("TOTP_SETUP", id);
                reply.totp_uri = uri;
                reply.totp_secret = encoded;
                reply
            }
            "totp_setup_confirm" => {
                let Some(pending) = s.pending_totp.take() else {
                    return Reply::status("INVALID_REQUEST", id);
                };
                if time >= pending.expires {
                    return Reply::status("EXPIRED", id);
                }
                let unix = now().max(0) as u64;
                let Some(step) =
                    runtime
                        .engine
                        .verify_step(&pending.secret, &r.totp_code, unix, None)
                else {
                    s.pending_totp = Some(pending);
                    return Reply::status("TOTP_INVALID", id);
                };
                let codes = match generate_recovery_codes(runtime.policy.recovery_codes) {
                    Ok(codes) => codes,
                    Err(_) => return Reply::status("UNAVAILABLE", id),
                };
                let hashes = match codes
                    .iter()
                    .map(|code| recovery_hash(&runtime.key, code))
                    .collect::<anyhow::Result<Vec<_>>>()
                {
                    Ok(hashes) => hashes,
                    Err(_) => return Reply::status("UNAVAILABLE", id),
                };
                let encrypted = match runtime.cipher.encrypt(&pending.secret) {
                    Ok(secret) => secret,
                    Err(_) => return Reply::status("UNAVAILABLE", id),
                };
                if self
                    .storage
                    .enable_totp_at_step(&s.name, &encrypted, &hashes, now(), Some(step))
                    .await
                    .is_err()
                {
                    return Reply::status("UNAVAILABLE", id);
                }
                let mut reply = Reply::status("TOTP_RECOVERY", id);
                reply.totp_enabled = true;
                reply.recovery_codes = codes;
                reply
            }
            "totp_disable" => match self
                .verify_credential(&s.name, &r.totp_code, &r.recovery_code, true)
                .await
            {
                Ok(()) => match self.storage.disable_totp(&s.name).await {
                    Ok(true) => Reply::status("TOTP_DISABLED", id),
                    Ok(false) => Reply::status("TOTP_NOT_ENABLED", id),
                    Err(_) => Reply::status("UNAVAILABLE", id),
                },
                Err(status) => Reply::status(status, id),
            },
            "totp_recovery_regenerate" => {
                if !r.recovery_code.is_empty() {
                    return Reply::status("INVALID_REQUEST", id);
                }
                if let Err(status) = self
                    .verify_credential(&s.name, &r.totp_code, "", false)
                    .await
                {
                    return Reply::status(status, id);
                }
                let codes = match generate_recovery_codes(runtime.policy.recovery_codes) {
                    Ok(codes) => codes,
                    Err(_) => return Reply::status("UNAVAILABLE", id),
                };
                let hashes = match codes
                    .iter()
                    .map(|code| recovery_hash(&runtime.key, code))
                    .collect::<anyhow::Result<Vec<_>>>()
                {
                    Ok(hashes) => hashes,
                    Err(_) => return Reply::status("UNAVAILABLE", id),
                };
                match self.storage.replace_recovery(&s.name, &hashes, now()).await {
                    Ok(true) => {
                        let mut reply = Reply::status("TOTP_RECOVERY", id);
                        reply.totp_enabled = true;
                        reply.recovery_codes = codes;
                        reply
                    }
                    Ok(false) => Reply::status("TOTP_NOT_ENABLED", id),
                    Err(_) => Reply::status("UNAVAILABLE", id),
                }
            }
            "premium_trust" => {
                if !s.premium {
                    return Reply::status("INVALID_REQUEST", id);
                }
                let expiry = now().saturating_add(
                    runtime.policy.premium_trust_seconds.min(i64::MAX as u64) as i64,
                );
                match self.storage.set_premium_trust(&s.name, &s.ip, expiry).await {
                    Ok(()) => Reply::status("PREMIUM_TRUSTED", id),
                    Err(_) => Reply::status("UNAVAILABLE", id),
                }
            }
            _ => Reply::status("AUTHENTICATED", id),
        }
    }

    async fn verify_credential(
        &self,
        name: &str,
        totp_code: &str,
        recovery_code: &str,
        allow_recovery: bool,
    ) -> Result<(), &'static str> {
        let runtime = self.totp.as_ref().ok_or("INVALID_REQUEST")?;
        if allow_recovery && !recovery_code.is_empty() && totp_code.is_empty() {
            let hash =
                recovery_hash(&runtime.key, recovery_code).map_err(|_| "RECOVERY_INVALID")?;
            return match self.storage.consume_recovery(name, &hash).await {
                Ok(true) => Ok(()),
                Ok(false) => Err("RECOVERY_INVALID"),
                Err(_) => Err("UNAVAILABLE"),
            };
        }
        if totp_code.is_empty() || !recovery_code.is_empty() {
            return Err("TOTP_INVALID");
        }
        let state = self
            .storage
            .totp_state(name)
            .await
            .map_err(|_| "UNAVAILABLE")?
            .ok_or("TOTP_NOT_ENABLED")?;
        let secret = runtime
            .cipher
            .decrypt(&state.encrypted)
            .map_err(|_| "UNAVAILABLE")?;
        let step = runtime
            .engine
            .verify_step(&secret, totp_code, now().max(0) as u64, state.last_step)
            .ok_or("TOTP_INVALID")?;
        match self.storage.accept_totp_step(name, step).await {
            Ok(true) => Ok(()),
            Ok(false) => Err("TOTP_INVALID"),
            Err(_) => Err("UNAVAILABLE"),
        }
    }
}

fn authenticate(s: &mut Session, id: &str, time: Instant, policy: &Policy) -> Reply {
    s.authenticated = Some(time);
    s.identity_verified = true;
    s.expires = time + Duration::from_secs(policy.authenticated_seconds);
    Reply::status("AUTHENTICATED", id)
}

#[derive(Default)]
struct Tombstones {
    ids: HashMap<String, Instant>,
    order: VecDeque<(Instant, String)>,
}

impl Tombstones {
    fn prune(&mut self, time: Instant) {
        while self
            .order
            .front()
            .is_some_and(|(expires, _)| *expires <= time)
        {
            let (expires, id) = self.order.pop_front().expect("front exists");
            if self.ids.get(&id).is_some_and(|stored| *stored == expires) {
                self.ids.remove(&id);
            }
        }
    }

    fn contains(&self, id: &str) -> bool {
        self.ids.contains_key(id)
    }

    fn remember(&mut self, id: String, expires: Instant, capacity: usize) {
        if self.ids.contains_key(&id) {
            return;
        }
        while self.ids.len() >= capacity {
            let Some((old_expiry, old_id)) = self.order.pop_front() else {
                break;
            };
            if self
                .ids
                .get(&old_id)
                .is_some_and(|stored| *stored == old_expiry)
            {
                self.ids.remove(&old_id);
            }
        }
        self.ids.insert(id.clone(), expires);
        self.order.push_back((expires, id));
    }
}

fn remember_tombstone(tombstones: &mut Tombstones, id: String, time: Instant, policy: &Policy) {
    tombstones.remember(
        id,
        time + Duration::from_secs(policy.pending_seconds),
        policy.tombstone_capacity,
    );
}
