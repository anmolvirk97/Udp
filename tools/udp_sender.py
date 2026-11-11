#!/usr/bin/env python3
"""
Test sender for UDP Mock GPS.

Broadcasts one position per second (default 255.255.255.255:10110) while moving in a circle, in one of
three formats the app understands:

  nmea : $GPRMC + $GPGGA sentences with valid checksums, CRLF-terminated, in ONE packet
  json : {"lat":..,"lon":..,"alt":..,"speed":..,"bearing":..,"acc":..}
  csv  : lat,lon,alt,speed,bearing,accuracy

Examples:
  python3 tools/udp_sender.py
  python3 tools/udp_sender.py --format json --lat 28.99 --lon 77.02
  python3 tools/udp_sender.py --host 192.168.1.42            # unicast to one phone
  python3 tools/udp_sender.py --bind 192.168.1.10            # pick the right NIC on multi-homed PCs
  python3 tools/udp_sender.py --drop-after 10 --drop-for 8   # silence for 8 s to test "signal lost"
"""
import argparse
import json
import math
import socket
import sys
import time
from datetime import datetime, timezone

M_PER_DEG_LAT = 111_320.0
MPS_TO_KNOTS = 1.0 / 0.514444


def checksum(body: str) -> str:
    c = 0
    for ch in body:
        c ^= ord(ch)
    return f"{c:02X}"


def sentence(body: str) -> str:
    return f"${body}*{checksum(body)}\r\n"


def nmea_lat(value: float):
    hemi = "N" if value >= 0 else "S"
    value = abs(value)
    deg = int(value)
    minutes = (value - deg) * 60.0
    return f"{deg:02d}{minutes:07.4f}", hemi


def nmea_lon(value: float):
    hemi = "E" if value >= 0 else "W"
    value = abs(value)
    deg = int(value)
    minutes = (value - deg) * 60.0
    return f"{deg:03d}{minutes:07.4f}", hemi


def build_nmea(now, lat, lon, alt, speed_mps, bearing, hdop):
    t = now.strftime("%H%M%S") + ".00"
    d = now.strftime("%d%m%y")
    la, ns = nmea_lat(lat)
    lo, ew = nmea_lon(lon)
    rmc = f"GPRMC,{t},A,{la},{ns},{lo},{ew},{speed_mps * MPS_TO_KNOTS:.2f},{bearing:.1f},{d},,,A"
    gga = f"GPGGA,{t},{la},{ns},{lo},{ew},1,10,{hdop:.1f},{alt:.1f},M,0.0,M,,"
    return sentence(rmc) + sentence(gga)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="255.255.255.255", help="destination (default: broadcast)")
    ap.add_argument("--port", type=int, default=10110)
    ap.add_argument("--format", choices=["nmea", "json", "csv"], default="nmea")
    ap.add_argument("--lat", type=float, default=37.4220, help="circle centre latitude")
    ap.add_argument("--lon", type=float, default=-122.0841, help="circle centre longitude")
    ap.add_argument("--alt", type=float, default=30.0)
    ap.add_argument("--radius", type=float, default=100.0, help="circle radius in metres")
    ap.add_argument("--speed", type=float, default=5.0, help="ground speed in m/s")
    ap.add_argument("--hdop", type=float, default=0.9)
    ap.add_argument("--interval", type=float, default=1.0, help="seconds between packets")
    ap.add_argument("--count", type=int, default=0, help="stop after N packets (0 = forever)")
    ap.add_argument("--bind", default="", help="local IP to send from")
    ap.add_argument("--drop-after", type=float, default=0, help="stop sending after N seconds ...")
    ap.add_argument("--drop-for", type=float, default=0, help="... for this many seconds, then resume")
    args = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    if args.bind:
        sock.bind((args.bind, 0))

    omega = args.speed / args.radius  # rad/s
    start = time.monotonic()
    next_tick = start
    sent = 0
    print(f"Sending {args.format.upper()} to {args.host}:{args.port} every {args.interval}s (Ctrl+C to stop)")
    try:
        while args.count == 0 or sent < args.count:
            elapsed = time.monotonic() - start
            silent = args.drop_after > 0 and args.drop_after <= elapsed < args.drop_after + args.drop_for
            if not silent:
                theta = omega * elapsed
                north = args.radius * math.cos(theta)
                east = args.radius * math.sin(theta)
                lat = args.lat + north / M_PER_DEG_LAT
                lon = args.lon + east / (M_PER_DEG_LAT * math.cos(math.radians(args.lat)))
                bearing = (math.degrees(theta) + 90.0) % 360.0
                now = datetime.now(timezone.utc)
                acc = args.hdop * 5.0
                if args.format == "nmea":
                    payload = build_nmea(now, lat, lon, args.alt, args.speed, bearing, args.hdop)
                elif args.format == "json":
                    payload = json.dumps({
                        "lat": round(lat, 7), "lon": round(lon, 7), "alt": args.alt,
                        "speed": args.speed, "bearing": round(bearing, 1), "acc": acc,
                    })
                else:
                    payload = f"{lat:.7f},{lon:.7f},{args.alt:.1f},{args.speed:.2f},{bearing:.1f},{acc:.1f}"
                sock.sendto(payload.encode("ascii"), (args.host, args.port))
                sent += 1
                print(f"[{sent}] {payload.strip().splitlines()[0][:100]}")
            else:
                print("(silent - simulating signal loss)")
            next_tick += args.interval
            time.sleep(max(0.0, next_tick - time.monotonic()))
    except KeyboardInterrupt:
        print("\nStopped.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
