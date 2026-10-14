"""Rough offline preview of the blown-snow particles (WindParticles + SnowFxParticle), to judge density and softness.

Simulates the spawner's numbers on a made-up scene (a snow crest dropping away downwind, a snowfield in front of the
camera), then draws every particle the way SnowFxParticle.render does: a quad along its motion, turned to the camera,
textured with the real sprites, alpha-blended. Not the game's lighting or shading.

    python art/wind_particles_preview.py
"""
import math
import os

import numpy as np
from PIL import Image, ImageDraw

from common import PREVIEW_DIR, TEX_ROOT

W, H = 960, 540
FOV = math.radians(70)
RNG = np.random.default_rng(7)


def sprite(name):
    return np.asarray(Image.open(os.path.join(TEX_ROOT, "particle", name + ".png")).convert("RGBA"), np.float32) / 255


SPRITES = {"grain": sprite("snow_grain"), "streak": sprite("snow_streak")}
WISPS = [sprite(f"snow_wisp_{i}") for i in range(4)]


def smooth(e0, e1, x):
    t = min(max((x - e0) / (e1 - e0), 0.0), 1.0)
    return t * t * (3 - 2 * t)


def ground(x, z):
    """A crest running along z at x = 0, 6 blocks high, dropping away to +x (downwind)."""
    if x < 0:
        return 6.0 - max(0.0, -x - 8) * 0.15
    return 6.0 - min(6.0, x * 1.2)


class P:
    __slots__ = ("pos", "vel", "kind", "length", "width", "alpha", "life", "age", "growth", "speed", "lift", "couple",
                 "hover", "tex")


def spawn_ridge(ps, kmh, gust):
    bpt = min(2.4, kmh / 100)
    for _ in range(22):
        z = RNG.uniform(-30, 30)
        if RNG.random() > 0.9 * smooth(40, 110, kmh * gust):
            continue
        speed = bpt * gust * (0.75 + RNG.random() * 0.35)
        x0, y0 = 1.2 + (RNG.random() - 0.5) * 1.6, 6.15 + RNG.random() * 0.5
        if RNG.random() < 0.65:
            p = P()
            p.pos = np.array([x0, y0 + 0.3, z]); p.vel = np.array([speed, 0.05, 0.0]); p.kind = "wisp"
            p.length = 2.2 + RNG.random() * 2.5; p.width = 0.6 + RNG.random() * 0.8
            p.alpha = (0.16 + RNG.random() * 0.18) * (0.6 + 0.4 * smooth(40, 110, kmh)); p.life = 30 + RNG.integers(26)
            p.growth = 1.6; p.speed = speed; p.lift = 0.02; p.couple = 0.06; p.hover = -1; p.tex = WISPS[RNG.integers(4)]
            p.age = 0; ps.append(p)
        for _ in range(1 + RNG.integers(3)):
            s = speed * (0.85 + RNG.random() * 0.4)
            p = P()
            p.pos = np.array([x0 + (RNG.random() - 0.5) * 0.8, y0 + RNG.random() * 0.6, z + (RNG.random() - 0.5) * 0.8])
            p.vel = np.array([s, 0.04 + RNG.random() * 0.08, 0.0]); p.kind = "grain"; p.length = s * 0.55
            p.width = 0.035 + RNG.random() * 0.035; p.alpha = 0.55 + RNG.random() * 0.35; p.life = 18 + RNG.integers(18)
            p.growth = 0; p.speed = s; p.lift = 0.025; p.couple = 0.12; p.hover = -1; p.tex = SPRITES["grain"]
            p.age = 0; ps.append(p)


def spawn_drift(ps, kmh, t):
    wind = smooth(32, 95, kmh)
    for _ in range(int(16 * wind)):
        x, z = RNG.uniform(-40, -6), RNG.uniform(-14, 26)
        band = math.sin(z / 3.2 * 1.7 + t * 0.01) * 0.5 + math.sin(z / 1.9 + 1.3) * 0.3
        gustp = 0.5 + 0.5 * math.sin((x - t * 0.8) / 18.0)
        if RNG.random() > smooth(0.3, 0.9, gustp) * min(max((band + 0.05) * 3, 0), 1):
            continue
        speed = min(2.4, kmh / 100) * (0.6 + RNG.random() * 0.3)
        hover = 0.12 + RNG.random() * 0.25
        p = P()
        p.pos = np.array([x, ground(x, z) + hover, z]); p.vel = np.array([speed, 0.0, 0.0]); p.kind = "drift"
        p.length = 2.4 + RNG.random() * 2.8; p.width = 0.28 + RNG.random() * 0.3; p.alpha = 0.14 + RNG.random() * 0.16
        p.life = 24 + RNG.integers(24); p.growth = 0.5; p.speed = speed; p.lift = 0; p.couple = 0.1; p.hover = hover
        p.tex = WISPS[RNG.integers(4)]; p.age = 0; ps.append(p)


