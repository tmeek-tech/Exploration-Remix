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
from plants_fern import LIT as FERN_LIT, STALK as FERN_STALK, BACK_LIT as FERN_BACK_LIT, BACK_STALK
from plants import (hexc, grid, blank, _line, register, put, stamp, sway, bezier, prisms, put_wrap)

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

# Olive strands and leaves (the amber bulb vine's until 0.14.3, which left them to the weeping ivy).
OLIVE = {"dark": "#404514", "stem": "#50561C", "stem2": "#5B6625"}
OLIVE_LEAVES = [("#404514", "#5B6625", "#78873A"), ("#50561C", "#69702C", "#9BAA55"), ("#404514", "#69702C", "#78873A")]

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




# ------------------------------------------------------------------------------------------------ Silver Fern Rift
# silver_fern_rift.jpg: pale fronds hanging from the ledges (#9A9D5E to #D3D6A3), tall ferns on the ledges, fronds
# fanning out of cracks in the walls. 0.14.6 (Taran, 17 Nov): the silver ferns' green, "a more saturated green but
# still paler than vanilla cave vines", where the picture's khaki read warm and 0.14.5's silver read grey.
FROND = list(FERN_LIT)                          # from the stalk out to the leaflet's tip
FROND_STALK = [FERN_STALK[1], FERN_STALK[0]]    # the stalk (and the leaflets behind), and every third stalk pixel


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


# The fronds of an unrolled fern, per block: (stalk column, top row, length, longest leaflet). Two to a block, half a
# block apart and half a block lower, so they lie like shingles, each one's wide top beside the narrow end of the one
# above; the back sheet's sit between them.
FROND_SHINGLES = [(4, 0, 14, 4), (12, 8, 14, 4)]
FROND_SHINGLES_BACK = [(0, 4, 12, 3), (8, 12, 12, 3)]


