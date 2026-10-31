"""Floor plants and moss of the cold caves: the Frost Fern Grotto and the frozen Root Vaults' inner halls.

Taran's picture of the grotto: ice vaults hung with icicles over a blue pool, mossy ledges crowded with bushes of
silvery mint fern, pale blue flowers along the terraces, green vines with glowing blue berries. The ferns, icicles and
frost are Immersive Weathering's own (Taran asked for them in place of ours, 0.4.1); the moss is ours, tinted blue,
because vanilla's read as too lush in the cold.

Same rules as tools/plants.py: 16 by 16, vanilla style, drawn with the shared helpers there. A painter registered
with glow=[colours] gets an overlay of those pixels that glows in the dark.
"""
import math, random
from PIL import Image
from plants import hexc, blank, register, put, put_wrap, stamp, bezier, ball
from plants_upper import variants

PAINTERS = {}


# ------------------------------------------------------------------------------------------------ Frost moss
# Vanilla's moss in a cold cast: a cushion of small round tufts, each lit from the top left, packed edge to edge over a
# dark ground, here in slate blue-teal instead of green, with a few grains of frost caught on the tufts. It tiles, and
# the carpet uses it too (as vanilla's moss carpet uses the moss block's texture).
FROST_MOSS = ["#2a4352", "#355363", "#426474", "#537a88", "#67909c", "#80a9b2"]   # deep shade .. lit tuft tops
FROST_GRAINS = ["#9cc2cc", "#cfe6eb"]


def _frost_moss(seed):
    rng = random.Random(seed)
    img = blank()
    px = img.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = hexc(FROST_MOSS[rng.choice((0, 1, 1, 2))])
    # tufts: big ones first, small ones over them, so the small ones read as the tops of the cushion
    for k in range(52):
        r = 1.9 - 1.0 * k / 52 + rng.random() * 0.35
        ball(px, rng.random() * 16, rng.random() * 16, r, FROST_MOSS[k % 2:], wrap=True)
    for _ in range(7):
        x, y = rng.randrange(16), rng.randrange(16)
        put_wrap(px, x, y, FROST_GRAINS[1] if rng.random() < 0.45 else FROST_GRAINS[0])
    return img


register(PAINTERS, "frost_moss", lambda: _frost_moss(7151))


# ------------------------------------------------------------------------------------------------ Frost aster
# Small pale blue stars over dark green leaves (the flowers along the picture's terraces): a mound of narrow leaves,
# stems rising out of it, and on them five-petal stars three pixels across, lit from the top left, with a yellow-white
# heart.
ASTER_LEAF = ["#3F5E3A", "#557A4A", "#6E9460"]                     # shade, leaf, lit edge
ASTER = {"L": "#CFE4F7", "M": "#9CC3EC", "D": "#6E9FD6", "c": "#F3EBB0"}
STAR = [".L.",
        "LcM",
        "D.D"]


def _leaf(px, x0, y0, angle, length, width=0.9):
    """A narrow leaf from the point (x0, y0) at angle (degrees from upright, positive to the right): widest in its
    middle, its upper side catching the light."""
    a = math.radians(angle)
    ux, uy = math.sin(a), -math.cos(a)
    for y in range(int(y0 - length - 2), int(y0 + 3)):
        for x in range(int(x0 - length - 2), int(x0 + length + 3)):
            dx, dy = x + 0.5 - x0, y + 0.5 - y0
            t, d = dx * ux + dy * uy, dx * -uy + dy * ux
            if not 0 <= t <= length or abs(d) > width * math.sin(math.pi * min(1.0, (t + 0.6) / (length + 0.6))) + 0.35:
                continue
            up = d * ux < 0
            put(px, x, y, ASTER_LEAF[2] if up and t > length * 0.35 else ASTER_LEAF[0] if not up and abs(d) > 0.3 else
                ASTER_LEAF[1])


# Leaves as (angle, length, root x, root y), stars as their middle pixels.
FROST_ASTERS = {
    # a cushion of leaves with the stars on it
    1: {"leaves": [(-82, 7, 7.5, 15.0), (78, 7, 8.5, 15.0), (-55, 7, 7.5, 15.0), (52, 8, 8.5, 15.0), (-28, 8, 7.5, 15.0),
                   (24, 7, 8.5, 15.0), (0, 7, 8.0, 15.0)],
        "stars": [(4, 4), (8, 2), (12, 4), (6, 7), (10, 8), (3, 10), (13, 10)]},
    # taller: a smaller mound, the stars higher up in two sprays
    2: {"leaves": [(-78, 6, 7.5, 15.0), (74, 6, 8.5, 15.0), (-46, 6, 7.5, 15.0), (42, 6, 8.5, 15.0), (-14, 5, 7.5, 15.0),
                   (16, 5, 8.5, 15.0)],
        "stars": [(3, 3), (6, 1), (2, 7), (7, 5), (10, 3), (13, 2), (12, 7), (5, 10)]},
}


