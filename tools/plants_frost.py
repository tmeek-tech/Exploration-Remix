"""Floor plants of the Frost Fern Grotto, a cold cave that is lush and icy together (vegetation rework).

Taran's picture of the grotto: ice vaults hung with icicles over a blue pool, mossy ledges crowded with bushes of
silvery mint fern, pale blue flowers along the terraces, green vines with glowing blue berries.

Same rules as tools/plants.py: 16 by 16, vanilla style, colours from the picture, drawn with the shared helpers there.
A painter registered with glow=[colours] gets an overlay of those pixels that glows in the dark. The tall frost fern is
painted on one 16 by 32 canvas and cut in two, so its halves join.
"""
import math, random
from PIL import Image
from plants import hexc, blank, register, put, stamp, bezier
from plants_upper import variants

PAINTERS = {}


# ------------------------------------------------------------------------------------------------ Frost fern
# The fern bushes of the picture's ledges: domes of fine fronds, silvery mint, frosted white at the tips. Each frond is a
# fishbone as in the picture, short leaflets on both sides of its stalk, two pixels long until near its tip, then one.
# The fronds behind are drawn two shades darker, so the pale ones in front stand out against them (the picture's
# shades #6E8C7E, #9DB8AA, #C6DACF and #E6F0EA, with a deeper one for the fronds behind). The third look is tinted pale
# cyan, like the ferns on the far terraces.
FROST = ["#506A5E", "#6E8C7E", "#9DB8AA", "#C6DACF", "#E6F0EA"]        # deep, dark, mid, light, frosted tip
FROST_CYAN = ["#47656B", "#62888E", "#8FB8BC", "#B5D6D8", "#DDEFF0"]
FROST_STEM = ["#3F5A48", "#557360"]                                   # the bare stalks near the root


