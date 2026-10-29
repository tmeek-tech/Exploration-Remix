package com.explorationremix.world.terrain;

/**
 * The two safe set pieces carved into the pack's own caves just above its old floor: the Silver Fern Rift (a tall,
 * narrow rift whose walls step up in mossy ledges over a stream) and the Ivory Root Cavern (a round cavern around a
 * still lake, with terraced shores). Set-piece sized, 40 to 60 blocks across. At most one per cell, kept away from the
 * first gate and from any ocean or river above it. Each piece knows its own shape ({@link Piece#dist}).
 */
public final class Upper {
    private Upper() {}

    /** One set piece. Its bounds include the margin its biome and fluids reach. */
    public abstract static sealed class Piece permits Rift, Cavern {
        public final double minX, maxX, minZ, maxZ;
        /** The surface of the rift's stream or the cavern's lake. */
        public final double waterY;
        /** The highest open block any part of the piece can reach. */
        public final double topY;

        Piece(double minX, double maxX, double minZ, double maxZ, double waterY, double topY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.waterY = waterY;
            this.topY = topY;
        }

        public boolean near(double x, double z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }

        public abstract DeepBiome biome();

        public abstract double centerX();

        public abstract double centerZ();

        /** Roughly how far (x, z) is from the piece's open space (negative inside). */
        public abstract double distXZ(double x, double z);

        /** The noise its walls are shaped with; set when it is planned. */
        Noise noise;

        Noise noise() {
            Noise n = noise;
            return n != null ? n : Noise.of(0L);
        }

        /**
         * Signed distance to its open space (negative inside), given the column's distance {@code d} from the rift's
         * centre line (or the cavern's axis) and {@code s}, the arc length along the rift.
         */
        public abstract double dist(double d, double s, int x, int y, int z);
    }

    /** The noise the set pieces are shaped with, one set per world seed. */
    static final class Noise {
        private static volatile Noise last;
        final long seed;
        final Perlin ledge, ceil, rough;

        private Noise(long seed) {
            this.seed = seed;
            long s = seed ^ 0x1F83D9ABFB41BD6BL;
            ledge = new Perlin(Hashing.mix(s, 1, 0, 0));
            ceil = new Perlin(Hashing.mix(s, 2, 0, 0));
            rough = new Perlin(Hashing.mix(s, 3, 0, 0));
        }

        static Noise of(long seed) {
            Noise n = last;
            if (n == null || n.seed != seed) {
                n = new Noise(seed);
                last = n;
            }
            return n;
        }
    }

    /** The Silver Fern Rift: a winding rift, a stream along its floor and stepped ledges up both walls under a roof. */
    public static final class Rift extends Piece {
        final double[] px, pz;
        final int n;
        public final double length, bedY, ledgeTop, halfTop, streamHalf, stepH, roofH;

        Rift(double[] px, double[] pz, double bedY, double ledgeTop, double halfTop, double streamHalf, double stepH, double roofH) {
            super(min(px) - halfTop - 10, max(px) + halfTop + 10, min(pz) - halfTop - 10, max(pz) + halfTop + 10, bedY + 1.6, ledgeTop + roofH + 5);
            this.px = px;
            this.pz = pz;
            this.n = px.length;
            double len = 0;
            for (int i = 1; i < n; i++) len += Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
            this.length = len;
            this.bedY = bedY;
            this.ledgeTop = ledgeTop;
            this.halfTop = halfTop;
            this.streamHalf = streamHalf;
            this.stepH = stepH;
            this.roofH = roofH;
        }

        /** Nearest point on the centre line: out = {distance, arc length, signed side}. */
        public void nearest(double x, double z, double[] out) {
            double best = 1e18, bs = 0, bv = 0, acc = 0;
            for (int i = 1; i < n; i++) {
                double ax = px[i - 1], az = pz[i - 1], dx = px[i] - ax, dz = pz[i] - az;
                double l2 = dx * dx + dz * dz, l = Math.sqrt(l2);
                double t = l2 < 1e-9 ? 0 : ((x - ax) * dx + (z - az) * dz) / l2;
                t = t < 0 ? 0 : (t > 1 ? 1 : t);
                double qx = x - (ax + dx * t), qz = z - (az + dz * t);
                double d2 = qx * qx + qz * qz;
                if (d2 < best) {
                    best = d2;
                    bs = acc + l * t;
                    bv = (dx * (z - az) - dz * (x - ax)) >= 0 ? 1 : -1;
                }
                acc += l;
            }
            out[0] = Math.sqrt(best);
            out[1] = bs;
            out[2] = bv * out[0];
        }

        public double pointX(int i) { return px[i]; }

        public double pointZ(int i) { return pz[i]; }

        public int points() { return n; }

        @Override public DeepBiome biome() { return DeepBiome.SILVER_FERN_RIFT; }

