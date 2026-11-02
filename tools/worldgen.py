"""Exploration Remix's biomes and their features, as datapack JSON. Called by gen_data.py.

Every biome's look is set here: fog, water and grass colours, ambient particles and sounds, and the features that
dress its caves. Features are listed once, in one master order (FEATURES), and each biome takes the ones it names in
that order, so feature sorting never finds a cycle between biomes.

Refuges hold no plants. Routes have some growth. Hunting grounds are the most overgrown places in their layer.
"""
import json, os

MOD = "explorationremix"

# ----------------------------------------------------------------------------------------------- biome list
UPPER = ["rootvaults", "silver_fern_rift", "ivory_root_cavern",
         # Upper Caves rework: the frozen mouths of the Root Vaults under snowy forests, the plains' sinkholes, the
         # deserts' and badlands' oasis hollows, and the cold plant caves
         "frozen_rootvaults", "sinkhole", "oasis_hollow", "frost_fern_grotto"]
# The entrances' biomes, whose features aim at their chambers (layer_range's grottos focus).
ENTRANCE_BIOMES = {"rootvaults", "frozen_rootvaults", "sinkhole", "oasis_hollow"}
DEEP = ["lantern_lake", "ember_gardens", "rust_springs", "deep_blue_rift", "asurine_geodes", "basalt_galleries", "obsidian_tubes"]
OURS = UPPER + DEEP
NAMES = {
    "rootvaults": "Root Vaults", "silver_fern_rift": "Silver Fern Rift", "ivory_root_cavern": "Ivory Root Cavern",
    "frozen_rootvaults": "Frozen Root Vaults", "sinkhole": "Sinkhole", "oasis_hollow": "Oasis Hollow", "frost_fern_grotto": "Frost Fern Grotto",
    "lantern_lake": "Lantern Lake", "ember_gardens": "Ember Gardens", "rust_springs": "Rust Springs",
    "deep_blue_rift": "Deep Blue Rift", "asurine_geodes": "Asurine Geodes",
    "basalt_galleries": "Basalt Galleries", "obsidian_tubes": "Obsidian Tubes",
}
LAYER = {"rootvaults": "upper", "silver_fern_rift": "upper", "ivory_root_cavern": "upper",
         "frozen_rootvaults": "upper", "sinkhole": "upper", "oasis_hollow": "upper", "frost_fern_grotto": "upper",
         "lantern_lake": "deep_gardens", "ember_gardens": "deep_gardens", "rust_springs": "deep_gardens",
         "deep_blue_rift": "blue_reaches", "asurine_geodes": "blue_reaches",
         "basalt_galleries": "basalt_galleries", "obsidian_tubes": "basalt_galleries"}

# Fog is the colour of the distance, water and water fog tint the water, grass and foliage tint ferns and leaves.
#                       fog        water      water fog  grass      foliage
LOOKS = {
    "rootvaults":        ("#b4c27c", "#3cc4b4", "#0f5a55", "#6fae58", "#5d9c48"),
    "silver_fern_rift":  ("#93ab98", "#3aa9c2", "#0f4a5a", "#7fa86a", "#6f985a"),
    "ivory_root_cavern": ("#c7b784", "#38cbc2", "#0e5e5a", "#8fae5a", "#7d9e4a"),
    # The entrances open to the sky in places: their fog is near the sky's. The cold ones' grass colour is a frosted
    # blue-grey, which tints Immersive Weathering's frosty ferns (0.4.0's snowy-taiga greens looked too lush).
    "frozen_rootvaults": ("#c4d6e8", "#3d57d6", "#0a1440", "#8fb3c4", "#7a9fb3"),
    "sinkhole":          ("#c0d8ef", "#3f76e4", "#0a2a52", "#91bd59", "#77ab2f"),
    "oasis_hollow":      ("#dccaa0", "#32c4c8", "#0a3d3f", "#8ab54a", "#6fa83a"),
    "frost_fern_grotto": ("#a9c8e0", "#2fb3c8", "#0e4d5c", "#86aebd", "#6f98a8"),
    "lantern_lake":      ("#5f7fb2", "#3bd0c4", "#12655f", "#a89a6a", "#988a5a"),
    "ember_gardens":     ("#b5562f", "#4cbcc9", "#163f4d", "#a8904c", "#98803c"),
    "rust_springs":      ("#a9482a", "#c9662b", "#5a2a10", "#a08a50", "#907a40"),
    "deep_blue_rift":    ("#1f3f7c", "#2554ba", "#08183d", "#6a8aa8", "#5a7a98"),
    "asurine_geodes":    ("#2a4a86", "#3e72d8", "#0b1f4a", "#6f8fa8", "#5f7f98"),
    "basalt_galleries":  ("#70574b", "#40607a", "#1a2630", "#8a7a5a", "#7a6a4a"),
    "obsidian_tubes":    ("#4d3b42", "#40607a", "#1a2630", "#8a7a5a", "#7a6a4a"),
}

# Ambient particles drifting in the air: (particle, probability). Steam over hot pools, embers over lava, pollen in
# the daylight shafts and drips under ceilings are spawned by the client (CaveAtmosphere), not here.
PARTICLES = {
    "frozen_rootvaults": ("minecraft:snowflake", 0.005),
    "frost_fern_grotto": ("minecraft:snowflake", 0.002),
    "lantern_lake": ("minecraft:mycelium", 0.012),
    "ember_gardens": ("minecraft:mycelium", 0.012),
    "rust_springs": ("minecraft:mycelium", 0.008),
    "deep_blue_rift": ("minecraft:warped_spore", 0.01),
    "asurine_geodes": ("minecraft:warped_spore", 0.004),
    "basalt_galleries": ("minecraft:white_ash", 0.06),
    "obsidian_tubes": ("minecraft:ash", 0.02),
}

# Ambient sound: (loop, additions, additions chance per tick). Our sound events are registered by the mod (ERSounds).
SOUNDS = {
    "rootvaults": (f"{MOD}:ambient.upper.loop", f"{MOD}:ambient.upper.additions", 0.008),
    "silver_fern_rift": (f"{MOD}:ambient.upper.loop", f"{MOD}:ambient.upper.additions", 0.008),
    "ivory_root_cavern": (f"{MOD}:ambient.upper.loop", f"{MOD}:ambient.upper.additions", 0.008),
    "frozen_rootvaults": (f"{MOD}:ambient.upper.loop", f"{MOD}:ambient.upper.additions", 0.006),
    "sinkhole": (f"{MOD}:ambient.upper.loop", f"{MOD}:ambient.upper.additions", 0.006),
    "oasis_hollow": (f"{MOD}:ambient.upper.loop", f"{MOD}:ambient.upper.additions", 0.004),
    "frost_fern_grotto": (f"{MOD}:ambient.upper.loop", f"{MOD}:ambient.upper.additions", 0.008),
    "lantern_lake": (f"{MOD}:ambient.gardens.loop", f"{MOD}:ambient.gardens.additions", 0.012),
    "ember_gardens": (f"{MOD}:ambient.springs.loop", f"{MOD}:ambient.gardens.additions", 0.012),
    "rust_springs": (f"{MOD}:ambient.springs.loop", f"{MOD}:ambient.gardens.additions", 0.008),
    "deep_blue_rift": (f"{MOD}:ambient.blue.loop", f"{MOD}:ambient.blue.additions", 0.006),
    "asurine_geodes": (f"{MOD}:ambient.blue.loop", f"{MOD}:ambient.blue.additions", 0.003),
    "basalt_galleries": (f"{MOD}:ambient.basalt.loop", f"{MOD}:ambient.basalt.additions", 0.012),
    "obsidian_tubes": (f"{MOD}:ambient.basalt.loop", f"{MOD}:ambient.basalt.additions", 0.004),
}

HOT = {"ember_gardens", "rust_springs", "basalt_galleries", "obsidian_tubes"}
DRY = {"basalt_galleries", "obsidian_tubes"}
# The Upper Caves rework's biomes: snow-cold, or desert-hot; rain and snow fall into the open entrances' pits and
# skylights (the pack's own biome holds the ground over them).
TEMPERATURE = {"frozen_rootvaults": -0.5, "frost_fern_grotto": -0.3, "oasis_hollow": 2.0, "sinkhole": 0.8}
PRECIPITATION = {"frozen_rootvaults", "sinkhole"}

# The pack's dripstone caves list these (WWOO's copy, which matches vanilla). The Rootvaults keep their ores and
# water springs in the same order, but not the lava lakes or lava springs: the caves above the first band are safe.
# Ordinary copper rather than dripstone's large copper: the surface biome's copper also goes into these biomes' rock
# (BiomeFilterMixin), and the two together would triple it.
ROOTVAULTS_VANILLA = [
    [],
    [],
    ["minecraft:amethyst_geode"],
    ["minecraft:monster_room", "minecraft:monster_room_deep"],
    [],
    [],
    ["minecraft:ore_dirt", "minecraft:ore_gravel", "minecraft:ore_granite_upper", "minecraft:ore_granite_lower", "minecraft:ore_diorite_upper",
     "minecraft:ore_diorite_lower", "minecraft:ore_andesite_upper", "minecraft:ore_andesite_lower", "minecraft:ore_tuff", "minecraft:ore_coal_upper",
     "minecraft:ore_coal_lower", "minecraft:ore_iron_upper", "minecraft:ore_iron_middle", "minecraft:ore_iron_small", "minecraft:ore_gold",
     "minecraft:ore_gold_lower", "minecraft:ore_redstone", "minecraft:ore_redstone_lower", "minecraft:ore_diamond", "minecraft:ore_diamond_large",
     "minecraft:ore_diamond_buried", "minecraft:ore_lapis", "minecraft:ore_lapis_buried", "minecraft:ore_copper", "minecraft:underwater_magma",
     "minecraft:disk_sand", "minecraft:disk_clay", "minecraft:disk_gravel"],
    [],
    ["minecraft:spring_water"],
    ["minecraft:glow_lichen"],
    ["minecraft:freeze_top_layer"],
]

# The cold caves leave out vanilla's green glow lichen (0.4.0 looked too lush); rime lichen lights them instead.
VANILLA_LEFT_OUT = {"frozen_rootvaults": {"minecraft:glow_lichen"}, "frost_fern_grotto": {"minecraft:glow_lichen"}}

STEPS = ["raw_generation", "lakes", "local_modifications", "underground_structures", "surface_structures", "strongholds",
         "underground_ores", "underground_decoration", "fluid_springs", "vegetal_decoration", "top_layer_modification"]

# ----------------------------------------------------------------------------------------------- helpers

def state(name, **props):
    s = {"Name": name}
    if props:
        s["Properties"] = {k: (str(v).lower() if isinstance(v, bool) else str(v)) for k, v in props.items()}
    return s


def simple(name, **props): return {"type": "minecraft:simple_state_provider", "state": state(name, **props)}
def fallback(*ids): return {"type": f"{MOD}:fallback", "blocks": list(ids)}
def mix(*pairs): return {"type": f"{MOD}:mix", "entries": [{"data": p, "weight": w} for p, w in pairs]}
def uniform(lo, hi): return {"type": "minecraft:uniform", "value": {"min_inclusive": lo, "max_inclusive": hi}}
def funiform(lo, hi): return {"type": "minecraft:uniform", "value": {"min_inclusive": lo, "max_exclusive": hi}}

LIMESTONE = fallback("create:limestone", "minecraft:calcite")
CRIMSITE = fallback("create:crimsite", "minecraft:granite")

AIR = {"type": "minecraft:matching_blocks", "blocks": ["minecraft:air", "minecraft:cave_air"]}
AIR_OR_WATER = {"type": "minecraft:matching_blocks", "blocks": ["minecraft:air", "minecraft:cave_air", "minecraft:water"]}
SQUARE = {"type": "minecraft:in_square"}
BIOME = {"type": "minecraft:biome"}
IN_WATER = {"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:matching_blocks", "blocks": ["minecraft:water"]}}
# Shallow water: air three blocks up, so the spot is on a shore or under at most three blocks of water.
SHALLOW = {"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:matching_blocks", "offset": [0, 3, 0],
                                                                     "blocks": ["minecraft:air", "minecraft:cave_air"]}}
