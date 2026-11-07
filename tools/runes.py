"""Exploration Remix's runes: their art and their data. Called by gen_data.py.

A rune is an item (explorationremix:rune_<id>) drawn as a small slate tablet with a glowing glyph carved into it. The
glyph's colour tells the rune's kind at a glance: actions warm gold, modifiers pale cyan, each element its own colour
(the colour its casts are drawn in, Element.java), the relays soft green and Fan white. Every tablet is the same
stone, so the glyph is the only thing that changes from one rune to the next, and each glyph is drawn by hand on a
10 by 10 grid so that it still reads at 16 by 16.

This file also paints the textures the rune renderers tint (textures/entity/rune: white on transparent, a bright core
and a soft alpha falloff), the three blocks runes make for a while (Barrier glass, Frost's ice, Glow's light), and
writes the rune data: the item tags by the layer a rune is first found in, the rune damage type (shields never block
it), Create's tag that keeps contraptions from carrying the rune blocks off, and the English names.

The table below is copied from RuneId.java (the source of truth). check_table() compares the two whenever the Java is
there, so a rune added, renamed or moved to another layer there fails here until this file is changed to match.

python3 tools/runes.py --sheet OUT.png draws a contact sheet of the rune items (8x, and at the sizes the game shows
them), the entity textures and the block textures, for review. python3 tools/runes.py [RuneId.java Element.java] only
runs the check (against the given files, or the repo's own).
"""
import math, os, random, re, sys
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from plants import hexc

MOD = "explorationremix"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUNE_ID_JAVA = os.path.join(ROOT, "src/main/java/com/explorationremix/rune/engine/RuneId.java")
ELEMENT_JAVA = os.path.join(ROOT, "src/main/java/com/explorationremix/rune/engine/Element.java")


# =============================================================================================== the runes
# id -> (name, kind, layer), in RuneId's order. The name is RuneId's title in title case ("On hit" is "On Hit"); the
# layer is where a rune is first found: 0 the Upper Caves, 1 the Blue Reaches, 2 the Deep Gardens.
RUNES = {
    # actions
    "spark": ("Spark", "action", 0),
    "shard": ("Shard", "action", 0),
    "orb": ("Orb", "action", 0),
    "glow": ("Glow", "action", 0),
    "burst": ("Burst", "action", 0),
    "cleave": ("Cleave", "action", 0),
    "field": ("Field", "action", 1),
    "barrier": ("Barrier", "action", 0),
    "dash": ("Dash", "action", 0),
    "reel": ("Reel", "action", 1),
    # modifiers
    "swift": ("Swift", "modifier", 0),
    "heavy": ("Heavy", "modifier", 0),
    "wide": ("Wide", "modifier", 0),
    "seek": ("Seek", "modifier", 1),
    "arc": ("Arc", "modifier", 0),
    "bounce": ("Bounce", "modifier", 0),
    "pierce": ("Pierce", "modifier", 1),
    "siphon": ("Siphon", "modifier", 2),
    # elements
    "ember": ("Ember", "element", 2),
    "frost": ("Frost", "element", 2),
    "storm": ("Storm", "element", 2),
    "tide": ("Tide", "element", 2),
    # relays and Fan
    "on_hit": ("On Hit", "relay", 1),
    "after": ("After a Moment", "relay", 1),
    "fan": ("Fan", "fan", 1),
}
LAYERS = ["upper_caves", "blue_reaches", "deep_gardens"]

# What each rune does, one plain line (its tooltip: rune.explorationremix.<id>.desc).
DESC = {
    "spark": "A quick bolt that flies straight and hits for 4.",
    "shard": "A cheap, fast splinter that hits for 3.",
    "orb": "A slow, heavy ball that hits for 7.",
    "glow": "A drifting mote that lights where it stops, or makes the creature it hits glow, for a minute.",
    "burst": "A blast all round where it happens; hits for 6. Your own blast throws you but never hurts you.",
    "cleave": "A wide slash in front of where it happens; hits for 6.",
    "field": "A patch on the ground that pulses its element into all who stand in it. Let go in the air, it drops to the floor.",
    "barrier": "A wall of rune glass across your aim, for 8 seconds.",
    "dash": "Throws you along your aim; what follows goes off when you hit something.",
    "reel": "Yanks a creature toward you, or pulls you to the wall it catches, up over its top when it can. Reaches 16 blocks; sneak to let go.",
    "swift": "Faster, with a shorter delay.",
    "heavy": "Hits harder and knocks back further, but slower.",
    "wide": "Bigger: a wider blast, a longer wall, a fatter bolt.",
    "seek": "Bends toward the nearest creature that is fighting you.",
    "arc": "Lobbed: falls in an arc.",
    "bounce": "Bounces off walls instead of stopping.",
    "pierce": "Passes through creatures and carries on.",
    "siphon": "Well-placed hits knock loose white motes that refill this gear.",
    "ember": "Sets alight. Steams the wet; thaws the frozen for extra damage.",
    "frost": "Chills. Freezes the wet in place, and still water into ice.",
    "storm": "Leaps to a second creature; stuns the wet.",
    "tide": "Soaks, and puts out fire.",
    "on_hit": "What follows goes off where the rune before it hits.",
    "after": "What follows goes off a moment later, wherever the rune before it has got to.",
    "fan": "Three copies of what follows, in a fan.",
}

