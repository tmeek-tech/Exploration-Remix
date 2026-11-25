"""The Hollow Choir (0.22.0): the Deep Dark boss's shape, its two entity textures and its two items' art.

The boss hangs in the air over a sunken arena on six long tendrils that reach down to the floor. There is no body: a
ring-shaped crown of pale bone plates about four and a half blocks across, four bone ribs arching up over the middle of
the ring, five small bone bells swinging under it, and a knot of sculk hanging in the middle on which all of its light
lives. It is the mod's own creature and nothing of the Warden's: no face, no arms, no legs, no heartbeat, and its light
is a cold light blue, never the Warden's cyan.

Its model is a handful of loose pieces that the renderer draws again and again, turning a copy each time, so the whole
ring, the ribs, the bells and the six tendrils come out of six root parts (the names are the renderer's contract):
  plate  one of the ring's eight bone plates, already out at the ring's front   (copies at k * 45 degrees)
  rib    one rib, a chain of five parts arching from the ring up over the middle (copies at k * 90 degrees)
  bell   one bell under the ring, every box below its pivot so the renderer can swing it (k * 72 degrees)
  core   the knot of sculk hanging in the middle, its cracks lit
  seg    one segment of a tendril, drawn a dozen and more times down a curve, a ring of light at its root end
  foot   a tendril's end on the floor: a knuckle and three claws
Model space is vanilla's, as the mod's other creatures use it (creatures.py): units of 1/16 block, y grows downward,
the creature faces -z, x is to the left.

Textures are painted face by face in vanilla's box layout (creatures.faces), but each texel is coloured by where it
lies in model space (texel_point), so the bone's weathering and cracks and the core's crazing run on across the seams
between boxes. The glow overlay is the lit pixels again at full brightness: the game draws it added over the texture,
so under a lit pixel the texture keeps only a dark tint of it (UNDER), as the vent urchin's does.

Taran's colour rules: nothing warm anywhere (no orange, yellow or green) and no cyan or teal. So the bone is a cool
neutral off-white, not the warm ivory of the mossback's den, and the light is light blue only (GLOW).

Called by creatures.write_all; python3 tools/hollow_choir.py paints into src/main/resources, and with a directory
after it also writes previews there.
"""
import json
import math
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import creatures as cr  # noqa: E402
import plants  # noqa: E402
from creatures import B, P, faces  # noqa: E402

MOD = cr.MOD
NAME = "hollow_choir"
TEX_W, TEX_H = 128, 128


# ================================================================================================ shape (pixels)
# The crown ring: eight bone plates round the y axis, each built out at the ring's front (-z) and turned into place by
# the renderer. The ring is 72 px across the outer lips, about four and a half blocks.
RING_OUT, RING_IN = 36, 20
PLATE_COPIES = 8
PLATE_BOXES = [
    B("lip", (-7, -2, -RING_OUT), (14, 4, 8)),  # the thick outer lip, weathered along its rim
    B("plate", (-7, -1.5, -28), (14, 3, 5)),    # the plate itself, thinner
    B("inner", (-7, -1, -RING_IN - 3), (14, 2, 3)),   # the thin inner edge, a fine line of light along it
    B("keel", (-2, -3, -35), (4, 1, 14)),       # a keel raised along the top (up is -y)
]

# A rib: a chain of five parts from the ring's inner edge, arching up (-y) and inward (+z) to end near the axis, each
# one shorter and thinner than the one before. (length, width, height, turn about x at its root); a positive turn about
# x tips a part's +z up, and each part starts a pixel inside the one before so the joints show no gap as they bend.
RIB_ROOT = 24
RIB_COPIES = 4
RIB_SEGS = ((9, 6, 4, 20), (9, 5, 4, 18), (8, 4, 3, 20), (6, 3, 3, 14), (5, 2, 2, 12))