def _frond_sheet(tip, back, top=False):
    """A struck silver fern's fronds unrolled down the rock (0.18.7, the rune_frond block: RunePlants): the hanging
    fern's fronds let down flat against the wall, each drawn as the hanging fern draws its own (plants_fern.py): a
    stalk with a leaflet off every pixel on alternating sides, each leaving at 45 degrees towards the tip, longest at
    the top and shortening to it, so the rock shows between them. They overlap like shingles (FROND_SHINGLES); the
    back sheet, drawn just behind and darker, fills in between, so the curtain reads thick. Body tiles run on into
    their neighbours on every side (drawn wrapped), so a curtain of any width and length joins up; a column's lowest
    block (tip) draws the ends of the fronds from the block above, and its own fronds shortened to end inside it; the
    highest block of a column beside the fern's (top) only the fronds that start in it, the first a little way down,
    so the column's top edge is ragged."""
    img = blank()
    px = img.load()
    stalk, lit = (BACK_STALK, FERN_BACK_LIT) if back else (FROND_STALK[0], FROND)
    fronds = FROND_SHINGLES_BACK if back else FROND_SHINGLES
    drawn = []
    for x0, y0, length, widest in fronds:
        if top:
            # nothing from above: the first frond starts a little way down
            drawn.append((x0, y0 + 2, length - 2, widest) if y0 < 4 else (x0, y0, length, widest))
            continue
        if not tip:
            drawn.append((x0, y0, length, widest))
            continue
        # the end of the one from the block above, and this block's own, ending inside it
        drawn.append((x0, y0 - 16, length, widest))
        drawn.append((x0, y0, min(length, 15 - y0), widest if 15 - y0 >= length else max(2, widest * (15 - y0) // length)))

    def at(x, y, c):
        if tip or top:
            # what runs on below a top block is drawn by the block below it, as for any block
            put(px, x % 16, y, c)
        else:
            px[x % 16, y % 16] = hexc(c)
    for x0, y0, length, widest in drawn:
        if length < 3:
            continue
        for i in range(length):
            y = y0 + i
            at(x0, y, stalk if back or i % 3 != 2 else FROND_STALK[1])
            n = max(1, int(round(widest * (1.0 - i / length) + 0.3)))
            side = -1 if i % 2 == 0 else 1
            for k in range(1, n + 1):
                at(x0 + side * k, y + k, lit[0] if k == 1 else lit[3] if k == n else lit[1] if k == 2 else lit[2])
        at(x0, y0 + length, lit[3])
    return img


# The unrolled fronds (rune_frond: tools/runes.py writes its models): the sheet against the rock, the darker one just
# behind it, and each one's tip and top.
register(PAINTERS, "rune_frond", lambda: _frond_sheet(False, False))
register(PAINTERS, "rune_frond_tip", lambda: _frond_sheet(True, False))
register(PAINTERS, "rune_frond_back", lambda: _frond_sheet(False, True))
register(PAINTERS, "rune_frond_back_tip", lambda: _frond_sheet(True, True))
register(PAINTERS, "rune_frond_top", lambda: _frond_sheet(False, False, True))
register(PAINTERS, "rune_frond_back_top", lambda: _frond_sheet(False, True, True))

# The silver ferns (small, tall and hanging) are in plants_fern.py.


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
# Everything that glows in the Blue Reaches is drawn from one saturated ramp (#1C3478 #2650B4 #3368D8 #4486EE #6AA6F6
# #A8D0FF): under a shader a glowing block shines at about 2.5 times its texture's brightness, so paler blues clip to
# white, and both pictures glow blue.
BELL = ["#2650B4", "#3368D8", "#4486EE", "#6AA6F6", "#A8D0FF"]
BELL_GLOW = ["#4486EE", "#6AA6F6", "#A8D0FF"]
BELL_STEM = ["#1F5266", "#2F7890", "#3F93AE"]
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


# Rung by a rune (0.18.7, GlowbellBlock), a glowbell rests a while, its glow sunk to an ember: the blooms a shade or
# two darker down the same ramp, and only their hearts still glowing, dimly.
BLOOM_RESTING_COLOURS = {"A": "#1C3478", "a": "#21418F", "b": BELL[0], "c": "#2C5AC4", "W": BELL[2]}
BELL_RESTING_GLOW = [BELL[2]]


def _glowbell(v, resting=False):
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
        stamp(px, bx, by, bloom, BLOOM_RESTING_COLOURS if resting else BLOOM_COLOURS)
    return img


register(PAINTERS, "glowbell", lambda: _glowbell(1), glow=BELL_GLOW)
register(PAINTERS, "glowbell_2", lambda: _glowbell(2), glow=BELL_GLOW)
register(PAINTERS, "glowbell_resting", lambda: _glowbell(1, True), glow=BELL_RESTING_GLOW)
register(PAINTERS, "glowbell_2_resting", lambda: _glowbell(2, True), glow=BELL_RESTING_GLOW)

PAGODA = {"top": ["#2A4A9E", "#3A62C4", "#4A7EE0"], "under": "#1A2550", "gill": "#22397A", "rim": "#6AA6F6",
          "stem": ["#4A5F96", "#6F86C0"]}
# Caps as (centre column, row of the underside, half width, thickness at the middle), from the top down, on a 16 by
# 32 canvas: a puffy top cap, then caps to alternate sides, widest low down, as in the picture. The second look has
# four caps, a wide one on top and the others alternating sides under it.
PAGODA_CAPS = [(7.5, 8, 5.5, 4), (10.5, 13, 5.0, 3), (6.0, 19, 6.5, 4), (11.5, 24, 4.2, 3), (4.0, 27, 4.2, 3)]
PAGODA_CAPS_2 = [(8.0, 9, 6.5, 4), (4.5, 16, 4.5, 3), (11.0, 21, 5.5, 4), (6.5, 28, 6.0, 3)]


def _pagoda(caps=PAGODA_CAPS):
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
    for cx, under, half, thick in caps:
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
register(PAINTERS, "pagoda_fungus_top_2", lambda: _pagoda(PAGODA_CAPS_2).crop((0, 0, 16, 16)), glow=[PAGODA["rim"]])
register(PAINTERS, "pagoda_fungus_bottom_2", lambda: _pagoda(PAGODA_CAPS_2).crop((0, 16, 16, 32)), glow=[PAGODA["rim"]])

# Blue lichen (0.22.8; Taran's pick on the look card, 25 Nov 2026: "Strands", in place of 0.21.7's rosettes): thin
# branching threads of silver blue over the rifts' walls, two tufts to a face, each spreading from a knot and ending in
# glowing knots, the closest of the looks to vanilla's glow lichen. The thick lichen of the streaks down the rifts'
# walls (climbable like vines) is the same threads grown into a mat over the whole face, wrapping round its edges so a
# streak runs on unbroken from block to block.
S_COL = ("#3A5294", "#5A7CC6", "#8CB8FA")                  # thread dark, thread, the glowing knots
S_FELT = "#283A70"                                         # the old dark threads under a thick mat


def _dot(px, x, y, c, wrap):
    x, y = int(math.floor(x)), int(math.floor(y))
    (put_wrap if wrap else put)(px, x, y, c)


def _thread(px, rng, x, y, a, n, depth, wrap, end=S_COL[2], branch_end=S_COL[2]):
    """A thread n pixels long from (x, y) heading a, wandering, branching now and then (up to depth deep), ending in a
    knot of colour end, its branches in knots of branch_end."""
    for i in range(n):
        x += math.cos(a) * 0.9
        y += math.sin(a) * 0.9
        a += rng.uniform(-0.45, 0.45)
        if not wrap and not (0.5 < x < 15.5 and 0.5 < y < 15.5):
            return
        _dot(px, x, y, S_COL[1] if i % 3 else S_COL[0], wrap)
        if depth and rng.random() < 0.2:
            _thread(px, rng, x, y, a + rng.choice((-1, 1)) * 0.9, max(2, n - i - 2), depth - 1, wrap, branch_end, branch_end)
    _dot(px, x, y, end, wrap)


def _tuft(px, rng, x, y, arms, reach, wrap, depth=2, branch_end=S_COL[2]):
    """arms threads spreading from a knot at (x, y), each reach[0] to reach[1] pixels long."""
    for k in range(arms):
        _thread(px, rng, x, y, k * 6.3 / arms + rng.random(), rng.randint(*reach), depth, wrap, S_COL[2], branch_end)
    for ox, oy in ((0, 0), (1, 0), (0, 1), (1, 1)):   # the knot, lit on its upper left
        _dot(px, x + ox - 0.5, y + oy - 0.5, S_COL[2] if ox + oy < 2 else S_COL[1], wrap)


def _lichen_strands(seed):
    """Two tufts of threads on a face (blue lichen)."""
    rng = random.Random(seed)
    img = blank()
    px = img.load()
    for _ in range(2):
        _tuft(px, rng, rng.uniform(4, 12), rng.uniform(4, 12), 3, (4, 7), False)
    return img


def _thick_strands(seed):
    """Threads grown into a mat over the whole face, wrapping round its edges (thick blue lichen): a felt of old dark
    threads, and over it three tufts of live ones, longer and less branched than the thin lichen's, whose own ends glow
    (their branches' do not, so a streak glows about as much as a face of the thin lichen while it covers half the
    face)."""
    rng = random.Random(seed)
    img = blank()
    px = img.load()
    for _ in range(10):   # the old growth, dark
        x, y, a = rng.uniform(0, 16), rng.uniform(0, 16), rng.uniform(0, 6.3)
        for _ in range(rng.randint(3, 6)):
            x, y, a = x + math.cos(a) * 0.9, y + math.sin(a) * 0.9, a + rng.uniform(-0.6, 0.6)
            _dot(px, x, y, S_FELT, True)
    knots = []
    for _ in range(2000):
        if len(knots) >= 3:
            break
        x, y = rng.uniform(0, 16), rng.uniform(0, 16)
        if all(min(abs(x - a), 16 - abs(x - a)) ** 2 + min(abs(y - b), 16 - abs(y - b)) ** 2 >= 7 ** 2 for a, b in knots):
            knots.append((x, y))
    for x, y in knots:
        _tuft(px, rng, x, y, 4, (5, 8), True, depth=1, branch_end=S_COL[1])
    return img


register(PAINTERS, "blue_lichen", lambda: _lichen_strands(9311), glow=[S_COL[2]])
register(PAINTERS, "blue_lichen_2", lambda: _lichen_strands(9312), glow=[S_COL[2]])
register(PAINTERS, "blue_lichen_3", lambda: _lichen_strands(9316), glow=[S_COL[2]])
register(PAINTERS, "thick_blue_lichen", lambda: _thick_strands(9325), glow=[S_COL[2]])
register(PAINTERS, "thick_blue_lichen_2", lambda: _thick_strands(9328), glow=[S_COL[2]])

# Asurine buds: small crystals among the big ones (asurine_geodes.jpg, deep_blue_rift.jpg).
ASURINE = {"dark": "#1C3478", "left": "#3368D8", "right": "#4486EE", "edge": "#6AA6F6", "tip": "#A8D0FF"}


def _asurine_bud(v):
    img = blank()
    px = img.load()
    prisms(px, [(4, 2, 5, -1), (7, 3, 7, 0), (11, 2, 4, 1)] if v == 1 else [(5, 3, 6, -1), (10, 2, 5, 1)], ASURINE)
    return img


register(PAINTERS, "asurine_bud", lambda: _asurine_bud(1), glow=["#6AA6F6", "#A8D0FF"])
register(PAINTERS, "asurine_bud_2", lambda: _asurine_bud(2), glow=["#6AA6F6", "#A8D0FF"])

# Asurine crust (0.22.8; Taran's pick on the look card, 25 Nov 2026: "Sugar", in place of 0.21.7's druse): fine
# glittering crystal over the asurine of the geodes' lining and the rifts' walls, which a shader leaves near black where
# nothing glows (asurine_geodes.jpg, deep_blue_rift.jpg): a patch or two of sugary grains, a few of them bright
# sparkles, each patch's lower right rim in shadow. The bright grains and the sparkles glow.
C_FACE = ("#1A2B66", "#2A4AA8", "#3F6BD4", "#5E92EE", "#8EBEFF", "#BEDCFF")   # shadow, dark, mid, lit, bright, sparkle


def _sugar(seed):
    rng = random.Random(seed)
    img = blank()
    px = img.load()
    patch = [[False] * 16 for _ in range(16)]
    for _ in range(2):
        cx, cy, r = rng.uniform(4, 12), rng.uniform(4, 12), rng.uniform(2.5, 4.5)
        for y in range(1, 15):
            for x in range(1, 15):
                if math.hypot(x + 0.5 - cx, y + 0.5 - cy) < r:
                    patch[y][x] = True
    for y in range(16):
        for x in range(16):
            if not patch[y][x]:
                continue
            if y < 15 and x < 15 and not patch[y + 1][x + 1]:   # the rim's lower right, in shadow
                put(px, x, y, C_FACE[1])
                continue
            v = rng.random()
            put(px, x, y, C_FACE[5] if v < 0.07 else C_FACE[4] if v < 0.3 else C_FACE[3] if v < 0.65 else C_FACE[2])
    return img


# Four looks (two did for the druse), as the round sugar patches would repeat plainly over a wall: two patches apart,
# two small ones, a long one and a tall one.
for _k, _seed in enumerate((9411, 9405, 9417, 9406)):
    register(PAINTERS, "asurine_crust" + (f"_{_k + 1}" if _k else ""), (lambda s=_seed: _sugar(s)), glow=[C_FACE[4], C_FACE[5]])

# More looks for the existing asurine crystal and blue glow mushroom, in their palettes and with their glow.
ASURINE_CRYSTAL = {"dark": "#1C3478", "left": "#3368D8", "right": "#4486EE", "edge": "#4486EE", "tip": "#A8D0FF"}


def asurine_crystal_2():
    """Fewer, taller shards than the first look."""
    img = blank()
    px = img.load()
    prisms(px, [(2, 2, 9, -1), (6, 3, 13, 0), (11, 2, 10, 1), (13, 2, 6, 1)], ASURINE_CRYSTAL)
    return img


register(PAINTERS, "asurine_crystal_2", asurine_crystal_2, glow=["#A8D0FF", "#4486EE"])

BLUE_MUSHROOM = {"w": "#A8D0FF", "B": "#6AA6F6", "b": "#4486EE", "d": "#2650B4", "s": "#6F86B8", "S": "#4C6496"}
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
], BLUE_MUSHROOM), glow=["#6AA6F6", "#A8D0FF"])
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
], BLUE_MUSHROOM), glow=["#6AA6F6", "#A8D0FF"])