def spawn_streaks(ps, kmh, cam, fwd):
    if kmh < 80:
        return
    rate = (kmh - 80) / 45 * 2.2
    n = int(rate) + (1 if RNG.random() < rate - int(rate) else 0)
    for _ in range(n):
        yaw = (RNG.random() - 0.5) * 1.6; pitch = (RNG.random() - 0.5) * 0.9
        d = np.array([math.cos(yaw) * fwd[0] - math.sin(yaw) * fwd[2], fwd[1] + pitch * 0.6, math.sin(yaw) * fwd[0] + math.cos(yaw) * fwd[2]])
        d /= np.linalg.norm(d)
        dist = 2.5 + RNG.random() * 10
        life = 7 + RNG.integers(7)
        s = min(2.4, kmh / 100) * 1.6 * (0.8 + RNG.random() * 0.5)
        p = P()
        p.pos = cam + d * dist - np.array([s * life * 0.5, 0, 0]) + np.array([0, (RNG.random() - 0.5) * 1.5, 0])
        p.vel = np.array([s, (RNG.random() - 0.5) * 0.03, 0]); p.kind = "streak"; p.length = s * 2.4
        p.width = 0.014 + RNG.random() * 0.01; p.alpha = 0.07 + RNG.random() * 0.12; p.life = life; p.growth = 0
        p.speed = s; p.lift = 0; p.couple = 0.3; p.hover = -1; p.tex = SPRITES["streak"]; p.age = 0; ps.append(p)


def step(ps, gust):
    alive = []
    for p in ps:
        p.age += 1
        if p.age >= p.life:
            continue
        target = p.speed * gust
        p.vel[0] += (target - p.vel[0]) * p.couple
        p.vel[2] += (0 - p.vel[2]) * p.couple
        left = 1 - p.age / p.life
        p.vel[1] += p.lift * left * 0.25
        if p.kind == "drift" or p.hover >= 0:
            want = ground(p.pos[0] + p.vel[0], p.pos[2]) + p.hover
            if want < p.pos[1] - 1.2:
                p.hover = -1
                p.kind = "wisp" if p.kind == "drift" else p.kind
            else:
                p.vel[1] = (want - p.pos[1]) * 0.35
        else:
            p.vel[1] -= 0.004 if p.kind == "grain" else 0.0008
        p.vel[1] *= 0.92
        p.pos = p.pos + p.vel
        alive.append(p)
    return alive


def look_at(cam, target):
    f = target - cam; f /= np.linalg.norm(f)
    r = np.cross(f, [0, 1, 0]); r /= np.linalg.norm(r)
    u = np.cross(r, f)
    return f, r, u


def project(pt, cam, f, r, u):
    v = pt - cam
    z = v @ f
    if z < 0.05:
        return None
    k = (H / 2) / math.tan(FOV / 2)
    return np.array([W / 2 + (v @ r) / z * k, H / 2 - (v @ u) / z * k])


def background(cam, f, r, u):
    img = np.zeros((H, W, 3), np.float32)
    k = (H / 2) / math.tan(FOV / 2)
    ys, xs = np.mgrid[0:H, 0:W]
    dirs = (f[None, None, :] + ((xs - W / 2) / k)[..., None] * r + (-(ys - H / 2) / k)[..., None] * u)
    dirs /= np.linalg.norm(dirs, axis=-1, keepdims=True)
    sky = np.array([0.42, 0.6, 0.92]) * (1 - dirs[..., 1:2]) * 0.5 + np.array([0.3, 0.48, 0.85]) * (0.5 + dirs[..., 1:2] * 0.5)
    img[:] = sky
    # March the made-up ground.
    hit = np.zeros((H, W), bool)
    shade = np.zeros((H, W), np.float32)
    for t in np.concatenate([np.linspace(0.2, 20, 120), np.linspace(20, 200, 120)]):
        p = cam[None, None, :] + dirs * t
        g = np.where(p[..., 0] < 0, 6.0 - np.maximum(0, -p[..., 0] - 8) * 0.15, 6.0 - np.minimum(6.0, p[..., 0] * 1.2))
        newly = (~hit) & (p[..., 1] < g)
        # Lee slope in shadow, the crest's top lit; a little texture so white-on-white reads.
        tex = 0.03 * np.sin(p[..., 2][newly] * 1.7) * np.sin(p[..., 0][newly] * 2.3)
        shade[newly] = np.where(p[..., 0][newly] > 0, 0.62, 0.93) + tex - np.minimum(t / 400, 0.2)
        hit |= newly
    img[hit] = np.array([0.88, 0.91, 0.96])[None, :] * shade[hit][:, None]
    return img


