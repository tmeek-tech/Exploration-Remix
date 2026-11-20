"""The Deep Gardens' plant life (0.20.2; Taran, 2026-11-20: "Can you add some atmospheric grass and plant life to the
Deep Gardens?"), painted in the vent field's neon on black. textures.py's look rules hold for every texture here
(cool_problems checks each one): nothing warm, no cyan or teal, lime only in the lime textures. Every glowing pixel is
one of a neon's mid, neon or hot shades (textures.GLOWING) or the seep crust's faint light blue, lifted into the
texture's _glow overlay; the plants give no light of their own (card pick: "Glowing parts, no extra light"), so the
overlay is all that shines and the mats and vents stay the layer's lights.

  filament grass    dark filaments ending in glowing tips (card pick: "Dark blades, glowing tips"), in the colour of
                    the mat under it, in three heights (the tuft, the grass and the tall grass, two blocks high) and the
                    cropped stubble a stilt mantis leaves, whose cut ends have lost their glow
  tube worms        pale ringed tubes round the live vents, with feathery plumes in the vent's colour (a 3D model:
                    boxes for the tubes, crossed planes for the plumes, which go when the worms pull in)
  glowworm threads  silk threads hanging from the dark halls' ceilings, beaded with tiny light blue lights
  seep reeds        slender near-black reeds along the oil seeps, their seed heads beaded faint light blue like the seep
                    crust, one or two blocks tall
  neon mushrooms    little mushrooms in their giant's colour round its stem (a 3D model): pale stems under caps of the
                    giant's soft skin and bold glowing spots (glow_cap_looks' look A)
  spores            the motes that drift up off the grass and mushrooms (textures/particle, tinted by the particle)

Each painter draws one pattern from a fixed seed as shade names and colours it, so a plant's four colours share one
pattern. The grass is drawn with the filaments of tools/scratch grass_looks.py's look A, the one shown to Taran.
"""
import math
import random

from PIL import Image

NEONS = ["magenta", "violet", "lime", "light_blue"]
GLOWING = ("mid", "neon", "hot")


def _T():
    import textures  # here, not at the top: textures imports this module while it loads
    return textures


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def _hex(c):
    return "#%02X%02X%02X" % tuple(c[:3])


def shades(neon):
    return _T().neon_shades(neon)


def glow_hexes(neon):
    s = shades(neon)
    return {_hex(s[k]) for k in GLOWING}


class Canvas:
    """A clear texture w by h, drawn by shade name or colour; pixels outside are dropped."""

    def __init__(self, h=16, w=16):
        self.w, self.h = w, h
        self.img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        self.px = self.img.load()

    def put(self, x, y, c, over=True):
        if 0 <= x < self.w and 0 <= y < self.h:
            if not over and self.px[x, y][3]:
                return
            self.px[x, y] = tuple(c[:3]) + (255,)

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.px[x, y]
        return (0, 0, 0, 0)

    def crop(self, box):
        return self.img.crop(box)


# =============================================================================================== filament grass
# Each filament is one pixel a row from its foot to its tip: a quadratic lean with a bow. Near-black, cool stems that
# lighten a step or two up their length, then the neon: a deep and a dim pixel lit by the world, and the glowing top,
# mid (on the longer ones), neon and a hot tip. A few fork, with a short side shoot and a tip of its own.

STEM = [hexc(c) for c in ("#110F17", "#17141E", "#1D1925", "#241F2D", "#2C2636", "#352E41")]


def _path(x0, y0, top, lean, bend):
    pts = []
    n = y0 - top
    for k in range(n + 1):
        t = k / max(1, n)
        x = x0 + lean * t * t + bend * 2 * t * (1 - t)
        pts.append((int(round(x)), y0 - k))
    return pts


