#!/usr/bin/env python3
"""Generates Exploration Remix's datapack JSON, blockstates, models, lang and textures.

Run from anywhere: python3 tools/gen_data.py
Everything this script writes lives in folders it owns, which it clears first, so delete a block or biome here and
its files disappear on the next run. Edit this script, not the JSON.
"""
import json, math, os, shutil
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import textures
import plants_vent
from plants_fern import HANGING_SIZES
import choir_blocks
import rune_stones
import worldgen
import sounds
import creatures
import runes
import rune_table_art
import swings
import check_data

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src/main/resources")
MOD = "explorationremix"
DATA = os.path.join(RES, "data", MOD)
ASSETS = os.path.join(RES, "assets", MOD)

OWNED = [
    os.path.join(DATA, "worldgen"), os.path.join(DATA, "tags"), os.path.join(DATA, "forge"), os.path.join(DATA, "loot_tables"),
    os.path.join(RES, "data", "minecraft", "tags"), os.path.join(RES, "data", "forge", "tags"), os.path.join(RES, "data", "immersive_weathering"),
    os.path.join(ASSETS, "blockstates"), os.path.join(ASSETS, "models"), os.path.join(ASSETS, "textures"), os.path.join(ASSETS, "lang"),
    os.path.join(ASSETS, "sounds.json"), os.path.join(ASSETS, "particles"), os.path.join(ASSETS, "geometry"),
    # the runes (runes.py): their damage type, and Create's tag that keeps contraptions off the rune blocks
    os.path.join(DATA, "damage_type"), os.path.join(RES, "data", "create", "tags"),
    # and the loot modifier that makes the world's loot plain (0.22.6)
    os.path.join(DATA, "loot_modifiers"), os.path.join(RES, "data", "forge", "loot_modifiers"),
]


# Files in the folders this script owns that it does not paint itself: Runes 0.11.0's effect textures (the Field's
# new look and glow, the bolts' halo and trail, the rune particles' sprites) were made by hand-run scripts. Kept as
# they are across runs (the generator's own older Field texture is replaced by the kept one).
KEPT = [os.path.join(ASSETS, "textures", "entity", "rune", n + ".png") for n in ("field", "field_glow", "halo", "trail")] + \
       [os.path.join(ASSETS, "textures", "particle", f"rune_{n}.png") for n in ("dot", "flash", "ring", "spark", "star")]


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def tag(ns, kind, name, values, replace=False): write(os.path.join(RES, "data", ns, "tags", kind, name + ".json"), {"replace": replace, "values": values})


# =============================================================================================== blocks
# Every block the mod adds: id -> (English name, kind) or (English name, kind, options). Kinds decide the model,
# blockstate, item and drop:
#   cube        a full block with one texture
#   cross       a plant drawn as two crossed planes (also single hanging plants and underwater plants)
#   vine        the growing tip of a hanging vine; "vine_body:<tip>" is the length above it, which drops the tip's item
#   crystal     a cluster that grows on any face, turned like vanilla's amethyst clusters
#   coral_fan   a flat fan, like vanilla's coral fans
#   shelf       bracket fungus on a wall, turned to face away from it
#   tall        a two-block plant: textures <id>_bottom and <id>_top; drops from its lower half only
#   cattails    a plant two or three blocks tall (block.SilverCattailsBlock): property part (short_bottom and short_top
#               for two, bottom, middle and top for three) and waterlogged, textures <id>_<part>; every block of a
#               plant is seeded from its foot, so variant k's slices stand together; drops once, from its foot
#   lichen      a sheet on any of the six faces, like glow lichen (a GlowLichenBlock); drops one per face
#   carpet      a flat sheet lying on the floor, turned at random
#   litter      a flat sheet lying on the floor whose look and turn are the block's state (properties look, one per
#               variant, and facing), so a feature or the game can choose them; drops the item named by option drop
#               (any namespace) with option chance
#   moss_carpet a carpet one pixel thick drawn with a block's texture, like vanilla's moss carpet (option texture)
#   wall_plant  a plant growing sideways out of a wall, drawn with vanilla's coral wall fan model
#   fern        a bushy fern: crossed planes (textures <id>) and four planes leaning out (<id>_fan), wider than a block
#   tall_fern   a two-block fern drawn the same way: <id>_bottom, <id>_top, and <id>_fan_bottom, <id>_fan_top for the
#               leaning planes, which run on from the lower half into the upper; option skirt names the textures of
#               the low fronds round its foot (variants of their own: <skirt>, <skirt>_2 ...)
#   hanging_fern  a fern hanging down the side of a block (textures <id> against the wall, <id>_front swung out from
#               it, <id>_side square to it), turned to face away from the wall, in three sizes (property size: 0, 1
#               and 2 drawn with <id>, <id>_medium and <id>_large and their _front and _side); it has no item, and
#               drops the item named by option drop
#   glow_cube   a full block drawn twice, its texture and over it the texture's glow overlay at full block light (the
#               texture needs an entry in textures.GLOW). Option neon: the block has the property neon (block.Neon),
#               a model per colour with textures <id>_<colour>; its item shows the colour in its BlockStateTag (the
#               item property explorationremix:neon picks the model) and it drops itself in its colour. Option faces
#               "top_side_bottom": textures <id>_<colour>_top, _side and _bottom, each with its glow. Option rock (a
#               name in ROCKS): the block is that vanilla rock, drawn as vanilla draws it, with our texture over it as
#               a cutout overlay (clear where the rock shows) and the overlay's glow over that; the texture
#               <id>_<colour> covers every face, or option overlays maps the face groups it covers (top, side, bottom)
#               to suffixes of their textures, <id>_<colour><suffix>, and leaves the others bare. Option turns: each
#               colour takes one of eight models by the block's position, turned about y by 0, 90, 180 or 270
#               degrees, plain or mirrored (every face's texture flipped), so a floor of them shows no stripes. Option
#               turns "overlay" (with option rock, no neon): the eight turns are the overlay's alone, mirrored on every
#               face and turned on the top and bottom only, while the rock under it stays as vanilla draws it; with
#               option variants (<texture>, <texture>_2 ...) each variant takes the eight, and option texture names the
#               overlay textures' base (default the block's id), so two blocks over different rocks can share them;
#               option render_type its models' (default minecraft:cutout)
#   pointed     vanilla's pointed dripstone in our textures (a PointedDripstoneBlock): a model per thickness (tip_merge,
#               tip, frustum, middle, base) and direction (down hanging, up standing) on vanilla's own model, two
#               crossed planes, with texture <id>_<down|up>_<thickness>; its item is held like vanilla's, with texture
#               item/<id>
# Options: variants (how many textures: <id>, <id>_2, <id>_3; the game picks one per block from its position, so it
# never changes), weights (one per variant), drop (hanging_fern, litter: the item it drops), chance (litter: how often
# it drops it), resting (cross: a GlowbellBlock, drawn with <look>_resting while it rests), glow_under (shelf: the
# whole underside glows), shader_glow (default True; False keeps the glow overlay but leaves the block out of
# glowing_blocks.json and lists it under as_rock there, so under Complementary it is lit like rock even where it gives
# light, and only the overlay shines). A texture with an entry in textures.GLOW gets an emissive overlay.
# The ember crust's options (both of its blocks): its overlay textures ember_crust<variant>_side round the block and
# ember_crust<variant>_top on its top and its underside, three variants.
# Drawn cutout_mipped, so its basalt has the mipmaps vanilla's basalt has and looks the same at a distance, where the
# thin crack fades into it and only the light it gives shows.
EMBER_CRUST = {"texture": "ember_crust", "overlays": {"top": "_top", "side": "_side", "bottom": "_top"}, "variants": 3,
               "turns": "overlay", "shader_glow": False, "render_type": "minecraft:cutout_mipped"}
