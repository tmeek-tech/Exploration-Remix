"""Particle textures the mod paints itself: (0.8.0) the volcanic smoke of the Basalt Galleries and the Wasteland, and
(0.9.0) the quakes' grit.

16 by 16 like the block textures, vanilla style: a short ramp of pale warm greys and alpha in a few hard steps, from a
thick core to a thin rim, no blur. The particle tints the puff (client/CaveParticles.VolcanicSmoke: a warm grey that
the lava's light under it turns orange under a shader), so the texture is drawn pale. The outline is lumpy, a few round
lobes on a core, so a column of them reads as billows rather than discs, and the corners stay clear: the quad turns
slowly as the puff rises. The particle picks one of the four at random.

The grit (client/CaveParticles.Grit) is a small stone: a lumpy pebble of a few mid greys, lit from the upper left, with a
hard edge and nothing translucent (it is drawn on the opaque sheet, which drops the clear pixels), pale enough to show
against the dark basalt under Cave Fog. Three of them. textures.py merges this file.
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
