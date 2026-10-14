use crate::{
    Reply, Request,
    captcha::{self, Challenge},
    storage::Storage,
};
use argon2::{
    Argon2, PasswordHash, PasswordHasher, PasswordVerifier,
    password_hash::{SaltString, rand_core::OsRng},
};
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
    pub(crate) authenticated: Option<Instant>,
}
pub struct Service {
    storage: Storage,
    pub(crate) sessions: Mutex<HashMap<String, Session>>,
    tombstones: Mutex<Tombstones>,
    policy: Policy,
    workers: Arc<Semaphore>,
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
        })
    }
    pub async fn handle(&self, mut r: Request) -> Reply {
        let id = r.session.clone();
        if r.op == "snapshot" {
            return match self.storage.snapshot(now()).await {
                Ok((green, premium_names)) => Reply {
                    green,
                    premium_names,
                    ..Reply::status("SNAPSHOT", &id)
                },
                Err(_) => Reply::status("UNAVAILABLE", &id),
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
        if uuid::Uuid::parse_str(&id).is_err() || r.password.len() > 256 || r.answer.len() > 32 {
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
        if !matches!(r.op.as_str(), "begin" | "answer" | "login" | "register") {
            return Reply::status("INVALID_REQUEST", &id);
        }
        if s.authenticated.is_some() {
            return Reply::status("AUTHENTICATED", &id);
        }
        if let Some(c) = &s.challenge {
            if time >= s.challenge_expires {
                return Reply::status("EXPIRED", &id);
            }
            if r.op == "begin" {
                return Reply {
                    image: c.image.clone(),
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
            s.authenticated = Some(time);
            s.expires = time + Duration::from_secs(self.policy.authenticated_seconds);
            return Reply::status("AUTHENTICATED", &id);
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
                s.authenticated = Some(Instant::now());
                s.expires = Instant::now() + Duration::from_secs(self.policy.authenticated_seconds);
                s.attempts = 0;
                Reply::status("AUTHENTICATED", &id)
            }
            Ok(Err(_)) => Reply::status("INVALID_CREDENTIALS", &id),
            Err(_) => Reply::status("UNAVAILABLE", &id),
        }
    }
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
