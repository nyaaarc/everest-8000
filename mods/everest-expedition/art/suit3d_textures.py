"""Textures for the 3D down suit (128x128) and the oxygen mask (64x32), plus the four suit item icons.

Colours follow the summit photo: a gold North-Face-style down suit with a darker caramel yoke and collar,
black hood and mitts, mirrored orange goggles, black pack straps, a black harness with blue webbing, yellow
8000 m boots with black soles, and a black mask with a clear regulator and a red line down the chest.

Every face is painted for the UV layout that suit3d_model.py packs, so the geometry can move without
touching this file unless a box changes size. Face grids follow ModelPart.Cube orientation:
    top/bottom   last row touches the front, first column is the wearer's right
    right (-X)   last column is the front     left (+X)  first column is the front
    front        first column is the wearer's right
    back         first column is the wearer's left (as seen from behind, left to right)
Left limbs use mirror(), so limb faces are drawn for the right limb ("right" = outer side).

    python suit3d_textures.py           # write both textures + preview sheets
    python suit3d_textures.py --icons   # also overwrite the four down suit item icons
"""
import sys

from PIL import Image, ImageDraw

import suit3d_model as M
from common import grid, rgb, save_texture, save_preview, up, check_symmetric

# ---------------------------------------------------------------------------
# Palette (one character per colour, '.' transparent)
# ---------------------------------------------------------------------------
GOLD = {
    "1": rgb("613805"),  # outline / deepest crease
    "2": rgb("9c630b"),  # baffle seam
    "3": rgb("d28c15"),  # shadow
    "4": rgb("f5b526"),  # base
    "5": rgb("ffcb47"),  # light
    "6": rgb("ffe596"),  # sheen
}
YOKE = {  # darker caramel-orange yoke and collar, as on the summit suit's shoulders
    "a": rgb("55290a"),
    "A": rgb("7f4513"),
    "f": rgb("a65f1f"),
    "F": rgb("c97a2c"),
    "v": rgb("dd9442"),
    "V": rgb("efb062"),
}
BLACK = {
    "k": rgb("121115"),
    "K": rgb("1f1e24"),
    "J": rgb("2e2d35"),
    "j": rgb("42414b"),
    "i": rgb("5f5e6a"),
}
BLUE = {  # harness webbing
    "b": rgb("163877"),
    "B": rgb("275fc0"),
    "c": rgb("4b8ae6"),
    "C": rgb("9ac6ff"),
}
LENS = {  # mirrored orange goggle lens
    "r": rgb("6e1006"),
    "R": rgb("bd230d"),
    "o": rgb("ee4813"),
    "O": rgb("ff861b"),
    "y": rgb("ffc23e"),
    "Y": rgb("fff0b4"),
}
SILVER = {
    "x": rgb("32363c"),
    "e": rgb("6d727b"),
    "E": rgb("a3a8b0"),
    "W": rgb("d6dae0"),
    "w": rgb("f6f8fa"),
}
BOOT = {  # 8000 m boot lemon yellow
    "q": rgb("5a3f06"),
    "d": rgb("9a6c0a"),
    "s": rgb("cc9614"),
    "u": rgb("f2c428"),
    "l": rgb("ffdf5e"),
    "h": rgb("fff3a8"),
}
CLEAR = {  # regulator dome
    "g": rgb("4f5c66"),
    "G": rgb("8ea4b1"),
    "n": rgb("c9dde8"),
    "N": rgb("ffffff"),
}
RED = {"m": rgb("7e160c"), "M": rgb("d23a1e")}
PACK_SHELL = {  # greys for the pack shell, tinted at render time by the pack's colour (red by default)
    "7": rgb("6e6e6e"),
    "8": rgb("a6a6a6"),
    "9": rgb("d2d2d2"),
    "0": rgb("f0f0f0"),
}
FUR = {  # parka ruff: dark roots to pale tips
    "Q": rgb("4a3b2c"),
    "X": rgb("8a7660"),
    "Z": rgb("c4b496"),
    "H": rgb("ece4d2"),
}
WEBBING = {  # grey compression straps
    "t": rgb("2f3135"),
    "T": rgb("5f626a"),
    "U": rgb("9497a0"),
}
KNIT = {  # Sherpa knit hat: Himalayan red wool, a cream pattern band, blue stitches
    "D": rgb("6a1214"),
    "P": rgb("a82220"),
    "p": rgb("cf3b2c"),
    "z": rgb("efe2c4"),
    "L": rgb("bfa982"),
    "I": rgb("2e5aa6"),
}


def merge(*pals):
    out = {}
    for p in pals:
        for k, v in p.items():
            if k in out:
                raise ValueError(f"palette key clash {k!r}")
            out[k] = v
    return out


PAL = merge(GOLD, YOKE, BLACK, BLUE, LENS, SILVER, BOOT, CLEAR, RED, PACK_SHELL, WEBBING, FUR, KNIT)


# ---------------------------------------------------------------------------
# Canvas
# ---------------------------------------------------------------------------
class Canvas:
    def __init__(self, texture):
        self.texture = texture
        tw, th, _ = M.TEXTURES[texture]
        self.img = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
        self.px = self.img.load()
        self.regions = M.uv_regions(texture)
        self.done = set()
        self.cells = {}  # (x, y) -> (uv key, palette char): what painted each texel, for the dye map

    def rect(self, key, face):
        u, v = M.uv_of(self.texture, key)
        w, h, d = self.regions[key]
        return {
            "top": (u + d, v, w, d),
            "bottom": (u + d + w, v, w, d),
            "right": (u, v + d, d, h),
            "front": (u + d, v + d, w, h),
            "left": (u + d + w, v + d, d, h),
            "back": (u + d + w + d, v + d, w, h),
        }[face]

    def size(self, key, face):
        return self.rect(key, face)[2:]

    def rows(self, key, face, rows):
        x0, y0, w, h = self.rect(key, face)
        img = grid(rows, PAL, f"{key}.{face}", (w, h))
        ipx = img.load()
        for y in range(h):
            for x in range(w):
                self.px[x0 + x, y0 + y] = ipx[x, y]
                if rows[y][x] not in ". ":
                    self.cells[(x0 + x, y0 + y)] = (key, rows[y][x])
        self.done.add((key, face))

    def fill(self, key, face, ch):
        w, h = self.size(key, face)
        self.rows(key, face, [ch * w] * h)

    def box(self, key, **faces):
        """faces: name -> rows | single palette char (fill) | callable(w, h) -> rows."""
        for face, spec in faces.items():
            w, h = self.size(key, face)
            if callable(spec):
                spec = spec(w, h)
            if isinstance(spec, str):
                self.fill(key, face, spec)
            else:
                self.rows(key, face, spec)

    def check_complete(self):
        missing = [(k, f) for k in self.regions for f in ("top", "bottom", "right", "front", "left", "back")
                   if (k, f) not in self.done]
        if missing:
            raise ValueError(f"{self.texture}: unpainted faces {missing}")


