"""The giant Skein of the Deep Blue Rift (0.18.8, Taran's 2026-11-18 ask): its shape, texture, glow and previews.

A Skein four times as long (about 30 blocks), slimmer for its length, its shell a pale ice blue (lighter than the
Skein's cobalt: light blue, never cyan), and spiky all over: two rows of long curved spines down its back, thorns along
every plate's rim, barbed legs, a crown of horns and a fan of long spikes at its tail. The tips of its spikes and its
eyes glow a soft light blue (Taran's pick), so across the dark rift it shows as a slow line of lights. The mod's own
creature; nothing here is taken from any other game.

It is drawn as the Skein is (client.render.GiantSkeinRenderer): a few loose pieces laid along its trail, each standing
on the rock at its own pivot (0, 24, 0), facing -z: the head, three sizes of plate and the tail. Its pieces are built at
full size, so a texel is a sixteenth of a block, as the world's are, and it stays as crisp as the rock it climbs.

Called by creatures.write_all; python3 tools/giant_skein.py <dir> draws previews.
"""
import math
import os
import random
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import creatures as cr  # noqa: E402
from creatures import B, C, P, ramp, noise2, faces  # noqa: E402

MOD = cr.MOD
TEX_W, TEX_H = 512, 512

# The body as the renderer lays it (GiantSkeinEntity: the Skein's 23 plates, four times as far apart): which piece
# each plate is and how wide it is drawn. Widths are px; a plate's length is GIANT_PLATE_D (they lie 19.2 px apart, so
# they overlap).
PLATES, SCALE = 23, 4.0
PLATE_START, PLATE_STEP = 0.15 * SCALE, 0.30 * SCALE
WIDTHS = {"plate_large": 40, "plate_mid": 33, "plate_small": 26, "tail": 20}
WIDE, NARROW = 40.0, 18.0
PLATE_D, SHELL_H, LIFT = 30, 8, 8
FEMUR, SHIN = 22, 24


