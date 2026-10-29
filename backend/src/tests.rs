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
async fn trusted_password_skips_existing_login_but_not_registration() {
    let s = service().await;
    let mut r = request("begin");
    s.handle(r.clone()).await;
    r.op = "register".into();
    assert_eq!(s.handle(r.clone()).await.status, "AUTHENTICATED");
    let mut login = request("begin");
    login.password_trusted = true;
    assert_eq!(s.handle(login.clone()).await.status, "AUTHENTICATED");
    let mut unknown = request("begin");
    unknown.username = "Bob".into();
    unknown.password_trusted = true;
    assert_eq!(s.handle(unknown).await.status, "REGISTER");
}

#[tokio::test]
async fn maintenance_can_show_factors_without_verifying_values() {
    let s = service().await;
    let mut registration = request("begin");
    s.handle(registration.clone()).await;
    registration.op = "register".into();
    assert_eq!(s.handle(registration).await.status, "AUTHENTICATED");

    let mut login = request("begin");
    login.maintenance_revision = 7;
    login.maintenance_enable_captcha = true;
    login.maintenance_enable_password = true;
    login.maintenance_enable_totp = true;
    login.captcha_required = true;
    assert_eq!(s.handle(login.clone()).await.status, "CAPTCHA");
    login.op = "answer".into();
    login.answer = "definitely wrong".into();
    assert_eq!(s.handle(login.clone()).await.status, "LOGIN");
    login.op = "login".into();
    login.password = "anything".into();
    assert_eq!(s.handle(login).await.status, "AUTHENTICATED");
}

#[tokio::test]
async fn maintenance_disabled_factor_is_skipped_but_verified_factor_still_rejects() {
    let s = service().await;
    let mut registration = request("begin");
    s.handle(registration.clone()).await;
    registration.op = "register".into();
    assert_eq!(s.handle(registration).await.status, "AUTHENTICATED");

    let mut skipped = request("begin");
    skipped.maintenance_revision = 8;
    assert_eq!(s.handle(skipped).await.status, "AUTHENTICATED");

    let mut verified = request("begin");
    verified.maintenance_revision = 9;
    verified.maintenance_enable_password = true;
    verified.maintenance_verify_password = true;
    assert_eq!(s.handle(verified.clone()).await.status, "LOGIN");
    verified.op = "login".into();
    verified.password = "wrong password".into();
    assert_eq!(s.handle(verified).await.status, "INVALID_CREDENTIALS");
}

#[tokio::test]
async fn maintenance_policy_cannot_change_after_begin() {
    let s = service().await;
    let mut registration = request("begin");
    s.handle(registration.clone()).await;
    registration.op = "register".into();
    assert_eq!(s.handle(registration).await.status, "AUTHENTICATED");
    let mut r = request("begin");
    r.maintenance_revision = 11;
    r.maintenance_enable_password = true;
    assert_eq!(s.handle(r.clone()).await.status, "LOGIN");
    r.maintenance_verify_password = true;
    assert_eq!(s.handle(r).await.status, "INVALID_REQUEST");
}

