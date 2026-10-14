"""Textures for the jet-stream wind effects.

    python art/wind_fx.py            writes the textures and art/previews/wind_*.png

plume_noise.png   a 64^3 tileable noise volume for the summit plume, stored as an 8x8 grid of 64x64 z-slices
                  R  gradient-noise fbm (body)        G  inverted Worley fbm (billows, erosion)
                  B  high-frequency gradient fbm      A  independent low-frequency fbm (warp)
particle/*.png    spindrift sprites: a soft grain, a motion-blurred streak and four fibrous wisps
"""
import os

import numpy as np
from PIL import Image

from common import PREVIEW_DIR, TEX_ROOT

N = 64
RNG = np.random.default_rng(8849)


def perlin3(n, period, seed):
    """Tileable 3D gradient noise on an n^3 grid with `period` cells per side, roughly in [-1, 1]."""
    rng = np.random.default_rng(seed)
    g = rng.normal(size=(period, period, period, 3))
    g /= np.linalg.norm(g, axis=-1, keepdims=True)
    # A random sub-cell offset per call keeps the lattice points of different octaves from lining up.
    off = rng.random(3)
    c = np.arange(n) * period / n
    x, y, z = np.meshgrid(c + off[0], c + off[1], c + off[2], indexing="ij")
    xi, yi, zi = np.floor(x).astype(int), np.floor(y).astype(int), np.floor(z).astype(int)
    xf, yf, zf = x - xi, y - yi, z - zi

    def fade(t):
        return t * t * t * (t * (t * 6 - 15) + 10)

    u, v, w = fade(xf), fade(yf), fade(zf)
    out = np.zeros_like(x)
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                gg = g[(xi + dx) % period, (yi + dy) % period, (zi + dz) % period]
                dot = gg[..., 0] * (xf - dx) + gg[..., 1] * (yf - dy) + gg[..., 2] * (zf - dz)
                wx = u if dx else 1 - u
                wy = v if dy else 1 - v
                wz = w if dz else 1 - w
                out += wx * wy * wz * dot
    return out * 1.6


def worley3(n, period, seed):
    """Tileable 3D Worley (cellular) distance to the nearest feature point, 0 at points, ~1 between."""
    rng = np.random.default_rng(seed)
    pts = rng.random((period, period, period, 3))
    c = (np.arange(n) + 0.5) * period / n
    x, y, z = np.meshgrid(c, c, c, indexing="ij")
    xi, yi, zi = np.floor(x).astype(int), np.floor(y).astype(int), np.floor(z).astype(int)
    best = np.full(x.shape, 9.0)
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                cx, cy, cz = xi + dx, yi + dy, zi + dz
                p = pts[cx % period, cy % period, cz % period]
                d = (cx + p[..., 0] - x) ** 2 + (cy + p[..., 1] - y) ** 2 + (cz + p[..., 2] - z) ** 2
                best = np.minimum(best, d)
    return np.clip(np.sqrt(best), 0, 1)


def fbm(fn, base, octaves, seed, gain=0.5):
    total, amp, norm = 0, 1.0, 0
    for o in range(octaves):
        total = total + amp * fn(N, base * 2 ** o, seed + o * 101)
        norm += amp
        amp *= gain
    return total / norm


def to_u8(a, lo=None, hi=None):
    lo = a.min() if lo is None else lo
    hi = a.max() if hi is None else hi
    return np.clip((a - lo) / (hi - lo) * 255 + 0.5, 0, 255).astype(np.uint8)


def plume_noise():
    body = fbm(perlin3, 4, 4, 11)
    billow = 1 - fbm(worley3, 4, 3, 23, gain=0.55)
    detail = fbm(perlin3, 8, 3, 37)
    warp = fbm(perlin3, 2, 3, 53)
    # Stretch each channel to the full byte range around its own spread, so the shader can treat them as 0..1.
    chans = [to_u8(body, np.percentile(body, 0.5), np.percentile(body, 99.5)),
             to_u8(billow, np.percentile(billow, 0.5), np.percentile(billow, 99.5)),
             to_u8(detail, np.percentile(detail, 0.5), np.percentile(detail, 99.5)),
             to_u8(warp, np.percentile(warp, 0.5), np.percentile(warp, 99.5))]
    vol = np.stack(chans, axis=-1)  # [x, y, z, c]
    atlas = np.zeros((8 * N, 8 * N, 4), np.uint8)
    for z in range(N):
        gx, gy = z % 8, z // 8
        # Image rows are y, columns are x.
        atlas[gy * N:(gy + 1) * N, gx * N:(gx + 1) * N] = vol[:, :, z].transpose(1, 0, 2)
    return atlas