# A bell: a short stem, a shoulder, the cup and its flared mouth, all of it below the pivot it swings on.
BELL_COPIES = 5
BELL_PIVOT = (0, 2, -20)
BELL_BOXES = [
    B("bell", (-1, 0, -1), (2, 4, 2)),          # the stem it hangs by
    B("bell", (-2.5, 4, -2.5), (5, 3, 5)),      # the shoulder
    B("bell", (-3.5, 7, -3.5), (7, 4, 7)),      # the cup
    B("mouth", (-4, 11, -4), (8, 2, 8)),        # the flared mouth, its rim lit
]

# The core: a knot of sculk, a cluster of lumps rather than a cube, inside x -11..11, y -12..12, z -11..11. Its top is
# the narrowest of it, so the four ribs arch clear over it.
CORE_BOXES = [
    B("sculk", (-8, -4, -8), (16, 11, 16)),     # the mass of it
    B("sculk", (-4, -11, -4), (8, 7, 8)),       # the lobe it hangs by, narrow so the ribs arch clear over it
    B("sculk", (-6, 6, -6), (12, 6, 12)),       # the lobe hanging under it
    B("sculk", (7, 0, -4), (4, 8, 8)),          # a lump to either side, low enough for the ribs to pass
    B("sculk", (-11, 0, -4), (4, 8, 8)),
    B("sculk", (-4, 1, -11), (8, 7, 4)),        # one in front
    B("sculk", (-4, 2, 7), (8, 6, 4)),          # one behind
]

# A tendril segment: from its root end at z 0 to its tip at z -16, a wide collar at the root and a narrower waist out
# to the tip (so a chain of them reads as knuckled cord). The ring of light sits on the collar, BAND px from the root.
SEG_BOXES = [
    B("band", (-5, -5, -5), (10, 10, 5)),
    B("cord", (-4, -4, -16), (8, 8, 11)),
]
BAND = 2.5

# A foot: the knuckle at the tendril's end with the pad it rests on, and three claws splaying out and down (+y is down)
# about 8 px out and 6 px down.
FOOT_BOXES = [
    B("knuckle", (-5, -5, -5), (10, 9, 10)),
    B("knuckle", (-3, 4, -3), (6, 2, 6)),       # the pad under it
]
CLAWS = 3
CLAW_OUT, CLAW_DROP, CLAW_BEND = 2.0, 26.0, 20.0


def rib_parts():
    """The rib's chain: the part named rib and its four children."""
    out = []
    parent, pivot = "root", (0, 0, -RIB_ROOT)
    for k, (length, w, h, turn) in enumerate(RIB_SEGS):
        name = "rib" if k == 0 else f"rib{k + 1}"
        out.append(P(name, parent, pivot, (turn, 0, 0), [B("rib", (-w / 2, -h / 2, 0), (w, h, length))]))
        parent, pivot = name, (0, 0, length - 1)
    return out


def claw_parts():
    """The foot's three claws, each a stout segment and a hooked tip, turned about the knuckle. A part turned by
    (drop, azimuth, 0) sends its -z box out along the azimuth and `drop` degrees down."""
    out = []
    for k in range(CLAWS):
        a = math.radians(k * 360.0 / CLAWS)
        pivot = (-CLAW_OUT * math.sin(a), 2, -CLAW_OUT * math.cos(a))
        out.append(P(f"foot_claw{k}", "foot", pivot, (CLAW_DROP, math.degrees(a), 0),
                     [B("claw", (-1.5, -1.5, -5), (3, 3, 5))]))
        out.append(P(f"foot_claw{k}_tip", f"foot_claw{k}", (0, 0, -4), (CLAW_BEND, 0, 0),
                     [B("claw", (-1, -1, -4), (2, 2, 4))]))
    return out


PARTS = [P("plate", "root", (0, 0, 0), (0, 0, 0), PLATE_BOXES)] + rib_parts() + [
    P("bell", "root", BELL_PIVOT, (0, 0, 0), BELL_BOXES),
    P("core", "root", (0, 0, 0), (0, 0, 0), CORE_BOXES),
    P("seg", "root", (0, 0, 0), (0, 0, 0), SEG_BOXES),
    P("foot", "root", (0, 0, 0), (0, 0, 0), FOOT_BOXES),
] + claw_parts()


