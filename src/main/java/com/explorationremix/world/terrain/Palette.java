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
    private final Perlin interiorN, patchN, veinN, veinMask, seamN, seamMask, wobN, lookN;

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
        lookN = new Perlin(Hashing.mix(s, 8, 0, 0));
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
                // Deepslate and blackstone, with pale limestone, crimsite and ochrum in bold patches.
                if (n > 0.24) return n > 0.42 ? Rock.CRIMSITE : Rock.BLACKSTONE;
                if (n < -0.3) return n < -0.46 ? Rock.OCHRUM : Rock.LIMESTONE;
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
                // A still lake with islands under a low ceiling of limestone and blackstone veined with gold.
                if (floor) {
                    if (wet) return n > 0.22 ? Rock.CLAY : (n < -0.22 ? Rock.GRAVEL : Rock.SAND);
                    if (top) return n > -0.08 ? Rock.MOSS : Rock.GRASS;
                    return above <= 2 ? Rock.DIRT : Rock.LIMESTONE;
                }
                if (ceil) {
                    boolean lime = n > -0.06;
                    if (vein(x, y, z, 0.03)) return lime ? Rock.GOLD_ORE : Rock.GILDED_BLACKSTONE;
                    return lime ? Rock.LIMESTONE : Rock.BLACKSTONE;
                }
                if (vein(x, y, z, 0.022)) return Rock.DEEPSLATE_GOLD_ORE;
                if (seam(x, y, z)) return Rock.LIMESTONE;
                return n > 0.26 ? Rock.LIMESTONE : (n < -0.3 ? Rock.BLACKSTONE : Rock.DEEPSLATE);
            }
            case EMBER_GARDENS: {
                // Blackstone caverns with pale limestone terraces, gold in the rock.
                if (floor) {
                    if (wet) return n > 0.2 ? Rock.CLAY : Rock.LIMESTONE;
                    if (top) return n > 0.28 ? Rock.CALCITE : Rock.LIMESTONE;
                    return above <= 2 ? Rock.LIMESTONE : Rock.BLACKSTONE;
                }
                if (speck(x, y, z) < 0.004) return Rock.GILDED_BLACKSTONE;
                if (vein(x, y, z, 0.02)) return Rock.DEEPSLATE_GOLD_ORE;
                if (seam(x, y, z)) return Rock.LIMESTONE;
                if (n > 0.34) return Rock.CRIMSITE;
                if (n < -0.38) return Rock.OCHRUM;
                return Rock.BLACKSTONE;
            }
            case RUST_SPRINGS: {
                // Crimsite caverns over a pale mineral crust, the hot pools glowing orange from below.
                if (floor) {
                    if (wet) return speck(x, y, z) < 0.16 ? Rock.OCHRE_FROGLIGHT : (n > 0.12 ? Rock.CALCITE : Rock.LIMESTONE);
                    if (top) return n > 0.24 ? Rock.CALCITE : (n < -0.3 ? Rock.DRIPSTONE : Rock.LIMESTONE);
                    return above <= 2 ? Rock.LIMESTONE : Rock.CRIMSITE;
                }
                if (n < -0.36) return Rock.BLACKSTONE;
                if (n > 0.42) return Rock.GRANITE;
                return Rock.CRIMSITE;
            }
            case ASURINE_GEODES: {
                // Dark rock outside, a band of white calcite, then the asurine lining.
                double g = t.geodeShellDepth(x, y, z);
                if (g < 1.7) return Rock.ASURINE;
                if (g < 3.1) return Rock.CALCITE;
                if (g < 4.5) return Rock.SMOOTH_BASALT;
                return n > 0.3 ? Rock.TUFF : Rock.DEEPSLATE;
            }
            case DEEP_BLUE_RIFT: {
                if (floor) {
                    if (wet) return n > 0.1 ? Rock.GRAVEL : Rock.DEEPSLATE;
                    return n > 0.3 ? Rock.TUFF : (n < -0.32 ? Rock.ASURINE : Rock.DEEPSLATE);
                }
                if (speck(x, y, z) < 0.0018) return Rock.DEEPSLATE_DIAMOND_ORE;
                if (seam(x, y, z)) return Rock.CALCITE;
                if (n < -0.2) return Rock.ASURINE;
                return n > 0.32 ? Rock.TUFF : Rock.DEEPSLATE;
            }
            case DEEP_DARK: {
                if (floor && !wet && n < -0.36) return Rock.COBBLED_DEEPSLATE;
                return n > 0.36 ? Rock.TUFF : Rock.DEEPSLATE;
            }
            case OBSIDIAN_TUBES: {
                // Smooth tubes lined with glassy obsidian, crying obsidian through it for light.
                double g = t.tubeShellDepth(x, y, z);
                if (g < 1.8) return speck(x, y, z) < 0.1 ? Rock.CRYING_OBSIDIAN : Rock.OBSIDIAN;
                if (g < 3.2) return Rock.BLACKSTONE;
                return n > 0.2 ? Rock.SMOOTH_BASALT : Rock.BASALT;
            }
            default: {
                // Basalt galleries: basalt and blackstone with scoria and scorchia, gilded blackstone and gold.
                if (floor) {
                    if (wet) return n > 0.18 ? Rock.MAGMA : (n < -0.2 ? Rock.BLACKSTONE : Rock.BASALT);
                    if (y <= W + 2 && n > -0.12) return Rock.MAGMA;
                    return n > 0.24 ? Rock.BLACKSTONE : (n < -0.34 ? Rock.SCORCHIA : Rock.BASALT);
                }
                if (n > 0.28) return speck(x, y, z) < 0.035 ? Rock.GILDED_BLACKSTONE : Rock.BLACKSTONE;
                if (n < -0.3) return n < -0.48 ? Rock.SCORIA : Rock.SCORCHIA;
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
            // Blue-grey cliffs of asurine and deepslate, stepped with mossy ledges, copper and iron in the walls.
            if (floor) {
                if (wet) return n > 0 ? Rock.GRAVEL : Rock.CLAY;
                if (top) return Rock.MOSS;
                return n < -0.05 ? Rock.ASURINE : Rock.DEEPSLATE;
            }
            if (ceil && n > 0.2) return Rock.LIMESTONE;
            if (vein(x, y, z, 0.026)) return speck(x, y, z) < 0.5 ? Rock.DEEPSLATE_COPPER_ORE : Rock.DEEPSLATE_IRON_ORE;
            if (n < 0.04) return Rock.ASURINE;
            return n > 0.38 ? Rock.TUFF : Rock.DEEPSLATE;
        }
        if (b == DeepBiome.IVORY_ROOT_CAVERN) {
            // Pale limestone and calcite over deepslate, a pale shore around the turquoise lake.
            double lake = t.upperLakeY(x, z);
            if (floor) {
                if (wet) return n > 0.18 ? Rock.CLAY : (n < -0.22 ? Rock.LIMESTONE : Rock.CALCITE);
                if (top && y <= lake + 1) return Rock.CALCITE;
                return n > 0.26 ? Rock.CALCITE : Rock.LIMESTONE;
            }
            double line = lake + 6 + 6 * wobN.noise2(x * 0.021, z * 0.021);
            if (y < line) return vein(x, y, z, 0.024) ? Rock.DEEPSLATE_IRON_ORE : (n > 0.3 ? Rock.TUFF : Rock.DEEPSLATE);
            if (ceil && n < -0.3) return Rock.DRIPSTONE;
            if (vein(x, y, z, 0.02)) return Rock.IRON_ORE;
            if (seam(x, y, z)) return Rock.CALCITE;
            return n > 0.24 ? Rock.CALCITE : Rock.LIMESTONE;
        }
        return null;
    }

    // ============================================================================================== Rootvaults

    /** Which of the three Rootvaults looks a spot has: 0 root halls, 1 teal grottos, 2 ravines open to the sky. */
    public int rootvaultsLook(int x, int z) {
        double v = lookN.noise2(x * 0.0045, z * 0.0045);
        return v < -0.1 ? 0 : (v < 0.14 ? 1 : 2);
    }

    /**
     * Rock in the Rootvaults, above the first band: the pack's stone and deepslate with patches of veridium and
     * limestone. Null keeps the pack's own choice.
     */
    public Rock rootvaults(int x, int y, int z, int above, int below) {
        double n = patch(x, y, z);
        boolean top = above <= 1;
        switch (rootvaultsLook(x, z)) {
            case 0: // root halls: grey stone and limestone ledges, as in the picture, with roots through the ceiling
                if (below <= 2 && n > -0.05) return Rock.ROOTED_DIRT;
                if (n > 0.22 || (top && n > -0.15)) return Rock.LIMESTONE;
                return Rock.STONE;
            case 1: // teal grottos: veridium walls, mossy ledges
                if (top && n > -0.25) return Rock.MOSS;
                if (n > -0.02) return Rock.VERIDIUM;
                return null;
            default: // ravines: veridium cliffs, moss and ferns on every ledge
                if (top) return Rock.MOSS;
                if (n > -0.12) return Rock.VERIDIUM;
                return n < -0.4 ? Rock.LIMESTONE : null;
        }
    }
}
