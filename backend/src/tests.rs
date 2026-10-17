use super::*;
fn request(op: &str) -> Request {
    Request {
        op: op.into(),
        session: uuid::Uuid::new_v4().to_string(),
        username: "Alice".into(),
        ip: "127.0.0.1".into(),
        password: "correct horse battery".into(),
        ..Default::default()
    }
}
async fn service() -> service::Service {
    service::Service::new("sqlite::memory:", service::Policy::default())
        .await
        .unwrap()
}
async fn totp_service() -> service::Service {
    service::Service::new_with_totp(
        "sqlite::memory:",
        service::Policy::default(),
        service::TotpPolicy::default(),
        [7; 32],
    )
    .await
    .unwrap()
}
#[tokio::test]
async fn register_login_and_case_normalization() {
    let s = service().await;
    let mut r = request("begin");
    assert_eq!(s.handle(r.clone()).await.status, "REGISTER");
    r.op = "register".into();
    assert_eq!(s.handle(r.clone()).await.status, "AUTHENTICATED");
    r.session = uuid::Uuid::new_v4().to_string();
    r.username = "ALICE".into();
    r.op = "begin".into();
    assert_eq!(s.handle(r.clone()).await.status, "LOGIN");
    r.op = "login".into();
    r.password = "wrong password".into();
    assert_eq!(s.handle(r.clone()).await.status, "INVALID_CREDENTIALS");
    r.password = "correct horse battery".into();
    assert_eq!(s.handle(r).await.status, "AUTHENTICATED");
}
#[tokio::test]
async fn captcha_cannot_be_bypassed_or_reset_by_begin() {
    let s = service().await;
    let mut r = request("begin");
    r.captcha_required = true;
    let first = s.handle(r.clone()).await;
    assert_eq!(first.status, "CAPTCHA");
    assert!(first.image.starts_with("iVBOR"));
    assert_eq!(first.captcha_glyphs.chars().count(), 5);
    assert!(
        first
            .captcha_glyphs
            .chars()
            .all(|c| ('\u{e000}'..='\u{e09f}').contains(&c))
    );
    r.op = "register".into();
    assert_eq!(s.handle(r.clone()).await.status, "INVALID_REQUEST");
    r.op = "answer".into();
    r.answer = "wrong".into();
    for _ in 0..2 {
        assert_eq!(s.handle(r.clone()).await.status, "CAPTCHA_INVALID");
    }
    r.op = "begin".into();
    r.captcha_required = false;
    let repeated = s.handle(r.clone()).await;
    assert_eq!(repeated.image, first.image);
    assert_eq!(repeated.captcha_glyphs, first.captcha_glyphs);
    r.op = "answer".into();
    assert_eq!(s.handle(r.clone()).await.status, "RATE_LIMITED");
    r.op = "begin".into();
    assert_eq!(s.handle(r).await.status, "RATE_LIMITED");
}
#[tokio::test]
async fn correct_captcha_and_premium_gate() {
    let s = service().await;
    let mut r = request("begin");
    r.captcha_required = true;
    r.premium = true;
    assert_eq!(s.handle(r.clone()).await.status, "CAPTCHA");
    r.answer = s
        .sessions
        .lock()
        .await
        .get(&r.session)
        .unwrap()
        .challenge
        .as_ref()
        .unwrap()
        .answer
        .clone();
    r.op = "answer".into();
    assert_eq!(s.handle(r.clone()).await.status, "AUTHENTICATED");
    r.op = "profile".into();
    assert_eq!(s.handle(r).await.status, "ACCOUNT_PREMIUM");
}
#[tokio::test]
async fn premium_claim_never_converts_offline() {
    let s = service().await;
    let mut r = request("begin");
    s.handle(r.clone()).await;
    r.op = "register".into();
    s.handle(r.clone()).await;
    r.session = uuid::Uuid::new_v4().to_string();
    r.op = "begin".into();
    r.premium = true;
    assert_eq!(s.handle(r.clone()).await.status, "LOGIN");
    r.op = "profile".into();
    assert_eq!(s.handle(r).await.status, "ACCOUNT_OFFLINE");
}
#[tokio::test]
async fn expiry_and_trust_require_elapsed_authenticated_time() {
    let s = service().await;
    let mut r = request("begin");
    s.handle(r.clone()).await;
    s.sessions.lock().await.get_mut(&r.session).unwrap().expires =
        tokio::time::Instant::now() - std::time::Duration::from_secs(1);
    r.op = "register".into();
    assert_eq!(s.handle(r.clone()).await.status, "EXPIRED");
    r.session = uuid::Uuid::new_v4().to_string();
    r.op = "begin".into();
    r.premium = true;
    assert_eq!(s.handle(r.clone()).await.status, "AUTHENTICATED");
    r.op = "trust".into();
    assert_eq!(s.handle(r.clone()).await.status, "INVALID_REQUEST");
    s.sessions
        .lock()
        .await
        .get_mut(&r.session)
        .unwrap()
        .authenticated = Some(tokio::time::Instant::now() - std::time::Duration::from_secs(601));
    assert_eq!(s.handle(r.clone()).await.status, "TRUSTED");
    r.op = "snapshot".into();
    assert_eq!(s.handle(r).await.green.get("alice").unwrap(), "127.0.0.1");
}
#[tokio::test]
async fn session_binding_and_unknown_premium_login_fail_closed() {
    let s = service().await;
    let mut r = request("begin");
    r.premium = true;
    s.handle(r.clone()).await;
    r.ip = "127.0.0.2".into();
    assert_eq!(s.handle(r.clone()).await.status, "INVALID_REQUEST");
    r.session = uuid::Uuid::new_v4().to_string();
    r.premium = false;
    assert_eq!(s.handle(r.clone()).await.status, "INVALID_CREDENTIALS");
}

