use crate::{AdminAccount, AdminReply, AdminRequest, storage::Storage};

pub struct AdminService {
    storage: Storage,
    max_page_size: u32,
}

impl AdminService {
    pub fn new(storage: Storage, max_page_size: u32) -> Self {
        Self {
            storage,
            max_page_size: max_page_size.clamp(1, 50),
        }
    }
    pub async fn handle(&self, mut request: AdminRequest) -> AdminReply {
        request.username.make_ascii_lowercase();
        let result = match request.op.as_str() {
            "list" if request.page > 0 => {
                let size = request.page_size.clamp(1, self.max_page_size);
                self.storage
                    .admin_list(request.page, size)
                    .await
                    .map(|(accounts, has_more)| AdminReply {
                        status: "OK".into(),
                        accounts,
                        page: request.page,
                        has_more,
                        ..Default::default()
                    })
            }
            "info" if valid_name(&request.username) => self
                .storage
                .admin_account(&request.username)
                .await
                .map(|account| match account {
                    Some(account) => AdminReply {
                        status: "OK".into(),
                        account: Some(account),
                        ..Default::default()
                    },
                    None => status("NOT_FOUND"),
                }),
            "delete" if valid_name(&request.username) => self
                .storage
                .admin_delete(&request.username)
                .await
                .map(|found| status(if found { "OK" } else { "NOT_FOUND" })),
            "resetPassword" if valid_name(&request.username) => self
                .storage
                .admin_reset_password(&request.username)
                .await
                .map(|found| status(if found { "OK" } else { "NOT_FOUND" })),
            _ => return status("INVALID_REQUEST"),
        };
        result.unwrap_or_else(|_| status("UNAVAILABLE"))
    }
}

fn status(value: &str) -> AdminReply {
    AdminReply {
        status: value.into(),
        ..Default::default()
    }
}
fn valid_name(name: &str) -> bool {
    (3..=16).contains(&name.len()) && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

pub(crate) fn account(
    name: String,
    premium: bool,
    trusted_ip: String,
    trusted_until: i64,
    hash: String,
) -> AdminAccount {
    AdminAccount {
        name,
        premium,
        trusted_ip,
        trusted_until,
        password_reset: hash.is_empty(),
    }
}
