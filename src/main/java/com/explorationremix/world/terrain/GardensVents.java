package com.explorationremix.world.terrain;

import java.util.ArrayList;
import java.util.List;

import static com.explorationremix.world.terrain.GardensNoise.n2;
import static com.explorationremix.world.terrain.GardensNoise.n3;
import static com.explorationremix.world.terrain.GardensNoise.ramp;
import static com.explorationremix.world.terrain.GardensPlan.SY;

/**
 * The Deep Gardens' big hollow vents (the previews' vents.py from the table on): each vent's row of numbers, its shape
 * as a closed form round a leaning axis (profile, elliptic section, lumps, flare, flanges, cracks with walkable sills),
 * so any block can be asked which part of which vent it is; the door test, the stamp, rubble round broken vents, the
 * keep-clear masks and the vent weights the dressing reads; the deep vents' throat tables.
 */
final class GardensVents {
    static final int NV = 48;
    static final double DS = 0.25, DOOR_OPEN = 0.9;
    static final int TILE = 16;

    // block codes (layer.py)
    static final byte AIR = 0, ROCK = 1, TALUS = 2, DEAD = 3, LIVE = 4, THROAT = 5, MAT = 6, STEM = 7, CAP = 8, OIL = 9,
            SEALROCK = 10, DRIP = 11, VDEAD = 12, VLIVE = 13, CROWN = 14, FISSURE = 15, VGLOW = 16, TVEIN = 17, TIP = 18;
    static final int MAGENTA = 0, VIOLET = 1, LIME = 2, LIGHTBLUE = 3;
    // vent parts
    static final byte VP_BODY = 1, VP_SKIRT = 2, VP_DEN = 3, VP_FLUE = 4, VP_CRACK = 5, VP_DOOR = 6, VP_ADIT = 7,
            VP_TUBE = 8, VP_HOOD = 9, VP_RIM = 10, VP_FLARE = 11, VP_PLUG = 12;

    /** One vent's table row (vents.table) and what goes with it. */
    static final class Vent {
        final GardensSites.Site site;
        final float[] t = new float[NV];
        final float[] vc = {Float.NaN, Float.NaN, Float.NaN};
        final float[] fly = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
        final float[][] rim = new float[3][3];
        long k24;
        /** The centre as the table holds it, relative to the plan's origin ({@link GardensVents#ox}). */
        double cx, cz;
        /** The vent's place in its list (its vid is idx + 1). */
        int idx;
        /** The deep throat's table: rows of (floor, radius, width, height), or null. */
        double[][] tab;

        Vent(GardensSites.Site s) {
            site = s;
            for (float[] r : rim) java.util.Arrays.fill(r, Float.NaN);
        }

        int form() { return (int) t[1]; }

        boolean live() { return t[16] > 0.5f; }

        int k() { return (int) t[3]; }

        double bound() { return t[33]; }
    }

    final GardensPlan plan;
    /** The origin the table's centres are measured from (the sites' own): the patch's corner in the preview's rules, else 0. */
    final int ox, oz;
    final int[] plump, pflare, pglow, pfl, ptube, pcrk, pvn;

    GardensVents(GardensPlan plan) {
        this.plan = plan;
        ox = plan.patch ? plan.px0 : 0;
        oz = plan.patch ? plan.pz0 : 0;
        int s = plan.seed + 3951;
        plump = plan.perm(s);
        pflare = plan.perm(s + 1);
        pglow = plan.perm(s + 2);
        pfl = plan.perm(s + 3);
        ptube = plan.perm(s + 4);
        pcrk = plan.perm(s + 5);
        pvn = plan.perm(s + 6);
    }

    static double u(long k24, int m) { return GardensSites.uSlot(k24, m); }

    /** A column relative to the origin, clipped into the patch in the preview's rules. */
    int clipX(int x) { return plan.patch ? Math.max(0, Math.min(plan.psx - 1, x)) : x; }

    int clipZ(int z) { return plan.patch ? Math.max(0, Math.min(plan.psz - 1, z)) : z; }

    static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    // ================================================================================================ the table

    /** The band a vent stands in: the dominant style of the highest storey whose floor is at or under ys + 1. */
    interface Bands {
        int bandAt(int x, int y, int z);
    }

