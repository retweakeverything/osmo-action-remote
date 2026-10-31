#!/usr/bin/env python3
"""Drive a DJI Osmo Action camera through the ESP32 relay (firmware/osmo-bridge).

The ESP32 only relays bytes and sends the wake advertisement; all camera
protocol logic runs here, using the same verified codec as probe.py.

    python3 bridge.py ports
    python3 bridge.py scan
    python3 bridge.py wake-test --first-pair      # first time on this ESP32
    python3 bridge.py wake-test                   # later runs
    python3 bridge.py repl                        # type commands by hand
"""

from __future__ import annotations

import argparse
import os
import queue
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import serial
from serial.tools import list_ports

import rsdk_cmds as C
from rsdk import RsdkFrame, FRAME_RESP, RESP_REQUIRED, CMD_CONNECTION, CMD_STATUS_PUSH

ESPRESSIF_VID = 0x303A
DJI_DEMO_MAC = bytes([0x38, 0x34, 0x56, 0x78, 0x9A, 0xBC])
ID_FILE = Path(__file__).with_name(".bridge_device_id")  # gitignored


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def find_port(explicit: str | None) -> str:
    if explicit:
        return explicit
    ports = list(list_ports.comports())
    for p in ports:
        if p.vid == ESPRESSIF_VID:
            return p.device
    for p in ports:
        if p.vid in (0x1A86, 0x10C4, 0x0403):  # CH340, CP210x, FTDI USB-UART bridges
            return p.device
    raise SystemExit("No ESP32 serial port found. Plug it in, or pass --port /dev/cu.usbmodemXXXX")


class FrameAssembler:
    """Rebuilds R SDK frames from notification chunks and skips DUML (0x55) frames."""

    def __init__(self) -> None:
        self.buf = bytearray()

    def feed(self, chunk: bytes) -> list[RsdkFrame]:
        self.buf += chunk
        out: list[RsdkFrame] = []
        while True:
            i = next((k for k, b in enumerate(self.buf) if b in (0xAA, 0x55)), -1)
            if i < 0:
                self.buf.clear()
                return out
            del self.buf[:i]
            if len(self.buf) < 3:
                return out
            if self.buf[0] == 0xAA:
                n = (self.buf[1] | (self.buf[2] << 8)) & 0x3FF
                if n < 18:
                    del self.buf[:1]
                    continue
            else:
                n = self.buf[1] | ((self.buf[2] & 0x03) << 8)
                if n < 13:
                    del self.buf[:1]
                    continue
            if len(self.buf) < n:
                return out
            raw = bytes(self.buf[:n])
            if raw[0] == 0xAA:
                try:
                    out.append(RsdkFrame.decode(raw))
                except ValueError:
                    del self.buf[:1]
                    continue
            del self.buf[:n]


@dataclass
class Camera:
    addr: str
    addr_type: int
    rssi: int
    name: str
    wake_id: str | None