BLOCKS = {
    "band_stone": ("Band Stone", "cube"),
    "white_glow_flower": ("White Glow Flower", "cross"),
    "lantern_fungus": ("Lantern Fungus", "cross", {"variants": 2}),
    # a vine since 0.14.3, so a hanging column of roots joins up (Taran: "lantern roots texture should connect")
    "lantern_roots": ("Lantern Roots", "vine"),
    "lantern_roots_plant": ("Lantern Roots", "vine_body:lantern_roots", {"variants": 2}),
    "wisteria": ("Wisteria", "vine"),
    "wisteria_plant": ("Wisteria", "vine_body:wisteria", {"variants": 3}),
    "lantern_pod": ("Lantern Pod", "cross"),
    "lavender_flower": ("Lavender Flower", "cross"),
    "gold_flower": ("Gold Flower", "cross"),
    "asurine_crystal": ("Asurine Crystal", "crystal", {"variants": 2}),
    "blue_glow_mushroom": ("Blue Glow Mushroom", "cross", {"variants": 3}),
    "blue_glow_vines": ("Blue Glow Vines", "vine"),
    "blue_glow_vines_plant": ("Blue Glow Vines", "vine_body:blue_glow_vines", {"variants": 2}),
    # the silver fern's item stands it on a floor or hangs it on a wall (silver_wall_fern), like a torch
    "silver_fern": ("Silver Fern", "fern", {"variants": 3}),
    "orange_shelf_fungus": ("Orange Shelf Fungus", "shelf", {"variants": 2}),
    "lavender_coral": ("Lavender Coral", "cross", {"variants": 2}),
    "pale_coral_fan": ("Pale Coral Fan", "coral_fan"),
    "ember_vines": ("Ember Vines", "vine"),
    "ember_vines_plant": ("Ember Vines", "vine_body:ember_vines", {"variants": 2}),
    # ---- vegetation rework, part A: Upper Caves and Blue Reaches
    "weeping_ivy": ("Weeping Ivy", "vine", {"variants": 2}),
    "weeping_ivy_plant": ("Weeping Ivy", "vine_body:weeping_ivy", {"variants": 3}),
    "grotto_ivy": ("Grotto Ivy", "lichen", {"variants": 2}),
    "silver_frond": ("Silver Frond", "vine", {"variants": 2}),
    "silver_frond_plant": ("Silver Frond", "vine_body:silver_frond", {"variants": 2}),
    "tall_silver_fern": ("Tall Silver Fern", "tall_fern", {"variants": 2, "skirt": "silver_fern_fan"}),
    "silver_wall_fern": ("Hanging Silver Fern", "hanging_fern", {"variants": 2, "drop": "silver_fern"}),
    "ivory_bracket": ("Ivory Bracket", "shelf", {"variants": 2, "glow_under": True}),
    "gold_threads": ("Gold Threads", "vine", {"variants": 2}),
    "gold_threads_plant": ("Gold Threads", "vine_body:gold_threads", {"variants": 2}),
    "sage_bush": ("Sage Bush", "cross", {"variants": 2}),
    "glowbell": ("Glowbell", "cross", {"variants": 2, "resting": True}),
    "pagoda_fungus": ("Pagoda Fungus", "tall", {"variants": 2}),
    "blue_lichen": ("Blue Lichen", "lichen", {"variants": 3}),
    # 0.21.7: the streaks down the noise rifts' walls, climbable like vines (Taran's card: "Climbable lichen streaks")
    "thick_blue_lichen": ("Thick Blue Lichen", "lichen", {"variants": 2}),
    "asurine_bud": ("Asurine Bud", "crystal", {"variants": 2}),
    "asurine_crust": ("Asurine Crust", "lichen", {"variants": 4}),
    # ---- vegetation rework, part B: Deep Gardens (no green) and Basalt Galleries
    "pale_wisteria": ("Pale Wisteria", "vine"),
    "pale_wisteria_plant": ("Pale Wisteria", "vine_body:pale_wisteria", {"variants": 2}),
    "wisteria_petals": ("Wisteria Petals", "carpet", {"variants": 3}),
    "lilac_waterweed": ("Lilac Waterweed", "cross", {"variants": 3}),
    "lantern_weed": ("Lantern Weed", "cross", {"variants": 2}),
    "pool_pearls": ("Pool Pearls", "carpet", {"variants": 2}),
    "pale_coral": ("Pale Coral", "cross", {"variants": 2}),
    "ochre_grass": ("Ochre Grass", "cross", {"variants": 3, "weights": [3, 2, 2]}),
    "lilac_plume": ("Lilac Plume", "tall", {"variants": 2}),
    "ember_anemone": ("Ember Anemone", "cross", {"variants": 2}),
    "rose_tendrils": ("Rose Tendrils", "vine"),
    "rose_tendrils_plant": ("Rose Tendrils", "vine_body:rose_tendrils", {"variants": 2}),
    "amber_shard": ("Amber Shard", "crystal", {"variants": 2}),
    "cinder_bloom": ("Cinder Bloom", "cross", {"variants": 2}),
    "ash_fern": ("Ash Fern", "cross", {"variants": 2, "shader_glow": False}),
    "smoulder_lichen": ("Smoulder Lichen", "lichen", {"variants": 2}),
    # Upper Caves rework: the Frost Fern Grotto and the frozen Root Vaults (their ferns, frost and icicles are
    # Immersive Weathering's)
    "frost_moss": ("Frost Moss", "cube"),
    "frost_moss_carpet": ("Frost Moss Carpet", "moss_carpet", {"texture": "frost_moss"}),
    "frost_aster": ("Frost Aster", "cross", {"variants": 2}),
    "snowbell": ("Snowbell", "cross", {"variants": 2}),
    "frostberry_vine": ("Frostberry Vine", "vine", {"variants": 2}),
    "frostberry_vine_plant": ("Frostberry Vine", "vine_body:frostberry_vine", {"variants": 3}),
    "hoarfrost": ("Hoarfrost", "lichen", {"variants": 2}),
    # the sinkholes and the oasis hollows
    "trailing_grass": ("Trailing Grass", "vine", {"variants": 2}),
    "trailing_grass_plant": ("Trailing Grass", "vine_body:trailing_grass", {"variants": 2}),
    "fan_palm": ("Fan Palm", "tall", {"variants": 2}),
    "oasis_grass": ("Oasis Grass", "cross", {"variants": 3, "weights": [3, 2, 2]}),
    "dune_lily": ("Dune Lily", "cross", {"variants": 2}),
    # 0.5: the lifeless Basalt Galleries' glowing rock, and the chains their climbs hang in place of vines (also in the
    # Wasteland's bare refuges). The chain gives no light, and its embers only glint (an overlay kept out of
    # glowing_blocks.json). 0.22.9 (Taran, 2026-11-25: "re-add ember crust to light up the lower Galleries but give it a
    # texture that blends in better with the basalt"): the ember crust is vanilla's basalt with a crack in it that still
    # glows (tools/minerals.py), the smooth ember crust smooth basalt with the same cracks, so each is the stone round it.
    # Three cracks to a face, each turned or mirrored on its own over the rock, which stays as vanilla draws it (option
    # turns "overlay"). They give light, but under Complementary they are lit as rock (shader_glow False: ShaderGlow
    # leaves them out), so only their cracks glow and not the basalt.
    "ember_crust": ("Ember Crust", "glow_cube", dict(EMBER_CRUST, rock="basalt")),
    "smooth_ember_crust": ("Smooth Ember Crust", "glow_cube", dict(EMBER_CRUST, rock="smooth_basalt")),
    # (0.20.1's glow ice, the deep Frost Fern Grotto's glowing veins, left the game in 0.20.7: Taran, "Remove glow ice".)
    "cinder_chain": ("Cinder Chain", "cross", {"shader_glow": False}),
    # (The Galleries' invisible lava glow left the game in 0.22.4, Taran: "remove the invisible light sources".)
    # A Mossback's den (creature.MossbackDen): trampled moss and gnawed bone on a den gate's upper landing. It drops
    # nothing, so nobody can carry one off to farm lizards.
    "mossback_den": ("Mossback Den", "moss_carpet", {"texture": "mossback_den"}),
    # The bones and scraps round a den (DenLairFeature): ribs and vertebrae, a skull fragment, feathers and fur, and the
    # lizards' own shed scales and hide. A bone now and then.
    "bone_scraps": ("Bone Scraps", "litter", {"variants": 4, "drop": "minecraft:bone", "chance": 0.4}),
    # Deep Gardens, the vent field (textures.py's vent field section has Taran's look rules): black dripstone and the
    # giant mushrooms' pale stem; then the glowing blocks, each in the four neon colours (block.Neon), and the seeps'
    # dim light blue crust. Round 3: the vents and spires are vanilla blackstone (vent rock and live vent rock left the
    # game, ERRemaps), so the neon blocks on them are drawn over it (option rock), and the black dripstone has its
    # pointed dripstone, vanilla's recoloured (block.PointedBlackDripstoneBlock).
    "black_dripstone": ("Black Dripstone", "cube"),
    "pointed_black_dripstone": ("Pointed Black Dripstone", "pointed"),
    "pale_stem": ("Pale Stem", "cube"),
    # round 3: the glow mat is a film over vanilla deepslate (tools/glow_mat_looks.py), the thin glow mat the sparser
    # film of a patch's outer ring; the glow cap has a top, a rim and an underside (tools/glow_cap_looks.py)
    "glow_mat": ("Glow Mat", "glow_cube", {"neon": True, "rock": "deepslate", "overlays": {"top": "_top", "side": "_side"},
                                          "turns": True}),
    "thin_glow_mat": ("Thin Glow Mat", "glow_cube", {"neon": True, "rock": "deepslate",
                                                    "overlays": {"top": "_top", "side": "_side"}, "turns": True}),
    "glow_cap": ("Glow Cap", "glow_cube", {"neon": True, "faces": "top_side_bottom"}),
    "vent_crown": ("Vent Crown", "glow_cube", {"neon": True, "rock": "blackstone"}),
    # 0.19.4's spire tip knobs on every face, over blackstone (round 4, Taran: "The vent bead texture was better
    # before"; card pick "Old knobs over blackstone"). It caps the small spires, mixes with the crown over a live vent's
    # top and runs down its walls, outside and in. (Round 4 also retired den fissure, vent glow and throat vein: beads
    # took their places.)
    "vent_beads": ("Vent Beads", "glow_cube", {"neon": True, "rock": "blackstone"}),
    "seep_crust": ("Seep Crust", "glow_cube"),
    # 0.20.2: the vent field's plant life (tools/plants_vent.py), grown by its dressing (GardensDress.plants). The grass
    # comes in three heights and the mat's colour (option neon: a model list per colour, textures <id>_<colour>), drops
    # only to shears (option shears), and a stilt mantis crops it to stubble that regrows (no item, no drop). The tube
    # worms and the small mushrooms are 3D models (kinds tube_worms and mushrooms); the glowworm threads and the seep
    # reeds are light blue only. None gives light: only their glow overlays shine.
    "filament_tuft": ("Filament Tuft", "cross", {"neon": True, "variants": 2, "shears": True}),
    "filament_grass": ("Filament Grass", "cross", {"neon": True, "variants": 2, "shears": True}),
    "tall_filament_grass": ("Tall Filament Grass", "tall", {"neon": True, "variants": 2, "shears": True}),
    "grazed_filament_grass": ("Grazed Filament Grass", "cross", {"neon": True, "variants": 2, "no_item": True}),
    "tube_worms": ("Tube Worms", "tube_worms", {"neon": True}),
    # 0.20.6: tube worms on a wall (the vents' steep lower walls), a block of their own so they keep to their wall
    # (the floor worms are set off a little by position); no item: they drop the tube worms and pick as them
    "wall_tube_worms": ("Tube Worms", "wall_tube_worms", {"neon": True, "no_item": True}),
    "glowworm_threads": ("Glowworm Threads", "vine", {"variants": 2}),
    "glowworm_threads_plant": ("Glowworm Threads", "vine_body:glowworm_threads", {"variants": 2}),
    "seep_reeds": ("Seep Reeds", "cross", {"variants": 2}),
    "tall_seep_reeds": ("Tall Seep Reeds", "tall", {"variants": 2}),
    # 0.21.7, the Blue Reaches polish: reeds by the rifts' water to hide in (Taran's card pick: "Silver cattails"),
    # drawn as glow sedge since 0.22.8 (Taran's later pick: "Glow Sedge"); the id stays, so worlds keep their reeds
    "silver_cattails": ("Glow Sedge", "cattails", {"variants": 3}),
    "neon_mushrooms": ("Neon Mushrooms", "mushrooms", {"neon": True}),
}
NO_DROP = {"band_stone", "mossback_den", "grazed_filament_grass"}
# The vent field's blocks: rock, never growth to /remix census (the whole blocks count as floor and ceiling).
VENT_FIELD_BLOCKS = {"black_dripstone", "pointed_black_dripstone", "pale_stem", "glow_mat", "thin_glow_mat", "glow_cap", "vent_crown",
                     "vent_beads", "seep_crust"}
# Blocks left out of explorationremix:plants: rock-like ground, which /remix census counts as floor, not growth, the
# lifeless blocks of the Galleries, the litter round the dens and the vent field's whole blocks.
NOT_PLANTS = NO_DROP | {"frost_moss", "ember_crust", "smooth_ember_crust", "cinder_chain", "bone_scraps"} | VENT_FIELD_BLOCKS