    /** vents.table: each site's row (5.3). */
    List<Vent> table(List<GardensSites.Site> sites, Bands bands) {
        List<Vent> out = new ArrayList<>(sites.size());
        for (GardensSites.Site v : sites) {
            Vent w = new Vent(v);
            w.idx = out.size();
            long k24 = v.key24;
            int form = v.form;
            double R = v.R, H = v.H;
            boolean live = v.live, deep = form == 3;
            int ys = v.ys;
            int band = deep ? 0 : (v.band >= 0 ? v.band : bands.bandAt(clipX((int) Math.floor(v.x)) + ox, ys, clipZ((int) Math.floor(v.z)) + oz));
            int colour = deep ? LIME : (band == 0 ? MAGENTA : (band == 1 ? VIOLET : LIGHTBLUE));
            double leanS = deep ? 0.0 : (form == 1 ? 0.05 : 0.02) * u(k24, 1);
            double leanA = 2 * Math.PI * u(k24, 0);
            double e = deep ? 1.0 : 0.75 + 0.25 * u(k24, 24);
            double phiE = 2 * Math.PI * u(k24, 2);
            double p0, p1;
            if (form == 0) { p0 = 0; p1 = 0; }
            else if (form == 1) { p0 = 1.05 + 0.2 * u(k24, 17); p1 = 0.35 + 0.2 * u(k24, 18); }
            else if (form == 2) { p0 = 0.45 + 0.45 * u(k24, 17); p1 = 0.30 + 0.25 * u(k24, 18); }
            else { p0 = 0.6; p1 = 0.42; }
            double bulge = deep ? 0.0 : 0.12 * R * (2 * u(k24, 19) - 1);
            double F, wall;
            if (form == 0) { F = 0.3 * R; wall = 1.4; }
            else if (form == 1) { F = 1.5 + 0.08 * H; wall = live ? 2.0 : 2.5; }
            else if (form == 2) { F = 0.45 * R; wall = Math.max(1.6, Math.min(2.6, 0.3 * R)); }
            else { F = 0.5 * R; wall = 3.0; }
            double sf = Math.max(form == 0 ? 2.0 : (deep ? 4.5 : 4.0), 1.4 * F);
            double cw, ch;
            if (deep) { cw = 1.8; ch = 5.5; }
            else if (live) { cw = 1.45; ch = 4.8; }
            else { cw = 0.75; ch = 3.2; }
            ch = Math.min(ch, H - 1.5);
            boolean broken = !live && u(k24, 7) < 0.5 && !deep;
            double Htop = broken ? H * (1 - (0.1 + 0.3 * u(k24, 16))) : H;
            double hd = form == 0 ? Math.min(H - 2, 6 + 2 * u(k24, 10)) : Math.min(H - 3, 6 + 2 * u(k24, 10));
            if (broken) Htop = Math.max(Htop, hd + 2);
            int P = 5 + (int) (5 * u(k24, 4));
            int nflMax;
            double Fl;
            if (form == 1) { nflMax = 4; Fl = 1.5 + 1.5 * u(k24, 6); }
            else if (form == 2) { nflMax = u(k24, 4) < 0.4 ? 1 : 0; Fl = 1.2 + 1.0 * u(k24, 6); }
            else if (deep) { nflMax = 2; Fl = 1.5 + 1.0 * u(k24, 6); }
            else { nflMax = 0; Fl = 0.0; }
            List<Double> rows = new ArrayList<>();
            double yPrev = -99;
            for (int j = 0; j < nflMax; j++) {
                double yj = ys + Math.max(6, 0.3 * H) + (int) (3 * u(k24, 5)) + j * P + Math.rint(2 * (2 * u(k24, 40 + j) - 1));
                if (yj > ys + Htop - 3 || yj - yPrev < 3) continue;
                rows.add(yj);
                yPrev = yj;
            }
            int nfl = Math.min(4, rows.size());
            for (int j = 0; j < nfl; j++) w.fly[j] = (float) (double) rows.get(j);
            double aTop = form == 0 ? 1.5 : (broken ? 2.0 : 1.2);
            int nrim = (form == 1 && H >= 16 && !broken) ? Math.min(3, (int) (u(k24, 9) * (1 + H / 14))) : 0;
            double topMin = ys + Htop - aTop;
            for (int j = 0; j < nrim; j++) {
                w.rim[j][0] = (float) (2 * Math.PI * u(k24, 48 + j));
                w.rim[j][1] = (float) (4 + 4 * u(k24, 52 + j));
                w.rim[j][2] = (float) (topMin - 2);
            }
            int ncr = 0;
            for (int j = 0; j < Math.min(3, v.ncr); j++) {
                w.vc[j] = (float) v.cracks[j];
                if (!Double.isNaN(v.cracks[j])) ncr++;
            }
            double hand = deep ? v.hand : 1.0, phiP = 0.0, Rh = 0.0;
            if (deep) {
                phiP = v.a0 + hand * Math.PI / 3;
                Rh = R - wall + 3.8;
            }
            double A = form == 0 ? 0.1 * R + 0.2 : 0.1 * R + 0.3;
            double profMax = form == 1 ? p0 : 1.0;
            double rsMax = R * profMax + Math.abs(bulge) + A + F + 0.5;
            double routMax = R * profMax + Math.abs(bulge) + A + F + Fl + (deep ? 8.0 : 0.0) + 3.0;
            double bound = Math.max(Math.max(routMax + 2, rsMax + 2 * (ys - v.yLo) + 4), rsMax + 7);
            if (deep) bound = Math.max(bound, Rh + 7.0 / 2 + 14);
            float[] t = w.t;
            t[0] = w.idx;
            t[1] = form;
            t[2] = band;
            t[3] = v.k;
            t[4] = (float) v.x;
            t[5] = (float) v.z;
            t[6] = ys;
            t[7] = v.yLo;
            t[8] = (float) H;
            t[9] = (float) R;
            t[10] = (float) v.Rf;
            t[11] = (float) F;
            t[12] = (float) sf;
            t[13] = (float) wall;
            t[14] = (float) (leanS * Math.cos(leanA));
            t[15] = (float) (leanS * Math.sin(leanA));
            t[16] = live ? 1f : 0f;
            t[17] = ncr;
            t[18] = (float) v.a0;
            t[19] = (float) cw;
            t[20] = (float) ch;
            t[21] = nfl;
            t[22] = nfl > 0 ? (float) (double) rows.get(0) : 0f;
            t[23] = P;
            t[24] = (float) Fl;
            t[25] = broken ? 1f : 0f;
            t[26] = (float) Htop;
            t[27] = (float) hd;
            t[28] = nrim;
            t[29] = colour;
            t[30] = (float) v.key;
            t[31] = 0;
            t[32] = (float) (R * profMax + F);
            t[33] = (float) bound;
            t[34] = (float) hand;
            t[35] = (float) phiP;
            t[36] = (float) Rh;
            t[37] = 0;
            t[40] = v.family;
            t[41] = (float) e;
            t[42] = (float) phiE;
            t[43] = (float) p0;
            t[44] = (float) p1;
            t[45] = (float) bulge;
            t[46] = (float) (8 + 10 * u(k24, 34));
            t[47] = 0;
            w.k24 = k24;
            // the preview measured from its float32 table; the game keeps the site's own doubles
            w.cx = plan.patch ? t[4] : v.x;
            w.cz = plan.patch ? t[5] : v.z;
            out.add(w);
        }
        return out;
    }

    // ================================================================================================ the shape

    static double profile(int form, double R, double t, double p0, double p1, double bulge) {
        double b;
        if (form == 0) b = R * (0.85 + 0.15 * (1.0 - t));
        else if (form == 1) b = R * (p0 - p1 * t);
        else if (form == 2) b = R * (p1 + (1.0 - p1) * Math.pow(1.0 - t, p0));
        else return R * (0.42 + 0.58 * Math.pow(1.0 - t, 0.6));
        return b + bulge * Math.sin(Math.PI * t);
    }

    /** _geom's results at one block. */
    static final class Geom {
        double rhoE, th, bl, rin, rout, flare, rho, t, hood;
    }

