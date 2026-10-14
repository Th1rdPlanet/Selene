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
}
#[derive(Default, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Reply {
    pub status: String,
    pub session: String,
    pub image: String,
    pub green: BTreeMap<String, String>,
    pub premium_names: Vec<String>,
    pub retry_after: u32,
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
