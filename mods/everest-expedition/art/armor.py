"""Down-suit armor layers (64x32, vanilla humanoid armor UV layout).

Box UV layout (same as vanilla ModelPart.Cube), for a box at texOffs (u, v)
with size (w, h, d):

    top    (u+d,       v)    w x d   last row touches the front face
    bottom (u+d+w,     v)    w x d
    right  (u,         v+d)  d x h   (entity's right / -X; last column = front)
    front  (u+d,       v+d)  w x h
    left   (u+d+w,     v+d)  d x h   (entity's left / +X; first column = front)
    back   (u+2d+w,    v+d)  w x h

HumanoidModel boxes on a 64x32 sheet:
    head 8x8x8 @ (0,0)   hat 8x8x8 @ (32,0) (left empty)
    body 8x12x4 @ (16,16)   arms 4x12x4 @ (40,16)   legs 4x12x4 @ (0,16)
Left arm / left leg reuse the same region mirrored, so limb faces are drawn
for the right limb: "right" = outer side, "left" = inner side.

layer_1 (outer, CubeDeformation 1.0): helmet = head; chestplate = body + arms;
boots = legs.  layer_2 (inner, CubeDeformation 0.5): leggings = body + legs.
"""
from PIL import Image

from common import grid, save_texture
from palettes import RED, BLACK, FUR, AMBER, YELLOW, merge

PAL = merge(RED, BLACK, FUR, AMBER, YELLOW)

HEAD = (0, 0, 8, 8, 8)
HAT = (32, 0, 8, 8, 8)
BODY = (16, 16, 8, 12, 4)
ARM = (40, 16, 4, 12, 4)
LEG = (0, 16, 4, 12, 4)


def face_rects(u, v, w, h, d):
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + d + w + d, v + d, w, h),
    }


def mirror(rows):
    return [r[::-1] for r in rows]


def place_box(img, box, faces, name):
    rects = face_rects(*box)
    px = img.load()
    for fname, rows in faces.items():
        x0, y0, fw, fh = rects[fname]
        part = grid(rows, PAL, f"{name}.{fname}", (fw, fh))
        ppx = part.load()
        for y in range(fh):
            for x in range(fw):
                c = ppx[x, y]
                if c[3]:
                    px[x0 + x, y0 + y] = c


# ==========================================================================
# LAYER 1: hood (helmet), jacket (chestplate), boots
# ==========================================================================

# Hood. Ring baffles run around the face tunnel: on the top they are rows
# (row 7 = front edge), on the sides they are columns. Goggle strap wraps the
# sides and back at rows 3-4. Fur ruff on the front face and the front edge
# of the side faces; the front face opening is transparent so the face shows.
HOOD_TOP = [
    "45566554",
    "22222222",
    "45566554",
    "44455444",
    "22222222",
    "45566554",
    "34444443",
    "gGgFgGgf",
]
HOOD_FRONT = [
    "gGgGggFf",
    "GgFgGgFf",
    "kJJKKKKk",
    "kYyyyyAk",
    "kyAkkAak",
    "g......f",
    "F......f",
    "gFgGgFff",
]
HOOD_RIGHT = [  # last column = front (fur ruff edge)
    "5265265G",
    "4254254g",
    "4254254F",
    "KKKKKKKK",
    "kkkkkkkk",
    "4254254g",
    "3243243F",
    "3123123f",
]
HOOD_BACK = [
    "55666655",
    "22222222",
    "45555554",
    "KKKKKKKK",
    "kkkkkkkk",
    "44444444",
    "22222222",
    "33333333",
]

# Jacket: black shoulder yoke, horizontal baffles, zip, black hem.
# Arms share the body's row pattern so seams line up across the shoulder.
JACKET_TOP = [
    "KJJJJJJK",
    "KJjjjjJK",
    "KJJJJJJK",
    "kKKKKKKk",
]
JACKET_BOTTOM = ["KKKKKKKK"] * 4
JACKET_FRONT = [
    "KJJjJJJK",
    "JjjjjjjJ",
    "KKKkKKKK",
    "565k5665",
    "444k4444",
    "222k2222",
    "565k5665",
    "444k4444",
    "222k2222",
    "565k5665",
    "444k4444",
    "KKKkKKKK",
]
JACKET_BACK = [
    "KJJJJJJK",
    "JjjjjjjJ",
    "KKKKKKKK",
    "56655665",
    "44444444",
    "22222222",
    "56655665",
    "44444444",
    "22222222",
    "56655665",
    "44444444",
    "KKKKKKKK",
]
JACKET_SIDE = [
    "KJJK",
    "JjjJ",
    "KKKK",
    "5665",
    "4444",
    "2222",
    "5665",
    "4444",
    "2222",
    "5665",
    "4444",
    "KKKK",
]
SLEEVE_TOP = ["KJJK", "JjjJ", "JjjJ", "KJJK"]
SLEEVE_BOTTOM = ["kKKk", "KkkK", "KkkK", "kKKk"]  # mitt
SLEEVE = [
    "KJJK",
    "JjjJ",
    "KKKK",
    "5665",
    "4444",
    "2222",
    "5665",
    "4444",
    "2222",
    "5665",
    "KJJK",  # expedition mitt / cuff
    "kKKk",
]

