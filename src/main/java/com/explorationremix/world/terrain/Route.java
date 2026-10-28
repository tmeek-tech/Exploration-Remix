package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.List;

/**
 * One layer's route inside one cell: a smoothed polyline from the entry landing to the exit landing, with a floor
 * profile that steps down the layer, and the layer's own set pieces along it: in the Blue Reaches a rift with bridges
 * and refuge geodes, in the Deep Gardens a chain of lake halls with Rust Springs refuges beside it, in the Basalt
 * Galleries obsidian tube refuges. Built once per (layer, cell) and only read afterwards.
 */
public final class Route {
    public final int layer, cellX, cellZ;
    final int n;
    final double[] px, pz, ps;
    public final double length;
    /** Per point: path floor y, corridor half width, corridor height, sideways offset of the path, slab thickness. */
    final double[] floor, half, height, offset, slab;
    /** Per point (rift layers): top of the rift and its half width at the top. */
    final double[] riftTop, riftHalf;
    final boolean rift;
    /**
     * The walking line, one point per centre-line point: where the path really runs. In a rift it hugs a ledge beside
     * the centre line; elsewhere it is the centre line. {@code ws} is the arc length along it.
     */
    final double[] wx, wz, ws;
    public double walkLength;
    /** Steepest climb allowed on the path's ramps, in blocks up per block walked. */
    static final double MAX_SLOPE = 0.85;
    public final double W;
    public final Plans.Gate entry, exit;
    public final double endX, endZ, endFloor, endR, endHalf;
    public final boolean bedrockEnd;
    public final List<Hall> halls = new ArrayList<>();
    public final List<Bridge> bridges = new ArrayList<>();
    public final List<Geode> geodes = new ArrayList<>();
    public final List<Spring> springs = new ArrayList<>();
    public final List<Tube> tubes = new ArrayList<>();
    public final List<Link> links = new ArrayList<>();
    final int side;
    /** In a rift: which side of the rift the ledge path keeps to, per point (-1 to 1; it changes over a bridge). */
    double[] sideAt;
    /** Arc lengths along the centre line where the ledge path crosses the rift. */
    final List<Double> crossings = new ArrayList<>();
    final double streamHalf;
    // spatial index of segments
    private final double gx0, gz0, bucket;
    private final int gn;
    private final int[][] buckets, walkBuckets;
    final double reach;

    /**
     * A big chamber on the route. The floor is a bowl: {@code floorY} in the middle, rising by {@code rise} at the rim.
     * A lake hall holds still water up to {@code lakeY} (NaN for a dry hall), with islands rising out of it.
     */
    /** A hall on the route; a lake hall holds water up to lakeY out to lakeR from its axis (NaN and 0 when dry). */
    public record Hall(double x, double z, double cy, double rxz, double ry, double floorY, double rise, double lakeY, double lakeR) {
        public boolean lake() { return !Double.isNaN(lakeY); }
    }

    /** How far out a hall's terraced bowl stays under its lake, as a fraction of the hall's radius. */
    static double lakeFraction(double floorY, double rise, double lakeY) {
        double ex = 0;
        while (ex < 1 && floorY + Terrain.terraced(ex * ex * rise, 3.0) < lakeY) ex += 0.002;
        return ex;
    }

    /**
     * A Rust Springs refuge: a cavern beside the route whose floor is a staircase of terraces stepping down along
     * (dirX, dirZ), from {@code floorY + drop / 2} at the end nearest the path to {@code floorY - drop / 2}.
     */
    public record Spring(double x, double y, double z, double rxz, double ry, double floorY, double dirX, double dirZ, double drop) {
        /** The staircase floor at a point, before the terraces are cut into it. */
        public double rampFloor(double px, double pz) {
            double t = ((px - x) * dirX + (pz - z) * dirZ) / rxz;
            t = t < -1 ? -1 : (t > 1 ? 1 : t);
            return floorY - drop * 0.5 * t;
        }
    }

    /** One straight piece of an obsidian tube refuge: a smooth round tube between two points. */
    public record Tube(double x1, double y1, double z1, double x2, double y2, double z2, double r) {
        /** Distance from the tube's axis and the axis height at the nearest point: {distance, axisY}. */
        public void axis(double x, double y, double z, double[] out) {
            double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
            double len2 = dx * dx + dy * dy + dz * dz;
            double t = len2 < 1e-9 ? 0 : ((x - x1) * dx + (y - y1) * dy + (z - z1) * dz) / len2;
            t = t < 0 ? 0 : (t > 1 ? 1 : t);
            double ex = x - (x1 + dx * t), ey = y - (y1 + dy * t), ez = z - (z1 + dz * t);
            out[0] = Math.sqrt(ex * ex + ey * ey + ez * ez);
            out[1] = y1 + dy * t;
        }

        public double minX() { return Math.min(x1, x2) - r; }
        public double maxX() { return Math.max(x1, x2) + r; }
        public double minZ() { return Math.min(z1, z2) - r; }
        public double maxZ() { return Math.max(z1, z2) + r; }
    }

    /** A natural stone bridge across the rift at arc length {@code s}, deck top at {@code y}. */
    public record Bridge(double s, double y, double halfAlong, double span) {}

    /** A refuge geode: a hollow sphere with a flattened floor. */
    public record Geode(double x, double y, double z, double r) {}

    /** A tunnel that joins the path to something beside it. */
    public record Link(double x1, double y1, double z1, double x2, double y2, double z2, double r) {
        public double dist(double x, double y, double z) {
            double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
            double len2 = dx * dx + dy * dy + dz * dz;
            double t = len2 < 1e-9 ? 0 : ((x - x1) * dx + (y - y1) * dy + (z - z1) * dz) / len2;
            t = t < 0 ? 0 : (t > 1 ? 1 : t);
            double ex = x - (x1 + dx * t), ey = (y - (y1 + dy * t)) * 1.25, ez = z - (z1 + dz * t);
            return Math.sqrt(ex * ex + ey * ey + ez * ez) - r;
        }
    }

