package com.explorationremix.world.terrain;

/**
 * Where the Wasteland meets the Basalt Galleries (0.5.1, Taran: "No band, opens into them"). There is no band between
 * the two layers: the Wasteland ends at row {@link #wBot} and the Galleries start right under it at {@link #gTop}, and
 * over the rows {@link #lo}..{@link #hi} round that line both fields are evaluated and combined into one density with a
 * smooth minimum ({@link #density}), at the corners and in the per-block pass alike, so no lid, step or seam can form.
 *
 * <p>The {@link Breakthrough} noise, beta (0..1), decides where the two meet: where it is high the Wasteland's lowest
 * sheet dips through its floor ({@link WastelandField}) and the Galleries raise their ceiling, and the two open into each
 * other, as holes with ledges round them. Where it is low a seal of rock keeps them apart ({@link #seal}). The seal also
 * keeps the Wasteland's lava away from the openings: lava pools only exist where beta is under {@link #LAVA_BETA},
 * open space under the lava table is air from {@link #AIR_BETA} up, and between the two the seal leaves no open space
 * under the table at all, so lava and an opening's air are never side by side.
 *
 * <p>Immutable; built last of the fields (breakthrough, tubes, Galleries, Wasteland, join), so nothing calls back into
 * a field that is not built yet.
 */
public final class Join {
    /** The band: this many rows under the Galleries' top and over the Wasteland's bottom. */
    static final int REACH = 15;
    /** The smooth minimum's width (blocks). */
    static final double K = 4;
    /** Under this, lava may pool in the Wasteland's lowest caves. */
    static final double LAVA_BETA = 0.05;
    /** From this up, open space under the Wasteland's lava table is air. */
    static final double AIR_BETA = 0.25;
    /** The lowest sheet starts to dip through its floor here, fully HOLE_RAMP further on; the seal ends here. */
    static final double HOLE_BETA = 0.45, HOLE_RAMP = 0.25;
    /** The Galleries keep 4 rows of rock under their top where beta is under this. */
    static final double CLOSED_BETA = 0.3;

    /** What the join needs from the layer under the Wasteland (the Galleries' field). */
    public interface Lower {
        /** Density in blocks (positive is rock) for rows from the band's top down, with no top seal of its own. */
        double density(int x, int y, int z);

        /** Its per-block pass for its own rows, in blocks; {@code c8} is the interpolated density in blocks. */
        double fineBlocks(int x, int y, int z, double c8);

        /** Space its per-block pass keeps open whatever {@code c8} says (negative inside), for the Wasteland's rows. */
        double openCore(int x, int y, int z);

        /** What fills an open block of its rows. */
        int fluid(int x, int y, int z);

        /**
         * Blocks from a column to the edge of the nearest hole it opens with a ramp down from the Wasteland (negative inside
         * the hole), or +inf where none is near: the Wasteland's lowest sheet goes down round it ({@link WastelandField}).
         */
        double craterRing(int x, int z);

        /**
         * The Galleries' field as the join's lower layer. Its per-block pass is the exact profile, so it ignores
         * {@code c8} and is its own open core (its tubes stop far under the band).
         */
        static Lower of(GalleriesField g) {
            return new Lower() {
                @Override public double density(int x, int y, int z) { return g.density(x, y, z); }
                @Override public double fineBlocks(int x, int y, int z, double c8) { return g.fineBlocks(x, y, z); }
                @Override public double openCore(int x, int y, int z) { return g.fineBlocks(x, y, z); }
                @Override public int fluid(int x, int y, int z) { return g.fluid(x, y, z); }
                @Override public double craterRing(int x, int z) { return g.craterRing(x, z); }
            };
        }
    }

    /** Whether a layout has the join: a Wasteland right over the Galleries with no band between them. */
    public static boolean on(Shape s) {
        return s.layerCount > Terrain.GALLERIES && s.bandThickness(Terrain.GALLERIES + 1) == 0;
    }

    final WastelandField upper;
    final Lower lower;
    public final Breakthrough breakthrough;
    final boolean on;
    /** The Wasteland's bottom row, the Galleries' top row, the band's rows, the Wasteland's lava table. */
    final int wBot, gTop, lo, hi, W;

