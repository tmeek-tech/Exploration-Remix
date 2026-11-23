"""The enchanting table's rune view, in pixel art (0.20.5, Taran 2026-11-23: "add some magical themed pixel art to the
interface"): two 256 x 256 sheets.

textures/gui/rune_table.png, drawn on the screen one texel to a GUI pixel (RuneTableScreen):
  the panel's frame (a 9-slice: silver bars with blue gems at the corners), the lavender gem that crowns it, the
  Enchant button's frame (normal and lit), the flourishes either side of the weapon's name, and the sockets' kind
  icons (a star for the active rune, a gem for the modifier, a crescent for the passive).

textures/gui/rune_table_world.png, drawn in the world over the table (RuneTableView):
  the rune circle behind the weapon (drawn added onto the picture, so it glows), the silver socket rings with gems in
  each socket's colour, the pips that fill with XP toward phase two, the twinkling stars and the stroke over a rune
  to take out.

Light falls from the top left. No warm colours and no cyan: silver-blue, light blue, lavender and white. The regions
are fixed: RuneTableScreen and RuneTableView name the same numbers (the comments by each painter say where it sits).
"""
import math
import os

from PIL import Image

# ------------------------------------------------------------------------------------------------ the palette

def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


INK = "#070B16"         # outlines
BODY = "#0B1224"        # the panel's dark blue
BODY_HI = "#121D3A"     # its first row under the frame, and a lit button
HAIR = "#2B4A8C"        # the hairline inside the frame
STEEL_DK = "#343E52"
STEEL = "#4F5D78"
SILVER_DK = "#7886A3"
SILVER = "#AEBBD6"
SILVER_HI = "#E6EEFF"
WHITE = "#F4F8FF"
BLUE_DK = "#3F63B8"
BLUE = "#8EC2FF"
BLUE_HI = "#C9DEFF"
LAV_DK = "#6A5BC2"
LAV = "#B7A8FF"
LAV_HI = "#E0D8FF"
PASS_DK = "#7F90B5"
PASS = "#D6E6FF"
PASS_HI = "#F2F7FF"

# a gem's three tones by colour: (light, mid, dark)
GEMS = {
    "blue": (BLUE_HI, BLUE, BLUE_DK),
    "lavender": (LAV_HI, LAV, LAV_DK),
    "silver": (PASS_HI, PASS, PASS_DK),
}


def stamp(img, rows, legend, x0, y0, flip_x=False, flip_y=False):
    """Draws an ASCII picture: each character is a legend key ('.' and ' ' leave the pixel as it is)."""
    px = img.load()
    h, w = len(rows), len(rows[0])
    for y, row in enumerate(rows):
        assert len(row) == w, f"ragged row {y}: {row!r}"
        for x, ch in enumerate(row):
            if ch in ". ":
                continue
            c = legend[ch]
            tx = x0 + (w - 1 - x if flip_x else x)
            ty = y0 + (h - 1 - y if flip_y else y)
            px[tx, ty] = hexc(c) if isinstance(c, str) else c


# a faceted gem, 9 by 9, lit from the top left (H light, B mid, d dark, W the glint)
GEM9 = [
    "....k....",
    "...kHk...",
    "..kHHBk..",
    ".kHWBBdk.",
    "kHBBBBddk",
    ".kBBBddk.",
    "..kBddk..",
    "...kdk...",
    "....k....",
]
# a small one, 5 by 5
GEM5 = [
    "..k..",
    ".kHk.",
    "kHBdk",
    ".kdk.",
    "..k..",
]


def gem_legend(colour):
    light, mid, dark = GEMS[colour]
    return {"k": INK, "H": light, "B": mid, "d": dark, "W": WHITE}


SILVER_GEM5 = {"k": INK, "H": SILVER_HI, "B": SILVER, "d": SILVER_DK, "W": WHITE}

# ------------------------------------------------------------------------------------------------ the screen's sheet

