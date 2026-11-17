"""The Silver Fern Rift's ferns, fluffier (Taran, 1 Nov): broad fronds like the picture's, drawn for the 3D models in
gen_data.py (templates fern, tall_fern_lower, tall_fern_upper, hanging_fern, hanging_fern_medium, hanging_fern_large).

silver_fern_rift.jpg: the ground ferns are wide clumps, frond after frond of small leaflets arching out past the edge of
their block; on the walls the fronds hang from the ledges and drape down the rock.

A frond here is a stalk with a leaflet off every pixel, on alternating sides, so each side has one every other pixel
with a gap between: the lacy herringbone that reads as a fern at 16 pixels. Stalks run straight or at 45 degrees, and
each leaflet leaves its stalk at 45 degrees towards the tip, so leaflets lie on clean diagonals or straight lines with
no stray pixels. Fronds behind the others are a shade darker.

0.14.6 (Taran, 17 Nov): back to 0.4.2's drawings ("the original texture is still the best"; 0.14.3 and 0.14.4 tried
others), in a more saturated green that is still paler than vanilla's cave vines; and the hanging ferns in three sizes,
the smallest 0.4.2's, each frond a natural triangle, its leaflets longest at the top, where it leaves the rock, and
shortening to its tip.

The textures, by model plane:
  silver_fern[_2, _3]                  small fern, the two crossed upright planes: an upright clump (also the item)
  silver_fern_fan[_2, _3]              small fern, the four planes leaning out: a fan of fronds from the bottom middle
  tall_silver_fern_{top,bottom}[_2]    tall fern, crossed planes: one 16 x 32 drawing cut in two
  tall_silver_fern_fan_{top,bottom}[_2]  tall fern, leaning planes that carry on through the upper half
  silver_wall_fern{,_medium,_large}[_2]        hanging fern, the plane against the rock: fronds hanging from the top
  silver_wall_fern{,_medium,_large}_front[_2]  hanging fern, the plane swung out from the rock
  silver_wall_fern{,_medium,_large}_side[_2]   hanging fern, the planes square to the wall (the wall on the right edge)
The hanging fern's textures and lengths are HANGING_SIZES': the small one's 16 x 32 with its fronds in the top 24 rows
(a block and a half), the others 32 x 48.
"""
import colorsys

from PIL import Image
from plants import hexc, register

PAINTERS = {}


def _hls(h, l, s):
    r, g, b = colorsys.hls_to_rgb(h / 360, l, s)
    return "#%02X%02X%02X" % (round(r * 255), round(g * 255), round(b * 255))


# A fern green (0.14.6), more saturated than 0.4.2's silver-green and still paler than vanilla's cave vines (whose
# leaves are hue 75 to 92, lightness 0.26 to 0.37, saturation 0.38 to 0.53): a little cooler than theirs, so it reads as
# fresh rather than warm, and far lighter.
HUE = 108
STALK = (_hls(HUE - 6, 0.40, 0.25), _hls(HUE - 4, 0.47, 0.25))                                   # lower, upper stalk
LIT = tuple(_hls(HUE, l, s) for l, s in ((0.55, 0.30), (0.63, 0.32), (0.71, 0.34), (0.80, 0.36)))  # stalk to tip
DIM = tuple(_hls(HUE, l, s) for l, s in ((0.48, 0.27), (0.55, 0.29), (0.63, 0.31), (0.63, 0.31)))
BACK_STALK = _hls(HUE - 6, 0.37, 0.23)
BACK_LIT = tuple(_hls(HUE, l, s) for l, s in ((0.48, 0.27), (0.55, 0.28), (0.62, 0.29), (0.62, 0.29)))
BACK_DIM = tuple(_hls(HUE, l, s) for l, s in ((0.42, 0.24), (0.48, 0.25), (0.55, 0.27), (0.55, 0.27)))

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
                    if length <= 3:  # 0.4.2's shading
                        c = cols[min(3, length)] if k == length - 1 and length >= 2 else cols[k]
                    else:  # the big hanging ferns' long leaflets: stepping out from the stalk to a pale tip
                        c = cols[3] if k == length - 1 else cols[round(k * 2 / (length - 2))]
                    self.put(x, y, c)
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
# Three sizes (0.14.6): the texture's width and height, the fronds' width (centred) and length from the top row, and
# how far the side planes reach out from the rock. gen_data.HANGING_SIZES builds the models from the same numbers.
# (Without a .mcmeta the game keeps a tall texture whole; only an animation's .mcmeta cuts it into frames.)
HANGING_SIZES = {
    "": {"tex": (16, 32), "width": 16, "length": 24, "out": 8},
    "_medium": {"tex": (32, 48), "width": 24, "length": 34, "out": 11},
    "_large": {"tex": (32, 48), "width": 32, "length": 46, "out": 14},
}