def mirror(rows):
    return [r[::-1] for r in rows]


GOLD_RAMP = "123456"  # outline, seam, shadow, base, light, sheen
YOKE_RAMP = "aAfFvV"


def baffle(w, sheen=(), zip_col=None, ramp=GOLD_RAMP, tone=0, logo=None):
    """A puffy down tube seen side-on (2 rows): light along the top, shadow underneath, rounder at the ends.
    tone -1 is a shade darker (rows near the harness); logo = (col, (top, bottom)) patch."""
    def sh(i):
        return ramp[max(0, min(5, i + tone))]
    top = [sh(4)] * w
    low = [sh(3)] * w
    for c in sheen:
        top[c] = sh(5)
    if w > 2:
        top[0] = top[-1] = sh(3)
        low[0] = low[-1] = sh(2)
    rows = [top, low]
    if zip_col is not None:
        top[zip_col] = low[zip_col] = "k"
    if logo:
        col, (t, b) = logo
        for i, ch in enumerate(t):
            top[col + i] = ch
        for i, ch in enumerate(b):
            low[col + i] = ch
    return ["".join(r) for r in rows]


def ledge(w, h, edge="5", inner="4"):
    """A top face whose outer ring shows as the ledge of a protruding baffle."""
    return ["".join(edge if x in (0, w - 1) or y in (0, h - 1) else inner for x in range(w)) for y in range(h)]


def seams(w, h, seam_rows, base="3", seam="2", zip_col=None):
    rows = []
    for y in range(h):
        r = [seam if y in seam_rows else base for _ in range(w)]
        if zip_col is not None and y in seam_rows:
            r[zip_col] = "k"
        rows.append("".join(r))
    return rows


def quilt_rows(w, h, phase=0, dark_from=None, sheen=()):
    """Black quilting as horizontal bands: light, mid, seam (top to bottom), darker toward the bottom."""
    rows = []
    for y in range(h):
        ch = "jJk"[(y + phase) % 3]
        if dark_from is not None and y >= dark_from and ch != "k":
            ch = {"j": "J", "J": "K"}[ch]
        row = [ch] * w
        if ch == "j":
            for c in sheen:
                row[c] = "i"
        rows.append("".join(row))
    return rows


def quilt_cols(w, h, phase=0, dark_from=None):
    """Black quilting as vertical bands (the hood's ring baffles seen from the side), kept low-contrast so the
    side of the hood doesn't read as stripes."""
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            ch = "JJK"[(x + phase) % 3] if y > 1 else "jJK"[(x + phase) % 3]
            if dark_from is not None and y >= dark_from and ch != "k":
                ch = {"j": "J", "J": "K", "K": "k"}[ch]
            row.append(ch)
        rows.append("".join(row))
    return rows


def roll_v(w, h, light="j", mid="J", dark="K", sheen="i", sheen_rows=(1,)):
    """A vertical down roll seen from the front: lit along the top, darkening down."""
    rows = []
    for y in range(h):
        t = y / max(1, h - 1)
        ch = light if t < 0.34 else (mid if t < 0.75 else dark)
        row = [ch] * w
        if y in sheen_rows and w > 1:
            row[min(1, w - 1)] = sheen
        rows.append("".join(row))
    return rows


def fur(w, h, seed=0, ragged_top=False, ragged_bottom=False, ragged_left=False, ragged_right=False, root="Q"):
    """Soft fur: light tan with sparse darker strands; pale tips along the ragged (outer, partly cut-out) edges."""
    import random
    rnd = random.Random(seed * 7919 + w * 31 + h)
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            ch = "X" if (x * 3 + y * 5 + seed) % 7 == 0 else "Z"
            edge = (ragged_top and y == 0) or (ragged_bottom and y == h - 1) or (ragged_left and x == 0)                 or (ragged_right and x == w - 1)
            if edge:
                ch = "." if rnd.random() < 0.4 else "H"
            row.append(ch)
        rows.append("".join(row))
    return rows


def strands(w, h, seed, root="bottom", ragged=True):
    """Fur as strands growing out from one edge (root): dark brown roots, tan along the strand, cream tips, a
    few darker guard hairs, and (ragged) some strands stopping short so the outer edge is shaggy. A face only
    one texel deep shows the tips alone."""
    import random
    rnd = random.Random(seed * 7919 + w * 31 + h)
    across = w if root in ("top", "bottom") else h
    depth = h if root in ("top", "bottom") else w
    cells = {}
    for i in range(across):
        reach = depth - (1 if ragged and depth > 1 and rnd.random() < 0.45 else 0)
        guard = rnd.random() < 0.25  # a dark-tipped guard hair
        for d in range(depth):
            tip = d == reach - 1
            if d >= reach:
                ch = "."
            elif depth == 1:
                ch = "X" if guard else ("H" if rnd.random() < 0.5 else "Z")
            elif d == 0:
                ch = "Q" if rnd.random() < 0.4 else "X"
            elif tip:
                ch = "X" if guard else ("H" if rnd.random() < 0.65 else "Z")
            else:
                ch = "Z" if (d + 0.5) / depth < 0.7 or rnd.random() < 0.5 else "H"
            if root == "bottom":
                x, y = i, h - 1 - d
            elif root == "top":
                x, y = i, d
            elif root == "left":
                x, y = d, i
            else:
                x, y = w - 1 - d, i
            cells[(x, y)] = ch
    return ["".join(cells[(x, y)] for x in range(w)) for y in range(h)]


def ring(w, h, ch="K"):
    """Only the outer ring opaque: closes the gap between a strap and the head without capping it."""
    return [ch * w if y in (0, h - 1) else ch + "." * (w - 2) + ch for y in range(h)]


def vented(w, h, vent_cols, vent_rows, edge="W", fill="w", vent="e"):
    """A helmet top: lit white, a shade darker round the rim, two vent slots running front to back."""
    return ["".join(edge if x in (0, w - 1) or y in (0, h - 1) else
                    (vent if x in vent_cols and vent_rows[0] <= y <= vent_rows[1] else fill) for x in range(w))
            for y in range(h)]


# ---------------------------------------------------------------------------
# Suit texture
# ---------------------------------------------------------------------------
def paint_suit():
    return paint_suit_canvas().img


