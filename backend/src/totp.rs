use anyhow::{Context, ensure};
use chacha20poly1305::{
    XChaCha20Poly1305, XNonce,
    aead::{Aead, KeyInit},
};
use data_encoding::BASE32_NOPAD;
use hmac::{Hmac, Mac};
use percent_encoding::{AsciiSet, CONTROLS, utf8_percent_encode};
use rand::{Rng, RngCore, rngs::OsRng};
use sha1::Sha1;
use sha2::Sha256;
use std::fmt;
use zeroize::Zeroizing;

const URI_COMPONENT: &AsciiSet = &CONTROLS
    .add(b' ')
    .add(b'!')
    .add(b'"')
    .add(b'#')
    .add(b'$')
    .add(b'%')
    .add(b'&')
    .add(b'\'')
    .add(b'(')
    .add(b')')
    .add(b'*')
    .add(b'+')
    .add(b',')
    .add(b'/')
    .add(b':')
    .add(b';')
    .add(b'<')
    .add(b'=')
    .add(b'>')
    .add(b'?')
    .add(b'@')
    .add(b'[')
    .add(b'\\')
    .add(b']')
    .add(b'^')
    .add(b'`')
    .add(b'{')
    .add(b'|')
    .add(b'}');

pub(crate) struct TotpEngine {
    issuer: String,
    period: u64,
    window: u64,
}

impl TotpEngine {
    pub(crate) fn new(issuer: &str, period: u64, window: u64) -> anyhow::Result<Self> {
        ensure!(
            !issuer.trim().is_empty() && issuer.len() <= 64,
            "invalid TOTP issuer"
        );
        ensure!(
            (15..=120).contains(&period) && window <= 5,
            "invalid TOTP timing"
        );
        Ok(Self {
            issuer: issuer.to_owned(),
            period,
            window,
        })
    }

    pub(crate) fn generate_secret(&self) -> Zeroizing<Vec<u8>> {
        let mut secret = Zeroizing::new(vec![0u8; 20]);
        OsRng.fill_bytes(secret.as_mut_slice());
        secret
    }

    #[cfg(test)]
    pub(crate) fn code_at(&self, secret: &[u8], unix_seconds: u64) -> anyhow::Result<String> {
        ensure!(!secret.is_empty(), "empty TOTP secret");
        hotp(secret, unix_seconds / self.period)
    }

    pub(crate) fn verify_step(
        &self,
        secret: &[u8],
        code: &str,
        unix_seconds: u64,
        last_step: Option<u64>,
    ) -> Option<u64> {
        if code.len() != 6 || !code.bytes().all(|byte| byte.is_ascii_digit()) || secret.is_empty() {
            return None;
        }
        let current = unix_seconds / self.period;
        for offset in -(self.window as i64)..=(self.window as i64) {
            let Some(step) = current.checked_add_signed(offset) else {
                continue;
            };
            if last_step.is_some_and(|last| step <= last) {
                continue;
            }
            if hotp(secret, step).ok().as_deref() == Some(code) {
                return Some(step);
            }
        }
        None
    }

    pub(crate) fn provisioning_uri(&self, account: &str, secret: &[u8]) -> anyhow::Result<String> {
        ensure!(
            !account.trim().is_empty() && account.len() <= 64 && !secret.is_empty(),
            "invalid TOTP provisioning data"
        );
        let issuer = utf8_percent_encode(&self.issuer, URI_COMPONENT).to_string();
        let label =
            utf8_percent_encode(&format!("{}:{account}", self.issuer), URI_COMPONENT).to_string();
        Ok(format!(
            "otpauth://totp/{label}?secret={}&issuer={issuer}&algorithm=SHA1&digits=6&period={}",
            BASE32_NOPAD.encode(secret),
            self.period
        ))
    }
}

fn hotp(secret: &[u8], step: u64) -> anyhow::Result<String> {
    let mut mac = <Hmac<Sha1> as Mac>::new_from_slice(secret).context("invalid TOTP secret")?;
    mac.update(&step.to_be_bytes());
    let digest = mac.finalize().into_bytes();
    let offset = (digest[19] & 0x0f) as usize;
    let binary = (u32::from(digest[offset] & 0x7f) << 24)
        | (u32::from(digest[offset + 1]) << 16)
        | (u32::from(digest[offset + 2]) << 8)
        | u32::from(digest[offset + 3]);
    Ok(format!("{:06}", binary % 1_000_000))
}

