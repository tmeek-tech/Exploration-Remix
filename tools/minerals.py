"""Textures of the lifeless Basalt Galleries (0.5): the ember crust, glowing rock that lights their halls, and the cinder
chain that their climbs hang in place of vines. (0.20.1's glow ice of the deep Frost Fern Grotto left the game in 0.20.7.)

Same rules as tools/plants.py: 16 by 16, vanilla style (a short ramp of shades, hard pixels), drawn with its helpers,
the colours those of the basalt pictures (plants_deep.CHAR and EMBER). Nothing here is alive. textures.py merges this
file after the plant files.

Since 0.22.9 the ember crust is basalt (Taran, 2026-11-25: "re-add ember crust to light up the lower Galleries but give
it a texture that blends in better with the basalt"): gen_data draws the block as vanilla's basalt, or smooth basalt for
the smooth ember crust, with these textures over it as a cutout overlay. Since 0.24.1 each face is crazed with fine
cracks (Taran's cards: "Crazed crust", "Lava orange"): dark lips of the basalt's own darkest shades, lit by the world like
the rock, and in two of them a stretch still glowing, which the glow overlay draws again at full brightness. Most of
every face is the vanilla rock, so the crust is that rock up close, crazed.
"""
import math, random
from plants import blank, register, put, put_wrap
from plants_deep import CHAR, EMBER

PAINTERS = {}


# Ember crust: basalt crazed with fine cracks (0.24.1, Taran's card "Pick how the new ember crust looks on the basalt":
# "C Crazed crust", the cards' picture "a few cracks lit"; 0.22.9 drew one long fissure down each side). Four short
# cracks to a face, five to nine pixels long; two of them still glow in a stretch along their middle and have cooled dark
# towards their ends, the other two have cooled dark all along. On a side the two glowing ones run roughly up and down,
# as the joint the crust lies in. Three sides and three tops, which the game picks by position and turns or mirrors
# (gen_data option turns "overlay"), so no wall or floor of crust repeats. The glow is lava orange, magma's own ramp
# (card "Pick the colour of the ember crust's glowing cracks": "Lava orange"); the lips are vanilla basalt's own darkest
# shades.
EMBER_LIPS = ["#1B2632", "#26252E"]
EMBER_CRACK = ["#9C3A0E", "#CA4E06", "#E66410", "#F48522", "#F89E44"]   # dim to hottest


def _crack_path(points):
    """The pixels of a crack through these points, 8-connected, in order."""
    out = []
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for i in range(n):
            q = (round(x0 + (x1 - x0) * i / n), round(y0 + (y1 - y0) * i / n))
            if not out or out[-1] != q:
                out.append(q)
    if not out or out[-1] != points[-1]:
        out.append(points[-1])
    return out


def _wander(rng, a, b, steps, jitter):
    """Control points from a to b, each knocked up to jitter pixels off the straight line."""
    pts = [a]
    for i in range(1, steps):
        t = i / steps
        pts.append((round(a[0] + (b[0] - a[0]) * t + rng.uniform(-jitter, jitter)),
                     round(a[1] + (b[1] - a[1]) * t + rng.uniform(-jitter, jitter))))
    return pts + [b]


def _heat_bumps(rng, k):
    """Heat along a crack (0..1 of its length): k bumps, so stretches glow and stretches between have cooled."""
    bumps = [(rng.random(), 0.08 + 0.12 * rng.random(), 0.7 + 0.45 * rng.random()) for _ in range(k)]
    return lambda t: max(a * math.exp(-((t - c) / w) ** 2) for c, w, a in bumps)


def _draw_crack(marks, rng, pix, heat, opens=0.82):
    """Marks a crack's pixels: a lip where it has cooled, else a glow shade by its heat; where hottest it opens a pixel
    wider, dimmer. marks: (x, y) -> ("lip", k) or ("glow", k); a hotter mark wins."""
    n = len(pix)
    for i, (x, y) in enumerate(pix):
        if not (0 <= x < 16 and 0 <= y < 16):
            continue
        h = heat(i / max(1, n - 1))
        mark = ("lip", 0) if h < 0.2 else ("glow", 0 if h < 0.4 else 1 if h < 0.6 else 2 if h < 0.82 else 3)
        cur = marks.get((x, y))
        if cur is None or cur[0] == "lip" or (mark[0] == "glow" and mark[1] > cur[1]):
            marks[(x, y)] = mark
        if h > opens:
            (ax, ay), (bx, by) = pix[max(i - 1, 0)], pix[min(i + 1, n - 1)]
            q = (x + rng.choice((-1, 1)), y) if abs(bx - ax) <= abs(by - ay) else (x, y + rng.choice((-1, 1)))
            if 0 <= q[0] < 16 and 0 <= q[1] < 16 and marks.get(q, ("lip", 0))[0] == "lip":
                marks[q] = ("glow", 1)