# Boots: bottom 6 rows of the leg box (upper part transparent).
_EMPTY6 = ["...."] * 6
BOOT_OUTER = _EMPTY6 + [  # last column = front
    "KJJK",
    "blll",
    "sbbl",
    "ssbb",
    "KKKJ",
    "kkkk",
]
BOOT_FRONT = _EMPTY6 + [  # gaiter zip down column 1
    "KJJK",
    "hkll",
    "lkbb",
    "lkbs",
    "JKKK",
    "kkkk",
]
BOOT_INNER = _EMPTY6 + [  # first column = front
    "KJJK",
    "llbb",
    "bbbs",
    "bbss",
    "KKKK",
    "kkkk",
]
BOOT_BACK = _EMPTY6 + [
    "KJJK",
    "bllb",
    "sbbs",
    "ssss",
    "KKKK",
    "kkkk",
]
BOOT_SOLE = ["kKkK", "KkKk", "kKkK", "KkKk"]

# ==========================================================================
# LAYER 2: bib pants (leggings)
# ==========================================================================
# Body box = bib + shoulder straps. Row 11 of the body and row 0 of the legs
# overlap in the same plane (y 11.5-12.5), so both are flat base red to keep
# any z-fighting invisible.
BIB_TOP = [
    ".k....k.",
    ".k....k.",
    ".k....k.",
    ".k....k.",
]
BIB_BOTTOM = ["22222222"] * 4
BIB_FRONT = [
    ".k....k.",
    ".k....k.",
    ".j....j.",
    ".k....k.",
    "kkkkkkkk",
    "56655665",
    "44444444",
    "22222222",
    "56655665",
    "44444444",
    "22222222",
    "44444444",
]
BIB_BACK = [
    ".k....k.",
    ".k....k.",
    ".k....k.",
    ".k....k.",
    "kkkkkkkk",
    "56655665",
    "44444444",
    "22222222",
    "56655665",
    "44444444",
    "22222222",
    "44444444",
]
BIB_SIDE = [
    "....",
    "....",
    "....",
    "....",
    "kkkk",
    "5665",
    "4444",
    "2222",
    "5665",
    "4444",
    "2222",
    "4444",
]
PANT_LEG = [
    "4444",
    "4444",
    "2222",
    "5665",
    "4444",
    "2222",
    "5665",
    "4444",
    "2222",
    "5665",
    "4444",
    "KJJK",
]
# Outer side carries a full-length side zip (column 1).
PANT_OUTER = [
    "4k44",
    "4k44",
    "2k22",
    "5k65",
    "4k44",
    "2k22",
    "5k65",
    "4k44",
    "2k22",
    "5k65",
    "4k44",
    "KkJK",
]
PANT_CUFF = ["kKKk", "KkkK", "KkkK", "kKKk"]


def build_layer1():
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    place_box(img, HEAD, {
        "top": HOOD_TOP,
        "front": HOOD_FRONT,
        "right": HOOD_RIGHT,
        "left": mirror(HOOD_RIGHT),
        "back": HOOD_BACK,
    }, "hood")
    place_box(img, BODY, {
        "top": JACKET_TOP,
        "bottom": JACKET_BOTTOM,
        "front": JACKET_FRONT,
        "right": JACKET_SIDE,
        "left": mirror(JACKET_SIDE),
        "back": JACKET_BACK,
    }, "jacket")
    place_box(img, ARM, {
        "top": SLEEVE_TOP,
        "bottom": SLEEVE_BOTTOM,
        "right": SLEEVE,
        "front": SLEEVE,
        "left": SLEEVE,
        "back": SLEEVE,
    }, "sleeve")
    place_box(img, LEG, {
        "bottom": BOOT_SOLE,
        "right": BOOT_OUTER,
        "front": BOOT_FRONT,
        "left": BOOT_INNER,
        "back": BOOT_BACK,
    }, "boot")
    return img


def build_layer2():
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    place_box(img, BODY, {
        "top": BIB_TOP,
        "bottom": BIB_BOTTOM,
        "front": BIB_FRONT,
        "right": BIB_SIDE,
        "left": BIB_SIDE,
        "back": BIB_BACK,
    }, "bib")
    place_box(img, LEG, {
        "bottom": PANT_CUFF,
        "right": PANT_OUTER,
        "front": PANT_LEG,
        "left": PANT_LEG,
        "back": PANT_LEG,
    }, "pant_leg")
    return img


def main():
    l1 = build_layer1()
    l2 = build_layer2()
    save_texture(l1, "models/armor/down_suit_layer_1.png")
    save_texture(l2, "models/armor/down_suit_layer_2.png")
    return l1, l2


if __name__ == "__main__":
    main()