    Route(Terrain terrain, int layer, int cellX, int cellZ, long h, List<double[]> pts, double sFloor, double eFloor,
          Plans.Gate entry, Plans.Gate exit, double endX, double endZ, double endR, double endHalf, double W) {
        this.layer = layer;
        this.cellX = cellX;
        this.cellZ = cellZ;
        this.entry = entry;
        this.exit = exit;
        this.endX = endX;
        this.endZ = endZ;
        this.endFloor = eFloor;
        this.endR = endR;
        this.endHalf = endHalf;
        this.bedrockEnd = exit == null;
        this.W = W;
        Shape shape = terrain.shape;
        LayerSettings ls = terrain.settings.layers().get(layer);
        n = pts.size();
        px = new double[n]; pz = new double[n]; ps = new double[n];
        double acc = 0;
        for (int i = 0; i < n; i++) {
            px[i] = pts.get(i)[0];
            pz[i] = pts.get(i)[1];
            if (i > 0) acc += Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
            ps[i] = acc;
        }
        length = Math.max(1, acc);
        floor = new double[n]; half = new double[n]; height = new double[n]; offset = new double[n]; slab = new double[n];
        riftTop = new double[n]; riftHalf = new double[n];
        rift = layer == Terrain.BLUE;
        side = Hashing.unit(Hashing.mix(h, 77, 0, 0)) < 0.5 ? -1 : 1;
        if (rift) sideAt = ledgeSides();
        streamHalf = Hashing.range(Hashing.mix(h, 78, 0, 0), 3.0, 4.5);
        Perlin along = new Perlin(Hashing.mix(h, 79, 0, 0));
        int top = shape.top(layer);

        // ---- floor profile: terraced steps from the entry landing's floor down to the exit landing's floor
        double descEnd = 0.92;
        double stepAvg = switch (layer) {
            case Terrain.GARDENS -> 6.0;
            case Terrain.BLUE -> 9.0;
            default -> 11.0;
        };
        if (rift) {
            wx = new double[n]; wz = new double[n]; ws = new double[n];
        } else {
            wx = px; wz = pz; ws = ps;
            walkLength = length;
        }
        dropEstimate = Math.max(0, sFloor - eFloor);
        double[][] lakes = layer == Terrain.GARDENS ? planLakeStretches(h) : null;
        if (lakes != null) floorWithFlats(h, ps, length, sFloor, eFloor, lakes, stepAvg, W);
        else floorProfile(h, ps, length, sFloor, eFloor, descEnd, stepAvg, W);
        corridor(along, ls, top, W);
        if (rift) {
            // The ledge path is longer or shorter than the centre line on bends: lay the steps out along the path
            // itself, then settle the ledge on the new floor, and lay the steps out once more on the final line.
            walk();
            for (int pass = 0; pass < 2; pass++) {
                floorProfile(h, ws, walkLength, sFloor, eFloor, descEnd, stepAvg, W);
                corridor(along, ls, top, W);
                walk();
            }
            floorProfile(h, ws, walkLength, sFloor, eFloor, descEnd, stepAvg, W);
            double[] walked = offset.clone();
            corridor(along, ls, top, W);
            System.arraycopy(walked, 0, offset, 0, n);
        }

        // ---- set pieces
        double hMin = ls.heightMin(), hMax = ls.heightMax();
        switch (layer) {
            case Terrain.GARDENS -> {
                planLakeHalls(h, lakes, W);
                planSprings(h, terrain, W);
            }
            case Terrain.BLUE -> planRift(h, terrain, W);
            default -> planTubes(h, terrain, W);
        }

        // ---- spatial index
        double r = 0, rw = 0;
        for (int i = 0; i < n; i++) {
            r = Math.max(r, rift ? riftHalf[i] + 6 : half[i] + 4);
            rw = Math.max(rw, half[i] * 1.3 + 6);
        }
        reach = r + 14;
        gx0 = cellX * (double) terrain.plans.cellSize;
        gz0 = cellZ * (double) terrain.plans.cellSize;
        bucket = 24;
        gn = (int) Math.ceil(terrain.plans.cellSize / bucket);
        buckets = index(px, pz, reach);
        walkBuckets = rift ? index(wx, wz, rw) : buckets;
    }

    private int[][] index(double[] xs, double[] zs, double pad) {
        List<List<Integer>> tmp = new ArrayList<>(gn * gn);
        for (int i = 0; i < gn * gn; i++) tmp.add(new ArrayList<>());
        for (int i = 0; i + 1 < n; i++) {
            double minX = Math.min(xs[i], xs[i + 1]) - pad, maxX = Math.max(xs[i], xs[i + 1]) + pad;
            double minZ = Math.min(zs[i], zs[i + 1]) - pad, maxZ = Math.max(zs[i], zs[i + 1]) + pad;
            int bx0 = clampI((int) Math.floor((minX - gx0) / bucket)), bx1 = clampI((int) Math.floor((maxX - gx0) / bucket));
            int bz0 = clampI((int) Math.floor((minZ - gz0) / bucket)), bz1 = clampI((int) Math.floor((maxZ - gz0) / bucket));
            for (int bx = bx0; bx <= bx1; bx++) for (int bz = bz0; bz <= bz1; bz++) tmp.get(bx * gn + bz).add(i);
        }
        int[][] out = new int[gn * gn][];
        for (int i = 0; i < gn * gn; i++) {
            List<Integer> l = tmp.get(i);
            int[] a = new int[l.size()];
            for (int k = 0; k < a.length; k++) a[k] = l.get(k);
            out[i] = a;
        }
        return out;
    }

