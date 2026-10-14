"""Inventory icons for dyed suit pieces, and the item models that switch to them.

A dyed piece (minecraft:dyed_color) shows a two-layer icon: layer 0 is the fabric in greys, which the item colour
multiplies by the dye colour (like leather armour); layer 1 is everything that keeps its colour (outline, zips, mitts,
harness, goggles, soles). The greys are the painted gold ramp's luma relative to its base, so a piece dyed the base
colour reads like the painted icon. The undyed icons are the painted ones (suit3d_textures.py --icons); the item
model switches with the everest_expedition:dyed predicate.

    python suit3d_dyed_icons.py
"""
import json
import os

from PIL import Image

import suit3d_colours as C
import suit3d_textures as T
from common import MOD_DIR, save_preview, save_texture, up

MODELS = os.path.join(MOD_DIR, "src", "client", "resources", "assets", "everest_expedition", "models", "item")
GOLD = ["613805", "9c630b", "d28c15", "f5b526", "ffcb47", "ffe596"]
YOKE = ["55290a", "7f4513", "a65f1f", "c97a2c", "dd9442", "efb062"]


def luma(hexrgb):
    r, g, b = C.hexrgb(hexrgb)
    return 0.299 * r + 0.587 * g + 0.114 * b


BASE = luma(GOLD[3])


def grey(cls, level):
    ramp = GOLD if cls == "main" else YOKE
    v = min(255, round(255 * luma(ramp[level]) / BASE))
    return v, v, v, 255


def char_grid(rows, size=(16, 16), at=(0, 0), into=None):
    grid = into or [["." for _ in range(size[0])] for _ in range(size[1])]
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch not in ". ":
                grid[at[1] + y][at[0] + x] = ch
    return grid


def icon_chars():
    boots = char_grid(["".join(T._REAR.get(ch, ch) for ch in row) for row in T.BOOT_ICON], at=(5, 0))
    boots = char_grid(T.BOOT_ICON, at=(0, 4), into=boots)
    return {"down_hood": char_grid(T.HOOD_ICON), "down_jacket": char_grid(T.JACKET_ICON),
            "down_pants": char_grid(T.PANTS_ICON), "down_boots": boots}


def tint(name, ch):
    """(class, level) for a pixel of this icon that takes the dye, or None."""
    if name == "down_hood":
        return ("main", C.HOOD_LEVELS[ch]) if ch in C.HOOD_LEVELS and ch != "k" else None
    if ch in C.GOLD_RAMP:
        return "main", C.GOLD_RAMP.index(ch)
    if name == "down_jacket" and ch in C.YOKE_RAMP:
        return "yoke", C.YOKE_RAMP.index(ch)
    if name == "down_boots" and ch in C.BOOT_RAMP:
        return "main", C.BOOT_RAMP.index(ch)
    return None


def build():
    out = {}
    for name, grid in icon_chars().items():
        greys = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        overlay = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        for y, row in enumerate(grid):
            for x, ch in enumerate(row):
                if ch in ". ":
                    continue
                t = tint(name, ch)
                if t is None:
                    overlay.putpixel((x, y), T.PAL[ch])
                else:
                    greys.putpixel((x, y), grey(*t))
        out[name] = (greys, overlay)
    return out


def dyed_preview(icons, k=6):
    """Each piece undyed and in every colourway, as the inventory shows them."""
    names = list(icons)
    ways = [w for w in C.COLOURWAYS if w[2] is not None]
    sheet = Image.new("RGBA", ((len(ways) + 1) * 16 * k, len(names) * 16 * k), (139, 139, 139, 255))
    for r, name in enumerate(names):
        painted = Image.open(os.path.join(MOD_DIR, "src", "client", "resources", "assets", "everest_expedition",
                                          "textures", "item", f"{name}.png")).convert("RGBA")
        sheet.alpha_composite(up(painted, k), (0, r * 16 * k))
        greys, overlay = icons[name]
        for c, (_, _, rgb, _) in enumerate(ways):
            dyed = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
            tr, tg, tb = C.from_int(rgb)
            px = greys.load()
            dpx = dyed.load()
            for y in range(16):
                for x in range(16):
                    g = px[x, y]
                    if g[3]:
                        dpx[x, y] = (g[0] * tr // 255, g[1] * tg // 255, g[2] * tb // 255, 255)
            dyed.alpha_composite(overlay)
            sheet.alpha_composite(up(dyed, k), ((c + 1) * 16 * k, r * 16 * k))
    return sheet


def main():
    icons = build()
    for name, (greys, overlay) in icons.items():
        save_texture(greys, f"item/{name}_dyed.png")
        save_texture(overlay, f"item/{name}_dyed_overlay.png")
        with open(os.path.join(MODELS, f"{name}_dyed.json"), "w", newline="\n") as f:
            json.dump({"parent": "minecraft:item/generated",
                       "textures": {"layer0": f"everest_expedition:item/{name}_dyed",
                                    "layer1": f"everest_expedition:item/{name}_dyed_overlay"}}, f, indent=2)
            f.write("\n")
        with open(os.path.join(MODELS, f"{name}.json"), "w", newline="\n") as f:
            json.dump({"parent": "minecraft:item/generated",
                       "textures": {"layer0": f"everest_expedition:item/{name}"},
                       "overrides": [{"predicate": {"everest_expedition:dyed": 1},
                                      "model": f"everest_expedition:item/{name}_dyed"}]}, f, indent=2)
            f.write("\n")
    save_preview(dyed_preview(icons), "colours/icons_dyed.png")


if __name__ == "__main__":
    main()
