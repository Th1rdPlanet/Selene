use crate::totp::{
    SecretCipher, TotpEngine, generate_recovery_codes, normalize_recovery_code, recovery_hash,
};

#[test]
fn rfc6238_sha1_vectors_are_truncated_to_six_digits() {
    let engine = TotpEngine::new("Selene", 30, 1).unwrap();
    let secret = b"12345678901234567890";
    assert_eq!(engine.code_at(secret, 59).unwrap(), "287082");
    assert_eq!(engine.code_at(secret, 1_111_111_109).unwrap(), "081804");
}

#[test]
fn verification_accepts_adjacent_steps_and_rejects_replay() {
    let engine = TotpEngine::new("Selene", 30, 1).unwrap();
    let secret = b"12345678901234567890";
    let previous = engine.code_at(secret, 30).unwrap();
    let step = engine.verify_step(secret, &previous, 60, None).unwrap();
    assert_eq!(step, 1);
    assert_eq!(engine.verify_step(secret, &previous, 60, Some(step)), None);
    assert_eq!(engine.verify_step(secret, "12345x", 60, None), None);
}

#[test]
fn provisioning_uri_escapes_labels_and_uses_unpadded_base32() {
    let engine = TotpEngine::new("Selene Auth", 30, 1).unwrap();
    let uri = engine
        .provisioning_uri("Vic Tim", b"hello!\xde\xad")
        .unwrap();
    assert_eq!(
        uri,
        "otpauth://totp/Selene%20Auth%3AVic%20Tim?secret=NBSWY3DPEHPK2&issuer=Selene%20Auth&algorithm=SHA1&digits=6&period=30"
    );
    assert!(!uri.contains("secret=NBSWY3DPEHPK2="));
}

#[test]
fn encrypted_secret_round_trips_and_rejects_tampering() {
    let cipher = SecretCipher::new([7; 32]);
    let encrypted = cipher.encrypt(b"top secret").unwrap();
    assert_eq!(encrypted.version(), 1);
    assert_eq!(
        cipher.decrypt(&encrypted).unwrap().as_slice(),
        b"top secret"
    );
    let (version, nonce, mut ciphertext) = encrypted.parts();
    ciphertext[0] ^= 1;
    let damaged = crate::totp::EncryptedSecret::from_parts(version, &nonce, ciphertext).unwrap();
    assert!(cipher.decrypt(&damaged).is_err());
    assert!(!format!("{encrypted:?}").contains("top secret"));
}

#[test]
fn recovery_codes_are_high_entropy_normalized_and_keyed() {
    let codes = generate_recovery_codes(10).unwrap();
    assert_eq!(codes.len(), 10);
    assert!(
        codes
            .iter()
            .all(|code| code.len() == 23 && code.chars().filter(|c| *c == '-').count() == 3)
    );
    let normalized = normalize_recovery_code(&codes[0]).unwrap();
    assert_eq!(normalized.len(), 20);
    assert_eq!(
        recovery_hash(&[9; 32], &codes[0]).unwrap(),
        recovery_hash(&[9; 32], &codes[0].to_ascii_lowercase()).unwrap()
    );
    assert_ne!(
        recovery_hash(&[9; 32], &codes[0]).unwrap(),
        recovery_hash(&[8; 32], &codes[0]).unwrap()
    );
}
