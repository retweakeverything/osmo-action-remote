"""R SDK codec tests, verified against DJI demo golden connection frames.

Run: python3 test_rsdk.py

These golden frames come from DJI's Osmo-GPS-Controller-Demo
(test/connect_cmd_frame_builder/connect_cmd_frame.txt) - real Connection
Request (0x00/0x19) frames. If our encoder reproduces their exact bytes and
our decoder validates their CRCs, the R SDK codec is correct.
"""

from rsdk import RsdkFrame, crc16, crc32

# DJI golden Connection Request frames (SEQ and CRC32 differ per frame).
GOLDEN = [
    "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000FE1F00000000796C667C",
    "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000191A0000000033C8C7BF",
    "AA330002000000000100982F00197856341206383456789ABC000000000000000000000000000000008F1200000000AC768844",
    "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000050B00000000E1B45396",
]


def test_golden_crc16_matches():
    for h in GOLDEN:
        b = bytes.fromhex(h)
        assert crc16(b[0:10]) == int.from_bytes(b[10:12], "little")


def test_golden_crc32_matches():
    for h in GOLDEN:
        b = bytes.fromhex(h)
        assert crc32(b[:-4]) == int.from_bytes(b[-4:], "little")


def test_golden_frames_decode():
    for h in GOLDEN:
        f = RsdkFrame.decode(bytes.fromhex(h))
        assert (f.cmd_set, f.cmd_id) == (0x00, 0x19)  # Connection Request


def test_encoder_reproduces_golden():
    # Reconstruct frame 0 from its parts and confirm byte-identical output.
    b = bytes.fromhex(GOLDEN[0])
    f = RsdkFrame.decode(b)
    re = RsdkFrame(cmd_set=f.cmd_set, cmd_id=f.cmd_id, seq=f.seq,
                   cmd_type=f.cmd_type, enc=f.enc, payload=f.payload).encode()
    assert re == b, f"\n got {re.hex()}\nwant {b.hex()}"


def test_corruption_detected():
    b = bytearray(bytes.fromhex(GOLDEN[0]))
    b[14] ^= 0xFF  # corrupt payload
    try:
        RsdkFrame.decode(bytes(b))
        assert False, "expected CRC32 mismatch"
    except ValueError as e:
        assert "CRC32" in str(e)


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
