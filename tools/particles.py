"""Particle textures the mod paints itself: (0.8.0) the volcanic smoke of the Basalt Galleries and the Wasteland, and
(0.9.0) the quakes' grit, the glow round a lava bomb, and the eruptions' lava gobs and fireballs (at the end of the file).

16 by 16 like the block textures, vanilla style: a short ramp of pale warm greys and alpha in a few hard steps, from a
thick core to a thin rim, no blur. The particle tints the puff (client/CaveParticles.VolcanicSmoke: a warm grey that
the lava's light under it turns orange under a shader), so the texture is drawn pale. The outline is lumpy, a few round
lobes on a core, so a column of them reads as billows rather than discs, and the corners stay clear: the quad turns
slowly as the puff rises. The particle picks one of the four at random.

The grit (client/CaveParticles.Grit) is a small stone: a lumpy pebble of a few mid greys, lit from the upper left, with a
hard edge and nothing translucent (it is drawn on the opaque sheet, which drops the clear pixels), pale enough to show
against the dark basalt under Cave Fog. Three of them.

The lava bomb's halo (client.render.LavaBombRenderer, textures/entity/lava_bomb/halo) is a round glow drawn as eyes are:
full-bright and added to whatever lies behind it, so black is see-through, and shader packs treat it as glowing. A small
hot yellow-white heart, deep orange round it, dying through a dark red to nothing before the square's edge; the alpha
falls with the light for packs that blend by alpha instead. Its colour is all in the texture, as the fireflies' is: the
shader's block light is warm, and the renderer only scales it. textures.py merges this file.
"""
import math, random
from PIL import Image

PAINTERS = {}

# pale to mid warm grey, and alpha from the core out to the rim
SHADES = [(238, 234, 228), (220, 215, 208), (199, 194, 187), (176, 171, 165)]
ALPHA = [255, 216, 156, 96]
# how far inside the outline (in pixels) each alpha step starts
DEPTHS = [2.6, 1.6, 0.8]
SEEDS = [6101, 6102, 6103, 6104]


def _puff(seed):
    rng = random.Random(seed)
    # a core and three to five lobes round it
    lobes = [(8 + rng.uniform(-0.6, 0.6), 8 + rng.uniform(-0.6, 0.6), rng.uniform(4.3, 5.0))]
    start = rng.random() * 2 * math.pi
    count = rng.randrange(3, 6)
    for k in range(count):
        a = start + k * 2 * math.pi / count + rng.uniform(-0.4, 0.4)
        d = rng.uniform(2.2, 3.1)
        lobes.append((8 + d * math.cos(a), 8 + d * math.sin(a), rng.uniform(2.5, 3.4)))
    grain = [[rng.random() for _ in range(16)] for _ in range(16)]
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            cx, cy = x + 0.5, y + 0.5
            if math.hypot(cx - 8, cy - 8) > 7.6:
                continue  # the corners stay clear
            depth = max(r - math.hypot(cx - lx, cy - ly) for lx, ly, r in lobes)
            if depth <= 0:
                continue
            step = next((i for i, d in enumerate(DEPTHS) if depth > d), len(DEPTHS))
            # paler inside, a little grain so the core is not flat
            v = min(1.0, max(0.0, step / len(DEPTHS) * 0.75 + (grain[y][x] - 0.5) * 0.5))
            shade = SHADES[min(len(SHADES) - 1, int(v * len(SHADES)))]
            px[x, y] = shade + (ALPHA[step],)
    return img


for i, seed in enumerate(SEEDS):
    PAINTERS[f"particle/volcanic_smoke_{i}"] = (lambda s=seed: _puff(s))


# the grit: light to dark stone greys, a little warm, and their seeds
GRIT_SHADES = [(196, 192, 186), (164, 160, 154), (134, 130, 125), (104, 101, 97)]
GRIT_SEEDS = [6201, 6202, 6203]


def _pebble(seed):
    rng = random.Random(seed)
    # a lumpy outline: a radius that wanders round the middle
    base = rng.uniform(4.2, 5.2)
    bumps = [(rng.uniform(0.4, 1.1), rng.randrange(2, 5), rng.random() * 2 * math.pi) for _ in range(2)]
    cx, cy = 8 + rng.uniform(-0.5, 0.5), 8 + rng.uniform(-0.5, 0.5)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            a = math.atan2(dy, dx)
            r = base + sum(h * math.sin(k * a + ph) for h, k, ph in bumps)
            if d > r:
                continue
            # light from the upper left, darker to the lower right and toward the rim, a little grain
            light = -(dx + dy) / (2 * r) * 0.6 + (1 - d / r) * 0.3 + (rng.random() - 0.5) * 0.35
            i = 1 if light > 0.15 else 2 if light > -0.12 else 3
            if light > 0.4: i = 0
            px[x, y] = GRIT_SHADES[i] + (255,)
    return img


