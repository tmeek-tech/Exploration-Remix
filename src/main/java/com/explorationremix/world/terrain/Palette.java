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
    // 0.5.1, the two bottom layers: the flood's tide mark, the ragged join of the Wasteland's and the Galleries' looks,
    // the dip of the Scoria Canyons' bands, and the patches of the terraces' cooling cracks
    private final Perlin tideN, joinN, tiltN, crackN;
    /** What the noise fields of the two bottom layers tell the dressing ({@link LookQueries}). */
    private final LookQueries looks;

    Palette(Terrain t, long seed) { this(t, seed, LookQueries.NONE); }

    Palette(Terrain t, long seed, LookQueries looks) {
        this.t = t;
        this.looks = looks;
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
        tideN = new Perlin(Hashing.mix(s, 9, 0, 0));
        joinN = new Perlin(Hashing.mix(s, 10, 0, 0));
        tiltN = new Perlin(Hashing.mix(s, 11, 0, 0));
        crackN = new Perlin(Hashing.mix(s, 12, 0, 0));
    }

    private double speck(int x, int y, int z) { return Hashing.unit(Hashing.mix(seed, x, y, z)); }

    /** Bold blobs, a few to a few dozen blocks across. */
    private double patch(int x, int y, int z) { return patchN.noise3(x * 0.036, y * 0.05, z * 0.036); }

    /**
     * The patch noise on one level, the same all along a column: what sets the shares of a column's stones, so a
     * column keeps one stone from its foot to its top while the mix changes from one stretch of wall to the next.
     */
    private double columnPatch(int x, int z) { return patchN.noise3(x * 0.036, 0.5, z * 0.036); }

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
        DeepBiome b = t.biomeAt(x, y, z);
        if (Terrain.basaltLook(li)) return basaltShell(lookBiome(b, x, y, z), li, x, y, z, above, below, wet);
        return shell(b, li, x, y, z, above, below, wet);
    }

    /**
     * A span of rock in the Scoria Canyons thin enough to have open space over and under it within a few blocks: a
     * natural bridge or the lip of a ledge, which is basalt rather than banded scoria (in the flood band too, but for its
     * magma rim).
     */
    private static boolean canyonSpan(int above, int below) { return above + below <= 8; }

    /** {@link #shell} in biome {@code b} of layer {@code li}, whatever the field puts there (offline swatches use it). */
    Rock shell(DeepBiome b, int li, int x, int y, int z, int above, int below, boolean wet) {
        if (Terrain.basaltLook(li)) return basaltShell(b, li, x, y, z, above, below, wet);
        Shape s = t.shape;
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
            default:
                return basaltShell(b, li, x, y, z, above, below, wet);
        }
    }

    // ============================================================================================== the two bottom layers

    /**
     * Rows either side of the plane where the Wasteland meets the Galleries (0.5.1: no band between them) over which the
     * two layers' looks blend: a block there takes one look or the other by a ragged boundary, so the stone up the wall of
     * an opening changes along no line.
     */
    static final int JOIN_BLEND = 7;

    /** A hash in [0, 1) per block, apart from {@link #speck}'s. */
    private double jitter(int x, int y, int z) { return Hashing.unit(Hashing.mix(seed ^ 0x2545F4914F6CDD1DL, x, y, z)); }

    /**
     * The layer whose look a block of layer {@code li} takes: its own, but in the {@link #JOIN_BLEND} rows either side
     * of the join, where it is the Wasteland's above a boundary that wanders up to about 4 blocks off the join's plane
     * over a few dozen blocks and 1 block either way from block to block. Layers 0 and 1 and a join with a band between
     * keep their own.
     */
    int lookLayer(int li, int x, int y, int z) {
        Shape s = t.shape;
        int gal = Terrain.GALLERIES, was = Terrain.WASTELAND;
        if ((li != gal && li != was) || gal >= s.layerCount) return li;
        int join = s.top(gal);                       // the Galleries' top row; the Wasteland's bottom row is join + 1
        if (s.bottom(was) != join + 1) return li;
        int d = y - join;
        if (d > JOIN_BLEND || d <= -JOIN_BLEND) return li;
        double v = d - 0.5 + 4.5 * joinN.noise3(x * 0.04, y * 0.06, z * 0.04) + 2.0 * (jitter(x, y, z) - 0.5);
        return v > 0 ? was : gal;
    }

    /**
     * The biome whose look a block of the two bottom layers takes: a neighbour's up to 3 blocks off along x and z, so a
     * sub-biome's border is a ragged zone a few blocks wide rather than a line of changing stone. Never into or out of
     * the Obsidian Tubes, whose lining follows its tube.
     */
    private DeepBiome lookBiome(DeepBiome b, int x, int y, int z) {
        if (b == DeepBiome.OBSIDIAN_TUBES) return b;
        long h = Hashing.mix(seed ^ 0x1B873593CC9E2D51L, x, y, z);
        int dx = (int) Long.remainderUnsigned(h, 7) - 3, dz = (int) Long.remainderUnsigned(h >>> 24, 7) - 3;
        if (dx == 0 && dz == 0) return b;
        DeepBiome o = t.biomeAt(x + dx, y, z + dz);
        return o == null || o == DeepBiome.OBSIDIAN_TUBES ? b : o;
    }

    /** A hash in [0, 1) per basalt column, the same all along it. */
    private double columnUnit(long cell) { return Hashing.unit(Hashing.mix(seed ^ 0x7F4A7C159E3779B9L, cell, 0, 0)); }

    /**
     * One column's stone, the same all along the column, so the columns of a wall, a terrace or a hanging cluster read
     * one by one: {@code b} for a share {@code pb} of the columns, {@code c} for a share {@code pc}, {@code a} for the
     * rest.
     */
    private Rock column(long cell, Rock a, Rock b, double pb, Rock c, double pc) {
        double u = columnUnit(cell);
        return u < pb ? b : u < pb + pc ? c : a;
    }

    private static double share(double p) { return Math.max(0.02, Math.min(0.7, p)); }

    /**
     * Magma along the lava: a ragged rim on the shore at the table's height, where the patch noise is over {@code cut},
     * and here and there a block higher. Null elsewhere. Dry floors more than a block under the table are not shore.
     */
    private Rock lavaRim(int x, int y, int z, double n, int W, double cut) {
        if (y < W - 1 || y > W + 2) return null;
        if (y <= W + 1 && n > cut) return Rock.MAGMA;
        return y == W + 2 && n > cut && speck(x, y, z) < 0.35 ? Rock.MAGMA : null;
    }

    /** Thin cracks, rarer than {@link #vein}'s: about one block in a hundred where they run. */
    private boolean crack(int x, int y, int z, double width) {
        if (veinMask.noise3(x * 0.02, y * 0.03, z * 0.02) < 0.28) return false;
        return Math.abs(veinN.noise3(x * 0.07, y * 0.09, z * 0.07)) < width;
    }

    /**
     * A glowing joint: ember crust in the joints between columns, in vertical streaks a few to a dozen blocks long where
     * a slow noise allows, so a few columns of a cliff show lit cracks between them rather than seams across them.
     */
    private boolean glowingJoint(int x, int y, int z) {
        if (crackN.noise3(x * 0.05, y * 0.035, z * 0.05) < 0.5) return false;
        return looks.columnEdge(x, z) < 0.35;
    }

    /**
     * The shore's cooling cracks: ember crust in the joints between the columns' tops in patches a few dozen blocks
     * across, on the low ground within 14 blocks over the table only, commonest by the lava.
     */
    private boolean cooling(int x, int y, int z, long cell, int W) {
        double near = 1 - (y - W) / 14.0;
        if (near <= 0) return false;
        if (crackN.noise2(x * 0.03, z * 0.03) < 0.5 - 0.25 * near) return false;
        return cell != 0 ? looks.columnEdge(x, z) < 0.5 : crack(x, y, z, 0.05);
    }

    /**
     * Rock near open space in the Wasteland and the Galleries. Natural stone only (Taran, 2026-11-02: "Polished
     * blackstone doesn't fit"): basalt, smooth basalt, blackstone and gilded blackstone, magma, obsidian and a little
     * crying obsidian, tuff, Create's scoria and scorchia and their layered forms, and the ember crust; no stripe at a
     * fixed height.
     */
    private Rock basaltShell(DeepBiome b, int li, int x, int y, int z, int above, int below, boolean wet) {
        int look = lookLayer(li, x, y, z);
        if (look != li) {
            if (look == Terrain.WASTELAND && b != DeepBiome.OBSIDIAN_TUBES && b != DeepBiome.ASH_HOLLOWS) b = DeepBiome.WASTELAND;
            else if (look == Terrain.GALLERIES && b == DeepBiome.WASTELAND) b = DeepBiome.BASALT_GALLERIES;
        }
        Shape s = t.shape;
        double n = patch(x, y, z);
        boolean top = above <= 1;
        boolean floor = above <= 3;
        boolean ceil = !floor && below <= 2;
        int W = s.waterY(look);
        // The tide. The Galleries' lava rises Terrain.surge over its table at the height of its cycle and scorches the
        // walls and overhangs it covers. Its mark is not drawn: the scorched rock reaches 1 to 6 blocks under the
        // highest reach, wandering over a few dozen blocks and ragged block by block, so the safe height reads roughly,
        // as the change from dark scorched rock to grey. The Obsidian Tubes lie above it.
        if (look == Terrain.GALLERIES && !floor && y >= W && b != DeepBiome.OBSIDIAN_TUBES) {
            double mark = W + Terrain.surge(look) - 1 - 2.5 * (1 + tideN.noise2(x * 0.045, z * 0.045)) - 2.0 * jitter(x, y, z);
            if (y < mark) return scorched(b, x, y, z, above, below, n, W);
        }
        switch (b) {
            case OBSIDIAN_TUBES:
                return tubes(x, y, z, top, floor, ceil, wet, n);
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
            case BASALT_GALLERIES:
                return galleries(x, y, z, top, floor, ceil, wet, n, W);
            case CALDERA:
                return caldera(x, y, z, top, floor, ceil, wet, n, W);
            case CAUSEWAY:
                return causeway(x, y, z, top, floor, ceil, wet, n, W);
            case SCORIA_CANYONS: {
                // Red rifts of scoria banded with scorchia and both stones' layered forms (scoriaBand), magma along the
                // lava, a few cracks of ember crust, and basalt bridges: any span of rock thin enough to have open space
                // over and under it within a few blocks (a natural bridge, or the lip of a ledge).
                if (canyonSpan(above, below)) return n > 0.3 ? Rock.SMOOTH_BASALT : Rock.BASALT;
                if (floor) {
                    if (wet) return n > -0.1 ? Rock.MAGMA : Rock.SCORCHIA;
                    Rock rim = lavaRim(x, y, z, n, W, -0.15);
                    if (rim != null) return rim;
                    if (top) return n > 0.3 ? Rock.SCORCHIA : Rock.SCORIA;
                    return Rock.SCORIA;
                }
                if (crack(x, y, z, 0.02)) return Rock.EMBER_CRUST;   // cracks in networks, not dashes everywhere
                return scoriaBand(x, y, z, false);
            }
            default: {
                // The Wasteland, with the old Basalt Galleries' look: basalt columns and blackstone floors, gilded
                // blackstone and gold, magma by its lava.
                if (floor) {
                    if (wet) return n > 0.18 ? Rock.MAGMA : (n < -0.2 ? Rock.BLACKSTONE : Rock.BASALT);
                    if (y >= W - 1 && y <= W + 2 && n > -0.12 && !looks.lavaKept(x, y, z)) return Rock.MAGMA;
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
     * A wall or overhang the risen lava covers: blackstone and basalt scorched dark (the Scoria Canyons' bands turned to
     * scorchia), magma only where the table's lava laps the rock, one to three blocks up, and in cracks just over it.
     */
    private Rock scorched(DeepBiome b, int x, int y, int z, int above, int below, double n, int W) {
        if (y <= W + 1 + (n > 0.2 ? 1 : 0)) return n > -0.3 && speck(x, y, z) < 0.85 ? Rock.MAGMA : Rock.BLACKSTONE;
        if (y <= W + 6 && vein(x, y, z, 0.022)) return Rock.MAGMA;
        if (b == DeepBiome.SCORIA_CANYONS) return canyonSpan(above, below) ? Rock.BASALT : scoriaBand(x, y, z, true);
        long cell = looks.columnCell(x, z);
        if (cell != 0) return column(cell, Rock.BLACKSTONE, Rock.BASALT, share(0.4 - 0.3 * columnPatch(x, z)), Rock.SMOOTH_BASALT, 0.1);
        return n > 0.02 ? Rock.BLACKSTONE : Rock.BASALT;
    }

    /**
     * The main cavern (the first reference picture): walls of basalt columns, each its own stone (mostly basalt, some
     * blackstone, some smooth basalt, in bold patches), crossed by a few glowing seams of ember crust; terraces of
     * blackstone and basalt column tops with gilded specks and, near the lava, cooling cracks of ember crust; hanging
     * clusters of plain basalt columns; the vault between them cracked with ember crust; magma only along the lava.
     */
    private Rock galleries(int x, int y, int z, boolean top, boolean floor, boolean ceil, boolean wet, double n, int W) {
        long cell = looks.columnCell(x, z);
        if (floor) {
            if (wet) return n > 0.1 ? Rock.MAGMA : (n < -0.25 ? Rock.BLACKSTONE : Rock.BASALT);
            Rock rim = lavaRim(x, y, z, n, W, -0.05);
            if (rim != null) return rim;
            if (top && cooling(x, y, z, cell, W)) return Rock.EMBER_CRUST;
            Rock r = cell != 0 ? column(cell, Rock.BLACKSTONE, Rock.BASALT, share(0.36 - 0.25 * columnPatch(x, z)), Rock.SMOOTH_BASALT, 0.1)
                    : n > -0.15 ? Rock.BLACKSTONE : Rock.BASALT;
            return top && r == Rock.BLACKSTONE && speck(x, y, z) < 0.08 ? Rock.GILDED_BLACKSTONE : r;
        }
        boolean hanging = looks.hang(x, y, z) > 0.05;
        if (ceil) {
            if (hanging && cell != 0) return column(cell, Rock.BASALT, Rock.SMOOTH_BASALT, 0.15, Rock.BLACKSTONE, 0.1);
            if (!hanging && crack(x, y, z, 0.03)) return Rock.EMBER_CRUST;
            return n > 0.25 ? Rock.BLACKSTONE : Rock.BASALT;
        }
        if (!hanging && (cell != 0 ? glowingJoint(x, y, z) : emberSeam(x, y, z, 0.3))) return Rock.EMBER_CRUST;
        if (speck(x, y, z) < 0.003) return Rock.DEEPSLATE_GOLD_ORE;
        if (cell != 0) {
            double m = columnPatch(x, z);
            Rock r = column(cell, Rock.BASALT, Rock.BLACKSTONE, share(0.16 + 0.45 * m), Rock.SMOOTH_BASALT, share(0.16 - 0.35 * m));
            return r == Rock.BLACKSTONE && speck(x, y, z) < 0.04 ? Rock.GILDED_BLACKSTONE : r;
        }
        if (n > 0.3) return speck(x, y, z) < 0.04 ? Rock.GILDED_BLACKSTONE : Rock.BLACKSTONE;
        return n > 0.08 ? Rock.SMOOTH_BASALT : Rock.BASALT;
    }

    /**
     * The Caldera, the brightest place in the Galleries: a crust of blackstone and basalt with plates of ember crust
     * and a little magma on the low ground, magma rims round its lava lake, cones of cinders ({@link #cone}), and walls
     * and a vault seamed and cracked with ember crust.
     */
    private Rock caldera(int x, int y, int z, boolean top, boolean floor, boolean ceil, boolean wet, double n, int W) {
        double d = looks.cone(x, z);
        if (d < 1) return cone(x, y, z, d, n, W);
        if (floor) {
            if (wet) return n > -0.2 ? Rock.MAGMA : Rock.BLACKSTONE;
            Rock rim = lavaRim(x, y, z, n, W, -0.25);
            if (rim != null) return rim;
            // hot ground: magma only in patches of the low ground by the lava, ember crust as cooling cracks that thin
            // out up the slopes (patches of either on every step's top read as orange contour lines on terraces)
            if (top && y < W + 8 && n > 0.42) return Rock.MAGMA;
            if (top && y < W + 30 && crack(x, y, z, 0.08 * (1 - (y - W) / 30.0))) return Rock.EMBER_CRUST;
            return n > -0.32 ? Rock.BLACKSTONE : Rock.BASALT;
        }
        // cracks in networks (crack() leaves most of the vault without any), not a vein sheet cut by every step
        if (ceil) return crack(x, y, z, 0.045) ? Rock.EMBER_CRUST : n > 0 ? Rock.BLACKSTONE : Rock.BASALT;
        if (y < W + 16 && vein(x, y, z, 0.03)) return Rock.MAGMA;
        if (emberSeam(x, y, z, 0.3)) return Rock.EMBER_CRUST;   // as rare as the halls': seams in a bowl read as contour lines
        long cell = looks.columnCell(x, z);
        if (cell != 0) return column(cell, Rock.BLACKSTONE, Rock.BASALT, 0.4, Rock.SMOOTH_BASALT, 0.08);
        return n < -0.3 ? Rock.BASALT : Rock.BLACKSTONE;
    }

    /**
     * A Caldera cone, {@code d} its foot radii from the axis: cinders of scorchia with patches of scoria on the flanks,
     * cooling cracks of ember crust thickening towards the lip, and the crater (the eruptions' vent) a throat of magma
     * and ember crust.
     */
    private Rock cone(int x, int y, int z, double d, double n, int W) {
        if (d < 0.22) return n > 0.15 ? Rock.MAGMA : speck(x, y, z) < 0.45 ? Rock.EMBER_CRUST : Rock.BLACKSTONE;
        double heat = 1 - (d - 0.22) / 0.78;
        if (speck(x, y, z) < 0.05 * heat || heat > 0.3 && vein(x, y, z, 0.03 * heat)) return Rock.EMBER_CRUST;
        if (y <= W + 1) return n > -0.2 ? Rock.MAGMA : Rock.BLACKSTONE;
        if (d > 0.8 && n < -0.15) return Rock.BLACKSTONE;
        return n > 0.18 ? Rock.SCORIA : Rock.SCORCHIA;
    }

    /**
     * The Causeway: coarse columns stepping out of the lava, their tops each one stone (the column ends of basalt, smooth
     * basalt or blackstone), sheer faces of basalt, a little magma where the lava laps them, and a few cracks of ember
     * crust. Few of the stepping stones' tops are magma.
     */
    private Rock causeway(int x, int y, int z, boolean top, boolean floor, boolean ceil, boolean wet, double n, int W) {
        long cell = looks.columnCell(x, z);
        if (floor) {
            if (wet) return n > 0 ? Rock.MAGMA : Rock.BASALT;
            if (y <= W + 1 && n > (top ? 0.2 : -0.2)) return Rock.MAGMA;
            if (!top) return Rock.BASALT;
            return cell != 0 ? column(cell, Rock.BASALT, Rock.SMOOTH_BASALT, 0.3, Rock.BLACKSTONE, 0.15)
                    : n > -0.1 ? Rock.BASALT : Rock.SMOOTH_BASALT;
        }
        if (crack(x, y, z, ceil ? 0.04 : 0.02)) return Rock.EMBER_CRUST;   // cracks in networks, not dashes everywhere
        if (ceil) return Rock.BASALT;
        if (cell != 0) return column(cell, Rock.BASALT, Rock.SMOOTH_BASALT, 0.22, Rock.BLACKSTONE, 0.06);
        return n < -0.4 ? Rock.SMOOTH_BASALT : Rock.BASALT;
    }

    /**
     * The Obsidian Tubes (the second reference picture): arches of glossy obsidian, the tube's ribs; between them walls
     * of column grain, basalt with pale tuff and brown scoria columns; a vault of obsidian and basalt (short columns hang
     * under the basalt, ColumnClusterFeature); stepped floors of obsidian and pale grey tuff and smooth basalt; still
     * pools on dark obsidian. Crying obsidian sparingly, about one obsidian block in 40.
     */
    private Rock tubes(int x, int y, int z, boolean top, boolean floor, boolean ceil, boolean wet, double n) {
        double g = looks.tubeShellDepth(x, y, z);
        if (g >= 1.8) return g < 3.2 || n <= 0.2 ? Rock.BASALT : Rock.SMOOTH_BASALT;
        if (floor && wet) return n > 0.25 ? Rock.BLACKSTONE : Rock.OBSIDIAN;
        if (looks.tubeRib(x, y, z)) return obsidian(x, y, z, 0.03);
        double m = wobN.noise3(x * 0.06, y * 0.085, z * 0.06);
        if (floor) {
            if (m > 0.12) return Rock.TUFF;
            if (m > -0.04 && top) return Rock.SMOOTH_BASALT;
            return obsidian(x, y, z, 0.015);
        }
        if (ceil) return m > -0.1 ? Rock.BASALT : obsidian(x, y, z, 0.03);
        if (m < -0.3) return obsidian(x, y, z, 0.02);
        long cell = looks.columnCell(x, z);
        if (cell == 0) cell = Hashing.mix(seed ^ 0x5851F42D4C957F2DL, Math.floorDiv(x, 2), 0, Math.floorDiv(z, 2));
        double c = columnPatch(x, z);
        return column(cell, Rock.BASALT, Rock.TUFF, share(0.22 + 0.3 * c), Rock.SCORIA, share(0.22 - 0.3 * c));
    }

    private Rock obsidian(int x, int y, int z, double crying) {
        return speck(x, y, z) < crying ? Rock.CRYING_OBSIDIAN : Rock.OBSIDIAN;
    }

    /**
     * The Scoria Canyons' walls: scoria with bands of scorchia and of the layered forms of both, about nine blocks
     * apart, wavering a few blocks over a hundred, thickening and thinning along their length, and dipping a few degrees
     * one way or another over a few hundred blocks, so no band lies level. Where the Galleries' lava rises
     * ({@code scorched}) the scoria gives way to scorchia, Create's scorched scoria, banded with its layered form and
     * with blackstone.
     */
    private Rock scoriaBand(int x, int y, int z, boolean scorched) {
        double v = (y + 5.0 * bandN.noise3(x * 0.012, y * 0.02, z * 0.012) + 14.0 * tiltN.noise2(x * 0.006, z * 0.006)) / 9.0;
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