def share_key(name):
    """Parts the renderer draws as copies of one another share one place on the texture."""
    if name.startswith("foot_claw"):
        return "foot_claw_tip" if name.endswith("_tip") else "foot_claw"
    return name


def pack(parts):
    """The texture's layout: one place per part (creatures.pack), the three claws sharing one, so a claw is painted
    once and all three read alike."""
    first, proto = {}, []
    for p in parts:
        key = share_key(p["name"])
        if key in first:
            continue
        first[key] = p
        proto.append(p)
    cr.pack(proto, TEX_W, TEX_H)
    for p in parts:
        src = first[share_key(p["name"])]
        if src is p:
            continue
        for b, s in zip(p["boxes"], src["boxes"]):
            b["uv"] = list(s["uv"])
    return proto


# ================================================================================================ palette
# How much of a lit pixel's colour the texture keeps under it (the overlay, added on top, brings the rest).
UNDER = 0.12


def _ramp(*hexes):
    return [cr.C(h)[:3] for h in hexes]


# Bone: a cool neutral off-white down to a weathered grey, no warmth in it anywhere.
BONE = _ramp("#1F1E25", "#2B2A33", "#3A3844", "#4A4855", "#5B5967", "#6D6B79", "#807E8B", "#94929E", "#A8A6B1",
             "#BCBAC4", "#CFCDD6", "#E2E1E8", "#F0EFF4")
# Sculk: near-black with a cold blue-violet cast, as the vent urchin's shell.
SCULK = _ramp("#07060B", "#0A0910", "#0E0C15", "#12101A", "#171420", "#1C1828", "#231E31")
# Up inside a bell, and the shadow under its rim.
HOLLOW = _ramp("#17161C", "#1F1E26", "#292832")
# The light: light blue only, dim to bright.
GLOW = _ramp("#8EC2FF", "#A6D2FF", "#BFE2FF")

# How lit each face of a box reads (the arena is lit from above, and y grows downward).
FACE_LIGHT = {"top": 1.0, "north": 0.74, "west": 0.66, "east": 0.60, "south": 0.52, "bottom": 0.34}


def shade(ramp, t):
    return ramp[max(0, min(len(ramp) - 1, int(t * len(ramp))))]


# ================================================================================================ painting
class Canvas:
    """The texture and its glow overlay (the lit pixels again, at full brightness)."""

    def __init__(self):
        self.img = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.glow = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.px, self.gx = self.img.load(), self.glow.load()

    def put(self, x, y, c, lit=False):
        """Colours texel (x, y). A lit texel goes on the overlay at full brightness and the texture keeps only a dark
        tint of it, so the two together come out its own light blue."""
        if not (0 <= x < TEX_W and 0 <= y < TEX_H):
            return
        c = tuple(c[:3])
        if lit:
            self.px[x, y] = tuple(round(v * UNDER) for v in c) + (255,)
            self.gx[x, y] = c + (255,)
        else:
            self.px[x, y] = c + (255,)


def texel_point(box, face, i, j):
    """Where texel (i, j) of a box's face lies, in model pixels (vanilla's box layout, as creatures.faces)."""
    (x0, y0, z0), (w, h, d) = box["origin"], box["size"]
    x1, y1, z1 = x0 + w, y0 + h, z0 + d
    return {
        "top": (x0 + i + 0.5, y0, z1 - j - 0.5),
        "bottom": (x0 + i + 0.5, y1, z1 - j - 0.5),
        "west": (x0, y0 + j + 0.5, z1 - i - 0.5),
        "north": (x0 + i + 0.5, y0 + j + 0.5, z0),
        "east": (x1, y0 + j + 0.5, z0 + i + 0.5),
        "south": (x1 - i - 0.5, y0 + j + 0.5, z1),
    }[face]


