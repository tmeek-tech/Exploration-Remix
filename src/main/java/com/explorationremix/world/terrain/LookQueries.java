package com.explorationremix.world.terrain;

/**
 * What the two bottom layers' rock dressing ({@link Palette#shell}) and the Galleries' glow ({@code HallGlowFeature})
 * ask of the noise fields that make the caves: the parts of a field's own working that show in the look. Every query is
 * a pure, thread-safe function of the seed and the position, like the fields' density, and costs about one field
 * lookup (a field answers from the same per-column caches its density uses).
 *
 * <p>The Galleries' field ({@link GalleriesField}) answers for the Basalt Galleries and its sub-biomes, and passes the
 * tube queries on to the tube field ({@link TubeField}) behind its country veto. The Wasteland's look is the old
 * Galleries', patches and seams of noise: it asks only {@link #lavaKept}, which the Wasteland's field
 * ({@link WastelandField}) answers. {@link #NONE} answers "no columns, no clusters, no tubes, no cones" everywhere, which
 * the palette reads as plain rock: the look of a field that has none of these.
 */
public interface LookQueries {
    /**
     * The basalt column holding column (x, z): an id that is the same for every block of one column and differs between
     * neighbouring columns, from the same cells the density's column quantisation uses (about 2.5 to 4 blocks across in
     * the halls, coarser in the Causeway). 0 where the field makes no columns.
     */
    long columnCell(int x, int z);

    /**
     * How far column (x, z) lies inside its column, in blocks from the nearest joint with a neighbouring column (0 on
     * the joint, about half the column's width in its middle). Large where {@link #columnCell} is 0.
     */
    double columnEdge(int x, int z);

    /**
     * How deep (x, y, z) lies in a hanging cluster of columns: 0 outside any, rising to 1 in a cluster's core (the
     * cellular noise the field hangs its clusters from, before its threshold).
     */
    double hang(int x, int y, int z);

    /** The tube field's value: negative inside a tube's air (arch ribs and pool basins included), positive in rock. */
    double tube(int x, int y, int z);

    /** Depth into the rock from the nearest tube's air, in blocks (0 at the tube's wall); large far from any tube. */
    double tubeShellDepth(int x, int y, int z);

    /** Whether (x, y, z) lies in one of a tube's arch ribs: the rings of rock that narrow a tube every few blocks. */
    boolean tubeRib(int x, int y, int z);

    /**
     * Distance from column (x, z) to the axis of the nearest Caldera cone, in units of that cone's foot radius: 0 on the
     * axis (the crater), 1 at the cone's foot, more off any cone. The same cones {@code Terrain.ventsNear} lists.
     */
    double cone(int x, int z);

    /**
     * Whether the layer's field keeps its lava off open space round (x, y, z) that lies at or under its table: the
     * Wasteland's rule that no lava sits next to an opening into the Galleries. The palette lays no magma shore there.
     */
    boolean lavaKept(int x, int y, int z);

    /** No columns, clusters, tubes or cones anywhere. */
    LookQueries NONE = new LookQueries() {
        @Override public long columnCell(int x, int z) { return 0; }
        @Override public double columnEdge(int x, int z) { return 1e9; }
        @Override public double hang(int x, int y, int z) { return 0; }
        @Override public double tube(int x, int y, int z) { return 1e9; }
        @Override public double tubeShellDepth(int x, int y, int z) { return 1e9; }
        @Override public boolean tubeRib(int x, int y, int z) { return false; }
        @Override public double cone(int x, int z) { return 1e9; }
        @Override public boolean lavaKept(int x, int y, int z) { return false; }
    };

    /**
     * The noise fields' answers: the Galleries' field ({@code g}) for its column cells, hanging clusters, tubes and cones,
     * the Wasteland's ({@code w}) for its lava rule. A null field (a layout without that layer) answers as {@link #NONE}.
     */
    static LookQueries of(GalleriesField g, WastelandField w) {
        if (g == null && w == null) return NONE;
        return new LookQueries() {
            @Override public long columnCell(int x, int z) { return g != null ? g.columnCell(x, z) : 0; }
            @Override public double columnEdge(int x, int z) { return g != null ? g.columnEdge(x, z) : 1e9; }
            @Override public double hang(int x, int y, int z) { return g != null ? g.hang(x, y, z) : 0; }
            @Override public double tube(int x, int y, int z) { return g != null ? g.tube(x, y, z) : 1e9; }
            @Override public double tubeShellDepth(int x, int y, int z) { return g != null ? g.tubeShellDepth(x, y, z) : 1e9; }
            @Override public boolean tubeRib(int x, int y, int z) { return g != null && g.tubeRib(x, y, z); }
            @Override public double cone(int x, int z) { return g != null ? g.cone(x, z) : 1e9; }
            @Override public boolean lavaKept(int x, int y, int z) { return w != null && w.lavaKept(x, y, z); }
        };
    }
}
