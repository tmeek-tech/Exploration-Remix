package com.explorationremix.client.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rune sword's ready light (Taran, 2026-11-11 13:20 and 14:45): it lies on the sword's own texels, plays once per
 * recharge and is gone after; since 0.22.7 (2026-11-25) a sparkly glow that swells over the whole sword at once, with a
 * burst of star glints and a soft halo along it; and its sparkle while it has the energy to cast (15:00).
 */
class BladeLightTest {
    /** Vanilla's sword outline (every vanilla sword has it): '.' clear, anything else opaque. Row 0 is the top. */
    private static final String[] SWORD = {
            ".............+++",
            "............+##+",
            "...........+###+",
            "..........+###+.",
            ".........+###+..",
            "........+###+...",
            "..++...+###+....",
            "..+++.+###+.....",
            "...+#+###+......",
            "...+##+#+.......",
            "....++++........",
            "...++++++.......",
            "..+++.++++......",
            "++++....++......",
            "+++.............",
            "+++.............",
    };

    private static int px(String[] mask, int x, int y) { return mask[y].charAt(x) == '.' ? 0 : 0xFF000000; }

    /** A generated item's two big faces (as ItemModelGenerator makes them: the back one's texture mirrored to match). */
    private static BladeLight.Cells[] swordFaces() {
        BladeLight.Pixels p = (x, y) -> px(SWORD, x, y);
        float zf = 8.5F / 16, zb = 7.5F / 16;
        BladeLight.Cells front = BladeLight.cut(new float[]{0, 1, zf, 0, 0, zf, 1, 0, zf, 1, 1, zf}, new float[]{0, 0, 0, 16, 16, 16, 16, 0}, 16, 16, p);
        BladeLight.Cells back = BladeLight.cut(new float[]{1, 1, zb, 1, 0, zb, 0, 0, zb, 0, 1, zb}, new float[]{16, 0, 16, 16, 0, 16, 0, 0}, 16, 16, p);
        return new BladeLight.Cells[]{front, back};
    }

    /** The cells' middles, moved so the hand is at the origin. */
    private static float[] middles(BladeLight.Cells[] faces, float hx, float hy, float hz) {
        int n = 0;
        for (BladeLight.Cells f : faces) n += f.count;
        float[] mid = new float[n * 3];
        int k = 0;
        for (BladeLight.Cells f : faces) {
            for (int i = 0; i < f.count; i++, k++) {
                mid[k * 3] = f.middles[i * 3] - hx;
                mid[k * 3 + 1] = f.middles[i * 3 + 1] - hy;
                mid[k * 3 + 2] = f.middles[i * 3 + 2] - hz;
            }
        }
        return mid;
    }

    /** The texel (x, y) of the sword texture a front-face cell lies on. */
    private static int[] texel(BladeLight.Cells front, int i) {
        return new int[]{(int) Math.floor(front.middles[i * 3] * 16), (int) Math.floor((1 - front.middles[i * 3 + 1]) * 16)};
    }

    @Test
    void theLightLiesOnTheOpaqueTexelsExactly() {
        int opaque = 0;
        for (String row : SWORD) for (char c : row.toCharArray()) if (c != '.') opaque++;
        BladeLight.Cells[] faces = swordFaces();
        assertEquals(opaque, faces[0].count, "one cell for each opaque texel on the front");
        assertEquals(opaque, faces[1].count, "and on the back");
        for (int i = 0; i < faces[0].count; i++) {
            int[] t = texel(faces[0], i);
            assertTrue(SWORD[t[1]].charAt(t[0]) != '.', "a cell on a clear texel at " + t[0] + "," + t[1]);
            assertEquals(BladeLight.texel(t[0], t[1]), faces[0].texels[i], "the cell knows its texel");
            // its corners on the texel's own edges
            for (int q = 0; q < 4; q++) {
                float x = faces[0].corners[i * 12 + q * 3] * 16, y = faces[0].corners[i * 12 + q * 3 + 1] * 16;
                assertEquals(Math.round(x), x, 1.0E-4F, "corner on a texel line");
                assertEquals(Math.round(y), y, 1.0E-4F, "corner on a texel line");
            }
        }
    }