class Bridge:
    """Line-protocol client for the ESP32 relay."""

    def __init__(self, port: str, verbose: bool = False) -> None:
        self.ser = serial.Serial(port, 115200, timeout=0.1)
        self.verbose = verbose
        self.lines: queue.Queue[str] = queue.Queue()
        self.frames: queue.Queue[RsdkFrame] = queue.Queue()
        self.asm = FrameAssembler()
        self.link_up = False
        self.status: C.CameraStatus | None = None
        self._stop = False
        self.own_addr: str | None = None
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self) -> None:
        buf = b""
        while not self._stop:
            try:
                buf += self.ser.read(4096)
            except (serial.SerialException, OSError, TypeError):
                return  # port closed during shutdown
            while b"\n" in buf:
                raw, buf = buf.split(b"\n", 1)
                line = raw.decode(errors="replace").strip()
                if line:
                    self._on_line(line)

    def _on_line(self, line: str) -> None:
        if line.startswith("RX "):
            for f in self.asm.feed(bytes.fromhex(line[3:])):
                if f.cmd_set == CMD_STATUS_PUSH[0] and f.cmd_id == CMD_STATUS_PUSH[1]:
                    st = C.CameraStatus.parse(f.payload)
                    if st:
                        self.status = st
                self.frames.put(f)
            return
        if line.startswith("LINK_UP"):
            self.link_up = True
        elif line.startswith("LINK_DOWN"):
            self.link_up = False
            log(f"bridge: {line}")
        elif line.startswith(("LOG", "ERR", "READY")) or self.verbose:
            log(f"bridge: {line}")
        self.lines.put(line)

    def send(self, line: str) -> None:
        if self.verbose:
            log(f"host:   {line[:120]}")
        self.ser.write((line + "\n").encode())

    def expect(self, prefixes: tuple[str, ...], timeout: float) -> str:
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                line = self.lines.get(timeout=max(0.05, end - time.monotonic()))
            except queue.Empty:
                break
            if line.startswith(prefixes):
                return line
        raise TimeoutError(f"no {'/'.join(prefixes)} within {timeout}s")

    def close(self) -> None:
        self._stop = True
        self.ser.close()

    # ---- bridge commands ----

    def hello(self) -> str:
        self.ser.reset_input_buffer()
        self.send("HELLO")
        line = self.expect(("HELLO",), 3)
        parts = line.split()
        self.own_addr = parts[3] if len(parts) >= 4 else None
        return line

    def set_addr(self, mode: str) -> str:
        """Choose the own address for scanning/connecting; keeps own_addr = the public address."""
        self.send(f"ADDR {mode}")
        line = self.expect(("OK ADDR", "ERR"), 3)
        if line.startswith("ERR"):
            raise RuntimeError(line)
        for part in line.split():
            if part.startswith("public="):
                self.own_addr = part.split("=", 1)[1]
        return line

    def scan(self, seconds: int = 6) -> list[Camera]:
        self.send(f"SCAN {seconds}")
        found: dict[str, Camera] = {}
        end = time.monotonic() + seconds + 3
        while time.monotonic() < end:
            try:
                line = self.lines.get(timeout=0.5)
            except queue.Empty:
                continue
            if line.startswith("SCAN_DONE"):
                break
            if line.startswith("SCAN_RESULT"):
                _, addr, typ, rssi, mfr, name = line.split(" ", 5)
                wake = None
                # manufacturer data: AA 08 <2B> FA <6-byte wake id> ...
                if mfr != "-" and len(mfr) >= 22 and mfr[8:10] == "fa":
                    wake = mfr[10:22]
                found[addr] = Camera(addr, int(typ), int(rssi), name, wake)
        return sorted(found.values(), key=lambda c: -c.rssi)

    def connect(self, cam: Camera) -> None:
        self.asm = FrameAssembler()
        self.status = None
        self.send(f"CONNECT {cam.addr} {cam.addr_type}")
        line = self.expect(("LINK_UP", "ERR"), 20)
        if line.startswith("ERR"):
            raise RuntimeError(line)

    def disconnect(self) -> None:
        self.send("DISCONNECT")
        try:
            self.expect(("LINK_DOWN",), 5)
        except TimeoutError:
            pass

    def tx(self, frame: RsdkFrame) -> None:
        self.send("TX " + frame.encode().hex())

    def wake(self, wake_id: str, ms: int = 3000, addr: str = "public") -> None:
        self.send(f"WAKE {wake_id} {ms} {addr}")
        line = self.expect(("WAKE_DONE", "ERR"), ms / 1000 + 5)
        if line.startswith("ERR"):
            raise RuntimeError(line)


