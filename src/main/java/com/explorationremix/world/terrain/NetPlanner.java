package com.explorationremix.world.terrain;

import com.explorationremix.world.terrain.Net.Arch;
import com.explorationremix.world.terrain.Net.Chamber;
import com.explorationremix.world.terrain.Net.EK;
import com.explorationremix.world.terrain.Net.Edge;
import com.explorationremix.world.terrain.Net.Kind;
import com.explorationremix.world.terrain.Net.Shaft;
import com.explorationremix.world.terrain.Net.Way;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Plans one layer's network in one cell: landings, portals, set pieces, refuges, ordinary chambers, then a graph built
 * by Kruskal over candidate edges where an edge only counts once its tunnel embeds without hitting anything already
 * built; then loops, extra ways to the gates, blind passages, the trunk and the inner walks (network-final.md, 2.4).
 * A plan reads only pure functions of the seed (gates, portals, city sites, 0.2.0's deep dark), never another plan.
 */
final class NetPlanner {
    /** Every planned space stays this far plus its own half-width inside the cell (portal stubs excepted). */
    static final double EDGE_MARGIN = 24;

    private NetPlanner() {}

    static double polyLen(List<double[]> p) { return P.polyLen(p); }

    static List<double[]> catmull(List<double[]> way, double step) { return P.catmull(way, step); }

    /** Distance in x and z from (x, z) to the segment (x1, z1)-(x2, z2). */
    static double segDistXZ(double x, double z, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1, dz = z2 - z1, len2 = dx * dx + dz * dz;
        double t = len2 < 1e-9 ? 0 : ((x - x1) * dx + (z - z1) * dz) / len2;
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        return Math.hypot(x - (x1 + dx * t), z - (z1 + dz * t));
    }

    /** Lava pools on (the default); off only for the voxel test that compares the walk graph without them. */
    static boolean POOLS = true;

    static Net plan(Network w, int li, int cx, int cz) {
        long t0 = System.nanoTime();
        Net n = new Net(li, cx, cz);
        new P(w, n).run();
        n.planMs = (System.nanoTime() - t0) / 1e6;
        return n;
    }

    /** Layer sizes. Blue keeps 0.2.0's sizes; the Deep Gardens and the Basalt Galleries follow Taran's 15-30 / 8-20. */
    static final class Spec {
        double half, halfVar, hgt, hgtVar, maxSlope = 0.5;
        int fill;
        double gapH, gapV;
        double yLo, yHi;           // floor range of ordinary chambers, relative to the water table
        double climbMax = 24;
        double loopP = 0.55, stubP = 0.22;
        double spacing;            // strand chambers, centre to centre along the strand
        double tierBase, tierGap;  // storeys: floors cluster at W + tierBase + k * tierGap
        double tierShare = 0.8;
        double rampP = 0, rampMax = 0;   // share of ordinary chambers whose floor steps down across them, and the drop (x diameter)
        int candK = 9;
        double candMax = 260, climbL = 60;
        // rock left between spaces: way to way (horizontal wall to wall, vertical roof to slab), way to chamber
        double clearH = 3, clearV = 2, clearC = 5, clearCV = 3;
        double loopShare = 0.22, tierJit = 4;
    }

    /**
     * The final tuning (network-final.md, sections 2.4 and 3). The Deep Gardens and the Basalt Galleries share the
     * chosen density: 160 chambers per 600-block cell, 12 blocks of rock between chambers (10 vertically), 6 between
     * tunnels (4 vertically), storeys 46 apart. The Basalt Galleries keep every floor above the highest surge.
     */
    static Spec spec(int li, Shape s, double cell) {
        Spec sp = new Spec();
        double W = s.waterY(li), T = s.top(li);
        switch (li) {
            case Terrain.GARDENS, Terrain.BASALT -> {
                boolean basalt = li == Terrain.BASALT;
                sp.half = basalt ? 3.2 : 3.0; sp.halfVar = basalt ? 0.22 : 0.25;
                sp.hgt = basalt ? 8.0 : 7.5; sp.hgtVar = 0.18;
                sp.maxSlope = 0.62;
                sp.fill = 160; sp.gapH = 12; sp.gapV = 10;
                sp.yLo = basalt ? Terrain.SURGE_RISE + 2 : -3; sp.yHi = (T - W) - 30;
                sp.climbMax = 50; sp.climbL = 46;
                sp.spacing = basalt ? 48 : 44;
                sp.tierBase = basalt ? Terrain.SURGE_RISE + 6 : 3; sp.tierGap = 46;
                sp.rampP = basalt ? 0.4 : 0.45; sp.rampMax = basalt ? 0.4 : 0.42;
                sp.candK = 7; sp.candMax = 170;
                sp.clearH = 6; sp.clearV = 4; sp.clearC = 7; sp.clearCV = 4;
                sp.loopShare = 0.15;
            }
            default -> { // Blue Reaches: the 0.2.0 corridor size
                sp.half = 4.8; sp.halfVar = 0.17; sp.hgt = 9.5; sp.hgtVar = 0.16;
                sp.maxSlope = 0.58;
                sp.fill = 40; sp.gapH = 14; sp.gapV = 10;
                sp.yLo = 6; sp.yHi = (T - W) - 34;
                sp.climbMax = 32; sp.climbL = 50;
                sp.spacing = 78; sp.tierBase = 10; sp.tierGap = 44;
                sp.rampP = 0.3; sp.rampMax = 0.35;
                sp.candK = 8; sp.candMax = 200;
            }
        }
        // the chamber count follows the cell's area (section 2.6)
        sp.fill = (int) Math.round(sp.fill * (cell / 600.0) * (cell / 600.0));
        return sp;
    }

    // =====================================================================================================
    private static final class P {
        final Network w;
        final Net n;
        final int li, cx, cz;
        final long h;
        final double W, T, B, x0, z0, cell;
        final Spec sp;
        final List<double[]> doorAngles = new ArrayList<>(); // per chamber: used door angles (radians)
        // collision index for way points and shafts: 12-block buckets
        static final int CB = 12;
        final int cn;
        final List<int[]>[] pts;   // {wayId, pointIndex}
        int[] uf = new int[0];     // union-find over chambers (grown as chambers are added)

        @SuppressWarnings("unchecked")
        P(Network w, Net n) {
            this.w = w;
            this.n = n;
            this.li = n.li;
            this.cx = n.cx;
            this.cz = n.cz;
            this.h = Hashing.mix(w.seed, 7100 + li, cx, cz);
            Shape s = w.shape;
            cell = w.cell;
            W = s.waterY(li);
            T = s.top(li);
            B = s.bottom(li);
            x0 = cx * cell;
            z0 = cz * cell;
            sp = spec(li, s, cell);
            cn = (int) Math.ceil((cell + 2 * Net.APRON) / CB);
            pts = new List[cn * cn];
            for (int i = 0; i < pts.length; i++) pts[i] = new ArrayList<>();
        }

        double u(long salt) { return Hashing.unit(Hashing.mix(h, salt, 0, 0)); }
        double r(long salt, double a, double b) { return a + (b - a) * u(salt); }

        int find(int a) { while (uf[a] != a) { uf[a] = uf[uf[a]]; a = uf[a]; } return a; }
        void union(int a, int b) { uf[find(a)] = find(b); }

        Chamber add(Chamber c) {
            n.add(c);
            if (c.id >= uf.length) {
                int old = uf.length;
                uf = Arrays.copyOf(uf, Math.max(256, old * 2));
                for (int i = old; i < uf.length; i++) uf[i] = i;
            }
            doorAngles.add(new double[0]);
            if (c.hash == 0) c.hash = Hashing.mix(h, 31, c.id, 0);
            return c;
        }

        // ------------------------------------------------------------------------------------------- run
        void run() {
            landings();
            if (li == Terrain.BASALT) lavaBasin();
            portals();
            tiers();
            if (li == Terrain.BLUE) city();
            courts();
            slots();
            anchors();
            strandMode = true;
            meander();
            // Blue Reaches: the rift first; its geodes fade the deep dark, whose regions then become nodes
            for (Strand st : strands) if (st.canyon) buildStrand(st);
            if (li == Terrain.BLUE) { darkPlan(); attachPortalsToDark(); }
            for (Strand st : strands) if (!st.canyon) buildStrand(st);
            strandMode = false;
            offStrandSetPieces();
            refuges();
            fill();
            List<Edge> cand = candidates();
            kruskal(cand);
            connectRequired(cand);
            dropOrphans();
            portalFallback();
            if (n.dark != null) closeUnjoined();
            loops(cand);
            gateWays();
            walkWays();
            surgeNiches();
            stubs();
            tubeStubs();
            trunk();
            biomes();
            innerWalks();
            arches();
            lavaPools();
            if (n.dark != null) {
                long s0 = System.nanoTime();
                sightBreak();
                n.sightMs = (System.nanoTime() - s0) / 1e6;
            }
            for (Strand st : strands) { n.strands.add(st.nodes); n.strandLines.add(st.line); }
            if (lava != null) {
                // added last, so no stage gives it a door or an edge and no other id moves
                lava.biome = w.biomeOf(li, lava);
                add(lava);
            }
            NetIndex.build(w, n);
        }

        // ------------------------------------------------------------------------------------------- the lava basin
        /** The lava basin behind the bedrock landing (section 11.3); an obstacle for every later stage. */
        Chamber lava;

        void lavaBasin() {
            Chamber E = n.ch.get(n.entry), L = n.ch.get(n.exit);
            double a = Math.atan2(L.z - E.z, L.x - E.x); // straight on, away from the entry
            Chamber c = chamber(Kind.LAVA, 9470);
            c.rx = r(9471, 18, 24); c.rz = r(9472, 14, 20); c.setRot(a); c.warp = 0.12;
            double d = c.rx + 15;
            c.x = L.x + Math.cos(a) * d; c.z = L.z + Math.sin(a) * d;
            c.f0 = Math.max(W - 5, B + Terrain.SEAL + 1);
            c.rise = Math.max(0, W - LAVA_RIM - c.f0);
            c.h = W + r(9473, 14, 20) - c.f0;
            c.pend = 3; c.set = true; c.required = false;
            lava = c;
        }

        /** The basin's floor at its wall, below the lava table (blocks): the lava reaches the walls all round. */
        static final double LAVA_RIM = 2.5;
        /** Courts at the bedrock landing: two, so the basin behind and the surge niches at the sides fit. */
        static final int LAVA_COURTS = 2;

        /** True when a space (centre, reach, heights) would come within the gaps of the lava basin. */
        boolean lavaHit(double x, double z, double R, double yLo, double yHi, double gapH, double gapV) {
            if (lava == null) return false;
            if (yHi + gapV < lava.yLo() || lava.yHi() + gapV < yLo) return false;
            if (Math.hypot(x - lava.x, z - lava.z) > R + lava.R() + gapH) return false;
            // by its footprint, not its bounding circle: the space's centre must lie (R + gap) beyond the basin's wall
            return w.e(lava, x, z) < 1 + (R + gapH) / Math.min(lava.rx, lava.rz);
        }

        // ------------------------------------------------------------------------------------------- nodes
        void landings() {
            Plans.Gate gin = w.base.plans.gate(li + 1, cx, cz);
            Chamber e = new Chamber();
            e.kind = Kind.ENTRY;
            e.x = gin.lx; e.z = gin.lz; e.f0 = gin.lFloor; e.h = gin.landHalf * 1.6;
            e.rx = e.rz = gin.landR; e.warp = 0; e.required = true;
            add(e);
            n.entry = e.id;
            Chamber x = new Chamber();
            if (li < w.shape.layerCount - 1) {
                Plans.Gate gout = w.base.plans.gate(li + 2, cx, cz);
                x.kind = Kind.EXIT;
                x.x = gout.ux; x.z = gout.uz; x.f0 = gout.uFloor; x.h = gout.landHalf * 1.6;
                x.rx = x.rz = gout.landR;
            } else {
                // The bedrock landing: far from the entry, on the lava shore (as in 0.2.0).
                double best = -1;
                for (int i = 0; i < 16; i++) {
                    double px = x0 + 110 + u(40 + i) * (cell - 220), pz = z0 + 110 + u(60 + i) * (cell - 220);
                    double d = Math.hypot(px - e.x, pz - e.z);
                    double score = d >= 250 ? 1000 - Math.abs(d - 300) : d;
                    if (score > best) { best = score; x.x = px; x.z = pz; }
                }
                x.kind = Kind.BEDROCK;
                x.f0 = W + 2; x.rx = x.rz = 19; x.h = 20;
            }
            x.warp = 0; x.required = true;
            add(x);
            n.exit = x.id;
        }

        final java.util.Map<Integer, Network.Portal> portalOf = new java.util.HashMap<>();

        /** The portal vestibules of this cell: one per portal on each of the four edges. */
        void portals() {
            for (Network.Portal p : w.portalsOfCell(li, cx, cz)) {
                Chamber c = new Chamber();
                c.kind = Kind.PORTAL;
                boolean mine = p.cellA(cx, cz);
                c.x = mine ? p.ax : p.bx;
                c.z = mine ? p.az : p.bz;
                c.f0 = p.floor;
                c.rx = c.rz = p.vestR;
                c.h = p.vestH;
                c.rise = 2;
                c.terr = 1;
                c.warp = 0.12;
                c.required = true;
                c.hash = Hashing.mix(w.seed, 7300 + li, (long) Math.floor(c.x), (long) Math.floor(c.z));
                add(c);
                n.portals.add(c.id);
                portalOf.put(c.id, p);
                Way stub = Network.copy(p.stub);
                // the stub runs from the first cell's vestibule to the second's: this vestibule is its start or its end
                // (with both cells taking the start, the second cell's inner walk ran from the other vestibule's door,
                // across the seam, and carved a walk and its slab through the rock over the stub)
                if (mine) stub.a = c.id;
                else stub.b = c.id;
                addWay(stub);
                // the stub's door is taken: the side facing the edge
                double th = Math.atan2(p.edgeZ(mine) - c.z, p.edgeX(mine) - c.x);
                useDoor(c.id, th);
            }
        }

        // ------------------------------------------------------------------------------------------- storeys
        double[] tierY = new double[0];
        double tiltX, tiltZ;

        /** Storeys: most ordinary chambers have their floors near one of a few levels, tilted a little per cell. */
        void tiers() {
            double base = W + sp.tierBase + r(80, -4, 4);
            double top = T - Terrain.SEAL - 30;
            // a layer under 112 blocks (config) gets a single storey
            int k = T - B + 1 < 112 ? 1 : Math.max(1, (int) Math.floor((top - base) / sp.tierGap) + 1);
            tierY = new double[k];
            for (int i = 0; i < k; i++) tierY[i] = base + i * sp.tierGap;
            tiltX = r(81, -0.02, 0.02);
            tiltZ = r(82, -0.02, 0.02);
        }

        double tierAt(int i, double x, double z) { return tierY[i] + tiltX * (x - x0 - cell / 2) + tiltZ * (z - z0 - cell / 2); }

        double snapTier(double f, double x, double z, double tol) {
            double best = f, bd = tol;
            for (int i = 0; i < tierY.length; i++) {
                double t = tierAt(i, x, z), d = Math.abs(t - f);
                if (d < bd) { bd = d; best = t; }
            }
            return best;
        }

        // ------------------------------------------------------------------------------------------- the city
        Chamber city;

        /**
         * Blue Reaches: the ancient city's cavern, at vanilla's own start position, at the cell's first eligible site
         * (section 11.1: 150 inside the cell, 140 from both landings; cells under 400 have none), right after the
         * landings, portals and storeys. Its reserve (radius 128, W + 8 to W + 80) then keeps every later chamber,
         * tunnel and climb out but its own door tunnels ({@link #reserveHit}).
         */
        void city() {
            for (double[] cs : w.eligibleCities(cx, cz)) {
                Chamber c = chamber(Kind.CITY, 790);
                c.x = cs[0]; c.z = cs[1]; c.rx = 62; c.rz = 56; c.setRot(r(791, 0, 3.14)); c.warp = 0.12;
                c.f0 = W + 20; c.h = 46; c.rise = 3; c.hunting = true; c.set = true;
                if (fits(c)) { add(c); city = c; return; }
            }
        }

        /**
         * True when a space (centre, reach, heights) enters the kept city's reserve: within {@link Network#CITY_RESERVE}
         * of its centre, between W + 8 and W + 80.
         */
        boolean reserveHit(double x, double z, double R, double yLo, double yHi) {
            if (city == null || !city.keep) return false;
            if (yHi < W + Network.CITY_RESERVE_LO || yLo > W + Network.CITY_RESERVE_HI) return false;
            return Math.hypot(x - city.x, z - city.z) < Network.CITY_RESERVE + R;
        }

        // ------------------------------------------------------------------------------------------- courts
        /** Two or three court chambers round each landing, each with its own short spoke: the ways to the gate. */
        void courts() {
            strandMode = true;
            courtsFor(n.entry, n.exit, true, n.courtsE);
            courtsFor(n.exit, n.entry, false, n.courtsX);
            strandMode = false;
        }

        void courtsFor(int L, int other, boolean entry, List<Integer> out) {
            Chamber c = n.ch.get(L), o = n.ch.get(other);
            int k = u(990 + L) < 0.62 ? 3 : 2;
            // with the lava basin behind the bedrock landing, two courts in front leave the sides to the surge niches
            // (three courts, the niches and the basin do not fit round one landing)
            if (lava != null && !entry) k = Math.min(k, LAVA_COURTS);
            double axis = Math.atan2(o.z - c.z, o.x - c.x);
            double fan = Math.toRadians(r(992 + L, 64, 84));
            double[] base = k == 3 ? new double[]{-fan, 0, fan} : new double[]{-fan * 0.62, fan * 0.62};
            for (int i = 0; i < k; i++) {
                long s = 1000 + L * 50L + i * 10L;
                // the Deep Gardens exit's middle court is the Lantern Lake hall, with the exit landing on its shore: 12
                // tries at its full size, 12 more at 36 to 44 across, then a plain court
                boolean special = !entry && i == k / 2 && li == Terrain.GARDENS;
                for (int t = 0; t < (special ? 30 : 18); t++) {
                    int ta = special && t >= 12 ? t - 12 : t;
                    double a = axis + base[i] + (ta == 0 ? 0 : ((ta & 1) == 0 ? 1 : -1) * Math.toRadians(7.0 * ((ta + 1) / 2)));
                    Chamber q = special && t < 24 ? lakeCourt(c, a, s, t >= 12) : courtChamber(c, a, entry, s + ta);
                    if (!fits(q)) continue;
                    add(q);
                    Edge e = edge(L, q.id);
                    noClimb = true;
                    widthMul = 1.2;
                    boolean ok = embed(e, false) || embed(e, true);
                    noClimb = false;
                    widthMul = 1;
                    if (ok) { e.gate = true; union(L, q.id); out.add(q.id); break; }
                    q.keep = false;
                }
            }
        }

        Edge edge(int a, int b) {
            Edge e = new Edge();
            e.a = Math.min(a, b); e.b = Math.max(a, b);
            Chamber A = n.ch.get(a), B = n.ch.get(b);
            e.len = Math.max(6, gapXZ(A, B, 0.8));
            e.dy = Math.abs(A.f0 - B.f0);
            e.cost = e.len + 2.2 * e.dy;
            return e;
        }

        Chamber courtChamber(Chamber L, double a, boolean entry, long s) {
            Chamber q = chamber(Kind.GROTTO, s);
            q.court = true; q.required = true;
            double dia = li == Terrain.BLUE ? r(s + 1, 20, 28) : r(s + 1, 16, 24);
            q.rx = dia / 2; q.rz = q.rx * r(s + 2, 0.75, 1.0); q.setRot(a);
            q.h = li == Terrain.BLUE ? r(s + 3, 12, 18) : r(s + 3, 9, 14);
            q.rise = 2; q.terr = 1; q.pend = 2; q.sq = 2.2;
            double d = L.R() + q.R() + r(s + 4, 7, 16);
            q.x = L.x + Math.cos(a) * d; q.z = L.z + Math.sin(a) * d;
            q.f0 = entry ? L.f0 - r(s + 5, 6, 12) : Math.max(W + 2, L.f0 + r(s + 5, 2, 6));
            // under the band: the seal and a margin stay rock above the court's ceiling
            q.h = Math.max(7.5, Math.min(q.h, T - Terrain.SEAL - 6.5 - q.f0 - q.rise - 2));
            if (li == Terrain.BASALT && !entry && q.f0 + 2 < W + Terrain.SURGE_RISE + 3) {
                // surge stair: the court by the bedrock landing climbs away from it in 1-block terraces to above the
                // highest surge, so its other doors (and the network) start where a surge cannot reach
                double sdia = r(s + 6, 26, 30);
                q.rx = sdia / 2; q.rz = q.rx * r(s + 2, 0.85, 1.0);
                q.rampDrop = W + Terrain.SURGE_RISE + 3 - q.f0;
                q.rampX = -Math.cos(a); q.rampZ = -Math.sin(a);
                q.h = Math.max(q.h, 11);
                d = L.R() + q.R() + r(s + 4, 7, 12);
                q.x = L.x + Math.cos(a) * d; q.z = L.z + Math.sin(a) * d;
            }
            q.low = li == Terrain.BASALT && q.f0 < W + Terrain.SURGE_RISE;
            return q;
        }

        /**
         * The Deep Gardens exit's middle court: the Lantern Lake hall on the water table, the exit landing on its shore;
         * 46 to 58 across, or 36 to 44 when the full size found no room.
         */
        Chamber lakeCourt(Chamber L, double a, long s, boolean smaller) {
            Chamber q = chamber(Kind.LAKE, 202);
            q.rx = smaller ? r(209, 18, 22) : r(203, 23, 29); q.rz = q.rx * r(204, 0.8, 1.0); q.setRot(r(205, 0, 3.14));
            q.lakeY = W; q.f0 = W - r(206, 5, 8); q.h = r(207, 18, 24) + (W - q.f0); q.rise = r(208, 4, 6);
            q.lakeE = 0.62; q.set = true; q.pend = 3;
            q.court = true; q.required = true;
            double d = L.R() + q.R() + r(s + 4, 5, 10);
            q.x = L.x + Math.cos(a) * d; q.z = L.z + Math.sin(a) * d;
            return q;
        }

        /** Floor height a way walks at through a chamber. */
        double walkF(Chamber c) {
            if (c.kind == Kind.LAKE) return c.lakeY + 1.5;
            return c.f0 + 1;
        }

        // ------------------------------------------------------------------------------------------- strands
        final class Strand {
            int s, a, b;
            double off;
            boolean walkOnly, dark, canyon;
            final List<Chamber> anchors = new ArrayList<>();
            final List<Double> anchorT = new ArrayList<>();
            List<double[]> line = new ArrayList<>();
            final List<Integer> nodes = new ArrayList<>();
            int climbs;
            double[] pitchS = new double[0], pitchD = new double[0];
            double segF0, segF1, segS0, segS1;

            /** Floor of the way at arc length s inside the current stretch: an even descent plus the pitches in it. */
            double prof(double s) {
                double drop = segF0 - segF1, pit = 0, before = 0;
                for (int i = 0; i < pitchS.length; i++) {
                    if (pitchS[i] <= segS0 || pitchS[i] >= segS1) continue;
                    pit += pitchD[i];
                    if (pitchS[i] < s) before += pitchD[i];
                }
                double scale = pit > 0.6 * Math.max(0, drop) ? 0.6 * Math.max(0, drop) / pit : 1;
                double lin = drop - pit * scale;
                double t = (s - segS0) / Math.max(1, segS1 - segS0);
                t = t < 0 ? 0 : (t > 1 ? 1 : t);
                return segF0 - lin * t - before * scale;
            }
        }

        final List<Strand> strands = new ArrayList<>();
        double axUx, axUz, axNx, axNz, axLen;
        boolean noClimb, preferClimb, strandMode;
        double widthMul = 1;

        /** Pairs entry courts with exit courts by side and gives each strand a lateral slot. */
        void slots() {
            Chamber E = n.ch.get(n.entry), X = n.ch.get(n.exit);
            axLen = Math.max(1, Math.hypot(X.x - E.x, X.z - E.z));
            axUx = (X.x - E.x) / axLen; axUz = (X.z - E.z) / axLen;
            axNx = -axUz; axNz = axUx;
            List<Integer> eC = new ArrayList<>(n.courtsE), xC = new ArrayList<>(n.courtsX);
            if (eC.isEmpty()) eC.add(n.entry);
            if (xC.isEmpty()) xC.add(n.exit);
            eC.sort(Comparator.comparingDouble(i -> lat(n.ch.get(i))));
            xC.sort(Comparator.comparingDouble(i -> lat(n.ch.get(i))));
            int S = Math.max(eC.size(), xC.size());
            double g = S == 3 ? r(85, 85, 120) : r(85, 100, 140);
            for (int i = 0; i < S; i++) {
                Strand st = new Strand();
                st.s = i;
                st.a = eC.get(S == 1 ? 0 : (int) Math.round(i * (eC.size() - 1) / (double) (S - 1)));
                st.b = xC.get(S == 1 ? 0 : (int) Math.round(i * (xC.size() - 1) / (double) (S - 1)));
                st.off = (i - (S - 1) / 2.0) * g;
                strands.add(st);
            }
            // one strand is walked end to end: no climbs on it, so the exit is always reachable on foot
            Strand ws = strands.get(S == 3 ? 1 : (u(86) < 0.5 ? 0 : 1));
            ws.walkOnly = true;
            if (li == Terrain.BLUE) {
                Strand rs = strands.get(u(87) < 0.5 ? 0 : S - 1);
                rs.canyon = true;
                rs.walkOnly = true;
                if (ws != rs) ws.walkOnly = false;
                Strand ds = null;
                double best = 1e18;
                for (Strand st : strands) {
                    if (st == rs) continue;
                    double d = city != null && city.keep ? Math.abs(lat(city) - st.off) : Math.abs(st.s - (S - 1 - rs.s));
                    if (d < best) { best = d; ds = st; }
                }
                if (ds != null) ds.dark = true;
            }
        }

        double lat(Chamber c) { Chamber E = n.ch.get(n.entry); return (c.x - E.x) * axNx + (c.z - E.z) * axNz; }

        double alongT(double x, double z) { Chamber E = n.ch.get(n.entry); return ((x - E.x) * axUx + (z - E.z) * axUz) / axLen; }

        /** The strand's planned line at fraction t: from its entry court to its exit court, bowed out to its slot. */
        double[] slotPoint(Strand st, double t) {
            Chamber a = n.ch.get(st.a), b = n.ch.get(st.b);
            double px = a.x + (b.x - a.x) * t, pz = a.z + (b.z - a.z) * t;
            double want = st.off - (lat(a) + (lat(b) - lat(a)) * t);
            double bump = Math.pow(Math.sin(Math.PI * Math.max(0, Math.min(1, t))), 0.8);
            px += axNx * want * bump;
            pz += axNz * want * bump;
            double m = EDGE_MARGIN + 34;
            return new double[]{Math.max(x0 + m, Math.min(x0 + cell - m, px)), Math.max(z0 + m, Math.min(z0 + cell - m, pz))};
        }

        double fT(Strand st, double t) {
            Chamber a = n.ch.get(st.a), b = n.ch.get(st.b);
            return walkF(a) + (walkF(b) - walkF(a)) * t;
        }

        /** Set pieces that sit on strands: the strand's line passes through them. */
        Chamber well, cascade, perched, greatHigh, chimney;

        void anchors() {
            if (strands.isEmpty()) return;
            int S = strands.size();
            switch (li) {
                case Terrain.GARDENS -> {
                    Strand sw = walkStrand();
                    if (helixRoom()) well = helixAnchor(sw, Kind.WELL, 260);
                    Strand sh = strands.get(S == 3 ? (u(261) < 0.5 ? 0 : 2) : 1 - sw.s);
                    double tc = r(240, 0.32, 0.62);
                    Chamber em = chamber(Kind.TERRACE, 240);
                    em.rx = r(241, 21, 27); em.rz = em.rx * r(242, 0.75, 0.95); em.setRot(r(243, 0, 3.14));
                    em.rampDrop = r(247, 12, 16);
                    em.f0 = snapTier(fT(sh, tc) - em.rampDrop * 0.5, 0, 0, 0);
                    em.h = r(245, 22, 28); em.rise = 3; em.terr = 2;
                    double[] d0 = slotPoint(sh, tc - 0.05), d1 = slotPoint(sh, tc + 0.05);
                    double dl = Math.max(1e-6, Math.hypot(d1[0] - d0[0], d1[1] - d0[1]));
                    em.rampX = (d1[0] - d0[0]) / dl; em.rampZ = (d1[1] - d0[1]) / dl; // low side towards the exit
                    em.set = true; em.hunting = true; em.pend = 2;
                    if (anchorPlace(sh, em, tc, 248)) { cascade = em; huntC = new double[]{em.x, em.f0, em.z}; }
                    if (S == 3) {
                        Strand sm = strands.get(2 - sh.s);
                        double tp = r(220, 0.3, 0.7);
                        Chamber pl = perchedLake(fT(sm, tp) - 6.5);
                        if (anchorPlace(sm, pl, tp, 228)) perched = pl;
                    }
                }
                case Terrain.BASALT -> {
                    Strand sw = walkStrand();
                    Strand sg = strands.get(S == 3 ? (u(320) < 0.5 ? 0 : 2) : 1 - sw.s);
                    double tg = r(321, 0.3, 0.62);
                    Chamber g = chamber(Kind.GREAT, 320);
                    g.rx = r(321, 24, 28); g.rz = r(322, 19, 25); // v12: as the low great hall
                    g.sq = 2.4; g.f0 = Math.max(W + Terrain.SURGE_RISE + 2, fT(sg, tg) - 4);
                    g.h = r(325, 40, 52); g.rise = 9; g.terr = 3; g.set = true;
                    double[] d0 = slotPoint(sg, tg - 0.05), d1 = slotPoint(sg, tg + 0.05);
                    g.setRot(Math.atan2(d1[1] - d0[1], d1[0] - d0[0]));
                    double dl = Math.max(1e-6, Math.hypot(d1[0] - d0[0], d1[1] - d0[1]));
                    g.rampX = (d1[0] - d0[0]) / dl; g.rampZ = (d1[1] - d0[1]) / dl;
                    g.rampDrop = r(327, 12, 18);
                    g.f0 -= g.rampDrop * 0.5;
                    if (anchorPlace(sg, g, tg, 326)) greatHigh = g;
                    if (helixRoom()) chimney = helixAnchor(sw, Kind.CHIMNEY, 360);
                }
                default -> {
                    if (city != null && city.keep) {
                        for (Strand st : strands) {
                            if (!st.dark) continue;
                            city.strand = st.s;
                            st.anchors.add(city);
                            st.anchorT.add(Math.max(0.15, Math.min(0.85, alongT(city.x, city.z))));
                        }
                    }
                }
            }
        }

        /**
         * The helix set pieces need height (section 2.6): the well only when the Deep Gardens are at least 180 tall, the
         * chimney only when the Basalt Galleries are at least 200 tall (the defaults are 240 and 288).
         */
        boolean helixRoom() { return T - B + 1 >= (li == Terrain.BASALT ? 200 : 180); }

        Strand walkStrand() {
            for (Strand st : strands) if (st.walkOnly) return st;
            return strands.get(0);
        }

        Chamber perchedLake(double fy) {
            Chamber pl = chamber(Kind.LAKE, 220);
            pl.rx = r(221, 20, 26); pl.rz = pl.rx * r(222, 0.8, 1.0); pl.setRot(r(223, 0, 3.14));
            pl.f0 = Math.max(W + 40, fy); pl.lakeY = pl.f0 + r(225, 5, 7); pl.h = r(226, 16, 22) + (pl.lakeY - pl.f0);
            pl.rise = r(227, 4, 6); pl.lakeE = 0.6; pl.set = true; pl.pend = 3;
            return pl;
        }

        /** A well or chimney on a strand: the strand comes in at the top of its spiral and leaves at the bottom. */
        Chamber helixAnchor(Strand st, Kind kind, long salt) {
            double tw = r(salt + 1, 0.38, 0.58);
            double total = fT(st, 0) - fT(st, 1);
            double drop = kind == Kind.CHIMNEY ? Math.min(r(salt + 2, 95, 130), total * 0.55) : Math.min(r(salt + 2, 70, 100), total * 0.5);
            Chamber c = chamber(kind, salt + 3);
            c.rx = c.rz = r(salt + 4, 10.5, 12.5);
            c.warp = 0.08;
            double mid = fT(st, tw);
            c.f0 = mid - drop / 2;
            if (kind == Kind.CHIMNEY) c.f0 = Math.max(c.f0, W + Terrain.SURGE_RISE + 4);
            c.top = Math.min(T - 26, c.f0 + drop + 12);
            c.h = c.top - c.f0;
            c.rise = 2;
            c.set = true;
            helix(c, salt + 5);
            return anchorPlace(st, c, tw, salt + 6) ? c : null;
        }

        boolean anchorPlace(Strand st, Chamber c, double t, long salt) {
            for (int i = 0; i < 24; i++) {
                double tt = t + (i == 0 ? 0 : r(salt + i * 3L, -0.12, 0.12));
                double[] p = slotPoint(st, tt);
                double lj = i == 0 ? 0 : r(salt + i * 3L + 1, -22, 22);
                c.x = p[0] + axNx * lj;
                c.z = p[1] + axNz * lj;
                if (fits(c)) {
                    add(c);
                    c.strand = st.s;
                    st.anchors.add(c);
                    st.anchorT.add(tt);
                    return true;
                }
            }
            return false;
        }

        double meanderA;

        /**
         * One meander amplitude for every strand of the cell (the largest any strand needs to be long enough for its
         * drop, at most 0.4 of the gap between strands), so the strands stay side by side and never cross.
         */
        void meander() {
            double gap = 1e9;
            for (int i = 0; i + 1 < strands.size(); i++) gap = Math.min(gap, Math.abs(strands.get(i + 1).off - strands.get(i).off));
            double cap = Math.min(70, gap * 0.4);
            // the fewest half-waves (3 to 8) at which every strand gets long enough within the amplitude cap
            meanderA = cap;
            meanderK = 4;
            for (int k = 3; k <= 4; k++) {
                double need = 0;
                boolean all = true;
                for (Strand st : strands) {
                    if (st.canyon) continue;
                    double want = strandWant(st);
                    double A = 0;
                    for (; A <= cap; A += 4) if (polyLen(catmull(controlPoints(st, A, k), 3.0)) >= want) break;
                    if (A > cap) { all = false; break; }
                    need = Math.max(need, A);
                }
                if (all) { meanderK = k; meanderA = need; break; }
            }
        }

        int meanderK = 3;

        double pitchTotal(Strand st, double drop) {
            if (st.walkOnly) return 0;
            return Math.min(3 * 28, 0.45 * drop);
        }

        double strandWant(Strand st) {
            double drop = walkF(n.ch.get(st.a)) - walkF(n.ch.get(st.b));
            for (Chamber c : st.anchors) {
                if (c.helix()) drop -= (c.helixTop - 2) - (c.f0 + 1);
                else if (c.rampDrop > 0) drop -= c.rampDrop * 0.8;
            }
            return Math.max(0, drop - pitchTotal(st, drop)) / 0.40 + 30;
        }

        /** Lays one strand: its line through its anchors, chambers along it, and the tunnels joining them in order. */
        void buildStrand(Strand st) {
            if (st.canyon) { canyonStrand(st); return; }
            Chamber a = n.ch.get(st.a), b = n.ch.get(st.b);
            // keys: the entry court, the anchors in order, the exit court; each with the floor the way enters and leaves at
            List<Object[]> keys = new ArrayList<>();
            keys.add(new Object[]{a, 0.0, walkF(a), walkF(a)});
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < st.anchors.size(); i++) order.add(i);
            order.sort(Comparator.comparingDouble(st.anchorT::get));
            for (int i : order) {
                Chamber c = st.anchors.get(i);
                double fin, fout;
                if (c.helix()) { fin = c.helixTop - 2; fout = c.f0 + 1; }
                else if (c.kind == Kind.TERRACE || (c.kind == Kind.GREAT && c.rampDrop > 0)) { fin = c.f0 + c.rampDrop * 0.8; fout = c.f0 + 1; }
                else { fin = fout = walkF(c); }
                keys.add(new Object[]{c, st.anchorT.get(i), fin, fout});
            }
            keys.add(new Object[]{b, 1.0, walkF(b), walkF(b)});
            // the drop this strand walks (the anchors' own drops left out) and the line length that takes:
            // chambers step down at about 0.3 and tunnels at about 0.5, so the line averages about 0.4
            double walkDrop = 0;
            for (int k = 0; k + 1 < keys.size(); k++) walkDrop += Math.max(0, (double) keys.get(k)[3] - (double) keys.get(k + 1)[2]);
            List<double[]> line = catmull(controlPoints(st, meanderA, meanderK), 3.0);
            st.line = line;
            double[] arc = arcs(line);
            double total = arc[arc.length - 1];
            double[] ks = new double[keys.size()];
            for (int k = 0; k < keys.size(); k++) {
                Chamber c = (Chamber) keys.get(k)[0];
                ks[k] = k == 0 ? 0 : (k == keys.size() - 1 ? total : Math.max(ks[k - 1], arcOf(line, arc, c.x, c.z)));
            }
            // pitches: on a climbing strand, part of each stretch's drop is taken by short vine shafts
            double walkDropAll = 0;
            for (int k = 0; k + 1 < keys.size(); k++) walkDropAll += Math.max(0, (double) keys.get(k)[3] - (double) keys.get(k + 1)[2]);
            double pt = pitchTotal(st, walkDropAll);
            int np = pt <= 0 ? 0 : Math.max(1, (int) Math.ceil(pt / 28));
            st.pitchS = new double[np];
            st.pitchD = new double[np];
            for (int i = 0; i < np; i++) {
                st.pitchS[i] = total * ((i + 1.0) / (np + 1) + r(1600 + st.s * 10L + i, -0.06, 0.06));
                st.pitchD[i] = pt / np;
            }
            st.nodes.add(st.a);
            int prev = st.a;
            for (int k = 0; k + 1 < keys.size(); k++) {
                Chamber k0 = (Chamber) keys.get(k)[0], k1 = (Chamber) keys.get(k + 1)[0];
                double f0 = (double) keys.get(k)[3], f1 = (double) keys.get(k + 1)[2];
                double s0 = ks[k], s1 = ks[k + 1];
                st.segF0 = f0; st.segF1 = f1; st.segS0 = s0; st.segS1 = s1;
                double sPrev = s0;
                Chamber pc = k0;
                for (int guard = 0; guard < 24; guard++) {
                    long salt = 3000 + st.s * 400L + k * 40L + guard;
                    Chamber proto = strandProto(st, salt, 0, 0, 0);
                    double step = Math.max(sp.spacing, pc.R() + proto.R() + 14);
                    double ss = sPrev + step;
                    if (ss > s1 - (proto.R() + k1.R() + 12)) break;
                    double[] p = pointAt(line, arc, ss), dir = dirAt(line, arc, ss);
                    double rr = Math.max(proto.rx, proto.rz) * 0.8;
                    double fHi = st.prof(ss - rr), fLo = st.prof(ss + rr);
                    Chamber c = strandNode(st, p, dir, fHi, fLo, salt);
                    sPrev = ss;
                    if (c == null) continue;
                    link(st, prev, c.id);
                    st.nodes.add(c.id);
                    prev = c.id;
                    pc = c;
                }
                link(st, prev, k1.id);
                prev = k1.id;
                st.nodes.add(k1.id);
            }
        }

        /** Control points of a strand's line: its slot, the anchors' centres, and a lateral meander of amplitude A. */
        List<double[]> controlPoints(Strand st, double A, int halfWaves) {
            Chamber a = n.ch.get(st.a), b = n.ch.get(st.b);
            double ph = u(88) < 0.5 ? 0 : Math.PI;
            List<double[]> ctrl = new ArrayList<>();
            int N = 16;
            for (int k = 0; k <= N; k++) {
                double t = k / (double) N;
                boolean near = false;
                for (double at : st.anchorT) if (Math.abs(at - t) < 0.05) near = true;
                if (near && k > 0 && k < N) continue;
                double[] p;
                if (k == 0) p = new double[]{a.x, a.z};
                else if (k == N) p = new double[]{b.x, b.z};
                else {
                    p = slotPoint(st, t);
                    double taper = Math.sqrt(Math.sin(Math.PI * t));
                    for (double at : st.anchorT) taper *= 1 - Math.exp(-((t - at) / 0.07) * ((t - at) / 0.07));
                    double off = A * Math.sin(Math.PI * halfWaves * t + ph) * taper;
                    double m = EDGE_MARGIN + 34;
                    p[0] = Math.max(x0 + m, Math.min(x0 + cell - m, p[0] + axNx * off));
                    p[1] = Math.max(z0 + m, Math.min(z0 + cell - m, p[1] + axNz * off));
                }
                ctrl.add(new double[]{p[0], p[1], t});
            }
            for (int i = 0; i < st.anchors.size(); i++) ctrl.add(new double[]{st.anchors.get(i).x, st.anchors.get(i).z, st.anchorT.get(i)});
            ctrl.sort(Comparator.comparingDouble(p -> p[2]));
            List<double[]> cp = new ArrayList<>();
            for (double[] p : ctrl) cp.add(new double[]{p[0], p[1]});
            return cp;
        }

        /** The kind and size of a strand chamber, before it has a place. */
        Chamber strandProto(Strand st, long s, double x, double z, double f) {
            Chamber c = new Chamber();
            c.hash = Hashing.mix(h, s, 6, 0);
            switch (li) {
                case Terrain.GARDENS -> {
                    if (inHunt(x, f, z)) {
                        c.kind = Kind.TERRACE; c.hunting = true;
                        c.rx = r(s + 3, 9, 16); c.rz = c.rx * r(s + 4, 0.7, 1.0);
                        c.h = r(s + 5, 12, 20); c.rise = 2; c.terr = r(s + 6, 1.4, 2.2);
                        c.rampDrop = r(s + 8, 5, 10); c.pend = 2;
                    } else {
                        c.kind = Kind.GROTTO;
                        double dia = sizeDraw(s + 3, 16, 30);
                        c.rx = dia / 2; c.rz = c.rx * r(s + 4, 0.65, 1.0);
                        c.h = heightDraw(s + 5, 10, 18); c.rise = r(s + 6, 2, 4); c.terr = 1; c.pend = r(s + 7, 2, 4);
                        c.sq = r(s + 8, 2.0, 2.8);
                    }
                }
                case Terrain.BASALT -> {
                    if (u(s + 9) < 0.5) {
                        c.kind = Kind.GALLERY;
                        // v9: galleries at Taran's sizes, 26-36 long and 14-22 wide (were 36-60 by 16-26)
                        c.rx = r(s + 3, 13, 18); c.rz = r(s + 4, 7, 11); c.sq = 2.5;
                        c.h = r(s + 5, 13, 19); c.rise = 4; c.terr = 2;
                    } else {
                        c.kind = Kind.GROTTO;
                        double dia = sizeDraw(s + 3, 16, 30);
                        c.rx = dia / 2; c.rz = c.rx * r(s + 4, 0.65, 1.0);
                        c.h = heightDraw(s + 5, 10, 18); c.rise = r(s + 6, 2, 4); c.terr = 1.5; c.sq = r(s + 8, 2.0, 2.8);
                    }
                }
                default -> {
                    c.kind = Kind.GROTTO;
                    double dia = sizeDraw(s + 3, 20, 32);
                    c.rx = dia / 2; c.rz = c.rx * r(s + 4, 0.7, 1.0);
                    c.h = heightDraw(s + 5, 12, 20); c.rise = 3; c.terr = 1; c.pend = 3;
                }
            }
            c.setRot(r(s + 10, 0, Math.PI));
            return c;
        }

        Chamber strandNode(Strand st, double[] p, double[] dir, double fHi, double fLo, long salt) {
            for (int i = 0; i < 14; i++) {
                long s = salt * 31 + i * 17L;
                double al = i == 0 ? 0 : r(s, -1, 1) * (4 + i * 1.5), la = i == 0 ? 0 : r(s + 1, -1, 1) * (6 + i * 2.5);
                double x = p[0] + dir[0] * al - dir[1] * la, z = p[1] + dir[1] * al + dir[0] * la;
                double fm = (fHi + fLo) / 2;
                Chamber c = strandProto(st, salt, x, z, fm);
                c.x = x;
                c.z = z;
                if (c.kind == Kind.GALLERY) c.setRot(Math.atan2(dir[1], dir[0]));
                // the floor steps down along the way through the chamber: about 0.3 at most
                double R = Math.max(c.rx, c.rz);
                c.rampDrop = Math.max(0, Math.min(fHi - fLo, 0.3 * 2 * R));
                if (c.kind == Kind.TERRACE) c.rampDrop = Math.max(c.rampDrop, 5);
                c.rampX = dir[0];
                c.rampZ = dir[1];
                if (c.terr <= 0) c.terr = 1;
                c.f0 = fm - c.rampDrop * 0.5 - 1;
                // Basalt Galleries: the whole floor stays above the highest surge, as in fill (near the bedrock landing
                // the strand's line comes down to the surge stairs, and a ramp's low end fell under the surge)
                if (li == Terrain.BASALT) c.f0 = Math.max(c.f0, W + Terrain.SURGE_RISE + 2);
                c.strand = st.s;
                if (fits(c)) {
                    add(c);
                    return c;
                }
            }
            n.strandBreaks++;
            return null;
        }

        boolean inHunt(double x, double f, double z) {
            if (huntC == null) return false;
            double dxz = Math.hypot(x - huntC[0], z - huntC[2]);
            return Math.hypot(dxz / 125.0, (f - huntC[1]) / 55.0) < 1;
        }

        double[] huntC;

        /** Joins two chambers of a strand; the walking strand never climbs, the others sometimes do. */
        boolean link(Strand st, int a, int b) {
            if (a == b || alreadyEdge(a, b)) return true;
            Edge e = edge(a, b);
            noClimb = st.walkOnly;
            preferClimb = !st.walkOnly && st.climbs < 4;
            widthMul = 1.2;
            boolean ok = embed(e, false) || embed(e, true);
            noClimb = false;
            preferClimb = false;
            widthMul = 1;
            if (ok) {
                e.strand = st.s;
                union(a, b);
                if (e.kind == EK.CLIMB) st.climbs++;
            } else {
                n.strandBreaks++;
            }
            return ok;
        }

        boolean linkVia(Strand st, int from, int to) {
            Chamber A = n.ch.get(from), B = n.ch.get(to);
            double d = Math.hypot(A.x - B.x, A.z - B.z);
            if (d > A.R() + B.R() + sp.spacing * 1.1) {
                double[] p = {(A.x + B.x) / 2, (A.z + B.z) / 2};
                double[] dir = {(B.x - A.x) / d, (B.z - A.z) / d};
                double fa = walkF(A), fb = walkF(B);
                Chamber c = strandNode(st, p, dir, fa + (fb - fa) * 0.35, fa + (fb - fa) * 0.65, 5000 + from * 7L + to);
                if (c != null && link(st, from, c.id)) {
                    st.nodes.add(c.id);
                    return link(st, c.id, to);
                }
            }
            return link(st, from, to);
        }

        /** Blue Reaches: one strand is the rift, 0.2.0's canyon with its ledge path, bridges and geodes. */
        void canyonStrand(Strand st) {
            Chamber a = n.ch.get(st.a), b = n.ch.get(st.b);
            double t0 = r(600, 0.16, 0.24), t1 = r(601, 0.78, 0.86);
            // 0.2.0's rift starts at the entry landing's floor: its ledge path comes down from near the top of the layer
            // and the rift stands its full depth there (Route caps its top at floor + 14). Starting at the entry court's
            // floor instead, as the prototype did, made it about 23 blocks shallower (tallest run 139 against 162).
            double sFloor = Math.max(walkF(a) - r(606, 8, 16), n.ch.get(n.entry).f0), eFloor = W + 3;
            Route best = null;
            int bestHits = Integer.MAX_VALUE;
            double push = 0;
            double sgn = st.off >= 0 ? 1 : -1;
            for (int attempt = 0; attempt < 6; attempt++) {
                double[] hp = slotPoint(st, t0 + 0.02 * attempt), fp = slotPoint(st, t1 - 0.02 * attempt);
                hp[0] += axNx * push * sgn; hp[1] += axNz * push * sgn;
                fp[0] += axNx * push * sgn; fp[1] += axNz * push * sgn;
                hp = clampP(hp, 100);
                fp = clampP(fp, 100);
                Route rt = w.canyon(li, cx, cz, Hashing.mix(h, 610 + attempt, 0, 0), hp[0], hp[1], fp[0], fp[1], sFloor, eFloor);
                int hits = 0;
                for (Chamber c : n.ch) if (c.keep && c.kind != Kind.CANYON && canyonHits(rt, c)) hits += c.kind == Kind.CITY ? 1 : 3;
                if (hits < bestHits) { bestHits = hits; best = rt; }
                if (hits == 0) break;
                push += 25;
            }
            Route rt = best;
            if (bestHits > 0) for (Chamber c : n.ch) if (c.keep && c.kind == Kind.CITY && canyonHits(rt, c)) { c.keep = false; n.droppedChambers++; }
            n.canyons.add(rt);
            // the geodes' tunnels become ways of the plan, so their floor and headroom are kept like every other way's
            for (Route.Link l : rt.links) {
                double L = Math.hypot(l.x2() - l.x1(), l.z2() - l.z1());
                int m = Math.max(3, (int) Math.ceil(L / 2.0) + 1);
                Way wy = new Way();
                wy.kind = EK.WALK;
                alloc(wy, m);
                for (int i = 0; i < m; i++) {
                    double t = i / (double) (m - 1);
                    wy.x[i] = l.x1() + (l.x2() - l.x1()) * t;
                    wy.z[i] = l.z1() + (l.z2() - l.z1()) * t;
                    wy.f[i] = l.y1() - 2.5 + (l.y2() - l.y1()) * t;
                    wy.w[i] = 2.8; wy.hgt[i] = 6; wy.slab[i] = 3;
                }
                easeFloor(wy, 0.85);
                wy.biome = DeepBiome.ASURINE_GEODES;
                wy.bounds();
                addWay(wy);
            }
            st.line = new ArrayList<>();
            for (double s = 0; s < rt.walkLength; s += 6) { double[] p = rt.pathAt(s); st.line.add(new double[]{p[0], p[1]}); }
            List<Integer> cn = new ArrayList<>();
            int k = 0, prev = -1;
            double step = 44;
            for (double s = 6; s <= rt.walkLength - 6; s += step) {
                double[] p = rt.pathAt(s);
                double fi = rt.walkIndex(s);
                int i = (int) Math.floor(fi);
                double off = rt.atIndex(rt.offset, fi);
                double[] d = rt.dirAt(rt.ps[Math.min(rt.n - 1, i)]);
                double sg = off >= 0 ? 1 : -1;
                double ox = -d[1] * sg, oz = d[0] * sg;
                if (Math.abs(off) < 2) { ox = 0; oz = 0; }
                Chamber c = new Chamber();
                c.kind = Kind.CANYON;
                c.x = p[0]; c.z = p[1]; c.f0 = p[2]; c.rx = c.rz = 6; c.warp = 0; c.h = 8;
                c.canyon = 0; c.canyonS = s; c.doorDX = ox; c.doorDZ = oz; c.strand = st.s;
                c.hash = Hashing.mix(h, 620, k++, 0);
                add(c);
                if (prev >= 0) {
                    Edge e = new Edge();
                    e.a = prev; e.b = c.id; e.kind = EK.LEDGE; e.tree = true; e.len = step; e.strand = st.s;
                    e.dy = Math.abs(n.ch.get(prev).f0 - c.f0); e.cost = step;
                    n.edges.add(e);
                    union(prev, c.id);
                    n.ch.get(prev).deg++; c.deg++;
                }
                cn.add(c.id);
                prev = c.id;
            }
            st.nodes.add(st.a);
            boolean ok = false;
            for (int i = 0; i < Math.min(5, cn.size()) && !ok; i++) ok = linkVia(st, st.a, cn.get(i));
            st.nodes.addAll(cn);
            ok = false;
            for (int i = cn.size() - 1; i >= Math.max(0, cn.size() - 5) && !ok; i--) ok = linkVia(st, cn.get(i), st.b);
            st.nodes.add(st.b);
        }

        double[] clampP(double[] p, double m) {
            return new double[]{Math.max(x0 + m, Math.min(x0 + cell - m, p[0])), Math.max(z0 + m, Math.min(z0 + cell - m, p[1]))};
        }

        // ------------------------------------------------------------------------------------------- the deep dark
        final java.util.Map<Integer, List<double[]>> darkDoors = new java.util.HashMap<>();
        final java.util.Map<Long, Double> darkGap = new java.util.HashMap<>();

        /** Blue Reaches: 0.2.0's deep dark tiers, rasterised; every kept region with enough of itself in the cell is a node. */
        void darkPlan() {
            List<Route.Geode> geo = new ArrayList<>();
            for (Route r : n.canyons) geo.addAll(r.geodes);
            n.dark = w.dark.plan(cx, cz, geo, EDGE_MARGIN + 12);
            for (NetDark.Region g : n.dark.regions) {
                boolean holdsPortal = false;
                if (g.keep) for (int pid : n.portals) { Chamber pc = n.ch.get(pid); if (n.dark.near(g, pc.x, pc.z, 4)) holdsPortal = true; }
                if (!g.keep || (g.inside < NetDark.JOIN_MIN && !holdsPortal) || g.boundary.length == 0) continue;
                Chamber c = chamber(Kind.CAVERN, 7000 + g.id);
                c.dark = g.id;
                c.x = g.cx; c.z = g.cz; c.f0 = g.f0; c.h = Math.max(8, g.ceil - g.f0);
                c.rx = c.rz = Math.min(40, Math.max(6, g.inR)); c.warp = 0;
                c.hunting = true; c.required = true;
                add(c);
                g.node = c.id;
            }
        }

        /** A portal vestibule standing on a kept deep dark region's floor is that region's door (no tunnel, section 6.4). */
        void attachPortalsToDark() {
            for (int pid : n.portals) {
                Chamber c = n.ch.get(pid);
                for (NetDark.Region g : n.dark.regions) {
                    if (g.node < 0 || !g.keep) continue;
                    if (!n.dark.near(g, c.x, c.z, 4)) continue;
                    double fl = n.dark.floorAt(g.tier, c.x, c.z);
                    if (Math.abs(fl - c.f0) > 4) continue;
                    Edge e = new Edge();
                    e.a = Math.min(pid, g.node); e.b = Math.max(pid, g.node); e.kind = EK.DARK_DOOR; e.tree = true; e.len = 1; e.dy = Math.abs(fl - c.f0);
                    n.edges.add(e);
                    union(pid, g.node);
                    n.attached++;
                    break;
                }
            }
        }

        /** The ancient city's reserve round its chamber's centre (blocks): no view-cutting shape stands in it either. */
        static final double CITY_RESERVE = Network.CITY_RESERVE;
        /** Margin of the view breaker's map round the cell (blocks): a long run touching the cell lies inside it. */
        static final double SIGHT_APRON = 120;
        /** Line directions of the view breaker over half a turn, and the spacing of its lines (blocks). */
        static final int SIGHT_DIRS = 120;
        static final double SIGHT_OFF = 2;
        /** How far a real ray strays from the nearest sampled line over a run (blocks): 1 + 80 * tan(0.75 degrees). */
        static final double SIGHT_SHRINK = 2;
        /** Spacing (blocks) of the candidate sites. */
        static final int SIGHT_STEP = 8;
        /** A fin's radius, and its half length's range (blocks). */
        static final double FIN_R = 4, FIN_HALF_MIN = 11, FIN_HALF_MAX = 20;
        /**
         * Blocks a shape's foot keeps from the cavern's walls, the rift and the chambers, and from the ways, climbs,
         * geodes and the city. Walking cores and slabs win over the shapes anyway (mustOpen and mustStay come after
         * them), so 4 is room enough to walk round a foot; 8 and 6 left a third of the long runs uncut.
         */
        static final double SIGHT_WALL = 4, SIGHT_ROOM = 4;
        /** A shape stands where its tier is at least this tall along its whole segment (blocks). */
        static final double SIGHT_MIN_H = 12;

        /** Map classes of the view breaker: open cavern floor, closed, a chamber, the rift. */
        static final byte SEE_OPEN = 0, SEE_WALL = 1, SEE_ROOM = 2, SEE_RIFT = 3;

        /**
         * Blue Reaches: columns and fins (columns drawn out along a segment, {@link Network#columnSolid}) that cut the deep
         * dark's long views, and the same shapes standing in the rift as spires from its floor to its vault (NetSight). Per
         * tier, a map of where the tier is open (the plan's kept caverns in the cell, 0.2.0's raw tiers round it, the
         * rift and the chambers at the tier's heights) is swept with lines in 120 directions. Candidates stand on a
         * cavern's floor inside the cell, clear of the rift, the chambers, the tier's ways, the climbs, the geodes and
         * the city; or in the rift, clear of its ledge path, its bridges and every way, climb and geode at any height.
         * Their reach (2.5 r round the segment) stays two blocks inside the cell, so that both sides of a seam carve the
         * same rock (lines along a seam are cut by {@link Network#seamPillars}). A spire chosen for the lower tier stands
         * for the upper one too.
         */
        void sightBreak() {
            NetDark.Plan p = n.dark;
            NetDark dk = w.dark;
            double g = NetDark.G;
            int M = (int) Math.ceil((cell + 2 * SIGHT_APRON) / g);
            double sx0 = x0 - SIGHT_APRON, sz0 = z0 - SIGHT_APRON;
            int off = (int) Math.round((p.x0 - sx0) / g);
            double top = w.shape.top(li);
            double[][] span = {{W + 16, W + 82}, {W + 90, top - 6}};
            double[] t2 = new double[2];
            NetSight.Shapes[][] seams = {w.seamPillars(true, cx, cz), w.seamPillars(true, cx + 1, cz), w.seamPillars(false, cx, cz), w.seamPillars(false, cx, cz + 1)};
            double[] focus = {x0, z0, x0 + cell, z0 + cell};
            double[] ang = new double[SIGHT_DIRS];
            for (int a = 0; a < ang.length; a++) ang[a] = Math.PI * a / SIGHT_DIRS;
            // a spire stands the rift's full height: it keeps its room from everything at any height
            float[] roomAll = new float[M * M];
            Arrays.fill(roomAll, 1e6f);
            for (Way wy : n.ways) for (int j = 0; j < wy.n; j++) stampRoom(roomAll, M, sx0, sz0, g, wy.x[j], wy.z[j], wy.w[j], 32);
            for (Shaft sh : n.shafts) stampRoom(roomAll, M, sx0, sz0, g, sh.x, sh.z, sh.r + 2, 32);
            for (Route r : n.canyons) {
                for (Route.Geode ge : r.geodes) stampRoom(roomAll, M, sx0, sz0, g, ge.x(), ge.z(), ge.r() + 4, 32);
                for (double s = 0; s <= r.walkLength; s += 2) {
                    double[] q = r.pathAt(s);
                    stampRoom(roomAll, M, sx0, sz0, g, q[0], q[1], r.atIndex(r.half, r.walkIndex(s)) + 1, 32);
                }
                for (Route.Bridge b : r.bridges) {
                    double[] c = r.pointAt(b.s()), d = r.dirAt(b.s());
                    for (double v = -b.span(); v <= b.span(); v += 2)
                        stampRoom(roomAll, M, sx0, sz0, g, c[0] - d[1] * v, c[1] + d[0] * v, b.halfAlong() + 1, 32);
                }
            }
            for (Chamber c : n.ch) if (c.keep && c.kind == Kind.CITY) stampRoom(roomAll, M, sx0, sz0, g, c.x, c.z, CITY_RESERVE, 32);
            NetSight.Shapes spires = new NetSight.Shapes();
            for (int t = 0; t < 2; t++) {
                SightMap sm = new SightMap(new NetSight.Raster(sx0, sz0, g, M, M), focus);
                NetSight.Raster ras = sm.ras;
                for (int i = 0; i < M; i++) for (int k = 0; k < M; k++) {
                    double x = ras.px(i), z = ras.pz(k);
                    int c = i * M + k, pi = i - off, pk = k - off;
                    boolean inPlan = pi >= 0 && pk >= 0 && pi < p.N && pk < p.N;
                    boolean inCell = x >= x0 && x < x0 + cell && z >= z0 && z < z0 + cell;
                    double o, F, C;
                    if (inPlan) {
                        int pc = pi * p.N + pk;
                        double raw = p.open[t][pc];
                        o = inCell ? raw * Math.max(NetDark.band(NetDark.seamDist(x, z, cell)), p.keep[t][pc]) : raw;
                        if (o <= 0) { sm.obst[c] = SEE_WALL; continue; }
                        if (o == raw) { F = p.F[t][pc]; C = p.C[t][pc]; }
                        else { dk.tier(o, dk.floor0(t, x, z), dk.ceil0(t, x, z), t2); F = t2[0]; C = t2[1]; }
                    } else {
                        o = dk.open(t, x, z);
                        if (o <= 0) { sm.obst[c] = SEE_WALL; continue; }
                        dk.tier(o, dk.floor0(t, x, z), dk.ceil0(t, x, z), t2);
                        F = t2[0]; C = t2[1];
                    }
                    if (C - F < 3) { sm.obst[c] = SEE_WALL; continue; }
                    ras.see[c] = 1;
                    sm.hgt[c] = (float) (C - F);
                }
                // the rift and the chambers at the tier's heights are open too; spires may stand in the rift
                for (Route r : n.canyons) {
                    for (int i = 0; i < M; i++) for (int k = 0; k < M; k++) {
                        double x = ras.px(i), z = ras.pz(k);
                        if (x < x0 - 8 || x > x0 + cell + 8 || z < z0 - 8 || z > z0 + cell + 8) continue;
                        double[] ro = w.riftOpen(r, x, z, 0);
                        if (ro == null || ro[1] < span[t][0] || ro[0] > span[t][1]) continue;
                        ras.see[i * M + k] = 1;
                        sm.obst[i * M + k] = SEE_RIFT;
                        sm.hgt[i * M + k] = (float) (ro[1] - ro[0]);
                    }
                }
                for (Chamber c : n.ch) {
                    if (!c.keep || c.dark >= 0 || c.yHi() < span[t][0] || c.yLo() > span[t][1]) continue;
                    double R = c.R();
                    int i0 = Math.max(0, (int) Math.floor((c.x - R - sx0) / g)), i1 = Math.min(M - 1, (int) Math.floor((c.x + R - sx0) / g));
                    int k0 = Math.max(0, (int) Math.floor((c.z - R - sz0) / g)), k1 = Math.min(M - 1, (int) Math.floor((c.z + R - sz0) / g));
                    for (int i = i0; i <= i1; i++) for (int k = k0; k <= k1; k++) {
                        if (w.e(c, ras.px(i), ras.pz(k)) >= 1) continue;
                        ras.see[i * M + k] = 1;
                        sm.obst[i * M + k] = SEE_ROOM;
                    }
                }
                NetSight.Stats st = p.sightStats[t];
                for (int i = 0; i < M; i++) for (int k = 0; k < M; k++) {
                    int c = i * M + k;
                    double x = ras.px(i), z = ras.pz(k);
                    if (ras.see[c] != 0 && sm.obst[c] == SEE_OPEN && x >= x0 && x < x0 + cell && z >= z0 && z < z0 + cell) st.open += g * g;
                }
                byte[] walls = new byte[M * M], rift = new byte[M * M];
                for (int c = 0; c < walls.length; c++) {
                    walls[c] = (byte) (sm.obst[c] == SEE_WALL || sm.obst[c] == SEE_ROOM ? 1 : 0);
                    rift[c] = (byte) (sm.obst[c] == SEE_RIFT ? 1 : 0);
                }
                sm.clear = NetSight.distanceTo(walls, M, M, g);
                sm.clearRift = NetSight.distanceTo(rift, M, M, g);
                sm.roomAll = roomAll;
                // how near the tier's ways, climbs, geodes and the city come (blocks from their edge)
                float[] room = sm.room;
                Arrays.fill(room, 1e6f);
                for (Way wy : n.ways) for (int j = 0; j < wy.n; j++) {
                    if (wy.f[j] + wy.hgt[j] < span[t][0] - 6 || wy.f[j] > span[t][1] + 6) continue;
                    stampRoom(room, M, sx0, sz0, g, wy.x[j], wy.z[j], wy.w[j], 32);
                }
                for (Shaft sh : n.shafts) {
                    if (sh.y1 < span[t][0] - 6 || sh.y0 > span[t][1] + 6) continue;
                    stampRoom(room, M, sx0, sz0, g, sh.x, sh.z, sh.r + 2, 32);
                }
                for (Route r : n.canyons) for (Route.Geode ge : r.geodes) {
                    if (ge.y() + ge.r() < span[t][0] - 6 || ge.y() - ge.r() > span[t][1] + 6) continue;
                    stampRoom(room, M, sx0, sz0, g, ge.x(), ge.z(), ge.r() + 4, 32);
                }
                for (Chamber c : n.ch) {
                    if (!c.keep || c.kind != Kind.CITY || c.yHi() < span[t][0] || c.yLo() > span[t][1]) continue;
                    stampRoom(room, M, sx0, sz0, g, c.x, c.z, CITY_RESERVE, 32);
                }
                // the pillars already there: 0.2.0's, the seams' fins (which both neighbours carve), the spires so far
                List<double[]> grid = new ArrayList<>();
                dk.gridPillars(t, sx0, sz0, sx0 + M * g, sz0 + M * g, grid);
                for (double[] pl : grid) {
                    int i = (int) Math.floor((pl[0] - sx0) / g), k = (int) Math.floor((pl[1] - sz0) / g);
                    if (i >= 0 && k >= 0 && i < M && k < M && ras.see[i * M + k] != 0 && sm.obst[i * M + k] == SEE_OPEN) sm.blk.add(pl[0], pl[1], pl[0], pl[1], pl[2]);
                }
                for (NetSight.Shapes[] e : seams) for (int j = 0; j < e[t].n; j++) sm.blk.add(e[t].get(j));
                for (int j = 0; j < spires.n; j++) sm.blk.add(spires.get(j));
                sm.index();
                // candidates: a round pillar and fins in four directions on every site
                NetSight.Shapes cand = new NetSight.Shapes();
                List<Boolean> inRift = new ArrayList<>();
                int step = (int) Math.round(SIGHT_STEP / g);
                for (int i = 0; i < M; i += step) for (int k = 0; k < M; k += step) {
                    double x = ras.px(i), z = ras.pz(k);
                    if (x < x0 || x >= x0 + cell || z < z0 || z >= z0 + cell) continue;
                    int c = i * M + k;
                    if (ras.see[c] == 0 || (sm.obst[c] != SEE_OPEN && sm.obst[c] != SEE_RIFT)) continue;
                    long hh = Hashing.mix(h, 9900 + t, i, k);
                    double rr = Hashing.range(Hashing.mix(hh, 1, 0, 0), 5, 7);
                    int f = sm.fits(x, z, x, z, rr);
                    if (f > 0) { cand.add(x, z, x, z, rr); inRift.add(f == 2); }
                    double half = Hashing.range(Hashing.mix(hh, 2, 0, 0), FIN_HALF_MIN, FIN_HALF_MAX);
                    double turn = Hashing.range(Hashing.mix(hh, 3, 0, 0), -0.2, 0.2);
                    for (int q = 0; q < 4; q++) {
                        double a = q * Math.PI / 4 + turn, ux = Math.cos(a) * half, uz = Math.sin(a) * half;
                        f = sm.fits(x - ux, z - uz, x + ux, z + uz, FIN_R);
                        if (f > 0) { cand.add(x - ux, z - uz, x + ux, z + uz, FIN_R); inRift.add(f == 2); }
                    }
                }
                double ccx = sx0 + M * g * 0.5, ccz = sz0 + M * g * 0.5;
                int[] ch = NetSight.choose(ras, ang, ccx, ccz, SIGHT_OFF, focus, sm.blk, cand, NetSight.LMAX, SIGHT_SHRINK, 0.9, st);
                NetSight.Shapes res = new NetSight.Shapes();
                for (int j : ch) {
                    if (inRift.get(j)) spires.add(cand.get(j));
                    else res.add(cand.get(j));
                }
                p.setSight(t, res);
            }
            p.setSight(NetDark.Plan.RIFT, spires);
        }

        /** The view breaker's map of one tier, and where a shape may stand on it. */
        static final class SightMap {
            static final int BK = 32;
            final NetSight.Raster ras;
            final double[] cellBox;
            final byte[] obst;
            final float[] hgt, room;
            /** Blocks to the nearest wall or chamber, and to the rift; the room from everything at any height. */
            float[] clear, clearRift, roomAll;
            final NetSight.Shapes blk = new NetSight.Shapes();
            private int[][] bk;
            private int nb;

            SightMap(NetSight.Raster ras, double[] cellBox) {
                this.ras = ras;
                this.cellBox = cellBox;
                int m = ras.nx * ras.nz;
                obst = new byte[m];
                hgt = new float[m];
                room = new float[m];
            }

            /** Buckets the blockers by where their feet reach. */
            void index() {
                nb = (int) Math.ceil(ras.nx * ras.g / BK);
                List<List<Integer>> tmp = new ArrayList<>();
                for (int i = 0; i < nb * nb; i++) tmp.add(new ArrayList<>());
                for (int j = 0; j < blk.n; j++) {
                    double f = 1.65 * blk.r[j];
                    int i0 = bucket(Math.min(blk.ax[j], blk.bx[j]) - f, ras.x0), i1 = bucket(Math.max(blk.ax[j], blk.bx[j]) + f, ras.x0);
                    int k0 = bucket(Math.min(blk.az[j], blk.bz[j]) - f, ras.z0), k1 = bucket(Math.max(blk.az[j], blk.bz[j]) + f, ras.z0);
                    for (int i = i0; i <= i1; i++) for (int k = k0; k <= k1; k++) tmp.get(i * nb + k).add(j);
                }
                bk = new int[nb * nb][];
                for (int i = 0; i < bk.length; i++) bk[i] = tmp.get(i).stream().mapToInt(Integer::intValue).toArray();
            }

            private int bucket(double v, double o) { return Math.max(0, Math.min(nb - 1, (int) Math.floor((v - o) / BK))); }

            /**
             * Whether a shape (segment a-b, radius r) may stand here, and where: 1 on a cavern's floor, 2 in
             * the rift (a spire), 0 not at all. Its reach (2.5 r round the segment) stays two blocks inside the cell and
             * all of its segment lies on one of the two, at least SIGHT_MIN_H tall, apart from the pillars already
             * there. On a cavern's floor its foot (1.65 r) keeps SIGHT_WALL from the walls, the rift and the chambers
             * and SIGHT_ROOM from the tier's ways, climbs, geodes and the city; in the rift it stands a foot inside the
             * rift's walls, and its waist (0.75 r) keeps two blocks from the ledge path, the bridges and every way, climb
             * and geode at any height (the walking cores are carved after it anyway).
             */
            int fits(double ax, double az, double bx, double bz, double r) {
                double foot = 1.65 * r, reach = 2.5 * r + 2;
                for (int e = 0; e < 2; e++) {
                    double x = e == 0 ? ax : bx, z = e == 0 ? az : bz;
                    if (x - cellBox[0] < reach || cellBox[2] - x < reach || z - cellBox[1] < reach || cellBox[3] - z < reach) return 0;
                }
                double len = Math.hypot(bx - ax, bz - az);
                int ns = Math.max(1, (int) Math.ceil(len / 4));
                int kind = 0;
                for (int s = 0; s <= ns; s++) {
                    double x = ax + (bx - ax) * s / ns, z = az + (bz - az) * s / ns;
                    int i = (int) Math.floor((x - ras.x0) / ras.g), k = (int) Math.floor((z - ras.z0) / ras.g);
                    if (i < 0 || k < 0 || i >= ras.nx || k >= ras.nz) return 0;
                    int c = i * ras.nz + k;
                    if (ras.see[c] == 0) return 0;
                    int here = obst[c] == SEE_OPEN ? 1 : (obst[c] == SEE_RIFT ? 2 : 0);
                    if (here == 0 || (kind != 0 && here != kind)) return 0;
                    kind = here;
                    if (hgt[c] < SIGHT_MIN_H) return 0;
                    if (kind == 1) {
                        if (clear[c] < foot + SIGHT_WALL || clearRift[c] < foot + SIGHT_WALL || room[c] < foot + SIGHT_ROOM) return 0;
                    } else if (clear[c] < foot || roomAll[c] < 0.75 * r + 2) return 0;
                }
                int i0 = bucket(Math.min(ax, bx), ras.x0), i1 = bucket(Math.max(ax, bx), ras.x0);
                int k0 = bucket(Math.min(az, bz), ras.z0), k1 = bucket(Math.max(az, bz), ras.z0);
                for (int i = i0; i <= i1; i++) for (int k = k0; k <= k1; k++)
                    for (int j : bk[i * nb + k])
                        if (NetSight.segSeg(ax, az, bx, bz, blk.ax[j], blk.az[j], blk.bx[j], blk.bz[j]) < 0.9 * (foot + 1.65 * blk.r[j])) return 0;
                return kind;
            }
        }

        /** Lowers {@code room} round (x, z) to the distance from a disc of radius rad, within reach blocks of it. */
        static void stampRoom(float[] room, int M, double sx0, double sz0, double g, double x, double z, double rad, double reach) {
            double R = rad + reach;
            int i0 = Math.max(0, (int) Math.floor((x - R - sx0) / g)), i1 = Math.min(M - 1, (int) Math.floor((x + R - sx0) / g));
            int k0 = Math.max(0, (int) Math.floor((z - R - sz0) / g)), k1 = Math.min(M - 1, (int) Math.floor((z + R - sz0) / g));
            for (int i = i0; i <= i1; i++) for (int k = k0; k <= k1; k++) {
                double d = Math.hypot(sx0 + g * (i + 0.5) - x, sz0 + g * (k + 0.5) - z) - rad;
                if (d < room[i * M + k]) room[i * M + k] = (float) d;
            }
        }

        /** Regions the planner could not join are closed, so nothing is carved that no one can reach. */
        void closeUnjoined() {
            boolean any = false;
            for (NetDark.Region g : n.dark.regions) {
                if (g.node < 0 || n.ch.get(g.node).keep) continue;
                g.keep = false;
                any = true;
            }
            if (any) w.dark.recomputeKeep(n.dark);
        }

        NetDark.Region region(Chamber c) { return n.dark.regions.get(c.dark); }

        int darkOf(int c) { return c >= 0 ? n.ch.get(c).dark : -1; }

        /** Horizontal gap between two nodes: centres less a share of the radii, or the raster distance for regions. */
        double gapXZ(Chamber a, Chamber b, double k) {
            if (a.dark < 0 && b.dark < 0) return Math.hypot(a.x - b.x, a.z - b.z) - k * (a.R() + b.R());
            if (a.dark >= 0 && b.dark >= 0) {
                long key = ((long) Math.min(a.id, b.id) << 32) | Math.max(a.id, b.id);
                Double v = darkGap.get(key);
                if (v == null) {
                    NetDark.Region ra = region(a), rb = region(b);
                    double best = 1e9;
                    for (int c : ra.boundary) best = Math.min(best, n.dark.distTo(rb, n.dark.px(c / n.dark.N), n.dark.pz(c % n.dark.N)));
                    v = best;
                    darkGap.put(key, v);
                }
                return v;
            }
            Chamber r = a.dark >= 0 ? a : b, o = a.dark >= 0 ? b : a;
            return n.dark.distTo(region(r), o.x, o.z) - k * o.R();
        }

        /** A door on region node R's core edge, facing the point (tx, tz): {x, z, floor, out x, out z}, or null. */
        double[] darkDoor(Chamber R, double tx, double tz, int attempt) {
            NetDark.Region g = region(R);
            List<double[]> cand = n.dark.nearestDoors(g, tx, tz, 6);
            List<double[]> used = darkDoors.getOrDefault(R.id, List.of());
            int skip = attempt;
            for (double[] p : cand) {
                boolean near = false;
                for (double[] u : used) if (Math.hypot(u[0] - p[0], u[1] - p[1]) < 16) near = true;
                if (near) continue;
                if (skip-- > 0) continue;
                double dx = tx - p[0], dz = tz - p[1], dl = Math.max(1e-6, Math.hypot(dx, dz));
                double fl = n.dark.floorAt(g.tier, p[0], p[1]);
                return new double[]{p[0], p[1], Math.max(fl, W + 1), dx / dl, dz / dl};
            }
            return null;
        }

        /** A short level walk from a region door into the cavern, so the tunnel's floor runs out onto the cavern's. */
        Way darkInnerWalk(Chamber R, double[] d) {
            int m = 9;
            Way wy = new Way();
            wy.kind = EK.WALK;
            alloc(wy, m);
            for (int i = 0; i < m; i++) {
                double s = i * 1.6;
                wy.x[i] = d[0] - d[3] * s;
                wy.z[i] = d[1] - d[4] * s;
                wy.f[i] = d[2];
                wy.w[i] = 2.2; wy.hgt[i] = 5; wy.slab[i] = 2.5;
            }
            wy.inner = true; wy.a = R.id; wy.biome = DeepBiome.DEEP_DARK;
            wy.bounds();
            return wy;
        }

        /** Set pieces that did not find a strand: placed anywhere they fit. */
        void offStrandSetPieces() {
            switch (li) {
                case Terrain.GARDENS -> {
                    if (perched == null) {
                        Chamber pl = perchedLake(W + r(224, 70, 140));
                        randomPlace(pl, 228);
                    }
                    if (well == null && helixRoom()) {
                        Chamber wl = chamber(Kind.WELL, 260);
                        wl.rx = wl.rz = r(261, 10.5, 12.5); wl.warp = 0.08;
                        wl.f0 = W + r(262, 2, 20); wl.top = Math.min(T - 26, wl.f0 + r(263, 70, 110));
                        wl.h = wl.top - wl.f0; wl.rise = 2; wl.set = true;
                        helix(wl, 264);
                        randomPlace(wl, 265);
                    }
                    if (cascade == null) {
                        Chamber em = chamber(Kind.TERRACE, 240);
                        em.rx = r(241, 21, 27); em.rz = em.rx * r(242, 0.75, 0.95); em.setRot(r(243, 0, 3.14));
                        em.f0 = W + r(244, 20, 110); em.h = r(245, 22, 28); em.rise = 3; em.terr = 2;
                        double ra = r(246, 0, 6.28); em.rampX = Math.cos(ra); em.rampZ = Math.sin(ra); em.rampDrop = r(247, 12, 16);
                        em.set = true; em.hunting = true; em.pend = 2;
                        randomPlace(em, 249);
                        if (em.id > 0 && n.ch.size() > em.id && n.ch.get(em.id) == em) cascade = em;
                    }
                }
                case Terrain.BASALT -> {
                    if (chimney == null && helixRoom()) {
                        Chamber c = chamber(Kind.CHIMNEY, 360);
                        c.rx = c.rz = r(361, 9.5, 11.5); c.warp = 0.08;
                        c.f0 = W + Terrain.SURGE_RISE + r(362, 4, 14); c.top = Math.min(T - 26, c.f0 + r(363, 70, 110));
                        c.h = c.top - c.f0; c.rise = 2; c.set = true;
                        helix(c, 364);
                        randomPlace(c, 365);
                    }
                    if (greatHigh == null) {
                        Chamber g = chamber(Kind.GREAT, 340);
                        g.rx = r(341, 24, 28); g.rz = r(342, 19, 25); g.setRot(r(343, 0, 3.14));
                        g.sq = 2.4; g.f0 = W + r(344, 40, 120); g.h = r(345, 40, 52); g.rise = 9; g.terr = 3; g.set = true;
                        randomPlace(g, 346);
                    }
                }
                default -> { }
            }
        }

        // ------------------------------------------------------------------------------------------- line helpers
        static double[] arcs(List<double[]> line) {
            double[] a = new double[line.size()];
            for (int i = 1; i < a.length; i++) a[i] = a[i - 1] + Math.hypot(line.get(i)[0] - line.get(i - 1)[0], line.get(i)[1] - line.get(i - 1)[1]);
            return a;
        }

        static double arcOf(List<double[]> line, double[] arc, double x, double z) {
            double best = 1e18, bs = 0;
            for (int i = 0; i < line.size(); i++) {
                double d = Math.hypot(line.get(i)[0] - x, line.get(i)[1] - z);
                if (d < best) { best = d; bs = arc[i]; }
            }
            return bs;
        }

        static double[] pointAt(List<double[]> line, double[] arc, double s) {
            int i = 1;
            while (i < arc.length - 1 && arc[i] < s) i++;
            double seg = Math.max(1e-9, arc[i] - arc[i - 1]);
            double t = Math.max(0, Math.min(1, (s - arc[i - 1]) / seg));
            return new double[]{line.get(i - 1)[0] + (line.get(i)[0] - line.get(i - 1)[0]) * t, line.get(i - 1)[1] + (line.get(i)[1] - line.get(i - 1)[1]) * t};
        }

        static double[] dirAt(List<double[]> line, double[] arc, double s) {
            double[] p = pointAt(line, arc, Math.max(0, s - 4)), q = pointAt(line, arc, s + 4);
            double d = Math.max(1e-9, Math.hypot(q[0] - p[0], q[1] - p[1]));
            return new double[]{(q[0] - p[0]) / d, (q[1] - p[1]) / d};
        }

        double strandDist(double x, double z) {
            double best = 1e9;
            for (Strand st : strands) for (double[] p : st.line) best = Math.min(best, Math.hypot(p[0] - x, p[1] - z));
            return best;
        }

        /** Refuges: bare, calm, off the main ways, reached by one tunnel of their own. */
        void refuges() {
            switch (li) {
                case Terrain.GARDENS -> {
                    for (int k = 0; k < 2; k++) {
                        Chamber s = chamber(Kind.SPRING, 400 + 20 * k);
                        s.rx = r(401 + 20 * k, 11, 15); s.rz = s.rx * r(402 + 20 * k, 0.8, 1.0); s.setRot(r(403 + 20 * k, 0, 3.14));
                        // up to 150 over the table, less in a layer too short for that (config)
                        s.f0 = W + r(404 + 20 * k, 10, Math.min(150, T - W - 50)); s.h = r(405 + 20 * k, 14, 19); s.rise = 2; s.terr = 2;
                        double ra = r(406 + 20 * k, 0, 6.28); s.rampX = Math.cos(ra); s.rampZ = Math.sin(ra); s.rampDrop = r(407 + 20 * k, 8, 12);
                        s.refuge = true; s.pend = 3;
                        offMainPlace(s, 408 + 20 * k);
                    }
                }
                case Terrain.BASALT -> {
                    for (int k = 0; k < 2; k++) {
                        Chamber t = chamber(Kind.TUBEHUB, 450 + 20 * k);
                        t.rx = t.rz = r(451 + 20 * k, 8, 10); t.warp = 0.06;
                        t.f0 = W + Terrain.SURGE_RISE + r(452 + 20 * k, 6, Math.min(90, T - W - Terrain.SURGE_RISE - 32)); t.h = r(453 + 20 * k, 12, 14); t.rise = 1;
                        t.refuge = true;
                        t.pillars = new double[]{0, 0, r(454 + 20 * k, 1.8, 2.6)};
                        offMainPlace(t, 455 + 20 * k);
                    }
                }
                default -> { }
            }
        }

        Chamber chamber(Kind k, long salt) {
            Chamber c = new Chamber();
            c.kind = k;
            c.hash = Hashing.mix(h, salt, 77, 0);
            c.setRot(0);
            return c;
        }

        void helix(Chamber c, long salt) {
            c.helixR = c.rx - 3.2;
            c.helixA0 = r(salt, 0, Math.PI * 2);
            c.helixDir = u(salt + 1) < 0.5 ? -1 : 1;
            c.helixSlope = 0.42;
            c.helixTop = c.top - 8;
            if (c.kind == Kind.WELL) {
                // a pool 3 deep at the foot of the well, out to half a block past the ledge's open edge, so a fall
                // from the spiral lands in water (section 10)
                c.lakeY = c.f0;
                c.lakeE = (c.helixR - 3.4 + 0.5) / c.rx;
            }
        }

        void clampIn(Chamber c) {
            double m = EDGE_MARGIN + c.R() + 6;
            c.x = Math.max(x0 + m, Math.min(x0 + cell - m, c.x));
            c.z = Math.max(z0 + m, Math.min(z0 + cell - m, c.z));
        }

        /** Lowers a chamber's ceiling so the band seal above stays rock (never below 7.5 blocks of headroom). */
        void underSeal(Chamber c) {
            if (c.helix() || c.landing()) return;
            double room = T - Terrain.SEAL - 6.5 - c.f0 - c.rise - c.rampDrop * 0.5 - 2;
            if (c.h > room) c.h = Math.max(7.5, room);
        }

        boolean fits(Chamber c) {
            if (strandMode) underSeal(c);
            double m = EDGE_MARGIN + c.R() + 4;
            if (c.x < x0 + m || c.x > x0 + cell - m || c.z < z0 + m || c.z > z0 + cell - m) return false;
            if (c.yLo() < B + Terrain.SEAL + (c.set ? 1 : 4) && !c.landing()) return false;
            if (c.yHi() > T - Terrain.SEAL - 6 && !c.landing()) return false;
            if (n.dark != null && c.dark < 0 && n.dark.hit(c.x, c.z, c.R() + sp.gapH, c.yLo() - sp.gapV, c.yHi() + sp.gapV, -1, -1)) return false;
            for (Chamber o : n.ch) {
                if (!o.keep || o.kind == Kind.CANYON || o.dark >= 0) continue;
                double gapH = sp.gapH;
                if (o.landing() || c.landing()) gapH = (c.court || o.court) ? 5 : sp.gapH + 10;
                double d = Math.hypot(c.x - o.x, c.z - o.z);
                if (d > c.R() + o.R() + gapH) continue;
                double gv = sp.gapV;
                if (c.yHi() + gv < o.yLo() || o.yHi() + gv < c.yLo()) continue;
                return false;
            }
            if (lavaHit(c.x, c.z, c.R(), c.yLo(), c.yHi(), sp.gapH, sp.gapV)) return false;
            if (c.kind != Kind.CITY && reserveHit(c.x, c.z, c.R(), c.yLo(), c.yHi())) return false;
            // keep out of the canyons' volume (Blue Reaches)
            for (Route r : n.canyons) {
                double[] o = w.riftOpen(r, c.x, c.z, c.R() + 12);
                if (o != null && c.yLo() - 6 < o[1] && c.yHi() + 6 > o[0]) return false;
            }
            if (wayHit(c)) return false;
            if (geodeHit(c.x, c.z, c.R(), c.yLo(), c.yHi(), GEODE_GAP + 2, GEODE_GAP)) return false;
            // the gates' passages and the band seals
            return !nearGatePassage(c);
        }

        /** Rock kept between a refuge geode and the network's rooms, tunnels and shafts (blocks). */
        static final double GEODE_GAP = 6;

        /**
         * Blue Reaches: true when a space (centre, reach, heights) comes within the gaps of a refuge geode. The network
         * keeps off the geodes, so a geode is entered by its own tunnel, as in 0.2.0, and no room or tunnel opens into
         * its bowl from halfway up (falls of 16 to 24 onto the geode's floor).
         */
        boolean geodeHit(double x, double z, double R, double yLo, double yHi, double gapH, double gapV) {
            for (Route r : n.canyons) for (Route.Geode g : r.geodes) {
                if (yHi + gapV < g.y() - g.r() * 0.5 || g.y() + g.r() + gapV < yLo) continue;
                if (Math.hypot(x - g.x(), z - g.z()) < R + g.r() + gapH) return true;
            }
            return false;
        }

        /** True when a planned tunnel passes through the chamber's volume (with a margin). */
        boolean wayHit(Chamber c) {
            double R = c.R();
            int bx0 = Math.max(0, (int) Math.floor((c.x - R - 12 - x0 + Net.APRON) / CB)), bx1 = Math.min(cn - 1, (int) Math.floor((c.x + R + 12 - x0 + Net.APRON) / CB));
            int bz0 = Math.max(0, (int) Math.floor((c.z - R - 12 - z0 + Net.APRON) / CB)), bz1 = Math.min(cn - 1, (int) Math.floor((c.z + R + 12 - z0 + Net.APRON) / CB));
            for (int bx = bx0; bx <= bx1; bx++) for (int bz = bz0; bz <= bz1; bz++) {
                for (int[] q : pts[bx * cn + bz]) {
                    Way o = n.ways.get(q[0]);
                    int j = q[1];
                    double d = Math.hypot(c.x - o.x[j], c.z - o.z[j]);
                    if (d > R + o.w[j] + Math.max(4, sp.clearC - 1)) continue;
                    if (o.f[j] + o.hgt[j] + sp.clearCV < c.yLo() || c.yHi() + sp.clearCV < o.f[j] - o.slab[j]) continue;
                    return true;
                }
            }
            return false;
        }

        boolean nearGatePassage(Chamber c) {
            for (int b = li + 1; b <= li + 2 && b <= w.shape.layerCount; b++) {
                Plans.Gate g = w.base.plans.gate(b, cx, cz);
                for (double y = c.yLo(); y <= c.yHi(); y += 4) {
                    if (g.passageXZ(c.x, y, c.z) < c.R() + g.radius * 1.5 + 8) return true;
                }
            }
            return false;
        }

        void placeOrRetry(Chamber c, long salt, Chamber near, double d) {
            for (int i = 0; i < 24; i++) {
                if (fits(c)) { add(c); return; }
                double a = r(salt + i * 3L, 0, Math.PI * 2);
                c.x = near.x + Math.cos(a) * d;
                c.z = near.z + Math.sin(a) * d;
                clampIn(c);
            }
            randomPlace(c, salt + 100);
        }

        void randomPlace(Chamber c, long salt) {
            for (int i = 0; i < 80; i++) {
                c.x = x0 + r(salt * 31 + i * 2L, 0, cell);
                c.z = z0 + r(salt * 31 + i * 2L + 1, 0, cell);
                clampIn(c);
                if (fits(c)) { add(c); return; }
            }
        }

        /**
         * Off the main ways: away from the line between the landings. A refuge that finds no place is tried again closer
         * to the main ways and 1 to 2 blocks smaller.
         */
        void offMainPlace(Chamber c, long salt) {
            if (offMain(c, salt, 75, 0.4, 90)) return;
            double shrink = r(salt * 31 + 997, 1, 2), f = (c.rx - shrink) / c.rx;
            c.rx -= shrink;
            c.rz *= f;
            offMain(c, salt + 7919, 45, 0.3, 70);
        }

        boolean offMain(Chamber c, long salt, double away, double relax, double fromLanding) {
            Chamber a = n.ch.get(n.entry), b = n.ch.get(n.exit);
            for (int i = 0; i < 120; i++) {
                c.x = x0 + r(salt * 31 + i * 2L, 0, cell);
                c.z = z0 + r(salt * 31 + i * 2L + 1, 0, cell);
                clampIn(c);
                double d = Math.min(segDistXZ(c.x, c.z, a.x, a.z, b.x, b.z) + 30, strandDist(c.x, c.z));
                if (d < away - i * relax) continue;
                if (Math.hypot(c.x - a.x, c.z - a.z) < fromLanding || Math.hypot(c.x - b.x, c.z - b.z) < fromLanding) continue;
                if (fits(c)) { add(c); return true; }
            }
            return false;
        }

        /** Ordinary chambers: dart throwing with a clearance rule, types by district. */
        void fill() {
            double[] dc = huntC;
            if (li == Terrain.GARDENS && dc == null) {
                // one hunting district per cell, away from the landings
                Chamber a = n.ch.get(n.entry), b = n.ch.get(n.exit);
                for (int i = 0; i < 40; i++) {
                    double px = x0 + r(500 + i, 120, cell - 120), pz = z0 + r(540 + i, 120, cell - 120);
                    if (Math.hypot(px - a.x, pz - a.z) < 130 || Math.hypot(px - b.x, pz - b.z) < 130) continue;
                    dc = new double[]{px, W + r(580 + i, 40, 130), pz};
                    huntC = dc;
                    break;
                }
            }
            int placed = 0, tries = 0;
            for (int i = 0; placed < sp.fill && i < sp.fill * 30; i++) {
                tries++;
                long s = 10_000L + i * 16L;
                Chamber c = new Chamber();
                c.hash = Hashing.mix(h, s, 5, 0);
                c.x = x0 + r(s, 0, cell);
                c.z = z0 + r(s + 1, 0, cell);
                double fy = W + r(s + 2, sp.yLo, sp.yHi);
                if (u(s + 11) < sp.tierShare && tierY.length > 0) {
                    int k = Math.min(tierY.length - 1, (int) (u(s + 12) * tierY.length));
                    fy = tierAt(k, c.x, c.z) + r(s + 13, -sp.tierJit, sp.tierJit);
                }
                boolean hunt = false;
                if (dc != null) {
                    double dxz = Math.hypot(c.x - dc[0], c.z - dc[2]);
                    hunt = Math.hypot(dxz / 125.0, (fy - dc[1]) / 55.0) < 1;
                }
                switch (li) {
                    case Terrain.GARDENS -> {
                        if (hunt) {
                            c.kind = Kind.TERRACE; c.hunting = true;
                            c.rx = r(s + 3, 9, 16); c.rz = c.rx * r(s + 4, 0.7, 1.0);
                            c.h = r(s + 5, 12, 20); c.rise = 2; c.terr = r(s + 6, 1.4, 2.2);
                            double ra = r(s + 7, 0, 6.28); c.rampX = Math.cos(ra); c.rampZ = Math.sin(ra); c.rampDrop = r(s + 8, 5, 10);
                            c.pend = 2;
                        } else {
                            c.kind = Kind.GROTTO;
                            double dia = sizeDraw(s + 3, 15, 30);
                            c.rx = dia / 2; c.rz = c.rx * r(s + 4, 0.65, 1.0);
                            c.h = heightDraw(s + 5, 10, 18); c.rise = r(s + 6, 2, 4); c.terr = 1; c.pend = r(s + 7, 2, 4);
                            c.sq = r(s + 8, 2.0, 2.8);
                        }
                    }
                    case Terrain.BASALT -> {
                        if (u(s + 9) < 0.45) {
                            c.kind = Kind.GALLERY;
                            // v9: galleries at Taran's sizes, 26-36 long and 14-22 wide (were 36-68 by 16-28)
                            c.rx = r(s + 3, 13, 18); c.rz = r(s + 4, 7, 11); c.sq = 2.5;
                            c.h = r(s + 5, 13, 19); c.rise = 4; c.terr = 2;
                        } else {
                            c.kind = Kind.GROTTO;
                            double dia = sizeDraw(s + 3, 15, 30);
                            c.rx = dia / 2; c.rz = c.rx * r(s + 4, 0.65, 1.0);
                            c.h = heightDraw(s + 5, 10, 18); c.rise = r(s + 6, 2, 4); c.terr = 1.5; c.sq = r(s + 8, 2.0, 2.8);
                        }
                        c.low = fy < W + Terrain.SURGE_RISE;
                    }
                    default -> {
                        // Blue Reaches: junction rooms between the rift, the caverns and the landings
                        c.kind = Kind.GROTTO;
                        double dia = sizeDraw(s + 3, 20, 32);
                        c.rx = dia / 2; c.rz = c.rx * r(s + 4, 0.7, 1.0);
                        c.h = heightDraw(s + 5, 12, 20); c.rise = 3; c.terr = 1; c.pend = 3;
                    }
                }
                c.setRot(r(s + 10, 0, Math.PI));
                c.f0 = fy;
                if (sp.rampP > 0 && c.kind == Kind.GROTTO && u(s + 14) < sp.rampP) {
                    // a sloping chamber: its floor steps down across it (1-block terraces), so storeys meet inside chambers
                    double dia = 2 * Math.max(c.rx, c.rz);
                    c.rampDrop = r(s + 15, 0.45, 1.0) * sp.rampMax * dia;
                    double ra = r(s + 16, 0, 6.28);
                    c.rampX = Math.cos(ra); c.rampZ = Math.sin(ra);
                    c.f0 = fy - c.rampDrop * 0.5;
                }
                if (li == Terrain.BASALT) {
                    // every ordinary chamber floor stays above the highest surge (the storeys' tilt and jitter included)
                    c.f0 = Math.max(c.f0, W + Terrain.SURGE_RISE + 2);
                    c.low = false;
                }
                underSeal(c);
                if (fits(c)) { add(c); placed++; }
            }
            n.tries = tries;
        }

        /** Diameters skewed towards the small end of the range: most caves small, some at the top. */
        double sizeDraw(long s, double lo, double hi) {
            double t = u(s);
            return lo + (hi - lo) * (0.65 * t + 0.35 * t * t);
        }

        double heightDraw(long s, double lo, double hi) {
            double t = (u(s) + u(s + 101)) * 0.5;
            return lo + (hi - lo) * t;
        }

        /**
         * True when the rift (or one of its geodes) comes within reach of a chamber: its half-width plus the chamber's
         * reach plus 16 (a geode: plus 10); for the city, its whole reserve (section 11.1: 128 plus the rift's
         * half-width round the site).
         */
        boolean canyonHits(Route r, Chamber c) {
            boolean isCity = c.kind == Kind.CITY;
            double reach = isCity ? Network.CITY_RESERVE : c.R() + 16;
            for (double s = 0; s < r.length; s += 6) {
                double[] p = r.pointAt(s);
                if (Math.hypot(p[0] - c.x, p[1] - c.z) < r.at(r.riftHalf, s) + reach) return true;
            }
            for (Route.Geode g : r.geodes) if (Math.hypot(g.x() - c.x, g.z() - c.z) < g.r() + (isCity ? Network.CITY_RESERVE : c.R() + 10)) return true;
            return false;
        }

        // ------------------------------------------------------------------------------------------- edges
        /** Candidate edges: each node to its nearest neighbours (vertical distance counts 2.5 times). */
        List<Edge> candidates() {
            List<Edge> out = new ArrayList<>();
            int N = n.ch.size();
            int K = sp.candK;
            for (int i = 0; i < N; i++) {
                Chamber a = n.ch.get(i);
                double[] best = new double[N];
                Integer[] idx = new Integer[N];
                for (int j = 0; j < N; j++) {
                    idx[j] = j;
                    Chamber b = n.ch.get(j);
                    double dxz = j == i ? 0 : gapXZ(a, b, 0.7);
                    double dy = Math.abs(a.f0 - b.f0);
                    best[j] = j == i ? 1e18 : Math.hypot(Math.max(0, dxz), dy * 2.5);
                    if (a.kind == Kind.CANYON && b.kind == Kind.CANYON) best[j] = 1e18;
                }
                Arrays.sort(idx, Comparator.comparingDouble(j -> best[j]));
                for (int k = 0; k < K && k < N; k++) {
                    int j = idx[k];
                    if (best[j] > sp.candMax) break;
                    if (j < i && contains(out, j, i)) continue;
                    Edge e = new Edge();
                    e.a = Math.min(i, j); e.b = Math.max(i, j);
                    Chamber b = n.ch.get(j);
                    e.len = Math.max(6, gapXZ(a, b, 0.8));
                    e.dy = Math.abs(a.f0 - b.f0);
                    e.cost = (e.len + 2.2 * e.dy) * (0.75 + 0.5 * Hashing.unit(Hashing.mix(h, 900, e.a, e.b)));
                    if (!contains(out, e.a, e.b)) out.add(e);
                }
            }
            out.sort(Comparator.comparingDouble(e -> e.cost));
            return out;
        }

        boolean contains(List<Edge> l, int a, int b) {
            int lo = Math.min(a, b), hi = Math.max(a, b);
            for (Edge e : l) if (e.a == lo && e.b == hi) return true;
            return false;
        }

        boolean allowed(Chamber a, Chamber b) {
            if (a.refuge && a.deg > 0) return false;
            if (b.refuge && b.deg > 0) return false;
            if (a.refuge && b.refuge) return false;
            if ((a.refuge && b.landing()) || (b.refuge && a.landing())) return false;
            if ((a.refuge && b.kind == Kind.PORTAL) || (b.refuge && a.kind == Kind.PORTAL)) return false;
            if ((a.refuge && b.court) || (b.refuge && a.court)) return false;
            return true;
        }

        void kruskal(List<Edge> cand) {
            for (Edge e : cand) {
                Chamber a = n.ch.get(e.a), b = n.ch.get(e.b);
                if (find(e.a) == find(e.b)) continue;
                if (!allowed(a, b)) continue;
                if (embed(e, false)) {
                    e.tree = true;
                    union(e.a, e.b);
                }
            }
        }

        /**
         * Required nodes (landings, portals) not yet joined to the entry: try harder, with longer meanders. A node that
         * failed is not tried again at the same level, so one hopeless node does not use up the passes of the others.
         * In the Blue Reaches the last 10 of the 40 passes may cross other deep dark caverns to reach a portal.
         */
        void connectRequired(List<Edge> cand) {
            int passes = n.dark != null ? 40 : 4;
            java.util.Set<Integer> failed = new java.util.HashSet<>();
            for (int pass = 0; pass < passes; pass++) {
                if (n.dark != null && pass == passes - 10) failed.clear();
                int N = n.ch.size();
                int[] size = new int[N];
                for (Chamber c : n.ch) if (c.keep) size[find(c.id)]++;
                int main = -1;
                for (int i = 0; i < N; i++) if (main < 0 || size[i] > size[main]) main = i;
                boolean all = true, tried = false;
                for (Chamber c : n.ch) {
                    if (!c.required || find(c.id) == main) continue;
                    all = false;
                    if (failed.contains(c.id)) continue;
                    tried = true;
                    // the last passes let a portal's or a landing's tunnel bridge deep dark caverns: in a short Blue
                    // Reaches (config) the lower tier can box the exit's courts in from above
                    relaxDark = n.dark != null && pass >= passes - 10 && (c.kind == Kind.PORTAL || c.landing() || c.court);
                    boolean joined = false;
                    int comp = find(c.id);
                    // the closest pairs between this component and the main one
                    List<int[]> pairs = new ArrayList<>();
                    for (Chamber a : n.ch) {
                        if (!a.keep || find(a.id) != comp) continue;
                        for (Chamber b : n.ch) {
                            if (!b.keep || b.refuge || find(b.id) != main) continue;
                            pairs.add(new int[]{a.id, b.id});
                        }
                    }
                    pairs.sort(Comparator.comparingDouble(p -> {
                        Chamber a = n.ch.get(p[0]), b = n.ch.get(p[1]);
                        return Math.hypot(a.x - b.x, a.z - b.z) + 2.5 * Math.abs(a.f0 - b.f0);
                    }));
                    for (int k = 0; k < Math.min(40, pairs.size()); k++) {
                        Edge e = new Edge();
                        e.a = Math.min(pairs.get(k)[0], pairs.get(k)[1]); e.b = Math.max(pairs.get(k)[0], pairs.get(k)[1]);
                        Chamber oa = n.ch.get(e.a), ob = n.ch.get(e.b);
                        if (!allowed(oa, ob) || alreadyEdge(e.a, e.b)) continue;
                        e.len = Math.max(6, Math.hypot(oa.x - ob.x, oa.z - ob.z) - 0.8 * (oa.R() + ob.R()));
                        e.dy = Math.abs(oa.f0 - ob.f0);
                        if (embed(e, true)) {
                            e.tree = true; e.forced = true; n.forced++;
                            union(e.a, e.b);
                            joined = true;
                            break;
                        }
                    }
                    relaxDark = false;
                    if (!joined) failed.add(c.id);
                    break; // components changed: recount
                }
                if (all || !tried) {
                    if (all || n.dark == null || pass >= passes - 10) break;
                    pass = passes - 11; // nothing left to try at this level: go on to the relaxed passes
                }
            }
            // the entry must be in the main component: everything else is measured from it
        }

        void dropOrphans() {
            int root = find(n.entry);
            if (find(n.exit) != root) {
                // keep whichever holds the landings; the probe reports the failure
                n.forced += 1000;
            }
            for (Chamber c : n.ch) {
                if (find(c.id) != root && c.kind != Kind.CANYON) {
                    c.keep = false;
                    n.droppedChambers++;
                }
            }
            // remove edges (and their ways) of dropped components
            List<Edge> keep = new ArrayList<>();
            for (Edge e : n.edges) {
                if (n.ch.get(e.a).keep && n.ch.get(e.b).keep) keep.add(e);
                else {
                    if (e.w1 != null) n.ways.remove(e.w1);
                    if (e.w2 != null) n.ways.remove(e.w2);
                    for (Way l : e.ledges) n.ways.remove(l);
                    if (e.sh != null) n.shafts.remove(e.sh);
                    for (Shaft sh : e.stages) n.shafts.remove(sh);
                }
            }
            n.edges.clear();
            n.edges.addAll(keep);
            rebuildPointIndex();
        }

        /**
         * A portal vestibule nothing could reach is gone (dropOrphans) and its stub goes with it. In its place a blind
         * end runs 10 blocks in from the seam along the stub's line and tapers shut, so the neighbour's half of the
         * stub ends in a short dead end rather than a flat wall.
         */
        void portalFallback() {
            boolean any = false;
            for (int id : n.portals) {
                Chamber c = n.ch.get(id);
                if (c.keep) continue;
                n.droppedPortals.add(id);
                Network.Portal p = portalOf.get(id);
                Way stub = null;
                for (Way wy : n.ways) if (wy.kind == EK.PORTAL && (wy.a == id || wy.b == id)) stub = wy;
                if (stub != null) n.ways.remove(stub);
                any = true;
                if (p == null) continue;
                // the stub's points from 2 past the seam to 11 in (the middle point is on the seam), seam first
                boolean mine = p.cellA(cx, cz);
                int mid = (p.stub.n - 1) / 2, m = 7;
                Way be = new Way();
                be.kind = EK.STUB;
                be.taper = true;
                alloc(be, m);
                for (int i = 0; i < m; i++) {
                    int j = mine ? mid + 1 - i : mid - 1 + i;
                    double q = i / (double) (m - 1);
                    double k = q < 0.5 ? 1 : 1 - (q - 0.5) / 0.5 * 0.75;
                    be.x[i] = p.stub.x[j]; be.z[i] = p.stub.z[j]; be.f[i] = p.stub.f[j];
                    be.w[i] = p.stub.w[j] * k; be.hgt[i] = p.stub.hgt[j] * k; be.slab[i] = p.stub.slab[j];
                }
                be.bounds();
                n.ways.add(be);
            }
            if (any) rebuildPointIndex();
        }

        /**
         * Entry to exit by walking alone (no climbs): while no such way exists, a walking tunnel is added between what
         * the entry reaches on foot and what the exit does, the shortest feasible tunnels first (a walk needs length
         * for its height); in the Blue Reaches, when none embeds, the same again with tunnels that may cross deep dark
         * caverns (as connectRequired's last passes do: the lower tier can box the exit's courts in, and once climbs
         * fit, a court's only other ways may be climbs up into a cavern beside it); when none embeds either, between
         * what the entry reaches and any other space, so a later round can go on from there. Up to 6 rounds. A pair
         * already joined by a climb may get a walking tunnel beside it.
         */
        void walkWays() {
            for (int it = 0; it < 6; it++) {
                if (NetGraph.maxFlow(n, n.entry, n.exit, true) >= 1) return;
                boolean[] S = NetGraph.reach(n, n.entry, true), X = NetGraph.reach(n, n.exit, true);
                boolean added = walkAcross(S, X, true);
                if (!added && n.dark != null) {
                    relaxDark = true;
                    added = walkAcross(S, X, true);
                    relaxDark = false;
                }
                if (!added) added = walkAcross(S, X, false);
                if (!added) return;
            }
        }

        boolean walkAcross(boolean[] S, boolean[] X, boolean toExit) {
            List<int[]> pairs = new ArrayList<>();
            List<Double> need = new ArrayList<>();
            for (Chamber a : n.ch) {
                if (!a.keep || !S[a.id] || a.refuge || a.landing() || a.kind == Kind.POCKET || a.kind == Kind.CANYON) continue;
                for (Chamber b : n.ch) {
                    if (!b.keep || S[b.id] || (toExit && !X[b.id]) || b.refuge || b.landing() || b.kind == Kind.POCKET || b.kind == Kind.CANYON) continue;
                    double gap = gapXZ(a, b, 1.0), dy = Math.abs(walkF(a) - walkF(b));
                    if (gap > (n.dark != null ? 240 : 170)) continue;
                    // a walking tunnel winds to at most 2.2 times the straight way: skip what cannot be walked
                    if (dy > sp.maxSlope * (Math.max(0, gap) * 2.2 - 8) + 4) continue;
                    pairs.add(new int[]{a.id, b.id, pairs.size()});
                    need.add(Math.max(gap, dy / sp.maxSlope));
                }
            }
            pairs.sort(Comparator.comparingDouble(p -> need.get(p[2])));
            boolean added = false;
            noClimb = true;
            for (int k = 0; k < Math.min(60, pairs.size()) && !added; k++) {
                Edge e = edge(pairs.get(k)[0], pairs.get(k)[1]);
                if (!allowed(n.ch.get(e.a), n.ch.get(e.b))) continue;
                if (embed(e, false) || (k < 20 && embed(e, true))) { e.loop = true; e.gate = toExit; n.walkRepairs++; added = true; }
            }
            noClimb = false;
            return added;
        }

        void rebuildPointIndex() {
            for (List<int[]> l : pts) l.clear();
            for (int i = 0; i < n.ways.size(); i++) n.ways.get(i).id = i;
            // shafts are renumbered too: the column index looks them up by id (a dropped climb used to shift them)
            for (int i = 0; i < n.shafts.size(); i++) n.shafts.get(i).id = i;
            for (Way wy : n.ways) indexWay(wy);
        }

        /** Extra edges that close long loops: the tree's way round between their ends is much longer than the edge. */
        void loops(List<Edge> cand) {
            int V = 0;
            for (Chamber c : n.ch) if (c.keep) V++;
            int target = (int) (V * sp.loopShare);
            int added = 0;
            for (Edge e : cand) {
                if (added >= target) break;
                if (e.tree || alreadyEdge(e.a, e.b)) continue;
                Chamber a = n.ch.get(e.a), b = n.ch.get(e.b);
                if (!a.keep || !b.keep || !allowed(a, b)) continue;
                if (a.refuge || b.refuge) continue;
                if (Hashing.unit(Hashing.mix(h, 950, e.a, e.b)) > sp.loopP) continue;
                double via = graphDist(e.a, e.b);
                if (via < 2.2 * (e.len + 2 * e.dy)) continue;
                if (embed(e, false)) { e.loop = true; added++; }
            }
            n.loopsAdded = added;
        }

        boolean alreadyEdge(int a, int b) {
            for (Edge e : n.edges) if ((e.a == a && e.b == b) || (e.a == b && e.b == a)) return true;
            return false;
        }

        /**
         * Two or three ways to each gate: the courts gave each landing its doors; here the network between them is
         * checked with a max-flow and, while it is short, a tunnel is added across the minimum cut.
         */
        void gateWays() {
            int want = Math.max(2, Math.min(n.courtsE.size(), n.courtsX.size()));
            for (int it = 0; it < 12; it++) {
                int f = NetGraph.maxFlow(n, n.entry, n.exit, false);
                if (f >= want) break;
                boolean[] S = NetGraph.minCutSide(n, n.entry, n.exit, false);
                List<int[]> pairs = new ArrayList<>();
                for (Chamber a : n.ch) {
                    if (!a.keep || !S[a.id] || a.refuge || a.landing() || a.kind == Kind.POCKET || a.kind == Kind.CANYON) continue;
                    for (Chamber b : n.ch) {
                        if (!b.keep || S[b.id] || b.refuge || b.landing() || b.kind == Kind.POCKET || b.kind == Kind.CANYON) continue;
                        if (gapXZ(a, b, 1.0) > (n.dark != null ? 240 : 170)) continue;
                        if (alreadyEdge(a.id, b.id)) continue;
                        pairs.add(new int[]{a.id, b.id});
                    }
                }
                pairs.sort(Comparator.comparingDouble(p -> {
                    Chamber a = n.ch.get(p[0]), b = n.ch.get(p[1]);
                    return gapXZ(a, b, 1.0) + 2.5 * Math.abs(a.f0 - b.f0);
                }));
                boolean added = false;
                for (int k = 0; k < Math.min(n.dark != null ? 120 : 60, pairs.size()) && !added; k++) {
                    Edge e = edge(pairs.get(k)[0], pairs.get(k)[1]);
                    if (!allowed(n.ch.get(e.a), n.ch.get(e.b))) continue;
                    if (embed(e, false) || (k < 20 && embed(e, true))) { e.loop = true; e.gate = true; n.cutRepairs++; added = true; }
                }
                if (!added && n.dark != null) {
                    // Blue Reaches: a last try that lets the tunnel cross other deep dark caverns (it bridges them)
                    relaxDark = true;
                    for (int k = 0; k < Math.min(60, pairs.size()) && !added; k++) {
                        Edge e = edge(pairs.get(k)[0], pairs.get(k)[1]);
                        if (!allowed(n.ch.get(e.a), n.ch.get(e.b))) continue;
                        if (embed(e, true)) { e.loop = true; e.gate = true; n.cutRepairs++; added = true; }
                    }
                    relaxDark = false;
                }
                if (!added) added = landingSpoke(S);
                if (!added) break;
            }
        }

        /**
         * The last resort of gateWays: a court can be boxed in (high under the band with every neighbour far below, its
         * doors taken), so nothing on its side of the cut embeds. Then the landing on that side gets one more spoke, a
         * wide walking tunnel like the courts' own, to the nearest space across the cut.
         */
        boolean landingSpoke(boolean[] S) {
            List<int[]> spokes = new ArrayList<>();
            for (int L : new int[]{n.entry, n.exit}) {
                Chamber l = n.ch.get(L);
                if (!l.keep) continue;
                for (Chamber b : n.ch) {
                    if (!b.keep || S[b.id] == S[L] || b.refuge || b.landing() || b.kind == Kind.POCKET || b.kind == Kind.CANYON || b.kind == Kind.PORTAL) continue;
                    if (gapXZ(l, b, 1.0) > 120 || alreadyEdge(L, b.id)) continue;
                    spokes.add(new int[]{L, b.id});
                }
            }
            spokes.sort(Comparator.comparingDouble(p -> {
                Chamber a = n.ch.get(p[0]), b = n.ch.get(p[1]);
                return gapXZ(a, b, 1.0) + 2.5 * Math.abs(a.f0 - b.f0);
            }));
            boolean added = false;
            noClimb = true;
            widthMul = 1.2;
            for (int k = 0; k < Math.min(40, spokes.size()) && !added; k++) {
                Edge e = edge(spokes.get(k)[0], spokes.get(k)[1]);
                if (!allowed(n.ch.get(e.a), n.ch.get(e.b))) continue;
                if (embed(e, false) || embed(e, true)) { e.loop = true; e.gate = true; n.cutRepairs++; added = true; }
            }
            noClimb = false;
            widthMul = 1;
            return added;
        }
        boolean relaxDark, surgeNiche;

        /**
         * Basalt Galleries: niches above the highest surge right by the bedrock landing, each reached by a vine shaft
         * from the landing's bowl, so that wherever the surge can reach there is somewhere close to climb to.
         */
        void surgeNiches() {
            if (li != Terrain.BASALT) return;
            Chamber L = null;
            for (Chamber c : n.ch) if (c.keep && c.kind == Kind.BEDROCK) L = c;
            if (L == null) return;
            int made = 0;
            for (int k = 0; k < 48 && made < 3; k++) {
                double a = r(990 + k, 0, Math.PI * 2);
                Chamber pk = new Chamber();
                pk.kind = Kind.POCKET;
                pk.rx = pk.rz = r(991 + k, 4.5, 6);
                pk.h = 7; pk.rise = 0; pk.terr = 1; pk.warp = 0.15; pk.pend = 1.5; pk.parent = L.id;
                // the climb is two stages (section 10): the floor (f0 to f0 + 2) stays within two stages of the
                // landing's and its lowest feet are a block over the highest surge; the pocket stands far enough out
                // for a short tunnel to the first stage, the ledge passage and the tunnel on from the second stage's top
                pk.f0 = W + Terrain.SURGE_RISE + 0.75;
                double d = L.R() + pk.R() + r(992 + k, 10, 18);
                pk.x = L.x + Math.cos(a) * d; pk.z = L.z + Math.sin(a) * d;
                pk.hash = Hashing.mix(h, 993, k, 0);
                L.keep = false; // the niche sits just over the landing's rim: only the other spaces count
                boolean fl = fitsLoose(pk, L);
                L.keep = true;
                if (!fl || wayHit(pk)) continue;
                add(pk);
                Edge e = edge(L.id, pk.id);
                surgeNiche = true; preferClimb = true;
                boolean ok = embed(e, true);
                surgeNiche = false; preferClimb = false;
                if (ok) { e.stub = false; made++; } else pk.keep = false;
            }
            n.surgeNiches = made;
        }

        /** Blind side passages: dead ends that pinch out or end in a small pocket. */
        void stubs() {
            int added = 0;
            List<Chamber> list = new ArrayList<>(n.ch);
            for (Chamber c : list) {
                if (!c.keep || c.landing() || c.refuge || c.kind == Kind.PORTAL || c.kind == Kind.CANYON || c.helix() || c.dark >= 0) continue;
                if (Hashing.unit(Hashing.mix(h, 970, c.id, 0)) > sp.stubP) continue;
                for (int t = 0; t < 4; t++) {
                    double th = Hashing.unit(Hashing.mix(h, 971, c.id, t)) * Math.PI * 2;
                    double len = 16 + 22 * Hashing.unit(Hashing.mix(h, 972, c.id, t));
                    double[] d = door(c, th, c.f0);
                    if (d == null || !doorFree(c.id, Math.atan2(d[1] - c.z, d[0] - c.x))) continue;
                    double ex = d[0] + d[3] * len, ez = d[1] + d[4] * len;
                    boolean pocket = Hashing.unit(Hashing.mix(h, 973, c.id, t)) < 0.5;
                    double ey = d[2] + (Hashing.unit(Hashing.mix(h, 974, c.id, t)) - 0.5) * len * 0.5;
                    // no blind end runs under water, as no tunnel does (its door is at W + 1 or higher); Basalt
                    // Galleries: no blind end or pocket under the highest surge
                    ey = Math.max(ey, li == Terrain.BASALT ? W + Terrain.SURGE_RISE + 2 : W + 1);
                    Chamber pk = null;
                    if (pocket) {
                        pk = new Chamber();
                        pk.kind = Kind.POCKET;
                        double pr = 4 + 3 * Hashing.unit(Hashing.mix(h, 975, c.id, t));
                        pk.x = ex + d[3] * pr * 0.6; pk.z = ez + d[4] * pr * 0.6; pk.rx = pk.rz = pr; pk.f0 = ey; pk.h = 6 + pr * 0.5;
                        pk.rise = 1; pk.terr = 1; pk.warp = 0.2; pk.parent = c.id; pk.pend = 1.5;
                        pk.hash = Hashing.mix(h, 976, c.id, t);
                        if (!fitsLoose(pk, c)) continue;
                    }
                    // the end's direction is the far space's outward normal (pointing back at c), as for any way's end;
                    // with d's own direction the line overshot by 5 blocks and curled back, and the floor reached the
                    // pocket's level only after its rim (v10: a 2-block step left one pocket unreachable)
                    double[] end = {ex, ez, ey, -d[3], -d[4]};
                    Way wy = walkWay(d, end, Hashing.mix(h, 977, c.id, t), sp.half * 0.8, sp.hgt * 0.85, false, false);
                    if (wy == null) continue;
                    wy.kind = EK.STUB;
                    wy.taper = !pocket;
                    if (wy.taper) {
                        for (int i = 0; i < wy.n; i++) {
                            double q = i / (double) (wy.n - 1);
                            double k = q < 0.6 ? 1 : 1 - (q - 0.6) / 0.4 * 0.75;
                            wy.w[i] *= k; wy.hgt[i] *= k;
                        }
                    }
                    if (!clear(wy, c.id, pk == null ? -1 : -2)) continue;
                    if (pk != null) {
                        add(pk);
                        wy.b = pk.id;
                    }
                    wy.a = c.id;
                    addWay(wy);
                    useDoor(c.id, Math.atan2(d[1] - c.z, d[0] - c.x));
                    Edge e = new Edge();
                    e.a = c.id; e.b = pk != null ? pk.id : c.id; e.kind = EK.STUB; e.stub = true; e.w1 = wy; e.len = wy.length;
                    if (pk != null) { c.deg++; pk.deg++; }
                    n.edges.add(e);
                    added++;
                    break;
                }
            }
            n.stubsAdded = added;
        }

        /**
         * Blind round tubes round each Obsidian Tubes hub (section 5): two or three, 8 to 14 long, level and pinching
         * out, so the hub reads as a junction of tubes. Each is a way of its hub (an inner walk joins it), not an edge,
         * so the hub keeps its one tunnel.
         */
        void tubeStubs() {
            for (Chamber c : n.ch) {
                if (!c.keep || c.kind != Kind.TUBEHUB) continue;
                int want = 2 + (Hashing.unit(Hashing.mix(h, 990, c.id, 0)) < 0.5 ? 1 : 0), made = 0;
                for (int t = 0; t < 12 && made < want; t++) {
                    double th = Hashing.unit(Hashing.mix(h, 991, c.id, t)) * Math.PI * 2;
                    double[] d = door(c, th, c.f0);
                    if (d == null || !doorFree(c.id, Math.atan2(d[1] - c.z, d[0] - c.x))) continue;
                    Way wy = tubeStub(d, 8 + 6 * Hashing.unit(Hashing.mix(h, 992, c.id, t)));
                    if (!clear(wy, c.id, -1)) continue;
                    wy.a = c.id;
                    addWay(wy);
                    useDoor(c.id, Math.atan2(d[1] - c.z, d[0] - c.x));
                    made++;
                }
                n.tubeStubs += made;
            }
        }

        /** A straight, level round tube from 3 inside a hub's door out to {@code len} past it, pinching out at its end. */
        Way tubeStub(double[] d, double len) {
            double sx = d[0] - d[3] * 3, sz = d[1] - d[4] * 3, ex = d[0] + d[3] * len, ez = d[1] + d[4] * len;
            int m = Math.max(3, (int) Math.ceil((len + 3) / 2.0) + 1);
            Way wy = new Way();
            wy.kind = EK.STUB;
            wy.round = true;
            wy.taper = true;
            alloc(wy, m);
            for (int i = 0; i < m; i++) {
                double q = i / (double) (m - 1);
                double k = q < 0.55 ? 1 : 1 - (q - 0.55) / 0.45 * 0.75;
                wy.x[i] = sx + (ex - sx) * q;
                wy.z[i] = sz + (ez - sz) * q;
                wy.f[i] = d[2];
                wy.w[i] = sp.half * 0.9 * k;
                wy.hgt[i] = sp.hgt * 0.85 * k;
                wy.slab[i] = Math.max(1.5, Math.min(3.5, d[2] - (B + Terrain.SEAL + 2.5)));
            }
            wy.bounds();
            return wy;
        }

        /**
         * Basalt arches (section 5): one across every great hall and about a third of the galleries longer than 30, a
         * span of rock across the hall's short axis at a point along its long one, its feet sunk in the walls. The
         * crown stands 8 to 14 over the floor under it, at least 10 over every floor under its middle, and over
         * {@code W + 38}; a gallery too low for that gets none. Of four places tried, the first whose underside clears
         * every walk and tunnel of the hall by 7 is taken; a great hall takes the one that clears most.
         */
        void arches() {
            if (li != Terrain.BASALT) return;
            for (Chamber c : n.ch) {
                if (!c.keep) continue;
                boolean great = c.kind == Kind.GREAT;
                if (!great && !(c.kind == Kind.GALLERY && c.rx > 15 && Hashing.unit(Hashing.mix(h, 995, c.id, 0)) < 0.4)) continue;
                Arch best = null;
                int bestCuts = Integer.MAX_VALUE;
                for (int t = 0; t < 4 && bestCuts > 0; t++) {
                    Arch a = arch(c, t);
                    if (a == null) continue;
                    int cuts = archCuts(c, a);
                    if (cuts < bestCuts) { best = a; bestCuts = cuts; }
                }
                if (best != null && (great || bestCuts == 0)) n.arches.add(best);
            }
        }

        // ------------------------------------------------------------------------------------------- lava pools
        /**
         * Lava pools cut 1 deep into floors (the lead's list, 2026-10-29), planned last, when every way is known. Each
         * Basalt great hall gets a perched channel along its lowest terrace where one fits ({@link #channel}); each
         * blind tube of an Obsidian Tubes hub ends in lava over its last 3 blocks ({@link #tubeEnd}).
         */
        void lavaPools() {
            if (li != Terrain.BASALT || !POOLS) return;
            for (Chamber c : n.ch) if (c.keep && c.kind == Kind.GREAT) channel(c);
            for (Way wy : new ArrayList<>(n.ways))
                if (wy.kind == EK.STUB && wy.round && wy.a >= 0 && n.ch.get(wy.a).kind == Kind.TUBEHUB) tubeEnd(wy);
        }

        /** A great hall's channel: 24 to 40 long where that fits, 12 at the least. */
        static final double CHANNEL_MIN = 12;

        /**
         * A great hall's perched lava channel: 3 to 4 wide, 24 to 40 long (shorter down to 12 where the tread is
         * smaller), lying on the flat of the hall's lowest tread with its banks (the blocks beside the lava, kept as
         * rock level with it, open over them) too, the banks at least 0.6 w + 4 from every way's line (so it never
         * crosses a walk and needs no bridge), 3 blocks from the hall's pillars and 4 from its climbs, and with the
         * hall's floor 4 blocks round its ends, so the tread joins round them. Tried along the hall's long axis
         * first, then turning 15 degrees at a time; longest first, from centres nearest the tread's middle. None
         * when nothing fits: the lowest tread is a blob 15 to 25 across that the walk from the low door crosses, so
         * most halls have no room (measured in the Implemented section).
         */
        void channel(Chamber c) {
            double R = c.R() + 2;
            int x0 = (int) Math.floor(c.x - R), z0 = (int) Math.floor(c.z - R), N = (int) Math.ceil(2 * R) + 1;
            // per block: the tread it is on (-1 off a tread's flat or near the wall), and whether the hall's floor is there
            int[] tread = new int[N * N];
            boolean[] hall = new boolean[N * N];
            int kMin = Integer.MAX_VALUE;
            for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) {
                double px = x0 + i + 0.5, pz = z0 + j + 0.5, e = w.e(c, px, pz);
                hall[i * N + j] = e < 0.92;
                tread[i * N + j] = -1;
                if (e >= 0.85) continue;
                double q = (w.floorAt(c, e, px, pz) - c.f0) / 3.0;
                long k = Math.round(q);
                if (Math.abs(q - k) > 1e-6) continue;
                tread[i * N + j] = (int) k;
                kMin = Math.min(kMin, (int) k);
            }
            if (kMin == Integer.MAX_VALUE) return;
            // the lowest tread's blocks clear of every way, pillar and climb
            boolean[] ok = new boolean[N * N];
            double sx = 0, sz = 0;
            int cnt = 0;
            for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) {
                if (tread[i * N + j] != kMin) continue;
                double px = x0 + i + 0.5, pz = z0 + j + 0.5;
                if (!clearOfWays(px, pz) || !clearOfPillars(c, px, pz)) continue;
                ok[i * N + j] = true;
                sx += px; sz += pz; cnt++;
            }
            if (cnt == 0) return;
            double mx = sx / cnt, mz = sz / cnt;
            List<double[]> centres = new ArrayList<>();
            for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) if (ok[i * N + j]) centres.add(new double[]{x0 + i + 0.5, z0 + j + 0.5});
            centres.sort((a, b) -> Double.compare(Math.hypot(a[0] - mx, a[1] - mz), Math.hypot(b[0] - mx, b[1] - mz)));
            double half = r(9610 + c.id, 1.3, 1.9), want = r(9611 + c.id, 24, 40);
            int y = (int) Math.ceil(c.f0 + 3 * kMin - 1e-9) - 1;
            // never under the lava table, where the tread is the lava's bed anyway
            if (y < W + 2) return;
            // the drawn width first, then the narrowest (3 blocks) before a shorter length
            double[] halves = half > 1.35 ? new double[]{half, 1.3} : new double[]{half};
            for (double L = want; L >= CHANNEL_MIN; L -= 2) {
                for (double hw : halves) {
                    for (double[] q : centres) {
                        for (int a = 0; a < 12; a++) {
                            double th = c.rot + ((a & 1) == 0 ? 1 : -1) * ((a + 1) / 2) * Math.PI / 12;
                            double ux = Math.cos(th) * L * 0.5, uz = Math.sin(th) * L * 0.5;
                            Net.Pool p = new Net.Pool(q[0] - ux, q[1] - uz, q[0] + ux, q[1] + uz, hw, y);
                            p.channel = true;
                            p.chamber = c.id;
                            if (!channelFits(p, x0, z0, N, ok, hall)) continue;
                            n.pools.add(p);
                            return;
                        }
                    }
                }
            }
        }

        /** Every block of the channel and its banks is on the clear lowest tread, the hall's floor 4 round its ends. */
        boolean channelFits(Net.Pool p, int x0, int z0, int N, boolean[] ok, boolean[] hall) {
            double reach = p.bank() + 4;
            int i0 = (int) Math.floor(Math.min(p.ax, p.bx) - p.half - reach) - x0, i1 = (int) Math.ceil(Math.max(p.ax, p.bx) + p.half + reach) - x0;
            int j0 = (int) Math.floor(Math.min(p.az, p.bz) - p.half - reach) - z0, j1 = (int) Math.ceil(Math.max(p.az, p.bz) + p.half + reach) - z0;
            for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) {
                double d = p.dist(x0 + i + 0.5, z0 + j + 0.5);
                if (d > reach) continue;
                boolean in = i >= 0 && j >= 0 && i < N && j < N;
                if (d <= p.bank() + 0.5 ? !(in && ok[i * N + j]) : !(in && hall[i * N + j])) return false;
            }
            return true;
        }

        /** True when (x, z) is at least 0.6 w + 4 from every way's line (w its half-width there) at any height, 2 from a climb. */
        boolean clearOfWays(double x, double z) {
            for (Way o : n.ways) {
                if (x < o.minX - 12 || x > o.maxX + 12 || z < o.minZ - 12 || z > o.maxZ + 12) continue;
                for (int j = 0; j + 1 < o.n; j++) {
                    double d = segDistXZ(x, z, o.x[j], o.z[j], o.x[j + 1], o.z[j + 1]);
                    // the bank's blocks are checked by their centres: half a block more
                    if (d < 0.6 * Math.max(o.w[j], o.w[j + 1]) + 4.5) return false;
                }
            }
            for (Shaft s : n.shafts) if (Math.hypot(x - s.x, z - s.z) < s.r + 4) return false;
            return true;
        }

        boolean clearOfPillars(Chamber c, double x, double z) {
            for (int k = 0; k + 2 < c.pillars.length; k += 3) {
                double px = c.kind == Kind.TUBEHUB ? c.x + c.pillars[k] : c.pillars[k], pz = c.kind == Kind.TUBEHUB ? c.z + c.pillars[k + 1] : c.pillars[k + 1];
                if (Math.hypot(x - px, z - pz) < c.pillars[k + 2] * 1.65 + 3) return false;
            }
            return true;
        }

        /**
         * A blind tube's lava end: its last 3 blocks along the line, 3 blocks wide, cut into the floor's top block, the
         * walking line ending 3 blocks before it. The tube's rock holds the pool (its floor slab, and the rock round its
         * pinched end); when another space comes within reach of the pool or its bank at that level, the end gets a
         * magma floor instead ({@link Network#tubeStubEnd}).
         */
        void tubeEnd(Way wy) {
            int m = wy.n;
            double ex = wy.x[m - 1], ez = wy.z[m - 1], L = 0;
            for (int i = 1; i < m; i++) L += Math.hypot(wy.x[i] - wy.x[i - 1], wy.z[i] - wy.z[i - 1]);
            double dx = ex - wy.x[m - 2], dz = ez - wy.z[m - 2], dl = Math.max(1e-6, Math.hypot(dx, dz));
            int y = (int) Math.ceil(wy.f[m - 1] - 1e-9) - 1;
            Net.Pool p = new Net.Pool(ex - dx / dl * 3, ez - dz / dl * 3, ex, ez, 1.0, y);
            p.chamber = wy.a;
            p.way = wy.id;
            if (poolHeld(p, wy)) {
                n.pools.add(p);
                wy.walkTo = L - 6;
            } else {
                n.magmaEnds.add(new double[]{ex, y, ez});
            }
        }

        /** No other space (way, chamber, climb) opens within 2 blocks of a pool or its bank, at the pool's level. */
        boolean poolHeld(Net.Pool p, Way own) {
            double reach = p.bank() + 2;
            double lo = p.y - 1, hi = p.y + 1;
            double cx = (p.ax + p.bx) * 0.5, cz = (p.az + p.bz) * 0.5, R = p.len * 0.5 + p.half + reach;
            for (Way o : n.ways) {
                if (o == own || cx < o.minX - R || cx > o.maxX + R || cz < o.minZ - R || cz > o.maxZ + R) continue;
                for (int j = 0; j < o.n; j++) {
                    if (o.f[j] - o.slab[j] > hi + 1 || o.f[j] + o.hgt[j] < lo - 1) continue;
                    if (p.dist(o.x[j], o.z[j]) < o.w[j] * 1.85 + reach) return false;
                }
            }
            for (Chamber c : n.ch) {
                if (!c.keep || c.kind == Kind.CANYON || c.yHi() < lo - 1 || c.yLo() > hi + 1) continue;
                if (Math.hypot(cx - c.x, cz - c.z) > c.R() + R) continue;
                for (double t = 0; t <= 1; t += 0.25) {
                    double qx = p.ax + (p.bx - p.ax) * t, qz = p.az + (p.bz - p.az) * t;
                    if (w.e(c, qx, qz) < 1 + reach / Math.min(c.rx, c.rz)) return false;
                }
            }
            for (Shaft s : n.shafts) {
                if (s.y1 < lo - 1 || s.y0 > hi + 1) continue;
                if (p.dist(s.x, s.z) < s.r + reach) return false;
            }
            return true;
        }

        Arch arch(Chamber c, int t) {
            double at = ((t & 1) == 0 ? 1 : -1) * c.rx * (0.18 + 0.25 * Hashing.unit(Hashing.mix(h, 996, c.id, t)));
            double ux = c.cos, uz = c.sin, vx = -c.sin, vz = c.cos;
            double px = c.x + ux * at, pz = c.z + uz * at;
            // the hall's width there, wall to wall across
            double vPlus = wallAlong(c, px, pz, vx, vz), vMinus = wallAlong(c, px, pz, -vx, -vz);
            if (vPlus < 3 || vMinus < 3) return null;
            double mid = (vPlus - vMinus) * 0.5;
            Arch a = new Arch();
            a.chamber = c.id;
            a.x = px + vx * mid; a.z = pz + vz * mid;
            a.ux = ux; a.uz = uz; a.vx = vx; a.vz = vz;
            a.span = (vPlus + vMinus) * 0.5 + 3;
            a.halfAlong = 2.5 + 1.5 * Hashing.unit(Hashing.mix(h, 997, c.id, t));
            double fC = w.floorAt(c, w.e(c, a.x, a.z), a.x, a.z), fMax = fC;
            for (double v = -0.6 * a.span; v <= 0.6 * a.span; v += 1) {
                double qx = a.x + vx * v, qz = a.z + vz * v, e = w.e(c, qx, qz);
                if (e < 1) fMax = Math.max(fMax, w.floorAt(c, e, qx, qz));
            }
            a.deck = Math.max(Math.max(fC + 8 + 6 * Hashing.unit(Hashing.mix(h, 998, c.id, t)), fMax + 10), W + 38);
            if (a.deck > w.ceilAt(c, w.e(c, a.x, a.z), a.x, a.z) - 3) return null;
            return a;
        }

        /** Distance from (x, z) along (dx, dz) to the chamber's wall line. */
        double wallAlong(Chamber c, double x, double z, double dx, double dz) {
            double d = 0;
            while (d < 80 && w.e(c, x + dx * d, z + dz * d) < 1) d += 0.5;
            return d;
        }

        /** Points of the hall's ways (its tunnels near it and its inner walks) where the arch's underside comes within 7 of the floor. */
        int archCuts(Chamber c, Arch a) {
            int cuts = 0;
            for (Way wy : n.ways) {
                if (wy.a != c.id && wy.b != c.id) continue;
                for (int i = 0; i < wy.n; i++) {
                    double dx = wy.x[i] - a.x, dz = wy.z[i] - a.z;
                    double q = Math.abs(dx * a.vx + dz * a.vz) / a.span, along = Math.abs(dx * a.ux + dz * a.uz);
                    if (q >= 1 || along > a.halfAlong * (1 + 1.2 * q * q) + wy.w[i] + 1.3) continue;
                    double under = a.deck - 4 * q * q - (3 + 9 * q * q * q);
                    if (under < wy.f[i] + 7) cuts++;
                }
            }
            return cuts;
        }

        boolean fitsLoose(Chamber pk, Chamber parent) {
            double m = EDGE_MARGIN + pk.R() + 4;
            if (pk.x < x0 + m || pk.x > x0 + cell - m || pk.z < z0 + m || pk.z > z0 + cell - m) return false;
            if (pk.yLo() < B + 8 || pk.yHi() > T - 10) return false;
            for (Chamber o : n.ch) {
                if (!o.keep || o.kind == Kind.CANYON) continue;
                double d = Math.hypot(pk.x - o.x, pk.z - o.z);
                if (d > pk.R() + o.R() + 8) continue;
                if (pk.yHi() + 6 < o.yLo() || o.yHi() + 6 < pk.yLo()) continue;
                return false;
            }
            if (lavaHit(pk.x, pk.z, pk.R(), pk.yLo(), pk.yHi(), 8, 6)) return false;
            if (reserveHit(pk.x, pk.z, pk.R(), pk.yLo(), pk.yHi())) return false;
            return !geodeHit(pk.x, pk.z, pk.R(), pk.yLo(), pk.yHi(), GEODE_GAP, GEODE_GAP);
        }

        /** The trunk: the strands and the courts' spokes (already built wider). */
        void trunk() {
            for (Edge e : n.edges) {
                if (e.strand < 0 && !(e.gate && (n.ch.get(e.a).landing() || n.ch.get(e.b).landing()))) continue;
                e.trunk = true;
                for (Way wy : new Way[]{e.w1, e.w2}) if (wy != null) wy.trunk = true;
                for (Way wy : e.ledges) wy.trunk = true;
            }
        }

        void biomes() {
            for (Chamber c : n.ch) c.biome = w.biomeOf(li, c);
            for (Edge e : n.edges) {
                Chamber a = n.ch.get(e.a), b = n.ch.get(e.b);
                DeepBiome bio;
                if (a.refuge || b.refuge) bio = a.refuge ? a.biome : b.biome;
                else if (a.hunting && b.hunting) bio = a.biome;
                else bio = Terrain.routeBiome(li);
                for (Way wy : new Way[]{e.w1, e.w2}) if (wy != null) wy.biome = bio;
                for (Way wy : e.ledges) wy.biome = bio;
                if (e.sh != null) e.sh.biome = bio;
                for (Shaft sh : e.stages) sh.biome = bio;
            }
            for (Way wy : n.ways) if (wy.biome == null) wy.biome = wy.a >= 0 ? n.ch.get(wy.a).biome : wy.b >= 0 ? n.ch.get(wy.b).biome : Terrain.routeBiome(li);
            for (Shaft s : n.shafts) if (s.biome == null) s.biome = Terrain.routeBiome(li);
        }

        /** Walks inside each chamber from every door to its hub, so the doors stay joined whatever the floor does. */
        void innerWalks() {
            List<Way> extra = new ArrayList<>();
            for (Chamber c : n.ch) {
                if (!c.keep || c.landing() || c.kind == Kind.CANYON || c.kind == Kind.POCKET || c.dark >= 0) continue;
                List<double[]> doors = new ArrayList<>();
                for (Way wy : n.ways) {
                    if (wy.inner) continue;
                    if (wy.a == c.id) doors.add(new double[]{wy.x[0], wy.z[0], wy.f[0]});
                    if (wy.b == c.id) doors.add(new double[]{wy.x[wy.n - 1], wy.z[wy.n - 1], wy.f[wy.n - 1]});
                }
                for (Shaft s : n.shafts) {
                    // a shaft that opens in this chamber's floor: only a climb's last stage may, and only inside the
                    // chamber's footprint (a lower stage beside a lake, within its bounding radius but outside its
                    // shore, took a shore walk down to its own top and drained the lake into it)
                    if (s.stage != s.stages || w.e(c, s.x, s.z) >= 1) continue;
                    if (s.y1 > c.f0 - 2 && s.y1 < c.f0 + c.h) doors.add(new double[]{s.x, s.z, s.y1 - 3});
                }
                if (doors.size() == 0) continue;
                if (c.helix()) {
                    extra.add(helixWay(c));
                    continue;
                }
                double hx = c.x, hz = c.z, hf;
                if (c.kind == Kind.LAKE) {
                    // the hub is on the shore, and walks run round the lake, never across it
                    extra.addAll(shoreWalks(c, doors));
                    continue;
                }
                hf = w.floorAt(c, w.e(c, hx, hz), hx, hz);
                for (int k = 0; k < doors.size(); k++) {
                    double[] d = doors.get(k);
                    double tx = hx, tz = hz, tf = hf;
                    if (c.kind == Kind.TUBEHUB && c.pillars.length >= 3) {
                        // walks end on a ring round the pillar, never through it (section 4.5)
                        double px = c.x + c.pillars[0], pz = c.z + c.pillars[1], rr = c.pillars[2] + 2.5;
                        double dx = d[0] - px, dz = d[1] - pz, dl = Math.max(1e-6, Math.hypot(dx, dz));
                        tx = px + dx / dl * rr; tz = pz + dz / dl * rr;
                        tf = w.floorAt(c, w.e(c, tx, tz), tx, tz);
                    }
                    Way wy = straightWalk(d[0], d[1], d[2], tx, tz, tf, c, Hashing.mix(c.hash, 980, k, 0));
                    wy.inner = true;
                    wy.a = c.id;
                    wy.biome = c.biome;
                    extra.add(wy);
                }
            }
            for (Way wy : extra) addWay(wy);
        }

        /** A walk inside a chamber that follows its floor, eased so no step is more than a block. */
        Way straightWalk(double ax, double az, double af, double bx, double bz, double bf, Chamber c, long hh) {
            double L = Math.hypot(bx - ax, bz - az);
            int m = Math.max(2, (int) Math.ceil(L / 2.0) + 1);
            Way wy = new Way();
            wy.kind = EK.WALK;
            alloc(wy, m);
            for (int i = 0; i < m; i++) {
                double t = i / (double) (m - 1);
                wy.x[i] = ax + (bx - ax) * t;
                wy.z[i] = az + (bz - az) * t;
                double fl = i == 0 ? af : (i == m - 1 ? bf : w.floorAt(c, w.e(c, wy.x[i], wy.z[i]), wy.x[i], wy.z[i]));
                wy.f[i] = fl;
                wy.w[i] = 2.2; wy.hgt[i] = 5; wy.slab[i] = 2.5;
            }
            easeFloor(wy, 0.85);
            wy.bounds();
            return wy;
        }

        /** Floor eased so it never rises or falls more than {@code max} per block between points. */
        void easeFloor(Way wy, double max) {
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 1; i < wy.n; i++) {
                    double ds = Math.hypot(wy.x[i] - wy.x[i - 1], wy.z[i] - wy.z[i - 1]);
                    double lim = max * ds;
                    if (wy.f[i] > wy.f[i - 1] + lim) wy.f[i] = wy.f[i - 1] + lim;
                    if (wy.f[i] < wy.f[i - 1] - lim) wy.f[i] = wy.f[i - 1] - lim;
                }
                for (int i = wy.n - 2; i >= 0; i--) {
                    double ds = Math.hypot(wy.x[i] - wy.x[i + 1], wy.z[i] - wy.z[i + 1]);
                    double lim = max * ds;
                    if (wy.f[i] > wy.f[i + 1] + lim) wy.f[i] = wy.f[i + 1] + lim;
                    if (wy.f[i] < wy.f[i + 1] - lim) wy.f[i] = wy.f[i + 1] - lim;
                }
            }
        }

        List<Way> shoreWalks(Chamber c, List<double[]> doors) {
            List<Way> out = new ArrayList<>();
            // order doors by angle, walk the shore ring between consecutive doors
            doors.sort(Comparator.comparingDouble(d -> Math.atan2(d[1] - c.z, d[0] - c.x)));
            int m = doors.size();
            for (int k = 0; k < m; k++) {
                if (m == 1) break;
                if (m == 2 && k == 1) break;
                double[] a = doors.get(k), b = doors.get((k + 1) % m);
                double ta = Math.atan2(a[1] - c.z, a[0] - c.x), tb = Math.atan2(b[1] - c.z, b[0] - c.x);
                if (tb <= ta) tb += Math.PI * 2;
                int steps = Math.max(4, (int) ((tb - ta) * c.rx * 0.8 / 2.0));
                Way wy = new Way();
                wy.kind = EK.WALK;
                alloc(wy, steps + 1);
                for (int i = 0; i <= steps; i++) {
                    double t = ta + (tb - ta) * i / steps;
                    double rr = shoreRadius(c, t);
                    wy.x[i] = c.x + Math.cos(t) * rr;
                    wy.z[i] = c.z + Math.sin(t) * rr;
                    wy.f[i] = Math.max(c.lakeY + 1, w.floorAt(c, w.e(c, wy.x[i], wy.z[i]), wy.x[i], wy.z[i]));
                    wy.w[i] = 2.2; wy.hgt[i] = 5; wy.slab[i] = 3;
                }
                wy.f[0] = a[2];
                wy.f[steps] = b[2];
                easeFloor(wy, 0.85);
                wy.inner = true; wy.a = c.id; wy.biome = c.biome;
                wy.bounds();
                out.add(wy);
            }
            return out;
        }

        double shoreRadius(Chamber c, double t) {
            // radius along angle t where e is just outside the lake
            double lo = 0, hi = Math.max(c.rx, c.rz) * 1.3;
            double target = c.lakeE + 0.12;
            for (int i = 0; i < 20; i++) {
                double mid = (lo + hi) / 2;
                double e = w.e(c, c.x + Math.cos(t) * mid, c.z + Math.sin(t) * mid);
                if (e < target) lo = mid; else hi = mid;
            }
            return lo;
        }

        /** The spiral ledge of a well or chimney, from the top door down to the bottom. */
        Way helixWay(Chamber c) {
            double len = (c.helixTop - (c.f0 + 1)) / c.helixSlope;
            int m = Math.max(4, (int) (len / 2.5));
            Way wy = new Way();
            wy.kind = EK.WALK;
            alloc(wy, m + 1);
            for (int i = 0; i <= m; i++) {
                double s = len * i / m;
                double y = c.helixTop - s * c.helixSlope;
                double a = c.helixA0 + c.helixDir * s / c.helixR;
                wy.x[i] = c.x + Math.cos(a) * c.helixR;
                wy.z[i] = c.z + Math.sin(a) * c.helixR;
                wy.f[i] = y;
                wy.w[i] = 2.4; wy.hgt[i] = 5; wy.slab[i] = 3.5;
            }
            wy.inner = true; wy.a = c.id; wy.biome = c.biome;
            wy.bounds();
            return wy;
        }

        // ------------------------------------------------------------------------------------------- doors and ways
        boolean doorFree(int c, double th) {
            for (double a : doorAngles.get(c)) {
                double d = Math.abs(Math.atan2(Math.sin(th - a), Math.cos(th - a)));
                if (d < minDoorSep(n.ch.get(c))) return false;
            }
            return true;
        }

        double minDoorSep(Chamber c) {
            if (c.landing()) return Math.toRadians(46);
            if (c.helix()) return Math.toRadians(25);
            return Math.toRadians(32);
        }

        void useDoor(int c, double th) {
            double[] o = doorAngles.get(c);
            double[] nn = Arrays.copyOf(o, o.length + 1);
            nn[o.length] = th;
            doorAngles.set(c, nn);
        }

        /**
         * The door of chamber c facing angle th: {x, z, floor, outward x, outward z}. Wells and chimneys put the door
         * where their spiral passes at the height wanted; landings on their rim at their floor.
         */
        double[] door(Chamber c, double th, double wantY) {
            double ox = Math.cos(th), oz = Math.sin(th);
            if (c.landing()) {
                double rr = c.rx * 0.72;
                return new double[]{c.x + ox * rr, c.z + oz * rr, c.f0, ox, oz};
            }
            if (c.kind == Kind.CANYON) {
                if (c.doorDX * ox + c.doorDZ * oz < 0.25) return null;
                return new double[]{c.x + c.doorDX * 2, c.z + c.doorDZ * 2, c.f0, c.doorDX, c.doorDZ};
            }
            if (c.helix()) {
                // heights where the spiral passes angle th
                double a = (th - c.helixA0) * c.helixDir;
                a = ((a % (Math.PI * 2)) + Math.PI * 2) % (Math.PI * 2);
                double best = Double.NaN;
                for (int k = 0; k < 8; k++) {
                    double s = (a + Math.PI * 2 * k) * c.helixR;
                    double y = c.helixTop - s * c.helixSlope;
                    if (y < c.f0 + 1) break;
                    if (Double.isNaN(best) || Math.abs(y - wantY) < Math.abs(best - wantY)) best = y;
                }
                if (Double.isNaN(best)) return null;
                double rr = c.helixR;
                return new double[]{c.x + ox * rr, c.z + oz * rr, best, ox, oz};
            }
            double rr = c.R();
            for (int i = 0; i < 40; i++) {
                double e = w.e(c, c.x + ox * rr, c.z + oz * rr);
                if (e <= 0.78) break;
                rr *= 0.95;
            }
            double px = c.x + ox * rr, pz = c.z + oz * rr;
            // v10: the floor where the tunnel ends, three blocks in from the door (walkWay runs on past the door by
            // three blocks at the door's floor): there the inner walk starts, at the chamber's own floor, so no shelf
            // of tunnel slab is left standing over a floor that drops away inside the chamber
            double qx = px - ox * 3, qz = pz - oz * 3;
            double fl = w.floorAt(c, w.e(c, qx, qz), qx, qz);
            if (c.kind == Kind.LAKE) fl = Math.max(fl, c.lakeY + 1);
            fl = Math.max(fl, W + 1);
            return new double[]{px, pz, fl, ox, oz};
        }

        /** Builds and checks the ways of an edge; on success they are added. */
        boolean embed(Edge e, boolean force) {
            Chamber A = n.ch.get(e.a), B = n.ch.get(e.b);
            if (!A.keep || !B.keep) return false;
            double th = Math.atan2(B.z - A.z, B.x - A.x);
            for (int attempt = 0; attempt < (force ? 6 : 3); attempt++) {
                n.tries++;
                double ja = (Hashing.unit(Hashing.mix(h, 1100 + attempt, e.a, e.b)) - 0.5) * (0.5 + attempt * 0.35);
                double jb = (Hashing.unit(Hashing.mix(h, 1200 + attempt, e.a, e.b)) - 0.5) * (0.5 + attempt * 0.35);
                double[] da, db;
                if (A.dark >= 0) {
                    double tx = B.x, tz = B.z;
                    if (B.dark >= 0) { List<double[]> ob = n.dark.nearestBoundary(region(B), A.x, A.z, 1); if (!ob.isEmpty()) { tx = ob.get(0)[0]; tz = ob.get(0)[1]; } }
                    da = darkDoor(A, tx, tz, attempt);
                    db = da == null ? null : (B.dark >= 0 ? darkDoor(B, da[0], da[1], attempt) : door(B, Math.atan2(da[1] - B.z, da[0] - B.x) + jb, A.f0));
                } else if (B.dark >= 0) {
                    db = darkDoor(B, A.x, A.z, attempt);
                    da = db == null ? null : door(A, Math.atan2(db[1] - A.z, db[0] - A.x) + ja, B.f0);
                } else {
                    da = door(A, th + ja, B.f0);
                    db = door(B, th + Math.PI + jb, A.f0);
                }
                if (da == null || db == null) continue;
                if ((A.dark < 0 && !doorFree(A.id, Math.atan2(da[1] - A.z, da[0] - A.x))) || (B.dark < 0 && !doorFree(B.id, Math.atan2(db[1] - B.z, db[0] - B.x)))) continue;
                double L = Math.hypot(db[0] - da[0], db[1] - da[1]);
                double dy = Math.abs(db[2] - da[2]);
                long hh = Hashing.mix(h, 1300 + attempt, e.a, e.b);
                double half = widthMul * sp.half * (1 + sp.halfVar * (Hashing.unit(Hashing.mix(hh, 1, 0, 0)) * 2 - 1));
                double hgt = (1 + (widthMul - 1) * 0.5) * sp.hgt * (1 + sp.hgtVar * (Hashing.unit(Hashing.mix(hh, 2, 0, 0)) * 2 - 1));
                boolean landingEnd = (A.landing() && !(surgeNiche && A.kind == Kind.BEDROCK)) || (B.landing() && !(surgeNiche && B.kind == Kind.BEDROCK));
                boolean walkOK = dy <= sp.maxSlope * (L * (force ? 2.2 : 1.5) - 8);
                // v9: no climb into a refuge (its way takes the refuge's biome, and refuges hold no vines)
                boolean climbOK = !noClimb && !landingEnd && !A.refuge && !B.refuge && dy <= (preferClimb ? Math.max(32, sp.climbMax) : sp.climbMax) && dy >= 5 && L <= sp.climbL;
                if (walkOK && !(preferClimb && climbOK && dy >= 8)) {
                    Way wy = walkWay(da, db, hh, half, hgt, force, true);
                    if (wy == null) continue;
                    // the Obsidian Tubes' tunnel is a round tube (section 5)
                    wy.round = A.kind == Kind.TUBEHUB || B.kind == Kind.TUBEHUB;
                    if (!clear(wy, e.a, e.b)) { n.embedFail++; continue; }
                    wy.a = e.a; wy.b = e.b;
                    addWay(wy);
                    e.kind = EK.WALK;
                    e.w1 = wy;
                } else if (climbOK) {
                    if (!climb(e, da, db, hh, half, hgt)) { n.embedFail++; continue; }
                } else {
                    continue;
                }
                useDoor(A.id, Math.atan2(da[1] - A.z, da[0] - A.x));
                useDoor(B.id, Math.atan2(db[1] - B.z, db[0] - B.x));
                if (A.dark >= 0) { darkDoors.computeIfAbsent(A.id, k -> new ArrayList<>()).add(da); addWay(darkInnerWalk(A, da)); }
                if (B.dark >= 0) { darkDoors.computeIfAbsent(B.id, k -> new ArrayList<>()).add(db); addWay(darkInnerWalk(B, db)); }
                A.deg++;
                B.deg++;
                n.edges.add(e);
                return true;
            }
            return false;
        }

        /**
         * Stages of a climb are at most this tall, so a fall down one, even from the top of the rim round its mouth
         * (ceil(top) + 1 over the foot's ceil), is at most 15.
         */
        static final double STAGE_MAX = 12.5;

        /**
         * A climb from the lower door up to the upper one (section 10): a level tunnel to the first stage's foot, stages
         * of at most {@link #STAGE_MAX} joined by ledge passages, and a level tunnel on from the last stage's top to the
         * upper door. Stage axes stand {@code 2 r + 3} to {@code 2 r + 5} apart along the climb's heading, each next one
         * turned by up to 45 degrees where that is what fits, so the holes never overlap and a fall ends on the floor of
         * the stage it fell down. The climb is checked as a whole, against the network and against itself; on success
         * its ways and shafts are added and the edge is filled in.
         */
        boolean climb(Edge e, double[] da, double[] db, long hh, double half, double hgt) {
            boolean aLow = da[2] < db[2];
            double[] lo = aLow ? da : db, hi = aLow ? db : da;
            int cLo = aLow ? e.a : e.b, cHi = aLow ? e.b : e.a;
            double L = Math.hypot(hi[0] - lo[0], hi[1] - lo[1]);
            double dy = hi[2] - lo[2];
            int ns = Math.max(1, (int) Math.ceil(dy / STAGE_MAX - 1e-9));
            if (ns > 4) return false;
            double sh = dy / ns;
            double rad = 2.6 + 0.8 * Hashing.unit(Hashing.mix(hh, 4, 0, 0));
            double gap = 2 * rad + 3 + 2 * Hashing.unit(Hashing.mix(hh, 5, 0, 0));
            double th0 = Math.atan2(hi[1] - lo[1], hi[0] - lo[0]);
            double ux = Math.cos(th0), uz = Math.sin(th0);
            // the first stage stands on the line between the doors, the chain of stages centred where the single
            // shaft would stand
            double d1 = L * (0.35 + 0.3 * Hashing.unit(Hashing.mix(hh, 3, 0, 0)));
            if (ns > 1) d1 = Math.max(6, Math.min(d1 - (ns - 1) * gap * 0.5, L - (ns - 1) * gap - 6));
            double sx = lo[0] + ux * d1, sz = lo[1] + uz * d1;
            // a single shaft's tunnel runs on 1.5 past its axis; a staged climb's ends at the first axis, so the second
            // stage keeps its 3 blocks of rock from the tunnel's end (the first stage's foot is the rock under its hole
            // either way, and the tunnel's slab reaches under the vines)
            double past = ns == 1 ? 1.5 : 0;
            double[] footEnd = {sx + ux * past, sz + uz * past, lo[2], -ux, -uz};
            Way w1 = climbTunnel(lo, footEnd, false, hh, half * 0.9, hgt);
            if (w1 == null || !clear(w1, e.a, e.b)) return false;
            List<Object> parts = new ArrayList<>();
            parts.add(w1);
            Shaft[] st = new Shaft[ns];
            Way[] ledges = new Way[ns - 1];
            st[0] = stage(sx, sz, rad, lo[2], lo[2] + sh, 1, ns);
            if (!stageFits(st[0], -1, ns == 1 ? -1 : cHi, parts, e.a, e.b)) return false;
            parts.add(st[0]);
            double heading = th0;
            Way w2 = null;
            for (int k = 1; k < ns; k++) {
                // stage k + 1 and the ledge passage to it from the top of stage k
                double sgn = Hashing.unit(Hashing.mix(hh, 40 + k, 0, 0)) < 0.5 ? 1 : -1;
                boolean placed = false;
                for (double turn : new double[]{0, sgn * Math.PI / 8, -sgn * Math.PI / 8, sgn * Math.PI / 4, -sgn * Math.PI / 4}) {
                    double hd = th0 + turn;
                    Shaft p = st[k - 1];
                    double qx = p.x + Math.cos(hd) * gap, qz = p.z + Math.sin(hd) * gap;
                    double fl = lo[2] + k * sh;
                    Way lg = ledgeWay(p.x, p.z, qx, qz, fl, hd);
                    if (!clear(lg, e.a, e.b) || !selfClear(lg, parts)) continue;
                    parts.add(lg);
                    Shaft s = stage(qx, qz, rad, fl, lo[2] + (k + 1) * sh, k + 1, ns);
                    Way top = null;
                    boolean ok = stageFits(s, cLo, k + 1 == ns ? -1 : cHi, parts, e.a, e.b);
                    if (ok) {
                        parts.add(s);
                        if (k + 1 == ns) {
                            // the last stage: the upper tunnel has to fit too, or another turn is tried
                            top = upperTunnel(s, hd, hi, hh, half, hgt);
                            ok = top != null && clear(top, e.a, e.b) && selfClear(top, parts);
                        }
                        if (!ok) parts.remove(parts.size() - 1);
                    }
                    if (!ok) { parts.remove(parts.size() - 1); continue; }
                    st[k - 1].vineA = hd;
                    st[k] = s;
                    ledges[k - 1] = lg;
                    heading = hd;
                    w2 = top;
                    placed = true;
                    break;
                }
                if (!placed) return false;
            }
            if (ns == 1) {
                w2 = upperTunnel(st[0], th0, hi, hh, half, hgt);
                if (w2 == null || !clear(w2, e.a, e.b) || !selfClear(w2, parts)) return false;
            }
            // the last stage's vines face the upper tunnel, which leaves along the last heading
            st[ns - 1].vineA = heading;
            for (Shaft s : st) Network.fixVines(s);
            w1.a = cLo; w1.b = -1;
            w2.a = -1; w2.b = cHi;
            addWay(w1);
            for (Way lg : ledges) { lg.a = -1; lg.b = -1; lg.ledge = true; addWay(lg); }
            addWay(w2);
            for (Shaft s : st) { s.id = n.shafts.size(); n.shafts.add(s); }
            e.kind = EK.CLIMB;
            e.w1 = w1; e.w2 = w2; e.sh = st[0];
            e.stages = st;
            e.ledges = ledges;
            return true;
        }

        Shaft stage(double x, double z, double r, double foot, double top, int k, int ns) {
            Shaft s = new Shaft();
            s.x = x; s.z = z; s.r = r;
            s.foot = foot; s.top = top;
            s.y0 = foot - 0.5; s.y1 = top + 3.5;
            s.stage = k; s.stages = ns;
            return s;
        }

        /**
         * The tunnel on from a climb's last stage: it starts just behind the axis (no further, so its walking core
         * leaves the rim behind the mouth standing) and runs out along the heading, then on to the upper door.
         */
        Way upperTunnel(Shaft s, double hd, double[] hi, long hh, double half, double hgt) {
            double ox = Math.cos(hd), oz = Math.sin(hd);
            double[] topStart = {s.x + ox * 2.5, s.z + oz * 2.5, hi[2], ox, oz};
            return climbTunnel(topStart, hi, true, Hashing.mix(hh, 9, 0, 0), half * 0.9, hgt);
        }

        /**
         * A climb's level tunnel between a and b ({x, z, floor, outward x, outward z}): a winding one when it is long,
         * a straight one under 16 blocks. A short one cannot wind: walkWay's handles reach 5 out of each end and 3 on
         * past it, so they cross, the line loops back on itself (and alongside its own chamber, which clear() refuses)
         * and the far end runs 3 blocks past b, into the next stage's rock when b is a stage's foot. The straight one
         * starts 3 blocks behind a, and runs 3 on past b only when b is a chamber's door ({@code intoB}).
         */
        Way climbTunnel(double[] a, double[] b, boolean intoB, long hh, double half, double hgt) {
            double L = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (L >= 16) return walkWay(a, b, hh, half, hgt, false, false);
            double sx = a[0] - a[3] * 3, sz = a[1] - a[4] * 3;
            double l0 = 3, l2 = intoB ? 3 : 0, tot = l0 + L + l2;
            double ex = b[0] - b[3] * l2, ez = b[1] - b[4] * l2;
            int m = Math.max(3, (int) Math.ceil(tot / 2.5) + 1);
            Way wy = new Way();
            wy.kind = EK.WALK;
            alloc(wy, m);
            Perlin pn = w.floorN;
            double off = (hh & 1023) * 0.37;
            for (int i = 0; i < m; i++) {
                double si = tot * i / (m - 1);
                if (si <= l0) { double t = si / l0; wy.x[i] = sx + (a[0] - sx) * t; wy.z[i] = sz + (a[1] - sz) * t; }
                else if (si <= l0 + L) { double t = (si - l0) / Math.max(1e-6, L); wy.x[i] = a[0] + (b[0] - a[0]) * t; wy.z[i] = a[1] + (b[1] - a[1]) * t; }
                else { double t = (si - l0 - L) / l2; wy.x[i] = b[0] + (ex - b[0]) * t; wy.z[i] = b[1] + (ez - b[1]) * t; }
                wy.f[i] = a[2];
                wy.w[i] = Math.max(2.6, half * (1 + 0.24 * pn.noise2(si / 17.0 + off, 3.3)));
                wy.hgt[i] = Math.max(6.5, hgt * (1 + 0.2 * pn.noise2(si / 21.0 + off, 9.9)));
                wy.hgt[i] = Math.max(5.0, Math.min(wy.hgt[i], T - Terrain.SEAL - 3.5 - wy.f[i]));
                wy.slab[i] = 3.5 + 1.5 * (0.5 + 0.5 * pn.noise2(si / 13.0 + off, 5.5));
                wy.slab[i] = Math.max(1.5, Math.min(wy.slab[i], wy.f[i] - (B + Terrain.SEAL + 2.5)));
            }
            wy.bounds();
            return wy;
        }

        /**
         * A ledge passage between two stages (section 10): straight and level at the lower stage's top, half-width 2.5,
         * 5 high, on a slab of 3. It starts half a block behind the lower stage's axis and ends 3 past the upper
         * stage's, so the upper stage's foot and its vines stand on its floor.
         */
        Way ledgeWay(double ax, double az, double bx, double bz, double fl, double hd) {
            double ox = Math.cos(hd), oz = Math.sin(hd);
            double sx = ax - ox * 0.5, sz = az - oz * 0.5, ex = bx + ox * 3, ez = bz + oz * 3;
            double len = Math.hypot(ex - sx, ez - sz);
            int m = Math.max(2, (int) Math.ceil(len / 2.0) + 1);
            Way wy = new Way();
            wy.kind = EK.WALK;
            alloc(wy, m);
            for (int i = 0; i < m; i++) {
                double t = i / (double) (m - 1);
                wy.x[i] = sx + (ex - sx) * t;
                wy.z[i] = sz + (ez - sz) * t;
                wy.f[i] = fl;
                wy.w[i] = 2.5;
                wy.hgt[i] = Math.max(4.0, Math.min(5.0, T - Terrain.SEAL - 3.5 - fl));
                wy.slab[i] = Math.max(1.5, Math.min(3.0, fl - (B + Terrain.SEAL + 2.5)));
            }
            wy.bounds();
            return wy;
        }

        /**
         * A stage keeps clear of the network (as a single shaft does, its own two rooms aside), of the climb's other
         * parts, and of the inside of its own rooms: only the first stage may open beside the lower room and only the
         * last into the upper one ({@code lo} or {@code hi} is -1 when that room is allowed).
         */
        boolean stageFits(Shaft s, int lo, int hi, List<Object> parts, int ca, int cb) {
            if (!clearShaft(s, ca, cb)) return false;
            for (int c : new int[]{lo, hi}) {
                if (c < 0) continue;
                Chamber k = n.ch.get(c);
                if (k.dark >= 0 || k.kind == Kind.CANYON) continue;
                double R = Math.min(k.rx, k.rz);
                if ((w.e(k, s.x, s.z) - 1) * R > s.r + 2) continue;
                if (s.y1 + 3 < k.yLo() || k.yHi() + 3 < s.y0) continue;
                return false;
            }
            return selfClear(s, parts);
        }

        /**
         * A new part of a climb against the parts already laid ({@code parts}, in order: lower tunnel, stage, ledge,
         * stage, ..., upper tunnel). Neighbours in that order meet by design, and so do two ways with one stage between
         * them, at that stage (one leaves its foot, the other its top); away from it they must not {@link #meet} (an
         * upper tunnel that turned back over the ledge under it, one stage height higher, had its floor cut by the
         * ledge's walking core); any other two must not meet at all.
         */
        boolean selfClear(Object x, List<Object> parts) {
            int m = parts.size();
            for (int i = 0; i < m; i++) {
                if (m - i == 1) continue;
                if (m - i == 2 && parts.get(i + 1) instanceof Shaft s) {
                    if (x instanceof Way p && parts.get(i) instanceof Way q && meetAway(p, q, s)) return false;
                    continue;
                }
                if (meet(x, parts.get(i))) return false;
            }
            return true;
        }

        /** {@link #meet} for two ways of a climb with stage {@code s} between them, leaving out where they reach it. */
        static boolean meetAway(Way p, Way q, Shaft s) {
            for (int i = 0; i < p.n; i++) {
                if (Math.hypot(p.x[i] - s.x, p.z[i] - s.z) < s.r + p.w[i] + 1) continue;
                for (int j = 0; j < q.n; j++) {
                    if (Math.hypot(q.x[j] - s.x, q.z[j] - s.z) < s.r + q.w[j] + 1) continue;
                    if (Math.hypot(p.x[i] - q.x[j], p.z[i] - q.z[j]) > p.w[i] + q.w[j] + 1) continue;
                    double gap = p.f[i] > q.f[j] ? p.f[i] - (q.f[j] + Math.max(q.hgt[j], 6.5)) : q.f[j] - (p.f[i] + Math.max(p.hgt[i], 6.5));
                    if (gap < 1.5) return true;
                }
            }
            return false;
        }

        /**
         * Two parts of one climb that are not neighbours in it. Holes keep 3 blocks of rock between them, so a fall ends
         * on the floor of the stage it fell down. A tunnel may pass close to a stage or another tunnel of its climb,
         * since the slabs between them stand (a slab is rock that must stay, which beats a tunnel's vault), but it may
         * not cut into a stage (its wall, or the floor at its foot, which would let a fall run on into the tunnel), and
         * the lower of two tunnels keeps 1.5 blocks of rock under the upper one's floor. A tunnel's open space reaches
         * its height or the walking core's 6.5, whichever is more. (Against the rest of the network a climb keeps the
         * clearances of {@link #clear} and {@link #clearShaft}; within it those 3 to 6 blocks refused most staged
         * climbs, whose stages stand 2 r + 3 to 2 r + 5 apart over tunnels 2.6 to 5.4 wide.)
         */
        static boolean meet(Object a, Object b) {
            if (a instanceof Shaft sa && b instanceof Shaft sb) {
                if (Math.hypot(sa.x - sb.x, sa.z - sb.z) >= sa.r + sb.r + 3 - 1e-6) return false;
                return !(sa.y1 + 2 < sb.y0 || sb.y1 + 2 < sa.y0);
            }
            if (a instanceof Shaft || b instanceof Shaft) {
                Shaft s = a instanceof Shaft ? (Shaft) a : (Shaft) b;
                Way o = a instanceof Shaft ? (Way) b : (Way) a;
                for (int j = 0; j < o.n; j++) {
                    if (Math.hypot(s.x - o.x[j], s.z - o.z[j]) > s.r + o.w[j] + 1) continue;
                    if (o.f[j] + Math.max(o.hgt[j], 6.5) + 1.5 < s.y0 || s.y1 + 1 < o.f[j]) continue;
                    return true;
                }
                return false;
            }
            Way p = (Way) a, q = (Way) b;
            for (int i = 0; i < p.n; i++) for (int j = 0; j < q.n; j++) {
                if (Math.hypot(p.x[i] - q.x[j], p.z[i] - q.z[j]) > p.w[i] + q.w[j] + 1) continue;
                double gap = p.f[i] > q.f[j] ? p.f[i] - (q.f[j] + Math.max(q.hgt[j], 6.5)) : q.f[j] - (p.f[i] + Math.max(p.hgt[i], 6.5));
                if (gap >= 1.5) continue;
                return true;
            }
            return false;
        }

        /**
         * A winding tunnel from door a to door b ({x, z, floor, outward x, outward z}), long enough for its descent at
         * the planning slope, terraced, with a width and height that swell and pinch.
         */
        Way walkWay(double[] a, double[] b, long hh, double half, double hgt, boolean force, boolean check) {
            double dy = Math.abs(b[2] - a[2]);
            double need = dy / sp.maxSlope + 10;
            double L = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double ux = (b[0] - a[0]) / Math.max(1e-6, L), uz = (b[1] - a[1]) / Math.max(1e-6, L);
            double nx = -uz, nz = ux;
            List<double[]> best = null;
            double bestLen = 0;
            for (int attempt = 0; attempt < (force ? 9 : 6); attempt++) {
                List<double[]> ctrl = new ArrayList<>();
                ctrl.add(new double[]{a[0] - a[3] * 3, a[1] - a[4] * 3});
                ctrl.add(new double[]{a[0], a[1]});
                ctrl.add(new double[]{a[0] + a[3] * 5, a[1] + a[4] * 5});
                int k = 1 + (L > 45 ? 1 : 0) + (L > 90 ? 1 : 0);
                double amp = (0.10 + 0.08 * Hashing.unit(Hashing.mix(hh, 20, 0, 0))) * (1 + 0.55 * attempt);
                double sign = Hashing.unit(Hashing.mix(hh, 21, 0, 0)) < 0.5 ? -1 : 1;
                for (int i = 0; i < k; i++) {
                    double t = (i + 1.0) / (k + 1) + (Hashing.unit(Hashing.mix(hh, 22 + i, 0, 0)) - 0.5) * 0.1;
                    double off = sign * amp * Math.max(L, 30) * (0.6 + 0.4 * Hashing.unit(Hashing.mix(hh, 30 + i, 0, 0)));
                    sign = -sign;
                    double px = a[0] + (b[0] - a[0]) * t + nx * off, pz = a[1] + (b[1] - a[1]) * t + nz * off;
                    double m = EDGE_MARGIN + half + 8;
                    px = Math.max(x0 + m, Math.min(x0 + cell - m, px));
                    pz = Math.max(z0 + m, Math.min(z0 + cell - m, pz));
                    ctrl.add(new double[]{px, pz});
                }
                ctrl.add(new double[]{b[0] + b[3] * 5, b[1] + b[4] * 5});
                ctrl.add(new double[]{b[0], b[1]});
                ctrl.add(new double[]{b[0] - b[3] * 3, b[1] - b[4] * 3});
                List<double[]> poly = catmull(ctrl, 2.5);
                double len = polyLen(poly);
                if (best == null || len > bestLen) { best = poly; bestLen = len; }
                if (len >= need) break;
            }
            if (bestLen < need - 0.5) return null;
            List<double[]> poly = best;
            int m = poly.size();
            Way wy = new Way();
            wy.kind = EK.WALK;
            alloc(wy, m);
            double[] s = new double[m];
            for (int i = 0; i < m; i++) {
                wy.x[i] = poly.get(i)[0];
                wy.z[i] = poly.get(i)[1];
                if (i > 0) s[i] = s[i - 1] + Math.hypot(wy.x[i] - wy.x[i - 1], wy.z[i] - wy.z[i - 1]);
            }
            double tot = s[m - 1];
            Perlin pn = w.floorN;
            double off = (hh & 1023) * 0.37;
            for (int i = 0; i < m; i++) {
                wy.f[i] = stepped(hh, s[i], 5, tot - 5, a[2], b[2]);
                double flare = Math.min(s[i], tot - s[i]) < 7 ? 1.25 : 1.0;
                wy.w[i] = Math.max(2.6, half * (1 + 0.24 * pn.noise2(s[i] / 17.0 + off, 3.3)) * flare);
                wy.hgt[i] = Math.max(6.5, hgt * (1 + 0.2 * pn.noise2(s[i] / 21.0 + off, 9.9)));
                wy.hgt[i] = Math.max(5.0, Math.min(wy.hgt[i], T - Terrain.SEAL - 3.5 - wy.f[i]));
                wy.slab[i] = 3.5 + 1.5 * (0.5 + 0.5 * pn.noise2(s[i] / 13.0 + off, 5.5));
                wy.slab[i] = Math.max(1.5, Math.min(wy.slab[i], wy.f[i] - (B + Terrain.SEAL + 2.5)));
            }
            wy.bounds();
            return wy;
        }

        /** Terraced descent from fa at s0 to fb at s1: steps of about 2.5 blocks, ramps never steeper than 0.85. */
        static double stepped(long hh, double s, double s0, double s1, double fa, double fb) {
            if (s <= s0) return fa;
            if (s >= s1) return fb;
            double drop = fb - fa, ad = Math.abs(drop), span = Math.max(1, s1 - s0);
            if (ad < 0.5) return fa + drop * (s - s0) / span;
            int steps = Math.max(1, (int) Math.round(ad / 2.5));
            double stepLen = span / steps, dj = ad / steps;
            double tread = Math.max(0, Math.min(0.5, 1 - 1.5 * dj / (Route.MAX_SLOPE * stepLen)));
            double u = (s - s0) / stepLen;
            int j = Math.min(steps - 1, (int) Math.floor(u));
            double local = u - j;
            double ramp = local <= tread ? 0 : Terrain.smooth((local - tread) / (1 - tread));
            return fa + Math.signum(drop) * dj * (j + ramp);
        }

        static List<double[]> catmull(List<double[]> way, double step) {
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i + 1 < way.size(); i++) {
                double[] p0 = way.get(Math.max(0, i - 1)), p1 = way.get(i), p2 = way.get(i + 1), p3 = way.get(Math.min(way.size() - 1, i + 2));
                double segLen = Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
                int steps = Math.max(1, (int) Math.ceil(segLen / step));
                for (int k = 0; k < steps; k++) {
                    double t = k / (double) steps;
                    pts.add(new double[]{Plans.catmull(p0[0], p1[0], p2[0], p3[0], t), Plans.catmull(p0[1], p1[1], p2[1], p3[1], t)});
                }
            }
            pts.add(way.get(way.size() - 1));
            return pts;
        }

        static double polyLen(List<double[]> p) {
            double l = 0;
            for (int i = 1; i < p.size(); i++) l += Math.hypot(p.get(i)[0] - p.get(i - 1)[0], p.get(i)[1] - p.get(i - 1)[1]);
            return l;
        }

        void alloc(Way wy, int m) {
            wy.n = m;
            wy.x = new double[m]; wy.z = new double[m]; wy.f = new double[m];
            wy.w = new double[m]; wy.hgt = new double[m]; wy.slab = new double[m];
        }

        void addWay(Way wy) {
            wy.id = n.ways.size();
            n.ways.add(wy);
            indexWay(wy);
        }

        void indexWay(Way wy) {
            for (int i = 0; i < wy.n; i++) {
                int b = bucket(wy.x[i], wy.z[i]);
                if (b >= 0) pts[b].add(new int[]{wy.id, i});
            }
        }

        int bucket(double x, double z) {
            int bx = (int) Math.floor((x - x0 + Net.APRON) / CB), bz = (int) Math.floor((z - z0 + Net.APRON) / CB);
            if (bx < 0 || bz < 0 || bx >= cn || bz >= cn) return -1;
            return bx * cn + bz;
        }

        /** True when the way keeps clear of every chamber but its own two, every other way and every shaft. */
        boolean clear(Way wy, int ca, int cb) {
            // v10: once a way has left one of its own chambers it may not come back alongside it (clear() used to
            // skip its own chambers entirely, so a steep tunnel could curl back past a chimney and cut its ledge)
            for (int c : new int[]{ca, cb}) {
                if (c < 0) continue;
                Chamber k = n.ch.get(c);
                if (k.dark >= 0 || k.kind == Kind.CANYON || k.landing()) continue;
                boolean fromStart = Math.hypot(wy.x[0] - k.x, wy.z[0] - k.z) <= Math.hypot(wy.x[wy.n - 1] - k.x, wy.z[wy.n - 1] - k.z);
                int i0 = fromStart ? 0 : wy.n - 1;
                double d0 = Math.hypot(wy.x[i0] - k.x, wy.z[i0] - k.z), arc = 0;
                for (int j = 1; j < wy.n; j++) {
                    int i = fromStart ? j : wy.n - 1 - j, ip = fromStart ? j - 1 : wy.n - j;
                    arc += Math.hypot(wy.x[i] - wy.x[ip], wy.z[i] - wy.z[ip]);
                    double d = Math.hypot(wy.x[i] - k.x, wy.z[i] - k.z), lim = k.R() + wy.w[i];
                    // past the straight way out (plus slack) and still beside the chamber, at its heights
                    if (arc > lim - d0 + 6 && d < lim - 1 && !(wy.f[i] + wy.hgt[i] + sp.clearCV < k.yLo() || k.yHi() + sp.clearCV < wy.f[i] - wy.slab[i])) return false;
                }
            }
            boolean cityDoor = city != null && (ca == city.id || cb == city.id);
            for (int i = 0; i < wy.n; i++) {
                double px = wy.x[i], pz = wy.z[i], pf = wy.f[i], pw = wy.w[i], ph = wy.hgt[i];
                // cell margin
                if (px < x0 + EDGE_MARGIN + pw || px > x0 + cell - EDGE_MARGIN - pw || pz < z0 + EDGE_MARGIN + pw || pz > z0 + cell - EDGE_MARGIN - pw) return false;
                if (pf - wy.slab[i] < B + Terrain.SEAL + 2 || pf + ph > T - Terrain.SEAL - 3) return false;
                if (n.dark != null && !relaxDark && (i & 1) == 0 && n.dark.hit(px, pz, pw + 2, pf - wy.slab[i] - 2, pf + ph + 2, darkOf(ca), darkOf(cb))) return false;
                for (Chamber c : n.ch) {
                    if (!c.keep) continue;
                    if (c.kind == Kind.CANYON || c.dark >= 0) continue;
                    double d = Math.hypot(px - c.x, pz - c.z);
                    if (c.id == ca || c.id == cb) continue;
                    if (d > c.R() + pw + sp.clearC) continue;
                    if (pf + ph + sp.clearCV < c.yLo() || c.yHi() + sp.clearCV < pf - wy.slab[i]) continue;
                    return false;
                }
                if (lavaHit(px, pz, pw + sp.clearC, pf - wy.slab[i], pf + ph, 0, sp.clearCV)) return false;
                if (!cityDoor && reserveHit(px, pz, pw, pf - wy.slab[i], pf + ph)) return false;
                if (geodeHit(px, pz, pw, pf - wy.slab[i], pf + ph, GEODE_GAP, 4)) return false;
                // ends near their own chambers are allowed to touch them
                int bx = (int) Math.floor((px - x0 + Net.APRON) / CB), bz = (int) Math.floor((pz - z0 + Net.APRON) / CB);
                int rb = pw + 4.5 + sp.clearH > CB ? 2 : 1;
                for (int dx = -rb; dx <= rb; dx++) for (int dz = -rb; dz <= rb; dz++) {
                    int qx = bx + dx, qz = bz + dz;
                    if (qx < 0 || qz < 0 || qx >= cn || qz >= cn) continue;
                    for (int[] q : pts[qx * cn + qz]) {
                        Way o = n.ways.get(q[0]);
                        if (o == wy) continue;
                        int j = q[1];
                        double d = Math.hypot(px - o.x[j], pz - o.z[j]);
                        if (d > pw + o.w[j] + sp.clearH) continue;
                        // two ways leaving the same chamber may share its mouth
                        if (sharesEnd(o, ca, cb) && nearOwnEnd(px, pz, ca, cb)) continue;
                        double gap = pf > o.f[j] ? pf - wy.slab[i] - (o.f[j] + o.hgt[j]) : o.f[j] - o.slab[j] - (pf + ph);
                        if (gap >= sp.clearV) continue;
                        return false;
                    }
                }
                for (Shaft s : n.shafts) {
                    double d = Math.hypot(px - s.x, pz - s.z);
                    if (d > s.r + pw + 3) continue;
                    if (pf + ph + 2 < s.y0 || s.y1 + 2 < pf - wy.slab[i]) continue;
                    return false;
                }
            }
            // the gates' passages
            for (int b = li + 1; b <= li + 2 && b <= w.shape.layerCount; b++) {
                Plans.Gate g = w.base.plans.gate(b, cx, cz);
                for (int i = 0; i < wy.n; i += 2) {
                    if (nearOwnEnd(wy.x[i], wy.z[i], ca, cb) && (n.ch.get(ca).landing() || (cb >= 0 && n.ch.get(cb).landing()))) continue;
                    for (double y = wy.f[i] - wy.slab[i]; y <= wy.f[i] + wy.hgt[i]; y += 3) {
                        if (g.passageXZ(wy.x[i], y, wy.z[i]) < wy.w[i] + g.radius * 1.5 + 4) return false;
                    }
                }
            }
            // the canyons (Blue): a way may only meet a canyon at its own canyon node
            for (Route r : n.canyons) {
                for (int i = 0; i < wy.n; i++) {
                    if (nearOwnEnd(wy.x[i], wy.z[i], ca, cb)) continue;
                    double[] o = w.riftOpen(r, wy.x[i], wy.z[i], wy.w[i] + 5);
                    if (o != null && wy.f[i] - wy.slab[i] - 3 < o[1] && wy.f[i] + wy.hgt[i] + 3 > o[0]) return false;
                }
            }
            return true;
        }

        boolean sharesEnd(Way o, int ca, int cb) {
            // ways that join no room (the Blue geode links) are no one's to keep clear of; a climb's ledges are
            return o.a == ca || o.b == ca || o.a == cb || o.b == cb || (o.a == -1 && o.b == -1 && !o.ledge);
        }

        boolean nearOwnEnd(double px, double pz, int ca, int cb) {
            for (int c : new int[]{ca, cb}) {
                if (c < 0) continue;
                Chamber k = n.ch.get(c);
                if (k.dark >= 0) { if (n.dark.near(region(k), px, pz, 9)) return true; continue; }
                if (Math.hypot(px - k.x, pz - k.z) < k.R() + 9) return true;
            }
            return false;
        }

        boolean clearShaft(Shaft s, int ca, int cb) {
            if (n.dark != null && n.dark.hit(s.x, s.z, s.r + 3, s.y0 - 2, s.y1 + 2, darkOf(ca), darkOf(cb))) return false;
            for (Chamber c : n.ch) {
                if (!c.keep || c.id == ca || c.id == cb || c.kind == Kind.CANYON || c.dark >= 0) continue;
                if (Math.hypot(s.x - c.x, s.z - c.z) > c.R() + s.r + 5) continue;
                if (s.y1 + 3 < c.yLo() || c.yHi() + 3 < s.y0) continue;
                return false;
            }
            if (lavaHit(s.x, s.z, s.r + 5, s.y0, s.y1, 0, 3)) return false;
            if (!(city != null && (ca == city.id || cb == city.id)) && reserveHit(s.x, s.z, s.r + 2, s.y0, s.y1)) return false;
            if (geodeHit(s.x, s.z, s.r, s.y0, s.y1, GEODE_GAP, 4)) return false;
            for (Way o : n.ways) {
                for (int j = 0; j < o.n; j++) {
                    if (Math.hypot(s.x - o.x[j], s.z - o.z[j]) > s.r + o.w[j] + 3) continue;
                    if (o.f[j] > s.y1 + 2 || o.f[j] + o.hgt[j] < s.y0 - 2) continue;
                    if (sharesEnd(o, ca, cb)) continue;
                    return false;
                }
            }
            return true;
        }

        double graphDist(int a, int b) {
            return NetGraph.dist(n, a, b);
        }
    }
}