    /** vents._geom: the radii of vent v at block (x, y, z); {@code useY} is the row the lumps' noise reads. */
    void geom(Vent v, int x, double y, int z, double useY, Geom g) {
        float[] T = v.t;
        double ys = T[6], H = T[8], R = T[9];
        int form = (int) T[1];
        boolean live = T[16] > 0.5f;
        double yp = y - ys, ypc = Math.max(yp, 0.0);
        double ax = v.cx + T[14] * ypc, az = v.cz + T[15] * ypc;
        double dx = (x - ox) + 0.5 - ax, dz = (z - oz) + 0.5 - az;
        // numba takes cos and sin of a float32 in float32
        double ce = (float) Math.cos(T[42]), se = (float) Math.sin(T[42]);
        double a = dx * ce + dz * se, b = -dx * se + dz * ce;
        double e = T[41];
        double rhoE = Math.sqrt(e * a * a + b * b / e);
        double rho = Math.sqrt(dx * dx + dz * dz);
        double th = Math.atan2(dz, dx), c = Math.cos(th), s = Math.sin(th);
        double s1 = 100.0 * u(v.k24, 14), s2 = 100.0 * u(v.k24, 15);
        double t = Math.min(1.0, Math.max(0.0, yp / H));
        double body = profile(form, R, t, T[43], T[44], T[45]);
        double A = form == 0 ? 0.1 * R + 0.2 : 0.1 * R + 0.3;
        double lump = A * (0.6 * n3(plump, 1.1 * c + s1, useY / 6.0, 1.1 * s + s2)
                + 0.4 * n3(plump, 2.3 * c + s1 + 17.0, useY / 3.0, 2.3 * s + s2 - 9.0));
        double wall = T[13];
        double rin = body + lump - wall, rinEff = rin;
        if (live && (form == 2 || form == 3) && t >= 0.8) rinEff = Math.max(rin, 1.5);
        double bl = Math.max(body + lump, rinEff + 1.2);
        double F = T[11], sf = T[12];
        double flare = F * Math.exp(-ypc / sf) * (0.6 + 0.4 * n3(pflare, 1.4 * c + s1, useY / 3.0, 1.4 * s + s2));
        double fl = 0.0;
        if (yp >= 0.0) {
            double Fl = T[24];
            for (int j = 0; j < 4; j++) {
                float yj = v.fly[j];
                if (Float.isNaN(yj)) continue;
                if (y == yj || y == yj - 1) {
                    double m = ramp(n2(pfl, 1.2 * c + 7.1 * j + s1, 1.2 * s - 3.3 * j + s2), -0.12, 0.12);
                    fl = Math.max(fl, (y == yj ? Fl : Math.min(Fl, 1.2)) * m);
                }
            }
        }
        double hood = 0.0;
        if (form == 3) {
            double da = wrap(T[34] * (th - T[35]));
            hood = 8.0 * Math.max(0.0, 1.0 - ypc / 10.0) * ramp(da, -0.35, -0.1) * (1.0 - ramp(da, 1.7, 1.95));
        }
        double belt = (yp >= 3.0 && yp < 5.0) ? 0.8 : 0.0;
        g.rhoE = rhoE;
        g.th = th;
        g.bl = bl;
        g.rin = rinEff;
        g.rout = bl + flare + fl + hood + belt;
        g.flare = flare;
        g.rho = rho;
        g.t = t;
        g.hood = hood;
    }

    double top(Vent v, double th) {
        int form = v.form();
        double a = form == 0 ? 1.5 : (v.t[25] > 0.5f ? 2.0 : 1.2);
        return v.t[6] + v.t[26] + a * n2(pglow, 1.3 * Math.cos(th) + 10.0 * u(v.k24, 12), 1.3 * Math.sin(th));
    }

    /**
     * vents.vent_part: which part of vent v block (x, y, z) is, ignoring other vents and the skirt: 0 none, a VP_ code,
     * {@link #VP_FLARE} for the flare (wall for this vent only), {@link #VP_PLUG} for the plug under the den (fills only
     * air or rubble). {@code ceilLo} is the ceiling of the storey under the vent's (-999 where there is none).
     */
    int part(Vent v, int x, int y, int z, float ceilLo, Geom g) {
        float[] T = v.t;
        double ys = T[6], H = T[8];
        int form = (int) T[1];
        boolean live = T[16] > 0.5f;
        double yp = y - ys;
        if (yp < -40 || yp > H + 14) return 0;
        geom(v, x, y, z, y, g);
        long k24 = v.k24;
        int nrim = (int) T[28];
        for (int j = 0; j < nrim; j++) {
            double beta = v.rim[j][0], hj = v.rim[j][1], y0 = v.rim[j][2];
            if (y >= y0 && y < y0 + hj) {
                double ypc = Math.max(yp, 0.0);
                double ax = v.cx + T[14] * ypc, az = v.cz + T[15] * ypc;
                double b1 = profile(form, T[9], 1.0, T[43], T[44], T[45]);
                double rc = b1 - 0.5 + 0.25 * (y - y0);
                double sx = ax + rc * (float) Math.cos(beta), sz = az + rc * (float) Math.sin(beta);
                double tau = (y - y0) / hj;
                double rr = 1.6 * Math.pow(1.0 - tau, 1.2) + 0.4;
                double ex = (x - ox) + 0.5 - sx, ez = (z - oz) + 0.5 - sz;
                if (ex * ex + ez * ez < rr * rr) return VP_RIM;
            }
        }
        if (form == 3 && yp >= 0 && yp < 4.6) {
            if (Math.abs(wrap(g.th - T[35])) * Math.max(g.rho, 1.0) < 2.3 && g.rho >= g.rin - 1.0 && g.rho <= T[36])
                return VP_ADIT;
        }
        if (g.rhoE >= g.rout) return 0;
        if (yp < 0) {
            if (y >= Math.max(T[7] - 3.0, ceilLo + 2.0)) return VP_PLUG;
            return 0;
        }
        if (y >= top(v, g.th)) return 0;
        double wallCore = g.bl + (g.rout - g.bl - g.flare);
        boolean isFlare = g.rhoE >= wallCore;
        boolean hollow;
        if (live) hollow = g.rhoE < g.rin;
        else {
            double q = g.rhoE / Math.max(g.rin, 0.5);
            hollow = g.rhoE < g.rin && yp < T[27] - 2.5 * (q * q);
        }
        if (hollow) {
            double hden = !live ? T[27] : Math.min(H - 3.0, Math.max(T[20] + 1.0, T[27]));
            return yp < hden ? VP_DEN : VP_FLUE;
        }
        if (g.rhoE >= g.rin - 1.0) {
            double cw = T[19], ch = T[20];
            double nu = form == 3 ? 0.0 : (live ? 0.15 : 0.10), tsc = form == 3 ? 0.0 : (live ? 0.25 : 0.15);
            for (int i = 0; i < 3; i++) {
                float alf = v.vc[i];
                if (Float.isNaN(alf)) continue;
                double al = alf;
                if (yp < ch) {
                    double dth = wrap(g.th - al - 0.15 * Math.sin(yp / 2.3 + al));
                    double side = dth >= 0 ? 1.0 : -1.0;
                    double taui = (2.0 * u(k24, 31 + i) - 1.0) * tsc;
                    double hh = ch * (1.0 + taui * side);
                    double q = yp / hh;
                    double ws = (cw * Math.sqrt(Math.max(0.0, 1.0 - q * q)) + 0.35)
                            * (1.0 + nu * n2(pcrk, yp / 2.5 + 13.0 * u(k24, 28 + i), 7.1 * i));
                    ws += 0.6 * ramp(g.rhoE, g.rout - 1.0, g.rout);
                    if (Math.abs(dth) * Math.max(g.rhoE, 1.0) < ws) return VP_CRACK;
                } else {
                    double Li = Math.min((1.6 + 0.9 * u(k24, 25 + i)) * ch, T[26] - 3.0);
                    if (yp < Li && Li > ch) {
                        double dl = 0.3 * (2.0 * u(k24, 28 + i) - 1.0) * (yp - ch) / (Li - ch) + 0.15 * Math.sin(yp / 2.3 + al);
                        if (Math.abs(wrap(g.th - al - dl)) * Math.max(g.rhoE, 1.0) < 0.5 + 0.15 * n2(pcrk, yp / 3.0, 3.7 * i + 20.0))
                            return VP_CRACK;
                    }
                }
            }
        }
        if (g.hood > 0.0 && g.rhoE >= g.bl + g.flare) return VP_HOOD;
        if (isFlare) return VP_FLARE;
        return VP_BODY;
    }

