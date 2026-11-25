package com.explorationremix.world.terrain;

import com.explorationremix.world.terrain.Net.Chamber;
import com.explorationremix.world.terrain.Net.Shaft;
import com.explorationremix.world.terrain.Net.Way;

import static com.explorationremix.world.terrain.BlueRifts.HALF;
import static com.explorationremix.world.terrain.BlueRifts.STEP;
import static com.explorationremix.world.terrain.BlueRifts.TOP;

/**
 * The Blue Reaches' rifts as the network carves them (0.21.7): the noise rifts ({@link BlueRifts}) kept clear of the
 * network's rooms, climbs, landings, gates, geodes and city, bridged where its tunnels cross them, and passing under
 * the deep dark's pillars; the shelves of both kinds of rift, the Deep Blue Rift's included (Taran: "Keep it, add
 * shelves and ponds"); and the ponds on the shelves, about half of them spilling over the shelf's edge (Taran's cards:
 * shelves "Mixed, 4 to 16 blocks out", ponds that spill "About half").
 */
final class NetRifts {
    private NetRifts() {}

    // ================================================================================================ keeping clear
    /**
     * Where a noise rift shuts and where it is whole again, in blocks past each thing it keeps clear of: a room's wall
     * (at most half the room's radius past it), a climb's shaft, a landing's rim, the exit's passage, a geode's shell,
     * the city's reserve and the Deep Blue Rift's ledge path. Inside them the rift narrows, grows shallower and shuts,
     * as at its own ends.
     */
    static final double ROOM_SHUT = 2, ROOM_OPEN = 12, SHAFT_SHUT = 2, SHAFT_OPEN = 8, LANDING_SHUT = 6, LANDING_OPEN = 22,
            PASSAGE_SHUT = 5, PASSAGE_OPEN = 16, GEODE_SHUT = 3, GEODE_OPEN = 12, CITY_OPEN = 24, WALK_SHUT = Terrain.CORE + 3,
            WALK_OPEN = Terrain.CORE + 14;

    static double ease(double d, double shut, double open) { return Terrain.smooth((d - shut) / (open - shut)); }

    /** How much of a noise rift a column keeps: 1 where nothing is near, 0 where it must shut. */
    static double keep(Network w, Network.LCol lc, int cx, int cz, double x, double z) {
        double k = 1;
        for (int i = 0; i < lc.nc && k > 0; i++) {
            Chamber c = lc.c[i];
            double minR = Math.min(c.rx, c.rz);
            k *= ease((lc.e[i] - 1) * minR, ROOM_SHUT, Math.max(ROOM_SHUT + 1, Math.min(ROOM_OPEN, 0.5 * minR)));
        }
        for (int i = 0; i < lc.ns && k > 0; i++) k *= ease(lc.sd[i] - lc.s[i].r, SHAFT_SHUT, SHAFT_OPEN);
        for (int i = 0; i < lc.geoN && k > 0; i++) k *= ease(Math.sqrt(lc.geoD2[i]) - lc.geo[i].r(), GEODE_SHUT, GEODE_OPEN);
        if (k <= 0) return 0;
        // every landing of the cell: the den gates' lower ones (the first is the way in) and the way down's upper one
        for (Plans.Gate g : w.base.plans.denGates(cx, cz)) k *= ease(Math.hypot(x - g.lx, z - g.lz) - g.landR, LANDING_SHUT, LANDING_OPEN);
        Plans.Gate ex = lc.ex;
        k *= ease(lc.exitD - ex.landR, LANDING_SHUT, LANDING_OPEN);
        if (k <= 0) return 0;
        // the way down's passage, which leaves its landing through the rifts' heights
        double W = w.shape.waterY(Terrain.BLUE);
        for (int j = 0; j < 4; j++) {
            double y = W - 2 + 30 * j, t = ex.tAt(y);
            if (Double.isNaN(t)) continue;
            k *= ease(ex.passageXZ(x, y, z) - ex.radiusAt(t), PASSAGE_SHUT, PASSAGE_OPEN);
        }
        Chamber city = lc.net.city;
        if (city != null) k *= ease(Math.hypot(x - city.x, z - city.z) - Network.CITY_RESERVE, 0, CITY_OPEN);
        if (lc.r != null) {
            if (lc.wOn) k *= ease(lc.wD, WALK_SHUT, WALK_OPEN);
            if (lc.w2On) k *= ease(lc.wD2, WALK_SHUT, WALK_OPEN);
            if (lc.bridge != null && lc.rOn) k *= ease(Math.abs(lc.rS - lc.bridge.s()) - lc.bridge.halfAlong(), 0, 6);
        }
        return k;
    }

    // ================================================================================================ the noise rifts
    /** How thick (blocks) the plate a deep dark pillar stands on stays over a noise rift passing under it. */
    static final double PLATE_THICK = 5;
    /** How far past a pillar's foot (blocks) the rift's vault climbs back from under its plate. */
    static final double PLATE_REACH = 8;