# The panel's frame, a 32 x 32 9-slice at (0, 0): corners 12 x 12, edges and middle 8 wide, tiled. From the outside
# in, each side is 2 clear texels (room for the corner gems to stand proud), then the bar: ink, silver, steel, ink,
# the blue hairline; then the body.
PANEL = (0, 0)
INSET = 2
BAR_LIT = [INK, SILVER, STEEL, INK, HAIR]        # top and left
BAR_SHADE = [INK, SILVER_DK, STEEL_DK, INK, HAIR]  # bottom and right
BODY_ALPHA = 238
INNER, INNER_LINE = "#1F3468", len(BAR_LIT) + 2


def panel_frame(img):
    x0, y0 = PANEL
    size = 32
    px = img.load()
    for y in range(size):
        for x in range(size):
            # how deep into the frame from each side
            dl, dr, dt, db = x - INSET, size - 1 - INSET - x, y - INSET, size - 1 - INSET - y
            if min(dl, dr, dt, db) < 0:
                continue
            depth = min(dl, dr, dt, db)
            if depth >= len(BAR_LIT):
                c = BODY_HI if dt == len(BAR_LIT) else BODY
                # a fine inner line two texels in, with a small loop where it turns each corner
                if depth == INNER_LINE:
                    c = INNER
                px[x0 + x, y0 + y] = hexc(c, BODY_ALPHA)
                continue
            # the side this texel belongs to: the nearest, the top and left winning ties (light from the top left)
            lit = depth in (dt, dl) and not (depth == db and dt > db) and not (depth == dr and dl > dr)
            px[x0 + x, y0 + y] = hexc((BAR_LIT if lit else BAR_SHADE)[depth])
    # the inner line's loops: a ring of three by three at each turn
    d = INSET + INNER_LINE
    for (lx, ly) in ((d, d), (size - 1 - d, d), (d, size - 1 - d), (size - 1 - d, size - 1 - d)):
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                if (dx, dy) != (0, 0):
                    px[x0 + lx + dx, y0 + ly + dy] = hexc(INNER if abs(dx) + abs(dy) == 2 else HAIR, BODY_ALPHA)
        px[x0 + lx, y0 + ly] = hexc(BLUE_DK, BODY_ALPHA)
    # the corner gems, standing over each corner of the bar
    for cx, cy in ((0, 0), (size - 9, 0), (0, size - 9), (size - 9, size - 9)):
        stamp(img, GEM9, gem_legend("blue"), x0 + cx, y0 + cy)


# The crown over the middle of the panel's top edge, 41 x 11 at (0, 40), its middle row laid on the bar's silver line:
# a big lavender gem, a blue one either side, joined by a silver bar two texels thick, and a bead at each end.
CROWN = (0, 40)
CROWN_SIZE = (41, 11)
GEM11 = [
    ".....k.....",
    "....kHk....",
    "...kHHBk...",
    "..kHWHBBk..",
    ".kHHBBBBdk.",
    "kHBBBBBBddk",
    ".kBBBBBddk.",
    "..kBBBddk..",
    "...kBddk...",
    "....kdk....",
    ".....k.....",
]


