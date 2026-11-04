"""Exploration Remix's creatures: their shapes, textures and previews. Called by gen_data.py.

A creature's shape is a tree of named parts, each a pivot, a rest rotation and a few boxes, written as JSON to
assets/explorationremix/geometry/<creature>.json. The game builds its model from that file (client.model.Geometry), so
the shape lives in this one place and the painter below always knows which pixel lands on which face.

Textures are painted face by face in vanilla's box layout (see FACES), so a box's top, belly and flanks each get the
right material. Every texture is deterministic: it only changes when this file does.

The Mossback is the Upper Caves' lizard: a heavy, territorial ambush hunter with moss and ferns growing on its back,
an amber eye and a throat sac and frills that flare and glow when it hunts. Its frost cousin wears the cold caves'
blue frost moss and silver ferns. It is the mod's own creature; nothing here is taken from any other game.

python3 tools/creatures.py --preview <dir> draws previews of every creature from a few angles.
"""
import json, math, os, random, sys
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from plants import hexc

MOD = "explorationremix"
TEX_W, TEX_H = 128, 128


# =============================================================================================== shape
# Model space as vanilla's: units of 1/16 block, y grows downward, the ground is at y 24, the creature faces -z.
# A part: (name, parent, pivot, rest rotation in degrees (x, y, z), boxes); a box: (material, origin, size, inflate).
# Sizes are whole pixels so every face maps onto whole texels.

def P(name, parent, pivot, rot, boxes):
    return {"name": name, "parent": parent, "pivot": list(pivot), "rot": list(rot), "boxes": boxes}


def B(mat, origin, size, inflate=0.0):
    assert all(float(s).is_integer() for s in size), size
    return {"mat": mat, "origin": list(origin), "size": [int(s) for s in size], "inflate": inflate}


def _leg(side, prefix, parent, pivot, upper, lower_h, splay):
    """A sprawling lizard leg: the upper leg reaches sideways out of the body, the lower leg down to a broad foot."""
    s = 1 if side > 0 else -1
    ux = 0 if s > 0 else -upper[0]
    return [
        P(f"{prefix}_upper", parent, pivot, (0, 0, s * splay), [B("leg", (ux, -upper[1] / 2, -upper[2] / 2), upper)]),
        P(f"{prefix}_lower", f"{prefix}_upper", (s * (upper[0] - 0.5), 0, 0), (0, 0, -s * splay),
          [B("leg", (-1.5, -1, -1.5), (3, lower_h, 3))]),
        P(f"{prefix}_foot", f"{prefix}_lower", (0, lower_h - 1, 0), (0, 0, 0), [B("foot", (-2, 0, -4), (4, 1, 5))]),
    ]


def _fern(name, parent, pivot, yaw, height, length, lean=12):
    """A fern tuft on the back: two crossed fronds."""
    return [P(f"{name}_a", parent, pivot, (-lean, yaw, 0), [B("fern", (0, -height, -length / 2), (0, height, length))]),
            P(f"{name}_b", parent, pivot, (-lean, yaw + 90, 0), [B("fern", (0, -height, -length / 2), (0, height, length))])]


MOSSBACK_PARTS = [
    # the body: chest and hips, so the spine can bend from side to side as it walks
    P("chest", "root", (0, 16.5, -1), (0, 0, 0), [B("hide", (-7, -4, -9), (14, 8, 9))]),
    P("moss_chest", "chest", (0, 0, 0), (0, 0, 0), [B("moss", (-6, -6, -8.5), (12, 2, 8), 0.25)]),
    P("hips", "chest", (0, 0, 0), (0, 0, 0), [B("hide", (-6.5, -3.5, 0), (13, 7, 9))]),
    P("moss_hips", "hips", (0, 0, 0), (0, 0, 0), [B("moss", (-5.5, -5.5, 0.5), (11, 2, 8), 0.25)]),
    # neck and head; the jaw hangs from a hinge at the back of the mouth, the throat sac under it
    P("neck", "chest", (0, -0.5, -9), (0, 0, 0), [B("hide", (-4.5, -3.5, -4), (9, 7, 5))]),
    P("head", "neck", (0, -0.5, -4), (0, 0, 0), [
        B("skull", (-5, -3.5, -6), (10, 5, 6)),
        B("snout", (-4, -2.5, -11), (8, 4, 5)),
        B("brow", (2, -4.5, -5.5), (3, 1, 4)),
        B("brow", (-5, -4.5, -5.5), (3, 1, 4)),
    ]),
    P("jaw", "head", (0, 1.5, -1), (0, 0, 0), [B("jaw", (-4.5, 0, -10), (9, 2, 10))]),
    P("throat", "jaw", (0, 2, -3), (0, 0, 0), [B("sac", (-3.5, 0, -5), (7, 2, 7))]),
    # frills folded back along the neck; they fan out when it hunts
    P("frill_left", "head", (5, -1, -2), (0, 10, 0), [B("frill", (0, -3, 0), (0, 6, 6))]),
    P("frill_right", "head", (-5, -1, -2), (0, -10, 0), [B("frill", (0, -3, 0), (0, 6, 6))]),
    # tail: five segments that follow the body's turns
    P("tail1", "hips", (0, -1, 9), (-6, 0, 0), [B("hide", (-4, -2.5, 0), (8, 6, 7))]),
    P("moss_tail", "tail1", (0, 0, 0), (0, 0, 0), [B("moss", (-3, -4, 0.5), (6, 2, 5), 0.2)]),
    P("tail2", "tail1", (0, 0.5, 7), (-4, 0, 0), [B("tail", (-3, -2, 0), (6, 5, 6))]),
    P("tail3", "tail2", (0, 0.5, 6), (-2, 0, 0), [B("tail", (-2, -1.5, 0), (4, 4, 6))]),
    P("tail4", "tail3", (0, 0.5, 6), (0, 0, 0), [B("tail", (-1.5, -1, 0), (3, 3, 6))]),
    P("tail5", "tail4", (0, 0.5, 6), (0, 0, 0), [B("tail", (-1, -0.5, 0), (2, 2, 5))]),
]
MOSSBACK_PARTS += _leg(1, "front_left", "chest", (6.5, 1.5, -5.5), (6, 3, 3), 6, 8)
MOSSBACK_PARTS += _leg(-1, "front_right", "chest", (-6.5, 1.5, -5.5), (6, 3, 3), 6, 8)
MOSSBACK_PARTS += _leg(1, "hind_left", "hips", (6, 1.5, 5.5), (6, 4, 4), 6, 6)
MOSSBACK_PARTS += _leg(-1, "hind_right", "hips", (-6, 1.5, 5.5), (6, 4, 4), 6, 6)
MOSSBACK_PARTS += _fern("fern1", "chest", (3, -6, -5), 30, 6, 7)
MOSSBACK_PARTS += _fern("fern2", "hips", (-2.5, -5.5, 4), -20, 5, 6)
MOSSBACK_PARTS += _fern("fern3", "chest", (-3.5, -6, -2), 70, 4, 5, lean=20)

CREATURES = {"mossback": MOSSBACK_PARTS}


# =============================================================================================== texture layout
# Vanilla's box layout (ModelPart.Cube): a box of size (w, h, d) at (u, v) uses a strip 2(w + d) wide, d + h tall.
#   row v .. v+d:        top   at u+d .. u+d+w  (u runs with x, row 0 is the back edge, +z)
#                        belly at u+d+w .. u+d+2w (u runs with x, row 0 is the back edge)
#   row v+d .. v+d+h:    west (-x) at u .. u+d   (u runs from back to front)
#                        north (front, -z) at u+d .. u+d+w (u runs with x)
#                        east (+x) at u+d+w .. u+2d+w (u runs from front to back)
#                        south (back, +z) at u+2d+w .. u+2d+2w (u runs against x)
#   on the four sides, row 0 is the top of the box.

def faces(box):
    u, v = box["uv"]
    w, h, d = box["size"]
    return {
        "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
        "west": (u, v + d, d, h), "north": (u + d, v + d, w, h),
        "east": (u + d + w, v + d, d, h), "south": (u + 2 * d + w, v + d, w, h),
    }