def _path(base, bend, tip, steps=400):
    """Points along a frond from its base to its tip: a quadratic curve through one bend, or a cubic one when bend is
    a pair of points (a frond that rises and then arches over)."""
    pts = []
    for i in range(steps + 1):
        t = i / steps
        if isinstance(bend[0], tuple):
            (ax, ay), (bx, by) = bend
            w = ((1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3)
            pts.append((w[0] * base[0] + w[1] * ax + w[2] * bx + w[3] * tip[0],
                        w[0] * base[1] + w[1] * ay + w[2] * by + w[3] * tip[1]))
        else:
            w = ((1 - t) ** 2, 2 * (1 - t) * t, t * t)
            pts.append((w[0] * base[0] + w[1] * bend[0] + w[2] * tip[0], w[0] * base[1] + w[1] * bend[1] + w[2] * tip[1]))
    return pts


def _frond(cput, base, bend, tip, depth, phase, ramp, gap=2.0, pale=5.0, mid=9.0, short=5.0, start=0.2, slant=0.35):
    """One frond as a fishbone: a bare stalk for the first `start` of its length, then a pair of leaflets every `gap`
    pixels, slanting a little towards the tip, two pixels long until the last `short` pixels. The frond is pale and
    frosted over its last `pale` pixels, mid over the `mid` before, dark inside the bush; the end of each leaflet is a
    shade lighter than its root. phase shifts the leaflets along the stalk, so that those of fronds side by side do not
    line up; depth darkens a frond further back by that many shades."""
    pts = _path(base, bend, tip)
    run = [0.0]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        run.append(run[-1] + math.hypot(x1 - x0, y1 - y0))
    total = run[-1]

    def shade(k):
        return ramp[max(0, min(len(ramp) - 1, k - depth))]
    last = None
    for (x, y), r in zip(pts, run):
        p = (int(math.floor(x)), int(math.floor(y)))
        if p != last:
            u = r / total
            cput(p[0], p[1], FROST_STEM[0] if u < start / 2 else FROST_STEM[1] if u < start else
                 shade(1) if total - r > pale else shade(2))
            last = p
    s, i = start * total + phase, 0
    while s < total - 0.6:
        while run[i] < s:
            i += 1
        (ax, ay), (bx, by) = pts[max(0, i - 3)], pts[min(len(pts) - 1, i + 3)]
        tl = math.hypot(bx - ax, by - ay) or 1.0
        tx, ty = (bx - ax) / tl, (by - ay) / tl
        left = total - s
        level = 3 if left < pale else 2 if left < mid else 1
        n = 2 if left > short else 1
        x0, y0 = int(math.floor(pts[i][0])), int(math.floor(pts[i][1]))
        for side in (-1, 1):
            dx, dy = -ty * side + slant * tx, tx * side + slant * ty
            dl = math.hypot(dx, dy)
            for k in range(1, n + 1):
                cput(x0 + int(round(dx / dl * k)), y0 + int(round(dy / dl * k)), shade(level + (1 if k == n else 0)))
        s += gap
    cput(last[0], last[1], shade(4))


def _fern(fronds, ramp=FROST, height=16, **kw):
    """Fronds (base, bend, tip, depth, phase) on a 16 by height canvas, the ones furthest back first."""
    canvas = Image.new("RGBA", (16, height), (0, 0, 0, 0))
    cpx = canvas.load()

    def cput(x, y, c):
        if 0 <= x < 16 and 0 <= y < height:
            cpx[x, y] = hexc(c)
    for base, bend, tip, depth, phase in sorted(fronds, key=lambda f: -f[3]):
        _frond(cput, base, bend, tip, depth, phase, ramp, **kw)
    return canvas


def _clump(front, back, rx=7.6, ry=15.0, bow=0.25, back_reach=0.8):
    """Fronds fanning out of one root at the foot of the tile, as (angle from upright in degrees, share of the full
    reach, phase): a pale one in front at each angle in front, a darker, shorter one behind at each angle in back. The
    fronds bow outwards a little, so the clump is a dome."""
    fronds = []
    for group, depth, scale in ((back, 2, back_reach), (front, 0, 1.0)):
        for angle, reach, phase in group:
            a = math.radians(angle)
            tip = (8.0 + rx * math.sin(a) * reach * scale, 15.5 - ry * math.cos(a) * reach * scale)
            bend = ((8.0 + tip[0]) / 2 + bow * 0.6 * (tip[0] - 8.0), (15.5 + tip[1]) / 2 - bow * abs(tip[0] - 8.0))
            fronds.append(((7.5 if angle < 0 else 8.5, 15.5), bend, tip, depth, phase))
    return fronds


FROST_FERNS = {
    # a wide clump, six fronds in front
    1: _clump(front=[(-84, 0.95, 0.0), (-50, 1.0, 1.0), (-16, 0.95, 0.5), (20, 1.0, 0.0), (55, 1.0, 1.0), (86, 0.9, 0.5)],
              back=[(-67, 1.0, 1.0), (-33, 1.0, 0.0), (2, 1.0, 1.0), (38, 1.0, 0.0), (71, 1.0, 0.5)]),
    # a rounder one, five fronds in front, one upright
    2: _clump(front=[(-76, 0.95, 0.0), (-38, 1.0, 1.0), (0, 1.0, 0.5), (38, 1.0, 0.0), (76, 0.95, 1.0)],
              back=[(-57, 1.0, 0.5), (-19, 1.0, 0.0), (19, 1.0, 1.0), (57, 1.0, 0.5), (88, 0.8, 0.0)]),
    # leaning a little to the left
    3: _clump(front=[(-80, 1.0, 0.5), (-46, 0.92, 0.0), (-10, 1.0, 1.0), (26, 0.95, 0.5), (60, 1.0, 0.0), (88, 0.85, 1.0)],
              back=[(-63, 1.0, 0.0), (-28, 1.0, 1.0), (8, 1.0, 0.0), (44, 1.0, 1.0), (75, 1.0, 0.0)]),
}

register(PAINTERS, "frost_fern", lambda: _fern(FROST_FERNS[1]))
register(PAINTERS, "frost_fern_2", lambda: _fern(FROST_FERNS[2]))
register(PAINTERS, "frost_fern_3", lambda: _fern(FROST_FERNS[3], FROST_CYAN))


# Tall frost fern: a two-block bush of big fronds, two upright ones behind, the ones in front rising and arching over,
# shorter ones arching out low down; on one 16 by 32 canvas cut in two. The fronds are long, so more of each is pale.
TALL_FROST = {
    1: [((7.5, 31.5), (6, 12), (5.5, 1), 2, 0.0), ((8.5, 31.5), (10, 12), (10.5, 2), 2, 1.0),
        ((7.5, 31.5), ((6, 8), (1, 0)), (0.5, 11), 0, 0.0), ((8.5, 31.5), ((10, 8), (15, 1)), (15.5, 12), 0, 1.0),
        ((7.5, 31.5), ((6, 18), (1, 12)), (0.5, 19), 1, 0.5), ((8.5, 31.5), ((10, 19), (15, 13)), (15.5, 21), 1, 0.0),
        ((8, 31.5), (8, 16), (8, 5), 0, 0.5),
        ((7.5, 31.5), ((5, 26), (1, 21)), (0.5, 27), 0, 1.0), ((8.5, 31.5), ((11, 27), (15, 22)), (15.5, 28), 0, 0.5)],
    2: [((7.5, 31.5), (7, 12), (7.5, 0.5), 2, 0.0), ((8.5, 31.5), (11, 14), (13, 4), 2, 1.0), ((7.5, 31.5), (3, 14), (2, 5), 2, 0.5),
        ((7.5, 31.5), ((7, 6), (1, -1)), (0.5, 10), 0, 0.0), ((8.5, 31.5), ((9, 9), (15, 3)), (15.5, 14), 0, 1.0),
        ((8.5, 31.5), ((9, 16), (13, 8)), (12.5, 4), 0, 0.5),
        ((7.5, 31.5), ((6, 19), (0, 15)), (0.5, 22), 1, 0.5), ((8.5, 31.5), ((10, 21), (15, 17)), (15.5, 24), 0, 0.0),
        ((7.5, 31.5), ((5, 27), (1, 24)), (0.5, 29), 0, 1.0), ((8.5, 31.5), ((11, 28), (15, 26)), (15.5, 30), 1, 0.5)],
}
TALL_SHADING = dict(pale=8.0, mid=13.0, gap=2.4)
for _k in (1, 2):
    register(PAINTERS, variants("tall_frost_fern_top", 2)[_k - 1],
             (lambda k=_k: _fern(TALL_FROST[k], height=32, **TALL_SHADING).crop((0, 0, 16, 16))))
    register(PAINTERS, variants("tall_frost_fern_bottom", 2)[_k - 1],
             (lambda k=_k: _fern(TALL_FROST[k], height=32, **TALL_SHADING).crop((0, 16, 16, 32))))


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
