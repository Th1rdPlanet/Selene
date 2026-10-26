use crate::{
    PremiumTrust, Reply, Request,
    captcha::{self, Challenge},
    storage::Storage,
    totp::{SecretCipher, TotpEngine, generate_recovery_codes, recovery_hash},
};
use argon2::{Algorithm, Argon2, Params, PasswordHasher, PasswordVerifier, Version};
use base64::Engine;
use data_encoding::BASE32_NOPAD;
use rand::{RngCore, rngs::OsRng as RandomOsRng};
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
    /// Argon2id lanes computed in parallel. The memory and time cost stay the same, so an
    /// attacker pays the same per guess while a login finishes on several cores.
    pub password_lanes: u32,
    pub tombstone_capacity: usize,
}
#[derive(Clone, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct TotpPolicy {
    pub issuer: String,
    pub account_label: String,
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
            account_label: "{username}".into(),
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
            // One Argon2 worker per core; lanes of concurrent hashes share the same cores.
            password_workers: std::thread::available_parallelism().map_or(2, |n| n.get()),
            password_lanes: 4,
            tombstone_capacity: 20000,
        }
    }
}
pub(crate) struct Session {
    name: String,
    ip: String,
    premium: bool,
    password_trusted: bool,
    totp_trusted: bool,
    maintenance_revision: u64,
    maintenance_enable_captcha: bool,
    maintenance_verify_captcha: bool,
    maintenance_enable_password: bool,
    maintenance_verify_password: bool,
    maintenance_enable_totp: bool,
    maintenance_verify_totp: bool,
    pub(crate) expires: Instant,
    challenge_expires: Instant,
    pub(crate) challenge: Option<Challenge>,
    captcha_kind: String,
    challenge_nonce: String,
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
        let engine = TotpEngine::with_account_label(
            &totp_policy.issuer,
            &totp_policy.account_label,
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
                && (1..=16).contains(&policy.password_lanes)
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
    /// Pending and authenticated session counts for metrics; None while the table is busy.
    pub fn session_counts(&self) -> Option<(usize, usize, usize)> {
        let sessions = self.sessions.try_lock().ok()?;
        let authenticated = sessions
            .values()
            .filter(|s| s.authenticated.is_some())
            .count();
        let maintenance = sessions.values().filter(|s| s.maintenance_revision > 0).count();
        Some((sessions.len() - authenticated, authenticated, maintenance))
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
            || r.captcha_kind.len() > 32
            || r.challenge_nonce.len() > 128
            || !matches!(
                r.captcha_kind.as_str(),
                "" | "DIALOG_FONT" | "MAP" | "INTERACTIVE"
            )
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
            if r.maintenance_revision > 0
                && (r.maintenance_verify_captcha && !r.maintenance_enable_captcha
                    || r.maintenance_verify_password && !r.maintenance_enable_password
                    || r.maintenance_verify_totp && !r.maintenance_enable_totp)
            {
                return Reply::status("INVALID_REQUEST", &id);
            }
            let captcha_enabled = if r.maintenance_revision > 0 {
                r.maintenance_enable_captcha
            } else {
                r.captcha_required
            };
            let interactive = captcha_enabled && r.captcha_kind == "INTERACTIVE";
            let mut nonce_bytes = [0_u8; 32];
            if interactive {
                RandomOsRng.fill_bytes(&mut nonce_bytes);
            }
            sessions.insert(
                id.clone(),
                Session {
                    name: r.username.clone(),
                    ip: r.ip.clone(),
                    premium: r.premium,
                    password_trusted: r.password_trusted,
                    totp_trusted: r.totp_trusted,
                    maintenance_revision: r.maintenance_revision,
                    maintenance_enable_captcha: r.maintenance_enable_captcha,
                    maintenance_verify_captcha: r.maintenance_verify_captcha,
                    maintenance_enable_password: r.maintenance_enable_password,
                    maintenance_verify_password: r.maintenance_verify_password,
                    maintenance_enable_totp: r.maintenance_enable_totp,
                    maintenance_verify_totp: r.maintenance_verify_totp,
                    expires: time + Duration::from_secs(self.policy.pending_seconds),
                    challenge_expires: time + Duration::from_secs(self.policy.captcha_seconds),
                    challenge: if captcha_enabled && !interactive {
                        Some(crate::traces::span_sync(
                            "captcha.generate",
                            captcha::generate,
                        ))
                    } else {
                        None
                    },
                    captcha_kind: r.captcha_kind.clone(),
                    challenge_nonce: if interactive {
                        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(nonce_bytes)
                    } else {
                        String::new()
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
        if s.name != r.username || s.ip != r.ip || !maintenance_matches(s, &r) {
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
                | "interactive_mistake"
                | "interactive_complete"
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
        if !s.challenge_nonce.is_empty() {
            if s.captcha_kind != "INTERACTIVE" {
                return Reply::status("INVALID_REQUEST", &id);
            }
            if time >= s.challenge_expires {
                return Reply::status("EXPIRED", &id);
            }
            if r.op == "begin" {
                let mut reply = Reply::status("CAPTCHA_INTERACTIVE", &id);
                reply.challenge_nonce = s.challenge_nonce.clone();
                return reply;
            }
            if !matches!(
                r.op.as_str(),
                "interactive_mistake" | "interactive_complete"
            ) || !constant_time_eq(r.challenge_nonce.as_bytes(), s.challenge_nonce.as_bytes())
            {
                return Reply::status("INVALID_REQUEST", &id);
            }
            if r.op == "interactive_mistake" {
                s.attempts += 1;
                if s.attempts >= self.policy.max_attempts {
                    return Reply::status("RATE_LIMITED", &id);
                }
                let mut reply = Reply::status("CAPTCHA_INTERACTIVE", &id);
                reply.challenge_nonce = s.challenge_nonce.clone();
                return reply;
            }
            s.challenge_nonce.clear();
            r.op = "answer".into();
        } else if matches!(
            r.op.as_str(),
            "interactive_mistake" | "interactive_complete"
        ) {
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
            if (s.maintenance_revision == 0 || s.maintenance_verify_captcha) && r.answer != c.answer {
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
        if s.maintenance_revision > 0 && account.is_none() {
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
        if s.password_trusted
            && account
                .as_ref()
                .is_some_and(|a| !a.premium && !a.hash.is_empty())
        {
            return self.finish_identity(s, &id, time).await;
        }
        if s.maintenance_revision > 0 && !s.maintenance_enable_password {
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
        if s.maintenance_revision > 0 && r.op == "register" {
            return Reply::status("INVALID_REQUEST", &id);
        }
        if s.maintenance_revision > 0 && r.op == "login" && !s.maintenance_verify_password {
            return self.finish_identity(s, &id, time).await;
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
        let previous_hash = hash.clone();
        let registering = r.op == "register";
        let password = r.password;
        let lanes = self.policy.password_lanes;
        let stage = if registering {
            "argon2.hash"
        } else {
            "argon2.verify"
        };
        let result = crate::traces::span(
            stage,
            tokio::task::spawn_blocking(move || {
                let _permit = permit;
                let argon = password_hasher(lanes);
                if registering {
                    argon
                        .hash_password(password.as_bytes())
                        .map(|h| PasswordOutcome::Registered(h.to_string()))
                        .map_err(|_| ())
                } else {
                    // The stored hash carries its own parameters, so older hashes still verify.
                    let Some(stored) = hash else { return Err(()) };
                    if argon
                        .verify_password(password.as_bytes(), stored.as_str())
                        .is_err()
                    {
                        return Err(());
                    }
                    // Upgrade hashes made with other parameters while the password is at hand.
                    let rehash = (!stored.starts_with(&password_hash_prefix(lanes)))
                        .then(|| argon.hash_password(password.as_bytes()).ok())
                        .flatten()
                        .map(|h| h.to_string());
                    Ok(PasswordOutcome::Verified { rehash })
                }
            }),
        )
        .await;
        if Instant::now() >= s.expires {
            return Reply::status("EXPIRED", &id);
        }
        match result {
            Ok(Ok(outcome)) => {
                match outcome {
                    PasswordOutcome::Registered(hash) => {
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
                    PasswordOutcome::Verified {
                        rehash: Some(new_hash),
                    } => {
                        // Best effort: the login already succeeded, and a failed upgrade is
                        // retried on the next login.
                        if let Some(old_hash) = previous_hash {
                            let _ = self
                                .storage
                                .upgrade_password_hash(&s.name, &old_hash, &new_hash)
                                .await;
                        }
                    }
                    PasswordOutcome::Verified { rehash: None } => {}
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
                    if s.maintenance_revision > 0 && !s.maintenance_enable_totp {
                        let mut reply = authenticate(s, id, time, &self.policy);
                        reply.totp_enabled = true;
                        return reply;
                    }
                    if s.totp_trusted {
                        let mut reply = authenticate(s, id, time, &self.policy);
                        reply.totp_enabled = true;
                        return reply;
                    }
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
        if s.maintenance_revision > 0 && !s.maintenance_verify_totp {
            s.second_factor_attempts = 0;
            let mut reply = authenticate(s, id, time, &self.policy);
            reply.totp_enabled = true;
            return reply;
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
                let mut reply = authenticate(s, id, time, &self.policy);
                reply.totp_enabled = true;
                reply
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

fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    left.iter()
        .zip(right)
        .fold(0_u8, |diff, (a, b)| diff | (a ^ b))
        == 0
}

fn maintenance_matches(session: &Session, request: &Request) -> bool {
    session.maintenance_revision == request.maintenance_revision
        && session.maintenance_enable_captcha == request.maintenance_enable_captcha
        && session.maintenance_verify_captcha == request.maintenance_verify_captcha
        && session.maintenance_enable_password == request.maintenance_enable_password
        && session.maintenance_verify_password == request.maintenance_verify_password
        && session.maintenance_enable_totp == request.maintenance_enable_totp
        && session.maintenance_verify_totp == request.maintenance_verify_totp
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

/// Result of the blocking Argon2 work for one login or registration.
enum PasswordOutcome {
    Registered(String),
    Verified { rehash: Option<String> },
}

/// Argon2id with the OWASP baseline memory and time cost, split across `lanes` lanes. With the
/// `parallel` feature the lanes run on several cores, which cuts login latency without lowering
/// the per-guess cost.
fn password_hasher(lanes: u32) -> Argon2<'static> {
    let params = Params::new(Params::DEFAULT_M_COST, Params::DEFAULT_T_COST, lanes, None)
        .expect("validated Argon2 parameters");
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
}

/// PHC prefix of hashes made with the current parameters; anything else is rehashed on login.
fn password_hash_prefix(lanes: u32) -> String {
    format!(
        "$argon2id$v=19$m={},t={},p={lanes}$",
        Params::DEFAULT_M_COST,
        Params::DEFAULT_T_COST
    )
}
