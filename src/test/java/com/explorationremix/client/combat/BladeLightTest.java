package com.explorationremix.client.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rune sword's ready light (Taran, 2026-11-11 13:20): it lies on the sword's own texels and nowhere else, runs from
 * just past the guard (or the hand, with no guard) to the tip, plays once per recharge and is gone after.
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
    void aVanillaSwordLightsFromJustPastTheGuardToTheTip() {
        BladeLight.Cells[] faces = swordFaces();
        // held by the grip, a little above the pommel
        float[] mid = middles(faces, 2.5F / 16, 1 - 13.5F / 16, 0.5F);
        int n = faces[0].count + faces[1].count;
        BladeLight.Run run = BladeLight.run(mid, n, 1.0F / 16);
        assertTrue(run.texels() > 10 && run.texels() < 15, "the blade's length: " + run.texels());
        float tip = -1, handle = 0, guardEnd = 0;
        float lowBlade = -1, highBlade = -1;
        for (int i = 0; i < faces[0].count; i++) {
            int[] t = texel(faces[0], i);
            float a = run.along()[i];
            if (t[0] == 15 && t[1] == 0) tip = a;
            if (t[0] <= 3 && t[1] >= 13) handle = Math.max(handle, a + 1);
            if ((t[0] == 2 && t[1] == 6) || (t[0] == 9 && t[1] == 13)) guardEnd = Math.max(guardEnd, a + 1);
            if (t[0] == 8 && t[1] == 7) lowBlade = a;
            if (t[0] == 12 && t[1] == 3) highBlade = a;
        }
        assertEquals(1.0F, tip, 0.08F, "the tip is the light's far end");
        assertEquals(0.0F, handle, "the grip and pommel stay dark");
        assertEquals(0.0F, guardEnd, "the guard's arms stay dark");
        assertTrue(lowBlade >= 0 && lowBlade < 0.3F, "the blade's foot is lit early: " + lowBlade);
        assertTrue(highBlade > lowBlade + 0.25F, "and the light climbs to the tip: " + lowBlade + " then " + highBlade);
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
    void aSwordWithNoGuardLightsFromTheHand() {
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
        float handY = -5 / 16F;
        float[] mid = middles(faces, 0.5F, handY, 0.5F);
        int n = 4 * 4 * 32;
        BladeLight.Run run = BladeLight.run(mid, n, 1.0F / 16);
        int k = 0;
        for (BladeLight.Cells f : faces) {
            for (int i = 0; i < f.count; i++, k++) {
                float y = f.middles[i * 3 + 1];
                if (y < handY - 1.0F / 16) assertEquals(-1.0F, run.along()[k], "below the hand: dark");
                if (y > handY + 1.0F / 16) assertTrue(run.along()[k] > 0, "above the hand: lit");
                if (y > top - 1.0F / 16) assertEquals(1.0F, run.along()[k], 0.05F, "the top end is the tip");
            }
        }
        assertEquals(28.5F, run.texels(), 1.0F);
    }

    @Test
    void theLightRunsUpTheBladeOnceAndIsGone() {
        float len = 12.0F;
        float[] white = new float[1];
        for (float x = 0; x <= len; x += 0.5F) {
            assertEquals(0.0F, BladeLight.light(x, len, -0.01F, white), "nothing before it starts");
            assertEquals(0.0F, BladeLight.light(x, len, BladeLight.END, white), "nothing once it is over");
            assertTrue(BladeLight.light(x, len, BladeLight.END - 0.05F, white) < 0.01F, "it fades out, not off");
        }
        // each spot's brightest moment comes later the higher it is
        float last = -1;
        for (float x = 0; x <= len - 3; x += 1.0F) {
            float best = 0, at = 0;
            for (float t = 0; t < BladeLight.END; t += 0.05F) {
                float l = BladeLight.light(x, len, t, white);
                assertTrue(l <= 1.0F && l >= 0.0F);
                if (l > best) {
                    best = l;
                    at = t;
                }
            }
            assertTrue(best > 0.85F, "the band lights every spot it passes: " + best + " at " + x);
            assertTrue(at > last, "the band climbs: " + at + " at " + x + " after " + last);
            last = at;
        }
        assertTrue(last < 9.0F, "it reaches the top within half a second: " + last);
        // the tip flares as the band gets there, and dies down after
        float flare = BladeLight.tipAt(len);
        assertTrue(BladeLight.light(len - 0.5F, len, flare, white) > 0.9F, "the tip flares");
        assertTrue(white[0] > 0.9F, "burning white");
        assertTrue(BladeLight.light(len - 0.5F, len, flare + 8, white) < 0.4F, "then dies down");
        // the afterglow, faint and fading
        float glow = BladeLight.light(2.0F, len, 14.0F, white);
        assertTrue(glow > 0.1F && glow < 0.35F, "a faint afterglow: " + glow);
        assertTrue(BladeLight.light(2.0F, len, 22.0F, white) < glow, "fading");
        assertEquals(0.0F, BladeLight.light(-1.0F, len, 5.0F, white), "nothing below the start");
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
}