#[tokio::test]
async fn storage_uniqueness_hashes_and_green_replacement_expiry() {
    let store = crate::storage::Storage::open("sqlite::memory:")
        .await
        .unwrap();
    let (a, b) = tokio::join!(
        store.register("Alice", "hash-a", false),
        store.register("ALICE", "hash-b", false)
    );
    assert_ne!(a.unwrap(), b.unwrap());
    assert!(store.account("alice").await.unwrap().is_some());
    store.trust("alice", "192.0.2.1", 200).await.unwrap();
    assert_eq!(
        store.snapshot(100).await.unwrap().0.get("alice").unwrap(),
        "192.0.2.1"
    );
    store.trust("alice", "192.0.2.2", 300).await.unwrap();
    let snapshot = store.snapshot(200).await.unwrap();
    assert_eq!(snapshot.0.len(), 1);
    assert_eq!(snapshot.0.get("alice").unwrap(), "192.0.2.2");
    assert!(store.snapshot(300).await.unwrap().0.is_empty());
}
#[tokio::test]
async fn capacity_and_close_tombstones_fail_closed() {
    let s = service::Service::new(
        "sqlite::memory:",
        service::Policy {
            session_capacity: 1,
            ..Default::default()
        },
    )
    .await
    .unwrap();
    let mut r = request("begin");
    assert_eq!(s.handle(r.clone()).await.status, "REGISTER");
    assert_eq!(s.handle(request("begin")).await.status, "BUSY");
    r.op = "close".into();
    assert_eq!(s.handle(r.clone()).await.status, "CLOSED");
    r.op = "begin".into();
    assert_eq!(s.handle(r).await.status, "EXPIRED");
    let replacement = request("begin");
    assert_eq!(s.handle(replacement).await.status, "REGISTER");
    assert_eq!(s.sessions.lock().await.len(), 1);
}