# ------------------------------------------------------------------------------------------------ glow sedge
# The reeds by the rifts' water to hide in (0.21.7; the block silver_cattails, whose id stays). 0.22.8 (Taran's pick
# on the look card, 25 Nov 2026: "Glow Sedge", in place of 0.21.7's silver cattails): dark navy blades whose tips glow
# light blue, like a field of glowing grass. A plant is drawn whole, on a canvas 16 wide and 16 a block tall, so its
# blades run on across the joins between its blocks; each block's texture is its slice (block.SilverCattailsBlock
# seeds every block of a plant from its foot, so the slices of one drawing stand together). Blades fan out from the
# foot, the back ones a shade darker, and the last stretch of each glows, brightest at its tip.
SEDGE_BLADE = ("#151D38", "#1F2A4E", "#2B3A66", "#3A4D80")   # navy: shadow, dark, mid, light
SEDGE_TIP = ("#3F6FD6", "#5F94EE", "#86B6FA")               # the glowing tips, dark to light
SEDGE_PARTS = {2: ("short_top", "short_bottom"), 3: ("top", "middle", "bottom")}   # top first, as drawn


def _sedge_blade(px, H, x0, h, lean, ramp, wide):
    def cput(x, y, c):
        x, y = int(math.floor(x)), int(math.floor(y))
        if 0 <= x < 16 and 0 <= y < H:
            px[x, y] = hexc(c)

    top = H - h
    pts = bezier((x0, H + 0.5), (x0 + lean * 0.15, top), (x0 + lean, top), steps=6 * H)
    seen = set()
    for i, (x, y) in enumerate(pts):
        key = (int(x), int(y))
        if key in seen:
            continue
        seen.add(key)
        t = i / (len(pts) - 1)
        if t > 0.72:
            c = SEDGE_TIP[min(2, int((t - 0.72) / 0.28 * 3))]
        else:
            c = ramp[1] if t < 0.25 else ramp[2] if t < 0.6 else ramp[3]
        cput(x, y, c)
        if wide and t < 0.4:
            cput(x + 1, y, ramp[0] if t < 0.2 else ramp[1])


