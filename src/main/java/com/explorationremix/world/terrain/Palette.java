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
    // 0.8.0: the clumps of magma where the Galleries' lava touches the rock
    private final Perlin heatN;
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
        heatN = new Perlin(Hashing.mix(s, 13, 0, 0));
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
        if (li < 0) {
            int band = s.bandAt(y);
            if (band == 1 && t.denRifts) return interior(Terrain.BLUE, x, y, z);
            return band > 0 ? Rock.BAND_STONE : Rock.DEEPSLATE;
        }
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

    /** How far from a Caldera cone's axis, in foot radii, its crater (the eruptions' vent) reaches. */
    static final double CRATER = 0.22;
    /**
     * How far over its cone ground ({@link LookQueries#coneTop}) a block still counts as the cone: the game's blocks follow
     * the field's floor to within a block or two (its density is interpolated between corners four blocks apart).
     */
    static final int CONE_OVER = 3;

    /**
     * Where (x, y, z) lies on a Caldera cone, in foot radii from its axis ({@link LookQueries#cone}), 9 when off any: only
     * the cone's own ground counts, not the walls, hanging masses and vault over its foot, which are the hall's.
     */
    private double coneAt(int x, int y, int z) {
        double d = looks.cone(x, z);
        return d < 1 && y > looks.coneTop(x, z) + CONE_OVER ? 9 : d;
    }

    /**
     * Whether a block of rock round the Galleries' open space touches the table's lava, which fills the open space under
     * {@code W} (its surface is row W - 1). Under that row any open space a block shows is lava. In it, a block touches
     * the lava if it has rock over it (it can only show the lava beside or under it), open space under it, lava resting
     * on it or lava beside it (a bank's edge); a dry column top level with the lava and walled round by rock shows only
     * the air over it, and does not. At W, a block right over the lava ({@code below} 1) does. These are the only blocks
     * that may be magma, but for the Caldera's craters.
     */
    boolean touchesLava(int x, int y, int z, int above, int below, boolean wet, int W) {
        if (y < W - 1) return true;
        if (y == W) return below <= 1;
        return y == W - 1 && (above > 1 || below <= 1 || wet || lavaBeside(x, y, z));
    }

    /** Lava on any of the four sides of (x, y, z), in the blocks as the game builds them ({@link Terrain#rockAt}). */
    private boolean lavaBeside(int x, int y, int z) {
        return lavaAt(x + 1, y, z) || lavaAt(x - 1, y, z) || lavaAt(x, y, z + 1) || lavaAt(x, y, z - 1);
    }

    private boolean lavaAt(int x, int y, int z) { return !t.rockAt(x, y, z) && t.fluidAt(x, y, z) == Terrain.LAVA; }

    /** The clumps of magma on the rock the lava touches: about one such block in eight, in clumps of a few blocks. */
    private boolean heat(int x, int y, int z) { return heatN.noise3(x * 0.11, y * 0.16, z * 0.11) > HEAT; }

    /** The cut that makes {@link #heat} true on about 12 % of blocks. */
    private static final double HEAT = 0.33;

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
     * blackstone doesn't fit. It looks too unnatural."): basalt, smooth basalt, blackstone and gilded blackstone, magma,
     * obsidian and a little crying obsidian, tuff, calcite and dripstone, Create's scoria and scorchia (never their cut or
     * layered forms), and the ember crust; no stripe at a fixed height.
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
        if (look == Terrain.GALLERIES && b != DeepBiome.OBSIDIAN_TUBES) {
            double cone = b == DeepBiome.CALDERA ? coneAt(x, y, z) : 9;
            // Magma only where the table's lava touches the rock, in clumps on about one such block in eight, so the
            // banks stay dark and crisp, lit by the lava itself (picture 1); none on the shore over it, nor on the dry
            // column tops level with the lava a few blocks back from it. The Caldera's craters keep their throats of
            // magma (cone). The cheap tests first: touchesLava may read the blocks beside.
            if (y <= W && heat(x, y, z) && cone >= CRATER && touchesLava(x, y, z, above, below, wet, W)) return Rock.MAGMA;
            // The tide. The Galleries' lava rises Terrain.surge over its table at the height of its cycle and scorches
            // the walls and overhangs it covers. Its mark is not drawn: under it more and more columns are blackstone,
            // a column turning dark once, at its own height, and never back (scorched), so the safe height reads
            // roughly, as the change from grey columns to dark ones. The Obsidian Tubes lie above it, and the Caldera's
            // cones keep their cinders under it.
            if (!floor && y >= W && cone >= 1) {
                double mark = tideMark(x, z, W);
                if (y < mark) return scorched(b, x, y, z, above, below, n, mark, ceil);
            }
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
                // Red rifts of scoria banded with scorchia (scoriaBand), a few cracks of ember crust, and basalt bridges:
                // any span of rock thin enough to have open space over and under it within a few blocks (a natural
                // bridge, or the lip of a ledge).
                if (canyonSpan(above, below)) return n > 0.3 ? Rock.SMOOTH_BASALT : Rock.BASALT;
                if (floor) {
                    if (wet) return n > 0.2 ? Rock.BLACKSTONE : Rock.SCORCHIA;
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
     * The flood's tide mark over column (x, z): 1 to 8 blocks under the lava's highest reach, wandering over a few dozen
     * blocks and a block or two from column to column, but the same all up a column, so no column crosses it twice.
     */
    private double tideMark(int x, int z, int W) {
        return W + Terrain.surge(Terrain.GALLERIES) - 1 - 2.5 * (1 + tideN.noise2(x * 0.045, z * 0.045)) - 2.0 * jitter(x, 0, z);
    }

    /**
     * A wall or overhang the risen lava covers, {@code mark} the tide's mark over it: each column keeps its dry stone
     * (dryColumn) or turns blackstone, a share of the columns that grows from 18 % at the mark to 36 % twelve blocks
     * under it. Only the dry rule's commonest stone turns, and a column that turns stays dark all the way down, so the
     * tide only ever darkens a column, and once (the Scoria Canyons' bands turn to scorchia and blackstone).
     */
    private Rock scorched(DeepBiome b, int x, int y, int z, int above, int below, double n, double mark, boolean ceil) {
        if (b == DeepBiome.SCORIA_CANYONS) return canyonSpan(above, below) ? Rock.BASALT : scoriaBand(x, y, z, true);
        long cell = looks.columnCell(x, z);
        if (cell != 0) return tide(cell, y, mark) ? Rock.BLACKSTONE : dryColumn(b, cell, x, z);
        return n > 0.02 ? Rock.BLACKSTONE : Rock.BASALT;
    }

    /**
     * Whether the tide has turned column {@code cell} to blackstone at row y, {@code mark} the mark over it: the top of
     * columnUnit's range, a share of the columns growing from 18 % at the mark to 36 % twelve blocks under it.
     */
    private boolean tide(long cell, int y, double mark) {
        return y < mark && columnUnit(cell) >= 1 - (0.18 + 0.18 * Math.max(0, Math.min(1, (mark - y) / 12.0)));
    }

    /**
     * Column {@code cell}'s stone in a floor (a terrace's top and the two blocks under it): the stone the column's sides
     * have at that height, its dry stone, or blackstone under the tide's mark where the tide turned it. So a column is
     * one stone from its foot to its top, as in picture 1, and its top is never lighter than its sides.
     */
    private Rock columnFloor(DeepBiome b, long cell, int x, int y, int z, int W) {
        if (y >= W && tide(cell, y, tideMark(x, z, W))) return Rock.BLACKSTONE;
        return dryColumn(b, cell, x, z);
    }

    /**
     * A column's dry stone, by biome, on every face of the column: its walls, its top, its underside and, in a hanging
     * cluster, the whole hanging column, so each column is one stone from end to end. The one column() call the dry
     * rules and the tide share. Each puts its commonest stone (basalt, in the Caldera blackstone) at the top of
     * columnUnit's range, the part the tide turns to blackstone.
     */
    private Rock dryColumn(DeepBiome b, long cell, int x, int z) {
        switch (b) {
            case CALDERA:
                // blackstone-led, as its crust was before its floors took their columns' stone
                return column(cell, Rock.BLACKSTONE, Rock.BASALT, 0.3, Rock.SMOOTH_BASALT, 0.06);
            case CAUSEWAY:
                // the mosaic of its column ends, basalt, smooth basalt and blackstone, now the columns' own stone
                return column(cell, Rock.BASALT, Rock.SMOOTH_BASALT, 0.3, Rock.BLACKSTONE, 0.15);
            default:
                // the halls' columns: dark and grey side by side, a third dark, from about a fifth to well over half
                // from one stretch of wall to the next (picture 1's alternation), hanging clusters alike
                return column(cell, Rock.BASALT, Rock.BLACKSTONE, share(0.35 + 0.3 * columnPatch(x, z)), Rock.SMOOTH_BASALT, 0.1);
        }
    }

    /**
     * The main cavern (the first reference picture): walls of basalt columns, each its own stone (mostly basalt, some
     * blackstone, some smooth basalt, in bold patches), crossed by a few glowing seams of ember crust; terraces of the
     * same column tops, each the stone of its column, with gilded specks on the blackstone and, near the lava, cooling
     * cracks of ember crust; hanging clusters of the same columns; undersides the stone of their columns, the vault
     * between the clusters cracked with ember crust; magma only where the lava touches the rock (basaltShell). Every
     * face of a column is the column's stone, so each reads as one stone from end to end (decision 3, picture 1).
     */
    private Rock galleries(int x, int y, int z, boolean top, boolean floor, boolean ceil, boolean wet, double n, int W) {
        long cell = looks.columnCell(x, z);
        if (floor) {
            if (wet) return n < -0.25 ? Rock.BASALT : Rock.BLACKSTONE;
            if (top && cooling(x, y, z, cell, W)) return Rock.EMBER_CRUST;
            Rock r = cell != 0 ? columnFloor(DeepBiome.BASALT_GALLERIES, cell, x, y, z, W) : n > -0.15 ? Rock.BLACKSTONE : Rock.BASALT;
            return top && r == Rock.BLACKSTONE && speck(x, y, z) < 0.08 ? Rock.GILDED_BLACKSTONE : r;
        }
        boolean hanging = looks.hang(x, y, z) > 0.05;
        if (ceil) {
            if (!hanging && crack(x, y, z, 0.03)) return Rock.EMBER_CRUST;
            if (cell != 0) return dryColumn(DeepBiome.BASALT_GALLERIES, cell, x, z);
            return n > 0.25 ? Rock.BLACKSTONE : Rock.BASALT;
        }
        if (!hanging && (cell != 0 ? glowingJoint(x, y, z) : emberSeam(x, y, z, 0.3))) return Rock.EMBER_CRUST;
        if (speck(x, y, z) < 0.003) return Rock.DEEPSLATE_GOLD_ORE;
        if (cell != 0) {
            Rock r = dryColumn(DeepBiome.BASALT_GALLERIES, cell, x, z);
            return r == Rock.BLACKSTONE && speck(x, y, z) < 0.02 ? Rock.GILDED_BLACKSTONE : r;
        }
        if (n > 0.3) return speck(x, y, z) < 0.04 ? Rock.GILDED_BLACKSTONE : Rock.BLACKSTONE;
        return n > 0.08 ? Rock.SMOOTH_BASALT : Rock.BASALT;
    }

    /**
     * The Caldera, the brightest place in the Galleries: a crust of blackstone-led columns, each one stone to its top,
     * with plates of ember crust on the low ground, cones of cinders ({@link #cone}, on the cones' own ground only:
     * {@link #coneAt}), and walls and a vault seamed and cracked with ember crust; magma where its lava touches the rock
     * (basaltShell) and in the cones' craters.
     */
    private Rock caldera(int x, int y, int z, boolean top, boolean floor, boolean ceil, boolean wet, double n, int W) {
        double d = coneAt(x, y, z);
        if (d < 1) return cone(x, y, z, d, n, W);
        long cell = looks.columnCell(x, z);
        if (floor) {
            if (wet) return n > -0.2 ? Rock.BLACKSTONE : Rock.BASALT;
            // hot ground: ember crust as cooling cracks that thin out up the slopes (patches on every step's top read
            // as orange contour lines on terraces)
            if (top && y < W + 30 && crack(x, y, z, 0.08 * (1 - (y - W) / 30.0))) return Rock.EMBER_CRUST;
            if (cell != 0) return columnFloor(DeepBiome.CALDERA, cell, x, y, z, W);
            return n > -0.32 ? Rock.BLACKSTONE : Rock.BASALT;
        }
        if (ceil) {
            // cracks in networks (crack() leaves most of the vault without any), not a vein sheet cut by every step
            if (crack(x, y, z, 0.045)) return Rock.EMBER_CRUST;
            if (cell != 0) return dryColumn(DeepBiome.CALDERA, cell, x, z);
            return n > 0 ? Rock.BLACKSTONE : Rock.BASALT;
        }
        if (emberSeam(x, y, z, 0.3)) return Rock.EMBER_CRUST;   // as rare as the halls': seams in a bowl read as contour lines
        if (cell != 0) return dryColumn(DeepBiome.CALDERA, cell, x, z);
        return n < -0.3 ? Rock.BASALT : Rock.BLACKSTONE;
    }

    /**
     * A Caldera cone, {@code d} its foot radii from the axis: cinders of scorchia with patches of scoria on the flanks,
     * cooling cracks of ember crust thickening towards the lip, and the crater (the eruptions' vent, within
     * {@link #CRATER} foot radii of the axis) a throat of magma and ember crust.
     */
    private Rock cone(int x, int y, int z, double d, double n, int W) {
        if (d < CRATER) return n > 0.15 ? Rock.MAGMA : speck(x, y, z) < 0.45 ? Rock.EMBER_CRUST : Rock.BLACKSTONE;
        double heat = 1 - (d - CRATER) / (1 - CRATER);
        if (speck(x, y, z) < 0.05 * heat || heat > 0.3 && vein(x, y, z, 0.03 * heat)) return Rock.EMBER_CRUST;
        if (d > 0.8 && n < -0.15) return Rock.BLACKSTONE;
        return n > 0.18 ? Rock.SCORIA : Rock.SCORCHIA;
    }

    /**
     * The Causeway: coarse columns stepping out of the lava, each one stone from its foot to its end (basalt, smooth
     * basalt or blackstone), so their tops make a mosaic of column ends; a little magma where the lava touches them
     * (basaltShell), and a few cracks of ember crust.
     */
    private Rock causeway(int x, int y, int z, boolean top, boolean floor, boolean ceil, boolean wet, double n, int W) {
        long cell = looks.columnCell(x, z);
        if (floor) {
            if (wet) return n > 0.3 ? Rock.BLACKSTONE : Rock.BASALT;
            if (cell != 0) return columnFloor(DeepBiome.CAUSEWAY, cell, x, y, z, W);
            return top && n <= -0.1 ? Rock.SMOOTH_BASALT : Rock.BASALT;
        }
        if (crack(x, y, z, ceil ? 0.04 : 0.02)) return Rock.EMBER_CRUST;   // cracks in networks, not dashes everywhere
        if (cell != 0) return dryColumn(DeepBiome.CAUSEWAY, cell, x, z);
        if (ceil) return Rock.BASALT;
        return n < -0.4 ? Rock.SMOOTH_BASALT : Rock.BASALT;
    }

    /**
     * The Obsidian Tubes (the second reference picture): arches of glossy obsidian, the tube's ribs; between them walls
     * of column grain, basalt with pale tuff and brown scoria columns; a vault of obsidian and basalt (the tube field
     * hangs whole column cells from it, TubeField.hang); stepped floors of obsidian and pale grey tuff and smooth basalt; still
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
     * The Scoria Canyons' walls: scoria with bands of scorchia about nine blocks apart, wavering a few blocks over a
     * hundred, thickening and thinning along their length, and dipping a few degrees one way or another over a few
     * hundred blocks, so no band lies level. Natural stone only: never Create's cut or layered forms, whose dressed
     * courses and framed caps read as masonry. Where the Galleries' lava rises ({@code scorched}) the scoria gives way to
     * scorchia, Create's scorched scoria, banded with blackstone.
     */
    private Rock scoriaBand(int x, int y, int z, boolean scorched) {
        double v = (y + 5.0 * bandN.noise3(x * 0.012, y * 0.02, z * 0.012) + 14.0 * tiltN.noise2(x * 0.006, z * 0.006)) / 9.0;
        double f = v - Math.floor(v);
        double w = 0.1 * bandN.noise3(x * 0.03 + 50.5, y * 0.05, z * 0.03 + 50.5);
        if (scorched) {
            if (f < 0.5 + w) return Rock.SCORCHIA;
            return f < 0.82 ? Rock.BLACKSTONE : Rock.SCORCHIA;
        }
        if (f < 0.46 + w) return Rock.SCORIA;
        if (f < 0.78 + w) return Rock.SCORCHIA;
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
        if (b == DeepBiome.IVORY_ROOT_CAVERN) return ivory(x, y, z, above, below, wet, t.upperLakeY(x, z));
        return null;
    }

    /**
     * The Ivory Root Cavern's rock round a lake (or a hall's lowest terrace) at {@code lake}: pale limestone and calcite,
     * plain stone low on the walls.
     */
    public Rock ivory(int x, int y, int z, int above, int below, boolean wet, double lake) {
        double n = patch(x, y, z);
        boolean top = above <= 1;
        boolean floor = above <= 3;
        boolean ceil = !floor && below <= 2;
        // Pale limestone and calcite over deepslate, a pale shore around the turquoise lake.
        if (floor) {
            if (wet) return n > 0.18 ? Rock.STONE : (n < -0.22 ? Rock.LIMESTONE : Rock.CALCITE);
            if (top && y <= lake + 1) return Rock.CALCITE;
            return n > 0.26 ? Rock.CALCITE : Rock.LIMESTONE;
        }
        double line = lake + 6 + 6 * wobN.noise2(x * 0.021, z * 0.021);
        if (y < line) return vein(x, y, z, 0.024) ? Rock.IRON_ORE : Rock.STONE;
        if (vein(x, y, z, 0.02)) return Rock.IRON_ORE;
        if (seam(x, y, z)) return Rock.CALCITE;
        return n > 0.24 ? Rock.CALCITE : Rock.LIMESTONE;
    }

    // ============================================================================================== entrances

    /**
     * The floors of the Root Vaults (see {@link Entrances} for the looks): moss in broad patches, gravel and bare rock
     * between them, gravel under their pools. Their walls and ceilings are the ground they are carved into (Taran,
     * 2026-11-12): the soil's rooted dirt over the top ones and the host rock round them ({@code HostRule}), so nothing
     * here lines them.
     */
    public Rock rootvaults(int look, boolean tropical, int x, int y, int z, int above, int below, boolean wet, double shell) {
        if (above > 1) return null;
        double n = patch(x, y, z);
        if (wet) return n > 0.05 ? Rock.GRAVEL : null;
        if (look == Entrances.RAVINE) return n > -0.2 ? Rock.MOSS : (n > -0.4 ? Rock.GRAVEL : null);
        return n > -0.1 ? Rock.MOSS : (n > -0.3 ? Rock.GRAVEL : null);
    }

    /**
     * The frozen Root Vaults under snowy forests, by how far a block lies beyond the snowy zone round the openings
     * ({@code chill}, see {@link Entrances#chill}). Near the openings: snow over the floors, drifted two and three deep
     * in places, with patches of packed ice; snow and packed ice on the walls; packed ice in patches on the ceilings
     * (blue ice in specks). Further in it thaws: the snow lies in patches on frost moss, and deep inside
     * ({@link Entrances#THAW_REACH} and more) only here and there. Pool beds are gravel (a feature lays ice on the still
     * water). Everything else is the ground the vaults are carved into ({@code HostRule}).
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
            if (wet) return top && n > 0.1 ? Rock.GRAVEL : null;
            double snow = -0.45 + 1.05 * t;
            if (top) {
                if (n > snow) return Rock.SNOW;
                if (t == 0) return n > snow - 0.15 ? Rock.PACKED_ICE : null;
                return n > snow - 0.25 - 0.45 * t ? Rock.FROST_MOSS : null;
            }
            return t == 0 && n > 0 ? Rock.SNOW : null; // drifts
        }
        if (ceil) return n > 0.25 + 0.4 * t ? (speck(x, y, z) < 0.07 ? Rock.BLUE_ICE : Rock.PACKED_ICE) : null;
        if (n > 0.42 + 0.3 * t) return Rock.PACKED_ICE;
        return t < 0.5 && n > 0.2 + 0.4 * t ? Rock.SNOW : null;
    }

    /**
     * A sinkhole's floors: moss, grass, gravel and cobbles on its talus and shore, clay, sand and gravel under its pool;
     * under snowy plains, snow on the talus and the shore and packed ice in patches on the walls. Its walls are the
     * ground it is carved into ({@code HostRule}): the soil at the rim, then the rock under the plains (limestone in big
     * blobs).
     */
    public Rock sinkhole(int x, int y, int z, int above, int below, boolean wet, double shell, boolean cold) {
        boolean top = above <= 1;
        boolean floor = above <= 3;
        boolean ceil = !floor && below <= 2;
        if (!floor && !ceil && shell > 3) return null;
        double n = patch(x, y, z);
        if (floor) {
            if (!top) return null;
            if (wet) return n > 0.15 ? Rock.CLAY : (n > -0.2 ? Rock.SAND : Rock.GRAVEL);
            if (cold && n > -0.3) return Rock.SNOW;
            if (n > 0.3) return Rock.MOSS;
            if (n > 0.0) return Rock.GRASS;
            if (n > -0.25) return Rock.GRAVEL;
            return speck(x, y, z) < 0.5 ? Rock.MOSSY_COBBLESTONE : Rock.COBBLESTONE;
        }
        return cold && !ceil && n > 0.45 ? Rock.PACKED_ICE : null;
    }

    /**
     * An oasis hollow's floors: sand (bare rock wherever open space lies right under a floor, so no sand hangs over air),
     * clay and sand round the spring; red sand in the badlands. Its walls and ceilings are the ground it is carved into
     * ({@code HostRule}): sandstone in big blobs under deserts, terracotta under badlands.
     */
    public Rock oasis(int x, int y, int z, int above, int below, boolean wet, double shell, boolean red) {
        if (above > 1 || below <= 1) return null;
        double n = patch(x, y, z);
        if (wet) return n > 0.0 ? Rock.CLAY : (red ? Rock.RED_SAND : Rock.SAND);
        return n < -0.4 ? null : (red ? Rock.RED_SAND : Rock.SAND);
    }

    /**
     * The Frost Fern Grotto (the pack's caves under cold ground): floors of frost moss with snow and sheets of packed
     * ice in patches, gravel and clay under its water. Everything under the floor's top, and the walls and ceilings,
     * keep the pack's rock: Immersive Weathering's icicle clusters ice them over in patches, as in its icy caves.
     */
    public Rock frostGrotto(int x, int y, int z, int above, int below, boolean wet) {
        if (above > 2) return null;
        double n = patch(x, y, z);
        if (wet) return n > 0.1 ? Rock.GRAVEL : Rock.CLAY;
        if (above > 1) return null;
        if (n > 0.55) return Rock.SNOW;
        if (n > -0.3) return Rock.FROST_MOSS;
        return n > -0.5 ? Rock.PACKED_ICE : null;
    }
}