    /** Terraced steps from sFloor down to eFloor, laid out along the arc lengths {@code arcs} (one per point). */
    private void floorProfile(long h, double[] arcs, double total, double sFloor, double eFloor, double descEnd, double stepAvg, double W) {
        double drop = Math.max(0, sFloor - eFloor);
        double s0 = 10, s1 = Math.max(s0 + 30, total * descEnd), span = s1 - s0;
        int steps = Math.max(2, (int) Math.round(drop / stepAvg));
        // More, lower steps where the run is short, so no ramp is steeper than MAX_SLOPE.
        steps = Math.max(steps, (int) Math.ceil(1.5 * 1.3 * drop / (MAX_SLOPE * span * 0.8)));
        double[] stepLevel = new double[steps + 1];
        double[] stepTread = new double[steps];
        double sum = 0;
        double[] w = new double[steps];
        for (int j = 0; j < steps; j++) { w[j] = Hashing.range(Hashing.mix(h, 2000 + j, 0, 0), 0.7, 1.3); sum += w[j]; }
        stepLevel[0] = sFloor;
        double stepLen = span / steps;
        for (int j = 0; j < steps; j++) {
            double dj = drop * w[j] / sum;
            stepLevel[j + 1] = stepLevel[j] - dj;
            double tread = Hashing.range(Hashing.mix(h, 3000 + j, 0, 0), 0.4, 0.62);
            // A smoothstep ramp is 1.5 times steeper than its average: give it the length it needs.
            stepTread[j] = Math.max(0, Math.min(tread, 1 - 1.5 * dj / (MAX_SLOPE * stepLen)));
        }
        for (int i = 0; i < n; i++) {
            double s = arcs[i];
            double f;
            if (s <= s0) f = sFloor;
            else if (s >= s1) f = eFloor;
            else {
                double u = (s - s0) / span * steps;
                int j = Math.min(steps - 1, (int) Math.floor(u));
                double local = u - j;
                double tr = stepTread[j];
                double ramp = local <= tr ? 0 : smooth((local - tr) / (1 - tr));
                f = stepLevel[j] + (stepLevel[j + 1] - stepLevel[j]) * ramp;
            }
            floor[i] = Math.max(f, W + 2);
        }
        // Ease into the exit landing's floor over the last stretch.
        for (int i = 0; i < n; i++) {
            double rem = total - arcs[i];
            if (rem < 24) floor[i] = floor[i] + (eFloor - floor[i]) * smooth(1 - rem / 24);
        }
    }

    /** Corridor size, slab under the path and, in a rift, the rift's top and the ledge offset (from the floor). */
    private void corridor(Perlin along, LayerSettings ls, int top, double W) {
        double hMin = ls.heightMin(), hMax = ls.heightMax();
        for (int i = 0; i < n; i++) {
            double s = ps[i];
            double a = along.noise2(s / 70.0, 3.3), b = along.noise2(s / 45.0, 9.1);
            switch (layer) {
                case Terrain.GARDENS -> { half[i] = 10 + 2.5 * a; height[i] = 16 + 5 * b; }
                case Terrain.BLUE -> { half[i] = 4.8 + 0.8 * a; height[i] = 8.5 + 1.5 * b; }
                default -> { half[i] = 8.5 + 1.5 * a; height[i] = 15 + 4 * b; }
            }
            // Near the water table the slab reaches down to the lake bed, so the path becomes a causeway.
            slab[i] = floor[i] < W + 8 ? floor[i] - (W - 12) : 4 + 1.5 * (a + 1);
            if (rift) {
                double depth = hMin + (hMax - hMin) * (0.5 + 0.5 * along.noise2(s / 160.0, 21.7));
                double rt = Math.min(top - 8, Math.max(W - 2 + depth, floor[i] + 14));
                riftTop[i] = rt;
                riftHalf[i] = 20 + (rt - W) * 0.13;
                offset[i] = sideAt[i] * (riftWallAt(floor[i], rt, riftHalf[i]) + 1.5);
            }
        }
    }