def _mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def filament(cv, s, x0, top, lean, bend, glow=2, fork=None, y0=None, shade_bias=0, cut=False):
    """A filament from (x0, y0) (the bottom row by default) up to row `top`. glow 3 lights a mid pixel under the neon.
    fork = (pixels under the tip, side, length): a side shoot. cut: the stubble's, cropped with its glow eaten, its cut
    end the neon's deep shade."""
    y0 = cv.h - 1 if y0 is None else y0
    pts = _path(x0, y0, top, lean, bend)
    n = len(pts)
    for i, (x, y) in enumerate(pts):
        t = i / max(1, n - 1)
        dn = n - 1 - i  # pixels under the tip
        base = STEM[min(len(STEM) - 1, max(0, int(t * 4.2) + shade_bias + ((x + y) % 3 == 0)))]
        if cut:
            c = s["deep"] if dn == 0 else (_mix(base, s["deep"], 0.45) if dn == 1 else base)
        elif dn == 0:
            c = s["hot"]
        elif dn == 1:
            c = s["neon"]
        elif dn == 2 and glow >= 3:
            c = s["mid"]
        elif dn <= glow:
            c = s["dim"]
        elif dn <= glow + 3:
            c = _mix(base, s["deep"], {1: 0.8, 2: 0.55, 3: 0.3}[dn - glow])
        else:
            c = base
        cv.put(x, y, c)
    if fork and not cut:
        k = max(0, n - 1 - fork[0])
        fx, fy = pts[k]
        for j in range(1, fork[2] + 1):
            cv.put(fx + fork[1] * j, fy - j, s["neon"] if j == fork[2] else s["dim"])
    return pts


# Plans: (x0, top, lean, bend, glow, fork), hand-tuned so the filaments fan out and their tips stand at mixed heights
# (the looks Taran was shown). Drawn tallest first, so the short ones' tips lie over the tall ones' stems.
TUFT = [
    [(2, 11, -1, 0.0, 2, None), (4, 9, -1, 0.5, 2, None), (6, 7, 0, 0.5, 2, None), (7, 12, 0, 0.0, 2, None),
     (9, 9, 1, -0.5, 2, None), (10, 6, 2, 0.5, 3, None), (12, 10, 2, 0.0, 2, None), (13, 12, 1, 0.0, 2, None),
     (5, 12, -2, 0.0, 2, None)],
    [(3, 10, -2, 0.0, 2, None), (5, 12, -1, 0.0, 2, None), (6, 8, -1, 0.5, 2, None), (8, 6, 0, -0.5, 3, None),
     (9, 11, 1, 0.0, 2, None), (11, 8, 2, -0.5, 2, None), (12, 12, 2, 0.0, 2, None)],
]
GRASS = [
    [(1, 9, -1, 0.0, 2, None), (3, 5, -1, 1.0, 3, (4, -1, 2)), (4, 11, -1, 0.0, 2, None), (6, 8, 0, 0.0, 2, None),
     (7, 2, 1, -1.0, 3, None), (8, 12, 0, 0.0, 2, None), (9, 6, 1, 0.5, 2, (3, 1, 2)), (10, 10, 2, 0.0, 2, None),
     (11, 3, 2, -0.5, 3, None), (12, 8, 2, 0.0, 2, None), (13, 11, 2, 0.0, 2, None), (5, 13, -2, 0.0, 2, None),
     (14, 13, 1, 0.0, 2, None)],
    [(2, 7, -2, 0.0, 3, None), (3, 12, -1, 0.0, 2, None), (5, 9, -1, 0.0, 2, None), (6, 3, 0, 1.0, 3, (5, -1, 2)),
     (7, 11, 0, 0.0, 2, None), (8, 6, 0, 0.0, 2, None), (9, 1, 2, -1.0, 3, None), (10, 12, 1, 0.0, 2, None),
     (11, 8, 2, 0.5, 2, None), (12, 4, 1, 0.0, 3, (3, 1, 2)), (13, 10, 2, 0.0, 2, None), (14, 13, 1, 0.0, 2, None)],
]
# the tall grass's plan, on a canvas two blocks high (rows 0-31): its lower half is rows 16-31, its upper half 0-15
TALL = [
    [(2, 22, -1, 0.0, 2, None), (3, 9, -2, 1.0, 3, (5, 1, 2)), (5, 16, -1, 0.0, 2, None), (6, 2, 0, 1.5, 3, None),
     (7, 25, 0, 0.0, 2, None), (8, 12, 1, -1.0, 3, (4, -1, 2)), (9, 5, 2, -1.0, 3, None), (10, 19, 1, 0.0, 2, None),
     (11, 8, 2, 0.5, 3, (6, 1, 2)), (12, 14, 2, 0.0, 2, None), (13, 23, 2, 0.0, 2, None), (4, 27, -2, 0.0, 2, None),
     (9, 28, 0, 0.0, 2, None)],
    [(1, 24, -1, 0.0, 2, None), (2, 13, -1, 0.5, 3, None), (4, 4, -1, 1.0, 3, (6, -1, 2)), (5, 20, 0, 0.0, 2, None),
     (6, 10, 0, -0.5, 3, None), (7, 27, -1, 0.0, 2, None), (8, 1, 1, -1.5, 3, (5, 1, 2)), (9, 17, 1, 0.0, 2, None),
     (10, 7, 2, 0.5, 3, None), (11, 23, 1, 0.0, 2, None), (12, 12, 2, 0.0, 3, (4, 1, 2)), (13, 26, 2, 0.0, 2, None),
     (14, 18, 1, 0.0, 2, None)],
]
# The stubble a stilt mantis leaves: cropped stems two to five pixels high, their cut ends the neon's deep shade (the
# glow eaten), and a few new shoots coming back with dim and mid tips.
STUBBLE = [
    {"cut": [(1, 12, 0, 0.0), (3, 11, -1, 0.0), (4, 13, 0, 0.0), (6, 10, 0, 0.0), (8, 12, 1, 0.0), (9, 11, 0, 0.0),
             (11, 13, 1, 0.0), (12, 10, 1, 0.0), (14, 12, 1, 0.0)],
     "shoot": [(5, 9, -1, 0.5), (10, 8, 1, -0.5)]},
    {"cut": [(2, 11, -1, 0.0), (3, 13, 0, 0.0), (5, 12, 0, 0.0), (7, 10, 0, 0.0), (8, 13, 0, 0.0), (10, 11, 1, 0.0),
             (12, 12, 1, 0.0), (13, 10, 1, 0.0)],
     "shoot": [(4, 8, -1, 0.0), (9, 9, 0, 0.5), (12, 7, 1, 0.0)]},
]


