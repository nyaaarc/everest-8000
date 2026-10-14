"""Oxygen HUD sprites (GUI sprite atlas: textures/gui/sprites/hud/oxygen/).

Geometry contract with the code:
  cylinder_frame   7x14  outline only, interior (1,2)..(5,12) is transparent
  cylinder_fill    5x11  drawn at frame offset (1,2), cropped from the TOP
                         (visible rows = bottom N rows), so it fills bottom-up
  cylinder_missing 7x14  same silhouette, grey + translucent
  mask             7x7
  spo2_background  56x5  interior (1,1)..(54,3) dark
  spo2_fill(_low)  54x3  drawn at (1,1), cropped from the RIGHT (width = pct)
"""
from PIL import Image, ImageDraw

from common import grid, rgb, save_texture, save_preview, up

OX = "gui/sprites/hud/oxygen/"

# --------------------------------------------------------------------------
# Cylinder frame: dark outline with rounded corners, silver valve on top.
# --------------------------------------------------------------------------
FRAME_PAL = {
    "o": rgb("1e0e06"),  # outline (very dark, warm)
    "v": rgb("767c86"),  # valve
    "V": rgb("e8ecf2"),  # valve highlight pixel
    "x": rgb("3a3e46"),  # valve shadow
}
FRAME = [
    "..xVx..",
    ".ooooo.",
    "o.....o",
    "o.....o",
    "o.....o",
    "o.....o",
    "o.....o",
    "o.....o",
    "o.....o",
    "o.....o",
    "o.....o",
    "o.....o",
    "o.....o",
    ".ooooo.",
]

MISSING_PAL = {
    "o": (70, 70, 74, 150),
    "v": (110, 110, 116, 130),
    "V": (140, 140, 146, 130),
    "x": (80, 80, 86, 130),
}

# --------------------------------------------------------------------------
# Oxygen fill: teal, highlight column at x=1, shadow column at x=4,
# two faint bubbles, darker bottom row.
# --------------------------------------------------------------------------
FILL_PAL = {
    "H": rgb("c8fffa"),  # highlight column
    "L": rgb("6fe6e0"),  # light
    "B": rgb("27c2c8"),  # base
    "S": rgb("1593a8"),  # shadow
    "D": rgb("0d6a82"),  # deep (bottom)
}
FILL = [
    "LHBBS",
    "LHBBS",
    "LHBLS",
    "LHBBS",
    "LHBBS",
    "LHBBS",
    "LHLBS",
    "LHBBS",
    "LHBBS",
    "LHBBS",
    "BLSSD",
]

# --------------------------------------------------------------------------
# Mask: grey rubber cup, narrow at the nose, orange exhale valve.
# --------------------------------------------------------------------------
MASK_PAL = {
    "k": rgb("141418"),
    "K": rgb("34363c"),
    "J": rgb("53565f"),
    "j": rgb("8a8e98"),
    "w": rgb("d4d8de"),
    "S": rgb("c45a14"),
    "L": rgb("ffb050"),
}
MASK = [
    "...k...",
    "..kwk..",
    ".kwjJk.",
    "kjJSJKk",
    "kJSLSKk",
    "kKJSJKk",
    ".kkkkk.",
]

# --------------------------------------------------------------------------
# SpO2 bar
# --------------------------------------------------------------------------
BAR_OUT = rgb("101014")
BAR_IN_TOP = rgb("181a24")
BAR_IN = rgb("232633")
BAR_TICK = rgb("343849")

SPO2_ROWS = [rgb("eef9ff"), rgb("9ed6ff"), rgb("5aa6ea")]       # healthy
SPO2_LOW_ROWS = [rgb("ffc8bc"), rgb("ee4a3c"), rgb("a8201e")]   # danger


