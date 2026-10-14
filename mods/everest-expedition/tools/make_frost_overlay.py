"""Generate the frostbite screen overlay (frostbite.png): ice crystals thick at the edges, clear in the middle.

The game scales this up so only the edges show at first, then shrinks it toward full size as the player freezes,
until just a small clear spot is left in the centre. Needs numpy, scipy and Pillow.

Usage: make_frost_overlay.py <out.png>
"""
import sys

import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter, zoom

W, H = 1024, 576
rng = np.random.default_rng(8849)


def fractal(octaves, base):
    out = np.zeros((H, W))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cells = base * 2 ** o
        grid = rng.random((cells * H // W + 2, cells + 2))
        layer = zoom(grid, (H / (grid.shape[0] - 1), W / (grid.shape[1] - 1)), order=3)[:H, :W]
        out += layer * amp
        total += amp
        amp *= 0.55
    return out / total


y, x = np.mgrid[0:H, 0:W]
u = (x / (W - 1)) * 2 - 1
v = (y / (H - 1)) * 2 - 1
r = np.sqrt(u ** 2 + v ** 2)
angle = np.arctan2(v, u)

# Feathery crystals: fine noise smeared along the radius, so frost grows inward in fronds.
fine = fractal(5, 24)
streak = np.zeros_like(fine)
for k in (37, 61, 89):
    streak += np.sin(angle * k + fine * 9) * 0.5 + 0.5
streak /= 3
blotch = fractal(4, 5)

edge = 0.22 + 0.28 * blotch                       # ragged inner edge of the frost
mask = np.clip((r - edge) / 0.55, 0, 1)
mask = mask * mask * (3 - 2 * mask)
crystals = 0.55 + 0.45 * (0.6 * streak + 0.4 * fine)
alpha = np.clip(mask * crystals * 1.15, 0, 0.97)
alpha = gaussian_filter(alpha, 0.8)

tint = 0.82 + 0.18 * fine
rgba = np.zeros((H, W, 4))
rgba[..., 0] = 0.80 * tint
rgba[..., 1] = 0.90 * tint
rgba[..., 2] = 1.00 * tint
rgba[..., 3] = alpha
Image.fromarray((rgba * 255).astype(np.uint8), "RGBA").save(sys.argv[1], optimize=True)
print(sys.argv[1], f"clear centre radius ~{edge.min():.2f}..{edge.max():.2f} of the half-screen")
