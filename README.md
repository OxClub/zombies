# OX Zombie Shooter

A 2D twin-stick zombie shooter for Android (Kotlin, no engine, no dependencies).

- **Left thumb** – move (floating joystick)
- **Right thumb** – aim; holding it fires
- Waves get bigger and faster; fast zombies from wave 2, tanks from wave 3
- Weapon upgrades: double shot at wave 4, triple shot at wave 8
- Health packs drop from zombies; a wave clear heals 15 HP
- High score is saved on the device
- Sound: gunshots, hit/kill squelches, three zombie groans (higher-pitched for fast zombies, deep for tanks), a tank roar, pickup, wave and game-over sounds. Zombie sounds are panned left/right by position and quieter when far away.
- Speaker button (top-right on the menu, pause and game-over screens) mutes/unmutes; the choice is saved.

### Sounds

All sounds are synthesized (no samples, no licensing issues) by `tools/generate_sounds.py` and stored in `app/src/main/res/raw/`.
To tweak them, edit the script and run `pip install numpy && python3 tools/generate_sounds.py`.
To use your own recordings, replace the `.wav` files with the same names.

## Build with GitHub Actions

1. Create a GitHub repo and push this whole folder to it.
2. Open the **Actions** tab. The *Build Android* workflow runs on every push (or run it manually).
3. Download the artifacts from the finished run:
   - `OXZombieShooter-debug-apk` – install directly on your phone to test
   - `OXZombieShooter-release-aab` – the file you upload to Google Play

## Signing the release for the Play Store

Create a keystore once (keep it safe and backed up – you need the same key for every future update):

```bash
keytool -genkeypair -v -keystore release.keystore -alias ox -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 release.keystore   # macOS: base64 -i release.keystore
```

Add these under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the base64 output above |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `ox` (or your alias) |
| `KEY_PASSWORD` | key password |

Re-run the workflow and the release AAB/APK will be signed. `versionCode` is set from the GitHub run number, so it goes up automatically with each build.

## Play Store checklist

- Play Console account ($25 one-time). New personal accounts must run a closed test with 12 testers for 14 days before applying for production; organization accounts are exempt.
- Upload the `.aab`, and the 512×512 icon in `store/play-store-icon-512.png`.
- You still need to add: screenshots, a 1024×500 feature graphic, short and full description, content rating, data safety form, and a privacy policy URL.
- The app targets Android 16 (API 36) and needs no permissions and collects no data.

## Change the package name

The app ID is `com.ox.zombieshooter`. Before publishing, change it to your own (e.g. `com.yourname.oxzombie`) in `app/build.gradle.kts` (`namespace` and `applicationId`) and move the Kotlin files to the matching folder / `package` line.