class Session:
    """R SDK conversation over the bridge (same handshake as the Android app)."""

    def __init__(self, br: Bridge) -> None:
        self.br = br
        self.seq = 0
        self.verify: str | None = None

    def next(self) -> int:
        self.seq = (self.seq + 1) & 0xFFFF
        return self.seq

    def request(self, frame: RsdkFrame, timeout: float = 3) -> RsdkFrame:
        self.br.tx(frame)
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                f = self.br.frames.get(timeout=0.2)
            except queue.Empty:
                continue
            if f.cmd_type & FRAME_RESP and f.seq == frame.seq:
                return f
            self._side(f)
        raise TimeoutError(f"no reply to 0x{frame.cmd_set:02x}/0x{frame.cmd_id:02x}")

    def _side(self, f: RsdkFrame) -> None:
        # Camera's own connection request (verify_mode 2): ack it or it resends forever.
        if (f.cmd_set, f.cmd_id) == CMD_CONNECTION and not f.cmd_type & FRAME_RESP:
            p = f.payload
            if len(p) > 27 and p[26] == 2:
                self.verify = "ALLOW" if p[27] == 0 else "REJECT"
                self.br.tx(C.build_connection_response(seq=f.seq))

    def handshake(self, first_pair: bool, device_id: int = 0, mac: bytes = b"") -> None:
        self.verify = None
        who = f"device_id=0x{device_id:08x} mac={mac.hex(':') or 'none'}"
        log(f"pairing: sending connection request as {who}" + (" (confirm on the camera)" if first_pair else ""))
        self.request(C.build_connection_request(1 if first_pair else 0, 1234, our_device_id=device_id,
                                                mac=mac, seq=self.next()))
        end = time.monotonic() + (90 if first_pair else 10)
        while self.verify is None and time.monotonic() < end:
            try:
                self._side(self.br.frames.get(timeout=0.2))
            except queue.Empty:
                pass
        if self.verify != "ALLOW":
            raise RuntimeError(f"camera did not allow the connection ({self.verify or 'no answer'})")
        self.request(C.build_status_subscribe(seq=self.next()), timeout=5)
        log("pairing: done, status subscribed")

    def drain(self) -> None:
        while True:
            try:
                self._side(self.br.frames.get_nowait())
            except queue.Empty:
                return

    def sleep(self) -> None:
        f = RsdkFrame(0x00, 0x1A, seq=self.next(), cmd_type=RESP_REQUIRED, payload=bytes([3]))
        try:
            r = self.request(f, timeout=3)
            log(f"sleep: camera replied ret={r.payload[:1].hex()}")
        except TimeoutError:
            log("sleep: no reply (camera may already be asleep)")

    def power_off_hold(self) -> None:
        """Long-press of the record key, like holding the button on a DJI remote."""
        r = None
        try:
            r = self.request(C.build_key_report(0x01, mode=1, key_value=0x0001, seq=self.next()), timeout=3)
        except TimeoutError:
            pass
        log(f"power-off (record key long press): ret={r.payload[:1].hex() if r else 'no reply'}")

    def wake_cmd(self) -> None:
        """In-link power mode switch to normal (0x00/0x1A payload 00)."""
        f = RsdkFrame(0x00, 0x1A, seq=self.next(), cmd_type=RESP_REQUIRED, payload=bytes([0]))
        try:
            r = self.request(f, timeout=3)
            log(f"wake command: camera replied ret={r.payload[:1].hex()}")
        except TimeoutError:
            log("wake command: no reply")

    def photo(self) -> bool:
        r = self.request(C.build_mode_switch(0x05, seq=self.next()))
        log(f"photo: switch to Photo mode ret={r.payload[:1].hex()}")
        wait_status(self.br, self, lambda s: s.camera_mode == 0x05, 5)
        before = self.br.status.remain_photo_num if self.br.status else None
        r = self.request(C.build_record_control(True, seq=self.next()))
        log(f"photo: shutter ret={r.payload[:1].hex()}")
        after = wait_status(self.br, self, lambda s: before is not None and s.remain_photo_num < before, 6)
        log("photo: taken (photos left went down)" if after else "photo: no change seen in photos left")
        return after is not None

    def record(self, start: bool) -> None:
        r = self.request(C.build_record_control(start, seq=self.next()))
        log(f"record {'start' if start else 'stop'}: ret={r.payload[:1].hex()}")


def wait_status(br: Bridge, sess: Session, predicate, seconds: float) -> C.CameraStatus | None:
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        sess.drain()
        st = br.status
        if st and predicate(st):
            return st
        time.sleep(0.2)
    return None


def identity(br: Bridge, mode: str) -> tuple[int, bytes]:
    """device_id and MAC to present in the connection request."""
    if mode == "none":
        return 0, b""
    if mode == "dji-demo":  # values DJI's demo and DJI-Remote ship with
        return 0x12345678, DJI_DEMO_MAC
    if mode == "dji-random":  # DJI-Remote: random id kept across runs, demo MAC
        if ID_FILE.exists():
            dev_id = int(ID_FILE.read_text().strip(), 16)
        else:
            dev_id = int.from_bytes(os.urandom(4), "big") or 1
            ID_FILE.write_text(f"{dev_id:08x}\n")
        return dev_id, DJI_DEMO_MAC
    if not br.own_addr:
        raise SystemExit("bridge did not report its address")
    mac = bytes.fromhex(br.own_addr.replace(":", ""))
    return int.from_bytes(mac[2:], "big"), mac  # stable id derived from the ESP32 address


