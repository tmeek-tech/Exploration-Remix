package com.explorationremix.world.terrain;

/**
 * Which rock goes where. Rock deep inside the walls is the layer's base rock with bold patches of its accent stones
 * ({@link #interior}). Rock near any open space is dressed per biome ({@link #shell}): floors, ceilings, walls, lake
 * beds, ore veins and the layered rims of the geodes. Stone changes come as patches, veins and wavy seams, never as
 * even stripes.
 *
 * <p>The shell is decided by the surface pass, which knows how deep a block sits below the open space above it
 * ({@code above}, 1 = exposed on top) and above the open space below it ({@code below}, 1 = exposed underneath).
 */
public final class Palette {
    private final Terrain t;
    private final long seed;
    private final Perlin interiorN, patchN, veinN, veinMask, seamN, seamMask, wobN;

    Palette(Terrain t, long seed) {
        this.t = t;
        this.seed = seed ^ 0x3C6EF372FE94F82BL;
        long s = this.seed;
        interiorN = new Perlin(Hashing.mix(s, 1, 0, 0));
        patchN = new Perlin(Hashing.mix(s, 2, 0, 0));
        veinN = new Perlin(Hashing.mix(s, 3, 0, 0));
        veinMask = new Perlin(Hashing.mix(s, 4, 0, 0));
        seamN = new Perlin(Hashing.mix(s, 5, 0, 0));
        seamMask = new Perlin(Hashing.mix(s, 6, 0, 0));
        wobN = new Perlin(Hashing.mix(s, 7, 0, 0));
    }

    private double speck(int x, int y, int z) { return Hashing.unit(Hashing.mix(seed, x, y, z)); }

    /** Bold blobs, a few to a few dozen blocks across. */
    private double patch(int x, int y, int z) { return patchN.noise3(x * 0.036, y * 0.05, z * 0.036); }

    /** Thin, branching veins that come and go. */
    private boolean vein(int x, int y, int z, double width) {
        if (veinMask.noise3(x * 0.02, y * 0.03, z * 0.02) < 0.05) return false;
        return Math.abs(veinN.noise3(x * 0.07, y * 0.09, z * 0.07)) < width;
    }

    /** Wavy seams one or two blocks thick, mostly flat-lying, that pinch out after a few dozen blocks. */
    private boolean seam(int x, int y, int z) {
        if (seamMask.noise3(x * 0.015, y * 0.02, z * 0.015) < 0.12) return false;
        return Math.abs(seamN.noise3(x * 0.012, y * 0.075, z * 0.012)) < 0.022;
    }

    // ============================================================================================== deep rock

    /** Rock more than a few blocks from any open space. */
    public Rock interior(int li, int x, int y, int z) {
        double n = interiorN.noise3(x * 0.024, y * 0.034, z * 0.024);
        switch (li) {
            case Terrain.GARDENS:
                // Blackstone and deepslate, with pale limestone and a little ochrum in bold patches.
                if (n > 0.24) return Rock.BLACKSTONE;
                if (n < -0.3) return n < -0.5 ? Rock.OCHRUM : Rock.LIMESTONE;
                return Rock.DEEPSLATE;
            case Terrain.BLUE:
                if (n > 0.28) return Rock.TUFF;
                if (n < -0.28) return Rock.ASURINE;
                return Rock.DEEPSLATE;
            default:
                if (n > 0.22) return Rock.BLACKSTONE;
                if (n < -0.28) return n < -0.45 ? Rock.SCORIA : Rock.SCORCHIA;
                return Rock.BASALT;
        }
    }

    // ============================================================================================== rock near open space