def paint_suit_canvas():
    c = Canvas("suit")

    # --- Jacket -----------------------------------------------------------
    # Torso core: only the seams between baffles (rows 3, 6, 9, 12) show, with the zip running through them.
    seam_rows = {3, 6, 9, 12}
    c.box("torso",
          top="A",
          bottom="2",
          front=lambda w, h: seams(w, h, seam_rows, zip_col=5),
          back=lambda w, h: seams(w, h, seam_rows),
          right=lambda w, h: seams(w, h, seam_rows),
          left=lambda w, h: seams(w, h, seam_rows))
    # Yoke: the darker caramel panel over the shoulders and upper chest.
    c.box("yoke",
          top=lambda w, h: ledge(w, h, "v", "F"),
          bottom=lambda w, h: ledge(w, h, "A", "f"),
          front=["FvFFvkvFFvF", "FFFFFkFFFFF", "ffffAkAffff"],
          back=["FFvFFvFFvFF", "FFFFFFFFFFF", "fffffffffff"],
          right=["FvFvFFv", "FFFFFFF", "fffffff"],
          left=["vFFvFvF", "FFFFFFF", "fffffff"])
    # Baffle rows, each with its own shading; the chest row carries a small white-on-black logo on the
    # wearer's left, and the lowest row is a shade darker above the harness.
    c.box("torso_baffle_1",
          top=lambda w, h: ledge(w, h, "6", "5"),
          bottom=lambda w, h: ledge(w, h, "2", "3"),
          front=baffle(11, sheen=(2,), zip_col=5, logo=(7, ("wW", "kk"))),
          back=baffle(11, sheen=(3, 8)),
          right=baffle(7, sheen=(2,)),
          left=baffle(7, sheen=(4,)))
    c.box("torso_baffle_2",
          top=lambda w, h: ledge(w, h, "6", "5"),
          bottom=lambda w, h: ledge(w, h, "2", "3"),
          front=baffle(11, sheen=(3, 8), zip_col=5),
          back=baffle(11, sheen=(2, 7)),
          right=baffle(7, sheen=(4,)),
          left=baffle(7, sheen=(2,)))
    c.box("torso_baffle_3",
          top=lambda w, h: ledge(w, h, "5", "4"),
          bottom=lambda w, h: ledge(w, h, "1", "2"),
          front=baffle(11, sheen=(2, 9), zip_col=5, tone=-1),
          back=baffle(11, sheen=(5,), tone=-1),
          right=baffle(7, sheen=(3,), tone=-1),
          left=baffle(7, sheen=(3,), tone=-1))
    # Collar: caramel, black piping round the top edge, the zip up the front.
    c.box("collar_back",
          top=lambda w, h: ["J" * w] * (h - 1) + ["A" * w],
          bottom="a",
          front=["AAAAAAAAA", "fffffffff"],
          back=["JJJJJJJJJ", "FFvFFFvFF"],
          right=["JJ", "fF"],
          left=["JJ", "Ff"])
    c.box("collar_side",
          top=["J"] * 6,
          bottom="a",
          front=["J", "F"],
          back=["J", "f"],
          right=["JJJJJJ", "fFFvFF"],
          left=["AAAAAA", "ffffff"])
    c.box("zip_pull", top="W", bottom="e", front=["W", "E"], back="x", right=["E", "e"], left=["E", "e"])
    # Sleeves: caramel shoulder cap, then gold baffles getting darker toward the dark cuff.
    def sleeve_core(w, h):
        return ["A" * w] + seams(w, h - 1, {2, 5, 8})
    c.box("sleeve",
          top=lambda w, h: ledge(w, h, "v", "V"),
          bottom="a",
          front=sleeve_core, back=sleeve_core, right=sleeve_core, left=sleeve_core)
    c.box("sleeve_yoke",
          top=lambda w, h: ledge(w, h, "V", "v"),
          bottom=lambda w, h: ledge(w, h, "A", "f"),
          front=baffle(7, sheen=(2,), ramp=YOKE_RAMP),
          back=baffle(7, sheen=(4,), ramp=YOKE_RAMP),
          right=baffle(6, sheen=(1, 4), ramp=YOKE_RAMP),
          left=baffle(6, ramp=YOKE_RAMP))
    c.box("sleeve_baffle_1",
          top=lambda w, h: ledge(w, h, "6", "5"),
          bottom=lambda w, h: ledge(w, h, "2", "3"),
          front=baffle(7, sheen=(4,)),
          back=baffle(7, sheen=(2,)),
          right=baffle(6, sheen=(2,)),
          left=baffle(6))
    c.box("sleeve_baffle_2",
          top=lambda w, h: ledge(w, h, "5", "4"),
          bottom=lambda w, h: ledge(w, h, "1", "2"),
          front=baffle(7, sheen=(2,), tone=-1),
          back=baffle(7, sheen=(4,), tone=-1),
          right=baffle(6, sheen=(3,), tone=-1),
          left=baffle(6, tone=-1))
    c.box("cuff", top="a", bottom=lambda w, h: ring(w, h, "a"),
          front=["aAAAa"], back=["aAAAa"], right=["aAAAa"], left=["aAAAa"])
    # Mitts: black expedition mitts, a thumb on the inner front.
    c.box("mitt",
          top="J",
          bottom=["kKKKk", "KkkkK", "KkkkK", "KkkkK", "kKKKk"],
          front=["JJJJJ", "KKKjJ", "KkKjJ", "kkKKk"],
          back=["JJJJJ", "KKKKK", "KkkkK", "kkkkk"],
          right=["JJJJJ", "KjKKK", "KJkkK", "kKkkk"],
          left=["JJJJJ", "JjKKK", "JjKkK", "kKkkk"])

    # --- Pants and harness -------------------------------------------------
    c.box("waist",
          top="3",
          bottom="2",
          front=["222222222", "455555554", "344444443"],
          back=["222222222", "455555554", "344444443"],
          right=["22222", "45554", "34443"],
          left=["22222", "45554", "34443"])
    c.box("belt",
          top=lambda w, h: ledge(w, h, "j", "K"),
          bottom="k",
          front=["KBjJKJKJjBK"],
          back=["KJKBJKJBKJK"],
          right=["JKJBJKJ"],
          left=["JKBJKJJ"])
    c.box("buckle", top="W", bottom="e", front=["wE", "Ee"], back="k", right=["E", "e"], left=["E", "e"])
    c.box("belay", top="J", bottom="k", front=["j", "J", "K"], back="K", right=["J", "K", "k"], left=["J", "K", "k"])
    c.box("sling", top="J", bottom="k", front=["J"], back=["K"], right=["K"], left=["K"])
    # Jumar: silver cam housing, yellow handle, black grip.
    c.box("jumar", top=["WE"], bottom=["kk"], front=["WE", "lu", "kK"], back=["Ee", "us", "kk"],
          right=["E", "u", "k"], left=["e", "s", "k"])
    leg_seams = {0, 2, 3, 5, 6, 8}
    c.box("leg",
          top="3",
          bottom="2",
          front=lambda w, h: seams(w, h, leg_seams),
          back=lambda w, h: seams(w, h, leg_seams),
          right=lambda w, h: seams(w, h, leg_seams),
          left=lambda w, h: seams(w, h, leg_seams))
    c.box("leg_baffle",
          top=lambda w, h: ledge(w, h, "6", "5"),
          bottom=lambda w, h: ledge(w, h, "2", "3"),
          front=baffle(5, sheen=(1,)),
          back=baffle(5),
          right=baffle(7, sheen=(2,)),
          left=baffle(7, sheen=(4,)))
    c.box("leg_baffle_top",
          top=lambda w, h: ledge(w, h, "6", "5"),
          bottom=lambda w, h: ledge(w, h, "2", "3"),
          front=["455c4", "45c34"],
          back=["4c554", "3c443"],
          right=baffle(7, sheen=(2,)),
          left=["455c554", "34c4443"])
    c.box("leg_loop",
          top=lambda w, h: ledge(w, h, "B", "b"),
          bottom="b",
          front=["cBCcB"],
          back=["BcBCc"],
          right=["cBCcBcC"],
          left=["CcBcBCc"])
    c.box("gaiter",
          top="3",
          bottom="J",
          front=["45554", "34443", "JjJJJ"],
          back=["44544", "33433", "JJJjJ"],
          right=["44555", "33444", "JJjJJ"],
          left=["55544", "44433", "JJjJJ"])

    # --- Boots -------------------------------------------------------------
    c.box("boot",
          top=["KKKKK", "KkkkK", "KkkkK", "KkkkK", "KkkkK", "KKKKK"],
          bottom="s",
          front=["KJjJK", "lhkll", "ulkus", "suksd"],
          back=["KJJJK", "luuul", "uuuus", "sssss"],
          right=["KJJJJK", "lllhhl", "uuulll", "sssuuu"],
          left=["KJJJJK", "llhlll", "lluuuu", "uuusss"])
    c.box("toe", top=["lhhll"], bottom="s", front=["lhlus", "uuuss"], back="s", right=["l", "u"], left=["l", "s"])
    c.box("sole",
          top=lambda w, h: ledge(w, h, "J", "K"),
          bottom=["kJkJk", "JkJkJ", "kJkJk", "kkkkk", "kJkJk", "JkJkJ", "kJkJk"],
          front=["JkJkJ"],
          back=["kJkJk"],
          right=["kJkJkJJ"],
          left=["JJkJkJk"])

    # --- Hood up -------------------------------------------------------------
    # The core only shows in the seams between the ring baffles: dark. Its front is open (the face shows) and its
    # bottom is open apart from the rim.
    c.box("hood", top="k", bottom=lambda w, h: ring(w, h, "k"), front=["." * 10] * 9,
          right="k", left="k", back="K")
    # Ring baffles round the face: lit tops, sides darkening toward the jaw.
    c.box("ring_top", top=["jjiijjjiij", "jjjjjjjjjj"], bottom="k", front=["J" * 10], back=["K" * 10],
          right=["JJ"], left=["JJ"])
    c.box("ring_corner", top=["j", "j"], bottom="k", front=["J"], back=["K"], right=["jJ"], left=["JJ"])
    c.box("ring_side", top=["j", "j"], bottom="k",
          front=roll_v(1, 9, sheen_rows=()), back=roll_v(1, 9, "J", "K", "k", sheen_rows=()),
          right=["ij", "jj", "jj", "jJ", "JJ", "JJ", "JK", "KK", "Kk"],
          left="k")
    c.box("hood_back_top", top=["jJjjJjjJ"], bottom="k", front="k", back=["JjJJjJJj"], right=["J"], left=["J"])
    c.box("hood_back", top=["jJjjJjjJjJ"], bottom="k", front="k",
          back=lambda w, h: quilt_rows(w, h, phase=2, dark_from=6, sheen=(2, 7)),
          right=lambda w, h: quilt_rows(w, h, phase=2, dark_from=6),
          left=lambda w, h: quilt_rows(w, h, phase=2, dark_from=6))
    c.box("crown", top=["jjiijjjj", "jijjjjij", "jjjjjjjj", "jjjjjjjj", "jjjjijjj", "jjjjjjjj", "JjjjjjjJ"],
          bottom="k", front=["jjjjjjjj"], back=["JJJJJJJJ"], right=["JJJjjJJ"], left=["JJjjJJJ"])
    c.box("nape", top="K", bottom="k", front="k", back=["KJKKJKKJ", "kKkkKkkK"], right=["kKK", "kkK"],
          left=["KKk", "Kkk"])
    # Thick down ruff round the face; its inner faces (toward the face) are dark so the face reads framed.
    c.box("rim_top", top=["jjjjjjjjj", "jijjijjij"], bottom="k",
          front=["jijjjjjij", "JJjJJJjJJ"], back="K", right=["jj", "JJ"], left=["jj", "JJ"])
    c.box("rim_side", top=["ij", "jj"], bottom="k",
          front=["ij", "ij", "jj", "jJ", "jJ", "JJ", "JJ", "JK", "KK", "Kk"],
          back="K",
          right=["jj", "jj", "jJ", "jJ", "JJ", "JJ", "JJ", "JK", "KK", "Kk"],
          left=["KK", "KK", "KK", "Kk", "Kk", "kk", "kk", "kk", "kk", "kk"])
    # Parka fur ruff: pale shaggy tips, ragged at the outer edges, dark at the roots by the face.
    c.box("fur_top", top=lambda w, h: strands(w, h, 1, "bottom"), bottom="Q",
          front=lambda w, h: strands(w, h, 2, "bottom"), back="X",
          right=lambda w, h: strands(w, h, 3, "bottom"), left=lambda w, h: strands(w, h, 4, "bottom"))
    c.box("fur_side", top=lambda w, h: strands(w, h, 5, "right"), bottom=lambda w, h: strands(w, h, 6, "right"),
          front=lambda w, h: strands(w, h, 7, "right"), back="X",
          right=lambda w, h: strands(w, h, 8, "right"), left=["Q" * 2] * 11)
    c.box("fur_chin", top="X", bottom=lambda w, h: strands(w, h, 9, "top"),
          front=lambda w, h: strands(w, h, 10, "top"), back="X", right=["ZX"], left=["XZ"])
    # Low hood: two rolls under the top one, the front edge pulled down to the brows; lit along their tops,
    # dark underneath where they curl in toward the face.
    c.box("brow_upper", top="J", bottom="K", front=["jjijjjijj", "KJJJJJJJK"], back="k",
          right=["jJ", "JK"], left=["Jj", "KJ"])
    c.box("brow_lower", top="J", bottom="k", front=["jJjjjjJj", "KKKKKKKK"], back="k",
          right=["JJ", "KK"], left=["JJ", "KK"])
    # Low fur hood: the parka ruff's top brought down to the brows, shaggy along both edges.
    c.box("fur_wrap_side", top=lambda w, h: strands(w, h, 20, "left"), bottom=lambda w, h: strands(w, h, 21, "left"),
          front=lambda w, h: strands(w, h, 22, "top"), back=lambda w, h: strands(w, h, 23, "top"),
          right=lambda w, h: strands(w, h, 24, "right"), left="Q")
    c.box("fur_wrap_top", top=lambda w, h: strands(w, h, 25, "bottom"), bottom="Q",
          front=lambda w, h: strands(w, h, 26, "bottom"), back=lambda w, h: strands(w, h, 27, "bottom"),
          right=lambda w, h: strands(w, h, 28, "bottom"), left=lambda w, h: strands(w, h, 29, "bottom"))
    c.box("fur_tuft", top="Z", bottom="X", front=lambda w, h: strands(w, h, 30, "right"),
          back="Q", right=lambda w, h: strands(w, h, 31, "right"), left=lambda w, h: strands(w, h, 32, "left"))
    c.box("fur_brow", top=lambda w, h: strands(w, h, 16, "bottom"), bottom="Q",
          front=lambda w, h: strands(w, h, 17, "bottom"), back="Q",
          right=lambda w, h: strands(w, h, 18, "bottom"), left=lambda w, h: strands(w, h, 19, "bottom"))
    # Sherpa knit hat: red wool, a cream zigzag band with blue stitches, a ribbed cuff, ear flaps and braided ties.
    c.box("knit_band", top="D", bottom="D", front=["PzPPzPPzP", "zPzIPzIzP"], back="D", right=["P", "D"],
          left=["P", "D"])
    knit_side = ["pPPpPPpPPp", "zPzIzPzIzP", "DPDPDPDPDP"]
    c.box("knit_body", top="P", bottom=lambda w, h: ring(w, h, "D"),
          front=knit_side, back=mirror(knit_side), right=knit_side, left=mirror(knit_side))
    c.box("knit_crown",
          top=["PpPPpPPpP", "pPPpPPpPP", "PPpPPpPPp", "PpPPDPPpP", "pPPDDDPPp", "PpPPDPPpP", "PPpPPpPPp",
               "pPPpPPpPP", "PpPPpPPpP"],
          bottom="D", front=["pPpPpPpPp"], back=["PpPpPpPpP"], right=["pPpPpPpPp"], left=["PpPpPpPpP"])
    c.box("knit_bobble", top=["zLz", "LzL", "zLz"], bottom="L", front=["zLz"], back=["LzL"], right=["zLz"], left=["LzL"])
    c.box("ear_flap", top="P", bottom="D", front=["P", "P", "D"], back=["P", "P", "D"],
          right=["PpPP", "zPzP", "DzDD"], left=["PPpP", "PzPz", "DDzD"])
    c.box("ear_tip", top="D", bottom="D", front=["D"], back=["D"], right=["DP"], left=["PD"])
    c.box("tie", top="z", bottom="L", front=["z", "L", "z"], back=["L", "z", "L"], right=["z", "L", "z"],
          left=["L", "z", "L"])
    # Climbing helmet: white shell, lit on top, dark vents along the sides, a black headlamp strap round it and a
    # headlamp with a pale lens on the front.
    c.box("helmet_up_shell", top=lambda w, h: vented(w, h, (3, 8), (2, 8)), bottom="K",
          front=["kWWWWWWWWWWk", "EEEEEEEEEEEE"], back=["kkkkkkkkkkkk", "EEEEEEEEEEEE"],
          right=["WWeWWeWWeWWk", "EEEEEEEEEEEE"], left=["kWWeWWeWWeWW", "EEEEEEEEEEEE"])
    c.box("helmet_up_dome", top=lambda w, h: vented(w, h, (2, 7), (2, 7)), bottom="W",
          front=["W" * 10], back=["W" * 10], right=["W" * 10], left=["W" * 10])
    c.box("helmet_shell", top=lambda w, h: vented(w, h, (2, 7), (2, 7)), bottom=lambda w, h: ring(w, h, "K"),
          front=["WWWWWWWWWW", "kWWWWWWWWk", "WWWWWWWWWW", "EEEEEEEEEE"],
          back=["WWWWWWWWWW", "kkkkkkkkkk", "WWeWWWWeWW", "EEEEEEEEEE"],
          right=["WWWWWWWWWW", "kkkkkkkkkW", "WeWWeWWeWW", "EEEEEEEEEE"],
          left=["WWWWWWWWWW", "WkkkkkkkkK", "WWeWWeWWeW", "EEEEEEEEEE"])
    c.box("helmet_dome", top=lambda w, h: vented(w, h, (2, 5), (1, 6)), bottom="W",
          front=["W" * 8], back=["W" * 8], right=["W" * 8], left=["W" * 8])
    c.box("helmet_up_cap", top=lambda w, h: vented(w, h, (), (0, 0)), bottom="W",
          front=["W" * 7], back=["W" * 7], right=["W" * 7], left=["W" * 7])
    c.box("helmet_cap", top=lambda w, h: vented(w, h, (), (0, 0)), bottom="W",
          front=["W" * 5], back=["W" * 5], right=["W" * 5], left=["W" * 5])
    c.box("helmet_lamp", top=["JjJ"], bottom="k", front=["KYK", "kyk"], back="k", right=["J", "k"], left=["J", "k"])
    c.box("chin_strap_up", top="K", bottom="k", front=["K"] * 7, back=["k"] * 7, right=["J", "K", "K", "K", "K", "K", "k"],
          left=["J", "K", "K", "K", "K", "K", "k"])
    c.box("chin_strap", top="K", bottom="k", front=["K"] * 6, back=["k"] * 6, right=["J", "K", "K", "K", "K", "k"],
          left=["J", "K", "K", "K", "K", "k"])
    # Chunky expedition goggle: wide black frame (grey bevel on top), big mirrored lens with glare and a nose notch.
    c.box("goggle_frame", top=["jJJJJJJJJj", "KKKKKKKKKK"], bottom=["kkkkkkkkkk", "kkkkkkkkkk"],
          front=["JjjjjjjjjJ", "JKKKKKKKKJ", "KKKKKKKKKK", "kKKKkkKKKk"],
          back=["KkkkkkkkkK", "KkkkkkkkkK", "KkkkkkkkkK", "kkkkkkkkkk"],
          right=["jJ", "JK", "KK", "kk"], left=["Jj", "KJ", "KK", "kk"])
    c.box("goggle_lens", top=["rRRRRRRr"], bottom=["rrrrrrrr"],
          front=["YyOyyOyY",
                 "OoOooOoO",
                 "RoR..RoR"],
          back="r", right=["o", "R", "r"], left=["o", "R", "r"])
    c.box("strap_drop", top="K", bottom="k", front=["J", "K", "K", "k"], back="k", right=["K", "K", "k", "k"],
          left=["K", "K", "k", "k"])
    c.box("goggle_strap", top=lambda w, h: ring(w, h)[:-1] + ["K" + "." * (w - 2) + "K"],
          bottom=lambda w, h: ring(w, h)[:-1] + ["K" + "." * (w - 2) + "K"],
          front=["." * 9], back=["KJKjJjKJK"], right=["JKJKJEeKJ"], left=["JKeEJKJKJ"])

    # --- Hood down -----------------------------------------------------------
    c.box("bunch", top=["jJjiJjJ", "KkkkkkK"], bottom="k", front="K",
          back=["jJijJij", "KJkKkJK"], right=["Jj", "kK"], left=["jJ", "Kk"])
    c.box("drape", top=["JjJJjJ"], bottom="k", front="K", back=["JjKKjJ", "KJkkJK"], right=["J", "K"],
          left=["J", "K"])
    c.box("tip", top=["JjjJ"], bottom="k", front="K", back=["KJJK"], right=["K"], left=["K"])
    c.box("fur_trim", top=lambda w, h: strands(w, h, 11, "bottom"), bottom="Q",
          front=lambda w, h: strands(w, h, 12, "bottom"), back=lambda w, h: strands(w, h, 13, "bottom"),
          right=lambda w, h: strands(w, h, 14, "bottom"), left=lambda w, h: strands(w, h, 15, "bottom"))

    c.check_complete()
    return c