def pick(br: Bridge, name: str, seconds: int = 6) -> Camera:
    for attempt in range(3):
        cams = [c for c in br.scan(seconds) if name.lower() in c.name.lower()]
        if cams:
            c = cams[0]
            log(f"found {c.name} {c.addr} type={c.addr_type} rssi={c.rssi} wakeId={c.wake_id}")
            return c
        log(f"scan {attempt + 1}: no camera matching '{name}' yet")
    raise SystemExit("camera not found (is it on? only one device can connect at a time)")


def cmd_ports(_args) -> int:
    for p in list_ports.comports():
        vid = f"{p.vid:04x}" if p.vid else "----"
        print(f"{p.device:40} vid={vid} {p.description}")
    return 0


def open_bridge(args) -> Bridge:
    br = Bridge(find_port(args.port), verbose=args.verbose)
    time.sleep(0.5)
    log(br.hello())
    # A previous run may have left the ESP32 connected; the camera does not advertise then.
    br.send("DISCONNECT")
    try:
        br.expect(("LINK_DOWN",), 3)
    except TimeoutError:
        pass
    return br


def cmd_scan(args) -> int:
    br = open_bridge(args)
    for c in br.scan(args.seconds):
        print(f"  {c.rssi:>4} dBm  {c.name:<24} {c.addr} type={c.addr_type} wakeId={c.wake_id}")
    br.close()
    return 0


def cmd_wake_test(args) -> int:
    """Pair through the ESP32, take a photo, sleep the camera, then wake it from the ESP32."""
    br = open_bridge(args)
    log(br.set_addr(args.conn_addr))
    dev_id, mac = identity(br, args.identity)
    sess = Session(br)
    cam = pick(br, args.name)
    if not cam.wake_id:
        log("warning: no wake id in the advertisement, using the BLE address")
        cam.wake_id = cam.addr.replace(":", "")
    br.connect(cam)
    sess.handshake(args.first_pair, dev_id, mac)
    st = wait_status(br, sess, lambda s: True, 5)
    log(f"status: power_mode={st.power_mode if st else '?'} battery={st.battery_pct if st else '?'}%")

    log(f"settling {args.settle}s after pairing")
    settle(sess, args.settle)
    if not args.no_photo:
        sess.photo()
        settle(sess, 3)

    log(f"STEP 1: {'power off (key hold)' if args.sleep_method == 'keyhold' else 'sleep'}")
    if args.sleep_method == "keyhold":
        sess.power_off_hold()
    else:
        sess.sleep()
    asleep = wait_status(br, sess, lambda s: s.power_mode == 3, 6)
    log("camera reports asleep" if asleep else "no asleep status seen (link may have dropped)")
    log(f"waiting {args.wait}s so the camera is properly asleep")
    settle(sess, args.wait)

    def woke_on_link(secs: float) -> bool:
        return br.link_up and wait_status(br, sess, lambda s: s.power_mode == 0, secs) is not None

    if br.link_up and args.wake_method in ("cmd", "both"):
        log("STEP 2a: in-link wake command (0x1A power_mode 0)")
        sess.wake_cmd()
        if woke_on_link(6):
            log("RESULT: PASS - camera woke up (in-link wake command)")
            br.close()
            return 0
        log("still asleep after the wake command")

    if args.wake_method in ("adv", "both"):
        for i in range(args.adv_repeat):
            if not br.link_up:
                break
            log(f"STEP 2b: wake broadcast with the link kept ({i + 1}/{args.adv_repeat})")
            br.wake(cam.wake_id, args.adv_ms, args.wake_addr)
            if woke_on_link(4):
                log("RESULT: PASS - camera woke up (broadcast, link kept)")
                br.close()
                return 0
        if br.link_up:
            log("still asleep, dropping the link")
            br.disconnect()
            time.sleep(2)
        for i in range(args.adv_repeat):
            log(f"STEP 2c: wake broadcast with no link ({i + 1}/{args.adv_repeat})")
            br.wake(cam.wake_id, args.adv_ms, args.wake_addr)
            time.sleep(1)
    elif br.link_up:
        br.disconnect()
        time.sleep(2)
    time.sleep(2)

    log("STEP 3: reconnect to confirm")
    try:
        cam2 = pick(br, args.name, seconds=8)
        br.connect(cam2)
        sess = Session(br)
        sess.handshake(False, dev_id, mac)
    except (SystemExit, TimeoutError, RuntimeError) as e:
        log(f"RESULT: FAIL - camera did not come back ({e})")
        br.close()
        return 1
    st = wait_status(br, sess, lambda s: True, 5)
    code = 0 if st and st.power_mode == 0 else 2
    log("RESULT: PASS - camera woke up and reconnected" if code == 0
        else f"RESULT: UNCLEAR - reconnected, power_mode={st.power_mode if st else '?'}")
    br.close()
    return code


