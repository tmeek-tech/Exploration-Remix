"""The Silver Fern Rift's ferns, fluffier (Taran, 1 Nov): broad fronds like the picture's, drawn for the 3D models in
gen_data.py (templates fern, tall_fern_lower, tall_fern_upper, hanging_fern).

silver_fern_rift.jpg: the ground ferns are wide sage-silver clumps, frond after frond of small leaflets arching out
past the edge of their block; on the walls the fronds hang from the ledges and drape down the rock.

0.14.3 (Taran: "try again for the silver fern textures") kept the 0.4.2 layout, a dark stalk with a leaflet or a pair
off every pixel or two, in softer colours, and in the game that read as the same fern (Taran, 17 Nov: the pictures "all
just show the original silver fern textures"). 0.14.4 draws a frond in one of three new shapes (STYLE), in pale
colours that stay within a few shades of each other, the stalk as pale as the leaflets, so a frond reads as one shape:
  lace     leaflet pairs every other pixel, square to the stalk and bent towards the tip: a dense feathery plume with a
           toothed edge (the default)
  miller   a branch every third pixel on alternate sides, with a lobe off its middle and a forked end: pale, deeply cut
           sprays, open between the branches
  lobed    a solid frond whose edge is a row of rounded lobes
Stalks run straight or at 45 degrees. Fronds behind the others are a shade darker (lace leaves them out).

The textures, by model plane:
  silver_fern[_2, _3]                  small fern, the two crossed upright planes: an upright clump (also the item)
  silver_fern_fan[_2, _3]              small fern, the four planes leaning out: a fan of fronds from the bottom middle
  tall_silver_fern_{top,bottom}[_2]    tall fern, crossed planes: one 16 x 32 drawing cut in two
  tall_silver_fern_fan_{top,bottom}[_2]  tall fern, leaning planes that carry on through the upper half
  silver_wall_fern[_2]                 hanging fern, the plane against the rock: fronds hanging from the top edge
  silver_wall_fern_front[_2]           hanging fern, the plane swung out from the rock
  silver_wall_fern_side[_2]            hanging fern, the planes square to the wall (the wall on the right edge)
The hanging fern's are 16 x 32, the fronds in the top 24 rows (a block and a half).
"""
from PIL import Image
from plants import grid, hexc, register
from plants_frost import FROST_MOSS_BANDS

PAINTERS = {}

# Silver-green, pale (0.4.2's darker greens read as teal shadow under a shader, 0.14.3's dark stalks as a checker).
STYLE = "lace"

# Each style's colours, from the stalk out to the tip or rim; the lobed style's half-widths by leaflet length 0 to 3.
LOBED = {"half": (0.0, 1.0, 1.9, 2.7),
         "lit": ("#A6BBA9", "#BDCFBF", "#D3E1D4"), "dim": ("#8BA191", "#9BB0A0", "#AEC1B0"),
         "back_lit": ("#8FA594", "#9FB3A3", "#B0C2B2"), "back_dim": ("#7A9080", "#869C8C", "#94A999"),
         "stalk": "#7C957F", "back_stalk": "#6A8170"}
FINE = {
    "lace": {"lit": ("#A9BEAE", "#BDCFC0", "#CEDDD0"), "dim": ("#9AB09F", "#AABFAE", "#B9CBBB"),
             "back_lit": ("#91A797", "#9FB4A4", "#ADC0B0"), "back_dim": ("#869C8C", "#93A898", "#9FB3A3"),
             "stalk": "#A3B8A8", "back_stalk": "#8DA393"},
    "miller": {"lit": ("#ABC0B4", "#BCCEC3", "#CFDDD4"), "dim": ("#9DB2A6", "#ADC0B5", "#BDCEC4"),
               "back_lit": ("#94A99D", "#A2B6AA", "#B0C2B7"), "back_dim": ("#889D91", "#95A99E", "#A2B5AA"),
               "stalk": "#A6BAAE", "back_stalk": "#90A498"},
}