def _hash3(i, j, k, salt):
    n = (i * 73856093) ^ (j * 19349663) ^ (k * 83492791) ^ (salt * 2654435761)
    n &= 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 15)) & 0xFFFF) / 0xFFFF


def noise3(p, cell, salt):
    """Smooth value noise over model space, in [0, 1). It is the same at the same point whichever face a texel belongs
    to, so what it draws runs on across the seams between boxes."""
    out = 0.0
    fx, fy, fz = p[0] / cell, p[1] / cell, p[2] / cell
    x0, y0, z0 = math.floor(fx), math.floor(fy), math.floor(fz)
    tx, ty, tz = [t * t * (3 - 2 * t) for t in (fx - x0, fy - y0, fz - z0)]
    for dz in (0, 1):
        wz = tz if dz else 1 - tz
        for dy in (0, 1):
            wy = ty if dy else 1 - ty
            for dx in (0, 1):
                wx = tx if dx else 1 - tx
                out += wx * wy * wz * _hash3(x0 + dx, y0 + dy, z0 + dz, salt)
    return out


def crack(p, cell, salt, width):
    """How deep in a crack the point p lies, 0 off one and 1 along its middle: cracks are where a smooth field crosses
    its own middle, so they wander and branch over the shape instead of following the boxes."""
    a = abs(noise3(p, cell, salt) - 0.5)
    return 0.0 if a > width else 1.0 - a / width


def bone(face, p, i, j, fw, fh, lit=0.0, cracked=1.0):
    """Bone: matte, finely grained, a little darker round each face's weathered edge, with a few hairline cracks."""
    t = 0.55 + 0.38 * FACE_LIGHT[face] + lit
    t += 0.14 * (noise3(p, 7.0, 11) - 0.5) + 0.10 * (noise3(p, 2.2, 23) - 0.5)
    if i == 0 or i == fw - 1 or j == 0 or j == fh - 1:
        t -= 0.09
    k = crack(p, 9.0, 31, 0.011)
    if k > 0.0:
        t -= (0.24 + 0.18 * k) * cracked
    return shade(BONE, t), False


def sculk(face, p, lit=0.0):
    """Sculk: near-black with a cold blue-violet cast, lumpy."""
    t = 0.30 + 0.40 * FACE_LIGHT[face] + lit
    t += 0.34 * (noise3(p, 4.0, 13) - 0.5) + 0.18 * (noise3(p, 1.7, 17) - 0.5)
    return shade(SCULK, t), False


def plate_texel(mat, face, p, i, j, fw, fh):
    if mat == "inner":
        # the fine line of light along the plate's inner edge: its inner face, and one texel over and under it
        if face == "south":
            return GLOW[2 if j == 0 else 1], True
        if face in ("top", "bottom") and j == 0:
            return GLOW[0], True
        return bone(face, p, i, j, fw, fh)
    if mat == "keel":
        return bone(face, p, i, j, fw, fh, lit=0.07 if face == "top" else 0.0)
    if mat == "lip":
        # the ring's outer rim takes the weather: darker, and more of it cracked
        outer = face == "north" or (face in ("top", "bottom") and j >= fh - 2)
        return bone(face, p, i, j, fw, fh, lit=-0.07 if outer else 0.0, cracked=1.6 if outer else 1.0)
    return bone(face, p, i, j, fw, fh)


def bell_texel(mat, face, p, i, j, fw, fh):
    if mat != "mouth":
        return bone(face, p, i, j, fw, fh)
    if face == "bottom":
        # the mouth seen from under the bell: its rim lit, the hollow dark, the clapper hanging in it
        if i in (0, fw - 1) or j in (0, fh - 1):
            return GLOW[1], True
        if 2 < i < fw - 3 and 2 < j < fh - 3:
            return shade(BONE, 0.52 + 0.3 * noise3(p, 2.0, 41)), False
        return shade(HOLLOW, 0.2 + 0.6 * noise3(p, 2.5, 43)), False
    if face != "top" and j == fh - 1:
        return GLOW[0], True                       # the rim's edge, seen from the side
    return bone(face, p, i, j, fw, fh)