def draw(img, ps, cam, f, r, u):
    pil = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).convert("RGBA")
    layer = np.asarray(pil, np.float32) / 255
    out = layer.copy()
    order = sorted(ps, key=lambda p: -np.linalg.norm(p.pos - cam))
    for p in order:
        t = p.age / p.life
        a = p.alpha * min(t / 0.15, 1) * min((1 - t) / 0.4, 1)
        dist = np.linalg.norm(p.pos - cam)
        near = {"wisp": smooth(1.0, 4.0, dist), "drift": smooth(1.0, 4.0, dist), "streak": smooth(0.8, 2.0, dist),
                "grain": smooth(0.25, 0.6, dist)}[p.kind]
        a *= near
        if a < 0.003:
            continue
        ax = p.vel / (np.linalg.norm(p.vel) + 1e-9)
        v = (p.pos - cam) / dist
        s = np.cross(ax, v)
        sl = np.linalg.norm(s)
        if sl < 1e-4:
            continue
        s /= sl
        grow = 1 + p.growth * t
        along = 0.5 * max(p.width, p.length * sl) * grow
        across = 0.5 * p.width * grow
        corners = [p.pos - ax * along - s * across, p.pos - ax * along + s * across, p.pos + ax * along + s * across,
                   p.pos + ax * along - s * across]
        pts = [project(c, cam, f, r, u) for c in corners]
        if any(q is None for q in pts):
            continue
        pts = np.array(pts)
        x0, y0 = np.floor(pts.min(0)).astype(int)
        x1, y1 = np.ceil(pts.max(0)).astype(int)
        if x1 < 0 or y1 < 0 or x0 >= W or y0 >= H or (x1 - x0) * (y1 - y0) > 400000:
            continue
        x0, y0 = max(x0, 0), max(y0, 0)
        x1, y1 = min(x1, W - 1), min(y1, H - 1)
        if x1 <= x0 or y1 <= y0:
            continue
        # Inverse bilinear mapping via two triangles is overkill here: map each pixel to (along, across) directly.
        ys, xs = np.mgrid[y0:y1 + 1, x0:x1 + 1]
        P0, P1, P3 = pts[0], pts[1], pts[3]
        e1, e2 = P3 - P0, P1 - P0  # u along, v across
        det = e1[0] * e2[1] - e1[1] * e2[0]
        if abs(det) < 1e-6:
            continue
        dx, dy = xs - P0[0], ys - P0[1]
        uu = (dx * e2[1] - dy * e2[0]) / det
        vv = (e1[0] * dy - e1[1] * dx) / det
        inside = (uu >= 0) & (uu <= 1) & (vv >= 0) & (vv <= 1)
        if not inside.any():
            continue
        th, tw = p.tex.shape[:2]
        tx = np.clip((uu * tw).astype(int), 0, tw - 1)
        ty = np.clip(((1 - vv) * th).astype(int), 0, th - 1)
        texa = p.tex[ty, tx, 3] * a * inside
        col = np.array([0.95, 0.965, 0.99])
        region = out[y0:y1 + 1, x0:x1 + 1, :3]
        out[y0:y1 + 1, x0:x1 + 1, :3] = region * (1 - texa[..., None]) + col * texa[..., None]
    return Image.fromarray((np.clip(out[..., :3], 0, 1) * 255).astype(np.uint8))


def scene(name, kmh, cam, target, ticks=70, ridge=True, drift=True, streaks=True):
    ps = []
    for t in range(ticks):
        gust = 1.0 + 0.3 * math.sin(t / 9.0) * math.sin(t / 23.0 + 1)
        if ridge:
            spawn_ridge(ps, kmh, gust)
        if drift:
            spawn_drift(ps, kmh * gust, t)
        f, r, u = look_at(cam, target)
        if streaks:
            spawn_streaks(ps, kmh * gust, cam, f)
        ps = step(ps, gust)
    f, r, u = look_at(cam, target)
    img = draw(background(cam, f, r, u), ps, cam, f, r, u)
    path = os.path.join(PREVIEW_DIR, f"wind_particles_{name}.png")
    img.save(path)
    print(name, len(ps), "particles ->", path)


if __name__ == "__main__":
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    # Standing below the crest, a little upwind of where it drops, looking along it with the wind blowing left to right.
    # Below the crest on its lee side, looking across the wind at the edge against the sky.
    side_cam, side_target = np.array([7.0, 1.2, -16.0]), np.array([3.0, 6.5, 6.0])
    scene("ridge_60", 60, side_cam, side_target, streaks=False)
    scene("ridge_110", 110, side_cam, side_target)
    # On the snowfield, wind from the left, looking across the snow toward the crest.
    scene("snowfield_95", 95, np.array([-24.0, 8.8, -10.0]), np.array([-8.0, 6.0, 12.0]))
    scene("storm_170", 170, side_cam, side_target)
