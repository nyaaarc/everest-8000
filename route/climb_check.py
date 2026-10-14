"""Full-resolution walkability check (1-block step-ups) between climb waypoints.

Reads MOTION_BLOCKING_NO_LEAVES heightmaps for a bounding box, builds a directed grid graph
(step up <= 1 block, drop <= MAX_DROP), and finds a walkable path between consecutive waypoints.
"""
import json, os, struct, sys, time, zlib
from multiprocessing import Pool

import numpy as np
from scipy.sparse import csr_matrix
from scipy.sparse.csgraph import breadth_first_order

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from scan_terrain import HM_TAG, decode_heightmap, MIN_Y

REGION_DIR = sys.argv[1]
OUT = sys.argv[2]
X0, X1, Z0, Z1 = 16384, 21504, 6656, 9728   # block bounds (exclusive max)
MAX_DROP = 12
VOID = -32768


def region_heights(args):
    rx, rz = args
    out = []
    path = os.path.join(REGION_DIR, f"r.{rx}.{rz}.mca")
    if not os.path.exists(path):
        return out
    buf = open(path, "rb").read()
    for idx in range(1024):
        loc = struct.unpack_from(">I", buf, idx * 4)[0]
        off = (loc >> 8) * 4096
        if not off:
            continue
        n = struct.unpack_from(">I", buf, off)[0]
        try:
            data = zlib.decompress(buf[off + 5: off + 4 + n])
        except zlib.error:
            continue
        i = data.find(HM_TAG)
        if i < 0:
            continue
        a = np.array(decode_heightmap(data, i), dtype=np.int32).reshape(16, 16)
        top = np.where(a > 0, a - 1 + MIN_Y, VOID).astype(np.int16)
        out.append(((rx * 32 + idx % 32) * 16, (rz * 32 + idx // 32) * 16, top))
    return out


def main():
    regions = [(rx, rz) for rx in range(X0 // 512, (X1 - 1) // 512 + 1) for rz in range(Z0 // 512, (Z1 - 1) // 512 + 1)]
    H = np.full((Z1 - Z0, X1 - X0), VOID, dtype=np.int16)
    t = time.time()
    with Pool(14) as pool:
        for chunks in pool.imap_unordered(region_heights, regions):
            for bx, bz, top in chunks:
                if X0 <= bx < X1 and Z0 <= bz < Z1:
                    H[bz - Z0:bz - Z0 + 16, bx - X0:bx - X0 + 16] = top
    print(f"heights loaded in {time.time() - t:.0f}s, shape {H.shape}", flush=True)
    np.save(os.path.join(OUT, "climb_heights.npy"), H)

    R, C = H.shape
    idx = np.arange(R * C, dtype=np.int64).reshape(R, C)
    h = H.astype(np.int32)
    valid = H > VOID
    rows, cols = [], []
    for dr, dc in ((0, 1), (0, -1), (1, 0), (-1, 0)):
        a = (slice(max(0, -dr), R - max(0, dr)), slice(max(0, -dc), C - max(0, dc)))
        b = (slice(max(0, dr), R - max(0, -dr)), slice(max(0, dc), C - max(0, -dc)))
        rise = h[b] - h[a]
        ok = valid[a] & valid[b] & (rise <= 1) & (rise >= -MAX_DROP)
        rows.append(idx[a][ok]); cols.append(idx[b][ok])
    rows = np.concatenate(rows); cols = np.concatenate(cols)
    G = csr_matrix((np.ones(len(rows), dtype=np.bool_), (rows, cols)), shape=(R * C, R * C))
    print(f"graph: {G.nnz} edges", flush=True)

    waypoints = json.load(open(os.path.join(OUT, "climb_waypoints.json")))
    result = []
    for (na, xa, za), (nb, xb, zb) in zip(waypoints, waypoints[1:]):
        def nearest_valid(x, z):
            r, c = z - Z0, x - X0
            for rad in range(0, 40):
                sub = valid[max(0, r - rad):r + rad + 1, max(0, c - rad):c + rad + 1]
                if sub.any():
                    rr, cc = np.argwhere(sub)[0]
                    return max(0, r - rad) + rr, max(0, c - rad) + cc
            raise ValueError("no terrain near waypoint")
        ra, ca = nearest_valid(xa, za); rb, cb = nearest_valid(xb, zb)
        order, pred = breadth_first_order(G, ra * C + ca, directed=True, return_predecessors=True)
        reach = np.zeros(R * C, dtype=bool); reach[order] = True
        target = rb * C + cb
        if reach[target]:
            path = [target]
            while path[-1] != ra * C + ca:
                path.append(pred[path[-1]])
            path.reverse()
            pts = [(int(p % C + X0), int(h.flat[p]), int(p // C + Z0)) for p in path]
            result.append({"from": na, "to": nb, "walkable": True, "steps": len(pts), "path": pts[::25] + [pts[-1]]})
            print(f"{na} -> {nb}: walkable, {len(pts)} steps", flush=True)
        else:
            rr = np.flatnonzero(reach)
            d2 = (rr % C - cb) ** 2 + (rr // C - rb) ** 2
            best = rr[np.argmin(d2)]
            hi = rr[np.argmax(h.flat[rr])]
            result.append({"from": na, "to": nb, "walkable": False,
                           "closest": [int(best % C + X0), int(h.flat[best]), int(best // C + Z0)],
                           "closest_dist": float(np.sqrt(d2.min())),
                           "highest_reached": [int(hi % C + X0), int(h.flat[hi]), int(hi // C + Z0)],
                           "reachable_cells": int(reach.sum())})
            print(f"{na} -> {nb}: NOT walkable; closest {result[-1]['closest']} "
                  f"({result[-1]['closest_dist']:.0f} blocks short), highest reached {result[-1]['highest_reached']}", flush=True)
    json.dump(result, open(os.path.join(OUT, "climb_result.json"), "w"))


if __name__ == "__main__":
    main()