#[derive(Clone)]
pub(crate) struct EncryptedSecret {
    version: u8,
    nonce: [u8; 24],
    ciphertext: Vec<u8>,
}

impl EncryptedSecret {
    pub(crate) fn from_parts(
        version: u8,
        nonce: &[u8],
        ciphertext: Vec<u8>,
    ) -> anyhow::Result<Self> {
        ensure!(
            version == 1 && nonce.len() == 24 && ciphertext.len() >= 16,
            "invalid encrypted TOTP secret"
        );
        let mut fixed = [0u8; 24];
        fixed.copy_from_slice(nonce);
        Ok(Self {
            version,
            nonce: fixed,
            ciphertext,
        })
    }
    #[cfg(test)]
    pub(crate) fn version(&self) -> u8 {
        self.version
    }
    pub(crate) fn parts(&self) -> (u8, [u8; 24], Vec<u8>) {
        (self.version, self.nonce, self.ciphertext.clone())
    }
}

impl fmt::Debug for EncryptedSecret {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("EncryptedSecret")
            .field("version", &self.version)
            .field("nonce", &"<redacted>")
            .field("ciphertext", &"<redacted>")
            .finish()
    }
}

pub(crate) struct SecretCipher(XChaCha20Poly1305);
impl SecretCipher {
    pub(crate) fn new(key: [u8; 32]) -> Self {
        Self(XChaCha20Poly1305::new((&key).into()))
    }
    pub(crate) fn encrypt(&self, secret: &[u8]) -> anyhow::Result<EncryptedSecret> {
        ensure!(
            !secret.is_empty() && secret.len() <= 128,
            "invalid TOTP secret"
        );
        let mut nonce = [0u8; 24];
        OsRng.fill_bytes(&mut nonce);
        let ciphertext = self
            .0
            .encrypt(XNonce::from_slice(&nonce), secret)
            .map_err(|_| anyhow::anyhow!("TOTP encryption failed"))?;
        Ok(EncryptedSecret {
            version: 1,
            nonce,
            ciphertext,
        })
    }
    pub(crate) fn decrypt(
        &self,
        encrypted: &EncryptedSecret,
    ) -> anyhow::Result<Zeroizing<Vec<u8>>> {
        ensure!(
            encrypted.version == 1,
            "unsupported TOTP encryption version"
        );
        self.0
            .decrypt(
                XNonce::from_slice(&encrypted.nonce),
                encrypted.ciphertext.as_slice(),
            )
            .map(Zeroizing::new)
            .map_err(|_| anyhow::anyhow!("TOTP decryption failed"))
    }
}

const RECOVERY_ALPHABET: &[u8] = b"23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
pub(crate) fn generate_recovery_codes(count: usize) -> anyhow::Result<Vec<String>> {
    ensure!((1..=32).contains(&count), "invalid recovery code count");
    let mut rng = OsRng;
    Ok((0..count)
        .map(|_| {
            let raw: String = (0..20)
                .map(|_| RECOVERY_ALPHABET[rng.gen_range(0..RECOVERY_ALPHABET.len())] as char)
                .collect();
            (0..4)
                .map(|group| &raw[group * 5..group * 5 + 5])
                .collect::<Vec<_>>()
                .join("-")
        })
        .collect())
}

pub(crate) fn normalize_recovery_code(code: &str) -> anyhow::Result<String> {
    let normalized: String = code
        .chars()
        .filter(|character| *character != '-' && !character.is_whitespace())
        .map(|character| character.to_ascii_uppercase())
        .collect();
    ensure!(
        normalized.len() == 20
            && normalized
                .bytes()
                .all(|byte| RECOVERY_ALPHABET.contains(&byte)),
        "invalid recovery code"
    );
    Ok(normalized)
}

pub(crate) fn recovery_hash(key: &[u8; 32], code: &str) -> anyhow::Result<[u8; 32]> {
    let normalized = normalize_recovery_code(code)?;
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("HMAC accepts any key size");
    mac.update(normalized.as_bytes());
    Ok(mac.finalize().into_bytes().into())
}