def _frost_aster(v):
    img = blank()
    px = img.load()
    spec = FROST_ASTERS[v]
    for cx, cy in spec["stars"]:  # a stem from the root to each star
        foot = 7.0 if cx < 8 else 8.0
        for i, (x, y) in enumerate(bezier((foot + 0.5, 15.5), ((foot + cx + 1) / 2, (cy + 15) / 2 + 2.5), (cx + 0.5, cy + 1.5),
                                          steps=40)):
            put(px, x, y, ASTER_LEAF[0] if i % 4 else ASTER_LEAF[1])
    for angle, length, x0, y0 in spec["leaves"]:
        _leaf(px, x0, y0, angle, length)
    for cx, cy in spec["stars"]:
        stamp(px, cx - 1, cy - 1, STAR, ASTER)
    return img


register(PAINTERS, "frost_aster", lambda: _frost_aster(1))
register(PAINTERS, "frost_aster_2", lambda: _frost_aster(2))


# ------------------------------------------------------------------------------------------------ Snowbell
# Drooping bells like snowdrops: each on a thin grey-green stalk that rises and bends over like a crook, the bell
# hanging from its end, with narrow strap leaves at the foot. The bells glow a saturated ice blue (paler blues clip to
# white under a shader) inside a darker outline that does not glow.
SNOW_STEM = ["#4A644B", "#5D7A5E", "#78977A"]
SNOWBELL = {"g": "#78977A", "o": "#3E5E8C", "b": "#5C9BE0", "B": "#78B2EE"}
SNOWBELL_GLOW = ["#5C9BE0", "#78B2EE"]
BELL = [".g.",
        "oBo",
        "BBb",
        "Bbb",
        "o.o"]
# Leaves as (foot column, top row, lean); flowers as (foot column, top of the crook, the pixel the bell hangs from).
SNOWBELLS = {
    1: {"leaves": [(6, 7, -2), (9, 9, 2), (7, 11, 0)],
        "flowers": [(6, (5, 2), (3, 3)), (8, (9, 4), (11, 5)), (7, (7, 9), (5, 9))]},
    2: {"leaves": [(5, 8, -2), (10, 7, 2), (8, 10, -1)],
        "flowers": [(7, (7, 1), (10, 2)), (6, (5, 5), (2, 6)), (9, (10, 8), (12, 9)), (8, (8, 11), (6, 11))]},
}


def _crook(foot, top, hang):
    """The pixels of a stalk from (foot, 15) up to the top of its crook and over to the pixel it hangs from, one pixel
    thick."""
    p0, p3 = (foot + 0.5, 15.5), (hang[0] + 0.5, hang[1] + 0.5)
    p1, p2 = (foot + 0.5, top[1] + 2), (top[0] + 0.5 + (hang[0] - foot) * 0.2, top[1] - 1.2)
    pts = []
    for i in range(201):
        t = i / 200
        w = ((1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3)
        p = (int(math.floor(sum(wk * q[0] for wk, q in zip(w, (p0, p1, p2, p3))))),
             int(math.floor(sum(wk * q[1] for wk, q in zip(w, (p0, p1, p2, p3))))))
        if not pts or pts[-1] != p:
            pts.append(p)
    i = 1
    while i < len(pts) - 1:  # drop the corner pixel of each L-shaped step
        (xa, ya), (xb, yb), (xc, yc) = pts[i - 1], pts[i], pts[i + 1]
        if abs(xc - xa) == 1 and abs(yc - ya) == 1 and (xb == xa or yb == ya):
            pts.pop(i)
        else:
            i += 1
    return pts


def _snowbell(v):
    img = blank()
    px = img.load()
    spec = SNOWBELLS[v]
    for foot, top, lean in spec["leaves"]:
        h = 15 - top
        for i in range(h + 1):
            t = i / h
            put(px, foot + int(round(lean * t * t)), 15 - i, SNOW_STEM[2] if t > 0.75 else SNOW_STEM[1] if t > 0.3 else SNOW_STEM[0])
    for foot, top, hang in spec["flowers"]:
        pts = _crook(foot, top, hang)
        for i, (x, y) in enumerate(pts):
            put(px, x, y, SNOW_STEM[1] if i < len(pts) - 3 else SNOW_STEM[2])
        stamp(px, hang[0] - 1, hang[1] + 1, BELL, SNOWBELL)
    return img


register(PAINTERS, "snowbell", lambda: _snowbell(1), glow=SNOWBELL_GLOW)
register(PAINTERS, "snowbell_2", lambda: _snowbell(2), glow=SNOWBELL_GLOW)
