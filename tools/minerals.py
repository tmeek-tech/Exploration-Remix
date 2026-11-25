"""Textures of the lifeless Basalt Galleries (0.5): the ember crust, glowing rock that lights their halls, and the cinder
chain that their climbs hang in place of vines. (0.20.1's glow ice of the deep Frost Fern Grotto left the game in 0.20.7.)

Same rules as tools/plants.py: 16 by 16, vanilla style (a short ramp of shades, hard pixels), drawn with its helpers,
the colours those of the basalt pictures (plants_deep.CHAR and EMBER). Nothing here is alive. textures.py merges this
file after the plant files.

Since 0.22.9 the ember crust is basalt (Taran, 2026-11-25: "re-add ember crust to light up the lower Galleries but give
it a texture that blends in better with the basalt"): gen_data draws the block as vanilla's basalt, or smooth basalt for
the smooth ember crust, with these textures over it as a cutout overlay. Each is a crack: dark lips of the basalt's own
darkest shades, lit by the world like the rock, and inside them stretches still glowing, which the glow overlay draws
again at full brightness. Most of every face is the vanilla rock, so the crust is that rock up close, with a crack in it.
"""
import math, random
from plants import blank, register, put, put_wrap
from plants_deep import CHAR, EMBER

PAINTERS = {}


# Ember crust (0.22.9): a crack in the basalt, still glowing in stretches. On the sides one crack runs down the block,
# entering at the top and leaving at the bottom in column 8, so a streak of crust blocks (a joint between two columns)
# reads as one long crack; between, it wanders up to three pixels off and a short branch cools away from it. On the top
# (and the bottom) cooling cracks cross the column's end, glowing in the middle, dark where they reach its edges. Three
# of each, which the game picks by position and turns or mirrors (gen_data option turns), so no wall or floor of crust
# repeats. The glow is deep red, dimmer than the lava's orange (card "Pick the colour of the ember crust's glowing
# cracks", option 0). The lips are vanilla basalt's own darkest shades.
EMBER_LIPS = ["#1B2632", "#26252E"]
EMBER_CRACK = ["#5E1610", "#86221A", "#AE321F", "#D04A2A", "#E8683A"]   # dim to hottest


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
    """Dark lips beside the glowing pixels, across the crack's run (left and right on a side's upright crack)."""
    for (x, y), (kind, _) in list(marks.items()):
        if kind != "glow":
            continue
        for q in ((x - 1, y), (x + 1, y)):
            if 0 <= q[0] < 16 and 0 <= q[1] < 16 and q not in marks and rng.random() < share:
                marks[q] = ("lip", 1)


def _crack_image(marks):
    img = blank()
    px = img.load()
    for (x, y), (kind, k) in marks.items():
        put(px, x, y, EMBER_LIPS[k] if kind == "lip" else EMBER_CRACK[k])
    return img


def _ember_side(seed):
    rng = random.Random(seed)
    marks = {}
    xs = [8] + [8 + rng.randint(-3, 3) for _ in range(3)] + [8]
    pix = _crack_path([(xs[0], 0), (xs[1], 4), (xs[2], 8), (xs[3], 12), (xs[4], 15)])
    bumps = _heat_bumps(rng, 2)
    # its ends glow a little, so a streak of crust reads as one crack from block to block
    _draw_crack(marks, rng, pix, lambda t: max(bumps(t), 0.5 if t < 0.06 or t > 0.94 else 0))
    # a branch off it, cooling away from it
    bx, by = pix[rng.randrange(4, len(pix) - 4)]
    d = rng.choice((-1, 1))
    branch = _crack_path([(bx, by), (bx + 2 * d, by + rng.choice((-2, 2))), (bx + 4 * d, by + rng.choice((-3, -1, 1, 3)))])
    _draw_crack(marks, rng, branch, lambda t: 0.75 * (1 - t) if t > 0.05 else 0.0)
    _lips(marks, rng, 0.35)
    return _crack_image(marks)


def _ember_top(seed, arms):
    """Cooling cracks across a column's end: arms cracks from a point near its middle out to its edges."""
    rng = random.Random(seed)
    marks = {}
    c = (8 + rng.randint(-2, 2), 8 + rng.randint(-2, 2))
    ends = [(rng.randint(4, 11), -1), (16, rng.randint(4, 11)), (rng.randint(4, 11), 16), (-1, rng.randint(4, 11))]
    for e in rng.sample(ends, arms):
        pix = _crack_path(_wander(rng, c, e, 3, 1.6))
        a = 0.8 + 0.3 * rng.random()
        _draw_crack(marks, rng, pix, lambda t, a=a: a * (1 - t) ** 0.6 if t < 0.8 else 0.1)
    _lips(marks, rng, 0.25)
    return _crack_image(marks)


EMBER_SIDES = {"ember_crust_side": 101, "ember_crust_2_side": 102, "ember_crust_3_side": 103}
EMBER_TOPS = {"ember_crust_top": (111, 3), "ember_crust_2_top": (112, 2), "ember_crust_3_top": (113, 3)}
for _name, _seed in EMBER_SIDES.items():
    register(PAINTERS, _name, lambda s=_seed: _ember_side(s), glow=EMBER_CRACK)
for _name, (_seed, _arms) in EMBER_TOPS.items():
    register(PAINTERS, _name, lambda s=_seed, a=_arms: _ember_top(s, a), glow=EMBER_CRACK)


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
