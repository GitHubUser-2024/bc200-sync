#!/usr/bin/python3
"""Make made-up BC200-style rides (.fit) for store screenshots. No real data: invented loop
route around a fixed point in the Alps, synthetic speed / heart rate / cadence / altitude.
Usage: tools/demo_rides.py <out dir>  (push the files to an emulator folder; used for the store screenshots)"""
import math, os, random, struct, sys
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

FIT_EPOCH = 631065600
TABLE = [0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
         0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400]


def crc(data, c=0):
    for b in data:
        t = TABLE[c & 0xF]; c = (c >> 4) & 0x0FFF; c ^= t ^ TABLE[b & 0xF]
        t = TABLE[c & 0xF]; c = (c >> 4) & 0x0FFF; c ^= t ^ TABLE[(b >> 4) & 0xF]
    return c


def definition(local, glob, fields):
    out = bytes([0x40 | local, 0, 0]) + struct.pack("<HB", glob, len(fields))
    for num, size, typ in fields:
        out += bytes([num, size, typ])
    return out


REC = [(253, 4, 0x86), (0, 4, 0x85), (1, 4, 0x85), (2, 2, 0x84), (3, 1, 0x02), (4, 1, 0x02),
       (5, 4, 0x86), (6, 2, 0x84), (13, 1, 0x01)]
SES = [(253, 4, 0x86), (2, 4, 0x86), (7, 4, 0x86), (8, 4, 0x86), (59, 4, 0x86), (9, 4, 0x86),
       (11, 2, 0x84), (14, 2, 0x84), (15, 2, 0x84), (16, 1, 0x02), (17, 1, 0x02), (18, 1, 0x02),
       (19, 1, 0x02), (22, 2, 0x84), (23, 2, 0x84), (50, 2, 0x84), (71, 2, 0x84), (57, 1, 0x01), (58, 1, 0x01)]


def ride(start, km, seed):
    rnd = random.Random(seed)
    lat0, lon0 = 46.5450, 11.7600  # invented start point in the Dolomites
    t0 = int(start.timestamp()) - FIT_EPOCH
    body = bytearray()
    body += definition(0, 0, [(0, 1, 0x00), (4, 4, 0x86), (8, 20, 0x07)])
    body += bytes([0, 4]) + struct.pack("<I", t0) + b"BC200".ljust(20, b"\0")
    body += definition(1, 20, REC)
    v_prev = 7.0
    dist = 0.0; t = 0; hrs = []; cads = []; alts = []; speeds = []; up = down = 0.0
    prev_alt = None; hr = 95.0; nv = 0.0; ncad = 0.0; grade_s = 0.0; coast = 0
    a1, a2, a3 = rnd.uniform(1.6, 2.4), rnd.uniform(3.5, 5.0), rnd.uniform(8.0, 11.0)
    p1, p2, p3 = rnd.uniform(0, 6), rnd.uniform(0, 6), rnd.uniform(0, 6)
    total = km * 1000
    while dist < total:
        f = dist / total
        # Route: a wobbly loop (radius ~ km/6.3 km) back to the start.
        ang = 2 * math.pi * f
        r = total / (2 * math.pi) * (1 + 0.25 * math.sin(3 * ang + seed) + 0.1 * math.sin(7 * ang))
        lat = lat0 + (r * (math.cos(ang) - 1)) / 111320
        lon = lon0 + (r * math.sin(ang)) / (111320 * math.cos(math.radians(lat0)))
        alt = (1450 + 120 * math.sin(2 * math.pi * f * a1 + p1) + 45 * math.sin(2 * math.pi * f * a2 + p2)
               + 15 * math.sin(2 * math.pi * f * a3 + p3))
        grade = 0 if prev_alt is None else (alt - prev_alt) / max(v_prev, 1) * 100  # percent
        grade_s += (grade - grade_s) * 0.05
        nv = 0.995 * nv + rnd.gauss(0, 0.05)  # slow, smooth speed changes
        v = max(2.5, min(16.0, 7.5 - grade_s * 0.55 + nv * 3))  # m/s, slower uphill
        if coast > 0: coast -= 1
        elif grade_s < -2.5 and rnd.random() < 0.02: coast = rnd.randint(8, 40)
        ncad = 0.98 * ncad + rnd.gauss(0, 0.4)
        cad = 0 if coast else int(max(55, min(105, 84 + grade_s * 1.2 + ncad * 3)))
        hr += ((145 + grade_s * 3.5 - (12 if coast else 0)) - hr) * 0.02 + rnd.gauss(0, 0.25)
        hr = max(90, min(178, hr))
        temp = int(round(14 + 4 * math.sin(math.pi * f) - (alt - 1450) / 150))
        body += bytes([1]) + struct.pack("<IiiHBBIHb", t0 + t, int(lat / 180 * 2**31), int(lon / 180 * 2**31),
                                         int((alt + 500) * 5), int(hr), cad, int(dist * 100), int(v * 1000), temp)
        if prev_alt is not None:
            d = alt - prev_alt
            if d > 0: up += d
            else: down -= d
        prev_alt = alt
        hrs.append(hr); cads.append(cad); alts.append(alt); speeds.append(v); v_prev = v
        dist += v; t += 1
    pause = rnd.choice([180, 300, 420])
    body += definition(2, 18, SES)
    moving = t * 1000
    pedal = [c for c in cads if c > 0]
    body += bytes([2]) + struct.pack(
        "<IIIIIIHHHBBBBHHHHbb", t0 + t + pause, t0, moving + pause * 1000, moving, moving, int(dist * 100),
        int(dist / 1000 * 28), int(dist / t * 1000), int(max(speeds) * 1000), int(sum(hrs) / len(hrs)), int(max(hrs)),
        int(sum(pedal) / len(pedal)), max(cads), int(up), int(down), int((max(alts) + 500) * 5),
        int((min(alts) + 500) * 5), 15, 18)
    head = struct.pack("<BBHI4s", 14, 0x20, 2132, len(body), b".FIT")
    head += struct.pack("<H", crc(head))
    data = head + bytes(body)
    return data + struct.pack("<H", crc(data))


out = sys.argv[1]
os.makedirs(out, exist_ok=True)
for start, km, seed in [((2026, 10, 4, 7, 12), 31.4, 1), ((2026, 10, 11, 12, 40), 22.8, 2),
                        ((2026, 10, 18, 8, 5), 44.6, 3), ((2026, 10, 25, 14, 20), 27.2, 4)]:
    d = datetime(*start, tzinfo=timezone.utc)
    name = d.astimezone(ZoneInfo("Europe/Prague")).strftime("%Y%m%d%H%M%S") + ".fit"
    data = ride(d, km, seed)
    assert crc(data) == 0
    open(os.path.join(out, name), "wb").write(data)
    print(name, len(data))
