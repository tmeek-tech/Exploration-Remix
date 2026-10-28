package com.explorationremix.world.terrain;

/** The cave biomes the deep world places itself. The game-side registry maps these to biome ids. */
public enum DeepBiome {
    IVORY_ROOT_CAVERN("ivory_root_cavern", Role.ROUTE),
    LANTERN_LAKE("lantern_lake", Role.HUNTING),
    ASURINE_GEODES("asurine_geodes", Role.REFUGE),
    DEEP_BLUE_RIFT("deep_blue_rift", Role.ROUTE),
    SILVER_FERN_RIFT("silver_fern_rift", Role.HUNTING),
    DEEP_DARK("minecraft:deep_dark", Role.HUNTING),
    RUST_SPRINGS("rust_springs", Role.ROUTE),
    EMBER_GARDENS("ember_gardens", Role.HUNTING),
    BASALT_GALLERIES("basalt_galleries", Role.ROUTE);

    public enum Role { REFUGE, ROUTE, HUNTING }

    public final String id;
    public final Role role;

    DeepBiome(String id, Role role) {
        this.id = id;
        this.role = role;
    }
}