def _mirror(plan):
    return [(15 - x0, top, -lean, -bend, glow, (fork[0], -fork[1], fork[2]) if fork else None)
            for (x0, top, lean, bend, glow, fork) in plan]


def grass_canvas(plan, neon, h=16):
    cv = Canvas(h)
    s = shades(neon)
    for (x0, top, lean, bend, glow, fork) in sorted(plan, key=lambda p: p[1]):
        filament(cv, s, x0, top, lean, bend, glow, fork)
    return cv


def tuft(v, neon):
    return grass_canvas(TUFT[v], neon).img


def grass(v, neon):
    return grass_canvas(GRASS[v], neon).img


def tall(v, neon, half):
    cv = grass_canvas(TALL[v], neon, h=32)
    return cv.crop((0, 0, 16, 16) if half == "top" else (0, 16, 16, 32))


def stubble(v, neon):
    cv = Canvas()
    s = shades(neon)
    plan = STUBBLE[v]
    for (x0, top, lean, bend) in plan["cut"]:
        filament(cv, s, x0, top, lean, bend, cut=True, shade_bias=-1)
    for (x0, top, lean, bend) in plan["shoot"]:
        pts = _path(x0, 15, top, lean, bend)
        for i, (x, y) in enumerate(pts):
            dn = len(pts) - 1 - i
            cv.put(x, y, s["mid"] if dn == 0 else (s["dim"] if dn == 1 else STEM[min(5, 1 + i)]))
    return cv.img


