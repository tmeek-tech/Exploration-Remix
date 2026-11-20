"""The glow mat's patterns (Deep Gardens round 3, Taran 2026-11-19: "Dither the glow mat so there's no hard boundary
between the bright patches and the rock around it. Also retexture them so they look good"; "The colors are good"):
four looks drawn for Taran to pick from, textures.GLOW_MAT_LOOK naming the one the game uses.

Each look is a film drawn over vanilla deepslate (the mat's top face over deepslate_top, its sides over deepslate): a
16x16 RGBA picture with hard alpha, every opaque pixel one of the neon's five shades (textures.neon_shades: deep, dim,
mid, neon, hot), and its _glow overlay, the film's mid, neon and hot pixels (textures.GLOWING), drawn again at full
brightness. Each look has a full mat and an edge mat (about 40 % of the full mat's film) for a patch's outer ring (the
thin glow mat), each with a top and a side face. A look's four colours share one pattern (one seed).

  A  Colonies  soft organic colonies, neon with hot hearts, their rims dithered out through mid, dim and deep
  B  Threads   glowing threads joining small nodes, over mostly bare rock
  C  Nylium    a fine dim carpet whose tufts' tips catch the glow, with dark specks and a few bright florets
  D  Spores    clusters of round spore dots of mixed sizes, densest in each cluster's middle

The painters are seeded and deterministic: each look's seed is the one of 40 whose top spreads its glow most evenly
over the tile's columns, rows and diagonals (stripe_score), since an empty band would run through a floor of mats as a
stripe. In the game each mat takes one of eight model variants (gen_data's option turns: turned about y by 0, 90, 180
or 270 degrees, plain or mirrored, its deepslate and film turning together), so a floor of mats shows no stripes.
"""
import math
import random

import numpy as np
from PIL import Image

import textures as T

BARE, DEEP, DIM, MID, NEON, HOT = -1, 0, 1, 2, 3, 4
SHADES = ("deep", "dim", "mid", "neon", "hot")
FACES = ("top", "side", "edge_top", "edge_side")


# ================================================================================================ shared helpers
# A pattern is a 16x16 grid (rows of columns) of shade levels, BARE where the deepslate shows through.

def grid():
    return [[BARE] * 16 for _ in range(16)]


def put(g, x, y, v, wrap_y=True, mode="set"):
    """Sets pixel (x, y), wrapping across the tile's sides (and top and bottom unless wrap_y is off: a side face's
    rows end at the block's edges). mode 'max' only brightens, 'bare' only fills bare pixels."""
    x %= 16
    if wrap_y:
        y %= 16
    elif not 0 <= y < 16:
        return
    if mode == "max" and g[y][x] >= v:
        return
    if mode == "bare" and g[y][x] != BARE:
        return
    g[y][x] = v


def get(g, x, y, wrap_y=True):
    x %= 16
    if wrap_y:
        y %= 16
    elif not 0 <= y < 16:
        return None
    return g[y][x]


def wdist(ax, ay, bx, by, wrap_y=True):
    """Distance on the tile, wrapping across its sides (and top and bottom unless wrap_y is off)."""
    dx = abs(ax - bx) % 16
    dx = min(dx, 16 - dx)
    dy = abs(ay - by)
    if wrap_y:
        dy %= 16
        dy = min(dy, 16 - dy)
    return math.hypot(dx, dy)