    @Test
    void aFaceWithItsTextureShrunkOrTurnedStillCutsOnTexels() {
        String[] mask = {"#.", "##"};
        BladeLight.Pixels p = (x, y) -> px(mask, x, y);
        // the game shrinks a face's texture a hair towards its middle
        float e = 0.004F;
        BladeLight.Cells shrunk = BladeLight.cut(new float[]{0, 1, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0}, new float[]{e, e, e, 2 - e, 2 - e, 2 - e, 2 - e, e}, 2, 2, p);
        assertEquals(3, shrunk.count);
        // turned a quarter: the clear texel (1, 0) still finds its place, at the face's bottom right
        BladeLight.Cells turned = BladeLight.cut(new float[]{0, 1, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0}, new float[]{0, 2, 2, 2, 2, 0, 0, 0}, 2, 2, p);
        assertEquals(3, turned.count);
        for (int i = 0; i < turned.count; i++) {
            boolean bottomRight = turned.middles[i * 3] > 0.5F && turned.middles[i * 3 + 1] < 0.5F;
            assertFalse(bottomRight, "the clear texel's cell is left out");
        }
        // a side face of a generated item: a one-texel strip across the item's thickness
        BladeLight.Cells strip = BladeLight.cut(new float[]{0.25F, 0.5F, 7.5F / 16, 0.75F, 0.5F, 7.5F / 16, 0.75F, 0.5F, 8.5F / 16, 0.25F, 0.5F, 8.5F / 16},
                new float[]{4, 8, 12, 8, 12, 9, 4, 9}, 16, 16, (x, y) -> 0xFF000000);
        assertEquals(8, strip.count, "one cell a texel along the edge");
    }

    @Test
    void aVanillaSwordLightsWhollyFromThePommelToTheTip() {
        BladeLight.Cells[] faces = swordFaces();
        // held by the grip, a little above the pommel
        float[] mid = middles(faces, 2.5F / 16, 1 - 13.5F / 16, 0.5F);
        int n = faces[0].count + faces[1].count;
        BladeLight.Run run = BladeLight.run(mid, n, 1.0F / 16);
        assertTrue(run.texels() > 18 && run.texels() < 24, "the whole sword's length, corner to corner: " + run.texels());
        for (int i = 0; i < n; i++) assertTrue(run.along()[i] >= 0, "every texel is lit, grip and guard too");
        float pommel = -1, grip = -1, guard = -1, low = -1, high = -1, tip = -1;
        for (int i = 0; i < faces[0].count; i++) {
            int[] t = texel(faces[0], i);
            float a = run.along()[i];
            if (t[0] == 0 && t[1] == 15) pommel = a;
            if (t[0] == 3 && t[1] == 12) grip = a;
            if (t[0] == 5 && t[1] == 10) guard = a;
            if (t[0] == 8 && t[1] == 7) low = a;
            if (t[0] == 12 && t[1] == 3) high = a;
            if (t[0] == 15 && t[1] == 0) tip = a;
        }
        assertEquals(0.0F, pommel, 0.05F, "the pommel is the light's start");
        assertEquals(1.0F, tip, 0.05F, "the tip its far end");
        assertTrue(pommel < grip && grip < guard && guard < low && low < high && high < tip,
                "the light climbs pommel, grip, guard, blade, tip: " + pommel + " " + grip + " " + guard + " " + low + " " + high + " " + tip);
        // the back face lights the same texels the same way
        for (int i = 0; i < faces[1].count; i++) {
            float bx = faces[1].middles[i * 3], by = faces[1].middles[i * 3 + 1];
            for (int j = 0; j < faces[0].count; j++) {
                if (Math.abs(faces[0].middles[j * 3] - bx) < 1.0E-4F && Math.abs(faces[0].middles[j * 3 + 1] - by) < 1.0E-4F) {
                    assertEquals(run.along()[j], run.along()[faces[0].count + i], 0.02F, "front and back alike");
                }
            }
        }
    }