PALE_CEILING = {"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:matching_blocks", "blocks": f"#{MOD}:pale_rock"}}
def count(n): return {"type": "minecraft:count", "count": n}
def rarity(n): return {"type": "minecraft:rarity_filter", "chance": n}
def layer(name, inset=2, above_water=False): return {"type": f"{MOD}:layer_range", "layer": name, "inset": inset, "above_water": above_water}
def looks(*l, cold=None, min_chill=None, max_chill=None):
    """Keeps a feature to some entrance looks; cold to entrances under snowy ground or not; min_chill and max_chill to
    spots of a frozen Root Vault cluster at least or at most that far past the snowy zone round its openings
    (Entrances.chill: 0 or less near the openings, THAW or more deep inside, where it has thawed)."""
    f = {"type": f"{MOD}:rootvaults_look", "looks": list(l)}
    if cold is not None: f["cold"] = cold
    if min_chill is not None: f["min_chill"] = float(min_chill)
    if max_chill is not None: f["max_chill"] = float(max_chill)
    return f
# Entrances.THAW_REACH: a frozen Root Vault has thawed this far past the snowy zone.
THAW = 8
# The entrances' looks (Entrances.java): root halls, teal grottos, ravines, sinkholes, oasis hollows.
HALL, TEAL, RAVINE, SINKHOLE, HOLLOW = range(5)


# The cave network (0.3) gives each floor and ceiling block of the Deep Gardens and the Basalt Galleries fewer of a
# feature's tries than 0.2.0's caverns, where the plants' counts were calibrated (vegetation.md, S7): a try starts at a
# random height in the layer and scans at most 32 blocks to a floor or a ceiling, so a floor block catches the tries
# started in the air over it, up to 32 blocks of it, and the network's rooms and tunnels are 5 to 20 tall where 0.2.0's
# caverns were 30 to 180. Measured on five cells of each layer (network-final.md, Implemented), a floor or ceiling block
# catches 0.35 to 0.43 times as many tries in the Deep Gardens and 0.24 to 0.25 times in the Basalt Galleries. Their
# plant features that scan for their spot are scaled back by these factors until the census tunes them; the Blue
# Reaches (0.2.0's rift and deep dark), the lake beds, the wall growth and the rock features keep their counts.
GARDENS_PLANTS = 2.5
BASALT_PLANTS = 4.0
# Census of 0.3.0 in game (seed 8675309, radius 4): Ember Gardens 20 % floor and 13 % ceiling against 35+ and 20+,
# Lantern Lake 12 % floor against 15-25, Basalt Galleries 2 % floor against 3-5; scaled by target over measured.
EMBER_FLOOR, EMBER_CEILING, LAKE_FLOOR, BASALT_FLOOR = 2.0, 1.8, 1.7, 2.0
def scaled(n, factor): return count(int(round(n * factor)))


def ceiling(offset=-1, steps=32, through=AIR):
    """From a random point, up to the ceiling above it (the ceiling block itself with offset 0)."""
    out = [{"type": "minecraft:environment_scan", "direction_of_search": "up",
            "target_condition": {"type": "minecraft:has_sturdy_face", "direction": "down"},
            "allowed_search_condition": through, "max_steps": steps}]
    if offset: out.append({"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": offset})
    return out


def floor(offset=1, steps=32, through=AIR):
    """From a random point, down to the floor below it (the block above the floor with offset 1)."""
    out = [{"type": "minecraft:environment_scan", "direction_of_search": "down",
            "target_condition": {"type": "minecraft:has_sturdy_face", "direction": "up"},
            "allowed_search_condition": through, "max_steps": steps}]
    if offset: out.append({"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": offset})
    return out


def patch(provider, survive, tries=32, xz=6, y=3, on=None):
    """Plants scattered around a spot: each where there is air, the plant can grow, and (optionally) on these blocks."""
    preds = [AIR, {"type": "minecraft:would_survive", "state": survive}]
    if on:
        preds.append({"type": "minecraft:matching_blocks", "offset": [0, -1, 0], "blocks": on})
    return {"type": "minecraft:random_patch", "config": {
        "tries": tries, "xz_spread": xz, "y_spread": y,
        "feature": {"feature": {"type": "minecraft:simple_block", "config": {"to_place": provider}},
                    "placement": [{"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:all_of", "predicates": preds}}]}}}


def hanging_column(body, tip, lo, hi):
    """A vine hanging from a ceiling: lo to hi blocks of body, then its tip (a block id, or a state provider)."""
    return {"type": "minecraft:block_column", "config": {
        "direction": "down", "allowed_placement": AIR, "prioritize_tip": True,
        "layers": [{"height": uniform(lo, hi), "provider": simple(body)},
                   {"height": 1, "provider": tip if isinstance(tip, dict) else simple(tip)}]}}


def hanging_patch(column, tries=8, xz=4, y=2):
    """Several vines hanging close together: a patch of block columns, each started right under a solid ceiling.
    Clusters light the caves in pockets with dim gaps between them, instead of an even scatter of single vines. Each
    try that lands in the air looks up for the ceiling over it, so the clusters form under rough ceilings too."""
    return {"type": "minecraft:random_patch", "config": {
        "tries": tries, "xz_spread": xz, "y_spread": y,
        "feature": {"feature": column, "placement": ceiling(steps=2 * y + 2)}}}


# Wisteria strands ending, one in seven, in a lantern pod, so the pods glow inside the curtains (lantern_lake.jpg).
LILAC_TIPS = mix((simple(f"{MOD}:wisteria"), 6), (simple(f"{MOD}:lantern_pod"), 1))
CREAM_TIPS = mix((simple(f"{MOD}:pale_wisteria"), 6), (simple(f"{MOD}:lantern_pod"), 1))


def berry_vines(lo, hi, berries):
    """Cave vines hanging from a ceiling, lo to hi blocks long, with glow berries on about this share of them (vanilla's
    share is a fifth). The tips are grown out, as vanilla's are, so the vines stay the length they were placed."""
    plain, ripe = round((1 - berries) * 20), round(berries * 20)
    def pick(name, **props):
        return {"type": "minecraft:weighted_state_provider", "entries": [
            {"data": state(name, berries=False, **props), "weight": plain}, {"data": state(name, berries=True, **props), "weight": ripe}]}
    tip = {"type": "minecraft:randomized_int_state_provider", "property": "age", "values": uniform(23, 25), "source": pick("minecraft:cave_vines", age=0)}
    return {"type": "minecraft:block_column", "config": {
        "direction": "down", "allowed_placement": AIR, "prioritize_tip": True,
        "layers": [{"height": uniform(lo, hi), "provider": pick("minecraft:cave_vines_plant")}, {"height": 1, "provider": tip}]}}


def attach(provider, faces, tries, on, radius=6, height=6, in_water=False):
    return {"type": f"{MOD}:surface_attach", "config": {"state": provider, "faces": faces, "tries": tries, "radius": radius,
                                                      "height": height, "on": on, "in_water": in_water}}


def fall(fluid, min_drop, width=(1, 1), search=10, rock=f"#{MOD}:cave_rock"):
    return {"type": f"{MOD}:fluid_fall", "config": {"fluid": state(fluid, level=0), "rock": rock, "min_drop": min_drop,
                                                  "search": search, "width": uniform(*width) if width[0] != width[1] else width[0]}}


def spike(body, tip, length, radius, floor_chance, point=None):
    c = {"body": body, "tip": tip, "length": uniform(*length), "radius": funiform(*radius), "floor_chance": floor_chance}
    if point: c["point"] = point
    return {"type": f"{MOD}:giant_spike", "config": c}


def pools(rim, bed, radius, edge=None, edge_chance=0.3, overflow=0.0, lava=False, inner=None, inner_chance=0.0):
    c = {"rim": rim, "bed": bed, "radius": radius, "edge_chance": edge_chance, "overflow_chance": overflow}
    if edge: c["edge"] = edge
    if inner:
        c["inner"] = inner
        c["inner_chance"] = inner_chance
    if lava: c["lava"] = True
    return {"type": f"{MOD}:terrace_pools", "config": c}


def ref(cf): return cf  # a vanilla configured feature, by id


# ----------------------------------------------------------------------------------------------- features
# (name, step, biomes, configured feature (inline dict or vanilla id), placement). The list order is the master order.
FEATURES = []


SET_PIECES = {"silver_fern_rift", "ivory_root_cavern"}


