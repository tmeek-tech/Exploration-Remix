package com.explorationremix.world.terrain;

/**
 * The shape of everything below the pack's old floor, as plain functions of position. Positive density is rock,
 * negative is open space; values are in blocks (a signed distance, roughly) until {@link #density} scales them for
 * Minecraft's interpolation.
 *
 * <p>The bands between the layers are solid but for the gate passages ({@link Plans.Gate}). Every block inside a layer
 * is the layer's cave network's ({@link Network}): chambers joined by tunnels and climbs, planned per cell, with the
 * Deep Blue Rift and the deep dark in the Blue Reaches. Above the pack's old floor, this carves only the first gate's
 * landing and ramp and the cell's upper set piece (the Silver Fern Rift or the Ivory Root Cavern).
 */
public final class Terrain {
    public static final int BLUE = 0, GARDENS = 1, BASALT = 2;
    /**
     * How far above its lava table the Basalt Galleries' lava rises at the height of a surge (a later milestone's
     * quake cycle). The natural arches, the obsidian tubes and the high benches along the gallery walls all sit above
     * this, so there is always somewhere to climb to.
     */
    public static final int SURGE_RISE = 24;
    public static final int AIR = 0, WATER = 1, LAVA = 2;
    static final double SOLID = 64;
    /** Blocks of layer rock kept between any cave and the band above or the bedrock below. */
    static final double SEAL = 4;
    /** Half width of the guaranteed walking space along the Deep Blue Rift's ledge path (0.2.0's route core). */
    static final double CORE = 3.3;

    public final TerrainSettings settings;
    public final Shape shape;
    public final long seed;
    public final Plans plans;
    public final Palette palette;
    /** The three deep layers' cave networks: every block inside a layer is theirs. */
    public final Network network;
    /** Asked before an upper set piece is placed, so none is carved under an ocean or river. Null in tests. */
    public volatile Upper.SurfaceProbe surfaceProbe;

    /** The wobble of the gate passages and landings (0.2.0's noise field 18, kept so the gates do not move). */
    private final Perlin detail;
    private final ThreadLocal<Column[]> cache;

    public Terrain(TerrainSettings settings, int packFloor, long seed) {
        this.settings = settings;
        this.cache = ThreadLocal.withInitial(() -> {
            Column[] a = new Column[1024];
            for (int i = 0; i < a.length; i++) a[i] = new Column();
            return a;
        });
        this.shape = new Shape(settings, packFloor);
        this.seed = seed;
        long s = seed ^ 0x6A09E667F3BCC909L;
        detail = new Perlin(Hashing.mix(s, 18, 0, 0));
        this.plans = new Plans(this, seed);
        this.palette = new Palette(this, seed);
        this.network = new Network(this);
    }

    // ================================================================================================ columns

    /** What a column (x, z) above the pack's old floor needs that does not depend on y, computed on first use. */
    public static final class Column {
        int x, z, cellX, cellZ;
        boolean valid;
        boolean upDone;                     // the upper set piece over this column (if any): computed on first use
        Upper.Piece up;
        double upD, upS;                    // distance from the rift's centre line or the cavern's axis; arc length
        final double[] upT = new double[3];
    }

    public Column column(int x, int z) {
        Column[] a = cache.get();
        Column c = a[((x & 31) << 5) | (z & 31)];
        if (c.valid && c.x == x && c.z == z) return c;
        c.x = x;
        c.z = z;
        c.cellX = plans.cellOf(x);
        c.cellZ = plans.cellOf(z);
        c.valid = true;
        c.upDone = false;
        return c;
    }

    private Upper.Piece upperOf(Column c) {
        if (!c.upDone) {
            Upper.Piece p = plans.upper(c.cellX, c.cellZ);
            c.up = null;
            if (p != null && p.near(c.x, c.z)) {
                c.up = p;
                if (p instanceof Upper.Rift r) {
                    r.nearest(c.x, c.z, c.upT);
                    c.upD = c.upT[0];
                    c.upS = c.upT[1];
                } else {
                    Upper.Cavern cv = (Upper.Cavern) p;
                    c.upD = Math.hypot(c.x - cv.cx, c.z - cv.cz);
                    c.upS = 0;
                }
            }
            c.upDone = true;
        }
        return c.up;
    }