    @Test
    void aTubeLightsEndToEndFromTheHand() {
        // Create's cardboard sword: a square tube 4 pixels across and 32 long, held 3 pixels from its bottom end
        BladeLight.Pixels solid = (x, y) -> 0xFF000000;
        float lo = 6 / 16F, hi = 10 / 16F, bottom = -8 / 16F, top = 24 / 16F;
        float[] st = {0, 0, 0, 32, 4, 32, 4, 0};
        BladeLight.Cells[] faces = {
                BladeLight.cut(new float[]{lo, top, lo, lo, bottom, lo, lo, bottom, hi, lo, top, hi}, st, 4, 32, solid),
                BladeLight.cut(new float[]{hi, top, hi, hi, bottom, hi, hi, bottom, lo, hi, top, lo}, st, 4, 32, solid),
                BladeLight.cut(new float[]{hi, top, lo, hi, bottom, lo, lo, bottom, lo, lo, top, lo}, st, 4, 32, solid),
                BladeLight.cut(new float[]{lo, top, hi, lo, bottom, hi, hi, bottom, hi, hi, top, hi}, st, 4, 32, solid),
        };
        float[] mid = middles(faces, 0.5F, -5 / 16F, 0.5F);
        int n = 4 * 4 * 32;
        BladeLight.Run run = BladeLight.run(mid, n, 1.0F / 16);
        int k = 0;
        for (BladeLight.Cells f : faces) {
            for (int i = 0; i < f.count; i++, k++) {
                float y = f.middles[i * 3 + 1], a = run.along()[k];
                assertEquals((y - (bottom + 0.5F / 16)) / (top - bottom - 1.0F / 16), a, 1.0E-3F, "lit in step from the bottom end up");
            }
        }
        assertEquals(31.0F, run.texels(), 0.01F);
    }

    @Test
    void theWholeSwordSwellsAtOnceThenFades() {
        float len = 20.0F;
        float[] white = new float[1];
        for (float x = 0; x <= len; x += 0.5F) {
            assertEquals(0.0F, BladeLight.light(x, len, -0.01F, white), "nothing before it starts");
            assertEquals(0.0F, BladeLight.light(x, len, BladeLight.END, white), "nothing once it is over");
            assertTrue(BladeLight.light(x, len, BladeLight.END - 0.05F, white) < 0.01F, "it fades out, not off");
        }
        // every spot comes to its height within a quarter of a second, the tip barely after the hand
        float first = -1, last = -1;
        for (float x = 0; x <= len; x += 1.0F) {
            float best = 0, at = 0;
            for (float t = 0; t < BladeLight.END; t += 0.05F) {
                float l = BladeLight.light(x, len, t, white);
                assertTrue(l >= 0.0F && l <= 1.0F);
                if (l > best + 1.0E-6F) {
                    best = l;
                    at = t;
                }
            }
            assertTrue(best > 0.99F, "the whole sword lit full: " + best + " at " + x);
            if (first < 0) first = at;
            assertTrue(at >= last, "a hint of flow from the hand to the tip: " + at + " at " + x + " after " + last);
            last = at;
        }
        assertTrue(last < 5.0F, "the whole sword at its height within a quarter of a second: " + last);
        assertTrue(last - first <= BladeLight.WASH + 0.06F, "all at once, near enough: " + (last - first));
        // whitest at its height, then a soft fade that lingers
        BladeLight.light(len / 2, len, BladeLight.SWELL_UP + 1.0F, white);
        assertTrue(white[0] > 0.85F, "near white at its height: " + white[0]);
        float half = BladeLight.light(len / 2, len, 10.0F, white);
        assertTrue(half > 0.2F && half < 0.6F, "fading by half a second: " + half);
        float late = BladeLight.light(len / 2, len, 17.0F, white);
        assertTrue(late > 0.02F && late < half, "lingering, and fading: " + late);
        assertEquals(0.0F, BladeLight.light(-1.0F, len, 5.0F, white), "nothing below the start");
    }