def pack(parts, width=TEX_W, height=TEX_H):
    """Gives every box a place on the texture (tallest strips first, in shelves). Boxes of a mirrored pair share
    nothing: each gets its own strip, so the two sides can differ."""
    boxes = [b for p in parts for b in p["boxes"]]
    order = sorted(range(len(boxes)), key=lambda i: (-(boxes[i]["size"][2] + boxes[i]["size"][1]), i))
    x = y = shelf = 0
    for i in order:
        b = boxes[i]
        w, h, d = b["size"]
        bw, bh = 2 * (w + d), d + h
        if bw == 0 or bh == 0:
            b["uv"] = [0, 0]
            continue
        if x + bw > width:
            x, y, shelf = 0, y + shelf, 0
        assert y + bh <= height, "the texture is too small for the shape"
        b["uv"] = [x, y]
        x += bw
        shelf = max(shelf, bh)


# =============================================================================================== palettes
PALETTES = {
    "moss": {
        "hide": ["#1b2414", "#24311a", "#2e3e20", "#394c27", "#45592e", "#526836"],
        "belly": ["#5f6840", "#727a4d", "#868d5b", "#999f69"],
        "moss": ["#2f4a17", "#3b5c1c", "#4a6f22", "#5a8329", "#6b9630", "#80a83a"],
        "fern": ["#2a4a1b", "#3a6125", "#4c7b30", "#62963d", "#7aae4b"],
        "mouth": ["#3a1416", "#5a2224", "#7a3434", "#94484a"],
        "tooth": ["#b9b292", "#d9d3b6"],
        "claw": ["#1c1812", "#3d362a"],
        "sac": ["#5e6a2a", "#728134", "#88993e"],
        "glow": ["#c4ec50", "#e2ff86"],
        "frill": ["#33461b", "#425a22", "#53702a"],
        "vein": "#24320f",
        "iris": ["#c7801c", "#f2b134", "#ffd77a"],
        "pupil": "#120c05",
        "bark": ["#2a2318", "#3a3122", "#4a3f2c"],
    },
    "frost": {
        "hide": ["#1b2229", "#232c34", "#2d3841", "#38454f", "#44535e", "#52626d"],
        "belly": ["#6c787e", "#808c92", "#949fa5", "#a8b2b7"],
        "moss": ["#2a4352", "#355363", "#426474", "#537a88", "#67909c", "#80a9b2"],
        "fern": ["#4c6b66", "#62857e", "#7fa39b", "#a1c3bb", "#c4e0d9"],
        "mouth": ["#2e1620", "#4a2230", "#663040", "#7e4252"],
        "tooth": ["#b4bcbe", "#dde4e6"],
        "claw": ["#15191d", "#343d44"],
        "sac": ["#3f6874", "#4f808c", "#6198a4"],
        "glow": ["#8fe8ff", "#d4fbff"],
        "frill": ["#2c444e", "#385663", "#476b78"],
        "vein": "#1a2a31",
        "iris": ["#4a9fc0", "#8fe6ff", "#d6f9ff"],
        "pupil": "#081014",
        "bark": ["#262a2e", "#343a40", "#444b52"],
    },
}


def C(h):
    return hexc(h)


# =============================================================================================== painting
class Canvas:
    """The texture plus its two glow masks: 'eyes' (always faintly lit) and 'flare' (lit while it hunts)."""

    def __init__(self):
        self.img = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.eyes = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.flare = Image.new("RGBA", (TEX_W, TEX_H), (0, 0, 0, 0))
        self.px, self.ex, self.fx = self.img.load(), self.eyes.load(), self.flare.load()

    def put(self, x, y, c, glow=None, dim=None):
        """dim: the colour the texture keeps under a glowing pixel (the glow mask gets c)."""
        if 0 <= x < TEX_W and 0 <= y < TEX_H:
            self.px[x, y] = dim if dim is not None else c
            if glow == "eyes": self.ex[x, y] = c
            elif glow == "flare": self.fx[x, y] = c

    def clear(self, x, y):
        if 0 <= x < TEX_W and 0 <= y < TEX_H:
            self.px[x, y] = (0, 0, 0, 0)


def ramp(pal, t):
    t = max(0.0, min(0.999, t))
    return C(pal[int(t * len(pal))])


