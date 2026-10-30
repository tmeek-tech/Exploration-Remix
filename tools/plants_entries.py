"""Plants of two entrances to the Upper Caves: the sinkholes under the plains (daylit pits over a pool) and the oasis
hollows under the deserts (sandstone hollows round a spring).

Same rules as tools/plants.py: 16 by 16, vanilla style, a short ramp of shades per material, hard pixels, transparent
background, drawn with the shared helpers there. A painter registered with glow=[colours] gets an overlay of those
pixels that glows in the dark.

The trailing grass is a vine: its strands keep the same columns in every variant (sway() repeats every 16 rows), so any
body tile stacks on any other and the tip continues the body from its row 0; loose blades and rootlets, which span
several rows, stay off rows 0, 1, 14 and 15 of a body tile. The fan palm is painted on one 16 by 32 canvas and cut in
two, so its halves join.
"""
import math
from PIL import Image
from plants import hexc, blank, register, put, stamp, sway, bezier
from plants_upper import variants

PAINTERS = {}


# ------------------------------------------------------------------------------------------------ Sinkholes
# Trailing grass: grass hanging over the rim of a daylit sinkhole, a curtain of long blades, pale green and
# yellow-green, with thin brown roots hanging out of the turf among them.
TRAIL = {"green": ["#4B6C2E", "#5F873A", "#79A246", "#95BE58", "#B6D67C"],     # behind, shade, mid, light, pale
         "yellow": ["#6E7B2C", "#8B9A35", "#A8B944", "#C4D25E", "#DDE384"]}
TRAIL_ROOT = ["#55402A", "#6E5235", "#8A6B47", "#A68A62"]                     # dark, mid, light, pale tip
# Strands as (column, sway amplitude, sway phase, kind, ramp). The phases put every strand on the same column at rows
# 14, 15 and 0, so the joins run straight down, and sway the strands nearly together, so they keep apart. A blade in
# front is two pixels wide (lit on its left), one behind is one pixel and darker, a root one pixel and brown.
TRAIL_STRANDS = [(2, 0.7, 0.6, "root", None), (4, 1.0, 0.4, "front", "green"), (8, 0.7, 0.6, "root", None),
                 (10, 0.7, 0.6, "back", "green"), (12, 1.0, 0.4, "front", "yellow")]
# The looks of the body, all on rows 2 to 13: a run of rows where a front blade catches the light, a run where it turns
# away, rootlets as (strand, row, side), and loose blades as (strand, top row, length, drift, ramp, in front): blades
# that come out from behind a strand and hang free, ending in a point.
TRAIL_BODY = {
    1: {"sheen": {1: (3, 3), 4: (8, 4)}, "dim": {1: (9, 2), 4: (3, 2)}, "rootlets": [(0, 3, 1), (2, 4, 1), (2, 10, -1)],
        "loose": [(1, 2, 9, 2, "yellow", False), (4, 4, 8, -2, "green", False), (3, 3, 7, 1, "yellow", True)]},
    2: {"sheen": {1: (8, 3), 4: (2, 3)}, "dim": {1: (4, 2), 4: (10, 2)}, "rootlets": [(0, 8, 1), (2, 6, 1)],
        "loose": [(1, 5, 8, -2, "green", False), (4, 2, 9, 1, "green", False), (1, 3, 6, -2, "yellow", True)]},
}
# The tips: the row each strand ends on, and loose blades as in the body. The blades narrow to a pale point; the roots
# end in a pale tip with a fork above it.
TRAIL_TIP = {
    1: {"ends": [9, 13, 15, 7, 11], "loose": [(1, 2, 8, 2, "yellow", False), (4, 3, 6, -2, "green", False)]},
    2: {"ends": [10, 12, 14, 13, 8], "loose": [(1, 3, 6, -2, "green", False), (4, 2, 10, 1, "green", False)]},
}


def _loose_blade(px, x, top, length, drift, ramp, front):
    """A free blade hanging from (x, top) for `length` rows, curving `drift` columns away, ending in a pale point."""
    for i in range(length):
        t = i / max(1, length - 1)
        if i == length - 1:
            c = ramp[4] if front else ramp[3]
        else:
            c = (ramp[3] if t > 0.5 else ramp[2]) if front else (ramp[2] if t > 0.5 else ramp[1])
        put(px, x + int(round(drift * t ** 1.6)), top + i, c)


