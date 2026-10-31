
## Install the app

1. Download `osmo-action-remote-<version>.apk` below on your Android phone (Android 12 or newer).
2. Open it. If Android asks, allow your browser or file manager to install unknown apps.
3. Optional: compare the file with `osmo-action-remote-<version>.apk.sha256`.

## Optional: ESP32 relay (needed to wake the camera)

| File | Board |
| --- | --- |
| `osmo-bridge-esp32c3.bin` | ESP32-C3 with built-in USB |
| `osmo-bridge-esp32s3.bin` | ESP32-S3 with built-in USB (not yet tested on hardware) |
| `osmo-bridge-esp32.bin` | original ESP32 with a CP210x or CH34x USB chip |

Each `.bin` is a complete image: write it at address **`0x0`** with any ESP
flasher, for example [esptool-js](https://espressif.github.io/esptool-js/) in
Chrome, an Android flasher app over USB OTG, or
`esptool write_flash 0x0 osmo-bridge-esp32c3.bin`. Checksums are in `SHA256SUMS`.

Then plug the board into the phone, choose **ESP32 on USB** on the Connect
screen, and pair once on the camera. Waking needs camera firmware 01.04.08.10
or later. Full guide: [firmware/README.md](https://github.com/retweakeverything/osmo-action-remote/tree/main/firmware#flashing).
