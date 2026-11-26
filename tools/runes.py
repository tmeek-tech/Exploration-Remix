"""Exploration Remix's runes: their art and their data. Called by gen_data.py.

A rune is an item (explorationremix:rune_<id>) drawn as a small slate tablet with a glowing glyph carved into it. The
glyph's colour tells the rune's kind (the socket it goes in) at a glance, in the enchanting table's colours: active
runes light blue, modifiers lavender, passives silver-blue, and the four element passives their element's own colour
(the colour its casts are drawn in, Element.java); armor's effect runes periwinkle, its collect runes pale lilac, and
tool runes pale sky blue (0.22.6). Every tablet is the same stone, so the glyph is the only thing that
changes from one rune to the next, and each glyph is drawn by hand on a 10 by 10 grid so that it still reads at 16 by
16.

This file also paints the textures the rune renderers tint (textures/entity/rune: white on transparent, a bright core
and a soft alpha falloff), the blocks runes make for a while (Barrier glass, Frost's ice, Glow's light, a struck fern's
unrolled fronds, a Rope arrow's rope), and
writes the rune data: the item tags by the layer a rune is first found in (and each layer's collect runes), the rune
damage type (shields never block it), Create's tag that keeps contraptions from carrying the rune blocks off, the loot
modifier that makes the world's loot plain now that the enchanting table sets runes, and the English names.

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
# kind is the socket it goes in (RuneKind: action is the active socket; effect and collect are armor's, tool a tool's);
# the layer is where a rune is first found: 0 the Upper Caves, 1 the Blue Reaches, 2 the Deep Gardens. Some ids are
# older than their names, so runes already found keep working: Overflow is "after" (once "After a Moment", then
# Echo), Return is "arc".
RUNES = {
    # sword actives
    "spark": ("Spark", "action", 0),
    "shard": ("Shard", "action", 0),
    "orb": ("Orb", "action", 0),
    "burst": ("Burst", "action", 0),
    "cleave": ("Cleave", "action", 0),
    "field": ("Field", "action", 1),
    "barrier": ("Barrier", "action", 0),
    "dash": ("Dash", "action", 0),
    "reel": ("Reel", "action", 1),
    # bow and crossbow actives: each goes off where its arrow lands
    "glow": ("Glow", "action", 0),
    "rope": ("Rope", "action", 0),
    "snare": ("Snare", "action", 1),
    "volley": ("Volley", "action", 1),
    "mine": ("Mine", "action", 2),
    # modifiers
    "swift": ("Swift", "modifier", 0),
    "heavy": ("Heavy", "modifier", 0),
    "wide": ("Wide", "modifier", 0),
    "seek": ("Seek", "modifier", 1),
    "arc": ("Return", "modifier", 0),
    "bounce": ("Bounce", "modifier", 0),
    "pierce": ("Pierce", "modifier", 1),
    "siphon": ("Siphon", "modifier", 2),
    "fan": ("Fan", "modifier", 1),
    # passives: the four elements, then the rest (Recall and Quickdraw are a bow's)
    "ember": ("Ember", "passive", 2),
    "frost": ("Frost", "passive", 2),
    "storm": ("Storm", "passive", 2),
    "tide": ("Tide", "passive", 2),
    "on_hit": ("On Hit", "passive", 1),
    "after": ("Overflow", "passive", 1),
    "recall": ("Recall", "passive", 0),
    "quickdraw": ("Quickdraw", "passive", 1),
    # armor's effect runes, three to each piece: helmet, chestplate, leggings, boots
    "lantern": ("Lantern", "effect", 0),
    "sense": ("Sense", "effect", 1),
    "far_sight": ("Far Sight", "effect", 0),
    "glide": ("Glide", "effect", 1),
    "second_wind": ("Second Wind", "effect", 2),
    "steadfast": ("Steadfast", "effect", 0),
    "stride": ("Stride", "effect", 0),
    "climb": ("Climb", "effect", 1),
    "swim": ("Swim", "effect", 1),
    "leap": ("Leap", "effect", 1),
    "feather": ("Feather", "effect", 0),
    "frost_walk": ("Frost Walk", "effect", 2),
    # armor's collect runes: two kinds to a layer, waking on three worn pieces
    "mend": ("Mend", "collect", 0),
    "vigor": ("Vigor", "collect", 0),
    "fleet": ("Fleet", "collect", 1),
    "thirst": ("Thirst", "collect", 1),
    "ward": ("Ward", "collect", 2),
    "wisdom": ("Wisdom", "collect", 2),
    # tool runes: a pickaxe's, an axe's, a shovel's or a hoe's one socket
    "haste": ("Haste", "tool", 0),
    "bounty": ("Bounty", "tool", 1),
    "gentle": ("Gentle", "tool", 2),
}
LAYERS = ["upper_caves", "blue_reaches", "deep_gardens"]

# What each rune does, one plain line (its tooltip: rune.explorationremix.<id>.desc). An active rune's second phase is
# RuneId's phaseTwo, shown under it with its level; a collect rune's set on three pieces and as a full set are RuneId's
# three and full, shown under it.
DESC = {
    "spark": "A quick bolt that flies straight and hits for 4.",
    "shard": "A close spray of five needles, each striking for itself, hardest up close.",
    "orb": "A slow, heavy ball that hits for 7.",
    "burst": "A blast all round you; hits for 6. Your own blast throws you but never hurts you.",
    "cleave": "A wide slash in front of you; hits for 6.",
    "field": "A patch on the ground that pulses into all who stand in it, with your weapon's element. Cast in the air, it drops to the floor.",
    "barrier": "A wall of rune glass across your aim, for 8 seconds.",
    "dash": "Throws you along your aim.",
    "reel": "Yanks a creature toward you, or pulls you to the wall it catches, up over its top when it can. Reaches 16 blocks; sneak to let go.",
    "glow": "A flare: where the arrow sticks, a light burns for a minute; a creature it strikes glows through walls.",
    "rope": "A rope of light hangs from where the arrow sticks, down to the floor or over a near lip, for 40 seconds. Climb it like a ladder.",
    "snare": "Roots of light hold the creatures round where the arrow lands in place for 3 seconds.",
    "volley": "Arrows of light rain down round where the arrow lands.",
    "mine": "A mine stays where the arrow lands, and goes off when a hostile creature comes near.",
    "swift": "Faster, with a shorter delay.",
    "heavy": "Hits harder and knocks back further, but slower.",
    "wide": "Bigger: a wider blast, a longer wall, a fatter bolt.",
    "seek": "Bends toward the nearest creature that is fighting you.",
    "arc": "Flies out and comes back to you, striking what it passes both ways.",
    "bounce": "Bounces off walls instead of stopping.",
    "pierce": "Passes through creatures and carries on.",
    "siphon": "Well-placed hits knock loose white motes that refill this weapon.",
    "fan": "Three of your cast at once, in a fan.",
    "ember": "Fire: full swings, arrows and casts set alight. Steams the wet, and thaws the frozen for extra damage.",
    "frost": "Cold: full swings, arrows and casts chill. Freezes the wet in place, and still water into ice.",
    "storm": "Lightning: full swings, arrows and casts leap to a second creature, and stun the wet.",
    "tide": "Water: full swings, arrows and casts soak, and put out fire.",
    "on_hit": "Every third full swing or arrow that hits a foe casts your active rune for free.",
    "after": "Energy the weapon gains past full makes its next cast a third stronger.",
    "recall": "Arrows fly back to you once they land.",
    "quickdraw": "A bow draws, and a crossbow loads, in half the time.",
    "lantern": "A soft light goes with you.",
    "sense": "Creatures hunting you within 24 blocks glow through the rock.",
    "far_sight": "Hold the peer key (Z) to look far off, as through a spyglass; a creature you hold in view is marked.",
    "glide": "Hold jump while falling to sink slowly and drift where you look. The landing never hurts.",
    "second_wind": "A hit that would leave you low heals you four hearts instead. It rests two minutes after.",
    "steadfast": "Knockback moves you half as far, and not at all while you sneak.",
    "stride": "You step up a full block, and walk through cobwebs, powder snow and berry bushes unslowed.",
    "climb": "Jump at a ledge up to two blocks over your head and you pull yourself up onto it.",
    "swim": "You swim 60% faster.",
    "leap": "Press jump in the air to jump once more before you land.",
    "feather": "Falls hurt half as much.",
    "frost_walk": "Still water freezes under your feet as you walk.",
    "mend": "XP you gather mends your gear.",
    "vigor": "More hearts.",
    "fleet": "A quicker pace.",
    "thirst": "Your weapons fill with energy faster.",
    "ward": "Creatures' hits hurt you less.",
    "wisdom": "More XP from what you kill and mine.",
    "haste": "Digs faster, as Efficiency III.",
    "bounty": "More from ores, as Fortune II.",
    "gentle": "Picks blocks up whole, as Silk Touch.",
}

# The blocks runes make for a while (RuneRegistry: TempBlock). They have no items and drop nothing. rune_frond is a
# struck silver fern's fronds, unrolled down the rock (0.18.7, RunePlants); its textures are the fern's, painted with
# the other plants (plants_upper.py). rune_lantern is Lantern's light, which follows the player: it is never drawn.
BLOCKS = {"rune_barrier": "Rune Barrier", "rune_ice": "Rune Ice", "rune_light": "Rune Light", "rune_frond": "Unrolled Fronds",
          "rune_lantern": "Rune Lantern", "rune_rope": "Rune Rope"}
ENTITIES = {"rune_bolt": "Rune Bolt", "rune_field": "Rune Field", "rune_mote": "Siphon Mote"}

# Everything else the player reads: the tooltips' words, the enchanting table's rune view, the key and what the casting
# code says.
LANG = {
    f"itemGroup.{MOD}.runes": "Runes",
    f"container.{MOD}.rune_table": "Runes",
    f"key.{MOD}.cast_runes": "Cast runes",
    f"key.{MOD}.peer": "Peer (Far Sight)",
    f"key.categories.{MOD}": "Exploration Remix",
    f"rune.{MOD}.kind.active": "Active",
    f"rune.{MOD}.kind.modifier": "Modifier",
    f"rune.{MOD}.kind.passive": "Passive",
    f"rune.{MOD}.kind.effect": "Effect",
    f"rune.{MOD}.kind.collect": "Collect",
    f"rune.{MOD}.kind.tool": "Tool",
    # what a rune fits (Fits.word), beside its kind on its tooltip
    f"rune.{MOD}.fits.sword": "Swords",
    f"rune.{MOD}.fits.bow": "Bows and crossbows",
    f"rune.{MOD}.fits.weapon": "Swords, bows and crossbows",
    f"rune.{MOD}.fits.armor": "Any armor",
    f"rune.{MOD}.fits.helmet": "Helmets",
    f"rune.{MOD}.fits.chestplate": "Chestplates",
    f"rune.{MOD}.fits.leggings": "Leggings",
    f"rune.{MOD}.fits.boots": "Boots",
    f"rune.{MOD}.fits.tool": "Pickaxes, axes, shovels and hoes",
    f"rune.{MOD}.fits.gear": "Gear",
    # a collect rune's set (RuneId's three and full)
    f"rune.{MOD}.collect_three": "On three pieces: %s",
    f"rune.{MOD}.collect_full": "Full set: %s",
    f"rune.{MOD}.cost": "Costs %s energy",
    f"rune.{MOD}.cost_each": "Adds %s energy to the cast it changes",
    f"rune.{MOD}.phase_two": "Phase two at level %s: %s",
    f"rune.{MOD}.set_at_table": "Set it into your gear at an enchanting table",
    f"rune.{MOD}.no_runes": "No runes: set them at an enchanting table",
    # the enchanting table's rune view (RuneTableScreen)
    f"screen.{MOD}.rune_table.how": "Click a socket to raise the runes you carry for it",
    f"screen.{MOD}.rune_table.how_chosen": "Click a rune to set it, or anywhere else to put them back",
    f"screen.{MOD}.rune_table.locked": "In combat: runes can be changed in %s s",
    f"screen.{MOD}.rune_table.empty": "Empty",
    f"screen.{MOD}.rune_table.none_set": "No runes set in this weapon yet.",
    f"screen.{MOD}.rune_table.socket_empty": "%s socket: empty",
    f"screen.{MOD}.rune_table.click_choose": "Click to raise the %s runes you carry",
    f"screen.{MOD}.rune_table.none_carried": "You carry no %s runes",
    f"screen.{MOD}.rune_table.click_change": "Click to change it, right-click to take it out",
    f"screen.{MOD}.rune_table.with_it": "With it:",
    f"screen.{MOD}.rune_table.without_it": "Without it:",
    f"screen.{MOD}.rune_table.take_out": "Take out %s",
    f"screen.{MOD}.rune_table.back_to_you": "The rune goes back to your inventory",
    # 0.20.5: the panel at the foot, its title when the mouse is over nothing, and what each socket is for
    f"screen.{MOD}.rune_table.what_it_does": "What it does",
    f"screen.{MOD}.rune_table.three": "A weapon holds three runes: one active, one modifier and one passive.",
    f"screen.{MOD}.rune_table.about.active": "The spell this weapon casts. Each active rune has a second, stronger phase that wakes at an XP level of its own.",
    f"screen.{MOD}.rune_table.about.modifier": "Shapes the active rune's spell: faster, heavier, wider, seeking, bouncing, piercing and more.",
    f"screen.{MOD}.rune_table.about.passive": "Works on its own: an element, a free cast, Overflow's charge, or a bow's Recall and Quickdraw.",
    # 0.22.6: armor and tools at the table, and the column of gear down its left
    f"screen.{MOD}.rune_table.about.effect": "What this piece does for you. Each piece has effect runes of its own.",
    f"screen.{MOD}.rune_table.about.collect": "Wakes when three worn pieces carry the same kind; all four make a full set.",
    f"screen.{MOD}.rune_table.about.tool": "A tool holds one rune: Haste digs faster, Bounty finds more ore, Gentle picks blocks up whole.",
    f"screen.{MOD}.rune_table.armor_three": "A piece of armor holds three runes: one effect and two collect.",
    f"screen.{MOD}.rune_table.none_set_armor": "No runes set in this piece yet.",
    f"screen.{MOD}.rune_table.none_set_tool": "No rune set in this tool yet.",
    f"screen.{MOD}.rune_table.no_effect": "No effect rune",
    f"screen.{MOD}.rune_table.no_runes": "No runes",
    f"screen.{MOD}.rune_table.hand": "In hand",
    f"screen.{MOD}.rune_table.nothing_held": "Hold a sword, bow, crossbow, tool or piece of armor to set its runes",
    f"screen.{MOD}.rune_table.nothing_worn": "Wear %s to set its runes",
    f"screen.{MOD}.rune_table.click_gear": "Click to set its runes",
    f"screen.{MOD}.rune_table.sets": "Sets",
    f"screen.{MOD}.rune_table.set_on": "On: %s",
    # 0.23.0: a piece of armor held in the hand, counted as if it were worn in place of the one worn there
    f"screen.{MOD}.rune_table.if_worn": "If worn:",
    f"screen.{MOD}.rune_table.if_worn_with_it": "If worn with it:",
    f"screen.{MOD}.rune_table.if_worn_without_it": "If worn without it:",
    f"screen.{MOD}.rune_table.held": "In your hand, not worn: its runes work once you put it on.",
    f"screen.{MOD}.rune_table.held_swap": "In your hand, not worn: its runes work once you put it on in place of your %s.",
    f"screen.{MOD}.rune_table.set_if_worn": "Wearing the %s in your hand:",
    f"message.{MOD}.runes.sputter": "Not enough energy: needs %s, has %s",
    f"message.{MOD}.runes.red": "This cast costs more than this %s holds",
    f"message.{MOD}.runes.nothing": "Nothing on this %s casts: set an active rune at an enchanting table",
    f"message.{MOD}.runes.busy": "Too many runes in flight",
    f"message.{MOD}.runes.phase_wakes": "%s: phase two wakes (level %s)",
    f"message.{MOD}.runes.phase_sleeps": "%s: phase two sleeps until level %s",
    f"message.{MOD}.runes.overflow": "Overflow: this %s's next cast is a third stronger",
    f"message.{MOD}.runes.handed_back": "Runes that don't fit where they were set are back in your inventory: %s",
    # armor's sets waking, filling and falling asleep, and Second Wind (the action bar)
    f"message.{MOD}.armor.wakes": "%s wakes: %s",
    f"message.{MOD}.armor.full": "%s, full set: %s",
    f"message.{MOD}.armor.not_full": "%s is no longer a full set: %s",
    f"message.{MOD}.armor.sleeps": "%s sleeps: it is on %s pieces, and wakes on three",
    f"message.{MOD}.armor.wind": "Second Wind!",
    f"message.{MOD}.armor.wind_back": "Second Wind is back",
    f"message.{MOD}.runes.converted": "Your weapons' rune rows are now sockets: one active rune, one modifier and one passive each. The runes that didn't fit are back in your inventory.",
    # the rune damage type's death messages: %2$s is the caster, %3$s their named weapon (.item); .player is
    # for the rare death with no caster known, where %2$s is whoever was fighting the victim
    f"death.attack.{MOD}.rune": "%1$s was struck down by %2$s's runes",
    f"death.attack.{MOD}.rune.item": "%1$s was struck down by %2$s using %3$s",
    f"death.attack.{MOD}.rune.player": "%1$s was struck down by runes while fighting %2$s",
    # 0.11.0's slams (combat.Slams): a creature knocked into a wall hard enough
    f"death.attack.{MOD}.slam": "%1$s was slammed into a wall by %2$s",
    f"death.attack.{MOD}.slam.item": "%1$s was slammed into a wall by %2$s using %3$s",
    f"death.attack.{MOD}.slam.player": "%1$s was slammed into a wall while fighting %2$s",
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
# glow is hottest), the dim its fainter ones. They are the enchanting table's socket colours (RuneTableView), with no
# warm colour or cyan among them. An element passive's main colour is the element's own (Element.java).
KIND_GLYPH = {
    "action": ("#8EC2FF", "#E6F2FF", "#4F7FC4"),
    "modifier": ("#B7A8FF", "#F0ECFF", "#7666C4"),
    "passive": ("#D6E6FF", "#FFFFFF", "#8495B8"),
    "effect": ("#9FB8FF", "#EEF3FF", "#5B6FB5"),
    "collect": ("#E0D4FF", "#FFFFFF", "#9886C6"),
    "tool": ("#BFE0FF", "#F4FAFF", "#6F96BE"),
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
    # a spray of needles, flying
    "shard": [
        ".......#..",
        "......*..#",
        "..#..#..*.",
        ".*..#..#..",
        "#..*..#...",
        "..#..*....",
        ".#..#.....",
        "#..*......",
        "..#.......",
        ".+........",
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
    # a boomerang, and the way it comes back
    "arc": [
        "......+++.",
        ".##......+",
        ".###.....+",
        "..###...+.",
        "...###....",
        "....######",
        ".....#####",
        "..........",
        "..........",
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
    # a full gauge, and energy rising past its top
    "after": [
        "....##....",
        "...#**#...",
        "..#.**.#..",
        "....**....",
        "##########",
        "#********#",
        "#********#",
        "##########",
        "..........",
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
    # a small lantern on its ring, its glass alight
    "lantern": [
        "....#.....",
        "...#.#....",
        "...###....",
        "..#####...",
        "..#***#...",
        "..#***#...",
        "..#***#...",
        "..#####...",
        "...+++....",
        "..........",
    ],
    # a goblet, brimming
    "thirst": [
        "..........",
        ".########.",
        ".#******#.",
        "..#****#..",
        "...#**#...",
        "....##....",
        "....##....",
        "....##....",
        "..######..",
        "..........",
    ],
    # ---- 0.22.6: bow and crossbow actives
    # a rope hanging from an arrow stuck in a lip, its strands twisting
    "rope": [
        "#######...",
        "...#..+*+.",
        "...##...+.",
        "...#+.....",
        "...+#.....",
        "....#+....",
        "....+#....",
        "....#+....",
        "...+#.....",
        "...**.....",
    ],
    # roots of light closing round a creature
    "snare": [
        "..#....#..",
        ".#......#.",
        "#...##...#",
        "#..#**#..#",
        "#..#**#..#",
        ".#..##..#.",
        "..#....#..",
        "...#..#...",
        "..#+..+#..",
        ".#......#.",
    ],
    # arrows of light raining down
    "volley": [
        ".#........",
        ".#....#...",
        ".#....#...",
        "#*#...#...",
        ".+...#*#..",
        "......+...",
        "...#....#.",
        "...#....#.",
        "..#*#...#.",
        "...+...#*#",
    ],
    # a mine on its spikes
    "mine": [
        "....#.....",
        ".#..#..#..",
        "..#####...",
        "..#***#...",
        "###*.*###.",
        "..#***#...",
        "..#####...",
        ".#..#..#..",
        "....#.....",
        "..........",
    ],
    # ---- a bow's passives
    # an arrow turning back
    "recall": [
        "..#.......",
        ".##.......",
        "#*#######.",
        ".##......#",
        "..#......#",
        ".........#",
        ".........#",
        "........#.",
        "..######..",
        "..........",
    ],
    # a bow drawn, its arrow nocked
    "quickdraw": [
        "...#......",
        "...##.....",
        "...#.#....",
        "...#..#.#.",
        "++#######*",
        "...#..#.#.",
        "...#.#....",
        "...##.....",
        "...#......",
        "..........",
    ],
    # ---- armor's effect runes
    # rings of a sound going out: what hunts you, felt
    "sense": [
        "..........",
        "..######..",
        ".#......#.",
        "#..####..#",
        "..#....#..",
        ".#..##..#.",
        "...#..#...",
        "....**....",
        "....**....",
        "..........",
    ],
    # an eye, looking far
    "far_sight": [
        "..........",
        "..........",
        "...####...",
        ".##....##.",
        "#...##...#",
        "#..#**#..#",
        "#...##...#",
        ".##....##.",
        "...####...",
        "..........",
    ],
    # wings held out on the air
    "glide": [
        "..........",
        "..........",
        "+........+",
        "##......##",
        ".###..###.",
        "...#**#...",
        "....++....",
        "..........",
        "..+....+..",
        "...+..+...",
    ],
    # a heart, and a breath of air curling round it
    "second_wind": [
        ".....###..",
        "........#.",
        ".##.##..#.",
        "#**#**#.#.",
        "#*****#...",
        "#*****#...",
        ".#***#....",
        "..#*#.....",
        "...#......",
        "..........",
    ],
    # an anchor: knocked about, you hold
    "steadfast": [
        "....##....",
        "...#..#...",
        "....##....",
        "..######..",
        "....##....",
        "....##....",
        "#...##...#",
        "##..**..##",
        ".###**###.",
        "...####...",
    ],
    # a step up
    "stride": [
        "..........",
        "......####",
        "......#...",
        "......#...",
        "...####...",
        "...#......",
        "...#......",
        "####......",
        "#.........",
        "+.........",
    ],
    # pulled up over a ledge
    "climb": [
        "....######",
        "....#.....",
        "..#.#.....",
        ".###......",
        "#*#*#.....",
        "..#.......",
        "..#.......",
        "..#.......",
        "..+.......",
        "..........",
    ],
    # a fish
    "swim": [
        "..........",
        "..........",
        "..####...#",
        ".#.....###",
        "#.*.....#.",
        "#.......#.",
        ".#.....###",
        "..####...#",
        "..........",
        "..........",
    ],
    # up, and up again
    "leap": [
        "....##....",
        "...#**#...",
        "..#....#..",
        ".#......#.",
        "....##....",
        "...#**#...",
        "..#....#..",
        ".#......#.",
        "..........",
        "+++++++++.",
    ],
    # a feather
    "feather": [
        "........#.",
        "......###.",
        ".....##*#.",
        "....##*##.",
        "...##*##..",
        "..##*##...",
        "..#*##....",
        ".#*#......",
        "#*........",
        "+.........",
    ],
    # footprints on ice
    "frost_walk": [
        "......##..",
        ".....####.",
        ".....####.",
        "......##..",
        "..##......",
        ".####.....",
        ".####.....",
        "..##......",
        "..........",
        "*#*#*#*#*#",
    ],
    # ---- armor's collect runes
    # a hammer, to mend with
    "mend": [
        "..........",
        "....##....",
        "...####...",
        "..###**#..",
        "...###*#..",
        "....#.##..",
        "...#......",
        "..#.......",
        ".#........",
        "+.........",
    ],
    # a heart, full
    "vigor": [
        "..........",
        ".##....##.",
        "#**#..####",
        "#*########",
        "##########",
        ".########.",
        "..######..",
        "...####...",
        "....##....",
        "..........",
    ],
    # a winged boot
    "fleet": [
        "..........",
        "#....###..",
        "##...###..",
        ".###.###..",
        "...#####..",
        ".....###..",
        "++...#####",
        ".++..#####",
        "..........",
        "..........",
    ],
    # a shield
    "ward": [
        ".########.",
        ".#......#.",
        ".#..**..#.",
        ".#.****.#.",
        ".#..**..#.",
        "..#.**.#..",
        "..#....#..",
        "...#..#...",
        "....##....",
        "..........",
    ],
    # an owl
    "wisdom": [
        "#........#",
        ".#......#.",
        ".########.",
        "#**#..#**#",
        "#**#..#**#",
        "#...##...#",
        ".#..##..#.",
        "..#....#..",
        "...####...",
        "..........",
    ],
    # ---- tool runes
    # a pick, swung fast
    "haste": [
        "..#####...",
        "+....###..",
        ".+....##..",
        "+....#.##.",
        ".+..#...#.",
        "...#....#.",
        "..#.......",
        ".#........",
        "#.........",
        "..........",
    ],
    # a gem, and its glint
    "bounty": [
        "........+.",
        ".......+*+",
        "..######+.",
        ".#*#..#*#.",
        "##########",
        ".#......#.",
        "..#....#..",
        "...#..#...",
        "....##....",
        "..........",
    ],
    # a block, whole
    "gentle": [
        "....##....",
        "..##**##..",
        "##******##",
        "#.##**##.#",
        "#...##...#",
        "#...##...#",
        "#...##...#",
        ".##.##.##.",
        "...####...",
        "..........",
    ],
}


def glyph_colours(rid):
    return ELEMENT_GLYPH[rid] if rid in ELEMENT_GLYPH else KIND_GLYPH[RUNES[rid][1]]


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


def rune_rope(fast=False, end=False):
    """A Rope arrow's rope of light, as a chain is drawn: two strands three pixels wide (x 0-2 and 3-5) for the model's
    two crossed planes. Light blue, twisted every four pixels; the second phase's paler and brighter; the bottom block's
    end frays into loose threads."""
    main, core, dim = (hexc(c) for c in (("#BFD9FF", "#FFFFFF", "#7FA6E0") if fast else ("#8EC2FF", "#E6F2FF", "#4F7FC4")))
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for strand in (0, 3):
        for y in range(16):
            twist = ((y + strand) // 2) % 2
            row = (dim, main, core) if twist == 0 else (core, main, dim)
            for i, c in enumerate(row):
                px[strand + i, y] = c
        if end:
            # the last five pixels fray: the middle thread runs on, the outer two stop short and splay
            for y in range(11, 16):
                for i in range(3):
                    px[strand + i, y] = (0, 0, 0, 0)
            for y, i, c in ((11, 0, dim), (11, 1, main), (11, 2, dim), (12, 1, core), (12, 0, dim), (13, 1, main), (13, 2, dim),
                            (14, 1, dim)):
                px[strand + i, y] = c
    return img


BLOCK_TEXTURES = {"rune_barrier": rune_barrier, "rune_ice": rune_ice, "rune_light": rune_light,
                  "rune_rope": rune_rope, "rune_rope_end": lambda: rune_rope(end=True),
                  "rune_rope_fast": lambda: rune_rope(fast=True), "rune_rope_fast_end": lambda: rune_rope(fast=True, end=True)}


def rope_model(look):
    """A rope's two crossed planes, as a chain's, lit full whatever the cave's light (it is light itself)."""
    tex = f"{MOD}:block/{look}"

    def plane(frm, to, faces):
        return {"from": frm, "to": to, "shade": False, "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45},
                "forge_data": {"block_light": 15}, "faces": faces}
    return {"parent": "minecraft:block/block", "render_type": "minecraft:cutout", "ambientocclusion": False,
            "textures": {"particle": tex, "rope": tex},
            "elements": [plane([6.5, 0, 8], [9.5, 16, 8], {f: {"uv": [0, 0, 3, 16], "texture": "#rope"} for f in ("north", "south")}),
                         plane([8, 0, 6.5], [8, 16, 9.5], {f: {"uv": [3, 0, 6, 16], "texture": "#rope"} for f in ("west", "east")})]}