# ---------------------------------------------------------------------------
# Oxygen mask texture
# ---------------------------------------------------------------------------
def paint_mask():
    c = Canvas("mask")
    # Black fleece neck gaiter: only its outer ring shows on top (the head fills the middle).
    c.box("gaiter",
          top=lambda w, h: ring(w, h, "J"), bottom=lambda w, h: ring(w, h, "k"),
          front=["JKJKJKJKJ", "KkKkKkKkK"], back=["JKJKJKJKJ", "KkKkKkKkK"],
          right=["JKJKJKJKJ", "KkKkKkKkK"], left=["JKJKJKJKJ", "KkKkKkKkK"])
    # The mask: black rubber, lighter round its rim; the regulator is a slim silver valve.
    c.box("mask",
          top=["KJJJJK", "JjjjjJ"],
          bottom="k",
          front=["JjjjjJ",
                 "KJJJJK",
                 "kKKKKk"],
          back="k",
          right=["Kj", "KJ", "kK"],
          left=["jK", "JK", "Kk"])
    c.box("valve", top=["Ee"], bottom="x", front=["WE", "ex"], back="x", right=["E", "e"], left=["E", "x"])
    c.box("jaw_strap", top="J", bottom="k", front=["jK"], back="k", right=["J"], left=["J"])
    # The regulator's red line.
    c.box("cord_1", top="M", bottom="m", front=["M", "m"], back="m", right=["m", "m"], left=["M", "m"])
    c.box("cord_2", top="M", bottom="m", front=["M", "M", "m", "M", "M", "m"], back="m",
          right=["m"] * 6, left=["M", "m", "M", "M", "m", "M"])
    # Coiled black hose: alternating light and dark rings.
    c.box("hose_top", top="J", bottom="k", front=["j"], back=["J"], right=["J"], left=["J"])
    c.box("hose_v2", top="J", bottom="k", front=["j", "k"], back=["J", "k"], right=["J", "k"], left=["J", "k"])
    c.box("hose_v3", top="J", bottom="k", front=["j", "k", "j"], back=["J", "k", "J"], right=["J", "k", "J"],
          left=["J", "k", "J"])
    c.box("hose_h", top=["JkJkJk"], bottom="k", front=["jkjkjk"], back=["JkJkJk"], right="J", left="j")
    c.box("hose_d", top=["J", "k", "J", "k"], bottom="k", front="j", back="J", right=["kJkj"], left=["jkJk"])
    c.check_complete()
    return c.img