# The blocks runes make for a while (RuneRegistry: TempBlock). They have no items and drop nothing.
BLOCKS = {"rune_barrier": "Rune Barrier", "rune_ice": "Rune Ice", "rune_light": "Rune Light"}
ENTITIES = {"rune_bolt": "Rune Bolt", "rune_field": "Rune Field", "rune_mote": "Siphon Mote"}

# Everything else the player reads: the tooltips' words, the rune screen, the key and what the casting code says.
LANG = {
    f"itemGroup.{MOD}.runes": "Runes",
    f"container.{MOD}.rune_editor": "Runes",
    f"key.{MOD}.runes": "Open the rune screen",
    f"key.categories.{MOD}": "Exploration Remix",
    f"rune.{MOD}.kind.action": "Action",
    f"rune.{MOD}.kind.modifier": "Modifier",
    f"rune.{MOD}.kind.element": "Element",
    f"rune.{MOD}.kind.relay": "Relay",
    f"rune.{MOD}.kind.fan": "Fan",
    f"rune.{MOD}.cost": "Costs %s energy",
    f"rune.{MOD}.cost_each": "Adds %s energy to each action it changes",
    f"message.{MOD}.runes.in_combat": "In combat: you can move runes in %s s",
    f"message.{MOD}.runes.sputter": "Not enough energy: needs %s, has %s",
    f"message.{MOD}.runes.red": "Every cast costs more than this %s holds",
    f"message.{MOD}.runes.nothing": "Nothing in this row casts",
    f"message.{MOD}.runes.busy": "Too many runes in flight",
    f"message.{MOD}.runes.deeper": "%s: your gear's deeper rune slots wake",
    f"message.{MOD}.runes.home": "Home: your gear's deeper rune slots sleep until you go down again",
    # the rune damage type's death messages: %2$s is the caster, %3$s their named weapon (.item); .player is
    # for the rare death with no caster known, where %2$s is whoever was fighting the victim
    f"death.attack.{MOD}.rune": "%1$s was struck down by %2$s's runes",
    f"death.attack.{MOD}.rune.item": "%1$s was struck down by %2$s using %3$s",
    f"death.attack.{MOD}.rune.player": "%1$s was struck down by runes while fighting %2$s",
    # a rune's element set off a reaction (the action bar, in the element's colour)
    f"rune.{MOD}.reaction.shock": "Shock!",
    f"rune.{MOD}.reaction.freeze": "Freeze!",
    f"rune.{MOD}.reaction.shatter": "Shatter!",
    f"rune.{MOD}.reaction.steam": "Steam!",
    f"rune.{MOD}.reaction.thaw": "Thaw!",
}


def check_table(rune_java=RUNE_ID_JAVA, element_java=ELEMENT_JAVA):
    """RUNES against RuneId.java, and the element glyph colours against Element.java: the same runes in the same order,
    with the same titles, kinds and layers. Returns the differences (none when the Java is not there to compare)."""
    problems = []
    if os.path.exists(rune_java):
        src = open(rune_java).read()
        java = []
        for m in re.finditer(r'^\s*([A-Z_]+)\("([a-z_]+)",\s*"([^"]+)",\s*(?:RuneKind\.)?([A-Z]+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)',
                             src, re.M):
            _, rid, title, kind, _, _, _, layer = m.groups()
            if int(layer) >= 0:  # ARROW (layer -1) is a bow's own arrow, not a rune
                java.append((rid, title, kind.lower(), int(layer)))
        ours = [(rid, name, kind, layer) for rid, (name, kind, layer) in RUNES.items()]
        if [j[0] for j in java] != [o[0] for o in ours]:
            problems.append(f"runes: RuneId.java has {[j[0] for j in java]}, runes.py {[o[0] for o in ours]}")
        for (rid, title, kind, layer), (_, name, our_kind, our_layer) in zip(java, ours):
            if title.lower() != name.lower():
                problems.append(f"runes: {rid} is titled {title!r} in RuneId.java, {name!r} here")
            if kind != our_kind or layer != our_layer:
                problems.append(f"runes: {rid} is a layer {layer} {kind} in RuneId.java, a layer {our_layer} {our_kind} here")
    if os.path.exists(element_java):
        src = open(element_java).read()
        for name, colour in re.findall(r'^\s*[A-Z]+\("([a-z]+)",\s*0x([0-9A-Fa-f]{6})\)', src, re.M):
            if name in ELEMENT_GLYPH and ELEMENT_GLYPH[name][0].lstrip("#").upper() != colour.upper():
                problems.append(f"runes: {name} is #{colour} in Element.java, {ELEMENT_GLYPH[name][0]} here")
    return problems