def blue_noise(seed, n=16, sigma=1.5):
    """A tileable blue-noise threshold map in (0, 1) (void and cluster on the wrapped tile): even, unclumped dither
    without the cross-hatch of an ordered matrix."""
    rng = np.random.default_rng(seed)
    i = np.arange(n)
    d = np.minimum(i, n - i).astype(float)
    K = np.fft.rfft2(np.exp(-(d[:, None] ** 2 + d[None, :] ** 2) / (2 * sigma ** 2)))

    def energy(b):
        return np.fft.irfft2(np.fft.rfft2(b) * K, s=b.shape)

    b = np.zeros((n, n))
    b.flat[rng.choice(n * n, n * n // 10, replace=False)] = 1
    for _ in range(2000):
        c = int(np.argmax(np.where(b > 0, energy(b), -np.inf)))
        b.flat[c] = 0
        v = int(np.argmin(np.where(b > 0, np.inf, energy(b))))
        b.flat[v] = 1
        if c == v:
            break
    rank = np.zeros(n * n, int)
    w, r = b.copy(), int(b.sum())
    while r > 0:
        c = int(np.argmax(np.where(w > 0, energy(w), -np.inf)))
        w.flat[c], r = 0, r - 1
        rank[c] = r
    w, r = b.copy(), int(b.sum())
    while r < n * n:
        if r < n * n // 2:
            v = int(np.argmin(np.where(w > 0, np.inf, energy(w))))
        else:
            v = int(np.argmax(np.where(w > 0, -np.inf, energy(1 - w))))
        w.flat[v] = 1
        rank[v], r = r, r + 1
    m = (rank.reshape(n, n) + 0.5) / (n * n)
    return [[float(m[y, x]) for x in range(n)] for y in range(n)]


BLUE = blue_noise(7001)


def tau(x, y, shift=(0, 0)):
    """The dither threshold of pixel (x, y); a shift reads the same map elsewhere, so two layers do not dither alike."""
    return BLUE[(y + shift[1]) % 16][(x + shift[0]) % 16]


def scatter(rng, n, spacing, box=None, tries=6000, wrap_y=True, sizes=None):
    """Up to n points (x, y[, size]) spaced at least `spacing` (plus their sizes) apart on the wrapped tile; box =
    (x0, y0, x1, y1) keeps them inside it."""
    out = []
    for _ in range(tries):
        s = rng.choice(sizes) if sizes else 0.0
        x0, y0, x1, y1 = box or (0, 0, 16, 16)
        p = (x0 + rng.random() * (x1 - x0), y0 + rng.random() * (y1 - y0), s)
        if all(wdist(p[0], p[1], q[0], q[1], wrap_y) >= spacing + s + q[2] for q in out):
            out.append(p)
            if len(out) == n:
                break
    return out


def even_points(rng, n, box=None, wrap_y=True, k=40, strata=False):
    """n points spread evenly over the wrapped tile (best candidate: each new point is the one of k tries farthest
    from those already placed), so features are neither clumped nor in rows. strata also puts each point in a column
    band and a row band of its own (n bands each way), so no band of the tile is left empty: an empty band would run
    through a floor of these blocks as a stripe, since the blocks' turned variants keep it."""
    x0, y0, x1, y1 = box or (0, 0, 16, 16)
    pts, free_x, free_y = [], list(range(n)), list(range(n))
    for _ in range(n):
        best, bd, bij = None, -1.0, None
        for _ in range(k if pts else 1):
            if strata:
                i, j = rng.choice(free_x), rng.choice(free_y)
                p = (x0 + (i + rng.random()) * (x1 - x0) / n, y0 + (j + rng.random()) * (y1 - y0) / n)
            else:
                i = j = None
                p = (x0 + rng.random() * (x1 - x0), y0 + rng.random() * (y1 - y0))
            d = min((wdist(*p, *q, wrap_y) for q in pts), default=0.0)
            if d > bd:
                best, bd, bij = p, d, (i, j)
        pts.append(best)
        if strata:
            free_x.remove(bij[0])
            free_y.remove(bij[1])
    return pts


def line_px(pts):
    """A pixel-perfect 8-connected path through integer points: gaps filled, L-shaped elbows removed."""
    out = []
    for p in pts:
        if out:
            a = out[-1]
            while max(abs(p[0] - a[0]), abs(p[1] - a[1])) > 1:
                a = (a[0] + (p[0] > a[0]) - (p[0] < a[0]), a[1] + (p[1] > a[1]) - (p[1] < a[1]))
                out.append(a)
            if p == out[-1]:
                continue
        out.append(p)
        if len(out) >= 3:
            a, b, c = out[-3], out[-2], out[-1]
            if max(abs(a[0] - c[0]), abs(a[1] - c[1])) == 1 and (a[0] == b[0] or a[1] == b[1]) and (b[0] == c[0] or b[1] == c[1]):
                del out[-2]
    return out


def clear_border(g, keep_top=False):
    """Bares the tile's outermost pixels (an edge mat's growth stays off the block's edges, so it is never cut off
    against bare deepslate); keep_top keeps row 0, where a side face meets the top."""
    for k in range(16):
        for x, y in ((k, 0), (k, 15), (0, k), (15, k)):
            if keep_top and y == 0 and 0 < x < 15:
                continue
            g[y][x] = BARE
    return g


def stats(g, rows=16):
    film = sum(1 for y in range(rows) for x in range(16) if g[y][x] != BARE)
    glow = sum(1 for y in range(rows) for x in range(16) if g[y][x] >= MID)
    return film / (16 * rows), glow / (16 * rows)


def stripe_score(g):
    """How unevenly a pattern's glow falls across the tile: the spread of its lit pixels over 4-pixel bands of its
    columns, rows and both diagonals ((max - min) / mean, in the worst direction). A band the glow leaves empty runs
    through a floor of these blocks as a stripe, whichever way each block is turned."""
    lit = [(x, y) for y in range(16) for x in range(16) if g[y][x] >= MID]
    worst = 0.0
    for key in (lambda x, y: x, lambda x, y: y, lambda x, y: (x + y) % 16, lambda x, y: (x - y) % 16):
        c = [0] * 16
        for x, y in lit:
            c[key(x, y)] += 1
        b = [sum(c[(i + k) % 16] for k in range(4)) for i in range(16)]
        worst = max(worst, (max(b) - min(b)) / max(1e-9, sum(b) / 16))
    return worst


def even_seed(make, seeds):
    """The seed among `seeds` whose pattern make(seed) spreads its glow most evenly (stripe_score). The pick is fixed,
    so the painter stays deterministic."""
    return min(seeds, key=lambda s: (stripe_score(make(s)), s))


# ================================================================================================ A: colonies

def ranks(field):
    """Each pixel's rank in a field, 0 (lowest) to 1 (highest): a level cut at r leaves exactly that share below."""
    order = sorted(field, key=lambda p: (field[p], p))
    return {p: k / (len(order) - 1) for k, p in enumerate(order)}


def window(x, y, margin=2.5, floor=0.55, wrap_y=True):
    """1 in the tile's middle, falling to `floor` at its edges: keeps an edge mat's growth off the block's edges, so
    it is not cut off against bare deepslate."""
    def w(c):
        d = min(c + 0.5, 16 - (c + 0.5))
        return floor + (1 - floor) * min(1.0, d / margin)
    return w(x) * (w(y) if wrap_y else 1.0)


def blob_noise(rng, specs, wrap_y=True):
    """Sparse Gaussian noise on the wrapped tile: blobs at spread-out centres, of mixed sizes and signs, summed. It has
    no lattice (gradient and value noise leave one at this size) and tiles seamlessly. specs = [(count, spacing,
    (sigma lo, hi), (weight lo, hi)), ...]. Returns f(x, y)."""
    blobs = []
    for k, (n, spacing, (s0, s1), (w0, w1)) in enumerate(specs):
        if spacing in (None, "strata"):
            pts = even_points(rng, n, wrap_y=wrap_y, strata=spacing == "strata")
        else:
            pts = [(x, y) for x, y, _ in scatter(rng, n, spacing, wrap_y=wrap_y)]
        for x, y in pts:
            blobs.append((x, y, rng.uniform(s0, s1), rng.uniform(w0, w1), k))
    return blob_sum(blobs, wrap_y)


def blob_sum(blobs, wrap_y=True):
    """f(x, y): the sum of Gaussian blobs (x, y, sigma, weight, spec); f.blobs keeps them."""
    def f(x, y):
        return sum(w * math.exp(-wdist(x, y, cx, cy, wrap_y) ** 2 / (2 * s * s)) for cx, cy, s, w, _ in blobs)
    f.blobs = blobs
    return f


def dist_to(mask, wrap_y=True, reach=4):
    """Each pixel's distance to the nearest pixel of a set (0 inside it), on the wrapped tile, up to `reach`."""
    out = {}
    for y in range(16):
        for x in range(16):
            best = reach + 1.0
            if (x, y) in mask:
                best = 0.0
            else:
                for dy in range(-reach, reach + 1):
                    yy = y + dy
                    if not wrap_y and not 0 <= yy < 16:
                        continue
                    for dx in range(-reach, reach + 1):
                        if ((x + dx) % 16, yy % 16 if wrap_y else yy) in mask:
                            best = min(best, math.hypot(dx, dy))
            out[x, y] = best
    return out


def rim_thin(d, t, t2):
    """A thinner rim, for the edge mat: a pixel out half mid, half dim; two out a sparse dim and deep."""
    if d <= 1.0:
        return MID if t < 0.5 else (DIM if t < 0.85 else DEEP)
    if d <= 1.5:
        return DIM if t < 0.3 else (DEEP if t < 0.55 else BARE)
    if d <= 2.3:
        return DEEP if t < 0.22 else BARE
    return BARE


def rim(d, t, t2):
    """The film round a colony core at distance d, dithered by thresholds t and t2: a pixel out mostly mid (some dim),
    two out a speckle of dim and deep, three out a sparse deep, then bare: the glow fades in a speckle rather than
    ending in a line."""
    if d <= 1.0:
        return MID if t < 0.66 else DIM
    if d <= 1.5:
        return MID if t < 0.14 else (DIM if t < 0.6 else (DEEP if t < 0.85 else BARE))
    if d <= 2.3:
        return DIM if t < 0.2 else (DEEP if t < 0.48 else BARE)
    if d <= 3.2:
        return DEEP if t < 0.16 else BARE
    return BARE


def _a_field(seed, edge=False):
    """The colonies' field: six colonies spread evenly, one to each column and row band, with a fine lumpy detail.
    For the edge mat, only the colonies whose middles lie well inside the block, shrunk."""
    f = blob_noise(random.Random(seed), [(6, "strata", (1.1, 1.6), (0.85, 1.15)), (44, 0.85, (0.45, 0.65), (-0.24, 0.24))])
    if edge:
        inner = [b for b in f.blobs if b[4] == 0 and 3.5 <= b[0] <= 12.5 and 3.5 <= b[1] <= 12.5]
        inner = sorted(inner, key=lambda b: math.dist(b[:2], (8, 8)))[:A_EDGE_COLONIES]
        f = blob_sum([(x, y, s * 0.85, w, k) for x, y, s, w, k in inner] + [b for b in f.blobs if b[4] == 1])
    return {(x, y): f(x + 0.5, y + 0.5) for y in range(16) for x in range(16)}


A_EDGE_COLONIES = 3


def _a_top(seed):
    g = grid()
    _colony_film(g, _a_field(seed), 0.25, hot_share=0.025, mid_share=0.18)
    return g




def _colony_film(g, f, core_share, wrap_y=True, hot_share=0.035, mid_share=0.22, thin=False):
    """Paints colonies: the top `core_share` of the field are the cores (neon, their highest `hot_share` of the tile
    hot, their lowest `mid_share` of themselves a dithered mid mottle), dithered rims round them (rim())."""
    r = ranks(f)
    core = {p for p, v in r.items() if v >= 1 - core_share}
    d = dist_to(core, wrap_y)
    for (x, y), v in r.items():
        if (x, y) in core:
            inner = (v - (1 - core_share)) / core_share  # 0 at the core's edge, 1 at its peak
            if v >= 1 - hot_share:
                lvl = HOT
            elif inner < mid_share and tau(x, y, (5, 1)) < 0.55:
                lvl = MID
            else:
                lvl = NEON
        else:
            lvl = (rim_thin if thin else rim)(d[x, y], tau(x, y), tau(x, y, (9, 6)))
        g[y][x] = lvl


def look_a(face):
    """Colonies: small lobed glowing colonies that touch and merge, neon with hot hearts and a mid mottle, each rimmed
    by a dithered fall-off (mostly mid a pixel out, dim two out, sparse deep three out) into the rock between them.
    The edge mat keeps the same colonies whose middles lie well inside the block, shrunk, so a patch thins out rather
    than changing pattern. On the side the colonies come over the edge and down the side, thinning out about half way
    (round 4, _a_side)."""
    g = grid()
    if face == "top":
        return _a_top(seed_for("A"))
    if face == "edge_top":
        f = {p: v - 0.9 * (1 - window(*p, margin=2.5, floor=0.0)) for p, v in _a_field(seed_for("A"), True).items()}
        _colony_film(g, f, 0.13, hot_share=0.012, mid_share=0.25, thin=True)
        return clear_border(g)
    return _a_side(A_SIDE_REACH if face == "side" else A_EDGE_SIDE_REACH, thin=face == "edge_side")


# Round 4 (Taran, 2026-11-19: "Have more dithering for glow mats and have the glow come further down the sides of the
# block"; card: "About half way"): how many rows down a mat's side its colonies reach before they have thinned out,
# and the edge mat's, which is thinner.
A_SIDE_REACH, A_EDGE_SIDE_REACH = 9.0, 6.0


def _a_side(reach, thin=False, seed=3121):
    """A mat's side: the colonies themselves coming over the edge and down the side, as full as the top's at the edge
    and thinning with depth (a bar that rises with the row) until none are left by `reach` rows, their rims dithered
    out through mid, dim and deep as on the top, and a speckle of dim and deep trailing a quarter past the reach. Hot
    hearts only in the upper half. The edge mat's side is the same, thinner (fewer colonies, thin rims), kept off the
    side's left and right edges so it never stops against a bare neighbour."""
    f0 = blob_noise(random.Random(seed), [(9, "strata", (1.0, 1.5), (0.85, 1.15)), (44, 0.85, (0.45, 0.65), (-0.24, 0.24))],
                    wrap_y=False)
    f0 = {(x, y): f0(x + 0.5, y + 0.5) for y in range(16) for x in range(16)}
    vals = sorted(f0.values())
    bar0 = vals[int(len(vals) * (0.8 if thin else 0.62))]
    span = vals[-1] - bar0
    f = {}
    for (x, y), v in f0.items():
        dep = (y + 0.5) / reach
        # the bar rises with depth; the top row is lifted a little, where the top face's film comes over the edge
        f[x, y] = v - bar0 - span * 1.15 * dep ** 1.4 + (0.35 * span if y == 0 else 0.0)
    core = {p for p, v in f.items() if v > 0}
    d = dist_to(core, wrap_y=False)
    g = grid()
    for y in range(16):
        for x in range(16):
            t, t2 = tau(x, y), tau(x, y, (9, 6))
            v = f[x, y]
            dep = (y + 0.5) / reach
            if (x, y) in core:
                if not thin and dep < 0.6 and v > 0.55 * span * (1 - dep):
                    lvl = HOT if t < 0.5 else NEON
                elif v < 0.12 * span and t < 0.6:
                    lvl = MID
                else:
                    lvl = NEON
            else:
                lvl = (rim_thin if thin else rim)(d[x, y], t, t2)
                if lvl == BARE and dep < 1.25:
                    p = 0.5 * (1.0 - dep / 1.25) * (1.0 if d[x, y] < 4 else 0.5) * (0.6 if thin else 1.0)
                    if t2 < 0.35 * p:
                        lvl = DIM
                    elif t2 < p:
                        lvl = DEEP
            g[y][x] = lvl
    if thin:
        clear_border(g, keep_top=True)
    return g


# ================================================================================================ B: threads

def _stain(g, rng, p, wrap_y=True):
    """Deep and dim pixels beside the threads (lit by the world): a stain the threads leave in the rock."""
    lit = [(x, y) for y in range(16) for x in range(16) if g[y][x] >= MID]
    for x, y in lit:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if get(g, x + dx, y + dy, wrap_y) == BARE and rng.random() < p:
                put(g, x + dx, y + dy, DEEP if rng.random() < 0.75 else DIM, wrap_y)


def _grow(rng, nodes, plan, wrap_y=True, rows=16, drift_sd=0.1):
    """Threads grown like hyphae: each leaves its node at an angle and wanders on (its heading drifts a little every
    half pixel), until it meets another thread (a join) or has grown its length (a free end). plan[k] = [(angle,
    length), ...] for node k. Returns [(node, pixels, joined)]: the pixels unwrapped, from the node outwards."""
    def key(p):
        return (p[0] % 16, p[1] % 16 if wrap_y else p[1])
    owner = {}
    for k, (nx, ny) in enumerate(nodes):
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                owner[key((int(nx) + dx, int(ny) + dy))] = ("n", k)
    out = []
    order = [(k, j) for k in range(len(nodes)) for j in range(len(plan[k]))]
    order.sort(key=lambda kj: (kj[1], kj[0]))  # every node's first thread, then every node's second, ...
    for tid, (k, j) in enumerate(order):
        angle, length = plan[k][j]
        x, y = int(nodes[k][0]) + 0.5, int(nodes[k][1]) + 0.5
        pts, joined, drift = [(int(nodes[k][0]), int(nodes[k][1]))], False, 0.0
        for _ in range(60):
            if len(pts) > length:
                break
            drift = 0.7 * drift + rng.gauss(0, drift_sd)
            angle += drift
            x, y = x + 0.5 * math.cos(angle), y + 0.5 * math.sin(angle)
            if not wrap_y and not 0 <= y < rows:
                break
            p = (math.floor(x), math.floor(y))
            if p == pts[-1]:
                continue
            pts.append(p)
            if len(pts) > 2:
                hits = [owner.get(key((p[0] + dx, p[1] + dy))) for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1))]
                if any(h is not None and h != ("t", tid) and h != ("n", k) for h in hits):
                    joined = True
                    break
        pts = line_px(pts)
        for p in pts[2:]:
            owner[key(p)] = ("t", tid)
        out.append((k, pts, joined))
    return out