# Blocks in vanilla tags: vines climb like weeping vines, and lantern pods can hang from wisteria.
BLOCK_TAGS = {
    ("minecraft", "climbable"): ["wisteria", "wisteria_plant", "blue_glow_vines", "blue_glow_vines_plant", "ember_vines", "ember_vines_plant",
                                 # vegetation rework, part A: the new vines, and the ivy on the walls
                                 "weeping_ivy", "weeping_ivy_plant", "silver_frond",
                                 "silver_frond_plant", "gold_threads", "gold_threads_plant", "grotto_ivy",
                                 # vegetation rework, part B
                                 "pale_wisteria", "pale_wisteria_plant", "rose_tendrils", "rose_tendrils_plant",
                                 # Upper Caves rework
                                 "frostberry_vine", "frostberry_vine_plant", "trailing_grass", "trailing_grass_plant",
                                 # 0.5: the Galleries' climbs
                                 "cinder_chain",
                                 # 0.21.7: the lichen streaks down the noise rifts' walls
                                 "thick_blue_lichen"],
    # Frost moss is ground, as vanilla's moss is: ferns, flowers and saplings grow on it (Immersive Weathering's frosty
    # ferns only grow on #minecraft:dirt), and moss patches may spread over it.
    ("minecraft", "dirt"): ["frost_moss"],
    ("minecraft", "mineable/hoe"): ["frost_moss", "frost_moss_carpet"],
    ("minecraft", "mineable/pickaxe"): ["asurine_crystal", "asurine_bud", "asurine_crust", "amber_shard", "ember_crust", "smooth_ember_crust",
                                        "cinder_chain",
                                        # the Deep Gardens: the vent field's dripstone, and the neon blocks grown on its rock
                                        "black_dripstone", "pointed_black_dripstone", "glow_mat", "thin_glow_mat", "vent_crown",
                                        "vent_beads", "seep_crust",
                                        # 0.23.1: the rune stones (any tool breaks them, a pickaxe best, as their stone)
                                        *rune_stones.BLOCK_IDS],
    ("minecraft", "mineable/axe"): ["orange_shelf_fungus", "ivory_bracket", "grotto_ivy", "blue_lichen", "thick_blue_lichen", "smoulder_lichen",
                                    # Deep Gardens round 2: the giant mushrooms
                                    "pale_stem", "glow_cap"],
    # A cinder chain hangs from the chain above it, so a climb's whole column holds.
    (MOD, "hanging_supports"): ["wisteria", "wisteria_plant", "lantern_roots", "lantern_roots_plant", "pale_wisteria", "pale_wisteria_plant", "cinder_chain"],
}
# Vanilla growth that /remix census counts as plants, with every block above but those in NOT_PLANTS (tag
# explorationremix:plants).
VANILLA_PLANTS = ["minecraft:cave_vines", "minecraft:cave_vines_plant", "minecraft:glow_lichen", "minecraft:moss_carpet", "minecraft:fern",
                  "minecraft:grass", "minecraft:large_fern", "minecraft:hanging_roots", "minecraft:azalea", "minecraft:flowering_azalea",
                  "minecraft:brown_mushroom", "minecraft:sea_pickle", "minecraft:seagrass", "minecraft:tall_seagrass", "minecraft:spore_blossom",
                  # Immersive Weathering's, which the cold caves grow since 0.4.1
                  *({"id": f"immersive_weathering:{b}", "required": False} for b in ("frosty_fern", "frosty_grass", "icicle", "frost"))]

# A crystal points along its facing; the model points up. These are vanilla amethyst's turns.
FACE6 = {"down": {"x": 180}, "east": {"x": 90, "y": 90}, "north": {"x": 90}, "south": {"x": 90, "y": 180}, "up": {}, "west": {"x": 90, "y": 270}}
# The shelf and wall plant models stick out north from a wall to their south.
FACE4 = {"north": {}, "east": {"y": 90}, "south": {"y": 180}, "west": {"y": 270}}
# A sheet's model lies against the block to its north; vanilla glow lichen's turns for the other five faces.
LICHEN_TURNS = {"north": {}, "east": {"y": 90, "uvlock": True}, "south": {"y": 180, "uvlock": True}, "west": {"y": 270, "uvlock": True},
                "up": {"x": 270, "uvlock": True}, "down": {"x": 90, "uvlock": True}}
FACES = ["down", "east", "north", "south", "up", "west"]

# Model templates (models/block/template/<name>.json). The *_glow ones draw each plane a second time with the overlay
# texture #glow at full block light (Forge's forge_data), so the overlay's pixels glow in the dark. The light is baked
# into the quads, which vanilla's renderer and Embeddium both merge with the world's light.
EMISSIVE = {"forge_data": {"block_light": 15}}


def _cross_planes(key, extra):
    """Vanilla's cross: two planes turned 45 degrees."""
    turn = {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": True}
    return [dict({"from": [0.8, 0, 8], "to": [15.2, 16, 8], "rotation": turn, "shade": False,
                  "faces": {"north": {"uv": [0, 0, 16, 16], "texture": key}, "south": {"uv": [0, 0, 16, 16], "texture": key}}}, **extra),
            dict({"from": [8, 0, 0.8], "to": [8, 16, 15.2], "rotation": turn, "shade": False,
                  "faces": {"west": {"uv": [0, 0, 16, 16], "texture": key}, "east": {"uv": [0, 0, 16, 16], "texture": key}}}, **extra)]


def _flat(key, extra):
    """A plane just above the floor."""
    return [dict({"from": [0, 0.25, 0], "to": [16, 0.25, 16],
                  "faces": {"up": {"uv": [0, 0, 16, 16], "texture": key}, "down": {"uv": [0, 16, 16, 0], "texture": key}}}, **extra)]


def _sheet(key, extra):
    """Vanilla glow lichen's plane, just off the face of the block to the north."""
    return [dict({"from": [0, 0, 0.1], "to": [16, 16, 0.1],
                  "faces": {"north": {"uv": [16, 0, 0, 16], "texture": key}, "south": {"uv": [0, 0, 16, 16], "texture": key}}}, **extra)]


def _cube(keys, extra):
    """A whole block's cube, like vanilla's block/cube: keys maps each face drawn to its texture, each face culled
    against the block beside it."""
    return [dict({"from": [0, 0, 0], "to": [16, 16, 16],
                  "faces": {f: {"texture": k, "cullface": f} for f, k in keys.items()}}, **extra)]


# A glowing whole block's overlay: unshaded, so its glow is as bright on every face, at full block light.
CUBE_GLOW = dict(EMISSIVE, shade=False)
SIDES = ("north", "east", "south", "west")

# The vanilla rocks a glowing block can be drawn over (kind glow_cube, option rock): the texture of the rock's top and
# bottom, then of its sides, as vanilla's own model draws them (cube_column's end and side). These are the only vanilla
# textures our models name; the check in block_assets refuses any other, as the game would show a typo as a purple and
# black square.
ROCKS = {"blackstone": ("minecraft:block/blackstone_top", "minecraft:block/blackstone"),
         "deepslate": ("minecraft:block/deepslate_top", "minecraft:block/deepslate"),
         # 0.22.9: the Galleries' two stones, under the ember crust's cracks
         "basalt": ("minecraft:block/basalt_top", "minecraft:block/basalt_side"),
         "smooth_basalt": ("minecraft:block/smooth_basalt", "minecraft:block/smooth_basalt")}
VANILLA_TEXTURES = {t for pair in ROCKS.values() for t in pair}
# An overlay's face groups, and the faces of the cube each covers.
OVERLAY_FACES = {"top": ("up",), "side": SIDES, "bottom": ("down",)}


def overlays(opts):
    """A glow_cube over a rock: its face groups with an overlay and their textures' suffixes (option overlays; by
    default one texture on every face)."""
    return opts.get("overlays", {g: "" for g in OVERLAY_FACES})


def rock_template(groups):
    """The name of the rock template whose overlay covers these face groups: rock_top_side_bottom_glow and so on."""
    return "rock_" + "_".join(g for g in OVERLAY_FACES if g in groups) + "_glow"


def _rock_overlay(groups):
    """A vanilla rock's block with our overlay over the faces of some face groups: the rock as vanilla's cube_column
    draws it (#rock_end on its top and bottom, #rock_side round it), then on each group's faces (OVERLAY_FACES) our
    cutout overlay #<group>, shaded and lit by the world like the rock, and over that the overlay's glow #<group>_glow,
    unshaded at full block light. The three lie on the same planes and are drawn in order, so each covers the one
    before wherever it has pixels."""
    rock = _cube(dict({"down": "#rock_end", "up": "#rock_end"}, **{f: "#rock_side" for f in SIDES}), {})
    over = _cube({f: "#" + g for g in OVERLAY_FACES if g in groups for f in OVERLAY_FACES[g]}, {})
    glow = _cube({f: f"#{g}_glow" for g in OVERLAY_FACES if g in groups for f in OVERLAY_FACES[g]}, CUBE_GLOW)
    return {"parent": "minecraft:block/block", "textures": {"particle": "#rock_side"}, "elements": rock + over + glow}


# Vanilla's pointed dripstone item (kind pointed): a flat sprite, held turned side on.
POINTED_ITEM_DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, 100, 0], "translation": [-1, -1, 0], "scale": [0.9, 0.9, 0.9]},
    "firstperson_righthand": {"rotation": [0, 100, 0], "translation": [0, -2, 0], "scale": [0.9, 0.9, 0.9]},
}


# The silver ferns (Taran, 1 Nov: fluffier, like the picture, and hanging on walls). Model planes can only turn
# 22.5 or 45 degrees about one axis, so fronds that lean out are planes hinged at their foot.
FERN_LEAN = 45         # the small fern's outer planes lean this far out: a low, wide clump (about 1.4 blocks across)
FERN_INNER_LEAN = 22.5  # and its inner ring this far, filling the clump between the outer planes and the upright middle
TALL_FERN_LEAN = 22.5   # the tall fern's: a vase of fronds two blocks high
TALL_FERN_SKIRT = 45    # with a skirt of low fronds round its foot, leaning this far
HANGING_FERN_TOP = 14  # the hanging fern leaves the wall this high in its block; its sizes are plants_fern.HANGING_SIZES
                       # (as HangingFernBlock's WIDTH and LENGTH), the fronds falling into the blocks below (kept clear)
HANGING_FERN_LIFT = 18  # the bigger sizes are drawn this much higher and moved back down (see _hanging_fern)


def _plane(frm, to, key, rotation=None, uv=(0, 0, 16, 16), faces=None):
    """A flat, unshaded, two-sided plane: square to z when frm and to share z, else square to x."""
    faces = faces or (("north", "south") if frm[2] == to[2] else ("west", "east"))
    el = {"from": list(frm), "to": list(to), "shade": False,
          "faces": {f: {"uv": list(uv), "texture": key} for f in faces}}
    if rotation:
        el["rotation"] = rotation
    return el


def _fern():
    """The small fern: the crossed planes of an upright clump (#fern), four planes hinged at the middle of the block
    leaning far out north and south (#fan_a) and east and west (#fan_b), and four between them and the middle, each
    with the other pair's fronds, so the clump is full all round."""
    els = _cross_planes("#fern", {})
    for lean, ns, ew in ((FERN_LEAN, "#fan_a", "#fan_b"), (FERN_INNER_LEAN, "#fan_b", "#fan_a")):
        for a in (lean, -lean):
            els.append(_plane((0, 0, 8), (16, 16, 8), ns, {"origin": [8, 0, 8], "axis": "x", "angle": a}))
            els.append(_plane((8, 0, 0), (8, 16, 16), ew, {"origin": [8, 0, 8], "axis": "z", "angle": a}))
    return {"ambientocclusion": False, "textures": {"particle": "#fern"}, "elements": els}


def _tall_fern(half):
    """A half of the tall fern: crossed planes (#fern), and four planes leaning out (#fan); the upper half's four start
    where the lower half's end, so each frond runs on through both. The lower half also has a skirt of four planes
    leaning far out (#skirt, a small fern's fronds) round its foot."""
    els = _cross_planes("#fern", {})
    out = round(16 * math.sin(math.radians(TALL_FERN_LEAN)), 3)
    y0 = 0 if half == "lower" else round(16 * math.cos(math.radians(TALL_FERN_LEAN)) - 16, 3)
    for sign in (1, -1):
        z = 8 if half == "lower" else 8 + sign * out
        els.append(_plane((0, y0, z), (16, y0 + 16, z), "#fan", {"origin": [8, y0, z], "axis": "x", "angle": sign * TALL_FERN_LEAN}))
        x = 8 if half == "lower" else 8 - sign * out
        els.append(_plane((x, y0, 0), (x, y0 + 16, 16), "#fan", {"origin": [x, y0, 8], "axis": "z", "angle": sign * TALL_FERN_LEAN}))
    if half == "lower":
        for a in (TALL_FERN_SKIRT, -TALL_FERN_SKIRT):
            els.append(_plane((0, 0, 8), (16, 16, 8), "#skirt", {"origin": [8, 0, 8], "axis": "x", "angle": a}))
            els.append(_plane((8, 0, 0), (8, 16, 16), "#skirt", {"origin": [8, 0, 8], "axis": "z", "angle": a}))
    return {"ambientocclusion": False, "textures": {"particle": "#fern"}, "elements": els}