# ---------------------------------------------------------------------------
# Expedition pack texture. The shell (pack_body, pack_upper, pack_lid) is painted in greys and tinted at render
# time; everything else keeps its colour.
# ---------------------------------------------------------------------------
def paint_pack():
    c = Canvas("pack")
    # Shell: three nested boxes stepping round like a cylinder. Shading darkens toward the sides and bottom so
    # the steps read as a rounded body; the centre of the back carries the pocket zip.
    def shell_column(h, top="9", body="9", bottom="8", hi=()):
        rows = [top] + [body] * (h - 2) + [bottom]
        return ["0" if y in hi else ch for y, ch in enumerate(rows)]

    def cols(*columns):
        return ["".join(c[y] for c in columns) for y in range(len(columns[0]))]

    H = 18
    deep_c = shell_column(H, hi=(2, 9))
    deep_z = ["7" if y in (5, 15) else ch for y, ch in enumerate(shell_column(H))]
    c.box("pack_deep",
          top="9", bottom="7", front="8",
          back=cols(deep_c, deep_z, deep_z, deep_z, deep_c),
          right=["8" * 8] * H, left=["8" * 8] * H)
    c.box("pack_mid",
          top="9", bottom="7", front="8",
          back=cols(*[shell_column(H, body="8", bottom="7")] + [shell_column(H, hi=(3,))] * 6
                    + [shell_column(H, body="8", bottom="7")]),
          right=["8" * 7] * H, left=["8" * 7] * H)
    side = [("0" if y in (1, 2) else "9" if y < 12 else "8") * 6 for y in range(H - 1)] + ["777777"]
    side = [r[:2] + "8" + r[3:] if 4 <= y <= 14 else r for y, r in enumerate(side)]  # a side seam
    c.box("pack_wide",
          top="9", bottom="7", front="8",
          back=["8" * 10] * (H - 1) + ["7" * 10],
          right=side, left=mirror(side))
    c.box("lid_wide",
          top=["9999999999", "9909999099", "9999999999", "9999099999", "9099999909", "9999999999", "8999999998"],
          bottom="7", front=["9999999999", "8888888888", "7777777777"], back=["9999999999", "8888888888", "7777777777"],
          right=["9999999", "9999998", "8888887"], left=["9999999", "8999999", "7888888"])
    c.box("lid_mid",
          top=["99999999", "99099999", "90999909", "99999999", "99999999", "99990999", "99999999", "89999998"],
          bottom="7", front=["99999999", "88888888", "77777777"], back=["99099099", "99999999", "88888888"],
          right=["99999999", "88888888", "77777777"], left=["99999999", "88888888", "77777777"])
    # Webbing and buckles.
    c.box("back_strap", top="T", bottom="t",
          front="t",
          back=["T", "T", "U", "T", "T", "W", "T", "T", "T", "T", "T", "T", "T", "W", "T", "T", "T", "t"],
          right=["t"] * 18, left=["t"] * 18)
    c.box("lid_strap", top="T", bottom="t", front="t", back=["T", "T", "W"], right=["t"] * 3, left=["t"] * 3)
    c.box("side_strap", top="T", bottom="t", front=["t"], back=["T"], right=["TTWTT"], left=["TTWTT"])
    # Closed-cell foam pad: ribbed light blue.
    c.box("foam_pad",
          top=["cCc", "ccc", "Bcc", "bbb"],
          bottom="b",
          front=["cBc", "bbb"] * 9,
          back=["Ccc", "bBb"] * 9,
          right=["cCCc", "bbbb"] * 9,
          left=["cBBc", "bbbb"] * 9)
    c.box("pad_strap",
          top=lambda w, h: ring(w, h, "T"),
          bottom=lambda w, h: ring(w, h, "t"),
          front=["TTT"], back=["TWT"], right=["TTTTT"], left=["TTTTT"])
    # Mesh side pocket and a clear water bottle with a black cap.
    c.box("side_pocket", top="t", bottom="t", front="t", back="t",
          right="t",
          left=["TUTU", "UTUT", "TUTU", "UTUT", "TTTT"])
    c.box("bottle", top=["nN", "Gn"], bottom="g",
          front=["nN", "Gn", "Gn", "gG"], back=["Nn", "nG", "nG", "Gg"],
          right=["nN", "Gn", "Gn", "gG"], left=["Nn", "nG", "nG", "Gg"])
    c.box("bottle_cap", top="J", bottom="k", front=["K"], back=["K"], right=["K"], left=["K"])
    # Charcoal sleeping-bag stuff sack with drawstring ends, and its straps.
    c.box("stuff_sack",
          top=["JjJJjJJjJJ", "JJJJJJJJJJ", "JjJJJJjJJJ", "JJJJjJJJJJ", "JJJJJJJJJJ", "KKKKKKKKKK"],
          bottom="k",
          front=["K" * 10, "k" * 10],
          back=["JjJJjJJjJJ", "KKKKKKKKKK"],
          right=["jiijjj", "JjjJJJ"], left=["jjjiij", "JJJjjJ"])
    c.box("stuff_sack_round",
          top=["jJjjJjjJjj", "jjjjjjjjjj", "jjJjjjjJjj", "JJJJJJJJJJ"],
          bottom="k", front=["K" * 10] * 3, back=["jJjjJjjJjj", "JJJJJJJJJJ", "KKKKKKKKKK"],
          right=["jiij", "JjjJ", "KKKK"], left=["jiij", "JjjJ", "KKKK"])
    c.box("sack_strap", top="T", bottom="t", front=["t"] * 3, back=["T", "W", "T"], right="T", left="T")
    # Orange rope coil on the lid.
    c.box("rope", top=["OoOoOoO", "oyoOoyo", "OoOoOoO", "oOoyoOo", "OoOoOoO"], bottom="o",
          front=["oOoOoOo"], back=["oOoOoOo"], right=["OoOoO"], left=["OoOoO"])
    # Ice axe on the back, head down.
    c.box("axe_shaft", top="E", bottom="K", front="K",
          back=["E", "j", "j", "j", "j", "j", "j", "j", "B", "B", "B", "B", "K"],
          right=["e", "J", "J", "J", "J", "J", "J", "J", "b", "b", "b", "b", "K"],
          left=["e", "J", "J", "J", "J", "J", "J", "J", "b", "b", "b", "b", "K"])
    c.box("axe_head", top=["WWWE"], bottom="e", front="x", back=["eWWE"], right=["E"], left=["E"])
    # Shoulder straps and hip belt (both fits share these texels where the sizes match).
    c.box("pack_strap_front",
          top="J",
          bottom="k",
          front=["jK", "JK", "JK", "JK", "JK", "JK", "WE", "JK", "JK", "Kk"],
          back="k",
          right=["K"] * 10,
          left=["k"] * 10)
    c.box("pack_strap_top", top=["jK"] * 10, bottom="k", front=["jK"], back=["jK"], right="k", left="k")
    c.box("hip_belt",
          top=lambda w, h: ledge(w, h, "j", "K"),
          bottom="k",
          front=["JjJJjJJjJJjJ"], back=["K" * 12],
          right=["JjJJjJJK"], left=["KJJjJJjJ"])
    c.box("hip_belt_bare",
          top=lambda w, h: ledge(w, h, "j", "K"),
          bottom="k",
          front=["JjJJjJJjJ"], back=["K" * 9],
          right=["JjJJK"], left=["KJJjJ"])
    for key in ("back_panel", "back_panel_suited"):
        c.box(key,
              top="J", bottom="k",
              front=["KJJJJJK", "KKKKKKK"] * 5,
              back="k",
              right=lambda w, h: ["J" * w if y % 2 == 0 else "K" * w for y in range(h)],
              left=lambda w, h: ["J" * w if y % 2 == 0 else "K" * w for y in range(h)])
    c.box("hip_buckle", top="W", bottom="e", front=["wE", "Ee"], back="k", right=["E", "e"], left=["E", "e"])
    c.box("carabiner", top="W", bottom="e", front=["W", "O"], back=["E", "o"], right=["E", "o"], left=["E", "o"])
    c.check_complete()
    return c.img