def _paint_threads(g, threads, wrap_y=True, fade_len=2):
    for k, pts, joined in threads:
        n = len(pts)
        for i, (x, y) in enumerate(pts[1:], 1):
            rest = n - 1 - i
            if not joined and rest < fade_len:
                lvl = (DEEP, DIM, DIM)[rest]
            elif i == 1 or (joined and rest == 0):
                lvl = NEON
            else:
                lvl = NEON if tau(x, y, (7, 3)) < 0.07 else MID
            put(g, x, y, lvl, wrap_y, "max")


def _paint_node(g, x, y, big, wrap_y=True):
    """A node: a hot pixel (its threads start in neon, so a node with four threads is a small star); a big node has a
    neon pixel below right as well."""
    x, y = int(x), int(y)
    if big:
        put(g, x + 1, y + 1, NEON, wrap_y, "max")
    put(g, x, y, HOT, wrap_y)


def _b_plan(rng, nodes, lengths):
    plan = []
    for k in range(len(nodes)):
        n = rng.choice((3, 4, 4))
        a0 = rng.random() * 2 * math.pi
        plan.append([(a0 + j * 2 * math.pi / n + rng.uniform(-0.45, 0.45), rng.randrange(*lengths)) for j in range(n)])
    return plan


def _b_top(seed, edge=False):
    """The top: four nodes and their threads; the edge mat keeps the three nodes nearest the middle, their threads cut
    short."""
    g = grid()
    rng = random.Random(seed)
    nodes = [(x, y) for x, y, _ in scatter(rng, 4, 5.4)]
    plan = _b_plan(rng, nodes, (4, 9))
    big = {0, 2}
    if edge:
        keep = sorted(range(len(nodes)), key=lambda k: math.dist(nodes[k], (8, 8)))[:3]
        nodes = [nodes[k] for k in keep]
        plan = [[(a, max(3, int(L * 0.45))) for a, L in plan[k]] for k in keep]
        big = set()
    threads = _grow(random.Random(seed + 4), nodes, plan)
    _paint_threads(g, threads, fade_len=2)
    for k, (x, y) in enumerate(nodes):
        _paint_node(g, x, y, k in big)
    _stain(g, random.Random(seed + 1), 0.14 if edge else 0.18)
    return g