    /**
     * Rock within a few blocks of open space below the pack's old floor.
     *
     * @param above solid blocks from the top of this run of rock down to here (1 = this block is a floor's top)
     * @param below solid blocks from here down to the bottom of the run (1 = this block is a ceiling's underside)
     * @param wet   whether water or lava rests on this run of rock
     */
    public Rock shell(int x, int y, int z, int above, int below, boolean wet) {
        Shape s = t.shape;
        int li = s.layerAt(y);
        if (li < 0) return s.bandAt(y) > 0 ? Rock.BAND_STONE : Rock.DEEPSLATE;
        DeepBiome b = t.biomeAt(x, y, z);
        double n = patch(x, y, z);
        boolean top = above <= 1;
        boolean floor = above <= 3;
        boolean ceil = !floor && below <= 2;
        int W = s.waterY(li);
        switch (b) {
            case LANTERN_LAKE: {
                // A still lake with islands: pale shores, ceilings and shallows, a dark bed under the open water, and
                // walls of cobbled deepslate and blackstone veined with gold.
                boolean slate = n < -0.05;   // cobbled deepslate in place of about half the blackstone
                Rock dark = slate ? Rock.COBBLED_DEEPSLATE : Rock.BLACKSTONE;
                if (floor) {
                    if (wet) {
                        // under 4 or more blocks of water
                        if (y < W - 4) return n > 0.1 ? Rock.GRAVEL : Rock.COBBLED_DEEPSLATE;
                        return n > 0.15 ? Rock.SAND : (n < -0.25 ? Rock.CALCITE : Rock.LIMESTONE);
                    }
                    if (top) return n > 0.28 ? Rock.CALCITE : (n < -0.4 ? Rock.COBBLED_DEEPSLATE : Rock.LIMESTONE);
                    return n > 0.1 ? Rock.LIMESTONE : dark;
                }
                if (ceil) {
                    boolean lime = n > -0.1;
                    if (vein(x, y, z, 0.03)) return lime ? Rock.GOLD_ORE : Rock.DEEPSLATE_GOLD_ORE;
                    return lime ? Rock.LIMESTONE : Rock.COBBLED_DEEPSLATE;
                }
                if (vein(x, y, z, 0.026)) return slate ? Rock.DEEPSLATE_GOLD_ORE : Rock.GILDED_BLACKSTONE;
                if (seam(x, y, z)) return Rock.LIMESTONE;
                return n > 0.3 ? Rock.LIMESTONE : dark;
            }
            case EMBER_GARDENS: {
                // Blackstone and deepslate caverns with pale limestone terraces, brown mushroom masses over about a third
                // of the walls, ceilings and terrace risers, gold and redstone in the rock.
                boolean flesh = n < -0.15;   // brown mushroom masses, about 30 %
                if (floor) {
                    if (wet) return n > 0.15 ? Rock.CALCITE : Rock.LIMESTONE;
                    if (top) return n < -0.3 ? Rock.MUSHROOM : (n > 0.28 ? Rock.CALCITE : Rock.LIMESTONE);
                    return above <= 2 ? (flesh ? Rock.MUSHROOM : Rock.LIMESTONE) : Rock.BLACKSTONE;
                }
                if (flesh) return Rock.MUSHROOM;
                if (speck(x, y, z) < 0.006) return Rock.GILDED_BLACKSTONE;
                if (vein(x, y, z, 0.02)) return speck(x, y, z) < 0.6 ? Rock.DEEPSLATE_GOLD_ORE : Rock.DEEPSLATE_REDSTONE_ORE;
                if (ceil && n > 0.2) return Rock.LIMESTONE;
                return n > 0.4 ? Rock.DEEPSLATE : Rock.BLACKSTONE;
            }
            case RUST_SPRINGS: {
                // Crimsite caverns over a pale mineral crust, with grey stone and granite in the walls.
                if (floor) {
                    if (wet) return n > 0.12 ? Rock.CALCITE : Rock.LIMESTONE;
                    if (top) return n > 0.24 ? Rock.CALCITE : (n < -0.32 ? Rock.GRANITE : Rock.LIMESTONE);
                    return above <= 2 ? Rock.LIMESTONE : Rock.CRIMSITE;
                }
                if (n < -0.38) return Rock.STONE;
                if (n > 0.4) return Rock.GRANITE;
                return Rock.CRIMSITE;
            }
            case ASURINE_GEODES: {
                // Dark rock outside, a band of white calcite, then the asurine lining. The floors are a patchwork of
                // asurine, cream calcite and cobbled deepslate (asurine_geodes.jpg).
                double g = t.geodeShellDepth(x, y, z);
                if (g < 1.7) {
                    if (top && !wet) {
                        if (n > 0.28) return Rock.CALCITE;
                        if (n < -0.05) return Rock.COBBLED_DEEPSLATE;
                    }
                    return Rock.ASURINE;
                }
                if (g < 3.1) return Rock.CALCITE;
                if (g < 4.5) return Rock.SMOOTH_BASALT;
                return n > 0.2 ? Rock.COBBLED_DEEPSLATE : Rock.DEEPSLATE;
            }
            case DEEP_BLUE_RIFT: {
                // deep_blue_rift.jpg: dark deepslate and cobbled deepslate with broad sheets of blue asurine;
                // gravel in the stream.
                if (floor) {
                    if (wet) return n > 0.1 ? Rock.GRAVEL : Rock.DEEPSLATE;
                    if (n < -0.1) return Rock.ASURINE;
                    return n > 0.1 ? Rock.COBBLED_DEEPSLATE : Rock.DEEPSLATE;
                }
                if (speck(x, y, z) < 0.0018) return Rock.DEEPSLATE_DIAMOND_ORE;
                if (n < -0.05) return Rock.ASURINE;
                return n > 0.2 ? Rock.COBBLED_DEEPSLATE : Rock.DEEPSLATE;
            }
            case DEEP_DARK: {
                if (floor && !wet && n < -0.36) return Rock.COBBLED_DEEPSLATE;
                return n > 0.36 ? Rock.TUFF : Rock.DEEPSLATE;
            }
            case OBSIDIAN_TUBES: {
                // Tubes lined with obsidian, crying obsidian only as glints in the walls and vault, patches of pale basalt
                // (about 17 blocks across, so a hub shows both), and obsidian with pebbly basalt tops underfoot.
                double g = t.tubeShellDepth(x, y, z);
                if (g < 1.8) {
                    double m = wobN.noise3(x * 0.06, y * 0.085, z * 0.06);
                    if (floor) return m > 0.1 ? Rock.BASALT : Rock.OBSIDIAN;
                    if (m > (ceil ? 0.0 : 0.2)) return Rock.BASALT;
                    return speck(x, y, z) < 0.05 ? Rock.CRYING_OBSIDIAN : Rock.OBSIDIAN;
                }
                if (g < 3.2) return Rock.BLACKSTONE;
                return n > 0.2 ? Rock.SMOOTH_BASALT : Rock.BASALT;
            }
            default: {
                // Basalt galleries: basalt columns and blackstone floors, gilded blackstone and gold.
                if (floor) {
                    if (wet) return n > 0.18 ? Rock.MAGMA : (n < -0.2 ? Rock.BLACKSTONE : Rock.BASALT);
                    if (y <= W + 2 && n > -0.12) return Rock.MAGMA;
                    if (speck(x, y, z) < 0.02) return Rock.GILDED_BLACKSTONE;
                    return n > -0.1 ? Rock.BLACKSTONE : Rock.BASALT;
                }
                if (n > 0.28) return speck(x, y, z) < 0.035 ? Rock.GILDED_BLACKSTONE : Rock.BLACKSTONE;
                if (speck(x, y, z) < 0.002) return Rock.DEEPSLATE_GOLD_ORE;
                return n > 0.12 ? Rock.SMOOTH_BASALT : Rock.BASALT;
            }
        }
    }

