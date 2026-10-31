# DJI Osmo Action BLE protocol notes

Everything here was verified on an Osmo Action 5 Pro (device id `0xFF44`)
unless marked otherwise. Camera firmware matters for wake; see below. Frame layouts and CRC parameters come from DJI's
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

**Sleep works on every route.** After `0x00/0x1A` with `03` the camera sleeps
and answers `ret 0`. The BLE link stays up, status pushes report power mode
`03` (then stop), and the camera answers no further commands, including power
mode `00`.

### Waking: the wake advertisement

The camera wakes when it hears, for about 2-3 s, an advertisement holding only
`0A FF 57 4B 50 <camera address reversed>` ("WKP"). For a camera at
`AA:BB:CC:DD:EE:FF` that is `0A FF 57 4B 50 FF EE DD CC BB AA`, sent as a
connectable undirected advert (`ADV_IND`). The camera answers on the existing
link with a status push showing power mode `00`.

Verified on an Action 5 Pro with the ESP32 relay (`firmware/osmo-bridge`):

| Camera firmware | Advertiser address | Result |
| --- | --- | --- |
| 01.02.03.40 | public (ESP32-C3, original ESP32, DJI's demo, the M5Stick remote's code) | never wakes |
| 01.06.01.04 | public, ESP32-C3 and original ESP32 | **wakes on the first advert**, link kept or dropped |
| 01.06.01.04 | random static, same ESP32-C3 | never wakes |

So two things are needed:

1. **Camera firmware 01.04.08.10 or later.** That release added wake-up for
   DJI's Avinox system. On older firmware no third-party remote we tried could
   wake it.
2. **A public advertiser address.** Same board, same pairing, same packet: only
   the address type decided the outcome.

What did not matter in our tests: the identity sent in the Connection Request
(DJI's sample MAC with a random device id, or an empty identity as the app
sends) and whether the GATT link was still up.

### Why a phone cannot do it alone

Android advertises from a random address. Choosing the address type
(`setOwnAddressType`) is a hidden system API that needs
`BLUETOOTH_PRIVILEGED`, so normal apps cannot pick the public address. The app
still sends the wake advert on the phone route, in case some phone's stack
advertises from its public address, and reports "camera did not wake up"
otherwise. On the ESP32 route the relay sends the same payload from its public
address.

Phone attempts on firmware 01.02.03.40 (all ignored; that firmware ignored the
ESP32 too): non-connectable advert, connectable advert, an advertising set
byte-identical to DJI's packet, with and without the GATT link.
`tools/mac-probe/sniff_wake.py` confirmed the bytes on air. The phone route has
not been re-tested on 01.06.01.04; the random-address result above predicts it
will not wake the camera.

Related discussion: [DJI issue #24](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo/issues/24).

### Not a wake method

- `0x00/0x1A` with `00` over the link while asleep: no reply.
- DUML commands over the link (the camera accepts a DJI Mimo style DUML
  session, but the Osmo Nano's wake command `0x53/0x10` does nothing on the
  Action 5 Pro).

## Sources

- [dji-sdk/Osmo-GPS-Controller-Demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo): R SDK frame, CRCs, command payloads, golden test frames
- [rhoenschrat/DJI-Remote](https://github.com/rhoenschrat/DJI-Remote): highlight via the QS key, connectable wake advertising, Action 5 Pro behaviour
- [theserialhobbyist/M5StickCPlus2_Remote_For_DJI_Osmo](https://github.com/theserialhobbyist/M5StickCPlus2_Remote_For_DJI_Osmo): ESP32 remote with sleep and wake for the Action 5 Pro
- [DJI Osmo Action 5 Pro release notes](https://www.dji.com/downloads/products/osmo-action-5-pro): firmware 01.04.08.10 adds wake-up for Avinox
- [dstrat28/action-multicam-remote](https://github.com/dstrat28/action-multicam-remote): independent Action 5 Pro client and its list of BLE limits
- [yigitkonur/lib-osmo-ble](https://github.com/yigitkonur/lib-osmo-ble), [xaionaro-go/djictl](https://github.com/xaionaro-go/djictl), [datagutt/node-osmo](https://github.com/datagutt/node-osmo): DUML framing and Pocket 3 research
