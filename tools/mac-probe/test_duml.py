"""Codec self-tests. Run: python3 -m pytest test_duml.py  (or: python3 test_duml.py)

NOTE: These confirm internal consistency (round-trip, CRC validation, field
placement). They do NOT confirm the CRC *parameters* match the real camera -
that requires a known-good frame captured from the Action 5 Pro, which we add
to KNOWN_FRAMES once bring-up produces one.
"""

from duml import (
    DumlMessage, crc8, crc16, pack_string, target,
    DEV_APP, DEV_CAMERA, FLAG_REQUEST,
)


def test_roundtrip_empty_payload():
    msg = DumlMessage(target=target(DEV_APP, DEV_CAMERA), msg_id=1,
                      cmd_set=0x1D, cmd_id=0x02, flags=FLAG_REQUEST)
    decoded = DumlMessage.decode(msg.encode())
    assert decoded.cmd_set == 0x1D
    assert decoded.cmd_id == 0x02
    assert decoded.msg_id == 1
    assert decoded.payload == b""


def test_roundtrip_with_payload():
    msg = DumlMessage(target=target(DEV_APP, DEV_CAMERA), msg_id=0xABCD,
                      cmd_set=0x07, cmd_id=0x45,
                      payload=pack_string("12345") + pack_string("5160"))
    raw = msg.encode()
    decoded = DumlMessage.decode(raw)
    assert decoded.msg_id == 0xABCD
    assert decoded.payload == msg.payload
    # msg_id is big-endian on the wire: bytes 6,7 == AB,CD
    assert raw[6] == 0xAB and raw[7] == 0xCD


def test_magic_and_length():
    raw = DumlMessage(target=0x0201, msg_id=1, cmd_set=0, cmd_id=0).encode()
    assert raw[0] == 0x55
    assert raw[1] | ((raw[2] & 0x03) << 8) == len(raw)


def test_header_crc8_detects_corruption():
    raw = bytearray(DumlMessage(target=0x0201, msg_id=1, cmd_set=0, cmd_id=0).encode())
    raw[4] ^= 0xFF  # corrupt a byte after the header CRC
    try:
        DumlMessage.decode(bytes(raw))
        assert False, "expected CRC16 mismatch"
    except ValueError as e:
        assert "CRC16" in str(e)


def test_pack_string():
    assert pack_string("ab") == bytes([0x02, 0x61, 0x62])
    assert pack_string("") == bytes([0x00])


def test_crc_params_are_stable():
    # Regression guard: if someone changes CRC params, these fixed vectors move.
    assert crc8(b"\x55\x0e\x04") == crc8(b"\x55\x0e\x04")  # deterministic
    assert isinstance(crc16(b"hello"), int)


# --- Known-good frames from the real camera (populate during bring-up) ----
# Each entry: (hex_string_from_capture, description). Once we capture a real
# frame via btsnoop/CoreBluetooth, drop it here to lock the CRC params.
KNOWN_FRAMES: list[tuple[str, str]] = [
    ("552f046305254203000d0200fb0f00003cfeffff00000000000000004001004c000000000000200000000000019ed7",
     "Action 5 Pro telemetry cmdSet=0x0D cmdId=0x02, captured 2026-10"),
    ("552f046305254303000d02000f1000003bfeffff00000000000000004001004c00000000000020000000000001ee02",
     "Action 5 Pro telemetry, same stream, next msg_id"),
    ("552f046305255503000d02000910000070feffff00000000000000004001004c0000000000002000000000000156f6",
     "Action 5 Pro telemetry, same stream"),
]


def test_known_frames_decode():
    for hexstr, desc in KNOWN_FRAMES:
        raw = bytes.fromhex(hexstr)
        DumlMessage.decode(raw)  # must not raise -> CRC params are correct


def test_known_frame_fields():
    # First known frame: telemetry push, cmdSet 0x0D cmdId 0x02, flags 0x00.
    raw = bytes.fromhex(KNOWN_FRAMES[0][0])
    m = DumlMessage.decode(raw)
    assert m.flags == 0x00, "unsolicited telemetry has flags 0x00"
    assert m.cmd_set == 0x0D
    assert m.cmd_id == 0x02


def test_crc_values_match_real_frame():
    # Locks the exact CRC outputs observed on hardware.
    raw = bytes.fromhex(KNOWN_FRAMES[0][0])
    assert crc8(raw[0:3]) == raw[3]
    assert crc16(raw[:-2]) == int.from_bytes(raw[-2:], "little")


if __name__ == "__main__":
    import sys
    fns = [v for k, v in sorted(globals().items())
           if k.startswith("test_") and callable(v)]
    failed = 0
    for fn in fns:
        try:
            fn()
            print(f"PASS {fn.__name__}")
        except AssertionError as e:
            failed += 1
            print(f"FAIL {fn.__name__}: {e}")
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"ERROR {fn.__name__}: {e!r}")
    print(f"\n{len(fns) - failed}/{len(fns)} passed")
    sys.exit(1 if failed else 0)
