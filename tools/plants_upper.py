"""Plant and crystal textures of the Upper Caves and the Blue Reaches (vegetation rework, part A).

Same rules as tools/plants.py: 16 by 16, vanilla style, colours from Taran's pictures, drawn with the shared helpers
there. A painter registered with glow=[colours] gets an overlay of those pixels that glows in the dark.

Vines keep their strands on the same columns in every variant (sway() repeats every 16 rows), so any body tile stacks
on any other and the tip continues the body. Leaves and bulbs that span two or more rows stay off rows 0, 1, 14 and 15
of a body tile, where a different variant may join; those rows get one-row leaves instead. (The silver frond's
leaflets instead run on into the tile below, drawn wrapped, and are the same in every variant near the joins.)
"""
import math, random
from PIL import Image
from plants import (hexc, grid, blank, _line, register, put, stamp, sway, bezier, fern_fan, prisms, speckle_sheet,
                    put_wrap)

PAINTERS = {}


# Leaves drawn off a strand to the right (mirrored to the left), as rows of a (shadow, mid, light) ramp. A leaf taller
# than one row only goes on rows 2 to 12 of a body tile; the one-row leaf fills the rows where tiles join.
LEAF = ["mll", "sm."]
LEAF_ROW = ["ml"]
LEAF_DROOP = ["ml", "mm", "s."]


def leaf(px, x, y, side, rows, ramp):
    """A leaf off the strand pixel (x, y), on side -1 (left) or 1 (right)."""
    shade = {"s": ramp[0], "m": ramp[1], "l": ramp[2]}
    for dy, row in enumerate(rows):
        for dx, ch in enumerate(row):
            if ch != ".":
                put(px, x + side * (dx + 1), y + dy, shade[ch])


def variants(base, n):
    """base, base_2 ... base_n."""
    return [base if k == 1 else f"{base}_{k}" for k in range(1, n + 1)]


# ------------------------------------------------------------------------------------------------ Rootvaults

# Amber bulb vine: rootvaults.jpg (bunches of amber bulbs on leafy vines), silver_fern_rift.jpg (strings of orange
# bulbs), teal_grotto.jpg. The bulbs glow (overlay), the olive strands and leaves don't.
AMBER = {"o": "#AB7017", "y": "#D38E2C", "m": "#DCA23F", "Y": "#ECBA5D", "W": "#FBD780"}
AMBER_GLOW = ["#ECBA5D", "#FBD780"]
OLIVE = {"dark": "#404514", "stem": "#50561C", "stem2": "#5B6625"}
OLIVE_LEAVES = [("#404514", "#5B6625", "#78873A"), ("#50561C", "#69702C", "#9BAA55"), ("#404514", "#69702C", "#78873A")]
AMBER_STRANDS = [(3, 1.0, 0.0), (8, 1.0, 2.1), (12, 1.0, 4.2)]
# Round bulbs lit from inside, with a darker rim so that bulbs side by side stay apart (like vanilla's glow berries):
# a big one 4 by 4, a small one 3 by 3, and a bead 2 by 2 for the strings of the rift.
BULB_BIG = [".yy.",
            "yWYo",
            "yYYo",
            ".oo."]
BULB_SMALL = [".Y.",
              "yWo",
              ".o."]
BEAD = ["WY",
        "yo"]
BULBS = {"big": BULB_BIG, "small": BULB_SMALL, "bead": BEAD}
# Where the bulbs sit on each look, as (strand, row the bulb hangs from, size, offset of its left column from the
# strand). Body tiles keep them on rows 2 to 13 so that no bulb is cut where two tiles join; a tip's longest strand
# ends in a bunch.
AMBER_BODY = {
    1: [(0, 2, "big", -1), (2, 5, "big", -2), (1, 9, "small", -1), (0, 10, "small", 0)],
    2: [(1, 2, "big", -2), (0, 7, "small", -1), (2, 9, "big", -1)],
    3: [(1, 2, "bead", 1), (1, 5, "bead", -2), (1, 8, "bead", 1), (1, 11, "bead", -2),
        (2, 3, "bead", -2), (2, 7, "bead", 1), (2, 11, "bead", -2), (0, 5, "bead", 1), (0, 10, "bead", -2)],
}
AMBER_TIP = {
    1: {"ends": [9, 10, 7], "bunch": 1, "others": [(0, "small"), (2, "small")]},
    2: {"ends": [12, 8, 10], "bunch": 2, "others": [(0, "big"), (1, "small")]},
}
# A bunch at the end of a strand: bulbs around and below the strand's last pixel, drawn top to bottom. It spans 7
# columns and 7 rows (from the strand's last row), kept inside the tile.
BUNCH = [(-3, 0, "big"), (0, 1, "big"), (-2, 3, "big"), (1, 4, "small")]


