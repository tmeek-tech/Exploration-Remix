package com.explorationremix.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The volcanic smoke of the Galleries and the Wasteland (0.6.0): registered with sprites of its own; each puff rises
 * 40 to 100 blocks, slowing, swells and fades, and spreads under the rock it meets instead of stopping dead; a vent's
 * puffs stand as an unbroken column; the plumes rise over the lava under open air, and the openings' plumes out through
 * the hole; and the client's count of puffs.
 */
class VolcanicSmokeTest {
    private static final SmokePuff.Rock NONE = (x, y, z) -> false;

    // ------------------------------------------------------------------------------------------------ registration

    @Test
    void registeredWithSpritesOfItsOwn() throws IOException {
        String particles = Files.readString(Path.of("src/main/java/com/explorationremix/registry/ERParticles.java"));
        assertTrue(particles.contains("register(\"volcanic_smoke\", () -> new SimpleParticleType(true))"),
                "ERParticles registers volcanic_smoke, never held back by distance");
        String setup = Files.readString(Path.of("src/main/java/com/explorationremix/client/ClientSetup.java"));
        assertTrue(setup.contains("registerSpriteSet(ERParticles.VOLCANIC_SMOKE.get(), CaveParticles.VolcanicSmoke.Provider::new)"),
                "ClientSetup gives the smoke its provider");
        String air = Files.readString(Path.of("src/main/java/com/explorationremix/client/CaveAtmosphere.java"));
        assertTrue(air.contains("ERParticles.VOLCANIC_SMOKE.get()"), "CaveAtmosphere spawns the smoke");

        Path assets = Path.of("src/main/resources/assets/explorationremix");
        JsonElement json = JsonParser.parseString(Files.readString(assets.resolve("particles/volcanic_smoke.json")));
        List<String> sprites = new ArrayList<>();
        json.getAsJsonObject().getAsJsonArray("textures").forEach(e -> sprites.add(e.getAsString()));
        assertFalse(sprites.isEmpty(), "volcanic_smoke.json lists its sprites");
        for (String sprite : sprites) {
            assertTrue(sprite.startsWith("explorationremix:"), sprite + ": the smoke's own sprite");
            Path png = assets.resolve("textures/particle/" + sprite.substring(sprite.indexOf(':') + 1) + ".png");
            assertTrue(Files.exists(png), png + " is missing");
            BufferedImage img = ImageIO.read(png.toFile());
            assertEquals(img.getWidth(), img.getHeight(), png + " is square");
            int s = img.getWidth() - 1;
            for (int[] c : new int[][]{{0, 0}, {s, 0}, {0, s}, {s, s}})
                assertEquals(0, alpha(img, c[0], c[1]), png + ": a corner is clear (the quad turns)");
            assertEquals(255, alpha(img, s / 2, s / 2), png + ": the puff's core is thick");
            Set<Integer> rim = new TreeSet<>();
            for (int y = 0; y <= s; y++) for (int x = 0; x <= s; x++) {
                int a = alpha(img, x, y);
                if (a > 0 && a < 255) rim.add(a);
            }
            assertTrue(rim.size() >= 2 && rim.size() <= 4, png + ": the rim thins in a few hard steps, " + rim);
        }
    }

    private static int alpha(BufferedImage img, int x, int y) { return img.getRGB(x, y) >>> 24; }

    // ------------------------------------------------------------------------------------------------- one puff

    /** A puff over lava at y 0, run to the end of its life in {@code rock}; returns its heights, one a tick. */
    private static double[] follow(SmokePuff p, SmokePuff.Rock rock, Random r) {
        double[] ys = new double[p.life + 1];
        ys[0] = p.y;
        while (p.age < p.life) {
            p.tick(rock, r::nextDouble);
            ys[p.age] = p.y;
        }
        return ys;
    }