    /**
     * The noise rift at a column (Blue Reaches): its open span (nF to nC, and nTop where a deep dark tier just over it
     * joins it), its shelf, and the fine span its bridges need.
     */
    static void noiseCol(Network w, Network.LCol lc, double x, double z, double keep) {
        lc.nOn = false;
        lc.nShelf = false;
        lc.nKeep = keep;
        BlueRifts br = w.rifts;
        if (keep <= 0) return;
        double[] ln = lc.nLine, sh = lc.nSh;
        if (!br.line(x, z, lc.nG, ln)) return;
        if (!br.shape(ln[3], ln[4], keep, sh)) return;
        double v = Math.max(0, ln[0] + br.wiggle(x, z));
        if (v >= sh[HALF] + 1) return;
        double W = br.W;
        double fl = BlueRifts.floor(v, W, sh), ce = BlueRifts.ceiling(v, sh);
        int side = ln[5] > 0 ? 1 : -1;
        double[] s = lc.nShelfAt;
        if (noiseShelf(br, ln[3], ln[4], side, sh, s)) {
            lc.nShelf = true;
            lc.nS = s[0];
            lc.nEdge = s[1];
            lc.nOut = s[2];
            if (v >= s[1]) fl = Math.max(fl, s[0]);
        }
        // the deep dark's pillars stand on plates the rift passes under; a tier whose floor lies within PLATE over the
        // vault opens into it (Network#PLATE), unless a pillar's plate is near
        double top = ce;
        boolean aPlate = false, bPlate = false;
        if (lc.darkOn) {
            if (lc.aC > lc.aF && lc.aPr > 0) {
                double pd = lc.aPd - (1.65 * lc.aPr + 2);
                if (pd < PLATE_REACH) { aPlate = true; ce = Math.min(ce, lc.aF - PLATE_THICK + 40 * Terrain.smooth(pd / PLATE_REACH)); }
            }
            if (lc.bC > lc.bF && lc.bPr > 0) {
                double pd = lc.bPd - (1.65 * lc.bPr + 2);
                if (pd < PLATE_REACH) { bPlate = true; ce = Math.min(ce, lc.bF - PLATE_THICK + 40 * Terrain.smooth(pd / PLATE_REACH)); }
            }
            top = ce;
            if (!aPlate) top = Math.max(top, Network.joinedTop(ce, lc.aF, lc.aC));
            if (!bPlate) top = Math.max(top, Network.joinedTop(ce, lc.bF, lc.bC));
        }
        if (ce <= fl + 0.5) return;
        lc.nOn = true;
        lc.nV = v;
        lc.nSide = side;
        lc.nF = fl;
        lc.nC = ce;
        lc.nTop = top;
        // a tunnel that crosses keeps its slab as a bridge, with an arch under it (mustStay): the per-block pass must reach it
        for (int k = 0; k < lc.nw; k++) {
            if (lc.w[k].inner || lc.wd[k] > lc.wslabHalf[k] + ARCH_WIDEN + 2) continue;
            double deck = lc.wf[k] - lc.wsl[k];
            if (lc.wf[k] < fl + 1 || deck > top) continue;
            lc.fLo = Math.min(lc.fLo, deck - ARCH_DEEP - 3);
            lc.fHi = Math.max(lc.fHi, lc.wf[k] + 1);
        }
    }

    /**
     * A noise rift's shelf on one side ({@link BlueRifts#shelf}) where its centre line's nearest point is (qx, qz) and
     * its cross-section {@code sh}: out = {the shelf's floor line, its edge, the foot of the riser behind it, its zone's
     * strength, where the terrace it widens begins (the distances from the centre line)}; false for none.
     */
    static boolean noiseShelf(BlueRifts br, double qx, double qz, int side, double[] sh, double[] out) {
        double W = br.W, depth = sh[TOP] - W;
        double[] z = out;
        if (!br.shelf(qx, qz, side, depth, sh[STEP], z)) return false;
        double v0 = BlueRifts.terraceStart(sh);
        int k = (int) z[1];
        // out over the terraces under it, but no further than about one and a half treads (a cliff of two steps under
        // it at most), so a narrow rift keeps its floor
        double tread = (sh[HALF] - v0) * sh[STEP] / Math.max(1, depth);
        double strength = z[0], out0 = Math.min(z[2], 1.6 * tread + 2);
        if (out0 < BlueRifts.SHELF_OUT_MIN) return false;
        double vIn = BlueRifts.riseAt(k * sh[STEP], v0, depth, sh[HALF]);
        double vOut = BlueRifts.riseAt((k + 0.55) * sh[STEP], v0, depth, sh[HALF]);
        double edge = Math.max(v0 + 1.5, vIn - out0 * strength);
        if (vIn - edge < 1) return false;
        out[0] = W + k * sh[STEP];
        out[1] = edge;
        out[2] = vOut;
        out[3] = strength;
        out[4] = vIn;
        return true;
    }

    /** How much wider than its slab (blocks) a bridge's arch grows towards the rift's walls, and how deep it reaches under its deck. */
    static final double ARCH_WIDEN = 2, ARCH_DEEP = 10;

    /**
     * The arch under a tunnel's slab where it crosses a noise rift (positive inside): the slab's own thickness at the
     * rift's middle, thickening to {@link #ARCH_DEEP} more and {@link #ARCH_WIDEN} wider towards its walls, so a
     * crossing reads as a natural bridge.
     */
    static double arch(Network.LCol lc, int y) {
        double d = -Network.SOLID;
        double q = Math.min(1, lc.nV / Math.max(1, lc.nSh[HALF]));
        double q3 = q * q * q;
        for (int k = 0; k < lc.nw; k++) {
            if (lc.w[k].inner) continue;
            double half = lc.wslabHalf[k] + ARCH_WIDEN * q;
            if (lc.wd[k] > half + 1) continue;
            double deck = lc.wf[k] - lc.wsl[k];
            if (lc.wf[k] < lc.nF + 1 || deck > lc.nTop) continue;
            double under = deck - 1 - ARCH_DEEP * q3 - 0.6 * (lc.wd[k] / Math.max(1, half)) * (lc.wd[k] / Math.max(1, half));
            if (y >= deck || y < under - 1) continue;
            d = Math.max(d, Math.min(Math.min(deck - y, y - under), half - lc.wd[k]));
        }
        return d;
    }

