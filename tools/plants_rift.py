"""The Fern Rift's silver band, 0.14.5: the rift redone by depth (Taran, 17 Nov: "Plants changing with depth is
pretty"). Green up top (vanilla's moss, ferns and glow berry vines), silver through the middle, light blue by the floor
(the Blue Reaches' glowbells, mushrooms, lichen and asurine). The silver band's plants, in place of the silver ferns:

  silver_grass[_2, _3]   pale tufts over the silver band's floors: blades from a dark foot to near-white tips, arching
                         out, the ones behind a shade darker (a cross model, like vanilla's grass)
  silver_moss[_2]        pale moss draping over the ledges' lips and down the walls: a mat along the top edge, and
                         strands hanging from it, wisps off their sides, paler towards their ends (a sheet like glow
                         lichen; on a lip its top edge is the ledge's)

Cool silver-greens a few shades apart, dark only at the foot, so they read pale against the deep moss and the dark rock.
"""
import random
from plants import blank, put, put_wrap, register

PAINTERS = {}

# dark foot to near-white tip
SILVER = ["#5F7468", "#768C7F", "#8EA496", "#A6BAAC", "#BECFC2", "#D6E2D8"]


def _silver_grass(v):
    """A clump drawn as vanilla's grass is: thin upright blades a pixel wide across the whole tile, of many heights, a
    few stepping a pixel aside as they rise, each a little lighter towards its tip, with a pixel's worth of noise in its
    shade so neighbours stand apart; the foot of the clump is dense and dark."""
    rng = random.Random(4100 + v)
    img = blank()
    px = img.load()
    for x0 in range(16):
        if rng.random() < (0.22, 0.12, 0.3)[v - 1]:
            continue
        mid = 1 - abs(x0 - 7.5) / 8.5
        top = 15 - int(round((5 + 9 * mid) * rng.uniform(0.6, 1.0)))
        back = rng.random() < 0.35
        x = x0
        for y in range(15, top - 1, -1):
            t = (15 - y) / max(1, 15 - top)
            k = 1 + 3.6 * t + rng.choice((-1, 0, 0, 0, 1)) - (1.2 if back else 0)
            if y == top:
                k = 5 if not back else 4
            put(px, x, y, SILVER[max(0, min(5, int(round(k))))])
            if t > 0.35 and rng.random() < 0.12:  # a step aside
                x += rng.choice((-1, 1))
    for y in (14, 15):  # the dense foot
        for x in range(16):
            if px[x, y][3] == 0 and rng.random() < 0.6:
                put(px, x, y, SILVER[rng.choice((0, 0, 1))])
    return img


# the moss: dark to light, its strands' ends the palest
MOSS = ["#566B5F", "#6B8174", "#84998B", "#9DB1A2", "#B7C8BA", "#CFDCD1"]


def _silver_moss(v):
    """Moss over a lip: a broken mat on rows 0 and 1, then thin strands down from it, swaying a pixel now and then,
    forking into wisps, paler towards their ends; gaps between them show the rock. Wrapped across, so a row of sheets
    joins up along a ledge."""
    rng = random.Random(5200 + v)
    img = blank()
    px = img.load()
    for x in range(16):
        deep = 1 + (rng.random() < 0.45)
        for y in range(deep):
            if rng.random() < 0.12:
                continue
            put(px, x, y, MOSS[rng.choice((1, 2, 2, 3))])
    starts = sorted(rng.sample(range(16), (7, 8)[v - 1]))
    for x0 in starts:
        length = rng.choice((5, 7, 9, 11, 13, 14))

        def strand(x, y, n, k0):
            for i in range(n):
                if y > 15:
                    return
                t = i / max(1, n - 1)
                k = min(5, k0 + (0 if t < 0.3 else 1 if t < 0.6 else 2 if t < 0.9 else 3))
                put_wrap(px, x, y, MOSS[k])
                if i > 1 and rng.random() < 0.22:
                    x += rng.choice((-1, 1))
                y += 1
                if 2 < i < n - 3 and rng.random() < 0.16:  # a fork: a short wisp off to one side
                    side = rng.choice((-1, 1))
                    strand(x + side, y, rng.randint(2, 4), min(5, k + 1))

        strand(x0, 1 + (rng.random() < 0.5), length, 1)
    return img


register(PAINTERS, "silver_grass", lambda: _silver_grass(1))
register(PAINTERS, "silver_grass_2", lambda: _silver_grass(2))
register(PAINTERS, "silver_grass_3", lambda: _silver_grass(3))
register(PAINTERS, "silver_moss", lambda: _silver_moss(1))
register(PAINTERS, "silver_moss_2", lambda: _silver_moss(2))
