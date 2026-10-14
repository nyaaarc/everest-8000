"""Hypoxia tunnel-vision overlay: textures/misc/hypoxia.png (512x288 RGBA).

Procedural: an oval, fully transparent centre that ramps (smoothly, no hard
band) to near-black at the screen edges, with a slightly wobbly, organic
boundary and a faint dark-red tint in the outermost corners. RGB is dark
everywhere, so linear filtering never produces light fringes.
Deterministic (fixed seed) so it regenerates identically.
"""
import math

import numpy as np
from PIL import Image, ImageDraw

from common import save_texture, save_preview

W, H = 512, 288
SEED = 8849


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def value_noise(shape, cells, rng):
    """Smooth low-frequency noise in [-1, 1] (bicubic-upsampled random grid)."""
    gh, gw = cells
    g = rng.uniform(-1, 1, (gh + 3, gw + 3)).astype(np.float32)
    img = Image.fromarray(g, "F").resize((shape[1] + 2 * shape[1] // gw, shape[0] + 2 * shape[0] // gh),
                                          Image.BICUBIC)
    a = np.asarray(img)
    oy, ox = shape[0] // gh, shape[1] // gw
    return a[oy:oy + shape[0], ox:ox + shape[1]]


def build():
    rng = np.random.default_rng(SEED)
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    nx = (x + 0.5) / W * 2 - 1
    ny = (y + 0.5) / H * 2 - 1
    # Slightly squash vertically so the clear area is a wide oval on a 16:9 screen.
    r = np.sqrt(nx ** 2 + (ny * 1.06) ** 2)
    theta = np.arctan2(ny, nx)

    wob = np.zeros_like(r)
    for k, amp in ((2, 0.030), (3, 0.034), (4, 0.018), (5, 0.022), (7, 0.012), (9, 0.008), (13, 0.005)):
        wob += amp * np.sin(k * theta + rng.uniform(0, 2 * math.pi))
    blot = value_noise((H, W), (5, 9), rng) * 0.045 + value_noise((H, W), (10, 18), rng) * 0.018
    rr = r * (1.0 + wob) + blot * np.clip(r, 0, 1)

    # Alpha: clear until ~0.36, long smooth ramp, ~0.93 at mid-edges, ~0.99 in corners.
    a = smoothstep(0.34, 1.20, rr)
    a = 1.0 - (1.0 - a) ** 1.6
    a = np.clip(a * 1.0, 0, 0.995)

    # Colour: near-black, warming to a faint dark red only at the outermost rim.
    tint = smoothstep(0.95, 1.45, rr)
    base = np.array([5, 3, 4], np.float32)
    red = np.array([46, 5, 6], np.float32)
    rgbv = base[None, None, :] * (1 - tint[..., None]) + red[None, None, :] * tint[..., None]

    out = np.zeros((H, W, 4), np.uint8)
    out[..., :3] = np.clip(np.round(rgbv), 0, 255).astype(np.uint8)
    out[..., 3] = np.clip(np.round(a * 255), 0, 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def preview(ov):
    """Overlay over mid grey at full strength, 'mild' (scaled 1.6x, centred), and alpha map."""
    pw, ph = W, H
    grey = Image.new("RGBA", (pw, ph), (128, 128, 128, 255))
    d = ImageDraw.Draw(grey)
    for i in range(0, pw, 32):
        d.line([(i, 0), (i, ph)], fill=(118, 118, 118, 255))
    for j in range(0, ph, 32):
        d.line([(0, j), (pw, j)], fill=(118, 118, 118, 255))
    d.rectangle([pw // 2 - 3, ph // 2 - 3, pw // 2 + 3, ph // 2 + 3], fill=(255, 255, 255, 255))

    full = grey.copy()
    full.alpha_composite(ov)

    s = 1.6
    big = ov.resize((int(W * s), int(H * s)), Image.BILINEAR)
    l, t = (big.width - pw) // 2, (big.height - ph) // 2
    mild = grey.copy()
    mild.alpha_composite(big.crop((l, t, l + pw, t + ph)))

    alpha = ov.getchannel("A").convert("RGBA")

    out = Image.new("RGBA", (pw * 3 + 40, ph + 36), (40, 40, 40, 255))
    od = ImageDraw.Draw(out)
    for i, (img, label) in enumerate(((full, "full strength (stretched 1:1)"),
                                      (mild, "mild: drawn 1.6x, centred"),
                                      (alpha, "alpha channel"))):
        out.alpha_composite(img, (10 + i * (pw + 10), 26))
        od.text((10 + i * (pw + 10), 8), label, fill=(255, 255, 255))
    return out


def main():
    ov = build()
    save_texture(ov, "misc/hypoxia.png")
    save_preview(preview(ov), "hypoxia_preview.png")


if __name__ == "__main__":
    main()