def _bulb(px, x, y, size):
    stamp(px, x, y, BULBS[size], AMBER)


def _amber_vine(v, tip):
    rng = random.Random(7100 + v * 13 + (500 if tip else 0))
    img = blank()
    px = img.load()
    t = AMBER_TIP[v] if tip else None
    ends = t["ends"] if tip else [16, 16, 16]
    paths = [sway(*s) for s in AMBER_STRANDS]
    leafy = 0.45 if v == 3 and not tip else 0.8
    for i, xs in enumerate(paths):
        for y in range(ends[i]):
            put(px, xs[y], y, OLIVE["stem2"] if (y + i) % 3 else OLIVE["stem"])
        for y in range((i + v) % 2, ends[i] - 1, 2):
            if rng.random() > leafy:
                continue
            side = 1 if (y // 2 + i) % 2 else -1
            ramp = OLIVE_LEAVES[rng.randrange(3)]
            leaf(px, xs[y], y, side, LEAF if 2 <= y <= 12 else LEAF_ROW, ramp)
    if tip:
        b = t["bunch"]
        e = ends[b] - 1
        bx = min(max(paths[b][e], 3), 12)
        for dx, dy, size in BUNCH:
            _bulb(px, bx + dx, min(e, 9) + dy, size)
        for i, size in t["others"]:
            e = ends[i] - 1
            _bulb(px, min(max(paths[i][e], 2), 13) - (1 if size == "small" else 2), min(e + 1, 16 - len(BULBS[size])), size)
    else:
        for i, y, size, dx in AMBER_BODY[v]:
            _bulb(px, paths[i][y] + dx, y, size)
    return img


for _k in (1, 2, 3):
    register(PAINTERS, variants("amber_bulb_vine_plant", 3)[_k - 1], (lambda k=_k: _amber_vine(k, False)), glow=AMBER_GLOW)
for _k in (1, 2):
    register(PAINTERS, variants("amber_bulb_vine", 2)[_k - 1], (lambda k=_k: _amber_vine(k, True)), glow=AMBER_GLOW)


# Weeping ivy: rootvaults.jpg, veridium_ravine.jpg (ivy curtains), teal_grotto.jpg (leafy vines): a dense curtain of
# drooping leaves on four strands; the tip thins out.
IVY_STRANDS = [(2, 1.0, 0.7), (6, 1.0, 2.9), (10, 1.0, 5.0), (13, 1.0, 1.4)]


def _weeping_ivy(v, tip):
    rng = random.Random(7300 + v * 17 + (900 if tip else 0))
    img = blank()
    px = img.load()
    density = {1: 0.85, 2: 0.6, 3: 0.7}[v]
    ends = [rng.randrange(7, 14) for _ in IVY_STRANDS] if tip else [16] * 4
    for i, s in enumerate(IVY_STRANDS):
        xs = sway(*s)
        for y in range(ends[i]):
            put(px, xs[y], y, OLIVE["dark"] if (y + i) % 2 else OLIVE["stem"])
        for y in range(i % 2, ends[i] - 1, 2):
            if rng.random() >= density * (0.6 if tip and y > ends[i] - 5 else 1):
                continue
            side = 1 if (y // 2 + i) % 2 else -1
            ramp = OLIVE_LEAVES[rng.randrange(3)]
            leaf(px, xs[y], y, side, LEAF_DROOP if 2 <= y <= 11 else LEAF_ROW, ramp)
        if tip:  # the strand ends in a last small leaf
            e = ends[i] - 1
            leaf(px, xs[e], e, -1 if i % 2 else 1, LEAF_ROW, OLIVE_LEAVES[1])
    if v == 3 and not tip:  # thin tendrils curling off two strands
        for i in (0, 2):
            xs = sway(*IVY_STRANDS[i])
            for x, y in _line(xs[5] + 1, 5, xs[5] + 2, 9):
                put(px, x, y, OLIVE["stem2"])
            put(px, xs[5] + 3, 10, "#9BAA55")
    return img


for _k in (1, 2, 3):
    register(PAINTERS, variants("weeping_ivy_plant", 3)[_k - 1], (lambda k=_k: _weeping_ivy(k, False)))
for _k in (1, 2):
    register(PAINTERS, variants("weeping_ivy", 2)[_k - 1], (lambda k=_k: _weeping_ivy(k, True)))


# Grotto ivy: teal_grotto.jpg, the dark teal ivy over the walls (#0C241A to #304A3D in the picture, brightened). A
# sheet that tiles: runners, and three-lobed leaves lit from the top left with a dark vein, kept apart by gaps.
GROTTO = ["#1F3A2B", "#2C4D38", "#3B6146", "#4E7855", "#6A9265"]
IVY_LEAF = [".L.L.",
            "LMMMm",
            ".MvM.",
            "..m..",
            ]


def _grotto_ivy(v):
    rng = random.Random(7500 + v)
    img = blank()
    px = img.load()
    for _ in range(3):  # runners under the leaves
        x, y = rng.randrange(16), rng.randrange(16)
        for _ in range(16):
            put_wrap(px, x, y, GROTTO[0])
            x += rng.choice((-1, 0, 0, 1))
            y += 1
    target = 0.55 if v == 1 else 0.65
    tries = 0
    while sum(1 for yy in range(16) for xx in range(16) if px[xx, yy][3] and px[xx, yy] != hexc(GROTTO[0])) < target * 256:
        tries += 1
        if tries > 400:
            break
        x, y = rng.randrange(16), rng.randrange(16)
        shade = rng.randrange(2)
        cols = {"L": GROTTO[3 + shade], "M": GROTTO[2 + shade], "m": GROTTO[1 + shade], "v": GROTTO[1]}
        flip = rng.random() < 0.5
        for dy, row in enumerate(IVY_LEAF):
            for dx, ch in enumerate(row[::-1] if flip else row):
                if ch != ".":
                    put_wrap(px, x + dx, y + dy, cols[ch])
    return img


register(PAINTERS, "grotto_ivy", lambda: _grotto_ivy(1))
register(PAINTERS, "grotto_ivy_2", lambda: _grotto_ivy(2))


# Starmoss: teal_grotto.jpg, moss on the ledges with tiny pale star flowers glowing in it. A carpet that tiles.
MOSS = ["#3E4E1E", "#4E5E22", "#66792C", "#7F9437"]
STAR_GLOW = ["#F4CF6A", "#F6F0DE", "#FFF6CB"]


def _starmoss(v):
    rng = random.Random(7700 + v)
    img = speckle_sheet(7700 + v, 0, 0.9, 1.7, MOSS, coverage=0.55 if v == 1 else 0.62)
    px = img.load()
    for _ in range(18):  # break the clumps up a little
        x, y = rng.randrange(16), rng.randrange(16)
        if px[x, y][3]:
            px[x, y] = hexc(MOSS[0]) if rng.random() < 0.6 else (0, 0, 0, 0)
    for _ in range(4 if v == 1 else 6):  # five-petalled stars: a cross with one petal split
        x, y = rng.randrange(16), rng.randrange(16)
        put_wrap(px, x, y, "#F4CF6A")
        for dx, dy in ((1, 0), (-1, 0), (0, -1), (1, 1), (-1, 1)):
            put_wrap(px, x + dx, y + dy, "#F6F0DE")
    for _ in range(5):
        put_wrap(px, rng.randrange(16), rng.randrange(16), "#FFF6CB")
    return img


register(PAINTERS, "starmoss", lambda: _starmoss(1), glow=STAR_GLOW)
register(PAINTERS, "starmoss_2", lambda: _starmoss(2), glow=STAR_GLOW)


# ------------------------------------------------------------------------------------------------ Silver Fern Rift
# silver_fern_rift.jpg: pale fronds hanging from the ledges (#9A9D5E to #D3D6A3), tall ferns on the ledges, fronds
# fanning out of cracks in the walls.
FROND = ["#999E7F", "#ACAE6B", "#B7BB88", "#D3D6A3"]
FROND_STALK = ["#7A7F55", "#5F6A3E"]


def _silver_frond(v, tip):
    """A pale frond hanging from a ledge, as in the picture: one stalk with leaflets on both sides, long and close
    together, slanting a little down and out, pale at their tips, with darker leaflets behind. The leaflets on rows 12
    to 15 and 0 to 1 are the same in every look and run on into the tile below (drawn wrapped), so body tiles stack in
    any order; the looks differ in rows 2 to 11. The tip's leaflets shorten towards its end."""
    img = blank()
    px = img.load()
    xs = sway(7, 1.0, 0.4)
    look = {1: [5, 6, 4, 6, 5, 3, 6, 5, 4, 6], 2: [4, 5, 6, 3, 6, 4, 5, 6, 3, 5]}[v]
    end = (11 if v == 1 else 13) if tip else 16
    for y in range(end):
        put(px, xs[y], y, FROND_STALK[0] if y % 3 else FROND_STALK[1])

    def at(x, y, c):
        if tip:
            put(px, x, y, c)
        else:
            put_wrap(px, x, y, c)
    # A tip also draws the ends of the leaflets that run on from the body tile above it (rows -2 and -1).
    for y in range(-2 if tip else 0, end):
        n = look[y - 2] if 2 <= y <= 11 else 5 if y % 2 else 4
        if tip and y >= 0:
            n = min(n, max(1, (end - y) // 2 + 1))
        side = -1 if y % 2 == 0 else 1
        for k in range(1, n + 1):
            x, yy = xs[y % 16] + side * k, y + k // 3
            if k >= 2 and k < n:  # a darker leaflet behind, one row down
                at(x, yy + 1, FROND_STALK[0])
            at(x, yy, FROND[0] if k == 1 else FROND[3] if k == n else FROND[2] if (k + y) % 2 else FROND[1])
    if tip:
        put(px, xs[end - 1], end, FROND[3])
    return img


for _k in (1, 2):
    register(PAINTERS, variants("silver_frond_plant", 2)[_k - 1], (lambda k=_k: _silver_frond(k, False)))
    register(PAINTERS, variants("silver_frond", 2)[_k - 1], (lambda k=_k: _silver_frond(k, True)))

# Tall silver fern: six or seven fronds from one base, drawn on one 16 by 32 canvas and cut in two.
TALL_FERN = [
    [((6.5, 31.5), (3, 22), (1, 12)), ((7.5, 31.5), (5, 16), (4, 2)), ((7.5, 31.5), (8, 14), (9, 0)),
     ((8.5, 31.5), (11, 16), (13, 4)), ((8.5, 31.5), (13, 22), (15, 13)), ((6.5, 31.5), (4, 28), (0, 23)),
     ((8.5, 31.5), (12, 28), (15, 24))],
    [((6.5, 31.5), (4, 16), (0, 8)), ((7.5, 31.5), (6, 14), (6, 1)), ((8.5, 31.5), (10, 14), (12, 1)),
     ((8.5, 31.5), (12, 18), (15, 9)), ((6.5, 31.5), (5, 24), (2, 18)), ((8.5, 31.5), (11, 25), (14, 19))],
]
for _k in (1, 2):
    register(PAINTERS, variants("tall_silver_fern_top", 2)[_k - 1],
             (lambda k=_k: fern_fan(TALL_FERN[k - 1], height=32).crop((0, 0, 16, 16))))
    register(PAINTERS, variants("tall_silver_fern_bottom", 2)[_k - 1],
             (lambda k=_k: fern_fan(TALL_FERN[k - 1], height=32).crop((0, 16, 16, 32))))

# More looks for the existing silver fern: five fronds, and three long ones.
register(PAINTERS, "silver_fern_2", lambda: fern_fan([
    ((7.5, 15.5), (5, 10), (1, 4)), ((7.5, 15.5), (7.5, 8), (7, 0)), ((8.5, 15.5), (11, 10), (14, 4)),
    ((6.5, 15.5), (4, 13), (0, 11)), ((8.5, 15.5), (12, 13), (15, 12))]))
register(PAINTERS, "silver_fern_3", lambda: fern_fan([
    ((6.5, 15.5), (4, 8), (2, 1)), ((7.5, 15.5), (8, 7), (8, 0)), ((8.5, 15.5), (12, 8), (14, 2))], max_len=3))

# Silver wall fern: fronds fanning out of a crack. The model is vanilla's coral_wall_fan, which puts the bottom rows
# against the wall and the top rows at the free tips.
register(PAINTERS, "silver_wall_fern", lambda: fern_fan([
    ((7.5, 15.5), (5, 9), (3, 1)), ((8.5, 15.5), (11, 9), (12, 1)), ((8.5, 15.5), (13, 12), (15, 8))]))
register(PAINTERS, "silver_wall_fern_2", lambda: fern_fan([
    ((6.5, 15.5), (3, 11), (0, 5)), ((7.5, 15.5), (7, 8), (6, 0)), ((8.5, 15.5), (10, 8), (12, 1)),
    ((8.5, 15.5), (13, 13), (15, 9))]))


# ------------------------------------------------------------------------------------------------ Ivory Root Cavern
# ivory_root_cavern.jpg: pale caps with glowing gold undersides, fine gold threads hanging from the roof, olive bushes
# on the shore terraces.
IVORY = {"A": "#A88E66", "B": "#BBA27C", "C": "#CDB894", "D": "#DDCCAE", "E": "#EAE0CA", "F": "#F7F1E3"}


def _ivory_bracket(v):
    """Top of the shelf (rows 0 to 9, row 0 the rim) and its edge strip (rows 12 to 15), laid out like the orange
    shelf fungus's: growth rings around where it meets the wall, the gold of the gills showing under the rim."""
    rows = []
    for y in range(16):
        row = ""
        for x in range(16):
            if y <= 9:
                if y == 0:
                    row += "F"
                    continue
                d = math.hypot((x - 7.5) * (0.75 if v == 1 else 0.9), (y - 12.0) * 1.25)
                row += "ABBCCDDEEEFF"[max(0, min(11, int(d / 1.5)))]
            elif y >= 12:
                row += {12: "F", 13: "E", 14: "D", 15: "C"}[y]
            else:
                row += "."
        rows.append(row)
    img = grid(rows, IVORY)
    px = img.load()
    rng = random.Random(8500 + v)
    for _ in range(6 if v == 1 else 12):  # flecks
        put(px, rng.randrange(1, 15), rng.randrange(2, 9), "#C4AD86" if v == 1 else "#B49A72")
    for x in range(16):
        if rng.random() < 0.5:
            put(px, x, 15, "#F2C870")
    return img


def ivory_bracket_under():
    """Gold gills fanning out from the wall (row 9) to the rim (row 0). The model lights the whole face (forge_data
    on the down face), so it needs no overlay."""
    img = blank()
    px = img.load()
    for y in range(10):
        for x in range(16):
            put(px, x, y, "#FFDE88" if y == 0 else "#F2C870")
    for xr in (0, 2, 4, 6, 9, 11, 13, 15):
        x0 = int(round(7.5 + (xr - 7.5) * 0.5))
        for x, y in _line(x0, 9, xr, 1):
            put(px, x, y, "#BD9146")
    for x in range(16):
        if x % 3 == 1:
            put(px, x, 1, "#FFF0B8")
    return img


register(PAINTERS, "ivory_bracket", lambda: _ivory_bracket(1))
register(PAINTERS, "ivory_bracket_2", lambda: _ivory_bracket(2))
register(PAINTERS, "ivory_bracket_under", ivory_bracket_under)

# Gold threads: five fine threads hanging from the roof with glowing droplets along them.
THREAD = ["#B78941", "#CFA256"]
DROP = ["#DFAB55", "#EEBF68", "#FAD178", "#FEE087"]
DROP_GLOW = ["#EEBF68", "#FAD178", "#FEE087"]
# Five threads, not evenly spaced, two of them a little wavy (sway() repeats every 16 rows, so the tiles stack).
THREADS = [(1, 0.0, 0.0), (4, 0.6, 1.7), (8, 0.0, 0.0), (11, 0.6, 4.4), (14, 0.0, 0.0)]


def _gold_threads(v, tip):
    """Fine threads with glowing drops on them at random, and a few sparkles beside them; at the tip the threads end at
    different lengths, each in a drop."""
    rng = random.Random(8700 + v + (60 if tip else 0))
    img = blank()
    px = img.load()
    for i, t in enumerate(THREADS):
        xs = sway(*t)
        end = rng.randrange(6, 14) if tip else 16
        for y in range(end):
            put(px, xs[y], y, THREAD[0] if (y + 2 * i) % 5 else THREAD[1])
        y = rng.randrange(1, 4)
        while y < min(13, end - 2):
            put(px, xs[y], y, DROP[3])
            put(px, xs[y], y + 1, DROP[1] if rng.random() < 0.6 else DROP[0])
            if rng.random() < 0.25:  # a sparkle beside the thread
                put(px, xs[y] + rng.choice((-1, 1)), y + rng.randrange(-1, 2), DROP[2])
            y += rng.randrange(4, 9)
        if tip:  # a drop gathering at the end of each thread
            put(px, xs[end - 1], end, DROP[2])
            put(px, xs[end - 1], end + 1, DROP[3])
            put(px, xs[end - 1], end + 2, DROP[0])
    return img


for _k in (1, 2):
    register(PAINTERS, variants("gold_threads_plant", 2)[_k - 1], (lambda k=_k: _gold_threads(k, False)), glow=DROP_GLOW)
    register(PAINTERS, variants("gold_threads", 2)[_k - 1], (lambda k=_k: _gold_threads(k, True)), glow=DROP_GLOW)

# Sage bush: the round olive bushes on the cavern's terraces (#383513 to #59541F in the picture): one bush, or two.
SAGE = ["#2F2C10", "#383513", "#4A4519", "#59541F", "#6E6A2C", "#8A8640"]


def _sage_bush(v):
    rng = random.Random(8900 + v)
    img = blank()
    px = img.load()
    domes = [(7.5, 8.5, 6.8, 6.0)] if v == 1 else [(4.0, 10.0, 3.7, 4.2), (11.5, 8.0, 4.2, 5.8)]
    for x in range(16):
        for y in range(16):
            for cx, cy, rx, ry in domes:
                d = ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2
                if d > 1.0 - rng.random() * 0.25 or y > 13:
                    continue
                if rng.random() < 0.13:
                    continue
                light = 0.55 - 0.35 * ((x + 0.5 - cx) / rx + (y + 0.5 - cy) / ry) + (rng.random() - 0.5) * 0.45
                put(px, x, y, SAGE[max(1, min(5, int(light * 5) + 1))])
    for x, y in ((7, 15), (8, 15), (7, 14), (6, 13), (9, 13), (8, 14)):
        put(px, x, y, SAGE[0])
    if v == 2:
        for x, y in ((3, 6), (10, 4), (13, 7)):
            put(px, x, y, "#C9C08A")
    return img


register(PAINTERS, "sage_bush", lambda: _sage_bush(1))
register(PAINTERS, "sage_bush_2", lambda: _sage_bush(2))

# More looks for the existing lantern fungus, in its palette and with its glow: two caps of two sizes.
register(PAINTERS, "lantern_fungus_2", lambda: grid([
    "................",
    "................",
    "........kcCCck..",
    "......kcCCCCCCck",
    "......cCCCCCCCCc",
    "......kgGgsSgGgk",
    ".......Y..sS..Y.",
    "..kcCck.y.sS..y.",
    ".kCCCCCk..sS..o.",
    ".kgGsSgk..sS....",
    "..y.sS.Y..sS....",
    "..o.sS.y..sS....",
    "....sS....sS....",
    "....sS...ssSS...",
    "...ssSS.sssSSS..",
    "................",
], {"C": "#EFE6D2", "c": "#DCCDB0", "k": "#BFAE8E", "g": "#FFE29A", "G": "#FFF3CC", "s": "#E6DCC6", "S": "#C9BB9E",
    "y": "#F1B84F", "Y": "#FFDD86", "o": "#B98A3E"}), glow=["#FFE29A", "#FFF3CC", "#FFDD86", "#F1B84F"])


# ------------------------------------------------------------------------------------------------ Deep Blue Rift
# deep_blue_rift.jpg: glowing bells on teal stems (left), a tiered pagoda fungus, specks of light on the walls.
BELL = ["#456FBB", "#698BD9", "#78BAF5", "#ACD5FB", "#DDEEFF"]
BELL_GLOW = ["#78BAF5", "#ACD5FB", "#DDEEFF"]
BELL_STEM = ["#2E6E80", "#4A98B5", "#6FBADB"]
# The blooms are round and glow all over but for their rim (in the picture they read as discs of light), five or
# four pixels across; each hangs from a stem that rises and bends over to it.
BLOOM_5 = [".abA.",
           "abcbA",
           "bcWcA",
           "abcbA",
           ".AAA."]
BLOOM_4 = [".bb.",
           "bWcA",
           "bccA",
           ".AA."]
BLOOM_COLOURS = {"A": BELL[0], "a": BELL[1], "b": BELL[2], "c": BELL[3], "W": BELL[4]}
# (bloom, its top left corner, the stem's foot on row 15, the bend of the stem)
GLOWBELLS = {
    1: [(BLOOM_5, (1, 6), 5, (6, 2)), (BLOOM_5, (6, 1), 8, (7, 9)), (BLOOM_4, (11, 6), 11, (11, 2))],
    2: [(BLOOM_5, (2, 3), 7, (8, 1)), (BLOOM_4, (11, 8), 9, (9, 5)), (BLOOM_4, (0, 10), 6, (6, 9))],
}


def _glowbell(v):
    img = blank()
    px = img.load()
    for bloom, (bx, by), foot, bend in GLOWBELLS[v]:
        top = (bx + len(bloom[0]) / 2, by + 0.5)
        for i, (x, y) in enumerate(bezier((foot + 0.5, 15.5), bend, top, steps=40)):
            put(px, x, y, BELL_STEM[1] if i % 4 else BELL_STEM[0])
    # leaves at the foot of the stems
    for x, y, c in ((5, 15, 0), (5, 14, 1), (4, 13, 2), (10, 15, 0), (10, 14, 1), (11, 13, 2)):
        put(px, x - (v - 1), y, BELL_STEM[c])
    for bloom, (bx, by), _, _ in GLOWBELLS[v]:
        stamp(px, bx, by, bloom, BLOOM_COLOURS)
    return img


register(PAINTERS, "glowbell", lambda: _glowbell(1), glow=BELL_GLOW)
register(PAINTERS, "glowbell_2", lambda: _glowbell(2), glow=BELL_GLOW)

PAGODA = {"top": ["#4E69AF", "#6081CC", "#7B9FE7"], "under": "#2B3A6D", "gill": "#334D90", "rim": "#ACD5FB",
          "stem": ["#6E86C4", "#9DB3E6"]}
# Caps as (centre column, row of the underside, half width, thickness at the middle), from the top down, on a 16 by
# 32 canvas: a puffy top cap, then caps to alternate sides, widest low down, as in the picture.
PAGODA_CAPS = [(7.5, 8, 5.5, 4), (10.5, 13, 5.0, 3), (6.0, 19, 6.5, 4), (11.5, 24, 4.2, 3), (4.0, 27, 4.2, 3)]


def _pagoda():
    """Both halves on one 16 by 32 canvas: a pale stem with puffy caps on it, their rims glowing."""
    canvas = Image.new("RGBA", (16, 32), (0, 0, 0, 0))
    cpx = canvas.load()
    rng = random.Random(9200)

    def cput(x, y, c):
        if 0 <= x < 16 and 0 <= y < 32:
            cpx[x, y] = hexc(c)
    for y in range(6, 32):
        cput(7, y, PAGODA["stem"][1])
        cput(8, y, PAGODA["stem"][0])
    for cx, under, half, thick in PAGODA_CAPS:
        for x in range(int(math.floor(cx - half)), int(math.ceil(cx + half))):
            d = abs(x + 0.5 - cx) / half
            if d > 1:
                continue
            t = max(1, int(round(thick * (1 - d * d) ** 0.35)))
            for k in range(t):
                y = under - 1 - k
                if k == t - 1:  # the top of the cap: pale, flecked with glowing blue, its ends glowing
                    c = PAGODA["rim"] if d > 0.8 or rng.random() < 0.35 else PAGODA["top"][2]
                elif k == t - 2:
                    c = PAGODA["top"][2] if rng.random() < 0.7 else PAGODA["rim"] if rng.random() < 0.4 else PAGODA["top"][1]
                else:
                    c = PAGODA["top"][1] if k > 0 or rng.random() < 0.5 else PAGODA["top"][0]
                cput(x, y, c)
            cput(x, under, PAGODA["rim"] if d > 0.8 else PAGODA["gill"] if x % 2 else PAGODA["under"])
    return canvas


register(PAINTERS, "pagoda_fungus_top", lambda: _pagoda().crop((0, 0, 16, 16)), glow=[PAGODA["rim"]])
register(PAINTERS, "pagoda_fungus_bottom", lambda: _pagoda().crop((0, 16, 16, 32)), glow=[PAGODA["rim"]])

# Blue lichen: specks of light on the rift's walls.
LICHEN_BLUE = ["#202F64", "#2E4486", "#4A63A8", "#6E8BCB", "#A9C4F2"]
register(PAINTERS, "blue_lichen", lambda: speckle_sheet(9301, 0, 0.7, 1.6, LICHEN_BLUE, coverage=0.34), glow=["#6E8BCB", "#A9C4F2"])
register(PAINTERS, "blue_lichen_2", lambda: speckle_sheet(9302, 0, 0.6, 1.2, LICHEN_BLUE, coverage=0.26), glow=["#6E8BCB", "#A9C4F2"])

# Asurine buds: small crystals among the big ones (asurine_geodes.jpg, deep_blue_rift.jpg).
ASURINE = {"dark": "#2B468F", "left": "#5D82D1", "right": "#87ABF2", "edge": "#B1D7FB", "tip": "#EEF6FF"}


def _asurine_bud(v):
    img = blank()
    px = img.load()
    prisms(px, [(4, 2, 5, -1), (7, 3, 7, 0), (11, 2, 4, 1)] if v == 1 else [(5, 3, 6, -1), (10, 2, 5, 1)], ASURINE)
    return img


register(PAINTERS, "asurine_bud", lambda: _asurine_bud(1), glow=["#B1D7FB", "#EEF6FF"])
register(PAINTERS, "asurine_bud_2", lambda: _asurine_bud(2), glow=["#B1D7FB", "#EEF6FF"])

# More looks for the existing asurine crystal and blue glow mushroom, in their palettes and with their glow.
ASURINE_CRYSTAL = {"dark": "#2B468F", "left": "#6E9FF0", "right": "#AACDFF", "edge": "#AACDFF", "tip": "#EEF6FF"}


def asurine_crystal_2():
    """Fewer, taller shards than the first look."""
    img = blank()
    px = img.load()
    prisms(px, [(2, 2, 9, -1), (6, 3, 13, 0), (11, 2, 10, 1), (13, 2, 6, 1)], ASURINE_CRYSTAL)
    return img


register(PAINTERS, "asurine_crystal_2", asurine_crystal_2, glow=["#EEF6FF", "#AACDFF"])

BLUE_MUSHROOM = {"w": "#F4F9FF", "B": "#CFE3FF", "b": "#8FB9FF", "d": "#5683DA", "s": "#C3D4EA", "S": "#8EA5C4"}
register(PAINTERS, "blue_glow_mushroom_2", lambda: grid([
    "................",
    "................",
    "......dbbd......",
    ".....dbBwBd.....",
    ".....bBwwBb.....",
    ".....dbbbbd.....",
    "..dbd..sS.......",
    ".dbwBd.sS.......",
    ".ddbbd.sS..dbd..",
    "...sS..sS.dbwbd.",
    "...sS..sS.ddbdd.",
    "...sS..sS...sS..",
    "...sS..sS...sS..",
    "..ssS..sS...sS..",
    "..sSS.ssSS.ssS..",
    "................",
], BLUE_MUSHROOM), glow=["#CFE3FF", "#F4F9FF"])
register(PAINTERS, "blue_glow_mushroom_3", lambda: grid([
    "................",
    "................",
    "....ddbbbbdd....",
    "...dbBBwwBBbd...",
    "..dbBwwwwBBBbd..",
    "..dbBBBBBBBbbd..",
    "..ddbbbbbbbbdd..",
    "....d.sSS..d....",
    ".......sS.......",
    ".......sS.......",
    ".......sS...dbd.",
    ".......sS..dbwbd",
    "......sSS..ddbdd",
    "......sSS....sS.",
    ".....ssSSS..ssS.",
    "................",
], BLUE_MUSHROOM), glow=["#CFE3FF", "#F4F9FF"])


# ------------------------------------------------------------------------------------------------ Deep dark
# Dusk vines: near-black strands with a few cold specks that glow without giving light. No picture: the deep dark
# stays dark, and these are its one new plant.
DUSK_STRANDS = [(3, 1.0, 0.4), (9, 1.0, 3.0), (13, 1.0, 5.1)]
DUSK_GLOW = ["#6FE3E0", "#2FA3A8"]


def _dusk_vines(v, tip):
    rng = random.Random(9500 + v + (30 if tip else 0))
    img = blank()
    px = img.load()
    for i, s in enumerate(DUSK_STRANDS):
        xs = sway(*s)
        end = rng.randrange(8, 14) if tip else 16
        for y in range(end):
            put(px, xs[y], y, "#14262C" if (y + i) % 3 else "#0E1A1F")
            if rng.random() < 0.25:
                put(px, xs[y] + rng.choice((-1, 1)), y, "#1C3640")
        for y in range(3 + (i * 4 + v) % 5, min(13, end), 6):
            put(px, xs[y] + (1 if i % 2 else -1), y, "#6FE3E0")
        if tip:
            put(px, xs[min(15, end)], min(15, end), "#2FA3A8")
    return img


register(PAINTERS, "dusk_vines_plant", lambda: _dusk_vines(1, False), glow=DUSK_GLOW)
register(PAINTERS, "dusk_vines_plant_2", lambda: _dusk_vines(2, False), glow=DUSK_GLOW)
register(PAINTERS, "dusk_vines", lambda: _dusk_vines(1, True), glow=DUSK_GLOW)
