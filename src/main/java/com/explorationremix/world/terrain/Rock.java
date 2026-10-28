package com.explorationremix.world.terrain;

/**
 * The blocks the deep world is built from, before they become block states. Every one is vanilla or Create (with a
 * vanilla stand-in when Create is missing), except band stone. The colour is only for the offline preview.
 */
public enum Rock {
    STONE(0x7d7d7d),
    DEEPSLATE(0x4f4f55),
    COBBLED_DEEPSLATE(0x4a4a4e),
    TUFF(0x6b6c64),
    CALCITE(0xdedfda),
    LIMESTONE(0xb0aa98),
    OCHRUM(0xc39a4e),
    ASURINE(0x4f6e9c),
    CRIMSITE(0x8e3a36),
    VERIDIUM(0x3e7868),
    SCORIA(0x6e4b3e),
    SCORCHIA(0x3d3532),
    BLACKSTONE(0x2b2429),
    BASALT(0x515157),
    SMOOTH_BASALT(0x47474c),
    GRANITE(0x966856),
    DRIPSTONE(0x876b5b),
    BEDROCK(0x575757),
    BAND_STONE(0x1d1a26),
    MOSS(0x5b702e),
    GRASS(0x5e8d3b),
    DIRT(0x86603f),
    ROOTED_DIRT(0x8f674b),
    CLAY(0xa0a6b4),
    SAND(0xdacfa2),
    GRAVEL(0x847e7c),
    MAGMA(0x8f401f),
    OCHRE_FROGLIGHT(0xf2c86a),
    GILDED_BLACKSTONE(0x6c4d2b),
    GOLD_ORE(0xb5a36a),
    DEEPSLATE_GOLD_ORE(0x77705b),
    DEEPSLATE_DIAMOND_ORE(0x5b8b8e),
    DEEPSLATE_COPPER_ORE(0x5d6658),
    DEEPSLATE_IRON_ORE(0x6b655f),
    IRON_ORE(0x88807a),
    OBSIDIAN(0x15111f),
    CRYING_OBSIDIAN(0x2c0d52);

    public final int color;

    Rock(int color) { this.color = color; }
}