def look_b(face):
    """Threads: thin glowing threads grown like hyphae from small hot nodes, three or four to a node, wandering a
    little, joining where they meet (neon at the join) and thinning out through dim and deep where they end free, with
    a faint deep stain beside them, over mostly bare rock. The edge mat keeps the three nodes nearest the middle,
    their threads cut short and fading; on the side threads come over the edge and hang down on their own, the
    longest ending in a node, the rest fading."""
    if face in ("top", "edge_top"):
        return _b_top(seed_for("B"), face == "edge_top")
    g = grid()
    full = face == "side"
    srng = random.Random(3203)
    xs = sorted(x for x, _ in even_points(srng, 4 if full else 2, box=(0, 0, 16, 0.01), wrap_y=False))
    lengths = (7, 3, 5, 4) if full else (4, 3)
    roots = [(int(x), 0) for x in xs]
    # each thread hangs on its own, leaning a little, alternately one way and the other (hanging threads do not join)
    threads = []
    for k, (root, L) in enumerate(zip(roots, lengths)):
        lean = (-1) ** k * srng.uniform(0.1, 0.35)
        t = _grow(random.Random(3206 + k), [root], [[(math.pi / 2 + lean, L)]], wrap_y=False, drift_sd=0.1)[0]
        threads.append((k, t[1], False))
    longest = max(range(len(threads)), key=lambda k: len(threads[k][1]))
    ends = {longest} if full else set()  # the longest thread ends in a small node, the rest fade out
    _paint_threads(g, [(k, pts, joined or k in ends) for k, pts, joined in threads], wrap_y=False, fade_len=2)
    for k, pts, joined in threads:
        put(g, pts[0][0], 0, NEON, wrap_y=False)
        if k in ends:
            ex, ey = pts[-1]
            put(g, ex, ey, HOT, wrap_y=False)
            put(g, ex + 1, ey, NEON, wrap_y=False, mode="max")
    for x in range(16):  # the threads' stain along the lip
        if g[0][x] == BARE and tau(x, 0, (2, 9)) < (0.35 if full else 0.15):
            g[0][x] = DEEP
    _stain(g, random.Random(3204), 0.15, wrap_y=False)
    return g