# =============================================================================================== the tablet
# A rounded slate tablet, 14 by 14 at (1, 1): a lighter rim round a dark face, lit from the top left, and a soft shadow
# one pixel down and right. The glyph is carved into the face's middle 10 by 10 (GLYPH_AT).
SLATE_FACE = ["#232A39", "#272F3F", "#2B3344"]       # dark blue-grey, darkest to lightest
SLATE_RIM_LIT = "#5B6884"                            # the rim where the light falls on it (top, left)
SLATE_RIM = "#46526B"                                # the rim on the turn of a corner
SLATE_RIM_SHADE = "#343E52"                          # the rim away from the light (bottom, right)
SLATE_GLINT = "#7886A3"                              # the top left corner's catch of light
SHADOW = (8, 10, 16)
GLYPH_AT = (3, 3)


def tablet_mask():
    """The tablet's pixels: a 14 by 14 square at (1, 1) with its corners cut two pixels round."""
    cut = {(1, 1), (2, 1), (1, 2), (14, 1), (13, 1), (14, 2), (1, 14), (2, 14), (1, 13), (14, 14), (13, 14), (14, 13)}
    return {(x, y) for x in range(1, 15) for y in range(1, 15) if (x, y) not in cut}


def tablet():
    """The bare tablet, and the set of its face pixels (inside the rim)."""
    rng = random.Random(7301)
    mask = tablet_mask()
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    # the shadow: the tablet's shape one pixel down and right, softer where it turns a corner
    for (x, y) in mask:
        sx, sy = x + 1, y + 1
        if (sx, sy) not in mask and sx < 16 and sy < 16:
            corner = ((sx - 1, sy) not in mask) or ((sx, sy - 1) not in mask)
            px[sx, sy] = SHADOW + (64 if corner else 120,)
    face = set()
    for (x, y) in mask:
        up, left = (x, y - 1) not in mask, (x - 1, y) not in mask
        down, right = (x, y + 1) not in mask, (x + 1, y) not in mask
        if up or left or down or right:
            lit, shade = up or left, down or right
            c = SLATE_RIM_LIT if lit and not shade else SLATE_RIM_SHADE if shade and not lit else SLATE_RIM
            px[x, y] = hexc(c)
        else:
            face.add((x, y))
            # a little lighter at the top: the face catches the light more where it leans back
            t = 2 if y <= 3 else 1 if y <= 9 else 0
            if rng.random() < 0.18:
                t = max(0, min(2, t + rng.choice([-1, 1])))
            px[x, y] = hexc(SLATE_FACE[t])
    px[2, 2] = hexc(SLATE_GLINT)
    px[3, 1] = hexc(SLATE_GLINT)
    px[1, 3] = hexc(SLATE_GLINT)
    return img, face


# =============================================================================================== the glyphs
# Colours by kind: (main, core, dim). The main colour is the glyph's stroke; the core its brightest pixels (where the
# glow is hottest), the dim its fainter ones. An element's main colour is the element's own (Element.java).
KIND_GLYPH = {
    "action": ("#FFC53D", "#FFF1BE", "#C7861B"),
    "modifier": ("#7FF0E6", "#E2FFFB", "#3AA9AE"),
    "relay": ("#A2EC88", "#E6FFDC", "#5C9F4C"),
    "fan": ("#F1F5FF", "#FFFFFF", "#A6B2C8"),
}
ELEMENT_GLYPH = {
    "ember": ("#FF7A2E", "#FFE08A", "#B8461A"),
    "frost": ("#9FE4FF", "#F2FCFF", "#5797C7"),
    "storm": ("#C9A8FF", "#F6F0FF", "#8661D2"),
    "tide": ("#3F8CFF", "#A9CFFF", "#2A5BC2"),
}
HALO = 0.30       # how far a face pixel beside a stroke is lit toward the stroke's colour
HALO_CORNER = 0.14  # and one only touching a stroke at its corner

