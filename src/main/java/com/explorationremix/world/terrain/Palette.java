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
    // and the dip of the Basalt Rifts' bands; and the ember crust's glowing joints and cooling cracks (gone with the crust
    // in 0.20.4, back with it in 0.22.9)
    private final Perlin tideN, joinN, tiltN, crackN;
    // 0.8.0: the clumps of magma where the Galleries' lava touches the rock
    private final Perlin heatN;
    // 0.14.5: the wander of the Fern Rift's bands by depth
    private final Perlin riftN;
    // 0.20.1: the wander of the Frost Fern Grotto's icy depths
    private final Perlin iceN;
    /** What the noise fields of the two bottom layers tell the dressing ({@link LookQueries}). */
    private final LookQueries looks;
    /**
     * Under the Deep Gardens (band 3, the Wasteland and the Galleries) the noise and the hashes read rows counted from the
     * world's floor as if it lay at LOW_FLOOR (-832, where it lay until round 3 of the Deep Gardens raised it by 48), so
     * those layers keep their looks wherever the stack puts them, as they keep their shapes
     * ({@link WastelandField#ROUGH_BOT}); over them, the world's own rows.
     */
    static final int LOW_FLOOR = -832;
    /** The highest row {@link #ny} moves (band 3's top) and what it adds to a row there. */
    private final int lowTop, lowDy;

    Palette(Terrain t, long seed) { this(t, seed, LookQueries.NONE); }

    Palette(Terrain t, long seed, LookQueries looks) {
        this.t = t;
        this.looks = looks;
        Shape sh = t.shape;
        lowTop = sh.layerCount > Terrain.WASTELAND ? sh.bandTop(Terrain.WASTELAND + 1) : Integer.MIN_VALUE;
        lowDy = LOW_FLOOR - sh.floorY;
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
        riftN = new Perlin(Hashing.mix(s, 14, 0, 0));
        iceN = new Perlin(Hashing.mix(s, 15, 0, 0));
    }

    /** The row the noise and the hashes read for a block in row y ({@link #LOW_FLOOR}). */
    private int ny(int y) { return y <= lowTop ? y + lowDy : y; }

    private double speck(int x, int y, int z) { return Hashing.unit(Hashing.mix(seed, x, ny(y), z)); }

    /** Bold blobs, a few to a few dozen blocks across. */
    private double patch(int x, int y, int z) { return patchN.noise3(x * 0.036, ny(y) * 0.05, z * 0.036); }

    /**
     * The patch noise on one level, the same all along a column: what sets the shares of a column's stones, so a
     * column keeps one stone from its foot to its top while the mix changes from one stretch of wall to the next.
     */
    private double columnPatch(int x, int z) { return patchN.noise3(x * 0.036, 0.5, z * 0.036); }

    /** Thin, branching veins that come and go. */
    private boolean vein(int x, int y, int z, double width) {
        int yn = ny(y);
        if (veinMask.noise3(x * 0.02, yn * 0.03, z * 0.02) < 0.05) return false;
        return Math.abs(veinN.noise3(x * 0.07, yn * 0.09, z * 0.07)) < width;
    }

    /** Wavy seams one or two blocks thick, mostly flat-lying, that pinch out after a few dozen blocks. */
    private boolean seam(int x, int y, int z) {
        int yn = ny(y);
        if (seamMask.noise3(x * 0.015, yn * 0.02, z * 0.015) < 0.12) return false;
        return Math.abs(seamN.noise3(x * 0.012, yn * 0.075, z * 0.012)) < 0.022;
    }

    // ============================================================================================== deep rock

    /** Rock more than a few blocks from any open space. */
    public Rock interior(int li, int x, int y, int z) {
        double n = interiorN.noise3(x * 0.024, ny(y) * 0.034, z * 0.024);
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
                // Basalt with bold patches of smooth basalt and, sparingly, a lump of scorchia (Taran, 2026-11-23:
                // "Blackstone could be replaced with smooth basalt. Don't use scoria but scorchia could be used
                // sparingly.")
                if (n > 0.22) return Rock.SMOOTH_BASALT;
                if (n < -0.46) return Rock.SCORCHIA;
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
     * A span of rock in the Basalt Rifts thin enough to have open space over and under it within a few blocks: a
     * natural bridge or the lip of a ledge, which is plain basalt rather than the walls' banded flows (in the flood band
     * too, but for its magma rim).
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
                    if (vein(x, y, z, 0.03)) return lime ? Rock.LIMESTONE_GOLD_QUARTZ : Rock.DEEPSLATE_GOLD_QUARTZ;
                    return lime ? Rock.LIMESTONE : Rock.COBBLED_DEEPSLATE;
                }
                if (vein(x, y, z, 0.026)) return slate ? Rock.DEEPSLATE_GOLD_QUARTZ : Rock.GILDED_BLACKSTONE;
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
                Rock rock = ceil && n > 0.2 ? Rock.LIMESTONE : n > 0.4 ? Rock.DEEPSLATE : Rock.BLACKSTONE;
                if (vein(x, y, z, 0.02)) return speck(x, y, z) < 0.6 ? goldIn(rock) : Rock.DEEPSLATE_REDSTONE_ORE;
                return rock;
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
                // Clean bands round the hollow (0.21.7, Taran's card: "Neat bands, tidy crystals"): the asurine lining,
                // its floor too, a band of white calcite, a band of dark smooth basalt, then plain deepslate. The old
                // floors' patchwork of calcite and cobbled deepslate and the cobbled patches outside are gone.
                double g = t.geodeShellDepth(x, y, z);
                if (g < 1.7) return Rock.ASURINE;
                if (g < 3.1) return Rock.CALCITE;
                if (g < 4.5) return Rock.SMOOTH_BASALT;
                return Rock.DEEPSLATE;
            }
            case DEEP_BLUE_RIFT: {
                // deep_blue_rift.jpg: dark deepslate and cobbled deepslate with broad sheets of blue asurine;
                // gravel in the stream.
                if (floor) {
                    if (wet) return n > 0.1 ? Rock.GRAVEL : Rock.DEEPSLATE;
                    if (n < -0.1) return Rock.ASURINE;
                    return n > 0.1 ? Rock.COBBLED_DEEPSLATE : Rock.DEEPSLATE;
                }
                // its diamonds: Minecraft Remix's, in a speck of the kimberlite they come up in (0.23.5)
                if (speck(x, y, z) < 0.0018) return Rock.KIMBERLITE_DIAMOND;
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
    private double jitter(int x, int y, int z) { return Hashing.unit(Hashing.mix(seed ^ 0x2545F4914F6CDD1DL, x, ny(y), z)); }

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
        double v = d - 0.5 + 4.5 * joinN.noise3(x * 0.04, ny(y) * 0.06, z * 0.04) + 2.0 * (jitter(x, y, z) - 0.5);
        return v > 0 ? was : gal;
    }

    /**
     * The biome whose look a block of the two bottom layers takes: a neighbour's up to 3 blocks off along x and z, so a
     * sub-biome's border is a ragged zone a few blocks wide rather than a line of changing stone. Never into or out of
     * the Obsidian Tubes, whose lining follows its tube.
     */
    private DeepBiome lookBiome(DeepBiome b, int x, int y, int z) {
        if (b == DeepBiome.OBSIDIAN_TUBES) return b;
        long h = Hashing.mix(seed ^ 0x1B873593CC9E2D51L, x, ny(y), z);
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
    private boolean heat(int x, int y, int z) { return heatN.noise3(x * 0.11, ny(y) * 0.16, z * 0.11) > HEAT; }

    /** The cut that makes {@link #heat} true on about 12 % of blocks. */
    private static final double HEAT = 0.33;

    // ============================================================================================== the ember crust
    // 0.22.9 (Taran, 2026-11-25: "re-add ember crust to light up the lower Galleries but give it a texture that blends in
    // better with the basalt"): basalt or smooth basalt with a crack in it that still glows (ERBlocks.EMBER_CRUST, light
    // 13), the light of the lower Galleries where no lava reaches. Built at the cards' recommended answers: glowing
    // fissures in deep red, on the cliffs, walls and tops, lighting most of the high ground, in the halls, the Caldera, the
    // Causeway and the Basalt Rifts. It is laid in three ways, each a stone's own crack (ember): glowing joints down the
    // walls and cliffs, cooling cracks across the tops, and cracks in networks over the vaults. Never on a hanging
    // cluster, under the flood's tide mark (its lava lights the walls it covers), in a wet floor, in scorchia or in the
    // Obsidian Tubes.

    /** How far the walls' and cliffs' glowing joints run: the cut on their slow noise (lower runs further). */
    static final double EMBER_JOINT = 0.42;
    /**
     * The cooling cracks across the tops: one at most in each square of EMBER_CELL blocks, in EMBER_TOP of the squares,
     * each the joints within 1.2 to 2.5 blocks of a point jittered over its square. With the walls' joints they light
     * about two thirds of the high ground to 6 or more (EmberCrustTest: 0.69 and 0.67 of the two boxes' places, with
     * about one top block in nineteen crust); squares of 12 in 0.8 of them light about half (0.51, 0.48).
     */
    static final double EMBER_TOP = 0.9;
    static final int EMBER_CELL = 10;
    /** The width of the vaults' cracks, and of the Basalt Rifts' walls' and the Caldera's cones'. */
    static final double EMBER_VAULT = 0.035, EMBER_RIFT = 0.03;

    /**
     * The ember crust in stone {@code r}: the smooth ember crust in smooth basalt, the ember crust in basalt, so the crust
     * is always the stone round it with a crack in it; any other stone (scorchia, magma, an ore) keeps no crust.
     */
    static Rock ember(Rock r) {
        if (r == Rock.BASALT) return Rock.EMBER_CRUST;
        if (r == Rock.SMOOTH_BASALT) return Rock.SMOOTH_EMBER_CRUST;
        return r;
    }

    /**
     * Gold in stone {@code r}: Minecraft Remix's gold quartz ore in that stone (vanilla's gold ore without it), so a
     * speck of gold is the rock round it with gold in it. Deepslate's for a stone that has none.
     */
    static Rock goldIn(Rock r) {
        return switch (r) {
            case LIMESTONE -> Rock.LIMESTONE_GOLD_QUARTZ;
            case BLACKSTONE -> Rock.BLACKSTONE_GOLD_QUARTZ;
            case BASALT -> Rock.BASALT_GOLD_QUARTZ;
            case SMOOTH_BASALT -> Rock.SMOOTH_BASALT_GOLD_QUARTZ;
            case SCORCHIA -> Rock.SCORCHIA_GOLD_QUARTZ;
            default -> Rock.DEEPSLATE_GOLD_QUARTZ;
        };
    }

    /** Thin cracks in networks, rarer than {@link #vein}'s: about one block in a hundred where they run. */
    private boolean crack(int x, int y, int z, double width) {
        int yn = ny(y);
        if (veinMask.noise3(x * 0.02, yn * 0.03, z * 0.02) < 0.28) return false;
        return Math.abs(veinN.noise3(x * 0.07, yn * 0.09, z * 0.07)) < width;
    }

    /**
     * A glowing joint in a wall or cliff: the joints between its columns ({@code columnEdge} under 0.35) in upright
     * streaks a few to a dozen blocks long where a slow noise passes {@link #EMBER_JOINT}, so here and there a lit crack
     * runs down between two columns, and the crust's own crack runs on from block to block down it.
     */
    private boolean glowingJoint(int x, int y, int z) {
        if (crackN.noise3(x * 0.05, ny(y) * 0.035, z * 0.05) < EMBER_JOINT) return false;
        return looks.columnEdge(x, z) < 0.35;
    }

    /**
     * Cooling cracks across a top: the joints between the column tops ({@code columnEdge} under 0.5) round points
     * scattered a square of {@link #EMBER_CELL} apart, a small lit web every ten blocks or so, so each lights its own
     * patch of the ground and few blocks of it are crust.
     */
    private boolean coolingCrack(int x, int z) {
        int cx = Math.floorDiv(x, EMBER_CELL), cz = Math.floorDiv(z, EMBER_CELL);
        for (int i = cx - 1; i <= cx + 1; i++) {
            for (int k = cz - 1; k <= cz + 1; k++) {
                long h = Hashing.mix(seed ^ 0x3C6EF372FE94F82AL, i, 0, k);
                if (Hashing.unit(h) >= EMBER_TOP) continue;
                double px = (i + 0.1 + 0.8 * Hashing.unit(Hashing.mix(h, 1, 0, 0))) * EMBER_CELL;
                double pz = (k + 0.1 + 0.8 * Hashing.unit(Hashing.mix(h, 2, 0, 0))) * EMBER_CELL;
                double r = 1.2 + 1.3 * Hashing.unit(Hashing.mix(h, 3, 0, 0)), dx = x + 0.5 - px, dz = z + 0.5 - pz;
                if (dx * dx + dz * dz <= r * r) return looks.columnEdge(x, z) < 0.5;
            }
        }
        return false;
    }

    /** A hanging cluster's rock, which keeps no crust: the crust lights the ground, not the masses hanging over it. */
    private boolean hanging(int x, int y, int z) {
        return looks.hang(x, y, z) > 0.05;
    }

    /**
     * Rock near open space in the Wasteland and the Galleries. Natural stone only (Taran, 2026-11-02: "Polished
     * blackstone doesn't fit. It looks too unnatural."), never Create's cut or layered forms, and no stripe at a fixed
     * height. The Wasteland: basalt, smooth basalt, blackstone and gilded blackstone, magma, tuff, calcite and dripstone.
     * The Galleries (Taran, 2026-11-23: "Blackstone could be replaced with smooth basalt. Don't use scoria but scorchia
     * could be used sparingly. Remove ember crust."): basalt and smooth basalt, scorchia sparingly (a few columns, the
     * Caldera's cinder cones, thin bands in the Basalt Rifts), magma where the lava touches the rock, obsidian and a
     * little crying obsidian, tuff in the Obsidian Tubes, and a speck of gold ore; no blackstone or scoria. Since 0.22.9
     * the ember crust again, as basalt and smooth basalt with a glowing crack in them (the ember crust's section).
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
            // the walls and overhangs it covers. Its mark is not drawn: under it more and more columns are smooth basalt,
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
                // The Basalt Rifts (the Scoria Canyons before 0.20.4): walls of stacked lava flows, basalt banded with
                // smooth basalt and thin scorchia flow tops (riftBand), and plain basalt bridges: any span of rock thin
                // enough to have open space over and under it within a few blocks (a natural bridge, or the lip of a
                // ledge). Floors of the same stones, scorchia only by the lava.
                if (canyonSpan(above, below)) return n > 0.3 ? Rock.SMOOTH_BASALT : Rock.BASALT;
                if (floor) {
                    if (wet) return n > -0.35 ? Rock.SMOOTH_BASALT : Rock.SCORCHIA;
                    if (top) {
                        Rock r = n > 0.25 ? Rock.SMOOTH_BASALT : Rock.BASALT;
                        return coolingCrack(x, z) ? ember(r) : r;
                    }
                    return Rock.BASALT;
                }
                // 0.22.9: cracks of ember crust in networks across the flows (riftBand's scorchia tops keep none)
                Rock r = riftBand(x, y, z, false);
                return crack(x, y, z, EMBER_RIFT) && !hanging(x, y, z) ? ember(r) : r;
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
                Rock rock = n > 0.12 ? Rock.SMOOTH_BASALT : Rock.BASALT;
                return speck(x, y, z) < 0.002 ? goldIn(rock) : rock;
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
     * (dryColumn) or turns smooth basalt, a share of the columns that grows from 18 % at the mark to 36 % twelve blocks
     * under it. Only the dry rule's commonest stone turns, and a column that turns stays dark all the way down, so the
     * tide only ever darkens a column, and once (the Basalt Rifts' basalt flows turn to smooth basalt).
     */
    private Rock scorched(DeepBiome b, int x, int y, int z, int above, int below, double n, double mark, boolean ceil) {
        if (b == DeepBiome.SCORIA_CANYONS) return canyonSpan(above, below) ? Rock.BASALT : riftBand(x, y, z, true);
        long cell = looks.columnCell(x, z);
        if (cell != 0) return tide(cell, y, mark) ? Rock.SMOOTH_BASALT : dryColumn(b, cell, x, z);
        return n > 0.02 ? Rock.SMOOTH_BASALT : Rock.BASALT;
    }

    /**
     * Whether the tide has turned column {@code cell} to smooth basalt at row y, {@code mark} the mark over it: the top
     * of columnUnit's range, a share of the columns growing from 18 % at the mark to 36 % twelve blocks under it.
     */
    private boolean tide(long cell, int y, double mark) {
        return y < mark && columnUnit(cell) >= 1 - (0.18 + 0.18 * Math.max(0, Math.min(1, (mark - y) / 12.0)));
    }

    /**
     * Column {@code cell}'s stone in a floor (a terrace's top and the two blocks under it): the stone the column's sides
     * have at that height, its dry stone, or smooth basalt under the tide's mark where the tide turned it. So a column is
     * one stone from its foot to its top, as in picture 1, and its top is never lighter than its sides.
     */
    private Rock columnFloor(DeepBiome b, long cell, int x, int y, int z, int W) {
        if (y >= W && tide(cell, y, tideMark(x, z, W))) return Rock.SMOOTH_BASALT;
        return dryColumn(b, cell, x, z);
    }

    /**
     * A column's dry stone, by biome, on every face of the column: its walls, its top, its underside and, in a hanging
     * cluster, the whole hanging column, so each column is one stone from end to end. The one column() call the dry
     * rules and the tide share. Each puts its commonest stone (basalt, in the Caldera smooth basalt) at the top of
     * columnUnit's range, the part the tide turns to smooth basalt.
     */
    private Rock dryColumn(DeepBiome b, long cell, int x, int z) {
        switch (b) {
            case CALDERA:
                // smooth-basalt-led, dark round its lake (no scorchia: the cones are its cinders)
                return column(cell, Rock.SMOOTH_BASALT, Rock.BASALT, 0.35, Rock.SMOOTH_BASALT, 0);
            case CAUSEWAY:
                // the mosaic of its column ends, basalt and smooth basalt with a rare scorchia prism, the columns' own stone
                return column(cell, Rock.BASALT, Rock.SMOOTH_BASALT, 0.4, Rock.SCORCHIA, 0.03);
            default:
                // the halls' columns: dark and grey side by side, about two in five dark, from about a quarter to two
                // thirds from one stretch of wall to the next (picture 1's alternation), hanging clusters alike, and one
                // column in forty scorchia
                return column(cell, Rock.BASALT, Rock.SMOOTH_BASALT, share(0.4 + 0.3 * columnPatch(x, z)), Rock.SCORCHIA, 0.025);
        }
    }

    /**
     * The halls (the first reference picture): walls of basalt columns, each its own stone (mostly basalt, some smooth
     * basalt, in bold patches, now and then a scorchia column); terraces of the same column tops, each the stone of its
     * column; hanging clusters of the same columns; undersides the stone of their columns; magma only where the lava
     * touches the rock (basaltShell), and a speck of gold ore in the walls. Every face of a column is the column's stone,
     * so each reads as one stone from end to end (decision 3, picture 1). Since 0.22.9 the ember crust lights them again:
     * glowing joints down the walls and the high ground's cliffs, cooling cracks across its tops and the floors, cracks
     * over the vaults, each in the stone of its column; the lava and its falls light the rest. (0.20.4 took the old,
     * orange crust out; 0.22.4 took out the unseen glow.)
     */
    private Rock galleries(int x, int y, int z, boolean top, boolean floor, boolean ceil, boolean wet, double n, int W) {
        long cell = looks.columnCell(x, z);
        if (floor) {
            if (wet) return n < -0.25 ? Rock.BASALT : Rock.SMOOTH_BASALT;
            Rock r = cell != 0 ? columnFloor(DeepBiome.BASALT_GALLERIES, cell, x, y, z, W) : n > -0.15 ? Rock.SMOOTH_BASALT : Rock.BASALT;
            return top && coolingCrack(x, z) ? ember(r) : r;
        }
        if (ceil) {
            Rock r = cell != 0 ? dryColumn(DeepBiome.BASALT_GALLERIES, cell, x, z) : n > 0.25 ? Rock.SMOOTH_BASALT : Rock.BASALT;
            return crack(x, y, z, EMBER_VAULT) && !hanging(x, y, z) ? ember(r) : r;
        }
        Rock r = cell != 0 ? dryColumn(DeepBiome.BASALT_GALLERIES, cell, x, z) : n > 0.08 ? Rock.SMOOTH_BASALT : Rock.BASALT;
        if (speck(x, y, z) < 0.003) return goldIn(r);
        return glowingJoint(x, y, z) && !hanging(x, y, z) ? ember(r) : r;
    }

    /**
     * The Caldera, the brightest place in the Galleries for its lava lake: a crust of dark, smooth-basalt-led columns,
     * each one stone to its top, round the lake, cones of scorchia cinders ({@link #cone}, on the cones' own ground only:
     * {@link #coneAt}), magma where its lava touches the rock (basaltShell) and in the cones' craters; since 0.22.9 the
     * ember crust's joints, cooling cracks and vault cracks as in the halls.
     */
    private Rock caldera(int x, int y, int z, boolean top, boolean floor, boolean ceil, boolean wet, double n, int W) {
        double d = coneAt(x, y, z);
        if (d < 1) return cone(x, y, z, d, n, W);
        long cell = looks.columnCell(x, z);
        if (floor) {
            if (wet) return n > -0.2 ? Rock.SMOOTH_BASALT : Rock.BASALT;
            Rock r = cell != 0 ? columnFloor(DeepBiome.CALDERA, cell, x, y, z, W) : n > -0.32 ? Rock.SMOOTH_BASALT : Rock.BASALT;
            return top && coolingCrack(x, z) ? ember(r) : r;
        }
        if (ceil) {
            Rock r = cell != 0 ? dryColumn(DeepBiome.CALDERA, cell, x, z) : n > 0 ? Rock.SMOOTH_BASALT : Rock.BASALT;
            return crack(x, y, z, EMBER_VAULT) && !hanging(x, y, z) ? ember(r) : r;
        }
        Rock r = cell != 0 ? dryColumn(DeepBiome.CALDERA, cell, x, z) : n < -0.3 ? Rock.BASALT : Rock.SMOOTH_BASALT;
        return glowingJoint(x, y, z) && !hanging(x, y, z) ? ember(r) : r;
    }

    /**
     * A Caldera cone, {@code d} its foot radii from the axis: cinders of scorchia (the Galleries' one big show of it)
     * with patches of basalt on the lower flanks, and the crater (the eruptions' vent, within {@link #CRATER} foot radii
     * of the axis) a throat of magma and scorchia. Since 0.22.9 the basalt patches are cracked with ember crust, more of
     * it towards the crater (the cinders keep none).
     */
    private Rock cone(int x, int y, int z, double d, double n, int W) {
        if (d < CRATER) return n > 0.15 || speck(x, y, z) < 0.4 ? Rock.MAGMA : Rock.SCORCHIA;
        Rock r = d > 0.75 && n < -0.15 ? Rock.SMOOTH_BASALT : n > 0.32 ? Rock.BASALT : Rock.SCORCHIA;
        double heat = 1 - (d - CRATER) / (1 - CRATER);
        return r != Rock.SCORCHIA && crack(x, y, z, EMBER_RIFT * (0.5 + heat)) ? ember(r) : r;
    }

    /**
     * The Causeway: coarse columns stepping out of the lava, each one stone from its foot to its end (basalt or smooth
     * basalt, a rare one scorchia), so their tops make a mosaic of column ends; a little magma where the lava touches
     * them (basaltShell); since 0.22.9 the ember crust's joints between its prisms, cooling cracks across their tops and
     * cracks over its vault.
     */
    private Rock causeway(int x, int y, int z, boolean top, boolean floor, boolean ceil, boolean wet, double n, int W) {
        long cell = looks.columnCell(x, z);
        if (floor) {
            if (wet) return n > 0.3 ? Rock.SMOOTH_BASALT : Rock.BASALT;
            Rock r = cell != 0 ? columnFloor(DeepBiome.CAUSEWAY, cell, x, y, z, W) : top && n <= -0.1 ? Rock.SMOOTH_BASALT : Rock.BASALT;
            return top && coolingCrack(x, z) ? ember(r) : r;
        }
        Rock r = cell != 0 ? dryColumn(DeepBiome.CAUSEWAY, cell, x, z) : ceil ? Rock.BASALT : n < -0.4 ? Rock.SMOOTH_BASALT : Rock.BASALT;
        if (ceil) return crack(x, y, z, EMBER_VAULT) && !hanging(x, y, z) ? ember(r) : r;
        return glowingJoint(x, y, z) && !hanging(x, y, z) ? ember(r) : r;
    }

    /**
     * The Obsidian Tubes (the second reference picture): arches of glossy obsidian, the tube's ribs; between them walls
     * of column grain, basalt with pale tuff and dark smooth basalt columns; a vault of obsidian and basalt (the tube
     * field hangs whole column cells from it, TubeField.hang); stepped floors of obsidian and pale grey tuff and smooth
     * basalt; still pools on dark obsidian. Crying obsidian sparingly, about one obsidian block in 40.
     */
    private Rock tubes(int x, int y, int z, boolean top, boolean floor, boolean ceil, boolean wet, double n) {
        double g = looks.tubeShellDepth(x, y, z);
        if (g >= 1.8) return g < 3.2 || n <= 0.2 ? Rock.BASALT : Rock.SMOOTH_BASALT;
        if (floor && wet) return n > 0.25 ? Rock.SMOOTH_BASALT : Rock.OBSIDIAN;
        if (looks.tubeRib(x, y, z)) return obsidian(x, y, z, 0.03);
        double m = wobN.noise3(x * 0.06, ny(y) * 0.085, z * 0.06);
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
        return column(cell, Rock.BASALT, Rock.TUFF, share(0.22 + 0.3 * c), Rock.SMOOTH_BASALT, share(0.22 - 0.3 * c));
    }

    private Rock obsidian(int x, int y, int z, double crying) {
        return speck(x, y, z) < crying ? Rock.CRYING_OBSIDIAN : Rock.OBSIDIAN;
    }

    /**
     * The Basalt Rifts' walls: stacked lava flows about nine blocks thick, each a band of basalt over one of smooth basalt
     * with a thin scorchia top (a block or so, where the flow's crust cooled to cinders), wavering a few blocks over a
     * hundred, thickening and thinning along their length, and dipping a few degrees one way or another over a few
     * hundred blocks, so no band lies level. Natural stone only: never Create's cut or layered forms, whose dressed
     * courses and framed caps read as masonry. Where the Galleries' lava rises ({@code scorched}) the basalt gives way to
     * smooth basalt, the scorchia tops staying.
     */
    private Rock riftBand(int x, int y, int z, boolean scorched) {
        int yn = ny(y);
        double v = (yn + 5.0 * bandN.noise3(x * 0.012, yn * 0.02, z * 0.012) + 14.0 * tiltN.noise2(x * 0.006, z * 0.006)) / 9.0;
        double f = v - Math.floor(v);
        double w = 0.1 * bandN.noise3(x * 0.03 + 50.5, yn * 0.05, z * 0.03 + 50.5);
        if (f >= 0.86 + 0.5 * w && f < 0.95 + 0.5 * w) return Rock.SCORCHIA;
        if (scorched) return Rock.SMOOTH_BASALT;
        return f < 0.5 + w ? Rock.BASALT : Rock.SMOOTH_BASALT;
    }

    // ============================================================================================== the Fern Rift's bands

    /**
     * The Fern Rift changes with depth (Taran, 17 Nov: "Plants changing with depth is pretty"): lush green up top,
     * silver through the middle, light blue by the floor, where the Blue Reaches lie close under it. A block's band
     * comes from its height over the pack's old floor, wobbled by a slow noise so the edges wander, and the bands melt
     * into each other over a few blocks. The rifts lie from about 4 to 55 blocks over that floor (UpperShapes'
     * RIFT_FLOOR and RIFT_HEIGHT), so most hold all three; the den gates' rifts, which go on down into the Blue
     * Reaches, are blue all the way down. {@link #riftBand} gives the bands' shares, which the rift's plants keep to
     * ({@code RiftBandPlacement}); the rock and the moss take the band a block lies in most ({@link #upperShell}).
     */
    public static final int RIFT_GREEN = 0, RIFT_SILVER = 1, RIFT_BLUE = 2;
    /** Blue under this height over the old floor, green over {@link #RIFT_GREEN_FOOT}, each melting over RIFT_BLEND. */
    public static final double RIFT_BLUE_TOP = 21, RIFT_GREEN_FOOT = 32, RIFT_BLEND = 3.5, RIFT_WOBBLE = 4;

    /** A block's height over the pack's old floor as the bands see it, wobbled. */
    public double riftDepth(int x, int y, int z) {
        return y - t.shape.packFloor + RIFT_WOBBLE * riftN.noise3(x * 0.045, y * 0.07, z * 0.045);
    }

    /** How much a block lies in a band (RIFT_GREEN, RIFT_SILVER or RIFT_BLUE), 0 to 1; the three add up to 1. */
    public double riftBand(int band, int x, int y, int z) {
        double h = riftDepth(x, y, z);
        double green = ramp((h - RIFT_GREEN_FOOT) / RIFT_BLEND), blue = ramp((RIFT_BLUE_TOP - h) / RIFT_BLEND);
        return band == RIFT_GREEN ? green : band == RIFT_BLUE ? blue : Math.max(0, 1 - green - blue);
    }

    /** The band a block of rock lies in most, its edges dithered a block or so. */
    public int riftBandAt(int x, int y, int z) {
        double h = riftDepth(x, y, z) + 2.0 * (speck(x, y, z) - 0.5);
        return h >= RIFT_GREEN_FOOT ? RIFT_GREEN : h < RIFT_BLUE_TOP ? RIFT_BLUE : RIFT_SILVER;
    }

    /** -1 to 1 onto 0 to 1, smoothly (0.5 at 0). */
    private static double ramp(double u) {
        double v = Math.max(0, Math.min(1, (u + 1) * 0.5));
        return v * v * (3 - 2 * v);
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
            // Cliffs of deepslate and asurine, stepped with mossy ledges, copper in the walls, changing with depth
            // (0.14.5): vanilla's moss over the ledges in the green and silver bands (0.14.6, Taran: "Deep moss can
            // just be moss"), two blocks deep so its sides show at the lips, where the hanging ferns grow, and over
            // the vaults in the green band; bare rock in the blue band; the walls hold more asurine the deeper they go.
            if (floor) {
                if (wet) return n > 0 ? Rock.GRAVEL : Rock.COBBLED_DEEPSLATE;
                // the ground's band at its surface, so a ledge's two blocks of moss are of one kind
                int band = riftBandAt(x, y + above - 1, z);
                if (band != RIFT_BLUE && above <= 2 && n > -0.3 && (top || patch(x, y + 1, z) > -0.3))
                    return Rock.MOSS;
                if (top) return band == RIFT_BLUE && n < 0.1 ? Rock.ASURINE : Rock.COBBLED_DEEPSLATE;
                return n < asurineShare(x, y, z) - 0.09 ? Rock.ASURINE : Rock.DEEPSLATE;
            }
            if (ceil && n > -0.15 && riftBandAt(x, y - below + 1, z) == RIFT_GREEN) return Rock.MOSS;
            if (vein(x, y, z, 0.026)) return n < asurineShare(x, y, z) ? Rock.ASURINE_CHALCOPYRITE : Rock.DEEPSLATE_CHALCOPYRITE;
            if (n < asurineShare(x, y, z)) return Rock.ASURINE;
            return n > 0.38 ? Rock.COBBLED_DEEPSLATE : Rock.DEEPSLATE;
        }
        if (b == DeepBiome.IVORY_ROOT_CAVERN) return ivory(x, y, z, above, below, wet, t.upperLakeY(x, z));
        return null;
    }

    /** Where the patch noise under this lies, the Fern Rift's walls are asurine: a little up top, more with depth. */
    private double asurineShare(int x, int y, int z) {
        return -0.22 * riftBand(RIFT_GREEN, x, y, z) + 0.04 * riftBand(RIFT_SILVER, x, y, z) + 0.26 * riftBand(RIFT_BLUE, x, y, z);
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
        if (y < line) return vein(x, y, z, 0.024) ? Rock.HEMATITE : Rock.STONE;
        boolean calcite = seam(x, y, z) || n > 0.24;
        if (vein(x, y, z, 0.02)) return calcite ? Rock.CALCITE_HEMATITE : Rock.LIMESTONE_HEMATITE;
        return calcite ? Rock.CALCITE : Rock.LIMESTONE;
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
     * How far past the snowy zone round its openings a frozen Root Vault is still snowed in all over (0.20.1, Taran: "Make
     * the Rootvaults more snowy", "There still should be ice in the cold Root Vaults too, not only snow"): the snow thins
     * from there in to about half the floors, and the plants keep to the small frost moss patches its features lay.
     */
    public static final double VAULT_SNOW_REACH = 3 * Entrances.THAW_REACH;

    /**
     * The frozen Root Vaults under snowy forests, by how far a block lies beyond the snowy zone round the openings
     * ({@code chill}, see {@link Entrances#chill}). Snowed in all through (0.20.1): near the openings snow over nine
     * floors in ten, drifted two and three deep in places, with patches of packed ice; snow and packed ice on the walls;
     * packed ice in patches on the ceilings (blue ice in specks). Further in ({@link #VAULT_SNOW_REACH}) the snow thins to
     * half the floors, the packed ice grows to a fifth of them and the drifts and the snow on the walls give out, with
     * the ground between; the ice on the walls and ceilings keeps on all through. Pool beds are gravel (a feature lays
     * ice on the still water). Everything else is the ground the vaults are carved into ({@code HostRule}).
     */
    public Rock frozenVaults(int look, double chill, int x, int y, int z, int above, int below, boolean wet, double shell) {
        boolean top = above <= 1;
        boolean floor = above <= 3;
        boolean ceil = !floor && below <= 2;
        if (!floor && !ceil && shell > 3) return null;
        double n = patch(x, y, z);
        // 0 in the snowy zone, 1 deep inside
        double t = chill <= 0 ? 0 : Math.min(1, chill / VAULT_SNOW_REACH);
        if (floor) {
            if (wet) return top && n > 0.1 ? Rock.GRAVEL : null;
            double snow = -0.36 + 0.36 * t;
            if (top) {
                if (n > snow) return Rock.SNOW;
                return n > snow - 0.14 - 0.01 * t ? iceSpeck(x, y, z) : null;
            }
            return t < 0.6 && n > -0.1 + 0.4 * t ? Rock.SNOW : null; // drifts
        }
        if (ceil) return n > 0.25 + 0.1 * t ? iceSpeck(x, y, z) : null;
        if (n > 0.42 + 0.06 * t) return Rock.PACKED_ICE;
        return t < 0.7 && n > 0.2 + 0.15 * t ? Rock.SNOW : null;
    }

    /** Packed ice with specks of blue ice. */
    private Rock iceSpeck(int x, int y, int z) { return speck(x, y, z) < 0.07 ? Rock.BLUE_ICE : Rock.PACKED_ICE; }

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

    // ============================================================================================== the Frost Fern Grotto

    /**
     * The Frost Fern Grotto grows icier the deeper it lies (0.20.1, Taran: "make ... the deeper caves more icy"). Depth
     * is counted from the column's ground, or from {@value #GROTTO_GROUND} where the ground is higher, so a grotto under
     * a mountain is as icy as one at the same height under the lowlands beside it: the ice comes as one goes down, not
     * as one walks in under a slope. The depths wander up and down by up to {@value #ICE_WOBBLE} blocks over a few
     * hundred. The grotto starts 18 under the ground ({@code PlantCaves.MIN_DEPTH}) and mostly lies 35 to 70 under it
     * (the plant caves' band, 45 deep on average): ice starts at {@value #ICE_TOP} and covers all it will at
     * {@value #ICE_FULL}. Where the band lies shallow a grotto keeps the top's look throughout, where it lies deep it is
     * ice nearly all through. (0.20.1's glow ice, light blue veins through the deep ice, left the game in 0.20.7: Taran,
     * "Remove glow ice".)
     */
    public static final int GROTTO_GROUND = 80;
    public static final double ICE_TOP = 36, ICE_FULL = 62, ICE_WOBBLE = 8;

    /** How deep a block of the grotto lies, as {@link #grottoIce} counts it, under ground at {@code ground}. */
    public double grottoDepth(int x, int y, int z, int ground) {
        return Math.min(ground, GROTTO_GROUND) - y + ICE_WOBBLE * iceN.noise2(x / 110.0, z / 110.0);
    }

    /**
     * How icy the grotto is at a block, from 0 near its top (frosted rock, snow, the frost moss patches) to 1 deep down
     * (ice floors, ice crusting the walls and ceilings, frozen pools, more icicles and ice columns, fewer patches).
     */
    public double grottoIce(int x, int y, int z, int ground) {
        double t = (grottoDepth(x, y, z, ground) - ICE_TOP) / (ICE_FULL - ICE_TOP);
        return t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t);
    }

    /** A block of the grotto's ice crusts and floors: packed ice with specks of blue ice. */
    public Rock grottoIceBlock(int x, int y, int z) {
        return iceSpeck(x, y, z);
    }

    /**
     * The Frost Fern Grotto (the pack's caves under cold ground), by how icy it is there ({@code ice}, {@link #grottoIce}).
     * Near the top its floors are bare rock with snow in broad drifts and a little packed ice; going down the ice takes
     * them over, to three floors in five deep down, with snow on a quarter (0.20.1: no carpet of frost moss, which grows
     * only in the small patches its features lay), and crusts the ceilings in patches, a fifth of them deep down (Taran,
     * 0.14.0: one sheet of ice over a ceiling looked odd). Gravel and clay under its water. Everything under the floor's
     * top and the walls keep the pack's rock: the grotto's ice crusts and Immersive Weathering's icicle clusters ice them
     * over in patches.
     */
    public Rock frostGrotto(int x, int y, int z, int above, int below, boolean wet, double ice) {
        if (above > 2 && below > 1) return null;
        double n = patch(x, y, z);
        if (above <= 2) {
            if (wet) return n > 0.1 ? Rock.GRAVEL : Rock.CLAY;
            if (above > 1) return null;
            if (n < -0.5 + 0.57 * ice) return grottoIceBlock(x, y, z);
            return n > 0.035 + 0.145 * ice ? Rock.SNOW : null;
        }
        return n > 0.75 - 0.52 * ice ? grottoIceBlock(x, y, z) : null;
    }
}