    // ================================================================================================ the Deep Blue Rift's shelves
    /**
     * The Deep Blue Rift's shelf at a column, at arc length s, sv across (signed) and v its riftCut distance, with its
     * top, half width and step there: out as {@link #noiseShelf}; false for none. Its zones run along the arc length.
     */
    static boolean mainShelf(BlueRifts br, Route r, double s, double sv, double top, double halfTop, double step, double[] out) {
        double bed = r.W - 2, depth = top - bed;
        double[] z = out;
        int side = sv >= 0 ? 1 : -1;
        if (!br.shelf(s, r.cellX * 61.3 + r.cellZ * 17.9, side, depth, step, z)) return false;
        double v0 = r.streamHalf;
        int k = (int) z[1];
        double strength = z[0], out0 = z[2];
        double vIn = mainRiseAt(k * step, v0, depth, halfTop);
        double vOut = mainRiseAt((k + 0.55) * step, v0, depth, halfTop);
        double edge = Math.max(v0 + 2.5, vIn - out0 * strength);
        if (vIn - edge < 1) return false;
        out[0] = bed + k * step;
        out[1] = edge;
        out[2] = vOut;
        out[3] = strength;
        out[4] = vIn;
        return true;
    }

    /** Where the Deep Blue Rift's floor ({@link Terrain#riftFloor}) reaches {@code g} over its bed: its unstepped curve's inverse. */
    static double mainRiseAt(double g, double v0, double depth, double half) {
        double t = Math.pow(Math.max(0, Math.min(1, g / depth)), 1 / 0.85);
        return v0 + t * Math.max(1, half - v0);
    }

    /**
     * Raises the Deep Blue Rift's floor at a column onto its shelf (after canyonCol), the shelf drawing back to its
     * terrace where it would stand over the ledge path's floor near the path, and none by a bridge.
     */
    static void mainShelfCol(Network w, Network.LCol lc, double x, double z) {
        lc.rShelf = false;
        if (!lc.rOn || lc.rrC <= lc.rrF || lc.bridge != null) return;
        Route r = lc.r;
        double v = Math.abs(lc.rV) + 2.0 * w.ledgeN.noise2(x / 16.0, z / 16.0);
        double step = 11 + 3 * w.ledgeN.noise2(lc.rS / 90.0, 4.4);
        double[] s = lc.nShelfAt;
        if (!mainShelf(w.rifts, r, lc.rS, lc.rV, lc.rTop, lc.rHalfTop, step, s)) return;
        double strength = 1;
        if (lc.wOn && s[0] > lc.rF - 3) strength *= ease(lc.wD, WALK_SHUT, WALK_SHUT + 6);
        if (lc.w2On && s[0] > lc.rF2 - 3) strength *= ease(lc.wD2, WALK_SHUT, WALK_SHUT + 6);
        if (strength <= 0) return;
        double edge = s[4] - (s[4] - s[1]) * strength;
        lc.rShelf = true;
        lc.rS2 = s[0];
        lc.rEdge = edge;
        lc.rOut = s[2];
        if (v >= edge && s[0] < lc.rrC - 3) lc.rrF = Math.max(lc.rrF, s[0]);
    }

    // ================================================================================================ ponds
    /**
     * Pond sites: one try in each square of this many blocks. A pond's centre stays within the squares round its own
     * ({@link #POND_LOOK}), so a column looks at the squares that far round its own.
     */
    static final int POND_GRID = 14, POND_LOOK = 2;
    /** A pond's rim (blocks of rock round the water), and its radius's bounds. */
    static final double RIM = 1.6, POND_R_MIN = 2.5, POND_R_MAX = 7;

    /**
     * A pond on a rift's shelf: a disc of water one block under the shelf's floor, {@code depth} deep in the middle and
     * one round its edge (the reeds' shallows), in a rim of rock with a floor under it. About half spill: a notch through
     * the rim towards the rift's middle runs to the shelf's edge, and the water pours over it ({@code PondSpillFeature}).
     */
    static final class Pond {
        /** The water's centre and radius. */
        final double x, z, r;
        /** The shelf's floor line, the water's top block, where feet stand on the shelf round it, the depth in the middle. */
        final double S;
        final int top, feet, depth;
        /**
         * The spill: its direction (unit, towards the rift's middle), its half width, and how far from the centre its
         * water may run (the shelf's edge) and its notch is cut (past the edge, through any lip the rock leaves). A half
         * width of 0 for a pond that does not spill.
         */
        final double sx, sz, sw, wet, reach;
        /** Which of two ponds too close together stays (the higher). */
        final double prio;
        /** On the Deep Blue Rift (or a noise rift). */
        final boolean main;

        Pond(double x, double z, double r, double S, int top, int feet, int depth, double sx, double sz, double sw, double wet,
             double reach, double prio, boolean main) {
            this.x = x; this.z = z; this.r = r; this.S = S; this.top = top; this.feet = feet; this.depth = depth;
            this.sx = sx; this.sz = sz; this.sw = sw; this.wet = wet; this.reach = reach; this.prio = prio; this.main = main;
        }

