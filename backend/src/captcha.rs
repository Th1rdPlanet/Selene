use base64::Engine;
use rand::{Rng, rngs::OsRng};
pub struct Challenge {
    pub(crate) answer: String,
    pub image: String,
    pub glyphs: String,
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
    // Each decimal digit has sixteen visually different glyphs in the Selene pack.
    // The codepoints reveal neither the answer in logs nor to ordinary fonts.
    let captcha_glyphs: String = answer
        .bytes()
        .map(|digit| {
            let variant = rng.gen_range(0..16) as u32;
            char::from_u32(0xE000 + u32::from(digit - b'0') * 16 + variant).unwrap()
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