for i, seed in enumerate(GRIT_SEEDS):
    PAINTERS[f"particle/grit_{i}"] = (lambda s=seed: _pebble(s))


# the bomb's halo: from its heart outwards, yellow-white, hot orange, deep orange, the dark red of its rim
HALO_SIZE = 32
HALO_RAMP = [(0.0, (255, 236, 170)), (0.15, (255, 176, 70)), (0.45, (240, 104, 28)), (0.75, (170, 44, 12)), (1.0, (90, 16, 6))]


def _halo_colour(t):
    for (t0, c0), (t1, c1) in zip(HALO_RAMP, HALO_RAMP[1:]):
        if t <= t1:
            k = (t - t0) / (t1 - t0)
            return tuple(c0[i] + (c1[i] - c0[i]) * k for i in range(3))
    return HALO_RAMP[-1][1]


def _bomb_halo():
    """A round orange glow, brightest in the middle (where the bomb lies over it), gone before the square's edge."""
    img = Image.new("RGBA", (HALO_SIZE, HALO_SIZE), (0, 0, 0, 0))
    px = img.load()
    mid = (HALO_SIZE - 1) / 2.0
    for y in range(HALO_SIZE):
        for x in range(HALO_SIZE):
            r = math.hypot(x - mid, y - mid) / (HALO_SIZE / 2.0)
            if r >= 1.0:
                continue
            light = min(1.0, 0.55 * math.exp(-(r / 0.22) ** 2) + 0.6 * math.exp(-(r / 0.5) ** 2) * (1.0 - r) ** 0.6)
            if light < 0.015:
                continue
            # quantized a little, as the mod's other textures are, so the glow reads as soft rings, not mush
            light = round(light * 24) / 24.0
            c = _halo_colour(min(1.0, r / 0.9))
            px[x, y] = tuple(int(round(v * light)) for v in c) + (int(round(255 * light)),)
    return img


PAINTERS["entity/lava_bomb/halo"] = _bomb_halo


# 0.9.0 (pack look fixes): the eruptions' lava gobs and fireballs.
#
# A lava gob (client/CaveParticles.LavaGob) is a clot of molten rock half a block to a block across, the body of a
# fountain: a lumpy round outline that fills most of the square (so the drawn size is the quad's), a white-yellow heart,
# yellow, orange and a deep red rim, with a few darker streaks of cooling skin in the orange so it reads as lava rather
# than a coloured disc. Hard-edged and opaque (the opaque sheet drops the clear pixels); drawn full-bright, and the
# particle reddens it as it cools. Four of them.
GOB_RAMP = [(0.0, (150, 38, 12)), (1.1, (226, 92, 24)), (2.2, (255, 158, 46)), (3.4, (255, 214, 96)), (4.6, (255, 246, 196))]
GOB_SKIN = (176, 58, 16)
GOB_SEEDS = [6301, 6302, 6303, 6304]


def _ramp(ramp, t):
    if t <= ramp[0][0]:
        return ramp[0][1]
    for (t0, c0), (t1, c1) in zip(ramp, ramp[1:]):
        if t <= t1:
            k = (t - t0) / (t1 - t0)
            return tuple(int(round(c0[i] + (c1[i] - c0[i]) * k)) for i in range(3))
    return ramp[-1][1]


