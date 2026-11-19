"""Exploration Remix block textures: 16 by 16, vanilla style (a short ramp of shades, hard pixels, no blur).

Each painter is deterministic, so the textures only change when this file does.
"""
import math, os, random
from PIL import Image


def hexc(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def wrap_noise(rng, size=16, cells=4):
    """Tileable value noise in [0, 1)."""
    g = [[rng.random() for _ in range(cells)] for _ in range(cells)]
    out = [[0.0] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            fx, fy = x / size * cells, y / size * cells
            x0, y0 = int(fx), int(fy)
            tx, ty = fx - x0, fy - y0
            tx = tx * tx * (3 - 2 * tx)
            ty = ty * ty * (3 - 2 * ty)
            x1, y1 = (x0 + 1) % cells, (y0 + 1) % cells
            a = g[y0][x0] * (1 - tx) + g[y0][x1] * tx
            b = g[y1][x0] * (1 - tx) + g[y1][x1] * tx
            out[y][x] = a * (1 - ty) + b * ty
    return out


def ramp_pick(ramp, v):
    i = int(v * len(ramp))
    return ramp[max(0, min(len(ramp) - 1, i))]


def band_stone():
    """Dense, dark, compressed rock: near-black with a violet cast, tight wavy layering and a few hard glints."""
    rng = random.Random(1601)
    ramp = [hexc(c) for c in ("#0e0c14", "#15121d", "#1c1826", "#241f30", "#2d273b")]
    glint = hexc("#4b4262")
    n1 = wrap_noise(rng, 16, 4)
    n2 = wrap_noise(rng, 16, 8)
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            # tight layering: a slow wave sets the bands, noise breaks them up
            wave = (y + 2.0 * n1[(y * 3) % 16][x]) % 4.0 / 4.0
            v = 0.45 * n1[y][x] + 0.35 * n2[y][x] + 0.2 * wave
            c = ramp_pick(ramp, v)
            px[x, y] = c + (255,)
    # fractures: short dark diagonal runs
    for _ in range(5):
        x, y = rng.randrange(16), rng.randrange(16)
        for k in range(rng.randrange(2, 5)):
            px[(x + k) % 16, (y + k // 2) % 16] = ramp[0] + (255,)
    # glints: single bright pixels with a darker neighbour under them
    for _ in range(4):
        x, y = rng.randrange(16), rng.randrange(16)
        px[x, y] = glint + (255,)
        px[x, (y + 1) % 16] = ramp[1] + (255,)
    return img


PAINTERS = {
    "block/band_stone": band_stone,
}

# The plant and crystal textures live in their own files: plants.py (the first plants and the shared helpers), then
# plants_upper.py (Upper Caves and Blue Reaches) and plants_deep.py (Deep Gardens and Basalt Galleries). plants_deep
# goes last because it may repaint a plant of plants.py. The Upper Caves rework added plants_frost.py and plants_ice.py
# (the Frost Fern Grotto and the frozen Root Vaults) and plants_entries.py (the sinkholes and the oasis hollows);
# plants_fern.py has the Silver Fern Rift's ferns, redrawn fluffier for their 3D models. 0.5's lifeless Galleries added
# minerals.py (the ember crust and the cinder chain), which is no plant file. 0.6.0's Mossback added creatures.py (its
# den, the bone scraps round it and its hide; the creatures' own textures, the Mossback's and since 0.7.0 the Skein's,
# are drawn by gen_data.py through it), 0.7.0 added fireflies.py (the blue fireflies' glow), and 0.8.0 added
# particles.py, the volcanic smoke's puffs (textures/particle).
import plants  # noqa: E402
import plants_upper  # noqa: E402
import plants_fern  # noqa: E402
import plants_frost  # noqa: E402
import plants_ice  # noqa: E402
import plants_entries  # noqa: E402
import plants_deep  # noqa: E402
import minerals  # noqa: E402
import creatures  # noqa: E402
import fireflies  # noqa: E402  (0.7: the blue fireflies' glow, in a file of its own)
import particles  # noqa: E402
import giant_skein  # noqa: E402  (0.18.8: the giant Skein's drop, its spine)
PAINTERS.update(plants.PAINTERS)
PAINTERS.update(plants_upper.PAINTERS)
PAINTERS.update(plants_fern.PAINTERS)
PAINTERS.update(plants_frost.PAINTERS)
PAINTERS.update(plants_ice.PAINTERS)
PAINTERS.update(plants_entries.PAINTERS)
PAINTERS.update(plants_deep.PAINTERS)
PAINTERS.update(minerals.PAINTERS)
PAINTERS.update(creatures.PAINTERS)
PAINTERS.update(fireflies.PAINTERS)
PAINTERS.update(particles.PAINTERS)
PAINTERS.update(giant_skein.PAINTERS)

# Emissive overlays: texture -> the colours lifted into <texture>_glow.png, which the models draw a second time at full
# block light (gen_data.py), so those pixels glow even where the plant gives little or no light. The plant files add
# theirs with plants.register(..., glow=...); the first glowing plants get theirs here.
GLOW = {
    "block/white_glow_flower": {"#F6F0DE", "#F4CF6A"},
    "block/lantern_fungus": {"#FFE29A", "#FFF3CC", "#FFDD86", "#F1B84F"},
    "block/lantern_pod": {"#F4A93A", "#FFD26E", "#FFF0C0"},
    "block/gold_flower": {"#FFF4C8", "#EBCB72"},
    "block/asurine_crystal": {"#A8D0FF", "#4486EE"},
    "block/blue_glow_mushroom": {"#A8D0FF", "#6AA6F6"},
    "block/blue_glow_vines": {"#8CC0FF", "#3F7FE8"},
    "block/blue_glow_vines_plant": {"#8CC0FF", "#3F7FE8"},
    "block/blue_glow_vines_plant_2": {"#8CC0FF", "#3F7FE8"},
    "block/ember_vines": {"#FFE6A6", "#F7B046"},
    "block/ember_vines_plant": {"#FFE6A6", "#F7B046"},
}
GLOW.update(plants.GLOW)


# =============================================================================================== Deep Gardens round 2
# The vent field's blocks (ERBlocks, the Deep Gardens from y -289 to -528). Taran's look rules, which every texture here
# keeps (cool_problems checks each one as it is written): the neon colours are only light blue, violet, magenta and
# lime, lime only near the ways down, and each glowing block comes in all four (its blockstate property neon); nothing
# warm (no orange, yellow, red, brown or warm-white tint), no cyan or teal, no froglights, no green moss. The cave walls
# are vanilla deepslate, the vents and spires vanilla blackstone (round 3, Taran: "Vents should be blackstone instead of
# vent rock and live vent rock"), the dripstone black dripstone, so the neon glow stands out on black. A glowing block's
# texture is its rock (or its cap) with the neon drawn in, or, for the neon blocks on the vents, the neon alone over
# clear pixels: gen_data draws those over vanilla's blackstone (option rock), so they blend into the vents round them.
# The neon's bright shades are lifted into the glow overlay, drawn again at full brightness, while the rock and the
# dark shades are lit by the world. A block's four colours share one pattern (one seed), so they read as one block in
# four colours. Small features over the whole tile, never one big shape, so a wall of them does not show the tile's
# repeat.

NEONS = ["magenta", "violet", "lime", "light_blue"]  # block.Neon's order
NEON_RGB = {"magenta": (255, 70, 205), "violet": (168, 96, 255), "lime": (176, 255, 70), "light_blue": (125, 175, 255)}
GLOWING = ("mid", "neon", "hot")  # the shades the glow overlay lifts


def neon_shades(neon):
    """A neon colour's shades, dark to light: deep and dim are lit by the world like the rock, mid, neon and hot glow.
    The darker shades scale the colour and the hot one mixes it halfway to white, so every shade keeps the neon's hue."""
    r, g, b = NEON_RGB[neon]

    def scale(k): return (round(r * k), round(g * k), round(b * k))

    return {"deep": scale(0.24), "dim": scale(0.44), "mid": scale(0.72), "neon": (r, g, b),
            "hot": tuple(round(v + (255 - v) * 0.5) for v in (r, g, b))}


def _hex(c): return "#%02X%02X%02X" % tuple(c[:3])


# The rocks' ramps, dark to light: all cool, a little blue-violet. VENT_RAMP is the retired vent rock's, kept because the
# neon painters still draw (and then clear) it to keep their patterns.
VENT_RAMP = [hexc(c) for c in ("#26232E", "#2E2B37", "#35323F", "#3C3947", "#444150", "#4D4A5A")]
VENT_PIT, VENT_GRAIN = hexc("#18161F"), hexc("#625E71")
NEAR_BLACK = [hexc(c) for c in ("#121117", "#18161E", "#1E1C25", "#24222C", "#2B2834")]  # under the neon
NEAR_BLACK_PIT = hexc("#0C0B10")
DRIP_RAMP = [hexc(c) for c in ("#1F1D26", "#27252E", "#2F2C37", "#373441", "#403D4B", "#4A4756")]
MAT_ROCK = [hexc(c) for c in ("#34343E", "#3F3F4A", "#4A4A56", "#565663", "#636370")]  # deepslate-dark, under a mat
STEM_RAMP = [hexc(c) for c in ("#BAB2C7", "#C5BDD2", "#CFC7DA", "#D8D1E3", "#E2DCEC", "#ECE7F4")]
FINE = ((8, 0.5), (4, 0.25))  # noise octaves (cells, weight): mostly the fine one


def _put(px, x, y, c):
    """Colours pixel (x, y), wrapping round the tile's edges; c is RGB."""
    px[x % 16, y % 16] = tuple(c[:3]) + (255,)


def _old_rock(rng, ramp, **kw):
    """A clear tile for a neon pattern drawn over vanilla's blackstone. Until round 3 the vents' neon blocks painted a
    near-black rock of their own under the neon; since then they are overlays (gen_data's option rock), but their
    painters still draw that rock from the same random numbers and throw it away, so every crystal, crack, vein and knob
    keeps its old place (Taran: "The spire tip and vent crown look good together")."""
    _rock(rng, ramp, **kw)
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def _field(rng, octaves=FINE, grain=0.25):
    """Tileable noise over the tile, a value per pixel: octaves of wrap_noise and a little grain of its own."""
    fields = [(wrap_noise(rng, 16, c), w) for c, w in octaves]
    return {(x, y): sum(f[y][x] * w for f, w in fields) + grain * rng.random() for y in range(16) for x in range(16)}


def _by_shares(field, shares):
    """The tile's pixels ranked by a field, lowest first, cut into groups of the given shares (the last takes the
    rest): patches of exact sizes, the last group at the patches' hearts."""
    order = sorted(field, key=field.get)
    out, i = [], 0
    for k, s in enumerate(shares):
        n = len(order) - i if k == len(shares) - 1 else round(s * len(order))
        out.append(order[i:i + n])
        i += n
    return out


def _rock(rng, ramp, pits=0, pit=None, lip=True, grains=0, grain=None):
    """Unlayered rock: the ramp spread evenly over a fine tileable noise, then pits (one or two dark pixels, the far
    wall's lip lit below right of most) and single pale grains. Everything wraps, so the tile repeats seamlessly."""
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for k, group in enumerate(_by_shares(_field(rng), [1 / len(ramp)] * len(ramp))):
        for x, y in group:
            _put(px, x, y, ramp[k])
    holes = set()
    for _ in range(pits):
        x, y = rng.randrange(16), rng.randrange(16)
        cells = [(x, y)] + ([((x + 1) % 16, y)] if rng.random() < 0.35 else [])
        holes.update(cells)
        for cx, cy in cells:
            _put(px, cx, cy, pit)
        lx, ly = (cells[-1][0] + 1) % 16, (y + 1) % 16
        if lip and rng.random() < 0.7 and (lx, ly) not in holes:
            _put(px, lx, ly, ramp[-1])
    for _ in range(grains):
        x, y = rng.randrange(16), rng.randrange(16)
        if (x, y) not in holes:
            _put(px, x, y, grain)
    return img


def black_dripstone():
    """Black dripstone: the vertical banding of vanilla's dripstone block, near-black and cool. Columns
    take a band's shade (bands one to three pixels wide); each column breaks into drips, runs of two to six rows a shade
    lighter or darker (a drip's head lighter, its tail darker), wrapping from the bottom row to the top so the cones'
    blocks stack."""
    rng = random.Random(2811)
    bands = []
    while len(bands) < 16:
        bands += [rng.choice((1, 2, 2, 3, 3, 4))] * rng.choice((1, 2, 2, 3))
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    top = len(DRIP_RAMP) - 1
    for x in range(16):
        y, filled = rng.randrange(16), 0
        while filled < 16:
            run = rng.randrange(2, 7)
            shade = bands[x] + rng.choice((-1, 0, 0, 1))
            for k in range(min(run, 16 - filled)):
                s = shade + (1 if k == 0 and run > 2 else 0) - (1 if k == run - 1 and run > 3 else 0)
                _put(px, x, y + filled + k, DRIP_RAMP[max(0, min(top, s))])
            filled += run
    return img


def pale_stem():
    """The giant mushrooms' stem: pale lavender-white with fibrous vertical streaks, like vanilla's mushroom stem but
    cool. Each column is a fibre of its own shade in long runs, with a few thin darker seams and paler strands."""
    rng = random.Random(2831)
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    top = len(STEM_RAMP) - 1
    base = [rng.choice((2, 2, 3, 3, 3, 4)) for _ in range(16)]
    for x in range(16):
        y, filled = rng.randrange(16), 0
        while filled < 16:
            run = rng.randrange(4, 11)
            s = max(0, min(top, base[x] + rng.choice((-1, 0, 0, 1))))
            for k in range(min(run, 16 - filled)):
                _put(px, x, y + filled + k, STEM_RAMP[s])
            filled += run
    for shade, n, (lo, hi) in ((0, 5, (3, 9)), (top, 4, (2, 6))):  # dark seams between fibres, pale strands
        for _ in range(n):
            x, y = rng.randrange(16), rng.randrange(16)
            for k in range(rng.randrange(lo, hi)):
                _put(px, x, y + k, STEM_RAMP[shade])
    return img


def glow_mat_bottom():
    """The deepslate-dark rock under a glow mat (its bottom face, and the rock of its sides): cool dark grey, its grain
    drawn out sideways like deepslate's, with a few dark flakes along it."""
    rng = random.Random(2840)
    n1, n2 = wrap_noise(rng, 16, 4), wrap_noise(rng, 16, 8)
    # the fine octave read from rows two apart, so the grain changes faster down than across
    field = {(x, y): 0.3 * n1[y][x] + 0.5 * n2[(2 * y) % 16][x] + 0.2 * rng.random() for y in range(16) for x in range(16)}
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for k, group in enumerate(_by_shares(field, [0.2] * 5)):
        for x, y in group:
            _put(px, x, y, MAT_ROCK[k])
    for _ in range(6):
        x, y = rng.randrange(16), rng.randrange(16)
        for k in range(rng.randrange(2, 5)):
            _put(px, x + k, y, MAT_ROCK[0])
    return img


def glow_mat_top(neon):
    """A glow mat from above: a living film over the floor, mostly neon, in small colonies with hot hearts (hot, neon
    and mid glow), fading through dim and deep film that the world lights to bare rock in the gaps between them."""
    s = neon_shades(neon)
    img = glow_mat_bottom()
    px = img.load()
    groups = _by_shares(_field(random.Random(2841), grain=0.15), (0.14, 0.12, 0.2, 0.26, 0.21, 0.07))
    for group, shade in zip(groups[1:], ("deep", "dim", "mid", "neon", "hot")):
        for x, y in group:
            _put(px, x, y, s[shade])
    return img


def glow_mat_side(neon):
    """A glow mat's side: the rock under it with the film's fringe over the top edge, hanging one to four pixels in
    drips that glow at the top and darken to their ends, like vanilla nylium's side."""
    s = neon_shades(neon)
    rng = random.Random(2842)
    img = glow_mat_bottom()
    px = img.load()
    for x in range(16):
        depth = rng.choice((1, 2, 2, 2, 3, 3, 4))
        for y in range(depth + 1):
            if y == 0:
                shade = "hot" if rng.random() < 0.15 else rng.choice(("neon", "neon", "mid"))
            elif y < depth - 1:
                shade = rng.choice(("mid", "mid", "neon", "dim"))
            elif y == depth - 1:
                shade = rng.choice(("mid", "dim"))
            else:
                shade = "deep"
            _put(px, x, y, s[shade])
    return img


def glow_cap(neon):
    """A glow cap, a giant mushroom's cap: neon all over, mottled with a glowing mid shade, with pale spots like
    vanilla's mushroom caps (hot, in neon rims) and darker specks and short gill-like streaks that do not glow."""
    s = neon_shades(neon)
    rng = random.Random(2851)
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    low, high = _by_shares(_field(rng, grain=0.3), (0.35, 0.65))
    for x, y in low:
        _put(px, x, y, s["mid"])
    for x, y in high:
        _put(px, x, y, s["neon"])
    for _ in range(5):  # gill streaks: two or three pixels down, edging sideways
        x, y, dx = rng.randrange(16), rng.randrange(16), rng.choice((-1, 1))
        for k in range(rng.randrange(2, 4)):
            _put(px, x + dx * (k // 2), y + k, s["dim"])
    for _ in range(12):
        _put(px, rng.randrange(16), rng.randrange(16), s["dim"])
    spots = []
    for _ in range(4000):
        p = (rng.randrange(16), rng.randrange(16))
        if all(_wrapped(*p, *q) > 6 for q in spots):
            spots.append(p)
            if len(spots) == 3:
                break
    for k, (x, y) in enumerate(spots):
        size = 3 if k == 0 else 2
        for dy in range(size):
            for dx in range(size):
                corner = size == 3 and dx != 1 and dy != 1
                _put(px, x + dx, y + dy, s["neon"] if corner else s["hot"])
    return img


def _wrapped(ax, ay, bx, by):
    dx, dy = abs(ax - bx), abs(ay - by)
    return math.hypot(min(dx, 16 - dx), min(dy, 16 - dy))


def _seeds(rng, n, spacing):
    """n points scattered over the tile at least `spacing` apart round its edges (fewer if they do not fit)."""
    out = []
    for _ in range(4000):
        p = (rng.random() * 16, rng.random() * 16)
        if all(_wrapped(*p, *q) >= spacing for q in out):
            out.append(p)
            if len(out) == n:
                break
    return out


def _cells(seeds, x, y):
    """(distance, seed) to the seeds from the middle of pixel (x, y), nearest first."""
    c = (x + 0.5, y + 0.5)
    return sorted((_wrapped(*c, *q), k) for k, q in enumerate(seeds))


def vent_crown(neon):
    """A vent crown, the bright mineral rim of a live vent's mouth: a crust of neon crystals over the vent's blackstone
    (an overlay), the brightest block of the set. Each crystal face is a cell of a tileable Voronoi pattern, lit by how
    it faces the light (from the top left): brightest at its top left, a step darker at its lower right, a hot glint at
    its top left corner; the blackstone shows in thin seams between the faces and in one bare face."""
    s = neon_shades(neon)
    rng = random.Random(2861)
    img = _old_rock(rng, NEAR_BLACK, pits=3, pit=NEAR_BLACK_PIT, lip=False)
    px = img.load()
    seeds = _seeds(rng, 12, 3.4)
    lit = [2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3]  # 1 dim, 2 mid, 3 neon (the face's body), 0 bare rock
    lit = [0] + lit[:len(seeds) - 1]
    rng.shuffle(lit)
    ladder = ["dim", "mid", "neon", "hot"]
    corner = {}
    for y in range(16):
        for x in range(16):
            (d1, k1), (d2, _) = _cells(seeds, x, y)[:2]
            if d2 - d1 < 0.6 or lit[k1] == 0:
                continue  # a seam, or bare rock
            sx, sy = seeds[k1]
            ox, oy = (x + 0.5 - sx + 8) % 16 - 8, (y + 0.5 - sy + 8) % 16 - 8
            step = lit[k1] - 1 + (1 if ox + oy < -1.0 else (-1 if ox + oy > 1.5 else 0))
            _put(px, x, y, s[ladder[max(0, min(3, step))]])
            if k1 not in corner or ox + oy < corner[k1][0]:
                corner[k1] = (ox + oy, x, y)
    for _, x, y in corner.values():
        _put(px, x, y, s["hot"])
    return img


def den_fissure(neon):
    """A den fissure, the floor of a live den: the vent's blackstone split by glowing cracks (an overlay). The cracks
    run along the edges of a tileable Voronoi pattern, its pixels pushed about by a tileable noise first so the cracks
    wander instead of running straight, about two in three of them open, so they meet in an irregular network that
    wraps round the tile: neon along their middles, mid at their edges, hot where three plates part; their lips take a
    deep tint of the glow, lit by the world."""
    s = neon_shades(neon)
    rng = random.Random(2873)
    img = _old_rock(rng, NEAR_BLACK, pits=9, pit=NEAR_BLACK_PIT)
    px = img.load()
    seeds = _seeds(rng, 7, 4.2)
    warp_x, warp_y = wrap_noise(rng, 16, 4), wrap_noise(rng, 16, 4)
    open_ = {(a, b): rng.random() < 0.7 for a in range(len(seeds)) for b in range(len(seeds)) if a < b}
    lips = []
    for y in range(16):
        for x in range(16):
            # _cells measures from the pixel's middle; the warp moves that point up to 1.6 pixels each way
            wx, wy = x + 3.2 * (warp_x[y][x] - 0.5), y + 3.2 * (warp_y[y][x] - 0.5)
            (d1, k1), (d2, k2), (d3, _) = _cells(seeds, wx, wy)[:3]
            if not open_[min(k1, k2), max(k1, k2)]:
                continue
            gap = d2 - d1
            if gap < 0.5:
                _put(px, x, y, s["hot"] if d3 - d1 < 1.1 else s["neon"])
            elif gap < 0.95:
                _put(px, x, y, s["mid"])
            elif gap < 1.7:
                lips.append((x, y))
    for x, y in lips:
        if rng.random() < 0.5:
            _put(px, x, y, s["deep"])
    return img


def vent_glow(neon):
    """Vent glow, lining the inside of a live vent: a patchy glowing mineral crust over the vent's blackstone (an
    overlay), small patches over about two fifths of the tile, grainy within (mid and neon, hot sparkles) and rimmed
    with dim crust that the world lights."""
    s = neon_shades(neon)
    rng = random.Random(2881)
    img = _old_rock(rng, NEAR_BLACK, pits=6, pit=NEAR_BLACK_PIT)
    px = img.load()
    bare, rim, crust = _by_shares(_field(rng, grain=0.12), (0.5, 0.1, 0.4))
    for x, y in rim:
        _put(px, x, y, s["dim"])
    for x, y in crust:
        r = rng.random()
        _put(px, x, y, s["hot"] if r < 0.1 else (s["neon"] if r < 0.5 else s["mid"]))
    return img


def throat_vein(neon):
    """A throat vein, lining the throats that lead down: thin glowing veins through the vent's blackstone (an overlay).
    A vein follows a contour of a tileable noise, so it winds and wraps round the tile, a pixel wide; a second, broader
    noise thins it to mid and breaks it off in places, and a few hot beads sit along it. Its lips take a deep tint of
    the glow."""
    s = neon_shades(neon)
    rng = random.Random(2891)
    img = _old_rock(rng, VENT_RAMP, pits=8, pit=VENT_PIT, grains=3, grain=VENT_GRAIN)
    px = img.load()
    n = _field(rng, ((4, 0.6), (2, 0.3), (8, 0.1)), grain=0.0)
    fade = _field(rng, ((2, 0.7), (4, 0.3)), grain=0.0)
    level = sorted(n.values())[128]
    vein = {}
    for (x, y), v in n.items():
        right, down = n[((x + 1) % 16, y)], n[(x, (y + 1) % 16)]
        if (v < level) != (right < level) or (v < level) != (down < level):
            f = fade[(x, y)]
            if f > 0.28:
                vein[(x, y)] = "neon" if f > 0.48 else "mid"
    beads = [p for p, shade in sorted(vein.items()) if shade == "neon"]
    for _ in range(3):
        if beads:
            vein[rng.choice(beads)] = "hot"
    for (x, y) in vein:
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if (nx % 16, ny % 16) not in vein and rng.random() < 0.25:
                _put(px, nx, ny, s["deep"])
    for (x, y), shade in vein.items():
        _put(px, x, y, s[shade])
    return img


def vent_beads_top(neon):
    """Vent beads' top (and bottom), the glowing top of a small live spire (the spire tip until round 3): rounded
    neon mineral knobs on the blackstone (an overlay), each lit from the top left (a hot highlight, neon, mid, then a
    dim and a deep shadow side that the world lights) with a dark shadow on the rock below right of it. The knobs wrap
    round the tile's edges."""
    s = neon_shades(neon)
    rng = random.Random(2901)
    img = _old_rock(rng, NEAR_BLACK, pits=6, pit=NEAR_BLACK_PIT)
    px = img.load()
    knobs = []
    for _ in range(4000):
        p = (rng.randrange(16) + 0.5, rng.randrange(16) + 0.5, rng.choice((1.5, 2.0, 2.0, 2.5)))
        if all(_wrapped(p[0], p[1], q[0], q[1]) >= p[2] + q[2] + 0.6 for q in knobs):
            knobs.append(p)
            if len(knobs) == 7:
                break
    ramp = [_hex(s[k]) for k in ("deep", "dim", "mid", "neon", "hot")]
    for cx, cy, r in knobs:
        for y in range(int(cy - r) - 2, int(cy + r) + 3):
            for x in range(int(cx - r) - 2, int(cx + r) + 3):
                if math.hypot(x + 0.5 - cx - 0.8, y + 0.5 - cy - 0.8) <= r + 0.05:
                    _put(px, x, y, NEAR_BLACK_PIT)
    for cx, cy, r in knobs:
        plants.ball(px, cx, cy, r, ramp, wrap=True)
        _put(px, int(cx - 0.4 * r), int(cy - 0.4 * r), s["hot"])  # the highlight
    return img


BEAD_TRAIL = (2.0, 1.5, 1.0, 1.0, 0.5)  # the beads of a trail on vent beads' side, as radii from its head down


def vent_beads_side(neon):
    """Vent beads' sides: the knobs' neon mineral running down the blackstone in two beaded trails (an overlay), as
    it also runs down a live vent's walls from the crown (round 3, Taran: "Have the spire tips run down the sides of
    vents too"). A trail is a string of beads lit like the knobs (a hot highlight at the top left, a dark shadow below
    right on the rock): a round bead at its head, then smaller and smaller down to a drop, joined by a thread of the deep
    tint. Each trail wanders a pixel or so and fills the tile's height, so it runs on through the bottom edge into the
    block below and a column of tips makes one long trail. The two lie seven to nine columns apart with their heads half
    a tile apart, so the beads of a wall never line up in rows."""
    s = neon_shades(neon)
    rng = random.Random(2904)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    ramp = [_hex(s[k]) for k in ("deep", "dim", "mid", "neon", "hot")]
    first = rng.randrange(16) + 0.5
    columns = (first, (first + rng.choice((7, 8, 9))) % 16)
    head = rng.random() * 16
    beads, thread = [], []
    for k, x0 in enumerate(columns):
        amp, phase = rng.choice((0.5, 0.8, 1.0)), rng.random() * 2 * math.pi

        def at(y, x0=x0, amp=amp, phase=phase):
            return x0 + amp * math.sin(2 * math.pi * y / 16 + phase)  # the trail's column at row y; it wraps every 16

        y = head + 8 * k + rng.random() * 2 - 1
        gaps = [rng.random() + 0.3 for _ in BEAD_TRAIL]
        free = 16 - sum(2 * r for r in BEAD_TRAIL)  # the rows between the beads, shared out so the trail fills 16
        for r, gap in zip(BEAD_TRAIL, (g / sum(gaps) * free for g in gaps)):
            beads.append((at(y + r), y + r, r))
            for t in range(int(round(2 * r + gap))):
                thread.append((at(y + t), y + t))
            y += 2 * r + gap
    for x, y in thread:
        _put(px, int(math.floor(x)), int(math.floor(y)), s["deep"])
    for cx, cy, r in beads:  # the shadows, below right of each bead, on the rock only
        for y in range(int(cy - r) - 2, int(cy + r) + 3):
            for x in range(int(cx - r) - 2, int(cx + r) + 3):
                if math.hypot(x + 0.5 - cx - 0.7, y + 0.5 - cy - 0.7) <= r + 0.05 and px[x % 16, y % 16][3] == 0:
                    _put(px, x, y, NEAR_BLACK_PIT)
    for cx, cy, r in beads:
        if r <= 0.5:  # a drop
            _put(px, int(cx), int(cy), s["mid"])
        elif r <= 1.0:  # a two by two bead
            x, y = round(cx) - 1, round(cy) - 1
            for (dx, dy), shade in (((0, 0), "hot"), ((1, 0), "neon"), ((0, 1), "neon"), ((1, 1), "mid")):
                _put(px, x + dx, y + dy, s[shade])
        else:
            plants.ball(px, cx, cy, r, ramp, wrap=True)
            _put(px, int(cx - 0.4 * r), int(cy - 0.4 * r), s["hot"])
    return img


SEEP_FILM = [tuple(round(v * k) for v in NEON_RGB["light_blue"]) for k in (0.22, 0.34, 0.5)]  # film, faint glow, glow


def seep_crust():
    """Seep crust, on the dry rock at the edge of the crude oil seeps: near-black pitted rock under a faint light blue
    film in thin broken patches, whose glowing pixels are the light blue at a third and at a half of its brightness,
    so it reads clearly dimmer than the neon blocks. Light blue only."""
    rng = random.Random(2911)
    img = _rock(rng, NEAR_BLACK, pits=8, pit=NEAR_BLACK_PIT)
    px = img.load()
    _, edge, faint, bright = _by_shares(_field(rng, grain=0.35), (0.62, 0.15, 0.14, 0.09))
    for group, c in zip((edge, faint, bright), SEEP_FILM):
        for x, y in group:
            _put(px, x, y, c)
    return img


# ------------------------------------------------------------------------------------------- Pointed black dripstone
# Vanilla's pointed dripstone in black dripstone (round 3, Taran: "Add pointed black dripstone that would be the same
# as pointed dripstone but recolored"). Vanilla's five hanging shapes and its item, pixel for pixel, as maps of
# vanilla's five shades, 0 the darkest to 4 the lightest ('.' clear): each shade becomes the same step of the black
# dripstone block's ramp, DRIP_RAMP[0] to [4]. The block spreads its pixels over those five steps much as vanilla's
# dripstone block does over vanilla's shades (its sixth, lightest step only lights its drips' heads), so the tips
# match the block they hang from as vanilla's do. The standing shapes are the hanging ones upside down, as vanilla's
# are; the item has only shades 1 to 4.
POINTED_DRIP_THICKNESS = ("tip_merge", "tip", "frustum", "middle", "base")  # vanilla's DripstoneThickness, tip first
POINTED_DRIP = {
    "tip_merge": [
        "....33222211....",
        "....33322112....",
        "....3433311.....",
        "....3444321.....",
        "....2333211.....",
        "....233331......",
        ".....34441......",
        ".....44431......",
        ".....33321......",
        ".....34421......",
        ".....23311......",
        ".....24321......",
        ".....1441.......",
        "......332.......",
        "......342.......",
        "......441.......",
    ],
    "tip": [
        "....23222211....",
        ".....3322112....",
        ".....333311.....",
        ".....444321.....",
        ".....233211.....",
        "......3331......",
        "......4441......",
        "......4431......",
        "......332.......",
        "......232.......",
        ".......32.......",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "frustum": [
        "..444333101222..",
        "..333330000001..",
        "..444432210012..",
        "..332210000002..",
        "..344322210001..",
        "...44432221011..",
        "...43311100001..",
        "...44332101222..",
        "...3444310002...",
        "...3333220001...",
        "...2333321001...",
        "....244432201...",
        "....443320001...",
        "....444220011...",
        "....34443201....",
        "....34321101....",
    ],
    "middle": [
        "..344420001221..",
        "..444432000001..",
        "..334443211121..",
        "..332212200011..",
        "..34432221000...",
        "..44443222101...",
        "..34331110001...",
        "..444432101211..",
        "..333333200011..",
        "..344443331221..",
        "..334432110011..",
        "...33333000001..",
        "...43443310111..",
        "...44432110001..",
        "..433333222111..",
        "..443322000011..",
    ],
    "base": [
        "3334442000122111",
        "2233443200000001",
        "4444444321110011",
        "4432221220000111",
        "334432222100111.",
        ".44444322210012.",
        ".33433111000002.",
        ".34443321012002.",
        ".33322222000122.",
        ".34444431112222.",
        ".33442221000002.",
        ".33322220000002.",
        ".34434433110012.",
        ".34444322200111.",
        ".3343333331111..",
        "..343322000011..",
    ],
}
POINTED_DRIP_ITEM = [
    "....11222232....",
    "....2112233.....",
    ".....113333.....",
    ".....123444.....",
    ".....112332.....",
    "......1333......",
    "......1444......",
    "......1344......",
    ".......233......",
    ".......232......",
    ".......23.......",
    "................",
    "................",
    "................",
    "................",
    "................",
]
POINTED_DRIP_SHADES = {str(k): _hex(DRIP_RAMP[k]) for k in range(5)}


def pointed_black_dripstone(thickness, direction):
    """One of pointed black dripstone's ten shapes: thickness (POINTED_DRIP_THICKNESS) and direction, "down" hanging
    or "up" standing (the hanging shape turned upside down)."""
    img = plants.grid(POINTED_DRIP[thickness], POINTED_DRIP_SHADES)
    return img if direction == "down" else img.transpose(Image.FLIP_TOP_BOTTOM)


for _name, _fn in (("black_dripstone", black_dripstone), ("pale_stem", pale_stem), ("glow_mat_bottom", glow_mat_bottom),
                   ("seep_crust", seep_crust)):
    PAINTERS["block/" + _name] = _fn
GLOW["block/seep_crust"] = {_hex(c) for c in SEEP_FILM[1:]}
# The neon blocks, a texture per colour: <block>_<colour>, or <block>_<colour>_<face> for a block whose faces differ
# (the glow mat's _top and _side, over glow_mat_bottom; the vent beads' _top, also drawn on its bottom, and _side).
NEON_PAINTERS = {"glow_mat": {"top": glow_mat_top, "side": glow_mat_side}, "glow_cap": glow_cap, "vent_crown": vent_crown,
                 "den_fissure": den_fissure, "vent_glow": vent_glow, "throat_vein": throat_vein,
                 "vent_beads": {"top": vent_beads_top, "side": vent_beads_side}}
VENT_FIELD = ["block/black_dripstone", "block/pale_stem", "block/glow_mat_bottom", "block/seep_crust"]  # every texture of the vent field, for cool_problems and tools/vent_sheet.py
for _direction in ("down", "up"):
    for _thickness in POINTED_DRIP_THICKNESS:
        _tex = f"block/pointed_black_dripstone_{_direction}_{_thickness}"
        PAINTERS[_tex] = (lambda t=_thickness, d=_direction: pointed_black_dripstone(t, d))
        VENT_FIELD.append(_tex)
PAINTERS["item/pointed_black_dripstone"] = lambda: plants.grid(POINTED_DRIP_ITEM, POINTED_DRIP_SHADES)
VENT_FIELD.append("item/pointed_black_dripstone")
for _block, _fns in NEON_PAINTERS.items():
    for _neon in NEONS:
        for _face, _fn in (_fns.items() if isinstance(_fns, dict) else [(None, _fns)]):
            _tex = f"block/{_block}_{_neon}" + (f"_{_face}" if _face else "")
            PAINTERS[_tex] = (lambda fn=_fn, neon=_neon: fn(neon))
            GLOW[_tex] = {_hex(neon_shades(_neon)[k]) for k in GLOWING}
            VENT_FIELD.append(_tex)


def hue_sat(c):
    """HSV hue in degrees (None for a grey: channels within 6 of each other) and saturation of an RGB colour."""
    r, g, b = c[:3]
    hi, lo = max(r, g, b), min(r, g, b)
    if hi - lo <= 6:
        return None, 0.0
    d = hi - lo
    if hi == r:
        h = 60 * ((g - b) / d) % 360
    elif hi == g:
        h = 60 * ((b - r) / d) + 120
    else:
        h = 60 * ((r - g) / d) + 240
    return h, d / hi


# Taran's colour rules as hue bands: blue through violet to magenta anywhere, lime's yellow-green only in the lime
# textures; any other tint (red, orange, yellow, brown, green, cyan or teal, a warm white) breaks them. Only greys
# (channels within 6) carry no hue.
COOL_HUES = (205.0, 335.0)
LIME_HUES = (75.0, 100.0)


def cool_problems(name, img):
    """The pixels of a vent field texture that break the colour rules, as (x, y, colour, hue)."""
    lime_ok = "_lime" in name
    out = []
    px = img.load()
    for y in range(img.size[1]):
        for x in range(img.size[0]):
            c = px[x, y]
            if c[3] == 0:
                continue
            h, _ = hue_sat(c)
            if h is None or COOL_HUES[0] <= h <= COOL_HUES[1] or (lime_ok and LIME_HUES[0] <= h <= LIME_HUES[1]):
                continue
            out.append((x, y, c[:3], round(h)))
    return out


def glow_layer(img, colours):
    """Only the pixels of these colours (hex), everything else transparent."""
    keep = {plants.hexc(c) for c in colours}
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    src, dst = img.load(), out.load()
    for y in range(img.size[1]):
        for x in range(img.size[0]):
            if src[x, y] in keep:
                dst[x, y] = src[x, y]
    return out


def write_all(textures_dir):
    for name, painter in PAINTERS.items():
        path = os.path.join(textures_dir, name + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img = painter()
        if name in VENT_FIELD:
            bad = cool_problems(name, img)
            assert not bad, f"{name}: colours outside the vent field's rules (x, y, colour, hue): {bad[:6]}"
        img.save(path)
        if name in GLOW:
            overlay = glow_layer(img, GLOW[name])
            assert overlay.getbbox() is not None, f"{name}: none of its glow colours is in the texture"
            overlay.save(os.path.join(textures_dir, name + "_glow.png"))
    for name in GLOW:
        assert name in PAINTERS, f"{name}: a glow overlay for a texture nobody paints"


def sheet(path, names=None, scale=8, cols=6):
    """A labelled contact sheet of the textures, for review."""
    from PIL import ImageDraw
    names = names or list(PAINTERS)
    rows = (len(names) + cols - 1) // cols
    cell = 16 * scale + 24
    out = Image.new("RGB", (cols * cell + 8, rows * (cell + 16) + 8), (40, 40, 44))
    d = ImageDraw.Draw(out)
    for i, n in enumerate(names):
        t = PAINTERS[n]().resize((16 * scale, 16 * scale), Image.NEAREST)
        x, y = 8 + (i % cols) * cell, 8 + (i // cols) * (cell + 16)
        out.paste(t, (x, y), t)
        d.text((x, y + 16 * scale + 4), n.split("/")[-1], fill=(230, 230, 230))
    out.save(path)