# ================================================================================================ C: nylium
# A fine even film. Lit through and through it is a flat neon slab in the dark, whose outline follows the block grid
# (work/c_lit_carpet.png), so here the carpet itself is dim and only its tufts' tips and the florets glow.

C_TUFT, C_DRIFT = 0.7, 2.4  # the blur (pixels) of the tufts and of the slow drift that thins and thickens them
C_LIT, C_TIP, C_BARE, C_DEEP = 0.2, 0.04, 0.2, 0.14  # shares: lit tips (mid), their brightest (neon), bare, deep
C_EDGE_KEEP = 0.36  # the share of the edge mat its torn pieces cover


def smooth_noise(seed, sigma, rows=16):
    """White noise blurred by a Gaussian on the wrapped tile (by FFT): band-limited, lattice-free, seamless, ranked
    to (0, 1). rows > 16 blurs on a taller wrapped strip whose top 16 rows are used (a side face, whose rows end at
    the block's edges). Returns {(x, y): rank}."""
    rng = np.random.default_rng(seed)
    w = rng.standard_normal((rows, 16))
    fy, fx = np.fft.fftfreq(rows)[:, None], np.fft.fftfreq(16)[None, :]
    k = np.exp(-2 * (math.pi * sigma) ** 2 * (fx ** 2 + fy ** 2))
    a = np.real(np.fft.ifft2(np.fft.fft2(w) * k))[:16]
    return ranks({(x, y): float(a[y, x]) for y in range(16) for x in range(16)})