def _trailing_grass(v, tip):
    img = blank()
    px = img.load()
    look = TRAIL_TIP[v] if tip else TRAIL_BODY[v]
    ends = look["ends"] if tip else [16] * len(TRAIL_STRANDS)
    paths = [sway(x0, amp, ph) for x0, amp, ph, _, _ in TRAIL_STRANDS]

    def loose(front):
        for i, top, length, drift, ramp, in_front in look["loose"]:
            if in_front == front:
                _loose_blade(px, paths[i][top], top, length, drift, TRAIL[ramp], front)
    loose(False)
    for i, (x0, amp, ph, kind, ramp) in enumerate(TRAIL_STRANDS):
        xs, end = paths[i], ends[i]
        if kind == "root":
            for y in range(end):
                put(px, xs[y], y, TRAIL_ROOT[0] if (y + i) % 4 == 0 else TRAIL_ROOT[1])
            if tip:  # a pale tip, and a fork a little above it, turning away from the next strand
                side = 1 if i == 0 else -1
                put(px, xs[end - 1], end - 1, TRAIL_ROOT[3])
                put(px, xs[end - 4] + side, end - 3, TRAIL_ROOT[2])
                put(px, xs[end - 4] + 2 * side, end - 2, TRAIL_ROOT[3])
            else:
                for j, y, side in look["rootlets"]:
                    if j == i:
                        put(px, xs[y] + side, y + 1, TRAIL_ROOT[2])
                        put(px, xs[y] + 2 * side, y + 2, TRAIL_ROOT[3])
            continue
        r = TRAIL[ramp]
        if kind == "back":
            for y in range(end):
                put(px, xs[y], y, r[0] if (y + i) % 5 else r[1])
            if tip:
                put(px, xs[end - 1], end - 1, r[2])
            continue
        s0, sn = (0, 0) if tip else look["sheen"].get(i, (0, 0))
        d0, dn = (0, 0) if tip else look["dim"].get(i, (0, 0))
        for y in range(end):
            lit, shade = r[3], r[2]
            if s0 <= y < s0 + sn:
                lit = r[4]
            if d0 <= y < d0 + dn:
                lit, shade = r[2], r[1]
            if tip and y >= end - 2:  # narrowing to the point
                put(px, xs[y], y, r[4] if y == end - 1 else r[3])
                continue
            put(px, xs[y], y, lit)
            put(px, xs[y] + 1, y, shade)
    loose(True)
    return img


for _k in (1, 2):
    register(PAINTERS, variants("trailing_grass_plant", 2)[_k - 1], (lambda k=_k: _trailing_grass(k, False)))
    register(PAINTERS, variants("trailing_grass", 2)[_k - 1], (lambda k=_k: _trailing_grass(k, True)))


# ------------------------------------------------------------------------------------------------ Oasis hollows
# Fan palm: a small palm standing in the sand of the oasis hollows, like a dwarf palmetto: a short trunk shaggy with
# brown fibre and the stubs of old fronds, and a crown of fan leaves on long stalks. Each fan is a spray of folded
# segments from the top of its stalk, webbed together near it and free towards their tips, alternately lit and shaded
# along their folds; the old fans low down droop, and some segments have dried yellow at their tips.
PALM_LEAF = ["#2A4C25", "#3A662D", "#4F8238", "#689E44", "#89BA58"]           # deep, dark, mid, light, pale
PALM_DRY = ["#A8933E", "#CDB95C"]                                              # yellowed tips: shaded, lit
PALM_STALK = ["#465F2A", "#5E7A36"]
PALM_TRUNK = {"D": "#35251A", "d": "#4C3522", "m": "#68492C", "l": "#86633A", "f": "#A7834F"}
# The trunk's bark, four columns wide: rows of old leaf bases, lighter, between darker fibre.
PALM_BARK = ["dlfd", "dmlD", "Ddmd", "dfld", "Dlmd", "dmdD"]