def crown(img):
    x0, y0 = CROWN
    w, h = CROWN_SIZE
    px = img.load()
    mid = h // 2
    # the bar, under everything else
    for x in range(3, w - 3):
        px[x0 + x, y0 + mid - 2] = hexc(INK)
        px[x0 + x, y0 + mid - 1] = hexc(SILVER_HI)
        px[x0 + x, y0 + mid] = hexc(SILVER)
        px[x0 + x, y0 + mid + 1] = hexc(INK)
    for bx in (1, w - 2):
        for dx, dy in ((0, -1), (0, 1), (-1, 0), (1, 0)):
            px[x0 + bx + dx, y0 + mid + dy] = hexc(INK)
        px[x0 + bx, y0 + mid] = hexc(SILVER_HI)
    stamp(img, GEM5, gem_legend("blue"), x0 + 8, y0 + mid - 2)
    stamp(img, GEM5, gem_legend("blue"), x0 + w - 13, y0 + mid - 2)
    stamp(img, GEM11, gem_legend("lavender"), x0 + w // 2 - 5, y0)


# The Enchant button, a 16 x 16 9-slice: normal at (32, 0), lit at (48, 0). Corners 5 x 5 with a small blue gem; a
# texel clear outside, then ink, silver, ink, then the body.
BUTTON = (32, 0)
BUTTON_LIT = (48, 0)


def button(img, origin, lit):
    x0, y0 = origin
    size = 16
    px = img.load()
    bars = [INK, SILVER_HI if lit else SILVER, INK]
    shade = [INK, SILVER if lit else SILVER_DK, INK]
    for y in range(size):
        for x in range(size):
            dl, dr, dt, db = x - 1, size - 2 - x, y - 1, size - 2 - y
            if min(dl, dr, dt, db) < 0:
                continue
            depth = min(dl, dr, dt, db)
            if depth >= len(bars):
                px[x0 + x, y0 + y] = hexc(BODY_HI if lit else BODY, 240)
                continue
            top_left = depth in (dt, dl) and not (depth == db and dt > db) and not (depth == dr and dl > dr)
            px[x0 + x, y0 + y] = hexc((bars if top_left else shade)[depth])
    for cx, cy in ((0, 0), (size - 5, 0), (0, size - 5), (size - 5, size - 5)):
        stamp(img, GEM5, gem_legend("blue"), x0 + cx, y0 + cy)


# The flourish to the left of the weapon's name, 40 x 9 at (0, 56); the right one is its mirror at (48, 56). A silver
# thread from far off that thickens toward the name, a bead on it, and a lavender gem by the name.
FLOURISH_LEFT = (0, 56)
FLOURISH_RIGHT = (48, 56)
FLOURISH_SIZE = (40, 9)


def flourish(img):
    w, h = FLOURISH_SIZE
    one = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = one.load()
    mid = h // 2
    for x in range(0, w - 8):
        if x < 10:
            # the far end: a dotted thread
            if x % 2 == 0:
                px[x, mid] = hexc(SILVER_DK if x < 6 else SILVER)
            continue
        px[x, mid - 1] = hexc(INK)
        px[x, mid] = hexc(SILVER_HI if x > 16 else SILVER)
        if x > 16:
            px[x, mid + 1] = hexc(SILVER_DK)
            px[x, mid + 2] = hexc(INK)
        else:
            px[x, mid + 1] = hexc(INK)
    # a bead along it
    for dx, dy in ((0, -1), (0, 1), (-1, 0), (1, 0), (-1, -1), (1, -1), (-1, 1), (1, 1)):
        px[20 + dx, mid + dy] = hexc(INK)
    px[20, mid] = hexc(LAV_HI)
    stamp(one, GEM9, gem_legend("lavender"), w - 9, 0)
    img.alpha_composite(one, FLOURISH_LEFT)
    img.alpha_composite(one.transpose(Image.FLIP_LEFT_RIGHT), FLOURISH_RIGHT)


# The kind icons, 9 x 9 each at (0, 72), (10, 72) and (20, 72): the active rune's star, the modifier's gem and the
# passive's crescent, outlined in ink so they read over anything; and at (30, 72) the second phase's mark, a Roman two
# (its line in the panel wears it).
ICONS = (0, 72)
ICON_STAR = [
    "....k....",
    "...kBk...",
    "...kHk...",
    ".kkBHBkk.",
    "kBHHWHHBk",
    ".kkBHBkk.",
    "...kHk...",
    "...kBk...",
    "....k....",
]
ICON_CRESCENT = [
    "..kkkk...",
    ".kHPPPk..",
    "kHPPkk...",
    "kPPk.....",
    "kPPk.....",
    "kPPk.....",
    "kPdPkk...",
    ".kdddPk..",
    "..kkkk...",
]


# The second phase's mark: a Roman two, its pillars joined top and bottom.
ICON_PHASE = [
    "kkkkkkkkk",
    "kWWWWWWHk",
    "kkHkkkHkk",
    ".kHk.kHk.",
    ".kHk.kHk.",
    ".kHk.kHk.",
    "kkHkkkHkk",
    "kHHHHHHHk",
    "kkkkkkkkk",
]


def icons(img, origin=ICONS, pip=True):
    x0, y0 = origin
    stamp(img, ICON_STAR, {"k": INK, "B": BLUE, "H": BLUE_HI, "W": WHITE}, x0, y0)
    stamp(img, GEM9, gem_legend("lavender"), x0 + 10, y0)
    stamp(img, ICON_CRESCENT, {"k": INK, "H": PASS_HI, "P": PASS, "d": PASS_DK}, x0 + 20, y0)
    if pip:
        stamp(img, ICON_PHASE, {"k": INK, "H": BLUE_HI, "W": WHITE}, x0 + 30, y0)


def gui_sheet():
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    panel_frame(img)
    crown(img)
    button(img, BUTTON, False)
    button(img, BUTTON_LIT, True)
    flourish(img)
    icons(img)
    return img


# ------------------------------------------------------------------------------------------------ the world's sheet

def put(px, x, y, c, size):
    if 0 <= x < size[0] and 0 <= y < size[1]:
        px[x, y] = c


def circle_pixels(r):
    """The pixels of a one-texel circle of radius r about the origin (midpoint algorithm), as offsets."""
    out = set()
    x, y, err = r, 0, 1 - r
    while x >= y:
        for a, b in ((x, y), (y, x), (-y, x), (-x, y), (-x, -y), (-y, -x), (y, -x), (x, -y)):
            out.add((a, b))
        y += 1
        if err < 0:
            err += 2 * y + 1
        else:
            x -= 1
            err += 2 * (y - x) + 1
    return out


def line_pixels(x0, y0, x1, y1):
    out = []
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    while True:
        out.append((x0, y0))
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy
    return out


# Small upright runes for the circle's band, 5 x 7 each (# stroke).
CIRCLE_RUNES = [
    ["..#..", ".###.", "#.#.#", "..#..", "..#..", ".#.#.", "#...#"],
    ["#...#", ".#.#.", "..#..", "..#..", "..#..", ".#.#.", "#...#"],
    ["###..", "#..#.", "###..", "#.#..", "#..#.", "#...#", "#...#"],
    ["..#..", "..#..", "#####", "..#..", ".#.#.", "#...#", "....."],
    ["#...#", "##.##", "#.#.#", "#...#", "#...#", "#...#", "#...#"],
    [".###.", "#...#", "..#..", ".###.", "..#..", "#...#", ".###."],
    ["#....", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"],
    ["..#..", ".#.#.", "#...#", ".#.#.", "..#..", "..#..", "..#.."],
]

# The rune circle, 128 x 128 at (0, 0). Drawn added onto the picture: light only, nothing dark.
CIRCLE = (0, 0)


def rune_circle(img):
    x0, y0 = CIRCLE
    size = (img.width, img.height)
    px = img.load()
    c = 63

    def ring(r, colour, dotted=0):
        for i, (dx, dy) in enumerate(sorted(circle_pixels(r), key=lambda p: math.atan2(p[1], p[0]))):
            if dotted and i % dotted:
                continue
            put(px, x0 + c + dx, y0 + c + dy, hexc(colour), size)

    ring(61, BLUE_DK)
    ring(59, BLUE)
    ring(47, BLUE)
    ring(45, BLUE_DK, dotted=2)
    ring(30, LAV_DK, dotted=3)
    # twelve runes round the band between the outer rings and the inner, upright as a clock's numbers
    for k in range(12):
        a = -math.pi / 2 + k * math.pi * 2 / 12
        gx, gy = round(c + math.cos(a) * 53) - 2, round(c + math.sin(a) * 53) - 3
        for yy, row in enumerate(CIRCLE_RUNES[k % len(CIRCLE_RUNES)]):
            for xx, ch in enumerate(row):
                if ch == "#":
                    put(px, x0 + gx + xx, y0 + gy + yy, hexc(LAV), size)
    # a compass rose inside the inner ring: four long points out to it, four short ones out to the dotted ring, each
    # point a thin kite from its tip to two shoulders near the middle, and a bead at each long point's tip
    tips = []
    for k in range(8):
        a = -math.pi / 2 + k * math.pi / 4
        reach = 44 if k % 2 == 0 else 29
        tip = (round(c + math.cos(a) * reach), round(c + math.sin(a) * reach))
        for side in (-1, 1):
            sa = a + side * math.pi / 8
            shoulder = (round(c + math.cos(sa) * 8), round(c + math.sin(sa) * 8))
            for (lx, ly) in line_pixels(tip[0], tip[1], shoulder[0], shoulder[1]):
                put(px, x0 + lx, y0 + ly, hexc(BLUE_DK if k % 2 else BLUE), size)
        if k % 2 == 0:
            tips.append(tip)
    for (dx, dy) in circle_pixels(5):
        put(px, x0 + c + dx, y0 + c + dy, hexc(BLUE_DK), size)
    for (bx, by) in tips:
        for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            put(px, x0 + bx + dx, y0 + by + dy, hexc(BLUE_HI if (dx, dy) == (0, 0) else BLUE), size)
    # ticks on the outer ring between the runes
    for k in range(12):
        a = -math.pi / 2 + (k + 0.5) * math.pi * 2 / 12
        for r in (58, 57):
            put(px, x0 + round(c + math.cos(a) * r), y0 + round(c + math.sin(a) * r), hexc(BLUE), size)


# The socket rings, 32 x 32: the active's at (128, 0), the modifier's at (160, 0), the passive's at (192, 0). A silver
# band round a dark slot (the rune's tablet sits in it), lit from the top left, with a gem in the socket's colour at
# each quarter and a stud between them.
RINGS = {"active": (128, 0), "modifier": (160, 0), "passive": (192, 0)}
RING_GEM = {"active": "blue", "modifier": "lavender", "passive": "silver"}


def socket_ring(img, kind):
    x0, y0 = RINGS[kind]
    px = img.load()
    c = 15.5
    for y in range(32):
        for x in range(32):
            dx, dy = x - c, y - c
            r = math.hypot(dx, dy)
            # light from the top left: 1 facing it, 0 facing away
            light = 0.5 + 0.5 * ((-dx - dy) / (math.sqrt(2) * max(r, 1e-6)))
            if r > 14.6:
                continue
            if r > 13.7:
                col = hexc(INK)
            elif r > 12.8:
                col = hexc(SILVER if light > 0.55 else SILVER_DK if light > 0.25 else STEEL)
            elif r > 11.9:
                col = hexc(SILVER_HI if light > 0.7 else SILVER if light > 0.35 else SILVER_DK)
            elif r > 11.0:
                col = hexc(STEEL if light > 0.3 else STEEL_DK)
            elif r > 10.2:
                col = hexc(INK)
            else:
                # the slot: darker under its lip at the top left, where the band shades it
                col = hexc(INK if (light > 0.8 and r > 8.6) else BODY, 235)
            px[x0 + x, y0 + y] = col
    # the gems, centred on the band at each quarter
    legend = gem_legend(RING_GEM[kind])
    for (gx, gy) in ((15, 3), (28, 15), (15, 28), (3, 15)):
        stamp(img, GEM5, legend, x0 + gx - 2, y0 + gy - 2)
    # studs at the diagonals
    for sx, sy in ((7, 7), (24, 7), (7, 24), (24, 24)):
        px[x0 + sx, y0 + sy] = hexc(SILVER_HI if (sx, sy) == (7, 7) else SILVER)


# The phase pips, 5 x 5: dark at (224, 0), lit at (230, 0), the glint at (236, 0).
PIP_DARK, PIP_LIT, PIP_GLINT = (224, 0), (230, 0), (236, 0)


def pips(img):
    stamp(img, GEM5, {"k": INK, "H": STEEL, "B": STEEL_DK, "d": STEEL_DK}, *PIP_DARK)
    stamp(img, GEM5, {"k": INK, "H": WHITE, "B": BLUE_HI, "d": BLUE}, *PIP_LIT)
    stamp(img, GEM5, {"k": BLUE_HI, "H": WHITE, "B": WHITE, "d": BLUE_HI}, *PIP_GLINT)


# The twinkling star's four frames, 9 x 9 each at (128 + 10 k, 40): light only (drawn added), white, so it takes
# whatever colour it is drawn in.
STARS = (128, 40)
STAR_FRAMES = [
    [".........", ".........", ".........", "....+....", "...+#+...", "....+....", ".........", ".........", "........."],
    [".........", ".........", "....+....", "....#....", "..+#W#+..", "....#....", "....+....", ".........", "........."],
    [".........", "....+....", "....#....", "...+#+...", ".+##W##+.", "...+#+...", "....#....", "....+....", "........."],
    ["....+....", "....#....", "....#....", "...+#+...", "+###W###+", "...+#+...", "....#....", "....#....", "....+...."],
]


def stars(img):
    legend = {"+": (150, 150, 160, 255), "#": (215, 220, 235, 255), "W": (255, 255, 255, 255)}
    for k, frame in enumerate(STAR_FRAMES):
        stamp(img, frame, legend, STARS[0] + 10 * k, STARS[1])


# The stroke over a rune to take out, 16 x 16 at (128, 56): a silver slash, outlined.
SLASH = (128, 56)


def slash(img):
    px = img.load()
    x0, y0 = SLASH
    for i in range(2, 14):
        x, y = 15 - i, i
        for (dx, dy, c) in ((0, 0, SILVER_HI), (1, 0, SILVER), (-1, 0, INK), (2, 0, INK), (0, -1, INK), (1, 1, INK)):
            tx, ty = x + dx, y + dy
            if 0 <= tx < 16 and 0 <= ty < 16 and not (c == INK and px[x0 + tx, y0 + ty][3] > 0):
                px[x0 + tx, y0 + ty] = hexc(c)


# The kind icons again, 9 x 9 at (128 + 10 k, 80): shown faintly in an empty socket's slot.
WORLD_ICONS = (128, 80)


def world_sheet():
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    rune_circle(img)
    for kind in RINGS:
        socket_ring(img, kind)
    pips(img)
    stars(img)
    slash(img)
    icons(img, WORLD_ICONS, pip=False)
    return img


def write_all(assets):
    folder = os.path.join(assets, "textures", "gui")
    os.makedirs(folder, exist_ok=True)
    gui_sheet().save(os.path.join(folder, "rune_table.png"))
    world_sheet().save(os.path.join(folder, "rune_table_world.png"))


# ------------------------------------------------------------------------------------------------ review

def nine(sheet, src, corner, edge, w, h):
    """The 9-slice at src (corner, edge sizes) drawn w x h, edges and middle tiled, as RuneTableScreen draws it."""
    sx, sy = src
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))

    def piece(u, v, pw, ph, x, y, tw, th):
        part = sheet.crop((sx + u, sy + v, sx + u + pw, sy + v + ph))
        for ty in range(0, th, ph):
            for tx in range(0, tw, pw):
                cw, ch = min(pw, tw - tx), min(ph, th - ty)
                out.alpha_composite(part.crop((0, 0, cw, ch)), (x + tx, y + ty))

    c, e = corner, edge
    full = c * 2 + e
    piece(0, 0, c, c, 0, 0, c, c)
    piece(c + e, 0, c, c, w - c, 0, c, c)
    piece(0, c + e, c, c, 0, h - c, c, c)
    piece(c + e, c + e, c, c, w - c, h - c, c, c)
    piece(c, 0, e, c, c, 0, w - 2 * c, c)
    piece(c, c + e, e, c, c, h - c, w - 2 * c, c)
    piece(0, c, c, e, 0, c, c, h - 2 * c)
    piece(c + e, c, c, e, w - c, c, c, h - 2 * c)
    piece(c, c, e, e, c, c, w - 2 * c, h - 2 * c)
    assert full == (32 if corner == 12 else 16)
    return out


