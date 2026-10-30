"""Hanging plants, sheets and icicles of the Frost Fern Grotto and of frozen cave mouths (vegetation rework, part C).

Same rules as tools/plants.py: 16 by 16, vanilla style, colours from Taran's picture of the grotto (vaults of pale ice
hung with icicles, green vines carrying glowing blue berries, frost ferns and blue mushrooms on mossy terraces), drawn
with the shared helpers there. A painter registered with glow=[colours] gets an overlay of those pixels that glows in
the dark.

Vines and icicles keep their strands (or their column of ice) on the same columns in every variant: sway() repeats
every 16 rows and the icicle's edges sit on the same columns at rows 0 and 15, so any body tile stacks on any other and
the tip continues the body from its row 0. Leaves and berries that span two or more rows stay off rows 0, 1, 14 and 15
of a body tile. The sheets (rime lichen, hoarfrost) are drawn as seen flat on a face and tile with copies of themselves.
"""
import random
from plants import hexc, blank, register, put, put_wrap, stamp, sway, ball, speckle_sheet
from plants_upper import variants, leaf, LEAF, LEAF_ROW, LEAF_DROOP

PAINTERS = {}


# ------------------------------------------------------------------------------------------------ Frost Fern Grotto
# Frostberry vine: the vines hanging from the grotto's roof, olive strands in a curtain of bright green leaves, with
# round berries glowing blue along them and in a bunch at their ends. The berries are lit from inside on a saturated
# blue (under the shader they shine white at the heart and blue at the edge, as in the picture); their lower corners
# are a dark blue that does not glow, which rounds them and keeps berries side by side apart.
FROST_LEAVES = [("#3E6B34", "#557F3C", "#73984A"), ("#3E6B34", "#73984A", "#8FB25A"), ("#557F3C", "#73984A", "#8FB25A")]
FROST_STEM = ["#4A4A1C", "#5E5B24"]
BERRY = {"r": "#1D3F86", "d": "#2F6FE0", "m": "#4F92F2", "h": "#86BBF8"}
BERRY_GLOW = ["#2F6FE0", "#4F92F2", "#86BBF8"]
BERRIES = {"big": ["dmd",
                   "mhm",
                   "rdr"],
           "small": ["hm",
                     "dr"]}
# Three strands; their phases put each strand on the same column at rows 15 and 0, so the joins run straight down.
FROST_STRANDS = [(3, 1.0, 1.5), (8, 1.0, 4.5), (12, 1.0, 1.2)]
# Berries of the body tiles as (strand, top row, size, side): each hangs beside its strand on that side, touching it,
# on rows 2 to 13 only. The third look is leafier and carries fewer berries.
FROST_BODY = {
    1: [(0, 2, "big", -1), (1, 6, "big", -1), (2, 3, "small", 1), (2, 10, "big", 1), (0, 11, "small", 1)],
    2: [(1, 2, "small", 1), (0, 5, "big", 1), (2, 7, "big", 1), (1, 11, "small", -1), (0, 11, "small", -1)],
    3: [(1, 4, "big", -1), (0, 10, "small", -1), (2, 11, "small", 1)],
}
# Tips: where each strand ends, the strand that ends in a bunch and the sides of its two upper berries (big, small),
# and the berry hanging from the end of each other strand.
FROST_TIP = {
    1: {"ends": [9, 13, 7], "bunch": 1, "sides": (-1, 1), "others": [(0, "big"), (2, "small")]},
    2: {"ends": [12, 8, 10], "bunch": 0, "sides": (1, -1), "others": [(1, "small"), (2, "big")]},
}


def _berry(px, x, y, size):
    stamp(px, x, y, BERRIES[size], BERRY)


def _berry_beside(px, xs, y, size, side):
    """A berry beside a strand (its columns xs), touching it on side -1 (left) or 1 (right) across its rows."""
    h = len(BERRIES[size])
    rows = xs[y:y + h]
    _berry(px, max(rows) + 1 if side > 0 else min(rows) - len(BERRIES[size][0]), y, size)