def _dark_carpet(g, cells, drift, tuft, fine, lit=C_LIT, tip=C_TIP, bias=None):
    """The nylium carpet over `cells`: dim, mottled with deep, with bare specks where the rock shows through, and its
    tufts' tips catching the glow (mid, the brightest neon), clumped a pixel or two by the tuft field and thinned
    where a slow drift is low. bias adds to the tuft field (a side face's fringe is lit most at the lip)."""
    for (x, y) in cells:
        t = tuft[x, y] + 0.25 * (drift[x, y] - 0.5) + (bias(x, y) if bias else 0.0)
        f = fine[x, y]
        if t >= 1 - tip:
            g[y][x] = NEON
        elif t >= 1 - lit:
            g[y][x] = MID
        elif f < C_BARE:
            g[y][x] = BARE
        elif f < C_BARE + C_DEEP or (t < 0.25 and tau(x, y, (6, 2)) < 0.4):
            g[y][x] = DEEP
        else:
            g[y][x] = DIM


FLORETS = {  # florets, lit from the top left: a hot heart, neon petals, mid where the light falls off
    "cross": ((0, 0, HOT), (1, 0, NEON), (-1, 0, NEON), (0, -1, NEON), (0, 1, MID)),
    "pair": ((0, 0, HOT), (1, 0, NEON), (0, 1, MID)),
    "bud": ((0, 0, HOT), (1, 1, MID)),
    "spark": ((0, 0, NEON), (1, 0, MID)),
}


def _floret(g, x, y, kind, wrap_y=True):
    """A floret at (x, y), set off from the tufts round it by a dim ring."""
    cells = FLORETS[kind]
    own = {(dx, dy) for dx, dy, _ in cells}
    for dx, dy, v in cells:
        put(g, x + dx, y + dy, v, wrap_y)
    for dx, dy, _ in cells:
        for ex, ey in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (dx + ex, dy + ey) not in own:
                q = get(g, x + dx + ex, y + dy + ey, wrap_y)
                if q is not None and q >= MID:
                    put(g, x + dx + ex, y + dy + ey, DIM, wrap_y)


def _c_top(seed):
    g = grid()
    drift, tuft, fine = smooth_noise(seed + 1, C_DRIFT), smooth_noise(seed, C_TUFT), smooth_noise(seed + 2, 0.45)
    _dark_carpet(g, [(x, y) for y in range(16) for x in range(16)], drift, tuft, fine)
    kinds = ("cross", "pair", "pair", "bud", "spark")
    for (x, y), kind in zip(even_points(random.Random(seed + 4), len(kinds), strata=True), kinds):
        _floret(g, int(x), int(y), kind)
    return g


