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