        @Override public double centerX() { return px[n / 2]; }

        @Override public double centerZ() { return pz[n / 2]; }

        @Override public double distXZ(double x, double z) {
            double best = 1e18;
            for (int i = 0; i < n; i++) best = Math.min(best, (px[i] - x) * (px[i] - x) + (pz[i] - z) * (pz[i] - z));
            return Math.sqrt(best) - halfTop;
        }

        @Override public double dist(double d, double s, int x, int y, int z) {
            // Stepped ledges up both walls from the stream, under a low roof; the ends close in.
            Noise nz = noise();
            double v = Math.max(0, d + 1.5 * nz.ledge.noise2(x / 10.0, z / 10.0 + 50));
            double endFade = Terrain.smooth((Math.min(s, length - s) - 2) / 10.0);
            double half = halfTop * (0.45 + 0.55 * endFade);
            if (v > half + 8) return Terrain.SOLID;
            double step = stepH + 0.8 * nz.ledge.noise2(s / 24.0, 7.7);
            double fl = Terrain.riftFloor(v, bedY, ledgeTop, half, streamHalf, step);
            double q = Math.min(1, v / half);
            double ce = ledgeTop + roofH * (1 - q * q) + 1.8 * nz.ceil.noise2(x / 9.0, z / 9.0 + 31);
            ce = fl + (ce - fl) * endFade;
            // Rough walls; none where the ends have closed, so no pockets open in the rock beyond them.
            return Math.max(fl - y, y - ce) + 1.2 * endFade * nz.rough.noise3(x / 8.0, y / 6.0, z / 8.0);
        }
    }

    /** The Ivory Root Cavern: a round cavern with a lake in the middle and shores that step up in pale terraces. */
    public static final class Cavern extends Piece {
        public final double cx, cz, rxz, ry, cy, bedY, rise;

        Cavern(double cx, double cz, double rxz, double ry, double cy, double lakeY, double bedY, double rise) {
            super(cx - rxz - 10, cx + rxz + 10, cz - rxz - 10, cz + rxz + 10, lakeY, cy + ry + 6);
            this.cx = cx;
            this.cz = cz;
            this.rxz = rxz;
            this.ry = ry;
            this.cy = cy;
            this.bedY = bedY;
            this.rise = rise;
        }

        @Override public DeepBiome biome() { return DeepBiome.IVORY_ROOT_CAVERN; }

        @Override public double centerX() { return cx; }

        @Override public double centerZ() { return cz; }

        @Override public double distXZ(double x, double z) { return Math.hypot(x - cx, z - cz) - rxz; }

        @Override public double dist(double d, double s, int x, int y, int z) {
            // A round cavern: a lake in the middle, then pale terraces stepping up to the walls.
            Noise nz = noise();
            double ex = d / rxz, ey = (y - cy) / ry;
            if (ex > 1.4) return Terrain.SOLID;
            double ell = (Math.sqrt(ex * ex + ey * ey) - 1) * Math.min(rxz, ry) + 2.0 * nz.rough.noise3(x / 12.0, y / 9.0, z / 12.0);
            double lakeEx = 0.5 + 0.06 * nz.ledge.noise2(x / 14.0 + 13, z / 14.0);
            double fl;
            if (ex < lakeEx) fl = bedY + (waterY - 1 - bedY) * (ex / lakeEx) * (ex / lakeEx);
            else fl = waterY + 1 + Terrain.stairs((ex - lakeEx) / (1 - lakeEx) * rise + 0.8 * nz.ledge.noise2(x / 7.0, z / 7.0), 2.0, 0.8);
            return Math.max(ell, fl - y);
        }
    }

    private static double min(double[] a) { double m = a[0]; for (double v : a) m = Math.min(m, v); return m; }

    private static double max(double[] a) { double m = a[0]; for (double v : a) m = Math.max(m, v); return m; }

    // ------------------------------------------------------------------------------------------------ planning

    /** Asked before a piece is placed: true where the surface above is an ocean, river or beach. */
    public interface SurfaceProbe {
        boolean wet(int x, int z);
    }

    /**
     * The piece in a cell, or null. It stays inside the cell, keeps clear of each {x, z, radius} in {@code avoid}, and
     * is never put under water.
     */
    static Piece plan(long seed, int cx, int cz, int cellSize, double margin, int packFloor, double[][] avoid, SurfaceProbe probe) {
        long h = Hashing.mix(seed, 3001, cx, cz);
        if (Hashing.unit(h) > 0.85) return null;
        boolean rift = Hashing.unit(Hashing.mix(h, 1, 0, 0)) < 0.5;
        outer:
        for (int attempt = 0; attempt < 10; attempt++) {
            long ha = Hashing.mix(h, 10 + attempt, 0, 0);
            Piece p = rift ? planRift(ha, cx, cz, cellSize, margin, packFloor) : planCavern(ha, cx, cz, cellSize, margin, packFloor);
            for (double[] a : avoid) if (p.distXZ(a[0], a[1]) < a[2]) continue outer;
            if (probe != null && wetAbove(p, probe)) continue;
            p.noise = Noise.of(seed);
            return p;
        }
        return null;
    }