        /** Whether it spills over its shelf's edge. */
        public boolean spills() { return sw > 0; }

        /** How far it reaches from its centre, its spill included. */
        double reachAll() { return Math.max(r + RIM, sw > 0 ? reach : 0); }
    }

    /** No pond: the caches' mark for a site that has none. */
    private static final Pond NONE = new Pond(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, false);

    /** Per worldgen thread: the pond sites seen lately, as they stand alone (raw) and once their neighbours are weighed. */
    static final class PondCache {
        static final int N = 1024;
        final long[] rawKey = new long[N], finKey = new long[N];
        final Pond[] raw = new Pond[N], fin = new Pond[N];
        final Cross a = new Cross(), b = new Cross();
        final double[] g = new double[2], ln = new double[6], sh = new double[8], sv = new double[5], t = new double[6];

        PondCache() {
            java.util.Arrays.fill(rawKey, Long.MIN_VALUE);
            java.util.Arrays.fill(finKey, Long.MIN_VALUE);
        }
    }

    private static long key(int li, int gx, int gz) { return ((long) li << 58) ^ ((gx & 0x1FFFFFFFL) << 29) ^ (gz & 0x1FFFFFFFL); }

    private static int slot(int gx, int gz) { return ((gx & 31) << 5) | (gz & 31); }

    /** The pond of site (gx, gz) of layer li, or null: its own pond, unless a neighbour's that would crowd it stays. */
    static Pond pond(Network w, int li, int gx, int gz) {
        PondCache c = w.pondCache.get();
        long k = key(li, gx, gz);
        int i = slot(gx, gz);
        if (c.finKey[i] == k) return c.fin[i] == NONE ? null : c.fin[i];
        Pond p = raw(w, c, li, gx, gz);
        if (p != null) {
            for (int dx = -POND_LOOK; dx <= POND_LOOK && p != null; dx++) for (int dz = -POND_LOOK; dz <= POND_LOOK && p != null; dz++) {
                if (dx == 0 && dz == 0) continue;
                Pond q = raw(w, c, li, gx + dx, gz + dz);
                if (q != null && q.prio > p.prio && Math.hypot(p.x - q.x, p.z - q.z) < p.reachAll() + q.reachAll() + 3) p = null;
            }
        }
        c.finKey[i] = k;
        c.fin[i] = p == null ? NONE : p;
        return p;
    }

    private static Pond raw(Network w, PondCache c, int li, int gx, int gz) {
        long k = key(li, gx, gz);
        int i = slot(gx, gz);
        if (c.rawKey[i] == k) return c.raw[i] == NONE ? null : c.raw[i];
        Pond p = makePond(w, c, li, gx, gz);
        c.rawKey[i] = k;
        c.raw[i] = p == null ? NONE : p;
        return p;
    }

    /** A rift's cross-section where a column lies in it, as the ponds weigh it: a noise rift's, or else the Deep Blue Rift's. */
    static final class Cross {
        boolean main;
        int side;
        /** The unit normal from the centre line towards the column, and the column's distance from the line (as the rift has it). */
        double nx, nz, v;
        /** Where the terraces start across, their base and step, the depth from the base to the rim, and the half width there. */
        double v0, base, step, depth, half;
        /** The floor and the vault over the column, without shelves or ponds. */
        double floor, ceil;
        /** The noise rift's cross-section, or the Deep Blue Rift's arc length, half width at its top and top, for {@link #floorAt}. */
        final double[] sh = new double[8];
        Route r;
        double arc, top;
        /** The shelf on the column's side, if there is one: its terrace and its edge (from the centre line). */
        int shelfK;
        double shelfEdge;
        final double[] shelf = new double[5];

        /** Where terrace k's flat begins and ends across (from the centre line); terrace 0 is the bank's (or the bed's). */
        double flatIn(int k) { return k <= 0 ? v0 : riseAt(k * step); }
        double flatOut(int k) { return riseAt((k + 0.55) * step); }

        private double riseAt(double g) {
            return main ? mainRiseAt(g, v0, depth, half) : BlueRifts.riseAt(g, v0, depth, half);
        }

        /** The floor across this rift at distance v from its centre line, without shelves or ponds. */
        double floorAt(double v, double W) {
            return main ? Terrain.riftFloor(v, base, top, half, r.streamHalf, step) : BlueRifts.floor(v, W, sh);
        }
    }

