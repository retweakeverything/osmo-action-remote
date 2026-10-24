"""DJI R SDK frame codec (the 0xAA control protocol) for DJI Osmo cameras.

This is the CONTROL channel on the Action 5 Pro - record, mode, pairing
(Connection Request), status subscription, GPS. Distinct from DUML (0x55),
which is the telemetry channel (see duml.py).

Frame format (little-endian throughout):
    [SOF=0xAA] [Ver/Length:2] [CmdType:1] [ENC:1] [RES:3] [SEQ:2]
    [CRC16:2] [CmdSet:1] [CmdID:1] [payload:n] [CRC32:4]

  Ver/Length: bits [15:10]=version (0), [9:0]=total frame length (LSB first)
  CRC16: over bytes SOF..SEQ (offsets 0..9)
  CRC32: over bytes SOF..end of DATA (everything except the trailing 4 bytes)

CRC parameters are taken from DJI's open-source demo headers
(utils/crc/custom_crc16.h, custom_crc32.h) and VERIFIED against the demo's
golden connection frames (see test_rsdk.py). This file implements the described
algorithm; it does not copy DJI's source.
"""

from __future__ import annotations

from dataclasses import dataclass, field


# --- CRC16: ARC table (reflected poly 0xA001), init 0x3AA3, no final xor -----

def _build_crc16_table() -> list[int]:
    t = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if (c & 1) else (c >> 1)
        t.append(c)
    return t


_T16 = _build_crc16_table()


def crc16(data: bytes, crc: int = 0x3AA3) -> int:
    for b in data:
        crc = (_T16[(crc ^ b) & 0xFF] ^ (crc >> 8)) & 0xFFFF
    return crc & 0xFFFF


# --- CRC32: reflected poly 0xEDB88320, init 0x00003AA3, no final xor ---------

def _build_crc32_table() -> list[int]:
    t = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ 0xEDB88320 if (c & 1) else (c >> 1)
        t.append(c)
    return t


_T32 = _build_crc32_table()


def crc32(data: bytes, crc: int = 0x00003AA3) -> int:
    for b in data:
        crc = (_T32[(crc ^ b) & 0xFF] ^ (crc >> 8)) & 0xFFFFFFFF
    return crc & 0xFFFFFFFF


# --- CmdType field helpers ---------------------------------------------------

# CmdType byte: [4:0]=response type, [5]=frame type (0=cmd,1=resp), [7:6]=reserved
RESP_NONE = 0x00       # no response expected
RESP_OPTIONAL = 0x01   # response acceptable but not required
RESP_REQUIRED = 0x02   # response mandatory
FRAME_CMD = 0x00
FRAME_RESP = 0x20      # bit 5 set


# --- Command (CmdSet, CmdID) constants (from DJI demo data_descriptors) ------

CMD_CONNECTION = (0x00, 0x19)       # connection request / pairing verify code
CMD_VERSION = (0x00, 0x00)
CMD_KEY_REPORT = (0x00, 0x11)
CMD_GPS_PUSH = (0x00, 0x17)
CMD_RECORD_CONTROL = (0x1D, 0x03)
CMD_MODE_SWITCH = (0x1D, 0x04)
CMD_STATUS_SUBSCRIBE = (0x1D, 0x05)
CMD_STATUS_PUSH = (0x1D, 0x02)      # camera -> app (after subscribe)


@dataclass
class RsdkFrame:
    cmd_set: int
    cmd_id: int
    seq: int = 0
    cmd_type: int = RESP_REQUIRED          # [5]=0 (command) by default
    enc: int = 0x00
    payload: bytes = field(default_factory=bytes)

    def encode(self) -> bytes:
        data = bytes([self.cmd_set & 0xFF, self.cmd_id & 0xFF]) + self.payload
        total_len = 1 + 2 + 1 + 1 + 3 + 2 + 2 + len(data) + 4
        if total_len > 0x3FF:
            raise ValueError("frame exceeds 10-bit length field")
        out = bytearray()
        out.append(0xAA)                                   # SOF
        out += (total_len & 0x3FF).to_bytes(2, "little")   # Ver(0)/Length
        out.append(self.cmd_type & 0xFF)                   # CmdType
        out.append(self.enc & 0xFF)                        # ENC
        out += bytes(3)                                    # RES
        out += (self.seq & 0xFFFF).to_bytes(2, "little")   # SEQ
        out += crc16(bytes(out)).to_bytes(2, "little")     # CRC16 over SOF..SEQ
        out += data                                        # DATA (set,id,payload)
        out += crc32(bytes(out)).to_bytes(4, "little")     # CRC32 over SOF..DATA
        return bytes(out)

    @classmethod
    def decode(cls, raw: bytes) -> "RsdkFrame":
        if len(raw) < 17 or raw[0] != 0xAA:
            raise ValueError("not an R SDK frame")
        total_len = int.from_bytes(raw[1:3], "little") & 0x3FF
        if total_len != len(raw):
            raise ValueError(f"length mismatch: header {total_len}, got {len(raw)}")
        if crc16(raw[0:10]) != int.from_bytes(raw[10:12], "little"):
            raise ValueError("CRC16 mismatch")
        if crc32(raw[:-4]) != int.from_bytes(raw[-4:], "little"):
            raise ValueError("CRC32 mismatch")
        cmd_type = raw[3]
        enc = raw[4]
        seq = int.from_bytes(raw[8:10], "little")
        cmd_set, cmd_id = raw[12], raw[13]
        payload = raw[14:-4]
        return cls(cmd_set=cmd_set, cmd_id=cmd_id, seq=seq,
                   cmd_type=cmd_type, enc=enc, payload=bytes(payload))

    def __str__(self) -> str:
        kind = "resp" if (self.cmd_type & FRAME_RESP) else "cmd"
        return (f"RSDK({kind} seq={self.seq} set=0x{self.cmd_set:02x} "
                f"id=0x{self.cmd_id:02x} payload={self.payload.hex()})")