def _sedge(seed, n):
    """A glow sedge plant n blocks tall, on a 16 x 16n canvas."""
    rng = random.Random(seed * 7919 + n)
    H = 16 * n
    canvas = Image.new("RGBA", (16, H), (0, 0, 0, 0))
    px = canvas.load()
    k = rng.randint(7, 9)
    blades = []
    for i in range(k):
        x0 = 1.0 + (i + rng.uniform(0.15, 0.85)) * 13.0 / k
        h = rng.uniform(0.55, 0.95) * H
        lean = (x0 - 8) / 8 * rng.uniform(1.0, 3.5) + rng.uniform(-1.2, 1.2)
        blades.append((rng.random(), x0, h, max(-x0 + 0.5, min(15.4 - x0, lean))))
    for depth, x0, h, lean in sorted(blades):
        ramp = SEDGE_BLADE if depth > 0.4 else (SEDGE_BLADE[0],) + SEDGE_BLADE[:3]   # back blades a shade darker
        _sedge_blade(px, H, x0, h, lean, ramp, rng.random() < 0.55)
    return canvas


def _sedge_slice(seed, n, k):
    return _sedge(seed, n).crop((0, 16 * k, 16, 16 * k + 16))


SEDGE_LOOKS = 3
for _n, _parts in SEDGE_PARTS.items():
    for _k, _part in enumerate(_parts):
        for _v in range(1, SEDGE_LOOKS + 1):
            _name = f"silver_cattails_{_part}" + ("" if _v == 1 else f"_{_v}")
            _img = _sedge_slice(_v * 31 + _n, _n, _k)
            _tips = {hexc(c) for c in SEDGE_TIP}
            _glows = any(_img.getpixel((x, y)) in _tips for x in range(16) for y in range(16))
            register(PAINTERS, _name, (lambda s=_v * 31 + _n, n=_n, k=_k: _sedge_slice(s, n, k)),
                     glow=list(SEDGE_TIP) if _glows else None)
