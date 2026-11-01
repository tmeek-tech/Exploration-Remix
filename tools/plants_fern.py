"""The Silver Fern Rift's ferns, fluffier (Taran, 1 Nov): broad fronds like the picture's, drawn for the 3D models in
gen_data.py (templates fern, tall_fern_lower, tall_fern_upper, hanging_fern).

silver_fern_rift.jpg: the ground ferns are wide sage-silver clumps, frond after frond of small leaflets arching out
past the edge of their block; on the walls the fronds hang from the ledges and drape down the rock.

A frond here is a stalk with a leaflet off every pixel, on alternating sides, so each side has one every other pixel
with a gap between: the lacy herringbone that reads as a fern at 16 pixels. Stalks run straight or at 45 degrees, and
each leaflet leaves its stalk at 45 degrees towards the tip, so leaflets lie on clean diagonals or straight lines with
no stray pixels. Fronds behind the others are a shade darker.

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
from plants import hexc, register

PAINTERS = {}

# Silver-green, a little blue: the first in-game pictures showed the darker greens of the first palette as teal shadow
# inside the clumps, far from the pale silver of the picture and of the old silver fern.
STALK = ("#62806A", "#769478")                       # lower stalk, upper stalk
LIT = ("#9DB9A8", "#B8D0BF", "#CFE1D3", "#E8F2EA")   # a leaflet on the lit side, from the stalk out to its pale tip
DIM = ("#7C9A87", "#9DB9A8", "#B8D0BF", "#B8D0BF")   # on the shadow side
BACK_STALK = "#5F7A68"
BACK_LIT = ("#7C9A87", "#97B3A1", "#AFC9B6", "#AFC9B6")
BACK_DIM = ("#66826F", "#7C9A87", "#97B3A1", "#97B3A1")

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
        """Leaflets off pts (the stalk, base first), on alternating sides starting with `first` (1: the right of the
        way the stalk grows), lens[i] pixels long (0 for none; the list is stretched over the stalk). The lit side is
        the one whose leaflets point up, or lit_side for a hanging frond. The stalk goes on top, its tip pale."""
        n = len(pts)
        side = first
        for i in range(1, n - 1):
            length = lens[min(len(lens) - 1, (i - 1) * len(lens) // max(1, n - 2))]
            if length > 0:
                tx = _sign(pts[i + 1][0] - pts[i - 1][0])
                ty = _sign(pts[i + 1][1] - pts[i - 1][1])
                # square to the stalk on this side, then 45 degrees towards the tip
                vx, vy = _sign(-ty * side + tx), _sign(tx * side + ty)
                lit = vy < 0 if lit_side is None else side == lit_side
                cols = (BACK_LIT if lit else BACK_DIM) if back else (LIT if lit else DIM)
                x, y = pts[i]
                for k in range(length):
                    x, y = x + vx, y + vy
                    self.put(x, y, cols[min(3, length)] if k == length - 1 and length >= 2 else cols[k])
            side = -side
        for i, (x, y) in enumerate(pts):
            self.put(x, y, BACK_STALK if back else STALK[0] if i < n * 0.55 else STALK[1])
        self.put(*pts[-1], BACK_LIT[2] if back else LIT[2])
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
