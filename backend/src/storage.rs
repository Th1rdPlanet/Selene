use crate::totp::EncryptedSecret;
use anyhow::Context;
use base64::{Engine, engine::general_purpose::STANDARD};
use sqlx::{AnyPool, Row, any::AnyPoolOptions};
use std::collections::BTreeMap;
#[derive(Clone)]
pub struct Storage {
    pool: AnyPool,
    postgres: bool,
}
pub struct Account {
    pub hash: String,
    pub premium: bool,
}
pub(crate) struct TotpState {
    pub encrypted: EncryptedSecret,
    pub last_step: Option<u64>,
}
pub(crate) struct PremiumTrustRecord {
    pub ip: String,
    pub expires_at: i64,
}
impl Storage {
    pub async fn open(url: &str) -> anyhow::Result<Self> {
        sqlx::any::install_default_drivers();
        let sqlite = url.starts_with("sqlite:");
        let memory = url.contains(":memory:");
        let pool = AnyPoolOptions::new()
            .max_connections(if memory { 1 } else { 8 })
            .acquire_timeout(std::time::Duration::from_secs(5))
            .after_connect(move |connection, _| {
                Box::pin(async move {
                    if sqlite {
                        // Wait for a short write lock instead of failing the login.
                        sqlx::query("PRAGMA busy_timeout = 5000")
                            .execute(&mut *connection)
                            .await?;
                    }
                    Ok(())
                })
            })
            .connect(url)
            .await
            .context("database connection failed")?;
        if sqlite && !memory {
            // Write-ahead logging lets account reads continue while another login writes, which
            // removes the millisecond-scale read tails of the default rollback journal.
            // Durability (`synchronous`) is unchanged.
            sqlx::query("PRAGMA journal_mode = WAL")
                .execute(&pool)
                .await
                .context("cannot enable SQLite write-ahead logging")?;
        }
        sqlx::query("CREATE TABLE IF NOT EXISTS selene_accounts (name VARCHAR(16) PRIMARY KEY, password_hash VARCHAR(255) NOT NULL, premium INTEGER NOT NULL, trusted_ip VARCHAR(45) NOT NULL, trusted_until BIGINT NOT NULL)").execute(&pool).await.context("cannot create or validate selene_accounts schema")?;
        sqlx::query("CREATE TABLE IF NOT EXISTS selene_totp (name VARCHAR(16) PRIMARY KEY, encryption_version INTEGER NOT NULL, nonce VARCHAR(64) NOT NULL, ciphertext VARCHAR(512) NOT NULL, last_step BIGINT NOT NULL, created_at BIGINT NOT NULL)").execute(&pool).await.context("cannot create or validate selene_totp schema")?;
        sqlx::query("CREATE TABLE IF NOT EXISTS selene_recovery_codes (name VARCHAR(16) NOT NULL, code_hash VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL, PRIMARY KEY (name, code_hash))").execute(&pool).await.context("cannot create or validate selene_recovery_codes schema")?;
        sqlx::query("CREATE TABLE IF NOT EXISTS selene_premium_trust (name VARCHAR(16) PRIMARY KEY, ip VARCHAR(45) NOT NULL, expires_at BIGINT NOT NULL)").execute(&pool).await.context("cannot create or validate selene_premium_trust schema")?;
        Ok(Self {
            pool,
            postgres: url.starts_with("postgres"),
        })
    }
    fn sql(&self, s: &str) -> String {
        if !self.postgres {
            return s.into();
        }
        let mut n = 0;
        s.chars()
            .map(|c| {
                if c == '?' {
                    n += 1;
                    format!("${n}")
                } else {
                    c.to_string()
                }
            })
            .collect()
    }
    pub async fn account(&self, name: &str) -> anyhow::Result<Option<Account>> {
        crate::traces::span("db.account", async move {
            let row = sqlx::query(
                &self.sql("SELECT password_hash, premium FROM selene_accounts WHERE name = ?"),
            )
            .bind(name.to_ascii_lowercase())
            .fetch_optional(&self.pool)
            .await?;
            Ok(row.map(|r| Account {
                hash: r.get("password_hash"),
                premium: r.get::<i32, _>("premium") != 0,
            }))
        })
        .await
    }
    pub async fn register(&self, name: &str, hash: &str, premium: bool) -> anyhow::Result<bool> {
        crate::traces::span("db.register", async move {
            let result=sqlx::query(&self.sql("INSERT INTO selene_accounts (name,password_hash,premium,trusted_ip,trusted_until) VALUES (?,?,?,'',0)")).bind(name.to_ascii_lowercase()).bind(hash).bind(if premium{1i32}else{0}).execute(&self.pool).await;
            match result {
                Ok(_) => Ok(true),
                Err(sqlx::Error::Database(e)) if e.is_unique_violation() => Ok(false),
                Err(e) => Err(e.into()),
            }
        })
        .await
    }
    pub async fn trust(&self, name: &str, ip: &str, until: i64) -> anyhow::Result<()> {
        crate::traces::span("db.trust", async move {
            sqlx::query(&self.sql(
                "UPDATE selene_accounts SET trusted_ip = ?, trusted_until = ? WHERE name = ?",
            ))
            .bind(ip)
            .bind(until)
            .bind(name)
            .execute(&self.pool)
            .await?;
            Ok(())
        })
        .await
    }
    pub async fn snapshot(
        &self,
        now: i64,
    ) -> anyhow::Result<(BTreeMap<String, String>, Vec<String>)> {
        crate::traces::span("db.snapshot", async move {
            let rows=sqlx::query(&self.sql("SELECT name, premium, trusted_ip, trusted_until FROM selene_accounts WHERE premium = 1 OR trusted_until > ? LIMIT 4097")).bind(now).fetch_all(&self.pool).await?;
            anyhow::ensure!(rows.len() <= 4096, "snapshot capacity exceeded");
            let mut green = BTreeMap::new();
            let mut premium = Vec::new();
            for r in rows {
                let name: String = r.get("name");
                if r.get::<i32, _>("premium") != 0 {
                    premium.push(name.clone());
                }
                if r.get::<i64, _>("trusted_until") > now {
                    green.insert(name, r.get("trusted_ip"));
                }
            }
            Ok((green, premium))
        })
        .await
    }
    pub async fn admin_account(&self, name: &str) -> anyhow::Result<Option<crate::AdminAccount>> {
        crate::traces::span("db.admin_account", async move {
            let row=sqlx::query(&self.sql("SELECT name,password_hash,premium,trusted_ip,trusted_until,CASE WHEN EXISTS (SELECT 1 FROM selene_totp WHERE selene_totp.name = selene_accounts.name) THEN 1 ELSE 0 END AS totp_enabled FROM selene_accounts WHERE name = ?")).bind(name.to_ascii_lowercase()).fetch_optional(&self.pool).await?;
            Ok(row.map(|r| {
                crate::admin::account(
                    r.get("name"),
                    r.get::<i32, _>("premium") != 0,
                    r.get("trusted_ip"),
                    r.get("trusted_until"),
                    r.get("password_hash"),
                    r.get::<i32, _>("totp_enabled") != 0,
                )
            }))
        })
        .await
    }
    pub async fn admin_list(
        &self,
        page: u32,
        page_size: u32,
    ) -> anyhow::Result<(Vec<crate::AdminAccount>, bool)> {
        crate::traces::span("db.admin_list", async move {
            let limit = page_size.min(50) as i64;
            let offset = (page.saturating_sub(1) as i64).saturating_mul(limit);
            let rows=sqlx::query(&self.sql("SELECT name,password_hash,premium,trusted_ip,trusted_until,CASE WHEN EXISTS (SELECT 1 FROM selene_totp WHERE selene_totp.name = selene_accounts.name) THEN 1 ELSE 0 END AS totp_enabled FROM selene_accounts ORDER BY name ASC LIMIT ? OFFSET ?")).bind(limit+1).bind(offset).fetch_all(&self.pool).await?;
            let has_more = rows.len() > limit as usize;
            let accounts = rows
                .into_iter()
                .take(limit as usize)
                .map(|r| {
                    crate::admin::account(
                        r.get("name"),
                        r.get::<i32, _>("premium") != 0,
                        r.get("trusted_ip"),
                        r.get("trusted_until"),
                        r.get("password_hash"),
                        r.get::<i32, _>("totp_enabled") != 0,
                    )
                })
                .collect();
            Ok((accounts, has_more))
        })
        .await
    }
    pub async fn admin_delete(&self, name: &str) -> anyhow::Result<bool> {
        crate::traces::span("db.admin_delete", async move {
            let canonical = name.to_ascii_lowercase();
            let mut tx = self.pool.begin().await?;
            for table in [
                "selene_recovery_codes",
                "selene_totp",
                "selene_premium_trust",
            ] {
                sqlx::query(&self.sql(&format!("DELETE FROM {table} WHERE name = ?")))
                    .bind(&canonical)
                    .execute(&mut *tx)
                    .await?;
            }
            let removed = sqlx::query(&self.sql("DELETE FROM selene_accounts WHERE name = ?"))
                .bind(&canonical)
                .execute(&mut *tx)
                .await?
                .rows_affected()
                > 0;
            tx.commit().await?;
            Ok(removed)
        })
        .await
    }
    pub async fn admin_reset_password(&self, name: &str) -> anyhow::Result<bool> {
        crate::traces::span("db.admin_reset_password", async move {
            Ok(sqlx::query(
                &self.sql("UPDATE selene_accounts SET password_hash = '' WHERE name = ?"),
            )
            .bind(name.to_ascii_lowercase())
            .execute(&self.pool)
            .await?
            .rows_affected()
                > 0)
        })
        .await
    }
    /// Replaces a password hash only if it is still `old_hash`, so a concurrent reset wins.
    pub async fn upgrade_password_hash(
        &self,
        name: &str,
        old_hash: &str,
        new_hash: &str,
    ) -> anyhow::Result<bool> {
        crate::traces::span("db.rehash", async move {
            Ok(sqlx::query(&self.sql(
                "UPDATE selene_accounts SET password_hash = ? WHERE name = ? AND password_hash = ?",
            ))
            .bind(new_hash)
            .bind(name.to_ascii_lowercase())
            .bind(old_hash)
            .execute(&self.pool)
            .await?
            .rows_affected()
                == 1)
        })
        .await
    }
    pub async fn register_reset(&self, name: &str, hash: &str) -> anyhow::Result<bool> {
        crate::traces::span("db.register_reset", async move {
            Ok(sqlx::query(&self.sql(
                "UPDATE selene_accounts SET password_hash = ? WHERE name = ? AND password_hash = ''",
            ))
            .bind(hash)
            .bind(name.to_ascii_lowercase())
            .execute(&self.pool)
            .await?
            .rows_affected()
                == 1)
        })
        .await
    }

