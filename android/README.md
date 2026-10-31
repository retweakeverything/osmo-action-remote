# Android app

Native Kotlin / Jetpack Compose app. Controls a DJI Osmo Action camera over
Bluetooth LE using the DJI R SDK protocol verified with `tools/mac-probe`,
either with the phone's own Bluetooth or through an ESP32 relay on USB
([`firmware/`](../firmware/README.md)).

## Modules

| Module | What it is | Needs Android? |
| --- | --- | --- |
| `:protocol` | R SDK frame codec, CRC16/CRC32, command builders, status parser, frame reassembly | No, pure Kotlin |
| `:core` | `CameraSession` (handshake), `CameraConnectionManager` (connect, pairing, reconnect, sleep/wake), workflow model and `WorkflowRunner`, and the ESP32 bridge client (`core/bridge`) | No, pure Kotlin |
| `:app` | Phone Bluetooth route, USB serial link, foreground service, storage, Compose UI | Yes |

## Routes

A `CameraRoute` bundles a scanner, a transport factory and a waker. There are
two:

| Route | Scanner / transport | Waker |
| --- | --- | --- |
| `phone` (`PhoneRoute`) | `BleCameraScanner`, `BleCameraTransport` | `BleCameraWaker`: the wake advert from the phone |
| `usb` (`BridgeRoute`) | `BridgeScanner`, `BridgeTransport` over `BridgeClient` | `BridgeWaker`: `WAKE` sent to the ESP32 |

`RouteSelector` holds the user's choice; the connection manager, workflow
engine and UI only ever call the active route. Both routes send the same R SDK
bytes and the same wake payload (`protocol/WakeAdvert`). Pairings are stored
per route (`RoutePairingStore`), because the camera sees the phone and the
ESP32 as different remotes.

The USB link uses [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)
3.11.0, which handles the ESP32-C3/S3 built-in USB (CDC-ACM) and CP210x / CH34x
chips. It is only published on JitPack, so `settings.gradle.kts` uses JitPack
for that one group and nothing else.

## Build

Requirements: Android SDK platform 36 and build-tools 36.1.0, JDK 17 or 21.
JDK 25 is too new for the Android Gradle Plugin; Android Studio's bundled JDK works:

```bash
cd android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS default SDK path
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :protocol:test :core:test   # JVM unit tests, no phone needed
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

Or open `android/` in Android Studio and run the `app` configuration.

Compose and AndroidX are pinned one release back (Compose BOM 2026.06.01,
core-ktx 1.18, lifecycle 2.10) because the newest versions require compileSdk 37.

## Releases

Pushing a tag such as `v0.2.0` runs `.github/workflows/release.yml`, which:

- tests, builds a signed and shrunk (R8) release APK, verifies the signature,
  and publishes it with a SHA-256 checksum on the GitHub Releases page;
- builds the ESP32 relay images (`firmware/build.sh`) and attaches them to the
  same release.

The release page text is `docs/releases/<tag>.md` (what's new) followed by
`docs/releases/install.md` (how to install the APK and flash the ESP32), then
GitHub's generated list of changes. Add `docs/releases/v0.3.0.md` before
tagging v0.3.0.

`.github/workflows/ci.yml` runs the tests, a debug build and a firmware build
on every push and pull request.


One-time signing setup:

1. Create a release key **outside the repo** and back it up somewhere safe.
   If it is lost, future updates cannot install over existing copies.

   ```bash
   keytool -genkeypair -keystore ~/osmo-release.jks -storetype PKCS12 \
     -alias osmo -keyalg RSA -keysize 4096 -validity 10000
   ```

2. In the GitHub repo: Settings > Secrets and variables > Actions, add:

   | Secret | Value |
   | --- | --- |
   | `OSMO_KEYSTORE_BASE64` | output of `base64 -i ~/osmo-release.jks` |
   | `OSMO_KEYSTORE_PASSWORD` | keystore password |
   | `OSMO_KEY_ALIAS` | `osmo` |
   | `OSMO_KEY_PASSWORD` | key password (same as keystore password for PKCS12) |

3. Release: write `docs/releases/v0.2.0.md`, commit, then `git tag v0.2.0 && git push origin v0.2.0`

The version name comes from the tag and the version code is
`major * 10000 + minor * 100 + patch`. Locally, a release build is signed only
when the `OSMO_*` environment variables are set; otherwise it is left unsigned.

## Install on a phone

1. Settings > About phone > Software information: tap **Build number** 7 times.
2. Developer options: enable **USB debugging**, connect over USB, accept the prompt.
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Tests

- `:protocol` checks every command frame byte for byte against the frames a
  real Action 5 Pro accepted from the Python probe, plus DJI's golden
  connection frames.
- `:core` runs the handshake, reconnect, sleep and wake, and workflow engine
  against a fake camera that mimics the Action 5 Pro's observed behavior,
  including split notifications and interleaved DUML telemetry. The bridge
  tests run a whole session through a fake osmo-bridge firmware, line by line.

## Debugging

The app logs to logcat with tag `OsmoRemote`: Bluetooth state, every command
sent and answered (`tx` / `rx` / `timeout`), and every workflow step
(`wf: begin` / `wf: end`). The same log is visible in the app under the camera
page > **View connection log**, and can be shared from there.

```bash
adb logcat -v time OsmoRemote:D AndroidRuntime:E '*:S'
```
