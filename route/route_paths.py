"""Walkable shortest paths (1-block step-ups) between consecutive stages, one bounding box per leg.

Usage: route_paths.py <region dir> <stages.json> <out.json>
stages.json: [[name, x, z], ...]
"""
import json, os, sys, time
from multiprocessing import Pool

import numpy as np
from scipy.sparse import csr_matrix
from scipy.sparse.csgraph import breadth_first_order

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import climb_check as cc

MARGIN = 500


def leg_path(a, b):
    (na, xa, za), (nb, xb, zb) = a, b
    x0, x1 = (min(xa, xb) - MARGIN) // 16 * 16, (max(xa, xb) + MARGIN) // 16 * 16 + 16
    z0, z1 = (min(za, zb) - MARGIN) // 16 * 16, (max(za, zb) + MARGIN) // 16 * 16 + 16
    regions = [(rx, rz) for rx in range(x0 // 512, (x1 - 1) // 512 + 1) for rz in range(z0 // 512, (z1 - 1) // 512 + 1)]
    H = np.full((z1 - z0, x1 - x0), cc.VOID, dtype=np.int16)
    with Pool(14) as pool:
        for chunks in pool.imap_unordered(cc.region_heights, regions):
            for bx, bz, top in chunks:
                if x0 <= bx < x1 and z0 <= bz < z1:
                    H[bz - z0:bz - z0 + 16, bx - x0:bx - x0 + 16] = top
    R, C = H.shape
    h = H.astype(np.int32)
    valid = H > cc.VOID
    idx = np.arange(R * C, dtype=np.int32).reshape(R, C)
    rows, cols = [], []
    for dr, dc in ((0, 1), (0, -1), (1, 0), (-1, 0)):
        sa = (slice(max(0, -dr), R - max(0, dr)), slice(max(0, -dc), C - max(0, dc)))
        sb = (slice(max(0, dr), R - max(0, -dr)), slice(max(0, dc), C - max(0, -dc)))
        rise = h[sb] - h[sa]
        ok = valid[sa] & valid[sb] & (rise <= 1) & (rise >= -cc.MAX_DROP)
        rows.append(idx[sa][ok]); cols.append(idx[sb][ok])
    rows = np.concatenate(rows); cols = np.concatenate(cols)
    G = csr_matrix((np.ones(len(rows), dtype=np.bool_), (rows, cols)), shape=(R * C, R * C))
    del rows, cols

    def nearest_valid(x, z):
        r, c = z - z0, x - x0
        for rad in range(0, 60):
            sub = valid[max(0, r - rad):r + rad + 1, max(0, c - rad):c + rad + 1]
            if sub.any():
                rr, cc_ = np.argwhere(sub)[0]
                return max(0, r - rad) + rr, max(0, c - rad) + cc_
        raise ValueError(f"no terrain near {x},{z}")

    ra, ca = nearest_valid(xa, za); rb, cb = nearest_valid(xb, zb)
    src, dst = ra * C + ca, rb * C + cb
    order, pred = breadth_first_order(G, src, directed=True, return_predecessors=True)
    reach = np.zeros(R * C, dtype=bool); reach[order] = True
    if not reach[dst]:
        rr = np.flatnonzero(reach)
        d2 = (rr % C - cb) ** 2 + (rr // C - rb) ** 2
        best = rr[np.argmin(d2)]
        if d2.min() > 40 ** 2:
            return {"from": na, "to": nb, "walkable": False,
                    "closest": [int(best % C + x0), int(h.flat[best]), int(best // C + z0)], "short_by": float(np.sqrt(d2.min()))}
        # Stage points can land on rooftops; end at the nearest reachable ground instead.
        dst = best
    path = [dst]
    while path[-1] != src:
        path.append(pred[path[-1]])
    path.reverse()
    pts = [(int(p % C + x0), int(h.flat[p]), int(p // C + z0)) for p in path]
    return {"from": na, "to": nb, "walkable": True, "steps": len(pts), "path": pts}


def main():
    cc.REGION_DIR = sys.argv[1]
    stages = json.load(open(sys.argv[2]))
    out = []
    for a, b in zip(stages, stages[1:]):
        t = time.time()
        res = leg_path(a, b)
        out.append(res)
        if res["walkable"]:
            print(f"{a[0]} -> {b[0]}: walkable, {res['steps']} steps ({time.time() - t:.0f}s)", flush=True)
        else:
            print(f"{a[0]} -> {b[0]}: NOT walkable, closest {res['closest']} short by {res['short_by']:.0f}", flush=True)
    json.dump(out, open(sys.argv[3], "w"))


if __name__ == "__main__":
    main()