def core_texel(face, p):
    """The knot of sculk: two crack fields crossing it, lit along their middles."""
    k = max(crack(p, 11.0, 61, 0.016), crack(p, 7.0, 71, 0.011))
    if k > 0.0:
        return GLOW[2 if k > 0.62 else (1 if k > 0.26 else 0)], True
    return sculk(face, p)


def seg_texel(mat, face, p):
    if mat == "band":
        z = p[2]
        if abs(z + BAND) < 0.6:
            return GLOW[2], True                   # the thin ring of light near the segment's root end
        if abs(z + BAND + 1.0) < 0.6:
            return GLOW[0], True
        return sculk(face, p, lit=0.06)
    return sculk(face, p)


def foot_texel(mat, face, p, i, j, fw, fh, box):
    if mat == "knuckle":
        return sculk(face, p, lit=0.05)
    # a claw: bone, dark where it leaves the knuckle and pale towards its point (the box runs from z 0 to -d)
    d = box["size"][2]
    along = max(0.0, min(1.0, -(p[2] - box["origin"][2] - d) / d))
    return bone(face, p, i, j, fw, fh, lit=-0.12 + 0.26 * along, cracked=0.5)


def paint_box(cv, box):
    mat = box["mat"]
    for face, (x0, y0, fw, fh) in faces(box).items():
        if fw == 0 or fh == 0:
            continue
        for j in range(fh):
            for i in range(fw):
                p = texel_point(box, face, i, j)
                if mat in ("lip", "plate", "inner", "keel"):
                    c, lit = plate_texel(mat, face, p, i, j, fw, fh)
                elif mat == "rib":
                    c, lit = bone(face, p, i, j, fw, fh)
                elif mat in ("bell", "mouth"):
                    c, lit = bell_texel(mat, face, p, i, j, fw, fh)
                elif mat == "sculk":
                    c, lit = core_texel(face, p)
                elif mat in ("band", "cord"):
                    c, lit = seg_texel(mat, face, p)
                elif mat in ("knuckle", "claw"):
                    c, lit = foot_texel(mat, face, p, i, j, fw, fh, box)
                else:
                    raise ValueError(mat)
                cv.put(x0 + i, y0 + j, c, lit)


def paint():
    cv = Canvas()
    for p in PROTO:
        for b in p["boxes"]:
            paint_box(cv, b)
    return cv


def check(cv):
    """Every texel of every face the shape uses is painted (no see-through gaps on a solid box), and something glows."""
    px = cv.img.load()
    for p in PARTS:
        for b in p["boxes"]:
            for face, (x0, y0, fw, fh) in faces(b).items():
                for j in range(fh):
                    for i in range(fw):
                        assert px[x0 + i, y0 + j][3] == 255, f"{NAME}: {p['name']} {face} ({i}, {j}) unpainted"
    assert cv.glow.getbbox(), f"{NAME}: no glowing pixels"


# ================================================================================================ its signs
# What the fight draws on the floor to warn of a blow (0.22.3, client.render.ChoirSigns): a slam's circle and the disc
# that fills it, a toll's ring and the low wall of light that runs with it, the grab's ripples, the sculk lit before a
# bloom. They are added onto whatever is under them, so black is nothing and a pixel's brightness is its strength.
# The sheet holds no colour and no soft edges of its own: the renderer gives every corner it draws the Choir's light
# blue at the strength it wants there, so a ring's glow fades smoothly however far a pixel is stretched, and the
# sheet only says where there is light at all. Its pieces (the renderer's contract; each is x, y, width, height in
# pixels, with clear pixels between any two):
SIGN_SIZE = 64
SIGN_WHITE = (56, 0, 8, 8)       # plain light: every ring, disc, wall and thread is drawn from the middle of this
SIGN_TILES = ((0, 24, 16, 16), (16, 24, 16, 16), (32, 24, 16, 16), (48, 24, 16, 16))   # sculk veins, four of them
SIGN_TINT = (128, 190, 255)      # the light blue the renderer draws them in, for the preview only