    @Test
    void aBurstOfStarGlintsSpreadsOverTheSwordAsItSwells() {
        int d = 84;
        for (int seed = 0; seed < 50; seed++) {
            BladeLight.Glint[] g = BladeLight.burst(d, seed);
            assertEquals(BladeLight.BURST, g.length);
            for (int i = 0; i < g.length; i++) {
                assertTrue(g[i].texel() >= i * d / g.length && g[i].texel() < (i + 1) * d / g.length,
                        "one in each share of the texels, so they spread over the sword: " + g[i]);
                assertTrue(g[i].at() >= BladeLight.BURST_FROM && g[i].at() + BladeLight.GLINT <= BladeLight.END, "each over within the light");
                assertTrue(g[i].size() > 0.5F * BladeLight.GLINT_SIZE && g[i].size() < 1.5F * BladeLight.GLINT_SIZE);
                assertTrue(Math.abs(g[i].tilt()) <= Math.PI / 8 + 1.0E-6, "near upright: " + g[i].tilt());
            }
        }
        BladeLight.Glint[] a = BladeLight.burst(d, 1), b = BladeLight.burst(d, 2);
        int moved = 0;
        for (int i = 0; i < a.length; i++) if (a[i].texel() != b[i].texel()) moved++;
        assertTrue(moved >= a.length / 2, "another play's glints fall elsewhere: " + moved);
        assertEquals(3, BladeLight.burst(3, 7).length, "a tiny sword: as many as it has texels");
        assertEquals(0, BladeLight.burst(0, 7).length);
    }

    @Test
    void aStarGlintFlashesSharplyAndFades() {
        assertEquals(0.0F, BladeLight.glint(-0.01F));
        assertEquals(0.0F, BladeLight.glint(BladeLight.GLINT));
        float peak = 0, at = 0;
        for (float t = 0; t < BladeLight.GLINT; t += 0.05F) {
            float g = BladeLight.glint(t);
            if (g > peak) {
                peak = g;
                at = t;
            }
        }
        assertTrue(peak > 0.99F);
        assertTrue(at < BladeLight.GLINT * 0.25F, "a sharp flash: " + at);
        assertTrue(BladeLight.glint(BladeLight.GLINT - 0.05F) < 0.01F, "fading out, not off");
        // a resting twinkle shows a star only when it is bright, coming in from nothing (so it never pops)
        assertEquals(0.0F, BladeLight.twinkleStar(0.2F));
        assertEquals(0.0F, BladeLight.twinkleStar(BladeLight.STAR_FROM));
        assertEquals(1.0F, BladeLight.twinkleStar(1.0F), 1.0E-6F);
        float some = BladeLight.twinkleStar(0.7F);
        assertTrue(some > 0.0F && some < 1.0F);
        assertEquals(BladeLight.twinkleTilt(5, 3), BladeLight.twinkleTilt(5, 3), "a texel's star turned the same each time");
    }