    // ============================================================================================== upper caves

    /**
     * Rock around the two set pieces carved into the pack's caves: the Silver Fern Rift and the Ivory Root Cavern.
     * Null keeps the pack's own choice.
     */
    public Rock upperShell(DeepBiome b, int x, int y, int z, int above, int below, boolean wet) {
        double n = patch(x, y, z);
        boolean top = above <= 1;
        boolean floor = above <= 3;
        boolean ceil = !floor && below <= 2;
        if (b == DeepBiome.SILVER_FERN_RIFT) {
            // Blue cliffs of asurine and deepslate, stepped with mossy ledges, copper in the walls.
            if (floor) {
                if (wet) return n > 0 ? Rock.GRAVEL : Rock.COBBLED_DEEPSLATE;
                if (top) return n > -0.3 ? Rock.MOSS : Rock.COBBLED_DEEPSLATE;
                return n < -0.05 ? Rock.ASURINE : Rock.DEEPSLATE;
            }
            if (vein(x, y, z, 0.026)) return Rock.DEEPSLATE_COPPER_ORE;
            if (n < 0.04) return Rock.ASURINE;
            return n > 0.38 ? Rock.COBBLED_DEEPSLATE : Rock.DEEPSLATE;
        }
        if (b == DeepBiome.IVORY_ROOT_CAVERN) {
            // Pale limestone and calcite over deepslate, a pale shore around the turquoise lake.
            double lake = t.upperLakeY(x, z);
            if (floor) {
                if (wet) return n > 0.18 ? Rock.STONE : (n < -0.22 ? Rock.LIMESTONE : Rock.CALCITE);
                if (top && y <= lake + 1) return Rock.CALCITE;
                return n > 0.26 ? Rock.CALCITE : Rock.LIMESTONE;
            }
            double line = lake + 6 + 6 * wobN.noise2(x * 0.021, z * 0.021);
            if (y < line) return vein(x, y, z, 0.024) ? Rock.IRON_ORE : Rock.STONE;
            if (ceil && n < -0.3) return Rock.DRIPSTONE;
            if (vein(x, y, z, 0.02)) return Rock.IRON_ORE;
            if (seam(x, y, z)) return Rock.CALCITE;
            return n > 0.24 ? Rock.CALCITE : Rock.LIMESTONE;
        }
        return null;
    }

