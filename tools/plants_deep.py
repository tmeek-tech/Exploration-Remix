"""Plant and crystal textures of the Deep Gardens and the Basalt Galleries (vegetation rework, part B).

Same rules as tools/plants.py: 16 by 16, vanilla style, colours from Taran's pictures, drawn with the shared helpers
there (register a painter with plants.register(PAINTERS, name, painter, glow=[...]) to give it a glow overlay). No
green anywhere in the Deep Gardens: stalks and stems are brown or mauve. textures.py merges this file after plants.py,
so a painter here may repaint a plant of plants.py (wisteria, lavender flower, gold flower).

Vines keep their strands on the same columns in every look and draw their outline from the row alone, so any body
tile stacks on any other; what spans several rows stays off rows 0, 1, 14 and 15 of a body tile.
"""
import math, random
from PIL import Image
from plants import (hexc, grid, blank, register, put, put_wrap, stamp, sway, ball, bezier, frond, fern_fan, prisms,
                    speckle_sheet, recolour, _line)
import plants
from plants_upper import variants

PAINTERS = {}


# ------------------------------------------------------------------------------------------------ Lantern Lake
# lantern_lake.jpg: lilac and cream wisteria hanging from the limestone, the lilac racemes dense, the cream ones
# narrower with florets off their sides, both speckled light and dark; lantern pods; cream and lilac coral on the
# islands; fronds on the lake bed.
STALK = ("#6B5A48", "#4E4136")   # brown stalks in place of the green ones: nothing in the Deep Gardens is green
LILAC = ["#6F5590", "#8063A6", "#AA8ECC", "#CDB6E4", "#E8DCF3"]
CREAM = ["#9C7A35", "#B8913E", "#DEC37B", "#F9DA87", "#FFF5C8"]
# Racemes as (column, sway, phase, row where its cluster starts, half width at its widest, length of the florets
# standing off its sides): one cluster per strand
# in every 16 rows, the strands' clusters starting on different rows, so a long column reads as clusters hanging one
# below the other, as in the picture.
LILAC_RACEMES = [(2, 0.0, 0.0, 0, 3, 0), (8, 0.6, 1.4, 9, 3, 0), (13, 0.0, 0.0, 4, 3, 0)]
CREAM_RACEMES = [(4, 0.0, 0.0, 2, 1.4, 2), (11, 0.6, 2.2, 10, 1.4, 2)]
# Half width of a cluster down its length: widest a third of the way down, then narrowing to a point.
CLUSTER = [1.5, 2.2, 2.8, 3.0, 3.0, 2.8, 2.5, 2.1, 1.8, 1.4, 1.1, 0.8, 0.5]