    /** The rift at (x, z), into o (the noise rift first); false where the column lies in none. */
    static boolean cross(Network w, PondCache c, int li, double x, double z, Cross o) {
        BlueRifts br = w.rifts;
        double[] ln = c.ln, sh = o.sh;
        if (br.line(x, z, c.g, ln) && br.shape(ln[3], ln[4], 1, sh)) {
            double v = Math.max(0, ln[0] + br.wiggle(x, z));
            if (v < sh[HALF]) {
                o.main = false;
                o.side = ln[5] > 0 ? 1 : -1;
                o.nx = ln[1];
                o.nz = ln[2];
                o.v = v;
                o.v0 = BlueRifts.terraceStart(sh);
                o.base = br.W;
                o.step = sh[STEP];
                o.depth = sh[TOP] - br.W;
                o.half = sh[HALF];
                o.floor = BlueRifts.floor(v, br.W, sh);
                o.ceil = BlueRifts.ceiling(v, sh);
                o.shelfK = 0;
                if (noiseShelf(br, ln[3], ln[4], o.side, sh, o.shelf) && o.shelf[3] > 0.5) {
                    o.shelfK = (int) Math.round((o.shelf[0] - br.W) / o.step);
                    o.shelfEdge = o.shelf[1];
                }
                return true;
            }
        }
        Net n = w.net(li, w.cellOf(x), w.cellOf(z));
        if (n.canyons.isEmpty()) return false;
        Route r = n.canyons.get(0);
        double[] t = c.t;
        if (!r.nearest(x, z, t)) return false;
        double arc = t[1], sv = t[2];
        double top = r.at(r.riftTop, arc), halfTop = r.at(r.riftHalf, arc);
        double v = Math.abs(sv) + 2.0 * w.ledgeN.noise2(x / 16.0, z / 16.0);
        if (v >= halfTop) return false;
        double step = 11 + 3 * w.ledgeN.noise2(arc / 90.0, 4.4);
        double[] dir = r.directionAt(arc);
        int side = sv >= 0 ? 1 : -1;
        o.main = true;
        o.side = side;
        o.nx = -dir[1] * side;
        o.nz = dir[0] * side;
        o.v = v;
        o.r = r;
        o.arc = arc;
        o.top = top;
        o.v0 = r.streamHalf;
        o.base = r.W - 2;
        o.step = step;
        o.depth = top - o.base;
        o.half = halfTop;
        o.floor = Terrain.riftFloor(v, o.base, top, halfTop, r.streamHalf, step);
        double ce = top + 7 * Math.max(0, 1 - (v / halfTop) * (v / halfTop));
        double endFade = Terrain.smooth((Math.min(arc, r.length - arc) - 10) / 60.0);
        o.ceil = o.floor + (ce - o.floor) * endFade;
        o.shelfK = 0;
        if (mainShelf(br, r, arc, sv, top, halfTop, step, o.shelf) && o.shelf[3] > 0.5) {
            o.shelfK = (int) Math.round((o.shelf[0] - o.base) / step);
            o.shelfEdge = o.shelf[1];
        }
        return true;
    }

    /** Calibration only: when set, each try that fails counts its reason here ({@link #makePond}'s steps, 0 to 11). */
    static volatile int[] debug;

    private static Pond fail(int why) {
        int[] d = debug;
        if (d != null) d[why]++;
        return null;
    }

    /** The share of the sites that try for a pond. */
    static final double POND_CHANCE = 0.8;

    /**
     * The pond of site (gx, gz) on its own, or null. A try in the site's square that lands in a rift sets a pond on the
     * shelf there if there is one with room for it, or else on one of the rift's terraces on its side with room: its
     * water's back against the foot of the riser behind, its platform ({@link #platform}) out over the terrace under it
     * (a bulge of the terrace, one step or two over the floor in front of it; {@link #front}). Moved across to there (it
     * must stay within the squares next to its own, so a column need only look {@link #POND_LOOK} squares round its
     * own), it must still lie in that rift at that height, sit clear of all the network's works, stand over the water
     * table with room over it and round it, and have its wall behind it ({@link #framed}).
     */
    private static Pond makePond(Network w, PondCache c, int li, int gx, int gz) {
        long h = Hashing.mix(w.seed ^ 0x5D588B656C078965L, gx, gz, li);
        if (Hashing.unit(Hashing.mix(h, 7, 0, 0)) > POND_CHANCE) return fail(11);
        double px = (gx + Hashing.range(Hashing.mix(h, 1, 0, 0), 0.2, 0.8)) * POND_GRID;
        double pz = (gz + Hashing.range(Hashing.mix(h, 2, 0, 0), 0.2, 0.8)) * POND_GRID;
        Cross p = c.a;
        if (!cross(w, c, li, px, pz, p)) return fail(0);
        int kmax = (int) Math.floor(p.depth / p.step) - 1;
        if (kmax < 1) return fail(1);
        // on the shelf here if there is one with room, else on one of the terraces with room (the lowest of them up to
        // the lower two thirds of the rift's, nearer the water more often)
        int k;
        boolean onShelf = p.shelfK >= 1 && p.shelfK <= kmax && room(p, p.shelfK, true) >= 2 * POND_R_MIN;
        if (onShelf) k = p.shelfK;
        else {
            int kLo = 1;
            while (kLo <= kmax && room(p, kLo, false) < 2 * POND_R_MIN) kLo++;
            if (kLo > kmax) return fail(2);
            int kHi = Math.max(kLo, Math.min(kmax, (int) Math.ceil(kmax * 0.67)));
            k = Math.min(kHi, kLo + (int) Math.floor(Math.pow(Hashing.unit(Hashing.mix(h, 8, 0, 0)), 1.3) * (kHi - kLo + 1)));
        }
        double R = Math.min(POND_R_MIN + Hashing.unit(Hashing.mix(h, 9, 0, 0)) * (POND_R_MAX - POND_R_MIN), room(p, k, onShelf) / 2);
        // its water's back against the foot of the riser behind the terrace
        double vc = p.flatOut(k) - 0.5 - R;
        boolean main = p.main;
        int side = p.side;
        double S = p.base + k * p.step;
        // moved across to there, and again for the field's own bend
        double cx = px + p.nx * (vc - p.v), cz = pz + p.nz * (vc - p.v);
        Cross q = c.b;
        for (int it = 0; it < 4; it++) {
            if (!cross(w, c, li, cx, cz, q) || q.main != main || q.side != side) return fail(3);
            if (Math.abs(q.v - vc) < 0.25) break;
            cx += q.nx * (vc - q.v);
            cz += q.nz * (vc - q.v);
        }
        if (Math.abs(Math.floorDiv((long) Math.floor(cx), POND_GRID) - gx) > POND_LOOK - 1
                || Math.abs(Math.floorDiv((long) Math.floor(cz), POND_GRID) - gz) > POND_LOOK - 1) return fail(4);
        // the height of that terrace here (the steps drift a little along a rift), and the platform still in its room
        S = q.base + k * q.step;
        if (onShelf && q.shelfK != k) return fail(5);
        if (q.v - R - RIM < front(q, k, onShelf) - 0.75 || q.v + R > q.flatOut(k) + 0.75) return fail(5);
        int feet = (int) Math.ceil(S - 1e-9), top = feet - 2;
        int depth = Hashing.unit(Hashing.mix(h, 3, 0, 0)) < 0.5 ? 2 : 3;
        double W = w.shape.waterY(li);
        if (top - depth < W + 2) return fail(6);
        if (q.ceil - feet < 6) return fail(7);
        double nx = q.nx, nz = q.nz;
        // the floor just past the platform's front, where a spill would land
        double low = q.floorAt(Math.max(0, q.v - R - RIM - 2), W);
        if (!clearOfPlan(w, li, cx, cz, R + RIM + SKIRT, top - depth - 4, feet + 3, main, S)) return fail(10);
        if (!framed(w, li, cx, cz, nx, nz, R + RIM, feet)) return fail(8);
        // about half spill (Taran: "About half"), where the platform's front drops 4 or more
        boolean spill = Hashing.unit(Hashing.mix(h, 4, 0, 0)) < 0.5 && S - low >= 4;
        // the notch a block or two wide (at least a block and a half across its axis, so it runs unbroken on a slant)
        double sw = spill ? (Hashing.unit(Hashing.mix(h, 5, 0, 0)) < 0.5 ? 0.8 : 1.3) : 0;
        double wet = R + RIM;
        return new Pond(cx, cz, R, S, top, feet, depth, -nx, -nz, sw, wet + 0.5, wet + 3.5, Hashing.unit(Hashing.mix(h, 6, 0, 0)), main);
    }

