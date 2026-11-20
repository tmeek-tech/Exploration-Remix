"""Textures of the lifeless Basalt Galleries (0.5): the ember crust, glowing rock that lights their halls, and the cinder
chain that their climbs hang in place of vines. And (0.20.1) the glow ice of the deep Frost Fern Grotto.

Same rules as tools/plants.py: 16 by 16, vanilla style (a short ramp of shades, hard pixels), drawn with its helpers,
the colours those of the basalt pictures (plants_deep.CHAR and EMBER). Nothing here is alive. textures.py merges this
file after the plant files.

A mod block that gives light glows over its whole face under Complementary (client/ShaderGlow), and the ember crust is
also drawn at full brightness without the shader (emissive rendering, like magma): so its texture is glowing plates
with dark seams between them, never dark rock with a few lit cracks, which would glow as dull lit rock.
"""
import math, random
from plants import blank, register, put, put_wrap
from plants_deep import CHAR, EMBER

PAINTERS = {}


# Ember crust: the skin of rock over the heat, broken into plates that glow, brightest in the middle of each, a few with
# a white-hot heart and a few cooling to a dull red; dark seams between them where it has cooled. A cell pattern on the
# torus, so it tiles across a wall.
CRUST_SEAM = ["#3B1D15", "#5A2A1A"]          # the cooled seams, darkest where three plates meet
CRUST_PLATE = ["#A8441F", "#C75628", EMBER[2], EMBER[3], "#FFB458"]
CRUST_COOL = ["#6E2C17", "#86361A", "#A8441F", "#C75628", EMBER[2]]
CRUST_HOT = "#FFE3A3"


def _wrapped(ax, ay, bx, by):
    dx, dy = abs(ax - bx), abs(ay - by)
    return math.hypot(min(dx, 16 - dx), min(dy, 16 - dy))


def _ember_crust():
    rng = random.Random(9101)
    seeds = []
    while len(seeds) < 8:  # plate centres at least 4.5 apart round the tile's edges
        p = (rng.random() * 16, rng.random() * 16)
        if all(_wrapped(*p, *q) >= 4.5 for q in seeds):
            seeds.append(p)
    picks = rng.sample(range(len(seeds)), 5)
    hearts, cool = set(picks[:3]), set(picks[3:])
    img = blank()
    px = img.load()
    for y in range(16):
        for x in range(16):
            c = (x + 0.5, y + 0.5)
            d = sorted((_wrapped(*c, *s), k) for k, s in enumerate(seeds))
            (d1, k1), (d2, _), (d3, _) = d[0], d[1], d[2]
            if d2 - d1 < 0.85:
                # a seam: darkest where a third plate is near too
                put(px, x, y, CRUST_SEAM[0] if d3 - d1 < 1.6 else CRUST_SEAM[1])
                continue
            # the plate: bright in the middle, dimmer to its edge, a little grain
            edge = d2 - d1
            t = min(1.0, edge / 3.2) + (rng.random() - 0.5) * 0.35
            ramp = CRUST_COOL if k1 in cool else CRUST_PLATE
            put(px, x, y, ramp[max(0, min(len(ramp) - 1, int(t * len(ramp))))])
            if k1 in hearts and d1 < 1.0:
                put(px, x, y, CRUST_HOT)
    return img


register(PAINTERS, "ember_crust", _ember_crust)


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


# Glow ice (0.20.1): the deep Frost Fern Grotto's ice, lit light blue from inside, in veins through its ice crusts.
# Like the ember crust it gives light, so under Complementary it glows over its whole face (client/ShaderGlow), and
# without the shader it is drawn at full brightness (emissive rendering): so its texture is glowing facets of ice,
# brightest at their hearts, with deeper blue cracks between them. Light blue, never cyan (Taran).
ICE_FACET = ["#86BDF4", "#97C8F8", "#A9D3FB", "#BBDDFD", "#CDE7FF"]
ICE_CRACK = ["#5C95DC", "#6FA6E8"]
ICE_HEART = "#EEF7FF"


def _glow_ice():
    rng = random.Random(2011)
    seeds = []
    while len(seeds) < 6:  # facet centres at least 4.5 apart round the tile's edges
        p = (rng.random() * 16, rng.random() * 16)
        if all(_wrapped(*p, *q) >= 4.5 for q in seeds):
            seeds.append(p)
    # each facet's shade leans one way across it, like light through a face of a crystal
    lean = [(rng.uniform(-1, 1), rng.uniform(-1, 1)) for _ in seeds]
    hearts = set(rng.sample(range(len(seeds)), 3))
    img = blank()
    px = img.load()
    for y in range(16):
        for x in range(16):
            c = (x + 0.5, y + 0.5)
            d = sorted((_wrapped(*c, *s), k) for k, s in enumerate(seeds))
            (d1, k1), (d2, _), (d3, _) = d[0], d[1], d[2]
            if d2 - d1 < 0.6:
                # a crack: deepest where a third facet is near too
                put(px, x, y, ICE_CRACK[0] if d3 - d1 < 1.5 else ICE_CRACK[1])
                continue
            sx, sy = seeds[k1]
            dx, dy = (c[0] - sx + 8) % 16 - 8, (c[1] - sy + 8) % 16 - 8
            t = 0.5 + 0.09 * (lean[k1][0] * dx + lean[k1][1] * dy) + min(0.3, (d2 - d1) / 9) + (rng.random() - 0.5) * 0.16
            put(px, x, y, ICE_FACET[max(0, min(len(ICE_FACET) - 1, int(t * len(ICE_FACET))))])
            if k1 in hearts and d1 < 0.9:
                put(px, x, y, ICE_HEART)
    return img


register(PAINTERS, "glow_ice", _glow_ice)