class _Canvas:
    """A 16 by h canvas that ignores pixels outside it."""

    def __init__(self, h):
        self.img = Image.new("RGBA", (16, h), (0, 0, 0, 0))
        self.px = self.img.load()
        self.h = h

    def put(self, x, y, c):
        if 0 <= x < 16 and 0 <= y < self.h:
            self.px[x, y] = hexc(c)


def _fan(cv, hx, hy, facing, spread, radius, rays, dry=(), squash=1.0, depth=0, web=0.45, droop=0.0):
    """One fan leaf from the top of its stalk (hx, hy), opening towards `facing` (degrees from upright, positive to the
    right) over `spread` degrees: `rays` folded segments, webbed together out to `web` of their length. squash narrows
    a fan seen from the side; depth darkens a fan further back; droop bends the free ends down; the segments listed in
    dry have yellowed tips."""
    f = math.radians(facing)
    ax, ay = math.sin(f), -math.cos(f)
    nx, ny = -ay, ax

    def shade(k):
        return PALM_LEAF[max(0, min(4, k - depth))]
    angles = [-spread / 2 + spread * k / (rays - 1) for k in range(rays)]
    lengths = [radius * (1.0 - 0.15 * abs(k - (rays - 1) / 2) / ((rays - 1) / 2)) for k in range(rays)]
    for y in range(cv.h):  # the web: each pixel the shade of its nearest segment
        for x in range(16):
            dx, dy = x + 0.5 - hx, y + 0.5 - hy
            along, across = dx * ax + dy * ay, (dx * nx + dy * ny) / squash
            r = math.hypot(along, across)
            a = math.degrees(math.atan2(across, along))
            if r > radius * web + 0.2 or abs(a) > spread / 2 + 6:
                continue
            k = min(range(rays), key=lambda j: abs(angles[j] - a))
            cv.put(x, y, shade(1 if r < 1.2 else 3 if k % 2 == 0 else 2))

    def point(ar, r, length):
        """Where a segment at angle ar (radians from the fan's middle) is at distance r; its free end droops."""
        along, across = r * math.cos(ar), r * math.sin(ar) * squash
        return (int(math.floor(hx + along * ax + across * nx)),
                int(math.floor(hy + along * ay + across * ny + droop * max(0.0, r - radius * web) ** 2 / length)))
    for k, a in enumerate(angles):
        ar = math.radians(a)
        length, lit, last = lengths[k], k % 2 == 0, None
        while length > 1.0:  # a segment that would reach the tile's side is shorter, so it ends inside the tile
            x, y = point(ar, length, lengths[k])
            if 1 <= x <= 14 and 0 <= y < cv.h:
                break
            length -= 0.25
        r0 = radius * web * 0.6
        n = int(length * 4)
        for i in range(n + 1):
            r = r0 + (length - r0) * i / n
            p = point(ar, r, lengths[k])
            if p == last:
                continue
            last = p
            t = r / length
            c = shade((3 if lit else 2) + (1 if t >= 0.8 else 0))
            if k in dry and t > 0.8:
                c = PALM_DRY[1] if lit else PALM_DRY[0]
            cv.put(p[0], p[1], c)


# Each look: the top row of the trunk, the stubs of old fronds on it as (column, row, side), the tip of the new leaf
# (still folded, a spike rising from the middle of the crown) if it shows, and the fans as (top of the stalk, facing,
# spread, length, segments, dry segments, squash, depth, droop, web). The fans further back are drawn first.
FAN_PALMS = {
    1: {"top": 17, "stubs": [(5, 19, -1), (10, 21, 1), (5, 24, -1), (10, 26, 1), (5, 29, -1)],
        "fans": [((4.5, 15.0), -110, 120, 4.5, 5, (4,), 0.85, 1, 0.8, 0.3),
                 ((11.5, 14.5), 110, 120, 4.5, 5, (0,), 0.85, 1, 0.8, 0.3),
                 ((5.5, 10.0), -40, 140, 5.0, 7, (3,), 1.0, 1, 0.1, 0.45),
                 ((10.5, 9.5), 40, 140, 5.0, 7, (4,), 1.0, 1, 0.1, 0.45),
                 ((8.5, 7.5), 8, 170, 7.0, 9, (1, 7), 1.0, 0, 0.0, 0.5)]},
    2: {"top": 19, "stubs": [(10, 21, 1), (5, 23, -1), (10, 26, 1), (5, 28, -1)], "spear": (7, 0),
        "fans": [((4.0, 18.0), -135, 110, 4.5, 5, (0, 1, 2), 0.7, 1, 1.2, 0.35),
                 ((12.0, 15.5), 110, 120, 4.0, 5, (4,), 0.7, 1, 0.8, 0.35),
                 ((5.5, 11.5), -50, 140, 5.0, 6, (3,), 1.0, 0, 0.3, 0.45),
                 ((10.0, 9.0), 25, 150, 6.0, 7, (2,), 1.0, 0, 0.0, 0.5)]},
}