    /**
     * How far towards a rift's middle a pond's platform on terrace k may reach (its rim's front): its shelf's edge, or
     * the flat of the terrace under it, so it stands one step over the floor in front of it (two where the terraces are
     * narrow, and where they are narrower still, as far as the stream's bank: a spur of rock from the wall).
     */
    private static double front(Cross p, int k, boolean onShelf) {
        if (onShelf) return p.shelfEdge + 0.5;
        double back = p.flatOut(k) - 0.5 - RIM - 2 * POND_R_MIN, bank = p.v0 + 0.5;
        for (int j = 1; j <= 2; j++) {
            double f = Math.max(p.flatIn(k - j) + 1, bank);
            if (f <= back) return f;
        }
        return bank;
    }

    /** The room across a rift for a pond on its terrace k (or its shelf): from its front to the foot of the riser behind it. */
    private static double room(Cross p, int k, boolean onShelf) {
        return p.flatOut(k) - 0.5 - front(p, k, onShelf) - RIM;
    }

    /** How far past a pond's rim (blocks) its platform's sides slope down, and how steeply (blocks down per block out). */
    static final double SKIRT = 5, SKIRT_SLOPE = 4;

    /** No floor: what {@link #platform} gives past a platform's sides (a height under every layer). */
    static final double NO_FLOOR = -1e9;

    /**
     * The floor a pond's platform holds a column at, r from the pond's centre: its shelf's floor out to the rim, then
     * its sides; {@link #NO_FLOOR} past them.
     */
    static double platform(Pond p, double r) {
        double out = r - (p.r + RIM);
        if (out <= 0) return p.S;
        if (out >= SKIRT) return NO_FLOOR;
        return p.S - out * SKIRT_SLOPE;
    }

    /** Calibration only: when set, which of {@link #clearOfPlan}'s checks turned a pond away (1 to 19). */
    static volatile int[] clearDebug;

    private static boolean no(int why) {
        int[] d = clearDebug;
        if (d != null) d[why]++;
        return false;
    }