    @Test
    void aPuffRisesItsReachSlowingAsItCools() {
        Random r = new Random(1);
        for (double reach : new double[]{SmokePuff.REACH_MIN, 70, SmokePuff.REACH_MAX}) {
            SmokePuff p = new SmokePuff(0.5, 1.1, 0.5, 1.0, reach, r::nextDouble);
            assertTrue(p.life >= 600 && p.life <= 1300, "life " + p.life + " for a rise of " + reach);
            double[] ys = follow(p, NONE, r);
            double rise = ys[p.life] - ys[0];
            assertTrue(rise > 0.95 * reach && rise < 1.05 * reach, "rose " + rise + " of " + reach);
            int fifth = p.life / 5;
            double early = ys[2 * fifth] - ys[fifth], late = ys[p.life] - ys[p.life - fifth];
            assertTrue(early > 2.5 * late, "slows as it cools: " + early + " then " + late);
        }
    }

    @Test
    void aPuffSwellsAndFades() {
        Random r = new Random(2);
        for (int i = 0; i < 20; i++) {
            SmokePuff p = new SmokePuff(0.5, 1.1, 0.5, 1.0, 40 + 60 * r.nextDouble(), r::nextDouble);
            float w0 = 2 * p.size();
            assertTrue(w0 >= 1.9 && w0 <= 2.7, "born " + w0 + " blocks across");
            assertEquals(0, p.alpha(), 1e-6, "born clear");
            float last = p.size(), peak = 0;
            boolean thinning = false;
            float before = 0;
            while (p.age < p.life) {
                p.tick(NONE, r::nextDouble);
                assertTrue(p.size() >= last, "never shrinks");
                last = p.size();
                float a = p.alpha();
                if (a < before - 1e-6) thinning = true;
                else assertFalse(thinning && a > before + 1e-6, "thickens once and thins once");
                before = a;
                peak = Math.max(peak, a);
            }
            assertTrue(2 * last >= 6.7 && 2 * last <= 8.9, "ends " + 2 * last + " blocks across");
            assertTrue(peak >= 0.4 && peak <= 0.5, "peak opacity " + peak);
            assertEquals(0, p.alpha(), 1e-6, "gone at the end");
        }
    }

    @Test
    void underAVaultItSpreadsAndLingers() {
        // a vault 30 over the lava, wide in every way
        SmokePuff.Rock vault = (x, y, z) -> y >= 30;
        double sum = 0;
        int seeds = 12;
        for (int seed = 0; seed < seeds; seed++) {
            Random r = new Random(seed);
            SmokePuff p = new SmokePuff(0.5, 1.1, 0.5, 1.0, 80, r::nextDouble);
            double cx = Double.NaN, cz = Double.NaN, moved = 0;
            int capped = 0;
            while (p.age < p.life) {
                p.tick(vault, r::nextDouble);
                assertTrue(p.y < 30 && p.y + SmokePuff.HEAD < 30.5, "never into the rock: " + p.y);
                if (p.capped) {
                    capped++;
                    if (Double.isNaN(cx)) {
                        cx = p.x;
                        cz = p.z;
                    }
                    moved = Math.max(moved, Math.hypot(p.x - cx, p.z - cz));
                }
            }
            assertEquals(1, p.spreads, "met the vault once and stayed under it");
            assertTrue(capped > 0.4 * p.life, "lingers under the vault: " + capped + " of " + p.life + " ticks");
            // pushed out from where it met the vault (the slow draught may bring it some way back)
            assertTrue(moved >= 1.5, "spread " + moved + " blocks along the vault");
            sum += moved;
        }
        assertTrue(sum / seeds >= 3, "spreads " + sum / seeds + " blocks on average");
    }

    @Test
    void itSlidesOutFromUnderAHangingMassAndRisesOn() {
        // a hanging mass of columns five blocks across, its foot 20 over the lava, right over the vent
        SmokePuff.Rock mass = (x, y, z) -> y >= 20 && Math.floor(x) >= -2 && Math.floor(x) <= 2 && Math.floor(z) >= -2 && Math.floor(z) <= 2;
        int risen = 0, seeds = 12;
        for (int seed = 0; seed < seeds; seed++) {
            Random r = new Random(seed);
            SmokePuff p = new SmokePuff(0.5, 1.1, 0.5, 1.0, 80, r::nextDouble);
            double top = 0;
            while (p.age < p.life) {
                p.tick(mass, r::nextDouble);
                assertFalse(mass.at(p.x, p.y, p.z), "never into the rock");
                top = Math.max(top, p.y);
            }
            assertTrue(p.spreads >= 1, "met the mass");
            if (top > 40) risen++;
        }
        assertTrue(risen >= seeds * 2 / 3, risen + " of " + seeds + " slid out and rose on past the mass");
    }

