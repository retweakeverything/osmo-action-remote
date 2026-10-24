# DJI Osmo Action BLE protocol notes

Everything here was verified on an Osmo Action 5 Pro (device id `0xFF44`)
unless marked otherwise. Frame layouts and CRC parameters come from DJI's
[Osmo-GPS-Controller-Demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo);
behaviour notes come from testing with `tools/mac-probe` and the Android app.

## Two protocols on one link

| Protocol | First byte | Used for |
| --- | --- | --- |
| DJI R SDK | `0xAA` | Control and status: pairing, record, mode, keys, status push |
| DUML | `0x55` | Unsolicited battery/power telemetry (`0x0D/0x02`), not needed by the app |

Sending control commands as DUML does nothing; the camera only acts on R SDK
frames. Both arrive interleaved on the same notify characteristic.

## GATT

Service `fff0`. Enable notifications on `fff4` (camera to phone). Write to
`fff5` with write-without-response (phone to camera). With a small MTU a frame
can be split across writes and notifications; the camera reassembles writes,
and the app reassembles notifications.

## R SDK frame

Little-endian throughout.

```
[0xAA][Ver/Len:2][CmdType:1][Enc:1][Res:3][Seq:2][CRC16:2][CmdSet:1][CmdId:1][payload][CRC32:4]
```

| Field | Notes |
| --- | --- |
| Ver/Len | bits 9..0 = total frame length, bits 15..10 = version (0) |
| CmdType | bits 4..0 = response wanted (0 none, 1 optional, 2 required); bit 5 = this is a response |
| Seq | chosen by the sender; a response echoes it |
| CRC16 | over bytes 0..9. ARC table (reflected poly `0xA001`), init `0x3AA3`, no final xor |
| CRC32 | over everything before it. Reflected poly `0xEDB88320`, init `0x00003AA3`, no final xor |

Both CRCs are checked against DJI's golden frames in the unit tests.

## Pairing handshake

```
phone  -> Connection Request 0x00/0x19   verify_mode 1 = first pairing (camera asks the user)
                                         verify_mode 0 = already paired (no prompt)
camera -> response, ret 0, payload starts with device id (44 FF = Action 5 Pro)
camera -> its own Connection Request with verify_mode 2 and result 0 (allow) or 1 (reject)
phone  -> Connection Response echoing that request's seq
          payload: device id (4) + ret 0 (1) + camera number 0 (4)
phone  -> Status Subscribe 0x1D/0x05     push_mode 3 (periodic + on change), push_freq 20 (2 Hz)
camera -> Status Push 0x1D/0x02 about twice a second from now on
```

Without the phone's Connection Response the camera resends its request
continuously and the session never settles.

Connection Request payload (33 bytes): sender device id (4), MAC length (1),
MAC (16), firmware version 0 (4), reserved (1), verify_mode (1), verify code (2),
reserved (4).

## Commands

| Action | Frame | Payload | Notes |
| --- | --- | --- | --- |
| Start recording | `0x1D/0x03` | device id (4) + `00` + 4 reserved | |
| Stop recording | `0x1D/0x03` | device id (4) + `01` + 4 reserved | |
| Take photo | `0x1D/0x03` start, in Photo mode | as start recording | |
| Switch mode | `0x1D/0x04` | device id (4) + mode (1) + 4 reserved | mode values below |
| Highlight | `0x00/0x11` | key `02`, mode `01`, value `0000` | only while recording; never acknowledged |
| Snapshot key | `0x00/0x11` | key `03`, mode `01`, value `0000` | |
| Record key | `0x00/0x11` | key `01`, mode `01`, value `0000` | toggles; the app uses `0x1D/0x03` instead |
| Sleep | `0x00/0x1A` | `03` | see below |

Command responses start with a return code (0 = success). Record and photo
commands are ignored while the camera is in playback.

## Status push `0x1D/0x02`

| Offset | Field | Values |
| --- | --- | --- |
| 0 | camera mode | `00` Slow Motion, `01` Video, `02` Timelapse, `05` Photo, `0A` Hyperlapse, `28` SuperNight |
| 1 | camera status | `00` screen off, `01` idle, `02` playback, `03` recording or taking a photo, `05` pre-recording |
| 2 | resolution | e.g. 95 = 2.7K 4:3; photo sizes 3/4 in Photo mode |
| 3 | fps index | e.g. 2 = 25 fps; burst count in Photo mode |
| 5 | record time (u16, s) | counts up while recording |
| 15 | free SD (u32, MB) | |
| 19 | photos left (u32) | |
| 23 | record time left (u32, s) | |
| 28 | power mode | `00` normal, `03` asleep |
| 37 | battery % | |

DJI's [`protocol_data_segment.md`](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo/blob/main/docs/protocol_data_segment.md)
has the full field list, including `0x1D/0x06` (mode name and parameters as text).

## Sleep and wake

**Sleep works.** After `0x00/0x1A` with `03` the camera sleeps. The BLE link
can stay up for a while and status pushes report power mode `03`, but the
camera answers no commands, including power mode `00`. Later it stops
advertising completely.

### Waking a sleeping camera is not possible from Android

DJI wakes a camera with a 2-3 s advertisement holding only
`0A FF 57 4B 50 <id reversed>` ("WKP"), where id is the 6 bytes the camera
advertises after the `FA` marker in its manufacturer data (company `0x08AA`).
On the Action 5 Pro that id is the BLE address, so a camera at
`AA:BB:CC:DD:EE:FF` needs `0A FF 57 4B 50 FF EE DD CC BB AA`.

Tested on a Galaxy S24 Ultra (Android 16) with the camera confirmed asleep:

| Attempt | Result |
| --- | --- |
| Non-connectable advertisement with the payload above | ignored |
| Connectable advertisement (`ADV_IND`), 3 s | ignored |
| Advertising set without the Flags field, byte-identical to DJI's packet | ignored |
| With the GATT link up, then again after disconnecting | ignored |

A Mac sniffer (`tools/mac-probe/sniff_wake.py`) confirmed the exact bytes went
out. Remotes that do wake the camera (DJI's GPS remote, ESP32 firmware, Avinox
firmware) advertise from a fixed address, and Avinox switches to its public
address just for the wake packet. Android advertises from a rotating random
address; picking the address type (`setOwnAddressType`) is a hidden system API
that needs `BLUETOOTH_PRIVILEGED`, there is no developer setting for it, and the
privacy property cannot be changed without root.

Others report the same on Android, iOS, Wear OS and Flutter in
[DJI issue #24](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo/issues/24),
still unanswered by DJI as of October 2026.

## Sources

- [dji-sdk/Osmo-GPS-Controller-Demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo): R SDK frame, CRCs, command payloads, golden test frames
- [rhoenschrat/DJI-Remote](https://github.com/rhoenschrat/DJI-Remote): highlight via the QS key, connectable wake advertising, Action 5 Pro behaviour
- [dstrat28/action-multicam-remote](https://github.com/dstrat28/action-multicam-remote): independent Action 5 Pro client and its list of BLE limits
- [yigitkonur/lib-osmo-ble](https://github.com/yigitkonur/lib-osmo-ble), [xaionaro-go/djictl](https://github.com/xaionaro-go/djictl), [datagutt/node-osmo](https://github.com/datagutt/node-osmo): DUML framing and Pocket 3 research
