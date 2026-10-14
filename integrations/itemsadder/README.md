# Selene CAPTCHA for ItemsAdder

Copy the `selene` directory to `plugins/ItemsAdder/contents/`, then run `/iazip`.
The pack defines the `selene:captcha` bitmap font used by Paper authentication dialogs.
Its 160 private-use glyphs are arranged as sixteen visual variants for each decimal digit.

The generated files follow ItemsAdder's raw resource-pack content layout:
`contents/selene/resourcepack/assets/selene/...`.

Regenerate the atlas after changing its design:

```bash
python3 integrations/itemsadder/generate_captcha_font.py
```
