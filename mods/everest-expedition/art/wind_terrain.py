"""Bakes the terrain height grids the summit plume uses to know which ridges stand in front of it.

    python art/wind_terrain.py <surface.npy> <origin x> <origin z>

<surface.npy> comes from art/wind/scan_surface.py: the top block y of each 4-block cell (max), -32768 unknown.
Writes src/client/resources/assets/everest_expedition/terrain/khumbu_heights.bin (read by TerrainHeights.java):
deflate( "EVHM", version 2, grid count 3 (core, fine, coarse), then per grid: origin x, origin z, cell, width, depth, and the rows of
int16 surface heights (y of the first air block, max over the cell), delta-coded along x ).
"""
import os
import struct
import sys
import zlib

import numpy as np
from scipy import ndimage

from common import MOD_DIR, PREVIEW_DIR

VOID = -32768
OUT = os.path.join(MOD_DIR, "src", "client", "resources", "assets", "everest_expedition", "terrain", "khumbu_heights.bin")
# Fine grid: the upper Khumbu, everything a plume can reach (summit +-4.5 km) and the camps that look at it.
FINE = dict(x0=14336, z0=2048, x1=25600, z1=13312, cell=8)
# Core grid: Everest, Lhotse and Nuptse with the Western Cwm and the South Col, where the plume meets the ridges.
CORE = dict(x0=18176, z0=6656, x1=21888, z1=9984, cell=4)
COARSE_CELL = 32


def fill_holes(h):
    """Unknown cells inside the map take their nearest known neighbour; the world's outer edge stays unknown."""
    known = h != VOID
    outside = ~ndimage.binary_closing(known, structure=np.ones((3, 3)), iterations=24)
    outside = ndimage.binary_opening(outside, iterations=2) & ~known
    idx = ndimage.distance_transform_edt(~known, return_distances=False, return_indices=True)
    filled = h[idx[0], idx[1]]
    filled[outside] = VOID
    return filled


def pool_max(h, f):
    zz, xx = h.shape[0] // f * f, h.shape[1] // f * f
    v = h[:zz, :xx].reshape(zz // f, f, xx // f, f)
    out = v.max(axis=(1, 3))
    # A coarse cell that is partly off the map keeps the known maximum; wholly unknown stays VOID (VOID is the min).
    return out


def grid_bytes(x0, z0, cell, g):
    out = bytearray(struct.pack(">5i", x0, z0, cell, g.shape[1], g.shape[0]))
    g = g.astype(np.int32)
    deltas = np.diff(np.concatenate([np.zeros((g.shape[0], 1), np.int32), g], axis=1), axis=1)
    out += ((deltas + 0x8000) % 0x10000 - 0x8000).astype(">i2").tobytes()
    return bytes(out)


def main():
    src = np.load(sys.argv[1])
    ox, oz = int(sys.argv[2]), int(sys.argv[3])
    surf = np.where(src == VOID, VOID, src.astype(np.int32) + 1).astype(np.int32)
    surf = fill_holes(surf)

    def window(spec):
        z0, x0 = (spec["z0"] - oz) // 4, (spec["x0"] - ox) // 4
        z1, x1 = (spec["z1"] - oz) // 4, (spec["x1"] - ox) // 4
        return pool_max(surf[z0:z1, x0:x1], spec["cell"] // 4)

    core = window(CORE)
    fine = window(FINE)
    c = COARSE_CELL // 4
    coarse = pool_max(surf, c)

    blob = b"EVHM" + struct.pack(">ii", 2, 3)
    blob += grid_bytes(CORE["x0"], CORE["z0"], CORE["cell"], core)
    blob += grid_bytes(FINE["x0"], FINE["z0"], FINE["cell"], fine)
    blob += grid_bytes(ox, oz, COARSE_CELL, coarse)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    data = zlib.compress(blob, 9)
    with open(OUT, "wb") as fh:
        fh.write(data)
    print(f"core {core.shape[1]}x{core.shape[0]} @{CORE['cell']}, fine {fine.shape[1]}x{fine.shape[0]} @{FINE['cell']}, coarse {coarse.shape[1]}x{coarse.shape[0]} @{COARSE_CELL}, "
          f"{len(blob) / 1e6:.2f} MB raw -> {len(data) / 1e6:.2f} MB")
    print("unknown fine cells", int((fine == VOID).sum()), "coarse", int((coarse == VOID).sum()))

    # Overview, for checking the fill.
    from PIL import Image
    v = coarse != VOID
    hf = np.where(v, coarse, np.nan).astype(np.float32)
    gz, gx = np.gradient(hf)
    shade = np.nan_to_num(np.clip(0.6 + (gx - gz) * 0.02, 0, 1))
    n = np.clip((np.nan_to_num(hf, nan=-1400) + 1400) / 3500, 0, 1)
    img = np.dstack([np.where(v, (0.3 + 0.7 * n) * shade * 255, 40)] * 2 + [np.where(v, (0.3 + 0.7 * n) * shade * 265, 80)])
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(os.path.join(PREVIEW_DIR, "wind_terrain_baked.png"))


if __name__ == "__main__":
    main()
