# ESP32 relay firmware (osmo-bridge)

Optional hardware for the Android app. An ESP32 plugged into the phone over
USB relays the app's camera commands over its own Bluetooth radio. The app
keeps all the camera logic; the ESP32 only scans, connects, passes bytes
through, and sends DJI's wake advertisement.

Why bother: the camera only wakes up for an advertisement from a fixed
(public) Bluetooth address. An ESP32 has one; Android apps cannot use the
phone's. Everything else works the same on both routes.

## Supported boards

| Chip | Image | USB |
| --- | --- | --- |
| ESP32-C3 | `osmo-bridge-esp32c3.bin` | built-in USB (shows up as CDC-ACM) |
| ESP32-S3 | `osmo-bridge-esp32s3.bin` | built-in USB port (the one labelled USB, not UART) |
| ESP32 (original) | `osmo-bridge-esp32.bin` | CP210x or CH34x USB chip |

Tested on hardware: an ESP32-C3 board with built-in USB and an ESP32-D0WD-V3
dev board with a CP2102 chip. The S3 image is built the same way but has not
been tested on hardware yet.

To use it with a phone you need a **USB OTG** connection: a USB-C to USB-C
data cable for C3/S3 boards with USB-C, or a micro-USB to USB-C OTG cable for
older boards. A charge-only cable will not work.

## Flashing

Download the `.bin` for your chip from the
[latest release](https://github.com/retweakeverything/osmo-action-remote/releases/latest)
(`SHA256SUMS` is next to it). Each `.bin` is a complete image (bootloader,
partition table and app): write it at address **`0x0`** with any ESP flasher.

### 1. A web flasher

For example Espressif's [esptool-js](https://espressif.github.io/esptool-js/),
in Chrome or Edge on a computer, or a recent Chrome on Android:

1. Plug in the board and click **Connect**, then pick its serial port.
2. Set **Flash Address** to `0x0`, choose the `.bin`, and click **Program**.
3. When it finishes, press the board's reset button (or unplug and replug it).

### 2. An ESP flasher app on Android

Open the `.bin` in a flasher app that supports USB OTG (for example *ESPFlash*
on Google Play) and flash it at address `0x0`.

### 3. esptool on a computer

```bash
pip install esptool
esptool --chip auto write_flash 0x0 osmo-bridge-esp32c3.bin
```

### 4. Arduino IDE or arduino-cli (from source)

- Board package **esp32 by Espressif 3.3.11** (Boards Manager URL
  `https://espressif.github.io/arduino-esp32/package_esp32_index.json`)
- Library **NimBLE-Arduino 2.5.1**
- Open `firmware/osmo-bridge/osmo-bridge.ino`, pick your board, and for
  C3/S3 set **Tools > USB CDC On Boot > Enabled**. Upload.

With arduino-cli:

```bash
arduino-cli compile -b esp32:esp32:esp32c3:CDCOnBoot=cdc firmware/osmo-bridge
arduino-cli upload  -b esp32:esp32:esp32c3:CDCOnBoot=cdc -p /dev/cu.usbmodem1101 firmware/osmo-bridge
```

`firmware/build.sh` builds all three release images into `firmware/dist/`
(the release workflow runs it). Change the sketch and run it to make your own
images.

If a board will not enter download mode, hold **BOOT**, tap **RST**, then
release BOOT.

## Check it

Open any serial monitor at 115200 baud and send `HELLO`. It answers
`HELLO osmo-bridge <version> <bluetooth-address>`. The app checks for exactly
this line; without it, the app shows "No osmo-bridge firmware answered on USB".

## Using it with the app

1. Plug the board into the phone. Android offers to open Osmo Remote: allow it
   (tick "always" to skip the question next time).
2. On **Connect**, choose **ESP32 on USB**. Cameras appear as usual.
3. Pair once on the camera. The camera treats the ESP32 as a separate remote,
   so it asks again even if the phone was paired before.
4. Sleep and Wake work from the Remote and Manage screens and in workflows.

Waking needs camera firmware **01.04.08.10 or later** (Settings > About on the
camera). On 01.02.03.40 nothing wakes the camera.

## Serial protocol

115200 baud, one command per line, binary as lowercase hex. The ESP32 handles
one command at a time and answers in order.

| Host sends | ESP32 answers |
| --- | --- |
| `HELLO` | `HELLO osmo-bridge <ver> <own-address>` |
| `SCAN <seconds>` | `SCAN_RESULT <addr> <type> <rssi> <mfr-hex\|-> <name>` per camera, then `SCAN_DONE` |
| `CONNECT <addr> [type]` | `LINK_UP <addr> <mtu>` or `ERR connect ...` |
| `DISCONNECT` | `LINK_DOWN <reason>` |
| `TX <hex>` | `OK TX` or `ERR ...` (written to `fff5`) |
| `WAKE <camera-addr-hex> [ms] [public\|random]` | `LOG wake advertising ...`, then `WAKE_DONE` |
| `ADDR public\|random` | `OK ADDR ...` (address used for scanning and connecting) |

Unprompted: `RX <hex>` for every camera notification (`fff4`), `LINK_DOWN`
when the camera drops the link, `LOG` / `ERR` diagnostics, `READY` at boot.

`tools/mac-probe/bridge.py` drives the same protocol from a Mac for testing.
