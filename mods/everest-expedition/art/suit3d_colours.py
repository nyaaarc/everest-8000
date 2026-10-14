"""Suit colours: the curated expedition colourways, the luminance ramps that turn any dye colour into down fabric,
and the dye map that says which suit texels take a colour.

A dyed suit piece (minecraft:dyed_color) is drawn from a copy of the suit texture in which every tintable texel is
repainted from a six-step ramp built from the dye colour: outline, seam, shadow, base, light, sheen. The ramp is
built in OKLCH and fitted to the hand-painted gold of the default suit, so f5b526 reproduces it: shadows darken
multiplicatively and turn toward the "dark" hue pole (blue-violet), highlights lift by a capped step and turn
toward the "light" pole (yellow), and chroma falls off at both ends. A second, deeper ramp (the yoke) is built the
same way from a darker, warmer variant of the colour, for the shoulder yoke, collar and cuffs.

Trims stay as painted: zips, mitts, harness, logo, buckles, goggles, fur and the boots' soles. The hood's black
quilting is tintable too (an undyed hood stays black; a dyed one takes its colour), and so is the boots' shell.

dev.everest8000.expedition.client.suit.SuitRamps ports ramp() and yoke_base() exactly; its test checks the two
agree on the values written by `python suit3d_colours.py --java`.

    python suit3d_colours.py          # write the dye map texture
    python suit3d_colours.py --java   # also write Colourway.java and the Java test's expected ramps
"""
import math
import os
import sys

import numpy as np

# ---------------------------------------------------------------------------
# OKLab
# ---------------------------------------------------------------------------


def _to_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _to_srgb(c):
    v = 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055
    return v * 255.0


def oklch(rgb):
    r, g, b = (_to_lin(x) for x in rgb)
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    l, m, s = (math.copysign(abs(x) ** (1 / 3), x) for x in (l, m, s))
    L = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
    a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
    bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    return L, math.hypot(a, bb), math.degrees(math.atan2(bb, a)) % 360.0


def _lin_rgb(L, C, h):
    a = C * math.cos(math.radians(h))
    b = C * math.sin(math.radians(h))
    l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3
    m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3
    s = (L - 0.0894841775 * a - 1.2914855480 * b) ** 3
    return (4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)


def _in_gamut(lin, eps=1e-6):
    return all(-eps <= v <= 1 + eps for v in lin)


def from_oklch(L, C, h):
    """sRGB (0..255 ints) for an OKLCH colour, chroma reduced at the same L and h until it fits."""
    L = min(1.0, max(0.0, L))
    lin = _lin_rgb(L, C, h)
    if not _in_gamut(lin):
        lo, hi = 0.0, C
        for _ in range(24):
            mid = (lo + hi) / 2
            if _in_gamut(_lin_rgb(L, mid, h)):
                lo = mid
            else:
                hi = mid
        lin = _lin_rgb(L, lo, h)
    return tuple(int(math.floor(_to_srgb(min(1.0, max(0.0, v))) + 0.5)) for v in lin)


# ---------------------------------------------------------------------------
# Ramps
# ---------------------------------------------------------------------------
LEVELS = ["outline", "seam", "shadow", "base", "light", "sheen"]
SHADOW_L = (0.47, 0.676, 0.855)           # shadows: L times these (outline, seam, shadow)
LIGHT_L = ((0.29, 0.07), (0.61, 0.14))    # highlights: L + min(k * (1 - L), cap) (light, sheen)
CHROMA = (0.52, 0.73, 0.91, 1.0, 0.97, 0.64)
HUE_SHIFT = (17.0, 11.8, 8.7, 0.0, 5.2, 10.6)  # degrees toward the dark pole (shadows) or the light pole (lights)
DARK_POLE, LIGHT_POLE = 290.0, 100.0
# The yoke: a deeper, warmer variant of the colour, then ramped the same way.
YOKE_L, YOKE_C, YOKE_HUE = 0.80, 0.84, 20.6


def _toward(h, pole, amount):
    """Turn hue h toward pole along the shorter way round, by at most amount, never past it."""
    d = (pole - h + 540.0) % 360.0 - 180.0
    step = min(amount, abs(d))
    return (h + math.copysign(step, d)) % 360.0


def ramp(rgb):
    """Six sRGB colours (outline .. sheen) for a dye colour, which becomes the base."""
    L, C, h = oklch(rgb)
    out = []
    for i in range(6):
        if i < 3:
            Li, hi = L * SHADOW_L[i], _toward(h, DARK_POLE, HUE_SHIFT[i])
        elif i == 3:
            Li, hi = L, h
        else:
            k, cap = LIGHT_L[i - 4]
            Li, hi = L + min(k * (1.0 - L), cap), _toward(h, LIGHT_POLE, HUE_SHIFT[i])
        out.append(from_oklch(Li, C * CHROMA[i], hi))
    out[3] = tuple(rgb)  # the dye colour itself, exactly
    return out