def _fan_palm(v):
    spec = FAN_PALMS[v]
    cv = _Canvas(32)
    top, spear = spec["top"], spec.get("spear")
    for y in range(top, 32):  # the trunk, flaring at its foot
        left, right = (5, 10) if y == 31 else (6, 10) if y == 30 else (6, 9)
        for x in range(left, right + 1):
            if right - left == 3:
                ch = PALM_BARK[(y - top) % len(PALM_BARK)][x - 6]
            else:
                ch = "d" if x in (left, right) else "m"
            cv.put(x, y, PALM_TRUNK[ch])
    for x, y, side in spec["stubs"]:
        cv.put(x, y, PALM_TRUNK["l"])
        cv.put(x + side, y - 1, PALM_TRUNK["f"])
        cv.put(x + side, y, PALM_TRUNK["d"])
    crown = (7.5, top + 1.0)
    for (hx, hy), *_ in spec["fans"]:  # the stalks, from the top of the trunk up to the fans
        foot = (crown[0] + (0.5 if hx > 8 else 0), crown[1])
        pts = bezier(foot, ((7.5 + hx) / 2, (crown[1] + hy) / 2 + 1.5), (hx, hy), steps=60)
        for i, (x, y) in enumerate(pts):
            cv.put(x, y, PALM_STALK[0] if i < len(pts) * 0.4 else PALM_STALK[1])
    fans = sorted(spec["fans"], key=lambda f: -f[7])
    for (hx, hy), facing, spread, radius, rays, dry, squash, depth, droop, web in fans:
        if depth == 0 and spear:  # the new leaf, still folded, rising from the middle of the crown
            pts = bezier(crown, (crown[0], (crown[1] + spear[1]) / 2), (spear[0] + 0.5, spear[1] + 0.5), steps=60)
            for i, (x, y) in enumerate(pts):
                n = len(pts)
                cv.put(x, y, PALM_LEAF[4] if i >= n - 2 else PALM_LEAF[3] if i > n * 0.6 else PALM_STALK[1])
            spear = None
        _fan(cv, hx, hy, facing, spread, radius, rays, dry, squash, depth, web, droop)
    return cv.img


for _k in (1, 2):
    register(PAINTERS, variants("fan_palm_top", 2)[_k - 1], (lambda k=_k: _fan_palm(k).crop((0, 0, 16, 16))))
    register(PAINTERS, variants("fan_palm_bottom", 2)[_k - 1], (lambda k=_k: _fan_palm(k).crop((0, 16, 16, 32))))


# Oasis grass: tufts of fresh green grass by the springs, a few yellow-green blades among them, and small seed heads
# on thin stems above them. Like vanilla's grass the blades stand nearly upright and end at uneven heights; the outer
# ones lean and curve out, so the tuft is narrow at its foot. The blades behind are a shade darker.
OASIS = {"green": ["#2F6A27", "#3F8430", "#549E3A", "#6FB747", "#92CE5E"],    # deep, shade, mid, light, pale
         "yellow": ["#5E7A26", "#7F9A34", "#9DB542", "#BCCD5C", "#D6DE80"]}
SEED = {"S": "#EDE2A8", "s": "#CDBC76", "d": "#A18F50"}                       # pale, straw, shade
# A seed head: a small spike, lit on its left, with a spikelet off one side.
SEED_HEAD = ["S.",
             "Ss",
             "sd",
             "d."]
