# Releasing

The launcher icon shows the app version. For every new version:

1. Bump `versionCode` and `versionName` in `app/build.gradle`.
2. Run `python3 tools/make_icons.py <sticker.png|webp>` (needs Pillow). It reads `versionName` and rewrites the launcher PNGs with a "v<version>" badge.
3. Run `python3 tools/make_icons.py <sticker> --preview preview.png` and look at the circle, rounded and small sizes. A longer version string widens the badge, so check it still fits.
4. Build, then confirm the built APK's versionName matches the badge.

Do not cut a release only to change the icon.
