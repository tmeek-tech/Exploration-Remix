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
    private final Perlin interiorN, patchN, veinN, veinMask, seamN, seamMask, wobN, bandN;

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
        bandN = new Perlin(Hashing.mix(s, 8, 0, 0));
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

    /**
     * The Galleries' glowing seams: the same flat-lying sheets as {@link #seam}, a little thicker, so a cliff 60 or more
     * tall has a few of them to light it where no lava reaches. {@code reach} is the mask below which no seam runs: the
     * lower it is, the further the seams run on through the rock (at 0.06 about one wall block in thirty is ember crust,
     * at -0.12 about one in eighteen).
     */
    private boolean emberSeam(int x, int y, int z, double reach) {
        if (seamMask.noise3(x * 0.015, y * 0.02, z * 0.015) < reach) return false;
        return Math.abs(seamN.noise3(x * 0.012, y * 0.075, z * 0.012)) < 0.03;
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
            case Terrain.GALLERIES:
                // Basalt with bold patches of blackstone and smooth basalt. No scoria: in the Galleries only the Scoria
                // Canyons' walls show it.
                if (n > 0.22) return Rock.BLACKSTONE;
                if (n < -0.3) return Rock.SMOOTH_BASALT;
                return Rock.BASALT;
            default:
                // the Wasteland
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
        return shell(t.biomeAt(x, y, z), li, x, y, z, above, below, wet);
    }

    /**
     * A span of rock in the Scoria Canyons thin enough to have open space over and under it within a few blocks: a
     * natural bridge or the lip of a ledge, which is basalt rather than banded scoria (in the flood band too, but for its
     * magma rim and high-water line).
     */
    private static boolean canyonSpan(int above, int below) { return above + below <= 8; }

    /** {@link #shell} in biome {@code b} of layer {@code li}, whatever the plan puts there (offline swatches use it). */
    Rock shell(DeepBiome b, int li, int x, int y, int z, int above, int below, boolean wet) {
        Shape s = t.shape;
        double n = patch(x, y, z);
        boolean top = above <= 1;
        boolean floor = above <= 3;
        boolean ceil = !floor && below <= 2;
        int W = s.waterY(li);
        // a blind tube's end whose lava pool could not be held by rock: a magma floor instead (Network.tubeStubEnd)
        if (floor && Terrain.basaltLook(li) && t.tubeStubEnd(x, y, z)) return Rock.MAGMA;
        // The Galleries' lava rises Terrain.FLOOD_RISE over its table at the height of its cycle. The walls it reaches
        // are scorched dark (the Scoria Canyons' bands turn to scorchia), magma rims the calm lava, and the high-water
        // line is drawn round every low hall in magma and polished blackstone, so the safe height reads from across a
        // hall. The Obsidian Tubes lie above it.
        if (li == Terrain.GALLERIES && !floor && !ceil && y >= W && b != DeepBiome.OBSIDIAN_TUBES) {
            int high = W + Terrain.surge(li);   // the lowest block the lava never reaches
            if (y < high) {
                if (y <= W + 1) return n > -0.3 ? Rock.MAGMA : Rock.BLACKSTONE;
                if (y >= high - 2) return n > -0.25 ? Rock.MAGMA : Rock.POLISHED_BLACKSTONE;
                if (b == DeepBiome.SCORIA_CANYONS) return canyonSpan(above, below) ? (n > 0.3 ? Rock.POLISHED_BASALT : Rock.BASALT) : scoriaBand(x, y, z, true);
                if (speck(x, y, z) < 0.03) return Rock.MAGMA;
                if (n < -0.38) return Rock.POLISHED_BLACKSTONE;
                return n > 0.02 ? Rock.BLACKSTONE : Rock.BASALT;
            }
        }
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
                // Tubes lined with obsidian, patches of pale basalt (about 17 blocks across, so a hub shows both), and
                // obsidian with pebbly basalt tops underfoot. Crying obsidian glints in the walls and vault (one block of
                // the lining's obsidian in four) and in a few blocks underfoot: nothing glows in the lifeless Galleries
                // but rock and lava, so this refuge is lit by its own lining.
                double g = t.tubeShellDepth(x, y, z);
                if (g < 1.8) {
                    double m = wobN.noise3(x * 0.06, y * 0.085, z * 0.06);
                    if (floor) return m > 0.1 ? Rock.BASALT : (speck(x, y, z) < 0.06 ? Rock.CRYING_OBSIDIAN : Rock.OBSIDIAN);
                    if (m > (ceil ? 0.0 : 0.2)) return Rock.BASALT;
                    return speck(x, y, z) < 0.25 ? Rock.CRYING_OBSIDIAN : Rock.OBSIDIAN;
                }
                if (g < 3.2) return Rock.BLACKSTONE;
                return n > 0.2 ? Rock.SMOOTH_BASALT : Rock.BASALT;
            }
            case ASH_HOLLOWS: {
                // The Wasteland's refuge: pale, bare rock that reads as safe against the dark, overgrown tunnels round it.
                // Smooth basalt and tuff flecked and seamed with calcite; pale floors of tuff and calcite with smooth basalt
                // and dripstone; no magma underfoot.
                if (floor) {
                    if (wet) return n > 0 ? Rock.SMOOTH_BASALT : Rock.BASALT;
                    if (top) {
                        if (n > 0.3) return Rock.CALCITE;
                        if (n < -0.4) return Rock.DRIPSTONE;
                        return n < -0.15 ? Rock.SMOOTH_BASALT : Rock.TUFF;
                    }
                    return n > -0.05 ? Rock.TUFF : Rock.SMOOTH_BASALT;
                }
                if (speck(x, y, z) < 0.02 || seam(x, y, z)) return Rock.CALCITE;
                return n > (ceil ? -0.2 : -0.1) ? Rock.TUFF : Rock.SMOOTH_BASALT;
            }
            case BASALT_GALLERIES: {
                // The Galleries' halls (basalt_galleries.jpg): basalt cliffs, smooth and polished basalt where the columns
                // show, blackstone floors with gilded blackstone, magma by the lava, gold in the rock, and seams and cracks
                // of ember crust that light the cliffs and vaults no lava reaches. No scoria.
                if (floor) {
                    if (wet) return n > 0.1 ? Rock.MAGMA : (n < -0.25 ? Rock.BLACKSTONE : Rock.BASALT);
                    if (y <= W + 2 && n > -0.12) return Rock.MAGMA;
                    if (top && speck(x, y, z) < 0.04) return Rock.GILDED_BLACKSTONE;
                    if (top && n < -0.38) return Rock.POLISHED_BLACKSTONE;
                    return n > -0.15 ? Rock.BLACKSTONE : Rock.BASALT;
                }
                if (ceil) {
                    if (vein(x, y, z, 0.032)) return Rock.EMBER_CRUST;
                    return n > 0.25 ? Rock.BLACKSTONE : Rock.BASALT;
                }
                if (emberSeam(x, y, z, 0.06)) return Rock.EMBER_CRUST;
                if (n > 0.3) return speck(x, y, z) < 0.04 ? Rock.GILDED_BLACKSTONE : Rock.BLACKSTONE;
                if (speck(x, y, z) < 0.003) return Rock.DEEPSLATE_GOLD_ORE;
                if (n < -0.3) return Rock.POLISHED_BASALT;
                return n > 0.08 ? Rock.SMOOTH_BASALT : Rock.BASALT;
            }
            case CALDERA: {
                // The crater where the eruptions will start, and the brightest place in the Galleries: a floor of glowing
                // crust, plates of ember crust and magma between blackstone and basalt; rims of polished blackstone
                // cracked with magma; ember crust seamed through the rims and cracking the vault.
                if (floor) {
                    if (wet) return n > -0.2 ? Rock.MAGMA : Rock.BLACKSTONE;
                    if (y <= W + 2 && n > -0.25) return Rock.MAGMA;
                    if (top) {
                        if (n > 0.32) return Rock.MAGMA;
                        if (n > 0.04) return Rock.EMBER_CRUST;
                        return n > -0.32 ? Rock.BLACKSTONE : Rock.BASALT;
                    }
                    return n > 0 ? Rock.MAGMA : Rock.BLACKSTONE;
                }
                if (ceil) {
                    if (vein(x, y, z, 0.045)) return Rock.EMBER_CRUST;
                    return n > 0 ? Rock.BLACKSTONE : Rock.BASALT;
                }
                if (vein(x, y, z, 0.03)) return Rock.MAGMA;
                if (emberSeam(x, y, z, -0.12)) return Rock.EMBER_CRUST;
                if (n > 0.2) return Rock.POLISHED_BLACKSTONE;
                return n < -0.3 ? Rock.BASALT : Rock.BLACKSTONE;
            }
            case CAUSEWAY: {
                // Basalt columns standing out of the lava: polished and smooth basalt caps on their tops, sheer basalt
                // faces, magma where the lava laps them, and a few cracks of ember crust.
                if (floor) {
                    if (wet) return n > 0 ? Rock.MAGMA : Rock.BASALT;
                    if (y <= W + 1) return n > -0.2 ? Rock.MAGMA : Rock.BASALT;
                    if (top) return n > -0.1 ? Rock.POLISHED_BASALT : Rock.SMOOTH_BASALT;
                    return Rock.BASALT;
                }
                if (vein(x, y, z, ceil ? 0.032 : 0.016)) return Rock.EMBER_CRUST;
                if (ceil) return Rock.BASALT;
                if (n > 0.38) return Rock.POLISHED_BASALT;
                return n < -0.4 ? Rock.SMOOTH_BASALT : Rock.BASALT;
            }
            case SCORIA_CANYONS: {
                // Red rifts of scoria banded with scorchia and both stones' layered forms (scoriaBand), magma along the lava
                // river, a few cracks of ember crust, and basalt bridges: any span of rock thin enough to have open space
                // over and under it within a few blocks (a natural bridge, or the lip of a ledge).
                if (canyonSpan(above, below)) return n > 0.3 ? Rock.POLISHED_BASALT : Rock.BASALT;
                if (floor) {
                    if (wet) return n > -0.1 ? Rock.MAGMA : Rock.SCORCHIA;
                    if (y <= W + 2 && n > -0.15) return Rock.MAGMA;
                    if (top) return n > 0.3 ? Rock.SCORCHIA : Rock.SCORIA;
                    return Rock.SCORIA;
                }
                if (vein(x, y, z, 0.014)) return Rock.EMBER_CRUST;
                return scoriaBand(x, y, z, false);
            }
            default: {
                // The Wasteland, with the old Basalt Galleries' look: basalt columns and blackstone floors, gilded
                // blackstone and gold.
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

    /**
     * The Scoria Canyons' walls: scoria with flat-lying bands of scorchia and of the layered forms of both, about nine
     * blocks apart, wavering a few blocks over a hundred and thickening and thinning along their length. Under the
     * Galleries' high-water line ({@code scorched}) the scoria gives way to scorchia, Create's scorched scoria, banded
     * with its layered form and with blackstone.
     */
    private Rock scoriaBand(int x, int y, int z, boolean scorched) {
        double v = (y + 5.0 * bandN.noise3(x * 0.012, y * 0.02, z * 0.012)) / 9.0;
        double f = v - Math.floor(v);
        double w = 0.1 * bandN.noise3(x * 0.03 + 50.5, y * 0.05, z * 0.03 + 50.5);
        if (scorched) {
            if (f < 0.5 + w) return Rock.SCORCHIA;
            if (f < 0.62 + w) return Rock.LAYERED_SCORCHIA;
            return f < 0.82 ? Rock.BLACKSTONE : Rock.SCORCHIA;
        }
        if (f < 0.46 + w) return Rock.SCORIA;
        if (f < 0.56 + w) return Rock.LAYERED_SCORIA;
        if (f < 0.78 + w) return Rock.SCORCHIA;
        if (f < 0.86 + w) return Rock.LAYERED_SCORCHIA;
        return Rock.SCORIA;
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

    // ============================================================================================== entrances

    /**
     * Rock around the Root Vaults' chambers, by look (see {@link Entrances}): root halls are grey stone with limestone
     * ledges, moss on top and rooted dirt in the ceiling where the roots come through. Under tropical trees (jungles and
     * mangrove swamps) teal grottos are stone and veridium with mossy ledges, and ravines veridium cliffs with moss and
     * pale limestone on the ledges; elsewhere the teal grottos take the root halls' rock, and the ravines are pale
     * limestone cliffs with patches of grey andesite, seams of calcite and moss. Only the shell is dressed: floors,
     * ceilings, and walls within {@code shell} 3 blocks of a chamber. Null keeps the pack's own stone (and its ores)
     * deeper in.
     *
     * @param tropical the cluster lies under tropical trees: veridium
     * @param shell    distance from the chamber's open space (its signed distance, positive in rock)
     * @param wet      water rests on this run of rock
     */
    public Rock rootvaults(int look, boolean tropical, int x, int y, int z, int above, int below, boolean wet, double shell) {
        boolean top = above <= 1;
        boolean floor = above <= 3;
        boolean ceil = !floor && below <= 2;
        if (!floor && !ceil && shell > 3) return null;
        double n = patch(x, y, z);
        if (!tropical && look == Entrances.TEAL) look = Entrances.HALL;
        switch (look) {
            case Entrances.TEAL:
                if (floor) {
                    if (wet) return n > 0.1 ? Rock.GRAVEL : Rock.STONE;
                    if (top) return n > -0.05 ? Rock.MOSS : Rock.STONE;
                    return n > 0.2 ? Rock.VERIDIUM : Rock.STONE;
                }
                return n > -0.02 ? Rock.VERIDIUM : Rock.STONE;
            case Entrances.RAVINE:
                // No plain stone on the faces: the pack's granite and diorite placed after this under birch and flower
                // forests replace all the stone they reach, and turned whole cliffs brown. They match minecraft:stone
                // only, so veridium, moss, limestone, calcite and andesite keep. Vanilla's granite, diorite, dirt and
                // gravel blobs match andesite too, but the pack (WWOO) turns the first three off and keeps gravel off
                // faces in the air; without it they would spot the andesite (about 5 % below y 60), as any cliff.
                if (!tropical) {
                    if (floor) {
                        if (wet) return n > 0.0 ? Rock.LIMESTONE : Rock.GRAVEL;
                        if (top) return n > -0.2 ? Rock.MOSS : Rock.LIMESTONE;
                        return n > 0.12 ? Rock.ANDESITE : Rock.LIMESTONE;
                    }
                    if (n > 0.38) return Rock.MOSS;
                    if (seam(x, y, z)) return Rock.CALCITE;
                    return n > 0.12 ? Rock.ANDESITE : Rock.LIMESTONE;
                }
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

    /**
     * The frozen Root Vaults under snowy forests, by how far a block lies beyond the snowy zone round the openings
     * ({@code chill}, see {@link Entrances#chill}). Near the openings: snow over the floors, drifted two and three deep
     * in places, with patches of packed ice; snow and packed ice in the walls; packed ice in patches on the ceilings
     * (blue ice in specks). Further in it thaws: the snow lies in patches on frost moss and limestone, and deep inside
     * ({@link Entrances#THAW_REACH} and more) the halls are the root halls' grey stone and limestone with frost moss on
     * the ledges and rooted dirt in the ceilings, snow only here and there. Pool beds stay gravel and stone (a feature
     * lays ice on the still water). No veridium: no tropical trees grow on snowy ground.
     */
    public Rock frozenVaults(int look, double chill, int x, int y, int z, int above, int below, boolean wet, double shell) {
        boolean top = above <= 1;
        boolean floor = above <= 3;
        boolean ceil = !floor && below <= 2;
        if (!floor && !ceil && shell > 3) return null;
        double n = patch(x, y, z);
        // 0 in the snowy zone, 1 deep inside
        double t = chill <= 0 ? 0 : Math.min(1, chill / Entrances.THAW_REACH);
        if (floor) {
            if (wet) return n > 0.1 ? Rock.GRAVEL : Rock.STONE;
            double snow = -0.45 + 1.05 * t;
            if (top) {
                if (n > snow) return Rock.SNOW;
                if (t == 0) return n > snow - 0.15 ? Rock.PACKED_ICE : Rock.STONE;
                if (n > snow - 0.25 - 0.45 * t) return Rock.FROST_MOSS;
                return n > -0.5 ? Rock.LIMESTONE : Rock.STONE;
            }
            if (t == 0 && n > 0) return Rock.SNOW; // drifts
            return n > 0.2 ? Rock.LIMESTONE : Rock.STONE;
        }
        if (ceil) {
            if (n > 0.25 + 0.4 * t) return speck(x, y, z) < 0.07 ? Rock.BLUE_ICE : Rock.PACKED_ICE;
            return look != Entrances.RAVINE && n < -0.25 + 0.2 * t ? Rock.ROOTED_DIRT : Rock.STONE;
        }
        if (n > 0.42 + 0.3 * t) return Rock.PACKED_ICE;
        if (t < 0.5 && n > 0.2 + 0.4 * t) return Rock.SNOW;
        if (n < -0.35) return Rock.LIMESTONE;
        return t >= 1 && n > 0.22 ? Rock.LIMESTONE : Rock.STONE;
    }

    /**
     * A sinkhole: limestone over stone in bold patches on its walls, with a seam of calcite; dripstone and limestone
     * under its lip; moss, grass, gravel and cobbles on its talus and shore; clay, sand and gravel under its pool. Under
     * snowy plains, snow on the talus and the shore and packed ice in the walls.
     */
    public Rock sinkhole(int x, int y, int z, int above, int below, boolean wet, double shell, boolean cold) {
        boolean top = above <= 1;
        boolean floor = above <= 3;
        boolean ceil = !floor && below <= 2;
        if (!floor && !ceil && shell > 3) return null;
        double n = patch(x, y, z);
        if (floor) {
            if (wet) return n > 0.15 ? Rock.CLAY : (n > -0.2 ? Rock.SAND : Rock.GRAVEL);
            if (top) {
                if (cold && n > -0.3) return Rock.SNOW;
                if (n > 0.3) return Rock.MOSS;
                if (n > 0.0) return Rock.GRASS;
                if (n > -0.25) return Rock.GRAVEL;
                return speck(x, y, z) < 0.5 ? Rock.MOSSY_COBBLESTONE : Rock.COBBLESTONE;
            }
            return n > 0.1 ? Rock.DIRT : Rock.LIMESTONE;
        }
        if (ceil) return n < -0.3 ? Rock.DRIPSTONE : (n > 0.1 ? Rock.LIMESTONE : Rock.STONE);
        if (cold && n > 0.45) return Rock.PACKED_ICE;
        if (seam(x, y, z)) return Rock.CALCITE;
        return n > -0.12 ? Rock.LIMESTONE : Rock.STONE;
    }

    /**
     * An oasis hollow: sandstone walls with smooth sandstone and bands and patches of ochrum, sandstone ceilings, sand
     * floors (sandstone wherever open space lies right under a floor, so no sand hangs over air), clay and sand round
     * the spring. In the badlands (red): red sandstone, terracotta and ochrum, and red sand.
     */
    public Rock oasis(int x, int y, int z, int above, int below, boolean wet, double shell, boolean red) {
        boolean top = above <= 1;
        boolean floor = above <= 3;
        boolean ceil = !floor && below <= 2;
        if (!floor && !ceil && shell > 3) return null;
        double n = patch(x, y, z);
        Rock sandstone = red ? Rock.RED_SANDSTONE : Rock.SANDSTONE;
        if (floor) {
            if (below <= 1) return sandstone;
            if (wet) return n > 0.0 ? Rock.CLAY : (red ? Rock.RED_SAND : Rock.SAND);
            if (top) {
                if (n < -0.4) return red ? Rock.SMOOTH_RED_SANDSTONE : Rock.SMOOTH_SANDSTONE;
                return red ? Rock.RED_SAND : Rock.SAND;
            }
            return sandstone;
        }
        if (ceil) return red && n > 0.2 ? Rock.TERRACOTTA : sandstone;
        if (seam(x, y, z) || n > 0.42) return Rock.OCHRUM;
        if (red) return n < -0.2 ? Rock.ORANGE_TERRACOTTA : (n < 0.15 ? Rock.RED_SANDSTONE : Rock.TERRACOTTA);
        return n < -0.3 ? Rock.SMOOTH_SANDSTONE : Rock.SANDSTONE;
    }

    /**
     * The Frost Fern Grotto (the pack's caves under cold ground): floors of frost moss with pale limestone ledges, bare
     * stone between them and snow in patches, gravel and clay under its water. The walls and ceilings keep the pack's
     * rock: Immersive Weathering's icicle clusters ice them over in patches, as in its icy caves, and patches of
     * deepslate come from a feature.
     */
    public Rock frostGrotto(int x, int y, int z, int above, int below, boolean wet) {
        boolean top = above <= 1;
        boolean floor = above <= 2;
        if (!floor) return null;
        double n = patch(x, y, z);
        if (wet) return n > 0.1 ? Rock.GRAVEL : Rock.CLAY;
        if (top) {
            if (n > 0.55) return Rock.SNOW;
            if (n > -0.3) return Rock.FROST_MOSS;
            return n > -0.5 ? Rock.LIMESTONE : Rock.STONE;
        }
        return n > 0.0 ? Rock.DIRT : Rock.LIMESTONE;
    }
}
