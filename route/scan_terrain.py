"""Scan a world's region files for terrain heights and signs of buildings.

Writes heights.npy (top block Y at 4-block resolution, -32768 = void) and
buildings.json (chunks whose palettes contain building blocks).
"""
import json, os, re, struct, sys, time, zlib
from multiprocessing import Pool

import numpy as np

MIN_Y, HEIGHT = -2032, 4064
BITS = (HEIGHT + 1 - 1).bit_length()  # bits per heightmap entry
PER_LONG = 64 // BITS
HM_TAG = b"\x0c\x00\x19MOTION_BLOCKING_NO_LEAVES"
VOID = -32768
# Block names that terrain generators never place, so they indicate builds.
MARKERS = [b"_door", b"_bed", b"glass", b"lantern", b"crafting_table", b"chest",
           b"_planks", b"campfire", b"_banner", b"_sign", b"bell", b"torch"]
REGION_RE = re.compile(r"r\.(-?\d+)\.(-?\d+)\.mca$")


def decode_heightmap(data, i):
    count = struct.unpack_from(">i", data, i + len(HM_TAG))[0]
    longs = struct.unpack_from(f">{count}q", data, i + len(HM_TAG) + 4)
    mask = (1 << BITS) - 1
    out = []
    for v in longs:
        v &= (1 << 64) - 1
        for k in range(PER_LONG):
            out.append((v >> (k * BITS)) & mask)
            if len(out) == 256:
                return out
    return out


def scan_region(path):
    m = REGION_RE.search(path)
    rx, rz = int(m.group(1)), int(m.group(2))
    heights = np.full((128, 128), VOID, dtype=np.int16)  # [z, x] at 4-block cells
    builds = []
    with open(path, "rb") as f:
        buf = f.read()
    if len(buf) < 8192:
        return rx, rz, heights, builds
    for idx in range(1024):
        loc = struct.unpack_from(">I", buf, idx * 4)[0]
        off, cnt = (loc >> 8) * 4096, loc & 0xFF
        if not off or not cnt:
            continue
        length, comp = struct.unpack_from(">IB", buf, off)
        raw = buf[off + 5: off + 4 + length]
        if comp == 2:
            data = zlib.decompress(raw)
        elif comp == 1:
            import gzip
            data = gzip.decompress(raw)
        else:
            continue
        cx, cz = idx % 32, idx // 32
        i = data.find(HM_TAG)
        if i >= 0:
            hm = decode_heightmap(data, i)
            a = np.array(hm, dtype=np.int32).reshape(16, 16)  # [z, x]
            top = np.where(a > 0, a - 1 + MIN_Y, VOID)
            cell = top.reshape(4, 4, 4, 4).max(axis=(1, 3))
            heights[cz * 4:cz * 4 + 4, cx * 4:cx * 4 + 4] = cell
        hits = [mk.decode() for mk in MARKERS if mk in data]
        if hits:
            builds.append({"chunkX": rx * 32 + cx, "chunkZ": rz * 32 + cz, "markers": hits})
    return rx, rz, heights, builds


def main():
    region_dir, out_dir = sys.argv[1], sys.argv[2]
    limit = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    workers = int(sys.argv[4]) if len(sys.argv) > 4 else 12
    files = sorted(os.path.join(region_dir, n) for n in os.listdir(region_dir) if REGION_RE.search(n))
    if limit:
        files = files[:limit]
    coords = [tuple(map(int, REGION_RE.search(p).groups())) for p in files]
    minrx, minrz = min(c[0] for c in coords), min(c[1] for c in coords)
    maxrx, maxrz = max(c[0] for c in coords), max(c[1] for c in coords)
    grid = np.full(((maxrz - minrz + 1) * 128, (maxrx - minrx + 1) * 128), VOID, dtype=np.int16)
    all_builds = []
    t = time.time()
    with Pool(workers) as pool:
        for n, (rx, rz, h, b) in enumerate(pool.imap_unordered(scan_region, files, chunksize=4), 1):
            gz, gx = (rz - minrz) * 128, (rx - minrx) * 128
            grid[gz:gz + 128, gx:gx + 128] = h
            all_builds.extend(b)
            if n % 200 == 0:
                print(f"{n}/{len(files)} regions, {time.time() - t:.0f}s", flush=True)
    os.makedirs(out_dir, exist_ok=True)
    np.save(os.path.join(out_dir, "heights.npy"), grid)
    with open(os.path.join(out_dir, "buildings.json"), "w") as f:
        json.dump({"origin_block": [minrx * 512, minrz * 512], "cell_blocks": 4, "chunks": all_builds}, f)
    print(f"done: {len(files)} regions in {time.time() - t:.0f}s, origin block {minrx * 512},{minrz * 512}, "
          f"{len(all_builds)} chunks with building markers", flush=True)


if __name__ == "__main__":
    main()
