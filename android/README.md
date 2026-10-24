# Android app

Native Kotlin / Jetpack Compose app. Controls a DJI Osmo Action camera over
Bluetooth LE using the DJI R SDK protocol verified with `tools/mac-probe`.

## Modules

| Module | What it is | Needs Android? |
| --- | --- | --- |
| `:protocol` | R SDK frame codec, CRC16/CRC32, command builders, status parser, frame reassembly | No, pure Kotlin |
| `:core` | `CameraSession` (handshake), `CameraConnectionManager` (connect, pairing, reconnect), workflow model and `WorkflowRunner` | No, pure Kotlin |
| `:app` | Bluetooth scanner and transport, foreground service, storage, Compose UI | Yes |

`:core` only depends on the `CameraTransport` interface, so a different
transport can plug in without touching the session, workflow engine, or UI.

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

Pushing a tag such as `v0.1.0` runs `.github/workflows/release.yml`, which
tests, builds a signed and shrunk (R8) release APK, verifies the signature, and
publishes it with a SHA-256 checksum on the GitHub Releases page.
`.github/workflows/ci.yml` runs the tests and a debug build on every push and
pull request.

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

3. Release: `git tag v0.1.0 && git push origin v0.1.0`

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
- `:core` runs the handshake, reconnect, and workflow engine against a fake
  camera that mimics the Action 5 Pro's observed behavior, including split
  notifications and interleaved DUML telemetry.

## Debugging

The app logs to logcat with tag `OsmoRemote`: Bluetooth state, every command
sent and answered (`tx` / `rx` / `timeout`), and every workflow step
(`wf: begin` / `wf: end`). The same log is visible in the app under the camera
page > **View connection log**, and can be shared from there.

```bash
adb logcat -v time OsmoRemote:D AndroidRuntime:E '*:S'
```