    /**
     * Whether a pond at (cx, cz) reaching {@code reach} across, between heights yLo and yHi, keeps clear of all the
     * network's works: rooms, tunnels near its heights, climbs, landings, the way down's passage, geodes and their
     * links, the city, the Deep Blue Rift's ledge path (a pond may sit under it) and bridges, the deep dark's caverns
     * (a pond may sit under a tier's floor), and the other kind of rift; and of the cell's seams, where the two cells'
     * plans meet.
     */
    private static boolean clearOfPlan(Network w, int li, double cx, double cz, double reach, int yLo, int yHi, boolean main, double S) {
        if (NetDark.seamDist(cx, cz, w.cell) < reach + 8) return no(1);
        int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
        int cellX = w.cellOf(cx), cellZ = w.cellOf(cz);
        Net n = w.net(li, cellX, cellZ);
        for (Chamber ch : n.ch) {
            if (!ch.keep || ch.dark >= 0 || ch.kind == Net.Kind.CANYON) continue;
            if ((w.e(ch, cx, cz) - 1) * Math.min(ch.rx, ch.rz) < reach + ROOM_OPEN) return no(2);
        }
        for (Shaft s : n.shafts) if (Math.hypot(cx - s.x, cz - s.z) < s.r + reach + SHAFT_OPEN) return no(3);
        for (Plans.Gate g : w.base.plans.denGates(cellX, cellZ))
            if (Math.hypot(cx - g.lx, cz - g.lz) < g.landR + reach + LANDING_OPEN) return no(4);
        Plans.Gate ex = w.base.plans.gate(li + 2, cellX, cellZ);
        if (Math.hypot(cx - ex.ux, cz - ex.uz) < ex.landR + reach + LANDING_OPEN) return no(5);
        for (int y = yLo; y <= yHi + 30; y += 6) {
            double t = ex.tAt(y);
            if (!Double.isNaN(t) && ex.passageXZ(cx, y, cz) < ex.radiusAt(t) + reach + PASSAGE_OPEN) return no(6);
        }
        if (n.city != null && Math.hypot(cx - n.city.x, cz - n.city.z) < Network.CITY_RESERVE + CITY_OPEN + reach) return no(7);
        if (!n.canyons.isEmpty()) {
            Route r = n.canyons.get(0);
            for (Route.Geode g : r.geodes) if (Math.hypot(cx - g.x(), cz - g.z()) < g.r() + reach + GEODE_OPEN) return no(8);
            for (Route.Link l : r.links) if (l.dist(cx, (yLo + yHi) * 0.5, cz) < reach + 8) return no(9);
        }
        Network.LCol lc = w.scratch(bx, bz, li);
        for (int k = 0; k < lc.nw; k++) {
            if (lc.wd[k] > lc.ww[k] * 1.25 + reach + 3) continue;
            if (lc.wf[k] - lc.wsl[k] - 3 < yHi && lc.wf[k] + lc.wh[k] + 3 > yLo) return no(10);
        }
        if (!darkClear(lc, yLo, yHi)) return no(11);
        if (lc.r != null) {
            // the ledge path: a pond may sit under it, but not on it or over it
            if (lc.wOn && lc.wD < reach + Terrain.CORE + 3 && yHi + 2 >= lc.rF - lc.rSlab) return no(13);
            if (lc.w2On && lc.wD2 < reach + Terrain.CORE + 3 && yHi + 2 >= lc.rF2 - lc.rSlab2) return no(14);
            if (lc.bridge != null) return no(15);
        }
        if (main) {
            // the column is the Deep Blue Rift's shelf, and no noise rift comes near
            if (!lc.rOn || lc.rrC <= lc.rrF || lc.rrF > S + 0.5 || lc.rrC - (yHi - 3) < 5) return no(16);
            double[] ln = new double[6], sh = new double[8];
            if (w.rifts.line(cx, cz, new double[2], ln) && w.rifts.shape(ln[3], ln[4], 1, sh) && ln[0] < sh[HALF] + reach + 6) return no(17);
        } else {
            if (!lc.nOn || lc.nKeep < 0.999 || lc.nF > S + 0.5 || lc.nC - (yHi - 3) < 5) return no(18);
            if (lc.rOn && Math.abs(lc.rV) < lc.rHalfTop + reach + 6) return no(19);
        }
        // and the deep dark's caverns round its rim (a tier's floor drifts)
        double rr = reach - SKIRT;
        for (int i = 0; i < 4; i++) {
            Network.LCol e = w.scratch((int) Math.floor(cx + (i == 0 ? rr : i == 1 ? -rr : 0)), (int) Math.floor(cz + (i == 2 ? rr : i == 3 ? -rr : 0)), li);
            if (!darkClear(e, yLo, yHi)) return no(12);
        }
        return true;
    }

    /**
     * Whether a pond at (cx, cz) whose rim reaches {@code rim} stands as it should against its rift's wall (towards
     * (nx, nz)): the rift open over its rim in front and at its sides, four blocks over its feet at least (not in a
     * slanting crack, as at the Deep Blue Rift's ends); and its wall behind it, no rift open there at its feet (not on
     * a narrow ridge, where two rifts run side by side).
     */
    private static boolean framed(Network w, int li, double cx, double cz, double nx, double nz, double rim, int feet) {
        for (int i = 0; i < 3; i++) {
            // in front, and either side
            double ox = i == 0 ? -nx : (i == 1 ? -nz : nz), oz = i == 0 ? -nz : (i == 1 ? nx : -nx);
            Network.LCol lc = w.scratch((int) Math.floor(cx + ox * rim), (int) Math.floor(cz + oz * rim), li);
            double ceil = Math.max(lc.nOn ? lc.nTop : NO_FLOOR, lc.rOn && lc.rrC > lc.rrF ? lc.rrC : NO_FLOOR);
            if (ceil < feet + 4) return false;
        }
        for (double d = rim + 2.5; d <= rim + 6; d += 3.5) {
            Network.LCol lc = w.scratch((int) Math.floor(cx + nx * d), (int) Math.floor(cz + nz * d), li);
            if (lc.nOn && lc.nF < feet + 2 && lc.nTop > feet + 2) return false;
            if (lc.rOn && lc.rrC > lc.rrF && lc.rrF < feet + 2 && lc.rrC > feet + 2) return false;
        }
        return true;
    }

    /** Whether the deep dark's caverns at a column keep out of heights yLo to yHi: none there, or a tier's floor 2 over yHi. */
    private static boolean darkClear(Network.LCol lc, int yLo, int yHi) {
        if (!lc.darkOn) return true;
        if (lc.aC0 > lc.aF && lc.aF < yHi + 2 && lc.aC0 > yLo - 6) return false;
        return !(lc.bC0 > lc.bF && lc.bF < yHi + 2 && lc.bC0 > yLo - 6);
    }

