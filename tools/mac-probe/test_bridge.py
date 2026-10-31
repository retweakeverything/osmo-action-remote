"""Offline tests for bridge.py: no ESP32 or camera needed. Run: python3 test_bridge.py"""

import queue

import bridge
import rsdk_cmds as C
from rsdk import RsdkFrame, FRAME_RESP, RESP_NONE, RESP_REQUIRED

DUML = bytes.fromhex(
    "552f046305254203000d0200fb0f00003cfeffff00000000000000004001004c000000000000200000000000019ed7")


def status_frame(power_mode=0, battery=63):
    p = bytearray(38)
    p[0], p[1], p[28], p[37] = 0x01, 0x01, power_mode, battery
    return RsdkFrame(0x1D, 0x02, seq=0, cmd_type=RESP_NONE, payload=bytes(p)).encode()


def test_assembler_rejoins_chunks_and_skips_duml():
    a = bridge.FrameAssembler()
    stream = DUML + status_frame() + DUML + status_frame(3)
    frames = []
    for i in range(0, len(stream), 20):
        frames += a.feed(stream[i:i + 20])
    assert [C.CameraStatus.parse(f.payload).power_mode for f in frames] == [0, 3]


def test_assembler_resyncs_after_garbage():
    a = bridge.FrameAssembler()
    frames = a.feed(bytes([0x01, 0xAA, 0x05, 0x00, 0x7F]) + status_frame())
    assert len(frames) == 1


class FakeBridge:
    """Records TX frames instead of writing to serial."""
    def __init__(self):
        self.frames = queue.Queue()
        self.sent = []
        self.status = None

    def tx(self, frame):
        self.sent.append(frame)


def test_session_acks_camera_allow_with_its_seq():
    br = FakeBridge()
    s = bridge.Session(br)
    payload = bytes.fromhex("44ff00000000000000000000000000000000000000000000000002000000000000")
    s._side(RsdkFrame(0x00, 0x19, seq=7053, cmd_type=RESP_REQUIRED, payload=payload))
    assert s.verify == "ALLOW"
    ack = br.sent[-1]
    assert (ack.cmd_set, ack.cmd_id, ack.seq) == (0x00, 0x19, 7053) and ack.cmd_type & FRAME_RESP


def test_wake_id_parsed_from_scan_line():
    class LineBridge(bridge.Bridge):
        def __init__(self, lines):
            self.lines = queue.Queue()
            for l in lines:
                self.lines.put(l)
        def send(self, line):
            pass
    br = LineBridge([
        "SCAN_RESULT 11:22:33:44:55:66 0 -40 aa081500fa11223344556600 OsmoAction5Pro0000",
        "SCAN_DONE",
    ])
    cams = br.scan(1)
    assert cams[0].wake_id == "112233445566" and cams[0].addr_type == 0


if __name__ == "__main__":
    import sys
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    failed = 0
    for t in tests:
        try:
            t(); print(f"PASS {t.__name__}")
        except Exception as e:
            failed += 1; print(f"FAIL {t.__name__}: {e!r}")
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)
