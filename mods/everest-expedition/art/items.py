"""16x16 item icons: down suit pieces (from suit3d_textures), oxygen cylinder, and the everest-radio radio.

Each icon is an explicit pixel grid. Keys are defined in palettes.py.
Run directly to write the textures and the item previews.
"""
import os

from PIL import Image, ImageDraw

from common import (grid, save_texture, save_preview, up, work_view,
                    MOD_DIR)
import suit3d_textures
from palettes import BLACK, ORANGE, SILVER, merge, rgb

# The radio belongs to the separate everest-radio mod.
RADIO_PATH = os.path.join(
    os.path.dirname(MOD_DIR), "everest-radio", "src", "main", "resources", "assets",
    "everest_radio", "textures", "item", "radio.png",
)

# --------------------------------------------------------------------------
# Oxygen cylinder: slim orange bottle, silver valve + regulator, hose, mask
# --------------------------------------------------------------------------
CYLINDER = [
    "....xxx.........",
    "...xwWEx........",
    "....xEx.........",
    "..xxWEexxx......",
    "..xWEEeeEexkk...",
    "..xxeeexxx.kJk..",
    "...OHLBO....kJk.",
    "..OHLBBSO....kJk",
    "..OHLBBSO....kJk",
    "..OHLBBSO....kJk",
    "..OHLBBSO...xxJk",
    "..OHLBBSO..xWEex",
    "..OHLBBSO.xWkkex",
    "..OLBBBSO.xEkkex",
    "..OSBBSDO..xeex.",
    "...OOOOO....xx..",
]

# --------------------------------------------------------------------------
# Expedition two-way radio (everest-radio mod): chunky black body, rubber
# antenna, speaker grille, orange channel knob as the one accent.
# --------------------------------------------------------------------------
RADIO = [
    ".....k..........",
    "....kjk.........",
    "....kJk.........",
    "....kJk.OOOO....",
    "....kJk.OHLO....",
    "....kKk.OBSO....",
    "...kkkkkkkkkk...",
    "...kijjjjjjKk...",
    "...kjkkkkkkKk...",
    "..kijJjjjjJKk...",
    "..kijkkkkkkKk...",
    "..kijJjjjjJKk...",
    "...kjkkkkkkKk...",
    "...kJJiJiJJKk...",
    "...kKKKKKKKKk...",
    "...kkkkkkkkkk...",
]

CYL_PAL = merge(ORANGE, SILVER, BLACK)
RADIO_PAL = merge(BLACK, ORANGE, {"i": rgb("8b8a96")})

def build_all():
    # The down suit icons are drawn with the 3D suit's palette in suit3d_textures.py.
    icons = suit3d_textures.build_icons()
    icons["oxygen_cylinder"] = grid(CYLINDER, CYL_PAL, "oxygen_cylinder", (16, 16))
    icons["radio"] = grid(RADIO, RADIO_PAL, "radio", (16, 16))
    return icons


def contact_sheet(icons, k=8):
    """Items at 8x on mid grey, plus a 2x strip in vanilla-style inventory slots."""
    names = list(icons)
    pad = 16
    cell = 16 * k
    w = pad + len(names) * (cell + pad)
    h = pad + cell + pad + 50 + pad
    sheet = Image.new("RGBA", (w, h), (128, 128, 128, 255))
    d = ImageDraw.Draw(sheet)
    for i, n in enumerate(names):
        x = pad + i * (cell + pad)
        sheet.alpha_composite(up(icons[n], k), (x, pad))
        d.text((x, pad + cell + 4), n, fill=(20, 20, 20, 255))
    y = pad + cell + 24
    for i, n in enumerate(names):
        x = pad + i * 40
        d.rectangle([x, y, x + 35, y + 35], fill=(139, 139, 139, 255))
        d.line([(x, y), (x + 35, y)], fill=(55, 55, 55, 255))
        d.line([(x, y), (x, y + 35)], fill=(55, 55, 55, 255))
        d.line([(x + 35, y), (x + 35, y + 35)], fill=(255, 255, 255, 255))
        d.line([(x, y + 35), (x + 35, y + 35)], fill=(255, 255, 255, 255))
        sheet.alpha_composite(up(icons[n], 2), (x + 2, y + 2))
    return sheet


def main():
    icons = build_all()
    for n, img in icons.items():
        if n == "radio":
            os.makedirs(os.path.dirname(RADIO_PATH), exist_ok=True)
            img.save(RADIO_PATH, "PNG")
            print(f"  wrote {RADIO_PATH} (16x16)")
        else:
            save_texture(img, f"item/{n}.png")
    save_preview(contact_sheet(icons), "items_contact_sheet.png")
    for n, img in icons.items():
        save_preview(work_view(img, 24), f"_work_{n}.png")


if __name__ == "__main__":
    main()