# Each glyph on a 10 by 10 grid: '#' stroke, '*' core, '+' dim, '.' bare stone. Drawn at GLYPH_AT.
GLYPHS = {
    # a four-point star
    "spark": [
        "....+.....",
        "....#.....",
        "....#.....",
        "...#*#....",
        "+##***##+.",
        "...#*#....",
        "....#.....",
        "....#.....",
        "....+.....",
        "..........",
    ],
    # a slim diamond, its left facet catching the light
    "shard": [
        "....#.....",
        "....#.....",
        "...*#.....",
        "...*#+....",
        "..*##+....",
        "..*##++...",
        "...*#+....",
        "...*#.....",
        "....#.....",
        "....+.....",
    ],
    # a filled circle
    "orb": [
        "..........",
        "...####...",
        "..#*##+#..",
        ".#**####+.",
        ".#*#####+.",
        ".########.",
        ".#######+.",
        "..#####+..",
        "...+++#...",
        "..........",
    ],
    # a dot with short rays
    "glow": [
        "....#.....",
        ".#.....#..",
        "...###....",
        "..#***#...",
        "#.#***#.#.",
        "..#***#...",
        "...###....",
        ".#.....#..",
        "....#.....",
        "..........",
    ],
    # lines radiating from an empty middle, brightest where they fly out
    "burst": [
        "*...*...*.",
        ".#..#..#..",
        "..+.+.+...",
        "..........",
        "*#+...+#*.",
        "..........",
        "..+.+.+...",
        ".#..#..#..",
        "*...*...*.",
        "..........",
    ],
    # a crescent: the sweep of a blade
    "cleave": [
        ".....##...",
        "...##*....",
        "..#*......",
        ".#*.......",
        ".#*.......",
        ".#*.......",
        ".#*.......",
        "..#*......",
        "...##*....",
        ".....##...",
    ],
    # a flat ring on the ground
    "field": [
        "..........",
        "..........",
        "...####...",
        ".##....##.",
        "#........#",
        "#........#",
        ".##....##.",
        "...####...",
        "..........",
        "..........",
    ],
    # a short brick wall
    "barrier": [
        "..........",
        "..........",
        "##########",
        "#...#....#",
        "#...#....#",
        "##########",
        "#.#....#.#",
        "#.#....#.#",
        "##########",
        "..........",
    ],
    # a double chevron
    "dash": [
        "..........",
        "##...##...",
        ".##...##..",
        "..##...##.",
        "...##...##",
        "..##...##.",
        ".##...##..",
        "##...##...",
        "..........",
        "..........",
    ],
    # a hook
    "reel": [
        "......##..",
        ".....#..#.",
        "......##..",
        ".......#..",
        ".......#..",
        ".#.....#..",
        "###....#..",
        ".#.....#..",
        ".##...##..",
        "..#####...",
    ],
    # one forward chevron with speed lines
    "swift": [
        "..........",
        ".....##...",
        "++....##..",
        ".......##.",
        "####....##",
        ".......##.",
        "++....##..",
        ".....##...",
        "..........",
        "..........",
    ],
    # a weight with a ring to lift it by
    "heavy": [
        "...####...",
        "..#....#..",
        "..#....#..",
        "...####...",
        "..######..",
        ".#*######.",
        ".#*######.",
        "#*#######+",
        "#*#######+",
        "+++++++++.",
    ],
    # two arrows apart
    "wide": [
        "..........",
        "..#....#..",
        ".##....##.",
        "###....###",
        "####..####",
        "###....###",
        ".##....##.",
        "..#....#..",
        "..........",
        "..........",
    ],
    # a crosshair
    "seek": [
        "....#.....",
        "..#####...",
        ".#.....#..",
        ".#.....#..",
        "##..*..##.",
        ".#.....#..",
        ".#.....#..",
        "..#####...",
        "....#.....",
        "..........",
    ],
    # a lob: up and over, coming down at its end
    "arc": [
        "..........",
        "...####...",
        "..#....#..",
        ".#......#.",
        ".#......#.",
        "#.......#.",
        "#.......#.",
        "*......###",
        "........#.",
        "..........",
    ],
    # a path off a line: down to the ground and up again
    "bounce": [
        "..........",
        "..........",
        "#......###",
        ".#......##",
        "..#....#.#",
        "...#..#...",
        "....**....",
        "##########",
        "..........",
        "..........",
    ],
    # an arrow through a bar
    "pierce": [
        "....++....",
        "....++....",
        "....++.#..",
        "....++..#.",
        "##########",
        "....++..#.",
        "....++.#..",
        "....++....",
        "....++....",
        "..........",
    ],
    # a drop, and a loop from its foot coming back up to it
    "siphon": [
        ".#....#...",
        "###..###..",
        ".#..#*###.",
        ".#..#*###.",
        ".#..#####.",
        ".#...###..",
        ".#.....#..",
        "..#.....#.",
        "...#####..",
        "..........",
    ],
    # a flame
    "ember": [
        "....#.....",
        "....##....",
        "...###..#.",
        "...####.#.",
        "..######..",
        ".###**###.",
        ".##****##.",
        ".##****##.",
        "..#****#..",
        "...####...",
    ],
    # a snowflake
    "frost": [
        "...#.#....",
        "....#.....",
        "..#.#.#...",
        "#..+#+..#.",
        ".###*###..",
        "#..+#+..#.",
        "..#.#.#...",
        "....#.....",
        "...#.#....",
        "..........",
    ],
    # a lightning bolt
    "storm": [
        ".....###..",
        "....###...",
        "...###....",
        "..######..",
        "....###...",
        "...###....",
        "...##.....",
        "..##......",
        "..#.......",
        "..........",
    ],
    # a wave
    "tide": [
        "..........",
        "..........",
        ".**...**..",
        "#..#.#..#.",
        "....#....#",
        "..........",
        ".##...##..",
        "#..#.#..#.",
        "....#....#",
        "..........",
    ],
    # a dot striking a bar, with a spark
    "on_hit": [
        ".......##.",
        "....#..##.",
        ".....#.##.",
        ".......##.",
        ".###.*.##.",
        "#####*###.",
        ".###.*.##.",
        ".......##.",
        ".....#.##.",
        "....#..##.",
    ],
    # an hourglass
    "after": [
        ".#######..",
        "..#...#...",
        "..#+++#...",
        "...#+#....",
        "....#.....",
        "...#+#....",
        "..#...#...",
        "..#+++#...",
        ".#######..",
        "..........",
    ],
    # three lines spreading
    "fan": [
        "#...#...#.",
        "#...#...#.",
        ".#..#..#..",
        ".#..#..#..",
        "..#.#.#...",
        "..#.#.#...",
        "...###....",
        "....#.....",
        "....#.....",
        "..........",
    ],
}