    @Test
    void theHaloFollowsTheSwordFromTheHandToTheTip() {
        BladeLight.Cells[] faces = swordFaces();
        float[] mid = middles(faces, 2.5F / 16, 1 - 13.5F / 16, 0.5F);
        int n = faces[0].count + faces[1].count;
        BladeLight.Run run = BladeLight.run(mid, n, 1.0F / 16);
        float[] glows = BladeLight.haloGlows(run.along(), mid, n, BladeLight.HALO_GLOWS);
        float lastAlong = -1;
        for (int g = 0; g < BladeLight.HALO_GLOWS; g++) {
            float along = glows[g * 4 + 3];
            assertTrue(along >= 0, "a glow in every stretch of a vanilla sword");
            assertTrue(along > lastAlong, "in order from the hand to the tip");
            lastAlong = along;
            float best = Float.MAX_VALUE;
            for (int i = 0; i < n; i++) best = Math.min(best, distance(glows, g * 4, mid, i * 3));
            assertTrue(best * 16 < 1.5F, "on the sword: " + best * 16 + " texels from it");
            if (g > 0) {
                float gap = distance(glows, g * 4, glows, (g - 1) * 4) * 16;
                assertTrue(gap < BladeLight.HALO_RADIUS * 1.2F, "each glow overlaps the next, so the halo reads as one: " + gap);
            }
        }
        float[] two = BladeLight.haloGlows(new float[]{0.0F, 1.0F}, new float[]{0, 0, 0, 1, 1, 1}, 2, 4);
        assertEquals(-1.0F, two[4 + 3], "a stretch with nothing in it has no glow");
        assertEquals(1.0F, two[3 * 4 + 3]);
        assertEquals(1.0F, two[3 * 4], 1.0E-6F);
    }