    static boolean isWall(int p) { return p == VP_BODY || p == VP_HOOD || p == VP_RIM || p == VP_FLARE; }

    /** vents.door_box: whether (x, y, z) is in the keep-clear box in front of crack i of vent v (5.4). */
    boolean doorBox(Vent v, int i, int x, int y, int z, Geom g) {
        return doorRows(v, i, y) && doorCol(v, i, x, z, g);
    }

    /** The door box's rows: from the sill up to the crack's top (and none for a shut crack). */
    static boolean doorRows(Vent v, int i, int y) {
        if (Float.isNaN(v.vc[i])) return false;
        double ys = v.t[6], ch = v.t[20];
        return y >= ys && y < ys + ch + 1;
    }

    /** The door box across: within 3 outside the wall at the sill's row, about as wide as the crack. */
    boolean doorCol(Vent v, int i, int x, int z, Geom g) {
        float al = v.vc[i];
        if (Float.isNaN(al)) return false;
        double ys = v.t[6];
        geom(v, x, ys, z, ys, g);
        double rs = g.rout;
        if (g.rhoE < rs || g.rhoE > rs + 3.0) return false;
        return Math.abs(wrap(g.th - al)) * g.rhoE < v.t[19] + 1.35;
    }

    /** The horizontal reach of a vent's door boxes round its centre (door_test's and door_mask's box). */
    static int doorReach(Vent v) { return (int) (v.t[32] + 6) + 4; }

    // ================================================================================================ blocks

    /**
     * A window of the layer's blocks: codes, vent parts and vent ids ({@code vid} is the vent's place in the list + 1),
     * indexed {@code (i * nz + j) * SY + y}.
     */
    static final class Grid {
        final int x0, z0, nx, nz;
        final byte[] mat, vpart;
        final int[] vid;

        Grid(int x0, int z0, int nx, int nz) {
            this.x0 = x0;
            this.z0 = z0;
            this.nx = nx;
            this.nz = nz;
            mat = new byte[nx * nz * SY];
            vpart = new byte[nx * nz * SY];
            vid = new int[nx * nz * SY];
        }

        int at(int x, int y, int z) { return ((x - x0) * nz + (z - z0)) * SY + y; }

        boolean has(int x, int z) { return x >= x0 && z >= z0 && x < x0 + nx && z < z0 + nz; }
    }

    /** The ceiling of the storey under each storey (rows), for the plug rule; -999 where that storey is shut. */
    interface CeilBelow {
        float at(int k, int x, int z);
    }

    /** _floor_under: the first solid row at or under y0 within depth, or -999. */
    static int floorUnder(boolean[] solid, int base, int y0, int depth) {
        for (int y = y0; y > Math.max(0, y0 - depth); y--) if (solid[base + y]) return y;
        return -999;
    }

    /**
     * vents.door_test (M1): a crack is kept if at least {@code share} of its door box is open (air or rubble) outside
     * every other vent's walls, and the floor under at least 90% of its columns lies within 12 under the sill. Returns
     * per vent per crack: -1 none, 0 drop, 1 keep. {@code solid} and {@code talus} cover the window {@code w}.
     */
    int[][] doorTest(List<Vent> vents, boolean[] solid, boolean[] talus, Grid w, CeilBelow cl, double share) {
        int n = vents.size();
        int[][] res = new int[n][3];
        Geom g = new Geom(), g2 = new Geom();
        for (int vi = 0; vi < n; vi++) {
            Vent v = vents.get(vi);
            java.util.Arrays.fill(res[vi], -1);
            int ys = (int) v.t[6];
            double ch = v.t[20];
            int rmax = (int) (v.t[32] + 6.0);
            int icx = (int) Math.floor(v.cx) + ox, icz = (int) Math.floor(v.cz) + oz;
            for (int i = 0; i < 3; i++) {
                if (Float.isNaN(v.vc[i])) continue;
                int tot = 0, good = 0, cols = 0, colsOk = 0;
                for (int x = icx - rmax - 4; x < icx + rmax + 5; x++)
                    for (int z = icz - rmax - 4; z < icz + rmax + 5; z++) {
                        if (plan.patch && (x < plan.px0 + 1 || x >= plan.px0 + plan.psx - 1 || z < plan.pz0 + 1 || z >= plan.pz0 + plan.psz - 1)) continue;
                        if (!w.has(x, z) || !doorCol(v, i, x, z, g)) continue;
                        boolean incol = false;
                        int base = ((x - w.x0) * w.nz + (z - w.z0)) * SY;
                        for (int y = ys; y <= (int) (ys + ch + 1); y++) {
                            if (!doorRows(v, i, y)) continue;
                            incol = true;
                            tot++;
                            boolean ok = !solid[base + y] || talus[base + y];
                            if (ok)
                                for (Vent o : tileList(vents, x, z)) {
                                    if (o == v) continue;
                                    int pw = part(o, x, y, z, cl.at(o.k(), x, z), g2);
                                    if (isWall(pw)) {
                                        ok = false;
                                        break;
                                    }
                                }
                            if (ok) good++;
                        }
                        if (incol) {
                            cols++;
                            int fy = floorUnder(solid, base, ys + 1, 14);
                            if (fy >= ys - 12 && fy <= ys) colsOk++;
                        }
                    }
                if (tot == 0 || cols == 0) res[vi][i] = 0;
                else if (good >= share * tot && colsOk >= 0.9 * cols) res[vi][i] = 1;
                else res[vi][i] = 0;
            }
        }
        return res;
    }