    // ================================================================================================ helpers

    static double smooth(double t) {
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    /** Floor of a rift at distance v from its centre line: a stream bed, then stepped ledges up to the rim. */
    static double riftFloor(double v, double bedY, double topY, double halfTop, double streamHalf, double stepH) {
        if (v <= streamHalf) return bedY;
        double depth = topY - bedY;
        double t = (v - streamHalf) / Math.max(1, halfTop - streamHalf);
        if (t >= 1) return topY + 2 + (t - 1) * 40;
        double g = depth * Math.pow(t, 0.85);
        double k = Math.floor(g / stepH), frac = g / stepH - k;
        double gq = stepH * (k + smooth((frac - 0.55) / 0.45));
        return bedY + Math.min(gq, depth + 2);
    }

    /** Steps of the given height that stay flat for {@code flat} of each step and then rise sharply. */
    static double stairs(double v, double step, double flat) {
        double k = Math.floor(v / step), frac = v / step - k;
        return step * (k + smooth((frac - flat) / (1 - flat)));
    }

    static double terraced(double v, double step) {
        double k = Math.floor(v / step), frac = v / step - k;
        return step * (k + smooth((frac - 0.6) / 0.4));
    }

    // ================================================================================================ density

    /** Minecraft-scaled density at a block: positive is rock. */
    public double density(int x, int y, int z) {
        double d = rawDensity(x, y, z);
        d /= 8.0;
        return d < -1 ? -1 : (d > 1 ? 1 : d);
    }

    /** Density in blocks: positive is rock. */
    public double rawDensity(int x, int y, int z) {
        if (y < shape.floorY + 1) return SOLID;
        if (y >= shape.packFloor) {
            // Only the first gate's passage continues upward; the pack's own terrain is used above its old floor.
            if (y < shape.packFloor + 16) {
                Column c = column(x, z);
                Plans.Gate g = plans.gate(1, c.cellX, c.cellZ);
                return passage(g, x, y, z);
            }
            return SOLID;
        }
        int li = shape.layerAt(y);
        if (li < 0) {
            int band = shape.bandAt(y);
            if (band == 0) return SOLID;
            Column c = column(x, z);
            return passage(plans.gate(band, c.cellX, c.cellZ), x, y, z);
        }
        return network.layerDensity(li, x, y, z);
    }

    private double passage(Plans.Gate g, int x, int y, int z) {
        double t = g.tAt(y);
        if (Double.isNaN(t)) return SOLID;
        double dx = x - g.px(t), dz = z - g.pz(t);
        double d2 = dx * dx + dz * dz;
        double r = g.radiusAt(t) + 2;
        if (d2 > (r + 6) * (r + 6)) return SOLID;
        return Math.sqrt(d2) - g.radiusAt(t) + 1.1 * detail.noise3(x / 7.0, y / 6.0, z / 7.0);
    }

    /** A rift geode's open space: a sphere with a flattened floor. Negative inside. */
    static double geodeSdf(Route.Geode g, double dxz2, double y) {
        double dy = y - g.y();
        double d = Math.sqrt(dxz2 + dy * dy) - g.r();
        double floor = g.y() - g.r() * 0.5;
        return Math.max(d, floor - y);
    }

    /**
     * Minecraft only samples the density every 8 blocks up and every 4 across, and interpolates in between, which
     * rounds off anything thinner than that. The network puts the thin pieces back block by block ({@link
     * Network#fine}): rock that has to be there (walking floors, bridges, arches, landing floors, pool rims) and space
     * that has to stay open (the walking cores, the climbs, the landings and the way down). {@code coarse} is the
     * interpolated density on Minecraft's scale; so is the result.
     */
    public double fine(int x, int y, int z, double coarse) {
        int li = shape.layerAt(y);
        if (li < 0) return coarse;
        return network.fine(li, x, y, z, coarse);
    }

    private double landing(double dxz, double rad, double cy, double half, double floor, int x, int y, int z) {
        double ex = dxz / rad, ey = (y - cy) / half;
        double ell = (Math.sqrt(ex * ex + ey * ey) - 1) * half + 1.8 * detail.noise3(x / 9.0, y / 7.0, z / 9.0);
        double fl = floor + 0.9 * detail.noise2(x / 6.0, z / 6.0) - y;
        return Math.max(ell, fl);
    }

    /**
     * Everything carved out of the pack's own terrain above its old floor: the first gate's landing and ramp, and the
     * Silver Fern Rift or Ivory Root Cavern of this cell. Negative inside.
     */
    public double upperCarve(int x, int y, int z) {
        return Math.min(gateOneCarve(x, y, z), pieceDist(x, y, z));
    }

    /** Signed distance to the open space of this column's upper set piece (negative inside), or SOLID when none is near. */
    public double pieceDist(int x, int y, int z) {
        if (y < shape.packFloor) return SOLID;
        Column c = column(x, z);
        Upper.Piece p = upperOf(c);
        if (p == null || y > p.topY + 2) return SOLID;
        return p.dist(c.upD, c.upS, x, y, z);
    }

    /**
     * What fills an open block above the pack's old floor near the upper set piece or the first gate's landing:
     * {@link #AIR} or {@link #WATER}, or -1 to leave it to the pack. Never lava, which the pack puts in its lowest caves.
     */
    public int upperFluid(int x, int y, int z) {
        if (y < shape.packFloor || y > shape.packFloor + 90) return -1;
        Column c = column(x, z);
        Upper.Piece p = upperOf(c);
        if (p != null && y <= p.topY + 2 && pieceDist(x, y, z) < 10) {
            if (y >= p.waterY) return AIR;
            if (p instanceof Upper.Cavern cv && c.upD > cv.rxz + 1) return AIR;
            return WATER;
        }
        if (y < shape.packFloor + 48 && gateOneCarve(x, y, z) < 14.0) return AIR;
        return -1;
    }

    /** The upper set piece's biome at a block, or null. */
    public DeepBiome upperBiome(int x, int y, int z) {
        if (y < shape.packFloor) return null;
        Column c = column(x, z);
        Upper.Piece p = upperOf(c);
        if (p == null || y > p.topY + 4) return null;
        return pieceDist(x, y, z) < 6 ? p.biome() : null;
    }

    /** The surface of the lake (or stream) of the upper set piece over a column. */
    public double upperLakeY(int x, int z) {
        Upper.Piece p = upperOf(column(x, z));
        return p != null ? p.waterY : shape.packFloor + 14;
    }

    /** The upper set piece over a column, if any. */
    public Upper.Piece upperPiece(int x, int z) { return upperOf(column(x, z)); }

    /**
     * The first gate's upper landing, the top of its passage and the ramp up into the pack's caves: carved out of
     * the pack's own terrain just above its old floor. Negative inside.
     */
    public double gateOneCarve(int x, int y, int z) {
        if (y < shape.packFloor - 8 || y > shape.packFloor + 56) return SOLID;
        Column c = column(x, z);
        Plans.Gate g = plans.gate(1, c.cellX, c.cellZ);
        double d = SOLID;
        double dxz = Math.hypot(x - g.ux, z - g.uz);
        if (dxz < g.landR + 6) d = Math.min(d, landing(dxz, g.landR, g.uCy, g.landHalf, g.uFloor, x, y, z));
        d = Math.min(d, passage(g, x, y, z));
        Route.Link ramp = plans.ramp(g);
        if (Math.abs(x - (ramp.x1() + ramp.x2()) * 0.5) < Math.abs(ramp.x2() - ramp.x1()) * 0.5 + 12
                && Math.abs(z - (ramp.z1() + ramp.z2()) * 0.5) < Math.abs(ramp.z2() - ramp.z1()) * 0.5 + 12) {
            d = Math.min(d, ramp.dist(x, y, z) + 1.5 * detail.noise3(x / 8.0, y / 6.0, z / 8.0));
        }
        return d;
    }

    // ================================================================================================ fluids

    /** What fills an open block: {@link #AIR}, {@link #WATER} or {@link #LAVA}. */
    public int fluidAt(int x, int y, int z) {
        int li = shape.layerAt(y);
        if (li < 0) return AIR;
        return network.fluidAt(li, x, y, z);
    }

    // ================================================================================================ biomes

    /** The biome the deep world places at a block, or null above the pack's old floor. */
    public DeepBiome biomeAt(int x, int y, int z) {
        if (y >= shape.packFloor) return null;
        int li = shape.layerAt(y);
        if (li < 0) {
            int band = shape.bandAt(y);
            li = band > 0 ? band - 1 : shape.layerCount - 1;
            return routeBiome(li);
        }
        return network.biomeAt(li, x, y, z);
    }

    /** A layer's main biome (0.2.0's route biome): the bands take their layer's, and the network falls back on it. */
    public static DeepBiome routeBiome(int li) {
        return switch (li) {
            case GARDENS -> DeepBiome.LANTERN_LAKE;
            case BLUE -> DeepBiome.DEEP_BLUE_RIFT;
            default -> DeepBiome.BASALT_GALLERIES;
        };
    }

    // ================================================================================================ queries for materials and features

    /** Depth into the rock around the nearest refuge geode (0 at its inner surface), or a large value when none is near. */
    public double geodeShellDepth(int x, int y, int z) {
        int li = shape.layerAt(y);
        return li < 0 ? 1e9 : network.geodeShellDepth(li, x, y, z);
    }

    /** Depth into the rock around the nearest obsidian tube (0 at its inner surface), or a large value when none is near. */
    public double tubeShellDepth(int x, int y, int z) {
        int li = shape.layerAt(y);
        return li < 0 ? 1e9 : network.tubeShellDepth(li, x, y, z);
    }

    /** True at the floor of a blind tube's end that has magma instead of a lava pool (within 2 blocks of the end). */
    public boolean tubeStubEnd(int x, int y, int z) {
        int li = shape.layerAt(y);
        return li >= 0 && network.tubeStubEnd(li, x, y, z);
    }

    /** True in the bowl of the bedrock landing at the bottom of the last layer. */
    public boolean bedrockLanding(int x, int y, int z) {
        int li = shape.layerAt(y);
        return li >= 0 && network.bedrockLanding(li, x, y, z);
    }

    // ------------------------------------------------------------------------------- the network's queries (12.1)
    /**
     * Every walking line of layer li within {@code reach} of column (x, z), nearest first: tunnels, inner walks, ledge
     * passages, stubs and the rift's ledge path ({@link Network#wayLinesAt}). A per-thread result.
     */
    public Network.WayLines wayLinesAt(int li, int x, int z, double reach) { return network.wayLinesAt(li, x, z, reach); }

    /** The lines of {@link #wayLinesAt} that the terrain carved in column (x, z): its own cell's plan only. */
    public Network.WayLines carvedLinesAt(int li, int x, int z, double reach) { return network.carvedLinesAt(li, x, z, reach); }

    /** The climbs and climb stages of layer li reaching into the box minX..maxX, minZ..maxZ (inclusive). */
    public java.util.List<Net.Shaft> shaftsIn(int minX, int minZ, int maxX, int maxZ, int li) { return network.shaftsIn(li, minX, minZ, maxX, maxZ); }

    /** The plan of layer li in cell (cx, cz). */
    public Net network(int li, int cx, int cz) { return network.network(li, cx, cz); }

    /**
     * True when a structure starting at (x, y, z) is a planned ancient city: a CITY chamber's centre within 24 blocks
     * of (x, z), y within 30 of its floor (section 11.1).
     */
    public boolean citySite(int x, int y, int z) { return network.citySite(x, y, z); }
}
