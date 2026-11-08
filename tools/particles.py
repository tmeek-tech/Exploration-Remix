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