    /**
     * The ponds that reach a column (after its rifts and shelves): into lc, the rift's floor raised onto their
     * platforms, and the per-block pass's span with them.
     */
    static void pondsCol(Network w, int li, Network.LCol lc, double x, double z) {
        lc.np = 0;
        // every column, in a rift or not: a basin may reach a little past its rift's floor into the wall behind it
        if (lc.noPonds) return;
        int gx = Math.floorDiv((int) Math.floor(x), POND_GRID), gz = Math.floorDiv((int) Math.floor(z), POND_GRID);
        for (int dx = -POND_LOOK; dx <= POND_LOOK; dx++) for (int dz = -POND_LOOK; dz <= POND_LOOK; dz++) {
            Pond p = pond(w, li, gx + dx, gz + dz);
            if (p == null) continue;
            double ox = x - p.x, oz = z - p.z, r = Math.hypot(ox, oz);
            double a = ox * p.sx + oz * p.sz, across = Math.abs(ox * p.sz - oz * p.sx);
            boolean in = r < p.r + RIM + SKIRT;
            boolean spill = p.sw > 0 && a > p.r - 2 && a < p.reach + 1 && across < p.sw + 2;
            if (!in && !spill) continue;
            if (lc.np == lc.pond.length) break;
            // the platform under it, out of the rift's floor; its sides never reach into the stream or onto its bank
            double pf = platform(p, r);
            if (r > p.r + RIM && (p.main ? lc.rOn && Math.abs(lc.rV) < lc.r.streamHalf + 1
                    : lc.nOn && lc.nV < BlueRifts.terraceStart(lc.nSh))) pf = NO_FLOOR;
            if (p.main) { if (lc.rOn && lc.rrC > lc.rrF && pf > lc.rrF) lc.rrF = Math.min(pf, lc.rrC - 1); }
            else if (lc.nOn && pf > lc.nF) lc.nF = Math.min(pf, lc.nC - 1);
            int i = lc.np++;
            lc.pond[i] = p;
            lc.pondR[i] = r;
            lc.pondA[i] = a;
            lc.pondX[i] = across;
            // the spill's water runs only over the platform (beyond, it pours)
            lc.pondOn[i] = p.main ? lc.rOn && lc.rrF >= p.S - 0.5 : lc.nOn && lc.nF >= p.S - 0.5;
            lc.fLo = Math.min(lc.fLo, p.top - p.depth - 5);
            lc.fHi = Math.max(lc.fHi, p.feet + 2);
        }
    }

    /** The lowest block of a pond's water at a column, r from its centre: depth blocks in the middle, one round the edge. */
    private static int bottom(Pond p, double r) {
        return r < p.r - 1.3 ? p.top - p.depth + 1 : p.top;
    }

    /** Whether a column, a along and across from a pond's centre, lies in its spill's notch (its water: within {@code to}). */
    private static boolean inSpill(Pond p, double a, double across, double to) {
        return p.sw > 0 && a > p.r - 1.5 && a < to && across < p.sw;
    }

    /**
     * Rock the ponds keep (positive inside): a cylinder of rock under and round each pond, from 3 under its floor up to
     * the shelf's floor, which the basin is cut out of ({@link #pondOpen}), and the trough its spill runs in over the
     * shelf: a floor under the water and a wall either side.
     */
    static double pondStay(Network.LCol lc, int y) {
        double d = -Network.SOLID;
        for (int i = 0; i < lc.np; i++) {
            Pond p = lc.pond[i];
            double r = lc.pondR[i];
            int lo = p.top - p.depth - 3, hi = p.feet - 1;
            if (y >= lo && y <= hi && r < p.r + RIM) d = Math.max(d, Math.min(Math.min(y - lo, hi - y) + 0.5, p.r + RIM - r));
            if (p.sw > 0 && lc.pondOn[i] && (y == p.top - 1 || y == p.top) && lc.pondA[i] > p.r - 1.5 && lc.pondA[i] < p.wet
                    && lc.pondX[i] < p.sw + 1) d = Math.max(d, 0.5);
        }
        return d;
    }

    /** Space the ponds keep open (negative inside): each basin, from its floor to two over the shelf's, and the spills' notches. */
    static double pondOpen(Network.LCol lc, int y) {
        double d = Network.SOLID;
        for (int i = 0; i < lc.np; i++) {
            Pond p = lc.pond[i];
            double r = lc.pondR[i];
            if (r < p.r && y >= bottom(p, r) && y <= p.feet + 1) d = Math.min(d, Math.max(r - p.r, -0.5));
            if (y >= p.top && y <= p.feet + 1 && inSpill(p, lc.pondA[i], lc.pondX[i], p.reach)) d = Math.min(d, -0.5);
        }
        return d;
    }

    /** Whether an open block holds a pond's water: in its basin up to its top block, or in its spill's notch over the shelf. */
    static boolean pondWater(Network.LCol lc, int y) {
        for (int i = 0; i < lc.np; i++) {
            Pond p = lc.pond[i];
            if (y > p.top) continue;
            double r = lc.pondR[i];
            if (r < p.r && y >= bottom(p, r)) return true;
            if (y == p.top && lc.pondOn[i] && inSpill(p, lc.pondA[i], lc.pondX[i], p.wet)) return true;
        }
        return false;
    }
}