def piece(k):
    return "tail" if k == PLATES - 1 else ("plate_large", "plate_mid", "plate_small")[min(2, k * 3 // (PLATES - 1))]


def width(k):
    return WIDE + (NARROW - WIDE) * k / (PLATES - 1)


# =============================================================================================== shape
def euler(d):
    """The rest rotation (degrees, as a part's) that turns a part's -y (up in model space) along direction d (model
    space: +x its left, +y down, +z back): ModelPart turns by z after y after x, and with no y turn x tips -y towards
    z and z then swings it towards x."""
    l = math.sqrt(sum(c * c for c in d))
    dx, dy, dz = (c / l for c in d)
    return (math.degrees(-math.asin(max(-1.0, min(1.0, dz)))), 0.0, math.degrees(math.atan2(dx, -dy)))


def spike(name, parent, pivot, direction, lengths, thick, mat="spine", tip="spine_tip", bend=-12.0):
    """A tapered spike pointing along direction from pivot: one segment per length, each a square of thick[i] px across
    and a child of the one before, bent bend degrees further back at each joint; the last is the tip (it glows)."""
    out = []
    par, piv, rot = parent, pivot, euler(direction)
    for i, (length, t) in enumerate(zip(lengths, thick)):
        nm = f"{name}_{i}"
        out.append(P(nm, par, piv, rot, [B(tip if i == len(lengths) - 1 else mat, (-t / 2, -length, -t / 2), (t, length, t))]))
        # the next starts a pixel inside this one's end, so the joint shows no gap as it bends
        par, piv, rot = nm, (0, -length + 1, 0), (bend, 0, 0)
    return out


SPINE_SETS = {
    # (back spine lengths and thicknesses, their spread from the middle (px), rim thorn lengths)
    "plate_large": ((13, 12, 10), (5, 3, 2), 9, (8, 6)),
    "plate_mid": ((12, 10, 9), (4, 3, 2), 8, (7, 6)),
    "plate_small": ((10, 9, 7), (4, 3, 2), 6, (6, 5)),
}


def legs(prefix, half):
    """A plate's pair of long barbed legs from under its edge: each thigh out and up to a high knee, each shin down and
    out to the rock (its foot well out from the shell), a barb jutting back from the knee, its claw pale."""
    x = half - 4
    out = []
    for side, s in (("left", 1), ("right", -1)):
        out += [
            P(f"{prefix}_leg_{side}", prefix, (s * x, -LIFT, 0), (0, 0, -s * 20),
              [B("leg", (0 if s > 0 else -FEMUR, -1.5, -1.5), (FEMUR, 3, 3))]),
            P(f"{prefix}_shin_{side}", f"{prefix}_leg_{side}", (s * (FEMUR - 1), 0, 0), (0, 0, -s * 30),
              [B("shin", (-1, 0, -1), (2, SHIN, 2))]),
        ]
        # a hooked barb off the back of the knee, and a smaller one half way down the shin
        out += spike(f"{prefix}_barb_{side}", f"{prefix}_shin_{side}", (0, 1, 0), (s * 0.2, -0.55, 0.8), (5, 4), (2, 1),
                     mat="barb", tip="barb_tip", bend=-14)
        out += spike(f"{prefix}_barb_low_{side}", f"{prefix}_shin_{side}", (0, SHIN // 2, 0), (s * 0.3, -0.2, 0.9), (4,), (1,),
                     mat="barb_tip", tip="barb_tip", bend=0)
    return out


def plate(name):
    """One shell plate: the domed shell (a crown over a broad plate), its pale belly, two long spines curving back from
    its crown, two thorns from each side of its rim, and its legs. The shell is its own part, so the renderer can
    taper it plate by plate; the spines and thorns are moved out with its edge."""
    w, d = WIDTHS[name], PLATE_D
    half = w / 2
    parts = [
        P(name, "root", (0, 24, 0), (0, 0, 0), []),
        P(f"{name}_shell", name, (0, 0, 0), (6, 0, 0), [
            B("shell", (-half, -LIFT - SHELL_H, -d / 2), (w, SHELL_H, d)),
            B("crown", (-half + 6, -LIFT - SHELL_H - 3, -d / 2 + 2), (w - 12, 3, d - 4)),
            B("belly", (-(half - 5), -LIFT - 1, -d / 2 + 2), (w - 10, 3, d - 4)),
        ]),
    ]
    if name in SPINE_SETS:
        lengths, thick, spread, thorn = SPINE_SETS[name]
        for side, s in (("left", 1), ("right", -1)):
            parts += spike(f"{name}_spine_{side}", name, (s * spread, -LIFT - SHELL_H - 2, -2), (s * 0.3, -0.75, 0.6), lengths, thick)
            for k, z in enumerate((-8, 6)):
                parts += spike(f"{name}_thorn_{side}{k}", name, (s * (half - 1), -LIFT - 4, z), (s * 1.0, -0.3, 0.55), thorn, (3, 2),
                               mat="thorn", tip="thorn_tip", bend=-10)
    else:
        # the tail: a fan of five long spikes from its back end, out and a little up, the middle one longest
        for k, ang in enumerate((-42, -21, 0, 21, 42)):
            a = math.radians(ang)
            n = (16, 15, 12) if ang == 0 else ((15, 13, 10) if abs(ang) < 30 else (13, 11, 8))
            parts += spike(f"tail_fan{k}", name, (round(math.sin(a) * 5), -LIFT - SHELL_H + 1, d / 2 - 3),
                           (math.sin(a), -0.28, math.cos(a)), n, (4, 3, 2), bend=-6)
        for side, s in (("left", 1), ("right", -1)):
            parts += spike(f"tail_thorn_{side}", name, (s * (half - 1), -LIFT - 4, -4), (s * 1.0, -0.3, 0.55), (6, 5), (3, 2),
                           mat="thorn", tip="thorn_tip", bend=-10)
    return parts + legs(name, half)


HEAD = [
    # the head: a wedge of stepped plates over a long skull, a crown of horns curving back off it, three eyes down each
    # side, great sickle mandibles under the snout and two long whip feelers from the brow
    P("head", "root", (0, 24, 0), (0, 0, 0), [
        B("shield", (-20, -LIFT - SHELL_H - 3, -6), (40, 5, 16)),
        B("shield", (-16, -LIFT - SHELL_H - 2, -20), (32, 5, 14)),
        B("shield", (-10, -LIFT - SHELL_H - 1, -30), (20, 5, 10)),
        B("skull", (-14, -LIFT - 6, -30), (28, 9, 30)),
        B("eye", (13, -LIFT - 5, -27), (2, 3, 3)),
        B("eye", (13, -LIFT - 5, -22), (2, 2, 3)),
        B("eye", (12, -LIFT - 2, -25), (2, 2, 2)),
        B("eye", (-15, -LIFT - 5, -27), (2, 3, 3)),
        B("eye", (-15, -LIFT - 5, -22), (2, 2, 3)),
        B("eye", (-14, -LIFT - 2, -25), (2, 2, 2)),
    ]),
    P("mandible_left", "head", (8, -LIFT + 2, -28), (0, -12, 0), [B("mandible", (-2.5, -2, -16), (5, 4, 16))]),
    P("mandible_left_tip", "mandible_left", (0, 0, -15), (0, 42, 0), [B("mandible", (-1.5, -1.5, -12), (3, 3, 12)),
                                                                     B("fang", (-1, -1, -15), (2, 2, 3))]),
    P("mandible_right", "head", (-8, -LIFT + 2, -28), (0, 12, 0), [B("mandible", (-2.5, -2, -16), (5, 4, 16))]),
    P("mandible_right_tip", "mandible_right", (0, 0, -15), (0, -42, 0), [B("mandible", (-1.5, -1.5, -12), (3, 3, 12)),
                                                                        B("fang", (-1, -1, -15), (2, 2, 3))]),
    P("feeler_left", "head", (6, -LIFT - SHELL_H, -29), (-22, -45, 0), [B("feeler", (-1, -1, -40), (2, 2, 40))]),
    P("feeler_left_tip", "feeler_left", (0, 0, -39), (14, -32, 0), [B("feeler_tip", (-1, -1, -34), (2, 2, 34))]),
    P("feeler_right", "head", (-6, -LIFT - SHELL_H, -29), (-22, 45, 0), [B("feeler", (-1, -1, -40), (2, 2, 40))]),
    P("feeler_right_tip", "feeler_right", (0, 0, -39), (14, 32, 0), [B("feeler_tip", (-1, -1, -34), (2, 2, 34))]),
]
for _side, _s in (("left", 1), ("right", -1)):
    HEAD += spike(f"horn_{_side}", "head", (_s * 10, -LIFT - SHELL_H - 2, 0), (_s * 0.35, -0.7, 0.62), (12, 11, 9), (5, 3, 2), bend=-14)
    HEAD += spike(f"horn_small_{_side}", "head", (_s * 5, -LIFT - SHELL_H - 1, -14), (_s * 0.3, -0.75, 0.6), (8, 7), (3, 2), bend=-12)

PARTS = list(HEAD)
for _p in ("plate_large", "plate_mid", "plate_small", "tail"):
    PARTS += plate(_p)

PALETTE = {
    # pale ice blue over steel blue: light blue throughout (hue about 215), never the cyan Taran dislikes
    "shell": ["#152039", "#1c2b4b", "#25395f", "#304976", "#3d5c8f", "#4a70b0", "#5a86c9", "#6f9ce0", "#88b3ee", "#a5c8f7", "#c3dcfc"],
    "rim": ["#a7c3ea", "#c6daf5", "#e3edfb"],
    "dark": ["#0a1020", "#101a30", "#17243f"],
    "belly": ["#4b5a77", "#5d6d8c", "#7181a1", "#8796b4", "#9daac5"],
    "leg": ["#16213a", "#1f2d4c", "#2b3e63", "#3a527e", "#4d699a", "#6585b5"],
    "spine": ["#24365a", "#2f4670", "#3d5a89", "#4f71a3", "#6689bc", "#80a3d2", "#9fbde4", "#bdd3f1", "#d6e5f8"],
    # the glow a clear light blue (it read white when paler): a little deeper than the rift's vines, never cyan
    "glow": ["#4f8fff", "#78acff", "#a6caff"],
    "glow_dim": ["#3f74d6", "#5b8fe8"],
    "claw": ["#a9bfdf", "#d4e2f5"],
    "mandible": ["#111a30", "#1b2846", "#2b3d63", "#41598a", "#5b77a8"],
    "fang": ["#c6d6ee", "#edf3fc"],
    "eye": ["#5d9bff", "#94c0ff", "#cfe2ff"],
    "pupil": "#08101e",
    "mouth": ["#0b1122", "#131c34", "#1d2a48"],
}


# =============================================================================================== painting
class Canvas(cr.Canvas):
    """The creature tool's canvas at the giant's own size."""

    def __init__(self):
        self.img = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.eyes = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.flare = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.px, self.ex, self.fx = self.img.load(), self.eyes.load(), self.flare.load()

    def put(self, x, y, c, glow=None, dim=None):
        if 0 <= x < TEX_W and 0 <= y < TEX_H:
            self.px[x, y] = dim if dim is not None else c
            if glow == "eyes": self.ex[x, y] = c
            elif glow == "flare": self.fx[x, y] = c

    def clear(self, x, y):
        if 0 <= x < TEX_W and 0 <= y < TEX_H:
            self.px[x, y] = (0, 0, 0, 0)


def _shell(cv, box, pal, rng, front_rim=False, crown=False):
    """A shell plate, the crown over it or the head's plates: pale ice blue along the crown, darkening to steel blue down
    its sides, finely pitted and faintly ridged across, with a pale rim along its back edge (all round on the head's)
    that rides over the plate behind."""
    w, h, d = box["size"]
    n = noise2(rng, w + 2 * d, d + h, 3.0)

    def fn(face, i, j, fw, fh):
        r = rng.random()
        nn = n[min(j, len(n) - 1)][min(i, len(n[0]) - 1)]
        if face == "top":
            mid = 1.0 - abs((i + 0.5) / w - 0.5) * 2.0
            if j == 0 or (front_rim and (j == d - 1 or i in (0, w - 1))):
                return C(pal["rim"][1 if r < 0.7 else (0 if r < 0.9 else 2)])
            t = (0.58 if crown else 0.38) + 0.45 * mid + 0.22 * (nn - 0.5) - (0.12 if r < 0.07 else 0.0)
            if j % 6 == 3: t -= 0.06                                   # faint growth ridges across the plate
            if j == 1 and r < 0.5: t += 0.1                             # a glint just inside the back rim
            return ramp(pal["shell"], t)
        if face in ("west", "east") or (front_rim and face == "north"):
            if j == 0:
                return C(pal["rim"][0])
            return ramp(pal["shell"], (0.38 if crown else 0.28) + 0.15 * nn - 0.07 * j)
        if face == "south":
            return ramp(pal["shell"], 0.35 - 0.06 * j + 0.08 * r)
        if face == "north":
            return ramp(pal["dark"], 0.5 + 0.4 * r)
        return ramp(pal["dark"], 0.3 + 0.5 * r)

    cr._texels(cv, box, fn)


def _belly(cv, box, pal, rng):
    def fn(face, i, j, fw, fh):
        k = cr._along(face, i, j, box["size"])
        return ramp(pal["belly"], 0.35 + 0.45 * rng.random() - (0.3 if k % 4 == 0 else 0.0))

    cr._texels(cv, box, fn)


def _spike(cv, box, pal, rng, tip, glow_rows=6, dim=False):
    """A spike's segment, pointing up its box: steel blue at its root paling towards its end; the tip's last few
    texels glow (the barbs' and thorns' a little dimmer), its very end brightest."""
    w, h, d = box["size"]
    g = pal["glow_dim"] if dim else pal["glow"]

    def fn(face, i, j, fw, fh):
        if face == "top":                                   # its far end
            return (C(g[-1]), "eyes", C(pal["rim"][2])) if tip else ramp(pal["spine"], 0.85)
        if face == "bottom":
            return ramp(pal["spine"], 0.2)
        if tip and j < glow_rows:
            k = len(g) - 1 if j == 0 else max(0, len(g) - 1 - j * len(g) // glow_rows)
            return (C(g[k]), "eyes", C(pal["rim"][1]))
        along = 1.0 - j / max(1, h - 1)                     # 1 at the far end, 0 at the root
        lit = 0.08 if face in ("north", "west") else -0.06
        base = 0.45 if tip else 0.15
        return ramp(pal["spine"], base + 0.5 * along + lit + 0.08 * (rng.random() - 0.5))

    cr._texels(cv, box, fn)


def _leg(cv, box, pal, rng, shin):
    w, h, d = box["size"]
    length = h if shin else w

    def fn(face, i, j, fw, fh):
        if shin:
            at = j if face not in ("top", "bottom") else (0 if face == "top" else length - 1)
        else:
            at = i if face in ("top", "bottom", "north") else (w - 1 - i if face == "south" else 0)
        if shin and at >= length - 3:
            return C(pal["claw"][1 if (face != "bottom" and at < length - 1) else 0])
        joint = at <= 1 or at >= length - 2 or at == length // 2
        return ramp(pal["leg"], (0.75 if joint else 0.35) + 0.15 * rng.random() + (0.1 if face == "top" else 0.0))

    cr._texels(cv, box, fn)


def _skull(cv, box, pal, rng):
    w, h, d = box["size"]

    def fn(face, i, j, fw, fh):
        k = cr._along(face, i, j, box["size"])
        if face == "bottom":
            return ramp(pal["mouth"], 0.2 + 0.7 * rng.random()) if k > d * 0.6 else ramp(pal["belly"], 0.15 + 0.35 * rng.random())
        if face == "north":
            return ramp(pal["mouth"], 0.3 + 0.6 * rng.random()) if j >= h - 3 else ramp(pal["shell"], 0.3 + 0.2 * rng.random())
        return ramp(pal["shell"], 0.22 + 0.2 * rng.random() + 0.01 * k)

    cr._texels(cv, box, fn)


def _eye(cv, box, pal, rng):
    """An eye: a glassy bead that glows, brightest at its middle, with no pupil, so it reads as a cold point of light."""
    def fn(face, i, j, fw, fh):
        edge = i in (0, fw - 1) or j in (0, fh - 1)
        return (C(pal["eye"][1 if edge and fw > 2 and fh > 2 else 2]), "eyes", C(pal["eye"][0]))

    cr._texels(cv, box, fn)


def _feeler(cv, box, pal, rng, tip):
    w, h, d = box["size"]

    def fn(face, i, j, fw, fh):
        k = cr._along(face, i, j, box["size"])
        if tip and k >= d - 3:
            return (C(pal["glow"][2 if k == d - 1 else 1]), "eyes", C(pal["rim"][1]))
        return ramp(pal["leg"], 0.3 + (0.4 if k % 3 == 0 else 0.0) + 0.15 * rng.random())

    cr._texels(cv, box, fn)


def paint_box(cv, box, pal, rng):
    mat = box["mat"]
    if mat == "shell": _shell(cv, box, pal, rng)
    elif mat == "crown": _shell(cv, box, pal, rng, crown=True)
    elif mat == "shield": _shell(cv, box, pal, rng, front_rim=True, crown=True)
    elif mat == "belly": _belly(cv, box, pal, rng)
    elif mat in ("spine", "spine_tip"): _spike(cv, box, pal, rng, mat == "spine_tip")
    elif mat in ("thorn", "thorn_tip"): _spike(cv, box, pal, rng, mat == "thorn_tip", glow_rows=4)
    elif mat in ("barb", "barb_tip"): _spike(cv, box, pal, rng, mat == "barb_tip", glow_rows=3, dim=True)
    elif mat in ("leg", "shin"): _leg(cv, box, pal, rng, mat == "shin")
    elif mat == "skull": _skull(cv, box, pal, rng)
    elif mat == "eye": _eye(cv, box, pal, rng)
    elif mat in ("feeler", "feeler_tip"): _feeler(cv, box, pal, rng, mat == "feeler_tip")
    elif mat == "mandible":
        cr._texels(cv, box, lambda f, i, j, fw, fh: ramp(pal["mandible"], 0.2 + 0.5 * rng.random() + (0.3 if f == "top" else 0.0)))
    elif mat == "fang":
        cr._texels(cv, box, lambda f, i, j, fw, fh: C(pal["fang"][1 if f in ("top", "north") else 0]))
    else:
        raise ValueError(mat)


def paint(seed=4601):
    cv = Canvas()
    rng = random.Random(seed)
    for p in PARTS:
        for b in p["boxes"]:
            paint_box(cv, b, PALETTE, rng)
    return cv


def write(assets, write_json):
    """The giant's geometry, its texture and its glow texture (the tips of its spikes, thorns, barbs and feelers, and its
    eyes: the renderer lights them all, in a slow wave down its body); returns its lang entries."""
    cr.pack(PARTS, TEX_W, TEX_H)
    write_json(os.path.join(assets, "geometry", "giant_skein.json"),
               {"texture_width": TEX_W, "texture_height": TEX_H, "parts": cr.geometry_json(PARTS)["parts"]})
    tex_dir = os.path.join(assets, "textures", "entity", "giant_skein")
    os.makedirs(tex_dir, exist_ok=True)
    cv = paint()
    cv.img.save(os.path.join(tex_dir, "giant_skein.png"))
    assert cv.eyes.getbbox(), "giant skein: no glowing pixels"
    cv.eyes.save(os.path.join(tex_dir, "giant_skein_glow.png"))
    write_json(os.path.join(assets, "models", "item", "giant_skein_spawn_egg.json"), {"parent": "minecraft:item/template_spawn_egg"})
    return {f"entity.{MOD}.giant_skein": "Giant Skein", f"item.{MOD}.giant_skein_spawn_egg": "Giant Skein Spawn Egg",
            f"item.{MOD}.giant_skein_spine": "Giant Skein Spine"}


# =============================================================================================== its drop
def _spine_item():
    """The Giant Skein Spine (its drop): one of its long back spines, curved like a sabre, steel blue at its broken root
    paling to its tip, which still holds a little of its light."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    pts = []
    for n in range(200):
        t = n / 199
        a, b, c = (2.6, 13.4), (4.6, 3.6), (14.2, 1.4)            # a curve bowed up and left
        x = (1 - t) ** 2 * a[0] + 2 * (1 - t) * t * b[0] + t * t * c[0]
        y = (1 - t) ** 2 * a[1] + 2 * (1 - t) * t * b[1] + t * t * c[1]
        pts.append((x, y, t, 2.3 * (1 - t) ** 0.9 + 0.45))
    body = {}
    for yy in range(16):
        for xx in range(16):
            cx, cy = xx + 0.5, yy + 0.5
            best = min(pts, key=lambda p: (p[0] - cx) ** 2 + (p[1] - cy) ** 2 - p[3] ** 2)
            d = math.hypot(best[0] - cx, best[1] - cy)
            if d <= best[3]:
                side = (cx - best[0]) * -0.7 + (cy - best[1]) * -0.7      # >0 on the upper left, the lit side
                body[(xx, yy)] = (best[2], side / max(0.5, best[3]))
    pal = PALETTE["spine"]
    for (xx, yy), (t, side) in body.items():
        edge = any((xx + dx, yy + dy) not in body for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        if t > 0.86:
            col = PALETTE["glow"][2 if t > 0.93 else 1]
        elif t > 0.74:
            col = PALETTE["glow"][0] if side > -0.2 else pal[6]
        elif edge and side < 0:
            col = PALETTE["dark"][2]
        else:
            k = 1 + 6.0 * t + 1.6 * side
            col = pal[max(0, min(len(pal) - 1, int(k)))]
        px[xx, yy] = C(col)
    # the broken root: a jagged dark end
    for xx, yy in ((1, 14), (2, 15), (3, 15), (1, 13)):
        if (xx, yy) in body: px[xx, yy] = C(PALETTE["dark"][0])
    return img


PAINTERS = {"item/giant_skein_spine": _spine_item}


# =============================================================================================== preview
REAR = [(1.6, 40), (1.2, 34), (0.8, 26), (0.45, 16), (0.2, 8), (0.05, 2)]


def layout(bend=0.0, phase=None, rear=0.0, plates=PLATES):
    """The giant laid out as the renderer lays it, as one tree of parts for creatures.render(): its pieces along a trail
    that curves by bend (radians a block), its legs in their wave at phase (None: at rest), its front reared by rear."""
    byname = {p["name"]: p for p in PARTS}

    def subtree(root):
        out = [byname[root]]
        for p in PARTS:
            if any(p["parent"] == q["name"] for q in out) and p not in out: out.append(p)
        return out

    def at(s):
        if abs(bend) < 1e-6: return 0.0, s * 16, 0.0
        th = bend * s
        return (1 - math.cos(th)) / bend * 16, math.sin(th) / bend * 16, math.degrees(th)

    mid = PLATE_START + PLATE_STEP * (plates - 1) / 2
    cx, cz, _ = at(mid)
    parts, pose = [], {}
    pieces = [("head", 0, 0.0)] + [(piece(k), k, PLATE_START + PLATE_STEP * k) for k in range(plates)]
    for idx, (pc, k, s) in enumerate(pieces):
        x, z, yaw = at(s)
        tag = f"#{idx}"
        lift, pitch = REAR[idx] if idx < len(REAR) else (0.0, 0.0)
        for p in subtree(pc):
            q = dict(p, name=p["name"] + tag, parent=p["parent"] + tag if p["parent"] != "root" else "root")
            if p["name"] == pc:
                q["pivot"] = [x - cx, 24 - lift * rear * 16, z - cz]
                q["rot"] = [-pitch * rear, yaw, 0]
            parts.append(q)
        if pc == "head":
            continue
        w = width(k)
        ls = 1.0 - 0.25 * k / (PLATES - 1)
        pose[f"{pc}_shell{tag}"] = {"scale": (w / WIDTHS[pc], 1.0, 1.0)}
        rest = 0.55 + (-0.6 - 0.55) * k / (PLATES - 1)
        for side, sgn in (("left", 1), ("right", -1)):
            leg = next(q for q in parts if q["name"] == f"{pc}_leg_{side}{tag}")
            leg["pivot"] = [sgn * (w / 2 - 4), -LIFT, 0]
            ph = (phase - 0.55 * k + (math.pi if sgn < 0 else 0)) if phase is not None else None
            swing = 0.35 * math.sin(ph) if ph is not None else 0.0
            lift_ = 0.35 * max(0.0, math.cos(ph)) if ph is not None else 0.0
            pose[leg["name"]] = {"rot": (0, math.degrees(sgn * (rest + swing)), math.degrees(-sgn * lift_)), "scale": (ls, ls, ls)}
    return parts, pose


def preview(out_dir, size=640):
    os.makedirs(out_dir, exist_ok=True)
    cr.pack(PARTS, TEX_W, TEX_H)
    cv = paint()
    cv.img.save(os.path.join(out_dir, "giant_skein_texture.png"))
    glow = cv.eyes
    # the front third up close, lit; the whole of it round a bend in the dark; from the side; rearing to strike
    shots = [(layout(0.0, None, 0.0, 6), 35, 28, 0.9, 1.0, (52, 56, 60), 11.0),
             (layout(0.06, 1.3, 0.0), 20, 50, 1.0, 0.45, (20, 23, 28), 48.0),
             (layout(0.0, 2.1, 0.0, 8), 90, 6, 1.0, 1.0, (52, 56, 60), 13.0),
             (layout(0.0, None, 1.0, 7), -40, 12, 1.0, 1.0, (52, 56, 60), 13.0),
             # on a wall seen from across the rift: from straight out, in the dark
             (layout(0.035, 0.6, 0.0), 0, 85, 1.0, 0.3, (14, 16, 22), 44.0)]
    for i, ((parts, pose), yaw, pitch, lvl, ll, bg, dist) in enumerate(shots):
        img = cr.render(parts, cv.img, pose, yaw, pitch, size, dist=dist, glow=glow, glow_level=lvl, bg=bg, light_level=ll)
        img.save(os.path.join(out_dir, f"giant_skein_{i}.png"))
        print("preview giant skein", i, flush=True)


if __name__ == "__main__":
    preview(sys.argv[1] if len(sys.argv) > 1 else "giant_preview")
