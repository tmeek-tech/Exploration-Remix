"""The glow cap's patterns (Deep Gardens round 3, Taran 2026-11-19: "Make a better-looking texture for glow caps"; "The
colors are good"): four looks drawn for Taran to pick from, textures.GLOW_CAP_LOOK naming the one the game uses.

A glow cap is a giant mushroom's cap (GardensDress.mushrooms: a disc of radius 1.6 to 5.2 on a pale stem, domed, one
to three blocks thick, light 13), seen mostly from below and from the side. Each look gives it three faces, top (the
cap skin), side (the rim) and bottom (the underside), each with its own _glow overlay. The colours are textures.py's,
unchanged: a neon's five shades (deep, dim, mid, neon, hot) and, for the darkest pits only, NEAR_BLACK_PIT; the overlay
lifts mid, neon and hot (textures.GLOWING).

A face is painted once, from a fixed seed, as a map of shade names, and then coloured, so its four colours share one
pattern. Everything wraps round the tile's edges, so a face repeats seamlessly; its features are small, of mixed sizes
and spread evenly over the whole tile (best-candidate sampling, never one big shape), so a cap many blocks wide shows no
repeat grid. A cap's rim stacks its side face two or three blocks high, so side faces have no top or bottom band:
their features run up and down.

  A  dark cap skin with bold glowing spots on every face
  B  mottled neon skin, a ribbed rim, dark gills edged with thin neon lines beneath
  C  overlapping lit scales: each scale's free edge glows, shingled down the rim, small scales beneath
  D  bright neon skin with dark pores, a bolete's pore surface beneath
"""
import math
import random

from PIL import Image

import textures as T

NEONS = T.NEONS
FACES = ("top", "side", "bottom")
PIT = T.NEAR_BLACK_PIT  # the darkest hearts of pores and gill gaps, the same in every colour
GLOWS = set(T.GLOWING)  # mid, neon, hot
PIXELS = [(x, y) for y in range(16) for x in range(16)]


# ------------------------------------------------------------------------------------------------------------ helpers

class Face:
    """A face's pattern: a shade name per pixel (pit, deep, dim, mid, neon, hot); reads and writes wrap round the
    tile's edges."""

    def __init__(self, shade):
        self.m = [[shade] * 16 for _ in range(16)]

    def __getitem__(self, p):
        return self.m[p[1] % 16][p[0] % 16]

    def __setitem__(self, p, shade):
        self.m[p[1] % 16][p[0] % 16] = shade

    def share(self, shades):
        return sum(self[p] in shades for p in PIXELS) / 256

    def glowing(self):
        return self.share(GLOWS)


def colour(shade, neon):
    return tuple(PIT) if shade == "pit" else T.neon_shades(neon)[shade]


def paint(face, neon):
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for x, y in PIXELS:
        px[x, y] = colour(face[x, y], neon) + (255,)
    return img


def glow_colours(neon):
    return {T._hex(T.neon_shades(neon)[k]) for k in T.GLOWING}


def overlay(img, neon):
    """The face's _glow overlay: its mid, neon and hot pixels, the rest transparent (textures.glow_layer)."""
    return T.glow_layer(img, glow_colours(neon))


def noise(rng, cx, cy=None):
    """Tileable value noise in [0, 1): cx cells across and cy down, smoothstepped, read at the pixels' middles. With
    cy < cx it is drawn out up and down (streaks)."""
    cy = cy or cx
    g = [[rng.random() for _ in range(cx)] for _ in range(cy)]
    out = {}
    for x, y in PIXELS:
        fx, fy = (x + 0.5) / 16 * cx, (y + 0.5) / 16 * cy
        x0, y0 = int(fx), int(fy)
        tx, ty = fx - x0, fy - y0
        tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
        x1, y1 = (x0 + 1) % cx, (y0 + 1) % cy
        a = g[y0][x0] * (1 - tx) + g[y0][x1] * tx
        b = g[y1][x0] * (1 - tx) + g[y1][x1] * tx
        out[x, y] = a * (1 - ty) + b * ty
    return out


def blend(*parts):
    """A weighted sum of fields: blend((field, weight), ...)."""
    return {p: sum(f[p] * w for f, w in parts) for p in PIXELS}


def grain(rng, field, k):
    """The field with a little per-pixel grain, so its contours break into a natural dither."""
    return {p: v + k * rng.random() for p, v in field.items()}