def rope_lip_model(look):
    """A rope's top block where it reaches a wall's lip or hangs from a ledge's edge (the block level with the top): a
    short stub up from the strand below, then the strand bent over flat onto the top, reaching a little over half a block
    in (toward north here; the block state turns it). The flat part is the same crossed planes laid on their side, a
    pixel and a quarter over the top so it never flickers with the stone under it."""
    tex = f"{MOD}:block/{look}"

    def piece(frm, to, axis, origin, faces):
        return {"from": frm, "to": to, "shade": False, "rotation": {"origin": origin, "axis": axis, "angle": 45},
                "forge_data": {"block_light": 15}, "faces": faces}
    stub = [8, 1.25, 8]
    flat = [8, 1.25, 1]
    return {"parent": "minecraft:block/block", "render_type": "minecraft:cutout", "ambientocclusion": False,
            "textures": {"particle": tex, "rope": tex},
            "elements": [
                # the stub: the hanging strand's last pixels, up to the bend
                piece([6.5, 0, 8], [9.5, 2.75, 8], "y", stub, {f: {"uv": [0, 13.25, 3, 16], "texture": "#rope"} for f in ("north", "south")}),
                piece([8, 0, 6.5], [8, 2.75, 9.5], "y", stub, {f: {"uv": [3, 13.25, 6, 16], "texture": "#rope"} for f in ("west", "east")}),
                # the strand laid over the top, from the bend at the block's middle on over the lip
                piece([6.5, 1.25, -6], [9.5, 1.25, 9.5], "z", flat, {f: {"uv": [0, 0, 3, 15.5], "texture": "#rope"} for f in ("up", "down")}),
                piece([8, -0.25, -6], [8, 2.75, 9.5], "z", flat,
                      {f: {"uv": [3, 0, 6, 15.5], "rotation": 90, "texture": "#rope"} for f in ("west", "east")})]}