    @Test
    void aVentsPuffsStandAsAnUnbrokenColumn() {
        for (int seed = 0; seed < 6; seed++) {
            Random r = new Random(seed);
            double rise = SmokeColumns.RISE_MIN + (SmokeColumns.RISE_MAX - SmokeColumns.RISE_MIN) * r.nextDouble();
            List<SmokePuff> puffs = new ArrayList<>();
            for (int t = 0; t < 2600; t++) {
                if (t % SmokeColumns.BEAT == 0) {
                    double reach = rise * (1 + SmokeColumns.RISE_JITTER * (2 * r.nextDouble() - 1));
                    assertTrue(reach >= SmokePuff.REACH_MIN && reach <= SmokePuff.REACH_MAX, "rise " + reach);
                    puffs.add(new SmokePuff(r.nextDouble(), 1.1, r.nextDouble(), 1.0, reach, r::nextDouble));
                }
                for (SmokePuff p : puffs) p.tick(NONE, r::nextDouble);
                puffs.removeIf(p -> p.age >= p.life);
            }
            // what vanilla draws (it drops a pixel under a tenth opaque), bottom to top
            List<SmokePuff> seen = new ArrayList<>(puffs.stream().filter(p -> p.alpha() >= 0.1).toList());
            seen.sort((a, b) -> Double.compare(a.y, b.y));
            assertTrue(seen.get(0).y < 7, "the column starts at the lava: " + seen.get(0).y);
            assertTrue(seen.get(seen.size() - 1).y > 0.75 * rise, "and stands " + seen.get(seen.size() - 1).y + " of " + rise);
            for (int i = 1; i < seen.size(); i++) {
                SmokePuff a = seen.get(i - 1), b = seen.get(i);
                double gap = b.y - a.y - a.size() - b.size();
                assertTrue(gap < 1, "a gap of " + gap + " blocks in the column at " + a.y);
            }
        }
    }

    @Test
    void theLavaLightsItFromBelow() {
        assertEquals(15, SmokePuff.lightFromBelow(-3));
        assertEquals(15, SmokePuff.lightFromBelow(0));
        assertEquals(9, SmokePuff.lightFromBelow(100));
        assertEquals(9, SmokePuff.lightFromBelow(160));
        for (int up = 1; up <= 120; up++) assertTrue(SmokePuff.lightFromBelow(up) <= SmokePuff.lightFromBelow(up - 1));
    }

    // ---------------------------------------------------------------------------------------------- where it rises

    /** A test cave: lava at and under {@code lava}, rock where {@code rock} says, air elsewhere. */
    private record Cave(int lava, SmokePuff.Rock rock) implements SmokeColumns.Blocks {
        @Override
        public boolean open(int x, int y, int z) { return y > lava && !rock.at(x, y, z); }

        @Override
        public boolean lava(int x, int y, int z) { return y <= lava && !rock.at(x, y, z); }
    }

    private record Puff(double x, double y, double z, double reach, double above) {}

    private static List<Puff> run(SmokeColumns.Blocks w, long from, long to, int px, int py, int pz, boolean galleries, int top) {
        List<Puff> out = new ArrayList<>();
        for (long t = from; t < to; t++)
            SmokeColumns.puff(w, t, px, py, pz, galleries, top, 0, (x, y, z, reach, above) -> out.add(new Puff(x, y, z, reach, above)));
        return out;
    }

