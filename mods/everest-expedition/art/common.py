"""Shared helpers for the Everest Expedition pixel-art scripts.

Every small sprite is authored as an explicit character grid plus a palette
(one character = one pixel). '.' and ' ' are transparent.
"""
import os
from PIL import Image, ImageDraw

ART_DIR = os.path.dirname(os.path.abspath(__file__))
MOD_DIR = os.path.dirname(ART_DIR)
TEX_ROOT = os.path.join(
    MOD_DIR, "src", "client", "resources", "assets", "everest_expedition", "textures"
)
PREVIEW_DIR = os.path.join(ART_DIR, "previews")


def rgb(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def grid(rows, palette, name="sprite", size=None):
    """Turn a list of equal-length strings into an RGBA image."""
    h = len(rows)
    w = len(rows[0])
    for i, r in enumerate(rows):
        if len(r) != w:
            raise ValueError(f"{name}: row {i} has length {len(r)}, expected {w}: {r!r}")
    if size is not None and (w, h) != size:
        raise ValueError(f"{name}: grid is {w}x{h}, expected {size[0]}x{size[1]}")
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    for y, r in enumerate(rows):
        for x, ch in enumerate(r):
            if ch in ". ":
                continue
            if ch not in palette:
                raise KeyError(f"{name}: unknown palette key {ch!r} at ({x},{y})")
            px[x, y] = palette[ch]
    return img


def paste_grid(dst, rows, palette, x0, y0, name="part"):
    """Draw an authored grid into dst at (x0, y0); transparent cells are skipped."""
    src = grid(rows, palette, name)
    dpx = dst.load()
    spx = src.load()
    for y in range(src.height):
        for x in range(src.width):
            c = spx[x, y]
            if c[3]:
                dpx[x0 + x, y0 + y] = c
    return src.size


def check_symmetric(img, name):
    """Warn if the alpha silhouette is not left/right symmetric."""
    w, h = img.size
    px = img.load()
    bad = []
    for y in range(h):
        for x in range(w // 2):
            if (px[x, y][3] > 0) != (px[w - 1 - x, y][3] > 0):
                bad.append((x, y))
    if bad:
        print(f"  [warn] {name}: silhouette asymmetric at {bad[:8]}{'...' if len(bad) > 8 else ''}")


def save_texture(img, rel):
    path = os.path.join(TEX_ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG")
    print(f"  wrote {path} ({img.width}x{img.height})")
    return path


def save_preview(img, name):
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    path = os.path.join(PREVIEW_DIR, name)
    img.save(path, "PNG")
    print(f"  preview {path}")
    return path


def up(img, k):
    return img.resize((img.width * k, img.height * k), Image.NEAREST)


def on_bg(img, color=(128, 128, 128, 255)):
    bg = Image.new("RGBA", img.size, color)
    bg.alpha_composite(img)
    return bg


def work_view(img, k=16, bg=(128, 128, 128, 255)):
    """Big nearest-neighbour view with a faint pixel grid, for editing."""
    big = up(on_bg(img, bg), k)
    d = ImageDraw.Draw(big)
    for x in range(0, big.width, k):
        d.line([(x, 0), (x, big.height)], fill=(0, 0, 0, 40))
    for y in range(0, big.height, k):
        d.line([(0, y), (big.width, y)], fill=(0, 0, 0, 40))
    return big