# Blades as (foot column, top row, lean, curve, ramp, behind); seed heads as (stem foot column, row of the head's
# top, lean of the stem). The blades behind are drawn first.
OASIS_TUFTS = {
    1: {"blades": [(6, 4, -1, 0.0, "green", 1), (9, 3, 1, 0.0, "green", 1), (7, 7, 0, 0.0, "yellow", 1),
                   (4, 9, 0, -3.0, "green", 0), (5, 5, -1, -2.0, "green", 0), (6, 8, -1, 0.0, "yellow", 0),
                   (7, 1, 0, -1.0, "green", 0), (8, 6, 0, 0.0, "green", 0), (9, 8, 1, 0.0, "green", 0),
                   (10, 4, 1, 2.0, "yellow", 0), (11, 9, 0, 3.0, "green", 0), (8, 10, 1, 0.0, "green", 0)],
        "seeds": [(8, 0, 2)]},
    2: {"blades": [(6, 5, -1, -1.0, "green", 1), (9, 6, 1, 1.0, "yellow", 1), (8, 2, 1, 0.0, "green", 1),
                   (4, 10, 0, -3.0, "yellow", 0), (5, 7, -1, -3.0, "green", 0), (7, 3, -1, -1.0, "green", 0),
                   (7, 9, 0, 0.0, "green", 0), (9, 4, 1, 1.0, "green", 0), (10, 8, 1, 3.0, "green", 0),
                   (11, 11, 1, 2.0, "yellow", 0), (8, 11, 0, 0.0, "green", 0)],
        "seeds": [(6, 1, -2), (10, 3, 2)]},
    3: {"blades": [(7, 6, -1, 0.0, "green", 1), (9, 7, 1, 0.0, "green", 1),
                   (5, 9, -1, -3.0, "green", 0), (6, 6, -1, -2.0, "yellow", 0), (7, 9, 0, 0.0, "green", 0),
                   (8, 5, 0, 0.0, "green", 0), (9, 10, 1, 0.0, "yellow", 0), (10, 7, 1, 3.0, "green", 0),
                   (8, 11, -1, 0.0, "green", 0)],
        "seeds": [(7, 0, -1), (9, 2, 2), (6, 4, -3)]},
}


def _oasis_grass(v):
    img = blank()
    px = img.load()
    spec = OASIS_TUFTS[v]
    for x0, top, lean in spec["seeds"]:  # thin stems up to the seed heads, behind the blades
        h = 15 - (top + len(SEED_HEAD))
        for i in range(h + 1):
            t = i / max(1, h)
            put(px, x0 + int(round(lean * t * t)), 15 - i, OASIS["yellow"][2] if t < 0.5 else OASIS["yellow"][3])
        stamp(px, x0 + lean, top, SEED_HEAD, SEED)
    for x0, top, lean, curve, ramp, behind in sorted(spec["blades"], key=lambda b: -b[5]):
        r = OASIS[ramp]
        h = 15 - top
        for i in range(h + 1):
            t = i / max(1, h)
            x = x0 + int(round(lean * t + curve * t * t))
            k = 4 if i == h else 3 if t > 0.55 else 2 if t > 0.2 else 1
            put(px, x, 15 - i, r[k - behind])
            if t < 0.25 and not behind:  # two pixels wide near the foot, the right one in shade
                put(px, x + 1, 15 - i, r[max(0, k - 1)])
    return img


for _k in (1, 2, 3):
    register(PAINTERS, variants("oasis_grass", 3)[_k - 1], (lambda k=_k: _oasis_grass(k)))


# Dune lily: a pale lily of the oasis sands: long narrow grey-green leaves arching out of one root, and one or two
# trumpets on tall stems, pale cream along the tube and glowing from inside at the mouth. Under the shader a glowing
# pixel shines at about 2.5 times its luminance, so the glowing petals are a saturated apricot and amber (a paler colour
# would clip to white), with a hotter heart on a few pixels; the darker edges of the petals, the shadow in the throat
# and the pale tube do not glow, so the flower keeps its shape when it is lit.
LILY_LEAF = ["#4E6656", "#66806C", "#84A08A", "#A3BBA6"]                      # shade, mid, light, pale edge
LILY_STEM = ["#5B7462", "#73907A"]
LILY = {"a": "#E8893A", "p": "#F2A65A", "h": "#FFC37A",                       # glowing: amber, apricot, heart
        "e": "#C4703A", "t": "#94502A",                                         # petal edges, throat
        "w": "#EEDCB6", "c": "#C8B48E", "g": "#7C9478"}                        # pale tube, its shade, stem's end