def taper(n, widest, bare=0):
    """A hanging frond's leaflet lengths down its n stalk pixels: `bare` pixels of bare stalk where it leaves the rock,
    then a natural triangle: short leaflets at the top of the blade, soon at their widest, and from a third of the way
    down narrowing steadily to one pixel at the tip."""
    out = [0] * min(bare, n)
    m = n - len(out)
    for i in range(m):
        t = i / max(1, m - 1)
        if t < 0.12:
            f = 0.45 + 0.55 * t / 0.12
        elif t < 0.38:
            f = 1.0
        else:
            f = 1 - (t - 0.38) / 0.62
        out.append(max(1, round(widest * f + 0.2)))
    return out


def _down(x, length, spread=0, at=0, bends=()):
    """A stalk falling `length` pixels from (x, 0): straight down `at` pixels, then `spread` steps diagonally aside
    (negative to the left), then straight down, with extra single (row, direction) steps aside at `bends`."""
    d = DOWN_LEFT if spread < 0 else DOWN_RIGHT
    steps = []
    for r in range(length - 1):
        if at <= r < at + abs(spread):
            steps.append(d)
        else:
            steps.append(DOWN)
    for row, bd in bends:
        if 0 <= row < len(steps):
            steps[row] = bd
    return stalk((x, 0), *[(st, 1) for st in steps])


def _fronds(size, specs):
    """Draws (x offset from the middle, length as a share of the size's, spread, rows straight before spreading, widest
    leaflet, back, extra bends) fronds, back ones first, each lit on the side away from the middle."""
    z = HANGING_SIZES[size]
    w, h = z["tex"]
    d = Drawing(w, h)
    c, L = w // 2, z["length"]
    for back in (True, False):
        for dx, share, spread, at, widest, is_back, bends in specs:
            if is_back != back:
                continue
            n = max(4, round(L * share))
            side = -1 if dx + spread > 0 or (dx + spread == 0 and dx > 0) else 1
            d.frond(_down(c + dx, n, spread, at, bends), taper(n, widest, 1 if back else 0), back=back, lit_side=side,
                    first=side)
    return d.img