def rope_states():
    """fast (the second phase's paler rope) x end (the bottom block, frayed) x lip (the top block bent over a lip or an
    edge, turned to face it)."""
    turn = {"north": 0, "east": 90, "south": 180, "west": 270}
    out = {}
    for end in ("false", "true"):
        for fast in ("false", "true"):
            for lip in ("false", "true"):
                for facing in ("north", "east", "south", "west"):
                    key = f"end={end},facing={facing},fast={fast},lip={lip}"
                    if lip == "true" and end == "false":
                        out[key] = {"model": f"{MOD}:block/rune_rope" + ("_fast" if fast == "true" else "") + "_lip", "y": turn[facing]}
                    else:
                        out[key] = {"model": f"{MOD}:block/rune_rope" + ("_fast" if fast == "true" else "") + ("_end" if end == "true" else "")}
    return {"variants": out}


def _frond_sheet_model(look):
    """Unrolled fronds against the rock, the rock to the south (FrondBlock's facing north): the fronds' sheet and,
    just behind it, the darker back sheet (rune_frond<look>, rune_frond_back<look>), drawn both ways."""
    front, back = f"{MOD}:block/rune_frond{look}", f"{MOD}:block/rune_frond_back{look}"

    def plane(z, tex):
        return {"from": [0, 0, z], "to": [16, 16, z], "shade": False,
                "faces": {"north": {"uv": [0, 0, 16, 16], "texture": tex}, "south": {"uv": [16, 0, 0, 16], "texture": tex}}}
    return {"parent": "minecraft:block/block", "render_type": "minecraft:cutout", "ambientocclusion": False,
            "textures": {"particle": front, "front": front, "back": back},
            "elements": [plane(15.6, "#back"), plane(14.8, "#front")]}


