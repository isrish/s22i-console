# S22i Console

An offline Android console for NordicTrack S22i bikes with a compatible FitPro USB controller. Native Java, no Gradle and no downloaded app libraries. Package: `dev.s22i.console`. Current version: **0.8.0**.

## Features

- Live power, cadence, speed, incline and reported magnetic resistance level.
- Explicit Start/End ride, elapsed time, estimated distance and whole-ride graphs.
- A full-width overlay for viewing ride statistics while using another app.
- A completed-ride summary with a photo view and optional date/time.
- Tap-to-wake dim mode and configurable inactivity dimming, defaulting to 30 minutes.
- Private ride storage and an idle-only deletion control in Settings.

The app has no Internet permission, analytics, accounts or cloud upload. Summary dates are hidden by default. Screenshots are created by the user; the app does not post or share them automatically.

## Compatibility and control limits

The current implementation targets Android 9 or later, a landscape console and the FitPro USB controller identified by vendor/product `213c:0002`, equipment type `8`. Compatibility with every S22i revision is not established.

The bike uses magnetic resistance. `Gear` in internal protocol names refers to field 26, not a mechanical drivetrain. The app displays the reported resistance level, but **on-screen resistance adjustments remain limited to levels 1 and 2**. Wider readback support is not evidence that wider app writes are safe or correct. The interface retains its test-limit label. Physical handlebar controls operate through the controller.

Connection alone does not start a workout. Manual adjustments require fresh validated state, and actuator writes are never automatically retried. Read [protocol constraints](docs/PROTOCOL.md) before changing controller code.

## Build

Install Python 3.9+, JDK 17 and Android SDK command-line tools. Set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your SDK; Java tools can also be found on `PATH`. Install the pinned SDK components:

```sh
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
python3 scripts/test_protocol.py
```

Production builds require a private signing key and its credentials. Use a secret manager or local environment for `S22I_STORE_PASSWORD` and, if different, `S22I_KEY_PASSWORD`. Never commit them.

```sh
export S22I_KEYSTORE="$HOME/.s22i/signing.keystore"
export S22I_KEY_ALIAS="s22i"
python3 scripts/build.py
```

For a **first installation only**, supply those credentials and run `python3 scripts/build.py --init-signing-key` to create a new key. Existing installations must keep their original key and alias to receive updates. Key initialization refuses to replace an existing file.

The default output is `build/s22i-console-release.apk`, signed, alignment-checked and non-debuggable. `python3 scripts/build.py --debug` builds a separate development APK with a local debug identity when no signing overrides are set. Neither command installs anything.

## Install and use

Enable USB debugging on your own console and authorize your computer. Select the intended device explicitly using `adb devices -l`, then set `S22I_SERIAL` locally to that device's serial or network endpoint.

```sh
adb -s "$S22I_SERIAL" install -r build/s22i-console-release.apk
adb -s "$S22I_SERIAL" shell am start -W -n dev.s22i.console/.MainActivity
```

Tap **Connect bike** and grant USB access if prompted. Start a ride explicitly. Enable Android's display-over-other-apps permission to use the floating panel. The panel can be expanded with an edge swipe and folded with its end arrow. **End ride** remains available while riding in another app.

**Settings** offers auto-dim, summary date/time visibility and saved-ride deletion. Deleting rides requires confirmation and an idle controller with no pending recovery. It does not remove screenshots or separately copied files.

## Privacy and development

Production builds disable diagnostic recording and Android `run-as` access. Debug builds offer explicitly opt-in private diagnostics while disconnected; identity/authentication payloads are redacted. Do not publish diagnostic recordings or real ride data. Android backups are disabled. This does not replace device-level Android or ADB security.

Only source, resources, synthetic tests, build tools and documentation belong in this repository. Local device inventories, experiment histories, captures, signing keys, APKs and third-party app downloads are excluded. Run the publication check after staging changes:

```sh
python3 scripts/check_publication.py
```

See [AGENTS.md](AGENTS.md) for architecture, verification commands and maintenance constraints.

## Attribution and license

Licensed under Apache-2.0; see [LICENSE](LICENSE). The protocol implementation adapts work from [STRIDE](https://github.com/keranm/strideApp) and [Hyperborea](https://github.com/ball2jh/Hyperborea). Original license texts, notices, pinned revisions and adaptation details are retained in [app/assets/licenses](app/assets/licenses/README.txt). No iFit applications, firmware or third-party media APKs are distributed here. NordicTrack and iFit are trademarks of their respective owners; this project is independent.
