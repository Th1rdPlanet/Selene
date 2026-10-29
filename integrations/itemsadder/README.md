# Selene CAPTCHA for ItemsAdder

Copy the `selene` directory to `plugins/ItemsAdder/contents/`, then run `/iazip`.
The pack defines two bitmap fonts used by Paper authentication dialogs:

- `selene:captcha` contains eight shaded cells (`U+E100`–`U+E107`) and an equal-width
  blank (`U+E108`). The backend draws a mosaic, so no codepoint identifies a digit;
- `selene:qr` contains every possible 2×4 module tile (`U+E400..U+E4FF`) used to render
  authenticator QR codes without sending a dynamically rebuilt resource pack.

The generated files follow ItemsAdder's raw resource-pack content layout:
`contents/selene/resourcepack/assets/selene/...`.

Regenerate the atlas after changing its design:

```bash
python3 integrations/itemsadder/generate_captcha_font.py
python3 integrations/itemsadder/generate_qr_font.py
python3 integrations/itemsadder/test_fonts.py
```

After regeneration copy `integrations/itemsadder/selene` into
`plugins/ItemsAdder/contents/selene`, run `/iazip`, and let ItemsAdder distribute the pack.
Keep `captcha.font: selene:captcha` and `dialogs.totp-setup.qr-font: selene:qr` in
Paper `interface.yml`.

Packs built before the shade-cell layout are incompatible and must be regenerated.
