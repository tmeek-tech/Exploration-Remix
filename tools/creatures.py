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


# =============================================================================================== output
def geometry_json(parts):
    return {"texture_width": TEX_W, "texture_height": TEX_H, "parts": [
        {"name": p["name"], "parent": p["parent"], "pivot": p["pivot"], "rotation": p["rot"],
         "boxes": [{"origin": b["origin"], "size": b["size"], "uv": b["uv"], "inflate": b["inflate"]} for b in p["boxes"]]}
        for p in parts]}


SPAWN_EGGS = {"mossback": (0x2e3e20, 0xc4ec50)}
NAMES = {"mossback": "Mossback"}
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


def preview(out_dir):
    os.makedirs(out_dir, exist_ok=True)
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
# The den and the hide, painted like the mod's other 16 x 16 textures (tools/plants.py's helpers): short ramps of
# shades, hard pixels. textures.py merges this table.
from plants import blank, put  # noqa: E402

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


PAINTERS = {
    "block/mossback_den": _den,
    "item/mossback_hide": _hide,
}