def signs():
    """The sheet of signs."""
    img = Image.new("RGBA", (SIGN_SIZE, SIGN_SIZE), (0, 0, 0, 0))
    px = img.load()
    x0, y0, w, h = SIGN_WHITE
    for j in range(h):
        for i in range(w):
            px[x0 + i, y0 + j] = (255, 255, 255, 255)
    for n, (x0, y0, w, h) in enumerate(SIGN_TILES):
        for j in range(h):
            for i in range(w):
                p = (i + 0.5 + 37 * n, 0.0, j + 0.5 + 53 * n)
                vein = max(crack(p, 4.5, 81 + n, 0.04), 0.8 * crack(p, 2.6, 91 + n, 0.035))
                v = 0.30 + 0.60 * vein ** 1.3
                if _hash3(i, j, n, 97) > 0.985:
                    v = 1.0                        # a speck of full light here and there, as sculk has
                g = round(255 * max(0.0, min(1.0, v)))
                px[x0 + i, y0 + j] = (g, g, g, 255)
    return img


# ================================================================================================ its items
# The key that opens the arena, and the bell the boss drops. Drawn as the mod's other 16 x 16 item art is, a character
# per pixel (plants.grid): 1-4 bone, 5-6 sculk, 7-8 its light blue, 9 the dark inside a bell.
ITEM_SHADES = {
    "0": "#2F2B3B", "1": "#2E2C35", "2": "#6D6B79", "3": "#A8A6B1", "4": "#DCDBE2",
    "5": "#0A0910", "6": "#17141F",
    "7": "#8EC2FF", "8": "#BFE2FF",
    "9": "#1F1E26",
}

CHOIR_KEY = [
    "................",
    ".....14441......",
    "....1478731.....",
    "....1488831.....",
    "....1378721.....",
    "....1233321.....",
    ".....12321......",
    "......065.......",
    "......065.......",
    "......0654431...",
    "......065221....",
    "......0654431...",
    "......065221....",
    "......065.......",
    "......065.......",
    "......232.......",
]

CHOIR_BELL = [
    "................",
    "......1331......",
    "......1..1......",
    "......1331......",
    ".....133221.....",
    "....13444321....",
    "...1344443221...",
    "...1344443221...",
    "..134444443221..",
    ".13444444443221.",
    ".13444444443221.",
    ".12333333332211.",
    "..178888888871..",
    "...1992882991...",
    ".....111111.....",
    "................",
]


def _key_item():
    """The Choir Key: a bone bow round an eye of light blue, a dark sculk shaft and two bone wards."""
    return plants.grid(CHOIR_KEY, ITEM_SHADES)


def _bell_item():
    """The Choir Bell (the boss's trophy): a small bone bell on its loop, the light blue still in its mouth."""
    return plants.grid(CHOIR_BELL, ITEM_SHADES)


PAINTERS = {"item/choir_key": _key_item, "item/choir_bell": _bell_item}


# ================================================================================================ output
def geometry_json(parts):
    return {"texture_width": TEX_W, "texture_height": TEX_H, "parts": [
        {"name": p["name"], "parent": p["parent"], "pivot": [round(v, 4) + 0.0 for v in p["pivot"]],
         "rotation": [round(v, 4) + 0.0 for v in p["rot"]],
         "boxes": [{"origin": b["origin"], "size": b["size"], "uv": b["uv"], "inflate": b["inflate"]} for b in p["boxes"]]}
        for p in parts]}


PROTO = pack(PARTS)


