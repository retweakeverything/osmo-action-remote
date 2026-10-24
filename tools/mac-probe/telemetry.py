"""Telemetry interpretation for DJI Osmo Action 5 Pro status frames.

Field offsets are being mapped empirically from real captures. Anything marked
TENTATIVE is a hypothesis from observed byte behavior, not yet confirmed by
deliberately changing that state on the camera.

Confirmed so far (cmdSet 0x0D cmdId 0x02, "power/battery" frame, 34-byte payload):
  - offset 20 (uint8): battery percent. Observed 0x4c=76 then 0x4a=74 as the
    battery drained between captures. CONFIRMED pending a third reading.
  - offsets 1-2 (uint16 LE): fluctuating ~0x0fe0-0x0ff0 (~4080-4090). Likely
    battery voltage in mV. TENTATIVE.
  - offsets 5-6 (int16 LE): fluctuating negative (~ -400 to -1100). Likely
    instantaneous current draw (mA, negative = discharging). TENTATIVE.
"""

from __future__ import annotations

from duml import DumlMessage


def describe(msg: DumlMessage) -> str | None:
    """Return a human-readable interpretation of a known frame, else None."""
    if (msg.cmd_set, msg.cmd_id) == (0x0D, 0x02):
        return _describe_power(msg.payload)
    # Record/mode status frames (cmdSet 0x1D) to be mapped once captured.
    if msg.cmd_set == 0x1D:
        return f"[status 0x1D/0x{msg.cmd_id:02x}] payload={msg.payload.hex()} (mapping TBD)"
    return None


def _describe_power(p: bytes) -> str:
    if len(p) < 21:
        return f"[power] short payload {p.hex()}"
    import struct
    voltage = struct.unpack_from("<H", p, 1)[0]      # TENTATIVE mV
    current = struct.unpack_from("<h", p, 5)[0]       # TENTATIVE mA (signed)
    battery = p[20]                                   # CONFIRMED-ish %
    return f"[power] battery={battery}%  voltage~{voltage}mV  current~{current}mA"
