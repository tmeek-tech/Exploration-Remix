"""Plant and crystal textures for Exploration Remix: 16 by 16, vanilla style, colours taken from Taran's pictures.

Shapes that need exact pixels are drawn as character grids (one character per pixel, '.' is transparent); hanging
vines and fronds are drawn by small deterministic routines, so every texture only changes when this file does.
"""
import math, random
from PIL import Image


def hexc(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def grid(rows, palette):
    assert len(rows) == 16, len(rows)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y, row in enumerate(rows):
        assert len(row) == 16, (y, row, len(row))
        for x, ch in enumerate(row):
            if ch != ".":
                px[x, y] = hexc(palette[ch])
    return img


def blank():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


# ------------------------------------------------------------------------------------------------ Rootvaults

def white_glow_flower():
    """Tiny white star flowers on thin stems, warm at the heart (the Teal Grotto picture)."""
    return grid([
        "................",
        "................",
        "....w...........",
        "...wyw.....w....",
        "....wW....wyw...",
        "....g......wW...",
        ".w..g.......g...",
        "wyw.g......g....",
        ".wW..g.....g..w.",
        "..g..g....g..wyw",
        "..g..g...g....wW",
        "...g.lg..g..lg..",
        "...gl.g.g..g.g..",
        "....g.Gg.lgG....",
        ".....gGG.G......",
        "......GG.G......",
    ], {"w": "#F6F0DE", "W": "#D9D0B8", "y": "#F4CF6A", "g": "#6D8B3B", "G": "#4E6A2C", "l": "#89A84A"})


# ------------------------------------------------------------------------------------------------ Ivory Root Cavern

def lantern_fungus():
    """A wide pale cap that glows gold underneath, roots of light trailing from its rim."""
    return grid([
        "................",
        "................",
        "................",
        ".....kcCCck.....",
        "...kcCCCCCCck...",
        ".kcCCCCCCCCCCck.",
        "kccCCCCCCCCCCcck",
        ".kgGgGgsSgGgGgk.",
        "..y.Y..sS..Y.y..",
        "..Y.y..sS..y.Y..",
        "..y.o..sS..o.y..",
        "..o....sS....o..",
        ".......sS.......",
        ".......sS.......",
        "......ssSS......",
        ".....sssSSS.....",
    ], {"C": "#EFE6D2", "c": "#DCCDB0", "k": "#BFAE8E", "g": "#FFE29A", "G": "#FFF3CC", "s": "#E6DCC6", "S": "#C9BB9E",
        "y": "#F1B84F", "Y": "#FFDD86", "o": "#B98A3E"})


def lantern_roots():
    """Thin roots hanging from the ceiling, glowing gold towards their tips."""
    return grid([
        ".r...r..r...r.r.",
        ".r...r..r...r.r.",
        ".o...r..o...o.o.",
        "..o..o..o...o.o.",
        "..y..o...o..y..o",
        "..y...y..y..y..y",
        "..Y...y..y...y.y",
        ".y....Y..Y...y.Y",
        ".y....y...y..Y.y",
        ".Y....y...y..y..",
        ".y...y....Y..y..",
        ".y...Y....y..Y..",
        "..Y..y....y.....",
        "..y.......Y.....",
        "..Y.............",
        "................",
    ], {"r": "#7E6240", "o": "#B98A3E", "y": "#F1B84F", "Y": "#FFE08A"})


# ------------------------------------------------------------------------------------------------ Lantern Lake

WISTERIA = {"a": "#E8DCF3", "b": "#CDB6E4", "c": "#AA8ECC", "d": "#8063A6", "v": "#5D6A3B", "V": "#46512D"}


def _racemes(tip):
    """Hanging lilac flower clusters: cones of florets, light at the top of each cone and dark at its point, on thin
    green stalks. The body tiles top to bottom; the tip ends each stalk in a last small cone."""
    rng = random.Random(4402)
    img = blank()
    px = img.load()
    shades = [hexc(WISTERIA[k]) for k in "abcd"]
    stalk = hexc(WISTERIA["v"])
    for k, (cx, phase, end) in enumerate(((1.5, 0, 13), (5.6, 3, 9), (9.5, 1, 15), (13.4, 5, 11))):
        last = end if tip else 16
        for y in range(last):
            u = ((y + phase) % 7) / 6.0             # position inside a cone: 0 top, 1 point
            if tip and y >= last - 4:
                u = max(u, (y - (last - 4)) / 4.0 * 0.9 + 0.1)
            half = 2.1 * (1.0 - u) ** 0.8 + 0.2
            for x in range(16):
                d = abs(x + 0.5 - cx)
                if d <= half + (rng.random() - 0.5) * 0.8:
                    t = u * 0.95 + (d / 2.5) * 0.25 + (rng.random() - 0.3) * 0.3
                    px[x, y] = shades[max(0, min(3, int(t * 4)))]
            if px[int(cx), y][3] == 0:
                px[int(cx), y] = stalk
        if tip:
            px[int(cx), min(15, last)] = shades[3]
    return img


def wisteria_plant():
    return _racemes(False)


def wisteria():
    return _racemes(True)


def lantern_pod():
    """Glowing amber pods hanging in a bunch from a short dark stalk."""
    return grid([
        ".......sS.......",
        ".......sS.......",
        "......s..S......",
        ".....s....S.....",
        "....s.....s.S...",
        "...os....oS..o..",
        "..oYyo..oYyo.oS.",
        "..yWYy..yWYy.yo.",
        "..yYYy..yYYy.Yy.",
        "..oyyo..oyyo.yo.",
        "...oo....oo..o..",
        "....s.....s.....",
        "...oYo...oYo....",
        "...yWy...yWy....",
        "...oyo...oyo....",
        "....o.....o.....",
    ], {"s": "#5B4630", "S": "#3F3020", "o": "#C9741E", "y": "#F4A93A", "Y": "#FFD26E", "W": "#FFF0C0"})


def lavender_flower():
    """Feathery spikes of lavender on an island (the Lantern Lake picture)."""
    return grid([
        "................",
        "....a......a....",
        "...aba....aba...",
        "....b..a...b....",
        "...bcb.aba.bc...",
        "....c.aba..c..a.",
        "...cdc.bcb.d.ab.",
        "....d..cdc.g.bc.",
        "....g...d..g..c.",
        "...g....g..g..d.",
        "...g...g...g..g.",
        "....g..g..g..g..",
        "....g..g..g.g...",
        ".....g.g.gg.....",
        "......ggGg......",
        "......GGG.......",
    ], {"a": "#E2D2F2", "b": "#C3A6E0", "c": "#A27FC6", "d": "#7E5CA4", "g": "#6E8740", "G": "#526A2E"})


def gold_flower():
    """Pale gold sprays that crowd the islands, catching the lantern light."""
    return grid([
        "................",
        "................",
        "...y.....y......",
        "..yWy...yWy..y..",
        "...yo....yo.yWy.",
        "....g.y...g..yo.",
        "....gyWy..g..g..",
        "..y.g.yo.g..g...",
        ".yWyg..g.g..g...",
        "..yog..gg..g....",
        "...g.g.g...g....",
        "....g.gg..g.....",
        ".....ggg.g......",
        "......gGg.......",
        "......GG........",
        "......G.........",
    ], {"W": "#FFF4C8", "y": "#EBCB72", "o": "#C99A3E", "g": "#748A3E", "G": "#55682C"})


# ------------------------------------------------------------------------------------------------ Blue Reaches

def asurine_crystal():
    """A cluster of blue shards with white tips (the Asurine Geodes picture)."""
    return grid([
        "................",
        ".......W........",
        "......WL........",
        "......LMD...W...",
        "......LMD..WL...",
        "...W..LMD..LM...",
        "...LD.LMD..LMD..",
        "..WLD.LMDK.LMD..",
        "..LMD.LMDK.LMDK.",
        "..LMDKLMDKLLMDK.",
        "..LMDKLMDKLMMDK.",
        ".WLMDKLMDKLMDK..",
        ".LLMDKLMDKLMDKW.",
        ".LMDDKLMDKLMDLM.",
        "..MDKKMMDKMDKMD.",
        "...DKKDDKKDKKD..",
    ], {"W": "#EEF6FF", "L": "#AACDFF", "M": "#6E9FF0", "D": "#4068C6", "K": "#2B468F"})


def blue_glow_mushroom():
    """Round glowing caps on thin pale stems (the Deep Blue Rift picture)."""
    return grid([
        "................",
        "................",
        "...dbbd.........",
        "..dbBwBd........",
        "..bBwBBb........",
        "..ddbbdd........",
        "....sS....dbbd..",
        "....sS...dbwBbd.",
        "....sS...ddbbdd.",
        "....sS.....sS...",
        ".....sS....sS...",
        ".....sS....sS...",
        "....sS......sS..",
        "....sS......sS..",
        "...ssS.....ssS..",
        "...sSS.....sSS..",
    ], {"w": "#F4F9FF", "B": "#CFE3FF", "b": "#8FB9FF", "d": "#5683DA", "s": "#C3D4EA", "S": "#8EA5C4"})


def _beaded_vines(seed, tip, vine, vine_dark, leaf, bead, bead_bright, bead_dim):
    """Hanging vines with glowing beads along them: the body tiles top to bottom, the tip ends in a bead."""
    rng = random.Random(seed)
    img = blank()
    px = img.load()
    for i, x0 in enumerate((2, 6, 10, 13)):
        end = 16 if not tip else rng.randrange(8, 15)
        x = x0
        for y in range(end):
            if y % 4 == 3 and rng.random() < 0.5:
                x = max(1, min(14, x + rng.choice((-1, 1))))
            px[x, y] = hexc(vine if (y + i) % 3 else vine_dark)
            if rng.random() < 0.22:
                side = x + rng.choice((-1, 1))
                if 0 <= side < 16:
                    px[side, y] = hexc(leaf)
            if (y + i * 3) % 5 == 2:
                bx = x + (1 if (y // 5 + i) % 2 else -1)
                if 0 <= bx < 16:
                    px[bx, y] = hexc(bead_bright)
                    if y + 1 < 16:
                        px[bx, y + 1] = hexc(bead)
                    if y + 2 < 16 and 0 <= bx < 16:
                        px[bx, y + 2] = hexc(bead_dim)
        if tip:
            px[x, min(15, end)] = hexc(bead_bright)
            if end + 1 < 16:
                px[x, end + 1] = hexc(bead_dim)
    return img


def blue_glow_vines_plant():
    return _beaded_vines(5303, False, "#4C74B4", "#3A5E9C", "#6A94D0", "#86B8FF", "#E2F0FF", "#5A8EE6")


def blue_glow_vines():
    return _beaded_vines(5303, True, "#4C74B4", "#3A5E9C", "#6A94D0", "#86B8FF", "#E2F0FF", "#5A8EE6")


def _line(x0, y0, x1, y1):
    pts, dx, dy = [], abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    while True:
        pts.append((x0, y0))
        if x0 == x1 and y0 == y1:
            return pts
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def silver_fern():
    """Silvery-mint fronds arching out of one root, leaflets in a herringbone (the Silver Fern Rift picture)."""
    return grid([
        "................",
        "...h.......h....",
        "..lc.......cl...",
        "...lc..h..cl....",
        "..h.lc.c.cl.h...",
        ".lc..lclcl..cl..",
        "..lc..scs..cl...",
        "h..lc.lcl.cl..h.",
        "lc..lscscsl..cl.",
        ".lc..sscss..cl..",
        "..lcs.scs.scl...",
        "....sssgsss.....",
        "......sgs.......",
        "......ggg.......",
        ".......g........",
        ".......g........",
    ], {"h": "#EEF6F0", "l": "#C6DACD", "c": "#9DB9A8", "s": "#78978A", "g": "#5F7C50"})


# ------------------------------------------------------------------------------------------------ Ember Gardens

def orange_shelf_fungus():
    """Top of the shelf and its edge. Rows 0 to 9 are the top face (row 0 is the pale growing rim, row 9 meets the
    wall); rows 12 to 15 are the strip the model wraps round the shelf's edge."""
    pal = {"A": "#7E361A", "B": "#A24A24", "C": "#C4642F", "D": "#DE8446", "E": "#F0A766", "F": "#F9CF95"}
    rows = []
    for y in range(16):
        row = ""
        for x in range(16):
            if y <= 9:
                if y == 0 or (y == 1 and x in (0, 15)):
                    row += "F"
                    continue
                # growth zones in rings around where the shelf meets the wall, darker towards the wall
                d = math.hypot((x - 7.5) * 0.8, (y - 12.5) * 1.3)
                row += "AABACBDCEDFF"[max(0, min(11, int(d / 1.6)))]
            elif y >= 12:
                row += {12: "F", 13: "D", 14: "C", 15: "E"}[y]
            else:
                row += "."
        rows.append(row)
    img = grid(rows, pal)
    px = img.load()
    rng = random.Random(6610)
    for _ in range(12):
        x, y = rng.randrange(1, 15), rng.randrange(2, 9)
        r, g, b, a = px[x, y]
        px[x, y] = (min(255, r + 20), min(255, g + 16), min(255, b + 10), a)
    for _ in range(5):
        x = rng.randrange(16)
        r, g, b, a = px[x, 13]
        px[x, 13] = (max(0, r - 26), max(0, g - 20), max(0, b - 14), a)
    return img


def orange_shelf_fungus_under():
    """Underside of the shelf: glowing gills fanning out from the wall (row 0) to the pale rim (row 9)."""
    img = blank()
    px = img.load()
    base, gill, rim = hexc("#FFD08A"), hexc("#E39548"), hexc("#F9CF95")
    for y in range(10):
        for x in range(16):
            px[x, y] = rim if y == 9 else base
    for xr in (0, 2, 4, 6, 9, 11, 13, 15):
        x0 = int(round(7.5 + (xr - 7.5) * 0.55))
        for x, y in _line(x0, 0, xr, 8):
            px[x, y] = gill
    return img


def lavender_coral():
    """Branching lavender coral that crowds the pool edges."""
    return grid([
        "................",
        "..a....a....a...",
        "..b..a.b..a.b...",
        "...b.b.b..b.b.a.",
        ".a.c.b.c.b..c.b.",
        ".b.c..cc.c.c..b.",
        "..cd..c..cc..c..",
        "..cd.cd...c..c..",
        "...dcd....dcdc..",
        "....d..a..dd....",
        ".a..d..b..d..a..",
        ".bc.d..c.d..cb..",
        "..cdd..cdd.dc...",
        "....dd.dd.dd....",
        ".....ddDdd......",
        "......DDD.......",
    ], {"a": "#E0D0F4", "b": "#C4A8E6", "c": "#A688CE", "d": "#8466AE", "D": "#644A88"})


def pale_coral_fan():
    """A pale cream fan, like bleached coral at the edge of a hot pool."""
    return grid([
        "................",
        "................",
        "................",
        "................",
        "..a..a.a.a..a...",
        "...a.b.b.b.a..a.",
        ".a..bbcbcbb..a..",
        "..b.bcbcbcb.b...",
        "...bcbcdcbcb....",
        "....bcdcdcb.....",
        ".....cdddc......",
        "......ddd.......",
        "......dDd.......",
        ".......D........",
        "................",
        "................",
    ], {"a": "#F6EEDA", "b": "#E8DCC0", "c": "#D3C3A2", "d": "#B9A784", "D": "#9A8866"})


def ember_vines_plant():
    return _beaded_vines(8821, False, "#7A4A24", "#5E3818", "#9A6230", "#F7B046", "#FFE6A6", "#D9822A")


def ember_vines():
    return _beaded_vines(8821, True, "#7A4A24", "#5E3818", "#9A6230", "#F7B046", "#FFE6A6", "#D9822A")


PAINTERS = {
    "block/white_glow_flower": white_glow_flower,
    "block/lantern_fungus": lantern_fungus,
    "block/lantern_roots": lantern_roots,
    "block/wisteria": wisteria,
    "block/wisteria_plant": wisteria_plant,
    "block/lantern_pod": lantern_pod,
    "block/lavender_flower": lavender_flower,
    "block/gold_flower": gold_flower,
    "block/asurine_crystal": asurine_crystal,
    "block/blue_glow_mushroom": blue_glow_mushroom,
    "block/blue_glow_vines": blue_glow_vines,
    "block/blue_glow_vines_plant": blue_glow_vines_plant,
    "block/silver_fern": silver_fern,
    "block/orange_shelf_fungus": orange_shelf_fungus,
    "block/orange_shelf_fungus_under": orange_shelf_fungus_under,
    "block/lavender_coral": lavender_coral,
    "block/pale_coral_fan": pale_coral_fan,
    "block/ember_vines": ember_vines,
    "block/ember_vines_plant": ember_vines_plant,
}