def _racemes(ramp, racemes, tip, seed, ends=None, thin=None):
    """Hanging wisteria: on each strand a cluster of florets, speckled light and dark as in the picture (each floret a
    light pixel over a darker one, the left side lit and the right in shade, with gaps and the brown stalk showing),
    then a stretch of bare stalk with a few florets to the next. A cluster's outline depends on the row only, so the
    body looks stack in any order; they differ in their speckle and in `thin` (raceme, first row, last row), rows
    inside 2 to 13 where a cluster is missing florets. A tip's strands end at `ends`, each in a last small cluster."""
    rng = random.Random(seed)
    img = blank()
    px = img.load()
    for i, (x0, amp, ph, start, wide, side) in enumerate(racemes):
        xs = sway(x0, amp, ph)
        end = ends[i] if tip else 16
        for y in range(end):
            x = xs[y]
            u = (y - start) % 16
            if tip and y >= end - 6:  # the strand's last cluster, short and narrow
                h = [1.2, 1.6, 1.5, 1.1, 0.7, 0.4][y - (end - 6)]
            else:
                h = CLUSTER[u] * wide / 3.0 if u < len(CLUSTER) else 0.0
            if thin and thin[0] == i and thin[1] <= y <= thin[2]:
                h = min(h, 0.8)
            put(px, x, y, STALK[(y + i) % 2])
            hi = int(round(h))
            for dx in range(-hi, hi + 1):
                edge = abs(dx) + 0.5 > h
                if rng.random() < (0.35 if edge else 0.1):
                    continue
                top = (y + (dx & 1)) % 2 == 0
                t = (3.4 if top else 1.7) - 0.4 * dx - 0.08 * u + (rng.random() - 0.5) * 1.1
                put(px, x + dx, y, ramp[max(0, min(len(ramp) - 1, int(round(t))))])
            if side and u < len(CLUSTER) - 2 and not (tip and y >= end - 6):  # florets off the sides, alternately
                sgn = -1 if u % 4 < 2 else 1
                for k in range(1, side + 1):
                    if u % 2 == 0:
                        put(px, x + sgn * (hi + k), y, ramp[4] if sgn < 0 and k == side else ramp[3])
                    elif k == 1:
                        put(px, x + sgn * (hi + k), y, ramp[1])
            if hi == 0 and u >= len(CLUSTER) and (y + i) % 3 == 0:  # a lone floret on the bare stalk
                put(px, x + (1 if (y // 3 + i) % 2 else -1), y, ramp[3])
        if tip:  # the last floret
            put(px, xs[end - 1], end, ramp[1])
    return img


WISTERIA_LOOKS = {1: (4410, None), 2: (4420, (1, 4, 11)), 3: (4430, (0, 3, 9))}
for _k in (1, 2, 3):
    register(PAINTERS, variants("wisteria_plant", 3)[_k - 1],
             (lambda k=_k: _racemes(LILAC, LILAC_RACEMES, False, *WISTERIA_LOOKS[k])))
register(PAINTERS, "wisteria", lambda: _racemes(LILAC, LILAC_RACEMES, True, 4440, ends=[13, 10, 15]))
for _k in (1, 2):
    register(PAINTERS, variants("pale_wisteria_plant", 2)[_k - 1],
             (lambda k=_k: _racemes(CREAM, CREAM_RACEMES, False, 4510 + k, thin=None if k == 1 else (1, 3, 8))))
register(PAINTERS, "pale_wisteria", lambda: _racemes(CREAM, CREAM_RACEMES, True, 4530, ends=[11, 14]))


# Wisteria petals: lilac and cream petals fallen on the island tops under the wisteria; a carpet that tiles across the
# floor. Petals of one, two or four pixels, lit from the top left, spread evenly with the floor showing between them.
PETAL_SHAPES = [[(0, 0, 2)], [(0, 0, 2), (1, 0, 1)], [(0, 0, 2), (0, 1, 0)], [(0, 0, 3), (1, 0, 2), (0, 1, 1), (1, 1, 0)]]


def _petals(v):
    rng = random.Random(4600 + v)
    img = blank()
    px = img.load()
    lilac, cream = LILAC[1:], CREAM[1:]
    pals = {1: [lilac], 2: [cream], 3: [lilac, cream]}[v]
    spots = []
    for _ in range(600):  # up to 22 petals, at least three pixels apart (counted round the tile's edges)
        x, y = rng.randrange(16), rng.randrange(16)
        if len(spots) < 22 and all(min(abs(x - a), 16 - abs(x - a)) + min(abs(y - b), 16 - abs(y - b)) >= 3 for a, b in spots):
            spots.append((x, y))
    for k, (x, y) in enumerate(spots):
        pal = pals[k % len(pals)]
        for dx, dy, c in PETAL_SHAPES[rng.choice((0, 1, 1, 2, 3, 3))]:
            put_wrap(px, x + dx, y + dy, pal[c])
    return img


for _k in (1, 2, 3):
    register(PAINTERS, variants("wisteria_petals", 3)[_k - 1], (lambda k=_k: _petals(k)))

# The flowers on the islands keep their pictures; their green stalks turn brown.
register(PAINTERS, "lavender_flower", lambda: recolour(plants.lavender_flower(), {"#6E8740": STALK[0], "#526A2E": STALK[1]}))
register(PAINTERS, "gold_flower", lambda: recolour(plants.gold_flower(), {"#748A3E": "#7A5E3A", "#55682C": "#5A4428"}))


# Coral: lantern_lake.jpg (cream and lilac coral on the islands), ember_gardens.jpg (cream staghorn coral along the
# pool rims, lilac coral on the ledges). Each branch is a path of points from its foot up; a fork leaves a branch at
# one of its points. Drawn 1 pixel wide, darker towards the foot, the tips palest; a branch's left side catches the
# light where it is 2 pixels wide.
PALE_CORAL = ["#9C7A52", "#B8966C", "#CFB088", "#E2C8A2", "#F0DCBA", "#FBF0D8"]
LAVENDER_CORAL = ["#644A88", "#8466AE", "#A688CE", "#C4A8E6", "#E0D0F4"]


def _coral(ramp, branches, wide=()):
    """branches: point lists from the foot up. wide: (x, y) points drawn 2 pixels wide (the light side to the left)."""
    img = blank()
    px = img.load()
    n = len(ramp)
    for pts in branches:
        path = []
        for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
            seg = _line(x0, y0, x1, y1)
            path += seg if not path else seg[1:]
        for x, y in path:
            put(px, x, y, ramp[max(0, min(n - 2, int((15 - y) / 16 * (n - 1))))])
        tx, ty = path[-1]
        put(px, tx, ty, ramp[-1])
    for x, y in wide:
        c = max(1, min(n - 2, int((15 - y) / 16 * (n - 1)) + 1))
        put(px, x - 1, y, ramp[c])
    return img


# Staghorn: fingers rising from one foot, each forking near its top.
PALE_CORAL_1 = [[(7, 15), (6, 12), (4, 8), (3, 4), (3, 2)], [(4, 8), (5, 6), (6, 3)],
                [(8, 15), (9, 11), (9, 6), (8, 2)], [(9, 7), (11, 4), (11, 2)],
                [(8, 14), (11, 12), (13, 9), (14, 5)], [(13, 9), (12, 7), (12, 6)],
                [(7, 14), (4, 13), (2, 11), (1, 7)], [(2, 11), (0, 10)]]
# Short fingers in a row, as along the pool rims.
PALE_CORAL_2 = [[(6, 15), (3, 12), (2, 9), (2, 7)], [(3, 12), (4, 9), (5, 6)],
                [(7, 15), (7, 11), (6, 8), (7, 5)], [(7, 10), (9, 8), (9, 6)],
                [(8, 15), (10, 13), (12, 10), (12, 7)], [(12, 10), (14, 8), (14, 6)],
                [(8, 14), (11, 12), (11, 10)]]
LAVENDER_CORAL_2 = [[(7, 15), (6, 12), (4, 9), (2, 6), (2, 3)], [(4, 9), (5, 6), (4, 3), (5, 1)],
                    [(2, 6), (0, 4)], [(8, 15), (8, 11), (9, 7), (8, 4), (9, 1)], [(9, 7), (11, 5), (11, 3)],
                    [(8, 13), (11, 11), (13, 8), (14, 5)], [(13, 8), (15, 6)], [(11, 11), (12, 9)]]
register(PAINTERS, "pale_coral", lambda: _coral(PALE_CORAL, PALE_CORAL_1, wide=[(7, 15), (8, 15), (7, 14), (8, 13)]))
register(PAINTERS, "pale_coral_2", lambda: _coral(PALE_CORAL, PALE_CORAL_2, wide=[(7, 15), (8, 15), (7, 14)]))
register(PAINTERS, "lavender_coral_2", lambda: _coral(LAVENDER_CORAL, LAVENDER_CORAL_2, wide=[(7, 15), (8, 15), (8, 14)]))


# Lilac waterweed: ll_water.png, the fern-like fronds on the lake bed (teal in the picture; lilac here, because the
# Deep Gardens have no green). Upright fronds with short leaflets on alternate sides, darker at the foot.
WEED = ["#4A3460", "#664A80", "#8466A0", "#A788C0", "#C9B0DC"]
WATERWEED = {
    1: [((5.5, 15.5), (4, 12), (3, 7)), ((8.5, 15.5), (9, 11), (8, 5)), ((10.5, 15.5), (12, 13), (13, 9))],   # short
    2: [((6.5, 15.5), (5, 9), (4, 1)), ((8.5, 15.5), (10, 8), (11, 2)), ((7.5, 15.5), (3, 13), (1, 9))],  # tall
    3: [((7.5, 15.5), (8, 9), (7, 2)), ((5.5, 15.5), (3, 12), (2, 6)), ((10.5, 15.5), (12, 10), (13, 4))],  # branched
}


def _waterweed(v):
    img = fern_fan(WATERWEED[v], ramp=(WEED[2], WEED[3], WEED[4]), rachis=(WEED[0], WEED[1]))
    if v == 3:  # side fronds off the middle one
        px = img.load()
        for pts in (bezier((7.5, 9.5), (9, 7), (11, 5)), bezier((7.5, 12.5), (6, 10), (4, 9))):
            for i, (x, y) in enumerate(pts):
                put(px, x, y, WEED[1] if i < len(pts) - 1 else WEED[4])
    return img


for _k in (1, 2, 3):
    register(PAINTERS, variants("lilac_waterweed", 3)[_k - 1], (lambda k=_k: _waterweed(k)))


# Lantern weed: the lantern pods' underwater kin (lantern_lake.jpg), lighting the lake bed in place of sea pickles:
# brown stalks rising from a few leaves, each ending in a round glowing pod with a dark rim.
POD = {"o": "#946D2D", "y": "#C08A3A", "Y": "#EBBF67", "W": "#FFE29A"}
POD_GLOW = ["#EBBF67", "#FFE29A"]
POD_BIG = [".yy.",
           "yWYo",
           "yYYo",
           ".oo."]
POD_SMALL = [".y.",
             "yWo",
             ".o."]
# (pod, its top left corner, the bend of its stalk)
LANTERN_WEED = {
    1: [(POD_BIG, (2, 3), (5, 10)), (POD_SMALL, (10, 1), (10, 8)), (POD_BIG, (8, 7), (9, 12))],
    2: [(POD_BIG, (4, 1), (7, 8)), (POD_SMALL, (11, 5), (10, 11)), (POD_SMALL, (2, 8), (5, 12))],
}


def _lantern_weed(v):
    img = blank()
    px = img.load()
    for pod, (x, y), bend in LANTERN_WEED[v]:
        top = (x + len(pod[0]) / 2, y + len(pod) - 0.5)
        for i, (sx, sy) in enumerate(bezier((7.5 + (0.5 if x > 7 else -0.5), 15.5), bend, top, steps=40)):
            put(px, sx, sy, STALK[0] if i % 3 else STALK[1])
    for x, y, c in ((6, 15, STALK[1]), (5, 14, "#5A4048"), (4, 13, STALK[0]), (9, 15, STALK[1]), (10, 14, "#5A4048"),
                    (11, 13, STALK[0])):
        put(px, x, y, c)
    for pod, (x, y), _ in LANTERN_WEED[v]:
        stamp(px, x, y, pod, POD)
    return img


register(PAINTERS, "lantern_weed", lambda: _lantern_weed(1), glow=POD_GLOW)
register(PAINTERS, "lantern_weed_2", lambda: _lantern_weed(2), glow=POD_GLOW)


# ------------------------------------------------------------------------------------------------ Ember Gardens
# ember_gardens.jpg: ochre tufts and tall lilac sprays on the ledges (bottom left), rust-rose strands among the glowing
# gold vines, pale coral and glowing water along the pool rims, stacked orange shelves, gold specks in the walls.

# Ochre grass: the ochre tufts of the bottom left, three looks: blades, a leafy tuft, blades with cream seed heads.
OCHRE = ["#6C4310", "#80561F", "#9B6625", "#B87D32", "#CA914A", "#E2AE6E"]
# Blades as (foot column, top row, lean, curve).
OCHRE_BLADES = {
    1: [(3, 7, -2, -1.0), (5, 3, -1, 0.0), (6, 9, 1, -1.0), (7, 2, 0, 1.0), (9, 5, 1, 0.0), (10, 8, 2, 1.0),
        (12, 4, 2, 0.0), (8, 10, -2, 0.0)],
    3: [(4, 6, -2, 0.0), (6, 1, -1, 0.0), (8, 4, 1, 0.0), (9, 2, 1, 1.0), (11, 7, 2, 0.0), (7, 9, -1, -1.0)],
}


def _ochre_blades(v):
    img = blank()
    px = img.load()
    for x0, top, lean, curve in OCHRE_BLADES[v]:
        h = 15 - top
        for i in range(h + 1):
            t = i / max(1, h)
            x = x0 + int(round(lean * t + curve * t * t))
            c = OCHRE[min(5, int(t * 6))]
            put(px, x, 15 - i, c)
            if i < h * 0.45:  # blades are two pixels wide near the foot, the right one in shade
                put(px, x + 1, 15 - i, OCHRE[max(0, min(5, int(t * 6)) - 1)])
    if v == 3:  # cream seed heads (the cream tufts at the picture's bottom right)
        for x0, top, lean, curve in OCHRE_BLADES[v][:5]:
            x = x0 + lean + int(round(curve))
            stamp(px, x - 1, top - 1, [".W.", "WYw", ".w."], {"W": "#F6E7C8", "Y": "#E2C690", "w": "#C9A877"})
    return img


def _ochre_leaves():
    """A leafy tuft, as the bottom left of the picture: fronds with broad leaflets arching out of one foot."""
    return fern_fan([((7.5, 15.5), (5, 9), (2, 3)), ((8.5, 15.5), (10, 8), (12, 2)), ((7.5, 15.5), (3, 13), (0, 9)),
                     ((8.5, 15.5), (13, 12), (15, 8)), ((8.5, 15.5), (8, 10), (7, 4))],
                    ramp=(OCHRE[2], OCHRE[4], OCHRE[5]), rachis=(OCHRE[0], OCHRE[1]), max_len=3)


register(PAINTERS, "ochre_grass", lambda: _ochre_blades(1))
register(PAINTERS, "ochre_grass_2", _ochre_leaves)
register(PAINTERS, "ochre_grass_3", lambda: _ochre_blades(3))


# Lilac plume: the tall lilac sprays of the picture (left and right): sprays rising from one foot on mauve stems,
# each a dense band of florets most of its length, widest a little below its middle and pointed at its tip, lit from
# the top left. Both halves are cut from one 16 by 32 canvas.
PLUME = ["#4F3A66", "#6A5288", "#8870AA", "#A891C8", "#C9B6E2"]
PLUME_STEM = ["#4A3A3E", "#5A4048", "#6B5A48"]
# Sprays as (foot, bend, tip) on the canvas, the ones behind first.
PLUMES = {
    1: [((7.5, 31.5), (2, 27), (1, 16)), ((8.5, 31.5), (14, 27), (14, 17)), ((7.5, 31.5), (5, 19), (4, 4)),
        ((8.5, 31.5), (11, 20), (12, 8)), ((8.5, 31.5), (8, 16), (8, 1))],
    2: [((7.5, 31.5), (1, 24), (1, 11)), ((8.5, 31.5), (15, 25), (14, 13)), ((7.5, 31.5), (6, 18), (5, 2)),
        ((8.5, 31.5), (10, 18), (11, 4))],
}


def _plume(v):
    rng = random.Random(4800 + v)
    canvas = Image.new("RGBA", (16, 32), (0, 0, 0, 0))
    cpx = canvas.load()

    def cput(x, y, c):
        if 0 <= x < 16 and 0 <= y < 32:
            cpx[x, y] = hexc(c)
    for k, (base, ctrl, tip) in enumerate(PLUMES[v]):
        pts = bezier(base, ctrl, tip, steps=90)
        n = len(pts)
        back = k < 2
        for i, (x, y) in enumerate(pts[:int(n * 0.45)]):  # the bare stem
            cput(x, y, PLUME_STEM[min(2, i * 3 // max(1, int(n * 0.45)))])
        for i in range(int(n * 0.3), n):
            x, y = pts[i]
            t = (i - n * 0.3) / (n * 0.7)                   # 0 where the florets start, 1 at the tip
            w = 1.4 * math.sin(math.pi * min(1.0, 0.25 + t * 0.85)) ** 0.7
            for dx in range(-2, 3):
                if abs(dx) > w + 0.2 or (rng.random() < 0.14 and dx != 0):  # gaps, but never in the spray's spine
                    continue
                top = (y + dx) % 2 == 0                     # florets: a light pixel over a darker one
                c = 1.8 + (0.9 if top else -0.6) - 0.5 * dx + 1.0 * t - (0.7 if back else 0) + (rng.random() - 0.5) * 0.8
                cput(x + dx, y, PLUME[max(0, min(4, int(round(c))))])
        tx, ty = pts[-1]
        cput(tx, ty - 1, PLUME[4])
    return canvas


for _k in (1, 2):
    register(PAINTERS, variants("lilac_plume_top", 2)[_k - 1], (lambda k=_k: _plume(k).crop((0, 0, 16, 16))))
    register(PAINTERS, variants("lilac_plume_bottom", 2)[_k - 1], (lambda k=_k: _plume(k).crop((0, 16, 16, 32))))


# Ember anemone: the pools' glowing plant (the rims in the picture glow from under the water): wavy tentacles rising
# from a low mound, fanning out, glowing more towards their tips; the short ones behind are darker.
ANEMONE = ["#C1935C", "#DCAB6E", "#E6BD87", "#F5CD96", "#FCE0AB", "#FFF3CC"]
ANEMONE_GLOW = ["#F5CD96", "#FCE0AB", "#FFF3CC"]
ANEMONE_MOUND = {"a": "#8E6440", "b": "#A97A4B", "c": "#C1935C", "d": "#DCAB6E"}
# Tentacles as (foot column, top row, lean over their height, wave phase), short ones (behind) first.
TENTACLES = {
    1: [(6, 7, -2, 1.0), (10, 8, 2, 2.5), (8, 6, 0, 0.3), (5, 4, -4, 2.0), (7, 2, -1, 4.0), (9, 3, 2, 0.8),
        (11, 5, 4, 3.2)],
    2: [(7, 7, -2, 2.2), (9, 6, 2, 0.6), (5, 5, -3, 3.4), (8, 1, 0, 1.5), (10, 3, 3, 4.6), (6, 3, -1, 0.2)],
}


def _anemone(v):
    img = blank()
    px = img.load()
    tents = TENTACLES[v]
    for k, (x0, top, lean, phase) in enumerate(tents):
        back = 15 - top <= 8
        h = 12 - top
        for i in range(h + 1):
            y = 12 - i
            t = i / max(1, h)
            x = int(round(x0 + lean * t ** 1.4 + 0.7 * math.sin(phase + i * 0.9)))
            if back:
                c = ANEMONE[1] if t < 0.5 else ANEMONE[2] if t < 0.9 else ANEMONE[3]
            else:
                c = ANEMONE[2] if t < 0.3 else ANEMONE[3] if t < 0.65 else ANEMONE[4] if t < 0.95 else ANEMONE[5]
            put(px, x, y, c)
    stamp(px, 4, 12, ["..dddd..",
                      ".cddddc.",
                      "bccdccbb",
                      "abbcbbaa"], ANEMONE_MOUND)
    return img


register(PAINTERS, "ember_anemone", lambda: _anemone(1), glow=ANEMONE_GLOW)
register(PAINTERS, "ember_anemone_2", lambda: _anemone(2), glow=ANEMONE_GLOW)


# Rose tendrils: ember_gardens.jpg, the rust-rose strands among the gold vines: thin strands hung with small drooping
# leaves, now and then a rose bud. Leaves and buds that span rows stay on rows 2 to 12 of a body tile; the rows where
# tiles join get one-row leaves.
ROSE = ["#5A2810", "#723418", "#8A4626"]
ROSE_LEAF = [("#5E2C1A", "#844232", "#B0644C"), ("#6E3420", "#9A5040", "#C27A62")]
ROSE_BUD = {"r": "#B0504A", "R": "#E0887A", "k": "#7A3020"}
ROSE_STRANDS = [(3, 1.0, 0.4), (8, 1.0, 2.6), (13, 1.0, 4.9)]
ROSE_LEAF_DROOP = ["ml", "mm", "s."]


def _rose(v, tip):
    rng = random.Random(4700 + v + (50 if tip else 0))
    img = blank()
    px = img.load()
    ends = [rng.randrange(7, 14) for _ in ROSE_STRANDS] if tip else [16] * 3
    for i, s_ in enumerate(ROSE_STRANDS):
        xs = sway(*s_)
        for y in range(ends[i]):
            put(px, xs[y], y, ROSE[(y + i) % 3])
        for y in range((i + v) % 3, ends[i] - 1, 3):
            if rng.random() < 0.2:
                continue
            side = 1 if (y // 3 + i) % 2 else -1
            ramp = ROSE_LEAF[rng.randrange(2)]
            rows = ROSE_LEAF_DROOP if 2 <= y <= 11 else ["ml"]
            for dy, row in enumerate(rows):
                for dx, ch in enumerate(row):
                    if ch != ".":
                        put(px, xs[y] + side * (dx + 1), y + dy, ramp["sml".index(ch)])
        y = 4 + (i * 5 + v * 3) % 6  # a rose bud on the strand
        if y < ends[i] - 3:
            stamp(px, xs[y] - 1, y, ["Rr", "rk"], ROSE_BUD)
        if tip:
            stamp(px, xs[ends[i] - 1] - (1 if i % 2 else 0), ends[i], ["Rr", "rk"], ROSE_BUD)
    return img


register(PAINTERS, "rose_tendrils_plant", lambda: _rose(1, False))
register(PAINTERS, "rose_tendrils_plant_2", lambda: _rose(2, False))
register(PAINTERS, "rose_tendrils", lambda: _rose(1, True))


# A second look for the ember vines: the same strands (same seed), the glowing beads moved two rows down.
def _ember_vines_2():
    rng = random.Random(8821)
    img = blank()
    px = img.load()
    vine, vine_dark, leaf, bead, bright, dim = "#7A4A24", "#5E3818", "#9A6230", "#F7B046", "#FFE6A6", "#D9822A"
    for i, x0 in enumerate((2, 6, 10, 13)):
        x = x0
        for y in range(16):  # the same calls on rng as plants._beaded_vines, so the strands match
            if y % 4 == 3 and rng.random() < 0.5:
                x = max(1, min(14, x + rng.choice((-1, 1))))
            put(px, x, y, vine if (y + i) % 3 else vine_dark)
            if rng.random() < 0.22:
                put(px, x + rng.choice((-1, 1)), y, leaf)
            if (y + i * 3 + 2) % 5 == 2:
                bx = x + (1 if (y // 5 + i) % 2 else -1)
                put(px, bx, y, bright)
                put(px, bx, y + 1, bead)
                put(px, bx, y + 2, dim)
    return img


register(PAINTERS, "ember_vines_plant_2", _ember_vines_2, glow=["#F7B046", "#FFE6A6"])


# Amber shard: gold specks in the walls of the picture, grown into small clusters of amber crystal.
AMBER_SHARD = {"dark": "#83531D", "left": "#A36024", "right": "#E0A55C", "edge": "#F5B964", "tip": "#FFE0A0"}
AMBER_GLOW = ["#F5B964", "#FFE0A0"]


def _amber_shard(v):
    img = blank()
    px = img.load()
    prisms(px, [(3, 2, 6, -1), (6, 3, 9, 0), (11, 2, 7, 1)] if v == 1 else [(4, 3, 8, -1), (9, 3, 10, 0), (13, 2, 4, 1)],
           AMBER_SHARD)
    return img


register(PAINTERS, "amber_shard", lambda: _amber_shard(1), glow=AMBER_GLOW)
register(PAINTERS, "amber_shard_2", lambda: _amber_shard(2), glow=AMBER_GLOW)


def orange_shelf_fungus_2():
    """A second top for the orange shelf fungus, laid out like the first (rows 0 to 9 the top, row 0 the rim; rows 12
    to 15 the edge): its growth rings centred off to one side, and more pale flecks."""
    pal = {"A": "#7E361A", "B": "#A24A24", "C": "#C4642F", "D": "#DE8446", "E": "#F0A766", "F": "#F9CF95"}
    rows = []
    for y in range(16):
        row = ""
        for x in range(16):
            if y <= 9:
                if y == 0 or (y == 1 and x in (0, 15)):
                    row += "F"
                    continue
                d = math.hypot((x - 4.5) * 0.7, (y - 12.0) * 1.25)
                row += "AABACBDCEDFF"[max(0, min(11, int(d / 1.45)))]
            elif y >= 12:
                row += {12: "F", 13: "E", 14: "C", 15: "D"}[y]
            else:
                row += "."
        rows.append(row)
    img = grid(rows, pal)
    px = img.load()
    rng = random.Random(6611)
    for _ in range(20):
        x, y = rng.randrange(1, 15), rng.randrange(2, 9)
        r, g, b, a = px[x, y]
        px[x, y] = (min(255, r + 24), min(255, g + 18), min(255, b + 12), a)
    return img


register(PAINTERS, "orange_shelf_fungus_2", orange_shelf_fungus_2)


# ------------------------------------------------------------------------------------------------ Basalt Galleries
# basalt_galleries.jpg: basalt greys (#241C1B to #71605E), lava-lit rims and glowing cracks (#AD4A22 to #F9913A), gold
# flecks in the rock. Sparse growth that likes the heat.
CHAR = ["#241C1B", "#372B2A", "#564847", "#71605E"]
EMBER = ["#AD4A22", "#CF5C28", "#EC7937", "#F9913A", "#FFC266"]
EMBER_GLOW = ["#EC7937", "#F9913A", "#FFC266"]


# Cinder bloom: a charred stalk with a cup of glowing petals, cracked like embers (one bloom, or two small ones).
BLOOM_BIG = ["a.b.a",
             "abcba",
             "bcdcb",
             ".beb.",
             "..a.."]
BLOOM_SMALL = ["a.a",
               "bdb",
               ".a."]
BLOOM_COLOURS = {"a": EMBER[1], "b": EMBER[2], "c": EMBER[3], "d": EMBER[4], "e": EMBER[0]}
# (bloom, its top left corner, the stalk's foot column, the stalk's bend)
CINDER = {1: [(BLOOM_BIG, (5, 2), 7, (6, 10))],
          2: [(BLOOM_SMALL, (2, 6), 7, (5, 12)), (BLOOM_SMALL, (9, 2), 8, (8, 8))]}


def _cinder(v):
    img = blank()
    px = img.load()
    for bloom, (bx, by), foot, bend in CINDER[v]:
        top = (bx + len(bloom[0]) / 2, by + len(bloom) - 0.5)
        for i, (x, y) in enumerate(bezier((foot + 0.5, 15.5), bend, top, steps=40)):
            put(px, x, y, CHAR[1] if i % 3 else CHAR[2])
    # charred leaves at the foot, spiky
    stamp(px, 3, 11, ["c.......c.",
                      ".bc...cb..",
                      "..bbabb...",
                      "...aaa....",
                      "....a....."], {"a": CHAR[0], "b": CHAR[1], "c": CHAR[2]})
    for bloom, (bx, by), _, _ in CINDER[v]:
        stamp(px, bx, by, bloom, BLOOM_COLOURS)
    return img


register(PAINTERS, "cinder_bloom", lambda: _cinder(1), glow=EMBER_GLOW)
register(PAINTERS, "cinder_bloom_2", lambda: _cinder(2), glow=EMBER_GLOW)


# Ash fern: a grey fern, the colour of the basalt's ash, with embers caught on a few of its tips. Its fronds are solid
# feathers (leaflets on both sides at every step, shorter towards the tip), so they read against the rock.
ASH = ["#4E4240", "#71605E", "#8E7F7B", "#B3A7A2"]   # shade, leaflet, lit leaflet, pale edge
ASH_RACHIS = ("#372B2A", "#443836")
ASH_FERNS = {
    1: [((7.5, 15.5), (5, 9), (3, 2)), ((8.5, 15.5), (11, 9), (12, 2)), ((7.5, 15.5), (3, 13), (1, 9)),
        ((8.5, 15.5), (13, 13), (15, 10))],
    2: [((7.5, 15.5), (6, 9), (5, 1)), ((8.5, 15.5), (11, 10), (13, 4)), ((7.5, 15.5), (3, 12), (1, 6))],
}
ASH_EMBERS = {1: [0, 1], 2: [0, 2]}   # the fronds whose tips hold an ember


def _ash_fern(v):
    img = blank()
    px = img.load()
    for k, (base, ctrl, tip) in enumerate(ASH_FERNS[v]):
        pts = bezier(base, ctrl, tip, steps=60)
        n = len(pts)
        for i, (x, y) in enumerate(pts):
            put(px, x, y, ASH_RACHIS[0] if i < n / 2 else ASH_RACHIS[1])
        for i in range(int(n * 0.25), n - 1):
            x, y = pts[i]
            dx, dy = pts[i + 1][0] - x, pts[i + 1][1] - y
            t = i / n
            reach = 2 if t < 0.7 else 1
            for side in (-1, 1):
                ox, oy = -dy * side, dx * side
                for r in range(1, reach + 1):
                    upper = oy < 0 or (oy == 0 and ox < 0)       # the side facing up (or left) catches the light
                    c = ASH[3] if r == reach and upper else ASH[2] if upper else ASH[1] if r == 1 else ASH[0]
                    put(px, x + ox * r, y + oy * r + (1 if r == 2 else 0), c)
        tx, ty = pts[-1]
        put(px, tx, ty, EMBER[2] if k in ASH_EMBERS[v] else ASH[3])
    return img


register(PAINTERS, "ash_fern", lambda: _ash_fern(1), glow=[EMBER[2]])
register(PAINTERS, "ash_fern_2", lambda: _ash_fern(2), glow=[EMBER[2]])


# Smoulder lichen: a dark crust on the basalt with glowing cracks running through it; a sheet that tiles.
def _smoulder(v):
    rng = random.Random(4900 + v)
    img = speckle_sheet(4900 + v, 0, 1.1, 2.3, ["#241C1B", "#2D2525", "#372B2A"], coverage=0.46 if v == 1 else 0.38)
    px = img.load()
    for _ in range(4 if v == 1 else 3):  # cracks: short zigzags inside the crust, brightest in the middle
        x, y = rng.randrange(16), rng.randrange(16)
        dx = rng.choice((-1, 1))
        n = rng.randrange(4, 7)
        for k in range(n):
            if px[x % 16, y % 16][3]:
                put_wrap(px, x, y, EMBER[3] if 0 < k < n - 1 and k % 2 else EMBER[2] if 0 < k < n - 1 else EMBER[1])
            if rng.random() < 0.6:
                x += dx
            else:
                y += 1
    return img


register(PAINTERS, "smoulder_lichen", lambda: _smoulder(1), glow=[EMBER[2], EMBER[3]])
register(PAINTERS, "smoulder_lichen_2", lambda: _smoulder(2), glow=[EMBER[2], EMBER[3]])