    // ============================================================================================== Rootvaults

    /**
     * Rock around the Rootvaults' grottos, by look (see {@link Rootvaults}): root halls are grey stone with limestone
     * ledges, moss on top and rooted dirt in the ceiling where the roots come through; teal grottos are stone and
     * veridium with mossy ledges; ravines are veridium cliffs with moss and pale limestone on the ledges. Only the
     * shell is dressed: floors, ceilings, and walls within {@code shell} 3 blocks of a grotto. Null keeps the pack's
     * own stone (and its ores) deeper in.
     *
     * @param shell distance from the grotto's open space (its signed distance, positive in rock)
     * @param wet   water rests on this run of rock
     */
    public Rock rootvaults(int look, int x, int y, int z, int above, int below, boolean wet, double shell) {
        boolean top = above <= 1;
        boolean floor = above <= 3;
        boolean ceil = !floor && below <= 2;
        if (!floor && !ceil && shell > 3) return null;
        double n = patch(x, y, z);
        switch (look) {
            case Rootvaults.TEAL:
                if (floor) {
                    if (wet) return n > 0.1 ? Rock.GRAVEL : Rock.STONE;
                    if (top) return n > -0.05 ? Rock.MOSS : Rock.STONE;
                    return n > 0.2 ? Rock.VERIDIUM : Rock.STONE;
                }
                return n > -0.02 ? Rock.VERIDIUM : Rock.STONE;
            case Rootvaults.RAVINE:
                // No plain stone on the faces: the vanilla granite, diorite and andesite blobs placed after this
                // replace stone, and turned whole cliffs brown. Veridium, moss and limestone are not theirs to replace.
                if (floor) {
                    if (wet) return n > 0.0 ? Rock.LIMESTONE : Rock.GRAVEL;
                    if (top) return n > -0.2 ? Rock.MOSS : Rock.LIMESTONE;
                    return n > -0.1 ? Rock.VERIDIUM : Rock.LIMESTONE;
                }
                if (n < -0.4) return Rock.LIMESTONE;
                return n > 0.38 ? Rock.MOSS : Rock.VERIDIUM;
            default:
                if (floor) {
                    if (wet) return n > 0.05 ? Rock.GRAVEL : Rock.STONE;
                    if (top) return n > -0.1 ? Rock.MOSS : (n > -0.35 ? Rock.LIMESTONE : Rock.STONE);
                    return n > 0.15 ? Rock.LIMESTONE : Rock.STONE;
                }
                if (ceil) return n > -0.05 ? Rock.ROOTED_DIRT : Rock.STONE;
                return n > 0.22 ? Rock.LIMESTONE : Rock.STONE;
        }
    }
}