UP, DOWN, LEFT, RIGHT = (0, -1), (0, 1), (-1, 0), (1, 0)
UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT = (-1, -1), (1, -1), (-1, 1), (1, 1)


def _sign(v):
    return (v > 0) - (v < 0)


def stalk(start, *runs):
    """A stalk's pixels, base first: from start, then (direction, count) runs of single steps."""
    pts = [start]
    x, y = start
    for (dx, dy), count in runs:
        for _ in range(count):
            x, y = x + dx, y + dy
            pts.append((x, y))
    return pts


class Drawing:
    def __init__(self, w=16, h=16):
        self.img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        self.px = self.img.load()
        self.w, self.h = w, h

    def put(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[x, y] = hexc(c)

    def frond(self, pts, lens, back=False, first=1, lit_side=None):
        """Leaflets off pts (the stalk, base first), lens[i] pixels long (0 for none; the list is stretched over the
        stalk), in the STYLE. Miller's branches start on side `first` (1: the right of the way the stalk grows). The
        lit side is the one whose leaflets point up, or lit_side for a hanging frond. The stalk goes on top, its tip pale."""
        if STYLE == "lobed":
            return self._broad(pts, lens, back, lit_side)
        return self._fine(pts, lens, back, first, lit_side)

    def _fine(self, pts, lens, back, first, lit_side):
        """Lace and miller, after the rift picture, drawn in FINE[STYLE]'s colours, which stay within a few
        shades of each other with no dark stalk, so a frond reads as one pale shape.
        Lace: leaflet pairs every other pixel (counted from the tip), each a block thicker than the line it runs
        along and as long as lens says plus one, square to the stalk and then 45 degrees towards the tip; a short stub
        each side between pairs. A dense feathery plume with a toothed edge, like the picture's ledge ferns.
        Miller: a branch every third pixel on alternate sides, 45 degrees towards the tip, ending in a fork of two blunt
        lobes, with a lobe off its middle: the picture's pale, deeply cut silver leaves, open between the branches."""
        pal = FINE[STYLE]
        n = len(pts)
        cols = lambda lit: pal["back_lit" if lit else "back_dim"] if back else pal["lit" if lit else "dim"]

        def lit_of(sd, vy):
            return (vy < 0 or vy == 0) if lit_side is None else sd == lit_side

        side = first
        for i in range(1, n - 1):
            length = lens[min(len(lens) - 1, (i - 1) * len(lens) // max(1, n - 2))]
            tx = _sign(pts[i + 1][0] - pts[i - 1][0])
            ty = _sign(pts[i + 1][1] - pts[i - 1][1])
            if length <= 0:
                continue
            if STYLE == "lace":
                if back:
                    continue
                if (n - 2 - i) % 2 == 0:
                    for sd in (1, -1):
                        # square to the stalk (on a diagonal stalk, the nearer grid direction towards the tip), the
                        # last pixel bent towards the tip
                        sx, sy = -ty * sd, tx * sd
                        if sx and sy:
                            sx, sy = _sign(sx + tx), _sign(sy + ty)
                        c = cols(lit_of(sd, sy))
                        x, y = pts[i]
                        reach = length + 1
                        for k in range(1, reach + 1):
                            x, y = (x + sx + tx, y + sy + ty) if k == reach else (x + sx, y + sy)
                            self.put(x, y, c[2] if k == reach else c[1] if k > 1 else c[0])
            else:
                if (n - 2 - i) % 3 != 0:
                    continue
                sd = side
                side = -side
                vx, vy = _sign(-ty * sd + tx), _sign(tx * sd + ty)
                c = cols(lit_of(sd, vy))
                x, y = pts[i]
                reach = length + 1
                for k in range(1, reach + 1):
                    x, y = x + vx, y + vy
                    self.put(x, y, c[1])
                    if k == 1 and reach >= 3:
                        # a lobe off the branch's middle, towards the tip
                        self.put(x + tx, y + ty, c[1])
                        self.put(x + 2 * tx, y + 2 * ty, c[2])
                # the fork at the end: a blunt lobe on, and one bent out
                ox, oy = _sign(vx - tx) if vx != tx else vx, _sign(vy - ty) if vy != ty else vy
                self.put(x + vx, y + vy, c[2])
                self.put(x + tx, y + ty, c[2])
                if reach >= 2:
                    self.put(x + ox, y + oy, c[1])
        if STYLE == "lace" and back:
            return self
        for i, (x, y) in enumerate(pts):
            self.put(x, y, pal["back_stalk"] if back else pal["stalk"])
        self.put(*pts[-1], cols(True)[2])
        return self

    def _broad(self, pts, lens, back, lit_side):
        """A broad frond round the stalk pts (base first), filled out to a half-width from lens (stretched over the
        stalk like the leaflets' lengths), in the LOBED colours: each pixel takes the stalk point nearest it and lies
        inside when within that point's half-width on its side, with a notch every third pixel, the two sides out of
        step, so the edge is a row of rounded lobes."""
        pal = LOBED
        n = len(pts)
        half = []
        for i in range(n):
            if i == 0:
                half.append(0.0)
                continue
            length = lens[min(len(lens) - 1, (i - 1) * len(lens) // max(1, n - 2))]
            half.append(pal["half"][length] if i < n - 1 else pal["half"][1] * 0.6)
        tang = []
        for i in range(n):
            a, b = pts[max(0, i - 1)], pts[min(n - 1, i + 1)]
            tx, ty = b[0] - a[0], b[1] - a[1]
            m = max(1e-6, (tx * tx + ty * ty) ** 0.5)
            tang.append((tx / m, ty / m))
        drawn = {}
        for y in range(self.h):
            for x in range(self.w):
                best, bj = None, -1
                for j, (sx, sy) in enumerate(pts):
                    d = ((x - sx) ** 2 + (y - sy) ** 2) ** 0.5
                    if best is None or d < best - 1e-9:
                        best, bj = d, j
                if best is None or bj == 0:
                    continue
                sx, sy = pts[bj]
                tx, ty = tang[bj]
                cross = tx * (y - sy) - ty * (x - sx)
                side = 1 if cross > 0 else -1 if cross < 0 else 0
                hw = half[bj]
                if side and (bj + (0 if side > 0 else 1)) % 3 == 0:
                    hw *= 0.45
                if best > hw + 0.3:
                    continue
                # the lit side: the one facing up (or lit_side for a hanging frond)
                if lit_side is not None:
                    lit = side == lit_side or side == 0
                else:
                    nx, ny = -ty * side, tx * side
                    lit = ny < 0.2 or side == 0
                r = best / max(0.5, hw)
                tone = 0 if r < 0.45 else 1 if r < 0.8 else 2
                cols = pal["back_lit" if lit else "back_dim"] if back else pal["lit" if lit else "dim"]
                drawn[(x, y)] = cols[tone]
        for (x, y), c in drawn.items():
            self.put(x, y, c)
        for i, (x, y) in enumerate(pts):
            self.put(x, y, pal["back_stalk"] if back else pal["stalk"])
        self.put(*pts[-1], (pal["back_lit"] if back else pal["lit"])[1])
        return self


# ------------------------------------------------------------------------------------------------ small fern
def _fern(v):
    """An upright clump: fronds rising from the base and arching over at their tips, two low ones behind."""
    d = Drawing()
    if v == 1:
        d.frond(stalk((5, 15), (UP_LEFT, 4), (LEFT, 2)), [1, 2, 2, 1], back=True, first=-1)
        d.frond(stalk((10, 15), (UP_RIGHT, 4), (RIGHT, 2)), [1, 2, 2, 1], back=True)
        d.frond(stalk((6, 15), (UP, 3), (UP_LEFT, 4), (UP, 5)), [1, 2, 2, 3, 3, 2, 1])
        d.frond(stalk((9, 15), (UP, 3), (UP_RIGHT, 4), (UP, 5)), [1, 2, 2, 3, 3, 2, 1], first=-1)
        d.frond(stalk((7, 15), (UP, 8), (UP_RIGHT, 3), (RIGHT, 1)), [0, 1, 2, 2, 3, 3, 2, 2, 1])
    elif v == 2:
        d.frond(stalk((6, 15), (UP_LEFT, 6), (UP, 3)), [1, 2, 2, 3, 2, 1], back=True)
        d.frond(stalk((9, 15), (UP_RIGHT, 6), (UP, 3)), [1, 2, 2, 3, 2, 1], back=True, first=-1)
        d.frond(stalk((7, 15), (UP, 5), (UP_LEFT, 3), (UP, 3), (UP_LEFT, 2), (LEFT, 1)), [1, 2, 3, 3, 3, 2, 1])
        d.frond(stalk((8, 15), (UP, 6), (UP_RIGHT, 3), (UP, 2), (UP_RIGHT, 2), (RIGHT, 1)), [1, 2, 2, 3, 2, 1], first=-1)
    else:
        d.frond(stalk((7, 15), (UP_LEFT, 7), (LEFT, 1)), [1, 2, 2, 2, 1], back=True)
        d.frond(stalk((8, 15), (UP_RIGHT, 7), (RIGHT, 1)), [1, 2, 2, 2, 1], back=True, first=-1)
        d.frond(stalk((7, 15), (UP, 2), (UP_LEFT, 2), (UP, 6), (UP_LEFT, 2), (LEFT, 1)), [1, 2, 2, 3, 3, 2, 2, 1])
        d.frond(stalk((8, 15), (UP, 2), (UP_RIGHT, 2), (UP, 6), (UP_RIGHT, 2)), [1, 2, 3, 3, 2, 2, 1], first=-1)
    return d.img


def _fern_fan(v):
    """Two or three fronds fanning from the bottom middle; on a plane leaning out they spread over the ground."""
    d = Drawing()
    if v == 1:
        d.frond(stalk((8, 15), (UP, 13)), [0, 1, 2, 2, 3, 3, 2, 2, 1], back=True)
        d.frond(stalk((7, 15), (UP_LEFT, 6), (UP, 7)), [1, 2, 3, 3, 3, 2, 2, 1])
        d.frond(stalk((8, 15), (UP_RIGHT, 6), (UP, 7)), [1, 2, 3, 3, 3, 2, 2, 1], first=-1)
    elif v == 2:
        d.frond(stalk((7, 15), (UP_LEFT, 3), (UP, 11)), [1, 2, 2, 3, 3, 3, 2, 1])
        d.frond(stalk((8, 15), (UP_RIGHT, 5), (UP, 8)), [1, 2, 3, 3, 2, 2, 1], first=-1)
    else:
        d.frond(stalk((7, 15), (UP, 1), (UP_LEFT, 5), (UP, 6)), [1, 2, 3, 3, 2, 2, 1], back=True)
        d.frond(stalk((8, 15), (UP, 14)), [0, 1, 2, 3, 3, 3, 3, 2, 2, 1])
        d.frond(stalk((9, 15), (UP_RIGHT, 5), (UP, 5)), [1, 2, 2, 3, 2, 1], first=-1, back=True)
    return d.img


for _v in (1, 2, 3):
    _s = "" if _v == 1 else f"_{_v}"
    register(PAINTERS, "silver_fern" + _s, lambda v=_v: _fern(v))
    register(PAINTERS, "silver_fern_fan" + _s, lambda v=_v: _fern_fan(v))


# ------------------------------------------------------------------------------------------------ tall fern
def _tall(v):
    """The crossed planes: a long upright frond with others bending out, on 16 x 32."""
    d = Drawing(16, 32)
    if v == 1:
        d.frond(stalk((5, 31), (UP, 6), (UP_LEFT, 4), (UP, 12)), [1, 2, 2, 3, 3, 3, 3, 2, 2, 1], back=True)
        d.frond(stalk((10, 31), (UP, 8), (UP_RIGHT, 4), (UP, 10)), [1, 2, 2, 3, 3, 3, 2, 2, 1], back=True, first=-1)
        d.frond(stalk((7, 31), (UP, 23), (UP_RIGHT, 3), (RIGHT, 1)), [0, 1, 1, 2, 2, 3, 3, 3, 3, 3, 2, 2, 1])
        d.frond(stalk((6, 31), (UP_LEFT, 4), (LEFT, 1)), [1, 2, 2, 1], first=-1)
        d.frond(stalk((9, 31), (UP_RIGHT, 4), (RIGHT, 1)), [1, 2, 2, 1])
    else:
        d.frond(stalk((6, 31), (UP, 10), (UP_LEFT, 4), (UP, 7), (UP_LEFT, 2), (LEFT, 1)), [1, 2, 2, 3, 3, 3, 3, 2, 1])
        d.frond(stalk((9, 31), (UP, 5), (UP_RIGHT, 5), (UP, 10), (UP_RIGHT, 2), (RIGHT, 1)), [1, 2, 2, 3, 3, 3, 3, 2, 1],
                first=-1)
        d.frond(stalk((8, 31), (UP, 26)), [0, 1, 2, 2, 3, 3, 3, 2, 2, 1], back=True)
    return d.img


def _tall_fan(v):
    """The leaning planes: long fronds from the bottom middle, on 16 x 32."""
    d = Drawing(16, 32)
    if v == 1:
        d.frond(stalk((7, 31), (UP_LEFT, 5), (UP, 22)), [1, 2, 2, 3, 3, 3, 3, 3, 2, 2, 1])
        d.frond(stalk((8, 31), (UP_RIGHT, 5), (UP, 20)), [1, 2, 2, 3, 3, 3, 3, 2, 2, 1], first=-1)
    else:
        d.frond(stalk((8, 31), (UP, 27)), [0, 1, 2, 2, 3, 3, 3, 3, 3, 2, 2, 1])
        d.frond(stalk((7, 31), (UP_LEFT, 4), (UP, 8)), [1, 2, 2, 2, 1], back=True)
    return d.img


for _v in (1, 2):
    _s = "" if _v == 1 else f"_{_v}"
    register(PAINTERS, "tall_silver_fern_top" + _s, lambda v=_v: _tall(v).crop((0, 0, 16, 16)))
    register(PAINTERS, "tall_silver_fern_bottom" + _s, lambda v=_v: _tall(v).crop((0, 16, 16, 32)))
    register(PAINTERS, "tall_silver_fern_fan_top" + _s, lambda v=_v: _tall_fan(v).crop((0, 0, 16, 16)))
    register(PAINTERS, "tall_silver_fern_fan_bottom" + _s, lambda v=_v: _tall_fan(v).crop((0, 16, 16, 32)))


# ------------------------------------------------------------------------------------------------ hanging fern
# 16 x 32 drawings, of which the model shows the top 24 rows: the fronds fall a block and a half. (Without a .mcmeta
# the game keeps a tall texture whole; only an animation's .mcmeta cuts it into frames.)
def _hanging(v):
    """The plane against the rock: fronds hanging from the top edge, the outer ones a shade darker behind."""
    d = Drawing(16, 32)
    if v == 1:
        d.frond(stalk((5, 0), (DOWN, 2), (DOWN_LEFT, 2), (DOWN, 15)), [1, 2, 2, 3, 3, 3, 2, 2, 1], back=True, lit_side=1)
        d.frond(stalk((11, 0), (DOWN, 3), (DOWN_RIGHT, 2), (DOWN, 12)), [1, 2, 2, 3, 3, 2, 2, 1], back=True, lit_side=-1,
                first=-1)
        d.frond(stalk((8, 0), (DOWN, 22)), [1, 2, 2, 3, 3, 3, 3, 2, 2, 1], lit_side=1)
    else:
        d.frond(stalk((3, 0), (DOWN_LEFT, 1), (DOWN, 17)), [1, 2, 2, 3, 3, 2, 2, 1], back=True, lit_side=1)
        d.frond(stalk((13, 0), (DOWN_RIGHT, 1), (DOWN, 14)), [1, 2, 3, 3, 2, 2, 1], back=True, lit_side=-1, first=-1)
        d.frond(stalk((7, 0), (DOWN, 6), (DOWN_LEFT, 1), (DOWN, 15)), [1, 2, 2, 3, 3, 3, 3, 2, 2, 1], lit_side=1)
        d.frond(stalk((10, 0), (DOWN, 4), (DOWN_RIGHT, 1), (DOWN, 12)), [1, 2, 3, 3, 3, 2, 1], lit_side=-1, first=-1)
    return d.img


def _hanging_front(v):
    """The plane swung out from the rock: two long fronds falling clear of the others, lit on their outer sides."""
    d = Drawing(16, 32)
    if v == 1:
        d.frond(stalk((4, 0), (DOWN, 5), (DOWN_LEFT, 2), (DOWN, 13)), [1, 2, 2, 3, 3, 3, 3, 2, 1], lit_side=1)
        d.frond(stalk((11, 0), (DOWN, 4), (DOWN_RIGHT, 2), (DOWN, 11)), [1, 2, 2, 3, 3, 3, 2, 1], lit_side=-1, first=-1)
    else:
        d.frond(stalk((6, 0), (DOWN, 3), (DOWN_LEFT, 3), (DOWN, 14)), [1, 2, 3, 3, 3, 3, 2, 2, 1], lit_side=1)
        d.frond(stalk((10, 0), (DOWN, 8), (DOWN_RIGHT, 2), (DOWN, 9)), [1, 2, 2, 3, 3, 2, 1], lit_side=-1, first=-1)
    return d.img


def _hanging_side(v):
    """Side on, the wall on the right edge (the model shows the right half): fronds leave the rock, arch out and fall."""
    d = Drawing(16, 32)
    if v == 1:
        d.frond(stalk((15, 1), (LEFT, 1), (DOWN_LEFT, 2), (DOWN, 17)), [1, 2, 2, 3, 3, 2, 2, 1], lit_side=-1)
        d.frond(stalk((15, 4), (DOWN_LEFT, 1), (DOWN, 14)), [1, 2, 2, 2, 1], back=True, first=-1)
    else:
        d.frond(stalk((15, 2), (DOWN_LEFT, 3), (DOWN, 18)), [1, 2, 2, 3, 3, 2, 1], lit_side=-1)
        d.frond(stalk((15, 0), (DOWN, 15)), [1, 2, 2, 2, 1], back=True, first=-1)
    return d.img


for _v in (1, 2):
    _s = "" if _v == 1 else f"_{_v}"
    register(PAINTERS, "silver_wall_fern" + _s, lambda v=_v: _hanging(v))
    register(PAINTERS, "silver_wall_fern_front" + _s, lambda v=_v: _hanging_front(v))
    register(PAINTERS, "silver_wall_fern_side" + _s, lambda v=_v: _hanging_side(v))


# ------------------------------------------------------------------------------------------------ deep moss
# The ledges' moss (Taran, 0.14.3: "add deep moss for silver fern ridges instead of normal moss"): vanilla's moss
# block in a deep, cool green, darker than vanilla's yellow-green, so the pale ferns stand out on it. As the frost moss
# does, each pixel of vanilla's moss texture takes the green of its brightness band, darkest 0 to brightest 4.
DEEP_MOSS = {"0": "#2E4430", "1": "#3A563A", "2": "#486A45", "3": "#5A7F52", "4": "#729A66"}
register(PAINTERS, "deep_moss", lambda: grid(FROST_MOSS_BANDS, DEEP_MOSS))