def write(assets, write_json):
    """Writes the geometry, the textures and the spawn egg's item model; returns the lang entries."""
    write_json(os.path.join(assets, "geometry", NAME + ".json"), geometry_json(PARTS))
    tex_dir = os.path.join(assets, "textures", "entity", NAME)
    os.makedirs(tex_dir, exist_ok=True)
    cv = paint()
    check(cv)
    cv.img.save(os.path.join(tex_dir, f"{NAME}.png"))
    cv.glow.save(os.path.join(tex_dir, f"{NAME}_glow.png"))
    signs().save(os.path.join(tex_dir, f"{NAME}_signs.png"))
    item_dir = os.path.join(assets, "textures", "item")
    os.makedirs(item_dir, exist_ok=True)
    for name, painter in PAINTERS.items():
        painter().save(os.path.join(item_dir, os.path.basename(name) + ".png"))
    write_json(os.path.join(assets, "models", "item", NAME + "_spawn_egg.json"), {"parent": "minecraft:item/template_spawn_egg"})
    return {f"entity.{MOD}.{NAME}": "The Hollow Choir", f"item.{MOD}.{NAME}_spawn_egg": "Hollow Choir Spawn Egg",
            f"item.{MOD}.choir_key": "Choir Key", f"item.{MOD}.choir_bell": "Choir Bell"}


# ================================================================================================ preview
# The boss laid out the way the renderer is meant to lay it out, as one tree of parts for creatures.render: the eight
# plates, the four ribs, the five bells, the core, and six tendrils of segments curving out and down to their feet.
TENDRILS = 6
SEG_LEN = 16
TENDRIL_RADIUS = 30
TENDRIL_DROP, TENDRIL_BEND = 55.0, 3.0


def _copies(root, tag, pivot, rot):
    """A copy of a part and everything under it, renamed with tag, the root's pivot and rotation set."""
    names, out = {root}, []
    for p in PARTS:
        if p["name"] != root and p["parent"] not in names:
            continue
        names.add(p["name"])
        q = dict(p, name=p["name"] + tag, parent="root" if p["name"] == root else p["parent"] + tag)
        if p["name"] == root:
            q["pivot"], q["rot"] = list(pivot), list(rot)
        out.append(q)
    return out


def _turn(pivot, az):
    """A pivot at the ring's front turned az radians about the y axis (as a part's y rotation turns it)."""
    x, y, z = pivot
    return (x * math.cos(az) + z * math.sin(az), y, -x * math.sin(az) + z * math.cos(az))


def layout(root_y=0.0, segs=8):
    """The whole boss, with its ring at model y root_y."""
    parts = []
    for k in range(PLATE_COPIES):
        parts += _copies("plate", f"_p{k}", (0, root_y, 0), (0, k * 360.0 / PLATE_COPIES, 0))
    for k in range(RIB_COPIES):
        a = k * 2 * math.pi / RIB_COPIES
        piv = _turn((0, 0, -RIB_ROOT), a)
        parts += _copies("rib", f"_r{k}", (piv[0], piv[1] + root_y, piv[2]), (0, math.degrees(a), 0))
    for k in range(BELL_COPIES):
        a = k * 2 * math.pi / BELL_COPIES
        piv = _turn(BELL_PIVOT, a)
        parts += _copies("bell", f"_b{k}", (piv[0], piv[1] + root_y, piv[2]), (0, math.degrees(a), 0))
    parts += _copies("core", "_c", (0, root_y, 0), (0, 0, 0))
    for k in range(TENDRILS if segs else 0):
        a = k * 2 * math.pi / TENDRILS
        piv = _turn((0, 2, -TENDRIL_RADIUS), a)
        start = (piv[0], piv[1] + root_y, piv[2])
        out, down = 0.0, 0.0
        for s in range(segs):
            tag = f"_t{k}_{s}"
            if s == 0:
                parts += _copies("seg", tag, start, (TENDRIL_DROP, math.degrees(a), 0))
            else:
                seg = dict(PARTS[SEG_INDEX], name="seg" + tag, parent=f"seg_t{k}_{s - 1}",
                           pivot=[0, 0, -SEG_LEN], rot=[TENDRIL_BEND, 0, 0])
                parts.append(seg)
            th = math.radians(TENDRIL_DROP + TENDRIL_BEND * s)
            out += SEG_LEN * math.cos(th)
            down += SEG_LEN * math.sin(th)
        foot = (start[0] - out * math.sin(a), start[1] + down, start[2] - out * math.cos(a))
        parts += _copies("foot", f"_f{k}", foot, (0, math.degrees(a), 0))
    return parts