def preview(path, scale=4):
    """The pieces put together as the game shows them, on a dim stand-in of the table's room, at scale."""
    g, wsheet = gui_sheet(), world_sheet()
    W, H = 480, 270
    bg = Image.new("RGBA", (W, H), (34, 30, 38, 255))
    px = bg.load()
    for y in range(H):
        for x in range(W):
            v = 26 + ((x // 8 + y // 8) % 2) * 6
            px[x, y] = (v, v + 2, v + 8, 255)
    panel = nine(g, PANEL, 12, 8, 440, 72)
    bg.alpha_composite(panel, (20, H - 6 - 72))
    bg.alpha_composite(g.crop((CROWN[0], CROWN[1], CROWN[0] + CROWN_SIZE[0], CROWN[1] + CROWN_SIZE[1])),
                       (W // 2 - CROWN_SIZE[0] // 2, H - 6 - 72 + INSET + 1 - CROWN_SIZE[1] // 2))
    bg.alpha_composite(nine(g, BUTTON, 5, 6, 52, 18), (W - 60, 6))
    bg.alpha_composite(nine(g, BUTTON_LIT, 5, 6, 52, 18), (W - 60, 28))
    fw, fh = FLOURISH_SIZE
    bg.alpha_composite(g.crop((FLOURISH_LEFT[0], FLOURISH_LEFT[1], FLOURISH_LEFT[0] + fw, FLOURISH_LEFT[1] + fh)), (W // 2 - 40 - fw, 6))
    bg.alpha_composite(g.crop((FLOURISH_RIGHT[0], FLOURISH_RIGHT[1], FLOURISH_RIGHT[0] + fw, FLOURISH_RIGHT[1] + fh)), (W // 2 + 40, 6))
    for k in range(3):
        bg.alpha_composite(g.crop((ICONS[0] + 10 * k, ICONS[1], ICONS[0] + 10 * k + 9, ICONS[1] + 9)), (150 + 90 * k, 150))
    # the world's pieces, at their own sizes; the circle added on at about the strength the game draws it
    circ = wsheet.crop((0, 0, 128, 128))
    cpx, bpx = circ.load(), bg.load()
    for y in range(128):
        for x in range(128):
            r, gg, b, a = cpx[x, y]
            if a:
                tx, ty = W // 2 - 64 + x, 20 + y
                o = bpx[tx, ty]
                bpx[tx, ty] = (min(255, o[0] + int(r * 0.55)), min(255, o[1] + int(gg * 0.55)), min(255, o[2] + int(b * 0.55)), 255)
    for k, kind in enumerate(RINGS):
        x, y = RINGS[kind]
        bg.alpha_composite(wsheet.crop((x, y, x + 32, y + 32)), (130 + 90 * k, 160))
    for k in range(3):
        bg.alpha_composite(wsheet.crop((PIP_DARK[0] + 6 * k, 0, PIP_DARK[0] + 6 * k + 5, 5)), (40 + 8 * k, 150))
    for k in range(4):
        bg.alpha_composite(wsheet.crop((STARS[0] + 10 * k, STARS[1], STARS[0] + 10 * k + 9, STARS[1] + 9)), (40 + 12 * k, 120))
    bg.alpha_composite(wsheet.crop((SLASH[0], SLASH[1], SLASH[0] + 16, SLASH[1] + 16)), (40, 90))
    bg.resize((W * scale, H * scale), Image.NEAREST).save(path)


if __name__ == "__main__":
    import sys
    preview(sys.argv[1] if len(sys.argv) > 1 else "rune_table_preview.png")