def soft_disc(w, h, sharp=2.0):
    y, x = np.mgrid[0:h, 0:w]
    dx = (x + 0.5 - w / 2) / (w / 2)
    dy = (y + 0.5 - h / 2) / (h / 2)
    r = np.sqrt(dx * dx + dy * dy)
    return np.clip(1 - r, 0, 1) ** sharp


def sprite(alpha, tint=(255, 255, 255)):
    a = to_u8(alpha, 0.0, 1.0)
    rgb = np.empty(a.shape + (3,), np.uint8)
    rgb[...] = tint
    return Image.fromarray(np.dstack([rgb, a]), "RGBA")


def grain():
    # A small soft dot: a solid core so it reads at a few pixels, then a quick falloff. 16 px so that, magnified with
    # the particle atlas's nearest filtering, it stays round instead of turning into a plus sign.
    return sprite(np.clip(soft_disc(16, 16, 1.2) * 1.5, 0, 1))


def streak():
    # A motion-blurred grain: bright along the middle line, tapering to both ends, brightest toward the head (right).
    w, h = 32, 8
    y, x = np.mgrid[0:h, 0:w]
    t = (x + 0.5) / w
    along = np.clip(np.minimum(t / 0.75, (1 - t) / 0.25), 0, 1) ** 0.8
    across = np.clip(1 - np.abs((y + 0.5 - h / 2) / (h / 2)), 0, 1) ** 1.5
    return sprite(along * across)


def wisp(seed):
    """A fibrous puff of blown snow: long along x (the wind), with streaky density and soft, ragged edges."""
    w, h = 64, 32
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:h, 0:w]
    u = (x + 0.5) / w
    v = (y + 0.5) / h
    # Fibres: noise that varies quickly across the wind and slowly along it.
    fib = np.zeros((h, w))
    for k, amp in ((3, 0.5), (6, 0.3), (12, 0.2)):
        phase = rng.random() * 6.28
        drift = rng.normal() * 0.6
        fib += amp * np.sin((v * k + drift * u * u) * 6.28 + phase + 1.5 * np.sin(u * 3.1 + phase))
    fib = 0.55 + 0.45 * fib
    # Envelope: a teardrop that is thicker toward the upwind end and frays out downwind.
    cy = 0.5 + 0.08 * np.sin(u * 3.0 + seed)
    thick = 0.42 * (1 - 0.45 * u)
    env = np.clip(1 - ((v - cy) / thick) ** 2, 0, 1) * np.clip(np.minimum(u / 0.18, (1 - u) / 0.6), 0, 1)
    a = np.clip(env ** 1.3 * fib, 0, 1)
    # Light blur so the fibres stay soft when the sprite is magnified with nearest filtering.
    a = (a + np.roll(a, 1, 0) + np.roll(a, -1, 0) + np.roll(a, 1, 1) + np.roll(a, -1, 1)) / 5
    return sprite(np.clip(a * 0.85, 0, 1))


def preview_sprites(sprites, path):
    scale = 6
    sheet_w = sum(im.width * scale + 16 for im in sprites) + 16
    sheet_h = max(im.height for im in sprites) * scale + 32
    for bg, name in (((70, 105, 160), ""), ((30, 30, 34), "_dark")):
        sheet = Image.new("RGBA", (sheet_w, sheet_h), bg + (255,))
        x = 16
        for im in sprites:
            big = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
            sheet.alpha_composite(big, (x, 16))
            x += big.width + 16
        sheet.convert("RGB").save(path.replace(".png", name + ".png"))


def main():
    particle_dir = os.path.join(TEX_ROOT, "particle")
    effect_dir = os.path.join(TEX_ROOT, "effect")
    os.makedirs(particle_dir, exist_ok=True)
    os.makedirs(effect_dir, exist_ok=True)
    os.makedirs(PREVIEW_DIR, exist_ok=True)

    sprites = {"snow_grain": grain(), "snow_streak": streak()}
    for i in range(4):
        sprites[f"snow_wisp_{i}"] = wisp(100 + i * 17)
    for name, im in sprites.items():
        im.save(os.path.join(particle_dir, name + ".png"))
    preview_sprites(list(sprites.values()), os.path.join(PREVIEW_DIR, "wind_sprites.png"))

    atlas = plume_noise()
    Image.fromarray(atlas, "RGBA").save(os.path.join(effect_dir, "plume_noise.png"))
    for c, name in enumerate("rgba"):
        Image.fromarray(atlas[..., c], "L").save(os.path.join(PREVIEW_DIR, f"wind_plume_noise_{name}.png"))
    print("wrote", ", ".join(sprites), "and plume_noise.png")


if __name__ == "__main__":
    main()