# =============================================================================================== tube worms
# One texture per colour, mapped by tools/gen_data.py's tube worm models: four ringed tube strips two pixels wide
# (columns 0-7, row 0 at a tube's mouth), two plumes (columns 8-13: rows 0-5 and 8-13) and the open mouth seen from
# above (columns 14-15, rows 0-1).

TUBE_STRIPS = [(0, 2), (2, 4), (4, 6), (6, 8)]
PLUMES = [(8, 0, 14, 6), (8, 8, 14, 14)]
MOUTH = (14, 0, 16, 2)
PLUME_SHADES = {"h": "hot", "n": "neon", "m": "mid", "d": "dim", "e": "deep"}
PLUME_ROWS = [
    ["h.h..h",
     "n.nhn.",
     "mn.nmh",
     ".mnm.n",
     "..md..",
     "..de.."],
    [".h..h.",
     "hn.hnh",
     "nmn.m.",
     ".nmnm.",
     "..mm..",
     "..de.."],
]
# pale lilac-white chitin, its grooves a few steps darker and the foot greyed with the vent's grime
TUBE = [hexc(c) for c in ("#5E576E", "#746C86", "#8E86A0", "#A79FB8", "#C5BDD2", "#D8D1E3", "#ECE7F4")]


def tube_worms(neon):
    s = shades(neon)
    cv = Canvas()
    for k, (a, b) in enumerate(TUBE_STRIPS):
        rng = random.Random(3101 + k)
        ring = rng.randrange(2, 4)
        for y in range(16):
            # the collar, where the worm's red-free flesh shows: the neon's deep and dim at the mouth
            if y == 0:
                cv.put(a, y, s["dim"])
                cv.put(a + 1, y, s["deep"])
                continue
            groove = (y - ring) % 3 == 0 or (y > 8 and rng.random() < 0.18)
            lit = 5 - (1 if groove else 0) - (2 if y >= 11 else (1 if y >= 8 else 0))
            if y == 1:
                lit = 6
            cv.put(a, y, TUBE[max(0, min(6, lit))])
            cv.put(a + 1, y, TUBE[max(0, min(6, lit - 2))])
    # the plumes: feathery crowns fanning out of the tube's mouth, drawn by hand (h hot, n neon, m mid, d dim, e deep)
    for (x0, y0, _, _), rows in zip(PLUMES, PLUME_ROWS):
        for dy, row in enumerate(rows):
            for dx, ch in enumerate(row):
                if ch != ".":
                    cv.put(x0 + dx, y0 + dy, s[PLUME_SHADES[ch]])
    # the open mouth from above: a pale lip round a dark hole with the worm's deep colour in it
    mx, my = MOUTH[0], MOUTH[1]
    cv.put(mx, my, TUBE[5])
    cv.put(mx + 1, my, TUBE[4])
    cv.put(mx, my + 1, TUBE[3])
    cv.put(mx + 1, my + 1, s["deep"])
    return cv.img


# The tube worms' clusters (gen_data's models): per look, tubes as (x, z, height, strip, plume or None), in pixels,
# each tube two pixels square; a plume stands on its tube's top, six pixels across and high, while the worms are out.
WORM_LOOKS = [
    [(3, 4, 11, 0, 0), (7, 3, 8, 1, 1), (10, 6, 10, 2, 0), (5, 9, 6, 3, 1), (9, 10, 5, 1, 0), (12, 11, 3, 0, None)],
    [(4, 5, 9, 2, 1), (8, 4, 11, 0, 0), (11, 8, 7, 3, 1), (6, 10, 5, 1, 0), (3, 11, 3, 0, None)],
    [(5, 3, 7, 1, 0), (9, 5, 9, 3, 1), (4, 8, 10, 0, 1), (8, 10, 6, 2, 0), (12, 9, 4, 1, None), (11, 2, 3, 2, None)],
]


# =============================================================================================== glowworm threads
# Silk hanging from the ceilings of the dark halls (light blue only): a few strands, each a faint grey-blue line on
# a column of its own, beaded with droplets of light every few pixels: mostly one neon pixel, now and then a bigger
# drop (mid over hot). The body's strands and beads repeat every 16 rows, so any body stacks on any other; the tip's
# strands go on from the body's columns and end at their own lengths in a drop.