def quantile(field, q):
    vals = sorted(field.values())
    return vals[max(0, min(255, int(q * 256)))]


def wrapped(ax, ay, bx, by):
    dx, dy = abs(ax - bx) % 16, abs(ay - by) % 16
    return math.hypot(min(dx, 16 - dx), min(dy, 16 - dy))


def offset(ax, ay, bx, by):
    """(a - b) round the tile's edges, each part in [-8, 8)."""
    return (ax - bx + 8) % 16 - 8, (ay - by + 8) % 16 - 8


def checker(x, y, phase=0):
    return (x + y + phase) % 2 == 0


def spread(rng, widths, tries=60, taken=(), snap=True):
    """Places round features of the given widths (pixels), widest first, spread evenly over the tile: of `tries`
    random spots each takes the one with the most room round it (counted round the tile's edges). With snap an odd
    width sits on a pixel's middle and an even one on a pixel corner. Returns [(cx, cy, width)]; `taken` holds
    features already placed."""
    placed = list(taken)
    out = []
    for w in sorted(widths, reverse=True):
        best = None
        for _ in range(tries):
            if snap:
                cx = rng.randrange(16) + (0.5 if w % 2 else 0.0)
                cy = rng.randrange(16) + (0.5 if w % 2 else 0.0)
            else:
                cx, cy = rng.random() * 16, rng.random() * 16
            room = min((wrapped(cx, cy, qx, qy) - (w + qw) / 2 for qx, qy, qw in placed), default=99.0)
            if best is None or room > best[0]:
                best = (room, cx, cy)
        placed.append((best[1], best[2], w))
        out.append((best[1], best[2], w))
    return out


RADIUS = {1: 0.5, 2: 1.0, 3: 1.5, 4: 1.9, 5: 2.35, 6: 2.75, 7: 3.2}


def disc(cx, cy, w):
    """The pixels of a round feature w pixels wide centred at (cx, cy): (x, y, dx, dy), (dx, dy) the pixel's middle
    from the centre."""
    r = RADIUS[w]
    out = []
    for y in range(int(math.floor(cy - r)) - 1, int(math.ceil(cy + r)) + 1):
        for x in range(int(math.floor(cx - r)) - 1, int(math.ceil(cx + r)) + 1):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            if math.hypot(dx, dy) <= r + 0.01:
                out.append((x % 16, y % 16, dx, dy))
    return out


def ring(cells):
    """The pixels round a set of pixels, eight ways, outside it."""
    s = {(x % 16, y % 16) for x, y in cells}
    out = set()
    for x, y in s:
        for a in (-1, 0, 1):
            for b in (-1, 0, 1):
                q = ((x + a) % 16, (y + b) % 16)
                if q not in s:
                    out.add(q)
    return out


# ------------------------------------------------------------------------------------- A: dark skin, bold glowing spots