def _gob(seed):
    rng = random.Random(seed)
    base = rng.uniform(6.0, 6.8)
    bumps = [(rng.uniform(0.3, 0.8), rng.randrange(3, 6), rng.random() * 2 * math.pi) for _ in range(2)]
    cx, cy = 8 + rng.uniform(-0.3, 0.3), 8 + rng.uniform(-0.3, 0.3)
    # the heart sits a little off the middle, as in a clot that is still turning over
    hx, hy = cx + rng.uniform(-0.8, 0.8), cy + rng.uniform(-0.8, 0.8)
    skin = set()
    for _ in range(rng.randrange(2, 4)):
        a = rng.random() * 2 * math.pi
        d = rng.uniform(3.0, 4.6)
        x0, y0 = cx + d * math.cos(a), cy + d * math.sin(a)
        t = a + math.pi / 2 + rng.uniform(-0.5, 0.5)
        for k in range(rng.randrange(2, 4)):
            skin.add((int(x0 + k * math.cos(t)), int(y0 + k * math.sin(t))))
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            a = math.atan2(dy, dx)
            r = min(7.6, base + sum(h * math.sin(k * a + ph) for h, k, ph in bumps))
            if d > r:
                continue
            # how deep inside: from the rim, and from the off-centre heart
            depth = min(r - d + 0.4, 5.2 - 0.9 * math.hypot(x + 0.5 - hx, y + 0.5 - hy)) + (rng.random() - 0.5) * 0.5
            c = _ramp(GOB_RAMP, depth)
            if (x, y) in skin and 1.0 < depth < 3.6:
                c = GOB_SKIN
            px[x, y] = c + (255,)
    return img


for i, seed in enumerate(GOB_SEEDS):
    PAINTERS[f"particle/lava_gob_{i}"] = (lambda s=seed: _gob(s))


# A fireball (client/CaveParticles.Fireball): the flash of fire at a vent's mouth and a bomb's blast. Shaped as the smoke
# puffs are (a core and lobes, corners clear, so a cluster reads as billows of flame), coloured from a white-yellow heart
# through orange to a deep red rim, and see-through toward its rim in the same hard alpha steps. Drawn full-bright on
# the translucent sheet; the particle reddens and thins it as it burns out.
FIRE_SHADES = [(140, 32, 10), (224, 84, 22), (255, 150, 48), (255, 218, 118)]
FIRE_ALPHA = [255, 224, 168, 104]
FIRE_SEEDS = [6401, 6402, 6403, 6404]


def _fireball(seed):
    rng = random.Random(seed)
    lobes = [(8 + rng.uniform(-0.6, 0.6), 8 + rng.uniform(-0.6, 0.6), rng.uniform(4.4, 5.0))]
    start = rng.random() * 2 * math.pi
    count = rng.randrange(4, 6)
    for k in range(count):
        a = start + k * 2 * math.pi / count + rng.uniform(-0.4, 0.4)
        d = rng.uniform(2.3, 3.2)
        lobes.append((8 + d * math.cos(a), 8 + d * math.sin(a), rng.uniform(2.6, 3.4)))
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            cx, cy = x + 0.5, y + 0.5
            if math.hypot(cx - 8, cy - 8) > 7.6:
                continue
            depth = max(r - math.hypot(cx - lx, cy - ly) for lx, ly, r in lobes)
            if depth <= 0:
                continue
            step = next((i for i, d in enumerate(DEPTHS) if depth > d), len(DEPTHS))
            v = min(1.0, max(0.0, (len(DEPTHS) - step) / len(DEPTHS) + (rng.random() - 0.5) * 0.3))
            shade = FIRE_SHADES[min(len(FIRE_SHADES) - 1, int(v * len(FIRE_SHADES)))]
            px[x, y] = shade + (FIRE_ALPHA[step],)
    return img


for i, seed in enumerate(FIRE_SEEDS):
    PAINTERS[f"particle/fireball_{i}"] = (lambda s=seed: _fireball(s))


# 0.9.0 (eruption effects, round 2): soft puffs for the dust and ash clouds, the eruption plume's billows, the flash of
# a blast and the vent's glow, and the rubble's chunks.
#
# The clouds (client/CaveParticles.Dust: a bomb's ash, a crashed block's rock dust) were drawn with the volcanic smoke's
# puffs, whose hard alpha steps show as flat square facets once a puff is blown up to a few blocks. A soft puff is the
# same lumpy outline of a core and lobes, but its alpha ramps down over the last two and a half pixels to its rim in
# fine steps, and its shade is mottled in blotches a few pixels across rather than per pixel, so many small ones build
# a cloud that reads as billowing dust rather than stacked panes. Pale, as the particle tints it.
SOFT_SHADES = [(255, 253, 250), (241, 238, 233), (226, 222, 216), (210, 206, 200), (194, 190, 184)]
SOFT_SEEDS = [6501, 6502, 6503, 6504]
# The eruption plume's ash (client/CaveParticles.EruptionAsh): each lobe light at its edge and dark at its heart, so a
# column of them, tinted a charcoal brown, shows lit billows with dark cores instead of a flat black shaft.
ASH_SHADES = [(92, 88, 84), (122, 117, 111), (156, 150, 142), (194, 187, 177), (230, 222, 211), (252, 246, 236)]
ASH_SEEDS = [6601, 6602, 6603, 6604]


