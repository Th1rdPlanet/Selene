use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
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
mod captcha;
pub mod service;
mod storage;
#[cfg(test)]
mod tests;