SEG_INDEX = next(i for i, p in enumerate(PARTS) if p["name"] == "seg")


def montage(out_dir):
    """The two entity textures 4x side by side (the colour, the glow and the two together as the game lights them),
    and the two item textures at 8x."""
    from PIL import ImageChops
    os.makedirs(out_dir, exist_ok=True)
    cv = paint()
    check(cv)
    lit = ImageChops.add(cv.img, cv.glow)
    lit.putalpha(cv.img.getchannel("A"))
    pad, s = 8, 4
    sheet = Image.new("RGBA", (3 * TEX_W * s + 4 * pad, TEX_H * s + 2 * pad), (46, 44, 54, 255))
    for n, im in enumerate((cv.img, cv.glow, lit)):
        big = im.resize((TEX_W * s, TEX_H * s), Image.NEAREST)
        under = Image.new("RGBA", big.size, (22, 21, 28, 255))
        sheet.paste(Image.alpha_composite(under, big), (pad + n * (TEX_W * s + pad), pad))
    path = os.path.join(out_dir, "hollow_choir_textures.png")
    sheet.save(path)
    s = 8
    items = Image.new("RGBA", (2 * 16 * s + 3 * pad, 16 * s + 2 * pad), (46, 44, 54, 255))
    for n, painter in enumerate(PAINTERS.values()):
        big = painter().resize((16 * s, 16 * s), Image.NEAREST)
        under = Image.new("RGBA", big.size, (22, 21, 28, 255))
        items.paste(Image.alpha_composite(under, big), (pad + n * (16 * s + pad), pad))
    items_path = os.path.join(out_dir, "hollow_choir_items.png")
    items.save(items_path)
    s = 8
    sheet = signs().resize((SIGN_SIZE * s, SIGN_SIZE * s), Image.NEAREST)
    under = Image.new("RGBA", sheet.size, (22, 21, 28, 255))
    signs_path = os.path.join(out_dir, "hollow_choir_signs.png")
    ImageChops.add(under, ImageChops.multiply(sheet, Image.new("RGBA", sheet.size, SIGN_TINT + (255,)))).save(signs_path)
    return [path, items_path, signs_path]


def renders(out_dir, size=560, segs=8):
    """Software renders of the whole boss and of its crown from below, to see the shape (creatures.render)."""
    os.makedirs(out_dir, exist_ok=True)
    cv = paint()
    out = []
    shots = [(layout(-94.0, segs), 34, 20, 23.0, (18, 17, 24)),      # the whole of it, hanging on its tendrils
             (layout(14.0, 0), 28, 14, 10.0, (18, 17, 24)),           # the crown alone: the ribs, the bells, the core
             (layout(14.0, 0), 24, -30, 10.0, (18, 17, 24)),          # from under it, as the arena sees it
             (layout(10.0, 1), 0, 78, 10.0, (18, 17, 24))]            # from over it
    for i, (parts, yaw, pitch, dist, bg) in enumerate(shots):
        img = cr.render(parts, cv.img, {}, yaw, pitch, size, dist=dist, glow=cv.glow, glow_level=1.0, bg=bg,
                        light_level=0.85)
        path = os.path.join(out_dir, f"hollow_choir_{i}.png")
        img.save(path)
        out.append(path)
        print("preview", path, flush=True)
    return out


def _write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    assets = os.path.join(root, "src", "main", "resources", "assets", MOD)
    names = write(assets, _write_json)
    print(f"{len(PARTS)} parts, {sum(len(p['boxes']) for p in PARTS)} boxes, {len(PROTO)} on the texture")
    for k, v in names.items():
        print(" ", k, "=", v)
    if len(sys.argv) > 1:
        for path in montage(sys.argv[1]):
            print("preview", path)
        renders(sys.argv[1])