def noise2(rng, w, h, cell=3.0):
    """Smooth value noise over a w x h face, in [0, 1)."""
    gw, gh = int(w / cell) + 2, int(h / cell) + 2
    g = [[rng.random() for _ in range(gw)] for _ in range(gh)]
    out = [[0.0] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            fx, fy = x / cell, y / cell
            x0, y0 = int(fx), int(fy)
            tx, ty = fx - x0, fy - y0
            tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
            a = g[y0][x0] * (1 - tx) + g[y0][x0 + 1] * tx
            b = g[y0 + 1][x0] * (1 - tx) + g[y0 + 1][x0 + 1] * tx
            out[y][x] = a * (1 - ty) + b * ty
    return out


def scales(cv, rect, pal, rng, light, belly_pal=None, belly_rows=0, spots=0.0):
    """Hide: small offset scales, each a little lighter in its middle, with darker blotches. light (0..1) sets how
    lit the face reads; the lowest belly_rows rows fade into the belly's colours."""
    x0, y0, w, h = rect
    n = noise2(rng, w, h, 3.5)
    for y in range(h):
        for x in range(w):
            sx = (x + (y // 2) % 2) % 2
            edge = (y % 2 == 1) and sx == 0
            t = light + 0.28 * (n[y][x] - 0.5) - (0.16 if edge else 0.0) + 0.05 * rng.random()
            c = ramp(pal, t)
            if belly_pal and h - y <= belly_rows:
                bt = 0.25 + 0.5 * n[y][x] - (0.12 if edge else 0.0)
                c = ramp(belly_pal, bt)
            if spots and n[y][x] > 1 - spots and not edge:
                c = ramp(pal, light + 0.3)
            cv.put(x0 + x, y0 + y, c)


def belly(cv, rect, pal, rng, bands_along_v=True):
    x0, y0, w, h = rect
    for y in range(h):
        for x in range(w):
            band = (y if bands_along_v else x) % 2 == 1
            t = 0.55 + 0.25 * rng.random() - (0.25 if band else 0.0) - 0.2 * abs((x + 0.5) / max(w, 1) - 0.5)
            cv.put(x0 + x, y0 + y, ramp(pal, t))


def moss(cv, rect, pal, rng, side=False):
    """A cushion of small tufts lit from above; on the sides the bottom row is ragged, strands hang a pixel lower."""
    x0, y0, w, h = rect
    for y in range(h):
        for x in range(w):
            cv.put(x0 + x, y0 + y, ramp(pal, 0.15 + 0.25 * rng.random()))
    for _ in range(max(1, w * h // 3)):
        cx, cy = rng.randrange(w), rng.randrange(h)
        r = rng.choice((0, 1, 1))
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                if abs(dx) + abs(dy) > r: continue
                x, y = cx + dx, cy + dy
                if 0 <= x < w and 0 <= y < h:
                    t = 0.55 + 0.2 * rng.random() - 0.18 * (dx + dy)
                    cv.put(x0 + x, y0 + y, ramp(pal, t))
    if side:
        for x in range(w):
            if rng.random() < 0.4:
                cv.clear(x0 + x, y0 + h - 1)


def fern(cv, rect, pal, rng):
    """A frond on a transparent ground: a curving stem up the middle, leaflets either side, shorter towards the tip."""
    x0, y0, w, h = rect
    for y in range(h):
        for x in range(w):
            cv.clear(x0 + x, y0 + y)
    mid = (w - 1) / 2.0
    for y in range(h):
        t = y / max(h - 1, 1)                      # 0 at the tip (top row), 1 at the base
        sx = int(round(mid + 0.8 * math.sin(t * 2.4 + rng.random() * 0.3)))
        cv.put(x0 + sx, y0 + y, ramp(pal, 0.25))
        reach = int(round((0.3 + 0.7 * t) * (w / 2.0)))
        for side in (-1, 1):
            if (y + (side > 0)) % 2: continue
            for k in range(1, reach + 1):
                x = sx + side * k
                if 0 <= x < w:
                    cv.put(x0 + x, y0 + y - (1 if k == reach and y > 0 else 0), ramp(pal, 0.5 + 0.12 * k - 0.2 * t + 0.1 * rng.random()))


def fill(cv, rect, pal, rng, lo=0.2, hi=0.6):
    x0, y0, w, h = rect
    for y in range(h):
        for x in range(w):
            cv.put(x0 + x, y0 + y, ramp(pal, lo + (hi - lo) * rng.random()))


def side_fronts(rect, face):
    """Columns of a side face from front to back: west runs back to front, east front to back."""
    x0, y0, w, h = rect
    cols = list(range(w))
    return cols[::-1] if face == "west" else cols


def paint_box(cv, box, pal, rng):
    f = faces(box)
    mat = box["mat"]
    w, h, d = box["size"]
    if mat in ("hide", "tail", "leg", "skull", "snout", "brow", "foot"):
        dark = mat == "brow"
        scales(cv, f["top"], pal["hide"], rng, 0.3 if not dark else 0.15, spots=0.12 if mat == "tail" else 0.0)
        belly(cv, f["bottom"], pal["belly"], rng)
        for side in ("west", "east"):
            scales(cv, f[side], pal["hide"], rng, 0.5 if not dark else 0.2, pal["belly"], 1 if h >= 4 and mat not in ("leg", "foot") else 0,
                   spots=0.1 if mat in ("hide", "tail") else 0.0)
        for side in ("north", "south"):
            scales(cv, f[side], pal["hide"], rng, 0.42 if not dark else 0.2, pal["belly"], 1 if h >= 4 and mat != "leg" else 0)
        if mat in ("hide", "tail") and h >= 3:
            # the markings: a broken row of spots along each upper flank that glows bright green while it moves
            for side in ("west", "east"):
                x0, y0, sw, sh = f[side]
                row = 1 if sh >= 5 else 0
                for x in range(1, sw - 1):
                    k = (x + (1 if side == "east" else 0)) % 3
                    if k == 0:
                        cv.put(x0 + x, y0 + row, C(pal["glow"][1]), "flare", ramp(pal["hide"], 0.62))
                    elif k == 1 and sh >= 6 and rng.random() < 0.55:
                        cv.put(x0 + x, y0 + row + 1, C(pal["glow"][0]), "flare", ramp(pal["hide"], 0.55))
        if mat == "skull":
            # an amber eye towards the front of each side, under the brow: iris around a slit pupil
            for side in ("west", "east"):
                x0, y0, sw, sh = f[side]
                cols = side_fronts(f[side], side)
                ex, ey = cols[1], 1
                for dx, dy, c, g in ((0, 0, pal["iris"][2], True), (1, 0, pal["iris"][1], True),
                                     (0, 1, pal["iris"][1], True), (1, 1, pal["iris"][0], True)):
                    xx = cols[min(len(cols) - 1, 1 + dx)] if True else ex
                    cv.put(x0 + xx, y0 + ey + dy, C(c), "eyes" if g else None)
                px_col = cols[min(len(cols) - 1, 1 + 1)] if side == "east" else cols[1]
                cv.put(x0 + cols[2] if side == "west" else x0 + cols[1], y0 + ey, C(pal["pupil"]))
        if mat == "snout":
            x0, y0, sw, sh = f["north"]
            cv.put(x0 + 2, y0 + 1, C(pal["hide"][0]))
            cv.put(x0 + sw - 3, y0 + 1, C(pal["hide"][0]))
            # palate (seen when the jaw opens) and a row of teeth along the bottom of each side
            fill(cv, f["bottom"], pal["mouth"], rng, 0.3, 0.8)
            for side in ("west", "east"):
                x0, y0, sw, sh = f[side]
                for x in range(sw):
                    if x % 2 == 0: cv.put(x0 + x, y0 + sh - 1, C(pal["tooth"][x % 4 // 2]))
            x0, y0, sw, sh = f["north"]
            for x in range(1, sw - 1, 2):
                cv.put(x0 + x, y0 + sh - 1, C(pal["tooth"][1]))
        if mat == "foot":
            x0, y0, sw, sh = f["north"]
            for x in range(0, sw, 2 if sw > 3 else 1):
                cv.put(x0 + x, y0 + sh - 1, C(pal["claw"][1]))
            x0, y0, sw, sh = f["top"]
            for x in range(0, sw, 2 if sw > 3 else 1):
                cv.put(x0 + x, y0 + sh - 1, C(pal["claw"][0]))
    elif mat == "jaw":
        fill(cv, f["top"], pal["mouth"], rng, 0.35, 0.95)               # tongue and gums, seen when it gapes
        x0, y0, tw, th = f["top"]
        for y in range(th):
            for x in (0, tw - 1):
                if y % 2 == 0: cv.put(x0 + x, y0 + y, C(pal["tooth"][1]))
        belly(cv, f["bottom"], pal["belly"], rng)
        for side in ("west", "east", "north", "south"):
            scales(cv, f[side], pal["hide"], rng, 0.45, pal["belly"], 1)
            x0, y0, sw, sh = f[side]
            if side != "south":
                for x in range(sw):
                    if x % 2 == 1: cv.put(x0 + x, y0, C(pal["tooth"][0]))
    elif mat == "sac":
        # the throat sac: pale skin with spots that glow when it flares
        for name, rect in f.items():
            x0, y0, sw, sh = rect
            n = noise2(rng, sw, sh, 2.0)
            for y in range(sh):
                for x in range(sw):
                    if n[y][x] > 0.6 and name != "top":
                        cv.put(x0 + x, y0 + y, C(pal["glow"][1 if n[y][x] > 0.78 else 0]), "flare", ramp(pal["sac"], 0.95))
                    else:
                        cv.put(x0 + x, y0 + y, ramp(pal["sac"], 0.3 + 0.6 * n[y][x]))
    elif mat == "frill":
        # a fan of skin on thin ribs: hinged at the front edge, rounded at the back, rib tips glowing
        for side in ("west", "east"):
            x0, y0, sw, sh = f[side]
            for y in range(sh):
                for x in range(sw):
                    a = (x if side == "east" else sw - 1 - x) + 0.5         # 0 at the hinge (front), sw at the back
                    b = abs(y + 0.5 - sh / 2.0) / (sh / 2.0)                 # 0 in the middle row, 1 at the edges
                    r = (a / sw) ** 2 + b ** 2 * (0.35 + 0.65 * a / sw)
                    if r > 1.0:
                        cv.clear(x0 + x, y0 + y)
                    elif r > 0.72 and (y % 2 == 0):
                        cv.put(x0 + x, y0 + y, C(pal["glow"][1]), "flare", ramp(pal["frill"], 0.95))
                    elif y % 2 == 0:
                        cv.put(x0 + x, y0 + y, C(pal["vein"]))
                    else:
                        cv.put(x0 + x, y0 + y, ramp(pal["frill"], 0.3 + 0.5 * rng.random()))
    elif mat == "moss":
        moss(cv, f["top"], pal["moss"], rng)
        for side in ("west", "east", "north", "south"):
            moss(cv, f[side], pal["moss"], rng, side=True)
        fill(cv, f["bottom"], pal["moss"], rng, 0.0, 0.2)
    elif mat == "fern":
        fern(cv, f["west"], pal["fern"], rng)
        fern(cv, f["east"], pal["fern"], rng)
    else:
        raise ValueError(mat)


def paint(parts, variant, seed):
    cv = Canvas()
    pal = PALETTES[variant]
    rng = random.Random(seed)
    for p in parts:
        for b in p["boxes"]:
            paint_box(cv, b, pal, rng)
    return cv


# =============================================================================================== the Skein
# The Blue Reaches' crawler: a long, low thing of overlapping cobalt shell plates, widest just behind its wedge of a
# head and tapering to two trailing spurs, each plate with a thin pale glassy rim, a seam of cyan light down the
# middle of its back, two thin dark legs splayed from under every plate, small hooked mandibles and two long feelers.
# The mod's own creature; nothing here is taken from any other game.
#
# Its model is a few loose pieces that the renderer (client.render.SkeinRenderer) draws again and again along the
# trail its head has run: the head, three sizes of shell plate (large behind the head, then mid, then small) and the
# tail. Every piece stands on the rock at its own pivot (0, 24, 0), facing -z, so the renderer can lay each on the trail
# where it lies. A plate tips up a little at the back, so each one's rim rides over the front of the next.

SKEIN_FEMUR, SKEIN_SHIN = 6, 7                      # leg lengths, px: thigh out and up to a high knee, shin down
SKEIN_PLATE_D = 7                                   # a plate's length, px (they lie 4.8 px apart, so they overlap)
SKEIN_WIDTHS = {"plate_large": 16, "plate_mid": 13, "plate_small": 10, "tail": 8}


def _skein_legs(piece, half):
    """A plate's pair of legs, from under its edge, splayed wide and low: each thigh out and a little up to its knee,
    each shin down and out to the rock, the foot well outside the shell."""
    x = half - 2
    return [
        P(f"{piece}_leg_left", piece, (x, -3, 0), (0, 0, -20), [B("leg", (0, -0.5, -0.5), (SKEIN_FEMUR, 1, 1))]),
        P(f"{piece}_shin_left", f"{piece}_leg_left", (SKEIN_FEMUR - 0.5, 0, 0), (0, 0, -25),
          [B("shin", (-0.5, 0, -0.5), (1, SKEIN_SHIN, 1))]),
        P(f"{piece}_leg_right", piece, (-x, -3, 0), (0, 0, 20), [B("leg", (-SKEIN_FEMUR, -0.5, -0.5), (SKEIN_FEMUR, 1, 1))]),
        P(f"{piece}_shin_right", f"{piece}_leg_right", (-(SKEIN_FEMUR - 0.5), 0, 0), (0, 0, 25),
          [B("shin", (-0.5, 0, -0.5), (1, SKEIN_SHIN, 1))]),
    ]


def _skein_plate(piece):
    """One shell plate: the domed shell with the seam's ridge along its top and the pale belly under it, and its legs.
    The shell is its own part so the renderer can taper it plate by plate without stretching the legs."""
    w, d = SKEIN_WIDTHS[piece], SKEIN_PLATE_D
    half = w / 2
    return [
        P(piece, "root", (0, 24, 0), (0, 0, 0), []),
        P(f"{piece}_shell", piece, (0, 0, 0), (6, 0, 0), [
            B("shell", (-half, -7, -d / 2), (w, 3, d)),
            B("ridge", (-1.5, -8, -d / 2 + 0.5), (3, 1, d - 1)),
            B("belly", (-(half - 2), -4, -d / 2 + 0.5), (w - 4, 2, d - 1)),
        ]),
    ] + _skein_legs(piece, half)


SKEIN_PARTS = [
    # the head: a wedge of a head plate over the head itself, stepping down to a blunt snout; eyes on its sides
    P("head", "root", (0, 24, 0), (0, 0, 0), [
        B("shield", (-7, -8, -4), (14, 2, 6)),
        B("shield", (-5, -7.5, -8), (10, 2, 4)),
        B("shield", (-3, -7, -11), (6, 2, 3)),
        B("skull", (-5, -6, -10), (10, 4, 10)),
        B("eye", (5, -6, -8.5), (1, 1, 2)),
        B("eye", (-6, -6, -8.5), (1, 1, 2)),
    ]),
    # small hooked mandibles under the snout, their tips turned in
    P("mandible_left", "head", (3, -3, -10), (0, 18, 0), [B("mandible", (-1, -0.5, -5), (2, 1, 5)),
                                                          B("fang", (-2, -0.5, -6), (2, 1, 1))]),
    P("mandible_right", "head", (-3, -3, -10), (0, -18, 0), [B("mandible", (-1, -0.5, -5), (2, 1, 5)),
                                                             B("fang", (0, -0.5, -6), (2, 1, 1))]),
    # two long feelers: up and out from the brow, the outer half bending further out and back
    P("feeler_left", "head", (2.5, -7.5, -10.5), (-22, -50, 0), [B("feeler", (-0.5, -0.5, -10), (1, 1, 10))]),
    P("feeler_left_tip", "feeler_left", (0, 0, -10), (14, -40, 0), [B("feeler_tip", (-0.5, -0.5, -9), (1, 1, 9))]),
    P("feeler_right", "head", (-2.5, -7.5, -10.5), (-22, 50, 0), [B("feeler", (-0.5, -0.5, -10), (1, 1, 10))]),
    P("feeler_right_tip", "feeler_right", (0, 0, -10), (14, 40, 0), [B("feeler_tip", (-0.5, -0.5, -9), (1, 1, 9))]),
]
for _piece in ("plate_large", "plate_mid", "plate_small", "tail"):
    SKEIN_PARTS += _skein_plate(_piece)
# the two short spurs the tail ends in, trailing back, up a little and out
SKEIN_PARTS += [
    P("tail_spur_left", "tail", (2, -5, 3), (10, 22, 0), [B("spur", (-0.5, -0.5, 0), (1, 1, 7))]),
    P("tail_spur_right", "tail", (-2, -5, 3), (10, -22, 0), [B("spur", (-0.5, -0.5, 0), (1, 1, 7))]),
]

SKEIN_PALETTE = {
    "shell": ["#0b1533", "#101d47", "#16285e", "#1d3478", "#244293", "#2e52ad", "#3d66c4"],
    "rim": ["#5aa3c8", "#86cfe6", "#bdeef8"],
    "rim_glow": ["#1e5f82", "#2c7fa6"],
    "seam": ["#3fc8f2", "#8fe8ff", "#d4fbff"],
    "dark": ["#070c1d", "#0c1430", "#121d42"],
    "belly": ["#25304c", "#323f60", "#435274", "#56668a"],
    "leg": ["#0c1328", "#141e3b", "#1e2b50", "#2c3d68"],
    "claw": ["#7f95b8", "#b6c6dd"],
    "mandible": ["#070b18", "#141c33", "#2a3654"],
    "fang": ["#b9c8de", "#e2ebf5"],
    "eye": ["#5fd6ff", "#c4f6ff", "#f2feff"],
    "pupil": "#04101a",
    "mouth": ["#1a0f2a", "#2a1840", "#3c2456"],
}


def _along(face, i, j, size):
    """How far along a box's length (z) a texel lies, 0 at the back (+z) to d-1 at the front."""
    w, h, d = size
    if face in ("top", "bottom"): return j
    if face == "west": return i
    if face == "east": return d - 1 - i
    return d - 1 if face == "north" else 0


def _texels(cv, box, fn):
    """Calls fn(face, i, j, face_w, face_h) for every texel of every face and puts what it gives: a colour, or a
    (colour, glow mask, dim colour) triple, or None for a see-through texel."""
    for face, (x0, y0, fw, fh) in faces(box).items():
        for j in range(fh):
            for i in range(fw):
                c = fn(face, i, j, fw, fh)
                if c is None:
                    cv.clear(x0 + i, y0 + j)
                elif isinstance(c, tuple) and len(c) == 3 and isinstance(c[1], (str, type(None))):
                    cv.put(x0 + i, y0 + j, c[0], c[1], c[2])
                else:
                    cv.put(x0 + i, y0 + j, c)


def _skein_shell(cv, box, pal, rng, front_rim=False):
    """A shell plate (or the head's plate): glossy cobalt, lit along its crown and dark towards its sides, finely
    pitted, with a thin pale glassy rim round its edge that holds a little light. The back edge's rim rides over the
    next plate; the front edge lies under the one before (the head's has a rim all round)."""
    w, h, d = box["size"]
    n = noise2(rng, w + d * 2, d + h, 2.5)

    def fn(face, i, j, fw, fh):
        r = rng.random()
        if face == "top":
            crown = 1.0 - abs((i + 0.5) / w - 0.5) * 2.0                   # 1 along the middle, 0 at the edges
            if j == 0 or (front_rim and j == d - 1):
                # the rim holds only a little light of its own (the glow texture adds to the colour under it)
                return (C(pal["rim_glow"][1 if r < 0.7 else 0]), "flare", C(pal["rim"][1 if r < 0.7 else 2]))
            t = 0.25 + 0.55 * crown + 0.25 * (n[j][i] - 0.5) - (0.12 if r < 0.08 else 0.0)
            if j == 1 and r < 0.5: t += 0.12                               # a glint just inside the back rim
            return ramp(pal["shell"], t)
        if face in ("west", "east") or (front_rim and face == "north"):
            if j == 0:
                return (C(pal["rim_glow"][0]), "flare", C(pal["rim"][0]))
            return ramp(pal["shell"], 0.18 + 0.15 * n[min(j, len(n) - 1)][min(i, len(n[0]) - 1)] - 0.08 * j)
        if face == "south":
            return ramp(pal["shell"], 0.3 - 0.08 * j + 0.1 * r)
        if face == "north":
            return ramp(pal["dark"], 0.5 + 0.4 * r)
        return ramp(pal["dark"], 0.3 + 0.5 * r)                            # its underside, under the belly

    _texels(cv, box, fn)


def _skein_ridge(cv, box, pal, rng):
    """The low ridge down the middle of each plate: the seam of cyan light along its crown, dark flanks."""
    w, h, d = box["size"]

    def fn(face, i, j, fw, fh):
        k = _along(face, i, j, box["size"])
        if face == "top":
            if i == w // 2:
                bright = 2 if k in (0, d - 1) else 1                       # brightest where it meets the next plate
                return (C(pal["seam"][bright]), "flare", C(pal["shell"][3]))
            return (C(pal["seam"][0]), "flare", ramp(pal["shell"], 0.55)) if rng.random() < 0.2 else ramp(pal["shell"], 0.6)
        if face in ("south", "north"):
            return (C(pal["seam"][1]), "flare", C(pal["shell"][3])) if i == w // 2 else ramp(pal["shell"], 0.4)
        return ramp(pal["shell"], 0.35 + 0.2 * rng.random())

    _texels(cv, box, fn)


def _skein_belly(cv, box, pal, rng):
    """The pale underside: soft grey-blue in bands across, barely seen but for the glimpse between its legs."""

    def fn(face, i, j, fw, fh):
        k = _along(face, i, j, box["size"])
        band = k % 3 == 0
        return ramp(pal["belly"], 0.35 + 0.45 * rng.random() - (0.3 if band else 0.0))

    _texels(cv, box, fn)


def _skein_leg(cv, box, pal, rng, shin):
    """A thin dark navy leg, banded at its joints; a shin ends in a pale claw."""
    w, h, d = box["size"]
    length = h if shin else w

    def fn(face, i, j, fw, fh):
        if shin:
            at = j if face not in ("top", "bottom") else (0 if face == "top" else length - 1)
        else:
            at = i if face in ("top", "bottom", "north") else (w - 1 - i if face == "south" else 0)
        if shin and at == length - 1:
            return C(pal["claw"][1 if face != "bottom" else 0])
        joint = at == 0 or at == length - 1
        return ramp(pal["leg"], (0.75 if joint else 0.3) + 0.2 * rng.random() + (0.1 if face == "top" else 0.0))

    _texels(cv, box, fn)


def _skein_skull(cv, box, pal, rng):
    """The head under its plate: dark, its mouth parts below, a few tiny lit eyelets along each side."""
    w, h, d = box["size"]

    def fn(face, i, j, fw, fh):
        k = _along(face, i, j, box["size"])
        if face == "bottom":
            return ramp(pal["mouth"], 0.2 + 0.7 * rng.random()) if k > d // 2 else ramp(pal["belly"], 0.2 + 0.3 * rng.random())
        if face in ("west", "east") and j == 0 and k in (d - 4, d - 6):
            return (C(pal["eye"][1]), "eyes", C(pal["eye"][0]))
        if face == "north":
            return ramp(pal["mouth"], 0.3 + 0.6 * rng.random()) if j >= h - 2 else ramp(pal["shell"], 0.25 + 0.2 * rng.random())
        return ramp(pal["shell"], 0.15 + 0.25 * rng.random() + 0.04 * k)

    _texels(cv, box, fn)


def _skein_eye(cv, box, pal, rng):
    """A glassy eye: pale cyan light round a dark pupil on its outer face."""

    def fn(face, i, j, fw, fh):
        if face in ("west", "east") and i == (1 if face == "east" else 0):
            return C(pal["pupil"])
        return (C(pal["eye"][2 if face == "top" else 1]), "eyes", C(pal["eye"][0]))

    _texels(cv, box, fn)


def _skein_feeler(cv, box, pal, rng, tip):
    """A feeler: thin dark rings, lighter between; its tip holds a faint light."""
    w, h, d = box["size"]

    def fn(face, i, j, fw, fh):
        k = _along(face, i, j, box["size"])
        if tip and k >= d - 2:
            return (C(pal["seam"][1 if k == d - 1 else 0]), "eyes", C(pal["rim"][0]))
        return ramp(pal["leg"], 0.25 + (0.45 if k % 2 == 0 else 0.0) + 0.15 * rng.random())

    _texels(cv, box, fn)


def _skein_spur(cv, box, pal, rng):
    """A tail spur: dark shell with a glassy rim along its top, its point lit."""
    w, h, d = box["size"]

    def fn(face, i, j, fw, fh):
        k = _along(face, i, j, box["size"])
        if k == 0 or face == "south":
            return (C(pal["seam"][1]), "flare", C(pal["rim"][0]))
        if face == "top":
            return (C(pal["rim"][0]), "flare", C(pal["shell"][3])) if k % 2 == 0 else ramp(pal["shell"], 0.6)
        return ramp(pal["shell"], 0.25 + 0.3 * rng.random())

    _texels(cv, box, fn)


def paint_skein_box(cv, box, pal, rng):
    mat = box["mat"]
    if mat == "shell": _skein_shell(cv, box, pal, rng)
    elif mat == "shield": _skein_shell(cv, box, pal, rng, front_rim=True)
    elif mat == "ridge": _skein_ridge(cv, box, pal, rng)
    elif mat == "belly": _skein_belly(cv, box, pal, rng)
    elif mat in ("leg", "shin"): _skein_leg(cv, box, pal, rng, mat == "shin")
    elif mat == "skull": _skein_skull(cv, box, pal, rng)
    elif mat == "eye": _skein_eye(cv, box, pal, rng)
    elif mat in ("feeler", "feeler_tip"): _skein_feeler(cv, box, pal, rng, mat == "feeler_tip")
    elif mat == "spur": _skein_spur(cv, box, pal, rng)
    elif mat == "mandible":
        _texels(cv, box, lambda f, i, j, fw, fh: ramp(pal["mandible"], 0.2 + 0.5 * rng.random() + (0.3 if f == "top" else 0.0)))
    elif mat == "fang":
        _texels(cv, box, lambda f, i, j, fw, fh: C(pal["fang"][1 if f in ("top", "north") else 0]))
    else:
        raise ValueError(mat)


def paint_skein(seed=4501):
    cv = Canvas()
    rng = random.Random(seed)
    for p in SKEIN_PARTS:
        for b in p["boxes"]:
            paint_skein_box(cv, b, SKEIN_PALETTE, rng)
    return cv


def write_skein(assets, write):
    """The Skein's geometry, its texture and its one glow texture (eyes, rims, seam and feeler tips: the renderer lights
    them all together, brighter while it moves); returns its lang entries."""
    pack(SKEIN_PARTS)
    write(os.path.join(assets, "geometry", "skein.json"), geometry_json(SKEIN_PARTS))
    tex_dir = os.path.join(assets, "textures", "entity", "skein")
    os.makedirs(tex_dir, exist_ok=True)
    cv = paint_skein()
    cv.img.save(os.path.join(tex_dir, "skein.png"))
    glow = Image.alpha_composite(cv.flare, cv.eyes)
    assert cv.eyes.getbbox() and cv.flare.getbbox(), "skein: no glowing pixels"
    glow.save(os.path.join(tex_dir, "skein_glow.png"))
    write(os.path.join(assets, "models", "item", "skein_spawn_egg.json"), {"parent": "minecraft:item/template_spawn_egg"})
    return {f"entity.{MOD}.skein": NAMES["skein"], f"item.{MOD}.skein_spawn_egg": NAMES["skein"] + " Spawn Egg",
            f"item.{MOD}.skein_plate": "Skein Plate"}


# The Skein's body as the renderer lays it (SkeinEntity.PLATES, PLATE_START, PLATE_STEP): which piece each plate is,
# and how wide it is drawn.
SKEIN_PLATES = 13
SKEIN_PLATE_START, SKEIN_PLATE_STEP = 0.12, 0.30


def skein_piece(k):
    return "tail" if k == SKEIN_PLATES - 1 else ("plate_large", "plate_mid", "plate_small")[min(2, k // 4)]


def skein_width(k):
    return 16.0 - 9.0 * k / (SKEIN_PLATES - 1)


# =============================================================================================== output
def geometry_json(parts):
    return {"texture_width": TEX_W, "texture_height": TEX_H, "parts": [
        {"name": p["name"], "parent": p["parent"], "pivot": p["pivot"], "rotation": p["rot"],
         "boxes": [{"origin": b["origin"], "size": b["size"], "uv": b["uv"], "inflate": b["inflate"]} for b in p["boxes"]]}
        for p in parts]}


SPAWN_EGGS = {"mossback": (0x2e3e20, 0xc4ec50), "skein": (0x1d3d96, 0x8fe8ff)}
NAMES = {"mossback": "Mossback", "skein": "Skein"}
VARIANTS = {"mossback": ("moss", "frost")}


def write_all(assets, write):
    """Writes geometry, textures and item models; returns the lang entries."""
    names = {}
    for name, parts in CREATURES.items():
        pack(parts)
        write(os.path.join(assets, "geometry", name + ".json"), geometry_json(parts))
        tex_dir = os.path.join(assets, "textures", "entity", name)
        os.makedirs(tex_dir, exist_ok=True)
        for i, variant in enumerate(VARIANTS[name]):
            cv = paint(parts, variant, 4401 + 17 * i)
            suffix = "" if i == 0 else "_" + variant
            cv.img.save(os.path.join(tex_dir, f"{name}{suffix}.png"))
            assert cv.eyes.getbbox() and cv.flare.getbbox(), f"{name}: no glowing pixels"
            cv.eyes.save(os.path.join(tex_dir, f"{name}{suffix}_eyes.png"))
            cv.flare.save(os.path.join(tex_dir, f"{name}{suffix}_flare.png"))
        write(os.path.join(assets, "models", "item", name + "_spawn_egg.json"), {"parent": "minecraft:item/template_spawn_egg"})
        names[f"entity.{MOD}.{name}"] = NAMES[name]
        names[f"item.{MOD}.{name}_spawn_egg"] = NAMES[name] + " Spawn Egg"
    names[f"item.{MOD}.mossback_hide"] = "Mossback Hide"
    # the Skein: one look, one glow texture, its pieces drawn along its trail (write_skein)
    names.update(write_skein(assets, write))
    return names


# =============================================================================================== preview
def _rot(rx, ry, rz):
    """Rz * Ry * Rx, as vanilla's ModelPart (Quaternionf.rotationZYX)."""
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    Rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    Ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    Rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    return _mm(Rz, _mm(Ry, Rx))


def _mm(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _mv(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def _world_transforms(parts, pose):
    """Each part's (rotation, translation) into model space; pose maps a part name to extra rotation (degrees) and
    scale, as the game's animation would set them."""
    byname = {p["name"]: p for p in parts}
    out = {}

    def get(name):
        if name == "root": return ([[1, 0, 0], [0, 1, 0], [0, 0, 1]], [0, 0, 0], [1, 1, 1])
        if name in out: return out[name]
        p = byname[name]
        pr, pt, ps = get(p["parent"])
        extra = pose.get(name, {})
        rot = [math.radians(a + b) for a, b in zip(p["rot"], extra.get("rot", (0, 0, 0)))]
        sc = extra.get("scale", (1, 1, 1))
        piv = [c / 16.0 for c in p["pivot"]]
        t = [pt[i] + _mv(pr, [piv[j] * ps[j] for j in range(3)])[i] for i in range(3)]
        r = _mm(pr, _rot(*rot))
        # scale is applied after the rotation, in the part's frame; keep it per axis for the boxes
        out[name] = (r, t, [ps[i] * sc[i] for i in range(3)])
        return out[name]

    for p in parts: get(p["name"])
    return out


def render(parts, tex, pose=None, yaw=30.0, pitch=25.0, size=512, dist=5.5, glow=None, glow_level=1.0, bg=(52, 56, 60),
           light_level=1.0):
    """A software render of the model: each texel of each face drawn as a small quad, far ones first."""
    pose = pose or {}
    xf = _world_transforms(parts, pose)
    tpx = tex.load()
    gpx = glow.load() if glow is not None else None
    W, H = tex.size
    quads = []
    # camera: orbit around the model's middle
    cy_, sy_ = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
    target = [0, 0.55, 0.15]
    cam = [target[0] + dist * sy_ * cp, target[1] + dist * sp, target[2] - dist * cy_ * cp]
    fwd = [target[i] - cam[i] for i in range(3)]
    fl = math.sqrt(sum(c * c for c in fwd)); fwd = [c / fl for c in fwd]
    right = [-fwd[2], 0, fwd[0]]; rl = math.sqrt(sum(c * c for c in right)); right = [c / rl for c in right]
    up = [right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0]]
    focal = size * 1.1
    light = [-0.4, 0.85, -0.35]

    def to_world(m):          # model space -> world: x and y flipped, ground at 0 (LivingEntityRenderer)
        return [-m[0], 1.501 - m[1], m[2]]

    def project(wp):
        d = [wp[i] - cam[i] for i in range(3)]
        z = sum(d[i] * fwd[i] for i in range(3))
        x = sum(d[i] * right[i] for i in range(3))
        y = sum(d[i] * up[i] for i in range(3))
        return (size / 2 + focal * x / z, size / 2 - focal * y / z, z)

    for p in parts:
        r, t, sc = xf[p["name"]]
        for b in p["boxes"]:
            ox, oy, oz = b["origin"]; w, h, d = b["size"]; g = b["inflate"]
            x0, y0, z0, x1, y1, z1 = ox - g, oy - g, oz - g, ox + w + g, oy + h + g, oz + d + g
            u, v = b["uv"]

            def P3(x, y, z):
                loc = [x / 16.0 * sc[0], y / 16.0 * sc[1], z / 16.0 * sc[2]]
                m = [t[i] + _mv(r, loc)[i] for i in range(3)]
                return to_world(m)

            # each face: corner at (u0, v0) and how x/y/z run along u and v (texel counts w_t x h_t)
            F = {
                "top": (lambda a, c: P3(x0 + (x1 - x0) * a, y0, z1 - (z1 - z0) * c), u + d, v, w, d),
                "bottom": (lambda a, c: P3(x0 + (x1 - x0) * a, y1, z1 - (z1 - z0) * c), u + d + w, v, w, d),
                "west": (lambda a, c: P3(x0, y0 + (y1 - y0) * c, z1 - (z1 - z0) * a), u, v + d, d, h),
                "north": (lambda a, c: P3(x0 + (x1 - x0) * a, y0 + (y1 - y0) * c, z0), u + d, v + d, w, h),
                "east": (lambda a, c: P3(x1, y0 + (y1 - y0) * c, z0 + (z1 - z0) * a), u + d + w, v + d, d, h),
                "south": (lambda a, c: P3(x1 - (x1 - x0) * a, y0 + (y1 - y0) * c, z1), u + 2 * d + w, v + d, w, h),
            }
            for fname, (fn, fu, fv, tw, th) in F.items():
                if tw == 0 or th == 0: continue
                a0, a1, c0 = fn(0, 0), fn(1, 0), fn(0, 1)
                e1 = [a1[i] - a0[i] for i in range(3)]; e2 = [c0[i] - a0[i] for i in range(3)]
                nrm = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]]
                nl = math.sqrt(sum(c * c for c in nrm)) or 1.0
                nrm = [c / nl for c in nrm]
                shade = 0.55 + 0.45 * abs(sum(nrm[i] * light[i] for i in range(3)))
                for j in range(th):
                    for i in range(tw):
                        cu, cvv = int(fu + i), int(fv + j)
                        if not (0 <= cu < W and 0 <= cvv < H): continue
                        col = tpx[cu, cvv]
                        if col[3] == 0: continue
                        gl = gpx[cu, cvv] if gpx else (0, 0, 0, 0)
                        corners = [fn(i / tw, j / th), fn((i + 1) / tw, j / th), fn((i + 1) / tw, (j + 1) / th), fn(i / tw, (j + 1) / th)]
                        pr_ = [project(c_) for c_ in corners]
                        if min(q[2] for q in pr_) <= 0.05: continue
                        depth = sum(q[2] for q in pr_) / 4
                        lit = shade * light_level
                        if gl[3]:
                            k = glow_level
                            c2 = tuple(min(255, int(col[n] * lit * (1 - k) + gl[n] * k)) for n in range(3))
                        else:
                            c2 = tuple(int(col[n] * lit) for n in range(3))
                        quads.append((depth, [(q[0], q[1]) for q in pr_], c2))
    img = Image.new("RGB", (size, size), bg)
    dr = ImageDraw.Draw(img)
    # ground grid, one line per block
    for k in range(-3, 4):
        for a, b_ in (((k, 0, -3), (k, 0, 3)), ((-3, 0, k), (3, 0, k))):
            pa, pb = project(list(a)), project(list(b_))
            if pa[2] > 0 and pb[2] > 0: dr.line([pa[:2], pb[:2]], fill=(70, 76, 80))
    for _, pts, col in sorted(quads, key=lambda q: -q[0]):
        dr.polygon(pts, fill=col)
    return img


WALK = {"front_left_upper": {"rot": (0, 28, 0)}, "hind_right_upper": {"rot": (0, 22, 0)},
        "front_right_upper": {"rot": (0, 14, -14)}, "hind_left_upper": {"rot": (0, 12, 14)},
        "chest": {"rot": (0, 6, 0)}, "hips": {"rot": (0, -12, 0)}, "tail1": {"rot": (0, -10, 0)},
        "tail2": {"rot": (0, 8, 0)}, "tail3": {"rot": (0, 14, 0)}, "tail4": {"rot": (0, 12, 0)}, "neck": {"rot": (0, -6, 0)}}

POSES = {
    "rest": {},
    "walk": WALK,
    "flare": {"jaw": {"rot": (38, 0, 0)}, "throat": {"scale": (1.35, 2.2, 1.25)}, "frill_left": {"rot": (0, 60, 0)},
              "frill_right": {"rot": (0, -60, 0)}, "neck": {"rot": (-14, 0, 0)}, "chest": {"rot": (-8, 0, 0)}},
}


SKEIN_REAR = ((0.4, 40), (0.28, 35), (0.18, 25), (0.09, 12), (0.03, 4))   # (lift in blocks, pitch) head, plates 0..3


def skein_layout(bend=0.0, phase=None, rear=0.0):
    """The Skein laid out as the renderer lays it, as one tree of parts for render(): its pieces along a trail that
    curves by bend (radians a block), its legs in their wave at phase (None: at rest), its front reared by rear (0..1)."""
    byname = {p["name"]: p for p in SKEIN_PARTS}

    def subtree(root):
        out = [byname[root]]
        for p in SKEIN_PARTS:
            if any(p["parent"] == q["name"] for q in out) and p not in out: out.append(p)
        return out

    def at(s):
        if abs(bend) < 1e-6: return 0.0, s * 16, 0.0
        th = bend * s
        return (1 - math.cos(th)) / bend * 16, math.sin(th) / bend * 16, math.degrees(th)

    cx, cz, _ = at(1.9)
    parts, pose = [], {}
    pieces = [("head", 0, 0.0)] + [(skein_piece(k), k, SKEIN_PLATE_START + SKEIN_PLATE_STEP * k) for k in range(SKEIN_PLATES)]
    for idx, (piece, k, s) in enumerate(pieces):
        x, z, yaw = at(s)
        tag = f"#{idx}"
        lift, pitch = SKEIN_REAR[idx] if idx < len(SKEIN_REAR) else (0.0, 0.0)
        for p in subtree(piece):
            q = dict(p, name=p["name"] + tag, parent=p["parent"] + tag if p["parent"] != "root" else "root")
            if p["name"] == piece:
                q["pivot"] = [x - cx, 24 - lift * rear * 16, z - cz]
                q["rot"] = [-pitch * rear, yaw, 0]
            parts.append(q)
        if piece == "head":
            continue
        w = skein_width(k)
        ls = 1.0 - 0.3 * k / (SKEIN_PLATES - 1)
        pose[f"{piece}_shell{tag}"] = {"scale": (w / SKEIN_WIDTHS[piece], 0.75 + 0.25 * w / 16, 1)}
        rest = 0.55 + (-0.6 - 0.55) * k / (SKEIN_PLATES - 1)
        for side, sgn in (("left", 1), ("right", -1)):
            leg = next(q for q in parts if q["name"] == f"{piece}_leg_{side}{tag}")
            leg["pivot"] = [sgn * (w / 2 - 2), -3 * ls, 0]
            ph = (phase - 0.65 * k + (math.pi if sgn < 0 else 0)) if phase is not None else None
            swing = 0.5 * math.sin(ph) if ph is not None else 0.0
            lift_ = 0.5 * max(0.0, math.cos(ph)) if ph is not None else 0.0
            pose[leg["name"]] = {"rot": (0, math.degrees(sgn * (rest + swing)), math.degrees(-sgn * lift_)), "scale": (ls, ls, ls)}
    return parts, pose


def preview_skein(out_dir):
    pack(SKEIN_PARTS)
    cv = paint_skein()
    cv.img.resize((TEX_W * 4, TEX_H * 4), Image.NEAREST).save(os.path.join(out_dir, "skein_texture.png"))
    glow = Image.alpha_composite(cv.flare, cv.eyes)
    tiles = []
    # lying still in the dark (dim glow); running round a bend, lit; from the side, running; rearing to strike
    for (bend, phase, rear), yaw, pitch, lvl, ll, bg in (((0.0, None, 0.0), 35, 30, 0.35, 0.55, (24, 27, 30)),
                                                        ((0.45, 1.3, 0.0), 20, 45, 1.0, 0.55, (24, 27, 30)),
                                                        ((0.0, 2.1, 0.0), 90, 8, 1.0, 1.0, (52, 56, 60)),
                                                        ((0.0, None, 1.0), -50, 15, 1.0, 1.0, (52, 56, 60))):
        parts, pose = skein_layout(bend, phase, rear)
        tiles.append(render(parts, cv.img, pose, yaw, pitch, 420, dist=9.0, glow=glow, glow_level=lvl, bg=bg, light_level=ll))
    sheet = Image.new("RGB", (420 * 2, 420 * 2))
    for k, t in enumerate(tiles):
        sheet.paste(t, ((k % 2) * 420, (k // 2) * 420))
    sheet.save(os.path.join(out_dir, "skein.png"))
    print("preview skein")


def preview(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    preview_skein(out_dir)
    for name, parts in CREATURES.items():
        pack(parts)
        for i, variant in enumerate(VARIANTS[name]):
            cv = paint(parts, variant, 4401 + 17 * i)
            cv.img.resize((TEX_W * 4, TEX_H * 4), Image.NEAREST).save(os.path.join(out_dir, f"{name}_{variant}_texture.png"))
            tiles = []
            glow = Image.alpha_composite(cv.eyes, cv.flare)
            # still and dim in a dark cave; moving, its markings lit; side view moving; lunging
            for pose_name, yaw, pitch, lvl, ll, bg in (("rest", 35, 22, 0.0, 0.55, (24, 27, 30)), ("walk", 35, 22, 1.0, 0.55, (24, 27, 30)),
                                                     ("walk", 90, 8, 1.0, 1.0, (52, 56, 60)), ("flare", -40, 15, 1.0, 1.0, (52, 56, 60))):
                g = glow if lvl > 0 else cv.eyes
                tiles.append(render(parts, cv.img, POSES[pose_name], yaw, pitch, 420, glow=g, glow_level=max(lvl, 0.25),
                                    bg=bg, light_level=ll))
            sheet = Image.new("RGB", (420 * 2, 420 * 2))
            for k, t in enumerate(tiles):
                sheet.paste(t, ((k % 2) * 420, (k // 2) * 420))
            sheet.save(os.path.join(out_dir, f"{name}_{variant}.png"))
            print("preview", name, variant)


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--preview":
        preview(sys.argv[2])
    else:
        print(__doc__)


# =============================================================================================== block and item textures
# The den, the bone scraps round it and the hide, painted like the mod's other 16 x 16 textures (tools/plants.py's
# helpers): short ramps of shades, hard pixels. textures.py merges this table.
from plants import blank, grid, put  # noqa: E402

DEN_MOSS = ["#2F3E1E", "#3B4D24", "#4A5E2B", "#586E33", "#667D3B"]
DEN_BONE = ["#B9AE8C", "#D8CDA8", "#ECE3C4"]
HIDE = ["#24331A", "#2F4220", "#3D5528", "#4C6830", "#5C7B38"]
HIDE_SPOT = "#8DAF3E"
HIDE_EDGE = "#1B2614"


def _den():
    """Trampled moss: flattened streaks swirling round the middle where it lies, with gnawed bone bits in it."""
    rng = random.Random(5150)
    img = blank()
    px = img.load()
    for y in range(16):
        for x in range(16):
            # streaks run round the centre, as if the moss had been pressed flat by a heavy body turning in place
            a = math.atan2(y - 7.5, x - 7.5)
            r = math.hypot(x - 7.5, y - 7.5)
            v = 0.5 + 0.35 * math.sin(a * 3 + r * 0.9) + rng.uniform(-0.25, 0.25)
            if r < 3.0:
                v -= 0.25                                       # the hollow it sleeps in is darker
            put(px, x, y, DEN_MOSS[max(0, min(4, int(v * 5)))])
    # bone bits: a few short slanted sticks and knuckles, each with a lit end
    for x0, y0, dx, dy, n in [(2, 3, 1, 1, 3), (11, 2, 1, -1, 2), (12, 11, -1, 1, 3), (3, 12, 1, 0, 2), (8, 13, 1, -1, 2)]:
        for i in range(n):
            put(px, x0 + dx * i, y0 + dy * i, DEN_BONE[1])
        put(px, x0, y0, DEN_BONE[2])
        put(px, x0 + dx * (n - 1) + (1 if dy else 0), y0 + dy * (n - 1), DEN_BONE[0])
    for x, y in [(6, 5), (10, 8), (5, 9)]:                       # loose chips
        put(px, x, y, DEN_BONE[0])
    return img


def _hide():
    """A folded hide: scaly green, a row of bright spots along it, a ragged dark edge."""
    rng = random.Random(5160)
    img = blank()
    px = img.load()
    rows = [(4, 11), (3, 12), (2, 13), (2, 13), (2, 14), (1, 14), (1, 14), (1, 14), (2, 14), (2, 13), (2, 13), (3, 12), (4, 11)]
    for i, (a, b) in enumerate(rows):
        y = 2 + i
        a += rng.choice([0, 0, 1]); b -= rng.choice([0, 0, 1])
        for x in range(a, b + 1):
            edge = x in (a, b) or i in (0, len(rows) - 1)
            if edge:
                put(px, x, y, HIDE_EDGE)
                continue
            scale = ((x + (y // 2) % 2) % 2 == 0) and y % 2 == 0          # a lattice of lit scale tops
            shade = 2 + (1 if scale else 0) - (1 if x - a < 2 or b - x < 2 else 0) + rng.choice([0, 0, -1])
            put(px, x, y, HIDE[max(0, min(4, shade))])
    for x, y in [(4, 6), (7, 5), (10, 6), (5, 10), (9, 10), (12, 9)]:      # its markings, dull now it is dead
        put(px, x, y, HIDE_SPOT)
    for x in range(5, 11):                                                 # the fold across the middle
        put(px, x, 8, HIDE[1])
    return img


# The bone scraps (gen_data.py's bone_scraps, one texture per look): litter lying on a den gate's landing and the floors
# round it, mostly see-through so the rock shows between the pieces. The bones are the den's own ramp, pale against
# the dark deepslate so they read in a dim cave, with a darker shade for their shadowed sides, cracks and gnawed ends.
SCRAP_BONE = {"k": "#4A4334", "d": "#857A5E", "a": DEN_BONE[0], "b": DEN_BONE[1], "c": DEN_BONE[2]}
# a pale flight feather (w lit vane, v and g its shaded side, e its edge, q the quill), wads of brown (n m f j) and grey
# (s r l) fur from what was caught
SCRAP_FUR = {"w": "#EEEAE0", "v": "#D3CCBE", "g": "#ADA595", "e": "#7A7366", "q": "#BFB49B",
             "n": "#B58F60", "m": "#8C6A45", "f": "#6B4E33", "j": "#4A3524",
             "s": "#B4B0A6", "r": "#8A867D", "l": "#5E5B55"}
# the Mossbacks' own: the hide's ramp and spots, with its pale underside (o p) where a torn edge curls over
SCRAP_HIDE = {"e": HIDE_EDGE, "3": HIDE[2], "4": HIDE[3], "5": HIDE[4], "s": HIDE_SPOT, "n": "#80A83A",
              "o": "#857556", "p": "#A39270"}


def _scraps_ribs():
    """Rib bones fallen flat, still curved as they lay round the body, and a few vertebrae: a short run and a loose one."""
    return grid([
        "................",
        "..cbbba.........",
        "......dbba......",
        "..........ba....",
        ".cbba......ad...",
        ".....dbba...a...",
        ".........ba.ad..",
        "..........a..d..",
        "..........ad....",
        "...........d....",
        "................",
        ".a..a........a..",
        ".cbdcbda....cbd.",
        ".dd.dd.cbda..d..",
        ".......dd.cbd...",
        "..........dd....",
    ], SCRAP_BONE)


def _scraps_skull():
    """A small skull on its side, the back of it broken away (dark inside), cracked across, with chips of bone round it."""
    return grid([
        "................",
        ".............b..",
        "............d...",
        "................",
        "....dccccb......",
        "...kcbbbbbba....",
        "..kdbbdbbkkbaa..",
        "..kbbbbdbkkbbba.",
        "..kdbbbbdbbbbad.",
        "...kabbbbbadada.",
        "....kaaddd.c.c..",
        "................",
        "................",
        ".bd.......c.....",
        "......ad....bd..",
        "................",
    ], SCRAP_BONE)


def _scraps_fur():
    """A flight feather with a split in its vane, a tuft of down, and wads of brown and grey fur."""
    return grid([
        "................",
        "..........w.....",
        ".w.w.....wwv....",
        "..wv....wwqve...",
        ".vqw...wwqvge...",
        "..q...wwqvgve...",
        "......w.qvve....",
        ".....wwqvge.....",
        "....wwqvve......",
        "....wqvve..nn...",
        "....qve..nnmmn..",
        "...q....nmmmfmnn",
        "..q......fmffmj.",
        "......ss..jj.j..",
        ".....srrss......",
        "......lrl.l.....",
    ], SCRAP_FUR)


def _scraps_hide():
    """Shed by the Mossbacks themselves: a torn piece of mossy hide, scaled and spotted, curling over to its pale
    underside along the tear, and loose scales round it."""
    return grid([
        "................",
        "..eeeee.........",
        ".e45454ee.......",
        ".e5s45454e......",
        ".e45454s45e..n5.",
        "..e54545454e.53e",
        "..eop45s454e..e.",
        "...eoppe4545e...",
        "....eee.e.ee....",
        "..........n5....",
        ".n5.......53e...",
        ".53e.......e..n5",
        "..e...........53",
        "......e454e....e",
        ".......eee.n5...",
        "...........53e..",
    ], SCRAP_HIDE)


SKEIN_SHELL = ["#0E1940", "#16285E", "#1D3478", "#244293", "#2E52AD", "#3D66C4"]
SKEIN_RIM = ["#6FB9D9", "#9FDCEE", "#D2F4FB"]
SKEIN_SEAM = "#8FE8FF"
SKEIN_EDGE = "#070C1D"


def _skein_plate():
    """A Skein's shell plate: a broad curved scute, glossy cobalt lit along its crown, a pale glassy rim along its
    edge, the dead seam's line down its middle, its underside's dark lip showing below."""
    rng = random.Random(5170)
    img = blank()
    px = img.load()
    for y in range(3, 13):
        # an arched band, its ends drooping: the plate seen from above and a little behind
        for x in range(1, 15):
            dx = (x - 7.5) / 7.0
            top = 4 + int(round(3.2 * dx * dx))
            bottom = top + 7 - int(round(2.0 * dx * dx))
            if not (top <= y <= bottom): continue
            if y == top or x in (1, 14):
                put(px, x, y, SKEIN_RIM[1 if rng.random() < 0.7 else 2])
            elif y == bottom:
                put(px, x, y, SKEIN_EDGE)
            else:
                crown = 1.0 - abs(dx)
                shade = int(1 + 3.5 * crown - 0.6 * (y - top) / 4 + rng.choice([0, 0, -1, 1]))
                put(px, x, y, SKEIN_SHELL[max(0, min(5, shade))])
    for y in range(5, 10):                                                  # the seam, dull now it is dead
        put(px, 7, y, SKEIN_SEAM if y % 2 else SKEIN_RIM[0])
    put(px, 5, 6, SKEIN_RIM[2])                                             # a glint on the gloss
    put(px, 6, 5, SKEIN_RIM[1])
    return img


PAINTERS = {
    "block/mossback_den": _den,
    "block/bone_scraps": _scraps_ribs,
    "block/bone_scraps_2": _scraps_skull,
    "block/bone_scraps_3": _scraps_fur,
    "block/bone_scraps_4": _scraps_hide,
    "item/mossback_hide": _hide,
    # the Skein's
    "item/skein_plate": _skein_plate,
}
