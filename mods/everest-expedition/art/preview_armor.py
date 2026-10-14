"""Armor previews.

1. armor_uv_check.png  - both 64x32 layers at 8x with every box face outlined
   and labelled (helmet/hood, jacket, sleeves, boots, bib, pant legs).
2. armor_3d_check.png  - a small software render of a HumanoidModel wearing the
   armor. Cube construction and UVs replicate net.minecraft.client.model.geom
   .ModelPart.Cube (incl. mirror() for left limbs and CubeDeformation growth),
   so faces land exactly where the game would put them.
"""
import math

import numpy as np
from PIL import Image, ImageDraw

import armor
from common import save_preview, up

# ---------------------------------------------------------------------------
# UV check sheet
# ---------------------------------------------------------------------------
REGION_COLORS = {
    "head": (255, 230, 0), "hat": (160, 160, 160), "body": (0, 220, 255),
    "arm": (120, 255, 120), "leg": (255, 120, 255),
}


def checker(w, h, k):
    img = Image.new("RGBA", (w, h), (70, 70, 70, 255))
    d = ImageDraw.Draw(img)
    for y in range(0, h, k):
        for x in range(0, w, k):
            if (x // k + y // k) % 2:
                d.rectangle([x, y, x + k - 1, y + k - 1], fill=(90, 90, 90, 255))
    return img


FACE_ABBR = {"top": "T", "bottom": "Bo", "right": "R", "front": "F", "left": "L", "back": "Bk"}


def uv_sheet(layer, title, boxes, k=12):
    """Layer at k-x over a checkerboard; each face outlined and tagged.

    Box name is written above the box's top-left; faces are tagged
    T/Bo/R/F/L/Bk (top, bottom, right=outer, front, left=inner, back).
    """
    w, h = 64 * k, 32 * k
    img = checker(w, h, k)
    img.alpha_composite(up(layer, k))
    d = ImageDraw.Draw(img)
    for bname, box, used in boxes:
        col = REGION_COLORS[bname] if used else (150, 150, 150)
        rects = armor.face_rects(*box)
        for fname, (x, y, fw, fh) in rects.items():
            d.rectangle([x * k, y * k, (x + fw) * k - 1, (y + fh) * k - 1], outline=col, width=2)
            tag = FACE_ABBR[fname]
            d.rectangle([x * k + 2, y * k + 2, x * k + 4 + 6 * len(tag), y * k + 13], fill=(0, 0, 0, 200))
            d.text((x * k + 3, y * k + 2), tag, fill=col)
    out = Image.new("RGBA", (w + 20, h + 64), (40, 40, 40, 255))
    out.alpha_composite(img, (10, 44))
    od = ImageDraw.Draw(out)
    od.text((10, 6), title, fill=(255, 255, 255))
    x = 10
    for bname, box, used in boxes:
        u, v, bw, bh, bd = box
        col = REGION_COLORS[bname] if used else (150, 150, 150)
        txt = f"{bname} {bw}x{bh}x{bd} @({u},{v})" + ("" if used else " unused")
        od.rectangle([x, 26, x + 8, 34], fill=col)
        od.text((x + 12, 24), txt, fill=col)
        x += 12 + 6 * len(txt) + 14
    return out


# ---------------------------------------------------------------------------
# Tiny HumanoidModel renderer
# ---------------------------------------------------------------------------
def cube_polys(u, v, x, y, z, w, h, d, grow, mirror, pivot):
    """Polygons of ModelPart.Cube: list of ([4 xyz], [4 uv], rect)."""
    minx, miny, minz = x - grow, y - grow, z - grow
    maxx, maxy, maxz = x + w + grow, y + h + grow, z + d + grow
    if mirror:
        minx, maxx = maxx, minx
    v7 = (minx, miny, minz); v0 = (maxx, miny, minz); v1 = (maxx, maxy, minz)
    v2 = (minx, maxy, minz); v3 = (minx, miny, maxz); v4 = (maxx, miny, maxz)
    v5 = (maxx, maxy, maxz); v6 = (minx, maxy, maxz)
    f4 = u; f5 = u + d; f6 = u + d + w; f7 = u + d + w + w
    f8 = u + d + w + d; f9 = u + d + w + d + w
    f10 = v; f11 = v + d; f12 = v + d + h
    spec = [
        ([v4, v3, v7, v0], f5, f10, f6, f11),   # DOWN (model -Y = visual top)
        ([v1, v2, v6, v5], f6, f11, f7, f10),   # UP (visual bottom)
        ([v7, v3, v6, v2], f4, f11, f5, f12),   # WEST
        ([v0, v7, v2, v1], f5, f11, f6, f12),   # NORTH (front)
        ([v4, v0, v1, v5], f6, f11, f8, f12),   # EAST
        ([v3, v4, v5, v6], f8, f11, f9, f12),   # SOUTH (back)
    ]
    out = []
    px, py, pz = pivot
    for verts, u1, va, u2, vb in spec:
        uvs = [(u2, va), (u1, va), (u1, vb), (u2, vb)]
        # model space -> world: entity renderer applies scale(-1,-1,1) + 180deg yaw
        pts = [(vx + px, -(vy + py), -(vz + pz)) for vx, vy, vz in verts]
        rect = (min(u1, u2), min(va, vb), max(u1, u2), max(va, vb))
        out.append((pts, uvs, rect))
    return out


# (name, texOffs, origin, size, pivot, mirror)
PARTS = {
    "head": ((0, 0), (-4, -8, -4), (8, 8, 8), (0, 0, 0), False),
    "hat": ((32, 0), (-4, -8, -4), (8, 8, 8), (0, 0, 0), False),
    "body": ((16, 16), (-4, 0, -2), (8, 12, 4), (0, 0, 0), False),
    "right_arm": ((40, 16), (-3, -2, -2), (4, 12, 4), (-5, 2, 0), False),
    "left_arm": ((40, 16), (-1, -2, -2), (4, 12, 4), (5, 2, 0), True),
    "right_leg": ((0, 16), (-2, 0, -2), (4, 12, 4), (-1.9, 12, 0), False),
    "left_leg": ((0, 16), (-2, 0, -2), (4, 12, 4), (1.9, 12, 0), True),
}
SLOT_PARTS = {
    "head": ["head", "hat"],
    "chest": ["body", "right_arm", "left_arm"],
    "legs": ["body", "right_leg", "left_leg"],
    "feet": ["right_leg", "left_leg"],
}


def mannequin_skin():
    """Plain stand-in body (64x32 legacy layout) so the face shows through the hood."""
    s = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(s)
    skin, hair = (214, 160, 120, 255), (70, 45, 25, 255)
    d.rectangle([0, 0, 31, 15], fill=skin)
    d.rectangle([8, 0, 15, 7], fill=hair)
    for x0 in (0, 8, 16, 24):
        d.rectangle([x0, 8, x0 + 7, 9], fill=hair)
    d.rectangle([24, 8, 31, 15], fill=hair)
    for x, c in ((9, (255, 255, 255)), (10, (40, 60, 160)), (13, (40, 60, 160)), (14, (255, 255, 255))):
        s.putpixel((x, 12), c + (255,))
    d.rectangle([11, 14, 12, 14], fill=(150, 90, 70, 255))
    d.rectangle([16, 16, 39, 31], fill=(40, 140, 150, 255))
    d.rectangle([40, 16, 55, 31], fill=skin)
    d.rectangle([40, 20, 55, 23], fill=(40, 140, 150, 255))
    d.rectangle([44, 16, 47, 19], fill=(40, 140, 150, 255))
    d.rectangle([0, 16, 15, 31], fill=(60, 60, 140, 255))
    d.rectangle([0, 30, 15, 31], fill=(90, 90, 90, 255))
    return s


def shade_for(normal):
    nx, ny, nz = normal
    a = np.abs(normal)
    if a[1] >= a[0] and a[1] >= a[2]:
        return 1.0 if ny > 0 else 0.5
    if a[2] >= a[0]:
        return 0.8
    return 0.6


class Renderer:
    def __init__(self, w, h, scale, yaw_deg, pitch_deg):
        self.w, self.h, self.s = w, h, scale
        t, p = math.radians(yaw_deg), math.radians(pitch_deg)
        cam = np.array([math.cos(p) * math.sin(t), math.sin(p), math.cos(p) * math.cos(t)])
        self.f = -cam
        r = np.cross(self.f, [0, 1, 0]); self.r = r / np.linalg.norm(r)
        self.u = np.cross(self.r, self.f)
        self.color = np.zeros((h, w, 4), np.float32)
        self.color[..., :3] = (150, 150, 150)
        self.color[..., 3] = 255
        self.z = np.full((h, w), np.inf, np.float32)
        self.cx, self.cy = w / 2, h * 0.28

    def proj(self, p):
        p = np.asarray(p, float)
        return (self.cx + self.s * p.dot(self.r), self.cy - self.s * (p.dot(self.u) - 0), p.dot(self.f))

    def draw_polys(self, polys, tex):
        t = np.asarray(tex).astype(np.float32)
        th, tw = t.shape[:2]
        for pts, uvs, rect in polys:
            P = [self.proj(p) for p in pts]
            n = np.cross(np.subtract(pts[1], pts[0]), np.subtract(pts[2], pts[0]))
            if np.linalg.norm(n) == 0:
                continue
            shade = shade_for(n / np.linalg.norm(n))
            for tri in ((0, 1, 2), (0, 2, 3)):
                self._tri([P[i] for i in tri], [uvs[i] for i in tri], rect, t, shade)

    def _tri(self, P, UV, rect, t, shade):
        (x0, y0, z0), (x1, y1, z1), (x2, y2, z2) = P
        minx = max(int(math.floor(min(x0, x1, x2))), 0); maxx = min(int(math.ceil(max(x0, x1, x2))), self.w - 1)
        miny = max(int(math.floor(min(y0, y1, y2))), 0); maxy = min(int(math.ceil(max(y0, y1, y2))), self.h - 1)
        if minx > maxx or miny > maxy:
            return
        den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if abs(den) < 1e-9:
            return
        xs, ys = np.meshgrid(np.arange(minx, maxx + 1) + 0.5, np.arange(miny, maxy + 1) + 0.5)
        a = ((y1 - y2) * (xs - x2) + (x2 - x1) * (ys - y2)) / den
        b = ((y2 - y0) * (xs - x2) + (x0 - x2) * (ys - y2)) / den
        c = 1 - a - b
        inside = (a >= -1e-6) & (b >= -1e-6) & (c >= -1e-6)
        if not inside.any():
            return
        z = a * z0 + b * z1 + c * z2
        u = a * UV[0][0] + b * UV[1][0] + c * UV[2][0]
        v = a * UV[0][1] + b * UV[1][1] + c * UV[2][1]
        ru0, rv0, ru1, rv1 = rect
        ui = np.clip(np.floor(u), ru0, ru1 - 1).astype(int)
        vi = np.clip(np.floor(v), rv0, rv1 - 1).astype(int)
        texel = t[vi, ui]
        sub_z = self.z[miny:maxy + 1, minx:maxx + 1]
        ok = inside & (texel[..., 3] > 25) & (z < sub_z - 1e-4)
        sub_c = self.color[miny:maxy + 1, minx:maxx + 1]
        sub_c[ok, :3] = texel[ok, :3] * shade
        sub_z[ok] = z[ok]

    def image(self):
        return Image.fromarray(np.clip(self.color, 0, 255).astype(np.uint8), "RGBA")


def render(layer1, layer2, slots, yaw, pitch=18, w=200, h=300, scale=7.0, center_y=None):
    r = Renderer(w, h, scale, yaw, pitch)
    if center_y is not None:
        r.cy = h / 2 + center_y * scale
    skin = mannequin_skin()
    for name in ("head", "body", "right_arm", "left_arm", "right_leg", "left_leg"):
        (u, v), o, s, pv, m = PARTS[name]
        r.draw_polys(cube_polys(u, v, *o, *s, 0.0, m, pv), skin)
    vis1, vis2 = set(), set()
    for slot in slots:
        (vis2 if slot == "legs" else vis1).update(SLOT_PARTS[slot])
    for parts, tex, base in ((vis1, layer1, 1.0), (vis2, layer2, 0.5)):
        for name in parts:
            (u, v), o, s, pv, m = PARTS[name]
            g = base + (0.5 if name == "hat" else 0.0)
            r.draw_polys(cube_polys(u, v, *o, *s, g, m, pv), tex)
    return r.image()


def main():
    l1, l2 = armor.build_layer1(), armor.build_layer2()
    boxes1 = [("head", armor.HEAD, True), ("hat", armor.HAT, False), ("body", armor.BODY, True),
              ("arm", armor.ARM, True), ("leg", armor.LEG, True)]
    boxes2 = [("body", armor.BODY, True), ("arm", armor.ARM, False), ("leg", armor.LEG, True)]
    a = uv_sheet(l1, "down_suit_layer_1.png  (helmet=head, chestplate=body+arms, boots=leg)  hat region intentionally empty", boxes1)
    b = uv_sheet(l2, "down_suit_layer_2.png  (leggings=body+leg)  arm region unused", boxes2)
    sheet = Image.new("RGBA", (a.width, a.height + b.height), (40, 40, 40, 255))
    sheet.alpha_composite(a, (0, 0))
    sheet.alpha_composite(b, (0, a.height))
    save_preview(sheet, "armor_uv_check.png")

    full = ["head", "chest", "legs", "feet"]
    shots = [
        ("full, front-right", full, -35),
        ("full, front-left", full, 35),
        ("full, back", full, 150),
        ("hood + jacket", ["head", "chest"], -25),
        ("pants + boots", ["legs", "feet"], -25),
        ("pants only", ["legs"], 155),
    ]
    tiles = []
    for label, slots, yaw in shots:
        img = render(l1, l2, slots, yaw)
        ImageDraw.Draw(img).text((6, 4), label, fill=(20, 20, 20))
        tiles.append(img)
    tw, th = tiles[0].size
    out = Image.new("RGBA", (tw * 3, th * 2), (40, 40, 40, 255))
    for i, t in enumerate(tiles):
        out.alpha_composite(t, ((i % 3) * tw, (i // 3) * th))
    save_preview(out, "armor_3d_check.png")


if __name__ == "__main__":
    main()