    /** A block of rock as the carve leaves it, before the rubble settles (the game's door test reads a few columns). */
    interface Solid {
        boolean at(int x, int y, int z);
    }

    /**
     * The game's door test for one vent: one round against every other vent's walls, on the carve before the rubble
     * settles (rubble counted open, as the preview's test took it). Per crack -1 none, 0 drop, 1 keep.
     */
    int[] doorTestOne(Vent v, List<Vent> vents, Solid solid, CeilBelow cl, double share) {
        int[] res = {-1, -1, -1};
        Geom g = new Geom(), g2 = new Geom();
        int ys = (int) v.t[6];
        double ch = v.t[20];
        int rmax = (int) (v.t[32] + 6.0);
        int icx = (int) Math.floor(v.cx) + ox, icz = (int) Math.floor(v.cz) + oz;
        List<Vent> others = near(vents, v, rmax + 8);
        for (int i = 0; i < 3; i++) {
            if (Float.isNaN(v.vc[i])) continue;
            int tot = 0, good = 0, cols = 0, colsOk = 0;
            for (int x = icx - rmax - 4; x < icx + rmax + 5; x++)
                for (int z = icz - rmax - 4; z < icz + rmax + 5; z++) {
                    if (!doorCol(v, i, x, z, g)) continue;
                    int tx = Math.floorDiv(x - ox, TILE), tz = Math.floorDiv(z - oz, TILE);
                    boolean incol = false;
                    for (int y = ys; y <= (int) (ys + ch + 1); y++) {
                        if (!doorRows(v, i, y)) continue;
                        incol = true;
                        tot++;
                        boolean ok = !solid.at(x, y, z);
                        if (ok)
                            for (Vent o : others) {
                                if (!onTile(o, tx, tz)) continue;
                                if (isWall(part(o, x, y, z, cl.at(o.k(), x, z), g2))) {
                                    ok = false;
                                    break;
                                }
                            }
                        if (ok) good++;
                    }
                    if (incol) {
                        cols++;
                        int fy = -999;
                        for (int y = ys + 1; y > Math.max(0, ys + 1 - 14); y--)
                            if (solid.at(x, y, z)) {
                                fy = y;
                                break;
                            }
                        if (fy >= ys - 12 && fy <= ys) colsOk++;
                    }
                }
            res[i] = tot == 0 || cols == 0 ? 0 : (good >= share * tot && colsOk >= 0.9 * cols ? 1 : 0);
        }
        return res;
    }

    /** The other vents whose bound could reach within {@code reach} of v's centre. */
    static List<Vent> near(List<Vent> vents, Vent v, double reach) {
        List<Vent> out = new ArrayList<>();
        for (Vent o : vents) {
            if (o == v) continue;
            if (Math.hypot(o.cx - v.cx, o.cz - v.cz) <= o.bound() + reach + 2) out.add(o);
        }
        return out;
    }

    /**
     * The vents whose bound reaches a column's tile (vents.tiles), in list order: the preview's 16-block tiles from its
     * patch corner, the game's from the world's origin.
     */
    List<Vent> tileList(List<Vent> vents, int x, int z) {
        int tx = Math.floorDiv(x - ox, TILE), tz = Math.floorDiv(z - oz, TILE);
        List<Vent> out = new ArrayList<>();
        for (Vent v : vents) if (onTile(v, tx, tz)) out.add(v);
        return out;
    }

    /** vents.tiles' rule: whether vent v's bound disc reaches tile (tx, tz); over the patch in float32, as numpy took it. */
    boolean onTile(Vent v, int tx, int tz) {
        if (plan.patch) {
            float cx = v.t[4], cz = v.t[5], b = v.t[33];
            int t0 = Math.max(0, (int) Math.floor((cx - b) / TILE)), t1 = Math.min(plan.psx / TILE, (int) Math.floor((cx + b) / TILE) + 1);
            int u0 = Math.max(0, (int) Math.floor((cz - b) / TILE)), u1 = Math.min(plan.psz / TILE, (int) Math.floor((cz + b) / TILE) + 1);
            if (tx < t0 || tx >= t1 || tz < u0 || tz >= u1) return false;
            float px = Math.min(Math.max(cx, (float) (tx * TILE)), (float) (tx * TILE + TILE));
            float pz = Math.min(Math.max(cz, (float) (tz * TILE)), (float) (tz * TILE + TILE));
            float dx = px - cx, dz = pz - cz;
            return dx * dx + dz * dz <= b * b;
        }
        double cx = v.cx, cz = v.cz, b = v.bound();
        double px = Math.min(Math.max(cx, tx * TILE), tx * TILE + TILE), pz = Math.min(Math.max(cz, tz * TILE), tz * TILE + TILE);
        return (px - cx) * (px - cx) + (pz - cz) * (pz - cz) <= b * b;
    }