def yoke_base(rgb):
    L, C, h = oklch(rgb)
    return from_oklch(L * YOKE_L, C * YOKE_C, _toward(h, DARK_POLE, YOKE_HUE))


def ramps(rgb):
    """{class: six colours}: 'main' from the dye colour, 'yoke' from its deeper variant."""
    return {"main": ramp(rgb), "yoke": ramp(yoke_base(rgb))}


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def to_int(rgb):
    return (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]


def from_int(v):
    return ((v >> 16) & 255, (v >> 8) & 255, v & 255)


# ---------------------------------------------------------------------------
# Curated colourways: real high-altitude down suit colours. The first is the default suit (undyed: the gold suit
# with its black hood, the red pack). Each has a suit colour (dyed_color of the jacket, hood, pants and boots) and a
# pack colour (dyed_color of the backpack, whose grey shell is multiplied by it). Order = picker order; ids are
# saved nowhere (only the colours are), so they can be renamed freely.
# ---------------------------------------------------------------------------
COLOURWAYS = [
    # id, name, suit rgb (None = undyed default), pack rgb
    ("gold", "Summit gold", None, 0xD9A21B),
    ("red", "Expedition red", 0xC42A24, 0xCC3636),
    ("orange", "Blaze orange", 0xF06A1A, 0xE8742A),
    ("blue", "Cobalt blue", 0x2B5CC4, 0x2E5FA8),
    ("teal", "Glacier teal", 0x1F9A9A, 0x2A9A96),
    ("green", "Alpine green", 0x3E8A3A, 0x4A8A3E),
    ("purple", "Himalayan violet", 0x6A3FB0, 0x7048B0),
    ("black", "Phantom black", 0x2C2D33, 0x34353C),
]
DEFAULT_SUIT_RGB = 0xF5B526   # what the undyed suit's ramp is built from (its 'base' level)
DEFAULT_PACK_RGB = 0xCC3636   # the undyed pack (suit3d_model.PACK_DEFAULT_RGB)

# ---------------------------------------------------------------------------
# Dye map: which suit texels take which ramp, at which level.
# ---------------------------------------------------------------------------
GOLD_RAMP = "123456"
YOKE_RAMP = "aAfFvV"
BOOT_RAMP = "qdsulh"
HOOD_LEVELS = {"k": 0, "K": 1, "J": 2, "j": 3, "i": 4}  # the hood's black quilting, as ramp levels
# Boxes whose black is the hood's fabric (tintable), not trim.
HOOD_BOXES = {"hood", "ring_top", "ring_corner", "ring_side", "hood_back", "hood_back_top", "crown", "nape",
              "rim_top", "rim_side", "brow_upper", "brow_lower", "bunch", "drape", "tip"}
BOOT_BOXES = {"boot", "toe"}
CLASSES = ["main", "yoke"]


def tint_of(key, ch):
    """(class, level) for a texel painted with palette char ch on box key, or None if it keeps its colour."""
    if ch in GOLD_RAMP:
        return "main", GOLD_RAMP.index(ch)
    if ch in YOKE_RAMP:
        return "yoke", YOKE_RAMP.index(ch)
    if ch in BOOT_RAMP and key in BOOT_BOXES:
        return "main", BOOT_RAMP.index(ch)
    if ch in HOOD_LEVELS and key in HOOD_BOXES:
        return "main", HOOD_LEVELS[ch]
    return None


def dye_map(canvas):
    """RGBA image the size of the suit texture: alpha 255 where a texel tints (red = level 0..5, green = class
    0 main / 1 yoke), alpha 0 where it keeps the painted colour."""
    from PIL import Image
    tw, th = canvas.img.size
    img = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
    px = img.load()
    for (x, y), (key, ch) in canvas.cells.items():
        t = tint_of(key, ch)
        if t is not None:
            px[x, y] = (t[1], CLASSES.index(t[0]), 0, 255)
    return img


def recolour(base, dmap, rgb):
    """base: the suit texture (H, W, 4 float array); dmap: the dye map (same, uint8); rgb: the dye colour."""
    out = base.copy()
    rs = ramps(rgb)
    table = np.array([rs[c] for c in CLASSES], np.float32)  # (class, level, 3)
    mask = dmap[..., 3] == 255
    lv = dmap[..., 0][mask].astype(int)
    cl = dmap[..., 1][mask].astype(int)
    out[mask, :3] = table[cl, lv]
    return out


def multiply(base, dmap, rgb):
    """The flat alternative, for comparison: tintable texels as greys (their luminance, sheen = white) times rgb."""
    out = base.copy()
    mask = dmap[..., 3] == 255
    lum = (base[..., :3] @ np.array([0.299, 0.587, 0.114], np.float32))
    ref = lum[mask].max() or 1.0
    grey = np.clip(lum / ref, 0, 1)
    out[mask, :3] = grey[mask, None] * np.array(rgb, np.float32)[None, :]
    return out