def _hanging_fern(size):
    """The hanging fern facing north, on a wall to its south (z = 16), at one of its sizes: fronds falling `length`
    pixels from HANGING_FERN_TOP across `width` centred on the block, one plane against the rock (#hang), one swung out
    22.5 degrees from the top (#front), and two side planes square to the wall (#side, the rock on the texture's right
    edge) reaching `out` pixels out. The planes show the middle `width` columns and the top `length` rows of their
    textures. A model's elements must lie between -16 and 32, so the bigger sizes are drawn HANGING_FERN_LIFT higher
    and moved back down by the model's root transform (Forge's "transform")."""
    z = HANGING_SIZES[size]
    tw, th = z["tex"]
    w, length, out = z["width"], z["length"], z["out"]
    lift = 0 if HANGING_FERN_TOP - length >= -16 else HANGING_FERN_LIFT
    top = HANGING_FERN_TOP + lift
    low = top - length
    x0, x1 = 8 - w / 2, 8 + w / 2
    u0, u1 = (tw - w) / 2 * 16 / tw, (tw + w) / 2 * 16 / tw
    v = round(length * 16 / th, 4)
    us = (tw - out) * 16 / tw  # the side planes' textures: the right `out` columns
    side_x = (8 - w / 4, 8 + w / 4)
    els = [
        _plane((x0, low, 15.6), (x1, top, 15.6), "#hang", uv=(u0, 0, u1, v)),
        _plane((x0, low, 15.2), (x1, top, 15.2), "#front", {"origin": [8, top, 15.2], "axis": "x", "angle": 22.5},
               uv=(u0, 0, u1, v)),
    ]
    for x in side_x:
        els.append(_plane((x, low, 16 - out), (x, top, 16), "#side", uv=(us, 0, 16, v), faces=("west",)))
        els.append(_plane((x, low, 16 - out), (x, top, 16), "#side", uv=(16, 0, us, v), faces=("east",)))
    for el in els:
        assert all(-16 <= c <= 32 for c in el["from"] + el["to"]), (size, el)
    model = {"ambientocclusion": False, "textures": {"particle": "#hang"}, "elements": els}
    if lift:
        model["transform"] = {"translation": [0, -lift / 16, 0]}
    return model


def _worm_plume(cx, top, cz, plume):
    """A tube worm's plume (plants_vent.PLUMES[plume]) on a tube whose mouth is at (cx, top, cz): two crossed planes
    six pixels across, their foot a pixel down in the tube, drawn again with #glow at full block light. #plume is a
    texture of its own (0.20.6), so near the player the game knows these faces and draws them moving instead
    (client.render.TubeWormsRenderer)."""
    u0, v0, u1, v1 = plants_vent.PLUMES[plume]
    turn = {"origin": [cx, top, cz], "axis": "y", "angle": 45}
    els = []
    for key, extra in (("#plume", {}), ("#glow", EMISSIVE)):
        els.append(dict({"from": [cx - 3, top - 1, cz], "to": [cx + 3, top + 5, cz], "rotation": turn, "shade": False,
                         "faces": {f: {"uv": [u0, v0, u1, v1], "texture": key} for f in ("north", "south")}}, **extra))
        els.append(dict({"from": [cx, top - 1, cz - 3], "to": [cx, top + 5, cz + 3], "rotation": turn, "shade": False,
                         "faces": {f: {"uv": [u0, v0, u1, v1], "texture": key} for f in ("west", "east")}}, **extra))
    return els


def _tube_worms(look, out):
    """A cluster of tube worms on a floor (a look of plants_vent.FLOOR_LOOKS): each tube a box two pixels square, its
    sides a ringed strip of #worms (row 0 at its mouth), its top the open mouth; while the worms are out, a plume on
    each tube that has one."""
    els = []
    for (x, z, h, strip, plume) in look:
        a, b = plants_vent.TUBE_STRIPS[strip]
        side = {"uv": [a, 0, b, h], "texture": "#worms"}
        els.append({"from": [x, 0, z], "to": [x + 2, h, z + 2],
                    "faces": dict({f: dict(side) for f in SIDES}, up={"uv": list(plants_vent.MOUTH), "texture": "#worms"})})
        if out and plume is not None:
            els += _worm_plume(x + 1, h, z + 1, plume)
    return {"parent": "minecraft:block/block", "ambientocclusion": False, "textures": {"particle": "#worms"}, "elements": els}


def _wall_tube_worms(look, out):
    """A cluster of tube worms on a wall (0.20.6, card pick "Lower half, on ledges and walls"; a look of
    plants_vent.WALL_LOOKS), the rock on the south side: each tube rises from its closed foot, a box two pixels square
    standing out from the rock, joined to the rock by a stub along its lowest two pixels (none when it lies against the
    rock); while the worms are out, a plume on each tube that has one. The blockstate turns the model to face away
    from its wall."""
    els = []
    lo, hi = plants_vent.STUB_ROWS
    for (x, foot, out_px, h, strip, plume) in look:
        a, b = plants_vent.TUBE_STRIPS[strip]
        zt = 14 - out_px
        side = {"uv": [a, 0, b, h], "texture": "#worms"}
        faces = {f: dict(side) for f in SIDES if not (f == "south" and out_px == 0)}
        faces["up"] = {"uv": list(plants_vent.MOUTH), "texture": "#worms"}
        faces["down"] = {"uv": list(plants_vent.FOOT), "texture": "#worms"}
        els.append({"from": [x, foot, zt], "to": [x + 2, foot + h, zt + 2], "faces": faces})
        if out_px:
            run = {"uv": [a, lo, b, min(hi, lo + out_px)], "texture": "#worms"}
            els.append({"from": [x, foot, zt + 2], "to": [x + 2, foot + 2, 16],
                        "faces": {"up": dict(run), "down": dict(run), "east": dict(run, rotation=90), "west": dict(run, rotation=90)}})
        if out and plume is not None:
            els += _worm_plume(x + 1, foot + h, zt + 1, plume)
    return {"parent": "minecraft:block/block", "ambientocclusion": False, "textures": {"particle": "#worms"}, "elements": els}


def worm_looks(wall):
    """The tube worms' looks by density, each a name and its tubes: plants_vent's floor looks, or its wall looks each
    twice, as drawn and turned over left to right."""
    out = {}
    for density in ("dense", "sparse"):
        if wall:
            ls = []
            for k, look in enumerate(plants_vent.WALL_LOOKS[density]):
                ls.append((f"{k}", look))
                ls.append((f"{k}_m", plants_vent.mirrored_wall_look(look)))
        else:
            ls = [(f"{k}", look) for k, look in enumerate(plants_vent.FLOOR_LOOKS[density])]
        out[density] = ls
    return out


def _mushrooms(count):
    """Small mushrooms (plants_vent.MUSHROOM_LOOKS[count]): each a pale stem under a cap two pixels thick with a dome a
    pixel high over it (caps 4 and 5), mapped from #mushrooms, the cap and dome drawn again with #glow at full block
    light; a leaning one is turned 22.5 degrees about its foot."""
    pv = plants_vent
    els = []
    for (x, z, h, w, cap, lean) in pv.MUSHROOM_LOOKS[count]:
        a, _, _, _ = pv.MUSH_STEM
        parts = [{"from": [x - w / 2, 0, z - w / 2], "to": [x + w / 2, h, z + w / 2],
                  "faces": {f: {"uv": [a, 0, a + w, h], "texture": "#mushrooms"} for f in SIDES}}]
        lit = []
        for key, extra in (("#mushrooms", {}), ("#glow", dict(EMISSIVE, shade=False))):
            box = {"from": [x - cap / 2, h, z - cap / 2], "to": [x + cap / 2, h + 2, z + cap / 2],
                   "faces": dict({f: {"uv": list(pv.CAP_SIDE[cap]), "texture": key} for f in SIDES},
                                 up={"uv": list(pv.CAP_TOP[cap]), "texture": key},
                                 down={"uv": list(pv.CAP_UNDER[cap]), "texture": key})}
            lit.append(dict(box, **extra))
            if cap >= 4:
                d = cap - 2
                u0, v0, _, _ = pv.CAP_TOP[cap]
                su0, sv0, _, _ = pv.CAP_SIDE[cap]
                dome = {"from": [x - d / 2, h + 2, z - d / 2], "to": [x + d / 2, h + 3, z + d / 2],
                        "faces": dict({f: {"uv": [su0 + 1, sv0, su0 + 1 + d, sv0 + 1], "texture": key} for f in SIDES},
                                      up={"uv": [u0 + 1, v0 + 1, u0 + 1 + d, v0 + 1 + d], "texture": key})}
                lit.append(dict(dome, **extra))
        for el in parts + lit:
            if lean:
                el["rotation"] = {"origin": [x, 0, z], "axis": lean[0], "angle": lean[1]}
            els.append(el)
    return {"parent": "minecraft:block/block", "ambientocclusion": False, "textures": {"particle": "#mushrooms"}, "elements": els}


TEMPLATES = {
    "fern": _fern(),
    "tall_fern_lower": _tall_fern("lower"),
    "tall_fern_upper": _tall_fern("upper"),
    "hanging_fern": _hanging_fern(""),
    "hanging_fern_medium": _hanging_fern("_medium"),
    "hanging_fern_large": _hanging_fern("_large"),
    "cross_glow": {"ambientocclusion": False, "textures": {"particle": "#cross"},
                   "elements": _cross_planes("#cross", {}) + _cross_planes("#glow", EMISSIVE)},
    "carpet": {"ambientocclusion": False, "textures": {"particle": "#texture"}, "elements": _flat("#texture", {})},
    # 0.20.2: the tube worms, out (plumes up) and in, and the small mushrooms by count; 0.20.6: the worms dense (a
    # clump's middle) and sparse (its edge), on floors and on walls
    **{f"tube_worms_{d}_{k}{o}": _tube_worms(look, o == "") for d, ls in worm_looks(False).items() for k, look in ls for o in ("", "_in")},
    **{f"wall_tube_worms_{d}_{k}{o}": _wall_tube_worms(look, o == "") for d, ls in worm_looks(True).items() for k, look in ls
       for o in ("", "_in")},
    **{f"neon_mushrooms_{n}": _mushrooms(n) for n in plants_vent.MUSHROOM_LOOKS},
    "carpet_glow": {"ambientocclusion": False, "textures": {"particle": "#texture"}, "elements": _flat("#texture", {}) + _flat("#glow", EMISSIVE)},
    "lichen": {"ambientocclusion": False, "textures": {"particle": "#texture"}, "elements": _sheet("#texture", {})},
    "lichen_glow": {"ambientocclusion": False, "textures": {"particle": "#texture"}, "elements": _sheet("#texture", {}) + _sheet("#glow", EMISSIVE)},
    # the vent field's glowing whole blocks (kind glow_cube): the block, then its overlay over the same faces
    "cube_glow": {"parent": "minecraft:block/block", "textures": {"particle": "#all"},
                  "elements": _cube({f: "#all" for f in FACES}, {}) + _cube({f: "#glow" for f in FACES}, CUBE_GLOW)},
    # and the glow cap's: a top, a side and a bottom, each with its overlay
    "cube_top_side_bottom_glow": {"parent": "minecraft:block/block", "textures": {"particle": "#side"},
                                  "elements": _cube(dict({"down": "#bottom", "up": "#top"}, **{f: "#side" for f in SIDES}), {})
                                  + _cube(dict({"down": "#bottom_glow", "up": "#top_glow"}, **{f: "#side_glow" for f in SIDES}),
                                          CUBE_GLOW)},
}
# and the blocks drawn over a vanilla rock (option rock): a template for each set of face groups their overlays cover
for _entry in BLOCKS.values():
    if len(_entry) > 2 and "rock" in _entry[2]:
        TEMPLATES[rock_template(overlays(_entry[2]))] = _rock_overlay(overlays(_entry[2]))