#[tokio::test]
async fn password_failures_survive_begin_replay() {
    let s = service().await;
    let mut r = request("begin");
    s.handle(r.clone()).await;
    r.op = "register".into();
    assert_eq!(s.handle(r.clone()).await.status, "AUTHENTICATED");
    r.session = uuid::Uuid::new_v4().to_string();
    r.op = "begin".into();
    s.handle(r.clone()).await;
    r.password = "incorrect password".into();
    for _ in 0..3 {
        r.op = "login".into();
        assert_eq!(s.handle(r.clone()).await.status, "INVALID_CREDENTIALS");
    }
    r.op = "begin".into();
    assert_eq!(s.handle(r.clone()).await.status, "RATE_LIMITED");
    r.op = "login".into();
    r.password = "correct horse battery".into();
    assert_eq!(s.handle(r).await.status, "RATE_LIMITED");
}

#[tokio::test]
async fn password_completion_after_session_expiry_cannot_register() {
    let s = service().await;
    let mut r = request("begin");
    s.handle(r.clone()).await;
    s.sessions.lock().await.get_mut(&r.session).unwrap().expires =
        tokio::time::Instant::now() + std::time::Duration::from_millis(1);
    r.op = "register".into();
    assert_eq!(s.handle(r.clone()).await.status, "EXPIRED");
    r.op = "profile".into();
    assert_eq!(s.handle(r).await.status, "ACCOUNT_NEW");
}

#[tokio::test]
async fn totp_setup_requires_confirmation_and_returns_recovery_once() {
    let s = totp_service().await;
    let mut r = request("begin");
    assert_eq!(s.handle(r.clone()).await.status, "REGISTER");
    r.op = "register".into();
    assert_eq!(s.handle(r.clone()).await.status, "AUTHENTICATED");
    r.op = "totp_setup_begin".into();
    let setup = s.handle(r.clone()).await;
    assert_eq!(setup.status, "TOTP_SETUP");
    assert!(setup.totp_uri.starts_with("otpauth://totp/"));
    assert!(!setup.totp_secret.is_empty());
    assert!(s.storage().totp_state("alice").await.unwrap().is_none());
    let secret = data_encoding::BASE32_NOPAD
        .decode(setup.totp_secret.as_bytes())
        .unwrap();
    let engine = crate::totp::TotpEngine::new("Selene", 30, 1).unwrap();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs();
    r.op = "totp_setup_confirm".into();
    r.totp_code = engine.code_at(&secret, now).unwrap();
    let confirmed = s.handle(r.clone()).await;
    assert_eq!(confirmed.status, "TOTP_RECOVERY");
    assert_eq!(confirmed.recovery_codes.len(), 10);
    assert!(confirmed.totp_enabled);
    assert!(s.storage().totp_state("alice").await.unwrap().is_some());
    assert!(s.handle(r).await.recovery_codes.is_empty());
}

#[tokio::test]
async fn pending_totp_setup_expires_without_persisting_a_secret() {
    let policy = service::TotpPolicy {
        setup_seconds: 1,
        ..Default::default()
    };
    let s = service::Service::new_with_totp(
        "sqlite::memory:",
        service::Policy::default(),
        policy,
        [7; 32],
    )
    .await
    .unwrap();
    let mut r = request("begin");
    s.handle(r.clone()).await;
    r.op = "register".into();
    s.handle(r.clone()).await;
    r.op = "totp_setup_begin".into();
    let setup = s.handle(r.clone()).await;
    let secret = data_encoding::BASE32_NOPAD
        .decode(setup.totp_secret.as_bytes())
        .unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(1_100)).await;
    let engine = crate::totp::TotpEngine::new("Selene", 30, 1).unwrap();
    let unix = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs();
    r.op = "totp_setup_confirm".into();
    r.totp_code = engine.code_at(&secret, unix).unwrap();
    assert_eq!(s.handle(r).await.status, "EXPIRED");
    assert!(s.storage().totp_state("alice").await.unwrap().is_none());
}

