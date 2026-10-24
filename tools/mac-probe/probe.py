#!/usr/bin/env python3
"""Mac BLE probe for the DJI Osmo Action 5 Pro.

Verifies the protocol against the real camera. The Action 5 Pro uses TWO
protocols on the same BLE link:
  - DUML  (0x55): telemetry push (battery/power)         -> duml.py
  - R SDK (0xAA): control (pair, record, mode, status)   -> rsdk.py

Control requires a handshake:
  Connection Request 0x00/0x19  (camera shows verify code; confirm on screen)
  -> Status Subscribe 0x1D/0x05
  -> Record 0x1D/0x03 / Mode 0x1D/0x04 now work; status 0x1D/0x02 pushes begin

Usage:
    python3 probe.py scan [--name Osmo]
    python3 probe.py listen --name Osmo [--seconds 20] [--changes-only]
    python3 probe.py connect --name Osmo [--seconds 60] [--record]
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time

from ble_client import OsmoBle, scan
from rsdk import CMD_CONNECTION, CMD_STATUS_PUSH
import rsdk_cmds as C


async def cmd_scan(args) -> int:
    print(f"scanning {args.timeout}s"
          + (f" for names containing '{args.name}'" if args.name else "") + " ...")
    hits = await scan(timeout=args.timeout, name_substr=args.name)
    if not hits:
        print("no devices found. Is the camera on and in range? "
              "On macOS, grant Bluetooth permission to your terminal app.")
        return 1
    for h in hits:
        print(f"  {h.rssi:>4} dBm  {h.name or '(no name)':<24} {h.device.address}")
    return 0


async def _resolve(args):
    hits = await scan(timeout=args.timeout, name_substr=args.name)
    if not hits:
        print("device not found")
        return None
    hit = hits[0]
    print(f"using {hit.name} ({hit.device.address}) RSSI {hit.rssi}")
    return hit.device


async def cmd_listen(args) -> int:
    dev = await _resolve(args)
    if not dev:
        return 1
    from telemetry import describe
    last_by_type: dict[tuple, bytes] = {}
    start = time.monotonic()

    def on_frame(src4, raw, decoded, kind):
        if decoded is None:
            return
        t = time.monotonic() - start
        key = (kind, decoded.cmd_set, decoded.cmd_id)
        if args.changes_only:
            prev = last_by_type.get(key)
            if prev == decoded.payload:
                return
            last_by_type[key] = decoded.payload
            marker = " *CHANGED*" if prev is not None else " (first seen)"
        else:
            marker = ""
        human = describe(decoded) if kind == "duml" else ""
        human = f"  {human}" if human else ""
        print(f"[{t:6.2f}s {src4} {kind}] set=0x{decoded.cmd_set:02x} "
              f"cmd=0x{decoded.cmd_id:02x} payload={decoded.payload.hex()}{human}{marker}")

    cam = OsmoBle(dev, on_frame=on_frame, verbose=False)
    await cam.connect()
    mode = "changes-only" if args.changes_only else "all frames"
    print(f"listening {args.seconds}s ({mode})...")
    try:
        await asyncio.sleep(args.seconds)
    finally:
        await cam.disconnect()
    return 0


async def cmd_connect(args) -> int:
    """Full R SDK connect: Connection Request -> handshake -> subscribe status."""
    dev = await _resolve(args)
    if not dev:
        return 1

    loop = asyncio.get_running_loop()
    events: asyncio.Queue = asyncio.Queue()
    start = time.monotonic()
    seen_status = {"n": 0}

    def on_frame(src4, raw, decoded, kind):
        t = time.monotonic() - start
        if decoded is None or kind != "rsdk":
            return
        key = (decoded.cmd_set, decoded.cmd_id)
        if key == CMD_CONNECTION:
            loop.call_soon_threadsafe(events.put_nowait, ("conn", t, decoded))
        elif key == CMD_STATUS_PUSH:
            st = C.CameraStatus.parse(decoded.payload)
            seen_status["n"] += 1
            print(f"[{t:6.2f}s] STATUS {st if st else decoded.payload.hex()}")
        else:
            print(f"[{t:6.2f}s] {decoded}")

    cam = OsmoBle(dev, on_frame=on_frame, verbose=False)
    await cam.connect()
    paired = False
    try:
        vm = 0 if args.paired else 1
        req = C.build_connection_request(verify_mode=vm, verify_data=args.code,
                                         seq=cam.next_seq())
        print(f"\n--> Connection Request (verify_mode={vm}). "
              f"If a verify-code popup appears on the camera, confirm it.\n")
        await cam.write_frame(req)

        # Handshake loop: observe CONNECTION frames, reply to the camera's
        # request ONCE. An already-paired camera may complete differently, so
        # we also treat an incoming status push (see on_frame) as success.
        deadline = loop.time() + args.seconds
        while loop.time() < deadline and not paired:
            try:
                kind, t, frame = await asyncio.wait_for(
                    events.get(), timeout=min(2.0, max(0.1, deadline - loop.time())))
            except asyncio.TimeoutError:
                if seen_status["n"] > 0:
                    paired = True
                    print(f"[{time.monotonic()-start:6.2f}s] "
                          f"status pushes arriving -> treating as CONNECTED")
                continue
            if kind != "conn":
                continue
            p = frame.payload
            is_resp = bool(frame.cmd_type & 0x20)
            # Print every CONNECTION frame so we can see the paired-path behavior.
            print(f"[{t:6.2f}s] CONNECTION {'resp' if is_resp else 'cmd'} "
                  f"seq={frame.seq} payload={p.hex()}")
            # The camera's connection REQUEST (cmd, not resp) carries
            # verify_mode@26 and result@27. We MUST reply with our connection
            # response echoing its seq, or the camera retransmits forever.
            if (not is_resp) and len(p) > 27 and p[26] == 2:
                allow = p[27] == 0
                if not allow:
                    print(f"[{t:6.2f}s] camera REJECTED the connection.")
                    return 2
                resp = C.build_connection_response(seq=frame.seq)
                await cam.write_frame(resp)
                print(f"[{t:6.2f}s] --> sent connection response (seq={frame.seq}) ALLOW")
                paired = True
                # Subscribe to status now that the handshake is complete.
                await cam.write_frame(C.build_status_subscribe(seq=cam.next_seq()))

        if not paired:
            print("\nnot paired: no ALLOW and no status pushes seen.")
            print("Tips: (1) try '--paired' (verify_mode=0); (2) on the camera, "
                  "forget this remote in Bluetooth settings and re-run without "
                  "--paired to force a fresh verify popup.\n")
            return 2

        print("\n*** PAIRED ***  subscribing to camera status...\n")
        await cam.write_frame(C.build_status_subscribe(seq=cam.next_seq()))
        await asyncio.sleep(2)

        if args.record:
            print("\n--> START recording for 4s\n")
            await cam.write_frame(C.build_record_control(True, seq=cam.next_seq()))
            await asyncio.sleep(4)
            print("\n--> STOP recording\n")
            await cam.write_frame(C.build_record_control(False, seq=cam.next_seq()))
            await asyncio.sleep(3)
        elif args.mode is not None:
            print(f"\n--> Mode switch to 0x{args.mode:02x}\n")
            await cam.write_frame(C.build_mode_switch(args.mode, seq=cam.next_seq()))
            await asyncio.sleep(4)
        elif args.photo:
            print("\n--> Photo (switch to Photo mode, then shutter)\n")
            await cam.write_frame(C.build_mode_switch(0x05, seq=cam.next_seq()))
            await asyncio.sleep(2)
            await cam.write_frame(C.build_record_control(True, seq=cam.next_seq()))
            await asyncio.sleep(3)
        elif args.highlight:
            print("\n--> Highlight tag (key 0x02). START recording first, "
                  "tag at +2s, stop at +5s\n")
            await cam.write_frame(C.build_record_control(True, seq=cam.next_seq()))
            await asyncio.sleep(2)
            await cam.write_frame(C.build_key_report(0x02, seq=cam.next_seq()))
            print("        (highlight sent; camera does not ack - check screen)")
            await asyncio.sleep(3)
            await cam.write_frame(C.build_record_control(False, seq=cam.next_seq()))
            await asyncio.sleep(2)
        elif args.key is not None:
            print(f"\n--> Key report key_code=0x{args.key:02x}\n")
            await cam.write_frame(C.build_key_report(args.key, seq=cam.next_seq()))
            await asyncio.sleep(4)
        else:
            print(f"listening {args.seconds}s for status pushes "
                  f"(press record/mode on the camera to see changes)...")
            await asyncio.sleep(args.seconds)

        print(f"\nreceived {seen_status['n']} status push(es).")
    finally:
        await cam.disconnect()
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    def common(sp):
        sp.add_argument("--name", default="Osmo", help="name substring filter")
        sp.add_argument("--timeout", type=float, default=8.0, help="scan timeout s")
        sp.add_argument("--seconds", type=float, default=30.0, help="duration s")

    sp = sub.add_parser("scan", help="scan for BLE devices")
    sp.add_argument("--name", default=None)
    sp.add_argument("--timeout", type=float, default=8.0)

    lp = sub.add_parser("listen", help="connect and observe pushed frames")
    common(lp)
    lp.add_argument("--changes-only", action="store_true",
                    help="only print a frame type when its payload changes")

    cp = sub.add_parser("connect", help="R SDK connect + pair + status (+record)")
    common(cp)
    cp.add_argument("--paired", action="store_true",
                    help="use verify_mode=0 (already paired) instead of 1")
    cp.add_argument("--code", type=int, default=12345,
                    help="verify_data code (uint16)")
    cp.add_argument("--record", action="store_true",
                    help="after pairing, start/stop a 4s recording")
    cp.add_argument("--mode", type=lambda s: int(s, 0), default=None,
                    help="switch camera mode, e.g. 0x01 Video, 0x05 Photo, 0x0A Hyperlapse")
    cp.add_argument("--photo", action="store_true",
                    help="switch to Photo mode and take a shot")
    cp.add_argument("--highlight", action="store_true",
                    help="record, insert a highlight tag (key 0x02), stop")
    cp.add_argument("--key", type=lambda s: int(s, 0), default=None,
                    help="send a raw Key Report key_code (0x01 record, 0x02 QS, 0x03 snapshot)")

    args = p.parse_args()
    handlers = {"scan": cmd_scan, "listen": cmd_listen, "connect": cmd_connect}
    try:
        return asyncio.run(handlers[args.cmd](args))
    except KeyboardInterrupt:
        print("\ninterrupted")
        return 130


if __name__ == "__main__":
    sys.exit(main())