def mirrored(template):
    """A template with every face's texture flipped left to right (option turns' mirrored models)."""
    out = json.loads(json.dumps(template))
    for el in out["elements"]:
        for face in el["faces"].values():
            u1, v1, u2, v2 = face.get("uv", [0, 0, 16, 16])
            face["uv"] = [u2, v1, u1, v2]
    return out


def overlay_turned(template, mirror, turn):
    """A rock template (option turns "overlay") with its overlay and glow, every element but the first (the rock),
    mirrored on every face and turned on its top and bottom faces; the rock stays as vanilla draws it."""
    out = json.loads(json.dumps(template))
    for el in out["elements"][1:]:
        for f, face in el["faces"].items():
            if mirror:
                u1, v1, u2, v2 = face.get("uv", [0, 0, 16, 16])
                face["uv"] = [u2, v1, u1, v2]
            if turn and f in ("up", "down"):
                face["rotation"] = turn
    return out


def turn_suffix(mirror, turn):
    return ("_mirrored" if mirror else "") + (f"_r{turn}" if turn else "")


TURNS = [(mirror, y) for mirror in (False, True) for y in (0, 90, 180, 270)]  # option turns' eight models
for _entry in BLOCKS.values():
    if len(_entry) > 2 and _entry[2].get("turns") is True:
        _name = rock_template(overlays(_entry[2])) if "rock" in _entry[2] else "cube_glow"
        TEMPLATES[_name + "_mirrored"] = mirrored(TEMPLATES[_name])
    elif len(_entry) > 2 and _entry[2].get("turns") == "overlay":
        _name = rock_template(overlays(_entry[2]))
        for _mirror, _turn in TURNS[1:]:
            TEMPLATES[_name + turn_suffix(_mirror, _turn)] = overlay_turned(TEMPLATES[_name], _mirror, _turn)


def shelf_model(tex, under, glow_under=False):
    m = {
        "parent": "minecraft:block/block",
        "render_type": "minecraft:cutout",
        "textures": {"particle": tex, "top": tex, "under": under},
        "elements": [{
            "from": [1, 5, 6], "to": [15, 9, 16],
            "faces": {
                # top: texture rows 0-9, the pale rim at the open edge; under: rows 0-9, gills fanning from the wall
                "up": {"uv": [1, 0, 15, 10], "texture": "#top"},
                "down": {"uv": [1, 0, 15, 10], "texture": "#under"},
                # the edge strip is rows 12-15 of the top texture
                "north": {"uv": [1, 12, 15, 16], "texture": "#top"},
                "east": {"uv": [0, 12, 10, 16], "texture": "#top"},
                "west": {"uv": [0, 12, 10, 16], "texture": "#top"},
            },
        }],
    }
    if glow_under:
        m["elements"][0]["faces"]["down"].update(EMISSIVE)
    return m


def ref(tex):
    return f"{MOD}:block/{tex}"


def variant_names(base, n):
    """base, base_2, base_3 ...: the texture and model names of a block's variants."""
    return [base] + [f"{base}_{i}" for i in range(2, n + 1)]


def pick(entries, weights=None):
    """A blockstate value: one model, or a list the game picks from by the block's position (weights optional)."""
    if weights:
        assert len(weights) == len(entries), entries
        entries = [dict(e, weight=w) for e, w in zip(entries, weights)]
    return entries[0] if len(entries) == 1 else entries


# The parts of a cattail plant (kind cattails), as block.SilverCattailsBlock.Part names them.
CATTAIL_PARTS = ("short_bottom", "short_top", "bottom", "middle", "top")


def plane_model(tex, plain_parent, template, key):
    """A cross, carpet or lichen model, drawn with the glow overlay when its texture has one."""
    t = {key: ref(tex)}
    if f"block/{tex}" in textures.GLOW:
        t["glow"] = ref(tex + "_glow")
        return {"parent": f"{MOD}:block/template/{template}_glow", "render_type": "minecraft:cutout", "textures": t}
    return {"parent": plain_parent, "render_type": "minecraft:cutout", "textures": t}


def neon_items(bid, colours, model_of):
    """A neon plant's items (0.20.2): an item model per colour (item/<id>_<colour>, from model_of), and the item's own
    model, the first colour's with overrides on explorationremix:neon picking the others (as kind glow_cube's do)."""
    extra = {f"{bid}_{c}": model_of(c) for c in colours}
    last = len(colours) - 1
    item = dict(model_of(colours[0]), overrides=[{"predicate": {f"{MOD}:neon": round((k - 0.5) / last, 4)}, "model": f"{MOD}:item/{bid}_{c}"}
                                                 for k, c in enumerate(colours) if k])
    return item, extra


SHEARS = {"condition": "minecraft:match_tool", "predicate": {"items": ["minecraft:shears"]}}


def loot(bid, drop, kind, chance=None, neon=False, shears=False, silk=False):
    entry = {"type": "minecraft:item", "name": drop if ":" in drop else f"{MOD}:{drop}"}
    if neon:
        # the item keeps the block's colour (its BlockStateTag), and places the block in it again
        entry["functions"] = [{"function": "minecraft:copy_state", "block": f"{MOD}:{bid}", "properties": ["neon"]}]
    if shears:
        # like vanilla's grass, only to shears
        entry.setdefault("conditions", []).append(dict(SHEARS))
    if silk:
        # only to a silk touch tool, like vanilla's packed ice
        entry.setdefault("conditions", []).append({"condition": "minecraft:match_tool", "predicate": {
            "enchantments": [{"enchantment": "minecraft:silk_touch", "levels": {"min": 1}}]}})
    if kind == "mushrooms":
        # one for each mushroom, like vanilla's sea pickles
        entry.setdefault("functions", []).extend(
            {"function": "minecraft:set_count", "count": n, "add": False,
             "conditions": [{"condition": "minecraft:block_state_property", "block": f"{MOD}:{bid}", "properties": {"mushrooms": str(n)}}]}
            for n in (2, 3))
    if kind in ("tall", "tall_fern"):
        # both halves break together and the plant drops once, from whichever half was broken, as vanilla's tall grass
        # does (0.20.2; so the breaking tool counts for either half): a half drops it while the other half still
        # stands by it
        pools = []
        for half, other, dy in (("lower", "upper", 1), ("upper", "lower", -1)):
            pools.append({"rolls": 1, "bonus_rolls": 0, "entries": [entry], "conditions": [
                {"condition": "minecraft:block_state_property", "block": f"{MOD}:{bid}", "properties": {"half": half}},
                {"condition": "minecraft:location_check", "offsetY": dy,
                 "predicate": {"block": {"blocks": [f"{MOD}:{bid}"], "state": {"half": other}}}},
                {"condition": "minecraft:survives_explosion"}]})
        return {"type": "minecraft:block", "pools": pools}
    elif kind == "cattails":
        # one plant, one drop, from its foot: breaking any other block of it breaks the foot (SilverCattailsBlock)
        entry.setdefault("conditions", []).append({"condition": "minecraft:any_of", "terms": [
            {"condition": "minecraft:block_state_property", "block": f"{MOD}:{bid}", "properties": {"part": p}}
            for p in ("short_bottom", "bottom")]})
    elif kind == "lichen":
        # one for each face it covers, like vanilla's glow lichen (which needs shears; these don't)
        entry["functions"] = [{"function": "minecraft:set_count", "add": True, "count": 1,
                               "conditions": [{"condition": "minecraft:block_state_property", "block": f"{MOD}:{bid}", "properties": {f: "true"}}]}
                              for f in FACES] + [{"function": "minecraft:set_count", "add": True, "count": -1}]
    if chance is not None:
        # only now and then
        entry.setdefault("conditions", []).append({"condition": "minecraft:random_chance", "chance": chance})
    return {"type": "minecraft:block",
            "pools": [{"rolls": 1, "bonus_rolls": 0, "entries": [entry], "conditions": [{"condition": "minecraft:survives_explosion"}]}]}


