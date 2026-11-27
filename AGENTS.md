# Working on S22i Console

## Start here

Read `README.md`, `CONTRIBUTING.md`, `docs/PROTOCOL.md`, `app/AndroidManifest.xml`, then the files relevant to the change. This is a native Java Android app, not a web app or Gradle project. Package `dev.s22i.console`, development version 0.9.0-test1 (public stable release 0.8.0), minimum and target API 28; compilation uses SDK 34. Python scripts invoke the Android build tools directly.

The repository is the public source distribution. Never depend on excluded local inventories, logs, experiment notes or signing keys. Do not commit personal paths, network addresses, device serials, authentication material, actual ride records, screenshots of user activity or downloaded APKs. README artwork and screenshots of empty state or synthetic sample previews are permitted after visual and metadata review. Source files intentionally contain no explanatory comments; maintain architecture and protocol rationale in these documents. Retain all third-party legal notices.

## Architecture

| Area | Files under `app/src/dev/s22i/console/` |
| --- | --- |
| Dashboard and USB permission flow | `MainActivity.java` |
| Foreground service, ride persistence and lifecycle | `RideService.java` |
| Single-owner USB worker, polling, authentication and explicit actions | `BikeSession.java` |
| Frame encoding, parsing and authentication | `FitPro.java`, `FitProAuthentication.java` |
| Pure control validation and initialization/recovery | `BikeControlPolicy.java`, `RideInitialization.java` |
| Read-only controller status interpretation | `ControllerDiagnostics.java` |
| Timeline, distance and statistics | `RideHistory.java`, `RideStats.java` |
| Saved summary and screenshot-friendly display | `RideSummary.java`, `RideSummaryActivity.java` |
| Floating panel and display dimming | `RideOverlay.java`, `DimActivity.java`, `ConsoleActivity.java`, `IdleTimer.java` |
| Settings, privacy and redaction | `ConsoleSettings.java`, `AutoDimSettings.java`, `AppPrivacy.java`, `PrivateFiles.java`, `ProtocolLog.java` |
| Decorative canvas elements | `ConsoleArtwork.java` |

The foreground service owns the session across activities and media apps. Do not move USB ownership into a view or start a second worker. UI callbacks belong on the main thread; blocking USB work belongs in the session worker. The UI is drawn and laid out in Java. Resource XML holds icons and theme colors.

## Commands

Set `JAVA_HOME` to JDK 17 and `ANDROID_HOME` to an SDK containing platform 34 and build-tools 34.0.0.

```sh
python3 scripts/test_protocol.py
python3 -m unittest discover -s tests -v
python3 scripts/build.py --debug
python3 scripts/build.py
python3 scripts/check_publication.py
```

The default build is production, non-debuggable and diagnostic recording is disabled. Supply the signing credentials described in README through the local environment. Never print passwords or commit a keystore. Production initialization is a separate explicit `--init-signing-key` action for first installations only. Preserve the installed app's signing identity for updates. Builds do not install or launch anything.

Tests are pure Java and Python with synthetic inputs and no Android hardware dependency. Verify build alignment/signature and inspect debug flags when changing build modes. Run relevant tests after behavior changes; a documentation-only change does not require hardware testing. Publication checks inspect the Git index, so stage the intended files first.

## Controller invariants

- The bike uses magnetic resistance; internal `Gear` names refer to protocol field 26.
- App resistance writes default to levels 1 and 2. The development build permits explicit, session-only test ceilings of 3, 8, 16 and 24 after fresh idle, zero-cadence and reported-maximum checks. Displayed levels and diagnostic profiles must never expand that policy implicitly. Keep experimental builds separate from stable releases until supervised hardware validation.
- Require fresh validated controller state and explicit user actions. Connection never starts a ride.
- Validate the complete frame and its request/equipment binding. Unknown or malformed values stay unavailable.
- Never automatically retry actuator writes, restore a resistance level, or introduce calibration/reset/firmware commands.
- Preserve original initialization flags before mutation and retain ownership/recovery state until successful cleanup. End ride must remain reachable after failed adjustments.
- A matching readback is not proof of physical braking force. Simulation and local tests cannot establish compatibility with a new bike revision.
- Hardware testing must be supervised with a person at the bike. Do not interrupt an active ride, install over one, send a control command, or delete real ride history merely to test a change.

## Privacy and UI invariants

The app has no Internet permission or network SDK. Keep backups disabled, service/internal activities unexported, USB callbacks scoped and validated, and external launch intents unable to initiate a connection. Production must reject `run-as`; do not install a debug build merely to bypass that boundary.

Only explicitly opted-in debug builds record private diagnostics, with metadata/authentication payloads redacted. Never add raw packet or workout Logcat output. Settings hides summary timestamps by default; Photo view must honor it. Deletion is confirmed, idle-only and confined to a file allowlist; it must not delete recovery state. The process must not retain a deleted summary on screen.

Ride distance integrates original accepted speed samples, not thinned graph points. Missing or stale samples must not invent movement. Keep the ride timeline, stopwatch and overlay consistent across activity transitions. Auto-dim applies to visible console/summary windows, skips active use and must not interrupt media playback in other apps. Leave End ride and tap-to-wake accessible.

## Public changes

Use a focused branch for follow-up work. Keep protocol changes separate from visual changes when possible. Before publishing, inspect staged content and run `scripts/check_publication.py`. Add only source, resources, synthetic tests, build tooling, documentation and reviewed README images; never force-add ignored files. The publication checker pins each approved image under `docs/assets/` by SHA-256: review pixels and metadata before updating its hash. An image hash is an integrity check, not an automatic privacy review. Use a GitHub noreply author address. Preserve upstream attribution in `app/assets/licenses/` and the root license. New copied code requires compatible licensing and attribution.

Document what changed, what was tested and any unverified hardware behavior. No local paths, rider anecdotes or session logs belong in a public commit or issue. The current test restriction on resistance is a known limitation, not a UI defect to remove without verification.

## Downloadable releases

APKs are GitHub Release assets, never tracked source files. The public download names are `s22i-console-release.apk`, `s22i-console-debug.apk` and `SHA256SUMS.txt`. Keep these names stable for the README's latest-release links. Build both variants from the tagged source and use the existing maintainer signing identity for published updates; never publish the keystore or credentials. Both published variants replace the same package, so explain the debug privacy difference and recommend production.

Before publishing, verify version/package metadata, the debug flag for each variant, signatures and alignment, absence of Internet permission and backups, packaged-file contents, and accidental identifiers or private paths. Include SHA-256 hashes and known control limits in the release. Verify downloaded assets match the local hashes. Publishing an APK does not authorize installing it over an active ride or replacing production with debug on a device.