    public Join(Shape s, WastelandField upper, Lower lower, Breakthrough breakthrough) {
        this.upper = upper;
        this.lower = lower;
        this.breakthrough = breakthrough;
        int li = Terrain.WASTELAND;
        on = on(s) && upper != null && lower != null && upper.joinOn;
        wBot = s.bottom(li);
        gTop = on ? s.top(Terrain.GALLERIES) : wBot - 1;
        lo = gTop - REACH;
        hi = wBot + REACH;
        W = s.waterY(li);
    }

    /** Whether row y is combined. */
    public boolean inBand(int y) { return on && y >= lo && y <= hi; }

    /**
     * Rock the join keeps whatever either field says (positive inside, or -SOLID): the Wasteland's rows up to a row over
     * its lava table where beta is under {@link #HOLE_BETA} (but for its lava pools' dips), 4 rows over its bottom under
     * lava-holding ground, and the Galleries' top 4 rows where beta is under {@link #CLOSED_BETA}. None of it in the
     * craters round the holes the Galleries open with a ramp ({@link WastelandField}) out to {@link WastelandField#crDry}
     * from the hole's edge: there the Wasteland's lowest sheet goes down to the hole's lip whatever beta is, its open
     * space under the lava table is air, no lava pool comes near, and the Galleries' own rock (their cap at T - 6 or lower
     * outside their discs) keeps the layers apart. Further out a crater's floor lies over the table (crDry is where it
     * rises past W + 2, plus a gap), the fluid under the table is lava again, and the seal holds as anywhere else, so no
     * interpolation opens a block of lava there.
     */
    double seal(int x, int y, int z) {
        WastelandField.Col c = upper.column(x, z);
        if (c.crater > 0 && c.ring < upper.crDry) return -Terrain.SOLID;
        double b = c.beta, s = -Terrain.SOLID;
        if (y >= wBot && y <= W + 1 && b < HOLE_BETA && c.lavaDip <= 0) s = Math.max(s, 1 + (W + 1 - y));
        if (y >= wBot && y <= wBot + 3 && b < LAVA_BETA) s = Math.max(s, 1 + (wBot + 3 - y));
        if (y <= gTop && y >= gTop - 3 && b < CLOSED_BETA) s = Math.max(s, 1 + Math.min(y - (gTop - 3), gTop - y));
        return s;
    }

    /**
     * How open the Galleries are right under the Wasteland's bottom at a column, 0..1: 1 where their density is at most -2
     * at both gTop-3 (the floor of an opening's dip) and gTop-6, 0 where either row is rock. The Wasteland's lowest sheet
     * only dips through its floor in proportion, so an opening never ends in a pit on a pillar, pinch or massif.
     */
    double under(int x, int z) { return on ? under(lower, gTop, x, z) : 0; }

    /** {@link #under} for the Wasteland's column pass, which runs before the join exists. */
    static double under(Lower lower, int gTop, int x, int z) {
        if (lower == null) return 0;
        double d = Math.max(lower.density(x, gTop - 3, z), lower.density(x, gTop - 6, z));
        return d >= 0 ? 0 : Math.min(1, -d / 2);
    }

    /** For /remix info: the join's values at a block of its rows, in words. */
    public String describe(int x, int y, int z) {
        WastelandField.Col c = upper.column(x, z);
        return String.format(java.util.Locale.ROOT, "join (rows %d..%d): beta %.2f, seal %.1f, Wasteland %.1f, Galleries %.1f, combined %.1f", lo, hi, c.beta,
                seal(x, y, z), upper.density(x, y, z), lower.density(x, y, z), density(x, y, z));
    }

    /** The combined density in blocks at a corner of the band. */
    public double density(int x, int y, int z) {
        double d = Network.smin(upper.density(x, y, z), lower.density(x, y, z), K);
        return Math.max(d, seal(x, y, z));
    }

    /**
     * The per-block pass in the band ({@code coarse} on Minecraft's scale, as is the result): each field's own pass for its
     * own rows, the other field's cores, then the seal.
     */
    public double fine(int x, int y, int z, double coarse) {
        double c8 = coarse * 8.0, d;
        if (y >= wBot) d = Math.min(upper.fineBlocks(x, y, z, c8), lower.openCore(x, y, z));
        else d = Math.min(lower.fineBlocks(x, y, z, c8), upper.openCore(x, y, z));
        d = Math.max(d, seal(x, y, z));
        d /= 8.0;
        return d < -1 ? -1 : (d > 1 ? 1 : d);
    }
}