def build_all():
    return {"suit": paint_suit(), "mask": paint_mask(), "pack": paint_pack()}


# ---------------------------------------------------------------------------
# Item icons (16x16), matching the 3D suit. Symmetric icons are drawn as a left half and mirrored.
# ---------------------------------------------------------------------------
def halves(left_rows, center=None):
    rows = [r + r[::-1] for r in left_rows]
    if center:
        for y, (col, ch) in center.items():
            rows[y] = rows[y][:col] + ch + rows[y][col + 1:]
    return rows


HOOD_ICON = halves([
    "......kk",
    "....kkJJ",
    "...kJjjj",
    "..kJjjJJ",
    ".kJjjiij",
    ".kJjKkkk",
    "kJjKkYyy",
    "kJjKkOoO",
    "kJjKkRok",
    "kJjjKkkk",
    "kJjjKkkk",
    ".kJjjKkk",
    ".kJJjjJJ",
    "..kKJJJJ",
    "...kkKKK",
    ".....kkk",
])

JACKET_ICON = halves([
    "........",
    ".....aJJ",
    "...aFFvF",
    "..aFvFFF",
    ".aFFFFFF",
    ".affffff",
    ".5521555",
    ".4421444",
    ".2221222",
    ".5521555",
    ".4421444",
    ".3321333",
    ".aAA1555",
    ".kKk1444",
    ".kkk1222",
    "....1111",
], center={y: (8, "k") for y in range(2, 15)})

