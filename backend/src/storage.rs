use anyhow::Context;
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
impl Storage {
    pub async fn open(url: &str) -> anyhow::Result<Self> {
        sqlx::any::install_default_drivers();
        let pool = AnyPoolOptions::new()
            .max_connections(if url.contains(":memory:") { 1 } else { 8 })
            .acquire_timeout(std::time::Duration::from_secs(5))
            .connect(url)
            .await
            .context("database connection failed")?;
        sqlx::query("CREATE TABLE IF NOT EXISTS selene_accounts (name VARCHAR(16) PRIMARY KEY, password_hash VARCHAR(255) NOT NULL, premium INTEGER NOT NULL, trusted_ip VARCHAR(45) NOT NULL, trusted_until BIGINT NOT NULL)").execute(&pool).await.context("cannot create or validate selene_accounts schema")?;
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
    }
    pub async fn register(&self, name: &str, hash: &str, premium: bool) -> anyhow::Result<bool> {
        let result=sqlx::query(&self.sql("INSERT INTO selene_accounts (name,password_hash,premium,trusted_ip,trusted_until) VALUES (?,?,?,'',0)")).bind(name.to_ascii_lowercase()).bind(hash).bind(if premium{1i32}else{0}).execute(&self.pool).await;
        match result {
            Ok(_) => Ok(true),
            Err(sqlx::Error::Database(e)) if e.is_unique_violation() => Ok(false),
            Err(e) => Err(e.into()),
        }
    }
    pub async fn trust(&self, name: &str, ip: &str, until: i64) -> anyhow::Result<()> {
        sqlx::query(
            &self
                .sql("UPDATE selene_accounts SET trusted_ip = ?, trusted_until = ? WHERE name = ?"),
        )
        .bind(ip)
        .bind(until)
        .bind(name)
        .execute(&self.pool)
        .await?;
        Ok(())
    }
    pub async fn snapshot(
        &self,
        now: i64,
    ) -> anyhow::Result<(BTreeMap<String, String>, Vec<String>)> {
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
    }
    pub async fn admin_account(&self, name: &str) -> anyhow::Result<Option<crate::AdminAccount>> {
        let row=sqlx::query(&self.sql("SELECT name,password_hash,premium,trusted_ip,trusted_until FROM selene_accounts WHERE name = ?")).bind(name.to_ascii_lowercase()).fetch_optional(&self.pool).await?;
        Ok(row.map(|r| {
            crate::admin::account(
                r.get("name"),
                r.get::<i32, _>("premium") != 0,
                r.get("trusted_ip"),
                r.get("trusted_until"),
                r.get("password_hash"),
            )
        }))
    }
    pub async fn admin_list(
        &self,
        page: u32,
        page_size: u32,
    ) -> anyhow::Result<(Vec<crate::AdminAccount>, bool)> {
        let limit = page_size.min(50) as i64;
        let offset = (page.saturating_sub(1) as i64).saturating_mul(limit);
        let rows=sqlx::query(&self.sql("SELECT name,password_hash,premium,trusted_ip,trusted_until FROM selene_accounts ORDER BY name ASC LIMIT ? OFFSET ?")).bind(limit+1).bind(offset).fetch_all(&self.pool).await?;
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
                )
            })
            .collect();
        Ok((accounts, has_more))
    }
    pub async fn admin_delete(&self, name: &str) -> anyhow::Result<bool> {
        Ok(
            sqlx::query(&self.sql("DELETE FROM selene_accounts WHERE name = ?"))
                .bind(name.to_ascii_lowercase())
                .execute(&self.pool)
                .await?
                .rows_affected()
                > 0,
        )
    }
    pub async fn admin_reset_password(&self, name: &str) -> anyhow::Result<bool> {
        Ok(
            sqlx::query(&self.sql("UPDATE selene_accounts SET password_hash = '' WHERE name = ?"))
                .bind(name.to_ascii_lowercase())
                .execute(&self.pool)
                .await?
                .rows_affected()
                > 0,
        )
    }
    pub async fn register_reset(&self, name: &str, hash: &str) -> anyhow::Result<bool> {
        Ok(sqlx::query(&self.sql(
            "UPDATE selene_accounts SET password_hash = ? WHERE name = ? AND password_hash = ''",
        ))
        .bind(hash)
        .bind(name.to_ascii_lowercase())
        .execute(&self.pool)
        .await?
        .rows_affected()
            == 1)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn expired_trust_rows_do_not_exhaust_snapshot_capacity() {
        let storage = Storage::open("sqlite::memory:").await.unwrap();
        sqlx::query("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<4097) INSERT INTO selene_accounts SELECT 'user'||x, '', 0, '127.0.0.1', 1 FROM n").execute(&storage.pool).await.unwrap();
        assert!(storage.snapshot(2).await.is_ok());
    }
}