#[tokio::test]
async fn password_and_premium_accounts_require_totp_and_reject_replay() {
    let s = totp_service().await;
    let (secret, _) = enable_totp(&s, false).await;
    let mut login = request("begin");
    assert_eq!(s.handle(login.clone()).await.status, "LOGIN");
    login.op = "login".into();
    assert_eq!(s.handle(login.clone()).await.status, "TOTP_REQUIRED");
    let engine = crate::totp::TotpEngine::new("Selene", 30, 1).unwrap();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs();
    login.op = "totp_verify".into();
    login.totp_code = engine.code_at(&secret, now + 30).unwrap();
    assert_eq!(s.handle(login.clone()).await.status, "AUTHENTICATED");

    let mut replay = request("begin");
    s.handle(replay.clone()).await;
    replay.op = "login".into();
    s.handle(replay.clone()).await;
    replay.op = "totp_verify".into();
    replay.totp_code = login.totp_code;
    assert_eq!(s.handle(replay).await.status, "TOTP_INVALID");

    let premium = totp_service().await;
    let (_secret, _setup) = enable_totp(&premium, true).await;
    let mut original = request("begin");
    original.premium = true;
    assert_eq!(premium.handle(original).await.status, "TOTP_REQUIRED");
}

#[tokio::test]
async fn recovery_code_is_single_use_and_totp_failures_are_bounded() {
    let s = totp_service().await;
    let (_secret, setup) = enable_totp(&s, false).await;
    let recovery = setup.recovery_codes[0].clone();
    let mut login = request("begin");
    s.handle(login.clone()).await;
    login.op = "login".into();
    s.handle(login.clone()).await;
    login.op = "totp_verify".into();
    login.recovery_code = recovery.clone();
    assert_eq!(s.handle(login).await.status, "AUTHENTICATED");
    let mut second = request("begin");
    s.handle(second.clone()).await;
    second.op = "login".into();
    s.handle(second.clone()).await;
    second.op = "totp_verify".into();
    second.recovery_code = recovery;
    assert_eq!(s.handle(second.clone()).await.status, "RECOVERY_INVALID");
    second.recovery_code.clear();
    second.totp_code = "000000".into();
    assert_eq!(s.handle(second.clone()).await.status, "TOTP_INVALID");
    assert_eq!(s.handle(second).await.status, "RATE_LIMITED");
}

#[test]
fn totp_master_key_is_required_and_exactly_32_bytes() {
    assert!(service::TotpKey::from_base64("").is_err());
    assert!(service::TotpKey::from_base64("YWJj").is_err());
    let encoded = base64::Engine::encode(&base64::engine::general_purpose::STANDARD, [7; 32]);
    assert_eq!(
        service::TotpKey::from_base64(&encoded)
            .unwrap()
            .into_bytes(),
        [7; 32]
    );
}

async fn enable_totp(s: &service::Service, premium: bool) -> (Vec<u8>, Reply) {
    let mut r = request("begin");
    r.premium = premium;
    s.handle(r.clone()).await;
    r.op = if premium {
        "totp_setup_begin".into()
    } else {
        "register".into()
    };
    if !premium {
        assert_eq!(s.handle(r.clone()).await.status, "AUTHENTICATED");
        r.op = "totp_setup_begin".into();
    }
    let setup = s.handle(r.clone()).await;
    let secret = data_encoding::BASE32_NOPAD
        .decode(setup.totp_secret.as_bytes())
        .unwrap();
    let engine = crate::totp::TotpEngine::new("Selene", 30, 1).unwrap();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs();
    r.op = "totp_setup_confirm".into();
    r.totp_code = engine.code_at(&secret, now).unwrap();
    let confirmed = s.handle(r.clone()).await;
    assert_eq!(confirmed.status, "TOTP_RECOVERY");
    (secret, confirmed)
}