    private static boolean wetAbove(Piece p, SurfaceProbe probe) {
        for (int i = 0; i <= 4; i++) for (int j = 0; j <= 4; j++) {
            int x = (int) (p.minX + (p.maxX - p.minX) * i / 4.0), z = (int) (p.minZ + (p.maxZ - p.minZ) * j / 4.0);
            if (probe.wet(x, z)) return true;
        }
        return false;
    }

    private static Rift planRift(long h, int cx, int cz, int cellSize, double margin, int packFloor) {
        double len = Hashing.range(Hashing.mix(h, 1, 0, 0), 46, 58);
        double halfTop = Hashing.range(Hashing.mix(h, 2, 0, 0), 8, 11);
        double room = margin + len * 0.5 + halfTop + 12;
        double lo = cx * (double) cellSize + room, hi = (cx + 1) * (double) cellSize - room;
        double mx = lo < hi ? Hashing.range(Hashing.mix(h, 3, 0, 0), lo, hi) : (cx + 0.5) * cellSize;
        lo = cz * (double) cellSize + room;
        hi = (cz + 1) * (double) cellSize - room;
        double mz = lo < hi ? Hashing.range(Hashing.mix(h, 4, 0, 0), lo, hi) : (cz + 0.5) * cellSize;
        double ang = Hashing.range(Hashing.mix(h, 5, 0, 0), 0, Math.PI * 2);
        double dx = Math.cos(ang), dz = Math.sin(ang), nx = -dz, nz = dx;
        // Five control points along the line, pushed sideways, then a smooth curve through them.
        double[] cxs = new double[5], czs = new double[5];
        for (int k = 0; k < 5; k++) {
            double t = k / 4.0 - 0.5;
            double off = (k == 0 || k == 4) ? 0 : Hashing.range(Hashing.mix(h, 20 + k, 0, 0), -0.12, 0.12) * len;
            cxs[k] = mx + dx * t * len + nx * off;
            czs[k] = mz + dz * t * len + nz * off;
        }
        int per = (int) Math.ceil(len / 4 / 2.0);
        int n = 4 * per + 1;
        double[] px = new double[n], pz = new double[n];
        for (int seg = 0; seg < 4; seg++) {
            int a = Math.max(0, seg - 1), b = seg, c = seg + 1, d = Math.min(4, seg + 2);
            for (int i = 0; i < per; i++) {
                double t = i / (double) per;
                px[seg * per + i] = catmull(cxs[a], cxs[b], cxs[c], cxs[d], t);
                pz[seg * per + i] = catmull(czs[a], czs[b], czs[c], czs[d], t);
            }
        }
        px[n - 1] = cxs[4];
        pz[n - 1] = czs[4];
        double bedY = packFloor + 12;
        double ledgeTop = bedY + Hashing.range(Hashing.mix(h, 6, 0, 0), 20, 26);
        double streamHalf = Hashing.range(Hashing.mix(h, 7, 0, 0), 1.5, 2.5);
        double stepH = Hashing.range(Hashing.mix(h, 8, 0, 0), 3.5, 5.0);
        double roofH = Hashing.range(Hashing.mix(h, 9, 0, 0), 5, 8);
        return new Rift(px, pz, bedY, ledgeTop, halfTop, streamHalf, stepH, roofH);
    }

    private static Cavern planCavern(long h, int cx, int cz, int cellSize, double margin, int packFloor) {
        double rxz = Hashing.range(Hashing.mix(h, 1, 0, 0), 21, 28);
        double ry = Hashing.range(Hashing.mix(h, 2, 0, 0), 13, 16);
        double room = margin + rxz + 12;
        double lo = cx * (double) cellSize + room, hi = (cx + 1) * (double) cellSize - room;
        double x = lo < hi ? Hashing.range(Hashing.mix(h, 3, 0, 0), lo, hi) : (cx + 0.5) * cellSize;
        lo = cz * (double) cellSize + room;
        hi = (cz + 1) * (double) cellSize - room;
        double z = lo < hi ? Hashing.range(Hashing.mix(h, 4, 0, 0), lo, hi) : (cz + 0.5) * cellSize;
        double lakeY = packFloor + 14;
        double bedY = lakeY - Hashing.range(Hashing.mix(h, 5, 0, 0), 3, 4.5);
        double rise = Hashing.range(Hashing.mix(h, 6, 0, 0), 6, 9);
        double cy = lakeY + ry * 0.3;
        return new Cavern(x, z, rxz, ry, cy, lakeY, bedY, rise);
    }

    private static double catmull(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }
}
