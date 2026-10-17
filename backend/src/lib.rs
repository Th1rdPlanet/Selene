use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
#[derive(Default, Clone, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase", deny_unknown_fields)]
pub struct AdminRequest {
    pub op: String,
    pub username: String,
    pub page: u32,
    pub page_size: u32,
}
#[derive(Default, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdminAccount {
    pub name: String,
    pub premium: bool,
    pub trusted_ip: String,
    pub trusted_until: i64,
    pub password_reset: bool,
    pub totp_enabled: bool,
}
#[derive(Default, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AdminReply {
    pub status: String,
    pub accounts: Vec<AdminAccount>,
    pub account: Option<AdminAccount>,
    pub page: u32,
    pub has_more: bool,
}
#[derive(Default, Clone, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Request {
    pub op: String,
    pub session: String,
    pub username: String,
    pub ip: String,
    pub password: String,
    pub answer: String,
    pub premium: bool,
    pub captcha_required: bool,
    pub totp_code: String,
    pub recovery_code: String,
    pub password_trusted: bool,
    pub totp_trusted: bool,
    pub captcha_kind: String,
    pub challenge_nonce: String,
}
#[derive(Default, Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PremiumTrust {
    pub ip: String,
    pub expires_at: i64,
}
#[derive(Default, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Reply {
    pub status: String,
    pub session: String,
    pub image: String,
    pub captcha_glyphs: String,
    pub green: BTreeMap<String, String>,
    pub premium_names: Vec<String>,
    pub retry_after: u32,
    pub totp_uri: String,
    pub totp_secret: String,
    pub recovery_codes: Vec<String>,
    pub totp_enabled: bool,
    pub premium_trust: BTreeMap<String, PremiumTrust>,
    pub challenge_nonce: String,
}
impl Reply {
    pub fn status(status: &str, session: &str) -> Self {
        Self {
            status: status.into(),
            session: session.into(),
            ..Self::default()
        }
    }
}
pub mod admin;
#[cfg(test)]
mod admin_tests;
mod captcha;
pub mod service;
mod storage;
#[cfg(test)]
mod tests;
pub(crate) mod totp;
#[cfg(test)]
mod totp_tests;