def glyph_colours(rid):
    kind = RUNES[rid][1]
    return ELEMENT_GLYPH[rid] if kind == "element" else KIND_GLYPH[kind]


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3)) + (255,)


def rune_item(rid):
    """A rune's item texture: the tablet with its glyph carved in, glowing in its kind's colour."""
    img, face = tablet()
    px = img.load()
    rows = GLYPHS[rid]
    assert len(rows) == 10 and all(len(r) == 10 for r in rows), f"{rid}: a glyph is 10 by 10"
    main, core, dim = (hexc(c) for c in glyph_colours(rid))
    colour = {"#": main, "*": core, "+": dim}
    gx, gy = GLYPH_AT
    strokes = {(gx + x, gy + y): colour[ch] for y, row in enumerate(rows) for x, ch in enumerate(row) if ch != "."}
    for (x, y) in strokes:
        assert (x, y) in face, f"{rid}: the glyph runs onto the rim at {(x, y)}"
    # the glow on the stone round the strokes
    for (x, y) in face:
        if (x, y) in strokes:
            continue
        if any(p in strokes for p in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1))):
            px[x, y] = mix(px[x, y], main, HALO)
        elif any(p in strokes for p in ((x - 1, y - 1), (x + 1, y - 1), (x - 1, y + 1), (x + 1, y + 1))):
            px[x, y] = mix(px[x, y], main, HALO_CORNER)
    for (x, y), c in strokes.items():
        px[x, y] = c
    return img


# =============================================================================================== the renderers' glows
# White on transparent, for the renderers to tint with a rune's colour (RuneFx.colour): a bright, solid core and a soft
# fall of alpha to nothing before the edge. Alpha comes in 24 steps, as the fireflies' glow does, so it reads as soft
# rings rather than mush. The colour is white wherever alpha is above nothing, so draw them alpha blended.