    pub(crate) async fn totp_state(&self, name: &str) -> anyhow::Result<Option<TotpState>> {
        crate::traces::span("db.totp_state", async move {
            let row = sqlx::query(&self.sql(
                "SELECT encryption_version,nonce,ciphertext,last_step FROM selene_totp WHERE name = ?",
            ))
            .bind(name.to_ascii_lowercase())
            .fetch_optional(&self.pool)
            .await?;
            row.map(|row| {
                let nonce = STANDARD
                    .decode(row.get::<String, _>("nonce"))
                    .context("invalid stored TOTP nonce")?;
                let ciphertext = STANDARD
                    .decode(row.get::<String, _>("ciphertext"))
                    .context("invalid stored TOTP ciphertext")?;
                let last: i64 = row.get("last_step");
                Ok(TotpState {
                    encrypted: EncryptedSecret::from_parts(
                        row.get::<i32, _>("encryption_version") as u8,
                        &nonce,
                        ciphertext,
                    )?,
                    last_step: (last >= 0).then_some(last as u64),
                })
            })
            .transpose()
        })
        .await
    }

    pub(crate) async fn enable_totp_at_step(
        &self,
        name: &str,
        encrypted: &EncryptedSecret,
        recovery_hashes: &[[u8; 32]],
        created_at: i64,
        last_step: Option<u64>,
    ) -> anyhow::Result<()> {
        crate::traces::span("db.enable_totp_at_step", async move {
            anyhow::ensure!(
                !recovery_hashes.is_empty() && recovery_hashes.len() <= 32 && created_at >= 0,
                "invalid TOTP persistence input"
            );
            let canonical = name.to_ascii_lowercase();
            let (version, nonce, ciphertext) = encrypted.parts();
            let mut tx = self.pool.begin().await?;
            sqlx::query(&self.sql("DELETE FROM selene_recovery_codes WHERE name = ?"))
                .bind(&canonical)
                .execute(&mut *tx)
                .await?;
            sqlx::query(&self.sql("DELETE FROM selene_totp WHERE name = ?"))
                .bind(&canonical)
                .execute(&mut *tx)
                .await?;
            let stored_step = last_step.map(i64::try_from).transpose()?.unwrap_or(-1);
            sqlx::query(&self.sql("INSERT INTO selene_totp (name,encryption_version,nonce,ciphertext,last_step,created_at) VALUES (?,?,?,?,?,?)"))
                .bind(&canonical).bind(i32::from(version)).bind(STANDARD.encode(nonce)).bind(STANDARD.encode(ciphertext)).bind(stored_step).bind(created_at).execute(&mut *tx).await?;
            for hash in recovery_hashes {
                sqlx::query(&self.sql(
                    "INSERT INTO selene_recovery_codes (name,code_hash,created_at) VALUES (?,?,?)",
                ))
                .bind(&canonical)
                .bind(hex(hash))
                .bind(created_at)
                .execute(&mut *tx)
                .await?;
            }
            tx.commit().await?;
            Ok(())
        })
        .await
    }

