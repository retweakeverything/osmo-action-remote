"""BLE client for a DJI Osmo camera over CoreBluetooth (via Bleak).

Handles: scan, connect, discover service fff0, subscribe to notifications,
write DUML frames to fff5, and surface every received frame decoded.

The GATT UUIDs and characteristic roles are from public reverse-engineering
notes (docs/PROTOCOL.md); the notify channel on the Action 5 Pro is confirmed
empirically at runtime (we subscribe to both fff4 and fff5 and log which
delivers data).
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice

from duml import DumlMessage
from rsdk import RsdkFrame

SERVICE_FFF0 = "0000fff0-0000-1000-8000-00805f9b34fb"
CHAR_FFF3 = "0000fff3-0000-1000-8000-00805f9b34fb"
CHAR_FFF4 = "0000fff4-0000-1000-8000-00805f9b34fb"
CHAR_FFF5 = "0000fff5-0000-1000-8000-00805f9b34fb"


@dataclass
class ScanHit:
    device: BLEDevice
    name: str
    rssi: int


async def scan(timeout: float = 8.0, name_substr: str | None = None) -> list[ScanHit]:
    """Scan for BLE devices, optionally filtering by name substring."""
    hits: dict[str, ScanHit] = {}

    def _cb(device: BLEDevice, adv):
        name = device.name or adv.local_name or ""
        if name_substr and name_substr.lower() not in name.lower():
            return
        hits[device.address] = ScanHit(device=device, name=name, rssi=adv.rssi)

    scanner = BleakScanner(detection_callback=_cb)
    await scanner.start()
    await asyncio.sleep(timeout)
    await scanner.stop()
    return sorted(hits.values(), key=lambda h: h.rssi, reverse=True)


class OsmoBle:
    """Connected session to one camera."""

    def __init__(self, device: BLEDevice, on_frame=None, verbose: bool = True):
        self._device = device
        self._client = BleakClient(device)
        self._on_frame = on_frame
        self._verbose = verbose
        self._msg_id = 0
        self._notify_chars: list[str] = []

    async def __aenter__(self) -> "OsmoBle":
        await self.connect()
        return self

    async def __aexit__(self, *exc) -> None:
        await self.disconnect()

    async def connect(self) -> None:
        await self._client.connect()
        if self._verbose:
            print(f"[ble] connected to {self._device.name or self._device.address}")
            self._log_services()
        # Subscribe to both candidate notify channels; log which delivers data.
        for uuid in (CHAR_FFF4, CHAR_FFF5, CHAR_FFF3):
            try:
                await self._client.start_notify(uuid, self._handle_notify)
                self._notify_chars.append(uuid)
                if self._verbose:
                    print(f"[ble] subscribed to {uuid[4:8]}")
            except Exception as e:  # noqa: BLE001
                if self._verbose:
                    print(f"[ble] could not subscribe to {uuid[4:8]}: {e}")

    def _log_services(self) -> None:
        for svc in self._client.services:
            print(f"[gatt] service {svc.uuid}")
            for ch in svc.characteristics:
                print(f"[gatt]   char {ch.uuid}  props={','.join(ch.properties)}")

    def _handle_notify(self, sender, data: bytearray) -> None:
        raw = bytes(data)
        src = getattr(sender, "uuid", str(sender))
        src4 = src[4:8] if isinstance(src, str) and len(src) >= 8 else src
        hexs = raw.hex()
        decoded = None
        kind = None
        # Try R SDK (0xAA control) first, then DUML (0x55 telemetry).
        if raw[:1] == b"\xaa":
            try:
                decoded = RsdkFrame.decode(raw)
                kind = "rsdk"
            except Exception:  # noqa: BLE001
                pass
        if decoded is None and raw[:1] == b"\x55":
            try:
                decoded = DumlMessage.decode(raw)
                kind = "duml"
            except Exception:  # noqa: BLE001
                pass
        if self._verbose:
            if decoded:
                print(f"[rx {src4}] {decoded}  raw={hexs}")
            else:
                print(f"[rx {src4}] (undecoded) raw={hexs}")
        if self._on_frame:
            self._on_frame(src4, raw, decoded, kind)

    def next_msg_id(self) -> int:
        self._msg_id = (self._msg_id + 1) & 0xFFFF
        return self._msg_id

    def next_seq(self) -> int:
        return self.next_msg_id()

    async def write_fff4(self, data: bytes) -> None:
        if self._verbose:
            print(f"[tx fff4] {data.hex()}")
        await self._client.write_gatt_char(CHAR_FFF4, data, response=True)

    async def write_raw(self, raw: bytes, chunk: int = 20) -> None:
        """Write raw bytes to fff5, chunked to respect small BLE MTUs.

        The Connection Request frame (~51 bytes) exceeds the default ~20-byte
        write; DJI's camera reassembles chunked writes to fff5.
        """
        if self._verbose:
            print(f"[tx fff5] {raw.hex()}")
        for i in range(0, len(raw), chunk):
            await self._client.write_gatt_char(CHAR_FFF5, raw[i:i + chunk],
                                               response=False)

    async def write_frame(self, msg) -> None:
        """Encode and send a DumlMessage or RsdkFrame."""
        await self.write_raw(msg.encode())

    async def disconnect(self) -> None:
        try:
            await self._client.disconnect()
        finally:
            if self._verbose:
                print("[ble] disconnected")