def _glow_texture(size, alpha_at):
    """alpha_at(dx, dy) is the light at a pixel's offset from the middle, in pixels; 0 to 1."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    mid = (size - 1) / 2.0
    for y in range(size):
        for x in range(size):
            a = max(0.0, min(1.0, alpha_at(x - mid, y - mid)))
            a = round(a * 24) / 24.0
            if a > 0:
                px[x, y] = (255, 255, 255, int(round(255 * a)))
    return img


def _edge(r, end):
    """1 well inside, falling smoothly to 0 at radius end."""
    t = max(0.0, min(1.0, r / end))
    return (1.0 - t * t) ** 2


def spark_glow():
    """A four-point star: long thin points along the axes and a round bright heart."""
    def a(dx, dy):
        r = math.hypot(dx, dy)
        heart = math.exp(-(r / 1.9) ** 2)
        along, across = max(abs(dx), abs(dy)), min(abs(dx), abs(dy))
        point = math.exp(-(across / (0.55 + 0.35 * max(0.0, 1.0 - along / 7.5))) ** 2) * max(0.0, 1.0 - along / 7.9) ** 0.9
        halo = 0.35 * math.exp(-(r / 3.6) ** 2)
        return heart + point + halo
    return _glow_texture(16, a)


def shard_glow():
    """A slim diamond, top to bottom, brightest along its middle."""
    def a(dx, dy):
        d = abs(dx) / 3.6 + abs(dy) / 7.6
        body = _edge(d, 1.0)
        ridge = math.exp(-(dx / 0.9) ** 2) * _edge(abs(dy), 6.4)
        return 0.8 * body + 0.6 * ridge
    return _glow_texture(16, a)


def orb_glow():
    """A round soft ball: solid in the middle, a soft rim."""
    def a(dx, dy):
        r = math.hypot(dx, dy)
        return min(1.0, 1.25 * _edge(r, 7.4)) if r > 2.5 else 1.0
    return _glow_texture(16, a)


def glow_glow():
    """A soft dot with faint rays round it."""
    def a(dx, dy):
        r = math.hypot(dx, dy)
        dot = math.exp(-(r / 2.6) ** 2)
        ang = math.atan2(dy, dx)
        rays = 0.4 * max(0.0, math.cos(4 * ang)) ** 6 * _edge(r, 7.6) * min(1.0, r / 2.5)
        return dot + rays + 0.12 * _edge(r, 7.5)
    return _glow_texture(16, a)


def mote_glow():
    """A small soft white dot."""
    return _glow_texture(16, lambda dx, dy: math.exp(-(math.hypot(dx, dy) / 2.2) ** 2) * 1.15)


def field_glow():
    """A soft ring with a faint fill: the Field's edge lies on the ring, so it is drawn near the texture's edge."""
    def a(dx, dy):
        r = math.hypot(dx, dy)
        ring = math.exp(-((r - 13.2) / 1.5) ** 2)
        fill = 0.16 + 0.12 * (r / 13.2) ** 2 if r < 13.2 else 0.0
        return (ring + fill) * (1.0 if r < 14.5 else _edge(r - 14.5, 1.4))
    return _glow_texture(32, a)


ENTITY_TEXTURES = {
    "spark": spark_glow,
    "shard": shard_glow,
    "orb": orb_glow,
    "glow": glow_glow,
    "mote": mote_glow,
    "field": field_glow,
}


# =============================================================================================== the blocks

def rune_barrier():
    """Rune glass: clear pale blue, a brighter edge, a faint glowing rune across it."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    rng = random.Random(7411)
    for y in range(16):
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            if edge:
                px[x, y] = (196, 236, 255, 170 if (x in (0, 15) and y in (0, 15)) else 150)
            else:
                px[x, y] = (150, 214, 250, 72 + rng.choice([0, 0, 0, 8]))
    # a sheen across the top left corner
    for (x, y) in ((2, 4), (3, 3), (4, 2), (2, 6), (3, 5), (4, 4), (5, 3), (6, 2)):
        px[x, y] = (214, 244, 255, 120)
    # the rune: a stem with two arms raised (protection), faint but lit
    rune = [(8, y) for y in range(5, 13)] + [(7, 7), (6, 6), (5, 5), (9, 7), (10, 6), (11, 5)]
    for (x, y) in rune:
        for (nx, ny) in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if (nx, ny) not in rune and 0 < nx < 15 and 0 < ny < 15:
                r, g, b, al = px[nx, ny]
                px[nx, ny] = (max(r, 180), max(g, 230), 255, max(al, 104))
    for (x, y) in rune:
        px[x, y] = (226, 248, 255, 190)
    return img


def rune_ice():
    """Clear pale ice with a few fine white cracks, which run on across the edges so a sheet of it tiles."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    rng = random.Random(7421)
    for y in range(16):
        for x in range(16):
            # faint long streaks, as still water freezes, repeating every 16 pixels so the sheet tiles
            v = 0.5 + 0.35 * math.sin(2 * math.pi * (x + 2 * y) / 16) + 0.15 * rng.random()
            px[x, y] = (204 + int(24 * v), 236 + int(12 * v), 255, 112 + int(28 * v))
    crack_px = set()

    def crack(x, y, angle, n, branch=True):
        fx, fy = x + 0.5, y + 0.5
        for _ in range(n):
            cx, cy = math.floor(fx) % 16, math.floor(fy) % 16
            crack_px.add((cx, cy))
            if branch and rng.random() < 0.18:
                crack(cx, cy, angle + rng.choice([-1.0, 1.0]), rng.randrange(2, 4), branch=False)
            angle += rng.uniform(-0.45, 0.45)
            fx, fy = fx + math.cos(angle), fy + math.sin(angle)

    crack(1, 4, 0.35, 11)
    crack(9, 1, 1.75, 8)
    crack(12, 10, 2.6, 7)
    for (x, y) in crack_px:
        # the crack's far side is a shade darker, so it reads as a cut in the ice
        sx, sy = (x + 1) % 16, (y + 1) % 16
        if (sx, sy) not in crack_px:
            px[sx, sy] = (172, 210, 238, 150)
    for (x, y) in crack_px:
        px[x, y] = (246, 252, 255, 205)
    # a few trapped bubbles
    for (x, y) in ((4, 12), (13, 3), (7, 7)):
        if (x, y) not in crack_px:
            px[x, y] = (236, 250, 255, 170)
    return img


def rune_light():
    """Glow's light: warm white, whitest in its middle (the little cube shows the middle three pixels)."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5) / 10.6
            c = mix(hexc("#FFFFFF"), hexc("#FFE3A0"), min(1.0, r * 1.1))
            px[x, y] = c
    return img