def settle(sess: Session, seconds: float) -> None:
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        sess.drain()
        time.sleep(0.3)


def cmd_repl(args) -> int:
    br = open_bridge(args)
    sess = Session(br)
    cam: Camera | None = None
    print("commands: scan | connect | pair | pair-first | status | sleep | wake | record | stop | disconnect | quit")
    while True:
        try:
            cmd = input("> ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            break
        try:
            if cmd == "scan":
                cam = pick(br, args.name)
            elif cmd == "connect":
                cam = cam or pick(br, args.name)
                br.connect(cam)
            elif cmd in ("pair", "pair-first"):
                sess = Session(br)
                sess.handshake(cmd == "pair-first")
            elif cmd == "status":
                sess.drain()
                print(br.status if br.status else "no status yet")
            elif cmd == "sleep":
                sess.sleep()
            elif cmd == "wake":
                cam = cam or pick(br, args.name)
                br.wake(cam.wake_id or cam.addr.replace(":", ""), args.adv_ms)
            elif cmd == "record":
                sess.record(True)
            elif cmd == "stop":
                sess.record(False)
            elif cmd == "disconnect":
                br.disconnect()
            elif cmd in ("quit", "exit"):
                break
            elif cmd:
                print("unknown command")
        except (TimeoutError, RuntimeError) as e:
            print(f"error: {e}")
    br.close()
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", help="serial port, auto-detected if omitted")
    ap.add_argument("--verbose", action="store_true", help="print every bridge line")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("ports", help="list serial ports")
    sp = sub.add_parser("scan", help="scan for cameras through the ESP32")
    sp.add_argument("--seconds", type=int, default=6)
    for name in ("wake-test", "repl"):
        p = sub.add_parser(name)
        p.add_argument("--name", default="Osmo", help="part of the camera name")
        p.add_argument("--adv-ms", type=int, default=3000, help="wake advertisement duration")
        if name == "wake-test":
            p.add_argument("--first-pair", action="store_true", help="first time pairing this ESP32")
            p.add_argument("--wait", type=int, default=10, help="seconds asleep before waking")
            p.add_argument("--settle", type=int, default=5, help="seconds to wait after pairing")
            p.add_argument("--no-photo", action="store_true", help="skip the test photo before sleeping")
            p.add_argument("--identity", choices=["esp", "dji-demo", "dji-random", "none"], default="esp",
                           help="device id/MAC sent when pairing (esp = the ESP32's own address)")
            p.add_argument("--sleep-method", choices=["cmd", "keyhold"], default="cmd",
                           help="cmd = power mode 0x1A sleep, keyhold = record key long press")
            p.add_argument("--wake-method", choices=["adv", "cmd", "both"], default="both",
                           help="adv = WKP broadcast, cmd = in-link 0x1A power_mode 0")
            p.add_argument("--adv-repeat", type=int, default=1, help="wake broadcasts per phase")
            p.add_argument("--wake-addr", choices=["public", "random"], default="public",
                           help="own address type for the wake advertisement")
            p.add_argument("--conn-addr", choices=["public", "random"], default="public",
                           help="own address type for the connection (wake advert is always public)")
    args = ap.parse_args()
    handlers = {"ports": cmd_ports, "scan": cmd_scan, "wake-test": cmd_wake_test, "repl": cmd_repl}
    return handlers[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