def feature(name, step, biomes, configured, placement):
    # In the upper layer, aim the tries of a set piece's own features at the set piece, and the Rootvaults' at the
    # grottos (layer_range's focus); features shared between them keep both.
    for p in placement:
        if isinstance(p, dict) and p.get("type") == f"{MOD}:layer_range" and p.get("layer") == "upper" and "focus" not in p:
            if set(biomes) <= SET_PIECES:
                p["focus"] = "pieces"
            elif set(biomes) <= ENTRANCE_BIOMES:
                p["focus"] = "grottos"
    # The game's count placement takes at most 256: a larger count becomes a count of k copies of each try.
    for i, p in enumerate(list(placement)):
        if isinstance(p, dict) and p.get("type") == "minecraft:count" and isinstance(p.get("count"), int) and p["count"] > 256:
            k = -(-p["count"] // 256)
            placement[i:i + 1] = [count(round(p["count"] / k)), count(k)]
            break
    FEATURES.append((name, step, biomes, configured, placement))


def build_features():
    FEATURES.clear()
    rv = ["rootvaults"]
    up = "upper"
    PLANT_FLOORS = ["minecraft:moss_block", "minecraft:grass_block", "minecraft:rooted_dirt", "minecraft:dirt", "minecraft:moss_carpet"]

    # ---- local modifications: shafts, spikes, pools and columns shape the space before anything grows in it
    # The Rootvaults' skylights: planned per grotto (0 to 2 over a root hall, 0 or 1 over a teal grotto; ravines are
    # open to the sky by their shape) and cut here through at most 24 blocks of dry rock, between the trees. The oasis
    # hollows' one skylight over the pool is cut the same way, through the sandstone roof and the dune over it.
    feature("rootvaults/skylights", "local_modifications", rv + ["frozen_rootvaults", "oasis_hollow"],
            {"type": f"{MOD}:daylight_shaft", "config": {"radius": funiform(1.5, 3.0), "max_depth": 24}},
            [{"type": f"{MOD}:rootvaults_openings"}] + ceiling() + [BIOME])
    feature("rootvaults/pools", "local_modifications", rv,
            pools(mix((LIMESTONE, 3), (simple("minecraft:moss_block"), 1)), mix((simple("minecraft:clay"), 2), (simple("minecraft:gravel"), 1)), 6,
                  inner=simple(f"{MOD}:pool_pearls"), inner_chance=0.3),
            [count(2), SQUARE, layer(up)] + floor() + [looks(0, 1), BIOME])
    # A pale bed of calcite and limestone under the lake, as in its picture: the biome's cells end at the water, so the
    # rock under it was often the pack's deepslate.
    feature("ivory_root_cavern/lake_bed", "local_modifications", ["ivory_root_cavern"],
            {"type": "minecraft:disk", "config": {
                "state_provider": {"fallback": mix((simple("minecraft:calcite"), 3), (LIMESTONE, 2)), "rules": []},
                "target": {"type": "minecraft:matching_blocks", "blocks": [
                    "minecraft:deepslate", "minecraft:cobbled_deepslate", "minecraft:stone", "minecraft:tuff", "minecraft:gravel",
                    "minecraft:andesite", "minecraft:diorite", "minecraft:granite"]},
                "radius": uniform(3, 6), "half_height": 2}},
            [count(24), SQUARE, layer(up)] + floor(through=AIR_OR_WATER) + [IN_WATER, BIOME])
    feature("ivory_root_cavern/stalactites", "local_modifications", ["ivory_root_cavern"],
            spike(mix((LIMESTONE, 3), (simple("minecraft:calcite"), 2)), simple("minecraft:dripstone_block"), (5, 14), (1.4, 3.2), 0.35,
                  point=state("minecraft:pointed_dripstone", thickness="tip", vertical_direction="down")),
            [count(3), SQUARE, layer(up)] + ceiling(offset=0) + [BIOME])
    # A stair of pools on the Ember Gardens' treads, rims lit by anemones, beds glowing turquoise with pool pearls
    # (ember_gardens.jpg).
    feature("ember_gardens/pools", "local_modifications", ["ember_gardens"],
            pools(mix((LIMESTONE, 3), (simple("minecraft:calcite"), 1)),
                  mix((simple("minecraft:calcite"), 6), (LIMESTONE, 3)), 10,
                  edge=mix((simple("explorationremix:lavender_coral"), 2), (simple(f"{MOD}:pale_coral"), 3),
                           (simple("explorationremix:pale_coral_fan"), 2), (simple(f"{MOD}:ember_anemone"), 5)), edge_chance=0.55,
                  overflow=0.08, inner=simple(f"{MOD}:pool_pearls"), inner_chance=0.4),
            [count(36), SQUARE, layer("deep_gardens", above_water=True)] + floor() + [BIOME])
    # Cream stalactites from the pale parts of the Deep Gardens' ceilings, after the pools so no stalagmite rises from
    # their water.
    feature("ember_gardens/stalactites", "local_modifications", ["ember_gardens"],
            spike(mix((LIMESTONE, 3), (simple("minecraft:calcite"), 1)), simple("minecraft:calcite"), (4, 12), (1.2, 2.4), 0.1),
            [count(32), SQUARE, layer("deep_gardens")] + ceiling(offset=0) + [PALE_CEILING, BIOME])
    feature("lantern_lake/stalactites", "local_modifications", ["lantern_lake"],
            spike(mix((LIMESTONE, 3), (simple("minecraft:calcite"), 1)), simple("minecraft:calcite"), (3, 9), (1.0, 1.8), 0.15),
            [count(16), SQUARE, layer("deep_gardens")] + ceiling(offset=0) + [PALE_CEILING, BIOME])
    # Red spikes of mixed sizes, three or four to a spring (rust_springs.jpg).
    feature("rust_springs/stalactites", "local_modifications", ["rust_springs"],
            spike(mix((CRIMSITE, 4), (simple("minecraft:granite"), 1)), mix((CRIMSITE, 2), (simple("minecraft:red_terracotta"), 1)), (5, 13), (1.2, 2.8), 0.4),
            [count(28), SQUARE, layer("deep_gardens")] + ceiling(offset=0) + [BIOME])
    feature("rust_springs/pools", "local_modifications", ["rust_springs"],
            pools(mix((simple("minecraft:calcite"), 3), (LIMESTONE, 2)),
                  mix((LIMESTONE, 5), (simple("minecraft:calcite"), 3)), 11, overflow=0.12),
            [count(20), SQUARE, layer("deep_gardens", above_water=True)] + floor() + [BIOME])
    # Small lava pools flush with the floor, in about every second or third gallery (basalt_galleries.jpg).
    feature("basalt_galleries/lava_pools", "local_modifications", ["basalt_galleries"],
            pools(mix((simple("minecraft:blackstone"), 2), (simple("minecraft:basalt", axis="y"), 3)), simple("minecraft:magma_block"), 4, lava=True),
            [count(32), SQUARE, layer("basalt_galleries", above_water=True)] + floor() + [BIOME])
    feature("basalt_galleries/columns", "local_modifications", ["basalt_galleries"],
            {"type": f"{MOD}:column_cluster", "config": {
                "block": mix((simple("minecraft:basalt", axis="y"), 4), (simple("minecraft:polished_basalt", axis="y"), 1)),
                "radius": 6, "count": 26, "height": uniform(3, 16)}},
            [count(8), SQUARE, layer("basalt_galleries", above_water=True)] + floor() + [BIOME])
    # Columns hanging from the vaults, longest in the middle of a cluster; the longest under the great hall's vault, the
    # chimney and the tallest galleries, where the floor is more than 16 below.
    feature("basalt_galleries/hanging_columns", "local_modifications", ["basalt_galleries"],
            {"type": f"{MOD}:column_cluster", "config": {
                "block": mix((simple("minecraft:basalt", axis="y"), 3), (simple("minecraft:polished_basalt", axis="y"), 1)),
                "radius": 4, "count": 20, "height": uniform(3, 10), "hang_chance": 1.0}},
            [count(12), SQUARE, layer("basalt_galleries", above_water=True)] + ceiling() + [BIOME])
    tall = {"type": "minecraft:block_predicate_filter", "predicate": dict(AIR, offset=[0, -16, 0])}
    feature("basalt_galleries/great_hanging_columns", "local_modifications", ["basalt_galleries"],
            {"type": f"{MOD}:column_cluster", "config": {
                "block": mix((simple("minecraft:basalt", axis="y"), 3), (simple("minecraft:polished_basalt", axis="y"), 1)),
                "radius": 6, "count": 34, "height": uniform(8, 18), "hang_chance": 1.0}},
            [count(6), SQUARE, layer("basalt_galleries", above_water=True)] + ceiling() + [tall, BIOME])
    # A still pool in the tube hubs, between obsidian and pebbly basalt, its bed glinting with crying obsidian.
    feature("obsidian_tubes/still_pool", "local_modifications", ["obsidian_tubes"],
            pools(mix((simple("minecraft:obsidian"), 3), (simple("minecraft:basalt", axis="y"), 1)),
                  mix((simple("minecraft:obsidian"), 5), (simple("minecraft:crying_obsidian"), 1)), 3),
            [count(64), SQUARE, layer("basalt_galleries", above_water=True)] + floor() + [BIOME])
    # Short basalt columns hanging only from the basalt patches of the tubes' vaults.
    under_basalt = {"type": "minecraft:block_predicate_filter",
                    "predicate": {"type": "minecraft:matching_blocks", "offset": [0, 1, 0], "blocks": "minecraft:basalt"}}
    feature("obsidian_tubes/vault_columns", "local_modifications", ["obsidian_tubes"],
            {"type": f"{MOD}:column_cluster", "config": {
                "block": mix((simple("minecraft:basalt", axis="y"), 3), (simple("minecraft:polished_basalt", axis="y"), 1)),
                "radius": 3, "count": 18, "height": uniform(1, 5), "hang_chance": 1.0}},
            [count(96), SQUARE, layer("basalt_galleries", above_water=True)] + ceiling() + [under_basalt, BIOME])

    # ---- underground decoration: moss, wall growth, crystals, machines
    feature("rootvaults/moss_floor", "underground_decoration", rv, "minecraft:moss_patch",
            [count(16), SQUARE, layer(up)] + floor() + [looks(0, 1, 2), BIOME])
    feature("rootvaults/glow_lichen", "underground_decoration", rv + ["silver_fern_rift", "sinkhole"],
            {"type": "minecraft:multiface_growth", "config": {
                "block": "minecraft:glow_lichen", "search_range": 20, "can_place_on_floor": True, "can_place_on_ceiling": True,
                "can_place_on_wall": True, "chance_of_spreading": 0.6,
                "can_be_placed_on": ["minecraft:stone", "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff",
                                     "minecraft:calcite", "minecraft:deepslate", "minecraft:cobbled_deepslate", "minecraft:dripstone_block",
                                     "minecraft:moss_block"]}},
            [count(50), SQUARE, layer(up), BIOME])
    feature("rootvaults/moss_ceiling", "underground_decoration", rv, "minecraft:moss_patch_ceiling",
            [count(16), SQUARE, layer(up)] + ceiling() + [looks(1, 2), BIOME])
    # Shelf fungi scattered over the walls, and in stacked tiers (ember_gardens.jpg).
    feature("ember_gardens/shelf_fungi", "underground_decoration", ["ember_gardens"],
            attach(simple("explorationremix:orange_shelf_fungus"), "walls", 96, f"#{MOD}:cave_rock", radius=8, height=8),
            [count(12), SQUARE, layer("deep_gardens", above_water=True), BIOME])
    feature("ember_gardens/shelf_stacks", "underground_decoration", ["ember_gardens"],
            attach(simple("explorationremix:orange_shelf_fungus"), "walls", 48, f"#{MOD}:cave_rock", radius=4, height=3),
            [count(12), SQUARE, layer("deep_gardens", above_water=True), BIOME])
    feature("rust_springs/machines", "underground_decoration", ["rust_springs"],
            {"type": f"{MOD}:broken_machine", "config": {}},
            [count(6), SQUARE, layer("deep_gardens", above_water=True)] + floor(through=AIR_OR_WATER) + [BIOME])
    # The rift's crystals grow out of the blue asurine of its walls and ledges, not its floors (deep_blue_rift.jpg).
    feature("deep_blue_rift/crystals", "underground_decoration", ["deep_blue_rift"],
            attach(mix((simple("explorationremix:asurine_crystal"), 1), (simple("explorationremix:asurine_bud"), 2)), "walls_ceiling", 56,
                   f"#{MOD}:asurine_lining", radius=7, height=7),
            [count(14), SQUARE, layer("blue_reaches", above_water=True), BIOME])
    # The geodes' crystals jut from the lining of the walls and hang from the ceilings, some in tight clusters, and about
    # a fifth stand on the floor ledges (asurine_geodes.jpg).
    feature("asurine_geodes/crystals", "underground_decoration", ["asurine_geodes"],
            attach(mix((simple("explorationremix:asurine_crystal"), 2), (simple("explorationremix:asurine_bud"), 3)), "walls_ceiling", 160,
                   f"#{MOD}:asurine_lining", radius=8, height=8),
            [count(14), SQUARE, layer("blue_reaches", above_water=True), BIOME])
    feature("asurine_geodes/crystal_clusters", "underground_decoration", ["asurine_geodes"],
            attach(mix((simple("explorationremix:asurine_crystal"), 3), (simple("explorationremix:asurine_bud"), 2)), "walls_ceiling", 14,
                   f"#{MOD}:asurine_lining", radius=2, height=2),
            [count(10), SQUARE, layer("blue_reaches", above_water=True)] + ceiling(offset=0) + [BIOME])
    feature("asurine_geodes/floor_crystals", "underground_decoration", ["asurine_geodes"],
            attach(mix((simple("explorationremix:asurine_crystal"), 1), (simple("explorationremix:asurine_bud"), 2)), "floor", 40,
                   f"#{MOD}:asurine_lining", radius=6, height=2),
            [count(20), SQUARE, layer("blue_reaches", above_water=True)] + floor() + [BIOME])
    # Under a shader the lining is black between the crystals; a crust of glowing facets over about a fifth of it keeps
    # it blue. It comes after the crystals, which get first pick of the lining.
    feature("asurine_geodes/crust", "underground_decoration", ["asurine_geodes"],
            attach(simple(f"{MOD}:asurine_crust"), "walls_ceiling", 96, f"#{MOD}:asurine_lining", radius=7, height=7),
            [count(120), SQUARE, layer("blue_reaches", above_water=True), BIOME])

    # ---- vegetation rework, part A: sheets of ivy and lichen, wall ferns and brackets, before the floor and ceiling
    # plants take their spots. Ivy covers walls and ceilings but never floors, where a climbable sheet slows walking.
    feature("rootvaults/grotto_ivy", "underground_decoration", rv,
            attach(simple(f"{MOD}:grotto_ivy"), "walls_ceiling", 48, f"#{MOD}:cave_rock", radius=6, height=6),
            [count(24), SQUARE, layer(up), looks(1, 2), BIOME])
    feature("ivory_root_cavern/grotto_ivy", "underground_decoration", ["ivory_root_cavern"],
            attach(simple(f"{MOD}:grotto_ivy"), "walls_ceiling", 32, f"#{MOD}:cave_rock", radius=5, height=5),
            [count(9), SQUARE, layer(up), BIOME])
    # Hanging ferns down the walls, where their fronds fall free (the picture's fronds draping the ledges).
    feature("silver_fern_rift/wall_ferns", "underground_decoration", ["silver_fern_rift"],
            attach(simple(f"{MOD}:silver_wall_fern"), "walls_hanging", 32, f"#{MOD}:cave_rock", radius=6, height=6),
            [count(10), SQUARE, layer(up), BIOME])
    feature("ivory_root_cavern/brackets", "underground_decoration", ["ivory_root_cavern"],
            attach(simple(f"{MOD}:ivory_bracket"), "walls", 64, f"#{MOD}:cave_rock", radius=7, height=8),
            [count(3), SQUARE, layer(up), BIOME])
    # The rift's blue walls are its asurine, near black under a shader: lichen specks and a crust of facets over about
    # one asurine face in six turn them blue in patches, and leave the dark rock bare.
    feature("deep_blue_rift/lichen", "underground_decoration", ["deep_blue_rift"],
            attach(simple(f"{MOD}:blue_lichen"), "walls_ceiling", 48, f"#{MOD}:asurine_lining", radius=6, height=6),
            [count(128), SQUARE, layer("blue_reaches", above_water=True), BIOME])
    feature("deep_blue_rift/crust", "underground_decoration", ["deep_blue_rift"],
            attach(simple(f"{MOD}:asurine_crust"), "walls", 48, f"#{MOD}:asurine_lining", radius=6, height=6),
            [count(40), SQUARE, layer("blue_reaches", above_water=True), BIOME])

    # ---- vegetation rework, part B: brown mushroom shelves up the Ember Gardens' walls and mushroom flesh in their
    # blackstone ceilings (vanilla blocks; plants grow on the shelves, vines hang from the caps), amber crystals in the
    # rock, a smouldering crust on the Basalt Galleries' walls. The caps only swap rock for rock, so they can't block a
    # way; the shelves keep off it.
    feature("ember_gardens/mushroom_shelves", "underground_decoration", ["ember_gardens"],
            {"type": f"{MOD}:mushroom_shelf", "config": {"block": simple("minecraft:brown_mushroom_block", down=False),
                                                       "tiers": uniform(2, 4), "spacing": uniform(2, 3), "depth": uniform(2, 4),
                                                       "half_width": uniform(1, 3)}},
            [count(20), SQUARE, layer("deep_gardens", above_water=True), BIOME])
    feature("ember_gardens/mushroom_caps", "underground_decoration", ["ember_gardens"],
            {"type": "minecraft:netherrack_replace_blobs", "config": {"target": state("minecraft:blackstone"),
                                                          "state": state("minecraft:brown_mushroom_block"), "radius": uniform(2, 3)}},
            [count(6), SQUARE, layer("deep_gardens")] + ceiling(offset=0) +
            [{"type": "minecraft:block_predicate_filter", "predicate": {"type": "minecraft:matching_blocks", "blocks": "minecraft:blackstone"}}, BIOME])
    feature("ember_gardens/amber_shards", "underground_decoration", ["ember_gardens"],
            attach(simple(f"{MOD}:amber_shard"), "any", 24, f"#{MOD}:cave_rock", radius=6, height=6),
            [count(6), SQUARE, layer("deep_gardens", above_water=True), BIOME])
    feature("basalt_galleries/smoulder_lichen", "underground_decoration", ["basalt_galleries"],
            attach(simple(f"{MOD}:smoulder_lichen"), "walls_ceiling", 32, f"#{MOD}:basalt_rock", radius=6, height=8),
            [count(6), SQUARE, layer("basalt_galleries", above_water=True), BIOME])
    feature("basalt_galleries/amber_shards", "underground_decoration", ["basalt_galleries"],
            attach(simple(f"{MOD}:amber_shard"), "any", 16, f"#{MOD}:basalt_rock", radius=6, height=6),
            [count(4), SQUARE, layer("basalt_galleries", above_water=True), BIOME])

    # ---- fluid springs: waterfalls and lava falls
    feature("rootvaults/waterfalls", "fluid_springs", rv + ["frozen_rootvaults", "sinkhole", "oasis_hollow"], fall("minecraft:water", 6, (1, 3)),
            [count(10), SQUARE, layer(up), BIOME])
    feature("silver_fern_rift/waterfalls", "fluid_springs", ["silver_fern_rift"], fall("minecraft:water", 8, (1, 3)),
            [count(8), SQUARE, layer(up), BIOME])
    feature("ivory_root_cavern/waterfalls", "fluid_springs", ["ivory_root_cavern"], fall("minecraft:water", 10, (2, 3)),
            [count(3), SQUARE, layer(up), BIOME])
    feature("deep_blue_rift/waterfalls", "fluid_springs", ["deep_blue_rift"], fall("minecraft:water", 16, (1, 3)),
            [count(8), SQUARE, layer("blue_reaches", above_water=True), BIOME])
    feature("asurine_geodes/meltwater", "fluid_springs", ["asurine_geodes"], fall("minecraft:water", 0, (1, 1), search=12),
            [count(1), SQUARE, layer("blue_reaches", above_water=True)] + floor() + [BIOME])
    # Lava falls lighting the galleries (basalt_galleries.jpg), each from 3 to 6 blocks under a vault; every one ends in
    # lava, in a pool or in its own sump, so none runs onto a walk.
    feature("basalt_galleries/lava_falls", "fluid_springs", ["basalt_galleries"],
            fall("minecraft:lava", 6, (1, 2), rock=f"#{MOD}:basalt_rock"),
            [count(80), SQUARE, layer("basalt_galleries", above_water=True)] + ceiling(offset=uniform(-6, -3)) + [BIOME])

    # ---- vegetal decoration: roots, vines and plants
    feature("rootvaults/hanging_roots", "vegetal_decoration", rv + ["frozen_rootvaults"],
            attach(simple("minecraft:hanging_roots"), "ceiling", 32, ["minecraft:rooted_dirt", "minecraft:dirt", "minecraft:moss_block"], radius=6, height=4),
            [count(14), SQUARE, layer(up)] + ceiling() + [looks(0), BIOME])
    feature("rootvaults/lantern_roots", "vegetal_decoration", rv,
            hanging_column("explorationremix:lantern_roots", "explorationremix:lantern_roots", 0, 4),
            [count(20), SQUARE, layer(up)] + ceiling() + [looks(0), BIOME])
    feature("rootvaults/cave_vines", "vegetal_decoration", rv, berry_vines(1, 7, 0.25),
            [count(40), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("silver_fern_rift/cave_vines", "vegetal_decoration", ["silver_fern_rift"], berry_vines(1, 8, 0.5),
            [count(16), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("rootvaults/ferns", "vegetal_decoration", rv,
            patch({"type": "minecraft:weighted_state_provider", "entries": [
                {"data": state("minecraft:fern"), "weight": 3}, {"data": state("minecraft:grass"), "weight": 2},
                {"data": state("minecraft:large_fern"), "weight": 1}]},
                state("minecraft:fern"), tries=32, on=PLANT_FLOORS),
            [count(40), SQUARE, layer(up)] + floor() + [BIOME])
    feature("rootvaults/white_glow_flowers", "vegetal_decoration", rv,
            patch(simple("explorationremix:white_glow_flower"), state("explorationremix:white_glow_flower"), tries=20, xz=5, on=PLANT_FLOORS),
            [count(10), SQUARE, layer(up)] + floor() + [looks(1), BIOME])
    feature("rootvaults/white_glow_flowers_sparse", "vegetal_decoration", rv,
            patch(simple("explorationremix:white_glow_flower"), state("explorationremix:white_glow_flower"), tries=12, xz=4, on=PLANT_FLOORS),
            [count(10), SQUARE, layer(up)] + floor() + [looks(0, 2), BIOME])
    feature("silver_fern_rift/silver_ferns", "vegetal_decoration", ["silver_fern_rift"],
            patch(simple("explorationremix:silver_fern"), state("explorationremix:silver_fern"), tries=40, xz=5, y=4),
            [count(40), SQUARE, layer(up)] + floor() + [BIOME])
    feature("ivory_root_cavern/giant_lantern_fungi", "vegetal_decoration", ["ivory_root_cavern"],
            {"type": f"{MOD}:giant_fungus", "config": {
                "stem": state("minecraft:mushroom_stem"), "cap": state("minecraft:mushroom_stem", down=False),
                "glow": state("minecraft:shroomlight"), "roots": state(f"{MOD}:lantern_roots"),
                "height": uniform(2, 5), "radius": uniform(2, 3), "root_length": uniform(1, 4)}},
            [count(2), SQUARE, layer(up)] + floor() + [BIOME])
    feature("ivory_root_cavern/lantern_fungi", "vegetal_decoration", ["ivory_root_cavern"],
            patch(simple("explorationremix:lantern_fungus"), state("explorationremix:lantern_fungus"), tries=12, xz=5, y=3),
            [count(5), SQUARE, layer(up)] + floor() + [BIOME])
    feature("ivory_root_cavern/lantern_roots", "vegetal_decoration", ["ivory_root_cavern"],
            attach(simple("explorationremix:lantern_roots"), "ceiling", 32, f"#{MOD}:cave_rock", radius=6, height=3),
            [count(3), SQUARE, layer(up)] + ceiling() + [BIOME])
    # Glow lichen on the lake bed lights the Ivory Root Cavern's lake from below, as in its picture; with a shader the
    # water's surface otherwise mirrors the dark cave.
    feature("ivory_root_cavern/lake_lichen", "vegetal_decoration", ["ivory_root_cavern"],
            {"type": "minecraft:multiface_growth", "config": {
                "block": "minecraft:glow_lichen", "search_range": 4, "can_place_on_floor": True, "can_place_on_ceiling": False,
                "can_place_on_wall": True, "chance_of_spreading": 0.8, "can_be_placed_on": f"#{MOD}:cave_rock"}},
            [count(64), SQUARE, layer(up)] + floor(through=AIR_OR_WATER) + [IN_WATER, BIOME])
    # The lichen alone left the lake dark under the shader; pool pearls over its bed turn it turquoise from below.
    feature("ivory_root_cavern/pool_pearls", "vegetal_decoration", ["ivory_root_cavern"],
            attach(simple(f"{MOD}:pool_pearls"), "floor", 64, f"#{MOD}:cave_rock", radius=6, height=2, in_water=True),
            [count(32), SQUARE, layer(up)] + floor(through=AIR_OR_WATER) + [IN_WATER, BIOME])
    # Pool pearls light the root halls' and teal grottos' streams and pools turquoise, as in their pictures.
    feature("rootvaults/pool_pearls", "vegetal_decoration", rv,
            attach(simple(f"{MOD}:pool_pearls"), "floor", 32, f"#{MOD}:grotto_bed", radius=5, height=2, in_water=True),
            [count(32), SQUARE, layer(up)] + floor(through=AIR_OR_WATER) + [IN_WATER, looks(0, 1, cold=False), BIOME])
    feature("ivory_root_cavern/moss", "vegetal_decoration", ["ivory_root_cavern"],
            patch(simple("minecraft:moss_carpet"), state("minecraft:moss_carpet"), tries=24, xz=4, y=2),
            [count(10), SQUARE, layer(up)] + floor() + [BIOME])
    feature("lantern_lake/wisteria", "vegetal_decoration", ["lantern_lake"],
            hanging_patch(hanging_column("explorationremix:wisteria_plant", LILAC_TIPS, 1, 6), tries=10, xz=5, y=2),
            [scaled(64, GARDENS_PLANTS), SQUARE, layer("deep_gardens")] + ceiling() + [BIOME])
    feature("lantern_lake/wisteria_pods", "vegetal_decoration", ["lantern_lake"],
            hanging_column("explorationremix:wisteria_plant", "explorationremix:lantern_pod", 1, 4),
            [scaled(20, GARDENS_PLANTS), SQUARE, layer("deep_gardens")] + ceiling() + [BIOME])
    feature("lantern_lake/lantern_pods", "vegetal_decoration", ["lantern_lake"],
            patch(simple("explorationremix:lantern_pod"), state("explorationremix:lantern_pod"), tries=20, xz=5, y=6),
            [scaled(10, GARDENS_PLANTS), SQUARE, layer("deep_gardens")] + ceiling() + [BIOME])
    feature("lantern_lake/flowers", "vegetal_decoration", ["lantern_lake"],
            patch(mix((simple("explorationremix:lavender_flower"), 3), (simple("explorationremix:gold_flower"), 2)),
                  state("explorationremix:lavender_flower"), tries=56, xz=6, y=3, on=f"#{MOD}:garden_soil"),
            [scaled(48, GARDENS_PLANTS * LAKE_FLOOR), SQUARE, layer("deep_gardens", above_water=True)] + floor() + [BIOME])
    feature("ember_gardens/ember_vines", "vegetal_decoration", ["ember_gardens"],
            hanging_patch(hanging_column("explorationremix:ember_vines_plant", "explorationremix:ember_vines", 1, 10), tries=10, xz=5, y=2),
            [scaled(56, GARDENS_PLANTS * EMBER_CEILING), SQUARE, layer("deep_gardens")] + ceiling() + [BIOME])
    feature("ember_gardens/coral", "vegetal_decoration", ["ember_gardens"],
            patch(mix((simple("explorationremix:lavender_coral"), 2), (simple(f"{MOD}:pale_coral"), 2), (simple("explorationremix:pale_coral_fan"), 2)),
                  state("explorationremix:lavender_coral"), tries=32, xz=5, y=2, on=f"#{MOD}:garden_soil"),
            [scaled(56, GARDENS_PLANTS * EMBER_FLOOR), SQUARE, layer("deep_gardens", above_water=True)] + floor() + [BIOME])
    # The rift's strands hang in groups, with dark gaps between them (deep_blue_rift.jpg).
    feature("deep_blue_rift/glow_vines", "vegetal_decoration", ["deep_blue_rift"],
            hanging_patch(hanging_column("explorationremix:blue_glow_vines_plant", "explorationremix:blue_glow_vines", 2, 10), tries=6, xz=3, y=2),
            [count(32), SQUARE, layer("blue_reaches")] + ceiling() + [BIOME])
    feature("deep_blue_rift/mushrooms", "vegetal_decoration", ["deep_blue_rift"],
            patch(simple("explorationremix:blue_glow_mushroom"), state("explorationremix:blue_glow_mushroom"), tries=10, xz=3, y=2),
            [count(40), SQUARE, layer("blue_reaches", above_water=True)] + floor() + [BIOME])

    # ---- vegetation rework, part A: vines in clusters, floor plants. The new vines hang in patches (hanging_patch), so
    # their light comes in pockets with dim gaps between them.
    feature("rootvaults/amber_bulb_vines", "vegetal_decoration", rv,
            hanging_patch(hanging_column(f"{MOD}:amber_bulb_vine_plant", f"{MOD}:amber_bulb_vine", 1, 6), tries=8, xz=4, y=2),
            [count(5), SQUARE, layer(up)] + ceiling() + [looks(0, 1), BIOME])
    feature("rootvaults/weeping_ivy", "vegetal_decoration", rv,
            hanging_patch(hanging_column(f"{MOD}:weeping_ivy_plant", f"{MOD}:weeping_ivy", 2, 12), tries=10, xz=5, y=3),
            [count(18), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("rootvaults/starmoss", "vegetal_decoration", rv,
            patch(simple(f"{MOD}:starmoss"), state(f"{MOD}:starmoss"), tries=32, xz=5, y=2),
            [count(10), SQUARE, layer(up)] + floor() + [looks(1), BIOME])
    feature("rootvaults/starmoss_sparse", "vegetal_decoration", rv,
            patch(simple(f"{MOD}:starmoss"), state(f"{MOD}:starmoss"), tries=16, xz=4, y=2),
            [count(4), SQUARE, layer(up)] + floor() + [looks(0), BIOME])
    feature("rootvaults/azaleas", "vegetal_decoration", rv,
            patch(mix((simple("minecraft:azalea"), 3), (simple("minecraft:flowering_azalea"), 1)), state("minecraft:azalea"), tries=12, xz=5, y=2,
                  on=PLANT_FLOORS),
            [count(10), SQUARE, layer(up)] + floor() + [looks(2), BIOME])
    feature("silver_fern_rift/amber_bulb_vines", "vegetal_decoration", ["silver_fern_rift"],
            hanging_patch(hanging_column(f"{MOD}:amber_bulb_vine_plant", f"{MOD}:amber_bulb_vine", 1, 6), tries=8, xz=4, y=2),
            [count(4), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("silver_fern_rift/fronds", "vegetal_decoration", ["silver_fern_rift"],
            hanging_patch(hanging_column(f"{MOD}:silver_frond_plant", f"{MOD}:silver_frond", 1, 5), tries=8, xz=5, y=3),
            [count(14), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("silver_fern_rift/tall_ferns", "vegetal_decoration", ["silver_fern_rift"],
            patch(simple(f"{MOD}:tall_silver_fern"), state(f"{MOD}:tall_silver_fern"), tries=24, xz=6, y=3),
            [count(16), SQUARE, layer(up)] + floor() + [BIOME])
    feature("ivory_root_cavern/gold_threads", "vegetal_decoration", ["ivory_root_cavern"],
            hanging_patch(hanging_column(f"{MOD}:gold_threads_plant", f"{MOD}:gold_threads", 2, 7), tries=3, xz=4, y=2),
            [count(1), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("ivory_root_cavern/sage", "vegetal_decoration", ["ivory_root_cavern"],
            patch(simple(f"{MOD}:sage_bush"), state(f"{MOD}:sage_bush"), tries=16, xz=5, y=2),
            [count(14), SQUARE, layer(up)] + floor() + [BIOME])
    feature("deep_blue_rift/glowbells", "vegetal_decoration", ["deep_blue_rift"],
            patch(simple(f"{MOD}:glowbell"), state(f"{MOD}:glowbell"), tries=12, xz=3, y=2),
            [count(18), SQUARE, layer("blue_reaches", above_water=True)] + floor() + [BIOME])
    feature("deep_blue_rift/pagodas", "vegetal_decoration", ["deep_blue_rift"],
            patch(simple(f"{MOD}:pagoda_fungus"), state(f"{MOD}:pagoda_fungus"), tries=8, xz=4, y=2),
            [count(20), SQUARE, layer("blue_reaches", above_water=True)] + floor() + [BIOME])
    # The stream at the bottom of the rift glows blue from its bed (deep_blue_rift.jpg); under a shader water is dark
    # unless something glows in it. Each try starts just above the layer's water table, looks down through the water
    # for the bed, and keeps only spots in the water.
    feature("deep_blue_rift/stream_glow", "vegetal_decoration", ["deep_blue_rift"],
            attach(mix((simple(f"{MOD}:blue_lichen"), 4), (simple(f"{MOD}:asurine_bud"), 3), (simple(f"{MOD}:asurine_crystal"), 1)),
                   "floor", 40, f"#{MOD}:blue_rift_walls", radius=5, height=2, in_water=True),
            [count(16), SQUARE,
             {"type": "minecraft:height_range", "height": {"type": f"{MOD}:layer_height", "layer": "blue_reaches", "above_fluid": 0, "fallback": -258}}]
            + floor(steps=8, through=AIR_OR_WATER) + [IN_WATER, BIOME])

    # ---- vegetation rework, part B: the Deep Gardens grow no green, and their floor plants grow only from limestone,
    # calcite and sand (garden_soil) and the mushroom shelves; under the lake they grow from its bed (lake_bed). The
    # Ember Gardens (the hunting ground) are the most overgrown, Lantern Lake (the route) has some growth, the Rust
    # Springs (a refuge) none. The Basalt Galleries get a little heat-loving growth; the Obsidian Tubes none.
    gardens_floor = [layer("deep_gardens", above_water=True)] + floor() + [BIOME]
    gardens_ceiling = [layer("deep_gardens")] + ceiling() + [BIOME]
    lake_bed = [layer("deep_gardens")] + floor(through=AIR_OR_WATER) + [BIOME]
    soil = f"#{MOD}:garden_soil"
    feature("lantern_lake/pale_wisteria", "vegetal_decoration", ["lantern_lake"],
            hanging_patch(hanging_column(f"{MOD}:pale_wisteria_plant", CREAM_TIPS, 1, 6), tries=10, xz=5, y=2),
            [scaled(48, GARDENS_PLANTS), SQUARE] + gardens_ceiling)
    feature("lantern_lake/plumes", "vegetal_decoration", ["lantern_lake"],
            patch(simple(f"{MOD}:lilac_plume"), state(f"{MOD}:lilac_plume"), tries=12, xz=4, y=2, on=soil),
            [scaled(12, GARDENS_PLANTS * LAKE_FLOOR), SQUARE] + gardens_floor)
    feature("lantern_lake/coral", "vegetal_decoration", ["lantern_lake"],
            patch(mix((simple(f"{MOD}:pale_coral"), 2), (simple(f"{MOD}:lavender_coral"), 2)), state(f"{MOD}:pale_coral"), tries=20, xz=4, y=2,
                  on=soil),
            [scaled(32, GARDENS_PLANTS * LAKE_FLOOR), SQUARE] + gardens_floor)
    feature("lantern_lake/petals", "vegetal_decoration", ["lantern_lake"],
            patch(simple(f"{MOD}:wisteria_petals"), state(f"{MOD}:wisteria_petals"), tries=32, xz=5, y=2, on=soil),
            [scaled(32, GARDENS_PLANTS * LAKE_FLOOR), SQUARE] + gardens_floor)
    feature("lantern_lake/waterweed", "vegetal_decoration", ["lantern_lake"],
            attach(simple(f"{MOD}:lilac_waterweed"), "floor", 96, f"#{MOD}:lake_bed", radius=7, height=2, in_water=True),
            [count(40), SQUARE] + lake_bed)
    feature("lantern_lake/lantern_weed", "vegetal_decoration", ["lantern_lake"],
            attach(simple(f"{MOD}:lantern_weed"), "floor", 10, f"#{MOD}:lake_bed", radius=6, height=2, in_water=True),
            [count(6), SQUARE] + lake_bed)
    # Pool pearls light the shallows round the islands and shores turquoise from the bed; the open water over the dark
    # bed stays dark (lantern_lake.jpg).
    feature("lantern_lake/pool_pearls", "vegetal_decoration", ["lantern_lake"],
            attach(simple(f"{MOD}:pool_pearls"), "floor", 96, soil, radius=6, height=2, in_water=True),
            [count(48), SQUARE, layer("deep_gardens")] + floor(through=AIR_OR_WATER) + [SHALLOW, BIOME])
    feature("ember_gardens/rose_tendrils", "vegetal_decoration", ["ember_gardens"],
            hanging_patch(hanging_column(f"{MOD}:rose_tendrils_plant", f"{MOD}:rose_tendrils", 2, 10), tries=10, xz=5, y=2),
            [scaled(40, GARDENS_PLANTS * EMBER_CEILING), SQUARE] + gardens_ceiling)
    feature("ember_gardens/plumes", "vegetal_decoration", ["ember_gardens"],
            patch(simple(f"{MOD}:lilac_plume"), state(f"{MOD}:lilac_plume"), tries=16, xz=5, y=3, on=soil),
            [scaled(32, GARDENS_PLANTS * EMBER_FLOOR), SQUARE] + gardens_floor)
    feature("ember_gardens/ochre_grass", "vegetal_decoration", ["ember_gardens"],
            patch(simple(f"{MOD}:ochre_grass"), state(f"{MOD}:ochre_grass"), tries=40, xz=6, y=3, on=soil),
            [scaled(64, GARDENS_PLANTS * EMBER_FLOOR), SQUARE] + gardens_floor)
    feature("ember_gardens/brown_mushrooms", "vegetal_decoration", ["ember_gardens"],
            patch(simple("minecraft:brown_mushroom"), state("minecraft:brown_mushroom"), tries=16, xz=5, y=2, on=soil),
            [scaled(12, GARDENS_PLANTS * EMBER_FLOOR), SQUARE] + gardens_floor)
    basalt_floor = [layer("basalt_galleries", above_water=True)] + floor() + [BIOME]
    feature("basalt_galleries/cinder_blooms", "vegetal_decoration", ["basalt_galleries"],
            patch(simple(f"{MOD}:cinder_bloom"), state(f"{MOD}:cinder_bloom"), tries=12, xz=5, y=2, on=f"#{MOD}:basalt_rock"),
            [scaled(24, BASALT_PLANTS * BASALT_FLOOR), SQUARE] + basalt_floor)
    # One ash-fern spot in four takes a cinder bloom (both need only a sturdy face below).
    feature("basalt_galleries/ash_ferns", "vegetal_decoration", ["basalt_galleries"],
            patch(mix((simple(f"{MOD}:ash_fern"), 3), (simple(f"{MOD}:cinder_bloom"), 1)), state(f"{MOD}:ash_fern"), tries=16, xz=6, y=3,
                  on=f"#{MOD}:basalt_rock"),
            [scaled(28, BASALT_PLANTS * BASALT_FLOOR), SQUARE] + basalt_floor)

    # ---- Upper Caves rework: the frozen Root Vaults (a cold cluster is frozen throughout: snowed in round its openings,
    # thawing further in, with plants only deep inside), the sinkholes (cold ones snowed in), the oasis hollows and the
    # Frost Fern Grotto. The entrances' features aim at their chambers (the grottos focus); the grotto's at the whole of
    # the pack's caves under the column, since the plant caves follow the ground (the caves focus). The cold caves grow
    # Immersive Weathering's frosty ferns, frost and icicles (Taran's ask for 0.4.1) and the mod's blue frost moss.
    fz, sk, oa, fg = ["frozen_rootvaults"], ["sinkhole"], ["oasis_hollow"], ["frost_fern_grotto"]
    def caves(): return layer(up) | {"focus": "caves"}
    frost_rock = f"#{MOD}:frost_rock"
    IW = "immersive_weathering"
    FROST_MOSS, FROST_CARPET = f"{MOD}:frost_moss", f"{MOD}:frost_moss_carpet"
    FROST_FLOORS = PLANT_FLOORS + [FROST_MOSS, "minecraft:podzol", "minecraft:coarse_dirt", "minecraft:snow_block", "minecraft:gravel"]
    OASIS_FLOORS = ["minecraft:sand", "minecraft:red_sand", "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:moss_block"]
    SNOW = {"type": "minecraft:weighted_state_provider", "entries": [
        {"data": state("minecraft:snow", layers=1), "weight": 3}, {"data": state("minecraft:snow", layers=2), "weight": 1}]}
    snow = patch(SNOW, state("minecraft:snow", layers=1), tries=40, xz=6, y=3)
    # Deeper snow for the frozen Root Vaults, in bigger patches: one to three layers.
    DEEP_SNOW = {"type": "minecraft:weighted_state_provider", "entries": [
        {"data": state("minecraft:snow", layers=1), "weight": 3}, {"data": state("minecraft:snow", layers=2), "weight": 3},
        {"data": state("minecraft:snow", layers=3), "weight": 1}]}
    def ice_sheet(lo, hi):
        """Ice over still water: the water's top layer, round the spot."""
        return {"type": "minecraft:disk", "config": {
            "state_provider": {"fallback": simple("minecraft:ice"), "rules": []},
            "target": {"type": "minecraft:all_of", "predicates": [
                {"type": "minecraft:matching_fluids", "fluids": "minecraft:water"}, dict(AIR, offset=[0, 1, 0])]},
            "radius": uniform(lo, hi), "half_height": 1}}
    def ice_rim(lo, hi):
        """Ice along the edges of still water: the water's top layer where it touches rock, round the spot."""
        rock = {"type": "minecraft:any_of", "predicates": [{"type": "minecraft:solid", "offset": o} for o in ([1, 0, 0], [-1, 0, 0], [0, 0, 1], [0, 0, -1])]}
        return {"type": "minecraft:disk", "config": {
            "state_provider": {"fallback": simple("minecraft:ice"), "rules": []},
            "target": {"type": "minecraft:all_of", "predicates": [
                {"type": "minecraft:matching_fluids", "fluids": "minecraft:water"}, dict(AIR, offset=[0, 1, 0]), rock]},
            "radius": uniform(lo, hi), "half_height": 1}}
    # From a random point down through the air to the water's surface.
    water_top = [{"type": "minecraft:environment_scan", "direction_of_search": "down",
                  "target_condition": {"type": "minecraft:matching_fluids", "fluids": "minecraft:water"},
                  "allowed_search_condition": AIR, "max_steps": 32}]
    on_water = water_top + [{"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": 1}]
    # Packed and blue ice only: plain ice melts by a torch or a frostberry and would hang a water source in the air.
    giant_icicle = spike(mix((simple("minecraft:packed_ice"), 3), (simple("minecraft:blue_ice"), 1)), simple("minecraft:packed_ice"), (4, 9), (1.0, 2.0), 0.0)
    ICICLE_REACH = 14  # how far up and down each column of a cluster looks for its ceiling and floor
    def icicle_cluster(height, radius, thickness, density):
        """Immersive Weathering's icy caves: vanilla's dripstone clusters in ice, packed ice crusting the ceiling (and the
        floor, where it is stone) a block or a few thick, in patches, with icicles hanging from it and rising under them.
        Dry: no puddles. Only over stone and ice (its icicle_replaceable_blocks tag)."""
        return {"type": f"{IW}:icicle_cluster", "config": {
            "floor_to_ceiling_search_range": ICICLE_REACH, "height": uniform(*height), "radius": uniform(*radius),
            "max_stalagmite_stalactite_height_diff": 1, "height_deviation": 3, "icicle_block_layer_thickness": uniform(*thickness),
            "density": funiform(*density), "wetness": 0.0, "chance_of_icicle_column_at_max_distance_from_center": 0.1,
            "max_distance_from_edge_affecting_chance_of_icicle_column": 3, "max_distance_from_center_affecting_height_bias": 8}}
    def icy_around(radius):
        """Immersive Weathering's icicles melt and fall by day outside its icy biomes: keep a cluster's whole reach in
        them (BiomeAroundFilter), not only its centre, so none reaches into the lush cave beside a grotto or up into the
        surface biome over a frozen Root Vault. A cluster's columns find their ceilings and floors up to ICICLE_REACH - 1
        blocks above and below the spot; radius covers its widest."""
        reach = ICICLE_REACH - 1
        return {"type": f"{MOD}:biome_around", "biomes": f"#{IW}:icy", "radius": radius, "above": reach, "below": reach}
    frostberries = hanging_patch(hanging_column(f"{MOD}:frostberry_vine_plant", f"{MOD}:frostberry_vine", 1, 7), tries=8, xz=4, y=2)
    # Immersive Weathering's frosty ferns (they grow only on dirt, frost moss included) with the asters and snowbells.
    frost_plants = mix((simple(f"{IW}:frosty_fern"), 6), (simple(f"{MOD}:frost_aster"), 2), (simple(f"{MOD}:snowbell"), 1))
    fern_survives = state(f"{IW}:frosty_fern")
    def frost_moss_patch(surface, vegetation, chance, xz):
        return {"type": "minecraft:vegetation_patch", "config": {
            "replaceable": "#minecraft:moss_replaceable", "ground_state": simple(FROST_MOSS),
            "vegetation_feature": {"feature": vegetation, "placement": []},
            "surface": surface, "depth": 1 if surface == "floor" else uniform(1, 2), "extra_bottom_block_chance": 0.0, "vertical_range": 5,
            "vegetation_chance": chance, "xz_radius": xz, "extra_edge_column_chance": 0.3}}
    def lily_pads(n, cold_filter):
        return ([count(n), SQUARE, layer(up)] + on_water + cold_filter + [BIOME])
    # The frozen Root Vaults' parts, by distance past the snowy zone round the openings (Entrances.chill).
    VAULT_LOOKS = (HALL, TEAL, RAVINE)
    def snowy(reach=0): return looks(*VAULT_LOOKS, max_chill=reach)
    def thawed(reach=THAW): return looks(*VAULT_LOOKS, min_chill=reach)

    # Ice first: giant icicles from the frozen ceilings by the openings, and Immersive Weathering's icicle clusters,
    # before anything grows on the rock. The grotto's ice is its icy caves' (Taran: one sheet of ice over the ceiling
    # looked odd).
    feature("frozen_rootvaults/giant_icicles", "local_modifications", fz, giant_icicle,
            [count(3), SQUARE, layer(up)] + ceiling(offset=0) + [snowy(2), BIOME])
    feature("frozen_rootvaults/icicle_clusters", "local_modifications", fz, icicle_cluster((1, 4), (2, 5), (1, 2), (0.3, 0.6)),
            [count(10), SQUARE, layer(up)] + ceiling() + [snowy(3), BIOME, icy_around(6)])
    feature("frost_fern_grotto/giant_icicles", "local_modifications", fg, giant_icicle,
            [count(2), SQUARE, caves()] + ceiling(offset=0) + [BIOME])
    feature("frost_fern_grotto/icicle_clusters", "local_modifications", fg, icicle_cluster((2, 6), (3, 6), (1, 3), (0.3, 0.7)),
            [count(20), SQUARE, caves()] + ceiling() + [BIOME, icy_around(7)])
    # Deep inside a frozen cluster, terraced pools like the root halls', rimmed with limestone and frost moss (a feature
    # below freezes their water over).
    feature("frozen_rootvaults/pools", "local_modifications", fz,
            pools(mix((LIMESTONE, 3), (simple(FROST_MOSS), 1)), mix((simple("minecraft:clay"), 2), (simple("minecraft:gravel"), 1)), 6),
            [count(2), SQUARE, layer(up)] + floor() + [looks(HALL, TEAL, min_chill=THAW // 2), BIOME])

    # Frost on the rock, snow on the floors, ice over the still water. Hoarfrost near the surface (the frozen Root Vaults
    # and the cold sinkholes) and Immersive Weathering's frost in the grotto, both in big patches; frost moss where the
    # frozen Root Vaults thaw.
    feature("frozen_rootvaults/hoarfrost", "underground_decoration", fz,
            attach(simple(f"{MOD}:hoarfrost"), "walls_ceiling", 96, frost_rock, radius=8, height=8),
            [count(16), SQUARE, layer(up), snowy(THAW), BIOME])
    feature("frozen_rootvaults/rime", "underground_decoration", fz,
            attach(simple(f"{MOD}:rime_lichen"), "walls_ceiling", 24, frost_rock, radius=6, height=6),
            [count(6), SQUARE, layer(up), BIOME])
    feature("frozen_rootvaults/snow", "underground_decoration", fz, patch(DEEP_SNOW, state("minecraft:snow", layers=1), tries=64, xz=7, y=3),
            [count(28), SQUARE, layer(up)] + floor() + [snowy(THAW * 2 // 3), BIOME])
    feature("frozen_rootvaults/frost_moss", "underground_decoration", fz,
            frost_moss_patch("floor", {"type": "minecraft:simple_block", "config": {"to_place": simple(FROST_CARPET)}}, 0.25, uniform(3, 6)),
            [count(14), SQUARE, layer(up)] + floor() + [thawed(THAW // 3), BIOME])
    feature("frozen_rootvaults/ice", "underground_decoration", fz, ice_sheet(2, 5), [count(10), SQUARE, layer(up)] + water_top + [BIOME])
    feature("sinkhole/moss", "underground_decoration", sk, "minecraft:moss_patch",
            [count(6), SQUARE, layer(up)] + floor() + [looks(SINKHOLE, cold=False), BIOME])
    feature("sinkhole/hoarfrost", "underground_decoration", sk,
            attach(simple(f"{MOD}:hoarfrost"), "walls_ceiling", 96, frost_rock, radius=8, height=8),
            [count(12), SQUARE, layer(up), looks(SINKHOLE, cold=True), BIOME])
    feature("sinkhole/snow", "underground_decoration", sk, snow,
            [count(16), SQUARE, layer(up)] + floor() + [looks(SINKHOLE, cold=True), BIOME])
    feature("sinkhole/ice", "underground_decoration", sk, ice_sheet(2, 4),
            [count(8), SQUARE, layer(up)] + water_top + [looks(SINKHOLE, cold=True), BIOME])
    # The Frost Fern Grotto (frost_fern_grotto.png, the picture Taran gave): frost moss floors with frosty ferns, frost
    # moss ceilings hung with frostberries, rime glowing on the walls, frost over the rock, icy-cave ice on the
    # ceilings and over the pools' edges; and the picture's dark speckled deepslate in patches on the walls (placed
    # through the grotto's caves and the rock round them, so a patch shows where it cuts a wall), from the old floor up
    # to under the highest grotto.
    for name, block, size, n in (("deepslate_patches", "minecraft:deepslate", 40, 6), ("cobbled_patches", "minecraft:cobbled_deepslate", 24, 3)):
        feature(f"frost_fern_grotto/{name}", "underground_ores", fg,
                {"type": "minecraft:ore", "config": {
                    "targets": [{"target": {"predicate_type": "minecraft:tag_match", "tag": "minecraft:stone_ore_replaceables"},
                                 "state": {"Name": block}}],
                    "size": size, "discard_chance_on_air_exposure": 0.0}},
                [count(n), SQUARE, caves(), BIOME])
    feature("frost_fern_grotto/moss_floor", "underground_decoration", fg,
            frost_moss_patch("floor", {"type": "minecraft:simple_block", "config": {"to_place": mix(
                (simple(f"{IW}:frosty_fern"), 5), (simple(FROST_CARPET), 5), (simple(f"{MOD}:frost_aster"), 1),
                (simple(f"{MOD}:snowbell"), 1))}}, 0.3, uniform(4, 7)),
            [count(48), SQUARE, caves()] + floor() + [BIOME])
    feature("frost_fern_grotto/moss_ceiling", "underground_decoration", fg,
            frost_moss_patch("ceiling", hanging_column(f"{MOD}:frostberry_vine_plant", f"{MOD}:frostberry_vine", 0, 4), 0.08, uniform(4, 7)),
            [count(24), SQUARE, caves()] + ceiling() + [BIOME])
    feature("frost_fern_grotto/frost", "underground_decoration", fg,
            attach(simple(f"{IW}:frost"), "walls_ceiling", 96, frost_rock, radius=8, height=8),
            [count(20), SQUARE, caves(), BIOME])
    feature("frost_fern_grotto/rime", "underground_decoration", fg,
            attach(simple(f"{MOD}:rime_lichen"), "walls_ceiling", 32, frost_rock, radius=6, height=6),
            [count(12), SQUARE, caves(), BIOME])
    feature("frost_fern_grotto/snow", "underground_decoration", fg, patch(SNOW, state("minecraft:snow", layers=1), tries=16, xz=4, y=2),
            [count(10), SQUARE, caves()] + floor() + [BIOME])
    # Ice shelves along the water's edges; the middle stays open (the picture's stream).
    feature("frost_fern_grotto/ice", "underground_decoration", fg, ice_rim(3, 6), [count(16), SQUARE, caves()] + water_top + [BIOME])

    # Plants: frosty ferns, asters and snowbells deep inside the frozen Root Vaults, frostberries a little nearer the
    # openings; grass and flowers in the sinkholes' daylight, palms and lilies round the oases' pools, the grotto's ferns
    # and frostberries.
    feature("frozen_rootvaults/plants", "vegetal_decoration", fz,
            patch(frost_plants, fern_survives, tries=24, xz=5, y=2, on=FROST_FLOORS),
            [count(12), SQUARE, layer(up)] + floor() + [thawed(), BIOME])
    feature("frozen_rootvaults/frostberries", "vegetal_decoration", fz, frostberries,
            [count(5), SQUARE, layer(up)] + ceiling() + [thawed(THAW * 2 // 3), BIOME])
    feature("sinkhole/trailing_grass", "vegetal_decoration", sk,
            hanging_patch(hanging_column(f"{MOD}:trailing_grass_plant", f"{MOD}:trailing_grass", 1, 7), tries=10, xz=5, y=3),
            [count(24), SQUARE, layer(up)] + ceiling() + [looks(SINKHOLE, cold=False), BIOME])
    feature("sinkhole/plants", "vegetal_decoration", sk,
            patch({"type": "minecraft:weighted_state_provider", "entries": [
                {"data": state("minecraft:grass"), "weight": 8}, {"data": state("minecraft:fern"), "weight": 3},
                {"data": state("minecraft:tall_grass", half="lower"), "weight": 2}, {"data": state("minecraft:dandelion"), "weight": 1},
                {"data": state("minecraft:poppy"), "weight": 1}, {"data": state("minecraft:oxeye_daisy"), "weight": 1},
                {"data": state("minecraft:cornflower"), "weight": 1}]},
                state("minecraft:grass"), tries=40, xz=6, y=3, on=PLANT_FLOORS + ["minecraft:grass_block"]),
            [count(30), SQUARE, layer(up)] + floor() + [looks(SINKHOLE, cold=False), BIOME])
    # (No icicles in the cold sinkholes: Immersive Weathering's melt by day outside its icy biomes, and the sinkhole
    # biome is shared with the warm ones.)
    feature("sinkhole/cold_plants", "vegetal_decoration", sk,
            patch(mix((simple(f"{IW}:frosty_fern"), 3), (simple(f"{IW}:frosty_grass"), 3), (simple(f"{MOD}:frost_aster"), 1)), fern_survives,
                  tries=24, xz=6, y=3, on=FROST_FLOORS + ["minecraft:grass_block"]),
            [count(12), SQUARE, layer(up)] + floor() + [looks(SINKHOLE, cold=True), BIOME])
    feature("sinkhole/cave_vines", "vegetal_decoration", sk, berry_vines(1, 5, 0.3),
            [count(8), SQUARE, layer(up)] + ceiling() + [looks(SINKHOLE, cold=False), BIOME])
    feature("sinkhole/lily_pads", "vegetal_decoration", sk,
            patch(simple("minecraft:lily_pad"), state("minecraft:lily_pad"), tries=12, xz=4, y=1), lily_pads(4, [looks(SINKHOLE, cold=False)]))
    feature("sinkhole/sugar_cane", "vegetal_decoration", sk,
            patch(simple("minecraft:sugar_cane"), state("minecraft:sugar_cane"), tries=24, xz=5, y=2),
            [count(4), SQUARE, layer(up)] + floor() + [looks(SINKHOLE, cold=False), BIOME])
    feature("oasis_hollow/palms", "vegetal_decoration", oa,
            patch(simple(f"{MOD}:fan_palm"), state(f"{MOD}:fan_palm"), tries=12, xz=6, y=2, on=OASIS_FLOORS),
            [count(8), SQUARE, layer(up)] + floor() + [BIOME])
    feature("oasis_hollow/grass", "vegetal_decoration", oa,
            patch(simple(f"{MOD}:oasis_grass"), state(f"{MOD}:oasis_grass"), tries=32, xz=6, y=2, on=OASIS_FLOORS),
            [count(20), SQUARE, layer(up)] + floor() + [BIOME])
    feature("oasis_hollow/lilies", "vegetal_decoration", oa,
            patch(simple(f"{MOD}:dune_lily"), state(f"{MOD}:dune_lily"), tries=10, xz=5, y=2, on=OASIS_FLOORS),
            [count(6), SQUARE, layer(up)] + floor() + [BIOME])
    feature("oasis_hollow/dead_bushes", "vegetal_decoration", oa,
            patch(simple("minecraft:dead_bush"), state("minecraft:dead_bush"), tries=8, xz=6, y=2, on=["minecraft:sand", "minecraft:red_sand"]),
            [count(6), SQUARE, layer(up)] + floor() + [BIOME])
    feature("oasis_hollow/sugar_cane", "vegetal_decoration", oa,
            patch(simple("minecraft:sugar_cane"), state("minecraft:sugar_cane"), tries=24, xz=5, y=2),
            [count(6), SQUARE, layer(up)] + floor() + [BIOME])
    feature("oasis_hollow/lily_pads", "vegetal_decoration", oa,
            patch(simple("minecraft:lily_pad"), state("minecraft:lily_pad"), tries=10, xz=4, y=1), lily_pads(3, []))
    feature("oasis_hollow/trailing_grass", "vegetal_decoration", oa,
            hanging_patch(hanging_column(f"{MOD}:trailing_grass_plant", f"{MOD}:trailing_grass", 1, 5), tries=8, xz=4, y=2),
            [count(6), SQUARE, layer(up)] + ceiling() + [BIOME])
    # The oasis pool glows turquoise from its bed, like the Root Vaults' streams.
    feature("oasis_hollow/pool_pearls", "vegetal_decoration", oa,
            attach(simple(f"{MOD}:pool_pearls"), "floor", 24, f"#{MOD}:grotto_bed", radius=5, height=2, in_water=True),
            [count(12), SQUARE, layer(up)] + floor(through=AIR_OR_WATER) + [IN_WATER, BIOME])
    # Fewer ferns than 0.4.0's 40 patches: the grotto looked too lush.
    feature("frost_fern_grotto/ferns", "vegetal_decoration", fg,
            patch(frost_plants, fern_survives, tries=32, xz=6, y=3, on=FROST_FLOORS),
            [count(16), SQUARE, caves()] + floor() + [BIOME])
    feature("frost_fern_grotto/snowbells", "vegetal_decoration", fg,
            patch(simple(f"{MOD}:snowbell"), state(f"{MOD}:snowbell"), tries=12, xz=4, y=2, on=FROST_FLOORS),
            [count(8), SQUARE, caves()] + floor() + [BIOME])
    feature("frost_fern_grotto/frostberries", "vegetal_decoration", fg, frostberries, [count(24), SQUARE, caves()] + ceiling() + [BIOME])

    # ---- top layer: roots of the trees above, once the chunk's trees have grown; the way down is kept open after
    # everything else. The roots take the wood of the trees over them (match_trees): oak, birch, spruce, jungle, dark
    # oak, cherry, or mangrove roots under a mangrove swamp.
    def roots(length, column_chance, branches):
        return {"type": f"{MOD}:ceiling_roots", "config": {
            "log": state("minecraft:oak_log", axis="y"), "dirt": state("minecraft:rooted_dirt"), "hanging": state("minecraft:hanging_roots"),
            "length": length, "match_trees": True, "column_chance": column_chance, "branches": branches}}
    feature("rootvaults/ceiling_roots", "top_layer_modification", rv + ["frozen_rootvaults"], roots(uniform(3, 14), 0.25, uniform(0, 2)),
            [count(8), SQUARE, layer(up)] + ceiling() + [BIOME])
    feature("rootvaults/tree_roots", "top_layer_modification", rv + ["frozen_rootvaults"], roots(uniform(4, 16), 0.3, uniform(1, 3)),
            [{"type": f"{MOD}:under_trees", "chance": 0.7}, BIOME])
    # The network's climbs get their vines, and the Blue Reaches' lips their shelf vines (section 10 of the network
    # design), once everything else has grown; then the ways are opened (open_way never removes a climbable block).
    feature("climb_vines", "top_layer_modification", DEEP, {"type": f"{MOD}:climb_vines", "config": {}}, [])
    feature("shelf_vines", "top_layer_modification", ["deep_blue_rift"], {"type": f"{MOD}:shelf_vines", "config": {}}, [])
    feature("open_way", "top_layer_modification", DEEP, {"type": f"{MOD}:open_way", "config": {}}, [])


# The vanilla deep dark, moved down into the Blue Reaches: its sculk, which vanilla only places relative to the old
# floor, placed again across the layer. Added to minecraft:deep_dark by a biome modifier. (name, configured feature
# (vanilla id or inline dict), placement).
DEEP_DARK_FEATURES = [
    ("deep_dark/sculk_vein", "minecraft:sculk_vein", [count(112), SQUARE, layer("blue_reaches"), BIOME]),
    ("deep_dark/sculk_patch", "minecraft:sculk_patch_deep_dark", [count(136), SQUARE, layer("blue_reaches"), BIOME]),
    # vegetation rework, part A: the deep dark's only plant, a few dark strands whose specks glow without giving light
    ("deep_dark/dusk_vines", hanging_column(f"{MOD}:dusk_vines_plant", f"{MOD}:dusk_vines", 1, 5),
     [count(4), SQUARE, layer("blue_reaches")] + ceiling() + [BIOME]),
]

# The Upper Caves rework's features added to other biomes by biome modifiers: (name, configured feature, placement,
# biomes, step). The tropical (jungle and mangrove) Root Vaults' veridium runs on into the lush caves their descents
# open into, after the plants; the pack's blind cave ends are joined to other caves first of all, in every overworld
# chunk.
# Taran's ask for 0.4.1: Immersive Weathering's frost on the surface of the snowy biomes (#immersive_weathering:icy) is
# swapped for the mod's hoarfrost in bigger patches: a biome modifier (gen_data.py) takes its frost_patch away, and these
# patches go on the cliffs, banks and overhangs round the surface instead (walls and ceilings only, as its frost did),
# on #explorationremix:surface_frost_rock (below).
UPPER_MODIFIER_FEATURES = [
    ("upper/veridium_spread", {"type": f"{MOD}:veridium_spread", "config": {}}, [{"type": f"{MOD}:junctions", "tropical": True}],
     ["minecraft:lush_caves"], "top_layer_modification"),
    ("upper/join_dead_ends", {"type": f"{MOD}:join_dead_ends", "config": {}}, [], "#minecraft:is_overworld", "raw_generation"),
    ("upper/surface_hoarfrost", attach(simple(f"{MOD}:hoarfrost"), "walls_ceiling", 96, f"#{MOD}:surface_frost_rock", radius=7, height=6),
     [count(4), SQUARE, {"type": "minecraft:heightmap", "heightmap": "WORLD_SURFACE_WG"}, BIOME], "#immersive_weathering:icy", "top_layer_modification"),
]

# ----------------------------------------------------------------------------------------------- Rootvaults tags
# The Rootvaults grow under trees. Each tag names the surface biomes whose trees send down roots of one wood; the
# mod checks them in the order mangrove, cherry, dark oak, birch, jungle, spruce, oak (the first match wins), and a
# biome in none of them grows no Rootvaults. WWOO keeps vanilla's biome ids, so these cover the pack.
ROOT_KINDS = {
    "mangrove": ["minecraft:mangrove_swamp"],
    "cherry": ["minecraft:cherry_grove"],
    "dark_oak": ["minecraft:dark_forest"],
    "birch": ["minecraft:birch_forest", "minecraft:old_growth_birch_forest"],
    "jungle": ["#minecraft:is_jungle"],
    "spruce": ["#minecraft:is_taiga", "minecraft:grove", "minecraft:windswept_forest"],
    "oak": ["minecraft:forest", "minecraft:flower_forest", "minecraft:swamp"],
}
# Grass without trees grows sinkholes; deserts and badlands grow oasis hollows (in red rock under the badlands).
UPPER_COVER = {
    "plains": ["minecraft:plains", "minecraft:sunflower_plains", "minecraft:meadow", "minecraft:savanna", "minecraft:savanna_plateau",
               "minecraft:snowy_plains"],
    "desert": ["minecraft:desert"],
    "badlands": ["#minecraft:is_badlands"],
}
# Where a ravine may not open to the sky: biomes that grow villages and other surface structures, swamps, water.
NO_RAVINES = ["#minecraft:has_structure/" + s for s in (
    "village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga", "pillager_outpost", "woodland_mansion",
    "jungle_temple", "igloo", "trail_ruins", "swamp_hut")] + [
    {"id": "#forge:is_swamp", "required": False}, "#minecraft:is_river", "#minecraft:is_ocean", "#minecraft:is_beach"]

# ----------------------------------------------------------------------------------------------- block tags
CREATE_STONES = ["create:limestone", "create:veridium", "create:asurine", "create:crimsite", "create:ochrum", "create:scoria", "create:scorchia"]
STAND_INS = ["minecraft:yellow_terracotta", "minecraft:deepslate_tiles", "minecraft:warped_wart_block", "minecraft:brown_terracotta",
             "minecraft:polished_blackstone", "minecraft:red_terracotta"]


def optional(ids): return [{"id": i, "required": False} for i in ids]


BLOCK_TAGS = {
    # Natural rock that plants, crystals and springs may grow on or out of.
    "cave_rock": ["#minecraft:base_stone_overworld", "minecraft:cobbled_deepslate", "minecraft:calcite", "minecraft:dripstone_block",
                  "minecraft:smooth_basalt", "minecraft:basalt", "minecraft:polished_basalt", "minecraft:blackstone", "minecraft:gilded_blackstone",
                  "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:magma_block", "minecraft:moss_block", f"{MOD}:frost_moss",
                  "minecraft:rooted_dirt",
                  "minecraft:dirt", "minecraft:clay", "minecraft:brown_mushroom_block", "#minecraft:coal_ores", "#minecraft:iron_ores",
                  "#minecraft:copper_ores", "#minecraft:gold_ores", "#minecraft:redstone_ores", "#minecraft:lapis_ores", "#minecraft:diamond_ores",
                  "#minecraft:emerald_ores"]
                 + STAND_INS + optional(CREATE_STONES),
    "asurine_lining": ["minecraft:deepslate_tiles"] + optional(["create:asurine"]),
    "blue_rift_walls": ["minecraft:deepslate", "minecraft:cobbled_deepslate", "minecraft:gravel", "minecraft:tuff", "minecraft:calcite",
                        "minecraft:deepslate_tiles"] + optional(["create:asurine"]),
    # vegetation rework, part B: the Deep Gardens' plants grow from limestone, calcite and sand (no grass or moss) and
    # from the brown mushroom shelves; under Lantern Lake's water they grow from its bed.
    "garden_soil": ["minecraft:calcite", "minecraft:sand", "minecraft:brown_mushroom_block"] + optional(["create:limestone"]),
    "lake_bed": ["minecraft:sand", "minecraft:gravel", "minecraft:clay", "minecraft:calcite"] + optional(["create:limestone"]),
    # The beds of the Rootvaults' streams and pools, where pool pearls grow.
    "grotto_bed": ["#minecraft:base_stone_overworld", "minecraft:gravel", "minecraft:clay", "minecraft:sand", "minecraft:calcite",
                   "minecraft:moss_block"] + optional(["create:limestone", "create:veridium"]),
    # The Deep Gardens' pale ceilings, which grow cream stalactites.
    "pale_rock": ["minecraft:calcite"] + optional(["create:limestone"]),
    # Upper Caves rework: rock that frost and rime grow on, ice and snow included.
    "frost_rock": [f"#{MOD}:cave_rock", "minecraft:packed_ice", "minecraft:ice", "minecraft:blue_ice", "minecraft:snow_block"],
    # 0.4.1: the surface rock, soil and ice of the snowy biomes, where the surface hoarfrost patches grow. Not snow
    # blocks: white frost on white snow did not show (half the patches on the jagged peaks were on snow).
    "surface_frost_rock": ["#minecraft:base_stone_overworld", "#minecraft:dirt", "minecraft:packed_ice", "minecraft:ice",
                           "minecraft:blue_ice", "minecraft:gravel", "minecraft:calcite", "minecraft:cobblestone", "minecraft:mossy_cobblestone"],
    "basalt_rock": ["minecraft:basalt", "minecraft:polished_basalt", "minecraft:smooth_basalt", "minecraft:blackstone", "minecraft:gilded_blackstone",
                    "minecraft:magma_block", "minecraft:deepslate_gold_ore", "minecraft:brown_terracotta", "minecraft:polished_blackstone"]
                   + optional(["create:scoria", "create:scorchia"]),
}

# ----------------------------------------------------------------------------------------------- writing

def rgb(h): return int(h.lstrip("#"), 16)


CAVE_MONSTERS = [
    {"type": "minecraft:spider", "weight": 100, "minCount": 4, "maxCount": 4},
    {"type": "minecraft:zombie", "weight": 95, "minCount": 4, "maxCount": 4},
    {"type": "minecraft:zombie_villager", "weight": 5, "minCount": 1, "maxCount": 1},
    {"type": "minecraft:skeleton", "weight": 100, "minCount": 4, "maxCount": 4},
    {"type": "minecraft:creeper", "weight": 100, "minCount": 4, "maxCount": 4},
    {"type": "minecraft:slime", "weight": 100, "minCount": 4, "maxCount": 4},
    {"type": "minecraft:enderman", "weight": 10, "minCount": 1, "maxCount": 4},
    {"type": "minecraft:witch", "weight": 5, "minCount": 1, "maxCount": 1},
]


def spawners(name):
    s = {"monster": CAVE_MONSTERS, "creature": [], "ambient": [{"type": "minecraft:bat", "weight": 10, "minCount": 8, "maxCount": 8}],
         "underground_water_creature": [], "water_creature": [], "water_ambient": [], "misc": [], "axolotls": []}
    if name not in DRY:
        s["underground_water_creature"] = [{"type": "minecraft:glow_squid", "weight": 10, "minCount": 4, "maxCount": 6}]
    if name == "rootvaults":
        s["axolotls"] = [{"type": "minecraft:axolotl", "weight": 10, "minCount": 4, "maxCount": 6}]
    return s


# Biomes open to the sky in places keep a sky of the usual colour (the Rootvaults' ravines and skylights).
SKY = {"rootvaults": "#78a7ff", "frozen_rootvaults": "#7ba4ff", "sinkhole": "#78a7ff", "oasis_hollow": "#6eb1ff"}


def effects(name):
    fog, water, water_fog, grass, foliage = (rgb(c) for c in LOOKS[name])
    sky = rgb(SKY[name]) if name in SKY else fog
    e = {"fog_color": fog, "sky_color": sky, "water_color": water, "water_fog_color": water_fog, "grass_color": grass, "foliage_color": foliage,
         "mood_sound": {"sound": "minecraft:ambient.cave", "tick_delay": 6000, "block_search_extent": 8, "offset": 2.0}}
    if name in PARTICLES:
        p, chance = PARTICLES[name]
        e["particle"] = {"options": {"type": p}, "probability": chance}
    loop, adds, chance = SOUNDS[name]
    e["ambient_sound"] = loop
    e["additions_sound"] = {"sound": adds, "tick_chance": chance}
    return e


def write_all(data_dir, res_dir, write):
    """data_dir: data/explorationremix; res_dir: src/main/resources; write(path, obj) writes JSON."""
    build_features()
    wg = os.path.join(data_dir, "worldgen")
    per_biome = {b: [[] for _ in STEPS] for b in OURS}
    for b, steps in [(b, ROOTVAULTS_VANILLA) for b in ("rootvaults", "frozen_rootvaults", "sinkhole", "oasis_hollow", "frost_fern_grotto")]:
        for i, ids in enumerate(steps):
            per_biome[b][i].extend(f for f in ids if f not in VANILLA_LEFT_OUT.get(b, ()))
    names = set()
    for name, step, biomes, configured, placement in FEATURES:
        assert name not in names, name
        names.add(name)
        if isinstance(configured, str):
            cf = configured
        else:
            write(os.path.join(wg, "configured_feature", name + ".json"), configured)
            cf = f"{MOD}:{name}"
        write(os.path.join(wg, "placed_feature", name + ".json"), {"feature": cf, "placement": placement})
        for b in biomes:
            per_biome[b][STEPS.index(step)].append(f"{MOD}:{name}")
    for name, cf, placement in DEEP_DARK_FEATURES:
        if not isinstance(cf, str):
            write(os.path.join(wg, "configured_feature", name + ".json"), cf)
            cf = f"{MOD}:{name}"
        write(os.path.join(wg, "placed_feature", name + ".json"), {"feature": cf, "placement": placement})
    for name, cf, placement, biomes, step in UPPER_MODIFIER_FEATURES:
        write(os.path.join(wg, "configured_feature", name + ".json"), cf)
        write(os.path.join(wg, "placed_feature", name + ".json"), {"feature": f"{MOD}:{name}", "placement": placement})
        write(os.path.join(data_dir, "forge", "biome_modifier", name.split("/", 1)[1] + ".json"), {
            "type": "forge:add_features", "biomes": biomes, "features": f"{MOD}:{name}", "step": step})
    for b in OURS:
        write(os.path.join(wg, "biome", b + ".json"), {
            "has_precipitation": b in PRECIPITATION,
            "temperature": TEMPERATURE.get(b, 1.2 if b in HOT else (0.5 if b == "asurine_geodes" else 0.8)),
            "downfall": 0.0 if b in DRY else 0.5,
            "effects": effects(b),
            "spawners": spawners(b),
            "spawn_costs": {},
            "carvers": {},
            "features": per_biome[b],
        })
    # The deep dark's sculk across the Blue Reaches; its climbs' and lips' vines, and the ways kept open through it.
    write(os.path.join(data_dir, "forge", "biome_modifier", "deep_dark_sculk.json"), {
        "type": "forge:add_features", "biomes": "minecraft:deep_dark",
        "features": [f"{MOD}:{n}" for n, _, _ in DEEP_DARK_FEATURES], "step": "underground_decoration"})
    write(os.path.join(data_dir, "forge", "biome_modifier", "deep_dark_open_way.json"), {
        "type": "forge:add_features", "biomes": "minecraft:deep_dark",
        "features": [f"{MOD}:climb_vines", f"{MOD}:shelf_vines", f"{MOD}:open_way"], "step": "top_layer_modification"})
    # Biome tags.
    def tag(ns, kind, tname, values):
        write(os.path.join(res_dir, "data", ns, "tags", kind, tname + ".json"), {"replace": False, "values": values})
    tag(MOD, "worldgen/biome", "is_deep", [f"{MOD}:{b}" for b in DEEP])
    # Immersive Weathering's icicles melt by day outside its icy biomes, deep underground too: the cold caves are icy.
    tag("immersive_weathering", "worldgen/biome", "icy", [f"{MOD}:frozen_rootvaults", f"{MOD}:frost_fern_grotto"])
    tag(MOD, "worldgen/biome", "is_upper", [f"{MOD}:{b}" for b in UPPER])
    tag("minecraft", "worldgen/biome", "is_overworld", [f"{MOD}:{b}" for b in UPPER])
    tag("forge", "worldgen/biome", "is_cave", [f"{MOD}:{b}" for b in OURS])
    tag("forge", "worldgen/biome", "is_underground", [f"{MOD}:{b}" for b in OURS])
    for kind, values in ROOT_KINDS.items():
        tag(MOD, "worldgen/biome", "rootvaults/" + kind, values)
    tag(MOD, "worldgen/biome", "rootvaults/no_ravines", NO_RAVINES)
    # The other entrances' ground (SurfaceOracle): grass without trees, sand, and the badlands' red sand.
    for kind, values in UPPER_COVER.items():
        tag(MOD, "worldgen/biome", "upper/" + kind, values)
    for tname, values in BLOCK_TAGS.items():
        tag(MOD, "blocks", tname, values)
    return {f"biome.{MOD}.{k}": v for k, v in NAMES.items()}