def glow_spot(face, cx, cy, w, halo="dim"):
    """A bold glowing spot w pixels wide, round, glowing from a hot heart a little up and left of its middle through
    neon to a mid rim; a halo of `halo` round it on the skin, solid four ways and dithered on the diagonals, so the glow
    fades into the dark skin. Returns the spot's pixels."""
    cells = disc(cx, cy, w)
    own = {(x, y) for x, y, _, _ in cells}
    r = RADIUS[w]
    for x, y, dx, dy in cells:
        t = 1.0 - math.hypot(dx, dy) / (r + 0.5) - 0.22 * (dx + dy) / (r + 0.5)  # 1 at the heart, 0 at the rim
        face[x, y] = "hot" if t > 0.66 else ("neon" if t > 0.28 else "mid")
    if w == 2:  # a 2x2 bead
        x0, y0 = int(math.floor(cx - 1)), int(math.floor(cy - 1))
        face[x0, y0], face[x0 + 1, y0], face[x0, y0 + 1], face[x0 + 1, y0 + 1] = "hot", "neon", "neon", "mid"
    if halo:
        for q in ring(own):
            if face[q] in GLOWS:
                continue
            four = any(((q[0] + a) % 16, (q[1] + b) % 16) in own for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if four or checker(*q):
                face[q] = halo
    return own


def dark_skin(rng, streaks=False):
    """The dark cap skin: deep, flecked with dim (a fine field's highest quarter, its contours dithered by grain) and a
    few near-black pits; on the rim the flecks run in short streaks up and down."""
    face = Face("deep")
    if streaks:
        f = grain(rng, blend((noise(rng, 8, 2), 0.6), (noise(rng, 16, 4), 0.4)), 0.25)
    else:
        f = grain(rng, blend((noise(rng, 8), 0.6), (noise(rng, 4), 0.4)), 0.3)
    hi = quantile(f, 0.76)
    for p in PIXELS:
        if f[p] >= hi:
            face[p] = "dim"
    for _ in range(6):
        p = (rng.randrange(16), rng.randrange(16))
        if face[p] == "deep":
            face[p] = "pit"
    return face


def sparkles(face, rng, n, taken, shades=("mid", "mid", "neon")):
    """Single glowing pixels on the skin, spread clear of the spots."""
    for cx, cy, _ in spread(rng, [1] * n, taken=taken):
        face[int(cx), int(cy)] = rng.choice(shades)


def spotted(seed, widths, n_sparkles, streaks=False):
    rng = random.Random(seed)
    face = dark_skin(rng, streaks)
    spots = spread(rng, widths)
    for cx, cy, w in spots:
        glow_spot(face, cx, cy, w)
    sparkles(face, rng, n_sparkles, spots)
    return face


def a_top(seed=3832):
    return spotted(seed, [5, 4, 4, 3, 3, 2, 2], 4)


def a_side(seed=3595):
    return spotted(seed, [4, 4, 3, 3, 2, 2], 3, streaks=True)


def a_bottom(seed=4191):
    return spotted(seed, [4, 3, 3, 3, 2, 2, 2], 6)


# ------------------------------------------------------------------------------- B: mottled neon skin, dark gills

def tones(face, field, cuts, shades, band=0.03, phase=0):
    """Shades the face by a smooth field in levels: below cuts[0] shades[0], and so on up to the last shade above the
    last cut. Within `band` of a cut the shades either side alternate in a checker, an ordered dither in place of a
    hard edge."""
    for p in PIXELS:
        v = field[p]
        k = sum(v >= c for c in cuts)
        for i, c in enumerate(cuts):
            if abs(v - c) < band:
                k = i + (1 if checker(p[0], p[1], phase) else 0)
                break
        face[p] = shades[k]


FRECKLES = {  # a freckle's possible shapes by width, as (dx, dy) from its top-left pixel; a big one's first is deep
    3: (((0, 0), (1, 0), (0, 1)), ((1, 0), (0, 0), (1, 1)), ((0, 1), (0, 0), (1, 1)), ((1, 1), (1, 0), (0, 1)),
        ((1, 0), (0, 0), (1, 1), (2, 1)), ((1, 1), (1, 0), (2, 0), (0, 1)),
        ((0, 1), (0, 0), (1, 1), (1, 2)), ((1, 1), (1, 0), (0, 1), (0, 2))),
    2: (((0, 0), (1, 0)), ((0, 0), (0, 1)), ((0, 0), (1, 1)), ((1, 0), (0, 1))),
    1: (((0, 0),),),
}


def freckle(face, rng, cx, cy, w):
    """A small dark freckle on the skin, of an irregular shape picked at random: three wide, a bent or zigzag run of
    dim round a deep heart; two wide, a dim pair, straight or diagonal; one wide, a dim pixel. No ring round it, so it
    reads as a fleck of pigment and never as a cross."""
    x0, y0 = int(math.floor(cx - w / 2 + 0.5)), int(math.floor(cy - w / 2 + 0.5))
    cells = rng.choice(FRECKLES[w])
    for i, (a, b) in enumerate(cells):
        face[x0 + a, y0 + b] = "deep" if w == 3 and i == 0 else "dim"


def mottle(face, cx, cy, rx, ry, wob, heart="deep"):
    """A soft dark mottle on the neon skin: an irregular blob (its outline wobbled by a fine field) darkening from the
    neon through mid and dim to a deep heart, each step dithered in a checker so it fades with no hard edge."""
    for p in PIXELS:
        dx, dy = offset(p[0] + 0.5, p[1] + 0.5, cx, cy)
        d = math.hypot(dx / rx, dy / ry) + 0.5 * (wob[p] - 0.5)
        level = 0 if d < 0.35 else (1 if d < 0.75 else (2 if d < 1.15 else 3))
        if level < 3 and d > (0.35, 0.75, 1.15)[level] - 0.14 and not checker(*p):
            level += 1  # dither the step outward
        shade = (heart, "dim", "mid", None)[level]
        if shade and ORDER.index(shade) < ORDER.index(face[p]):
            face[p] = shade


ORDER = ("pit", "deep", "dim", "mid", "neon", "hot")


def b_top(seed=4265):
    """Neon skin, mottled: soft mid patches dithered into the neon at their edges, small dark freckles of mixed sizes
    and irregular shapes spread over it, and a few hot flecks."""
    rng = random.Random(seed)
    face = Face("neon")
    f = blend((noise(rng, 8), 0.7), (noise(rng, 4), 0.3))
    tones(face, f, [quantile(f, 0.4)], ("mid", "neon"), band=0.025)
    freckles = spread(rng, [3, 3, 3, 3, 2, 2, 2, 2, 1, 1], snap=True)
    for cx, cy, w in freckles:
        freckle(face, rng, cx, cy, w)
    for cx, cy, _ in spread(rng, [1] * 5, taken=[(x, y, w + 2) for x, y, w in freckles]):
        p = (int(cx), int(cy))
        if face[p] in ("neon", "mid"):
            face[p] = "hot"
    return face


def b_side(seed=3484):
    """The rim, a striate margin: six thin neon lines running down it, the gills' edges showing through the thin skin
    of the margin, evenly spaced and wandering a little, mid where they fade, a few hot beads; between them the dark of
    the gills, dim beside each line and deep between."""
    rng = random.Random(seed)
    face = Face("deep")
    xs = [0.6 + k * 16 / 6 + rng.uniform(-0.25, 0.25) for k in range(6)]
    fade = noise(rng, 4, 2)
    lines = {}
    for k, x0 in enumerate(xs):
        a, ph = rng.uniform(0.2, 0.55), rng.uniform(0, 2 * math.pi)
        for y in range(16):
            x = int(math.floor(x0 + a * math.sin(2 * math.pi * (y + 0.5) / 16 + ph))) % 16
            v = 0.15 + 0.85 * ((fade[x, y] + 0.29 * k) % 1.0)
            lines[x, y] = "neon" if v > 0.3 else ("mid" if v > 0.12 else "dim")
    for (x, y), shade in lines.items():
        face[x, y] = shade
        if shade != "dim" and ((x - 1) % 16, y) not in lines:
            face[x - 1, y] = "dim"
    for p in rng.sample(sorted(p for p, s in lines.items() if s == "neon"), 4):
        face[p] = "hot"
    return face


def kernel(s):
    r = max(1, int(3 * s + 0.5))
    ws = [(d, math.exp(-d * d / (2 * s * s))) for d in range(-r, r + 1)]
    tot = sum(w for _, w in ws)
    return [(d, w / tot) for d, w in ws]


def blur(f, sx, sy):
    kx, ky = kernel(sx), kernel(sy)
    tmp = {(x, y): sum(w * f[(x + d) % 16, y] for d, w in kx) for x, y in PIXELS}
    return {(x, y): sum(w * tmp[x, (y + d) % 16] for d, w in ky) for x, y in PIXELS}


def stripes(rng, s1, s2, stretch=1.0, steps=30):
    """A stripe pattern that settles by itself, like a Turing pattern: a band-pass (a narrow blur less a wide one),
    then a squash, over and over, so stripes come out evenly spaced that fork and end like gills or a fingerprint.
    stretch > 1 blurs more up and down than across, so the stripes run mostly up and down. Returns the last
    band-passed field, smooth across each stripe."""
    u = {p: rng.uniform(-1, 1) for p in PIXELS}
    v = u
    for _ in range(steps):
        a, b = blur(u, s1, s1 * stretch), blur(u, s2, s2 * stretch)
        v = {p: a[p] - b[p] for p in PIXELS}
        m = max(abs(t) for t in v.values()) or 1.0
        u = {p: math.tanh(3.0 * v[p] / m) for p in PIXELS}
    return v


def gills(seed, main=4, short=True):
    """Dark gills edged with thin neon lines, drawn line by line: `main` gills running down the tile, evenly spaced,
    each wandering a pixel or so either way (whole waves over the tile, so they wrap), and between each pair a shorter
    gill (mid, its end dim) that forks off one of them and runs on between them for part of the tile, as real gills
    do. Beside each line the gill's face, dim on its lit left and deep on its right; the gaps deep, near-black at their
    hearts; a few hot beads on the lines."""
    rng = random.Random(seed)
    face = Face("deep")
    lines = {}
    sp = 16 / main
    xs = []
    for k in range(main):
        x0 = (k + 0.5) * sp + rng.uniform(-0.3, 0.3)
        a, b = rng.uniform(0.45, 0.85), rng.uniform(0.0, 0.35)
        p1, p2 = rng.uniform(0, 2 * math.pi), rng.uniform(0, 2 * math.pi)
        xs.append([x0 + a * math.sin(2 * math.pi * (y + 0.5) / 16 + p1) + b * math.sin(4 * math.pi * (y + 0.5) / 16 + p2)
                   for y in range(16)])

    def draw(path, shades):
        for t, (x, y) in enumerate(path):
            xi = int(math.floor(x))
            lines[xi % 16, y % 16] = shades(t)
            if t + 1 < len(path):
                xn = int(math.floor(path[t + 1][0]))
                if abs(xn - xi) >= 2:  # join a step of two across
                    lines[(xi + (1 if xn > xi else -1)) % 16, y % 16] = shades(t)

    for k in range(main):
        draw([(xs[k][y % 16], y) for y in range(17)], lambda t: "neon")
    if short:
        for k in range(main):
            n = (k + 1) % main
            start, length = rng.randrange(16), rng.randrange(6, 11)
            path = []
            for t in range(length):
                y = (start + t) % 16
                right = xs[n][y] + (16 if n == 0 else 0)
                mid = (xs[k][y] + right) / 2
                path.append((xs[k][y] + 1.0 + (mid - xs[k][y] - 1.0) * min(1.0, t / 2.0), start + t))
            draw(path[1:], lambda t, L=length - 1: "dim" if t == L - 1 else "mid")
    for (x, y), shade in lines.items():
        face[x, y] = shade
    for (x, y), shade in list(lines.items()):
        if shade in ("neon", "mid") and ((x - 1) % 16, y) not in lines:
            face[x - 1, y] = "dim"
    for x, y in PIXELS:
        if face[x, y] == "deep" and all(((x + a) % 16, y) not in lines for a in (-1, 1)) and checker(x, y) and rng.random() < 0.5:
            face[x, y] = "pit"
    beads = sorted(p for p, s in lines.items() if s == "neon")
    for p in rng.sample(beads, 3):
        face[p] = "hot"
    return face


def b_bottom(seed=3577):
    return gills(seed)


def b_bottom_maze(seed=3203):
    """Labyrinth gills, as on a maze polypore: a Turing stripe pattern settled over the tile, stripes about four pixels
    apart that wind, fork and end every way at once, so nothing runs in one direction across a cap. Each stripe's right
    and lower edge is a one-pixel neon line (a few hot beads), the rest of the stripe dim, the gaps between deep,
    near-black at their hearts."""
    rng = random.Random(seed)
    v = stripes(rng, 0.62, 1.25)
    on = {p: v[p] > 0 for p in PIXELS}
    face = Face("deep")
    lines = {(x, y) for x, y in PIXELS if on[x, y] and (not on[(x + 1) % 16, y] or not on[x, (y + 1) % 16])}
    low = quantile(v, 0.12)
    for p in PIXELS:
        face[p] = "neon" if p in lines else ("dim" if on[p] else ("pit" if v[p] < low else "deep"))
    for p in rng.sample(sorted(lines), 4):
        face[p] = "hot"
    return face


# ------------------------------------------------------------------------------------- C: overlapping lit scales

def scales(seed, across, rows, ry, band, half=0.0, glints=0.75):
    """Overlapping scales like shingles, dark, their free edges lit: `rows` rows of `across` scales, each row set half
    a scale over. A scale is an ellipse as wide as its place in the row (so a row's scales just touch) and ry of a
    row's height deep; the rows above lie over the rows below, so each scale shows a rounded, U-shaped free edge with
    its top tucked between the two above. The edge glows neon, with a hot glint where it hangs lowest on most scales;
    the glow runs `band` pixels in from the edge (neon, then mid, then dim), the rest of the scale is deep, and the
    strip just under the edge of the scale above lies in its shadow, near-black. A `half` share of the scales are lit a
    shade down."""
    rng = random.Random(seed)
    w, h = 16 / across, 16 / rows
    sc = []
    for j in range(rows):
        for i in range(across):
            cx = (i + 0.5 * (j % 2)) * w + rng.uniform(-0.25, 0.25)
            cy = j * h + rng.uniform(-0.2, 0.2)
            sc.append((cx, cy, 0.5 * w + 0.06, ry * h * rng.uniform(0.95, 1.05), rng.random() < half, rng.random() < glints))
    owner = {}
    for p in PIXELS:
        best = None
        for k, (cx, cy, ax, ay, _, _) in enumerate(sc):
            dx, dy = offset(p[0] + 0.5, p[1] + 0.5, cx, cy)
            if (dx / ax) ** 2 + (dy / ay) ** 2 <= 1.0 and (best is None or dy > best[1]):
                best = (k, dy)  # the scale centred furthest above lies on top
        owner[p] = None if best is None else best[0]

    def lies_over(k, m):  # scale k lies over scale m: m is centred lower
        return m is None or offset(sc[m][0], sc[m][1], sc[k][0], sc[k][1])[1] > 0.5

    # each pixel's steps in from its scale's free edge, four ways within the scale
    depth = {}
    frontier = []
    for x, y in PIXELS:
        k = owner[x, y]
        if k is not None and any(owner[(x + a) % 16, (y + b) % 16] != k and lies_over(k, owner[(x + a) % 16, (y + b) % 16])
                                 for a, b in ((1, 0), (-1, 0), (0, 1))):
            depth[x, y] = 0
            frontier.append((x, y))
    while frontier:
        nxt = []
        for x, y in frontier:
            for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                q = ((x + a) % 16, (y + b) % 16)
                if q not in depth and owner[q] == owner[x, y]:
                    depth[q] = depth[x, y] + 1
                    nxt.append(q)
        frontier = nxt
    face = Face("pit")
    ramp = ["neon", "mid", "dim"][:band] + ["deep"]
    low = {}
    for x, y in PIXELS:
        k = owner[x, y]
        if k is None:
            continue
        shade = ramp[min(depth.get((x, y), 9), len(ramp) - 1)]
        if sc[k][4] and shade != "deep":
            shade = {"neon": "mid", "mid": "dim", "dim": "deep"}[shade]
        above = owner[x, (y - 1) % 16]
        if shade == "deep" and above != k and above is not None and lies_over(above, k):
            shade = "pit"  # the shadow of the scale above
        face[x, y] = shade
        if depth.get((x, y)) == 0 and sc[k][5] and not sc[k][4]:
            dx, dy = offset(x + 0.5, y + 0.5, sc[k][0], sc[k][1])
            if k not in low or (dy - abs(dx)) > low[k][0]:
                low[k] = (dy - abs(dx), x, y)
    for _, x, y in low.values():
        face[x, y] = "hot"
    return face


def c_top(seed=3301):
    """Scales half a block wide; on the top the glow runs three pixels in from each edge (neon, mid, dim), so the
    scales read as lit from their edges."""
    return scales(seed, across=2, rows=4, ry=1.05, band=3)


def c_side(seed=3302):
    """The rim: the same scales hanging like shingles, the glow two pixels in (neon, mid)."""
    return scales(seed, across=2, rows=4, ry=1.05, band=2)


def c_bottom(seed=3303):
    """Under the cap the edges alone glow, one pixel of neon: a scalloped net of neon arcs over dark scales."""
    return scales(seed, across=2, rows=4, ry=1.05, band=1)


# -------------------------------------------------------------------------------- D: bright skin, dark pores

def pits(seed, widths, stretch=1.0, ridge=0.55, lattice=None):
    """Bright skin full of dark pits, like a morel's or a sponge's: pits of the given widths spread evenly (or on a
    jittered lattice), each an irregular round hole (stretch > 1 draws them out up and down) near-black down its
    shadowed upper left, deep, and dim at its lit lower-right wall; between them the glowing skin, mid where it dips
    into a pit, neon on the ridges, hot where a ridge stands furthest from any pit."""
    rng = random.Random(seed)
    if lattice:
        nx, ny = lattice
        seeds = [((i + 0.5 * (j % 2) + 0.5) * 16 / nx + rng.uniform(-0.45, 0.45),
                  (j + 0.5) * 16 / ny + rng.uniform(-0.45, 0.45), rng.choice(widths)) for j in range(ny) for i in range(nx)]
    else:
        seeds = spread(rng, widths, snap=False)
    wob = noise(rng, 8)
    face = Face("neon")
    clear, inside = {}, {}
    for p in PIXELS:
        best, bk = 99.0, None
        for k, (cx, cy, w) in enumerate(seeds):
            dx, dy = offset(p[0] + 0.5, p[1] + 0.5, cx, cy)
            d = math.hypot(dx, dy / stretch) - 0.5 * w + 0.6 * (wob[p] - 0.5)
            if d < best:
                best, bk = d, (k, dx, dy)
        clear[p] = best
        inside[p] = bk
    for p in PIXELS:
        c = clear[p]
        k, dx, dy = inside[p]
        if c < 0:
            lean = (dx + dy / stretch) / (0.5 * seeds[k][2] + 0.01)  # toward the lit lower-right wall
            if c < -0.9 and lean < 0.3:
                face[p] = "pit"
            elif lean > 0.55 and c > -0.6:
                face[p] = "dim"
            else:
                face[p] = "deep"
        elif c < ridge:
            face[p] = "mid"
    for p in PIXELS:
        if clear[p] > ridge + 0.6 and all(clear[p] >= clear[(p[0] + a) % 16, (p[1] + b) % 16] for a in (-1, 0, 1) for b in (-1, 0, 1)):
            face[p] = "hot"
    return face


def d_top(seed=4319):
    """Bright skin full of dark pores of mixed sizes, spread evenly, a sponge's or a morel's."""
    return pits(seed, [4, 4, 3, 3, 3, 3, 2, 2, 2, 2, 2, 1, 1])


def d_side(seed=4014):
    """The rim: the pores drawn out up and down into slots, as a morel's ridges run."""
    return pits(seed, [3, 3, 3, 2, 2, 2, 2, 2, 1], stretch=1.9)


def d_bottom(seed=4712):
    """A bolete's pore surface: small round pores on a jittered lattice, a fine glowing mesh between."""
    return pits(seed, [3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2], ridge=0.4)


# ----------------------------------------------------------------------------------------------------------- the table

LOOKS = {
    "A": {"name": "Dark skin, bold spots", "top": a_top, "side": a_side, "bottom": a_bottom},
    "B": {"name": "Mottled skin, dark gills", "top": b_top, "side": b_side, "bottom": b_bottom},
    "C": {"name": "Overlapping lit scales", "top": c_top, "side": c_side, "bottom": c_bottom},
    "D": {"name": "Bright skin, dark pores", "top": d_top, "side": d_side, "bottom": d_bottom},
}

_cache = {}


def pattern(look, face):
    key = (look, face)
    if key not in _cache:
        _cache[key] = LOOKS[look][face]()
    return _cache[key]


def texture(look, face, neon):
    """The face's texture in one colour (RGBA, 16x16)."""
    return paint(pattern(look, face), neon)


def name(face, neon):
    return f"block/glow_cap_{neon}_{face}"


def _colours(im):
    px = im.load()
    return [px[x, y] for y in range(im.size[1]) for x in range(im.size[0])]


def check():
    """Every texture and overlay of every look against textures.cool_problems, and no grey leaning warm (as
    vent_sheet.colour_check); every overlay not empty and holding only the glowing shades. Returns the problems."""
    problems = []
    for look in LOOKS:
        for face in FACES:
            for neon in NEONS:
                img = texture(look, face, neon)
                glow = overlay(img, neon)
                for n, im in ((name(face, neon), img), (name(face, neon) + "_glow", glow)):
                    problems += [(look, n) + p for p in T.cool_problems(n, im)]
                    for c in _colours(im):
                        if c[3] and T.hue_sat(c)[0] is None and (c[0] > c[2] or c[1] > c[2]):
                            problems.append((look, n, "grey leaning warm", c[:3]))
                if glow.getbbox() is None:
                    problems.append((look, name(face, neon), "empty overlay"))
                keep = glow_colours(neon)
                if any(c[3] and T._hex(c) not in keep for c in _colours(glow)):
                    problems.append((look, name(face, neon), "overlay holds a colour that is not mid, neon or hot"))
    return problems