def _frostberry(v, tip):
    rng = random.Random(9600 + v * 11 + (300 if tip else 0))
    img = blank()
    px = img.load()
    t = FROST_TIP[v] if tip else None
    ends = t["ends"] if tip else [16, 16, 16]
    paths = [sway(*s) for s in FROST_STRANDS]
    leafy = {1: 0.8, 2: 0.8, 3: 0.95}[v]
    # leaves first, then the strands over them, so the strands stay whole where tiles join
    for i, xs in enumerate(paths):
        for y in range((i + v) % 2, ends[i] - 1, 2):
            if rng.random() > leafy * (0.5 if tip and y > ends[i] - 4 else 1):
                continue
            side = 1 if (y // 2 + i) % 2 else -1
            ramp = FROST_LEAVES[rng.randrange(3)]
            if 2 <= y <= 11 and rng.random() < 0.4:
                shape = LEAF_DROOP
            elif 2 <= y <= 12:
                shape = LEAF
            else:
                shape = LEAF_ROW
            leaf(px, xs[y], y, side, shape, ramp)
    for i, xs in enumerate(paths):
        for y in range(ends[i]):
            put(px, xs[y], y, FROST_STEM[1] if (y + i) % 3 else FROST_STEM[0])
    if tip:  # the bunch: a big and a small berry either side of the strand's last rows, and a big one at its end
        b = t["bunch"]
        e = ends[b] - 1
        _berry_beside(px, paths[b], e - 2, "big", t["sides"][0])
        _berry_beside(px, paths[b], e - 2, "small", t["sides"][1])
        _berry(px, paths[b][e] - 1, e + 1, "big")
        for i, size in t["others"]:
            e = ends[i] - 1
            _berry(px, paths[i][e] - (1 if size == "big" else 0), e + 1, size)
    else:
        for i, y, size, side in FROST_BODY[v]:
            _berry_beside(px, paths[i], y, size, side)
    return img


for _k in (1, 2, 3):
    register(PAINTERS, variants("frostberry_vine_plant", 3)[_k - 1], (lambda k=_k: _frostberry(k, False)), glow=BERRY_GLOW)
for _k in (1, 2):
    register(PAINTERS, variants("frostberry_vine", 2)[_k - 1], (lambda k=_k: _frostberry(k, True)), glow=BERRY_GLOW)


# Rime lichen: icy lichen on the grotto's walls, colder and greener than the blue lichen of the Deep Blue Rift: specks,
# and small rosettes, a ring round a dark heart. The two lighter shades glow (cyan under the shader).
RIME = ["#1E5F86", "#2F8DBA", "#55B8E0"]
RIME_GLOW = ["#2F8DBA", "#55B8E0"]
ROSETTES = [[".lm.",
             "lddm",
             "mddm",
             ".mm."],
            [".l.",
             "ldm",
             ".m."]]


def _rime_stamp(px, rng, x, y):
    """A speck, or now and then a rosette; nothing lands on a pixel already taken, so the specks spread out."""
    if px[x % 16, y % 16][3]:
        return
    if rng.random() < 0.18:
        shape = ROSETTES[0 if rng.random() < 0.5 else 1]
        for dy, row in enumerate(shape):
            for dx, ch in enumerate(row):
                if ch != ".":
                    put_wrap(px, x + dx, y + dy, RIME["dml".index(ch)])
    else:
        ball(px, x + rng.random(), y + rng.random(), 0.6 + rng.random() * 0.8, RIME, wrap=True)


register(PAINTERS, "rime_lichen", lambda: speckle_sheet(9701, 0, 0, 0, RIME, coverage=0.35, stamp_fn=_rime_stamp),
         glow=RIME_GLOW)
register(PAINTERS, "rime_lichen_2", lambda: speckle_sheet(9706, 0, 0, 0, RIME, coverage=0.25, stamp_fn=_rime_stamp),
         glow=RIME_GLOW)


# Hoarfrost: white frost on the rock of frozen cave mouths: feathery bursts of needles, each lit on top and casting a
# pale blue shadow down and right, which crowd into patches, and small six-pointed stars kept clear of them so that
# they read as stars. No glow.
FROST = {"s": "#B4CDE0", "m": "#DCEBF5", "l": "#F4FAFF"}
STARS = [["m.l.m",
          ".mlm.",
          "..l..",
          ".mlm.",
          "m.s.m"],
         ["m...m",
          ".m.m.",
          "llllm",
          ".m.m.",
          "s...s"]]
DIRS8 = [(0, -1), (1, -1), (1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1)]


def _near(pts, zone, margin):
    """Whether any of these pixels lies within margin steps of the zone (round the tile's edges)."""
    return any(((x + dx) % 16, (y + dy) % 16) in zone for x, y in pts
               for dx in range(-margin, margin + 1) for dy in range(-margin, margin + 1) if abs(dx) + abs(dy) <= margin)


def _burst(rng, x, y):
    """Needles out of one point in three to five directions, light along their length and paler at the tips; a long
    needle carries a barb near its tip. Returns (x, y, shade) for each pixel."""
    pts = [(x, y, "l")]
    k = rng.randrange(3, 6)
    first = rng.randrange(8)
    for d in sorted({(first + j * 8 // k + rng.randrange(2)) % 8 for j in range(k)}):
        dx, dy = DIRS8[d]
        n = rng.choice((1, 2, 2, 3))
        pts += [(x + dx * i, y + dy * i, "l" if i < n else "m") for i in range(1, n + 1)]
        if n == 3:
            bx, by = DIRS8[(d + rng.choice((-1, 1))) % 8]
            pts.append((x + dx * 2 + bx, y + dy * 2 + by, "m"))
    return pts


def _hoarfrost(seed, coverage, stars):
    rng = random.Random(seed)
    img = blank()
    px = img.load()
    star_px = set()
    placed = 0
    for _ in range(600):  # the stars first, well apart
        if placed >= stars:
            break
        rows = STARS[rng.randrange(2)]
        x0, y0 = rng.randrange(16), rng.randrange(16)
        cells = [((x0 + dx) % 16, (y0 + dy) % 16, ch) for dy, row in enumerate(rows) for dx, ch in enumerate(row)
                 if ch != "."]
        if _near([(x, y) for x, y, _ in cells], star_px, 3):
            continue
        for x, y, ch in cells:
            put_wrap(px, x, y, FROST[ch])
            star_px.add((x, y))
        placed += 1
    for _ in range(600):  # then bursts of needles, clear of the stars, until the frost covers its share of the face
        if sum(1 for y in range(16) for x in range(16) if px[x, y][3]) >= coverage * 256:
            break
        x, y = rng.randrange(16), rng.randrange(16)
        if any(px[(x + dx) % 16, (y + dy) % 16][3] for dx in (-1, 0, 1) for dy in (-1, 0, 1)):
            continue  # each burst grows from bare rock, so they spread over the face
        pts = _burst(rng, x, y)
        if _near([(a, b) for a, b, _ in pts], star_px, 1):
            continue
        for a, b, ch in pts:
            put_wrap(px, a, b, FROST[ch])
        for a, b, _ in pts:
            if not px[(a + 1) % 16, (b + 1) % 16][3]:
                put_wrap(px, a + 1, b + 1, FROST["s"])
    return img


register(PAINTERS, "hoarfrost", lambda: _hoarfrost(9812, 0.4, 2))
register(PAINTERS, "hoarfrost_2", lambda: _hoarfrost(9840, 0.3, 2))


# Icicles: hanging ice for a cross model, like vanilla's pointed dripstone: the body a column of ice four to six pixels
# wide, lit on its left edge, with a darker streak down its core and a rim of light on its shaded right side; the tip
# tapers to a point. The edges of every body sit on columns 5 and 10 at rows 0 and 15 (the tip's row 0 too), and the
# streak on column 8, so bodies stack in any order and the tip continues them.
ICE = {"c": "#7AA3CC", "s": "#8FB4D9", "m": "#B3D2EE", "l": "#DCEBF8", "h": "#F5FBFF"}
# Per row: the left and right edge, the column of the core streak, and whether the highlight shows (a break in it
# reads as a ripple in the ice). Frozen drips on the sides as (column, top row, length): a bead of ice frozen on the
# outside of the column, on rows where the column reaches out to it.
ICICLE_BODY = {
    1: {"left": [5, 5, 5, 6, 6, 5, 5, 5, 5, 5, 6, 6, 6, 5, 5, 5],
        "right": [10, 10, 10, 10, 9, 9, 10, 10, 10, 10, 10, 9, 9, 10, 10, 10],
        "core": [8, 8, 8, 8, 7, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8],
        "shine": "1110111101111011",
        "drips": [(11, 6, 3), (4, 1, 2)]},
    2: {"left": [5, 5, 6, 6, 6, 6, 5, 5, 5, 6, 6, 5, 5, 5, 5, 5],
        "right": [10, 10, 10, 9, 9, 10, 10, 10, 9, 9, 9, 10, 10, 10, 10, 10],
        "core": [8, 8, 8, 8, 8, 8, 8, 9, 8, 8, 8, 8, 7, 7, 8, 8],
        "shine": "1101111110111101",
        "drips": [(4, 6, 3), (11, 11, 3)]},
}
ICICLE_TIP = {
    1: {"left": [5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 7, 8, 8, 8],
        "right": [10, 10, 10, 10, 9, 9, 9, 9, 9, 8, 8, 8, 8, 8, 8],
        "core": [8, 8, 8, 8, 8, 8, 7, 8, 8, 8, 8, 8, 8, 8, 8],
        "shine": "111011110111111",
        "drips": [(10, 4, 3)]},
    2: {"left": [5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 8],
        "right": [10, 10, 10, 9, 9, 9, 9, 9, 9, 8, 8, 8, 8, 8, 8],
        "core": [8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8],
        "shine": "110111110111111",
        "drips": [(4, 1, 3)]},
}


def _ice_row(px, y, left, right, core, shine):
    """One row of the column: the lit left edge, a highlight beside it, the core streak, the shade right of it and the
    rim of light on the right edge. Narrow rows keep the light edge and the shade."""
    w = right - left + 1
    for x in range(left, right + 1):
        if w == 1:
            c = "m"
        elif w == 2:
            c = "l" if x == left else "s"
        elif x == left:
            c = "l"
        elif x == right:
            c = "m" if w >= 4 else "s"
        elif x == core:
            c = "c"
        elif x == left + 1:
            c = "h" if shine else "l"
        elif x < core:
            c = "m"
        else:
            c = "s"
        put(px, x, y, ICE[c])


def _icicle(v, tip):
    spec = ICICLE_TIP[v] if tip else ICICLE_BODY[v]
    img = blank()
    px = img.load()
    for y, (lo, hi) in enumerate(zip(spec["left"], spec["right"])):
        _ice_row(px, y, lo, hi, spec["core"][y], spec["shine"][y] == "1")
    for x, top, n in spec["drips"]:  # lit on the left side of the column, shaded on the right
        lit = x < 8
        for k in range(n):
            put(px, x, top + k, ICE[("l" if k < n - 1 else "m") if lit else ("m" if k == 0 else "s")])
    if tip:  # the point: one pale pixel under the last row
        y = len(spec["left"])
        put(px, spec["left"][-1], y, ICE["l"])
    return img


for _k in (1, 2):
    register(PAINTERS, variants("icicle_plant", 2)[_k - 1], (lambda k=_k: _icicle(k, False)))
    register(PAINTERS, variants("icicle", 2)[_k - 1], (lambda k=_k: _icicle(k, True)))
