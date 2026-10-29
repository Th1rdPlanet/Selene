use base64::Engine;
use rand::{rngs::OsRng, Rng};
pub struct Challenge {
    pub(crate) answer: String,
    pub image: String,
    pub glyphs: String,
}
impl std::fmt::Debug for Challenge {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("Challenge")
            .field("answer", &"<redacted>")
            .field("image_bytes", &self.image.len())
            .field("glyph_count", &self.glyphs.chars().count())
            .finish()
    }
}
pub fn generate() -> Challenge {
    let glyphs: [[&str; 7]; 10] = [
        [
            "01110", "11011", "11011", "11011", "11011", "11011", "01110",
        ],
        [
            "00100", "01100", "00100", "00100", "00100", "00100", "01110",
        ],
        [
            "01110", "10001", "00001", "00010", "00100", "01000", "11111",
        ],
        [
            "11110", "00001", "00001", "01110", "00001", "00001", "11110",
        ],
        [
            "00010", "00110", "01010", "10010", "11111", "00010", "00010",
        ],
        [
            "11111", "10000", "10000", "11110", "00001", "00001", "11110",
        ],
        [
            "01110", "10000", "10000", "11110", "10001", "10001", "01110",
        ],
        [
            "11111", "00001", "00010", "00100", "01000", "01000", "01000",
        ],
        [
            "01110", "10001", "10001", "01110", "10001", "10001", "01110",
        ],
        [
            "01110", "10001", "10001", "01111", "00001", "00001", "01110",
        ],
    ];
    let mut rng = OsRng;
    let answer: String = (0..5)
        .map(|_| char::from(b'0' + rng.gen_range(0..10)))
        .collect();
    // Each decimal digit has sixty-four visually different glyphs in the Selene pack.
    // The codepoints reveal neither the answer in logs nor to ordinary fonts.
    let captcha_glyphs: String = answer
        .bytes()
        .map(|digit| {
            let variant = rng.gen_range(0..64) as u32;
            char::from_u32(0xE000 + u32::from(digit - b'0') * 64 + variant).unwrap()
        })
        .collect();
    let mut pixels = vec![236u8; 128 * 128 * 3];
    for (i, digit) in answer.bytes().enumerate() {
        let offset = rng.gen_range(-5i32..6);
        for (y, row) in glyphs[(digit - b'0') as usize].iter().enumerate() {
            for (x, c) in row.bytes().enumerate() {
                if c == b'1' {
                    for dy in 0..5 {
                        for dx in 0..3 {
                            let yy = 42 + y as i32 * 5 + dy + offset;
                            let xx = 8
                                + i as i32 * 23
                                + x as i32 * 3
                                + dx
                                + ((yy as f32 / 9.0).sin() * 3.0) as i32;
                            let p = (yy as usize * 128 + xx as usize) * 3;
                            pixels[p..p + 3].copy_from_slice(&[36, 52, 64]);
                        }
                    }
                }
            }
        }
    }
    for _ in 0..1100 {
        let p = rng.gen_range(0..128 * 128) * 3;
        let shade = rng.gen_range(110..245);
        pixels[p..p + 3].fill(shade);
    }
    let mut output = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut output, 128, 128);
        encoder.set_color(png::ColorType::Rgb);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().expect("valid PNG parameters");
        writer.write_image_data(&pixels).expect("memory PNG output");
    }
    Challenge {
        answer,
        image: base64::engine::general_purpose::STANDARD.encode(output),
        glyphs: captcha_glyphs,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn glyphs_use_five_digits_and_sixty_four_variant_bands() {
        let mut variants = HashSet::new();
        for _ in 0..128 {
            let challenge = generate();
            assert_eq!(challenge.answer.len(), 5);
            assert_eq!(challenge.glyphs.chars().count(), 5);
            for (digit, glyph) in challenge.answer.bytes().zip(challenge.glyphs.chars()) {
                let offset = glyph as u32 - 0xE000;
                assert_eq!(offset / 64, u32::from(digit - b'0'));
                assert!(offset % 64 < 64);
                variants.insert(offset % 64);
            }
            assert!(!format!("{challenge:?}").contains(&challenge.answer));
        }
        assert!(
            variants.len() > 32,
            "variant selection lacks expected entropy"
        );
    }
}