def look_c(face):
    """Nylium: a fine even film like nylium or moss: a dim carpet mottled with deep, bare specks where the rock shows
    through, its tufts' tips catching the glow (mid, a few neon) a pixel or two at a time, more where a slow drift
    thickens it, and a few hot florets of mixed shapes. The edge mat is torn pieces of the same carpet off the block's
    edges, with fewer lit tips and two small florets; on the side the carpet comes over the lip as a short ragged
    fringe, lit most along the lip."""
    g = grid()
    if face == "top":
        return _c_top(seed_for("C"))
    if face == "edge_top":
        seed = seed_for("C")
        drift, tuft, fine = smooth_noise(seed + 1, C_DRIFT), smooth_noise(seed, C_TUFT), smooth_noise(seed + 2, 0.45)
        cells = [(x, y) for y in range(16) for x in range(16)]
        rng = random.Random(3304)  # the edge mat: torn pieces of the carpet
        isl = [(x, y, s) for (x, y), s in zip(even_points(rng, 3, box=(2.5, 2.5, 13.5, 13.5), strata=True),
                                               (1.8, 1.6, 1.4))]
        keep = {p: sum(math.exp(-wdist(p[0] + 0.5, p[1] + 0.5, cx, cy) ** 2 / (2 * s * s)) for cx, cy, s in isl)
                + 0.35 * (fine[p] - 0.5) for p in cells}
        rk = ranks(keep)
        inside = {p for p in cells if rk[p] >= 1 - C_EDGE_KEEP}
        _dark_carpet(g, list(inside), drift, tuft, fine, lit=0.15, tip=0.03)
        d = dist_to(inside)
        for (x, y) in cells:
            if (x, y) not in inside:  # the pieces fray out in a sparse deep and dim
                t = tau(x, y, (9, 6))
                g[y][x] = (DIM if t < 0.12 else (DEEP if t < 0.45 else BARE)) if d[x, y] <= 1.0 else (
                    DEEP if d[x, y] <= 1.5 and t < 0.2 else BARE)
        spots = sorted(inside, key=lambda p: -rk[p])
        a = spots[0]
        b = next(p for p in spots if wdist(*p, *a) >= 5)
        _floret(g, a[0], a[1], "pair")
        _floret(g, b[0], b[1], "spark")
        return clear_border(g)
    full = face == "side"  # the side: a short ragged fringe over the lip
    rng = random.Random(3310 if full else 3311)
    bumps = [(x, rng.uniform(1.0, 1.8), rng.uniform(1.2, 2.6) if full else rng.uniform(1.1, 1.8))
             for (x, _) in even_points(rng, 4 if full else 3, box=(0, 0, 16, 0.01), wrap_y=False)]
    base = 1.6 if full else 0.0
    drift, tuft, fine = smooth_noise(3308, C_DRIFT, 32), smooth_noise(3307, C_TUFT, 32), smooth_noise(3309, 0.45, 32)
    cells = []
    for x in range(16):
        h = base + sum(a * math.exp(-wdist(x + 0.5, 0, cx, 0, wrap_y=False) ** 2 / (2 * s * s)) for cx, s, a in bumps)
        h += 0.6 * (tau(x, 3, (1, 1)) - 0.5)
        for y in range(16):
            if y < h:
                cells.append((x, y))
            elif y < h + 1.4 and tau(x, y, (3, 5)) < 0.3:
                g[y][x] = DEEP  # a stain dithered out below the fringe
    _dark_carpet(g, cells, drift, tuft, fine, lit=0.3 if full else 0.24, tip=0.06,
                 bias=lambda x, y: 0.12 - 0.07 * y)
    if full:
        _floret(g, int(bumps[0][0]), 1, "bud", wrap_y=False)
    return g


# ================================================================================================ D: spores

CODE = {"d": DEEP, "i": DIM, "m": MID, "n": NEON, "h": HOT}
DOTS = {  # spore dots, lit from the top left; the darker corners round them off
    1: ["n"],
    2: ["hn", "nm"],
    3: ["mnm", "nhn", "mni"],
    4: [".mn.", "mhnm", "nnmi", ".mi."],
}


def _dot(g, x, y, size, wrap_y=True, faint=False):
    rows = DOTS[size]
    for dy, row in enumerate(rows):
        for dx, ch in enumerate(row):
            if ch != ".":
                put(g, x + dx, y + dy, MID if (faint and size == 1) else CODE[ch], wrap_y)
    w = len(rows)
    shadow = ((w, w - 1), (w - 1, w), (w, w)) if size >= 3 else (((2, 1), (1, 2)) if size == 2 else ((1, 1),))
    for dx, dy in shadow:  # a deep shadow below right
        put(g, x + dx, y + dy, DEEP, wrap_y, "bare")