    pub(crate) async fn replace_recovery(
        &self,
        name: &str,
        recovery_hashes: &[[u8; 32]],
        created_at: i64,
    ) -> anyhow::Result<bool> {
        crate::traces::span("db.replace_recovery", async move {
            anyhow::ensure!(
                !recovery_hashes.is_empty() && recovery_hashes.len() <= 32 && created_at >= 0,
                "invalid recovery persistence input"
            );
            let canonical = name.to_ascii_lowercase();
            let mut tx = self.pool.begin().await?;
            let exists = sqlx::query(&self.sql("SELECT name FROM selene_totp WHERE name = ?"))
                .bind(&canonical)
                .fetch_optional(&mut *tx)
                .await?
                .is_some();
            if !exists {
                return Ok(false);
            }
            sqlx::query(&self.sql("DELETE FROM selene_recovery_codes WHERE name = ?"))
                .bind(&canonical)
                .execute(&mut *tx)
                .await?;
            for hash in recovery_hashes {
                sqlx::query(&self.sql(
                    "INSERT INTO selene_recovery_codes (name,code_hash,created_at) VALUES (?,?,?)",
                ))
                .bind(&canonical)
                .bind(hex(hash))
                .bind(created_at)
                .execute(&mut *tx)
                .await?;
            }
            tx.commit().await?;
            Ok(true)
        })
        .await
    }