    private static float distance(float[] a, int i, float[] b, int j) {
        float dx = a[i] - b[j], dy = a[i + 1] - b[j + 1], dz = a[i + 2] - b[j + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    void itPlaysOncePerRecharge() {
        BladeLight.Trigger t = new BladeLight.Trigger();
        Object sword = "sword";
        int now = 0;
        assertFalse(t.update(sword, 1.0F, now++), "first seen already full: nothing");
        for (int i = 0; i < 40; i++) assertFalse(t.update(sword, 1.0F, now++), "staying full: nothing");
        assertFalse(t.update(sword, 0.0F, now++), "spent");
        assertFalse(t.update(sword, RuneGlow.WAITING, now++), "enough energy, but waiting out a delay");
        assertTrue(t.update(sword, 1.0F, now), "ready: it plays");
        assertEquals(0.0F, t.age(now));
        now++;
        for (int i = 0; i < 60; i++) assertFalse(t.update(sword, 1.0F, now++), "once only");
        // a cast that leaves enough for the next one: never short, so no recharge to show
        assertFalse(t.update(sword, RuneGlow.WAITING, now++));
        for (int i = 0; i < 10; i++) assertFalse(t.update(sword, 1.0F, now++));
        // short, then full again: it plays again
        assertFalse(t.update(sword, 0.0F, now++));
        assertTrue(t.update(sword, 1.0F, now++), "a second recharge plays again");
    }

    @Test
    void takingAFullSwordInHandDoesNotPlay() {
        BladeLight.Trigger t = new BladeLight.Trigger();
        int now = 0;
        t.update("a", 0.0F, now++);
        assertTrue(t.update("a", 1.0F, now++));
        // straight to another, already full sword: the first's light stops and the new one stays dark
        assertFalse(t.update("b", 1.0F, now));
        assertTrue(t.age(now) > BladeLight.END, "the light stops with the switch");
        now++;
        for (int i = 0; i < 30; i++) assertFalse(t.update("b", 1.0F, now++));
        // and an empty one taken in hand plays when it fills
        assertFalse(t.update("c", 0.0F, now++));
        assertTrue(t.update("c", 1.0F, now++));
    }

    @Test
    void aChargedSwordSparklesHereAndThereOnABeat() {
        for (int texels : new int[]{40, 84, 200}) {
            // each texel shown twice, as a generated item's front and back cells show it; none beside another
            long[] keys = new long[texels * 2];
            for (int i = 0; i < texels; i++) {
                keys[i] = BladeLight.key(7, BladeLight.texel(2 * (i % 16), 2 * (i / 16)));
                keys[texels + i] = keys[i];
            }
            float[] out = new float[keys.length], white = new float[keys.length];
            boolean[] was = new boolean[texels];
            float[] since = new float[texels];
            int starts = 0, litAtOnce = 0, long_ = 0;
            float seconds = 300.0F;
            for (float t = 1000; t < 1000 + seconds * 20; t += 0.25F) {
                BladeLight.sparkle(keys, keys.length, t, 99, out, white);
                float brightest = 0;
                for (int i = 0; i < texels; i++) {
                    assertEquals(out[i], out[texels + i], "a texel's front and back sparkle together");
                    assertTrue(out[i] >= 0.0F && out[i] <= 1.0F && white[i] >= 0.0F && white[i] <= 1.0F);
                    brightest = Math.max(brightest, out[i]);
                    boolean on = out[i] > 0;
                    if (on && !was[i]) {
                        starts++;
                        since[i] = t;
                    }
                    if (on) litAtOnce++;
                    // a quick flash (longer only where the same texel happens to be picked again as it fades)
                    if (!on && was[i] && t - since[i] > BladeLight.TWINKLE + 0.25F) long_++;
                    was[i] = on;
                }
                assertTrue(brightest > 0.1F, "never dark for a moment: " + brightest + " at " + t);
            }
            assertTrue(long_ < starts * 0.1F, "twinkles are quick flashes: " + long_ + " of " + starts + " ran long");
            float perSecond = starts / seconds;
            assertEquals(BladeLight.TWINKLES, perSecond, BladeLight.TWINKLES * 0.15F, texels + " texels: " + perSecond + " twinkles a second");
            float atOnce = litAtOnce / (seconds * 20 * 4);
            assertTrue(atOnce > 1.5F && atOnce < 4.5F, "a few at a time, not a blaze: " + atOnce);
        }
        // another sword (another seed) twinkles out of step
        long[] keys = new long[40];
        for (int i = 0; i < 40; i++) keys[i] = BladeLight.key(7, BladeLight.texel(2 * (i % 16), 2 * (i / 16)));
        float[] a = new float[40], b = new float[40], w = new float[40];
        int same = 0, samples = 0;
        for (float t = 0; t < 2000; t += 1.0F) {
            BladeLight.sparkle(keys, 40, t, 1, a, w);
            BladeLight.sparkle(keys, 40, t, 2, b, w);
            for (int i = 0; i < 40; i++) {
                if (a[i] > 0 || b[i] > 0) {
                    samples++;
                    if (a[i] > 0 && b[i] > 0) same++;
                }
            }
        }
        assertTrue(same < samples / 5, "two swords' twinkles mostly fall apart: " + same + " of " + samples);
    }

    @Test
    void aTwinkleLightsOnlyTheSwordsOwnTexelsBesideIt() {
        // (3, 3) with one neighbour in the sword, (4, 3); (9, 9) alone
        long a = BladeLight.key(1, BladeLight.texel(3, 3)), b = BladeLight.key(1, BladeLight.texel(4, 3)), lone = BladeLight.key(1, BladeLight.texel(9, 9));
        long[] keys = {a, b, lone};
        // the texels in order: a, b, lone (the same texture; a before b on the same row; lone on a later row)
        float[] out = new float[3], white = new float[3];
        boolean sawArm = false;
        for (float t = 0; t < 20 * 300; t += 0.5F) {
            BladeLight.sparkle(keys, 3, t, 5, out, white);
            float[] own = BladeLight.twinkles(3, t, 5);
            assertEquals(own[2], out[2], 1.0E-6F, "nothing round a lone texel lights it: what is beside it is clear");
            assertEquals(Math.max(own[1], BladeLight.ARM * own[0] * own[0]), out[1], 1.0E-6F, "lit a little by its neighbour's twinkle, near its peak");
            if (own[0] > 0.5F && own[1] == 0) {
                sawArm = true;
                assertTrue(white[1] < white[0], "the flash's middle burns whitest");
            }
        }
        assertTrue(sawArm);
    }
}