def frond_models():
    """rune_frond's models: the sheet against the rock (a column's middle, its lowest block and the top of a column
    beside the fern's), and a strand hanging free, drawn as the hanging silver fronds are."""
    models = {f"rune_frond{look}": _frond_sheet_model(look) for look in ("", "_tip", "_top")}
    for name, tex in (("strand", "silver_frond_plant"), ("strand_2", "silver_frond_plant_2"), ("strand_tip", "silver_frond"),
                      ("strand_tip_2", "silver_frond_2")):
        models[f"rune_frond_{name}"] = {"parent": "minecraft:block/cross", "render_type": "minecraft:cutout",
                                        "textures": {"cross": f"{MOD}:block/{tex}"}}
    return models


def frond_states():
    """facing (away from the rock it lies on; down: hanging free) x bottom (a column's lowest block) x up (the top of a
    column beside the fern's). The sheet's model lies against the rock to its south and is turned as the hanging fern's
    is."""
    turns = {"north": {}, "east": {"y": 90}, "south": {"y": 180}, "west": {"y": 270}}
    variants = {}
    for facing in ("north", "south", "west", "east", "down"):
        for bottom in ("false", "true"):
            for up in ("false", "true"):
                if facing == "down":
                    look = "strand_tip" if bottom == "true" else "strand"
                    v = [{"model": f"{MOD}:block/rune_frond_{look}"}, {"model": f"{MOD}:block/rune_frond_{look}_2"}]
                else:
                    look = "_tip" if bottom == "true" else "_top" if up == "true" else ""
                    v = dict({"model": f"{MOD}:block/rune_frond{look}"}, **turns[facing])
                variants[f"bottom={bottom},facing={facing},up={up}"] = v
    return {"variants": variants}