    /**
     * Which side of the rift the ledge path keeps to: the outside of each bend, so the ledge never folds back on the
     * inside of a tight one. Where the bends change direction the path crosses the rift on a natural bridge.
     */
    private double[] ledgeSides() {
        int win = 12;
        double[] sinT = new double[n];
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - win), b = Math.min(n - 1, i + win);
            double ax = px[i] - px[a], az = pz[i] - pz[a], bx = px[b] - px[i], bz = pz[b] - pz[i];
            double la = Math.hypot(ax, az), lb = Math.hypot(bx, bz);
            sinT[i] = la < 1e-6 || lb < 1e-6 ? 0 : (ax * bz - az * bx) / (la * lb);
        }
        // Positive offsets lie toward the line's left normal, which is the inside of a bend whose sinT is positive.
        double[] raw = new double[n];
        int cur = 0, lastSwitch = -1000, lastInflection = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0 && Math.signum(sinT[i]) != Math.signum(sinT[i - 1])) lastInflection = i;
            int want = sinT[i] > 0.3 ? -1 : (sinT[i] < -0.3 ? 1 : 0);
            if (cur == 0 && want != 0) {
                cur = want;
                for (int k = 0; k < i; k++) raw[k] = cur;
            } else if (want != 0 && want != cur && ps[i] - (lastSwitch < 0 ? -1e9 : ps[lastSwitch]) > 110
                    && ps[lastInflection] > 60 && ps[lastInflection] < length - 60) {
                cur = want;
                lastSwitch = lastInflection;
                crossings.add(ps[lastInflection]);
                for (int k = lastInflection; k < i; k++) raw[k] = cur;
            }
            raw[i] = cur == 0 ? side : cur;
        }
        // The crossing takes about fifty blocks of path, diagonally over the rift.
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0, wsum = 0;
            for (int k = Math.max(0, i - 8); k <= Math.min(n - 1, i + 8); k++) {
                double w = 9 - Math.abs(k - i);
                sum += raw[k] * w;
                wsum += w;
            }
            out[i] = sum / wsum;
        }
        return out;
    }

    /**
     * Lays the walking line beside the rift's centre line. The ledge offset fades out near both ends, so the path
     * starts and ends on the landings, and is kept inside the radius of every bend, so the line never folds back.
     */
    private void walk() {
        double[] off = new double[n];
        for (int i = 0; i < n; i++) off[i] = offset[i] * smooth((Math.min(ps[i], length - ps[i]) - 4) / 40.0);
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < n; i++) {
                int a = Math.max(0, i - 5), b = Math.min(n - 1, i + 5);
                if (a == i || b == i) continue;
                double ax = px[i] - px[a], az = pz[i] - pz[a], bx = px[b] - px[i], bz = pz[b] - pz[i];
                double cross = ax * bz - az * bx, dot = ax * bx + az * bz;
                double ang = Math.abs(Math.atan2(cross, dot));
                if (ang < 1e-4) continue;
                double rad = (Math.hypot(ax, az) + Math.hypot(bx, bz)) / ang;
                if (cross * off[i] > 0 && Math.abs(off[i]) > 0.55 * rad) off[i] = Math.signum(off[i]) * 0.55 * rad;
            }
            for (int k = 0; k < 4; k++) {
                double prev = off[0];
                for (int i = 1; i + 1 < n; i++) {
                    double cur = off[i];
                    off[i] = 0.25 * prev + 0.5 * cur + 0.25 * off[i + 1];
                    prev = cur;
                }
            }
        }
        // Where the line would still run backwards on a tight bend, pull it in towards the centre line.
        for (int iter = 0; iter < 40; iter++) {
            layWalk(off);
            boolean bad = false;
            for (int i = 0; i + 1 < n; i++) {
                double pdx = px[i + 1] - px[i], pdz = pz[i + 1] - pz[i];
                double wdx = wx[i + 1] - wx[i], wdz = wz[i + 1] - wz[i];
                if (wdx * pdx + wdz * pdz < 0.35 * (pdx * pdx + pdz * pdz)) {
                    bad = true;
                    for (int k = Math.max(0, i - 4); k <= Math.min(n - 1, i + 5); k++) off[k] *= 0.85;
                }
            }
            if (!bad) break;
        }
        System.arraycopy(off, 0, offset, 0, n);
        layWalk(offset);
        double acc = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0) acc += Math.hypot(wx[i] - wx[i - 1], wz[i] - wz[i - 1]);
            ws[i] = acc;
        }
        walkLength = Math.max(1, acc);
    }

    private void layWalk(double[] off) {
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
            double dx = px[b] - px[a], dz = pz[b] - pz[a], l = Math.max(1e-6, Math.hypot(dx, dz));
            wx[i] = px[i] - dz / l * off[i];
            wz[i] = pz[i] + dx / l * off[i];
        }
    }

    private int clampI(int v) { return v < 0 ? 0 : (v >= gn ? gn - 1 : v); }

    static double smooth(double t) {
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return t * t * (3 - 2 * t);
    }

    /** Continuous wall profile of the rift: how far from the centre line the wall reaches height y. */
    double riftWallAt(double y, double top, double halfTop) {
        double depth = Math.max(8, top - (W - 2));
        double hgt = Math.max(0, Math.min(1, (y - (W - 2)) / depth));
        return streamHalf + (halfTop - streamHalf) * Math.pow(hgt, 1 / 0.85);
    }

    /** Point index nearest to arc length s. */
    int indexAt(double s) {
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ps[mid] < s) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Interpolated per-point value at arc length s. */
    double at(double[] arr, double s) {
        if (s <= 0) return arr[0];
        if (s >= length) return arr[n - 1];
        int i = indexAt(s);
        if (i == 0) return arr[0];
        double a = ps[i - 1], b = ps[i];
        double t = b - a < 1e-9 ? 0 : (s - a) / (b - a);
        return arr[i - 1] + (arr[i] - arr[i - 1]) * t;
    }

    /** Value of a per-point array at a fractional point index. */
    double atIndex(double[] arr, double fi) {
        if (fi <= 0) return arr[0];
        if (fi >= n - 1) return arr[n - 1];
        int i = (int) fi;
        double t = fi - i;
        return arr[i] + (arr[i + 1] - arr[i]) * t;
    }

    /** Fractional point index at arc length s along the walking line. */
    double walkIndex(double s) {
        if (s <= 0) return 0;
        if (s >= walkLength) return n - 1;
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ws[mid] < s) lo = mid + 1; else hi = mid;
        }
        if (lo == 0) return 0;
        double a = ws[lo - 1], b = ws[lo];
        return lo - 1 + (b - a < 1e-9 ? 0 : (s - a) / (b - a));
    }

    public double floorAt(double s) { return at(floor, s); }
    public double offsetAt(double s) { return at(offset, s); }
    public double[] pointAt(double s) {
        if (s <= 0) return new double[]{px[0], pz[0]};
        if (s >= length) return new double[]{px[n - 1], pz[n - 1]};
        int i = indexAt(s);
        if (i == 0) return new double[]{px[0], pz[0]};
        double a = ps[i - 1], b = ps[i];
        double t = b - a < 1e-9 ? 0 : (s - a) / (b - a);
        return new double[]{px[i - 1] + (px[i] - px[i - 1]) * t, pz[i - 1] + (pz[i] - pz[i - 1]) * t};
    }

    /** The walking path at arc length s along it (0 to {@link #walkLength}): {x, z, floorY}. */
    public double[] pathAt(double s) {
        double fi = walkIndex(s);
        return new double[]{atIndex(wx, fi), atIndex(wz, fi), atIndex(floor, fi)};
    }

    /** Unit direction of the route at arc length s. */
    double[] dirAt(double s) {
        int i = Math.max(1, Math.min(n - 1, indexAt(s)));
        double dx = px[i] - px[i - 1], dz = pz[i] - pz[i - 1], l = Math.max(1e-6, Math.hypot(dx, dz));
        return new double[]{dx / l, dz / l};
    }

    /**
     * Nearest point on the route's centre line to (x, z), written into {@code out}: [distance, arc length, signed
     * sideways distance, fractional point index]. Returns false when the route is out of reach of the point.
     */
    boolean nearest(double x, double z, double[] out) {
        if (!nearestOn(px, pz, buckets, x, z, out)) return false;
        out[3] = out[1];
        out[1] = atIndex(ps, out[1]);
        return true;
    }

    /** Nearest point on the walking line: [distance, fractional point index]. False when it is out of reach. */
    boolean nearestWalk(double x, double z, double[] out) {
        return nearestOn(wx, wz, walkBuckets, x, z, out);
    }

    /**
     * Nearest point on a polyline: [distance, fractional index, signed side]. Where the line comes back past itself
     * (a hairpin, or a later stretch running under an earlier one) the nearest point on another stretch, at least
     * {@link #SECTION} points away along the line, goes in [4] (distance, or 1e9 for none) and [5] (its index).
     */
    private boolean nearestOn(double[] xs, double[] zs, int[][] bk, double x, double z, double[] out) {
        out[4] = 1e9;
        int bx = (int) Math.floor((x - gx0) / bucket), bz = (int) Math.floor((z - gz0) / bucket);
        if (bx < 0 || bz < 0 || bx >= gn || bz >= gn) return false;
        int[] cand = bk[bx * gn + bz];
        if (cand.length == 0) return false;
        double best = Double.MAX_VALUE, bestI = 0, bestV = 0;
        for (int i : cand) {
            double ax = xs[i], az = zs[i], dx = xs[i + 1] - ax, dz = zs[i + 1] - az;
            double len2 = dx * dx + dz * dz;
            double t = len2 < 1e-9 ? 0 : ((x - ax) * dx + (z - az) * dz) / len2;
            t = t < 0 ? 0 : (t > 1 ? 1 : t);
            double cx = ax + dx * t, cz = az + dz * t;
            double ex = x - cx, ez = z - cz;
            double d2 = ex * ex + ez * ez;
            if (d2 < best) {
                best = d2;
                bestI = i + t;
                double cross = dx * ez - dz * ex;
                bestV = cross >= 0 ? Math.sqrt(d2) : -Math.sqrt(d2);
            }
        }
        out[0] = Math.sqrt(best);
        out[1] = bestI;
        out[2] = bestV;
        double best2 = Double.MAX_VALUE, bestI2 = 0;
        for (int i : cand) {
            if (Math.abs(i - bestI) < SECTION) continue;
            double ax = xs[i], az = zs[i], dx = xs[i + 1] - ax, dz = zs[i + 1] - az;
            double len2 = dx * dx + dz * dz;
            double t = len2 < 1e-9 ? 0 : ((x - ax) * dx + (z - az) * dz) / len2;
            t = t < 0 ? 0 : (t > 1 ? 1 : t);
            double ex = x - (ax + dx * t), ez = z - (az + dz * t);
            double d2 = ex * ex + ez * ez;
            if (d2 < best2) { best2 = d2; bestI2 = i + t; }
        }
        if (best2 < Double.MAX_VALUE) {
            out[4] = Math.sqrt(best2);
            out[5] = bestI2;
        }
        return true;
    }

    /** Points apart along a line before two nearby stretches of it count as separate (about 36 blocks). */
    static final int SECTION = 12;

    // ------------------------------------------------------------------------------------------------ set pieces

    // ------------------------------------------------------------------------------------------------ Deep Gardens

    /**
     * Where the Lantern Lake's lakes sit along the route: {arc length of the middle, half length of the level stretch
     * across it, lake radius}. The last lake is the layer's own water table, by the exit landing; one or two perched
     * lakes sit on the way down, as long as the descent between them stays gentle.
     */
    private double[][] planLakeStretches(long h) {
        List<double[]> out = new ArrayList<>();
        double rEnd = Hashing.range(Hashing.mix(h, 640, 0, 0), 30, 40);
        double halfEnd = rEnd * 0.9 + 4;
        double sEnd = length - 28 - halfEnd;
        int mids = length > 560 ? 2 : 1;
        for (int k = 0; k < mids; k++) {
            long hk = Hashing.mix(h, 650 + k, 0, 0);
            double frac = mids == 1 ? 0.46 : (k == 0 ? 0.3 : 0.6);
            double s = length * frac + Hashing.range(Hashing.mix(hk, 1, 0, 0), -15, 15);
            double r = Hashing.range(Hashing.mix(hk, 2, 0, 0), 30, 42);
            out.add(new double[]{s, r * 0.95 + 4, r});
        }
        out.add(new double[]{sEnd, halfEnd, rEnd});
        // Keep at least 2.2 blocks of walking for every block of drop outside the level stretches: drop perched lakes
        // (the later one first), then shorten what is left.
        while (true) {
            double flat = 0;
            for (double[] f : out) flat += 2 * f[1];
            double descent = sEnd - halfEnd - 10 - (flat - 2 * halfEnd);
            if (descent >= 2.2 * dropEstimate || out.size() == 1) break;
            out.remove(out.size() - 2);
        }
        return out.toArray(new double[0][]);
    }

    /** Set before the floor profile: how far the route has to descend (start floor minus end floor). */
    private double dropEstimate;

    /**
     * Like {@link #floorProfile}, but level across each lake stretch: the descent is shared out between the stretches
     * in proportion to their lengths, so every ramp is as gentle as the route allows.
     */
    private void floorWithFlats(long h, double[] arcs, double total, double sFloor, double eFloor, double[][] flats, double stepAvg, double W) {
        double drop = Math.max(0, sFloor - eFloor);
        int k = flats.length;
        double[] a = new double[k], b = new double[k];
        for (int i = 0; i < k; i++) { a[i] = flats[i][0] - flats[i][1]; b[i] = flats[i][0] + flats[i][1]; }
        // descent parts: [10, a0], [b0, a1], ... [b(k-2), a(k-1)]; the last flat runs on to the exit landing
        double[] p0 = new double[k], p1 = new double[k];
        double sum = 0;
        for (int j = 0; j < k; j++) {
            p0[j] = j == 0 ? 10 : b[j - 1];
            p1[j] = Math.max(p0[j] + 1, a[j]);
            sum += p1[j] - p0[j];
        }
        double[] lvl = new double[k + 1];
        lvl[0] = sFloor;
        for (int j = 0; j < k; j++) lvl[j + 1] = lvl[j] - drop * (p1[j] - p0[j]) / Math.max(1, sum);
        lvl[k] = eFloor;
        for (int i = 0; i < n; i++) {
            double s = arcs[i];
            double f = lvl[k];
            if (s <= 10) f = sFloor;
            else {
                for (int j = 0; j < k; j++) {
                    if (s < p1[j]) { f = stepped(h, 4000 + 100 * j, s, p0[j], p1[j], lvl[j], lvl[j + 1], stepAvg); break; }
                    if (s <= b[j] || j == k - 1) { f = lvl[j + 1]; if (s <= b[j]) break; }
                }
            }
            floor[i] = Math.max(f, W + 2);
        }
        for (int i = 0; i < n; i++) {
            double rem = total - arcs[i];
            if (rem < 24) floor[i] = floor[i] + (eFloor - floor[i]) * smooth(1 - rem / 24);
        }
    }

    /** Terraced steps from level hA at arc p to level hB at arc q, the same way {@link #floorProfile} lays them. */
    private static double stepped(long h, int salt, double s, double p, double q, double hA, double hB, double stepAvg) {
        double dropAll = Math.max(0, hA - hB), span = Math.max(1, q - p);
        int steps = Math.max(2, (int) Math.round(dropAll / stepAvg));
        steps = Math.max(steps, (int) Math.ceil(1.5 * 1.3 * dropAll / (MAX_SLOPE * span * 0.8)));
        double sumW = 0;
        double[] w = new double[steps];
        for (int j = 0; j < steps; j++) { w[j] = Hashing.range(Hashing.mix(h, salt + j, 0, 0), 0.7, 1.3); sumW += w[j]; }
        double stepLen = span / steps;
        double u = (s - p) / span * steps;
        int j = Math.max(0, Math.min(steps - 1, (int) Math.floor(u)));
        double level = hA;
        for (int i = 0; i < j; i++) level -= dropAll * w[i] / sumW;
        double dj = dropAll * w[j] / sumW;
        double tread = Hashing.range(Hashing.mix(h, salt + 50 + j, 0, 0), 0.4, 0.62);
        tread = Math.max(0, Math.min(tread, 1 - 1.5 * dj / (MAX_SLOPE * stepLen)));
        double local = u - j;
        double ramp = local <= tread ? 0 : smooth((local - tread) / (1 - tread));
        return level - dj * ramp;
    }

    /**
     * The Lantern Lake: a wide, low hall over each level stretch, holding a still lake a block under the path, which
     * crosses it on a causeway. The hall sits off to one side so the path runs partly along the shore.
     */
    private void planLakeHalls(long h, double[][] lakes, double W) {
        for (int k = 0; k < lakes.length; k++) {
            long hk = Hashing.mix(h, 660 + k, 0, 0);
            double s = lakes[k][0], rxz = lakes[k][2];
            boolean last = k == lakes.length - 1;
            double level = floorAt(s);
            double lakeY = last ? W : level - 0.8;
            double depth = Hashing.range(Hashing.mix(hk, 1, 0, 0), 5, 8);
            double ry = Hashing.range(Hashing.mix(hk, 2, 0, 0), 13, 18);
            double rise = Hashing.range(Hashing.mix(hk, 3, 0, 0), 11, 14);
            double[] p = pointAt(s), d = dirAt(s);
            double offFrac = Hashing.range(Hashing.mix(hk, 4, 0, 0), 0.25, 0.5) * (Hashing.unit(Hashing.mix(hk, 5, 0, 0)) < 0.5 ? 1 : -1);
            double frac = lakeFraction(lakeY - depth, rise, lakeY);
            double hx = 0, hz = 0;
            boolean wet = true;
            if (!last) {
                // A perched lake is held in by a rim (see Terrain.mustStay). The path's walking space may only cross
                // that rim where the path is level with the lake, so shrink the hall until every lower stretch of
                // the path keeps clear of it.
                for (int it = 0; it < 6; it++) {
                    hx = p[0] - d[1] * offFrac * rxz;
                    hz = p[1] + d[0] * offFrac * rxz;
                    double minD = 1e9;
                    for (int i = 0; i < n; i++) {
                        if (floor[i] >= lakeY - 0.5) continue;
                        minD = Math.min(minD, Math.hypot(wx[i] - hx, wz[i] - hz));
                    }
                    double maxR = (minD - 8.5) / Math.max(0.05, frac);
                    if (rxz <= maxR) break;
                    rxz = maxR;
                }
                if (rxz < 18) { wet = false; rxz = Math.max(18, rxz); }
            }
            hx = p[0] - d[1] * offFrac * rxz;
            hz = p[1] + d[0] * offFrac * rxz;
            Hall hall = wet
                    ? new Hall(hx, hz, lakeY + ry * 0.45, rxz, ry, lakeY - depth, rise, lakeY, frac * rxz)
                    : new Hall(hx, hz, level - 3 + ry * 0.7, rxz, ry, level - 4, 9, Double.NaN, 0);
            halls.add(hall);
            if (!wet) continue;
            // The causeway: under the path the slab reaches down to the lake bed.
            for (int i = 0; i < n; i++) {
                double dxz = Math.hypot(wx[i] - hall.x(), wz[i] - hall.z());
                if (dxz < rxz + 2) slab[i] = Math.max(slab[i], floor[i] - (hall.floorY() - 2));
            }
        }
        // A dry hall halfway down each descent between lakes.
        double prevEnd = 10;
        for (int k = 0; k < lakes.length; k++) {
            double a = lakes[k][0] - lakes[k][1];
            if (a - prevEnd > 90) {
                long hk = Hashing.mix(h, 680 + k, 0, 0);
                double s = (prevEnd + a) * 0.5 + Hashing.range(Hashing.mix(hk, 1, 0, 0), -10, 10);
                double rxz = Hashing.range(Hashing.mix(hk, 2, 0, 0), 18, 26);
                double ry = Hashing.range(Hashing.mix(hk, 3, 0, 0), 14, 22);
                double[] p = pointAt(s), d = dirAt(s);
                double off = Hashing.range(Hashing.mix(hk, 4, 0, 0), 0.15, 0.35) * rxz * (k % 2 == 0 ? 1 : -1);
                double f = floorAt(s);
                halls.add(new Hall(p[0] - d[1] * off, p[1] + d[0] * off, f - 3 + ry * 0.7, rxz, ry, f - 4, 9, Double.NaN, 0));
            }
            prevEnd = lakes[k][0] + lakes[k][1];
        }
    }

    /** Rust Springs refuges: a cavern beside the path, reached by its own short tunnel, its floor a staircase of pools. */
    private void planSprings(long h, Terrain terrain, double W) {
        int count = length > 400 ? 2 : 1;
        double[] fracs = count == 1 ? new double[]{0.62} : new double[]{0.24, 0.74};
        for (int c = 0; c < count; c++) {
            long hc = Hashing.mix(h, 950 + c, 0, 0);
            double sg = length * fracs[c] + Hashing.range(Hashing.mix(hc, 1, 0, 0), -15, 15);
            // not under a lake hall: slide forward off any level stretch
            for (Hall hl : halls) {
                if (!hl.lake()) continue;
                double[] q = pointAt(sg);
                if (Math.hypot(q[0] - hl.x(), q[1] - hl.z()) < hl.rxz() + 30) sg = Math.min(length - 60, sg + hl.rxz() + 40);
            }
            double[] p = pointAt(sg), d = dirAt(sg);
            double f = floorAt(sg);
            int sd = (c % 2 == 0 ? side : -side);
            double rxz = Hashing.range(Hashing.mix(hc, 2, 0, 0), 22, 30);
            double ry = Hashing.range(Hashing.mix(hc, 3, 0, 0), 14, 19);
            double drop = Hashing.range(Hashing.mix(hc, 4, 0, 0), 10, 15);
            double v = sd * (at(half, sg) + 12 + rxz);
            double nx = -d[1] * sd, nz = d[0] * sd;
            double cx = p[0] + nx * Math.abs(v), cz = p[1] + nz * Math.abs(v);
            double cs = terrain.plans.cellSize, mg = Plans.MARGIN - 20 + rxz;
            cx = Math.max(cellX * cs + mg, Math.min((cellX + 1) * cs - mg, cx));
            cz = Math.max(cellZ * cs + mg, Math.min((cellZ + 1) * cs - mg, cz));
            double dx = cx - p[0], dz = cz - p[1], dl = Math.max(1e-6, Math.hypot(dx, dz));
            double dirX = dx / dl, dirZ = dz / dl;
            double floorY = Math.max(W + 2 + drop * 0.25, f - drop * 0.5);
            Spring sp = new Spring(cx, floorY + ry * 0.55, cz, rxz, ry, floorY, dirX, dirZ, drop);
            springs.add(sp);
            int wi = indexAt(sg);
            double ex = cx - dirX * rxz * 0.72, ez = cz - dirZ * rxz * 0.72;
            double ey = sp.rampFloor(ex, ez);
            links.add(new Link(wx[wi], f + 2.5, wz[wi], ex, ey + 2.5, ez, 3.2));
        }
    }

    // ------------------------------------------------------------------------------------------------ Basalt Galleries

    /** Obsidian Tubes refuges: a few smooth round tubes beside the path, branching from one reached by a short tunnel. */
    private void planTubes(long h, Terrain terrain, double W) {
        int count = length > 400 ? 2 : 1;
        double[] fracs = count == 1 ? new double[]{0.55} : new double[]{0.3, 0.7};
        int top = terrain.shape.top(layer);
        double cs = terrain.plans.cellSize;
        for (int c = 0; c < count; c++) {
            long hc = Hashing.mix(h, 970 + c, 0, 0);
            double sg = length * fracs[c] + Hashing.range(Hashing.mix(hc, 1, 0, 0), -15, 15);
            double[] p = pointAt(sg), d = dirAt(sg);
            double f = floorAt(sg);
            int sd = (c % 2 == 0 ? side : -side);
            double nx = -d[1] * sd, nz = d[0] * sd;
            double r0 = Hashing.range(Hashing.mix(hc, 2, 0, 0), 5.0, 6.5);
            double ang = Math.atan2(nz, nx) + Hashing.range(Hashing.mix(hc, 3, 0, 0), -0.4, 0.4);
            double len = Hashing.range(Hashing.mix(hc, 4, 0, 0), 40, 60);
            double ax = p[0] + nx * (at(half, sg) + 10), az = p[1] + nz * (at(half, sg) + 10);
            // Refuges stay above the highest a lava surge reaches.
            double ay = Math.max(W + Terrain.SURGE_RISE + 4 + r0 * 0.55, f + r0 * 0.55);
            double bx = ax + Math.cos(ang) * len, bz = az + Math.sin(ang) * len;
            double by = clampY(ay + Hashing.range(Hashing.mix(hc, 5, 0, 0), -4, 5), r0, W, top);
            double[] A = clampXZ(ax, az, r0, cs), B = clampXZ(bx, bz, r0, cs);
            Tube main = new Tube(A[0], ay, A[1], B[0], by, B[1], r0);
            tubes.add(main);
            int branches = 2 + (int) (Hashing.unit(Hashing.mix(hc, 6, 0, 0)) * 2);
            for (int e = 0; e < branches; e++) {
                long he = Hashing.mix(hc, 20 + e, 0, 0);
                double t = Hashing.range(Hashing.mix(he, 1, 0, 0), 0.35, 1.0);
                double sx = main.x1() + (main.x2() - main.x1()) * t, sy = main.y1() + (main.y2() - main.y1()) * t, sz = main.z1() + (main.z2() - main.z1()) * t;
                double turn = Hashing.range(Hashing.mix(he, 2, 0, 0), 0.6, 1.4) * (e % 2 == 0 ? 1 : -1);
                double bl = Hashing.range(Hashing.mix(he, 3, 0, 0), 22, 40);
                double r = Hashing.range(Hashing.mix(he, 4, 0, 0), 4.0, 5.5);
                double ex = sx + Math.cos(ang + turn) * bl, ez = sz + Math.sin(ang + turn) * bl;
                double ey = clampY(sy + Hashing.range(Hashing.mix(he, 5, 0, 0), -6, 6), r, W, top);
                double[] E = clampXZ(ex, ez, r, cs);
                tubes.add(new Tube(sx, sy, sz, E[0], ey, E[1], r));
            }
            int wi = indexAt(sg);
            links.add(new Link(wx[wi], f + 2.5, wz[wi], main.x1(), ay - r0 * 0.55 + 2.5, main.z1(), 3.2));
        }
    }

    private double[] clampXZ(double x, double z, double r, double cs) {
        double mg = Plans.MARGIN - 20 + r;
        return new double[]{Math.max(cellX * cs + mg, Math.min((cellX + 1) * cs - mg, x)), Math.max(cellZ * cs + mg, Math.min((cellZ + 1) * cs - mg, z))};
    }

    private static double clampY(double y, double r, double W, int top) {
        return Math.max(W + Terrain.SURGE_RISE + 4 + r * 0.55, Math.min(top - r - 10, y));
    }

    // ------------------------------------------------------------------------------------------------ Blue Reaches

    private void planRift(long h, Terrain terrain, double W) {
        // The bridges the path crosses on, where it changes sides; the path's own slab is their deck.
        for (double sc : crossings) {
            int i = indexAt(sc);
            bridges.add(new Bridge(sc, floor[i], Hashing.range(Hashing.mix(h, 790, (int) sc, 0), 4.5, 6.5), riftHalf[i] + 4));
        }
        // More natural bridges across the rift, out of the path's way.
        double s = Hashing.range(Hashing.mix(h, 800, 0, 0), 40, 70);
        int k = 0;
        while (s < length - 40) {
            long hk = Hashing.mix(h, 810 + k, 0, 0);
            double rt = at(riftTop, s);
            double lo = W + 18, hi = rt - 16;
            boolean clear = true;
            for (double sc : crossings) if (Math.abs(sc - s) < 45) clear = false;
            if (hi > lo && clear) {
                double y = Hashing.range(Hashing.mix(hk, 1, 0, 0), lo, hi);
                bridges.add(new Bridge(s, y, Hashing.range(Hashing.mix(hk, 2, 0, 0), 2.5, 4.0), at(riftHalf, s) + 4));
            }
            s += Hashing.range(Hashing.mix(hk, 3, 0, 0), 70, 120);
            k++;
        }
        // Refuge geode clusters beside the path, behind the rift wall the path hugs.
        double[] fracs = {0.3, 0.68};
        for (int c = 0; c < fracs.length; c++) {
            long hc = Hashing.mix(h, 900 + c, 0, 0);
            double sg = length * fracs[c] + Hashing.range(Hashing.mix(hc, 1, 0, 0), -15, 15);
            double[] p = pointAt(sg), d = dirAt(sg);
            double f = floorAt(sg);
            double rh = at(riftHalf, sg);
            double v = (at(sideAt, sg) >= 0 ? 1 : -1) * (rh + Hashing.range(Hashing.mix(hc, 2, 0, 0), 20, 30));
            double r0 = Hashing.range(Hashing.mix(hc, 3, 0, 0), 17, 24);
            double gx = p[0] - d[1] * v, gz = p[1] + d[0] * v;
            double cs = terrain.plans.cellSize, mg = Plans.MARGIN - 20 + r0;
            gx = Math.max(cellX * cs + mg, Math.min((cellX + 1) * cs - mg, gx));
            gz = Math.max(cellZ * cs + mg, Math.min((cellZ + 1) * cs - mg, gz));
            double gy = Math.max(W + r0 * 0.55 + 6, f + r0 * 0.45 - 2);
            int before = geodes.size();
            geodes.add(new Geode(gx, gy, gz, r0));
            int extra = 2 + (int) (Hashing.unit(Hashing.mix(hc, 4, 0, 0)) * 3);
            double ang = Hashing.range(Hashing.mix(hc, 5, 0, 0), 0, Math.PI * 2);
            for (int e = 0; e < extra; e++) {
                long he = Hashing.mix(hc, 10 + e, 0, 0);
                Geode base = geodes.get(before + (int) (Hashing.unit(Hashing.mix(he, 1, 0, 0)) * (geodes.size() - before)));
                double r = Hashing.range(Hashing.mix(he, 2, 0, 0), 12, 20);
                ang += Hashing.range(Hashing.mix(he, 3, 0, 0), 1.2, 2.6);
                double dist = (base.r() + r) * Hashing.range(Hashing.mix(he, 4, 0, 0), 0.62, 0.8);
                double ex = base.x() + Math.cos(ang) * dist, ez = base.z() + Math.sin(ang) * dist;
                double ey = base.y() + Hashing.range(Hashing.mix(he, 5, 0, 0), -8, 10);
                ey = Math.max(W + r * 0.55 + 4, Math.min(terrain.shape.top(layer) - r - 10, ey));
                double lo = cellX * (double) terrain.plans.cellSize + Plans.MARGIN - 20 + r, hi = (cellX + 1) * (double) terrain.plans.cellSize - Plans.MARGIN + 20 - r;
                double loz = cellZ * (double) terrain.plans.cellSize + Plans.MARGIN - 20 + r, hiz = (cellZ + 1) * (double) terrain.plans.cellSize - Plans.MARGIN + 20 - r;
                ex = Math.max(lo, Math.min(hi, ex));
                ez = Math.max(loz, Math.min(hiz, ez));
                geodes.add(new Geode(ex, ey, ez, r));
            }
            // The way in: a short tunnel from the path through the rift wall to the first geode's floor.
            double pvx = wx[indexAt(sg)], pvz = wz[indexAt(sg)];
            Geode g0 = geodes.get(before);
            links.add(new Link(pvx, f + 2.5, pvz, g0.x(), g0.y() - g0.r() * 0.45 + 2.5, g0.z(), 3.2));
        }
    }
}
