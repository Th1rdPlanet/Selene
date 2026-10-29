use base64::Engine;
use rand::{Rng, rngs::OsRng};
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
    let captcha_glyphs = mosaic(&answer, &glyphs, &mut rng);
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

/// First of eight pixel glyphs in `selene:captcha`, one per shade.
pub(crate) const PIXEL_BASE: u32 = 0xE100;
/// Transparent glyph with the same advance as a pixel.
pub(crate) const BLANK: char = '\u{E108}';
pub(crate) const COLUMNS: usize = 31;
pub(crate) const ROWS: usize = 10;

/// Draws the answer as rows of pixel glyphs. A codepoint names only a shade, never a digit,
/// so a client has to recognise the picture instead of mapping glyphs back to the answer.
fn mosaic(answer: &str, glyphs: &[[&str; 7]; 10], rng: &mut OsRng) -> String {
    let mut grid = [[None::<u32>; COLUMNS]; ROWS];
    for (i, digit) in answer.bytes().enumerate() {
        let left = 1 + i * 6;
        let top = rng.gen_range(1..=2);
        let shade = rng.gen_range(4..8);
        for (y, row) in glyphs[(digit - b'0') as usize].iter().enumerate() {
            for (x, c) in row.bytes().enumerate() {
                if c == b'1' && !rng.gen_bool(0.04) {
                    grid[top + y][left + x] = Some(if rng.gen_bool(0.2) {
                        rng.gen_range(4..8)
                    } else {
                        shade
                    });
                }
            }
        }
    }
    for cell in grid.iter_mut().flatten() {
        if cell.is_none() && rng.gen_bool(0.09) {
            *cell = Some(rng.gen_range(0..8));
        }
    }
    grid.iter()
        .map(|row| {
            row.iter()
                .map(|cell| cell.map_or(BLANK, |shade| char::from_u32(PIXEL_BASE + shade).unwrap()))
                .collect::<String>()
        })
        .collect::<Vec<_>>()
        .join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn glyphs_encode_a_shade_mosaic_not_digit_bands() {
        for _ in 0..32 {
            let challenge = generate();
            assert_eq!(challenge.answer.len(), 5);
            let rows: Vec<&str> = challenge.glyphs.split('\n').collect();
            assert_eq!(rows.len(), 10);
            assert!(rows.iter().all(|row| row.chars().count() == 31));
            assert!(rows.iter().flat_map(|row| row.chars())
                .all(|glyph| ('\u{e100}'..='\u{e108}').contains(&glyph)));
            let lit = rows.iter().flat_map(|row| row.chars())
                .filter(|glyph| *glyph != '\u{e108}').count();
            assert!(lit > 50, "digits must be drawn, got {lit} lit pixels");
            assert!(!format!("{challenge:?}").contains(&challenge.answer));
        }
    }
}
