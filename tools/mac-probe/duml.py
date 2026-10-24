"""DUML-over-BLE frame codec for DJI Osmo cameras.

Pure logic, no BLE dependency, so it can be unit-tested off-device and later
ported to Kotlin verbatim. Frame layout and CRC parameters are condensed from
public reverse-engineering sources (see docs/PROTOCOL.md); this implementation
was written from those described parameters, not copied from any source.

Frame wire format:
    0x55 | len_lo | (ver<<2 | len_hi) | crc8 | target(2, LE) |
    msg_id(2, BE) | flags | cmd_set | cmd_id | payload(N) | crc16(2, LE)
"""

from __future__ import annotations

from dataclasses import dataclass, field


# CRC parameters below are VERIFIED against real DJI Osmo Action 5 Pro frames
# captured 2026 (telemetry cmdSet 0x0D cmdId 0x02). Do not change without
# re-verifying against KNOWN_FRAMES in test_duml.py.


def _reflect(x: int, n: int) -> int:
    r = 0
    for i in range(n):
        if x & (1 << i):
            r |= 1 << (n - 1 - i)
    return r


# --- CRC8: poly 0x31, init 0xEE, reflected in/out -------------------------

def crc8(data: bytes, init: int = 0xEE) -> int:
    """Reflected CRC8, poly 0x31, init 0xEE. Verified against real frames."""
    crc = init
    for b in data:
        crc ^= _reflect(b, 8)
        for _ in range(8):
            crc = ((crc << 1) ^ 0x31) & 0xFF if (crc & 0x80) else (crc << 1) & 0xFF
    return _reflect(crc, 8) & 0xFF


# --- CRC16: poly 0x1021, init 0x496C, reflected in/out --------------------

def crc16(data: bytes, init: int = 0x496C) -> int:
    """Reflected CRC16, poly 0x1021, init 0x496C. Verified against real frames."""
    crc = init
    for b in data:
        crc ^= _reflect(b, 8) << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if (crc & 0x8000) else (crc << 1) & 0xFFFF
    return _reflect(crc, 16) & 0xFFFF


# --- PackString -----------------------------------------------------------

def pack_string(s: str) -> bytes:
    """DJI PackString: 1-byte length prefix + UTF-8 bytes."""
    raw = s.encode("utf-8")
    if len(raw) > 0xFF:
        raise ValueError("string too long for 1-byte length prefix")
    return bytes([len(raw)]) + raw


# --- Message --------------------------------------------------------------

# Flag values in the type field's first byte.
FLAG_REQUEST = 0x40      # command from app -> device
FLAG_RESPONSE = 0xC0     # response from device
FLAG_TELEMETRY = 0x00    # unsolicited push from device


@dataclass
class DumlMessage:
    target: int          # 2-byte target (sender/receiver), little-endian on wire
    msg_id: int          # 2-byte sequence counter, big-endian on wire
    cmd_set: int         # command set id
    cmd_id: int          # command id
    flags: int = FLAG_REQUEST
    payload: bytes = field(default_factory=bytes)

    def encode(self) -> bytes:
        body = bytearray()
        body.append(0x55)                                  # magic
        # length is total frame length including magic and both CRCs.
        total_len = 1 + 1 + 1 + 1 + 2 + 2 + 3 + len(self.payload) + 2
        if total_len > 0x3FF:
            raise ValueError("frame exceeds 10-bit length field")
        ver = 1
        body.append(total_len & 0xFF)                      # len_lo
        body.append(((ver << 2) | (total_len >> 8)) & 0xFF)  # ver<<2 | len_hi
        body.append(crc8(bytes(body[0:3])))               # crc8 over header
        body += self.target.to_bytes(2, "little")
        body += self.msg_id.to_bytes(2, "big")
        body.append(self.flags & 0xFF)
        body.append(self.cmd_set & 0xFF)
        body.append(self.cmd_id & 0xFF)
        body += self.payload
        body += crc16(bytes(body)).to_bytes(2, "little")
        return bytes(body)

    @classmethod
    def decode(cls, data: bytes) -> "DumlMessage":
        if len(data) < 13 or data[0] != 0x55:
            raise ValueError("not a DUML frame")
        total_len = data[1] | ((data[2] & 0x03) << 8)
        if total_len != len(data):
            raise ValueError(f"length mismatch: header says {total_len}, got {len(data)}")
        if crc8(data[0:3]) != data[3]:
            raise ValueError("header CRC8 mismatch")
        expected_crc16 = crc16(data[:-2])
        actual_crc16 = int.from_bytes(data[-2:], "little")
        if expected_crc16 != actual_crc16:
            raise ValueError("frame CRC16 mismatch")
        target = int.from_bytes(data[4:6], "little")
        msg_id = int.from_bytes(data[6:8], "big")
        flags, cmd_set, cmd_id = data[8], data[9], data[10]
        payload = data[11:-2]
        return cls(target=target, msg_id=msg_id, cmd_set=cmd_set,
                   cmd_id=cmd_id, flags=flags, payload=bytes(payload))

    def __str__(self) -> str:
        return (f"DUML(target=0x{self.target:04x} id={self.msg_id} "
                f"flags=0x{self.flags:02x} set=0x{self.cmd_set:02x} "
                f"id=0x{self.cmd_id:02x} payload={self.payload.hex()})")


# Subsystem ids for building the target field (sender/receiver).
DEV_CAMERA = 0x01
DEV_APP = 0x02
DEV_WIFI = 0x07
DEV_STREAM = 0x08


def target(sender: int, receiver: int) -> int:
    """Build a 2-byte target value. Low byte = receiver, high byte = sender.

    Encoded little-endian on the wire, so receiver appears first in bytes.
    """
    return ((sender & 0x1F) << 8) | (receiver & 0x1F)
