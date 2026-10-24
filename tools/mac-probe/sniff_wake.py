#!/usr/bin/env python3
"""Listen for DJI wake broadcasts (manufacturer id 0x4B57, 'WK') and print them.

Used to confirm what a phone actually puts on air when it tries to wake a camera.
Usage: python3 sniff_wake.py [--seconds 90]
"""
import argparse
import asyncio
import time

from bleak import BleakScanner

WAKE_ID = 0x4B57


async def main(seconds: float) -> None:
    seen = 0
    start = time.monotonic()

    def cb(device, adv):
        nonlocal seen
        data = adv.manufacturer_data.get(WAKE_ID)
        if data is None:
            return
        seen += 1
        t = time.monotonic() - start
        print(f"[{t:6.2f}s] WAKE packet from {device.address} rssi={adv.rssi} "
              f"data(after 574B)={data.hex()} services={adv.service_uuids} name={adv.local_name}", flush=True)

    scanner = BleakScanner(detection_callback=cb)
    await scanner.start()
    print(f"listening {seconds:.0f}s for 0x4B57 wake packets...", flush=True)
    await asyncio.sleep(seconds)
    await scanner.stop()
    print(f"done, {seen} wake packet(s) seen", flush=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=90)
    asyncio.run(main(ap.parse_args().seconds))
