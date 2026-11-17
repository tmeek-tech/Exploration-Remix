"""The Silver Fern Rift's ferns, fluffier (Taran, 1 Nov): broad fronds like the picture's, drawn for the 3D models in
gen_data.py (templates fern, tall_fern_lower, tall_fern_upper, hanging_fern).

silver_fern_rift.jpg: the ground ferns are wide sage-silver clumps, frond after frond of small leaflets arching out
past the edge of their block; on the walls the fronds hang from the ledges and drape down the rock.

0.14.3 (Taran: "try again for the silver fern textures"): the 0.4.2 fronds, a leaflet off every pixel on alternating
sides, read in the game as white noise, a checker of near-white tips on dark stalks. A frond is now drawn in one of
three styles (STYLE), in a softer silver-green whose leaflets stay within a few shades of each other, so the shape of
each frond carries the fern and not its pixels:
  chevron   pairs of leaflets every third pixel, at 45 degrees towards the tip, each with a shade line under it: a stack
            of clean Vs with a gap between (the default)
  feather   pairs of leaflets every other pixel, square to the stalk, their last pixel bent towards the tip: lacier
  herring   the 0.4.2 herringbone, in the softer colours
Stalks run straight or at 45 degrees. Fronds behind the others are a shade darker.

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

# Silver-green (0.4.2: the darker greens of the first palette read as teal shadow under a shader). 0.14.3: softer, the
# leaflets from #8FAA95 to #C4D9C7 where 0.4.2 ran from #7C9A87 to near-white #E8F2EA, the stalks lighter.
STYLE = "chevron"
STALK = ("#6F8C78", "#7A9783")                       # lower stalk, upper stalk
LIT = ("#A2BDA8", "#ACC6B1", "#B6CEBA", "#C4D9C7")   # a leaflet on the lit side, from the stalk out to its pale tip
DIM = ("#8FAA95", "#98B39E", "#A2BDA8", "#A2BDA8")   # on the shadow side
BACK_STALK = "#67826F"
BACK_LIT = ("#8BA691", "#93AE99", "#9BB5A1", "#9BB5A1")
BACK_DIM = ("#7D9884", "#85A08B", "#8BA691", "#8BA691")

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
        stalk), in the STYLE. Herring starts on side `first` (1: the right of the way the stalk grows). The lit side is
        the one whose leaflets point up, or lit_side for a hanging frond. The stalk goes on top, its tip pale."""
        n = len(pts)
        side = first
        for i in range(1, n - 1):
            length = lens[min(len(lens) - 1, (i - 1) * len(lens) // max(1, n - 2))]
            tx = _sign(pts[i + 1][0] - pts[i - 1][0])
            ty = _sign(pts[i + 1][1] - pts[i - 1][1])
            if STYLE == "herring":
                if length > 0:
                    self._leaflet(pts[i], tx, ty, side, length, back, lit_side)
                side = -side
            elif length > 0 and (n - 2 - i) % (3 if STYLE == "chevron" else 2) == 0:
                # in pairs, counted from the tip so every frond ends in a pair
                for sd in (1, -1):
                    self._leaflet(pts[i], tx, ty, sd, length, back, lit_side)
        for i, (x, y) in enumerate(pts):
            self.put(x, y, BACK_STALK if back else STALK[0] if i < n * 0.55 else STALK[1])
        self.put(*pts[-1], BACK_LIT[2] if back else LIT[2])
        return self

    def _leaflet(self, at, tx, ty, side, length, back, lit_side):
        """One leaflet from the stalk pixel `at` (the stalk heading tx, ty) on `side`, `length` pixels long."""
        if STYLE == "feather":
            # square to the stalk (on a diagonal stalk, the nearer grid direction towards the tip), the last pixel bent
            # towards the tip
            sx, sy = -ty * side, tx * side
            if sx and sy:
                sx, sy = _sign(sx + tx), _sign(sy + ty)
            lit = (sy < 0 or sy == 0 and ty < 0) if lit_side is None else side == lit_side
            cols = (BACK_LIT if lit else BACK_DIM) if back else (LIT if lit else DIM)
            x, y = at
            for k in range(1, length + 1):
                x, y = (x + sx + tx, y + sy + ty) if k == length and length >= 2 else (x + sx, y + sy)
                self.put(x, y, cols[min(3, length)] if k == length and length >= 2 else cols[k - 1])
            return
        # square to the stalk on this side, then 45 degrees towards the tip
        vx, vy = _sign(-ty * side + tx), _sign(tx * side + ty)
        lit = vy < 0 if lit_side is None else side == lit_side
        cols = (BACK_LIT if lit else BACK_DIM) if back else (LIT if lit else DIM)
        x, y = at
        for k in range(1, length + 1):
            x, y = x + vx, y + vy
            if STYLE == "chevron" and k < length:
                # the leaflet's underside, a pixel towards the base, where nothing is drawn yet
                ux, uy = x - tx, y - ty
                if 0 <= ux < self.w and 0 <= uy < self.h and self.px[ux, uy][3] == 0:
                    self.put(ux, uy, BACK_DIM[0] if back else cols[0])
            if STYLE == "chevron":
                self.put(x, y, cols[min(3, length)] if k == length and length >= 2 else cols[min(k, 2)] if k > 1 else cols[0])
            else:
                self.put(x, y, cols[min(3, length)] if k == length and length >= 2 else cols[k - 1])


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
