use crate::{
    AdminRequest, Request,
    admin::AdminService,
    service::{Policy, Service},
    storage::Storage,
};

#[tokio::test]
async fn admin_info_normalizes_names_and_never_returns_hashes() {
    let storage = Storage::open("sqlite::memory:").await.unwrap();
    storage
        .register("Alice", "secret-hash", false)
        .await
        .unwrap();
    let admin = AdminService::new(storage.clone(), 50);
    let reply = admin
        .handle(AdminRequest {
            op: "info".into(),
            username: "ALICE".into(),
            ..Default::default()
        })
        .await;
    assert_eq!(reply.status, "OK");
    let account = reply.account.unwrap();
    assert_eq!(account.name, "alice");
    assert!(
        !serde_json::to_string(&account)
            .unwrap()
            .contains("secret-hash")
    );
}

#[tokio::test]
async fn list_is_deterministic_bounded_and_pageable() {
    let storage = Storage::open("sqlite::memory:").await.unwrap();
    for name in ["charlie", "alice", "bravo"] {
        storage.register(name, "hash", false).await.unwrap();
    }
    let admin = AdminService::new(storage, 2);
    let first = admin
        .handle(AdminRequest {
            op: "list".into(),
            page: 1,
            page_size: 50,
            ..Default::default()
        })
        .await;
    assert_eq!(
        first
            .accounts
            .iter()
            .map(|a| a.name.as_str())
            .collect::<Vec<_>>(),
        ["alice", "bravo"]
    );
    assert!(first.has_more);
    let second = admin
        .handle(AdminRequest {
            op: "list".into(),
            page: 2,
            page_size: 50,
            ..Default::default()
        })
        .await;
    assert_eq!(second.accounts[0].name, "charlie");
}

#[tokio::test]
async fn reset_and_delete_preserve_only_expected_state() {
    let storage = Storage::open("sqlite::memory:").await.unwrap();
    storage.register("Alice", "hash", true).await.unwrap();
    storage.trust("alice", "192.0.2.4", 500).await.unwrap();
    let admin = AdminService::new(storage.clone(), 50);
    assert_eq!(
        admin
            .handle(AdminRequest {
                op: "resetPassword".into(),
                username: "Alice".into(),
                ..Default::default()
            })
            .await
            .status,
        "OK"
    );
    let info = admin
        .handle(AdminRequest {
            op: "info".into(),
            username: "alice".into(),
            ..Default::default()
        })
        .await
        .account
        .unwrap();
    assert!(info.premium);
    assert_eq!(info.trusted_ip, "192.0.2.4");
    assert_eq!(info.trusted_until, 500);
    assert!(info.password_reset);
    assert_eq!(
        admin
            .handle(AdminRequest {
                op: "delete".into(),
                username: "ALICE".into(),
                ..Default::default()
            })
            .await
            .status,
        "OK"
    );
    assert_eq!(
        admin
            .handle(AdminRequest {
                op: "info".into(),
                username: "alice".into(),
                ..Default::default()
            })
            .await
            .status,
        "NOT_FOUND"
    );
}

#[tokio::test]
async fn reset_offline_account_requires_registration_and_rejects_passwordless_login() {
    let service = Service::new("sqlite::memory:", Policy::default())
        .await
        .unwrap();
    service
        .storage()
        .register("alice", "", false)
        .await
        .unwrap();
    let mut request = Request {
        op: "begin".into(),
        session: uuid::Uuid::new_v4().to_string(),
        username: "Alice".into(),
        ip: "127.0.0.1".into(),
        ..Default::default()
    };
    assert_eq!(service.handle(request.clone()).await.status, "REGISTER");
    request.op = "login".into();
    request.password = "password-one".into();
    assert_eq!(
        service.handle(request.clone()).await.status,
        "INVALID_REQUEST"
    );
    request.op = "register".into();
    assert_eq!(
        service.handle(request.clone()).await.status,
        "AUTHENTICATED"
    );
    assert!(
        !service
            .storage()
            .account("alice")
            .await
            .unwrap()
            .unwrap()
            .hash
            .is_empty()
    );
}