# ---------------------------------------------------------------------------
# Java test fixture: expected ramps for a spread of colours (the curated ones, vanilla dye colours, extremes).
# ---------------------------------------------------------------------------
FIXTURE_COLOURS = sorted({c for _, _, c, _ in COLOURWAYS if c is not None} | {
    DEFAULT_SUIT_RGB, 0xB02E26, 0xF9801D, 0xFED83D, 0x80C71F, 0x5E7C16, 0x169C9C, 0x3AB3DA, 0x3C44AA, 0x8932B8,
    0xC74EBD, 0xF38BAA, 0x835432, 0x9D9D97, 0x474F52, 0x1D1D21, 0xF9FFFE, 0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00,
    0x0000FF, 0x123456, 0x7F7F7F})


def java_fixture():
    lines = ["# colour: main ramp (6), yoke ramp (6), as rrggbb; written by art/suit3d_colours.py --java"]
    for c in FIXTURE_COLOURS:
        rs = ramps(from_int(c))
        lines.append(f"{c:06x}: " + " ".join(f"{to_int(x):06x}" for x in rs["main"]) + " | "
                     + " ".join(f"{to_int(x):06x}" for x in rs["yoke"]))
    return "\n".join(lines) + "\n"


def java_colourways():
    """dev.everest8000.expedition.gear.Colourway, generated from COLOURWAYS."""
    out = ["package dev.everest8000.expedition.gear;", "",
           "import net.minecraft.network.chat.Component;", "",
           "import java.util.Locale;", "",
           "/**",
           " * The curated suit colourways offered on the picker: real high-altitude down suit colours. Each gives the",
           " * suit's dye colour (jacket, hood and pants; -1 for the undyed default suit) and the matching pack colour.",
           " *",
           " * <p>Generated by art/suit3d_colours.py from COLOURWAYS; change them there and regenerate.",
           " */",
           "public enum Colourway {"]
    rows = []
    for cid, name, suit, pack in COLOURWAYS:
        value = "-1" if suit is None else f"0x{suit:06X}"
        rows.append(f"    /** {name}. */\n    {cid.upper()}({value}, 0x{pack:06X})")
    out.append(",\n".join(rows) + ";")
    out += ["",
            "    /** The suit colour the undyed suit is painted in (its ramp's base), for swatches. */",
            f"    public static final int DEFAULT_SUIT_RGB = 0x{DEFAULT_SUIT_RGB:06X};",
            "    /** The undyed pack's colour. */",
            f"    public static final int DEFAULT_PACK_RGB = 0x{DEFAULT_PACK_RGB:06X};",
            "    private static final Colourway[] VALUES = values();", "",
            "    /** Dye colour of the suit pieces, or -1 for the undyed default suit. */",
            "    public final int suitRgb;",
            "    /** Dye colour of the pack. */",
            "    public final int packRgb;", "",
            "    Colourway(int suitRgb, int packRgb) {",
            "        this.suitRgb = suitRgb;",
            "        this.packRgb = packRgb;",
            "    }", "",
            "    public String id() {",
            "        return name().toLowerCase(Locale.ROOT);",
            "    }", "",
            "    public Component displayName() {",
            "        return Component.translatable(\"colourway.everest_expedition.\" + id());",
            "    }", "",
            "    /** The suit colour as shown on a swatch: the undyed suit's gold for the default. */",
            "    public int suitSwatch() {",
            "        return suitRgb < 0 ? DEFAULT_SUIT_RGB : suitRgb;",
            "    }", "",
            "    /** The colourway with this id, or null. */",
            "    public static Colourway byId(String id) {",
            "        for (Colourway c : VALUES) if (c.id().equals(id)) return c;",
            "        return null;",
            "    }",
            "}", ""]
    return "\n".join(out)


def lang_entries():
    return {f"colourway.everest_expedition.{cid}": name for cid, name, _, _ in COLOURWAYS}


def main():
    import suit3d_textures as T
    from common import save_texture, MOD_DIR
    canvas = T.paint_suit_canvas()
    save_texture(dye_map(canvas), "models/armor/down_suit_3d_dye.png")
    if "--java" in sys.argv:
        path = os.path.join(MOD_DIR, "src", "main", "java", "dev", "everest8000", "expedition", "gear", "Colourway.java")
        with open(path, "w", newline="\n") as f:
            f.write(java_colourways())
        print(f"  wrote {path}")
        path = os.path.join(MOD_DIR, "src", "test", "resources", "suit_ramps_expected.txt")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", newline="\n") as f:
            f.write(java_fixture())
        print(f"  wrote {path}")


if __name__ == "__main__":
    main()