    pub(crate) async fn consume_recovery(
        &self,
        name: &str,
        hash: &[u8; 32],
    ) -> anyhow::Result<bool> {
        crate::traces::span("db.consume_recovery", async move {
            Ok(sqlx::query(
                &self.sql("DELETE FROM selene_recovery_codes WHERE name = ? AND code_hash = ?"),
            )
            .bind(name.to_ascii_lowercase())
            .bind(hex(hash))
            .execute(&self.pool)
            .await?
            .rows_affected()
                == 1)
        })
        .await
    }

    pub(crate) async fn accept_totp_step(&self, name: &str, step: u64) -> anyhow::Result<bool> {
        crate::traces::span("db.accept_totp_step", async move {
            let step = i64::try_from(step).context("TOTP step overflow")?;
            Ok(sqlx::query(&self.sql("UPDATE selene_totp SET last_step = ? WHERE name = ? AND (last_step < ? OR last_step = -1)"))
                .bind(step).bind(name.to_ascii_lowercase()).bind(step).execute(&self.pool).await?.rows_affected()==1)
        })
        .await
    }

    pub(crate) async fn disable_totp(&self, name: &str) -> anyhow::Result<bool> {
        crate::traces::span("db.disable_totp", async move {
            let canonical = name.to_ascii_lowercase();
            let mut tx = self.pool.begin().await?;
            sqlx::query(&self.sql("DELETE FROM selene_recovery_codes WHERE name = ?"))
                .bind(&canonical)
                .execute(&mut *tx)
                .await?;
            let removed = sqlx::query(&self.sql("DELETE FROM selene_totp WHERE name = ?"))
                .bind(&canonical)
                .execute(&mut *tx)
                .await?
                .rows_affected()
                == 1;
            tx.commit().await?;
            Ok(removed)
        })
        .await
    }