LILY_GLOW = ["#E8893A", "#F2A65A", "#FFC37A"]
# Trumpets open to the right (mirrored for the left) and join their stem at 'g': a small one, a big one, and a bud.
TRUMPET = ["....ep.",
           "..wppa.",
           "gwwwhte",
           "..capa.",
           "....ee."]
TRUMPET_BIG = [".....pe.",
               "....epp.",
               "..wwpah.",
               "gwwwwhte",
               "..ccapa.",
               "....eap.",
               ".....ee."]
BUD = [".p",
       "wa",
       "wc",
       "cg",
       "g."]
# Leaves as (foot, bend, tip); flowers as (stem foot, stem bend, the stem's end, kind, top left corner of the flower).
DUNE_LILIES = {
    1: {"leaves": [((7.5, 15.5), (3, 8), (1.5, 13.5)), ((8.5, 15.5), (13, 8), (14.5, 14.5)),
                   ((7.5, 15.5), (4, 6), (1.5, 9.5)), ((8.5, 15.5), (12, 5), (14.5, 9.5)),
                   ((7.5, 15.5), (6, 9), (4.5, 11.5)), ((8.5, 15.5), (10, 9), (11.5, 11.5))],
        "flowers": [((7.5, 15.5), (7, 4), (7, 3), "left", (1, 1)), ((8.5, 15.5), (8, 8), (8, 7), "right", (8, 5))]},
    2: {"leaves": [((7.5, 15.5), (3.5, 7), (1.5, 12.5)), ((8.5, 15.5), (12.5, 9), (14.5, 13.5)),
                   ((7.5, 15.5), (5, 7), (2.5, 10.5)), ((8.5, 15.5), (11, 6), (13.5, 10.5)),
                   ((8.5, 15.5), (10, 10), (11.5, 12.5))],
        "flowers": [((8.0, 15.5), (5, 5), (6, 4), "big", (6, 1)), ((7.5, 15.5), (4, 10), (3, 7), "bud", (2, 3))]},
}


def _strap(px, base, bend, tip, ramp):
    """A long narrow leaf along a curve: two pixels wide over its lower half (the underside in shade), lit along its
    middle, pale at its tip."""
    pts = bezier(base, bend, tip, steps=60)
    n = len(pts)
    for i, (x, y) in enumerate(pts):
        t = i / max(1, n - 1)
        if t < 0.5:
            put(px, x, y + 1, ramp[0])
    for i, (x, y) in enumerate(pts):
        t = i / max(1, n - 1)
        put(px, x, y, ramp[3] if i == n - 1 else ramp[2] if 0.25 < t < 0.8 else ramp[1])


def _dune_lily(v):
    img = blank()
    px = img.load()
    spec = DUNE_LILIES[v]
    for foot, bend, hang, kind, corner in spec["flowers"]:
        for i, (x, y) in enumerate(bezier(foot, bend, (hang[0] + 0.5, hang[1] + 0.5), steps=50)):
            put(px, x, y, LILY_STEM[0] if i % 3 else LILY_STEM[1])
    for base, bend, tip in spec["leaves"]:
        _strap(px, base, bend, tip, LILY_LEAF)
    for foot, bend, hang, kind, (cx, cy) in spec["flowers"]:
        rows = {"right": TRUMPET, "left": [r[::-1] for r in TRUMPET], "big": TRUMPET_BIG, "bud": BUD}[kind]
        stamp(px, cx, cy, rows, LILY)
    return img


register(PAINTERS, "dune_lily", lambda: _dune_lily(1), glow=LILY_GLOW)
register(PAINTERS, "dune_lily_2", lambda: _dune_lily(2), glow=LILY_GLOW)