    @Test
    void theColumnsFindTheLavaUnderOpenAir() {
        // a ledge 50 to 52 over the lava: the lava under it is found past the ledge
        Cave ledge = new Cave(0, (x, y, z) -> y >= 50 && y <= 52);
        assertEquals(0, SmokeColumns.lavaUnderAir(ledge, 3, 3, 60, -50));
        // lava under a rock floor is not
        Cave floor = new Cave(0, (x, y, z) -> y >= 1 && y <= 4);
        assertEquals(SmokeColumns.UNKNOWN, SmokeColumns.lavaUnderAir(floor, 3, 3, 4, -50));
        // a clear drop from an opening: only open air down to the lava
        assertEquals(0, SmokeColumns.clearDrop(new Cave(0, NONE), 3, 3, 100, -1));
        assertEquals(SmokeColumns.UNKNOWN, SmokeColumns.clearDrop(ledge, 3, 3, 100, -1));
    }

    @Test
    void theGalleriesVentsPuffOnTheirBeatsFromFixedSpots() {
        Cave sea = new Cave(0, NONE);
        int px = 7, pz = -5;
        List<Puff> puffs = run(sea, 1000, 1000 + SmokeColumns.FAR_BEAT, px, 30, pz, true, SmokeColumns.UNKNOWN);
        Map<Long, List<Puff>> byCell = new HashMap<>();
        for (Puff p : puffs) {
            int gx = Math.floorDiv((int) Math.floor(p.x), SmokeColumns.CELL), gz = Math.floorDiv((int) Math.floor(p.z), SmokeColumns.CELL);
            assertEquals(1.1, p.y, 1e-9, "starts over the lava");
            assertTrue(p.reach >= SmokePuff.REACH_MIN && p.reach <= SmokePuff.REACH_MAX, "rise " + p.reach);
            byCell.computeIfAbsent((long) gx << 32 ^ (gz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(p);
        }
        int cx = Math.floorDiv(px, SmokeColumns.CELL), cz = Math.floorDiv(pz, SmokeColumns.CELL), near = 0, vents = 0;
        for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
            int gx = cx + dx, gz = cz + dz, d2 = dx * dx + dz * dz;
            long h = SmokeColumns.hash(gx, gz);
            int beat = SmokeColumns.ventBeat(h, d2);
            List<Puff> got = byCell.getOrDefault((long) gx << 32 ^ (gz & 0xFFFFFFFFL), List.of());
            assertEquals(beat == 0 ? 0 : SmokeColumns.FAR_BEAT / beat, got.size(), "cell " + gx + "," + gz + " on beat " + beat);
            for (Puff p : got) {
                // the spot, give or take half a block of wander
                assertEquals(SmokeColumns.spotX(gx, h) + 0.5, p.x, 0.5 + 1e-9);
                assertEquals(SmokeColumns.spotZ(gz, h) + 0.5, p.z, 0.5 + 1e-9);
            }
            if (d2 <= SmokeColumns.NEAR * SmokeColumns.NEAR) {
                near++;
                if (beat > 0) vents++;
            }
        }
        assertTrue(vents > 0.3 * near && vents < 0.7 * near, vents + " vents of " + near + " near cells");
        // each puff of a vent within a ninth of the vent's own rise
        for (List<Puff> got : byCell.values()) if (got.size() == 2)
            assertTrue(Math.abs(got.get(0).reach - got.get(1).reach) <= 2 * SmokeColumns.RISE_JITTER * SmokeColumns.RISE_MAX + 1e-9);
    }

    @Test
    void theNearestPlumesComeFirst() {
        // so when the count of puffs is full, the farthest plumes go short, on every side alike
        Cave sea = new Cave(0, NONE);
        int puffs = 0;
        for (long t = 0; t < SmokeColumns.FAR_BEAT; t++) {
            int last = -1;
            for (Puff p : run(sea, t, t + 1, 0, 30, 0, true, SmokeColumns.UNKNOWN)) {
                int dx = Math.floorDiv((int) Math.floor(p.x), SmokeColumns.CELL), dz = Math.floorDiv((int) Math.floor(p.z), SmokeColumns.CELL);
                int d2 = dx * dx + dz * dz;
                assertTrue(d2 >= last, "a plume " + d2 + " cells squared out after one " + last + " out, tick " + t);
                last = d2;
                puffs++;
            }
        }
        assertTrue(puffs > 0);
        // and the rings cover every cell out to FAR once
        Set<Long> cells = new java.util.HashSet<>();
        for (int[] o : SmokeColumns.RINGS) assertTrue(cells.add((long) o[0] << 32 ^ (o[1] & 0xFFFFFFFFL)), "a cell twice");
        int all = 0;
        for (int dx = -SmokeColumns.FAR; dx <= SmokeColumns.FAR; dx++) for (int dz = -SmokeColumns.FAR; dz <= SmokeColumns.FAR; dz++)
            if (dx * dx + dz * dz <= SmokeColumns.FAR * SmokeColumns.FAR) all++;
        assertEquals(all, SmokeColumns.RINGS.length);
    }

    @Test
    void theOpeningsPlumesRiseOutThroughTheHole() {
        int top = 100;
        // the Galleries' roof from 60 to the top row, the Wasteland open over it, a hole 30 across at x, z 0 to 29
        SmokePuff.Rock roof = (x, y, z) -> y >= 60 && y <= top && !(x >= 0 && x < 30 && z >= 0 && z < 30);
        Cave cave = new Cave(0, roof);
        // from the Wasteland: only the hole's spots, each a tall plume from the lava to over the top
        List<Puff> wasteland = run(cave, 0, SmokeColumns.BEAT, 15, top + 20, 15, false, top);
        assertFalse(wasteland.isEmpty(), "the hole's spots puff");
        for (Puff p : wasteland) {
            assertTrue(p.x >= 0 && p.x < 30 && p.z >= 0 && p.z < 30, "a plume from under the roof: " + p);
            assertEquals(1.1, p.y, 1e-9);
            assertEquals(top + SmokeColumns.OUT - 1, p.reach, 1e-9);
        }
        // the Wasteland never has plumes of its own, nor any when the rows are not known
        assertTrue(run(new Cave(0, (x, y, z) -> y >= 60 && y <= top), 0, SmokeColumns.BEAT, 15, top + 20, 15, false, top).isEmpty());
        assertTrue(run(cave, 0, SmokeColumns.BEAT, 15, top + 20, 15, false, SmokeColumns.UNKNOWN).isEmpty());
        // from the Galleries: the same tall plumes through the hole, plain ones under the roof
        List<Puff> galleries = run(cave, 0, SmokeColumns.FAR_BEAT, 15, 30, 15, true, top);
        boolean tall = false, plain = false;
        for (Puff p : galleries) {
            boolean inHole = p.x >= 0 && p.x < 30 && p.z >= 0 && p.z < 30;
            if (p.reach > SmokePuff.REACH_MAX) {
                assertTrue(inHole, "a tall plume only through the hole");
                tall = true;
            } else {
                plain = true;
            }
        }
        assertTrue(tall && plain, "both kinds from the Galleries");
        // and a tall plume's puffs rise out through the hole, still to be seen there
        Random r = new Random(5);
        SmokePuff p = new SmokePuff(10.5, 1.1, 10.5, 1.0, top + SmokeColumns.OUT - 1, r::nextDouble);
        SmokePuff.Rock rock = (x, y, z) -> roof.at((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        boolean out = false;
        while (p.age < p.life) {
            p.tick(rock, r::nextDouble);
            if (p.y > top + 1 && p.alpha() >= 0.1) out = true;
        }
        assertTrue(out, "the plume's puffs come out over the top");
    }

    @Test
    void theBudgetCountsThePuffsAlive() {
        SmokeBudget.clear();
        try {
            SmokeBudget.add(30);
            SmokeBudget.add(10);
            SmokeBudget.add(20);
            assertEquals(3, SmokeBudget.alive(5));
            assertEquals(1, SmokeBudget.alive(20));
            assertEquals(0, SmokeBudget.alive(30));
            SmokeBudget.add(100);
            SmokeBudget.clear();
            assertEquals(0, SmokeBudget.alive(0));
        } finally {
            SmokeBudget.clear();
        }
    }
}