BLOCK_TEXTURES = {"rune_barrier": rune_barrier, "rune_ice": rune_ice, "rune_light": rune_light}


def block_models():
    """Barrier glass and ice are clear full blocks; Glow's light a tiny bright cube that light comes from."""
    six = ("down", "up", "north", "south", "west", "east")
    light = f"{MOD}:block/rune_light"
    return {
        "rune_barrier": {"parent": "minecraft:block/cube_all", "render_type": "minecraft:translucent",
                         "textures": {"all": f"{MOD}:block/rune_barrier"}},
        "rune_ice": {"parent": "minecraft:block/cube_all", "render_type": "minecraft:translucent",
                     "textures": {"all": f"{MOD}:block/rune_ice"}},
        "rune_light": {"parent": "minecraft:block/block", "render_type": "minecraft:cutout", "ambientocclusion": False,
                       "textures": {"particle": light, "light": light},
                       "elements": [{"from": [6.5, 6.5, 6.5], "to": [9.5, 9.5, 9.5], "shade": False,
                                     "forge_data": {"block_light": 15},
                                     "faces": {f: {"uv": [6.5, 6.5, 9.5, 9.5], "texture": "#light"} for f in six}}]},
    }


# =============================================================================================== writing

def drop_pool(chance, looting, tags):
    """A loot pool for a creature's table: now and then, when a player kills it, one rune from tags [(layer, weight)]
    (weight None: the entry's default)."""
    entries = []
    for layer, weight in tags:
        e = {"type": "minecraft:tag", "name": f"{MOD}:runes/{layer}", "expand": True}
        if weight is not None:
            e["weight"] = weight
        entries.append(e)
    return {"rolls": 1, "conditions": [{"condition": "minecraft:killed_by_player"},
                                       {"condition": "minecraft:random_chance_with_looting", "chance": chance, "looting_multiplier": looting}],
            "entries": entries}