def _cluster(g, rng, cx, cy, R, occ, heart=3, wrap_y=True, rows=16, sparse=1.0, inset=False):
    """A cluster of spore dots packed from its middle outwards: candidate spots are taken nearest the middle first,
    each takes the biggest dot that fits a pixel clear of the others (the heart's size in the middle, 2x2 beads, then
    single spores), and more and more of them are skipped further out, so the cluster is densest in its middle."""
    wy = (lambda q: q % 16) if wrap_y else (lambda q: q)

    def fits(ix, iy, size):
        if not wrap_y and (iy < 0 or iy + size > rows):
            return None
        if inset and not (1 <= ix and ix + size <= 15 and 1 <= iy and iy + size <= 15):
            return None  # an edge mat's dots stay a pixel off the block's edges
        cells = {((ix + dx) % 16, wy(iy + dy)) for dy in range(size) for dx in range(size)}
        ring = {((ix + dx) % 16, wy(iy + dy)) for dy in range(-1, size + 1) for dx in range(-1, size + 1)}
        return None if ring & occ else cells

    cands = []
    for iy in range(int(cy - R) - 2, int(cy + R) + 3):
        for ix in range(int(cx - R) - 2, int(cx + R) + 3):
            d = math.hypot(ix + 0.5 - cx, iy + 0.5 - cy)
            if d <= R:
                cands.append((d + 0.9 * rng.random(), d, ix, iy))
    cands.sort()
    placed = []
    for _, d, ix, iy in cands:
        q = d / R
        if placed and rng.random() < sparse * 0.75 * q ** 2.0:
            continue
        want = heart if not placed else (2 if q < 0.7 else 1)
        for size in range(want, 0, -1):
            ox, oy = ix - (size - 1) // 2, iy - (size - 1) // 2
            cells = fits(ox, oy, size)
            if cells:
                occ |= cells
                placed.append((ox, oy, size, q))
                break
    for ox, oy, size, q in sorted(placed, key=lambda p: (p[1], p[0])):
        _dot(g, ox, oy, size, wrap_y, faint=size == 1 and q > 0.7)
    return placed


def _bed(g, centres, wrap_y=True, rows=16):
    """A deep stain under each cluster, dithered out with distance."""
    for y in range(rows):
        for x in range(16):
            if g[y][x] == BARE:
                v = max(max(0.0, 1 - wdist(x + 0.5, y + 0.5, cx, cy, wrap_y) / (R * 1.15)) for cx, cy, R in centres)
                if v > 0.30 + 0.62 * tau(x, y, (3, 7)):
                    g[y][x] = DEEP


def _d_top(seed):
    g, rng, occ = grid(), random.Random(seed), set()
    centres = [(x, y, R) for (x, y), R in zip(even_points(rng, 3, strata=True), (5.0, 4.4, 3.7))]
    for (cx, cy, R), heart in zip(centres, (4, 3, 3)):
        _cluster(g, rng, cx, cy, R, occ, heart)
    _bed(g, centres)
    return g


def look_d(face):
    """Spores: clusters of round glowing spore dots, packed densest in each cluster's middle: a big round dot at the
    heart (a hot highlight, neon, mid and dim to the lower right), 2x2 beads round it, then single spores thinning out,
    each dot a pixel clear of the others with a deep shadow below right, on a dithered deep bed. The edge mat has two
    small clusters kept off the block's edges; on the side the clusters spill over the edge."""
    if face == "top":
        return _d_top(seed_for("D"))
    g = grid()
    rng = random.Random({"edge_top": 3402, "side": 3403, "edge_side": 3404}[face])
    occ = set()
    if face == "edge_top":
        centres = [(x, y, R) for (x, y), R in zip(even_points(rng, 2, box=(3.0, 3.0, 13.0, 13.0)), (3.4, 2.8))]
        for (cx, cy, R), heart in zip(centres, (3, 2)):
            _cluster(g, rng, cx, cy, R, occ, heart, sparse=1.0, inset=True)
        _bed(g, centres)
        return clear_border(g)
    full = face == "side"
    xs = even_points(rng, 2, box=(0, 0, 16, 0.01), wrap_y=False)
    centres = [(x, rng.uniform(0.8, 1.6), R) for (x, _), R in zip(xs, (4.2, 3.4) if full else (2.8, 2.3))]
    for (cx, cy, R), heart in zip(centres, (3, 2) if full else (2, 1)):
        _cluster(g, rng, cx, cy, R, occ, heart, wrap_y=False, rows=10)
    _bed(g, centres, wrap_y=False, rows=8)
    return g


LOOKS = {"A": ("Colonies", look_a), "B": ("Threads", look_b), "C": ("Nylium", look_c), "D": ("Spores", look_d)}
# Each look's top painter, its first seed and the step between seeds (a painter that also uses seed + 1, + 2 or + 4
# for its other layers steps by 10).
TOPS = {"A": (_a_top, 3100, 1), "B": (_b_top, 32000, 10), "C": (_c_top, 33000, 10), "D": (_d_top, 3400, 1)}
_seeds = {}


def seed_for(letter):
    """The look's seed: of 40 candidates, the one whose top spreads its glow most evenly (even_seed)."""
    if letter not in _seeds:
        make, first, step = TOPS[letter]
        _seeds[letter] = even_seed(make, range(first, first + 40 * step, step))
    return _seeds[letter]


# ================================================================================================ films and overlays

_cache = {}


def pattern(letter, face):
    key = (letter, face)
    if key not in _cache:
        _cache[key] = LOOKS[letter][1](face)
    return _cache[key]


def film(letter, face, neon):
    """The film as RGBA with hard alpha: each level in the neon's shade, bare pixels transparent."""
    s = T.neon_shades(neon)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    g = pattern(letter, face)
    for y in range(16):
        for x in range(16):
            if g[y][x] != BARE:
                px[x, y] = tuple(s[SHADES[g[y][x]]]) + (255,)
    return img


def glow(letter, face, neon):
    """The film's _glow overlay: its mid, neon and hot pixels (textures.GLOWING), drawn again at full brightness."""
    s = T.neon_shades(neon)
    return T.glow_layer(film(letter, face, neon), {T._hex(s[k]) for k in T.GLOWING})