PANTS_ICON = halves([
    "........",
    ".kKjJKJK",
    ".1Bc5555",
    ".14Bc444",
    ".122Bc22",
    ".1555Bc5",
    ".1cBcBcB",
    ".1bbbb1.",
    ".155551.",
    ".144441.",
    ".122221.",
    ".155551.",
    ".144441.",
    ".133331.",
    ".1JjJJ1.",
    "..kkkk..",
], center={1: (8, "E")})
PANTS_ICON[1] = PANTS_ICON[1][:7] + "W" + PANTS_ICON[1][8:]

BOOT_ICON = [
    "....kkkkkkk",
    "....kJjjJKk",
    "....qhllusq",
    "....qhlkusq",
    "....qllkusq",
    "...qqluuusq",
    ".qqhluuuusq",
    "qhlluuuussq",
    "qluuuuussdq",
    "kJjJJJJKKKk",
    "kJJkJJkJJKk",
    ".kkkkkkkkk.",
]
_REAR = {"h": "u", "l": "u"}


def build_icons():
    icons = {
        "down_hood": grid(HOOD_ICON, PAL, "down_hood", (16, 16)),
        "down_jacket": grid(JACKET_ICON, PAL, "down_jacket", (16, 16)),
        "down_pants": grid(PANTS_ICON, PAL, "down_pants", (16, 16)),
    }
    boots = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    rear = ["".join(_REAR.get(ch, ch) for ch in row) for row in BOOT_ICON]
    boots.alpha_composite(grid(rear, PAL, "boot_rear"), (5, 0))
    boots.alpha_composite(grid(BOOT_ICON, PAL, "boot"), (0, 4))
    icons["down_boots"] = boots
    for n in ("down_hood", "down_jacket", "down_pants"):
        check_symmetric(icons[n], n)
    return icons