SILK = [hexc(c) for c in ("#232736", "#2B3042")]
# per look, per strand: (column, its beads as (row, kind), the tip's length); kinds: s a small bead (mid), n a bead
# (neon), b a big drop (mid over hot). The rows repeat every 16, so the body stacks.
THREADS = [
    [(2, [(1, "n"), (5, "s"), (9, "b"), (13, "n")], 9), (6, [(3, "s"), (7, "n"), (12, "b")], 14),
     (9, [(0, "b"), (4, "n"), (8, "s"), (12, "n")], 6), (13, [(2, "n"), (6, "b"), (11, "s")], 12)],
    [(3, [(2, "b"), (6, "n"), (11, "s"), (14, "n")], 12), (7, [(0, "n"), (4, "s"), (8, "b"), (12, "n")], 7),
     (10, [(3, "n"), (8, "n"), (13, "b")], 15), (14, [(1, "s"), (5, "b"), (10, "n")], 5)],
]


def threads(look, tip):
    s = shades("light_blue")
    cv = Canvas()
    for k, (x, beads, length) in enumerate(THREADS[look]):
        end = length if tip else 16
        for y in range(end):
            cv.put(x, y, SILK[(y // 4 + k) % 2])
        for r, kind in beads:
            if tip and r >= end - 2:
                continue
            if kind == "b":
                cv.put(x, r, s["mid"])
                cv.put(x, r + 1, s["hot"])
            else:
                cv.put(x, r, s["neon"] if kind == "n" else s["mid"])
        if tip:
            # the last drop, the biggest, hanging from the strand's end
            cv.put(x, end - 1, s["mid"])
            cv.put(x, end, s["hot"])
    return cv.img


# =============================================================================================== seep reeds
# Slender near-black reeds, one pixel wide and two at the foot, with a long leaf or two leaning off; most end in a seed
# head two to four pixels long, beaded with the seep crust's faint light blue (a third and a half of the neon's
# brightness) and a mid pixel for a glint.

REED = [hexc(c) for c in ("#14151C", "#1A1C25", "#21232E", "#292B38", "#323446")]


def _seep():
    T = _T()
    film = T.SEEP_FILM
    return {"faint": film[1], "glow": film[2], "glint": shades("light_blue")["mid"]}


def reed_glow():
    p = _seep()
    return {_hex(p["faint"]), _hex(p["glow"]), _hex(p["glint"])}


# (column, top, lean, head length or 0, leaf: (rows over the foot, side, length) or None, width at the foot)
REEDS = [
    [(3, 4, -1, 3, (6, -1, 4), 2), (6, 1, 0, 4, None, 1), (8, 7, 1, 3, (4, 1, 3), 1), (11, 3, 1, 4, (7, 1, 4), 2),
     (13, 9, 1, 2, None, 1), (5, 10, -1, 0, None, 1)],
    [(2, 6, -1, 3, None, 1), (5, 2, 0, 4, (5, -1, 4), 2), (7, 9, 0, 2, None, 1), (9, 0, 1, 4, (8, 1, 3), 1),
     (12, 5, 1, 3, (4, 1, 4), 2), (14, 11, 1, 0, None, 1)],
]
# the tall reeds: a plan two blocks high (rows 0-31), split like the tall grass
TALL_REEDS = [
    [(3, 10, -1, 3, (9, -1, 5), 2), (5, 2, 0, 4, (14, -1, 4), 1), (7, 18, 0, 3, None, 1), (9, 0, 1, 4, (11, 1, 5), 2),
     (11, 7, 1, 3, (18, 1, 4), 1), (13, 21, 1, 2, None, 1), (6, 25, -1, 0, None, 1)],
    [(2, 14, -1, 3, None, 1), (4, 4, -1, 4, (12, -1, 5), 2), (7, 1, 0, 4, (16, 1, 4), 1), (8, 20, 0, 2, None, 1),
     (10, 6, 1, 4, (10, 1, 5), 2), (12, 16, 1, 3, (6, 1, 3), 1), (14, 24, 1, 0, None, 1)],
]


def reeds_canvas(plan, h=16):
    cv = Canvas(h)
    p = _seep()
    for (x0, top, lean, head, leaf, width) in sorted(plan, key=lambda r: r[1]):
        pts = _path(x0, h - 1, top, lean, 0.0)
        n = len(pts)
        side = 1 if lean >= 0 else -1
        for i, (x, y) in enumerate(pts):
            dn = n - 1 - i
            if head and dn == 0:
                cv.put(x, y, REED[3])  # the spike over the head
                continue
            if head and dn <= head:
                # the head: two pixels wide, beaded in the seep crust's faint light blue, a glint near its top
                a1, a2 = (p["glow"], p["faint"]) if dn % 2 else (p["faint"], p["glow"])
                if dn == 1:
                    a1 = p["glint"]
                cv.put(x, y, a1)
                if dn < head or head <= 2:
                    cv.put(x + side, y, a2)
                continue
            t = i / max(1, n - 1)
            c = REED[min(4, 1 + int(t * 3.5) + ((x + y) % 4 == 0))]
            cv.put(x, y, c)
            if width == 2 and i < 4:
                cv.put(x + 1, y, REED[0])
        if leaf:
            k = min(n - 1, leaf[0])
            lx, ly = pts[k]
            for j in range(1, leaf[2] + 1):
                cv.put(lx + leaf[1] * j, ly - j - (j // 2), REED[min(4, 2 + j // 2)])
    return cv


def reeds(v):
    return reeds_canvas(REEDS[v]).img


def tall_reeds(v, half):
    cv = reeds_canvas(TALL_REEDS[v], h=32)
    return cv.crop((0, 0, 16, 16) if half == "top" else (0, 16, 16, 32))


# =============================================================================================== neon mushrooms
# One texture per colour, mapped by tools/gen_data.py's mushroom models: three cap sizes (5, 4 and 3 pixels across),
# each a top, a rim two pixels high and an underside, and a pale stem strip. The caps are the giant glow caps' look A in
# small: a soft skin (between dim and mid, lit by the world) with bold glowing spots, a glowing rim and dark gills.

CAP_TOP = {5: (0, 0, 5, 5), 4: (5, 0, 9, 4), 3: (9, 0, 12, 3)}
CAP_SIDE = {5: (0, 6, 5, 8), 4: (5, 6, 9, 8), 3: (9, 6, 12, 8)}
CAP_UNDER = {5: (0, 9, 5, 14), 4: (5, 9, 9, 13), 3: (9, 9, 12, 12)}
MUSH_STEM = (13, 0, 15, 8)
STEM_PALE = [hexc(c) for c in ("#8E86A0", "#A79FB8", "#BAB2C7", "#CFC7DA", "#E2DCEC")]
SPOTS = {5: [(1, 1, "hot"), (3, 0, "neon"), (3, 3, "neon"), (0, 3, "mid")], 4: [(1, 1, "hot"), (2, 3, "neon"), (3, 0, "mid")],
         3: [(1, 1, "hot"), (2, 2, "mid")]}


def mushrooms(neon):
    s = shades(neon)
    soft = tuple(round(v * 0.58) for v in _T().NEON_RGB[neon])
    cv = Canvas()
    for size, (x0, y0, x1, y1) in CAP_TOP.items():
        for y in range(y0, y1):
            for x in range(x0, x1):
                cv.put(x, y, soft)
        for dx, dy, shade in SPOTS[size]:
            cv.put(x0 + dx, y0 + dy, s[shade])
    for size, (x0, y0, x1, y1) in CAP_SIDE.items():
        for x in range(x0, x1):
            # the rim: the skin over a glowing lip
            cv.put(x, y0, soft if (x - x0) % 3 else s["mid"])
            cv.put(x, y0 + 1, s["neon"] if (x - x0) % 2 == 0 else s["mid"])
    for size, (x0, y0, x1, y1) in CAP_UNDER.items():
        cx, cy = (x0 + x1 - 1) / 2, (y0 + y1 - 1) / 2
        for y in range(y0, y1):
            for x in range(x0, x1):
                d = max(abs(x - cx), abs(y - cy))
                # gills radiating from the stem: dark between, dim on the gills, the edge deep
                gill = (x + y) % 2 == 0
                c = s["dim"] if gill and d < size / 2 - 0.5 else s["deep"]
                if d < 0.6:
                    c = STEM_PALE[1]
                cv.put(x, y, c)
    a, y0, b, y1 = MUSH_STEM
    for y in range(y0, y1):
        cv.put(a, y, STEM_PALE[4 - min(3, y // 2)])
        cv.put(a + 1, y, STEM_PALE[3 - min(3, y // 2)])
    return cv.img


# The mushrooms' looks by count (NeonMushroomsBlock.MUSHROOMS: 1 to 3), each a list of mushrooms as (x, z, stem
# height, stem width, cap size, lean): x and z the stem's foot, in pixels; lean a turn about x or z (22.5 degrees, as
# models allow) or None.
MUSHROOM_LOOKS = {
    1: [(7.0, 7.0, 5, 2, 5, None)],
    2: [(5.0, 6.0, 4, 1, 4, None), (10.5, 9.5, 3, 1, 3, ("z", -22.5))],
    3: [(6.5, 6.0, 5, 2, 5, None), (11.0, 9.0, 3, 1, 3, ("x", 22.5)), (4.0, 11.0, 2, 1, 3, ("z", 22.5))],
}


# =============================================================================================== spores
def spore(seed):
    """A mote of light: a round core with a soft rim in a few alpha steps, white (the particle tints it)."""
    rng = random.Random(seed)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    r0 = 5.6 + rng.random() * 0.8
    for y in range(16):
        for x in range(16):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8) / r0
            if d >= 1.3:
                continue
            a = 255 if d < 0.45 else (190 if d < 0.75 else (110 if d < 1.0 else 45))
            c = 255 if d < 0.45 else 240
            px[x, y] = (c, c, c, a)
    return img


# =============================================================================================== registry
def textures():
    """Every texture of this file: name -> (painter, the hex colours its glow overlay lifts, or None)."""
    out = {}
    for neon in NEONS:
        g = glow_hexes(neon)
        for v in range(2):
            sfx = "" if v == 0 else f"_{v + 1}"
            out[f"block/filament_tuft_{neon}{sfx}"] = (lambda v=v, n=neon: tuft(v, n), g)
            out[f"block/filament_grass_{neon}{sfx}"] = (lambda v=v, n=neon: grass(v, n), g)
            for half in ("bottom", "top"):
                out[f"block/tall_filament_grass_{neon}_{half}{sfx}"] = (lambda v=v, n=neon, h=half: tall(v, n, h), g)
            out[f"block/grazed_filament_grass_{neon}{sfx}"] = (lambda v=v, n=neon: stubble(v, n), g)
        out[f"block/tube_worms_{neon}"] = (lambda n=neon: tube_worms(n), g)
        out[f"block/neon_mushrooms_{neon}"] = (lambda n=neon: mushrooms(n), g)
    lb = glow_hexes("light_blue")
    for v in range(2):
        sfx = "" if v == 0 else f"_{v + 1}"
        out[f"block/glowworm_threads{sfx}"] = (lambda v=v: threads(v, True), lb)
        out[f"block/glowworm_threads_plant{sfx}"] = (lambda v=v: threads(v, False), lb)
        out[f"block/seep_reeds{sfx}"] = (lambda v=v: reeds(v), reed_glow())
        for half in ("bottom", "top"):
            out[f"block/tall_seep_reeds_{half}{sfx}"] = (lambda v=v, h=half: tall_reeds(v, h), reed_glow())
    return out


PARTICLE_PAINTERS = {f"particle/spore_{i}": (lambda s=seed: spore(s)) for i, seed in enumerate((6901, 6902))}
