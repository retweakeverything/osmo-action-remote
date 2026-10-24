"""R SDK command payload builders and response parsers for the Action 5 Pro.

Payload layouts are from DJI's demo docs/protocol_data_segment.md. All
little-endian. device_id for Osmo Action 5 Pro is 0xFF44.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass

from rsdk import (
    RsdkFrame, CMD_CONNECTION, CMD_RECORD_CONTROL, CMD_MODE_SWITCH,
    CMD_STATUS_SUBSCRIBE, CMD_KEY_REPORT, RESP_REQUIRED, RESP_OPTIONAL,
    RESP_NONE, FRAME_RESP,
)

DEVICE_ID = {
    "action4": 0xFF33,
    "action5pro": 0xFF44,
    "action6": 0xFF55,
    "osmo360": 0xFF66,
}

# Camera mode names (camera_mode field in status push).
CAMERA_MODES = {
    0x00: "SlowMotion", 0x01: "Video", 0x02: "Timelapse", 0x05: "Photo",
    0x0A: "Hyperlapse", 0x1A: "LiveStream", 0x23: "UVCLive",
    0x28: "SuperNight", 0x34: "SubjectTracking",
}
CAMERA_STATUS = {
    0x00: "ScreenOff", 0x01: "LiveOrIdle", 0x02: "Playback",
    0x03: "PhotoOrRecording", 0x05: "PreRecording",
}


# --- Connection Request (0x00/0x19) ----------------------------------------

def build_connection_request(verify_mode: int, verify_data: int = 0,
                             our_device_id: int = 0,
                             mac: bytes = b"", seq: int = 0) -> RsdkFrame:
    """Connection Request command frame.

    verify_mode: 1 = first pairing (camera shows verify code popup),
                 0 = already paired (camera decides), 2 = result.
    verify_data: the verification code / data (uint16).
    """
    payload = bytearray()
    payload += struct.pack("<I", our_device_id)      # device_id (sender)
    mac = mac[:16]
    payload.append(len(mac))                          # mac_addr_len
    payload += mac + bytes(16 - len(mac))             # mac_addr[16]
    payload += struct.pack("<I", 0)                   # fw_version = 0
    payload.append(0)                                 # conidx (reserved)
    payload.append(verify_mode & 0xFF)                # verify_mode
    payload += struct.pack("<H", verify_data & 0xFFFF)  # verify_data
    payload += bytes(4)                               # reserved[4]
    return RsdkFrame(*CMD_CONNECTION, seq=seq,
                     cmd_type=RESP_REQUIRED, payload=bytes(payload))


def build_connection_response(device_id: int = 0xFF44, ret_code: int = 0,
                              camera_number: int = 0, seq: int = 0) -> RsdkFrame:
    """Reply the remote sends after the camera's ALLOW (spec step 4).

    Response frame: device_id(4) + ret_code(1) + reserved(4)=camera number.
    """
    payload = struct.pack("<I", device_id) + bytes([ret_code & 0xFF]) \
        + struct.pack("<I", camera_number)
    return RsdkFrame(*CMD_CONNECTION, seq=seq,
                     cmd_type=FRAME_RESP | RESP_NONE, payload=payload)


@dataclass
class ConnectionResponse:
    device_id: int
    ret_code: int
    reserved: int

    @classmethod
    def parse(cls, payload: bytes) -> "ConnectionResponse":
        device_id = struct.unpack_from("<I", payload, 0)[0]
        ret_code = payload[4] if len(payload) > 4 else 0xFF
        reserved = struct.unpack_from("<I", payload, 5)[0] if len(payload) >= 9 else 0
        return cls(device_id, ret_code, reserved)


# --- Record Control (0x1D/0x03) --------------------------------------------

def build_record_control(start: bool, device_id: int = 0xFF44,
                         seq: int = 0) -> RsdkFrame:
    payload = struct.pack("<I", device_id) + bytes([0 if start else 1]) + bytes(4)
    return RsdkFrame(*CMD_RECORD_CONTROL, seq=seq,
                     cmd_type=RESP_REQUIRED, payload=payload)


# --- Mode Switch (0x1D/0x04) -----------------------------------------------

def build_mode_switch(mode: int, device_id: int = 0xFF44,
                      seq: int = 0) -> RsdkFrame:
    payload = struct.pack("<I", device_id) + bytes([mode & 0xFF]) + bytes(4)
    return RsdkFrame(*CMD_MODE_SWITCH, seq=seq,
                     cmd_type=RESP_REQUIRED, payload=payload)


# --- Status Subscription (0x1D/0x05) ---------------------------------------

def build_status_subscribe(push_mode: int = 3, push_freq: int = 20,
                           seq: int = 0) -> RsdkFrame:
    """push_mode 3 = periodic + on-change; push_freq fixed 20 (=2Hz)."""
    payload = bytes([push_mode & 0xFF, push_freq & 0xFF]) + bytes(4)
    return RsdkFrame(*CMD_STATUS_SUBSCRIBE, seq=seq,
                     cmd_type=RESP_REQUIRED, payload=payload)


# --- Key Report (0x00/0x11) -- alternative trigger for record/QS -----------

def build_key_report(key_code: int, mode: int = 1, key_value: int = 0,
                     seq: int = 0) -> RsdkFrame:
    """key_code: 0x01=Record, 0x02=QS/Highlight-while-recording, 0x03=Snapshot.
    mode 1 = event, key_value 0 = short press. (rhoenschrat: highlight = QS
    key 0x02 sent while recording; snapshot = 0x03.)"""
    payload = bytes([key_code & 0xFF, mode & 0xFF]) + struct.pack("<H", key_value)
    return RsdkFrame(*CMD_KEY_REPORT, seq=seq,
                     cmd_type=RESP_OPTIONAL, payload=payload)


# --- Status Push (0x1D/0x02) parser ----------------------------------------

@dataclass
class CameraStatus:
    camera_mode: int
    camera_status: int
    video_resolution: int
    fps_idx: int
    record_time: int
    remain_capacity_mb: int
    remain_photo_num: int
    remain_time_s: int
    power_mode: int
    battery_pct: int

    @classmethod
    def parse(cls, p: bytes) -> "CameraStatus | None":
        if len(p) < 38:
            return None
        return cls(
            camera_mode=p[0],
            camera_status=p[1],
            video_resolution=p[2],
            fps_idx=p[3],
            record_time=struct.unpack_from("<H", p, 5)[0],
            remain_capacity_mb=struct.unpack_from("<I", p, 15)[0],
            remain_photo_num=struct.unpack_from("<I", p, 19)[0],
            remain_time_s=struct.unpack_from("<I", p, 23)[0],
            power_mode=p[28],
            battery_pct=p[37],
        )

    def __str__(self) -> str:
        mode = CAMERA_MODES.get(self.camera_mode, f"0x{self.camera_mode:02x}")
        stat = CAMERA_STATUS.get(self.camera_status, f"0x{self.camera_status:02x}")
        rec = "REC" if self.camera_status == 0x03 else "idle"
        return (f"[{rec}] mode={mode} status={stat} "
                f"batt={self.battery_pct}% recTime={self.record_time}s "
                f"freeSD={self.remain_capacity_mb}MB photos={self.remain_photo_num} "
                f"recLeft={self.remain_time_s}s res={self.video_resolution} fps_idx={self.fps_idx}")