#[tokio::test]
async fn maintenance_totp_stage_can_be_enabled_without_verification() {
    let s = totp_service().await;
    enable_totp(&s, false).await;
    let mut login = request("begin");
    login.maintenance_revision = 12;
    login.maintenance_enable_password = true;
    login.maintenance_enable_totp = true;
    assert_eq!(s.handle(login.clone()).await.status, "LOGIN");
    login.op = "login".into();
    login.password = "anything".into();
    assert_eq!(s.handle(login.clone()).await.status, "TOTP_REQUIRED");
    login.op = "totp_verify".into();
    login.totp_code = "anything".into();
    assert_eq!(s.handle(login).await.status, "AUTHENTICATED");
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
            .all(|c| ('\u{e000}'..='\u{e27f}').contains(&c))
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
async fn interactive_begin_reuses_one_bound_nonce() {
    let s = service().await;
    let mut r = request("begin");
    r.captcha_required = true;
    r.captcha_kind = "INTERACTIVE".into();
    let first = s.handle(r.clone()).await;
    assert_eq!(first.status, "CAPTCHA_INTERACTIVE");
    assert_eq!(first.challenge_nonce.len(), 43);
    let repeated = s.handle(r.clone()).await;
    assert_eq!(repeated.challenge_nonce, first.challenge_nonce);
    r.op = "interactive_complete".into();
    r.challenge_nonce = "wrong".into();
    assert_eq!(s.handle(r.clone()).await.status, "INVALID_REQUEST");
    r.challenge_nonce = first.challenge_nonce;
    assert_eq!(s.handle(r.clone()).await.status, "REGISTER");
    assert_eq!(s.handle(r).await.status, "INVALID_REQUEST");
}

#[tokio::test]
async fn interactive_mistakes_share_numeric_attempt_budget() {
    let s = service().await;
    let mut r = request("begin");
    r.captcha_required = true;
    r.captcha_kind = "INTERACTIVE".into();
    let first = s.handle(r.clone()).await;
    r.challenge_nonce = first.challenge_nonce;
    r.op = "interactive_mistake".into();
    assert_eq!(s.handle(r.clone()).await.status, "CAPTCHA_INTERACTIVE");
    assert_eq!(s.handle(r.clone()).await.status, "CAPTCHA_INTERACTIVE");
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
async fn premium_upgrade_needs_the_password_and_a_licence() {
    let s = service().await;
    let mut r = request("begin");
    s.handle(r.clone()).await;
    r.op = "register".into();
    assert_eq!(s.handle(r.clone()).await.status, "AUTHENTICATED");
    // An offline session cannot switch the flag, even when logged in.
    r.op = "premium_enable".into();
    assert_eq!(s.handle(r.clone()).await.status, "PREMIUM_REQUIRES_LICENSE");
    // Nor can a licensed session that has not logged in yet.
    r.session = uuid::Uuid::new_v4().to_string();
    r.premium = true;
    r.op = "begin".into();
    assert_eq!(s.handle(r.clone()).await.status, "LOGIN");
    r.op = "premium_enable".into();
    assert_eq!(s.handle(r.clone()).await.status, "INVALID_REQUEST");
    // Licence plus the account password converts the account.
    r.op = "login".into();
    assert_eq!(s.handle(r.clone()).await.status, "AUTHENTICATED");
    r.op = "premium_enable".into();
    assert_eq!(s.handle(r.clone()).await.status, "PREMIUM_ENABLED");
    r.op = "profile".into();
    assert_eq!(s.handle(r.clone()).await.status, "ACCOUNT_PREMIUM");
    // From now on an offline client cannot log in with the password.
    let mut pirate = request("begin");
    s.handle(pirate.clone()).await;
    pirate.op = "login".into();
    assert_eq!(s.handle(pirate).await.status, "INVALID_CREDENTIALS");
    // A licensed session logs in without the password and may switch back.
    r.session = uuid::Uuid::new_v4().to_string();
    r.op = "begin".into();
    assert_eq!(s.handle(r.clone()).await.status, "AUTHENTICATED");
    r.op = "premium_disable".into();
    assert_eq!(s.handle(r.clone()).await.status, "PREMIUM_DISABLED");
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
async fn password_hash_does_not_block_another_session() {
    let s = service().await;
    let mut slow = request("begin");
    assert_eq!(s.handle(slow.clone()).await.status, "REGISTER");
    slow.op = "register".into();
    let other = request("begin");
    let (registered, began) = tokio::join!(s.handle(slow), s.handle(other));
    assert_eq!(registered.status, "AUTHENTICATED");
    assert_eq!(began.status, "REGISTER");
}

#[tokio::test]
async fn one_password_request_is_allowed_in_flight_per_session() {
    let s = service().await;
    let mut first = request("begin");
    assert_eq!(s.handle(first.clone()).await.status, "REGISTER");
    first.op = "register".into();
    let mut second = first.clone();
    second.password = "another long password".into();
    let (first_reply, second_reply) = tokio::join!(s.handle(first), s.handle(second));
    assert_eq!(first_reply.status, "AUTHENTICATED");
    assert_eq!(second_reply.status, "BUSY");
}

#[tokio::test]
async fn closing_session_during_password_hash_prevents_registration() {
    let s = service().await;
    let mut register = request("begin");
    assert_eq!(s.handle(register.clone()).await.status, "REGISTER");
    register.op = "register".into();
    let mut close = register.clone();
    close.op = "close".into();
    close.password.clear();
    let (registration, closed) = tokio::join!(s.handle(register.clone()), s.handle(close));
    assert_eq!(closed.status, "CLOSED");
    assert_eq!(registration.status, "EXPIRED");
    register.op = "profile".into();
    assert_eq!(s.handle(register).await.status, "ACCOUNT_NEW");
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
async fn trusted_totp_skips_second_factor_and_reports_it_enabled() {
    let s = totp_service().await;
    enable_totp(&s, false).await;
    let mut login = request("begin");
    login.password_trusted = true;
    login.totp_trusted = true;
    let reply = s.handle(login).await;
    assert_eq!(reply.status, "AUTHENTICATED");
    assert!(reply.totp_enabled);
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
#[tokio::test]
async fn login_upgrades_a_hash_made_with_older_argon2_parameters() {
    let directory = std::env::temp_dir().join(format!("selene-rehash-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&directory).unwrap();
    let url = format!(
        "sqlite://{}?mode=rwc",
        directory.join("accounts.db").display()
    );
    let policy = |lanes| service::Policy {
        password_lanes: lanes,
        ..service::Policy::default()
    };

    // Register with the previous single-lane parameters.
    let old = service::Service::new(&url, policy(1)).await.unwrap();
    let mut r = request("begin");
    assert_eq!(old.handle(r.clone()).await.status, "REGISTER");
    r.op = "register".into();
    assert_eq!(old.handle(r.clone()).await.status, "AUTHENTICATED");
    let before = old
        .storage_clone()
        .account("alice")
        .await
        .unwrap()
        .unwrap()
        .hash;
    assert!(before.contains(",p=1$"), "{before}");
    drop(old);

    // The current four-lane service still accepts the old hash and replaces it.
    let current = service::Service::new(&url, policy(4)).await.unwrap();
    r.session = uuid::Uuid::new_v4().to_string();
    r.op = "begin".into();
    assert_eq!(current.handle(r.clone()).await.status, "LOGIN");
    r.op = "login".into();
    assert_eq!(current.handle(r.clone()).await.status, "AUTHENTICATED");
    let after = current
        .storage_clone()
        .account("alice")
        .await
        .unwrap()
        .unwrap()
        .hash;
    assert!(
        after.starts_with("$argon2id$v=19$m=19456,t=2,p=4$"),
        "{after}"
    );

    // The upgraded hash keeps working.
    r.session = uuid::Uuid::new_v4().to_string();
    r.op = "begin".into();
    assert_eq!(current.handle(r.clone()).await.status, "LOGIN");
    r.op = "login".into();
    assert_eq!(current.handle(r.clone()).await.status, "AUTHENTICATED");
    std::fs::remove_dir_all(directory).ok();
}
