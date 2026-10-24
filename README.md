# OsmoActionRemote

A free, open-source **Android** remote for the **DJI Osmo Action 5 Pro**. It
talks to the camera directly over Bluetooth Low Energy using DJI's R SDK
protocol. No DJI Mimo, no account, no cloud.

<p>
  <img src="docs/screenshots/remote.jpg" alt="Remote: battery, mode, SD space, photos left and the shutter button" width="230">
  <img src="docs/screenshots/workflows.jpg" alt="Workflows: built-in templates and saved workflows" width="230">
  <img src="docs/screenshots/builder.jpg" alt="Workflow builder with a Repeat block and its contents" width="230">
</p>

## Features

- **Pairing**: confirm once on the camera, then it reconnects silently.
  Auto-reconnects if the link drops.
- **Live status** at 2 Hz: battery, mode, SD free space, recording time left,
  photos left, and a REC timer while recording.
- **Remote**: record / stop, take photo (in Photo mode), highlight tags while
  recording, and direct mode selection (Video, Photo, Slow Motion, Timelapse,
  Hyperlapse, SuperNight).
- **Workflows**: build capture sequences from blocks (set mode, take photo,
  record for N seconds, start/stop recording, highlight, wait, repeat N times
  or until stopped, sleep). Ships with templates such as interval clips and
  "photo + 2 s clip". Pause, resume, and stop at any time; stopping ends any
  recording the workflow started.
- **Runs in the background** with a notification, so workflows keep going
  with the screen off.
- **Share workflows** as JSON and import them from a file or by pasting.
- **Connection log** in the app for troubleshooting.

## Screenshots

| Connect | Pairing (phone) | Pairing (camera) |
| --- | --- | --- |
| <img src="docs/screenshots/connect.jpg" alt="Scanning and the list of nearby cameras" width="230"> | <img src="docs/screenshots/pairing.jpg" alt="Phone waiting for confirmation, showing the code" width="230"> | <img src="docs/screenshots/camera-prompt.jpg" alt="Camera showing the same code with Accept and Reject" width="230"> |

| Mode picker | Running workflow | Camera page |
| --- | --- | --- |
| <img src="docs/screenshots/mode.jpg" alt="Mode picker sheet" width="230"> | <img src="docs/screenshots/running.jpg" alt="Workflow running with pause and stop" width="230"> | <img src="docs/screenshots/camera.jpg" alt="Camera page with reconnect, keep-alive, sleep, disconnect and forget" width="230"> |

The Remote also warns when the camera is in playback, where it ignores
commands ([screenshot](docs/screenshots/playback.jpg)).

## Known limitations

- **The app cannot wake a sleeping camera.** Sleep works, but the camera only
  accepts the wake broadcast from a remote that uses a fixed (public)
  Bluetooth address. Android reserves that for system apps, so a normal app
  cannot do it. We confirmed the exact wake packet over the air and the camera
  still ignores it; others report the same in
  [DJI issue #24](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo/issues/24).
  Wake the camera with its power button. Details in
  [`docs/PROTOCOL.md`](docs/PROTOCOL.md#waking-a-sleeping-camera-not-possible-from-android).
- **No live preview or media download.** Those use the camera's Wi-Fi.
- **Screen brightness, screen-off and auto power-off** are not in DJI's
  Bluetooth protocol. Set them on the camera. For long workflows, set auto
  power-off to the longest option, because the app cannot turn the camera back on.
- **Commands are ignored in playback.** Leave playback on the camera first.
- Only tested with the Osmo Action 5 Pro. Action 4, Action 6, and Osmo 360 use
  the same protocol but are untested.

## Install

1. Download the latest `.apk` from the [Releases](../../releases/latest) page
   on your phone.
2. Open it. If Android asks, allow your browser or file manager to install
   unknown apps.

Requires Android 12 or newer with Bluetooth LE. The app asks for Bluetooth
(nearby devices) and notification permissions, and never uses location.

To build it yourself, see [`android/README.md`](android/README.md).

## Using it

1. Turn on the camera and open the app. It scans automatically.
2. Tap your camera. The first time, confirm the pairing prompt on the camera.
3. The **Remote** tab opens. The big button records, or takes a photo in Photo
   mode. Use **Mode** to switch modes and **Highlight** while recording.
4. **Workflows**: run a template, or build your own. A Repeat block shows its
   contents indented underneath; use **Add block inside repeat** to fill it.
   Blocks run exactly as ordered, so set the right mode before capturing.
5. Tap the bar at the top to manage the camera: disconnect, forget, sleep,
   background keep-alive, and the connection log.

## Project layout

| Path | What it is |
| --- | --- |
| [`android/`](android/README.md) | The app: `:protocol` (codec), `:core` (session, reconnect, workflow engine), `:app` (Bluetooth + UI) |
| [`tools/mac-probe/`](tools/mac-probe/README.md) | Python/Bleak tool used to reverse-engineer and verify the protocol from a Mac |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | Verified protocol notes: frame format, CRCs, handshake, commands, status fields |

## Roadmap

- GPS injection from the phone into recordings
- QR code sharing for workflows
- Show the camera's mode details (for example "4K 60fps")
- Test Action 4, Action 6, and Osmo 360
- Multi-camera control

## Credits

Built on public reverse-engineering work, especially DJI's own
[Osmo-GPS-Controller-Demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo)
(R SDK protocol and CRC parameters),
[rhoenschrat/DJI-Remote](https://github.com/rhoenschrat/DJI-Remote), and
[dstrat28/action-multicam-remote](https://github.com/dstrat28/action-multicam-remote).
Full list in [`docs/PROTOCOL.md`](docs/PROTOCOL.md#sources).

## Disclaimer

Independent project, **not affiliated with, endorsed by, or sponsored by
DJI**. "DJI", "Osmo", and "Osmo Action" are trademarks of their owners, used
only to describe compatibility. Use at your own risk; see [`NOTICE.md`](NOTICE.md).

## License

[MIT](LICENSE).
