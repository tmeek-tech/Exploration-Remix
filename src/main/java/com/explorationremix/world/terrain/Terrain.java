package com.explorationremix.world.terrain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shape of everything below the pack's old floor, as plain functions of position. Positive density is rock,
 * negative is open space; values are in blocks (a signed distance, roughly) until {@link #density} scales them for
 * Minecraft's interpolation.
 *
 * <p>Each layer is built from a few large pieces: heightfield caverns (a floor and a ceiling per column, so spaces are
 * big and connected), rift networks, the planned route with its set pieces, and the gate landings and passages. Rock
 * that must stay (pillars, bridges, arches, the path's floor, the seals under each band) is added on top, and the
 * route's walking space, the landings and the passages are cut last so nothing can block the way down.
 */
public final class Terrain {
    public static final int LANTERN = 0, BLUE = 1, EMBER = 2, BASALT = 3;
    public static final int AIR = 0, WATER = 1, LAVA = 2;
    static final double SOLID = 64;
    /** Blocks of layer rock kept between any cave and the band above or the bedrock below. */
    static final double SEAL = 4;
    /** Half width of the guaranteed walking space along the route. */
    static final double CORE = 3.3;

    public final TerrainSettings settings;
    public final Shape shape;
    public final long seed;
    public final Plans plans;
    public final Palette palette;

    private final Perlin warpX, warpZ, maskA, maskB, chan, floorN, ceilN, islandN, heightN, region, riftN, riftDepth,
            rough1, rough2, gal1, gal2, ledgeN, detail;
    private final Map<Long, Arch> arches = new ConcurrentHashMap<>();
    private final ThreadLocal<Column[]> cache = ThreadLocal.withInitial(() -> {
        Column[] a = new Column[1024];
        for (int i = 0; i < a.length; i++) a[i] = new Column(4);
        return a;
    });

    public Terrain(TerrainSettings settings, int packFloor, long seed) {
        this.settings = settings;
        this.shape = new Shape(settings, packFloor);
        this.seed = seed;
        long s = seed ^ 0x6A09E667F3BCC909L;
        warpX = new Perlin(Hashing.mix(s, 1, 0, 0));
        warpZ = new Perlin(Hashing.mix(s, 2, 0, 0));
        maskA = new Perlin(Hashing.mix(s, 3, 0, 0));
        maskB = new Perlin(Hashing.mix(s, 4, 0, 0));
        chan = new Perlin(Hashing.mix(s, 5, 0, 0));
        floorN = new Perlin(Hashing.mix(s, 6, 0, 0));
        ceilN = new Perlin(Hashing.mix(s, 7, 0, 0));
        islandN = new Perlin(Hashing.mix(s, 8, 0, 0));
        heightN = new Perlin(Hashing.mix(s, 9, 0, 0));
        region = new Perlin(Hashing.mix(s, 10, 0, 0));
        riftN = new Perlin(Hashing.mix(s, 11, 0, 0));
        riftDepth = new Perlin(Hashing.mix(s, 12, 0, 0));
        rough1 = new Perlin(Hashing.mix(s, 13, 0, 0));
        rough2 = new Perlin(Hashing.mix(s, 14, 0, 0));
        gal1 = new Perlin(Hashing.mix(s, 15, 0, 0));
        gal2 = new Perlin(Hashing.mix(s, 16, 0, 0));
        ledgeN = new Perlin(Hashing.mix(s, 17, 0, 0));
        detail = new Perlin(Hashing.mix(s, 18, 0, 0));
        this.plans = new Plans(this, seed);
        this.palette = new Palette(this, seed);
    }

    // ================================================================================================ columns

    /** Everything about one (x, z) column that does not depend on y, computed per layer on first use. */
    public static final class Column {
        int x, z, cellX, cellZ;
        boolean valid;
        final LayerCol[] layers;

        Column(int count) {
            layers = new LayerCol[count];
            for (int i = 0; i < count; i++) layers[i] = new LayerCol();
        }
    }

    static final class LayerCol {
        boolean done, routeDone;            // everything, or only the route part (enough for the per-block pass)
        double geoNear;                     // distance to the nearest refuge geode's shell
        double aF, aC, bF, bC;              // two heightfield cavern tiers: floor and ceiling (open where C > F)
        double aPd, aPr, bPd, bPr;          // nearest pillar: distance to its axis and its radius
        boolean aHour, bHour;               // hourglass pillars (stalactite meets stalagmite) or straight columns
        double regionV;                     // blue reaches: > 0 silver fern rifts, < 0 deep dark
        Route route;
        boolean rOn, wOn;                   // near the route's centre line / near its walking line
        double rD, rS, rV, rTop, rHalfTop;  // centre line: distance, arc length, signed side; the rift's top and half width
        double wD, rF, rW, rH, rSlab;       // walking line: distance, floor, corridor half width and height, slab under it
        boolean w2On;                       // a second stretch of the walking line passing near (hairpins, crossings)
        double wD2, rF2, rW2, rH2, rSlab2;
        double cap, cap2;                   // where one stretch crosses over the other: top of the lower one's headroom
        double rrF, rrC;                    // the route rift's floor and ceiling at this column
        int hallN;
        final Route.Hall[] hall = new Route.Hall[6];
        final double[] hallD = new double[6];
        int geoN;
        final Route.Geode[] geo = new Route.Geode[10];
        final double[] geoD2 = new double[10];
        Route.Bridge bridge;
        Plans.Gate entry, exit;
        double entryD, exitD, endD;
        Arch arch;
        double archAlong, archAcross;
        final double[] tmp = new double[6];
    }

    /** A natural arch spanning a basalt gallery. */
    record Arch(double x, double z, double nx, double nz, double deckY, double span, double halfAlong) {}

    public Column column(int x, int z) {
        Column[] a = cache.get();
        Column c = a[((x & 31) << 5) | (z & 31)];
        if (c.valid && c.x == x && c.z == z) return c;
        c.x = x;
        c.z = z;
        c.cellX = plans.cellOf(x);
        c.cellZ = plans.cellOf(z);
        c.valid = true;
        for (LayerCol lc : c.layers) { lc.done = false; lc.routeDone = false; }
        return c;
    }

    LayerCol layerCol(Column c, int li) {
        LayerCol lc = c.layers[li];
        if (!lc.done) computeLayer(c, li, lc);
        return lc;
    }

    /** Only the route, its set pieces, the landings and (in the basalt) the arches: cheap enough for every column. */
    LayerCol routeCol(Column c, int li) {
        LayerCol lc = c.layers[li];
        if (!lc.routeDone) computeRoute(c, li, lc);
        return lc;
    }

    // ================================================================================================ helpers

    static double smooth(double t) {
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static void tier(LayerCol lc, boolean a, double open, double f0, double c0, double midFrac, double ceilPow) {
        if (open <= 0 || c0 <= f0) return;
        double e = smooth(open);
        double mid = f0 + (c0 - f0) * midFrac;
        double f = mid + (f0 - mid) * e;
        double c = mid + (c0 - mid) * Math.pow(e, ceilPow);
        if (a) {
            if (lc.aC > lc.aF) { // already open (a second piece in the same tier): keep the larger opening
                lc.aF = Math.min(lc.aF, f);
                lc.aC = Math.max(lc.aC, c);
            } else { lc.aF = f; lc.aC = c; }
        } else {
            if (lc.bC > lc.bF) { lc.bF = Math.min(lc.bF, f); lc.bC = Math.max(lc.bC, c); }
            else { lc.bF = f; lc.bC = c; }
        }
    }

    private void pillars(LayerCol lc, boolean a, double x, double z, double grid, double prob, double rMin, double rMax, int salt) {
        int gx = (int) Math.floor(x / grid), gz = (int) Math.floor(z / grid);
        double best = 1e9, br = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long h = Hashing.mix(seed, salt, gx + dx, gz + dz);
            if (Hashing.unit(h) > prob) continue;
            double cx = (gx + dx + Hashing.range(Hashing.mix(h, 1, 0, 0), 0.2, 0.8)) * grid;
            double cz = (gz + dz + Hashing.range(Hashing.mix(h, 2, 0, 0), 0.2, 0.8)) * grid;
            double r = Hashing.range(Hashing.mix(h, 3, 0, 0), rMin, rMax);
            double d = Math.hypot(x - cx, z - cz);
            if (d - r < best - br) { best = d; br = r; }
        }
        if (a) { lc.aPd = best; lc.aPr = br; } else { lc.bPd = best; lc.bPr = br; }
    }

    /** Distance in blocks from (x, z) to the nearest zero line of a warped noise field: the centre of a rift or gallery. */
    private double lineDist(Perlin p, double x, double z, double scale, double warp) {
        double v0 = lineNoise(p, x, z, scale, warp);
        double gx = (lineNoise(p, x + 1.5, z, scale, warp) - lineNoise(p, x - 1.5, z, scale, warp)) / 3.0;
        double gz = (lineNoise(p, x, z + 1.5, scale, warp) - lineNoise(p, x, z - 1.5, scale, warp)) / 3.0;
        double g = Math.sqrt(gx * gx + gz * gz);
        return Math.abs(v0) / Math.max(g, 1e-6);
    }

    private double lineNoise(Perlin p, double x, double z, double scale, double warp) {
        double wx = x + warp * warpX.noise2(x / (scale * 0.6), z / (scale * 0.6) + 5.3);
        double wz = z + warp * warpZ.noise2(x / (scale * 0.6) - 2.1, z / (scale * 0.6));
        return p.noise2(wx / scale, wz / scale);
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

    private static double terraced(double v, double step) {
        double k = Math.floor(v / step), frac = v / step - k;
        return step * (k + smooth((frac - 0.6) / 0.4));
    }

    // ================================================================================================ layer columns

    private void computeRoute(Column c, int li, LayerCol lc) {
        double x = c.x, z = c.z;
        lc.hallN = 0;
        lc.geoN = 0;
        lc.bridge = null;
        lc.arch = null;
        Route r = plans.route(li, c.cellX, c.cellZ);
        lc.route = r;
        double[] tmp = lc.tmp;
        lc.rOn = r.nearest(x, z, tmp);
        double fi = tmp[3];
        lc.rrF = SOLID;
        lc.rrC = -SOLID;
        if (lc.rOn) {
            lc.rD = tmp[0];
            lc.rS = tmp[1];
            lc.rV = tmp[2];
            if (r.rift) {
                lc.rTop = r.at(r.riftTop, lc.rS);
                lc.rHalfTop = r.at(r.riftHalf, lc.rS);
                double v = Math.abs(lc.rV) + 2.0 * ledgeN.noise2(x / 16.0, z / 16.0);
                double step = 11 + 3 * ledgeN.noise2(lc.rS / 90.0, 4.4);
                double fl = riftFloor(Math.max(0, v), r.W - 2, lc.rTop, lc.rHalfTop, r.streamHalf, step);
                double ce = lc.rTop + 7 * Math.max(0, 1 - (v / lc.rHalfTop) * (v / lc.rHalfTop));
                // Never let the rift run past either end of the route; near the ends the path is still a tunnel
                // on its way from the landing out to the ledge, so the rift stays closed under it.
                double endFade = smooth((Math.min(lc.rS, r.length - lc.rS) - 10) / 60.0);
                ce = fl + (ce - fl) * endFade;
                if (ce > fl) { lc.rrF = fl; lc.rrC = ce; }
                for (Route.Bridge b : r.bridges) {
                    if (Math.abs(lc.rS - b.s()) < b.halfAlong() + 6) { lc.bridge = b; break; }
                }
            }
        }
        // The walking line: the centre line itself, or in a rift the ledge beside it.
        if (r.rift) {
            lc.wOn = r.nearestWalk(x, z, tmp);
            lc.wD = tmp[0];
            fi = tmp[1];
        } else {
            lc.wOn = lc.rOn;
            lc.wD = lc.rD;
        }
        lc.w2On = false;
        if (lc.wOn) {
            lc.rF = r.atIndex(r.floor, fi);
            lc.rW = r.atIndex(r.half, fi);
            lc.rH = r.atIndex(r.height, fi);
            lc.rSlab = r.atIndex(r.slab, fi);
            double fi2 = tmp[5];
            lc.wD2 = tmp[4];
            if (lc.wD2 < 40) {
                lc.w2On = true;
                lc.rF2 = r.atIndex(r.floor, fi2);
                lc.rW2 = r.atIndex(r.half, fi2);
                lc.rH2 = r.atIndex(r.height, fi2);
                lc.rSlab2 = r.atIndex(r.slab, fi2);
            }
        }
        // Where the path crosses over itself, the upper stretch's floor thins so the lower one keeps three blocks of
        // headroom, and the lower one's walking space stops under that floor.
        lc.cap = lc.cap2 = 1e9;
        if (lc.w2On && Math.abs(lc.rF - lc.rF2) >= 4.5 && Math.abs(lc.rF - lc.rF2) < 16) {
            double gap = Math.abs(lc.rF - lc.rF2);
            if (lc.rF > lc.rF2) {
                lc.rSlab = Math.max(1.5, Math.min(lc.rSlab, gap - 3));
                lc.cap2 = lc.rF - lc.rSlab;
            } else {
                lc.rSlab2 = Math.max(1.5, Math.min(lc.rSlab2, gap - 3));
                lc.cap = lc.rF2 - lc.rSlab2;
            }
        }
        for (Route.Hall hl : r.halls) {
            double d = Math.hypot(x - hl.x(), z - hl.z());
            if (d < hl.rxz() + 10 && lc.hallN < lc.hall.length) {
                lc.hall[lc.hallN] = hl;
                lc.hallD[lc.hallN++] = d;
            }
        }
        double geoNear = 1e9;
        for (Route.Geode g : r.geodes) {
            double d2 = (x - g.x()) * (x - g.x()) + (z - g.z()) * (z - g.z());
            double d = Math.sqrt(d2);
            geoNear = Math.min(geoNear, d - g.r());
            if (d < g.r() + 12 && lc.geoN < lc.geo.length) {
                lc.geo[lc.geoN] = g;
                lc.geoD2[lc.geoN++] = d2;
            }
        }
        lc.entry = r.entry;
        lc.exit = r.exit;
        lc.entryD = Math.hypot(x - r.entry.lx, z - r.entry.lz);
        lc.exitD = r.exit != null ? Math.hypot(x - r.exit.ux, z - r.exit.uz) : 1e9;
        lc.endD = r.bedrockEnd ? Math.hypot(x - r.endX, z - r.endZ) : 1e9;
        lc.geoNear = geoNear;
        if (li >= BASALT) {
            LayerSettings ls = settings.layers().get(li);
            Arch arch = nearestArch(x, z, ls, shape.waterY(li), shape.top(li));
            if (arch != null) {
                double dx = x - arch.x(), dz = z - arch.z();
                lc.arch = arch;
                lc.archAcross = dx * arch.nx() + dz * arch.nz();
                lc.archAlong = -dx * arch.nz() + dz * arch.nx();
            }
        }
        lc.routeDone = true;
    }

    private void computeLayer(Column c, int li, LayerCol lc) {
        if (!lc.routeDone) computeRoute(c, li, lc);
        double x = c.x, z = c.z;
        double W = shape.waterY(li);
        int top = shape.top(li);
        LayerSettings ls = settings.layers().get(li);
        double hMin = ls.heightMin(), hMax = ls.heightMax();
        lc.aF = lc.bF = SOLID;
        lc.aC = lc.bC = -SOLID;
        lc.aPd = lc.bPd = 1e9;
        lc.aPr = lc.bPr = 0;
        lc.aHour = lc.bHour = true;
        lc.regionV = 0;
        // Hunting caves fade out around refuges, so a refuge is only reached by its own tunnel.
        double refugeFade = lc.geoNear > 1e8 ? 1 : smooth((lc.geoNear - 8) / 16.0);

        double wx = x + 48 * warpX.noise2(x / 160.0, z / 160.0 + li * 13.1);
        double wz = z + 48 * warpZ.noise2(x / 160.0 + li * 7.7, z / 160.0);

        switch (li) {
            case LANTERN -> {
                // Tier A: wide still lakes with islands under a low ceiling.
                double m = maskA.fbm2(wx / 95.0, wz / 95.0, 3);
                double ch = 1 - Math.abs(chan.noise2(wx / 230.0, wz / 230.0)) * 9.0;
                double open = Math.min(1, Math.max((m + 0.04) / 0.16, ch * 0.6));
                if (open > 0) {
                    double f0 = W - 7 + 2.5 * floorN.noise2(x / 47.0, z / 47.0);
                    double isl = islandN.fbm2(x / 30.0, z / 30.0, 2);
                    if (isl > 0.12) f0 = Math.min(W + 4, f0 + (isl - 0.12) / 0.5 * 13);
                    double c0 = W + 17 + 8 * ceilN.noise2(x / 64.0, z / 64.0) + 14 * Math.max(0, heightN.noise2(x / 120.0, z / 120.0));
                    tier(lc, true, open, f0, Math.min(c0, top - 6), 0.3, 0.55);
                }
                pillars(lc, true, x, z, 34, 0.4, 3.5, 7, 1001);
                // Tier B: tall dry halls higher up.
                double mb = maskB.fbm2(wx / 125.0 + 31.4, wz / 125.0 - 11.9, 3);
                double openB = Math.min(1, (mb - 0.1) / 0.16) * refugeFade;
                if (openB > 0) {
                    double f0 = W + 40 + 9 * floorN.noise2(x / 80.0 + 50, z / 80.0);
                    double hgt = hMin + (hMax - hMin) * (0.5 + 0.5 * heightN.noise2(x / 90.0 + 7, z / 90.0));
                    tier(lc, false, openB, f0, Math.min(f0 + hgt, top - 6), 0.3, 0.7);
                }
                pillars(lc, false, x, z, 46, 0.3, 4, 8.5, 1002);
            }
            case BLUE -> {
                double reg = region.fbm2(x / 420.0, z / 420.0, 2);
                lc.regionV = reg;
                if (reg > -0.14) {
                    // Silver fern rifts: a network of deep stepped rifts with a stream at the bottom.
                    double fade = smooth((reg + 0.14) / 0.26) * refugeFade;
                    double v = lineDist(riftN, x, z, 270, 60) + 2.5 * ledgeN.noise2(x / 18.0, z / 18.0);
                    double depth = (hMin + (hMax - hMin) * (0.5 + 0.5 * riftDepth.noise2(x / 300.0, z / 300.0))) * fade;
                    double topY = Math.min(top - 8, W - 2 + depth);
                    double halfTop = 16 + depth * 0.15;
                    if (depth > 14 && v < halfTop + 4) {
                        double step = 11 + 3 * ledgeN.noise2(x / 90.0, z / 90.0);
                        double fl = riftFloor(Math.max(0, v), W - 2, topY, halfTop, 3.5 + 1.2 * ledgeN.noise2(x / 40.0, z / 40.0 + 9), step);
                        double ce = topY + 7 * Math.max(0, 1 - (v / halfTop) * (v / halfTop));
                        if (ce > fl) { lc.aF = fl; lc.aC = ce; }
                    }
                }
                if (reg < 0.14) {
                    // Deep dark: big rounded caverns with pillars, sculk and (later) ancient cities.
                    double fade = smooth((0.14 - reg) / 0.26) * refugeFade;
                    double m = maskB.fbm2(wx / 85.0 - 40, wz / 85.0 + 13, 3);
                    double open = Math.min(1, (m + 0.12) / 0.18) * fade;
                    if (open > 0) {
                        double f0 = W + 24 + 10 * floorN.noise2(x / 60.0 + 90, z / 60.0);
                        double hgt = 24 + 22 * (0.5 + 0.5 * heightN.noise2(x / 75.0 + 3, z / 75.0));
                        tier(lc, false, open, f0, Math.min(f0 + hgt, top - 6), 0.4, 0.8);
                    }
                    pillars(lc, false, x, z, 30, 0.45, 3, 6, 1003);
                }
            }
            case EMBER -> {
                // Big terraced caverns, the hot pools sit on the terraces.
                double m = maskA.fbm2(wx / 215.0 + 77, wz / 215.0 - 31, 3);
                double open = Math.min(1, (m + 0.06) / 0.18);
                if (open > 0) {
                    double base = 15 * (0.5 + 0.5 * floorN.fbm2(x / 120.0 - 20, z / 120.0, 2));
                    double f0 = W - 3 + terraced(base + 1.5 * ledgeN.noise2(x / 30.0, z / 30.0), 4.5);
                    double hgt = hMin + (hMax - hMin) * (0.5 + 0.5 * heightN.noise2(x / 150.0, z / 150.0 + 9));
                    tier(lc, true, open, f0, Math.min(f0 + hgt, top - 6), 0.25, 0.65);
                }
                pillars(lc, true, x, z, 64, 0.35, 5, 10, 1004);
                double mb = maskB.fbm2(wx / 160.0 + 5, wz / 160.0 + 77, 3);
                double openB = Math.min(1, (mb - 0.22) / 0.18);
                if (openB > 0) {
                    double f0 = W + 105 + 10 * floorN.noise2(x / 70.0, z / 70.0 - 60);
                    double hgt = 35 + 25 * (0.5 + 0.5 * heightN.noise2(x / 80.0 - 7, z / 80.0));
                    tier(lc, false, openB, f0, Math.min(f0 + hgt, top - 6), 0.3, 0.7);
                }
                pillars(lc, false, x, z, 50, 0.3, 4, 8, 1005);
            }
            default -> {
                // Basalt galleries: long cathedral halls over a lava river, walls of columns.
                double[] g = new double[4];
                basaltGallery(x, z, ls, W, top, g);
                if (g[0] > 0) tier(lc, true, Math.min(1, g[0] * 3.5), g[1], g[2], 0.45, 0.35);
                pillars(lc, true, x, z, 28, 0.22, 3.5, 7, 1006);
                lc.aHour = false;
            }
        }
        lc.done = true;
    }

    /** Gallery shape at a column: {openness, floor, ceiling, distance to the gallery's centre line}. */
    private void basaltGallery(double x, double z, LayerSettings ls, double W, int top, double[] out) {
        double v1 = lineDist(gal1, x, z, 330, 90);
        double v2 = lineDist(gal2, x, z, 230, 70) * 1.35;
        double v = Math.min(v1, v2);
        // Walls step in and out like clustered columns, so a gallery never has a flat side.
        double halfW = (ls.widthMin() + (ls.widthMax() - ls.widthMin()) * (0.5 + 0.5 * heightN.noise2(x / 200.0 + 40, z / 200.0))) / 2
                * (1 + 0.22 * ledgeN.noise2(x / 23.0 + 70, z / 23.0));
        double open = 1 - v / halfW;
        double riverHalf = 5 + 3 * (0.5 + 0.5 * floorN.noise2(x / 70.0, z / 70.0));
        double bank = Math.min(1, Math.max(0, (v - riverHalf) / Math.max(4, halfW * 0.5)));
        double f0 = W - 5 + terraced(bank * (9 + 7 * (0.5 + 0.5 * ledgeN.noise2(x / 30.0, z / 30.0))), 3.5);
        double hgt = ls.heightMin() + (ls.heightMax() - ls.heightMin()) * (0.5 + 0.5 * ceilN.noise2(x / 180.0, z / 180.0));
        // A vaulted roof: highest over the centre line, with ribs.
        double q = Math.min(1, v / halfW);
        hgt -= q * q * 34 - 9 * ceilN.noise2(x / 37.0 - 9, z / 37.0);
        out[0] = open;
        out[1] = f0;
        out[2] = Math.min(f0 + hgt, top - 6 - 4 * (1 + ceilN.noise2(x / 29.0, z / 29.0 + 3)));
        out[3] = v;
    }

    private Arch nearestArch(double x, double z, LayerSettings ls, double W, int top) {
        double grid = 84;
        int gx = (int) Math.floor(x / grid), gz = (int) Math.floor(z / grid);
        Arch best = null;
        double bd = 1e9;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Arch a = arch(gx + dx, gz + dz, grid, ls, W, top);
            if (a == null) continue;
            double d = Math.hypot(x - a.x(), z - a.z());
            if (d < a.span() + 4 && d < bd) { bd = d; best = a; }
        }
        return best;
    }

    private static final Arch NO_ARCH = new Arch(0, 0, 0, 0, 0, 0, 0);

    private Arch arch(int gx, int gz, double grid, LayerSettings ls, double W, int top) {
        long key = ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
        Arch a = arches.get(key);
        if (a == null) {
            a = NO_ARCH;
            long h = Hashing.mix(seed, 2001, gx, gz);
            if (Hashing.unit(h) < 0.55) {
                double ax = (gx + Hashing.range(Hashing.mix(h, 1, 0, 0), 0.15, 0.85)) * grid;
                double az = (gz + Hashing.range(Hashing.mix(h, 2, 0, 0), 0.15, 0.85)) * grid;
                double[] g = new double[4];
                basaltGallery(ax, az, ls, W, top, g);
                if (g[0] > 0.45) {
                    // Arch runs across the gallery: along the gradient of the gallery's distance field.
                    double[] g1 = new double[4], g2 = new double[4], g3 = new double[4], g4 = new double[4];
                    basaltGallery(ax + 3, az, ls, W, top, g1);
                    basaltGallery(ax - 3, az, ls, W, top, g2);
                    basaltGallery(ax, az + 3, ls, W, top, g3);
                    basaltGallery(ax, az - 3, ls, W, top, g4);
                    double nx = g1[3] - g2[3], nz = g3[3] - g4[3], l = Math.hypot(nx, nz);
                    if (l > 1e-6) {
                        nx /= l; nz /= l;
                        double hgt = g[2] - g[1];
                        double deck = g[1] + hgt * Hashing.range(Hashing.mix(h, 3, 0, 0), 0.3, 0.7);
                        double span = g[3] + (ls.widthMax() / 2) + 10;
                        a = new Arch(ax, az, nx, nz, deck, span, Hashing.range(Hashing.mix(h, 4, 0, 0), 3.0, 5.5));
                    }
                }
            }
            arches.putIfAbsent(key, a);
        }
        return a == NO_ARCH ? null : a;
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
        return layerDensity(column(x, z), li, x, y, z);
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

    private double rough(int li, int x, int y, int z) {
        double a, s;
        switch (li) {
            case LANTERN -> { a = 4.5; s = 22; }
            case BLUE -> { a = 4.0; s = 20; }
            case EMBER -> { a = 5.5; s = 28; }
            default -> { a = 7.0; s = 34; }
        }
        return a * rough1.noise3(x / s, y / (s * 0.75), z / s) + a * 0.35 * rough2.noise3(x / (s * 0.4), y / (s * 0.4), z / (s * 0.4));
    }

    private static double vault(double r, double y, double f, double w, double h) {
        double q = r / w;
        if (q >= 1.25) return SOLID;
        double fl = f + (q > 0.35 ? (q - 0.35) * (q - 0.35) * w * 0.9 : 0);
        double ce = f + h * Math.sqrt(Math.max(0, 1 - q * q));
        return Math.max(fl - y, y - ce);
    }

    static double hallSdf(Route.Hall h, double dxz, double y) {
        double ex = dxz / h.rxz(), ey = (y - h.cy()) / h.ry();
        double ell = (Math.sqrt(ex * ex + ey * ey) - 1) * Math.min(h.rxz(), h.ry());
        double rise = ex * ex * h.rise();
        double bowl = h.floorY() + (h.lake() ? terraced(rise, 3.0) : rise);
        return Math.max(ell, bowl - y);
    }

    static double geodeSdf(Route.Geode g, double dxz2, double y) {
        double dy = y - g.y();
        double d = Math.sqrt(dxz2 + dy * dy) - g.r();
        double floor = g.y() - g.r() * 0.5;
        return Math.max(d, floor - y);
    }

    private double pillarSolid(double pd, double pr, boolean hour, double y, double f, double c) {
        if (pr <= 0 || c <= f) return -SOLID;
        double r = pr;
        if (hour) {
            double mid = (f + c) * 0.5, half = Math.max(4, (c - f) * 0.5);
            double q = (y - mid) / half;
            r = pr * (0.75 + 0.9 * q * q);
        }
        return r - pd;
    }

    double layerDensity(Column c, int li, int x, int y, int z) {
        LayerCol lc = layerCol(c, li);
        double W = shape.waterY(li);
        int top = shape.top(li), bot = shape.bottom(li);
        double d = SOLID;

        // ---- open space: caverns, rifts, the route and its set pieces
        if (lc.aC > lc.aF) d = Math.min(d, Math.max(lc.aF - y, y - lc.aC));
        if (lc.bC > lc.bF) d = Math.min(d, Math.max(lc.bF - y, y - lc.bC));
        Route r = lc.route;
        if (lc.rOn && r.rift && lc.rrC > lc.rrF) d = Math.min(d, Math.max(lc.rrF - y, y - lc.rrC));
        if (lc.wOn) d = Math.min(d, vault(lc.wD, y, lc.rF, lc.rW, lc.rH));
        if (lc.w2On) d = Math.min(d, vault(lc.wD2, y, lc.rF2, lc.rW2, lc.rH2));
        for (int k = 0; k < lc.hallN; k++) d = Math.min(d, hallSdf(lc.hall[k], lc.hallD[k], y));
        for (Route.Link l : r.links) {
            if (Math.abs(x - (l.x1() + l.x2()) * 0.5) > Math.abs(l.x2() - l.x1()) * 0.5 + 10) continue;
            if (Math.abs(z - (l.z1() + l.z2()) * 0.5) > Math.abs(l.z2() - l.z1()) * 0.5 + 10) continue;
            d = Math.min(d, l.dist(x, y, z));
        }
        if (d > -14 && d < 14) d += rough(li, x, y, z);
        // Geodes keep their round shape (only a gentle wobble) so their layered rims follow the wall.
        if (lc.geoN > 0) d = Math.min(d, geodeDist(lc, x, y, z));

        // ---- rock that has to stay
        if (lc.aC > lc.aF && lc.aPd < lc.aPr * 2.5) d = Math.max(d, pillarSolid(lc.aPd, lc.aPr, lc.aHour, y, lc.aF, lc.aC));
        if (lc.bC > lc.bF && lc.bPd < lc.bPr * 2.5) d = Math.max(d, pillarSolid(lc.bPd, lc.bPr, lc.bHour, y, lc.bF, lc.bC));
        d = Math.max(d, mustStay(lc, x, y, z));
        Plans.Gate en = lc.entry, ex = lc.exit;
        if (top - y < SEAL) d = Math.max(d, SEAL - (top - y));
        if (y - bot < SEAL) d = Math.max(d, SEAL - (y - bot));
        if (ex != null && y < W + 1) {
            // A dry shell around the way down where it passes under the water table.
            double pxz = ex.passageXZ(x, y, z);
            if (pxz < ex.radius * 2.4 + 4) d = Math.max(d, ex.radius * 1.45 + 3.5 - pxz);
        }

        // ---- space that must stay open: the walking line of the route, the landings and the passages
        d = Math.min(d, mustOpen(lc, x, y, z));
        if (y > en.y1 - 3) d = Math.min(d, passage(en, x, y, z));
        if (ex != null && y < ex.y0 + 3) d = Math.min(d, passage(ex, x, y, z));
        return d;
    }

    /** The rock under the walking line: positive inside, -SOLID away from it. */
    private static double pathSlab(double pathD, double floor, double slab, int y) {
        if (pathD >= CORE + 3 || y >= floor || y <= floor - slab - 2) return -SOLID;
        return Math.min(Math.min(floor - y, y - (floor - slab)), CORE + 2.2 - pathD);
    }

    /** The walking space over the walking line: negative inside, SOLID away from it. */
    private static double pathCore(double pathD, double floor, int y) {
        if (pathD >= CORE + 1.5 || y <= floor || y >= floor + 8) return SOLID;
        double dy = (y - (floor + 3.6)) / 1.1;
        return Math.max(Math.sqrt(pathD * pathD + dy * dy) - CORE, floor + 0.5 - y);
    }

    /**
     * Rock that has to be there whatever the caves do: the path's floor, natural bridges, basalt arches and the floors
     * of the landings. Positive inside, -SOLID where none of them reaches.
     */
    private double mustStay(LayerCol lc, int x, int y, int z) {
        double d = -SOLID;
        Route r = lc.route;
        if (lc.wOn) d = Math.max(d, pathSlab(lc.wD, lc.rF, lc.rSlab, y));
        if (lc.w2On) d = Math.max(d, pathSlab(lc.wD2, lc.rF2, lc.rSlab2, y));
        if (lc.bridge != null) {
            Route.Bridge b = lc.bridge;
            double across = Math.abs(lc.rV), along = Math.abs(lc.rS - b.s());
            if (across < b.span() && y <= b.y() + 3 && y >= b.y() - 14) {
                double q = across / b.span();
                double thick = 3.0 + 9.0 * q * q * q;
                double deck = b.y() + 1.5 * q * q;
                double vert = Math.min(deck - y, y - (deck - thick));
                double side = b.halfAlong() * (1 + 0.7 * q * q) - along;
                d = Math.max(d, Math.min(vert, side) + 0.8 * detail.noise3(x / 5.0, y / 5.0, z / 5.0));
            }
        }
        if (lc.arch != null) {
            Arch a = lc.arch;
            double across = Math.abs(lc.archAcross), along = Math.abs(lc.archAlong);
            if (across < a.span() && y <= a.deckY() + 2 && y >= a.deckY() - 40) {
                double q = across / a.span();
                double deck = a.deckY() - 10 * q * q;
                double thick = 5 + 22 * q * q * q;
                double vert = Math.min(deck - y, y - (deck - thick));
                double side = a.halfAlong() * (1 + 1.2 * q * q) - along;
                d = Math.max(d, Math.min(vert, side) + 1.5 * detail.noise3(x / 6.0, y / 6.0, z / 6.0));
            }
        }
        Plans.Gate en = lc.entry, ex = lc.exit;
        if (lc.entryD < en.landR) d = Math.max(d, Math.min(Math.min(en.lFloor - y, y - (en.lFloor - 7)), en.landR - lc.entryD));
        if (ex != null && lc.exitD < ex.landR) d = Math.max(d, Math.min(Math.min(ex.uFloor - y, y - (ex.uFloor - 7)), ex.landR - lc.exitD));
        if (r.bedrockEnd && lc.endD < r.endR) d = Math.max(d, Math.min(Math.min(r.endFloor - y, y - (r.endFloor - 7)), r.endR - lc.endD));
        return d;
    }

    /** Space that has to stay open: the route's walking line and the landings. Negative inside, SOLID where none reaches. */
    private double mustOpen(LayerCol lc, int x, int y, int z) {
        double d = SOLID;
        Route r = lc.route;
        if (lc.wOn && y < lc.cap) d = Math.min(d, pathCore(lc.wD, lc.rF, y));
        if (lc.w2On && y < lc.cap2) d = Math.min(d, pathCore(lc.wD2, lc.rF2, y));
        Plans.Gate en = lc.entry, ex = lc.exit;
        if (lc.entryD < en.landR + 4 && y > en.lFloor - 2 && y < en.lCy + en.landHalf + 4)
            d = Math.min(d, landing(lc.entryD, en.landR, en.lCy, en.landHalf, en.lFloor, x, y, z));
        if (ex != null && lc.exitD < ex.landR + 4 && y > ex.uFloor - 2 && y < ex.uCy + ex.landHalf + 4)
            d = Math.min(d, landing(lc.exitD, ex.landR, ex.uCy, ex.landHalf, ex.uFloor, x, y, z));
        if (r.bedrockEnd && lc.endD < r.endR + 4 && y > r.endFloor - 2 && y < r.endFloor + r.endHalf * 1.6 + 4)
            d = Math.min(d, landing(lc.endD, r.endR, r.endFloor + r.endHalf * 0.55, r.endHalf, r.endFloor, x, y, z));
        return d;
    }

    /**
     * Minecraft only samples the density every 8 blocks up and every 4 across, and interpolates in between, which
     * rounds off anything thinner than that. This puts the thin pieces back block by block: rock that has to be there
     * (the path's floor, bridges, arches, landing floors) and space that has to stay open (the walking line, the
     * landings and the way down). {@code coarse} is the interpolated density on Minecraft's scale; so is the result.
     */
    public double fine(int x, int y, int z, double coarse) {
        int li = shape.layerAt(y);
        if (li < 0) return coarse;
        LayerCol lc = routeCol(column(x, z), li);
        double keep = mustStay(lc, x, y, z), open = mustOpen(lc, x, y, z);
        if (keep <= -SOLID && open >= SOLID) return coarse;
        Plans.Gate en = lc.entry, ex = lc.exit;
        if (y > en.y1 - 3) open = Math.min(open, passage(en, x, y, z));
        if (ex != null && y < ex.y0 + 3) open = Math.min(open, passage(ex, x, y, z));
        double d = Math.min(Math.max(coarse * 8.0, keep), open) / 8.0;
        return d < -1 ? -1 : (d > 1 ? 1 : d);
    }

    private double landing(double dxz, double rad, double cy, double half, double floor, int x, int y, int z) {
        double ex = dxz / rad, ey = (y - cy) / half;
        double ell = (Math.sqrt(ex * ex + ey * ey) - 1) * half + 1.8 * detail.noise3(x / 9.0, y / 7.0, z / 9.0);
        double fl = floor + 0.9 * detail.noise2(x / 6.0, z / 6.0) - y;
        return Math.max(ell, fl);
    }

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
        if (li < 0 || y >= shape.waterY(li)) return AIR;
        Column c = column(x, z);
        LayerCol lc = layerCol(c, li);
        Plans.Gate ex = lc.exit;
        if (ex != null) {
            double pxz = ex.passageXZ(x, y, z);
            if (pxz < ex.radius * 1.45 + 3.0) return AIR;
        }
        return shape.lava(li) ? LAVA : WATER;
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
        Column c = column(x, z);
        LayerCol lc = layerCol(c, li);
        switch (li) {
            case LANTERN:
                return inRoute(lc, li, y) ? DeepBiome.IVORY_ROOT_CAVERN : DeepBiome.LANTERN_LAKE;
            case BLUE:
                for (int k = 0; k < lc.geoN; k++) if (geodeSdf(lc.geo[k], lc.geoD2[k], y) < 6) return DeepBiome.ASURINE_GEODES;
                if (inRoute(lc, li, y)) return DeepBiome.DEEP_BLUE_RIFT;
                return lc.regionV >= 0 ? DeepBiome.SILVER_FERN_RIFT : DeepBiome.DEEP_DARK;
            case EMBER:
                return inRoute(lc, li, y) ? DeepBiome.RUST_SPRINGS : DeepBiome.EMBER_GARDENS;
            default:
                return DeepBiome.BASALT_GALLERIES;
        }
    }

    public static DeepBiome routeBiome(int li) {
        return switch (li) {
            case LANTERN -> DeepBiome.IVORY_ROOT_CAVERN;
            case BLUE -> DeepBiome.DEEP_BLUE_RIFT;
            case EMBER -> DeepBiome.RUST_SPRINGS;
            default -> DeepBiome.BASALT_GALLERIES;
        };
    }

    private boolean inRoute(LayerCol lc, int li, int y) {
        if (lc.rOn && lc.route.rift) {
            if (Math.abs(lc.rV) < lc.rHalfTop + 6 && y > lc.route.W - 6 && y < lc.rTop + 10) return true;
        }
        if (lc.wOn && lc.wD < lc.rW + 8 && y > lc.rF - 10 && y < lc.rF + lc.rH + 10) return true;
        for (int k = 0; k < lc.hallN; k++) if (hallSdf(lc.hall[k], lc.hallD[k], y) < 8) return true;
        if (lc.entryD < lc.entry.landR + 6 && Math.abs(y - lc.entry.lCy) < lc.entry.landHalf + 6) return true;
        return lc.exit != null && lc.exitD < lc.exit.landR + 6 && Math.abs(y - lc.exit.uCy) < lc.exit.landHalf + 6;
    }

    // ================================================================================================ queries for materials and features

    /** Depth into the rock around the nearest refuge geode (0 at its inner surface), or a large value when none is near. */
    public double geodeShellDepth(int x, int y, int z) {
        int li = shape.layerAt(y);
        if (li != BLUE) return 1e9;
        LayerCol lc = layerCol(column(x, z), li);
        return lc.geoN == 0 ? 1e9 : geodeDist(lc, x, y, z);
    }

    /** Signed distance to the nearest refuge geode's inner surface, with the same wobble the terrain uses. */
    private double geodeDist(LayerCol lc, int x, int y, int z) {
        double best = 1e9;
        for (int k = 0; k < lc.geoN; k++) best = Math.min(best, geodeSdf(lc.geo[k], lc.geoD2[k], y));
        if (best > 12) return best;
        return best + 0.7 * detail.noise3(x / 6.0, y / 6.0, z / 6.0);
    }

    /** True in the bowl of the bedrock landing at the end of the last layer's route. */
    public boolean bedrockLanding(int x, int y, int z) {
        int li = shape.layerAt(y);
        if (li != shape.layerCount - 1) return false;
        LayerCol lc = layerCol(column(x, z), li);
        Route r = lc.route;
        return r.bedrockEnd && lc.endD < r.endR + 3 && y <= r.endFloor + 2 && y > r.endFloor - 6;
    }

    /**
     * For layer li at a column: {distance from the route's walking line, the walking floor there, and the same two for
     * a second stretch of the line passing nearby (distance 1e9 when there is none)}, or null off the route.
     */
    public double[] routePathAt(int li, int x, int z) {
        LayerCol lc = routeCol(column(x, z), li);
        if (!lc.wOn) return null;
        return new double[]{lc.wD, lc.rF, lc.w2On ? lc.wD2 : 1e9, lc.w2On ? lc.rF2 : 0};
    }

    /** Distance from the route's walking line, and the walking floor there: {distance, floorY}, or null off the route. */
    public double[] routePath(int x, int y, int z) {
        int li = shape.layerAt(y);
        if (li < 0) return null;
        LayerCol lc = routeCol(column(x, z), li);
        if (!lc.wOn) return null;
        return new double[]{lc.wD, lc.rF};
    }
}