def _lobes(rng):
    lobes = [(8 + rng.uniform(-0.5, 0.5), 8 + rng.uniform(-0.5, 0.5), rng.uniform(4.4, 5.0))]
    start = rng.random() * 2 * math.pi
    count = rng.randrange(4, 6)
    for k in range(count):
        a = start + k * 2 * math.pi / count + rng.uniform(-0.35, 0.35)
        d = rng.uniform(2.3, 3.0)
        lobes.append((8 + d * math.cos(a), 8 + d * math.sin(a), rng.uniform(2.6, 3.3)))
    return lobes


def _blotches(rng):
    """Value noise of blotches about four pixels across: a 5 x 5 grid of draws, smoothly blended."""
    g = [[rng.random() for _ in range(6)] for _ in range(6)]

    def at(x, y):
        fx, fy = x / 4.0, y / 4.0
        ix, iy = int(fx), int(fy)
        tx, ty = fx - ix, fy - iy
        tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
        a = g[iy][ix] + (g[iy][ix + 1] - g[iy][ix]) * tx
        b = g[iy + 1][ix] + (g[iy + 1][ix + 1] - g[iy + 1][ix]) * tx
        return a + (b - a) * ty
    return at


def _soft(seed, shades, shade_of):
    rng = random.Random(seed)
    lobes = _lobes(rng)
    blot = _blotches(rng)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            cx, cy = x + 0.5, y + 0.5
            if math.hypot(cx - 8, cy - 8) > 7.6:
                continue  # the corners stay clear
            depths = [r - math.hypot(cx - lx, cy - ly) for lx, ly, r in lobes]
            depth = max(depths)
            if depth <= 0:
                continue
            # the lobe in front here (the outer lobes lie over the core), and how deep inside it
            own = next(d for d in reversed(depths) if d > 0)
            n = blot(x, y)
            # alpha: a soft ramp over the outer 2.6 pixels in twelfths, a little thinner in the blotches' gaps
            a = min(1.0, depth / 2.6) ** 0.85 * (0.86 + 0.14 * n)
            a = round(a * 12) / 12.0
            if a <= 0:
                continue
            v = shade_of(depth, own, n, rng.random())
            shade = shades[max(0, min(len(shades) - 1, int(v * len(shades))))]
            px[x, y] = shade + (int(round(255 * a)),)
    return img


def _soft_puff(seed):
    # pale all through, a shade paler inside, mottled in blotches
    return _soft(seed, SOFT_SHADES, lambda depth, own, n, g: 0.15 + 0.35 * min(1.0, depth / 4.0) + 0.55 * n + (g - 0.5) * 0.15)


def _ash_puff(seed):
    # each lobe light at its own edge and darker inside it (a billow lit round its rim), the core lobe's heart darkest
    return _soft(seed, ASH_SHADES, lambda depth, own, n, g: 0.95 - 0.55 * min(1.0, max(0.0, own - 0.6) / 2.6)
                 - 0.15 * min(1.0, max(0.0, depth - 2.5) / 2.0) + (n - 0.5) * 0.3 + (g - 0.5) * 0.1)


for i, seed in enumerate(SOFT_SEEDS):
    PAINTERS[f"particle/soft_puff_{i}"] = (lambda s=seed: _soft_puff(s))
for i, seed in enumerate(ASH_SEEDS):
    PAINTERS[f"particle/ash_puff_{i}"] = (lambda s=seed: _ash_puff(s))


# A flash (client/CaveParticles.Flash): the blaze of a lava bomb's blast and of an eruption's first ticks, and the glow
# building at a vent's mouth while it warns. A round glow with a slightly ragged rim: a white-yellow heart, yellow,
# orange and a deep orange edge, its alpha falling with the light to nothing before the square's edge (the particle
# tints it deeper orange for the vent's glow). Full-bright on the translucent sheet. Two of them.
FLASH_RAMP = [(0.0, (255, 252, 236)), (0.22, (255, 236, 168)), (0.45, (255, 188, 92)), (0.7, (250, 128, 40)), (1.0, (214, 72, 18))]
FLASH_SEEDS = [6701, 6702]