def _lips(marks, rng, share):
    """Dark lips beside the glowing pixels, across the crack's run (right and left on a side's upright crack)."""
    for (x, y), (kind, _) in list(marks.items()):
        if kind != "glow":
            continue
        for q in ((x + 1, y), (x - 1, y)):
            if 0 <= q[0] < 16 and 0 <= q[1] < 16 and q not in marks and rng.random() < share:
                marks[q] = ("lip", 1)


def _crack_image(marks):
    img = blank()
    px = img.load()
    for (x, y), (kind, k) in marks.items():
        put(px, x, y, EMBER_LIPS[k] if kind == "lip" else EMBER_CRACK[k])
    return img


def _crazed(seed, side):
    """A face crazed with fine cracks: four short ones, two of them glowing in a stretch, the others cooled dark; on a
    side (side True) the glowing two run roughly up and down. The same draws as the cards' picture, so the same pixels."""
    rng = random.Random(seed)
    marks = {}
    for k in range(4):
        a = (rng.randint(1, 14), rng.randint(1, 14))
        ang = (math.pi / 2 + rng.uniform(-0.5, 0.5)) if side and k < 2 else rng.uniform(0, math.pi)
        length = rng.randint(5, 9)
        b = (round(a[0] + length * math.cos(ang)), round(a[1] + length * math.sin(ang)))
        pix = _crack_path(_wander(rng, a, b, 2, 1.2))
        _draw_crack(marks, rng, pix, _heat_bumps(rng, 1) if k < 2 else (lambda t: 0.0), opens=0.9)
    _lips(marks, rng, 0.2)
    return _crack_image(marks)


EMBER_SIDES = {"ember_crust_side": 301, "ember_crust_2_side": 302, "ember_crust_3_side": 303}
EMBER_TOPS = {"ember_crust_top": 311, "ember_crust_2_top": 312, "ember_crust_3_top": 313}
for _name, _seed in EMBER_SIDES.items():
    register(PAINTERS, _name, lambda s=_seed: _crazed(s, True), glow=EMBER_CRACK)
for _name, _seed in EMBER_TOPS.items():
    register(PAINTERS, _name, lambda s=_seed: _crazed(s, False), glow=EMBER_CRACK)


# Cinder chain: knobbly beads of dark cinder fused end to end, drawn upright in the middle of the tile (a cross model, so
# it reads round from every side); four beads to a tile, so chains stack seamlessly. Embers caught where beads join
# glint (a glow overlay), giving no light.
BEAD = [".abbc.",
        "abccdd",
        "bccdde",
        ".cdde."]
# column of each bead's left edge (the tile repeats every four beads, so the strand never breaks between blocks)
BEAD_X = [5, 5, 4, 5]
BEAD_COLOURS = {"a": "#8E7F7B", "b": CHAR[3], "c": CHAR[2], "d": CHAR[1], "e": CHAR[0]}
# (bead, side): an ember in the neck under that bead, on its left (-1) or right (1)
CHAIN_EMBERS = [(0, 1), (2, -1)]


def _cinder_chain():
    img = blank()
    px = img.load()
    for k, bx in enumerate(BEAD_X):
        y0 = 4 * k
        for dy, row in enumerate(BEAD):
            for dx, ch in enumerate(row):
                if ch != ".":
                    put(px, bx + dx, y0 + dy, BEAD_COLOURS[ch])
    for k, side in CHAIN_EMBERS:
        y = 4 * k + 3
        x = BEAD_X[k] + (0 if side < 0 else 5)
        put_wrap(px, x, y, EMBER[3])
        put_wrap(px, x, y + 1, EMBER[2])
    return img


register(PAINTERS, "cinder_chain", _cinder_chain, glow=[EMBER[2], EMBER[3]])