# The fronds of each size and look, as _fronds' specs: against the rock a dense cluster (as 0.4.2's, its fronds a few
# pixels apart so their leaflets lace together), the middle frond the longest and the outer ones shorter and splayed,
# so the cluster narrows to the middle frond's tip: a triangle hanging from the rock.
_B, _F = True, False
HANG = {
    ("", 1): [(-3, 0.52, -2, 2, 3, _B, ()), (3, 0.46, 2, 3, 3, _B, ()), (0, 0.96, 0, 0, 3, _F, ())],
    ("", 2): [(-4, 0.54, -1, 0, 3, _B, ()), (4, 0.46, 1, 0, 3, _B, ()), (-1, 0.88, -1, 6, 3, _F, ()),
              (2, 0.68, 1, 4, 3, _F, ())],
    ("_medium", 1): [(-4, 0.28, -6, 0, 3, _B, ()), (4, 0.25, 6, 1, 3, _B, ()), (-3, 0.56, -3, 2, 3, _B, ()),
                     (3, 0.5, 3, 3, 3, _B, ()), (0, 0.97, 0, 0, 3, _F, ()), (2, 0.78, 1, 5, 3, _F, ())],
    ("_medium", 2): [(-5, 0.3, -5, 0, 3, _B, ()), (4, 0.26, 6, 0, 3, _B, ()), (-2, 0.52, -4, 2, 3, _B, ()),
                     (3, 0.56, 3, 1, 3, _B, ()), (-1, 0.92, -1, 7, 3, _F, ()), (2, 0.74, 1, 4, 3, _F, ())],
    ("_large", 1): [(-6, 0.26, -9, 0, 3, _B, ()), (6, 0.24, 9, 0, 3, _B, ()), (-5, 0.4, -6, 1, 3, _B, ()),
                    (5, 0.36, 6, 2, 3, _B, ()), (-3, 0.62, -4, 2, 3, _B, ()), (3, 0.58, 4, 3, 3, _B, ()),
                    (0, 0.97, 0, 0, 4, _F, ()), (-2, 0.78, -2, 6, 3, _F, ()), (2, 0.74, 2, 4, 3, _F, ())],
    ("_large", 2): [(-7, 0.27, -8, 0, 3, _B, ()), (6, 0.25, 9, 1, 3, _B, ()), (-4, 0.42, -7, 1, 3, _B, ()),
                    (4, 0.38, 6, 1, 3, _B, ()), (-2, 0.6, -5, 3, 3, _B, ()), (3, 0.62, 3, 2, 3, _B, ()),
                    (-1, 0.95, -1, 9, 4, _F, ()), (1, 0.8, 2, 5, 3, _F, ()), (-3, 0.6, -2, 4, 3, _F, ())],
}
# swung out from the rock: fronds falling clear in front of the others, short at the top and lit on their outer sides
FRONT = {
    ("", 1): [(-3, 0.74, -2, 4, 3, _F, ()), (3, 0.62, 2, 3, 3, _F, ())],
    ("", 2): [(-2, 0.78, -2, 3, 3, _F, ()), (2, 0.6, 2, 6, 3, _F, ())],
    ("_medium", 1): [(-4, 0.66, -3, 4, 3, _F, ()), (4, 0.58, 3, 3, 3, _F, ()), (0, 0.5, 0, 0, 3, _B, ())],
    ("_medium", 2): [(-3, 0.7, -3, 3, 3, _F, ()), (4, 0.54, 3, 6, 3, _F, ()), (-1, 0.48, 1, 2, 3, _B, ())],
    ("_large", 1): [(-5, 0.66, -4, 5, 4, _F, ()), (5, 0.6, 4, 4, 4, _F, ()), (-1, 0.56, -1, 2, 3, _B, ()),
                    (2, 0.46, 2, 2, 3, _B, ())],
    ("_large", 2): [(-4, 0.7, -4, 4, 4, _F, ()), (5, 0.56, 4, 7, 4, _F, ()), (0, 0.52, 1, 3, 3, _B, ()),
                    (-3, 0.4, -2, 1, 3, _B, ())],
}


def _hanging(size, v):
    """The plane against the rock."""
    return _fronds(size, HANG[size, v])


def _hanging_front(size, v):
    """The plane swung out from the rock."""
    return _fronds(size, FRONT[size, v])


def _hanging_side(size, v):
    """Side on, the wall on the right edge (the model shows the right `out` columns): fronds leave the rock, arch out
    and fall, the arch reaching further out on the bigger ferns."""
    z = HANGING_SIZES[size]
    w, h = z["tex"]
    d = Drawing(w, h)
    L, out = z["length"], z["out"]
    arch = {8: 2, 11: 3, 14: 5}[out]
    b = round(L * 0.6)
    d.frond(stalk((w - 1, 4 - v), (DOWN_LEFT, max(1, arch)), (DOWN, b - arch - 1)), taper(b, 3, 1), back=True, first=-1)
    a = round(L * (0.85 if v == 1 else 0.92))
    d.frond(stalk((w - 1, v), (LEFT, 1), (DOWN_LEFT, arch + 1), (DOWN, a - arch - 3)), taper(a, 3), lit_side=-1)
    return d.img


for _size in HANGING_SIZES:
    for _v in (1, 2):
        _s = "" if _v == 1 else f"_{_v}"
        register(PAINTERS, "silver_wall_fern" + _size + _s, lambda z=_size, v=_v: _hanging(z, v))
        register(PAINTERS, "silver_wall_fern" + _size + "_front" + _s, lambda z=_size, v=_v: _hanging_front(z, v))
        register(PAINTERS, "silver_wall_fern" + _size + "_side" + _s, lambda z=_size, v=_v: _hanging_side(z, v))
