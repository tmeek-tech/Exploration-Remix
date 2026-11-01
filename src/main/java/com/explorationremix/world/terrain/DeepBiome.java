package com.explorationremix.world.terrain;

/**
 * The cave biomes the deep world places itself: the four deep layers' biomes, and the two regions carved into the
 * pack's own caves above the first band. The game-side registry maps these to biome ids.
 */
public enum DeepBiome {
    // Blue Reaches
    DEEP_BLUE_RIFT("deep_blue_rift", Role.ROUTE),
    DEEP_DARK("minecraft:deep_dark", Role.HUNTING),
    ASURINE_GEODES("asurine_geodes", Role.REFUGE),
    // Deep Gardens
    LANTERN_LAKE("lantern_lake", Role.ROUTE),
    EMBER_GARDENS("ember_gardens", Role.HUNTING),
    RUST_SPRINGS("rust_springs", Role.REFUGE),
    // Basalt Galleries (since 0.5 the bottom layer, under the Wasteland)
    BASALT_GALLERIES("basalt_galleries", Role.ROUTE),
    OBSIDIAN_TUBES("obsidian_tubes", Role.REFUGE),
    // Upper caves, carved into the pack's caves between y -64 and about 0
    SILVER_FERN_RIFT("silver_fern_rift", Role.SAFE),
    IVORY_ROOT_CAVERN("ivory_root_cavern", Role.SAFE),
    // Since 0.5 (appended so the older biomes keep their feature order). The Wasteland: the old Basalt Galleries' look,
    // where predators will hunt, and its bare refuge.
    WASTELAND("wasteland", Role.HUNTING),
    ASH_HOLLOWS("ash_hollows", Role.REFUGE),
    // The Basalt Galleries' lifeless sub-biomes (Taran's pick, 2026-11-01): the crater where eruptions start, basalt
    // column tops over the lava, and red rifts of Create's scoria.
    CALDERA("caldera", Role.ROUTE),
    CAUSEWAY("causeway", Role.ROUTE),
    SCORIA_CANYONS("scoria_canyons", Role.ROUTE);

    /** Refuges are bare and calm, routes have some growth, hunting grounds are the most overgrown; upper caves are safe. */
    public enum Role { REFUGE, ROUTE, HUNTING, SAFE }

    public final String id;
    public final Role role;

    DeepBiome(String id, Role role) {
        this.id = id;
        this.role = role;
    }
}