def block_assets():
    lang = {}
    used_glow = set()
    glowing = set()  # blocks drawn with a glow overlay (assets/<mod>/glowing_blocks.json, read by client/ShaderGlow)
    as_rock = set()  # blocks the shader lights as rock even where they give light (option shader_glow False)
    for name, model in TEMPLATES.items():
        write(os.path.join(ASSETS, "models", "block", "template", name + ".json"), model)
    for bid, entry in BLOCKS.items():
        name, kind = entry[0], entry[1]
        opts = entry[2] if len(entry) > 2 else {}
        texs = variant_names(bid, opts.get("variants", 1))
        weights = opts.get("weights")
        lang[f"block.{MOD}.{bid}"] = name
        models = {}
        item = {"parent": "minecraft:item/generated", "textures": {"layer0": ref(bid)}}
        extra_items = {}
        drop = bid
        if kind == "cube":
            models[bid] = {"parent": "minecraft:block/cube_all", "textures": {"all": ref(bid)}}
            states = {"variants": {"": {"model": ref(bid)}}}
            item = {"parent": ref(bid)}
        elif kind == "cross" and opts.get("neon"):
            # a model list per colour: <id>_<colour>, <id>_<colour>_2 ...
            variants = {}
            for c in textures.NEONS:
                ts = variant_names(f"{bid}_{c}", len(texs))
                for t in ts:
                    models[t] = plane_model(t, "minecraft:block/cross", "cross", "cross")
                variants[f"neon={c}"] = pick([{"model": ref(t)} for t in ts], weights)
            states = {"variants": variants}
            item, extra_items = neon_items(bid, textures.NEONS, lambda c: {"parent": "minecraft:item/generated", "textures": {"layer0": ref(f"{bid}_{c}")}})
        elif kind == "tall" and opts.get("neon"):
            variants = {}
            for c in textures.NEONS:
                bottoms, tops = variant_names(f"{bid}_{c}_bottom", len(texs)), variant_names(f"{bid}_{c}_top", len(texs))
                for t in bottoms + tops:
                    models[t] = plane_model(t, "minecraft:block/cross", "cross", "cross")
                # as for "tall": both halves are seeded from the lower half's position
                variants[f"half=lower,neon={c}"] = pick([{"model": ref(t)} for t in bottoms], weights)
                variants[f"half=upper,neon={c}"] = pick([{"model": ref(t)} for t in tops], weights)
            states = {"variants": variants}
            item, extra_items = neon_items(bid, textures.NEONS, lambda c: {"parent": "minecraft:item/generated", "textures": {"layer0": ref(f"{bid}_{c}_top")}})
        elif kind in ("tube_worms", "wall_tube_worms"):
            # each colour's looks by density (0.20.6: dense for a clump's middle, sparse for its edge), out and in;
            # a floor look turned four ways, a wall look as drawn and turned over, turned to face away from its wall.
            # The lists keep one order out and in, so a cluster keeps its look as its worms go in and out
            # (block.TubeWormsBlock.OUT; the near renderer finds the plumes of the look the game picked by the same
            # order, client.render.TubeWormsRenderer)
            wall = kind == "wall_tube_worms"
            looks = worm_looks(wall)
            variants = {}
            for c in textures.NEONS:
                t = {"worms": ref(f"tube_worms_{c}"), "plume": ref(f"tube_worm_plumes_{c}"), "glow": ref(f"tube_worm_plumes_{c}_glow")}
                for d, ls in looks.items():
                    for k, _ in ls:
                        for o in ("", "_in"):
                            models[f"{bid}_{c}_{d}_{k}{o}"] = {"parent": f"{MOD}:block/template/{bid}_{d}_{k}{o}",
                                                               "render_type": "minecraft:cutout", "textures": t}
                    for out, o in (("true", ""), ("false", "_in")):
                        sparse = "true" if d == "sparse" else "false"
                        if wall:
                            for facing, r in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
                                variants[f"facing={facing},neon={c},out={out},sparse={sparse}"] = pick(
                                    [dict({"model": ref(f"{bid}_{c}_{d}_{k}{o}")}, **({"y": r} if r else {})) for k, _ in ls])
                        else:
                            variants[f"neon={c},out={out},sparse={sparse}"] = pick(
                                [dict({"model": ref(f"{bid}_{c}_{d}_{k}{o}")}, **({"y": r} if r else {}))
                                 for k, _ in ls for r in (0, 90, 180, 270)])
            states = {"variants": variants}
            if wall:
                drop = "tube_worms"
            else:
                item, extra_items = neon_items(bid, textures.NEONS, lambda c: {"parent": ref(f"{bid}_{c}_dense_0")})
        elif kind == "mushrooms":
            # a model per count (block.NeonMushroomsBlock.MUSHROOMS) and colour, turned four ways by position
            variants = {}
            for c in textures.NEONS:
                t = {"mushrooms": ref(f"{bid}_{c}"), "glow": ref(f"{bid}_{c}_glow")}
                for n in plants_vent.MUSHROOM_LOOKS:
                    models[f"{bid}_{c}_{n}"] = {"parent": f"{MOD}:block/template/neon_mushrooms_{n}", "render_type": "minecraft:cutout",
                                                "textures": t}
                    variants[f"mushrooms={n},neon={c}"] = pick([dict({"model": ref(f"{bid}_{c}_{n}")}, **({"y": r} if r else {}))
                                                               for r in (0, 90, 180, 270)])
            states = {"variants": variants}
            item, extra_items = neon_items(bid, textures.NEONS, lambda c: {"parent": ref(f"{bid}_{c}_2")})
        elif kind in ("cross", "vine", "crystal") or kind.startswith("vine_body:"):
            for t in texs:
                models[t] = plane_model(t, "minecraft:block/cross", "cross", "cross")
            if kind == "crystal":
                states = {"variants": {f"facing={f}": pick([dict({"model": ref(t)}, **turn) for t in texs], weights) for f, turn in FACE6.items()}}
            elif opts.get("resting"):
                # GlowbellBlock: rung by a rune it rests a while, its glow sunk to an ember (<look>_resting). The lists
                # are the same length and order, so a bell keeps its look as it dims.
                rests = [t + "_resting" for t in texs]
                for t in rests:
                    models[t] = plane_model(t, "minecraft:block/cross", "cross", "cross")
                states = {"variants": {"resting=false": pick([{"model": ref(t)} for t in texs], weights),
                                       "resting=true": pick([{"model": ref(t)} for t in rests], weights)}}
            else:
                states = {"variants": {"": pick([{"model": ref(t)} for t in texs], weights)}}
            if kind.startswith("vine_body:"):
                item = None
                drop = kind.split(":", 1)[1]
        elif kind == "tall":
            bottoms, tops = variant_names(bid + "_bottom", len(texs)), variant_names(bid + "_top", len(texs))
            for t in bottoms + tops:
                models[t] = plane_model(t, "minecraft:block/cross", "cross", "cross")
            # Lists of the same length and order: both halves are seeded from the lower half's position, so variant k's
            # top always sits on variant k's bottom.
            states = {"variants": {"half=lower": pick([{"model": ref(t)} for t in bottoms], weights),
                                   "half=upper": pick([{"model": ref(t)} for t in tops], weights)}}
            item = {"parent": "minecraft:item/generated", "textures": {"layer0": ref(tops[0])}}
        elif kind == "cattails":
            variants = {}
            for part in CATTAIL_PARTS:
                ts = variant_names(f"{bid}_{part}", len(texs))
                for t in ts:
                    models[t] = plane_model(t, "minecraft:block/cross", "cross", "cross")
                # Lists of one length and order for every part: all the blocks of a plant are seeded from its foot.
                for wet in ("false", "true"):
                    variants[f"part={part},waterlogged={wet}"] = pick([{"model": ref(t)} for t in ts], weights)
            states = {"variants": variants}
            item = {"parent": "minecraft:item/generated", "textures": {"layer0": ref(f"{bid}_short_top")}}
        elif kind == "moss_carpet":
            models[bid] = {"parent": "minecraft:block/carpet", "textures": {"wool": ref(opts["texture"])}}
            states = {"variants": {"": {"model": ref(bid)}}}
            item = {"parent": ref(bid)}
        elif kind == "carpet":
            for t in texs:
                models[t] = plane_model(t, f"{MOD}:block/template/carpet", "carpet", "texture")
            states = {"variants": {"": pick([{"model": ref(t), "y": r} for t in texs for r in (0, 90, 180, 270)])}}
        elif kind == "litter":
            for t in texs:
                models[t] = plane_model(t, f"{MOD}:block/template/carpet", "carpet", "texture")
            states = {"variants": {f"facing={f},look={k}": dict({"model": ref(t)}, **turn) for k, t in enumerate(texs) for f, turn in FACE4.items()}}
            drop = opts["drop"]
        elif kind == "lichen":
            for t in texs:
                models[t] = plane_model(t, f"{MOD}:block/template/lichen", "lichen", "texture")
            # Vanilla's multipart: a part per face, and every part when no face is set (as vanilla draws that state).
            # All parts of one block share a random seed, so all its faces show the same variant.
            parts = []
            for face, turn in LICHEN_TURNS.items():
                apply = pick([dict({"model": ref(t)}, **turn) for t in texs], weights)
                parts.append({"when": {face: "true"}, "apply": apply})
                parts.append({"when": {f: "false" for f in FACES}, "apply": apply})
            states = {"multipart": parts}
        elif kind == "fern":
            fans = variant_names(bid + "_fan", len(texs))
            for k, t in enumerate(texs):
                models[t] = {"parent": f"{MOD}:block/template/fern", "render_type": "minecraft:cutout",
                             "textures": {"fern": ref(t), "fan_a": ref(fans[k]), "fan_b": ref(fans[(k + 1) % len(fans)])}}
            # each look also turned four ways, as its two fans differ
            states = {"variants": {"": pick([{"model": ref(t), "y": r} if r else {"model": ref(t)} for t in texs for r in (0, 90, 180, 270)],
                                            [w for w in weights for _ in range(4)] if weights else None)}}
        elif kind == "tall_fern":
            bottoms, tops = variant_names(bid + "_bottom", len(texs)), variant_names(bid + "_top", len(texs))
            fan_bottoms, fan_tops = variant_names(bid + "_fan_bottom", len(texs)), variant_names(bid + "_fan_top", len(texs))
            for k in range(len(texs)):
                models[bottoms[k]] = {"parent": f"{MOD}:block/template/tall_fern_lower", "render_type": "minecraft:cutout",
                                      "textures": {"fern": ref(bottoms[k]), "fan": ref(fan_bottoms[k]),
                                                   "skirt": ref(variant_names(opts["skirt"], len(texs))[k])}}
                models[tops[k]] = {"parent": f"{MOD}:block/template/tall_fern_upper", "render_type": "minecraft:cutout",
                                   "textures": {"fern": ref(tops[k]), "fan": ref(fan_tops[k])}}
            # as for "tall": both halves are seeded from the lower half's position, so variant k's top sits on its bottom
            states = {"variants": {"half=lower": pick([{"model": ref(t)} for t in bottoms], weights),
                                   "half=upper": pick([{"model": ref(t)} for t in tops], weights)}}
            item = {"parent": "minecraft:item/generated", "textures": {"layer0": ref(tops[0])}}
        elif kind == "hanging_fern":
            # one look per variant at each size (HangingFernBlock.SIZE): <id>, <id>_medium, <id>_large
            variants = {}
            for k_size, size in enumerate(HANGING_SIZES):
                hangs = variant_names(bid + size, len(texs))
                fronts, sides = variant_names(bid + size + "_front", len(texs)), variant_names(bid + size + "_side", len(texs))
                for k, t in enumerate(hangs):
                    models[t] = {"parent": f"{MOD}:block/template/hanging_fern{size}", "render_type": "minecraft:cutout",
                                 "textures": {"hang": ref(t), "front": ref(fronts[k]), "side": ref(sides[k])}}
                for f, turn in FACE4.items():
                    # unrolled (0.18.7, struck by a rune: its fronds let down the rock as a curtain), it looks the same
                    for unrolled in ("false", "true"):
                        variants[f"facing={f},size={k_size},unrolled={unrolled}"] = pick([dict({"model": ref(t)}, **turn) for t in hangs], weights)
            states = {"variants": variants}
            item = None
            drop = opts["drop"]
        elif kind == "wall_plant":
            for t in texs:
                models[t] = {"parent": "minecraft:block/coral_wall_fan", "render_type": "minecraft:cutout", "textures": {"fan": ref(t)}}
            states = {"variants": {f"facing={f}": pick([dict({"model": ref(t)}, **turn) for t in texs], weights) for f, turn in FACE4.items()}}
        elif kind == "coral_fan":
            for t in texs:
                models[t] = {"parent": "minecraft:block/coral_fan", "render_type": "minecraft:cutout", "textures": {"fan": ref(t)}}
            states = {"variants": {"": pick([{"model": ref(t)} for t in texs], weights)}}
        elif kind == "invisible":
            # Drawn as nothing (RenderShape.INVISIBLE): vanilla's empty air model stands in for every state.
            states = {"variants": {"": {"model": "minecraft:block/air"}}}
            item = None
        elif kind == "shelf":
            for t in texs:
                models[t] = shelf_model(ref(t), ref(bid + "_under"), opts.get("glow_under", False))
            states = {"variants": {f"facing={f}": pick([dict({"model": ref(t)}, **turn) for t in texs], weights) for f, turn in FACE4.items()}}
            item = {"parent": ref(bid)}
        elif kind == "glow_cube" and opts.get("turns") == "overlay":
            # a vanilla rock under our overlay in variants, each in the eight turns of its overlay alone
            end, side = ROCKS[opts["rock"]]
            groups = overlays(opts)
            entries = []
            n = opts.get("variants", 1)
            for tv, mv in zip(variant_names(opts.get("texture", bid), n), variant_names(bid, n)):
                t = {"rock_end": end, "rock_side": side}
                for group, suffix in groups.items():
                    t[group], t[group + "_glow"] = ref(tv + suffix), ref(tv + suffix + "_glow")
                for mirror, turn in TURNS:
                    models[mv + turn_suffix(mirror, turn)] = {
                        "parent": f"{MOD}:block/template/{rock_template(groups)}{turn_suffix(mirror, turn)}",
                        "render_type": opts.get("render_type", "minecraft:cutout"), "textures": t}
                    entries.append({"model": ref(mv + turn_suffix(mirror, turn))})
            states = {"variants": {"": entries}}
            item = {"parent": ref(bid)}
        elif kind == "glow_cube":
            colours = textures.NEONS if opts.get("neon") else [None]
            for c in colours:
                m = bid if c is None else f"{bid}_{c}"
                if "rock" in opts:
                    # the vanilla rock, then our overlay and its glow on the faces the overlay covers
                    end, side = ROCKS[opts["rock"]]
                    t = {"rock_end": end, "rock_side": side}
                    for group, suffix in overlays(opts).items():
                        t[group], t[group + "_glow"] = ref(m + suffix), ref(m + suffix + "_glow")
                    template = rock_template(overlays(opts))
                elif opts.get("faces") == "top_side_bottom":
                    t = {g: ref(f"{m}_{g}") for g in ("top", "side", "bottom")}
                    t.update({g + "_glow": ref(f"{m}_{g}_glow") for g in ("top", "side", "bottom")})
                    template = "cube_top_side_bottom_glow"
                else:
                    t = {"all": ref(m), "glow": ref(m + "_glow")}
                    template = "cube_glow"
                models[m] = {"parent": f"{MOD}:block/template/{template}", "render_type": "minecraft:cutout", "textures": t}
                if opts.get("turns"):
                    models[m + "_mirrored"] = {"parent": f"{MOD}:block/template/{template}_mirrored",
                                               "render_type": "minecraft:cutout", "textures": t}
            if opts.get("neon"):
                if opts.get("turns"):
                    states = {"variants": {f"neon={c}": [dict({"model": ref(f"{bid}_{c}" + ("_mirrored" if mirror else ""))},
                                                              **({"y": y} if y else {})) for mirror, y in TURNS]
                                           for c in colours}}
                else:
                    states = {"variants": {f"neon={c}": {"model": ref(f"{bid}_{c}")} for c in colours}}
                # The item property explorationremix:neon (client.ClientSetup) is the colour's place in block.Neon over
                # the last place, 0 to 1; a colour's model takes over from halfway below its value. The game uses the
                # last override that matches, so they go in order.
                last = len(colours) - 1
                item = {"parent": ref(f"{bid}_{colours[0]}"),
                        "overrides": [{"predicate": {f"{MOD}:neon": round((k - 0.5) / last, 4)}, "model": ref(f"{bid}_{c}")}
                                      for k, c in enumerate(colours) if k]}
            else:
                states = {"variants": {"": {"model": ref(bid)}}}
                item = {"parent": ref(bid)}
        elif kind == "pointed":
            # vanilla's states and models (block/pointed_dripstone, two crossed planes), with our textures
            variants = {}
            for d in ("down", "up"):
                for thickness in textures.POINTED_DRIP_THICKNESS:
                    t = f"{bid}_{d}_{thickness}"
                    models[t] = {"parent": "minecraft:block/pointed_dripstone", "render_type": "minecraft:cutout",
                                 "textures": {"cross": ref(t)}}
                    variants[f"thickness={thickness},vertical_direction={d}"] = {"model": ref(t)}
            states = {"variants": variants}
            item = {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/{bid}"},
                    "display": POINTED_ITEM_DISPLAY}
        else:
            raise ValueError(f"{bid}: unknown kind {kind}")
        if opts.get("glow_under"):
            glowing.add(bid)
        if not opts.get("shader_glow", True):
            as_rock.add(bid)
        for mname, m in models.items():
            for key, tex in m["textures"].items():
                if tex.startswith("minecraft:"):  # vanilla's, which the game has: only the rocks' (a typo is refused)
                    assert tex in VANILLA_TEXTURES, f"{bid}: {tex} is not one of the vanilla textures gen_data knows (ROCKS)"
                    continue
                painted = "block/" + tex.split("block/", 1)[1]
                if key.endswith("glow"):  # an overlay: #glow, or a face's (#top_glow, #side_glow)
                    assert painted[:-len("_glow")] in textures.GLOW, f"{bid}: no glow overlay is painted for {tex}"
                    used_glow.add(painted[:-len("_glow")])
                    if opts.get("shader_glow", True):
                        glowing.add(bid)
                else:
                    assert painted in textures.PAINTERS, f"{bid}: no painter for {tex}"
            write(os.path.join(ASSETS, "models", "block", mname + ".json"), m)
        write(os.path.join(ASSETS, "blockstates", bid + ".json"), states)
        if opts.get("no_item"):
            item, extra_items = None, {}
        if item is not None:
            for name_, m_ in dict(extra_items, **{bid: item}).items():
                for tex in m_.get("textures", {}).values():  # an item's sprite needs a painter too
                    assert tex.split(":", 1)[1] in textures.PAINTERS, f"{bid}: no painter for its item's {tex}"
                write(os.path.join(ASSETS, "models", "item", name_ + ".json"), m_)
        if bid not in NO_DROP:
            write(os.path.join(DATA, "loot_tables", "blocks", bid + ".json"),
                  loot(bid, drop, kind, opts.get("chance"), opts.get("neon", False), opts.get("shears", False),
                       opts.get("silk", False)))
    unused = set(textures.GLOW) - used_glow
    assert not unused, f"glow overlays no model draws: {sorted(unused)}"
    write(os.path.join(ASSETS, "glowing_blocks.json"), {"blocks": sorted(glowing), "as_rock": sorted(as_rock)})
    lang[f"itemGroup.{MOD}"] = "Exploration Remix"
    return lang


