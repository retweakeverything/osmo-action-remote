# mac-probe

Python tool for talking to a DJI Osmo Action camera over Bluetooth from a Mac.
It was used to reverse-engineer and verify the protocol before the Android app
existed, and is handy for checking a new camera model or firmware.

Uses macOS CoreBluetooth through [Bleak](https://github.com/hbldh/bleak).

## Setup

```bash
cd tools/mac-probe
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

The first scan triggers a macOS Bluetooth permission prompt for your terminal
app. If scans find nothing, check System Settings > Privacy & Security >
Bluetooth.

Only one device can be connected to the camera at a time, so close the phone
app (or disconnect it) first.

## Commands

```bash
# List nearby BLE devices with signal strength
.venv/bin/python3 probe.py scan

# Pair and stream live status (battery, mode, SD, recording state)
.venv/bin/python3 probe.py connect --name Osmo --seconds 30

# First time on a camera: omit --paired and confirm the prompt on the camera.
# Afterwards add --paired so no prompt appears.
.venv/bin/python3 probe.py connect --name Osmo --paired --record        # 4 s recording
.venv/bin/python3 probe.py connect --name Osmo --paired --photo         # switch to Photo, take one
.venv/bin/python3 probe.py connect --name Osmo --paired --mode 0x01     # switch to Video
.venv/bin/python3 probe.py connect --name Osmo --paired --highlight     # record, tag, stop
.venv/bin/python3 probe.py connect --name Osmo --paired --key 0x01      # raw key report

# Watch raw frames without pairing (only DUML battery telemetry arrives)
.venv/bin/python3 probe.py listen --name Osmo --seconds 20 --changes-only

# Print any DJI wake advertisements (WKP) seen nearby
.venv/bin/python3 sniff_wake.py --seconds 90
```

`--name` matches part of the advertised name, for example `Osmo`.

## Tests

```bash
.venv/bin/python3 test_rsdk.py    # R SDK codec vs DJI's golden frames
.venv/bin/python3 test_duml.py    # DUML codec vs frames captured from a camera
```

## Files

| File | Purpose |
| --- | --- |
| `probe.py` | CLI: `scan`, `listen`, `connect` |
| `rsdk.py` | R SDK frame codec and CRCs (the control protocol) |
| `rsdk_cmds.py` | Command payloads and the status push parser |
| `duml.py`, `telemetry.py` | DUML codec and battery telemetry decoder |
| `ble_client.py` | Bleak GATT client for service `fff0` |
| `sniff_wake.py` | Listens for wake advertisements |

Protocol details are in [`docs/PROTOCOL.md`](../../docs/PROTOCOL.md).

## Safety

Commands only run when you ask for them. `--key` and `--mode` send exactly what
you pass, so stick to the values listed in the protocol notes.
