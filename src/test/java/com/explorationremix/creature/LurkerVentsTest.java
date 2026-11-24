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
 * The chimney lurkers' vents on the test seed's real vent field (0.21.0), read from the field's own blocks: about half
 * the live vents hold one (Taran's card: "About half of them"), each holding vent has a spot in its mouth, and from that
 * spot its way out over the rim comes down to the floor and its way home from there climbs back over the rim into its
 * mouth (the crawl the game runs, over the real vents' shapes: rims, flanges, leaning flues, terraced feet).
 */
@Tag("slow")
class LurkerVentsTest {
    static final long SEED = 8675309L;

    @Test
    void ventsHoldLurkersAndTheirWaysWork() {
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
        double ride = LurkerRig.shared().spec.ride;
        List<GardensField.LiveVent> vents = f.liveVents(x0, z0, x0 + size, z0 + size);
        int narrow = 0, held = 0, homes = 0, out = 0, back = 0;
        List<String> lines = new ArrayList<>(), fails = new ArrayList<>();
        int roomy = 0, could = 0;
        for (GardensField.LiveVent v : vents) {
            if (v.flue() < LurkerVents.MIN_FLUE) narrow++;
            else if (Boolean.getBoolean("lurker.could")) {
                // how many could hold one at all (for setting HELD): every roomy vent tried, held or not
                roomy++;
                if (LurkerVents.find(solid, rock, v, bottom, (x, z) -> true) != null) could++;
            }
            if (!LurkerVents.held(v)) continue;
            held++;
            String at = String.format(Locale.ROOT, "%s vent at %.1f %.1f (rim %.1f, flue %.1f, wall %.1f, %d cracks, form %d)",
                    com.explorationremix.block.Neon.ofCode(v.colour()).getSerializedName(), v.x(), v.z(), v.topRow() + bottom, v.flue(), v.wall(),
                    v.cracks().length, v.form());
            LurkerVents.Home h = LurkerVents.find(solid, rock, v, bottom, (x, z) -> true);
            if (h == null) { fails.add(at + ": no spot in its mouth"); continue; }
            homes++;
            Vector3d foot = LurkerVents.footOf(rock, h);
            if (foot == null) { fails.add(at + String.format(Locale.ROOT, ": its way out from %.1f %.1f %.1f never reaches a floor", h.waitX(), h.waitY(), h.waitZ())); continue; }
            out++;
            // home: from the foot of its way out, in and up over the rim, down into its mouth (as ChimneyLurkerEntity.climb)
            LurkerCrawl c = new LurkerCrawl(ride);
            c.start(new Vector3d(foot), h.x(), h.z(), LurkerCrawl.Sense.IN);
            LurkerVents.In judge = new LurkerVents.In(h);
            String result = "never arrives";
            Vector3d from = new Vector3d(c.pos);
            for (int i = 0; i < 900; i++) {
                if (!c.step(rock, ChimneyLurkerEntity.HOME_CRAWL)) { result = String.format(Locale.ROOT, "loses the rock at %.1f %.1f %.1f", c.pos.x, c.pos.y, c.pos.z); break; }
                LurkerVents.Verdict j = judge.judge(c);
                if (j == LurkerVents.Verdict.HOME) {
                    // back in its own mouth, near its spot: within a block or two of it, on the same side
                    double off = Math.hypot(c.pos.x - h.waitX(), c.pos.z - h.waitZ()), dy = Math.abs(c.pos.y - h.waitY());
                    result = off < 2.5 && dy < 2.5 ? null : String.format(Locale.ROOT, "arrives %.1f from its spot (%.1f up or down) at %.1f %.1f %.1f",
                            off, dy, c.pos.x, c.pos.y, c.pos.z);
                    break;
                }
                if (j == LurkerVents.Verdict.LOST) { result = String.format(Locale.ROOT, "loses its way at %.1f %.1f %.1f (%.1f off its axis, %.1f under its rim)",
                        c.pos.x, c.pos.y, c.pos.z, h.across(c.pos.x, c.pos.z), h.top() - c.pos.y); break; }
                if (i % 30 == 29) {
                    if (from.distanceSquared(c.pos) < 0.25) { result = String.format(Locale.ROOT, "sticks at %.1f %.1f %.1f", c.pos.x, c.pos.y, c.pos.z); break; }
                    from.set(c.pos);
                }
            }
            if (result != null) { fails.add(at + ": its climb home from " + fmt(foot) + " " + result); continue; }
            back++;
            lines.add(String.format(Locale.ROOT, "%s: waits at %.1f %.1f %.1f (%.1f under its rim), out to the floor at %s (%.1f off its axis, %.1f under its rim), home in %d ticks, back at %s",
                    at, h.waitX(), h.waitY(), h.waitZ(), h.top() - h.waitY(), fmt(foot), h.across(foot.x, foot.z), h.top() - foot.y, c.ticks, fmt(c.pos)));
        }
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "%d live vents in %d x %d at %d %d: %d too narrow, %d chosen to hold a lurker, %d with a spot (%.0f%% of all), %d reach the floor, %d climb home%n",
                vents.size(), size, size, x0, z0, narrow, held, homes, 100.0 * homes / Math.max(1, vents.size()), out, back));
        if (roomy > 0) b.append(String.format(Locale.ROOT, "  of the %d roomy vents, %d could hold one (%.0f%% of all live vents)%n", roomy, could, 100.0 * could / vents.size()));
        for (String l : lines) b.append("  ").append(l).append('\n');
        for (String l : fails) b.append("  FAIL ").append(l).append('\n');
        System.out.println(b);
        // what a player meets: the vents with a lurker waiting in them (a held vent with no side that will do has none)
        double share = homes / (double) Math.max(1, vents.size());
        assertTrue(share > 0.38 && share < 0.62, "about half the live vents hold a lurker: " + b);
        assertTrue(back >= homes * 0.95, "nearly every lurker's way out and home works: " + b);
    }

    private static String fmt(Vector3d v) { return String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x, v.y, v.z); }

    /**
     * For looking into vents (-Dlurker.trace=x,z;x,z;... near their axes): each of its twelve sides and why it will or
     * will not do for its spot; then, on its spot's side (or -Dlurker.az=degrees), a slice of its blocks in the upright
     * plane through its axis (# rock, . open, w its spot, o its way out, i its way home) and both crawls' steps.
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
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%nvent at %.1f %.1f rim %.1f flue %.1f wall %.1f form %d cracks %s held %s%n", v.x(), v.z(), v.topRow() + bottom, v.flue(), v.wall(),
                v.form(), java.util.Arrays.toString(java.util.Arrays.stream(v.cracks()).map(Math::toDegrees).map(Math::round).toArray()), LurkerVents.held(v)));
        LurkerVents.Home chosen = null, first = null;
        double phase = LurkerVents.phase(v);
        for (int i = 0; i < 12; i++) {
            double az = phase + i * (2.0 * Math.PI / 12.0);
            StringBuilder why = new StringBuilder();
            LurkerVents.Home h = LurkerVents.spotAt(solid, rock, v, bottom, (x, z) -> true, az, why);
            if (first == null) first = LurkerVents.spotAt(solid, null, v, bottom, (x, z) -> true, az, null);
            out.append(String.format(Locale.ROOT, "  side %3.0f deg: %s%n", Math.toDegrees(LurkerVents.LurkerCrawlMath.wrap(az)),
                    h != null ? String.format(Locale.ROOT, "spot %.1f %.1f %.1f, rim %.1f", h.waitX(), h.waitY(), h.waitZ(), h.top()) : why));
            if (h != null && chosen == null) chosen = h;
        }
        String forced = System.getProperty("lurker.az");
        LurkerVents.Home h = chosen != null ? chosen : first;
        if (forced != null) h = LurkerVents.spotAt(solid, null, v, bottom, (x, z) -> true, Math.toRadians(Double.parseDouble(forced)), out);
        System.out.print(out);
        if (h == null) { System.out.println("  (no side to trace)"); return; }
        double az = h.azimuth();
        double c = Math.cos(az), sn = Math.sin(az);
        java.util.Map<Long, Character> marks = new java.util.HashMap<>();
        java.util.function.BiConsumer<Vector3d, Character> mark = (p, ch) -> {
            double s = (p.x - v.x()) * c + (p.z - v.z()) * sn;
            marks.put(((long) Math.floor(s * 2) << 32) ^ ((long) Math.floor(p.y) & 0xFFFFFFFFL), ch);
        };
        StringBuilder trace = new StringBuilder();
        trace.append(String.format(Locale.ROOT, "  traced side %.0f deg, spot %.1f %.1f %.1f, rim %.1f, wall %.1f%n", Math.toDegrees(az), h.waitX(), h.waitY(), h.waitZ(), h.top(), h.wall()));
        mark.accept(new Vector3d(h.waitX(), h.waitY(), h.waitZ()), 'w');
        LurkerCrawl o = new LurkerCrawl(ride);
        o.start(new Vector3d(h.waitX(), h.waitY(), h.waitZ()), h.x(), h.z(), LurkerCrawl.Sense.OUT);
        LurkerVents.Out oj = new LurkerVents.Out(h, new Vector3d(o.pos));
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
                if (i % 8 == 0 || j != LurkerVents.Verdict.ON) trace.append(String.format(Locale.ROOT, "  in %3d: across %.2f, %.2f under the rim, ns %.2f ny %.2f %s%n",
                        i, h.across(in.pos.x, in.pos.z), h.top() - in.pos.y, in.facingOut(), in.facingUp(), j));
                if (j != LurkerVents.Verdict.ON) break;
            }
        }
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