def icon_sheet(icons, k=8):
    names = list(icons)
    pad, cell = 16, 16 * k
    w = pad + len(names) * (cell + pad)
    h = pad + cell + pad + 50
    sheet = Image.new("RGBA", (w, h), (128, 128, 128, 255))
    d = ImageDraw.Draw(sheet)
    for i, n in enumerate(names):
        x = pad + i * (cell + pad)
        sheet.alpha_composite(up(icons[n], k), (x, pad))
        d.text((x, pad + cell + 4), n, fill=(20, 20, 20, 255))
    y = pad + cell + 22
    for i, n in enumerate(names):
        x = pad + i * 40
        d.rectangle([x, y, x + 35, y + 35], fill=(139, 139, 139, 255))
        d.line([(x, y), (x + 35, y)], fill=(55, 55, 55, 255))
        d.line([(x, y), (x, y + 35)], fill=(55, 55, 55, 255))
        d.line([(x + 35, y), (x + 35, y + 35)], fill=(255, 255, 255, 255))
        d.line([(x, y + 35), (x + 35, y + 35)], fill=(255, 255, 255, 255))
        sheet.alpha_composite(up(icons[n], 2), (x + 2, y + 2))
    return sheet


# ---------------------------------------------------------------------------
# UV sheet preview
# ---------------------------------------------------------------------------
FACE_TAGS = {"top": "T", "bottom": "Bo", "right": "R", "front": "F", "left": "L", "back": "Bk"}


def uv_sheet(k=6):
    tex = build_all()
    panels = []
    for name, img in tex.items():
        c = Canvas(name)
        w, h = img.size
        bg = Image.new("RGBA", (w * k, h * k), (70, 70, 70, 255))
        d = ImageDraw.Draw(bg)
        for y in range(h):
            for x in range(w):
                if (x + y) % 2:
                    d.rectangle([x * k, y * k, x * k + k - 1, y * k + k - 1], fill=(85, 85, 85, 255))
        bg.alpha_composite(up(img, k))
        d = ImageDraw.Draw(bg)
        for key in c.regions:
            u, v = M.uv_of(name, key)
            for face in FACE_TAGS:
                x, y, fw, fh = c.rect(key, face)
                d.rectangle([x * k, y * k, (x + fw) * k - 1, (y + fh) * k - 1], outline=(0, 220, 255, 160), width=1)
            d.text((u * k + 2, v * k + 1), key, fill=(255, 255, 0, 255))
        panel = Image.new("RGBA", (bg.width + 20, bg.height + 30), (40, 40, 40, 255))
        ImageDraw.Draw(panel).text((10, 8), f"{M.TEXTURES[name][2]}  ({w}x{h})", fill=(255, 255, 255, 255))
        panel.alpha_composite(bg, (10, 24))
        panels.append(panel)
    out = Image.new("RGBA", (max(p.width for p in panels), sum(p.height for p in panels)), (40, 40, 40, 255))
    y = 0
    for p in panels:
        out.alpha_composite(p, (0, y))
        y += p.height
    return out


def main(icons=False):
    tex = build_all()
    for name, img in tex.items():
        save_texture(img, M.TEXTURES[name][2].replace("textures/", "", 1))
    ic = build_icons()
    save_preview(icon_sheet(ic), "suit3d_icons.png")
    if icons:
        for n, img in ic.items():
            save_texture(img, f"item/{n}.png")
    return tex


if __name__ == "__main__":
    main(icons="--icons" in sys.argv)
