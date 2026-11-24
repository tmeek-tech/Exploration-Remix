package com.explorationremix.creature;

import com.explorationremix.creature.body.LurkerCrawl;
import com.explorationremix.creature.body.LurkerRig;
import com.explorationremix.creature.body.Terrain;
import com.explorationremix.world.terrain.GardensField;
import com.explorationremix.world.terrain.TerrainSettings;
import org.joml.Vector3d;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vent urchins' vents on the test seed's real vent field (0.21.3), read from the field's own blocks: about half the
 * live vents hold one (Taran's card: "About half of them"), each sat in its vent's mouth (Taran's 0.21.3 card: "Half
 * sunk into the mouth") with most of its spines on the rim, and from over the rim on its side its way down comes to the
 * floor and its way home from there climbs back over the rim to its mouth's edge (the crawl the game runs, over the real
 * vents' shapes: rims, flanges, leaning flues, terraced feet).
 */
@Tag("slow")
class LurkerVentsTest {
    static final long SEED = 8675309L;

    @Test
    void ventsHoldUrchinsAndTheirWaysWork() {
        com.explorationremix.world.terrain.Terrain t = new com.explorationremix.world.terrain.Terrain(TerrainSettings.defaults(), -64, SEED);
        GardensField f = t.gardens;
        int bottom = t.shape.bottom(com.explorationremix.world.terrain.Terrain.GARDENS);
        int rows = t.shape.height(com.explorationremix.world.terrain.Terrain.GARDENS);
        int x0 = Integer.getInteger("lurker.x0", -512), z0 = Integer.getInteger("lurker.z0", -512), size = Integer.getInteger("lurker.size", 1024);
        LurkerVents.Solid solid = (x, y, z) -> {
            int row = y - bottom;
            return row < 0 || row >= rows || !f.open(x, row, z);
        };
        Terrain rock = (x, y, z, sink) -> { if (solid.at(x, y, z)) sink.box(x, y, z, x + 1, y + 1, z + 1); };
        LurkerVents.Readable all = (x, z) -> true;
        List<GardensField.LiveVent> vents = f.liveVents(x0, z0, x0 + size, z0 + size);
        int narrow = 0, wide = 0, held = 0, homes = 0, out = 0, back = 0, flueDown = 0;
        double ride = com.explorationremix.creature.body.LurkerRig.shared().spec.ride;
        List<String> lines = new ArrayList<>(), fails = new ArrayList<>();
        boolean could = Boolean.getBoolean("lurker.could");
        // for setting MIN_FLUE, MAX_FLUE and HELD: every vent tried, by its flue's radius (half-block bins)
        int[] binAll = new int[16], binCould = new int[16];
        java.util.Map<String, Integer> whyNot = new java.util.TreeMap<>();
        for (GardensField.LiveVent v : vents) {
            if (v.flue() < LurkerVents.MIN_FLUE) narrow++;
            else if (v.flue() > LurkerVents.MAX_FLUE) wide++;
            if (could) {
                int bin = Math.min(15, (int) Math.floor(v.flue() * 2));
                binAll[bin]++;
                StringBuilder why = new StringBuilder();
                LurkerVents.Mouth m = LurkerVents.mouth(solid, rock, v, bottom, all, why);
                if (m != null && LurkerVents.find(solid, rock, v, bottom, all, null) != null) binCould[bin]++;
                else whyNot.merge(m == null ? why.toString().replaceAll("[0-9.\\-]+", "#") : "no side", 1, Integer::sum);
            }
            if (!LurkerVents.held(v)) continue;
            held++;
            String at = String.format(Locale.ROOT, "%s vent at %.1f %.1f (rim %.1f, flue %.1f, wall %.1f, %d cracks, form %d)",
                    com.explorationremix.block.Neon.ofCode(v.colour()).getSerializedName(), v.x(), v.z(), v.topRow() + bottom, v.flue(), v.wall(),
                    v.cracks().length, v.form());
            LurkerVents.Home h = LurkerVents.find(solid, rock, v, bottom, all, null);
            if (h == null) {
                StringBuilder report = new StringBuilder();
                LurkerVents.find(solid, rock, v, bottom, all, report);
                fails.add(at + ": no home (" + report.toString().replace("\n", ";") + ")");
                continue;
            }
            homes++;
            // 0.21.4 (card: "Climbs down inside"): its way down the inside of its flue to the floor under it
            List<Vector3d> flue = LurkerVents.flueWay(solid, all, h);
            Vector3d flueEnd = flue == null ? null : flue.get(flue.size() - 1);
            boolean flueFloor = flueEnd != null && solid.at((int) Math.floor(flueEnd.x), (int) Math.floor(flueEnd.y - ride - 0.01), (int) Math.floor(flueEnd.z));
            if (flueFloor) flueDown++;
            else fails.add(at + (flueEnd == null ? ": no way down its flue" : ": its way down its flue sticks at " + fmt(flueEnd)));
            String flueAt = flueEnd == null ? "no way down its flue" : String.format(Locale.ROOT, "down its flue %.1f to %s%s", h.waitY() - flueEnd.y, fmt(flueEnd),
                    flueFloor ? "" : " (stuck)");
            int on = LurkerVents.onRim(rock, new Vector3d(h.waitX(), h.waitY(), h.waitZ()), h.top());
            Vector3d crest = LurkerVents.crest(solid, all, h, h.azimuth());
            StringBuilder why = new StringBuilder();
            LurkerVents.Trial down = crest == null ? null : LurkerVents.trialOut(rock, h, crest, all, why);
            if (down == null || down.verdict() != LurkerVents.Verdict.FLOOR) {
                fails.add(at + String.format(Locale.ROOT, ": its way down from its seat %.1f %.1f %.1f never reaches a floor (%s)", h.waitX(), h.waitY(), h.waitZ(), why));
                continue;
            }
            out++;
            Vector3d foot = down.at();
            LurkerVents.Trial up = LurkerVents.trialHome(rock, h, crest, foot, all, why);
            if (up.verdict() != LurkerVents.Verdict.HOME) { fails.add(at + ": its climb home from " + fmt(foot) + " fails (" + why + ")"); continue; }
            back++;
            lines.add(String.format(Locale.ROOT, "%s: sat at %.1f %.1f %.1f (%.2f over its rim's top, its mouth's edge %.1f out, %d spines on the rim), over the rim at %s,"
                            + " down to the floor at %s (%.1f off its axis, %.1f under its rim), back over at %s; %s",
                    at, h.waitX(), h.waitY(), h.waitZ(), h.waitY() - h.top(), h.mouth(), on, fmt(crest), fmt(foot), h.across(foot.x, foot.z), h.top() - foot.y,
                    fmt(up.at()), flueAt));
        }
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "%d live vents in %d x %d at %d %d: %d too narrow, %d too wide, %d chosen to hold an urchin, %d with a home (%.0f%% of all), %d reach the floor, %d climb home, %d climb down their flue to its floor%n",
                vents.size(), size, size, x0, z0, narrow, wide, held, homes, 100.0 * homes / Math.max(1, vents.size()), out, back, flueDown));
        if (could) {
            int ca = 0, cc = 0;
            for (int i = 0; i < 16; i++) {
                if (binAll[i] == 0) continue;
                ca += binAll[i];
                cc += binCould[i];
                b.append(String.format(Locale.ROOT, "  flue %.1f-%.1f: %d vents, %d could hold one%n", i / 2.0, (i + 1) / 2.0, binAll[i], binCould[i]));
            }
            b.append(String.format(Locale.ROOT, "  %d of %d could hold one (%.0f%%); why not: %s%n", cc, ca, 100.0 * cc / Math.max(1, ca), whyNot));
        }
        for (String l : lines) b.append("  ").append(l).append('\n');
        for (String l : fails) b.append("  FAIL ").append(l).append('\n');
        System.out.println(b);
        // what a player meets: the vents with an urchin sat in them (a held vent with no seat or no side that will do has none)
        double share = homes / (double) Math.max(1, vents.size());
        assertTrue(share > 0.38 && share < 0.62, "about half the live vents hold an urchin: " + b);
        assertTrue(back >= homes * 0.95, "nearly every urchin's way out and home works: " + b);
        // 0.21.4: nearly every urchin can climb down the inside of its flue (one that cannot drops down it, as before)
        assertTrue(flueDown >= homes * 0.9, "nearly every urchin's way down its flue reaches its floor: " + b);
    }

    private static String fmt(Vector3d v) { return String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x, v.y, v.z); }

    /**
     * For setting the seat rules (-Dlurker.seats=true): every live vent with a mouth its test fits, its body laid sat at
     * seats over and round its axis, and how many vents would pass under each rule (how many spines on the rim, how deep
     * under its top they may stand: over its outer edge, straddling).
     */
    @Test
    void seatRules() {
        if (!Boolean.getBoolean("lurker.seats")) return;
        com.explorationremix.world.terrain.Terrain t = new com.explorationremix.world.terrain.Terrain(TerrainSettings.defaults(), -64, SEED);
        GardensField f = t.gardens;
        int bottom = t.shape.bottom(com.explorationremix.world.terrain.Terrain.GARDENS);
        int rows = t.shape.height(com.explorationremix.world.terrain.Terrain.GARDENS);
        LurkerVents.Solid solid = (x, y, z) -> {
            int row = y - bottom;
            return row < 0 || row >= rows || !f.open(x, row, z);
        };
        Terrain rock = (x, y, z, sink) -> { if (solid.at(x, y, z)) sink.box(x, y, z, x + 1, y + 1, z + 1); };
        LurkerVents.Readable all = (x, z) -> true;
        List<GardensField.LiveVent> vents = f.liveVents(-512, -512, 512, 512);
        double[] depths = {1.5, 2.5, 3.5};
        int[] needs = {10, 11, 12, 13};
        int[][] passAxis = new int[depths.length][needs.length], passGrid = new int[depths.length][needs.length];
        StringBuilder b = new StringBuilder();
        int seated = 0;
        for (GardensField.LiveVent v : vents) {
            StringBuilder why = new StringBuilder();
            LurkerVents.Mouth m = LurkerVents.mouth(solid, null, v, bottom, all, why);
            if (m == null) { b.append(String.format(Locale.ROOT, "  %.1f %.1f flue %.1f wall %.1f: %s%n", v.x(), v.z(), v.flue(), v.wall(), why)); continue; }
            seated++;
            int[][] axisBest = new int[depths.length][1], gridBest = new int[depths.length][1];
            String best = "";
            for (int gx = -2; gx <= 2; gx++)
                for (int gz = -2; gz <= 2; gz++) {
                    double x = m.seat.x + gx * 0.4, z = m.seat.z + gz * 0.4;
                    if (!LurkerVents.fits(solid, all, x, m.seat.y, z, 1.0)) continue;
                    int[] on = onRimBands(rock, new Vector3d(x, m.seat.y, z), m.refTop, depths);
                    for (int d = 0; d < depths.length; d++) {
                        if (gx == 0 && gz == 0) axisBest[d][0] = on[d];
                        if (on[d] > gridBest[d][0]) { gridBest[d][0] = on[d]; if (d == 1) best = String.format(Locale.ROOT, "%+.1f %+.1f", gx * 0.4, gz * 0.4); }
                    }
                }
            for (int d = 0; d < depths.length; d++)
                for (int k = 0; k < needs.length; k++) {
                    if (axisBest[d][0] >= needs[k]) passAxis[d][k]++;
                    if (gridBest[d][0] >= needs[k]) passGrid[d][k]++;
                }
            b.append(String.format(Locale.ROOT, "  %.1f %.1f flue %.1f wall %.1f mouth %.1f widest %.1f: at its seat %d/%d/%d spines on the rim (1.5/2.5/3.5 under), best round it %d/%d/%d (%s)%n",
                    v.x(), v.z(), v.flue(), v.wall(), m.mean, m.widest, axisBest[0][0], axisBest[1][0], axisBest[2][0], gridBest[0][0], gridBest[1][0], gridBest[2][0], best));
        }
        b.append(String.format(Locale.ROOT, "%d live vents, %d with a mouth its test fits%n", vents.size(), seated));
        for (int d = 0; d < depths.length; d++)
            for (int k = 0; k < needs.length; k++)
                b.append(String.format(Locale.ROOT, "  %d spines no deeper than %.1f: %d at its seat, %d round it (%.0f%% / %.0f%% of all)%n", needs[k], depths[d],
                        passAxis[d][k], passGrid[d][k], 100.0 * passAxis[d][k] / vents.size(), 100.0 * passGrid[d][k] / vents.size()));
        System.out.println(b);
    }

    /** Its body laid sat on home {@code h}'s seat: where each spine's point stands (off its axis, over or under its rim's top). */
    private static String spines(Terrain rock, LurkerVents.Home h) {
        com.explorationremix.creature.body.LurkerBody body = new com.explorationremix.creature.body.LurkerBody(LurkerRig.shared().spec);
        com.explorationremix.creature.body.LurkerBody.Drive d = new com.explorationremix.creature.body.LurkerBody.Drive();
        d.x = h.waitX(); d.y = h.waitY(); d.z = h.waitZ();
        d.upright = true;
        d.heading.set(0, 1, 0);
        d.mode = com.explorationremix.creature.body.LurkerBody.Mode.SURFACE;
        int[] bb = new int[6];
        body.bounds(d, bb);
        com.explorationremix.creature.body.TerrainPatch patch = new com.explorationremix.creature.body.TerrainPatch();
        patch.build(rock, bb[0], bb[1], bb[2], bb[3], bb[4], bb[5]);
        body.tick(patch, d);
        StringBuilder b = new StringBuilder("  its spines sat:");
        for (com.explorationremix.creature.body.LurkerBody.Foot ft : body.feet) {
            double a = Math.toDegrees(Math.atan2(ft.at.z - h.z(), ft.at.x - h.x()));
            b.append(String.format(Locale.ROOT, " [%d %s %.0f deg %.1f off %+.1f]", ft.index, ft.planted ? "on" : "air", a, h.across(ft.at.x, ft.at.z), ft.at.y - h.top()));
        }
        return b.toString();
    }

    /** Its body laid sat at {@code seat}: how many spines plant no deeper than each of {@code depths} under {@code top}. */
    private static int[] onRimBands(Terrain rock, Vector3d seat, double top, double[] depths) {
        com.explorationremix.creature.body.LurkerBody body = new com.explorationremix.creature.body.LurkerBody(LurkerRig.shared().spec);
        com.explorationremix.creature.body.LurkerBody.Drive d = new com.explorationremix.creature.body.LurkerBody.Drive();
        d.x = seat.x; d.y = seat.y; d.z = seat.z;
        d.upright = true;
        d.heading.set(0, 1, 0);
        d.mode = com.explorationremix.creature.body.LurkerBody.Mode.SURFACE;
        int[] bb = new int[6];
        body.bounds(d, bb);
        com.explorationremix.creature.body.TerrainPatch patch = new com.explorationremix.creature.body.TerrainPatch();
        patch.build(rock, bb[0], bb[1], bb[2], bb[3], bb[4], bb[5]);
        body.tick(patch, d);
        int[] on = new int[depths.length];
        for (com.explorationremix.creature.body.LurkerBody.Foot ft : body.feet)
            if (ft.planted) for (int k = 0; k < depths.length; k++) if (ft.at.y > top - depths[k]) on[k]++;
        return on;
    }

    /**
     * For looking into vents (-Dlurker.trace=x,z;x,z;... near their axes): its seat and each of its sixteen sides and why
     * it will or will not do for its way down; then, on its home's side (or -Dlurker.az=degrees), a slice of its blocks
     * in the upright plane through its axis (# rock, . open, s its seat, c over its rim, o its way down, i its way home)
     * and both crawls' steps.
     */
    @Test
    void traceOne() {
        String at = System.getProperty("lurker.trace");
        if (at == null) return;
        com.explorationremix.world.terrain.Terrain t = new com.explorationremix.world.terrain.Terrain(TerrainSettings.defaults(), -64, SEED);
        GardensField f = t.gardens;
        int bottom = t.shape.bottom(com.explorationremix.world.terrain.Terrain.GARDENS);
        int rows = t.shape.height(com.explorationremix.world.terrain.Terrain.GARDENS);
        LurkerVents.Solid solid = (x, y, z) -> {
            int row = y - bottom;
            return row < 0 || row >= rows || !f.open(x, row, z);
        };
        Terrain rock = (x, y, z, sink) -> { if (solid.at(x, y, z)) sink.box(x, y, z, x + 1, y + 1, z + 1); };
        for (String one : at.split(";")) {
            String[] xz = one.trim().split(",");
            double tx = Double.parseDouble(xz[0]), tz = Double.parseDouble(xz[1]);
            GardensField.LiveVent found = null;
            for (GardensField.LiveVent c : f.liveVents(tx - 8, tz - 8, tx + 8, tz + 8)) found = c;
            if (found == null) { System.out.println("no live vent near " + one); continue; }
            trace(found, solid, rock, bottom);
        }
    }

    private static void trace(GardensField.LiveVent v, LurkerVents.Solid solid, Terrain rock, int bottom) {
        double ride = LurkerRig.shared().spec.ride;
        LurkerVents.Readable all = (x, z) -> true;
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%nvent at %.1f %.1f rim %.1f flue %.1f wall %.1f form %d cracks %s held %s%n", v.x(), v.z(), v.topRow() + bottom, v.flue(), v.wall(),
                v.form(), java.util.Arrays.toString(java.util.Arrays.stream(v.cracks()).map(Math::toDegrees).map(Math::round).toArray()), LurkerVents.held(v)));
        StringBuilder report = new StringBuilder();
        LurkerVents.Home h = LurkerVents.find(solid, rock, v, bottom, all, report);
        out.append("  ").append(report).append('\n');
        String forced = System.getProperty("lurker.az");
        if (h == null || forced != null) {
            // its seat alone (no spines laid), and the side asked for or the first tried
            LurkerVents.Mouth m = LurkerVents.mouth(solid, null, v, bottom, all, out);
            if (m == null) { System.out.print(out); System.out.println("  (no seat to trace from)"); return; }
            double az = forced != null ? Math.toRadians(Double.parseDouble(forced)) : LurkerVents.phase(v);
            h = new LurkerVents.Home(v.key(), v.x(), v.z(), m.refTop, az, m.seat.x, m.seat.y, m.seat.z, m.mean, Math.max(v.wall(), m.widest + 1.0),
                    com.explorationremix.block.Neon.ofCode(v.colour()));
        }
        System.out.print(out);
        double az = h.azimuth();
        double c = Math.cos(az), sn = Math.sin(az);
        java.util.Map<Long, Character> marks = new java.util.HashMap<>();
        java.util.function.BiConsumer<Vector3d, Character> mark = (p, ch) -> {
            double s = (p.x - v.x()) * c + (p.z - v.z()) * sn;
            marks.put(((long) Math.floor(s * 2) << 32) ^ ((long) Math.floor(p.y) & 0xFFFFFFFFL), ch);
        };
        StringBuilder trace = new StringBuilder();
        trace.append(String.format(Locale.ROOT, "  traced side %.0f deg, seat %.1f %.1f %.1f, rim's top %.1f, mouth %.1f, wall %.1f, %d spines on the rim%n",
                Math.toDegrees(az), h.waitX(), h.waitY(), h.waitZ(), h.top(), h.mouth(), h.wall(),
                LurkerVents.onRim(rock, new Vector3d(h.waitX(), h.waitY(), h.waitZ()), h.top())));
        trace.append(spines(rock, h)).append('\n');
        Vector3d crest = LurkerVents.crest(solid, all, h, az);
        if (crest == null) { System.out.print(trace); System.out.println("  (no rim on that side)"); return; }
        LurkerCrawl o = new LurkerCrawl(ride);
        o.start(new Vector3d(crest), h.x(), h.z(), LurkerCrawl.Sense.OUT);
        LurkerVents.Out oj = new LurkerVents.Out(h, new Vector3d(crest));
        Vector3d foot = null;
        for (int i = 0; i < 400; i++) {
            if (!o.step(rock, ChimneyLurkerEntity.EMERGE_CRAWL)) { trace.append("  out: lost the rock\n"); break; }
            mark.accept(o.pos, 'o');
            LurkerVents.Verdict j = oj.judge(o);
            if (i % 4 == 0 || j != LurkerVents.Verdict.ON) trace.append(String.format(Locale.ROOT, "  out %3d: across %.2f, %.2f under the rim, ns %.2f ny %.2f %s %s%n",
                    i, h.across(o.pos.x, o.pos.z), h.top() - o.pos.y, o.facingOut(), o.facingUp(), j, j == LurkerVents.Verdict.LOST ? oj.why() : ""));
            if (j == LurkerVents.Verdict.FLOOR) { foot = new Vector3d(o.pos); break; }
            if (j == LurkerVents.Verdict.LOST) break;
        }
        if (foot != null) {
            LurkerCrawl in = new LurkerCrawl(ride);
            in.start(foot, h.x(), h.z(), LurkerCrawl.Sense.IN);
            LurkerVents.In ij = new LurkerVents.In(h);
            for (int i = 0; i < 900; i++) {
                if (!in.step(rock, ChimneyLurkerEntity.HOME_CRAWL)) { trace.append("  in: lost the rock\n"); break; }
                mark.accept(in.pos, 'i');
                LurkerVents.Verdict j = ij.judge(in);
                if (i % 8 == 0 || j != LurkerVents.Verdict.ON) trace.append(String.format(Locale.ROOT, "  in %3d: across %.2f, %.2f under the rim, ns %.2f ny %.2f %s %s%n",
                        i, h.across(in.pos.x, in.pos.z), h.top() - in.pos.y, in.facingOut(), in.facingUp(), j, j == LurkerVents.Verdict.LOST ? ij.why() : ""));
                if (j != LurkerVents.Verdict.ON) break;
            }
        }
        mark.accept(new Vector3d(h.waitX(), h.waitY(), h.waitZ()), 's');
        mark.accept(crest, 'c');
        System.out.print(trace);
        // the slice: s from -10 to +24 across (half blocks), rows from the rim + 6 down to 34 under it
        int top = (int) Math.floor(h.top());
        StringBuilder b = new StringBuilder();
        for (int y = top + 6; y >= top - 34; y--) {
            b.append(String.format(Locale.ROOT, "%5d ", y));
            for (int k = -20; k <= 48; k++) {
                double s = k / 2.0 + 0.25;
                int x = (int) Math.floor(v.x() + c * s), z = (int) Math.floor(v.z() + sn * s);
                Character m = marks.get(((long) k << 32) ^ (y & 0xFFFFFFFFL));
                b.append(m != null ? m : solid.at(x, y, z) ? '#' : '.');
            }
            b.append('\n');
        }
        System.out.print(b);
    }
}