def write_all(res, write, tag):
    """Writes the rune items' textures and models, the renderers' textures, the rune blocks, the rune tags and damage
    type; returns the lang entries."""
    if not os.path.exists(RUNE_ID_JAVA):
        print("runes: RuneId.java not found, so the rune table was not checked against it")
    problems = check_table()
    assert not problems, "runes.py's table differs from the Java:\n" + "\n".join(problems)
    assert set(GLYPHS) == set(RUNES) == set(DESC), "every rune needs a glyph and a line"
    assets = os.path.join(res, "assets", MOD)
    data = os.path.join(res, "data", MOD)
    names = dict(LANG)
    for rid, (name, kind, layer) in RUNES.items():
        path = os.path.join(assets, "textures", "item", f"rune_{rid}.png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        rune_item(rid).save(path)
        write(os.path.join(assets, "models", "item", f"rune_{rid}.json"),
              {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/rune_{rid}"}})
        names[f"item.{MOD}.rune_{rid}"] = f"{name} Rune"
        names[f"rune.{MOD}.{rid}.desc"] = DESC[rid]
    tex = os.path.join(assets, "textures", "entity", "rune")
    os.makedirs(tex, exist_ok=True)
    for name, painter in ENTITY_TEXTURES.items():
        painter().save(os.path.join(tex, name + ".png"))
    for bid, model in block_models().items():
        path = os.path.join(assets, "textures", "block", bid + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        BLOCK_TEXTURES[bid]().save(path)
        write(os.path.join(assets, "models", "block", bid + ".json"), model)
        write(os.path.join(assets, "blockstates", bid + ".json"), {"variants": {"": {"model": f"{MOD}:block/{bid}"}}})
        names[f"block.{MOD}.{bid}"] = BLOCKS[bid]
    names.update({f"entity.{MOD}.{e}": n for e, n in ENTITIES.items()})
    # the rune tags: every rune, and the runes first found in each layer (what the creatures there drop)
    tag(MOD, "items", "runes", [f"{MOD}:rune_{r}" for r in RUNES])
    for i, layer in enumerate(LAYERS):
        tag(MOD, "items", f"runes/{layer}", [f"{MOD}:rune_{r}" for r, (_, _, l) in RUNES.items() if l == i])
    # rune damage: the caster's, and never blocked by a shield
    write(os.path.join(data, "damage_type", "rune.json"), {"message_id": f"{MOD}.rune", "exhaustion": 0.1, "scaling": "never"})
    tag("minecraft", "damage_type", "bypasses_shield", [f"{MOD}:rune"])
    # Create's contraptions leave the rune blocks where they are: they are gone in seconds anyway
    tag("create", "blocks", "non_movable", [f"{MOD}:{b}" for b in BLOCKS])
    return names


# =============================================================================================== review

def sheet(path, scale=8, cols=7):
    """A labelled contact sheet: every rune at scale, the runes at 1x to 3x on a slot's grey and on a dark screen, the
    renderers' glows tinted and the rune blocks."""
    from PIL import ImageFont
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 13)
    except OSError:
        font = ImageFont.load_default()
    ids = list(RUNES)
    cell = 16 * scale + 16
    rows = (len(ids) + cols - 1) // cols
    small_h = 3 * (16 * 3 + 8) + 8
    glow_h = 32 * 4 + 40
    width = max(cols * cell + 16, len(ids) * (16 * 3 + 6) + 16)
    out = Image.new("RGB", (width, rows * (cell + 20) + 16 + small_h + glow_h + 16 * 4 + 60), (40, 40, 46))
    d = ImageDraw.Draw(out)
    for i, rid in enumerate(ids):
        x, y = 8 + (i % cols) * cell, 8 + (i // cols) * (cell + 20)
        t = rune_item(rid).resize((16 * scale, 16 * scale), Image.NEAREST)
        out.paste(t, (x, y), t)
        d.text((x, y + 16 * scale + 2), f"{rid} ({RUNES[rid][1]})", fill=(230, 230, 230), font=font)
    # actual sizes: on an inventory slot's grey, and on a dark panel
    y0 = 8 + rows * (cell + 20)
    for k, (bg, sc) in enumerate((((139, 139, 139), 1), ((139, 139, 139), 2), ((24, 24, 30), 3))):
        y = y0 + k * (16 * 3 + 8)
        d.rectangle((0, y - 4, width, y + 16 * sc + 4), fill=bg)
        for i, rid in enumerate(ids):
            t = rune_item(rid).resize((16 * sc, 16 * sc), Image.NEAREST)
            out.paste(t, (8 + i * (16 * sc + 6), y), t)
    # the glows, tinted with a few rune colours, on dark and on a pale sky
    y = y0 + small_h + 8
    tints = [(0xFF, 0xE9, 0xA8), (0xBD, 0xEB, 0xFF), (0xD6, 0xA8, 0xFF), (0xFF, 0x7A, 0x2E)]
    x = 8
    for name, painter in ENTITY_TEXTURES.items():
        g = painter()
        s = 4 if g.size[0] == 16 else 2
        for j, bg in enumerate(((16, 16, 22), (150, 190, 230))):
            tint = tints[(list(ENTITY_TEXTURES).index(name) + j) % len(tints)]
            tinted = g.copy()
            tp = tinted.load()
            for yy in range(g.size[1]):
                for xx in range(g.size[0]):
                    r, gg, b, a = tp[xx, yy]
                    tp[xx, yy] = (r * tint[0] // 255, gg * tint[1] // 255, b * tint[2] // 255, a)
            big = tinted.resize((g.size[0] * s, g.size[1] * s), Image.NEAREST)
            d.rectangle((x, y + j * 70, x + 64, y + j * 70 + 64), fill=bg)
            out.paste(big, (x, y + j * 70), big)
        d.text((x, y + 140), name, fill=(230, 230, 230), font=font)
        x += 80
    # the blocks, a few side by side so the glass and ice tile, over stone
    x += 16
    for bid, painter in BLOCK_TEXTURES.items():
        t = painter().resize((64, 64), Image.NEAREST)
        d.rectangle((x, y, x + 128, y + 64), fill=(96, 96, 100))
        for k in range(0, 128, 16):
            d.line((x + k, y, x + k, y + 64), fill=(70, 70, 74))
        out.paste(t, (x, y), t)
        out.paste(t, (x + 64, y), t)
        d.text((x, y + 140), bid, fill=(230, 230, 230), font=font)
        x += 140
    out.save(path)


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--sheet":
        sheet(sys.argv[2])
        print("sheet written:", sys.argv[2])
    else:
        problems = check_table(*sys.argv[1:3]) if len(sys.argv) >= 3 else check_table()
        for p in problems:
            print(p)
        print(f"rune table check: {len(problems)} problems")