# =============================================================================================== biomes
# The biomes, their features and the block tags they use live in worldgen.py.


def tags():
    # Band stone: nothing breaks it, moves it or grows through it.
    # 0.23.1: and no feature carves or grows through a rune stone either, nor does a geode swallow one
    stones = [f"{MOD}:{b}" for b in rune_stones.BLOCK_IDS]
    for t in ("dragon_immune", "wither_immune", "features_cannot_replace", "geode_invalid_blocks"):
        tag("minecraft", "blocks", t, [f"{MOD}:band_stone"] + (stones if t in ("features_cannot_replace", "geode_invalid_blocks") else []))
    for (ns, name), ids in BLOCK_TAGS.items():
        tag(ns, "blocks", name, [f"{MOD}:{b}" for b in ids])
    tag(MOD, "blocks", "plants", [f"{MOD}:{b}" for b in BLOCKS if b not in NOT_PLANTS] + VANILLA_PLANTS)


def biome_modifiers():
    write(os.path.join(DATA, "forge", "biome_modifier", "remove_create_striations.json"), {"type": f"{MOD}:remove_create_striations"})
    write(os.path.join(DATA, "forge", "biome_modifier", "remove_upper_lava.json"), {"type": f"{MOD}:remove_upper_lava"})
    # Immersive Weathering's surface frost gives way to the mod's hoarfrost (worldgen.UPPER_MODIFIER_FEATURES). Forge
    # removes features after every mod has added its own, whatever the load order.
    write(os.path.join(DATA, "forge", "biome_modifier", "remove_iw_surface_frost.json"), {
        "type": "forge:remove_features", "biomes": "#immersive_weathering:icy", "features": "immersive_weathering:frost_patch",
        "steps": "top_layer_modification"})
    # Being in #immersive_weathering:icy (so their icicles never melt) also gives the mod's two cold caves IW's own
    # icicle clusters, wherever they lie above y 92: in a frozen Root Vault's thawed halls too. Their ice is placed by
    # the mod (worldgen.py, near the openings only), so IW's go.
    write(os.path.join(DATA, "forge", "biome_modifier", "remove_iw_cave_icicles.json"), {
        "type": "forge:remove_features", "biomes": [f"{MOD}:frozen_rootvaults", f"{MOD}:frost_fern_grotto"],
        "features": "immersive_weathering:icicles", "steps": "top_layer_modification"})