def build_spo2_background():
    w, h = 56, 5
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    for x in range(1, w - 1):
        px[x, 0] = BAR_OUT
        px[x, h - 1] = BAR_OUT
    for y in range(1, h - 1):
        px[0, y] = BAR_OUT
        px[w - 1, y] = BAR_OUT
    for x in range(1, w - 1):
        px[x, 1] = BAR_IN_TOP
        px[x, 2] = BAR_IN
        px[x, 3] = BAR_IN
    # faint quarter ticks (hidden under the fill when it is above them)
    for q in (1, 2, 3):
        px[1 + round(54 * q / 4), 3] = BAR_TICK
    return img


def build_spo2_fill(rows):
    img = Image.new("RGBA", (54, 3), (0, 0, 0, 0))
    px = img.load()
    for y, c in enumerate(rows):
        for x in range(54):
            px[x, y] = c
    return img


def build_all():
    s = {}
    s["cylinder_frame"] = grid(FRAME, FRAME_PAL, "cylinder_frame", (7, 14))
    s["cylinder_fill"] = grid(FILL, FILL_PAL, "cylinder_fill", (5, 11))
    s["cylinder_missing"] = grid(FRAME, MISSING_PAL, "cylinder_missing", (7, 14))
    s["mask"] = grid(MASK, MASK_PAL, "mask", (7, 7))
    s["spo2_background"] = build_spo2_background()
    s["spo2_fill"] = build_spo2_fill(SPO2_ROWS)
    s["spo2_fill_low"] = build_spo2_fill(SPO2_LOW_ROWS)
    # contract check: frame interior must be exactly the fill rect
    fr = s["cylinder_frame"].load()
    for y in range(14):
        for x in range(7):
            inside = 1 <= x <= 5 and 2 <= y <= 12
            if inside and fr[x, y][3] != 0:
                raise ValueError(f"frame interior pixel ({x},{y}) is not transparent")
    return s


# --------------------------------------------------------------------------
# Mock-up helpers: blit exactly the way the HUD code is expected to.
# --------------------------------------------------------------------------
def draw_cylinder(canvas, s, x, y, pct, in_use=False):
    canvas.alpha_composite(s["cylinder_frame"], (x, y))
    rows = round(11 * pct)
    if rows > 0:
        fill = s["cylinder_fill"].crop((0, 11 - rows, 5, 11))
        canvas.alpha_composite(fill, (x + 1, y + 2 + 11 - rows))
    if in_use:
        canvas.alpha_composite(s["mask"], (x - 8, y + 7))


def draw_spo2(canvas, s, x, y, pct, low=False):
    canvas.alpha_composite(s["spo2_background"], (x, y))
    w = round(54 * pct)
    if w > 0:
        f = s["spo2_fill_low" if low else "spo2_fill"].crop((0, 0, w, 3))
        canvas.alpha_composite(f, (x + 1, y + 1))