    /**
     * vents._stamp: every vent listed for a column's tile is asked for its part at each row; walls beat other vents'
     * air, door boxes stay clear, air carves only air or rubble (the adit and tubes carve anything), then skirts and
     * flares fill what is left open. Deep tubes are carved last. {@code doorOk[v][i]} is 1 for a kept door.
     */
    void stamp(Grid w, List<Vent> vents, int[][] doorOk, CeilBelow cl, int xa, int za, int xb, int zb) {
        Geom g = new Geom();
        boolean[] dcol = new boolean[48];
        for (int x = xa; x < xb; x++)
            for (int z = za; z < zb; z++) {
                List<Vent> list = tileList(vents, x, z);
                if (list.isEmpty()) continue;
                int base = ((x - w.x0) * w.nz + (z - w.z0)) * SY;
                int ylo = SY, yhi = 0;
                for (Vent v : list) {
                    ylo = Math.min(ylo, (int) v.t[7] - 4);
                    yhi = Math.max(yhi, (int) ((double) (v.t[6] + v.t[8]) + 14.0));
                }
                ylo = Math.max(ylo, 1);
                yhi = Math.min(yhi, SY - 1);
                double S = -1e9;
                Vent sv = null;
                for (Vent v : list) {
                    double ys = v.t[6];
                    geom(v, x, ys, z, ys, g);
                    double s = ys - 1.0 - 0.5 * (g.rhoE - g.rout);
                    if (g.rhoE < g.rout) s = ys - 1.0;
                    if (s >= v.t[7] - 3 && s > S) {
                        S = s;
                        sv = v;
                    }
                }
                // each kept door's box across this column (its rows are tested per row below)
                int nl = list.size();
                if (dcol.length < nl * 3) dcol = new boolean[nl * 3];
                for (int li = 0; li < nl; li++) {
                    Vent v = list.get(li);
                    for (int i = 0; i < 3; i++) dcol[li * 3 + i] = doorOk[v.idx][i] == 1 && doorCol(v, i, x, z, g);
                }
                int colfloor = -999;
                if (sv != null) {
                    int ysv = (int) sv.t[6];
                    for (int y = ysv; y > 0; y--) {
                        byte m = w.mat[base + y];
                        if (m != AIR && m != OIL && m != TALUS) {
                            colfloor = y;
                            break;
                        }
                    }
                }
                for (int y = ylo; y < yhi; y++) {
                    byte m0 = w.mat[base + y];
                    Vent wallV = null, airV = null, aditV = null, flareV = null, plugV = null, doorV = null;
                    int wallCode = 0, airPart = 0;
                    boolean door = false;
                    for (int li = 0; li < nl; li++) {
                        Vent v = list.get(li);
                        int p = part(v, x, y, z, cl.at(v.k(), x, z), g);
                        if (p == VP_BODY || p == VP_HOOD || p == VP_RIM) {
                            if (wallV == null || v.idx < wallV.idx) {
                                wallV = v;
                                wallCode = p;
                            }
                        } else if (p == VP_DEN || p == VP_FLUE || p == VP_CRACK) {
                            if (airV == null) {
                                airV = v;
                                airPart = p;
                            }
                        } else if (p == VP_ADIT) aditV = v;
                        else if (p == VP_FLARE) {
                            if (flareV == null || v.idx < flareV.idx) flareV = v;
                        } else if (p == VP_PLUG) {
                            if (plugV == null || v.idx < plugV.idx) plugV = v;
                        }
                        for (int i = 0; i < 3; i++)
                            if (dcol[li * 3 + i] && doorRows(v, i, y)) {
                                door = true;
                                doorV = v;
                            }
                    }
                    byte out = m0;
                    int op = 0;
                    Vent ov = null;
                    if (door) {
                        if (m0 == TALUS) out = AIR;
                        if (aditV != null) {
                            out = AIR;
                            op = VP_ADIT;
                            ov = aditV;
                        } else if (airV != null && (m0 == AIR || m0 == TALUS)) {
                            out = AIR;
                            op = airPart;
                            ov = airV;
                        } else if (out == AIR || out == OIL) {
                            op = VP_DOOR;
                            ov = doorV;
                        }
                    } else if (wallV != null) {
                        boolean live = wallV.live();
                        if (wallCode == VP_RIM) out = live ? LIVE : DEAD;
                        else out = live ? VLIVE : VDEAD;
                        op = wallCode;
                        ov = wallV;
                    } else if (airV != null && (m0 == AIR || m0 == TALUS || m0 == OIL)) {
                        out = AIR;
                        op = airPart;
                        ov = airV;
                    } else if (aditV != null) {
                        out = AIR;
                        op = VP_ADIT;
                        ov = aditV;
                    } else if (airV != null) {
                        // an air part never carves rock
                    } else if (flareV != null) {
                        out = flareV.live() ? VLIVE : VDEAD;
                        op = VP_BODY;
                        ov = flareV;
                    } else if (plugV != null && (m0 == AIR || m0 == TALUS || m0 == OIL)) {
                        out = plugV.live() ? VLIVE : VDEAD;
                        op = VP_BODY;
                        ov = plugV;
                    } else if (sv != null && y <= Math.floor(S) && y >= sv.t[7] - 3 && y <= sv.t[6] - 1
                            && colfloor >= sv.t[6] - 12 && (m0 == AIR || m0 == OIL)) {
                        byte under = w.mat[base + y - 1];
                        if (under != AIR && under != OIL) {
                            out = ROCK;
                            op = VP_SKIRT;
                            ov = sv;
                        }
                    }
                    if (out != m0 || op != 0) {
                        w.mat[base + y] = out;
                        w.vpart[base + y] = (byte) op;
                        w.vid[base + y] = ov == null ? 0 : ov.idx + 1;
                    }
                }
                for (Vent v : list) {
                    if (v.form() != 3 || v.tab == null || v.tab.length == 0) continue;
                    double cx = v.cx, cz = v.cz, Rh = v.t[36];
                    double dxx = (x - ox) + 0.5 - cx, dzz = (z - oz) + 0.5 - cz;
                    if (Math.abs(Math.sqrt(dxx * dxx + dzz * dzz) - Rh) > PAD) continue;
                    for (int y = 0; y < Math.min((int) v.t[6] + 12, SY); y++) {
                        if (tubeAir(v, x, y, z) >= 0) {
                            w.mat[base + y] = AIR;
                            w.vpart[base + y] = VP_TUBE;
                            w.vid[base + y] = v.idx + 1;
                        }
                    }
                }
            }
    }