def block_models():
    """Barrier glass and ice are clear full blocks; Glow's light a tiny bright cube that light comes from; a fern's
    unrolled fronds (frond_models); Lantern's light nothing at all (it is never drawn, only its breaking dust)."""
    six = ("down", "up", "north", "south", "west", "east")
    light = f"{MOD}:block/rune_light"
    ropes = {look: rope_model(look) for look in ("rune_rope", "rune_rope_end", "rune_rope_fast", "rune_rope_fast_end")}
    ropes.update({look + "_lip": rope_lip_model(look) for look in ("rune_rope", "rune_rope_fast")})
    return dict(frond_models(), **ropes, **{
        "rune_barrier": {"parent": "minecraft:block/cube_all", "render_type": "minecraft:translucent",
                         "textures": {"all": f"{MOD}:block/rune_barrier"}},
        "rune_ice": {"parent": "minecraft:block/cube_all", "render_type": "minecraft:translucent",
                     "textures": {"all": f"{MOD}:block/rune_ice"}},
        "rune_lantern": {"textures": {"particle": light}},
        "rune_light": {"parent": "minecraft:block/block", "render_type": "minecraft:cutout", "ambientocclusion": False,
                       "textures": {"particle": light, "light": light},
                       "elements": [{"from": [6.5, 6.5, 6.5], "to": [9.5, 9.5, 9.5], "shade": False,
                                     "forge_data": {"block_light": 15},
                                     "faces": {f: {"uv": [6.5, 6.5, 9.5, 9.5], "texture": "#light"} for f in six}}]},
    })


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
    for name, painter in BLOCK_TEXTURES.items():
        path = os.path.join(assets, "textures", "block", name + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        painter().save(path)
    for name, model in block_models().items():
        write(os.path.join(assets, "models", "block", name + ".json"), model)
    for bid, name in BLOCKS.items():
        states = frond_states() if bid == "rune_frond" else rope_states() if bid == "rune_rope" else {"variants": {"": {"model": f"{MOD}:block/{bid}"}}}
        write(os.path.join(assets, "blockstates", bid + ".json"), states)
        names[f"block.{MOD}.{bid}"] = name
    names.update({f"entity.{MOD}.{e}": n for e, n in ENTITIES.items()})
    # the rune tags: every rune; the runes first found in each layer (what the creatures there drop, now and then); and
    # each layer's two kinds of collect rune, which its creatures drop often (armor's sets want three of a kind)
    tag(MOD, "items", "runes", [f"{MOD}:rune_{r}" for r in RUNES])
    for i, layer in enumerate(LAYERS):
        tag(MOD, "items", f"runes/{layer}", [f"{MOD}:rune_{r}" for r, (_, k, l) in RUNES.items() if l == i and k != "collect"])
        tag(MOD, "items", f"runes/collect/{layer}", [f"{MOD}:rune_{r}" for r, (_, k, l) in RUNES.items() if l == i and k == "collect"])
    # 0.22.6: the enchanting table sets runes now, so the world's loot comes plain (rune.loot.PlainLoot): an enchanted
    # book found becomes a rune, enchanted gear comes without its enchantments
    write(os.path.join(data, "loot_modifiers", "plain_loot.json"), {"type": f"{MOD}:plain_loot", "conditions": []})
    write(os.path.join(res, "data", "forge", "loot_modifiers", "global_loot_modifiers.json"),
          {"replace": False, "entries": [f"{MOD}:plain_loot"]})
    # rune damage: the caster's, and never blocked by a shield
    write(os.path.join(data, "damage_type", "rune.json"), {"message_id": f"{MOD}.rune", "exhaustion": 0.1, "scaling": "never"})
    # a slam (0.11.0, combat.Slams): a creature a player knocked into a wall
    write(os.path.join(data, "damage_type", "slam.json"), {"message_id": f"{MOD}.slam", "exhaustion": 0.1, "scaling": "never"})
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