def sketch_scene(w, h):
    """Snowfield + sky backdrop and a hand-sketched hotbar/heart/hunger strip."""
    img = Image.new("RGBA", (w, h), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / h
        if t < 0.35:
            c = (int(120 + 80 * t), int(160 + 60 * t), int(215 + 20 * t))
        else:
            c = (236, 240, 246) if (y // 3) % 7 else (220, 226, 236)
        d.line([(0, y), (w, y)], fill=c + (255,))
    d.polygon([(0, int(h * 0.36)), (40, int(h * 0.22)), (75, int(h * 0.33)), (110, int(h * 0.18)),
               (150, int(h * 0.36))], fill=(232, 236, 244, 255))
    d.polygon([(170, int(h * 0.36)), (205, int(h * 0.26)), (240, int(h * 0.36))], fill=(90, 92, 100, 255))
    cx = w // 2
    hb_x, hb_y = cx - 91, h - 22
    # hotbar: 9 slots
    d.rectangle([hb_x, hb_y, hb_x + 181, hb_y + 21], fill=(30, 30, 30, 200), outline=(10, 10, 10, 255))
    for i in range(9):
        sx = hb_x + 1 + i * 20
        d.rectangle([sx, hb_y + 1, sx + 19, hb_y + 20], outline=(120, 120, 120, 255))
        d.rectangle([sx + 1, hb_y + 2, sx + 18, hb_y + 19], fill=(70, 70, 70, 170))
    d.rectangle([hb_x, hb_y, hb_x + 21, hb_y + 21], outline=(230, 230, 230, 255))
    # XP bar
    d.rectangle([hb_x, h - 29, hb_x + 181, h - 25], fill=(20, 20, 20, 255))
    d.rectangle([hb_x + 1, h - 28, hb_x + 60, h - 26], fill=(110, 200, 40, 255))
    # heart + hunger placeholders (simple sketched shapes, 9px tall rows)
    row_y = h - 39
    for i in range(10):
        x = hb_x + i * 8
        d.polygon([(x + 4, row_y + 8), (x, row_y + 3), (x + 1, row_y + 1), (x + 3, row_y + 1),
                   (x + 4, row_y + 2), (x + 5, row_y + 1), (x + 7, row_y + 1), (x + 8, row_y + 3)],
                  fill=(200, 30, 30, 255), outline=(30, 0, 0, 255))
        x2 = hb_x + 181 - 9 - i * 8
        d.ellipse([x2 + 1, row_y + 1, x2 + 7, row_y + 7], fill=(170, 100, 50, 255), outline=(40, 20, 0, 255))
    return img, hb_x, row_y


def hud_mockup(s, k=4):
    W, H = 260, 92
    panels = []
    for pct, low, label in ((1.0, False, "SpO2 100%"), (0.2, True, "SpO2 20% (danger)")):
        img, hb_x, row_y = sketch_scene(W, H)
        right = hb_x + 181
        # cylinders right-aligned above the hunger row, 2px apart
        cy = row_y - 16
        draw_cylinder(img, s, right - 7, cy, 1.0)
        draw_cylinder(img, s, right - 7 - 9, cy, 0.7, in_use=True)
        # SpO2 bar left of the cylinders, bottom-aligned with them
        draw_spo2(img, s, right - 7 - 9 - 10 - 56, cy + 9, pct, low)
        # a missing-cylinder slot example far left above the hearts
        img.alpha_composite(s["cylinder_missing"], (hb_x, cy))
        big = up(img, k)
        bd = ImageDraw.Draw(big)
        bd.text((8, 6), label + "  (GUI px x4)", fill=(10, 10, 10))
        bd.text(((right - 16) * k - 34, (cy - 4) * k - 8), "70%+mask  full", fill=(10, 10, 10))
        bd.text((hb_x * k, (cy - 4) * k - 8), "missing slot", fill=(10, 10, 10))
        bd.text(((right - 82) * k, (cy + 4) * k - 8), "SpO2", fill=(10, 10, 10))
        panels.append(big)
    out = Image.new("RGBA", (panels[0].width, panels[0].height * 2 + 8), (40, 40, 40, 255))
    out.alpha_composite(panels[0], (0, 0))
    out.alpha_composite(panels[1], (0, panels[0].height + 8))
    return out


def sprite_sheet(s, k=8):
    names = list(s)
    pad = 12
    cols_w = [s[n].width * k for n in names]
    W = pad + sum(cw + pad for cw in cols_w[:4])
    W = max(W, 56 * k + 2 * pad)
    H = pad + 14 * k + pad + 20 + (5 * k + pad + 20) * 3
    sheet = Image.new("RGBA", (W, H), (128, 128, 128, 255))
    d = ImageDraw.Draw(sheet)
    x = pad
    for n in names[:4]:
        sheet.alpha_composite(up(s[n], k), (x, pad))
        d.text((x, pad + 14 * k + 2), n, fill=(20, 20, 20))
        x += s[n].width * k + pad
    y = pad + 14 * k + pad + 20
    for n in names[4:]:
        sheet.alpha_composite(up(s[n], k), (pad, y))
        d.text((pad, y + s[n].height * k + 2), n, fill=(20, 20, 20))
        y += 5 * k + pad + 20
    return sheet


def main():
    s = build_all()
    for n, img in s.items():
        save_texture(img, OX + n + ".png")
    save_preview(hud_mockup(s), "hud_mockup.png")
    save_preview(sprite_sheet(s), "hud_sprites.png")


if __name__ == "__main__":
    main()