    /** vents._rubble: broken dead vents shed piles of 2-4 rubble blocks round their foot. */
    int rubble(Grid w, List<Vent> vents, int[][] doorOk) {
        int cnt = 0;
        Geom g = new Geom();
        for (Vent v : vents) {
            if (v.t[25] < 0.5f) continue;
            long k24 = v.k24;
            double ys = v.t[6];
            int npile = 2 + (int) (3 * u(k24, 11));
            for (int m = 0; m < npile; m++) {
                double ang = 2 * Math.PI * u(k24, 60 + m);
                double r0 = v.t[32];
                double dist = r0 + 1.0 + 4.0 * u(k24, 70 + m);
                int px = (int) Math.floor(v.cx + dist * Math.cos(ang)) + ox, pz = (int) Math.floor(v.cz + dist * Math.sin(ang)) + oz;
                int nb = 2 + (int) (3 * u(k24, 80 + m));
                int placed = 0, bx = px, bz = pz;
                for (int q = 0; q < nb; q++) {
                    if (q > 0) {
                        int c = (int) (4 * u(k24, 90 + 4 * m + q));
                        if (c == 0) { bx = px + 1; bz = pz; }
                        else if (c == 1) { bx = px - 1; bz = pz; }
                        else if (c == 2) { bx = px; bz = pz + 1; }
                        else { bx = px; bz = pz - 1; }
                        if (q == nb - 1 && placed >= 2) { bx = px; bz = pz; }
                    }
                    if (plan.patch && (bx < plan.px0 + 1 || bz < plan.pz0 + 1 || bx >= plan.px0 + plan.psx - 1 || bz >= plan.pz0 + plan.psz - 1)) continue;
                    if (!w.has(bx, bz)) continue;
                    int base = ((bx - w.x0) * w.nz + (bz - w.z0)) * SY;
                    int yy = (int) ys + 2;
                    while (yy > 1 && (w.mat[base + yy - 1] == AIR || w.mat[base + yy - 1] == OIL)) yy--;
                    if (yy < ys - 8 || w.mat[base + yy] != AIR || w.mat[base + yy + 1] != AIR) continue;
                    boolean isDoor = false;
                    for (Vent o : vents)
                        for (int i = 0; i < 3 && !isDoor; i++) if (doorOk[o.idx][i] == 1 && doorBox(o, i, bx, yy, bz, g)) isDoor = true;
                    if (isDoor || w.vpart[base + yy] != 0) continue;
                    w.mat[base + yy] = TALUS;
                    w.vpart[base + yy] = VP_SKIRT;
                    w.vid[base + yy] = v.idx + 1;
                    placed++;
                    cnt++;
                }
            }
        }
        return cnt;
    }

    /** vents.door_mask: every kept door box, over the window. */
    boolean[] doorMask(Grid w, List<Vent> vents, int[][] doorOk) {
        boolean[] m = new boolean[w.nx * w.nz * SY];
        Geom g = new Geom();
        for (Vent v : vents) {
            int ys = (int) v.t[6];
            int r = (int) (v.t[32] + 6.0);
            int icx = (int) Math.floor(v.cx) + ox, icz = (int) Math.floor(v.cz) + oz;
            for (int i = 0; i < 3; i++) {
                if (doorOk[v.idx][i] != 1) continue;
                for (int x = icx - r - 4; x < icx + r + 5; x++)
                    for (int z = icz - r - 4; z < icz + r + 5; z++) {
                        if (!w.has(x, z) || !doorCol(v, i, x, z, g)) continue;
                        int base = ((x - w.x0) * w.nz + (z - w.z0)) * SY;
                        for (int y = ys; y < Math.min(SY, (int) (ys + (double) v.t[20] + 1) + 1); y++)
                            if (doorRows(v, i, y)) m[base + y] = true;
                    }
            }
        }
        return m;
    }

    /** vents.porch_mask: in front of every kept door, out to rout + reach, from ys - below to the top of the door. */
    boolean[] porchMask(Grid w, List<Vent> vents, int[][] doorOk, double reach, int below) {
        boolean[] m = new boolean[w.nx * w.nz * SY];
        Geom g = new Geom();
        for (Vent v : vents) {
            int ys = (int) v.t[6];
            int r = (int) (v.t[32] + reach + 6);
            int icx = (int) Math.floor(v.cx) + ox, icz = (int) Math.floor(v.cz) + oz;
            for (int i = 0; i < 3; i++) {
                if (doorOk[v.idx][i] != 1) continue;
                double al = v.vc[i];
                for (int x = icx - r - 4; x < icx + r + 5; x++)
                    for (int z = icz - r - 4; z < icz + r + 5; z++) {
                        if (!w.has(x, z)) continue;
                        geom(v, x, ys, z, ys, g);
                        if (g.rhoE < g.rout || g.rhoE > g.rout + reach) continue;
                        if (Math.abs(wrap(g.th - al)) * g.rhoE >= v.t[19] + 1.35) continue;
                        int base = ((x - w.x0) * w.nz + (z - w.z0)) * SY;
                        for (int y = Math.max(0, ys - below); y < Math.min(SY, (int) (ys + (double) v.t[20] + 1) + 1); y++) m[base + y] = true;
                    }
            }
        }
        return m;
    }