def _flash(seed):
    rng = random.Random(seed)
    bumps = [(rng.uniform(0.03, 0.07), rng.randrange(5, 9), rng.random() * 2 * math.pi) for _ in range(2)]
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8, y + 0.5 - 8
            a = math.atan2(dy, dx)
            rim = 7.8 * (1 - sum(h * (1 + math.sin(k * a + ph)) for h, k, ph in bumps))
            r = math.hypot(dx, dy) / rim
            if r >= 1.0:
                continue
            light = min(1.0, 1.15 * (1 - r) ** 1.3 + 0.35 * math.exp(-(r / 0.3) ** 2))
            light = round(light * 14) / 14.0
            if light <= 0:
                continue
            c = _ramp([(t * 1.0, col) for t, col in FLASH_RAMP], min(1.0, r))
            px[x, y] = c + (int(round(255 * light)),)
    return img


for i, seed in enumerate(FLASH_SEEDS):
    PAINTERS[f"particle/flash_{i}"] = (lambda s=seed: _flash(s))


# Rubble (client/CaveParticles.Rubble): a broken chunk of rock a third to half a block across, thrown out by a blast or
# a crashed block, which lies on the floor a while. The block's own texture shrunk to a patch reads as a flat tile and
# vanishes on the same rock, so the chunk has its own sprite: an angular outline of five to seven corners, its faces
# lit from the upper left (a pale lit facet, a mid face, a shadowed face lower right), a darker crack or two and a dark
# rim pixel round it, all greys the particle tints with the colour of the struck block's own texture, lightened (fresh
# broken stone). Opaque (the opaque sheet drops the clear pixels). Four of them.
RUBBLE_SHADES = [(250, 248, 244), (214, 211, 206), (176, 173, 168), (138, 135, 131)]
RUBBLE_RIM = (92, 90, 87)
RUBBLE_SEEDS = [6801, 6802, 6803, 6804]


def _chunk(seed):
    rng = random.Random(seed)
    n = rng.randrange(5, 8)
    cx, cy = 8 + rng.uniform(-0.4, 0.4), 8 + rng.uniform(-0.4, 0.4)
    start = rng.random() * 2 * math.pi
    corners = []
    for k in range(n):
        a = start + k * 2 * math.pi / n + rng.uniform(-0.3, 0.3)
        r = rng.uniform(5.2, 6.8)
        corners.append((cx + r * math.cos(a), cy + r * math.sin(a)))

    def inside(px_, py_):
        hit = False
        for i in range(n):
            x1, y1 = corners[i]
            x2, y2 = corners[(i + 1) % n]
            if (y1 > py_) != (y2 > py_) and px_ < x1 + (py_ - y1) * (x2 - x1) / (y2 - y1):
                hit = not hit
        return hit
    # a ridge through the chunk splits its lit facet (upper left) from its shadowed face (lower right)
    ridge = rng.uniform(-1.2, 1.2)
    cracks = set()
    for _ in range(rng.randrange(1, 3)):
        a = rng.random() * 2 * math.pi
        x0, y0 = cx + rng.uniform(-2, 2), cy + rng.uniform(-2, 2)
        for k in range(rng.randrange(2, 4)):
            cracks.add((int(x0 + k * math.cos(a)), int(y0 + k * math.sin(a))))
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    filled = [[inside(x + 0.5, y + 0.5) for x in range(16)] for y in range(16)]
    for y in range(16):
        for x in range(16):
            if not filled[y][x]:
                continue
            edge = any(not (0 <= x + ex < 16 and 0 <= y + ey < 16 and filled[y + ey][x + ex])
                       for ex, ey in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            # which side of the ridge, and how far toward the light
            side = dx + dy - ridge
            if edge and side > 0:
                c = RUBBLE_RIM
            elif edge:
                c = RUBBLE_SHADES[0]
            elif (x, y) in cracks:
                c = RUBBLE_SHADES[3]
            else:
                g = (rng.random() - 0.5) * 0.6
                i = 0 if side < -3.5 + g else 1 if side < 0 + g else 2 if side < 3.5 + g else 3
                c = RUBBLE_SHADES[i]
            px[x, y] = c + (255,)
    return img


for i, seed in enumerate(RUBBLE_SEEDS):
    PAINTERS[f"particle/rubble_{i}"] = (lambda s=seed: _chunk(s))