    /// Marks an account as licensed (password-less, Mojang handshake only) or back to offline.
    /// Leaving premium also drops the premium trust record. Returns false for a missing account.
    pub(crate) async fn set_premium(&self, name: &str, premium: bool) -> anyhow::Result<bool> {
        crate::traces::span("db.set_premium", async move {
            let canonical = name.to_ascii_lowercase();
            let mut tx = self.pool.begin().await?;
            let changed = sqlx::query(&self.sql("UPDATE selene_accounts SET premium = ? WHERE name = ?"))
                .bind(if premium { 1i32 } else { 0 })
                .bind(&canonical)
                .execute(&mut *tx)
                .await?
                .rows_affected()
                == 1;
            if changed && !premium {
                sqlx::query(&self.sql("DELETE FROM selene_premium_trust WHERE name = ?"))
                    .bind(&canonical)
                    .execute(&mut *tx)
                    .await?;
            }
            tx.commit().await?;
            Ok(changed)
        })
        .await
    }

    pub(crate) async fn set_premium_trust(
        &self,
        name: &str,
        ip: &str,
        expires_at: i64,
    ) -> anyhow::Result<()> {
        crate::traces::span("db.set_premium_trust", async move {
            anyhow::ensure!(expires_at >= 0, "invalid premium trust expiry");
            let canonical = name.to_ascii_lowercase();
            let mut tx = self.pool.begin().await?;
            sqlx::query(&self.sql("DELETE FROM selene_premium_trust WHERE name = ?"))
                .bind(&canonical)
                .execute(&mut *tx)
                .await?;
            sqlx::query(
                &self.sql("INSERT INTO selene_premium_trust (name,ip,expires_at) VALUES (?,?,?)"),
            )
            .bind(&canonical)
            .bind(ip)
            .bind(expires_at)
            .execute(&mut *tx)
            .await?;
            tx.commit().await?;
            Ok(())
        })
        .await
    }

    pub(crate) async fn premium_snapshot(
        &self,
        time: i64,
        limit: usize,
    ) -> anyhow::Result<BTreeMap<String, PremiumTrustRecord>> {
        crate::traces::span("db.premium_snapshot", async move {
            let rows=sqlx::query(&self.sql("SELECT name,ip,expires_at FROM selene_premium_trust WHERE expires_at > ? ORDER BY name ASC LIMIT ?"))
                .bind(time).bind(i64::try_from(limit.saturating_add(1))?).fetch_all(&self.pool).await?;
            anyhow::ensure!(
                rows.len() <= limit,
                "premium trust snapshot capacity exceeded"
            );
            Ok(rows
                .into_iter()
                .map(|row| {
                    (
                        row.get("name"),
                        PremiumTrustRecord {
                            ip: row.get("ip"),
                            expires_at: row.get("expires_at"),
                        },
                    )
                })
                .collect())
        })
        .await
    }
}

fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut result = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        result.push(DIGITS[(byte >> 4) as usize] as char);
        result.push(DIGITS[(byte & 15) as usize] as char);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::totp::SecretCipher;
    #[tokio::test]
    async fn expired_trust_rows_do_not_exhaust_snapshot_capacity() {
        let storage = Storage::open("sqlite::memory:").await.unwrap();
        sqlx::query("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<4097) INSERT INTO selene_accounts SELECT 'user'||x, '', 0, '127.0.0.1', 1 FROM n").execute(&storage.pool).await.unwrap();
        assert!(storage.snapshot(2).await.is_ok());
    }

    #[tokio::test]
    async fn totp_setup_and_recovery_consumption_are_atomic() {
        let storage = Storage::open("sqlite::memory:").await.unwrap();
        storage.register("alice", "hash", false).await.unwrap();
        let encrypted = SecretCipher::new([7; 32]).encrypt(b"secret").unwrap();
        let first = [1u8; 32];
        let second = [2u8; 32];
        storage
            .enable_totp_at_step("Alice", &encrypted, &[first, second], 100, None)
            .await
            .unwrap();
        let state = storage.totp_state("alice").await.unwrap().unwrap();
        assert_eq!(state.encrypted.parts(), encrypted.parts());
        assert_eq!(state.last_step, None);
        assert!(storage.consume_recovery("ALICE", &first).await.unwrap());
        assert!(!storage.consume_recovery("alice", &first).await.unwrap());
        assert!(storage.consume_recovery("alice", &second).await.unwrap());
    }

    #[tokio::test]
    async fn failed_totp_replacement_rolls_back_secret_and_codes() {
        let storage = Storage::open("sqlite::memory:").await.unwrap();
        storage.register("alice", "hash", false).await.unwrap();
        let cipher = SecretCipher::new([7; 32]);
        let original = cipher.encrypt(b"original").unwrap();
        storage
            .enable_totp_at_step("alice", &original, &[[1; 32]], 100, None)
            .await
            .unwrap();
        let replacement = cipher.encrypt(b"replacement").unwrap();
        assert!(
            storage
                .enable_totp_at_step("alice", &replacement, &[[2; 32], [2; 32]], 200, None)
                .await
                .is_err()
        );
        let state = storage.totp_state("alice").await.unwrap().unwrap();
        assert_eq!(
            cipher.decrypt(&state.encrypted).unwrap().as_slice(),
            b"original"
        );
        assert!(storage.consume_recovery("alice", &[1; 32]).await.unwrap());
        assert!(!storage.consume_recovery("alice", &[2; 32]).await.unwrap());
    }

    #[tokio::test]
    async fn totp_step_replay_disable_and_premium_trust_are_persisted() {
        let storage = Storage::open("sqlite::memory:").await.unwrap();
        storage.register("alice", "hash", true).await.unwrap();
        let encrypted = SecretCipher::new([7; 32]).encrypt(b"secret").unwrap();
        storage
            .enable_totp_at_step("alice", &encrypted, &[[1; 32]], 100, None)
            .await
            .unwrap();
        assert!(storage.accept_totp_step("alice", 40).await.unwrap());
        assert!(!storage.accept_totp_step("alice", 40).await.unwrap());
        assert!(!storage.accept_totp_step("alice", 39).await.unwrap());
        assert!(storage.accept_totp_step("alice", 41).await.unwrap());
        storage
            .set_premium_trust("Alice", "2001:db8::1", 200)
            .await
            .unwrap();
        assert_eq!(
            storage
                .premium_snapshot(199, 10)
                .await
                .unwrap()
                .get("alice")
                .unwrap()
                .ip,
            "2001:db8::1"
        );
        assert!(storage.premium_snapshot(200, 10).await.unwrap().is_empty());
        assert!(storage.disable_totp("alice").await.unwrap());
        assert!(storage.totp_state("alice").await.unwrap().is_none());
        assert!(!storage.consume_recovery("alice", &[1; 32]).await.unwrap());
    }
}