    /**
     * vents._vent_w: per storey, the largest vent weight per column (spires ring each vent, none inside its inner
     * ring) and that vent's index; {@code mask2d} marks columns inside some vent's inner ring.
     */
    void ventWeights(List<Vent> vents, int x0, int z0, int nx, int nz, float[][] wk, int[][] nearV, boolean[] mask2d) {
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < nz; j++) {
                int q = i * nz + j, x = x0 + i, z = z0 + j;
                for (Vent v : vents) {
                    int k = v.k();
                    double dx = (x - ox) + 0.5 - v.cx, dz = (z - oz) + 0.5 - v.cz;
                    double lim = v.t[10] + 4 + 1.35 * v.t[46] + 3;
                    if (dx * dx + dz * dz > lim * lim) continue;
                    double ce = (float) Math.cos(v.t[42]), se = (float) Math.sin(v.t[42]);
                    double a = dx * ce + dz * se, b = -dx * se + dz * ce;
                    double e = v.t[41];
                    double rhoE = Math.sqrt(e * a * a + b * b / e);
                    double th = Math.atan2(dz, dx);
                    double s1 = 100.0 * u(v.k24, 14), s2 = 100.0 * u(v.k24, 15);
                    double nv = n2(pvn, 1.3 * Math.cos(th) + s1, 1.3 * Math.sin(th) + s2);
                    double nv2 = n2(pvn, 0.9 * Math.cos(th) + s2, 0.9 * Math.sin(th) - s1);
                    double rin = v.t[10] + 1.0 + 2.0 * nv;
                    double rout = v.t[46] * (1.0 + 0.35 * nv2);
                    double wv;
                    if (rhoE < rin) {
                        wv = 1.0;
                        mask2d[q] = true;
                    } else wv = 1.0 - ramp(rhoE - rin, 0.0, rout);
                    if (wv > wk[k][q]) {
                        wk[k][q] = (float) wv;
                        nearV[k][q] = v.idx;
                    }
                }
            }
    }

    // ================================================================================================ the throat

    static double n1(int[] p, double t) { return n2(p, t, 0.37); }

    /** The throat's wander (Taran, 2026-11-18, "Make them wander"): the axis drift's wobble and slow bend, its cap. */
    static final double DRIFT = 4.0, DRIFT_L = 36.0, BEND = 6.0, BEND_L = 90.0, DRIFT_MAX = 9.0;
    /** A column farther than this from the ring (|rho - Rh|) never holds throat. */
    static final double PAD = 21.0;

    /**
     * vents.throat_table: rows of (floor row, radius, width, height, axis drift x, axis drift z) per DS of the helix
     * parameter s. Level for 4, steep to 14, then about 0.36 a block (per block actually walked) down to g, then level
     * for levelLen (6.3). The axis bends slowly and wobbles about once a turn, up to DRIFT_MAX (ramped in over s 30..70,
     * so the den, hood and adit stay on the vent's axis); each turn is lumpy (0.70..1.35 of Rh, ramped in over s
     * 20..40) and the pitch varies.
     */
    static double[][] throatTable(int[] p, double ys, double gRow, double Rh, double ds, double levelLen, double phiP, double d) {
        int n = 6000;
        List<double[]> rows = new ArrayList<>();
        double y = ys, sLevel = -1.0, qx0 = 0.0, qz0 = 0.0;
        for (int i = 0; i < n; i++) {
            double s = i * ds;
            double wob = (0.20 * n1(p, s / 22.0 + 11.0) + 0.06 * n1(p, s / 9.0 + 61.0)) * ramp(s, 20.0, 40.0);
            double r = Rh * Math.min(1.35, Math.max(0.70, 1.0 + wob));
            double rmp = ramp(s, 30.0, 70.0);
            double ax = rmp * (DRIFT * n1(p, s / DRIFT_L + 101.0) + BEND * n1(p, s / BEND_L + 303.0));
            double az = rmp * (DRIFT * n1(p, s / DRIFT_L + 202.0) + BEND * n1(p, s / BEND_L + 404.0));
            double am = Math.sqrt(ax * ax + az * az);
            if (am > DRIFT_MAX) {
                ax *= DRIFT_MAX / am;
                az *= DRIFT_MAX / am;
            }
            double psi = phiP + d * s / Rh;
            double qx = ax + r * Math.cos(psi), qz = az + r * Math.sin(psi);
            double speed = i == 0 ? 1.0 : Math.sqrt((qx - qx0) * (qx - qx0) + (qz - qz0) * (qz - qz0)) / ds;
            qx0 = qx;
            qz0 = qz;
            if (sLevel < 0) {
                double sl = s < 4.0 ? 0.0 : (s < 14.0 ? 0.55 : 0.36 + 0.09 * n1(p, s / 25.0));
                if (i > 0) y -= sl * speed * ds;
                if (y <= gRow) {
                    y = gRow;
                    sLevel = s;
                }
            } else if (s - sLevel > levelLen) break;
            double pk = n1(p, s / 31.0 + 50.0);
            double bump = s > 30.0 ? ramp(pk, 0.55, 0.75) : 0.0;
            rows.add(new double[]{y, r, 4.6 + 0.6 * n1(p, s / 9.0 + 3.0) + 2.5 * bump, 5.0 + 0.8 * n1(p, s / 7.0 + 7.0) + 2.0 * bump, ax, az});
        }
        return rows.toArray(new double[0][]);
    }

    int[] throatPerm(GardensSites.Site s) { return plan.perm(plan.seed + 3708 + 7919 * s.i + 104729 * s.kk); }

    /** vents.tube_air: whether (x, y, z) is inside vent v's helix tube; the turn it is on, or -1. */
    int tubeAir(Vent v, int x, int y, int z) {
        long h = tubeHit(v.tab, v.cx, v.cz, v.t[35], v.t[34], v.t[36], x, y, z);
        return h < 0 ? -1 : (int) (h & 0xFFFF);
    }

    /**
     * vents.tube_hit: the stretch of a throat that holds (x, y, z), packed as (table index << 16 | turn), or -1. For
     * each turn the helix parameter s is found by a few fixed-point steps: the point's angle about the drifted axis,
     * mapped to the turn's branch nearest the last guess. The centre (cx, cz) is in the table's frame.
     */
    long tubeHit(double[][] tab, double cx, double cz, double phiP, double d, double Rh, int x, int y, int z) {
        double px = (x - ox) + 0.5, pz = (z - oz) + 0.5;
        double dx = px - cx, dz = pz - cz;
        double rho = Math.sqrt(dx * dx + dz * dz);
        if (Math.abs(rho - Rh) > PAD) return -1;
        double psi = Math.atan2(dz, dx);
        double dd = d * (psi - phiP);
        while (dd < 0) dd += 2 * Math.PI;
        while (dd >= 2 * Math.PI) dd -= 2 * Math.PI;
        int n = tab.length;
        double sEnd = (n - 1) * DS, turn = 2 * Math.PI * Rh;
        for (int k = 0; k < 10; k++) {
            double s = dd * Rh + k * turn;
            if (s > sEnd + 24.0) break;
            int i0 = Math.min((int) (s / DS), n - 1);
            if (y < tab[i0][0] - 12.0 || y > tab[i0][0] + 21.0) continue;
            for (int it = 0; it < 5; it++) {
                int i = (int) (s / DS);
                if (i < 0) i = 0;
                if (i >= n) i = n - 1;
                double ccx = cx + tab[i][4], ccz = cz + tab[i][5];
                double a = d * (Math.atan2(pz - ccz, px - ccx) - phiP) * Rh;
                double m = Math.floor((s - a) / turn + 0.5);
                double sNew = a + m * turn;
                double moved = Math.abs(sNew - s);
                s = sNew;
                if (moved < 0.05) break;
            }
            if (s < 0.0 || s > sEnd) continue;
            int i = (int) (s / DS);
            double ccx = cx + tab[i][4], ccz = cz + tab[i][5];
            double rr = Math.sqrt((px - ccx) * (px - ccx) + (pz - ccz) * (pz - ccz));
            double e = rr - tab[i][1], tw = tab[i][2];
            double rough = 0.6 * n2(ptube, (x + 0.5) / 4.5, (z + 0.5) / 4.5) * ramp(i * DS, 4.0, 14.0);
            if (Math.abs(e) >= tw / 2 + rough) continue;
            int yb = (int) Math.floor(tab[i][0] + 0.5);
            double q = e / (tw / 2 + 0.5), q2 = q * q;
            double hh = tab[i][3] * Math.sqrt(Math.max(0.0, 1.0 - q2 * q2)) + 0.5 * n3(ptube, x / 4.5, y / 4.5 + 9.0, z / 4.5);
            if (y >= yb && y < yb + hh) return ((long) i << 16) | k;
        }
        return -1;
    }
}