def creature_data():
    """The creatures' drops: the Mossback's (and what it hunts), the Skein's, the giant Skein's and the chimney lurker's."""
    write(os.path.join(DATA, "loot_tables", "entities", "mossback.json"), {
        "type": "minecraft:entity",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:mossback_hide", "functions": [
            {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 1, "max": 2}},
            {"function": "minecraft:looting_enchant", "count": {"type": "minecraft:uniform", "min": 0, "max": 1}}]}]},
                  # now and then a rune of the Upper Caves, when a player kills it, and often one of the layer's two
                  # kinds of collect rune (0.22.6: armor's sets want three of a kind)
                  runes.drop_pool(0.25, 0.05, [("upper_caves", None)]),
                  runes.drop_pool(0.5, 0.1, [("collect/upper_caves", None)])]})
    # cave animals it catches when hungry (creepers live in the lush caves and Root Vaults)
    tag(MOD, "entity_types", "mossback_prey", ["minecraft:bat", "minecraft:axolotl", "minecraft:glow_squid", "minecraft:creeper",
                                               "minecraft:frog", "minecraft:rabbit", "minecraft:chicken", "minecraft:fox",
                                               "minecraft:goat", "minecraft:sheep", "minecraft:pig", "minecraft:silverfish"])
    write(os.path.join(ASSETS, "models", "item", "mossback_hide.json"),
          {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/mossback_hide"}})
    # the Skein: a plate or two of its shell, kept for crafting later
    write(os.path.join(DATA, "loot_tables", "entities", "skein.json"), {
        "type": "minecraft:entity",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:skein_plate", "functions": [
            {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 1, "max": 2}},
            {"function": "minecraft:looting_enchant", "count": {"type": "minecraft:uniform", "min": 0, "max": 1}}]}]},
                  # a rune a little more often than a Mossback: four times in five one of the Blue Reaches', else one of
                  # the Deep Gardens' (each item in a tag takes its entry's weight: 7 x 20 against 5 x 7)
                  runes.drop_pool(0.35, 0.05, [("blue_reaches", 20), ("deep_gardens", 7)]),
                  runes.drop_pool(0.45, 0.1, [("collect/blue_reaches", None)])]})
    write(os.path.join(ASSETS, "models", "item", "skein_plate.json"),
          {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/skein_plate"}})
    # 0.18.8: the giant Skein of the Deep Blue Rift, one to a rift and gone for good once killed, so it pays well: a
    # handful of its long spines and big plates, and when a player kills it a rune of the Blue Reaches for certain and
    # sometimes one of the Deep Gardens' too
    write(os.path.join(DATA, "loot_tables", "entities", "giant_skein.json"), {
        "type": "minecraft:entity",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:giant_skein_spine", "functions": [
            {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 3, "max": 5}},
            {"function": "minecraft:looting_enchant", "count": {"type": "minecraft:uniform", "min": 0, "max": 2}}]}]},
                  {"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:skein_plate", "functions": [
                      {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 4, "max": 7}}]}]},
                  runes.drop_pool(1.0, 0.0, [("blue_reaches", None)]),
                  runes.drop_pool(0.4, 0.1, [("deep_gardens", None)]),
                  runes.drop_pool(1.0, 0.0, [("collect/blue_reaches", None)])]})
    write(os.path.join(ASSETS, "models", "item", "giant_skein_spine.json"),
          {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/giant_skein_spine"}})
    # 0.21.0: the chimney lurker of the Deep Gardens' vents: a fang or two, and when a player kills it, a rune of the Deep
    # Gardens a little more often than a Skein gives one (it is the layer's hunter, and a hard fight)
    write(os.path.join(DATA, "loot_tables", "entities", "chimney_lurker.json"), {
        "type": "minecraft:entity",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:chimney_lurker_fang", "functions": [
            {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 1, "max": 2}},
            {"function": "minecraft:looting_enchant", "count": {"type": "minecraft:uniform", "min": 0, "max": 1}}]}]},
                  runes.drop_pool(0.4, 0.05, [("deep_gardens", None)]),
                  runes.drop_pool(0.5, 0.1, [("collect/deep_gardens", None)])]})
    write(os.path.join(ASSETS, "models", "item", "chimney_lurker_fang.json"),
          {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/chimney_lurker_fang"}})
    # 0.21.9: the Hollow Choir of the Deep Dark. It costs three keys from three ancient cities and five minutes of a
    # hard fight, and there is one of it per arena per summoning, so it pays like nothing else: the hollow bell itself,
    # and two runes for certain, one of the Blue Reaches and one of either deep layer (Taran's pick: experience, a
    # trophy and two runes). The experience is the entity's own (HollowChoirEntity.XP), not a drop.
    write(os.path.join(DATA, "loot_tables", "entities", "hollow_choir.json"), {
        "type": "minecraft:entity",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"{MOD}:choir_bell"}]},
                  runes.drop_pool(1.0, 0.0, [("blue_reaches", None)]),
                  runes.drop_pool(1.0, 0.0, [("blue_reaches", 10), ("deep_gardens", 10)]),
                  runes.drop_pool(1.0, 0.0, [("collect/deep_gardens", None)])]})
    # A boss never staggers (combat.Stagger) and nothing shoves it off its dais.
    tag("forge", "entity_types", "bosses", [f"{MOD}:hollow_choir"])
    # The two of its blocks a player may keep drop themselves; the reliquary, the sockets and the sprouts do not
    # (ERBlocks gives those noLootTable).
    for bid in ("choir_brazier", "choir_bell"):
        write(os.path.join(DATA, "loot_tables", "blocks", bid + ".json"), {
            "type": "minecraft:block",
            "pools": [{"rolls": 1, "bonus_rolls": 0,
                       "entries": [{"type": "minecraft:item", "name": f"{MOD}:{bid}"}],
                       "conditions": [{"condition": "minecraft:survives_explosion"}]}]})


# Entities that are not creatures (creatures.py names those): 0.5.1's lava bomb, thrown by the Caldera's eruptions.
ENTITY_NAMES = {"lava_bomb": "Lava Bomb", "freed_rune": "Freed Rune"}


# What the Hollow Choir's arena says (0.21.9): the key's tooltip, how the three sockets stand, why a blow on the crown
# did nothing, and the summoning. All of it is said on the action bar, so nothing fills the chat.
CHOIR_WORDS = {
    f"item.{MOD}.choir_key.tip": "One of three. Set it in a socket at the Choir's bowl.",
    f"item.{MOD}.choir_key.tip2": "Every ancient city keeps one.",
    f"message.{MOD}.choir.need": "The socket is empty. It wants a choir key.",
    f"message.{MOD}.choir.keys": "%s of %s keys set.",
    f"message.{MOD}.choir.keys_hush": "%s of %s keys set. No shrieker here can call the Warden now.",
    f"message.{MOD}.choir.woken": "The Hollow Choir wakes.",
    f"message.{MOD}.choir.held": "The crown is held up. Cut the tendrils.",
}


def lang(extra):
    out = dict(extra)
    out.update(CHOIR_WORDS)
    # the blue fireflies (creature.FireflySwarm; tools/fireflies.py paints their glow): one entity is a whole swarm
    out[f"entity.{MOD}.firefly_swarm"] = "Blue Fireflies"
    write(os.path.join(ASSETS, "lang", "en_us.json"), dict(sorted(out.items())))


# The mod's particle types (ERParticles): the first three and the lava spatter reuse vanilla sprites, the others have
# their own (textures/particle, painted by tools/particles.py); CaveParticles tints and animates them. Every type ERParticles registers needs its file
# here (check_data checks it): one without crashes the game when it is spawned.
PARTICLES = {
    "steam": [f"minecraft:big_smoke_{i}" for i in range(12)],
    "ember": ["minecraft:generic_0", "minecraft:generic_1"],
    "pollen": ["minecraft:generic_0"],
    # 0.8.0: painted by tools/particles.py
    "volcanic_smoke": [f"{MOD}:volcanic_smoke_{i}" for i in range(4)],
    # 0.9.0: the quakes' grit, painted by tools/particles.py
    "grit": [f"{MOD}:grit_{i}" for i in range(3)],
    # 0.9.0: the eruptions' lava spatter, vanilla's lava sprite drawn full-bright (CaveParticles.Spatter)
    "lava_spatter": ["minecraft:lava"],
    # 0.9.0 (pack look fixes): the fountain's lava gobs and the fire at the mouth, painted by tools/particles.py; the
    # plume's dark ash, the blasts' ash and the crashes' rock dust tint the volcanic smoke's puffs
    "lava_gob": [f"{MOD}:lava_gob_{i}" for i in range(4)],
    "fireball": [f"{MOD}:fireball_{i}" for i in range(4)],
    # 0.9.0 (eruption effects, round 2): the clouds and the plume take soft puffs of their own (the smoke's hard-stepped
    # puffs showed square facets blown up), and the blasts' flash, the vents' glow and the rubble have their own sprites
    "eruption_ash": [f"{MOD}:ash_puff_{i}" for i in range(4)],
    "blast_ash": [f"{MOD}:soft_puff_{i}" for i in range(4)],
    "rock_dust": [f"{MOD}:soft_puff_{i}" for i in range(4)],
    # (round 3) the blasts' flash takes white-hot sprites of its own, after the glow's and the blaze's (picked by index)
    "flash": [f"{MOD}:flash_{i}" for i in range(2)] + [f"{MOD}:flash_hot_{i}" for i in range(2)],
    "rubble": [f"{MOD}:rubble_{i}" for i in range(4)],
    # 0.20.2: the motes drifting up off the Deep Gardens' grass and small mushrooms, tinted their colour
    # (CaveParticles.Spore), painted by tools/plants_vent.py
    "spore": [f"{MOD}:spore_{i}" for i in range(2)],
}


def particles():
    for name, sprites in PARTICLES.items():
        write(os.path.join(ASSETS, "particles", name + ".json"), {"textures": sprites})


def ruin_loot():
    """0.22.4: the lower Galleries' ruins' chests (GalleriesRuinsFeature; Taran's card: "A rune and some loot"). The rune's
    chest in each cluster's big structure holds a rune for certain, most often one of the deep layers' (each item in a
    tag takes its entry's weight: the Deep Gardens' 5 x 10, the Blue Reaches' 7 x 5, the Upper Caves' 16 x 2), and a few
    things besides; the loot chests in two or three of its huts hold the builders' leavings: gold, iron, torches for the
    dark, obsidian, a bottle of fire resistance for the lava, now and then a diamond, a golden apple or ancient debris."""
    def item(name, weight, lo=1, hi=1, extra=None):
        e = {"type": "minecraft:item", "name": name, "weight": weight}
        f = [] if lo == hi == 1 else [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": lo, "max": hi}}]
        if extra: f.append(extra)
        if f: e["functions"] = f
        return e
    leavings = [item("minecraft:gold_nugget", 20, 3, 9), item("minecraft:gold_ingot", 10, 1, 3), item("minecraft:iron_ingot", 12, 1, 4),
                item("minecraft:torch", 12, 4, 12), item("minecraft:obsidian", 8, 1, 3), item("minecraft:magma_cream", 6, 1, 3),
                item("minecraft:experience_bottle", 8, 1, 4),
                item("minecraft:potion", 6, extra={"function": "minecraft:set_potion", "id": "minecraft:fire_resistance"}),
                item("minecraft:diamond", 3, 1, 2), item("minecraft:golden_apple", 2), item("minecraft:ancient_debris", 1)]
    write(os.path.join(DATA, "loot_tables", "chests", "galleries_ruin.json"), {
        "type": "minecraft:chest",
        "pools": [{"rolls": {"type": "minecraft:uniform", "min": 3, "max": 6}, "entries": leavings}]})
    write(os.path.join(DATA, "loot_tables", "chests", "galleries_ruin_rune.json"), {
        "type": "minecraft:chest",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:tag", "name": f"{MOD}:runes/{layer}", "expand": True, "weight": w}
                                           for layer, w in (("deep_gardens", 10), ("blue_reaches", 5), ("upper_caves", 2))]},
                  {"rolls": {"type": "minecraft:uniform", "min": 2, "max": 4}, "entries": leavings}]})


def main():
    kept = {}
    for path in KEPT:
        assert os.path.isfile(path), f"{path}: a kept file is missing (restore it from git)"
        with open(path, "rb") as f:
            kept[path] = f.read()
    for d in OWNED:
        if os.path.isdir(d): shutil.rmtree(d)
        elif os.path.isfile(d): os.remove(d)
    names = block_assets()
    textures.write_all(os.path.join(ASSETS, "textures"))
    # 0.23.1: the rune stones, a hidden block in each layer that frees runes when broken (rune_stones.py)
    names.update(rune_stones.write_all(RES, write))
    # 0.12.0: the timed swords' blade light and swing sweep (textures/misc)
    swings.write_all(ASSETS)
    names.update(worldgen.write_all(DATA, RES, write))
    tags()
    biome_modifiers()
    names.update(sounds.write_all(ASSETS, write))
    names.update(creatures.write_all(ASSETS, write))
    # 0.21.9: the Hollow Choir's own blocks, whose shapes and states fit none of the kinds above (choir_blocks.py)
    names.update(choir_blocks.write_all(ASSETS, write))
    names.update(runes.write_all(RES, write, tag))
    # 0.20.5: the rune table's pixel art (textures/gui)
    rune_table_art.write_all(ASSETS)
    names.update({f"entity.{MOD}.{e}": n for e, n in ENTITY_NAMES.items()})
    creature_data()
    ruin_loot()
    particles()
    lang(names)
    for path, data in kept.items():
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
    print("data written")
    # The game refuses the whole datapack over one unknown id, and unit tests never load it.
    problems = check_data.main()
    assert not problems, "the datapack would not load:\n" + "\n".join(problems)


if __name__ == "__main__":
    main()
